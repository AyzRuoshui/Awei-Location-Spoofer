package com.awei.remotecam

import com.awei.common.Proto
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 服务器中转链路（远程 App → 中转服务器，跨网络远程调用）。
 *
 * 长连接注册拿到"远程 ID"（服务器按 devid 保持 ID 稳定），屏幕上显示给主端填；
 * 服务器通知 incoming 后本连接转入透传——后续字节就是主端的双机协议帧
 * （hello→cam_request→...），复用 CamServerService.ClientConn 的完整授权/会话逻辑；
 * 会话结束发 detach 回到可拨入状态。断线自动重连（5s 起步 60s 封顶，成功复位）。
 *
 * 服务器侧约定（见 common/Proto 注释与 AGENTS.md）：本质是"按 ID 路由的 TCP 管道"。
 * 注意：Socket 必须每轮新建——java.net.Socket 一旦 close 后不可再 connect，
 * 复用对象会让重连循环从第二轮起永久失败。
 */
class RelayLink(private val server: String, private val devid: String) {

    companion object {
        /** 服务器分配的远程 ID（未连接为 null），主界面显示 */
        @Volatile
        var currentId: String? = null
            private set

        /** 链路状态文案（UI 轮询显示） */
        @Volatile
        var statusLine: String = "未连接"
            private set
    }

    /** 当前轮次的连接；closeSocket 关的是它（会话层 ClientConn 回调用） */
    @Volatile
    private var sock: Socket? = null

    @Volatile
    private var stopped = false

    /** 本链路是否仍是服务当前使用的链路（被 ACTION_RELAY 换代后旧线程不得再碰全局状态） */
    private fun isCurrent(): Boolean = CamServerService.instance?.isCurrentLink(this) == true

    fun stop() {
        stopped = true
        closeSocket()
    }

    /** 供会话层（ClientConn close 回调）断开底层连接 */
    fun closeSocket() {
        try {
            sock?.close()
        } catch (t: Throwable) {
        }
    }

    fun loop() {
        var backoff = 5_000L
        while (!stopped) {
            val parsed = Proto.parseAddr(server)
            if (parsed == null) {
                statusLine = "中转服务器地址无效"
                sleep(10_000)
                continue
            }
            val (host, port) = parsed
            val sock = Socket()
            this.sock = sock
            try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 8_000)
                sock.soTimeout = 45_000 // 服务器 30s 心跳，45s 无消息判死
                val dis = DataInputStream(sock.getInputStream())
                val dos = DataOutputStream(sock.getOutputStream())

                Proto.writeJson(dos, Proto.reg(devid, "1"))
                var registered = false
                while (!registered) {
                    val j = nextJson(dis) ?: throw IOException("reg 响应中断")
                    when (j.optString("t")) {
                        "reg_ok" -> {
                            currentId = j.optString("id")
                            registered = true
                        }
                        "reg_fail" -> {
                            statusLine = "注册被拒: ${j.optString("reason")}"
                            CamServerService.logLine("中转注册被拒: ${j.optString("reason")}")
                            sleep(30_000)
                            break
                        }
                        "ping" -> Proto.writeJson(dos, Proto.pong())
                    }
                }
                if (!registered) continue

                statusLine = "已连接，等待本机 App 拨入"
                CamServerService.logLine("中转链路就绪 ID=${currentId ?: "-"} ($host:$port)")
                backoff = 5_000L

                while (!stopped) {
                    val frame = try {
                        Proto.read(dis)
                    } catch (e: SocketTimeoutException) {
                        throw IOException("服务器心跳超时")
                    } ?: break
                    if (frame.first != Proto.TYPE_JSON) continue
                    when (Proto.parseJson(frame.second)?.optString("t") ?: continue) {
                        "ping" -> Proto.writeJson(dos, Proto.pong())
                        "incoming" -> {
                            // 转入透传：本线程阻塞跑完整会话（授权弹窗/编码/扇出全复用）
                            val app = Proto.parseJson(frame.second)?.optString("app") ?: "unknown"
                            CamServerService.logLine("中转拨入: $app")
                            statusLine = "会话进行中（$app）"
                            // 会话必须绑定"收到 incoming 的这条链路"——若按服务当前链路
                            // 解析，换代竞态下旧会话收尾会错杀新链路的 socket
                            val conn = CamServerService.instance?.newRelaySession(this, dis, dos, "中转:$app")
                            if (conn != null) {
                                conn.run()
                                // 会话结束：释放隧道，服务器回 reg_ok 确认回到可拨入
                                try {
                                    Proto.writeJson(dos, Proto.detach())
                                } catch (t: Throwable) {
                                    throw IOException("会话结束上传送失败") // 连接已死 → 外层重连
                                }
                                statusLine = "已连接，等待本机 App 拨入"
                            } else {
                                throw IOException("服务已停止")
                            }
                        }
                        "reg_ok" -> statusLine = "已连接，等待本机 App 拨入"
                    }
                }
            } catch (t: Throwable) {
                if (!stopped) CamServerService.logLine("中转链路断开: ${t.javaClass.simpleName}")
            } finally {
                // 换代后的旧线程不得覆写新链路已写入的状态（开关切换/改地址时出现）
                if (isCurrent()) {
                    if (stopped) statusLine = "中转已关闭"
                    else statusLine = "连接断开，重连中"
                    currentId = null
                }
                closeSocket()
            }
            if (stopped) break
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** 读下一帧 JSON（非 JSON 帧跳过）；EOF 返回 null，超时/异常向上抛 */
    private fun nextJson(dis: DataInputStream): JSONObject? {
        while (true) {
            val f = Proto.read(dis) ?: return null
            if (f.first == Proto.TYPE_JSON) return Proto.parseJson(f.second)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }
}

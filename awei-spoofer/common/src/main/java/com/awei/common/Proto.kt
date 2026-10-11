package com.awei.common

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * 双机远程摄像头通信协议（主 App Hook 进程 ↔ 远程摄像头 App）。
 *
 * 传输：裸 TCP（LAN 直连），帧格式 [len:4 大端][type:1][payload(len-1)]。
 * - type 0x01 JSON 控制消息（UTF-8）
 * - type 0x02 H.264 access unit（Annex-B）
 * - type 0x03 编解码配置（SPS/PPS，Annex-B）
 *
 * 控制消息（JSONObject，字段 t 为类型）：
 * C→S hello{token,device,ver}      → S→C hello_ok{name} / auth_fail
 * C→S cam_request{rid,app}         → S→C cam_grant{rid} / cam_deny{rid,reason}
 * C→S cam_release{rid} / keyframe
 * S→C stream_stop{reason}（预留：当前远程端以断开 TCP 表达停止）
 * C→S ping（客户端侧仅在网络源/握手等待期主动发；稳态收流期由 S 每 15s 主动 ping
 *   保活——稳态收流时主端只收不发，无上行会撞 S 的读超时被掐断）
 * 双向 pong 应答
 *
 * 中转信令（远程 App ↔ 中转服务器、主端 ↔ 中转服务器；服务器按 ID 路由的透明管道）：
 * 远程端→服务器 reg{devid,ver}          → 服务器→远程端 reg_ok{id}（devid 相同则 ID 稳定）/ reg_fail{reason}
 * 主端→服务器   dial{id,device}         → 服务器→主端   dial_ok{name} / dial_fail{reason:offline|busy|badid}
 * 服务器→远程端 incoming{app}（此后该连接转入透传，跑上面的双机协议，服务器只转发字节）
 * 远程端→服务器 detach{}（会话结束释放，服务器回 reg_ok 确认回到可拨入状态）
 * 双向 ping / pong（链路心跳，30s 一次）
 */
object Proto {

    const val TYPE_JSON: Int = 0x01
    const val TYPE_H264: Int = 0x02
    const val TYPE_CSD: Int = 0x03

    const val PORT_DEFAULT = 17521
    const val PROTO_VER = 1
    private const val MAX_FRAME = 8 * 1024 * 1024

    /** 写一帧（调用方保证串行/加锁） */
    @Throws(IOException::class)
    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeInt(1 + payload.size)
        out.writeByte(type)
        out.write(payload)
        out.flush()
    }

    @Throws(IOException::class)
    fun writeJson(out: DataOutputStream, json: JSONObject) {
        write(out, TYPE_JSON, json.toString().toByteArray(Charsets.UTF_8))
    }

    /** 阻塞读一帧；流结束返回 null */
    @Throws(IOException::class)
    fun read(dis: DataInputStream): Pair<Int, ByteArray>? {
        val len = try {
            dis.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len < 1 || len > MAX_FRAME) throw IOException("bad frame len $len")
        val type = dis.readByte().toInt() and 0xFF
        val payload = ByteArray(len - 1)
        dis.readFully(payload)
        return Pair(type, payload)
    }

    fun parseJson(payload: ByteArray): JSONObject? = try {
        JSONObject(String(payload, Charsets.UTF_8))
    } catch (t: Throwable) {
        null
    }

    // ---- 消息构造 ----

    fun hello(token: String, device: String) = JSONObject()
        .put("t", "hello")
        .put("token", token)
        .put("device", device.take(64))
        .put("ver", PROTO_VER)

    fun helloOk(name: String) = JSONObject().put("t", "hello_ok").put("name", name.take(64))

    fun camRequest(rid: Int, app: String) = JSONObject()
        .put("t", "cam_request")
        .put("rid", rid)
        .put("app", app.take(100))

    fun camGrant(rid: Int, w: Int = 0, h: Int = 0) = JSONObject()
        .put("t", "cam_grant").put("rid", rid).put("w", w).put("h", h)

    fun camDeny(rid: Int, reason: String) = JSONObject()
        .put("t", "cam_deny")
        .put("rid", rid)
        .put("reason", reason)

    fun camRelease(rid: Int) = JSONObject().put("t", "cam_release").put("rid", rid)

    fun streamStop(reason: String) = JSONObject().put("t", "stream_stop").put("reason", reason)

    fun keyframe() = JSONObject().put("t", "keyframe")

    fun ping() = JSONObject().put("t", "ping")

    fun pong() = JSONObject().put("t", "pong")

    // ---- 中转信令（客户端侧构造；服务器侧应答见类注释）----

    /** 默认中转服务器地址。开源版本默认为空，需在 App 设置或远程摄像头中配置自建中转服务器（IP:端口）。 */
    const val RELAY_DEFAULT = ""

    /** 远程端注册：devid 是持久设备标识，服务器据此保持远程 ID 稳定 */
    fun reg(devid: String, ver: String) = JSONObject()
        .put("t", "reg")
        .put("devid", devid.take(64))
        .put("ver", ver.take(16))

    /** 主端按远程 ID 拨入 */
    fun dial(id: String, device: String) = JSONObject()
        .put("t", "dial")
        .put("id", id.take(16))
        .put("device", device.take(64))

    /** 远程端会话结束后释放隧道，回到可拨入状态 */
    fun detach() = JSONObject().put("t", "detach")

    /** "1.2.3.4" + 17521 → 17521；"1.2.3.4:8888" → 8888；"[::1]:17520" → 17520（IPv6 括号形式） */
    fun parseAddr(addr: String): Pair<String, Int>? {
        val s = addr.trim()
        if (s.isEmpty()) return null
        // IPv6 字面量含多个冒号：按 [host]:port 解析（无端口 → 默认端口）
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close > 0) {
                val host = s.substring(1, close)
                val rest = s.substring(close + 1)
                if (rest.isEmpty()) return Pair(host, PORT_DEFAULT)
                if (rest.startsWith(":")) {
                    val port = rest.substring(1).toIntOrNull() ?: return null
                    if (port in 1..65535) return Pair(host, port)
                }
            }
            return null
        }
        val idx = s.lastIndexOf(':')
        return if (idx > 0) {
            val port = s.substring(idx + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) null else Pair(s.substring(0, idx), port)
        } else {
            Pair(s, PORT_DEFAULT)
        }
    }
}

package com.awei.spoofer.xposed

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.awei.spoofer.camera.RemoteCamSource
import com.awei.spoofer.camera.StreamManager
import com.awei.spoofer.data.HookConfigStore
import com.awei.spoofer.data.HookStatusStore
import com.awei.spoofer.xposed.hooks.CnSdkHook
import com.awei.spoofer.xposed.hooks.DetectionHook
import com.awei.spoofer.xposed.hooks.DetectionHideHook
import com.awei.spoofer.xposed.hooks.LocationHook
import com.awei.spoofer.xposed.hooks.Camera1Hook
import com.awei.spoofer.xposed.hooks.Camera2Hook
import com.awei.spoofer.xposed.hooks.RecordingHook
import com.awei.spoofer.xposed.hooks.ImageHook
import com.awei.spoofer.xposed.hooks.SensorHook
import com.awei.spoofer.xposed.hooks.WifiCellHook
import com.awei.spoofer.xposed.hooks.CameraXPhotoHook
import com.awei.spoofer.xposed.hooks.ActivityRecognitionHook
import com.awei.spoofer.xposed.hooks.PackageHideHook
import com.awei.spoofer.xposed.hooks.StackHideHook
import com.awei.spoofer.xposed.hooks.TencentLocationHook

class XposedInit : IXposedHookLoadPackage {

    // 必须 lazy：LSPosed 在 Zygote fork 后立即 Class.newInstance 实例化模块类，
    // 早于 ActivityThread.main 的 Looper.prepareMainLooper()——构造期 getMainLooper()
    // 为 null，Handler(null) 抛 NPE 会让整个类加载失败，所有 hook 一个都装不上
    // （1.1.10 微信实测：LSPosed 日志 88 次 "Failed to load class" 全是这个 NPE，
    // 微信进程同样失败 → 模拟位置在微信永远不生效）。首次使用在 Application.attach
    // 之后（loadAndSchedule），此时主线程 Looper 必已就绪
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        // 1.1.31：Hook 日志 tag 带完整进程名（主进程=包名不变；appbrandN 进程=
        // com.tencent.mm:appbrandN），多进程日志在导出里可分辨
        XLog.processName = lpp.processName
        // 不 hook 自身；但若 LSPosed 作用域勾选了本 App，LSPosed 会把模块注入自身进程——
        // 等 Application.attach 拿到 Context 后同进程直写"已注入"标记（HookStatusStore），
        // 供主 App 状态页体检确认"模块已启用+勾选了本 App"。不安装任何 hook，行为零影响
        if (lpp.packageName == SELF_PKG) {
            // 能走到这里 = LSPosed 已把本模块加载进本 App 进程。进程内静态标志，
            // 供体检区分"没注入(作用域/强停)" vs "注入了但自标记没写(异常)"
            HookStatusStore.selfLoadedInProcess = true
            XposedHelpers.findAndHookMethod(
                Application::class.java, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            HookStatusStore.selfRecord((param.args[0] as Context).applicationContext)
                        } catch (t: Throwable) {
                        }
                    }
                }
            )
            return
        }
        if (lpp.appInfo == null) return

        // 系统核心进程不动
        if (lpp.packageName == "android" || lpp.processName == "system_server") return

        // 每模块独立 try/catch：单个模块挂载失败（如 hookAllMethods 命中抽象方法声明、
        // ROM 差异缺方法）不得拖垮其余模块——旧的单 try 包全部会在第一个失败点中断，
        // 后面的模块一个都装不上。成功数上浮日志：自检"hook 装了几个"不用猜
        var ok = 0
        val modules = arrayOf(
            "LocationHook" to { LocationHook.hook(lpp.classLoader) },
            "CnSdkHook" to { CnSdkHook.hook(lpp.classLoader) },
            "DetectionHook" to { DetectionHook.hook() },
            "WifiCellHook" to { WifiCellHook.hook() },
            "SensorHook" to { SensorHook.hook() },
            "Camera2Hook" to { Camera2Hook.hook() },
            "Camera1Hook" to { Camera1Hook.hook() },
            "ImageHook" to { ImageHook.hook() },
            "RecordingHook" to { RecordingHook.hook() },
            "CameraXPhotoHook" to { CameraXPhotoHook.hook(lpp.classLoader) },
            "DetectionHideHook" to { DetectionHideHook.hook() },
            "ActivityRecognitionHook" to { ActivityRecognitionHook.hook(lpp.classLoader) },
            // 1.1.5 存在性反检测：PackageManager 侧门全堵 + 堆栈帧过滤
            "PackageHideHook" to { PackageHideHook.hook() },
            "StackHideHook" to { StackHideHook.hook() },
            // 1.1.9 腾讯位置服务 SDK：微信等腾讯系 App 的定位主路径
            "TencentLocationHook" to { TencentLocationHook.hook(lpp.classLoader) },
        )
        for ((name, fn) in modules) {
            try {
                fn()
                ok++
            } catch (t: Throwable) {
                XposedBridge.log("awei: $name hook failed: $t")
                XLog.e("模块 $name 安装失败: ${t.javaClass.simpleName}")
            }
        }
        XLog.i("进程 ${lpp.processName} hook 安装完成（$ok/${modules.size} 模块）")

        // Application.attach 拿到 context 后读配置
        XposedHelpers.findAndHookMethod(
            Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as Application
                        if (app.packageName != lpp.packageName) return
                        val ctx = param.args[0] as Context
                        // 1.1.32：子进程准确进程名识别（如 com.tencent.mm:appbrand0）
                        try {
                            val curProc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                Application.getProcessName()
                            } else {
                                java.io.File("/proc/self/cmdline").readText().trim().trimEnd(0.toChar())
                            }
                            if (!curProc.isNullOrBlank()) {
                                XLog.processName = curProc
                            }
                        } catch (t: Throwable) {
                        }
                        XLog.attach(ctx)
                        XLog.i("目标进程 Application attach，开始加载配置")
                        reportStatus(ctx)
                        loadAndSchedule(ctx)
                    } catch (t: Throwable) {
                        XposedBridge.log("awei: attach hook failed: $t")
                    }
                }
            }
        )
    }

    /** 1.1.16：后台小任务统一入口（此前 15s 刷新周期每次裸建 4 条 Thread，churn 无谓）；daemon */
    private fun bg(block: () -> Unit) {
        val t = Thread(block, "awei-hook-bg")
        t.isDaemon = true
        t.start()
    }

    private var scheduled = false

    private fun loadAndSchedule(ctx: Context) {
        loadConfig(ctx)
        loadCameraVideo(ctx)
        loadWifiIdentity(ctx)
        // 1.1.14 配置即时推送：HookConfigStore.save 写入后 notifyChange，此处 observer
        // 秒级触发 loadConfig（15s 轮询降为兜底）——待命/起跑/暂停切换若靠轮询，
        // 两 App 会出现最长 15s 的"一个在走一个站着"不同步窗口
        try {
            val obs = object : android.database.ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    bg { loadConfig(ctx) }
                }
            }
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://$SELF_PKG.config/current"), true, obs
            )
        } catch (t: Throwable) {
            XposedBridge.log("awei: config observer register failed: $t")
        }
        if (scheduled) return
        scheduled = true
        // 定期刷新配置（开关/参数变更）+ 心跳上报（主 App 体检页判断注入活跃度）。
        // 15s 周期：停止伪装/改配置最迟 15s 生效（60s 时用户会以为"关了没用"）；
        // IPC 成本为单条 provider 查询，可忽略
        val refresh = object : Runnable {
            override fun run() {
                loadConfig(ctx)
                loadCameraVideo(ctx)
                loadWifiIdentity(ctx)
                reportStatus(ctx)
                handler.postDelayed(this, 15_000L)
            }
        }
        handler.postDelayed(refresh, 15_000L)
    }

    /** WiFi 身份（假 AP）配置下发到 WifiCellHook。
     *  contentResolver 查询是 IPC：15s 周期在目标 App 主线程上同步执行会卡顿，移入后台 */
    private fun loadWifiIdentity(ctx: Context) {
        bg {
            try {
                val ssid = queryString(ctx, "wifi_ssid")
                val bssid = queryString(ctx, "wifi_bssid")
                val rssi = queryString(ctx, "wifi_rssi")?.toIntOrNull()
                if (ssid != null || bssid != null || rssi != null) {
                    WifiCellHook.applyIdentity(
                        ssid ?: WifiCellHook.fakeSsid,
                        bssid ?: WifiCellHook.fakeBssid,
                        rssi ?: WifiCellHook.fakeRssi
                    )
                }
            } catch (t: Throwable) {
            }
        }
    }

    @Volatile
    private var lastVideoUri: String? = null

    @Volatile
    private var lastImageUri: String? = null

    // 远程/网络源的状态描述只在变化时打日志：15s 刷新周期无变化也打会把目标 App 日志刷屏
    @Volatile
    private var lastRemoteDesc: String? = null

    private fun loadCameraVideo(ctx: Context) {
        bg {
            try {
                val enabled = queryBoolean(ctx, "cam_enabled") ?: false
                val source = queryString(ctx, "cam_source") ?: "file"
                val mirror = queryBoolean(ctx, "cam_mirror") ?: false
                var mirrorChanged = false
                val wasActive = StreamManager.cameraActive
                if (mirror != StreamManager.mirror) {
                    // 镜像模式切换：直连/中转/远程绘制是不同渲染路径，停流强制按新模式重建
                    XLog.i("镜像模式切换 ${StreamManager.mirror} → $mirror")
                    StreamManager.mirror = mirror
                    StreamManager.stopAll()
                    mirrorChanged = true
                }
                if (!enabled) {
                    // 彻底卸载视频源（停流 + 释放 retriever/pfd / 断开远程）
                    StreamManager.detachVideo()
                    lastVideoUri = null
                    lastRemoteDesc = null
                    if (mirrorChanged || wasActive) XLog.i("虚拟摄像头已关闭")
                    return@bg
                }
                if (source == "remote" || source == "network") {
                    // 远程手机（无 Root）或网络直播流出画面，本机 Hook 进程拉流解码
                    val addr = queryString(ctx, "cam_remote_addr")
                    val token = queryString(ctx, "cam_remote_token")
                    val isNet = source == "network"
                    val linkMode = queryString(ctx, "cam_link_mode") ?: "lan"
                    val isRelay = source == "remote" && linkMode == "relay"
                    val relayServer = if (isRelay) queryString(ctx, "cam_relay_server") else null
                    if (addr.isNullOrBlank() || (!isNet && token.isNullOrBlank()) ||
                        (isRelay && relayServer.isNullOrBlank())
                    ) {
                        XLog.w(
                            when {
                                isNet -> "网络源未配置流地址，虚拟摄像头不生效"
                                isRelay -> "中转源未配置服务器地址/远程 ID/配对码，虚拟摄像头不生效"
                                else -> "远程源未配置地址/配对码，虚拟摄像头不生效"
                            }
                        )
                        StreamManager.detachVideo()
                        lastRemoteDesc = null
                        return@bg
                    }
                    val label = appLabel(ctx)
                    if (StreamManager.useRemote && StreamManager.cameraActive) {
                        RemoteCamSource.setup(ctx, addr, token ?: "", label, relayServer)
                    } else {
                        StreamManager.detachVideo()
                        RemoteCamSource.setup(ctx, addr, token ?: "", label, relayServer)
                        StreamManager.attachRemote()
                    }
                    val desc = when {
                        isNet -> "网络源"
                        isRelay -> "远程源(中转 ID=$addr)"
                        else -> "远程源(局域网)"
                    } + " mirror=$mirror"
                    if (desc != lastRemoteDesc) {
                        lastRemoteDesc = desc
                        XLog.i("虚拟摄像头=$desc")
                    }
                } else {
                    // 本地视频/照片源：经 provider PFD 中转
                    val mediaUri = queryString(ctx, "cam_video")
                    if (mediaUri != null) {
                        val isImage = source == "image"
                        val lastUri = if (isImage) lastImageUri else lastVideoUri
                        val otherUri = if (isImage) lastVideoUri else lastImageUri
                        // URI 变了、源类型变了（otherUri 非空说明之前是另一种本地源）、或未激活 → 重新 attach
                        if (mediaUri != lastUri || otherUri != null || !StreamManager.cameraActive) {
                            val pfd = ctx.contentResolver.openFileDescriptor(
                                Uri.parse("content://$SELF_PKG.config/video"), "r"
                            )
                            if (pfd != null) {
                                val ok = if (isImage) StreamManager.attachImage(pfd)
                                         else StreamManager.attachVideo(pfd)
                                if (ok) {
                                    if (isImage) { lastImageUri = mediaUri; lastVideoUri = null }
                                    else { lastVideoUri = mediaUri; lastImageUri = null }
                                    XLog.i("虚拟摄像头就绪${if (isImage) "（照片源）" else ""} ${StreamManager.videoWidth}x${StreamManager.videoHeight} mirror=$mirror")
                                } else {
                                    XLog.e("${if (isImage) "照片源" else "视频源"} attach 失败")
                                }
                            }
                        }
                    } else {
                        StreamManager.detachVideo()
                        lastVideoUri = null
                        lastImageUri = null
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("awei: camera video load failed: $t")
            }
        }
    }

    /** 目标 App 展示名（远程授权弹窗显示用） */
    private fun appLabel(ctx: Context): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(ctx.packageName, 0)).toString()
    } catch (t: Throwable) {
        ctx.packageName
    }

    /**
     * 注入心跳上报：经 provider /status 告知主 App"本进程仍被 hook 着"。
     * 主 App 未装/provider 不可达时静默失败（此时主 App 自己也看不到体检页）
     */
    private fun reportStatus(ctx: Context) {
        bg {
            try {
                val ver = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0)?.versionName ?: ""
                } catch (t: Throwable) {
                    ""
                }
                val cv = android.content.ContentValues().apply {
                    put("pkg", ctx.packageName)
                    put("ver", ver)
                }
                ctx.contentResolver.insert(Uri.parse("content://$SELF_PKG.config/status"), cv)
            } catch (t: Throwable) {
                XLog.w("心跳上报失败: ${t.message}")
            }
        }
    }

    private fun queryString(ctx: Context, key: String): String? = try {
        ctx.contentResolver.query(
            Uri.parse("content://$SELF_PKG.config/current?k=$key"), null, null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun queryBoolean(ctx: Context, key: String): Boolean? = queryString(ctx, key)?.toBooleanStrictOrNull()

    @Volatile
    private var lastCfgJson: String? = null

    private fun loadConfig(ctx: Context) {
        bg {
            try {
                // 1.1.5：SpooferProvider.query 不带 ?k= 已返 null（防探测），改用 ?k=__config__
                // 特权键取全量 JSON。XposedInit 之外无人用此键——第三方探测只能凭 ?k=cam_*
                // 等单项键或猜中 __config__（dex 反编译可见，已记入 SpooferProvider 待办根治）
                val uri = Uri.parse("content://$SELF_PKG.config/current?k=__config__")
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val json = c.getString(0)
                        // 15s 周期刷新：配置未变时跳过 apply 与日志（apply 内部有同配置
                        // 幂等保护，这里跳过避免重复走 JSON 解析与日志刷屏）
                        if (json != lastCfgJson) {
                            lastCfgJson = json
                            HookState.apply(json)
                            val h = HookConfigStore.parse(json)
                            if (h?.enabled == true) {
                                XLog.i(
                                    "伪装配置生效 mode=${if (h.mode == 1) "轨迹(${h.route.size}点)" else "固定点"} " +
                                        "lat=%.5f lng=%.5f speed=${h.speedKmh}".format(h.lat, h.lng)
                                )
                            } else {
                                XLog.i("伪装配置读取成功（当前未启用伪装）")
                            }
                        }
                    }
                } ?: run {
                    lastCfgJson = null
                    HookState.apply(null)
                }
            } catch (t: Throwable) {
                // 主 App 不存在或 provider 不可用：不伪装
                XposedBridge.log("awei: config load failed: $t")
                XLog.w("配置读取失败（主 App 可能未安装）: ${t.javaClass.simpleName}")
                lastCfgJson = null
                HookState.apply(null)
            }
        }
    }

    companion object {
        const val SELF_PKG = "com.awei.spoofer"
    }
}

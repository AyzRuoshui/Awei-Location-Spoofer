package com.awei.spoofer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.awei.spoofer.data.HookConfigStore
import com.awei.spoofer.data.HookStatusStore
import com.awei.spoofer.data.SpLogger

/**
 * 导出的配置 Provider：Hook 进程（目标 App uid）通过
 * content://com.awei.spoofer.config/current?k=__config__ 读取伪装配置全量 JSON；
 * content://com.awei.spoofer.config/current?k=cam_enabled 等单项键取具体字段；
 * content://com.awei.spoofer.config/video 获取视频文件描述符（虚拟摄像头帧源）；
 * insert content://com.awei.spoofer.config/log 接收 Hook 进程日志回传（汇入统一日志）；
 * insert content://com.awei.spoofer.config/status 接收 Hook 进程注入心跳（HookStatusStore）。
 *
 * 1.1.5 防探测：未带 ?k= 或 k 不在已知键集合（含 __config__ 特权键）→ query 返 null；
 * openFile 在 cam_enabled=false 时返 null。此前无 ?k= 时直接 dump 全量 JSON，第三方
 * App 仅 query 即证明模块存在 + 拿到全部轨迹坐标/配对码/视频路径。
 *
 * 残留风险：__config__ 键可被反编译 dex 看到，懂行的检测方仍可探到——根治需 caller
 * 鉴权（Hook 代码跑在目标 App 进程，与目标 App 自己的反作弊代码同 UID，无法区分）。
 * 当前方案是把"不带 ?k= 就 dump 全量"这个最糟分支堵掉，提高探测门槛，记入待办根治。
 */
class SpooferProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!uri.path.orEmpty().contains("video")) return null
        val ctx = context ?: return null
        // 1.1.5 防探测：cam_enabled=false 时直接返 null——检测方探
        // content://com.awei.spoofer.config/video 不再拿到 PFD（没开虚拟摄像头时
        // 本就不该有视频可拿）。Hook 代码只在 queryBoolean(cam_enabled)=true 后才
        // 调 openFile，故此分支不影响合法调用
        if (!HookConfigStore.cameraEnabled(ctx)) return null
        val videoUri = HookConfigStore.cameraVideoUri(ctx) ?: return null
        return try {
            ctx.contentResolver.openFileDescriptor(Uri.parse(videoUri), "r")
        } catch (t: Throwable) {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Hook 进程日志回传：insert 到 /log，tag+msg 由 XLog 打包
        if (uri.path.orEmpty().contains("log")) {
            val ctx = context ?: return null
            SpLogger.ensureInit(ctx)
            val tag = values?.getAsString("tag") ?: "Hook"
            val msg = values?.getAsString("msg") ?: return null
            SpLogger.hookLine(tag, msg)
            return uri
        }
        // Hook 进程状态心跳：insert 到 /status（pkg+ver），供状态页"体检"判断模块注入是否活跃。
        // 注意 provider exported，写入方可被第三方伪造——这里只做引导展示，不作安全判定
        if (uri.path.orEmpty().contains("status")) {
            val ctx = context ?: return null
            val pkg = values?.getAsString("pkg")?.trim().orEmpty()
            if (pkg.isNotEmpty() && pkg != "com.awei.spoofer") {
                HookStatusStore.report(ctx, pkg, values?.getAsString("ver").orEmpty())
            }
            return uri
        }
        return null
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val key = uri.getQueryParameter("k")
        // 1.1.5 防探测：未带 ?k= 或 k 不在已知键集合 → 返 null（此前无 ?k= 时直接
        // 返 HookConfigStore.json(ctx) 全量 JSON，第三方 App 仅 query 即证明模块存在
        // + 拿到全部轨迹坐标/配对码/视频路径）。loadConfig 改用 ?k=__config__ 走特权分支
        val value: String? = when (key) {
            "cam_enabled" -> HookConfigStore.cameraEnabled(ctx).toString()
            "cam_video" -> HookConfigStore.cameraVideoUri(ctx)
            "cam_mirror" -> HookConfigStore.cameraMirror(ctx).toString()
            "cam_source" -> HookConfigStore.cameraSource(ctx)
            "cam_link_mode" -> HookConfigStore.cameraLinkMode(ctx)
            "cam_remote_addr" -> HookConfigStore.cameraRemoteAddr(ctx)
            "cam_remote_token" -> HookConfigStore.cameraRemoteToken(ctx)
            "cam_relay_server" -> HookConfigStore.cameraRelayServer(ctx)
            "wifi_ssid" -> HookConfigStore.wifiSsid(ctx)
            "wifi_bssid" -> HookConfigStore.wifiBssid(ctx)
            "wifi_rssi" -> HookConfigStore.wifiRssi(ctx).toString()
            "__config__" -> HookConfigStore.json(ctx)  // loadConfig 用
            else -> null  // 未知键或不带 ?k=：探测即落空
        } ?: return null
        val c = MatrixCursor(arrayOf("json"))
        c.addRow(arrayOf(value))
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

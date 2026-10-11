# Awei Spoofer (满血版)

本工程是「阿伟定位伪装·满血版」的 Android 客户端工程，采用 Xposed / LSPosed 运行时 Hook 方案，实现系统底层级别的精准位置、传感器、网络环境与摄像头画面的注入与伪装。

---

## 模块组成

- **`:app`** (com.awei.spoofer)
  主应用程序（Xposed 模块），包含：
  - **位置注入**：Hook Android 系统 `LocationManager` 与 `FusedLocationProvider`，支持经纬度、海拔、精度、方位角与速度模拟，提供逼真的步频随机微抖动（Jitter）。
  - **传感器模拟**：支持加速度计、陀螺仪、计步器（STEP_COUNTER / STEP_DETECTOR）的协同伪装。
  - **网络与基站**：抹除真实 WiFi 扫描结果与基站信号（CellInfo），避免第三方基于 BSSID/基站指纹反向定位。
  - **虚拟相机替换**：
    - 支持 Camera1 (`setPreviewCallback`, `Camera.takePicture`)
    - 支持 Camera2 (`CaptureRequest.Builder`, `ImageReader`, `SurfaceTexture`)
    - 支持 CameraX (`ImageCapture.OutputFileOptions`, `ImageReaderProxy`)
    - 支持注入本地静态图片、本地视频循环以及远程流（双机局域网推流或自建中转推流、RTSP/RTMP 直播源）。
  - **反检测**：隐藏 Xposed 堆栈调用帧、隐藏应用自身安装包特征、隐藏系统 mock_location 调试标记。
  - **悬浮窗与轨迹规划**：支持实时摇杆遥控、自定义路线绘制、导航环线自动闭合与平滑插值。

- **`:common`** (com.awei.common)
  共享协议库，定义双机远程摄像头通信与自建中转服务器的 TCP 二进制帧协议。

- **`:remotecam`** (com.awei.remotecam)
  独立运行的远程摄像头采集端（安装在副机上）。负责采集物理摄像头的原始预览流，通过 MediaCodec 硬编码为 H.264 视频流，并通过 TCP 实时推流至主机或自建中转服务器。

---

## 编译方法

在工程根目录下执行：

```bash
# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 版本（需配置 keystore.properties）
./gradlew assembleRelease
```

如果未提供 `keystore.properties`，构建系统将优雅跳过 release 签名配置。若需要签名，可在本目录创建 `keystore.properties` 文件：
```properties
storeFile=/path/to/your/release.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

---

## 架构说明

```text
[ 目标应用 (Target App) ]
         │
         ▼
[ LSPosed 框架注入 ] ──► [ Hooks: Location / Camera / Sensor / WifiCell / Anti-Detection ]
                               ▲
                               │ 本地 IPC / 广播下发配置
                               │
                      [ Awei Spoofer 主应用 ] ◄── (自建中转服务器 / 局域网 TCP) ──► [ Awei RemoteCam ]
```

---

## 免责声明

本工程源代码仅供 Android 系统机制探索、软件架构测试及安全逆向学术交流使用。请勿用于任何商业舞弊、违法犯罪或侵害他人合法权益的场景。使用者对因使用本代码产生的一切后果自负。

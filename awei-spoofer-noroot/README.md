# Awei Spoofer NoRoot (免Root版)

本工程是「阿伟定位伪装·免Root版」的独立 Android 客户端工程。基于 Android 系统原生的开发者选项「模拟位置信息应用」机制构建，专注于提供零 Root 门槛的位置模拟、轨迹巡航与摇杆遥控体验。

---

## 核心特性

- **零门槛即装即用**：无需解锁 Bootloader、无需 Root、无需安装 LSPosed 框架。直接在「开发者选项 - 选择模拟位置信息应用」中选中本应用即可生效。
- **全源差异化模拟**：覆盖 GPS、Network、Fused 与 Passive 定位源，支持动态航向平滑与步频抖动模拟（Jitter）。
- **摇杆控制模式**：支持全局悬浮窗摇杆遥控，支持线性/平滑加减速控制行走方向。
- **轨迹与路线巡航**：支持起终点检索、高德/ArcGIS/OSM 底图、一键环线规划、手绘轨迹捕获及实时画线续跑。
- **独立可共存**：包名采用 `com.awei.spoofer.noroot`，可与满血版（`com.awei.spoofer`）在同一台手机上并存安装。

---

## 与满血版对比

| 特性维度 | 免Root版（本工程） | 满血版（`awei-spoofer`） |
|---|---|---|
| **系统权限要求** | 普通用户权限（开启开发者选项） | Root + LSPosed 框架 |
| **定位伪装能力** | 官方开发者模拟通道（系统打标） | 系统底层 Hook（抹除模拟标记） |
| **虚拟相机替换** | ❌ 不支持 | ✅ Camera1/Camera2/CameraX 替换 |
| **WiFi / 基站伪装** | ❌ 不支持 | ✅ 抹除/伪装扫描列表 |
| **反检测级别** | 基础数据层拟真（防初级特征匹配） | 全方位反检测（防指纹、探针、堆栈） |

---

## 编译与构建

环境要求：JDK 17+, Android SDK 35 (API 35), Gradle 8.9+。

```bash
# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 版本（可选配置 keystore.properties）
./gradlew assembleRelease
```

如果未提供 `keystore.properties`，构建逻辑会自动跳过签名配置并允许编译。如需 Release 签名，可在本工程根目录下创建 `keystore.properties`：
```properties
storeFile=/path/to/your/release.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

---

## 免责声明

本工程源代码仅供 Android 模拟定位机制研究、地图导航算法测试与技术学习使用。严禁用于任何打卡作弊、考勤舞弊、商业欺诈或违反国家法律法规的非法行为。使用者自行承担所有使用后果。

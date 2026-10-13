import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awei.spoofer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awei.spoofer"
        minSdk = 21
        targetSdk = 35
        versionCode = 79
        versionName = "1.1.34"
    }

    val ksPropsFile = rootProject.file("keystore.properties")
    if (ksPropsFile.exists()) {
        val ksProps = Properties().apply {
            ksPropsFile.inputStream().use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = file(ksProps["storeFile"] as String)
                storePassword = ksProps["storePassword"] as String
                keyAlias = ksProps["keyAlias"] as String
                keyPassword = ksProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 不开 R8/混淆（1.1.17 起删掉 proguardFiles 死配置——minify 关闭时它不生效）：
            // Xposed 入口经 assets/xposed_init 按类名引用，混淆改名 = 模块直接失效
        }
    }

    // 本工具就是 mock 定位专用，lint 把 ACCESS_MOCK_LOCATION 当"debug-only" 是误报
    lint {
        disable += "MockLocation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation(project(":common"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Exif 方向读取（坑 #71）：androidx 版支持 JPEG/WebP/PNG/HEIF 全 8 种方向，
    // 框架 android.media.ExifInterface 不认 WebP（微信保存图常是 WebP）会直接抛错
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // 网络摄像头源（RTSP/RTMP/HTTP 直播流）：Media3 纯 Java 无 native，可在 Hook 进程内加载；
    // rtmp 走官方 datasource-rtmp 扩展；rtsp 是独立 artifact（DefaultMediaSourceFactory 按
    // classpath 上有没有 RtspMediaSource 决定支持 rtsp://，缺包时 rtsp 永远黑屏）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-datasource-rtmp:1.4.1")
    // 隐藏 API 绕过：清除 Location 内部 mock 标记（hook getter 之外的更深一层防御）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}

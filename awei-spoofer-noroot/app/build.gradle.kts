import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awei.spoofer"
    compileSdk = 35

    defaultConfig {
        // 独立包名：与完整版（com.awei.spoofer）可并存安装
        applicationId = "com.awei.spoofer.noroot"
        minSdk = 21
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.14"
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
            // 不开 R8/混淆（1.0.14 起删掉 proguardFiles 死配置——minify 关闭时它不生效；
            // 若未来要开混淆需先评估 keep 规则并全量回归）
        }
    }

    // pitfall #48：ACCESS_MOCK_LOCATION 被 lintVitalRelease 拦截（免Root版必须声明此权限）
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}

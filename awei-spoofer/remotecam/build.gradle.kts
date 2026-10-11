import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awei.remotecam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awei.remotecam"
        minSdk = 21
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.3"
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
            // 与 app 模块对齐：minify 一旦开启不至于漏规则文件
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(project(":common"))
}

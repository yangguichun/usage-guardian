plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.huajie.guardian"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.huajie.guardian"
        // minSdk 26：直接使用自适应图标（XML），无需准备多套 PNG
        // HarmonyOS 4 / EMUI 的底层是 Android 12（API 31），完全满足
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "2.6.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用 debug 签名，方便直接生成可安装的 release 包
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

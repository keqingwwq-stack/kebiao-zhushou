plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.timetable"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.timetable"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// 不依赖任何第三方库（不需要 androidx / appcompat），
// 只用 Android 框架自带的 Activity + View + RemoteViews，
// 这样既能用 Android Studio 构建，也能用手工 aapt2 + kotlinc + d8 打包。
dependencies {
}
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val buildStamp: String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Taipei") }
    .format(Date())

android {
    namespace = "com.narrator.jp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.narrator.jp"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        buildConfigField("String", "BUILD_DATE", "\"$buildStamp\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // voices.bin 必須以未壓縮的形式存放，AssetManager.openFd() 才能取得偏移量。
    androidResources {
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}

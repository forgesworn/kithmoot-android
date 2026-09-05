plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.forgesworn.kithmoot"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.forgesworn.kithmoot"
        // 26 is the floor, not a preference: the protocol module uses
        // java.util.Base64, which only lands in the platform at API 26.
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The WebRTC and libsecp256k1 natives are the bulk of the APK. Two
            // ABIs cover every device worth caring about and every emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle ships signature files that collide once the jar is
            // merged into the APK.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // AGP embeds an encrypted Play Store dependency block in the APK signing
    // block by default. Only Google can read it, and it has no business in a
    // binary meant to be reproducible by whoever builds it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.webrtc.android)
    implementation(libs.bouncycastle.provider)

    // libsecp256k1: the JNI natives for the device, and the desktop natives so
    // the unit tests can sign and verify on a plain JVM.
    implementation(libs.secp256k1.jni.android)
    testRuntimeOnly(libs.secp256k1.jni.jvm)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

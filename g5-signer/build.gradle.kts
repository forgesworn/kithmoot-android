plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.forgesworn.kithmoot.g5signer"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.forgesworn.kithmoot.g5signer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.secp256k1.jni.android)
}

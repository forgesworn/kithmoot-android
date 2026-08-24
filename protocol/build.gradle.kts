import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately no Android dependencies: this module must compile and run on a
// plain JVM so the interop vector suite stays fast and the protocol stays
// reusable outside the app.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.secp256k1.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.provider)

    // The JNI-backed secp256k1 implementation, supplied at runtime. On Android
    // the consumer swaps in secp256k1-kmp-jni-android instead.
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

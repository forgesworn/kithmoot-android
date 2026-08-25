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

// secp256k1-kmp-jni-jvm 0.19.0 (the version that fixed 16 KB page alignment on
// Android, see the version pin comment in libs.versions.toml) only publishes a
// JVM 21+ variant. Raise just the test compilation's target so the test
// classpath can resolve it; the module's own compiled output, and everything
// :app consumes, stays at JVM 17.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
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

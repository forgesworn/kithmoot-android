import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val linkBridgeDirectory = layout.buildDirectory.dir("link-bridge")
val requiredLinkBridgeFiles = listOf(
    "jniLibs/arm64-v8a/liblink_ffi.so",
    "jniLibs/x86_64/liblink_ffi.so",
    "kotlin/dev/forgesworn/link/ffi/link_ffi.kt",
    "manifest.json",
)
val verifyLinkBridgePrepared by tasks.registering {
    inputs.files(requiredLinkBridgeFiles.map { relative -> linkBridgeDirectory.map { it.file(relative) } })
    inputs.file(rootProject.file("scripts/prepare-link-bridge.py"))
    doLast {
        val root = linkBridgeDirectory.get().asFile
        val missing = requiredLinkBridgeFiles.filter { !root.resolve(it).isFile || root.resolve(it).length() == 0L }
        require(missing.isEmpty()) {
            "ForgeSworn Link bridge is not prepared; missing: ${missing.joinToString()}. " +
                "Run scripts/fetch-link-bridge.sh and scripts/prepare-link-bridge.py first."
        }
        val verification = providers.exec {
            commandLine("python3", rootProject.file("scripts/prepare-link-bridge.py"), "--verify-prepared")
        }
        check(verification.result.get().exitValue == 0) {
            verification.standardError.asText.get().trim()
        }
    }
}

// Product selection is independent of the debug-only development switches.
val invitationProfile = providers.gradleProperty("kithmoot.invitationProfile").orNull ?: "disabled"
require(invitationProfile in setOf("disabled", "admin2")) {
    "kithmoot.invitationProfile must be disabled or admin2"
}
val accountNetworkDefault = providers.gradleProperty("kithmoot.accountNetworkDefault").orNull ?: "nostr"
require(accountNetworkDefault in setOf("nostr", "private-invitations")) {
    "kithmoot.accountNetworkDefault must be nostr or private-invitations"
}
fun explicitBoolean(name: String): Boolean {
    val value = providers.gradleProperty(name).orNull ?: "false"
    require(value in setOf("true", "false")) { "$name must be true or false" }
    return value == "true"
}
val adminInvitationBundle = explicitBoolean("kithmoot.adminInvitationBundle")
val invitationPreview = explicitBoolean("kithmoot.experimentalInvitationMls")
require(invitationProfile != "admin2" ||
    (accountNetworkDefault == "private-invitations" && !adminInvitationBundle && !invitationPreview)) {
    "Admin2 product profile requires private-invitations and excludes both local development switches."
}
if (adminInvitationBundle) {
    require(invitationPreview) { "Admin2 bundle requires explicit experimental invitation development opt-in." }
    gradle.taskGraph.whenReady {
        require(allTasks.none { it.project == project && it.name.contains("Release", ignoreCase = true) }) { "Local Admin2 development bundle cannot build release tasks." }
    }
}
val vmlsFfiDirectory = layout.buildDirectory.dir(if (adminInvitationBundle) "vmls-admin-ffi" else "vmls-ffi")
val requiredVmlsFfiFiles = listOf(
    "jniLibs/arm64-v8a/libvmls_ffi.so",
    "jniLibs/x86_64/libvmls_ffi.so",
    "kotlin/dev/forgesworn/vmls/ffi/vmls_ffi.kt",
    "manifest.json",
    "manifest.txt",
)
val verifyVmlsFfiPrepared by tasks.registering {
    inputs.property("adminInvitationBundle", adminInvitationBundle)
    inputs.file(rootProject.file("scripts/prepare-vmls-ffi.py"))
    inputs.file(rootProject.file("scripts/prepare-admin-vmls-dev.py"))
    inputs.files(requiredVmlsFfiFiles.map { relative -> vmlsFfiDirectory.map { it.file(relative) } })
    doLast {
        val root = vmlsFfiDirectory.get().asFile
        val missing = requiredVmlsFfiFiles.filter { !root.resolve(it).isFile || root.resolve(it).length() == 0L }
        require(missing.isEmpty()) {
            "The VMLS engine bundle is not prepared; missing: ${missing.joinToString()}. " +
                "Run scripts/fetch-vmls-ffi.sh and scripts/prepare-vmls-ffi.py first."
        }
        val verification = providers.exec {
            commandLine("python3", rootProject.file(if (adminInvitationBundle) "scripts/prepare-admin-vmls-dev.py" else "scripts/prepare-vmls-ffi.py"), "--verify-prepared")
        }
        check(verification.result.get().exitValue == 0) { verification.standardError.asText.get().trim() }
    }
}

android {
    namespace = "dev.forgesworn.kithmoot"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.forgesworn.kithmoot"
        // The first production APK rotates away from the published preview
        // certificate. Android 13 is the floor on which the selected v3
        // lineage semantics are consistent and testable.
        minSdk = 33
        targetSdk = 35
        // Remain newer than the 0.6.3 rendezvous candidate (code 26).
        versionCode = 146
        versionName = "0.6.123"

        buildConfigField("String", "ACCOUNT_NETWORK_DEFAULT", "\"$accountNetworkDefault\"")
        buildConfigField("String", "INVITATION_PROFILE", "\"$invitationProfile\"")
        buildConfigField("boolean", "EXPERIMENTAL_INVITATION_MLS", "false")
        buildConfigField("boolean", "EXPERIMENTAL_ADMIN_INVITATION_MLS", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The STUN servers a call asks when the person has set none of their
        // own: somebody else's, never ForgeSworn's (23 October 2026). A build
        // can name others, comma-separated, with -Pkithmoot.defaultStun=...,
        // or none with an empty value.
        val defaultStun = providers.gradleProperty("kithmoot.defaultStun").orNull ?: "stun:stun.cloudflare.com:3478"
        require(defaultStun.matches(Regex("^[A-Za-z0-9:.,\\[\\]-]*$"))) { "kithmoot.defaultStun: use stun:host:port, separated by commas" }
        buildConfigField("String", "DEFAULT_STUN_URLS", "\"$defaultStun\"")

        ndk {
            // The native libraries are the bulk of the APK. arm64 is every
            // phone that can run Android 13; x86_64 is only for emulators, so
            // release builds drop it (see androidComponents below).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            // Explicit local opt-in; release always retains default false.
            buildConfigField("boolean", "EXPERIMENTAL_INVITATION_MLS", invitationPreview.toString())
            buildConfigField("boolean", "EXPERIMENTAL_ADMIN_INVITATION_MLS", (invitationPreview && adminInvitationBundle).toString())
        }
        release {
            // Gradle always produces an unsigned release. The release script
            // applies the owner-selected key and reviewed certificate lineage
            // with explicit APK Signature Scheme v3 settings.
            isMinifyEnabled = true
            isShrinkResources = true
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
        aidl = true
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("../protocol/src/test/resources")
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/attachments")
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("browser-call-interop"))
    // The reviewed Link bundle is unpacked into build/link-bridge only after
    // its archive and per-file manifest have been verified. Never copy these
    // generated bindings or JNI libraries into source control.
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("link-bridge/kotlin"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("link-bridge/jniLibs"))
    // The reviewed VMLS engine bundle (vennel's vmls-ffi, P3-03b), unpacked
    // the same way into build/vmls-ffi after its archive and manifest are
    // verified. Every build carries it (shipped after vennel's D1 review), and
    // CI checks that the release APK has the library, for arm64 only.
    sourceSets.getByName("main").java.srcDir(vmlsFfiDirectory.map { it.dir("kotlin") })
    sourceSets.getByName("main").jniLibs.srcDir(vmlsFfiDirectory.map { it.dir("jniLibs") })

    packaging {
        // Compress dex and native libraries inside the APK. With minSdk 33 AGP
        // stores both uncompressed, which suits Play (it compresses the
        // download) but not a sideloaded or Zapstore APK, where the file is
        // the download: 0.6.60 was 142 MB, almost all of it dex and native
        // code stored raw.
        dex { useLegacyPackaging = true }
        jniLibs { useLegacyPackaging = true }
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

tasks.named("preBuild") {
    dependsOn(verifyLinkBridgePrepared)
}

// Every build needs the VMLS engine bundle, release as well as debug.
tasks.matching { it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyVmlsFfiPrepared)
}

// Bundled MediaPipe camera inference and ML Kit QR decoding use local no-op
// datatransport classes. Reject the real telemetry runtime and unreviewed
// MediaPipe versions so a dependency upgrade cannot silently restore reporting.
abstract class VerifyNoDatatransportTelemetryTask : DefaultTask() {
    @get:Internal
    abstract val rootComponent: Property<org.gradle.api.artifacts.result.ResolvedComponentResult>

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    abstract val bundledModel: RegularFileProperty

    @TaskAction
    fun verify() {
        val offending = mutableSetOf<String>()
        val cameraSdks = mutableSetOf<String>()
        val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()

        fun visit(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (!visited.add(component.id)) return
            if (component.moduleVersion?.group == "com.google.android.datatransport") {
                offending += component.moduleVersion.toString()
            }
            if (component.moduleVersion?.group == "com.google.mediapipe") {
                cameraSdks += component.moduleVersion.toString()
            }
            for (dep in component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()) {
                visit(dep.selected)
            }
        }
        visit(rootComponent.get())

        val approvedCameraSdks = setOf(
            "com.google.mediapipe:tasks-vision:0.10.35",
            "com.google.mediapipe:tasks-core:0.10.35",
        )
        require(cameraSdks == approvedCameraSdks) {
            "Camera SDKs differ from the reviewed telemetry-free dependency pins: $cameraSdks. " +
                "Review the logging path before changing these pins (docs/effects-no-telemetry.md)."
        }
        val model = bundledModel.get().asFile.readBytes()
        val modelSha256 = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        require(model.size == 249_537 && modelSha256 == "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b") {
            "The bundled camera model differs from its reviewed pin (assets/models/README.md)."
        }

        require(offending.isEmpty()) {
            "Google's datatransport telemetry runtime is back on the release " +
                "classpath ($offending). MediaPipe's tasks-core and play-services-mlkit-" +
                "barcode-scanning both use this to report usage to Google, which this app " +
                "must never do silently. Restore the " +
                "`exclude(group = \"com.google.android.datatransport\")` on whichever " +
                "dependency reintroduced it in app/build.gradle.kts, or update " +
                "docs/effects-no-telemetry.md if the upstream fix has changed."
        }

        val manifestText = mergedManifest.get().asFile.readText()
        val forbiddenComponents = listOf(
            "TransportBackendDiscovery",
            "JobInfoSchedulerService",
            "AlarmManagerSchedulerBroadcastReceiver",
        )
        val present = forbiddenComponents.filter { manifestText.contains(it) }
        require(present.isEmpty()) {
            "The merged release manifest declares Google datatransport " +
                "component(s) $present, meaning something on the release classpath is using " +
                "the real telemetry runtime again even though no datatransport artifact " +
                "showed up on the dependency graph. See docs/effects-no-telemetry.md."
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // Only emulators and Intel Chromebooks run x86_64 Android, and
        // Chromebook users have the web app, so the release APK carries arm64
        // only and saves ~43 MB. CI's signing
        // lineage check installs the release APK on an x86_64 emulator and
        // passes -Pkithmoot.releaseEmulatorAbi=true to keep it.
        if (providers.gradleProperty("kithmoot.releaseEmulatorAbi").orNull != "true") {
            variant.packaging.jniLibs.excludes.add("lib/x86_64/**")
        }
        val verifyNoDatatransportTelemetry = tasks.register<VerifyNoDatatransportTelemetryTask>(
            "verifyNoDatatransportTelemetry",
        ) {
            group = "verification"
            description = "Fails if Google's datatransport telemetry runtime (used by " +
                "MediaPipe and ML Kit for usage reporting) is back on the release build " +
                "(docs/effects-no-telemetry.md)."
            rootComponent.set(
                configurations.named("${variant.name}RuntimeClasspath")
                    .map { it.incoming.resolutionResult.root },
            )
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            bundledModel.set(layout.projectDirectory.file("src/main/assets/models/selfie_segmenter.tflite"))
        }
        tasks.named("check") { dependsOn(verifyNoDatatransportTelemetry) }
        tasks.matching { it.name in setOf("packageRelease", "assembleRelease", "bundleRelease") }.configureEach {
            dependsOn(verifyNoDatatransportTelemetry)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":sdk-signet-contacts"))
    implementation(project(":mesh-radio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
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

    // QR-first sign-in and Bothy pairing use an on-device decoder. CameraX
    // supplies the lifecycle-safe preview; ML Kit's bundled model keeps this
    // path available without a network request or a Google service.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // play-services-mlkit-barcode-scanning also depends on
    // com.google.android.datatransport for its own usage-telemetry client
    // (decompiled com.google.android.gms.internal.mlkit_vision_barcode.zzwt
    // .. zzwx). Excluded for the same reason as the MediaPipe dependency
    // below, and necessarily together with it: Android dexing puts every
    // dependency's classes in one flat namespace, so this app's no-op
    // com/google/android/datatransport/**/*Stub.kt stand-ins and a real
    // datatransport cannot both be on the classpath under the same fully
    // qualified names. See docs/effects-no-telemetry.md.
    // Bundled selfie model: on-device inference, without a Play service or
    // runtime model download. Both camera and QR telemetry transports are no-ops.
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.android.datatransport")
    }
    implementation(libs.mlkit.barcode.scanning) {
        exclude(group = "com.google.android.datatransport")
    }
    // This is the matching local encoder for capability QR codes. It does not
    // use a camera, a network service, or a Google service.
    implementation(libs.zxing.core)

    // UniFFI's generated Link and VMLS Kotlin bindings load their libraries
    // through JNA.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // libsecp256k1: the JNI natives for the device, and the desktop natives so
    // the unit tests can sign and verify on a plain JVM.
    implementation(libs.secp256k1.jni.android)
    testRuntimeOnly(libs.secp256k1.jni.jvm)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Read the exact shared interoperability fixture, without a second copy.
tasks.withType<Test>().configureEach {
    systemProperty("kithmoot.boxVectors", rootProject.file("protocol/src/test/resources/box-discovery.json").absolutePath)
    systemProperty("kithmoot.storageVectors", rootProject.file("protocol/src/test/resources/storage-vectors.json").absolutePath)
}

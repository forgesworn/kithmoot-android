import com.android.build.api.artifact.SingleArtifact

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
    doLast {
        val root = linkBridgeDirectory.get().asFile
        val missing = requiredLinkBridgeFiles.filter { !root.resolve(it).isFile || root.resolve(it).length() == 0L }
        require(missing.isEmpty()) {
            "ForgeSworn Link bridge is not prepared; missing: ${missing.joinToString()}. " +
                "Run scripts/fetch-link-bridge.sh and scripts/prepare-link-bridge.py first."
        }
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
        versionCode = 42
        versionName = "0.6.19"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The WebRTC and libsecp256k1 natives are the bulk of the APK. Two
            // ABIs cover every device worth caring about and every emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // Gradle always produces an unsigned release. The release script
            // applies the owner-selected key and reviewed certificate lineage
            // with explicit APK Signature Scheme v3 settings.
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

    sourceSets.getByName("androidTest").assets.srcDir("../protocol/src/test/resources")
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/attachments")
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("browser-call-interop"))
    // The reviewed Link bundle is unpacked into build/link-bridge only after
    // its archive and per-file manifest have been verified. Never copy these
    // generated bindings or JNI libraries into source control.
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("link-bridge/kotlin"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("link-bridge/jniLibs"))

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

tasks.named("preBuild") {
    dependsOn(verifyLinkBridgePrepared)
}

// Guards the fix in docs/effects-no-telemetry.md: MediaPipe's tasks-core
// ships a logger that reports segmenter usage to Google's datatransport
// runtime (see the comment on the mediapipe.tasks.vision dependency above),
// and it turned out play-services-mlkit-barcode-scanning depends on the same
// runtime for its own telemetry. Both are excluded and both are stood in
// for with no-op classes under the real package names -- Android dexing
// puts every dependency's classes in one flat namespace, so a real
// datatransport and these stubs cannot coexist, which is also why this
// checks for the artifact group at all, not just a MediaPipe-shaped edge.
// This app talks to nobody but the relays and servers the person chose, so
// if a future dependency bump puts datatransport back on the release
// classpath, `check` (and therefore CI) must fail loudly rather than ship a
// silent regression.
abstract class VerifyNoDatatransportTelemetryTask : DefaultTask() {
    @get:Internal
    abstract val rootComponent: Property<org.gradle.api.artifacts.result.ResolvedComponentResult>

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val offending = mutableSetOf<String>()
        val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()

        fun visit(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (!visited.add(component.id)) return
            if (component.moduleVersion?.group == "com.google.android.datatransport") {
                offending += component.moduleVersion.toString()
            }
            for (dep in component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()) {
                visit(dep.selected)
            }
        }
        visit(rootComponent.get())

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
        }
        tasks.named("check") { dependsOn(verifyNoDatatransportTelemetry) }
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
    implementation(libs.mlkit.barcode.scanning) {
        exclude(group = "com.google.android.datatransport")
    }
    // This is the matching local encoder for capability QR codes. It does not
    // use a camera, a network service, or a Google service.
    implementation(libs.zxing.core)

    // Background replacement on the camera: MediaPipe runs the bundled selfie
    // segmentation model on the device. No network call and no Google service -
    // the .tflite is in this module's assets, see the README beside it.
    //
    // tasks-core (a transitive dependency of tasks-vision, on every released
    // version -- see docs/effects-no-telemetry.md) ships a logger that
    // reports segmenter usage to Google via com.google.android.datatransport.
    // Excluding that group keeps the segmenter itself, minus the reporting;
    // com/google/android/datatransport/**/*Stub.kt under this module's
    // sources supplies the same-named classes MediaPipe's compiled
    // RemoteLoggingClient needs so it links and runs, as no-ops.
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.android.datatransport")
    }

    // UniFFI's generated Link Kotlin bindings load liblink_ffi through JNA.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // libsecp256k1: the JNI natives for the device, and the desktop natives so
    // the unit tests can sign and verify on a plain JVM.
    implementation(libs.secp256k1.jni.android)
    testRuntimeOnly(libs.secp256k1.jni.jvm)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Read the exact shared interoperability fixture, without a second copy.
tasks.withType<Test>().configureEach {
    systemProperty("kithmoot.boxVectors", rootProject.file("protocol/src/test/resources/box-discovery.json").absolutePath)
}

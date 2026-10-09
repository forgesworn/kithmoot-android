import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { alias(libs.plugins.android.library) }

val sourceRoot = layout.buildDirectory.dir("source")
val sourcePin = file("source.json")
val verifyMeshRadioSource by tasks.registering {
    // Deliberately verify on every build; a warm build is not permission to
    // compile modified files from the generated directory.
    doLast {
        val pin = JsonSlurper().parse(sourcePin) as Map<*, *>
        val files = pin["files"] as Map<*, *>
        for ((path, expected) in files) {
            val source = sourceRoot.get().file(path.toString()).asFile
            require(source.isFile) { "Run python3 scripts/prepare-mesh-radio.py first (missing $path)" }
            val actual = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(actual == expected) { "Mesh radio source changed: $path; prepare the pinned source again" }
        }
        val allowed = files.keys.map { it.toString() }.toSet()
        require(sourceRoot.get().asFile.walkTopDown().filter { it.isFile }
            .all { it.relativeTo(sourceRoot.get().asFile).invariantSeparatorsPath in allowed }) {
            "Unexpected mesh radio source file; prepare the pinned source again"
        }
    }
}

android {
    namespace = "dev.forgesworn.meshble.radio"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        java.setSrcDirs(listOf(sourceRoot.map { it.dir("android-radio/src/main/java") }))
        manifest.srcFile(sourceRoot.map { it.file("android-radio/src/main/AndroidManifest.xml") })
    }
}
tasks.named("preBuild") { dependsOn(verifyMeshRadioSource) }
dependencies { implementation(libs.androidx.core.ktx) }

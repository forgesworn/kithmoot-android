import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm); `java-library` }

// Build the pinned shared SDK source with this application's Kotlin toolchain.
// No sibling checkout or network fetch is needed on consumer builds.
val sourceArchive = layout.projectDirectory.file("vendor/signet-contacts-core.tar.gz")
val unpackSource by tasks.registering(Sync::class) {
    inputs.file(sourceArchive)
    from(tarTree(resources.gzip(sourceArchive)))
    into(layout.buildDirectory.dir("upstream"))
    doFirst {
        val actual = MessageDigest.getInstance("SHA-256").digest(sourceArchive.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == "b8f2cad2735548bdb1b2d629990380b0038e2e1056671c55c9c92ef9bb8b608a") { "Signet contacts SDK source checksum mismatch" }
    }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets.getByName("main").kotlin.srcDir(layout.buildDirectory.dir("upstream/core/src/main/kotlin"))
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.named("compileKotlin") { dependsOn(unpackSource) }
tasks.named("processResources") { dependsOn(unpackSource) }
tasks.named<Jar>("jar") { from(layout.buildDirectory.file("upstream/LICENSE")) { into("META-INF/signet-contacts") } }
dependencies { api(libs.kotlinx.coroutines.core) }

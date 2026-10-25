# WebRTC's Java layer is called back into from native code by name, so nothing
# under org.webrtc may be renamed or stripped.
-keep class org.webrtc.** { *; }
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn org.bouncycastle.**

# Instrumentation compiles against the original app API and shares its Kotlin
# runtime rather than applying the release mapping or packaging a second copy.
-keep class dev.forgesworn.kithmoot.** { *; }
-keep class kotlin.** { *; }

# The Link and VMLS engines are called through uniffi's JNA bindings. JNA
# resolves native symbols and structure fields by name at runtime, so neither
# the generated bindings nor JNA itself may be renamed or stripped.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-keep class dev.forgesworn.link.ffi.** { *; }
-keep class dev.forgesworn.vmls.ffi.** { *; }
-dontwarn com.sun.jna.**

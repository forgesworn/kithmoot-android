# WebRTC's Java layer is called back into from native code by name, so nothing
# under org.webrtc may be renamed or stripped.
-keep class org.webrtc.** { *; }
# WebRTC also loads JNI Zero helpers by name from JNI_OnLoad. They live
# outside org.webrtc; stripping JniInit makes the native loader abort.
-keep class org.jni_zero.** { *; }
# MediaPipe resolves Java names from JNI. Keep its native callback surface and
# the no-op transport API used by its compiled logger in minified releases.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.android.datatransport.** { *; }
# MediaPipe's Graph initialiser calls FluentLogger.forEnclosingClass(), which
# finds its caller by walking the stack. Renaming/inlining Flogger breaks it.
-keep class com.google.common.flogger.** { *; }
# Graph options include protobuf Any and other well-known types. Their lite
# schemas find fields such as typeUrl_ by name, including from kept SDK protos.
-keep class com.google.protobuf.** { *; }
# The pinned AAR exposes optional profiling/template methods without these
# protos. ImageSegmenter uses CalculatorGraphConfig, never those optional APIs.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn org.bouncycastle.**

# Instrumentation compiles against original app and library APIs instead of
# applying the release mapping. Preserve those APIs and their override contracts.
-keep class dev.forgesworn.kithmoot.** { *; }
-keep class dev.forgesworn.signet.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.camera.view.PreviewView { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.tracing.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-keep class com.google.zxing.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.bouncycastle.crypto.CipherParameters { *; }
-keep class org.bouncycastle.crypto.digests.SHA3Digest { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }

# The Link and VMLS engines are called through uniffi's JNA bindings. JNA
# resolves native symbols and structure fields by name at runtime, so neither
# the generated bindings nor JNA itself may be renamed or stripped.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-keep class dev.forgesworn.link.ffi.** { *; }
-keep class dev.forgesworn.vmls.ffi.** { *; }
-dontwarn com.sun.jna.**

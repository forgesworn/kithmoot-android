# WebRTC's Java layer is called back into from native code by name, so nothing
# under org.webrtc may be renamed or stripped.
-keep class org.webrtc.** { *; }
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn org.bouncycastle.**

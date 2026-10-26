# Android 0.6.113: WebRTC native load crash

The M4-connected Pixel 10 Pro XL recorded four native crashes on 26 October
2026 when opening a saved room. Its installed 0.6.113 (136) APK was the
published asset, SHA-256
`03a7bf4280b423ee08047d32e3c7728156adaebc2837dbb6ff9071b286d8d2bb`.

Immediately before SIGTRAP, the process reported
`ClassNotFoundException: org.jni_zero.JniInit` and
`jni_zero.cc:38 Failed to find class org/jni_zero/JniInit`. The stack ran
through WebRTC's `JNI_OnLoad`, `PeerConnectionFactory.initialize` and
`WebRtcEngine` construction. The published release's R8 `usage.txt` lists
`JniInit` and `JniUtil` as removed. The existing keep rule covered
`org.webrtc`, but JNI Zero lives outside that package.

The fix preserves `org.jni_zero.**`, including private methods invoked by
the native loader. It changes no production Kotlin code or dependency.
`WebRtcNativeLoadTest` initialises the native library and factory, creates
audio and video sources, and disposes them without starting capture or
joining a room. The signing-lineage emulator gate now runs this test against
the minified release on both configured API levels, rather than relying on
debug media tests to detect release stripping.

## Qualification and private handset update

- Built from main `86a3079e` plus this fix on M4, with JDK 21 and the pinned
  prepared Link, VMLS and mesh components. Release packaging, debug test APK
  packaging and the telemetry/dependency guard passed. Protocol and app debug
  unit tests, plus debug and release lint, also passed.
- The same test APK crashed against the original release on an isolated
  ARM64 API 35 emulator, with the same missing `JniInit` diagnostic. It
  passed against the fixed minified release.
- Signed the fixed release with the existing owner production key and
  preview-to-production lineage. Verified production certificate SHA-256
  `135bcabf715216d13b7a596c6f891c3220a7f90826ef8e75930d51b34506a2e9`.
- Updated the physical Pixel in place. Version remains 0.6.113 (136);
  this is a private patched build, with no replacement of public release
  metadata. Fixed APK SHA-256:
  `1d84d8cbaa1a4c21ca9c636f32052c2d7f5f8cd1b0aad8f55d6ff654d17c6a7d`.
- The native-load test passed in three fresh processes on the Pixel's
  installed release. Removed the temporary instrumentation app and cold
  launched the main activity successfully (560 ms). The original installation
  timestamp, 13 September 2026, and primary-user UID 10229 were retained.
  The delivery service returned to LIVE with the same 14 watched rooms.
  Pulling the installed APK back independently reproduced the fixed APK hash.
- Stopped the task's read-only emulator instance; its borrowed AVD and the
  other running emulators were preserved.

Private raw logs, APKs, signing verification, release mapping and build/test
reports are retained under
`~/.local/share/kithmoot-release-artefacts/pixel-jni-crash-20261026/`.
No room content, account credentials or camera/audio capture is included in
this public note. These checks establish the native startup fix on this
handset; a live call and broader GrapheneOS acceptance remain separate gates.

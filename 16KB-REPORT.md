# 16 KB page alignment — fix verification

Fixes the defect in `PIXEL-REPORT.md` §1: the platform's "This app isn't 16
KB-compatible. ELF alignment check failed" dialog, seen on a Pixel 10 Pro XL
(Android 17, API 37) before the app's first frame.

## The version landed on

`fr.acinq.secp256k1` **0.19.0** — not 0.17.3. Maven Central now has releases up
to 0.24.0 (this ecosystem has moved fast); acinq's actual 16 KB fix landed in
**0.19.0** (`ACINQ/secp256k1-kmp#124`/`#126`), which is also the *ceiling*:
0.20.0 onwards is compiled with Kotlin 2.2+ metadata and the Kotlin 2.0.21
compiler here can't read it (verified — 0.20.0 and 0.24.0 both fail to
compile with "Module was compiled with an incompatible version of Kotlin").
0.19.0 is therefore the only version that is both 16 KB-aligned and buildable
with this project's pinned toolchain.

One knock-on fix was required: `secp256k1-kmp-jni-jvm:0.19.0` (used only by
`:protocol`'s desktop-JVM test runtime) publishes a JVM 21+-only variant.
`protocol/build.gradle.kts` now targets JVM 21 for `compileTestKotlin` /
`compileTestJava` specifically — the module's own compiled output and
everything `:app` consumes is untouched at JVM 17. JDK 21 was already this
machine's Gradle daemon JVM, so no new toolchain install was needed.

## `p_align`, read from the actual rebuilt APK (`llvm-readelf -l`)

| Library | Before | After | ABI |
|---|---|---|---|
| `libsecp256k1-jni.so` | `0x1000` | **`0x4000`** | arm64-v8a |
| `libsecp256k1-jni.so` | `0x1000` | **`0x4000`** | x86_64 |
| `libandroidx.graphics.path.so` | `0x4000` | `0x4000` | arm64-v8a, x86_64 |
| `libjingle_peerconnection_so.so` | `0x4000` | `0x4000` | arm64-v8a, x86_64 |

"Before" values confirmed by extracting `lib/arm64-v8a/*.so` from the
already-shipped `app-debug.apk` (not assumed from the report) — they matched
`PIXEL-REPORT.md` exactly. Independently verified every released JNI-android
AAR from 0.15.0 through 0.24.0 by downloading each from Maven Central and
reading its program headers directly: 0.15.0–0.18.0 are all `0x1000`; 0.19.0
onwards are all `0x4000`. 0.17.0–0.17.3 are byte-identical to each other
(same sha256) — no native change at all in that range, confirming a bump to
0.17.3 specifically would have done nothing.

## Dialog

Installed the rebuilt debug APK on the Pixel (`59041FDCQ005D3`), force-stopped
and relaunched. `logcat` carries no 16 KB / ELF alignment lines this run (there
were several on the original run). `dumpsys window` shows
`mCurrentFocus=...dev.forgesworn.kithmoot/.MainActivity` directly — no
system dialog window on top. Screenshot confirms the app's own home screen
(KithMoot / "Start a room") renders as the first frame, not a warning.
Saved as `docs/screenshots/81-real-16kb-fixed.png`; read back and inspected
visually before writing this line.

## Tests

`./gradlew :protocol:test :app:testDebugUnitTest --rerun-tasks` — **157
passed**, 0 failed, unchanged count. `:protocol`'s suite includes every
published interop vector (schnorr signatures, room derivation, kindred
proofs, roster events, signal wrap, TURN credentials, join URLs), which pin
byte-for-byte against the TypeScript reference — all passing on 0.19.0 is the
proof the crypto is unchanged.

## Unresolved

Nothing outstanding for this defect. Note for whoever revisits the pin: once
the house Kotlin standard moves to 2.2+, later secp256k1-kmp releases (up to
0.24.0 confirmed) are available and also 16 KB-aligned.

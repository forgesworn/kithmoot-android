# ForgeSworn Link Android bridge

KithMoot consumes ForgeSworn Link as a reviewed binary hand-off. It does not
rebuild Link, copy JNI libraries into the repository, or accept an unpinned
release name.

The current hand-off is Link commit `e3f80d48f34e8e72f1028540d9962b7eb4988577`,
published as the permanent prerelease `android-ffi-e3f80d4`, with archive SHA-256
`bb3ba23b2dd030b58e23cb2a499453360167e9a3c0e052d61a20b6b118fdfaed`.
Its manifest pins the two shipped ABIs and the generated UniFFI Kotlin binding
individually.

This hand-off adds Link's VMLS route table (P3-04) and the witness refusal flag:
a Link HTTP response's `witnessRefused` is true only for a 403 on a Bothy
restore-witness route carrying `vmls-witness: refused`, and the app carries it
as `LinkJsonResponse.witnessRefused`. Cadence requests keep their existing
rules.

To prepare any local or hosted build, download and verify the source-pinned
public release asset:

```sh
scripts/fetch-link-bridge.sh build/link-ffi-android.zip
scripts/prepare-link-bridge.py build/link-ffi-android.zip
```

The fetch script retains the previous archive after a failed download. The
preparation script rejects a changed archive, source commit, file list, ABI,
minimum SDK, file size or file digest before replacing the generated directory.
Every app build also refuses to start unless the verified bridge files are
present, so CI cannot certify an APK that silently lacks paired Link transport.

The generated directory is an input to Android packaging: Kotlin sources come
from `kotlin/`, while `jniLibs/` contributes exactly `arm64-v8a` and `x86_64`.
The relay and cadence transports both depend on this preparation step before
they load `dev.forgesworn.link.ffi`.

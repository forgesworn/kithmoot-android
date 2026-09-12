# ForgeSworn Link Android bridge

KithMoot consumes ForgeSworn Link as a reviewed binary hand-off. It does not
rebuild Link, copy JNI libraries into the repository, or accept an unpinned
release name.

The current hand-off is Link commit `f127d18b3e5ed9d3ee64074cb539b6123d325d1c`,
published as the permanent prerelease `android-ffi-f127d18`, with archive SHA-256
`cfe1b3ea68c11aa74fad12710d94cfaee1c8e2e67ea80e0ac120e089e96b8f6b`.
Its manifest pins the two shipped ABIs and the generated UniFFI Kotlin binding
individually.

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
The later Link transport code must depend on this preparation step before it
references `dev.forgesworn.link.ffi`.

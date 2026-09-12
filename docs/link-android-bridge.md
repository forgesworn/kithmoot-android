# ForgeSworn Link Android bridge

KithMoot consumes ForgeSworn Link as a reviewed binary hand-off. It does not
rebuild Link, copy JNI libraries into the repository, or accept an unpinned
release name.

The current hand-off is Link commit `c454bb3d83010fc4d335963914f0e2e874bef7f5`,
published as the permanent prerelease `android-ffi-c454bb3`, with archive SHA-256
`724d867f4714668078b90bb3769a0f2235651a610c5a2ebba552c1130a2332f4`.
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
The relay and cadence transports both depend on this preparation step before
they load `dev.forgesworn.link.ffi`.

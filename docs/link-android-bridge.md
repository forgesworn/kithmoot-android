# ForgeSworn Link Android bridge

KithMoot consumes ForgeSworn Link as a reviewed binary hand-off. It does not
rebuild Link, copy JNI libraries into the repository, or accept an unpinned
release name.

The current hand-off is Link commit `4cdc7de37f0b8947d8d746798da406d8b3932be1`,
published as the permanent prerelease `android-ffi-4cdc7de`, with archive SHA-256
`3459d321527e1ae8875b04b329385963deaf531fcd85fd1f9eb3e1725e77071d`.
Its manifest pins the two shipped ABIs and the generated UniFFI Kotlin binding
individually.

This hand-off moves rustls to 0.23.45 for RUSTSEC-2026-0285 (forgesworn-link
#66, D1 finding B0). The earlier hand-off, `android-ffi-2cdf5a9`, gave a
filled VMLS slot's status answer the fetch page's 2 MiB bound
(forgesworn-link #64), so a commit in the 1 MiB bucket can be read back
(P3-03b-3a). Before that, `android-ffi-e3f80d4` added Link's VMLS
route table (P3-04) and the witness refusal flag: a Link HTTP response's
`witnessRefused` is true only for a 403 on a Bothy restore-witness route
carrying `vmls-witness: refused`, and the app carries it as
`LinkJsonResponse.witnessRefused`. Cadence requests keep their existing rules.

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

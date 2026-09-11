# ForgeSworn Link Android bridge

KithMoot consumes ForgeSworn Link as a reviewed binary hand-off. It does not
rebuild Link, copy JNI libraries into the repository, or accept an unpinned
release name.

The current hand-off is Link commit `6bb5242bc19119fd2da702af50c466cea42975d7`,
GitHub Actions artifact `10189494804`, with archive SHA-256
`906f16979bfacc2cbd3e773309fe843e64c940ec468fa6546f6a98aa2afc7706`.
It expires on 10 December 2026. Its manifest pins the two shipped ABIs and the
generated UniFFI Kotlin binding individually.

To prepare a local build, download the archive with a token that can read only
the Actions artifacts in `forgesworn/forgesworn-link`, then verify and unpack it:

```sh
scripts/fetch-link-bridge.sh build/link-ffi-android.zip
scripts/prepare-link-bridge.py build/link-ffi-android.zip
```

`FORGESWORN_LINK_ARTIFACT_TOKEN` is intentionally not a Gradle property or a
tracked configuration value. The fetch script exposes it only through the
GitHub CLI process environment and retains the previous archive after a failed
download. The
preparation script rejects a changed archive, source commit, file list, ABI,
minimum SDK, file size or file digest before replacing the generated directory.

The generated directory is an input to Android packaging: Kotlin sources come
from `kotlin/`, while `jniLibs/` contributes exactly `arm64-v8a` and `x86_64`.
The later Link transport code must depend on this preparation step before it
references `dev.forgesworn.link.ffi`.

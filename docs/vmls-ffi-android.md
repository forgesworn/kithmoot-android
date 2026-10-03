# The VMLS engine bundle (vmls-ffi)

KithMoot ships vennel's VMLS/1 engine and its restore-witness coordinator
(`vmls-ffi`, P3-03b) the same way as the ForgeSworn Link bridge
(`docs/link-android-bridge.md`). The bundle is:
- a stripped `libvmls_ffi.so` for arm64-v8a and x86_64;
- the UniFFI Kotlin binding;
- a manifest naming the vennel source commit and every file's SHA-256.

vennel's `VMLS Android bundle` workflow builds it. vennel is private, so a
reviewed build is republished, byte for byte, as a prerelease of this
repository.

**Debug builds only.** The engine is non-shipping until its independent
review (vennel decision D1). Only the `debug` build type (the debug APK
and instrumentation tests) compiles the binding and packages
`libvmls_ffi.so`, and only debug builds require the bundle. CI fails if a
release APK carries the library, and the production release script never
fetches it.

```sh
scripts/fetch-vmls-ffi.sh build/vmls-ffi-android.zip
scripts/prepare-vmls-ffi.py build/vmls-ffi-android.zip
```

`prepare-vmls-ffi.py` checks four things before unpacking into
`app/build/vmls-ffi`:
- the archive's SHA-256;
- the manifest's source commit and minimum SDK;
- the exact file set;
- every file's hash.

The download URL is only a transport. `preBuild` refuses to run until the
bundle is prepared. Nothing generated is committed.

To move to a new engine build, review the vennel change, publish its CI
bundle, and update the tag in `fetch-vmls-ffi.sh` and the archive hash and
commit in `prepare-vmls-ffi.py`, in one commit.

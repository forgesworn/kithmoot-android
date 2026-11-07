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

**Every build carries it.** The engine shipped after vennel's D1 review (an
automated model review; see vennel `docs/gate/2026-10-07-d1-review.md`).
The `main` source set compiles the binding and packages `libvmls_ffi.so`,
and debug and release builds both require the bundle. Release is arm64
only, as for Link. CI fails if the release APK lacks
`lib/arm64-v8a/libvmls_ffi.so` or carries any x86_64 library, and the
production release script fetches the bundle.

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

## Current merged VMLS 0.1.2 hand-off

The current pin is Vennel `feea45fc4ca172b05ea671579df51e936c5804b6`, the
merged VMLS 0.1.2 revision on `main`, built with Rust 1.94.1 and min SDK 26.
The archive SHA-256 is
`0de096b79e7ef3a7d98c97dac74ea0582d5b1856d8bdb8d646d911b82aceaa26`.
The protocol fixture is byte-identical to standalone `1c0adec` and has exactly
43 cases, including six valid framed cases and five unframed cases.

Capability opening and introduction completion pass the current `VmlsPlatform`.
Invitation inspection passes the vault's current revoked credential ids. The
existing `EngineSessions` constructor still uses the empty-revocation-list
constructor, so native capability revocation enforcement is not claimed as
production coverage. Emulator evidence is not physical GrapheneOS acceptance.

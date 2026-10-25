# Android Admin2 development candidate

This unmerged candidate tracks current `origin/main` while retaining the lab
roster reconciliation fix (`3a6618797ddc8feac029bced95e1d976245fd8d5`). It adds the
separate `Vennel/invitation-admins/2` / `designated-leaf-admins/1` profile. The
older Invitation1 API and development UI remain a distinct experimental target.
No group is upgraded or retried through another profile.

The independent Kotlin reader requires exactly 17 canonical context keys and
context version 2; the authorization signing body retains outer version 1.
It recomputes the Admin2 authorization digest and context commitment before the
purpose-specific vault signs. Fresh consent displays profile, policy, admin
commitment, invitation, group/home/installation, exact target person/device/
rendezvous/home, private return route, expiry and signing deadline. The signing
journal purpose, reply provenance and runtime entrypoints are separate from
Invitation1. Current device credential, rendezvous child, cancellation, session,
clock and durable journal checks remain enforced through final signature release.

Admin2 create/join/issue/Add/Remove use explicit native profile entrypoints and
refuse profile mismatch. Private Link routes, protected response replay and
separate keeper registration remain in place. A package registration receipt
proves one observed storage operation, never MLS admin authority.

Device admin roles come from native `getCurrentAdminRolesV1`, including the
applied MLS epoch and complete policy commitment. Each row names an exact
person/device/leaf. Promotion or demotion uses the reviewed complete commitment
in native `setAdminRolesV1`; stale intent, absent/unchanged targets and last-admin
demotion refuse. The native engine remains the authority for incoming and local
commits. `VmlsRole.KEEPER`, node grants, host persistence generations and display
rosters confer no Admin2 role. Admin role commits have an explicit room signal.
Snapshot 5 uses only `openAdminInvitationSession`; unknown formats/versions refuse.

## Exact local native bundle

The production pin in `scripts/prepare-vmls-ffi.py` is unchanged. This candidate
uses new JNI symbols and therefore does **not** compile with that old pinned
bundle. It is not merge-ready until a separately reviewed native publication and
consumer pin update. No native bundle publication or production enablement is
part of this branch.

For local debug qualification only, prepare the exact source
`5b3eb5fe28255b5c0cc09d959a3010a3d48f9562` bundle:

```sh
python3 scripts/prepare-admin-vmls-dev.py --development \
  /Users/darren/WebstormProjects/vennel/release/admin-policy-20261025/artifacts/5b3eb5fe28255b5c0cc09d959a3010a3d48f9562/vmls-ffi-android.zip \
  69bc48ed9d845c825a54423c94a993153ada34a0f37efedd7e11b1eca00e4f28
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/darren/Library/Android/sdk ./gradlew \
  -Pkithmoot.adminInvitationBundle=true -Pkithmoot.experimentalInvitationMls=true \
  :app:testDebugUnitTest :app:assembleDebug
```

The preparer verifies archive SHA256, exact file set, source commit, minimum SDK,
and every binding/JNI manifest checksum. Output is confined to
`app/build/vmls-admin-ffi`. Gradle selects it only through the explicit flag,
requires the separate invitation development opt-in, re-verifies its files and
refuses release tasks. Release configuration keeps both preview flags false.
No public fallback transport, deploy, device installation, physical run or
emulator modification is performed by this work.

## Validation evidence

The committed bounded summary is
`docs/evidence/admin-policy-20261025/summary.json`. Full logs, initial failures,
JUnit XML, lint reports, exact source/test hashes and the final debug-signed APK
are preserved under the primary repository's
`release/admin-policy-20261025/`; the Vennel release folder holds a cross-repo
receipt pointing to that APK without duplicating it.

Final source verification passed: 571 pure-JVM protocol tests in 89 suites,
68 focused app tests in five suites, debug lint (0 errors, 99 warnings), and
`assembleDebug`. The embedded arm64 and x86_64 native libraries match the exact
candidate manifest. The release-task and missing-opt-in guards both refused as
expected. The first protocol compile used unsupported Kotlin-test imports;
the corrected JUnit4 run passed. The first Android compile revealed the added
native `ADMINS` commit-kind enum; its explicit event mapping and tests were
added before successful final verification.

Independent security/wire review, a real mixed-client private invitation and
role-change journey, transport fault coverage, physical devices and production
publication remain separate acceptance gates.

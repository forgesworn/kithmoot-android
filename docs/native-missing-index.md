# Missing saved index preserves native authority

`EncryptedRoomStorage.read()` now refuses a missing ciphertext when the store's
Android Keystore alias still exists. A first-use store with neither file nor
alias still reads empty. Explicit reset still deletes both and permits fresh use.
The guard uses an alias-existence query; it never creates a key or an empty list.

This follows the precode design committed in mesh-kit-private at 7c63d2bd,
`spec/native-missing-index-maintenance-design.md`. The independently accepted
20b75661 pending trace identified foreground maintenance interpreting a missing
index as an empty saved-room list and writing the receiver journal. C4594f7d's
separate hostile-index commit failures remain unattributed. No concurrency fix
or relaxed commit check is included.

Four additive Android cases use real Keystore/AtomicFile: first-use emptiness,
missing-index refusal with exact unrelated receiver/member bytes and all aliases
preserved, exact ciphertext restoration, explicit reset/fresh use, and the
actual foreground Nearby creation/leave/refresh maintenance path. The latter
checks storage error, both late retention callbacks refusing, a complete empty
receiver observation, unchanged retained ciphertexts/aliases and no new route
factory, radio offer or relay write; exact restoration recovers the saved room.

All five existing encrypted storage cases and eleven native-host entry methods
remain byte-identical. All original 162 cases/48 groups remain, in their original
order. Two new groups add three storage cases and one maintenance case, requiring
166 ordinary cases/50 groups. Removing only their two advertised command lines
reconstructs the original integrated runner exactly. All thirteen positive
process-death drivers, five pending windows and eleven hostile faults per window
remain required. The new branch triggers the same nine hosted CI jobs.

Local source compilation against immutable dependencies is not Android execution,
Gradle/lint/APK, Keystore or physical qualification. The final changed head needs
its own hosted evidence. Fresh compilation of four production sources, the real
host fixture, both new Android classes and the existing recovery/enrol helper
sources passes, followed by 19 existing JVM recovery/project cases in 23.089 s.
The immutable 6,793-file dependency set includes an explicitly labelled JVM
BuildConfig flag fixture and mocked Android API runtime. The four new Android
methods were compiled but have not run locally. Eleven recovery-driver control
tests pass; they exercise the driver with a generated adb fixture, not Android.
The final changed head needs
its own full hosted evidence and complete diagnostics before merge. Bounded
cleanup stack completeness, the controlled AtomicFile overlap investigation,
capacity/security and physical radio qualification remain open. No hardware,
radio/MQTT configuration, channel, public relay or account operation is included.

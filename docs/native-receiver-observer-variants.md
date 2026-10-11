# Receiver observer test variants

The common observer fixture expected debug registration in release; verify at
4a773684 ran 1,972 release tests and nine observer calls hit the correct debug
guard. Its immutable failure capture remains in mesh-kit-private lab/. Precode
is `spec/native-receiver-observer-variant-design.md` at mesh-kit-private 80d5ec0.

All eleven existing checks now live byte-identically in `src/testDebug` and
execute against the debug variant. Two committed `src/testRelease` controls
assert the actual false BuildConfig flag, reject registration in every phase,
retain no observation token and preserve the real seal boundary's original
missing-provider failure. Neither suite skips/ignores cases or changes the
production flag. The probe, seal wrapper and Android cold fixture are unchanged.

Both hosted unit-test commands, all nine jobs and every existing 162 ordinary
check/48-group and 13+5 process-death driver remain. This is a test-selection
repair, not a receiver mutation fix. Actual caller attribution, all 55 pending
rows/five reporting controls/five complete observer captures and exact final
head qualification still precede merge. Local explicit JVM flags cannot prove
Gradle BuildConfig, AndroidKeyStore, lint, APK or emulator behaviour.

All capacity, migration, admission, transport, receipt, sync/airtime and physical
requirements remain. No radio/MQTT/group/firmware is changed; never flash M4 or
open its T-Display. Refresh main before final merge.

# Mixed browser / Android invitation development lab

`MixedInvitationAppTest` is a test-APK-only bridge over the installed app owners:
`KithMootApplication.vmlsBoxes`, `EngineCore`, `RoomViewModel.vmlsSigner`,
and the ordinary account/rendezvous protected stores. It does not seed those
stores, construct another MLS runtime, implement a witness, or supply a signer.
Sign-in uses the installed Cambium NIP-55 app. A synthetic Heartwood endpoint
can supply the typed encrypted provisioning ceremony; this is not hardware acceptance.

Build with JDK 21 and the unchanged reviewed native preparation scripts:

```sh
./gradlew -Pkithmoot.experimentalInvitationMls=true :app:assembleDebug :app:assembleDebugAndroidTest
```

Use only the dedicated fresh emulator approved for the lab. Do not use the
existing emulator or physical phones. Device installation and process restart
belong to the controller operator; this test performs neither. Do not clear
application state or logcat.

Pass only the non-secret loopback URL as `mixed_control` and one bounded action
name as `mixed_step` to `dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest`.
The private controller accepts POST `/android/step` with `{step}` and responds
with that step's private arguments. The bridge posts `{step,ok,result}` to
`/android/result`. Both endpoint responses must be JSON objects, with no redirects.
Use an explicit `adb -s` target and reverse the controller port to Android loopback.

The bridge never approves consent. Keep the actual app UI visible and inspect
the exact expected operation before approving its prompts. Ordinary vault
consent renders globally. Invitation consent renders on the VMLS Boxes page
inside the existing experimental controls. Cambium owns signer approval UI.
A new instrumentation invocation launches MainActivity, so navigate to that
page again when an invitation signing step waits.

`tls-check` runs before sign-in and uses Android system trust plus HTTPS
hostname checking for `link-lab.forgesworn.dev` over the reversed local port
28444. It changes no trust configuration and accepts no custom trust manager.

`signer-pair` runs before app sign-in. Its private args are `bunkerURI`,
`expectedPersona` (public hex) and independently supplied `expectedNpub`.
It uses Android UiAutomation resource-ID actions on Cambium's actual
`bunkerInput` and ordinary setup `pairButton`, then verifies its normal
`pairingNpubValue` public display. The helper first verifies that the new URI
is present, then waits for Cambium itself to clear it after a successful
pairing write, re-enable Pair, and finish without progress or error. An old
matching identity row cannot satisfy this new setup completion check.
It never dumps the UI, copies the URI to
clipboard, approves consent, or reads signer stores. The URI field is cleared
in `finally`; do not take screenshots or UI dumps while setup is pending.

Actions: `sign-in`, `provision` (`index`), `witness-prepare`, `witness-pair`
(`code`), `witness-genesis`, `witness-check`, `node-pair` (`code`, `box`),
`guest-home-pair` (`code`, returns app MLS device), `node-check` (`box`),
`target`, `create` (`box`, `name`), `issue` (`session`, `persona`, `device`,
`rendezvous`, `leafHome`, `expiresAt`), `review`/`accept` (`inviter`,
`authorization`, `name` for accept), `response` (`session`), `import-response`
and `registration-request` (`session`, `authorization`, `response`),
`registration-review`/`register` (`request`), `receipt-add` (`session`,
`authorization`, `response`, `receipt`), `remove` (`session`, `leaf`),
`send`/`receive` (`session`, `body`), `drive`, `snapshot`, `restart-check`
(optional `rounds`, 1 through 30). Rejoin uses a new issue/accept ceremony.

Only the precise capability/request/receipt required for the next ceremony
may leave the app on the private in-memory controller connection. Never print
or persist those values. Public export receipts contain booleans, counts,
state and digests only. Snapshot message entries expose SHA-256 and direction,
never bodies. A `restart-check` reads the existing app stores; the operator
must separately restart the dedicated emulator app process to prove restart.
A completed build is not executed interoperability, physical-device acceptance,
production activation, independent review, or permission to merge/publish.

For a nonkeeper guest use `guest-home-pair`, never `node-pair`. The actual
experimental product control calls the same gated runtime entry: it enrols
the real app device and pairs its Link route without publishing a grant.
The independent keeper must authorize that public device through its normal
storage-grant ceremony. Only then does `node-check` confirm VMLS access.

`configure-relays` calls the normal Settings APIs: `setProfilesEnabled(false)`
then `saveAccountRelays` with only `wss://link-lab.forgesworn.dev:28444`, enabled
for reading and writing. It checks the resulting public settings state.
There is no separate NIP-05 resolver toggle in this source; the UI describes
NIP-05 as self-reported. This action is not a network fence: other owners,
including the VMLS directory UI, can independently add public profile relays.

`provision-target` returns only `{targetDevice}`: the public key of the current
real account's protected provision transport, with the actual app signer and
account checked before and after. It does not initiate provisioning, change
stores, expose a scalar, or alter the typed provision/Keystore guards.

Setup-only `signer-pair` and `tls-check` do not launch KithMoot MainActivity.
The actual MainActivity scenario opens lazily only for actions that need the
real RoomViewModel, then closes after that test. This prevents an unrelated
account-sync decrypt prompt from covering Cambium's setup screen. The original
covered pairing timeout and its refusal receipt remain recorded separately.

Directory boundary checked in this exact source: enabling preview automatically
sets request-directory candidates in memory (selected read relays plus public
profile relays). That setter performs no I/O, and foreground request rounds
perform no unsolicited directory/inbox read. Public directory traffic requires
the existing explicit public check/send UI action; the current private journey
does not invoke it. There is no product directory behavior change in this lab.

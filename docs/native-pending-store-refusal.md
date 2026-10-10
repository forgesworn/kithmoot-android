# Pending replacement: cold store refusal matrix

Implementation follows mesh-kit-private precode **7043883**,
`spec/native-replacement-pending-store-refusal.md`. This is an acceptance fixture
and guarded driver; production storage and entry behaviour are unchanged.

The driver invokes the existing `NativeReplacementRestartTest#a_prepare` for
each of the five actual source/index write-return windows. It waits for the
complete checkpoint bundle, verifies the live emulator PID, issues external
SIGKILL, confirms death and invokes `#b_refuse_pending_stores` in a different
process. It does not substitute for `#b_recover`: all thirteen existing positive
recovery profiles remain required independently.

Each new process checks the checkpoint's actual source/index digests, traffic,
three devices, previous/proposed generations, original retirement/welcome,
cached old answer, prior spends, both receivers and marked courier. It retains
bounded ciphertext copies only in memory. No AndroidKeyStore key is exported.
It measures eleven faults against that exact stage: source, receiver, courier
and index each missing or ciphertext-corrupt, followed by three valid encrypted
indexes with unrelated owner/device, route/pins or invitation/reference.
`SavedRoom.decode` must accept the hostile index's structure before it is written
with the real `EncryptedRoomStorage`; repository save guards remain intact.

Every attempt creates fresh actual repository, receiver, source and courier
readers and invokes `NativeKeeperEntry.openForRoom` with
`NativeKeeperVault.openForEntry`. Missing/corrupt index also fails an actual
repository read before selection. All other cases exercise a fresh foreground
ViewModel. Refusal must precede radios, subscriptions, offers or relay requests,
preserve exact post-fault file/key inventory and bytes, release both source and
courier leases and leave every unaffected source field unchanged. The rollback
resistant receiver retains its real per-version key; damage neither resets it
nor reconstructs it from source history.

Only after each reader/foreground owner closes does the next fault restore the
identical stage ciphertexts. These are eleven faults on one actual killed
transaction, not eleven independent process deaths or rollback resistance.
Five separate staged transactions are required for the complete 55-row matrix.

Rows reach the actual instrumentation result stream only after all assertions
and NonCancellable stage-wide cleanup succeed. Cleanup restores this disposable
fixture's originals solely to locate/delete its stores, removes its source,
courier, peer, checkpoint, receiver/history files and aliases, removes the saved
room and receiver entry, and wipes retained ciphertexts even on failure. The
guarded driver additionally force-stops and clears the disposable app profile
on success or failure. The fixture refuses to reset a shared receiver profile.

Run only on an explicitly selected disposable emulator with the built debug
app and instrumentation APK installed:

```sh
python3 scripts/check-native-pending-store-refusal-emulator.py committed-source-before-offer
python3 scripts/check-native-pending-store-refusal-emulator.py charged-original-before-offer
python3 scripts/check-native-pending-store-refusal-emulator.py offered-before-index
python3 scripts/check-native-pending-store-refusal-emulator.py index-committed-before-source-acknowledgement
python3 scripts/check-native-pending-store-refusal-emulator.py reference-installed-before-subscription-switch
```

`ANDROID_SERIAL` must name an emulator and `ANDROID_HOME` its SDK. Physical
devices are refused. Existing preparation/death/recovery bounds remain intact.

Local fresh instrumentation compilation and generated driver/row checks are
separate evidence. They do not measure a process death or any real matrix row.
Hosted workflow sizing/sharding, exact-head collector integration and the full
changed-head base gate plus five new profiles remain pending. The sharing-read
candidate's own workflow remains unchanged and cannot qualify this successor.
No radio, firmware, channel, MQTT setting, public relay or personal signer is
part of this work.

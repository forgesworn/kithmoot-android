# Serialize same-path encrypted storage transactions

The unchanged five-case platform fixture at ancestor `8d8d4885` executes on
API 35 ARM64: the raw platform and unrelated-path controls pass; same-path
read, write and reset enter while another instance has a staged transaction,
causing actual writer errors. The complete red capture is hash-bound in
mesh-kit-private `lab/hybrid-room/native-atomic-overlap-platform-red.json`,
published before this change in `6bc2cd1`. Two earlier pre-fixture startup
failures remain retained. The design precedes the reproducer and correction.

`EncryptedRoomStorage` now strongly owns one monitor per canonical vault path.
A synchronized registry holds weak values and prunes cleared entries on lookup.
Separate instances of one path share that monitor; unrelated paths have their
own. Each entire read, write and reset runs under it, including key operations,
authentication, staging/finish/failure cleanup, strict commit verification and
deletion verification. No existing assertion, fixture or deadline changes.

This is process-local exclusion. It does not provide cross-process locks or
multi-file repository transactions, and the controlled reproducer does not
attribute the earlier hosted failures. Missing-key/index refusal stays intact.

The successor branch has its own nine-job workflow trigger. Require the
unchanged five-case genuine platform green run, all existing ordinary cases and
process-death controls, complete debug/release reports and all nine exact-head
CI jobs before qualification. New upstream main `763a1c52` is still a separate
integration and requalification requirement before merge. No hardware, MQTT,
group/public messaging, firmware or lab-key operation is included.

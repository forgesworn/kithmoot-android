# Android 0.5.9 discovery preview

Version 0.5.9 (17) adds explicit box-status checks to the contact book and
retains the 0.5.8 shared-work features. A contact card never starts a box
lookup on its own. Check box status asks for confirmation; Stop and Forget
close the reads, and expiry, retirement or lost live history remove current
attribution. Link transport hints are not message endpoints.

The actual Bothy relay exposed a normal completion case: an exact claim
lookup can receive EOSE followed by CLOSED. Both clients now allow that
completed immutable lookup to finish while keeping status and keeper-claim
history live. Closures before EOSE and lost live histories still invalidate
trust. Native reader tests reproduce the failure before the fix, including
multiple relays, and the composed signed-discovery test exercises it too.

This remains a debug-signed preview, with the existing preview update
identity. It is not a store release or physical-device acceptance. Public
drops remain disabled pending their joint contract and flood-bound gate;
this candidate does not enable a box service. Publish only after the exact
candidate's build/lint, unit and emulator checks pass, and verify APK version,
hash and certificate before replacing the preview download.

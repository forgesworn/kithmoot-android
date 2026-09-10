# Choosing a self-hosted site

Android 0.5.10 (18) adds **Site settings** on the home screen. A person can
choose a trusted HTTPS origin for room invitations, pairing links, contact
cards and the return page after browser Signet sign-in. Saved rooms retain
their exact invitation payload when shared from that site. NIP-46 invitation
metadata and its callback use the same captured origin; a running sign-in
cannot have its site changed underneath it.

The setting accepts origins only, rejecting HTTP, user information, paths,
queries, fragments and invalid ports. A valid choice is saved before it
becomes active. Cancel and invalid input keep the previous choice. The site
must serve the normal KithMoot `/j/` app and `/signet/` return page. No
connectivity probe or implicit trust is granted by saving an address.

The existing default stays available. Android's manifest deliberately does
not auto-verify invitation links. A link to another host can be opened in
its browser app or pasted into the native app; this setting does not claim
OS-level verified links for arbitrary hosts.

Validation: address/Signet/pairing unit tests pass. A fresh disposable API 35
emulator exercised the real setting screen, invalid-input refusal, a saved
choice across a forced process restart, an offline saved-room reopen, and
pairing-link creation from the chosen site. The production recovery script
now runs those two cases. Full hosted candidate CI is required before this
preview is published. These checks are not a physical-device sign-in test.

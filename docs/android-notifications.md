# Android notifications, 16 September 2026

Version 0.6.3 (26). Account menu -> Notifications & sound. Device-local opt-in; Zen bell preview; message previews off by default; Android settings link. Native notification number reflects unread messages in the current joined room. Android launcher decides whether to display a dot or number.

Scope: only verified live messages in the joined room while connected. No other-room watcher, background service, or closed/suspended-app push was added. History and own messages do not alert; edits do not ring; retractions remove unread counts; reading at the end of chat clears notifications. Calls suppress sound. Lock-screen public version is generic; expanded private previews require explicit opt-in. Room IDs in notification intents only open locally saved rooms.

Validation: 394 unit tests pass, release lint passes, debug/release APKs build. Two native Android API 35 tests pass for channel sound URI/badges, privacy defaults, counts, reading, disabled alerts and silent call alerts. First emulator invocation hit a startup ANR; after boot settled the rerun passed. No physical phone or audible bell acceptance claimed.

Unsigned candidate SHA-256: ec90c9d72fddd55edce21f663a44959d67e718e94888676061b2055b9691d526. Staged on M4 at ~/kithmoot-notifications-20260916. sign.sh verifies the exact candidate and existing production certificate, and prompts locally for the keystore password. Not yet signed, installed or published. Public website remains 0.6.2 until signed update verification.

Shipment reconciliation: the above 0.6.3 candidate was superseded before signing because main already allocated code 26. The merged-source release is 0.6.4 (27), retaining rendezvous and signer setup from main; a new unsigned artifact and signing step are required.

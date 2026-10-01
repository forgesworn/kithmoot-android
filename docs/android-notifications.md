# Android notifications, 16 September 2026

## Messages as they are sent, 1 October 2026

Message notifications now work like a phone's own messages:

- **On by default, previews included.** Message notifications, previews and "Receive messages when KithMoot is closed" all default to on. Settings already saved on a device keep their saved value, so a phone where previews were left off still needs that one switch turned on. The lock screen still sees only "New message" unless Android is set to show private content.
- **From the background service.** A saved room watched while KithMoot is closed now shows each new message as it arrives. The service already decrypted and verified the message to count it, so a notification is now posted from that same message. Only messages the inbox counts (someone else's, new, not an edit, reaction, retraction or invitation) notify. The text goes into Android's notification when previews are on, is otherwise held in the service's memory, and is never written to storage: `BackgroundInbox` still stores none.
- **One notification per room**, in Android's conversation style: the latest six messages with their senders. A two-person conversation is titled by its sender, any other room by its name. Each room's notification is tagged with its id and its tap intent carries the id in its data, so rooms neither replace each other nor share a tap target. Opening a room clears its notification.
- **Heads-up.** Messages use a new channel, `chat_messages_v2`, at high importance, because a channel's importance cannot be raised after creation. The old `chat_zen_v1` channel is deleted, with any sound or importance a person had set on it.
- **Asked for once.** On Android 13 and later the notification permission is asked for the first time a room opens, with an explanation first, then the battery-optimisation exemption. Without the exemption Android suspends the background connection soon after the screen goes off.
- **Tapping goes to the conversation.** It opens the room on its Chat tab at the latest message. If a different room is open it is left first. If that room is on a call, the notified room opens beside the call instead.

Not yet done: a Reply action on the notification itself.

Not checked on a phone: the permission and battery prompts, heads-up display, how quickly a message arrives with the screen off, and the tap from a cold start.


Version 0.6.3 (26). Account menu -> Notifications & sound. Device-local opt-in; Zen bell preview; message previews off by default; Android settings link. Native notification number reflects unread messages in the current joined room. Android launcher decides whether to display a dot or number.

Scope: only verified live messages in the joined room while connected. No other-room watcher, background service, or closed/suspended-app push was added. History and own messages do not alert; edits do not ring; retractions remove unread counts; reading at the end of chat clears notifications. Calls suppress sound. Lock-screen public version is generic; expanded private previews require explicit opt-in. Room IDs in notification intents only open locally saved rooms.

Validation: 394 unit tests pass, release lint passes, debug/release APKs build. Two native Android API 35 tests pass for channel sound URI/badges, privacy defaults, counts, reading, disabled alerts and silent call alerts. First emulator invocation hit a startup ANR; after boot settled the rerun passed. No physical phone or audible bell acceptance claimed.

Unsigned candidate SHA-256: ec90c9d72fddd55edce21f663a44959d67e718e94888676061b2055b9691d526. Staged on M4 at ~/kithmoot-notifications-20260916. sign.sh verifies the exact candidate and existing production certificate, and prompts locally for the keystore password. Not yet signed, installed or published. Public website remains 0.6.2 until signed update verification.

Shipment reconciliation: the above 0.6.3 candidate was superseded before signing because main already allocated code 26. The merged-source release is 0.6.4 (27), retaining rendezvous and signer setup from main; a new unsigned artifact and signing step are required.

The reconciled 0.6.4 runtime passes 402 app and 210 protocol tests, debug/release lint and all builds. Its unsigned SHA-256 is 5596fb749d1e5a83327f4691a38c30952fa4cc8519d4414bd552ca5f4b799d3e. The owner-signed production APK is 113105447 bytes, SHA-256 124759cefe9c0eb028fdefc842d0b01f71a5987d1b29bf5cd45d08f44f2ee3fa. Its v3 certificate is the existing production certificate and its ZIP payload matches the unsigned candidate byte-for-byte. Follow-up changes update recovery fixtures for Leave room, the account menu, relay editor and quiet schedule sheet; they do not change the signed runtime.

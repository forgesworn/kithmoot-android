# Encrypted chat reactions

A reaction is an optional `reaction` object inside the existing encrypted kind-1460 chat payload. It uses the same room or named-channel key, sender device signature, participant credential, admission checks, retention and rate limits as a message. No public kind-7 event, target tag or participant tag is published.

```json
{"messageId":"original-message-id","participant":"original-sender-64-character-hex-key","emoji":"❤️","active":true,"revision":1}
```

`messageId` is 1–128 characters. `participant` is a 64-character hexadecimal key, normalised to lower case. `emoji` is one of 👍 ❤️ 🤦 😂 🎉 👀 🙏 😢. `active` is a JSON boolean and `revision` is an integer from 1 to 2147483647. Invalid reactions are refused. A reaction cannot also carry a transcript/directive marker or attachments.

The envelope's authenticated participant is the person reacting; `reaction.participant` identifies the original message's sender. Reactions are matched only against loaded messages in the same conversation by both message ID and original sender. For each reacting participant, target and emoji, the greatest revision wins; ties use `sentAt`, then lexicographic message ID. All devices of the same participant share one vote. Removing a reaction publishes `active:false` with the next revision, preserving the update so an older add cannot resurrect it. Rapid toggles therefore work within the same second. Concurrent devices converge after receiving each other's updates.

The required text is a readable fallback, for example `Reacted ❤️ to message abc` or `Removed reaction ❤️ from message abc`. Older clients show that text as an ordinary message. Updated clients display counts beneath the target and omit updates from conversation search. Unknown or expired targets do not create a standalone reaction row. The existing 500-event history bound includes reaction updates.

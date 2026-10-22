# Your own call relay

Under **Settings → Connections → Call relay (TURN)** enter the relay
addresses, username and password supplied by your coturn server or TURN
provider. For example, `turn:relay.example:3478, turns:relay.example:5349`.
Use commas between addresses. Save, then leave and reopen the room.
Existing rooms and invitations work; creating a new room is unnecessary.

Set the relay on each device that needs it. Credentials stay in a
Keystore-encrypted, no-backup device store and never enter invitation links
or Nostr events. The configured relay replaces the default call-server
list, and its password is attached only to the addresses explicitly saved
with it. Settings can remove the stored credentials. An unreadable store
reports a call error rather than silently choosing another network route.

The web repository's `docs/private-call-relay.md` and
`deploy/private-call-relay/prepare.py` provide a private coturn setup with
separate accounts for you and your friend. Its minimal listener uses UDP
and TCP on 3478; networks restricted to TLS on 443 need a TLS listener and
valid certificate. Enter the `turns:` address when using one.

Validation of this change must distinguish JVM unit/build checks, browser
relay-only transport tests and physical GrapheneOS acceptance without
Google Play. Only the final one establishes phone acceptance.

The [22 October 2026 validation record](evidence/private-call-relay-20261022.json)
records 11 passing focused unit tests, instrumentation compilation, a debug
APK build and debug lint on the current-main worktree. It also records the
web client's relay-only media checks and the unavailable local WebKit
runtime. Hosting, deployment and physical device acceptance remain open.

The [private deployment record](evidence/private-call-relay-stall-20261022.json)
adds the subsequently deployed authenticated UDP relay on stall. Two outside
browser peers received decoded video and non-zero audio through relay-only
connections, and the wrong-password check was refused. The existing STUN
endpoint and retired public mint were checked afterwards. The client release
and physical GrapheneOS/friend call require their own acceptance.

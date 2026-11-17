# Call connection details

Call bar → More → Connection details separates this device's room messaging
node/relay configuration from the selected WebRTC media path to each caller's
device. A direct path may run over a VPN. A TURN path shows the selected local
relay hostname only when libwebrtc exposes its TURN URL. A remote-only relay's
server name stays unavailable. A hostname identifies a server, not its operator.
Room relay addresses are labelled as configured; their presence is not proof of
per-message delivery. The total connected relay count is shown separately.

Only the selected transport pair counts. Nominated backups and configured but
unused TURN servers never imply that media uses a relay. Missing or ambiguous
selections remain unavailable; disconnected peers show their last selected path.
Where exposed, UDP/TCP/TLS labels on TURN describe this device's leg to its relay,
not the whole path. Video frames, packet loss, traffic and round-trip measurements
are cumulative local observations, not a diagnosis or quality guarantee.

Opening the sheet samples stats; closing cancels the UI polling. Stats reads use
the engine's existing native-disposal guard and have a bounded timeout. No peer
IP, SDP, credentials, room capability or URL query reaches the sheet. No lookup,
export, persistent history, new server or room configuration change is involved.
The sheet cannot inspect the route between two other people in the room.

Review on standalone Android: join two disposable identities, open More →
Connection details, check each caller/device and the selected direct/TURN path,
then close and reopen. In a node-hosted room the room node must be shown
separately from selected media observations. Check missing stats and disconnected
peers remain honest, camera and microphone continue, enlarged text scrolls, and
rotation/dismissal ends the polling composition. Repeat with a physical GrapheneOS
phone without Google Play and the updated desktop client. JVM checks and APK
packaging do not establish physical-device or actual-call acceptance.

This follow-up adds observation, not a speculative Android video-recovery change.
The remaining real Android-to-desktop flash needs a live reproduction with the
actual devices and transport. Archipelago launch and embedded screens are unchanged.

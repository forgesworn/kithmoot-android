# Leave the call without leaving the room

Android exposed Leave room behind the back arrow and room details, but had no
separate hang-up action. Chat and Call now share a pinned red Leave call button
with a text label and a minimum 48 dp target. Leaving returns to Chat; Call then
offers an explicit Join call action. The back arrow is labelled Leave room.

The view model marks the call inactive before asynchronous teardown, closes
peer connections, releases local capture and audio routing, stops screen sharing,
and releases microphone/monitor role claims. Chat and the room session remain
open. Relay or epoch errors cannot prevent local hang-up. Media controls are
serialized so an in-flight permission result cannot restart capture after leave.

The engine suppresses roster-driven reconnection while inactive. Rejoining
reconciles the current roster but does not restart local capture. A pending media
engine also respects a leave action that occurred during startup.

Acceptance uses a disposable emulator and synthetic room identities. UI coverage
checks leave from Chat and Call, return to Chat, and explicit rejoin without
invoking Leave room. Native engine coverage checks microphone release, empty
peer/track state, restored audio mode, a new arrival while inactive, continued
chat delivery, and explicit reconnection without restarting the microphone.

Validation: 391 JVM tests, release build and lint passed. Three emulator tests
passed in 11.788 seconds. The leave control screenshot was visually checked.
Unsigned candidate: `40a424c55b4df2d8c4222302fc3692307780e66ca3b99b78ed65fe3ae7eee13b`. Awaiting local production signing
and Pixel installation; no physical Pixel acceptance claimed yet.

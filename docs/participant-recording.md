# Participant recording

Anyone on a group or private call may record locally. A device signs its own
complete recording warning, binding the recording ID, layout, participant,
device and call. Roster authentication and admission are checked before the
warning is adopted. Room moderation still requires the room authority key.

Profile 4 must be supported by every device currently on the call. An idle
older device does not block capture. The room creator can use the legacy
authority notice in normal rooms with older clients; quiet rooms require
compatible live-presence warnings. A guarded exact relay acknowledgement
precedes capture. A key, call, room or compatibility change stops capture.

Warnings are independent per call and device, so stopping one recording does
not clear another. An already signed device-private Off is retained before On
publication. Process restart or failed publication can retry that exact Off,
including after leaving the call. No secret is stored in the stop journal.

Clips remain local until an explicit Save or Share. Unit tests cover two
independent recorders without owner keys, failed-start recovery and older
devices. The JVM independently verifies the TypeScript-signed profile 4
fixtures for all layouts and Off. The emulator capture suite includes the
actual non-owner Compose controls and AAC export. Android's dock keeps a live
gallery of the original call above another chat or the rooms list, using the
same video tracks and audio engine. Only the explicit Hide gallery control
collapses it. Physical phone acceptance
and production publication are separate checks.

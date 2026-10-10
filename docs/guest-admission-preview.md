# Native guest admission preview

A new temporary-room invitation opens a local preview before requesting
entry. It shows the invitation's unverified room label and an editable name.
Opening the preview does not request admission or join a call. Saved rooms,
persistent invitations and nearby pairing retain their existing entry paths.

Press **Request to join** to send one signed request. Repeated taps cannot
start another attempt. The screen distinguishes signer confirmation, sending,
relay-acknowledged waiting and reconnecting. A relay receipt does not imply
that a host is present or has admitted the guest. Retries reuse the same
signed event and do not flash back to Sending after acknowledgement.

**Cancel request** closes the entry operation and its reply collector. A late
grant cannot revive a cancelled attempt. Authenticated refusal, retirement,
room expiry and unanswered requests have distinct feedback. Reviewing and
retrying keeps the entered name and requires another deliberate request.
Cancellation cannot retract a request already written to a relay.

## Optional local device checks

**Preview camera** uses an independently owned CameraX preview; **Check
microphone** measures local microphone levels without recording or sending
sound. Neither starts the room's call engine. Each asks for its own permission.
Checks stop before requesting entry, closing the invitation, backgrounding
or disposing the screen. Late permission/provider callbacks cannot revive
stopped inputs. An existing active call disables these checks so its inputs
are not disturbed. Failure or refusal of a device check does not prevent
requesting admission.

## Entry ownership

`GuestAdmissionGate` holds the private invitation and exposes only the room
label, entered name and phase. Replacement, cancellation, refusal, expiry and
clear retire the attempt. An admitted attempt retains its guard until entry
commits. Publication checks account ownership, operation lifetime and bounded
invitation expiry at each relay socket write, including when a preceding
write's callback withdraws authority. Invalid or cancelled grant secrets are
wiped before room entry.

## Qualification boundary

The foundation passed 417 protocol tests and 1,949 app tests in each debug
and release variant, with no failures or skips. Both lint variants and both
APK builds passed. The focused 52 transport/request cases and six gate cases
use JVM fakes and synthetic events.

New instrumented fixtures exercise the actual entry ViewModel and loopback
WebSockets with signed host replies. They cover no initial relay request,
immutable coalescing, acknowledgement-backed waiting, cancellation, refusal,
explicit retry and successful admission followed by leaving. Compose checks
cover permission races, device-check failure and call-input ownership.
Large-text whole-control bounds are a required screen check, not inferred
from a partly visible clickable control. All 24 combined emulator cases passed with no failures or skips, including
light and dark screens at 1.6 times font size. Full APK bytes were checked
after a normal update using the existing M1 debug key. The window metrics
exclude system bars and cutouts from the scroll viewport, keeping entire
request and close controls inside the physical display. Both final lint
variants and the final release APK build also passed. Publication remains
pending.

Physical camera/microphone capture, permission denial, keyboard and lifecycle
behaviour, background hosts, expired links, anonymous creation and unfamiliar
users remain separate acceptance gates. This work does not implement native
call recording/export or close G17. No new production APK is published by the
preview work.

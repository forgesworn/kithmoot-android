# Native closed-room artwork catch-up

Installed Android checks use real room entry, WebSocket subscriptions, native
signatures and encrypted device storage. A waiting guest makes no request to
the room's artwork/control channel, sees no room logo and receives no saved
room. After an authentic admission grant, the current encrypted control history
supplies the room logo without adding an ordinary chat message or joining a call.

A removal published while the guest waits wins over both stored and delayed
older artwork after admission. Another check leaves the admitted room, clears
its process-local artwork cache, replaces the ViewModel and reopens the saved
room with artwork history withheld. The accepted logo recovers from the
encrypted saved record. The invite in this fixture requires a host grant; the
underlying room has no expiry and is eligible for saved-room recovery.

The six-case `GuestAdmissionEntryTest` batch passed on the owned API 35 ARM
emulator at 1080x2400 and 420 dpi with animations disabled. Accounts, rooms and
relay history are synthetic. This proves native admission gating and local
cache restoration, rather than a physical-phone, cross-client replacement-device
or forced-process-restart journey. Those remaining G15/G17 gates stay open.
No production APK or store publication is claimed by these checks.

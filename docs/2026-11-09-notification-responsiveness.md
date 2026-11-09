# Chat visibility and notification responsiveness

Opening or leaving the chat, foreground changes and call-state changes used to
call `ChatNotifications.refresh()` synchronously from the UI. That method
resolved the complete accepted conversation again while holding the
notification monitor. A retained-history traversal or a slow notification
service could therefore stall those UI callbacks.

The notification tracker now resolves messages when `accept` receives a new
chat snapshot. Visibility and settings refreshes reuse that accepted state,
including edits, retractions, unread counts and the read-through transition.
The model requests refreshes through one background worker with one conflated
pending request. Notification failures permit a later retry; cancellation
stops pending work. No room, signer, storage format, relay choice or authority
changes, remote diagnostics or additional permission are introduced.

A real emulator regression supplies 2,000 retained messages and changes
foreground, reading and call flags 20 times. Baseline visits were 42,000; the
repair visits only the original 2,000. All three existing/new notification
instrumentation cases pass, as do 49 notification JVM tests. The worker test
holds processing while 10,000 visibility requests return, then observes just
one pending refresh using the latest state. Read/unread, edits, retraction,
post failure and cancellation checks remain explicit.

The physical Pixel remained on 0.6.140 during read-only diagnostics. Its
reported freezes did not reproduce: the captured system report had no ANR
since boot and no process crash in the captured log. This repair removes a
proved blocking path; it does not establish the cause of every reported
freeze or physical GrapheneOS acceptance.

Device acceptance: open an existing large conversation, switch repeatedly
between chat/call and the room list, background/resume, then verify that
typing, room switching and notification read/unread behaviour remain responsive.
Keep another desktop and web client connected. No reset, replacement room or
sign-out is part of that check.

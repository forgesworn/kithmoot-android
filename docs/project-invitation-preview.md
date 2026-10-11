# Native persistent project invitation preview

Opening an external or pasted persistent room invitation now shows a review
screen when the signed-in account already holds the exact link in a sole,
joined and current project. It uses that verified directory's room name,
project name and inline artwork, and shows the actual account name and a
selectable npub. The account is read-only; a temporary guest name cannot
replace it. Unknown or ambiguous persistent links retain ordinary room entry.

Reviewing does not query the invitation, create membership, save a room or
start a call. Only **Open room** starts the lookup. Optional camera and
microphone checks stay on the device and stop before opening. **Open room**
and **Close invitation** remain visible below the scrolling review content.
The logo labels the project and does not establish admission authority.

The attempt captures the exact account session, project and expected room.
Entry rechecks that context after asynchronous lookup and credential signing,
and at the room commit boundary. The authenticated room id and participant
must match the review. Withdrawal cannot substitute another project that holds
the same link. An account or project change requires fresh review. Cancel
retires the attempt; a late response cannot save or enter the room. Retry first
returns to review and requires another explicit **Open room** action.

Temporary guest admission retains its sending, waiting, authenticated decline,
cancel and deliberate retry behaviour. Persistent project entry instead shows
**Opening your room** and **Cancel opening** without claiming a host request.

The installed journey uses actual Android VIEW intents, Compose controls,
WebSocket relay traffic, native cryptography and encrypted Android storage,
with synthetic accounts. Cases cover no-fetch preview, a held lookup, cancel
and late response, deliberate retry, account replacement, authenticated room
mismatch and withdrawal during lookup. ActivityScenario's launcher intent is
restored only during fixture teardown: the app correctly retains the external
VIEW intent, which otherwise causes the test rule to ignore destruction events.

The [qualification receipt](evidence/project-invitation-preview-20261011.json)
records the passing JVM/build checks and complete 40-case installed run,
including large-text controls in both themes and the preserved earlier failures.

The [closed-room artwork checks](closed-room-logo-catchup.md) also qualify
post-admission history, removal and encrypted local cache restoration.

Implementation, automated acceptance and published availability are separate.
This change does not publish a production APK or complete G15/G17. Independent browser/native recovery, forced-process-restart logo recovery, physical-phone device
checks and unfamiliar guest/host acceptance remain open.

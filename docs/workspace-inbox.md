# Native Inbox and Work

Inbox and All work open from home and the room header. They read canonical
assignment journals and recent authenticated chat from rooms already opened
as the current Nostr account. Cards name their room and project, filter by
project and open the exact originating task or author-bound message. The room
remains the place to answer questions, accept results or manage work.

The full-screen panel keeps the current conversation composed, preserving its
draft. Opening another room during a call uses the existing chat-only visitor
and call dock. Projects in that visitor read the call instance's authenticated
directory; they do not open a second directory writer. Locked calls and
anonymous room mode do not open workspace observation.

## Reading and lifecycle

`SavedRoom.workspaceAdmission` checks an existing account/device credential at
its issuance, plus the current account, local opening marker and retained room
capability. Expiry does not renew signing permission. Bookmarks or project
membership alone cannot admit a reader. Temporary, ended, anonymous and quiet
rooms are excluded. Nearby-only and sheltered Bothy routes offer an explicit
origin-room check rather than a public relay fallback.

`WorkspaceActivityReader` has no identity or writable assignment store. Its
transport rejects publication and the underlying pool has no write relays. It
loads the origin's existing `AssignmentVault` through a load-only source, with
128 recent envelopes over 24 hours, bounded initial replay, authenticated
roster labels and live updates. Retained authority rekeys are checked separately;
best-effort chat history never establishes admission. Every assignment reader
snapshot remains `historyComplete = false`.

An account/device change, forgetting, changed policy or traffic epoch drops
activity. A new authority epoch requires reopening the origin for its ordinary
recovery process. An authenticated closing rekey drops the cards; a
self-destructing closure uses the existing terminal epoch journal and room
cleanup. Closing or backgrounding the panel cancels its readers and queries,
clears the exposed state and wipes the readers' key copies.

Inbox resolves author-bound edits, retractions and nested replies before
selecting human mentions and replies. Known agents stay out of human attention,
including authenticated agent labels from the project's directory while they
are offline. Origin `BackgroundInboxVault` receipts determine reading state;
opening the panel never marks messages read or writes task journals. Uncertain
assignment sends must be checked and retried in their origin.

## Evidence and remaining work

The [10 October source and emulator receipt](evidence/workspace-inbox-2026-10-10.json)
records exact source hashes and separates automated checks from publication
and physical acceptance.

Twelve focused JVM tests cover canonical cached/live work, attention projection,
author-bound modifications, read receipts, account/device admission, expired
credentials, cancelled queries, forged authority events and signed closure.
The full app suite contains 1,725 tests; the independent protocol suite contains
392. Five emulator UI fixtures cover filters, exact routes, draft/media
preservation, sign-out, the selected task's canonical head and author-bound
message IDs, including repeated navigation to the same message.

The installed-app loopback journey uses the actual view model, Android
Keystore-backed origin vaults and native crypto. Three admitted fixture rooms
load causal assignment chains and live messages; an unopened fourth room has
no activity query. Observation neither publishes chat/presence nor changes
origin journals/read positions. The actual Inbox opens a task, the room signs
an answer against its exact blocked head, and sign-out clears activity. Saved
admissions and workers in this journey are synthetic.

This does not complete G9. Physical-phone acceptance, a combined signed-project
and retained-call journey, fresh-device summary delivery, older relay history,
sheltered/quiet route coordination, representative performance/battery
measurements and live remote execution remain open. These source changes also
need a separately reviewed, signed and published APK before installed users
receive them.

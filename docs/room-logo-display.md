# Private room logo display

When an admitted native session receives a room logo control, its open-room
header and saved-room row show the validated image. Explicit removal returns
to initials; names and unread counts remain visible and accessible. Anonymous,
quiet and direct conversations retain their existing identity treatment.
This is one part of G15, not the complete room/project logo journey.

## Retention and ordering

Ordinary persistent rooms cache the accepted record and epoch inside the
existing encrypted saved-room store. There are at most 64 persisted images;
eviction removes optional artwork, never a room or its admission capability.
Malformed optional cache fields fall back to initials without preventing
membership recovery. The cache strips claimed authorship because it is not
an independently signed identity statement.

Temporary room images stay in a bounded process-memory cache shared by the
chat and call ViewModels. They do not enter the saved-room JSON. Learning an
end time or self-destruction also strips older persisted artwork. Expiry,
forgetting a room, storage reset and changed saved identities clear the
applicable memory entry. The cache does not change activity timestamps or
unread state.

Callbacks capture the metadata follower's accepted epoch rather than reading
the session during its rekey transition. A stale callback cannot replace a
newer record, removal or epoch observed by another view. Opening validates a
cached seed against known rekeys before presenting it. Closed or replaced
sessions cannot adopt delayed metadata writes.

## Local rendering

Compose decodes bounded PNG/WebP bytes off the UI thread, checking the image
hash, native decoded dimensions and visible pixels. Invalid or fully
transparent images keep initials. Replacement clears the old image before
decoding; cancelling a decode disposes any bitmap not transferred to the
renderer. No remote image loader or public URL is introduced.

The decorative thumbnail contributes no extra spoken name: the neighbouring
room name and row action supply that. Both light and dark themes retain the
name when an image is missing, replaced or removed.

## Qualification and remaining work

`RoomLogoCacheTest` exercises saved-room representation, removal ordering,
malformed optional fields, learned expiry, eviction without losing admission,
identity-scoped memory and competing-view ordering. `RoomWorkLogoTest` also
checks callback epochs and invalidation when opening after rekey.

`RoomLogoUiTest` runs real Android decoders and Compose pixel checks on API 35:
PNG/WebP, broken pixels, transparency, replacement/removal in both themes,
accessible names, unread-row opening and rapid replacement/navigation.
Its five cases passed on an owned emulator. These are synthetic fixtures;
they do not establish physical phone acceptance or a published production APK.

The complete source passed 427 protocol tests, 1,968 debug app unit tests and
1,968 release app unit tests, with zero failures, errors or skipped cases.
Debug and release lint passed with no errors; their existing warnings remain.

The phone editor, project companion journal/inheritance, closed-room control
catch-up, admission and originating-call branding, fresh account recovery,
cross-client journeys and physical acceptance remain open. Android's public
release is unchanged by this implementation branch.

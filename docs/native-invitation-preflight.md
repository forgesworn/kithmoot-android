# Native invitation replacement: unsigned layout and source ownership

This implements prerequisites from the source storage design committed in
mesh-kit-private as **9c57b12**, after the full replacement design
**376d995/ad06819**. It does not implement a replacement transaction or schema 6.

`persistentInvitationEventBytes` measures the exact v3 body in UTF-8, applies
NIP-44's padded/base64 length, and measures the signed envelope's fixed-width
public fields, tags and timestamp. It shares the encoder's body spelling and
policy checks. Its placeholders never escape; it returns only an integer.
There is no encryption, key derivation, minting, random source or signature in
that preflight. The ordinary encoder still has random default arguments and
must not be used as a preflight call.

Native creation canonicalises the full relay policy, checks the timestamp and
decodeable safe-integer end, and measures the envelope against the source's
event bound before either minting source runs. The existing factory delegates
through that same policy gate to its real invitation/base entropy sources.
Failure after minting wipes the retained host buffers and any candidate base
buffer. These checks will also supply the unsigned welcome layout needed by
replacement's complete pending/completed file-capacity checks; those transaction
checks are still unimplemented.

The durable Record now owns its invitation bearer, exact welcome and welcome
delivery accounting together. KeeperMaterial holds only immutable base/root
signing material. Serialisation reads its candidate Record rather than a second
current invitation in KeeperMaterial. Private decoding buffers are tracked as
soon as they are allocated, so a later strict parse/signature error can wipe
them before the full Record exists. Normal close wipes retained private state.

Schema 4/5 retain their existing exact field contracts and write ordering. A cold
open does not write or migrate to 6. Existing welcome retries retain their signed
original, attempt/debt/backoff counters, invitation, phase and traffic epoch.
Approved chat, historical retirement originals and listener ownership retain
their existing guards. Generation history, pending replacement, conditional
source/index reconciliation, switching an existing owner's invitation listeners,
stale-generation refusal and rendered replacement controls remain required.

Validation compares real encrypted/signed envelopes with the unsigned count,
including the independent web fixture, eight end/destruct/relay combinations,
both sides of fifteen NIP-44 padding boundaries, maximum relay spelling and
native timestamp widths. Creation refusal checks observe zero host/base minting
calls for invalid policies; failure checks retain references to candidate buffers
and check their wiping. Actual source reopen checks keep the legacy encoding and
the same welcome original with conserved retry spending. The changed head still
needs its own four hosted jobs and complete 140-check/44-group recovery evidence,
six measured refusal rows and eight external process-kill/recovery drivers.

All local and hosted fixtures are software/emulator evidence. They do not prove
physical BLE/RF, MQTT/group delivery, participant receipts, measured device-wide
airtime or replacement's five process-kill windows. No radio configuration,
firmware change, public relay publication or M4 settings-reader operation is
authorised or performed by these changes.

Local validation freshly compiles both changed production files and four JVM
test files against immutable compiled dependencies. The final **181 checks/ten
classes** pass in **88.275 s** total (**13.541 s** JUnit), with all 64 checked
source hashes and read-only dependency hashes unchanged. All **59** actual signed
envelopes equal the unsigned count; the largest measured supported envelope is
**3,961 bytes**. Nine invalid creation policies each observe zero host/base
minting calls. The corrected terminal predecessor check precedes RoomEpoch
construction, whose source copies before checking its range. Invalid stored
predecessors preserve retained bytes and release the failed authority owner.
This is local qualification only; hosted Gradle/lint/APK/installed acceptance
for this changed head is still required.

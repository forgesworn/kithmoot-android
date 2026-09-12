# Sheltered relay authentication

This pins KithMoot's client side of Vennel G3-G5 against Android
`f208d3c`, Bothy Node `c20c98f`, and ForgeSworn Link `f127d18`. The frozen wire
and authority contract remains
`vennel/docs/gate/2026-09-10-g3-g4-integration-contracts.md`.

## Boundary

`RelayPool` gains an optional `RelayAuthenticatorProvider`, looked up by exact
canonical relay URL on every socket generation. An ordinary relay with no
authenticator remains application-ready at `RelaySocketListener.onOpen`, exactly
as it is now. A configured sheltered relay is only physically open then; it is
not included in `connected` and receives no `EVENT`, `REQ`, queued publish, or
query until authentication completes.

The provider returns an authenticator bound to one selected account and active
consent record. Its only asynchronous operation signs kind `22242` with empty
content and exactly these tags:

```text
["relay", canonicalUrl]
["challenge", challenge]
```

The signer result must retain the requested kind, time, tags, content and
selected account pubkey and have a valid signature. Existing
`ParticipantSigner.sign` already enforces this for local, NIP-55 and NIP-46
signers. Route material, room keys and grants do not enter the authenticator.

## Codec

`RelayMessage.Auth(challenge)` accepts only a two-element JSON array
`["AUTH", <string>]`. The challenge must contain 1 to 512 UTF-8 bytes and no
control character. Every malformed form remains `Unknown`; parsing never
throws. `RelayCodec.authFrame(event)` emits `["AUTH", <event>]`.

## Socket state

Each `RelayLink` owns a monotonically increasing socket generation and one of:

```text
connecting -> awaiting-challenge -> signing -> awaiting-ok -> ready
                                                 |
                                                 +-> blocked
any live state -> closed -> reconnect backoff
```

For an ordinary relay, physical open moves directly to `ready`. For a
sheltered relay, physical open moves to `awaiting-challenge`. A valid challenge
cancels any previous signing job, captures the pool generation, socket
generation, exact socket, exact URL, challenge, account pubkey and current
authenticator, then signs on the pool scope. A late result is sent only when
all captured values still match and the provider still returns authority for
the same account and URL.

Sending AUTH records its event id and moves to `awaiting-ok`. Only a matching
`OK true` moves the link to `ready`, re-sends live subscriptions, flushes the
bounded outbox and adds the URL to `connected`. Other `OK` frames continue to
serve normal publication receipts. A matching `OK false`, signer refusal,
signer cancellation, account change or consent withdrawal moves to `blocked`,
cancels work, closes the socket and suppresses automatic reconnect and signer
prompts. A public `retryAuthentication(url)` clears that exact blocked route
after deliberate product action.

Socket close cancels its signing job and makes every result from that
generation inert. `stop` advances the pool generation before cancelling links.
An AUTH frame on an ordinary relay is ignored as an additive protocol frame;
it cannot silently turn a public connection into a signer request.

## Proof

JVM fakes must show:

1. ordinary relays retain the existing open, publish and reconnect behaviour;
2. a sheltered physical open does not signal connected or release queued work;
3. the exact challenge and canonical URL are signed and AUTH is the first
   application frame;
4. only the matching `OK true` releases subscriptions and queued events;
5. `OK false` closes and blocks without a reconnect or second signing prompt;
6. retry is required before another socket is opened;
7. a replaced socket, newer challenge, account change, consent withdrawal and
   pool stop make a late signature inert;
8. hostile and oversized AUTH frames cannot call the signer or kill an
   otherwise valid public connection.

## Circle grant lifecycle

Connect Bothy now distinguishes the persistent DM creator from its guest. The
creator reads the other participant's fresh, signed kind `20461` roster on the
existing room relays, explicitly asks the selected NIP-55 signer for kinds
`22242` and `24242`, and signs an active and terminal revoked event for every
current guest device. Both exact events are encrypted in the consent vault
before publication. KithMoot publishes the active events only through the
keeper-authenticated paired Bothy route, requires `OK true`, and commits the
room's relay cutover afterwards. A guest requests kind `22242`, pairs its own
route, answers NIP-42, and publishes a fresh signed kind `20461` roster event
through Bothy. Its confirmed write proves the creator's grant matches this
exact persona, room, device and kind; only then does KithMoot switch the guest's
relay set.

The pre-signed revocation makes every crash boundary recoverable without
retaining an account secret. An interrupted creator install, explicit guest
revocation, or disconnect retries the exact signed event; Bothy `c20c98f`
treats an exact retained replay as an idempotent receipt without advancing its
authority generation. KithMoot retains the Link route until revocation is
confirmed. The creator can revoke guest access while keeping the room on Bothy;
the guest socket and subscriptions close under Bothy's authority generation.
Disconnect restores the prior relay set only after that cleanup. A guest who
did not issue the remote grant withdraws their local route; the creator remains
the authority that can revoke the remote grant before its expiry.

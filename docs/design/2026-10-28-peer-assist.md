# Peer assist on Android

Design only. No code changes accompany this document.

- Date: 28 October 2026
- Decision being served: owner decision of 28 October 2026 to bring the
  member-volunteer relay ("Help other people connect") to Android.
- Read against: web `forgesworn/kithmoot` origin/main at `61f3b6da`, Android
  `forgesworn/kithmoot-android` origin/main at `e660aa6e`.

## 1. Summary

ForgeSworn runs no call relay. When two people cannot connect directly
(strict NAT at both ends, many commercial VPNs, some carriers), web and
desktop can route the pair through a third room member who has opted in.
Android can neither be helped nor help, so a phone in such a pair gets no
media.

Reading the web code to design parity found that web peer assist is
complete for **signalling, selection and route ladder**, but the **media
plane is not wired in the shipped app**, and the encoded-frame protection
does **not** run on the assist path at all. "Parity" therefore cannot mean
"copy what web does": it means building the missing media plane once,
defined jointly for both platforms, and then bringing Android to it.

The plan in order:

1. Web prerequisites (Section 10, W1 to W3): correct two overclaims, wire the
   relay media plane in the app with a mutual-request rule and an explicit
   attribution field, and publish shared selection vectors.
2. Android being helped (A1 to A4): port selection and the roster offer,
   add the `assist` signal and the attribution field, add an assist step to
   the engine, and say in the call what is happening. No frame-layer
   dependency.
3. Android frame layer (A5): an owner decision between three options,
   because the pinned libwebrtc cannot run KithMoot's frame format.
4. Android helping (H0 to H4): gated on a custom libwebrtc build, because
   the stock Android SDK cannot forward encoded frames without decoding
   them.

## 2. What exists today

| Layer | Web / desktop | Android |
|---|---|---|
| Roster `assist` offer (encode, sanitise, decode) | Shipped: `src/types.ts` `AssistOffer`, `src/peer-assist.ts` `sanitiseAssistOffer`, `src/roster.ts` | Dropped on decode (`Roster.kt` has no field). Vectors `assist-offer` and `assist-offer-hostile` are vendored but skipped (`RosterEventVectorsTest` `NOT_MODELLED_YET = setOf("assist")`) |
| Deterministic choice | Shipped: `rankAssistants`, `selectAssistant` | None |
| `assist` signal (request, reply) | Shipped: `src/signal.ts`, `src/mesh.ts` `#requestAssist`, `#handleAssistRequest`, `#handleAssistReply` | `SignalBody` has no `assist` or `accept`. An `assist` signal reaches the link and is ignored by `Negotiation.applySignal` (`else -> Unit`) |
| Route ladder direct, assist, forwarder, TURN | Shipped: `src/mesh.ts` `#escalate` | No route ladder. ICE servers (STUN plus any relay) are given to every link at once; only the profile-2 health ladder (restart, rebuild) exists |
| Volunteer consent and UI | Shipped: `assistDecision`, `buildAssistOffer`, `app/index.html` `#assist`, `app/src/main.ts` `renderAssist`, `toggleAssist`, `pollAssist` | None |
| Reachability and uplink measurement | Shipped: `src/reachability.ts` `ReachabilityProbe`, `src/uplink.ts` `UplinkProbe` | None |
| Frame relay (encoded frames in, out, undecoded) | Library only: `src/peer-relay.ts` `FrameRelay`, `RelayPair`, `PeerRelay`. Proven by hand-driven pages in `test/peer-assist.spec.ts` and `test/relay-capability.spec.ts`. **Not wired in the app** (Section 3.1) | None, and not possible with the stock SDK (Section 3.3) |
| Encoded-frame protection | Shipped **for the server forwarder only**: `src/media-crypto.ts`, `app/src/forwarder-media.ts`, `src/mesh.ts` `#onForwardedTrack`, `#protectForwarderSender` | None |

## 3. Findings that shape the design

### 3.1 The web relay media plane is not wired

- `onRelayStart` and `onRelayStop` in `app/src/main.ts` only call
  `renderAssist()`. No code in `app/src`, `src/session.ts` or `src/mesh.ts`
  calls `RelayPair.leg`, `FrameRelay.consume` or `FrameRelay.emit`, or adds a
  clock track for a relayed pair.
- On `accept: true`, `Mesh.#handleAssistReply` marks the route connected at
  once ("the route is working the moment it says yes").
- `Mesh.#reconcile` then removes the far device from the endpoint set, so
  the direct peer to it is closed.

Consequence on web today: a pair that enters assist closes its direct peer,
receives no relayed media, is reported as connected, and never reaches the
TURN rung while the volunteer stays in the roster. It is rare in practice
because a volunteer must be on Chromium, measure itself `public` (no NAT)
and opt in, but it is a live defect and it would apply to any Android pair
that copied the web behaviour.

### 3.2 What the frame layer proves against a volunteer

`deriveMediaKey(roomKey, senderDevice)` is HKDF of the room key per sending
device. Its own comment says: every member holds the room key, so every
member can derive every other member's media key. `media-crypto.ts` contains
no signature with a device key.

So against a volunteer, who is a member:

- Confidentiality: none beyond what the volunteer already has. The volunteer
  receives the pair's media as an ordinary participant anyway; this part of
  the web reasoning is correct.
- Attribution: the AEAD tag detects a relabelled **unmodified** ciphertext.
  It does not stop a volunteer re-encrypting any media under any member's
  derived key. A malicious volunteer **can** present its own camera as
  another member's.

The web volunteer copy in `app/index.html` ("every picture and voice is
signed by whoever sent it, and a relabelled one is thrown out at the other
end") and the comments in `src/peer-relay.ts` and `src/types.ts` ("it
cannot forge one") overstate this. The brief's "signature checks" do not
exist. The real property for a relayed track is:

- each hop is protected by DTLS-SRTP between authenticated devices (the
  signal carrying the DTLS fingerprint is signed by the device key, see
  `docs/2026-10-26-call-privacy-and-one-click.md`);
- the far end attributes a relayed track to its source because the source's
  own signed roster advert names the track and the far end itself asked that
  volunteer to carry that source. This is a hint that an honest or confused
  volunteer cannot get wrong silently, and a malicious one can.

Stronger attribution needs per-sender signing (for example a device-key
signature over a hash chain of frames, checked at least on key frames). That
is new protocol work on both platforms and is listed as an owner decision,
not part of this plan.

Design consequence: Android being helped does **not** wait for a frame
layer. The frame layer adds no volunteer-specific property that hop
DTLS-SRTP plus roster attribution do not already give.

### 3.3 Encoded-frame support in the pinned libwebrtc

Pinned in `gradle/libs.versions.toml`: `webrtc = "144.7559.12"`, artefact
`io.github.webrtc-sdk:android`. Inspected from the cached AAR's
`classes.jar` (`javap`) and `jni/arm64-v8a/libjingle_peerconnection_so.so`
(`llvm-nm`):

- `org.webrtc.FrameCryptor`, `FrameCryptorFactory`,
  `FrameCryptorKeyProvider` exist.
  `FrameCryptorFactory.createFrameCryptorForRtpSender(factory, sender,
  participantId, algorithm, keyProvider)` and the receiver twin attach a
  built-in transformer.
- `FrameCryptorAlgorithm` has one value: `AES_GCM`.
  `FrameCryptorKeyDerivationAlgorithm` is `PBKDF2` or `HKDF`. The key
  provider takes a ratchet salt, window, magic bytes, failure tolerance and
  key-ring size, and keys per participant id or shared.
- `RtpSender.setFrameEncryptor(FrameEncryptor)` and
  `RtpReceiver.setFrameDecryptor(FrameDecryptor)` exist, but
  `FrameEncryptor` and `FrameDecryptor` are interfaces returning a native
  pointer only.
- There is no Java API for an arbitrary encoded-frame transformer (no
  insertable streams, no script transform). No class matching
  `Transformer`, `Insertable` or `EncodedFrame` beyond `EncodedImage`.
- The shared library exports 225 dynamic symbols, all `Java_*` plus
  `JNI_OnLoad`. No libwebrtc C++ symbol is exported.

What follows:

- KithMoot's frame format is ChaCha20-Poly1305 with a `[clear header][ct ||
  tag][IV 12][prefix length 1]` trailer. `FrameCryptor` is AES-GCM with its
  own trailer and key derivation. They are **wire-incompatible** as they
  stand.
- A KithMoot-format transformer cannot be supplied from a separate NDK
  library: with no exported C++ symbols, it would have to implement
  libwebrtc's virtual interfaces against the exact M144 headers and the
  build's own libc++ ABI. That is unsupported and fragile, and is not
  proposed.
- Forwarding encoded frames verbatim (what helping needs) is not possible
  with the stock SDK at all. The only stock route is decode and re-encode,
  which costs a phone a full decode and encode per stream and is rejected
  here.

Not verified from source and not to be relied on: the exact `FrameCryptor`
trailer layout, its HKDF parameters, and its H.264 handling. Read the
webrtc-sdk M144 source before choosing option (b) below.

Options for the frame layer on Android (owner decision, A5):

- (a) In-house libwebrtc build: an AAR built from the same webrtc-sdk M144
  revision with a KithMoot-format transformer and a frame-forwarding
  transformer added. Fits the in-house preference. Costs a libwebrtc build
  in CI, a larger update burden on every libwebrtc bump, and APK size if
  both builds ever coexist. Also unlocks helping (Section 5).
- (b) Move both platforms to a format `FrameCryptor` produces. A web
  protocol change (new media profile with negotiation, WebCrypto AES-GCM in
  the worker), ties the format to a third-party derivation, and does not
  unlock helping.
- (c) Ship being helped without a frame layer. Defensible per Section 3.2.
  Recommended for A1 to A4. The same gap also keeps Android off the server
  forwarder route, which web refuses without frame protection; that is out
  of scope here.

### 3.4 Local-address hiding is not merged yet

The rule that drops raw host candidates from outgoing signals is on
unmerged branches: web `fix/hide-local-call-addresses` (`f43926ce`,
`src/local-address-filter.ts`) and Android `fix/hide-local-call-addresses`
(`09468edb`, `protocol/.../LocalAddressPrivacy.kt`). Section 6.3 covers what
it changes for assist.

### 3.5 Android has no route ladder

Android gives each link every ICE server the selector chose (STUN, plus a
relay when one is configured) in one ICE agent, so TURN is used by ICE
itself rather than as a later rung. Adding a STUN-only first rung to match
web's order (direct, assist, forwarder, TURN) would change the call path
everyone uses, against the standing "call stability first" priority. This
design does not do that (Section 6.2).

## 4. Being helped on Android

### 4.1 Deterministic choice, ported exactly

New pure Kotlin in the `protocol` module, `PeerAssist.kt`, mirroring
`src/peer-assist.ts`:

- Constants: `MAX_ASSISTED_PAIRS = 3`, `ASSIST_STREAMS_PER_PAIR = 2`,
  `MAX_SLOTS = 16`, `DEFAULT_HEADROOM = 0.8`, label `kithmoot/v1/assist`.
- `assistPairKey(a, b)`: both `normaliseHex` (lower-case, which matches
  fold-kit's `toLowerCase`), sorted by `<`, joined with `:`.
- `slotScore(pairKey, device, slot)`: SHA-256 of the UTF-8 string
  `kithmoot/v1/assist|<pairKey>|<device>|<slot>` (slot in decimal), lower
  hex, first 16 characters. `bestScore` is the greatest over slots
  `0 until slots`.
- `spareUplinkBps`: `uplink * headroom - peers * perPeer`, floored at 0,
  0 for any non-finite input or headroom outside (0, 1]. Same operation
  order as web so IEEE 754 doubles give identical results.
- `assistCostBps = perPeerBps * 2`, 0 when not positive.
- `assistSlots = max(0, min(maxRelayed - relaying, affordable, 16))` where
  `affordable = floor(spare / cost)` when cost > 0, else the remainder.
- `sanitiseAssistOffer`: reachability one of `public`, `nat`, `symmetric`,
  `unknown`; `uplinkBps`, `peers`, `perPeerBps`, `relaying`, `maxRelayed`
  finite and non-negative; `peers`, `relaying`, `maxRelayed` integers in the
  JavaScript sense (`2.0` is an integer, `2.5` is not, a JSON string is
  not); anything else drops the offer whole and keeps the entry;
  `maxRelayed` clamped to 3.
- `rankAssistants(pair, volunteers, exclude)`: skip blank devices, either
  end, excluded devices, offers that do not sanitise, offers not `public`,
  and offers with fewer than one slot. Sort by `bestScore` descending, ties
  by device ascending. Kotlin `String.compareTo` compares UTF-16 code units,
  as JavaScript `<` does.

The engine applies the two local filters web applies in
`Mesh.#pickAssistant` and `#collectVolunteers`: the volunteer must be a
device this phone already holds a **connected** link to, must not be the far
device, and must not belong to this phone's own participant.

### 4.2 When Android asks for help

A new pure state machine, `AssistRoutes` (app `media` package, JVM-tested),
owns one route per remote device: `DIRECT`, `ASSIST(volunteer)`,
`EXHAUSTED`, plus the `failed` list.

- Enter assist only for a link that has not reached `connected` in its
  current build within the route budget: 10 s (`DEFAULT_ROUTE_TIMEOUT_MS`)
  when the link has no relay server, 20 s (`DEFAULT_TURN_ROUTE_TIMEOUT_MS`)
  when it has one, because TURN allocation is part of that same ICE
  attempt. A link that was connected and dropped goes through the existing
  health ladder first; a rebuild that then fails to connect is a
  not-connected link and qualifies.
- Never when `relayOnly` is on (Section 6.1).
- Pick per 4.1. With no candidate, stay as today (keep retrying the link)
  and mark the route exhausted for the UI.
- On choosing a volunteer: send the request, close the link to the far
  device (as web does, so both ends converge on the same topology), and
  arm the route timer on the request.
- On `accept: false`, on timeout, or when the volunteer leaves the roster
  or its link to this phone drops: add it to `failed`, pick again; with no
  candidate left, reopen the direct link and mark exhausted. After the
  30 s rest (`EXHAUSTED_RETRY_MS`, doubling to web's cap) clear `failed` and
  start at `DIRECT`.
- Behind a build flag in the style of `CALL_PROFILE_2_ENABLED`, default off
  until the handset checks in Section 9.3 pass.

Convergence in a mixed pair is by determinism, as on web: both ends rank
the same roster. The local "connected to me" filter can differ between the
ends; a mismatch is resolved by the volunteer refusing under the
mutual-request rule (4.3) and both ends excluding it.

### 4.3 Signalling, and two rule changes for both platforms

The existing wire, unchanged: a signal of `type: "assist"`, `assist: <far
device hex>`, request without `accept`, reply with `accept: true|false`,
inside the ordinary per-recipient gift wrap. The sender is the near end, so
neither end can ask about a pair it is not part of. Android adds `assist`
and `accept` to `SignalBody` and handles the type in
`RoomSession.onSignalEvent` as a branch of its own (like `annotation`),
after the roster membership and rate-limit checks, never passing it to
negotiation.

Two rules this design adds, which web must adopt in W2 so both platforms
behave the same:

1. **Mutual request.** A volunteer replies `accept: true` only once **both**
   ends have asked it about the pair, and carries a leg from X to Y only
   because X asked. It holds the first request for at most 8 s (shorter than
   the asker's 10 s route timer) and then replies `accept: false`. This
   keeps each person's consent over where their own media goes (a member who
   blocked someone, or refuses media to agents, never asks for that pair, so
   their media is never carried to them), and it makes an old client that
   never asks fall back cleanly instead of leaving its partner on a dead
   route.
2. **Connected means media.** An asker treats the assist route as connected
   on the first relayed media for the far device, not on the reply.
   `accept: true` re-arms the route timer for that first media, because the
   volunteer may have held the request for up to 8 s and still has to
   renegotiate; web's `#handleAssistReply` clears the timer on accept today,
   so W2 changes that too. No media within the re-armed budget is a failed
   rung.

A new optional field, `carry`, on an `offer` sent by the volunteer to an
end: a map from transceiver `mid` to `{ "device": <source device hex>,
"role": "camera" | "mic" | "screen" | "screen-audio" }`. Additive: an old
reader's JSON parse ignores it. Validation in `validSignalExtensions` (web)
and `SignalBody.fromJson` (Android): at most 8 entries, `device` 64 hex,
`role` one of the four. It is needed because a browser cannot choose a
sender's track id, so the volunteer cannot make the relayed stream's `msid`
match the source's roster advert.

### 4.4 Receiving a relayed track

On the link to the volunteer, the volunteer renegotiates and adds receive
transceivers carrying the far device's media. Android:

- Accepts the extra transceivers. On a profile-2 pair the four fixed slots
  stay as they are; carried m-lines sit outside the slot map. Whether the
  profile-2 machine on both platforms accepts m-lines outside `slots` is
  unverified and must be settled in W2 before A3.
- Attributes a carried track to `carry[mid].device` only when all hold:
  this phone's route for that device is `ASSIST` through this volunteer;
  that device's roster entry advertises the role; the volunteer is the
  sender of the offer. Otherwise the track is treated as the volunteer's own
  only if the volunteer's roster advertises it, and dropped if not. This
  mirrors `Mesh.#onEndpointTrack`.
- Feeds the track into `RemoteTiles` under the source participant, with
  `via = ASSIST`, so audio playback, the speaking line and recording inputs
  see it as that person's.

Android sends nothing new. Its own media already reaches the volunteer on
the existing link, and the volunteer carries it onwards once Android has
asked (rule 1).

### 4.5 Media protection when being helped

As Section 3.2: hop DTLS-SRTP plus roster attribution, the same as web can
offer once W2 lands. No frame layer is required. If the owner later chooses
option (a) or (b), relayed tracks additionally carry the source's
frame-layer ciphertext verbatim and Android decrypts it with the source's
derived key, failing closed on an unopened frame, exactly as the forwarder
path does on web.

## 5. Helping from Android

### 5.1 The platform wall

Helping means taking the encoded frames from one link's receiver and
putting them, undecoded, onto a sender on another link. Section 3.3: the
stock SDK cannot. H0 is the decision to build libwebrtc in-house (option
(a)). Without it, Android does not help, and its consent UI explains that
this phone cannot pass video on without unpacking it, in the same words
web uses for `no-relay-support`.

With an in-house build, the forwarding design follows `src/peer-relay.ts`:

- A native frame transformer on the source receiver copies each encoded
  frame into a bounded queue (60 frames, oldest dropped) and does not pass
  it to the decoder for the relay path. This phone still decodes the source
  normally for its own screen, which it does anyway as a participant.
- A sender on the link to the other end is driven by a clock source; its
  transformer replaces each locally encoded frame with the next queued
  frame and drops the frame when the queue is empty. A clock that ticks at
  the source's rate is preferable to a fixed one; using the decoded source
  track as the clock input is one way and is unverified.
- The carried transceiver's codec must equal the source's negotiated codec
  (Android's encoder factory enables H.264 High Profile and VP8); mismatch
  refuses the pair.
- Key-frame requests from the far end reach the clock's encoder, not the
  source. The transformer must turn a key frame from the clock encoder into
  a key-frame request on the source receiver. Unverified on both platforms.
- Counters: received, forwarded, dropped, starved, bytes in and out, for the
  indicator.

### 5.2 Consent, stricter than web

Web: explicit opt-in always; `mobile`, `on-battery` and `metered` are soft
blocks; the control is hidden only on a phone on mobile data. Android is a
phone, so every one of those becomes a hard block:

| Condition | Source on Android | Web | Android |
|---|---|---|---|
| Person turned it on, this call | In-memory, never persisted, off at every join | Hard | Hard |
| Hide my IP off | `relayOnly` | (not checked) | Hard |
| Unmetered Wi-Fi or Ethernet | `NetworkCapabilities` `NOT_METERED` and `TRANSPORT_WIFI` or `TRANSPORT_ETHERNET` | Soft (`metered`) | Hard |
| Charging | `BatteryManager.isCharging` and `ACTION_POWER_CONNECTED/DISCONNECTED` | Soft (`on-battery`) | Hard |
| Not in battery saver | `PowerManager.isPowerSaveMode` | n/a | Hard |
| Thermal status below `THERMAL_STATUS_MODERATE` | `PowerManager.currentThermalStatus` | n/a | Hard |
| Frame forwarding available | In-house build present and self-tested | Hard | Hard |
| Measured `public` | Reachability port (H1) | Hard | Hard |
| Measured spare uplink for one pair | Uplink port (H1) | Hard | Hard |

Any hard block arising mid-call (unplugged, moved to mobile data, phone
heats up) withdraws the offer and stops carrying at once, in the same order
as web's `toggleAssist` off: stop carrying, then republish without the
offer. `assistDecision` on web stays as it is; Android uses its own
`AssistConsent` that adds these blocks and maps `formFactor` to `mobile`
without asking.

Capacity: Android advertises `maxRelayed = 1`. The wire allows up to 3, and
a phone carrying one pair is already four extra streams.

### 5.3 Capacity estimate

Port `src/uplink.ts` (`uplinkBps` from the largest
`availableOutgoingBitrate` on a candidate pair, `perPeerBps` from measured
media rates, 30 s staleness, 500 ms minimum sample) and
`src/reachability.ts` (`classifyReachability` over gathered candidates) as
pure Kotlin, fed from `getStats` on the existing two-second health sampling
and from `onIceCandidate`. The classifier must read candidates as gathered,
**before** `LocalAddressPrivacy` rewrites them: after the rewrite host
candidates are gone and `raddr` is `0.0.0.0`, so every device would read
`unknown`. Web already feeds `ReachabilityProbe` from the raw
`icecandidate` event in `app/src/main.ts`, before the filter in `Peer`.

### 5.4 Battery and data impact

Arithmetic, not measured: one pair is two streams in and two out at the
volunteer's own per-peer rate. At Android's full camera rung (1.2 Mbit/s
plus about 32 kbit/s audio) that is about 2.5 Mbit/s each way, roughly
1.1 GB out and 1.1 GB in per hour. At the small rung (500 kbit/s) about
0.5 GB each way per hour. Battery cost is the radio at a sustained rate,
which is why charging and unmetered Wi-Fi are hard blocks. The indicator
shows the measured figure, as web's `costSentence` does, never a table.

### 5.5 Background and notification

A volunteer is always in the call already (it must hold links to both
ends), so carrying needs no new kind of service if the call itself survives
in the background. While carrying in the background, the call's ongoing
notification adds "Helping <name> and <name> connect" and a **Stop
helping** action that runs the off path above. Unverified: which
foreground service keeps an ordinary Android call (no screen share) alive
in the background today; the manifest shows `ScreenShareService`
(`mediaProjection|camera|microphone`) and `BackgroundCallListenerService`
(`specialUse`, ringing and messages). Settle this in H3 before relying on
background carrying.

## 6. Interactions

### 6.1 Hide my IP

Rule: with `relayOnly` on, Android never asks for assist and never offers.

Reasoning, stated precisely. Assist reuses links that already exist, so
under relay-only the phone's link to the volunteer already runs through the
chosen relay and the volunteer would not see the phone's address from
assist itself. The rule still holds for three reasons: Hide my IP promises
media goes through the person's approved relay and must not broaden that
set (requirement 5 in the call-privacy note), and a volunteer is an extra
media hop outside it; a relay-only pair that fails has a failed relay, which
a volunteer reached through the same relay does not fix; and relay-only
gathers no reflexive candidate, so reachability would read `unknown` and
the hard block would apply anyway. Making it explicit keeps any later change
to how assist opens connections from quietly exposing an address.

Web should adopt the same explicit rule in W2 (`app/src/main.ts` passes
`relayOnly` into the mesh `assist` option and into `assistEnvironment`).

### 6.2 Spry and room relays

`CallIceSelector` order is unchanged: hand-entered relay, then Spry (the
pairing's offer, then the room's), then a discovered public relay, then
STUN only. Assist comes **after** whatever the selector supplied, because
on Android the relay is tried inside the same ICE attempt. So on Android
the effective order is direct-or-TURN, then assist; web's order is direct,
assist, forwarder, TURN. In a mixed pair the two still meet: whichever end
moves to assist closes its direct peer, the other end's link then fails,
and it moves to the same volunteer. A relay change mid-call
(`updateIceServers`) does not disturb an assist route; it applies to links
as today.

### 6.3 Local-address hiding

Once merged, devices meet through server-reflexive (or relay) candidates.
A `public` volunteer has a server-reflexive candidate equal to its
interface address and port, which survives the filter, so it stays
reachable. Assist opens no new link, so the filter changes nothing in the
assist exchange itself; it only decides whether the links assist reuses
could form. The classifier caveat in 5.3 applies to both platforms.

### 6.4 Agents, blocks and audience

The mutual-request rule (4.3) is what keeps `WebRtcEngine.setAudience`
and device-only blocks meaningful: Android asks for a pair only when its
audience rule admits the far device, so a volunteer never carries this
phone's media to someone the phone has refused.

## 7. Protocol compatibility

| Item | Change | Old readers |
|---|---|---|
| Roster `assist` | None. Android starts decoding it with `sanitiseAssistOffer` semantics; publishes it only from H3 | Unaffected; web already sanitises |
| `assist` signal | None | Old Android ignores it (`else -> Unit`). A web asker gets no reply and times out after 10 s, then escalates, as today |
| `carry` on offers | New, optional, additive | Ignored by JSON parse. Under the mutual-request rule a volunteer never sends carried media to an end that did not ask, so an old client never receives a stream it would misattribute |
| Mutual request, connected-means-media | Behaviour, both platforms | A web build without W2 accepts on the first request and reports connected at once (3.1). Mixed with W2 builds, its partner still converges because a W2 volunteer refuses after 8 s without the second request |
| `tier` on offers | Android still neither sends nor reads it | Unchanged |

Mixed room outcomes after W2 and A1 to A4:

- Web end and Android end, web volunteer: works.
- Android and Android, web volunteer: works.
- Any pair, Android volunteer: only after H0 to H3.
- Pair with a pre-A3 Android end: volunteer refuses after 8 s, web end
  escalates to forwarder or TURN as it would without assist.

## 8. UI

Plain voice, matching web.

### 8.1 Being helped

- Tile or chip while connecting: "Connecting through <name>…", as web's
  `connectingWord` says "connecting via <name>…". Android uses "through"
  only if the web string changes too; otherwise "via", for one wording.
- Once media flows: a small line on the tile and in participant details,
  "Through <name>, who offered to help people connect."
- Exhausted: as today's Android wording for a failed link, plus "Nobody in
  the room could help connect you" when there were no volunteers.
- Volunteer leaves: no dialog; the line returns to "Connecting…" while the
  next one is tried.

### 8.2 Helping (H2 onwards)

In Room info, **Privacy and connection** (`RoomInfo.kt` `PRIVACY`), the
same place as web's `#assist`:

- Switch: "Help other people connect", off at every join.
- Under it, the first hard block in plain words, for example "Only while
  charging and on Wi-Fi. Helping somebody means sending steadily, and it
  would use your battery and data." When the in-house build is absent: the
  web `no-relay-support` sentence adapted to "This phone cannot pass video
  on without unpacking it first, so it cannot help anybody."
- While carrying: "Helping 1 of 1: <name> and <name>. About <rate> going
  out, <amount> passed on so far."
- "What helping costs you": web's cost sentence and pair note, with the cap
  stated as one pair on this phone, and the attribution note **rewritten**
  per Section 3.2 (owner wording decision, W1), for example "The people you
  would be helping are already in the room with you. Passing their video
  along gives you nothing you did not already have."

## 9. Tests

### 9.1 JVM unit tests (Android)

- `PeerAssistTest` and `PeerAssistVectorsTest`: selection against the
  shared vectors; sanitising hostile offers; `2.0` versus `2.5`; clamping;
  zero `perPeerBps`; ends and own devices excluded.
- `RosterEventVectorsTest`: remove `assist` from `NOT_MODELLED_YET`;
  `assist-offer` round-trips, `assist-offer-hostile` keeps the entry and
  drops the offer.
- `SignalBody` round trip for `assist`, `accept`, `carry`, with malformed
  `carry` dropped; signal compatibility vectors if W3 adds them.
- `AssistRoutesTest`: enter on timeout and on `failed`; relay-only never
  enters; refusal, timeout and volunteer departure each move down the list;
  exhausted rest and reset; connected only on first carried media.
- `CarryAttributionTest`: each attribution condition in 4.4 negated alone
  drops or reassigns the track.
- From H1: `CallReachabilityTest` with recorded candidate sets, including a
  post-filter set to show it reads `unknown`; `UplinkProbeTest`;
  `AssistConsentTest` covering every row of 5.2.

### 9.2 Shared vectors

A new `assistSelection` group, generated in web `vectors/generate.mjs`,
recorded in `vectors/kithmoot-vectors.json`, checked by
`vectors/verify.test.ts` and described in `vectors/README.md`. Cases:
pair key with mixed case; slot scores for three slots; ranking with and
without `exclude`; a non-`public` offer; an offer with no slots left; a
`spareUplinkBps` boundary where the floor changes the slot count;
`maxRelayed` above 3; integer-valued doubles. Android merges only this group
into its vendored `protocol/src/test/resources/kithmoot-vectors.json`, by
hand, without replacing the file, and pins its case count in the test.

### 9.3 End to end with web peers

What an emulator or CI can prove, using the offline relay
(`test/ws-relay.mjs`) and the existing `BrowserCallInteropTest` pattern:

- A debug-only fault hook that refuses ICE between two named devices, so a
  "pair that cannot connect" exists on loopback.
- Android end plus web end plus web volunteer: route enters assist,
  `getStats` on the phone shows inbound media on the volunteer link
  attributed to the web end, none on a direct link; the web end shows the
  phone's media arriving via the volunteer.
- Volunteer closes its tab mid-call: the pair moves to the next volunteer,
  or back to direct-or-TURN, within the budget.
- Old-client case: a volunteer with only one end asking refuses after 8 s.
- Relay-only Android: never sends `assist`.

What needs real networks and handsets (owner hardware, not CI):

- A handset on a carrier with CGNAT, a second device behind a strict NAT or
  a commercial VPN, and a desktop volunteer with a measured `public`
  address: the original motivating case, with audio and video both ways.
- Hide my IP on the handset with a Spry relay: no assist signal sent.
- From H3: a handset volunteering on charge and Wi-Fi, carrying one pair
  for 30 minutes, with measured data, battery and temperature; unplugging
  stops carrying within a poll interval; Stop helping from the notification
  with the screen off. The emulator cannot prove media effects or thermal
  behaviour on this project's setup.

## 10. Ordered PR list

Web prerequisites, `forgesworn/kithmoot`:

- **W1** Correct the overclaims. `app/index.html` (`#assistCost` notes),
  comments in `src/peer-relay.ts`, `src/types.ts` (`AssistOffer`),
  `src/media-crypto.ts` where it discusses relays. Owner approves wording.
- **W2** Assist media plane and the two rules. `src/mesh.ts`
  (`#handleAssistRequest` mutual request with 8 s hold,
  `#handleAssistReply` connected on first media, `#onEndpointTrack` via
  `carry`, relay-only gate), `src/peer-relay.ts` (`PeerRelay.admit` per-leg
  consent), `src/signal.ts` (`carry`, `validSignalExtensions`),
  `app/src/main.ts` (`onRelayStart`, `onRelayStop` wiring), a new
  `app/src/peer-relay-media.ts` (clock senders, codec match, key-frame
  bridging), tests in `src/mesh-assist.test.ts`, `src/session-assist.test.ts`
  and `test/peer-assist.spec.ts` driven through `Mesh` rather than by hand.
  Settles the profile-2 m-line question in 4.4.
- **W3** `assistSelection` vectors as in 9.2.

Android being helped, `forgesworn/kithmoot-android`, after W2 and W3:

- **A1** Protocol. `protocol/.../protocol/PeerAssist.kt` (new),
  `Roster.kt` (`assist` field, decode with sanitising), vendored vectors
  (surgical merge), `RosterEventVectorsTest.kt`, new
  `PeerAssistVectorsTest.kt` and `PeerAssistTest.kt`.
- **A2** Signals. `protocol/.../protocol/Signal.kt` (`assist`, `accept`,
  `carry`), `session/RoomSession.kt` (`onSignalEvent` assist branch and an
  `assistSignals` flow), `media/Negotiation.kt` and `WebRtcEngine.kt`
  envelope mapping for `carry`, tests.
- **A3** Engine. New `media/AssistRoutes.kt`; `media/WebRtcEngine.kt`
  (route budget, close and reopen of the direct link, request and reply
  handling, carried receive transceivers); `media/RemoteTrackIds.kt` and
  `media/RemoteTiles.kt` (carry attribution, `via`); flag off by default;
  `AssistRoutesTest`, `CarryAttributionTest`, an instrumented
  `PeerAssistInteropTest` with the fault hook.
- **A4** UI for being helped. `ui/room/CallTile.kt`, `ui/room/Tiles.kt`,
  `ui/room/ParticipantDetails.kt`, `ui/room/SpeakingLine.kt` if it names a
  route, strings beside the web ones. Then the handset checks in 9.3 and
  turning the flag on.
- **A5** Frame layer, only if the owner chooses option (a) or (b).

Android helping, after H0:

- **H0** Decision and spike: in-house libwebrtc M144 AAR with a
  KithMoot-format transformer and a frame-forwarding transformer; CI build;
  `gradle/libs.versions.toml` pin.
- **H1** Measurement. New `media/CallReachability.kt` and
  `media/UplinkProbe.kt` (pure), fed from `WebRtcEngine` raw candidates and
  stats; tests.
- **H2** Consent and UI. New `media/AssistConsent.kt`;
  `ui/room/RoomInfo.kt` Privacy and connection row; tests.
- **H3** Carrying. New `media/PeerRelay.kt` (pairs, cap 1, counters),
  native forwarding glue, `session/RoomSession.kt` publishes the roster
  offer, ongoing-notification Stop action, background service check from
  5.5.
- **H4** Handset qualification in 9.3.

## 11. Unverified and open

Owner decisions:

1. Accept W1 to W3 as prerequisites, since web assist currently carries no
   media (3.1).
2. Frame layer: (a), (b) or (c) (3.3). Recommended: (c) now, revisit with H0.
3. Wording for the corrected volunteer note (3.2, W1), and whether
   per-sender frame signing is wanted as separate protocol work.
4. Whether Android ever helps, given it needs an in-house libwebrtc build
   (H0).
5. `maxRelayed = 1` on Android (5.2).

Unverified facts:

- `FrameCryptor` trailer layout, HKDF parameters and H.264 handling in
  webrtc-sdk M144 (read source before option (b)).
- Whether profile-2 pairs on both platforms accept m-lines outside the slot
  map (4.4).
- Key-frame request bridging and clock-rate matching for forwarded frames,
  on both platforms (5.1).
- Which foreground service keeps an ordinary Android call alive in the
  background (5.5).
- The local-address hiding branches are unmerged (3.4); 6.3 assumes they
  merge as described in their commit messages.
- All bandwidth and data figures in 5.4 are arithmetic.

## Owner decisions, 28 October 2026

- **Web volunteering paused now.** kithmoot `fix/pause-peer-assist` (57a49d26):
  the app neither offers nor asks for assist and hides the control, so failing
  pairs go on to a forwarder or TURN. It returns only with W2 and W3 (frame
  pumps wired; a route counts as connected only once relayed media arrives).
- **W1 wording correction:** go ahead. Say plainly what a volunteer can and
  cannot see or change.
- **Android being helped (A1 onwards):** go ahead, after W2 and W3, with no
  frame layer for now.
- **Per-sender frame signing:** go ahead as separate protocol work, so a
  volunteer cannot alter or impersonate media.
- **Android helping (H0 onwards):** go ahead, including the in-house libwebrtc
  build it needs. Any native binding follows ForgeSworn's ferrule decision
  (hand-written C ABI and JNI, no generators). One-pair cap on Android stands.

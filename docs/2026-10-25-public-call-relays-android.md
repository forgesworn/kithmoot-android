# Finding public call relays: Android

Addendum, 25 October 2026, to the web design in the kithmoot repository,
`docs/2026-10-25-public-relay-discovery.md` (merged in kithmoot #570 and
built in #573). Everything there binds this app too: the client rules in
spry-turn `docs/public-mode.md`, the ten changes from the soundness review
and the legal shape. This note records only where Android differs, and the
gate outcome for that difference. Line references are to `origin/main` at
d50708c9.

## Same as web

- Off by default. A switch in Settings, Connections, with the web's words
  exactly: "Find public call relays on my Nostr relays", these relays are
  run by strangers, KithMoot does not check or vouch for them, and the relay
  sees the call's IP addresses, timing and volume but not its content.
- One query, `kinds:[31478]`, `#d:["spry-turn"]`, `limit:64`, `since` two
  hours ago, when the picker opens, closed at end of stored events or after
  5 s. No standing subscription, and no query mid-call.
- The user picks. At most 3 operators contacted for one call, counting the
  check. Never probe in the background or the whole list.
- Hour-long logins, renewed while in the call. Logins in memory for one call
  only. Relay-only never falls back to direct.
- Display as web review change 8: "the operator says" for note and region,
  region as a code, bidi, zero-width, BOM and line separators stripped,
  note at most two lines, ASCII hostname with `xn--` left encoded, short
  transport key, and a flag for a known hostname under a new key.
- Nothing is reported to ForgeSworn, the operator or anyone else.

## Where Android differs

1. **Verifier in `:protocol`.** A pure JVM port of spry-link `advert.rs`
   (rules 2 to 4 and the watermark book), using `Events.verify`
   (`NostrEvent.kt:121`) and `Schnorr.verify`. The fixture
   `protocol/src/test/resources/public-advert-v1.json` is a byte-identical
   copy of spry-turn `docs/fixtures/public-advert-v1.json` at 98831a1
   (SHA-256 `d91ecbbc…b11f9`, the same file as kithmoot
   `test/fixtures/public-advert-v1.json`); a test pins the hash and runs
   every case. The Rust destination unit-test table is ported too.
2. **Which relays are asked.** `accountRelayChoices()`
   (`RoomViewModel.kt:3864`, per account through `relayChoicesKey`) filtered
   to read. A relay is skipped as failing when its last known health in this
   session has a connection value containing "failed" or a read value
   containing "refused"; with no history, it is asked. Health is measured on
   the device only; there is no shipped block list. Never a room's relays or
   invitation hints, and no relay is added for discovery.
3. **Not signed in.** A separate short-lived `RelayPool` built on
   `OkHttpRelaySockets()` (not `HybridRelaySockets`, which carries Link
   routes) with the default `{ null }` authenticator, so no relay learns
   which key searched. Query with `queryAvailable(filters, 5000)`, then stop
   the pool.
4. **Refused in anonymous mode.** While `anonymousMode` is on, the switch is
   shown off and disabled, and no query, check or login fetch is made: TURN
   cannot run over Tor, and these requests would go out in the clear.
5. **The login fetch: OkHttp, not fetch.** No CORS, so no preflight, but the
   request is the same: `POST`, body exactly `{"v":1}`,
   `Content-Type: application/json`. Its own client, not `SpryClient`'s, with
   its own connection pool (evicted when the call ends), `Proxy.NO_PROXY`
   (a proxy would resolve the name and skip the check in point 6),
   `followRedirects(false)`, `followSslRedirects(false)`, no cookie jar, no
   cache, a 4 s call timeout, and the body read as a stream capped at 4 KiB.
   It sends no `Origin`, `Referer` or cookies, and no `User-Agent` other
   than OkHttp's default. Response checks as web review change 2: 200 with
   `application/json`, every URL exactly one from the verified advert,
   `username` matching `^\d+:pub-[0-9a-f]{24}$` with the prefix equal to
   `expiresAt`, `credential` base64 of at most 64 characters, `expiresAt`
   an integer in `(now, now+3600+60]`.
6. **Android can resolve names, so it does.** The web's hostname rules and
   extra suffix denylist (`arpa`, `lan`, `home`, `localdomain`, `onion`,
   `alt`, `example`, plus Spry's) apply first. Then the login client's `Dns`
   refuses the name if any address is not public. Reuse
   `SpryClient.publicAddress` (`SpryClient.kt:105-110`), widened for both
   users: also refuse 2001:db8::/32, 2002::/16, 2001::/32, 198.51.100.0/24,
   203.0.113.0/24 and 192.88.99.0/24; for 64:ff9b::/96, check the IPv4
   address inside. The TURN hostname is the same name (rule 4): resolve and
   check it the same way, then give WebRTC the checked address in the URL and
   the name through `IceServer.Builder.setHostname`, so libwebrtc does no
   lookup of its own and `turns:` still verifies the name. That closes the
   rebinding gap the web has. If this libwebrtc build does not honour
   `setHostname` for `turns:`, stop and report rather than drop it.
7. **When it applies: at call join, through one selector.** The engine is
   built when the room opens (`startMedia`, `RoomViewModel.kt:5724-5760`),
   not when the user joins a call, so nothing is fetched at build time. A
   call-scoped selector owns every `engine.updateIceServers` call. Today
   both the media build and the `SpryRoomAccess` callback
   (`RoomViewModel.kt:1299-1305`) call it, and that callback already
   overrides a hand-entered relay mid-call and can push an empty list in
   relay-only mode; the selector fixes that too. Its order: hand-entered
   relay, then Spry (pairing, then the room's own offer), then the
   discovered relay, then STUN only. A discovered relay is used only when
   both of the first two give nothing, and stands down if Spry later
   supplies one (the web's `superseded: () => spryActive`). With a saved
   relay or a Spry pairing present, no discovery query or login request is
   made. When `session.currentCall()` starts, the selector fetches the login
   from the relay the user chose (selected, or last good), checks it as in
   point 6 and applies it; when the call ends it drops the relay and the
   login. No picker opens mid-call.
8. **Relay-only before the call.** With relay-only on, no other relay, and a
   discovered relay chosen, the engine is built with an empty server list
   (no candidates, so nothing leaks) instead of refusing with
   `HIDE_IP_NO_RELAY`. If the login fetch at join fails, the call is
   refused with "call relay lost", never direct.
9. **Renewal.** `WebRtcEngine.updateIceServers` already calls
   `setConfiguration` and `restartIce` on every link
   (`WebRtcEngine.kt:142-146, 768-773`); correct its KDoc, which says links
   keep their old servers. The web's WebKit `transport=tcp` filter does not
   apply here and is not ported. Renewal goes through the selector, only
   while this device is in the call, with jitter, retrying a 429 before the
   deadline. A failure is shown as "call relay lost", not swallowed.
10. **The check.** Android has no relay check today. For a relay the user
    has selected: fetch a login (point 5), then build one relay-only
    `PeerConnection` with it and wait up to 12 s for a `relay` candidate.
    That proves the endpoint and the TURN login, though not a full data
    path as the web check does. One allocation. Reuse the login for the call
    while it is valid (web change 10). Show passed or failed, with no raw
    error text.
11. **Storage.** Its own `EncryptedRoomStorage` file, not `CallRelayStore`:
    the switch, the last good relay (transport key and hostname) and replay
    watermarks (at most 1024, pruned two hours after their `created_at`,
    each with the id that set it, so the one identical event is accepted once
    after a restart). Adverts and logins stay in memory. Turning the switch
    off clears the file.
12. **Logs.** No URL, hostname, username or credential in any log line. A
    test holds the existing server-count line (`RoomViewModel.kt:5766`) to
    counts only.

The operator also answers STUN for a call using its relay, because
`CallIceServers.stunUrls` derives `stun:` from the relay's `turn:` URLs. The
privacy wording below says the operator sees the device's IP address.

## Gates

- **Soundness:** 25 October 2026, sound with changes. Points 2 to 12 above
  include all nine required changes, and the rebinding and NAT64 suggestions.
- **Legal cover, KithMoot only:** 25 October 2026, passes with wording
  changes. The legal shape is unchanged: design (b) on the shipped default
  relays, design (a) on relays the user chose. Nothing adds a list,
  directory, ranking, ForgeSworn hostname or report. Owed in the release
  that ships the APK, not this merge:
  - kithmoot privacy page 4.5: "In the web app" becomes "In the web app, and
    the Android app from version [INPUT]". The line that the operator sees
    which web app asked becomes true only for the web app; Android sends no
    app name beyond the network library's default identifier.
  - kithmoot `docs/legal/call-transport-position.md`: the Android app reads
    adverts too; Android parity reviewed 25 October 2026, no change to the
    reading.
  - This repo's README: one line saying discovery is off by default, adverts
    are read from your own Nostr relays, and KithMoot ships no list and runs
    no directory of call relays.

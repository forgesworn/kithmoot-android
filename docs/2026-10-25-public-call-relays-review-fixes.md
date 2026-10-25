# Public call relays: phase B review fixes

25 October 2026. Continuation from tested revision `0aea0b4f` and saved,
untested revision `449d1a80`. The Android design and its original soundness
and legal gates are in `2026-10-25-public-call-relays-android.md`.

## Soundness review before building

Source review completed in this session before running Gradle. This is a
code review by the implementing agent, not a fresh independent review.

| Finding | Resolution and regression coverage |
|---|---|
| Relay-only engine keeps a stranger's server and login after leave or lapse | `CallIceSelector.publish` sends an empty server list when the discovered source goes. Engine updates are serialised with selection and detach. `CallIceSelectorTest` and `PublicRelayCallRouteTest` cover leave and lapse; the ordinary-call and private Spry rules retain separate coverage. |
| Initial off-call emission clears the check's login and operator budget | `PublicRelayCallRoute.onCallChanged` acts on changes only, starting with off. Test opens a room off-call, retains the check's login, and reuses it on join. |
| Two attempts across the eligibility suspension orphan a renewing call | The route claims an attempt before suspension. Leave cancels startup and ends the call; close also invalidates pending startup. Tests suspend eligibility, login and pinning, deliver duplicate join signals, leave/rejoin and close, then advance beyond renewal. |
| Login arriving after leave returns to the shared cache | `PublicCallRelays.dropLogins` advances a generation. A fetch begun before that generation is refused rather than cached. A regression suspends the fetch, leaves, then releases the response and verifies a new fetch is needed. Budget checking and reservation are under one lock. |
| Relay-only start refusal can be silent | The route supplies the relay-lost notice when start returns false without one; an existing notice is not duplicated. Tests cover no candidates, eligibility refusal and failed login. |

The saved WIP also extracts the route from RoomViewModel and suppresses the
missing-relay line while the relay-only join waits for its login. Pending
status belongs to the current attempt: an old startup's finally block
cannot clear a newer one's status. Leave and close clear it immediately.
Spry takeover cancels startup/renewal and forgets public logins without
resetting the operator budget mid-call. Comments name the empty-list rule
and the single ICE restart per link for each applied renewal.

Review outcome: ready for local verification within the existing design.
No new discovery, relay, credential, telemetry or fallback policy is added.

## Legal-cover readback before building

The legal-cover skill's project inventory was run and the existing design,
README, picker wording, query path and login client were read against the
law note. The position remains publisher with independent defaults:
`PublicRelayDiscovery.queryRelays` uses the account's read relays;
`RoomViewModel.searchPublicRelays` uses a temporary unauthenticated pool;
the operator is selected on the device; no directory, operator list or
ForgeSworn credential endpoint is added. No publisher-run component in
this change receives content keys. Relay logins remain in memory only.

Jurisdiction-kit `law/gb/online-services.md` is verified 28 September 2026,
review due 28 March 2027; section 2.13's primary text was read on 25 October
2026. Its applications to discovery designs (a) and (b) remain unchecked
readings `[U]`, not settled exemptions. This continuation checks the code
against the already reviewed position; it does not make a fresh legal
classification or repeat the primary-source research.

The fixes reduce retention and preserve that position. The wording owed
at APK release remains unchanged: web privacy section 4.5 must name the
Android version, distinguish the web-only app identifier, and the shared
call-transport position must include Android discovery. The README line
is present. No APK release is authorised by this note. No live deployment,
host logs, legal-basis choices or operator capacity were rechecked.

## Local validation

Pending. Run one heavy Gradle invocation at a time, with JDK 21:

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=2
```

Do not run androidTest, a device or an emulator locally for this task.
Hosted CI has 16 jobs and is a separate gate.

## Android review setup and expected results

Screens: Settings > Connections > Public call relays, and the room call
status and relay-loss notice. Build: the debug APK from the reviewed PR
revision, `app/build/outputs/apk/debug/app-debug.apk`. Physical review is
pending; the steps below are for when the owner allows devices.

1. With discovery off, open a room and join/leave normally. Expect the
   existing hand-entered/Spry/default-STUN precedence, with no public-relay
   query or login fetch.
2. With no Spry pairing or hand-entered relay, enable discovery, select a
   consenting independent operator and run the check with microphone and
   camera permissions absent. Expect a relay address or a bounded failure,
   with no microphone/camera use. No operator list is supplied by KithMoot.
3. Open a room after a successful check and join. Expect its login reused
   while valid; opening the room off-call must not clear it.
4. Enable Hide IP on calls and join through that relay. Expect no direct
   fallback and no missing-relay flash while login/pinning is pending.
   Leave and rejoin: expect a fresh login and no old renewal after leave.
5. Make the chosen operator unavailable before join, then test a renewal
   refusal/lapse. Expect one relay-lost refusal at failed relay-only join;
   lapse must remove the public server/login and never switch direct.
6. Check `turns:` certificate verification with an IP URL and `setHostname`,
   DNS refusal through a real resolver, dialog scrolling/layout and larger
   text. These remain open on-device evidence gates.

No Archipelago screen or package is changed by this Android-only PR.
Archy public-mode design PR #49 remains dependent on the owner's six
decisions and its own maintainer acceptance gate before any UI merge.

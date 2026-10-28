# Bounded Android metadata observer (explicit LAB build only)

Default builds do not activate observation. Activation requires BOTH DEBUG and
`-Pkithmoot.labMetadataObserver=true`, followed by an explicit owned in-process
window. Release tasks refuse the lab flag; release BuildConfig is always false.
The observer changes no permission, signer, crypto, routing or account-network
policy. It creates no logs, disk capture, network endpoint or production export.

The fixed15 roles, shared32-byte salt, action sequence and duration match the
synthetic Node oracle fixture in `app/src/test/resources/lab/metadata-vectors.json`.
All retained capture records contain salted HMACs, byte counts, literal presence
maps, classifications and application observation times. No captured body/frame,
header value, endpoint URL or key is retained in a record. The private fixed role
terms and salt necessarily remain in memory while armed; close drops term refs
and clears the mutable salt. This is not a secure-memory-erasure guarantee.

One logical value produces one record. Original captured bytes feed HMAC/size;
Node-compatible UTF8 replacement plus KMP handles streamed literal matching, not
occurrence counts, URL decoding, case folding or base64 searches. WS JSON is
normalized for matching and canonical-event digests; unsupported unsafe/fractional
JSON numbers or unpaired UTF16 poison capture instead of silently changing parity.
U+FFFD/unpaired-surrogate terms are refused. Canonical event hashes ignore claimed
id/signature and do NOT verify signatures. Main app/native frames never inherit
the separate Cambium/Bark required-Root-routing masking classification.

Coverage is explicitly scoped:

- OkHttpRelaySockets, TorOnlyRelaySockets and SpryControl: accepted outgoing frames,
  incoming text/binary callbacks and lifecycle signals. Times are callback-entry
  observations anchored to epoch time with monotonic deltas, not packet timestamps.
- All11 owned OkHttp client construction sites: initial APPLICATION-interceptor
  request-attempt URL and each header name/value before cache/connect. Actual
  RequestBody.writeTo is invoked only once per real write and forwarded in bounded
  chunks. Successful sink offers are counted; partial/unknown writes, retries,
  redirects, unperformed bodies and untouched duplex bodies have explicit gaps.
  Cache hits are attempts, not wire bandwidth. Original response/body identity is
  preserved: response bodies are not observed. WS handshake headers are a separate
  extra view and must not be merged into the browser's outgoing HTTP comparison.
- Reflective Link request/socket boundaries AFTER existing worker authorization:
  offered/returned native-inner values only. No startup/pair/upsert/custody/key
  arguments or generic reflection hook. Native-inner is NOT external encrypted wire.

Windows are bounded to10,000 records/8,000,000 payload+URL-reference processing bytes.
Overflow, clock regression, incomplete bodies, missing records, deadline crossings
or callbacks still active at disarm invalidate capture completeness. Immutable
callback-entry leases cannot retarget old work to a new arm; new arm refuses
outstanding callbacks/attempts. Disarm is terminal even when the clock is poisoned.
HTTP response bodies, native encrypted outer packets, WebRTC, third-party SDK HTTP,
OS/DNS/TLS traffic and instrumentation loopback control are not covered. Do not
interpret zero records on an inactive/unobserved surface as an absence proof.
`absenceClaim` and `privacyReductionClaim` always remain false.

The owned test method is
`MixedInvitationAppTest#run_private_observed_product_steps`. It uses the existing
private HttpURLConnection control exchange (excluded from observed journey clients),
not an exported app API or persisted manifest. A loop is limited to32 non-wait
commands,600 inert waits (<=1s each) and10min. It supports arm/snapshot/disarm and
only existing target/admin-review/admin-accept/admin-response/send/receive/drive/
snapshot product steps, plus finish. Retained expectedPersona and actual normal
product/consent guards are rechecked; no pairing, provisioning, witness enrolment,
new room, grant or process-restart action is added. Finish requires explicit disarm;
failure finally disarms and sanitizes all JUnit output, including setup/teardown.
A process restart ends a window; terms are never persisted to bridge a restart.

Adapter MockWebServer tests are unit/integration evidence, not real app-owned
qualification controls. Positive controls require actual app-owned operations and
independently matched owned-host REQ/EOSE/CLOSE or HTTP/native receipts at both window
ends. This build does not invent such success flags. Fresh installed Root/device/
rendezvous/state, complete action/duration equivalence and full comparison remain
separate runtime qualifications. Frozen45f/DDD acceptance does not transfer here.

# Match the recording capture caller to production

The integrated capture run on `62a97b88` executes all 22 cases, with one
browser/native video answer rejected for media-line ordering. That failure
remains retained and its precise signal sequence remains unattributed.

The capture fixture omitted the sender-owned media view that WebRtcEngine
passes to WebRtcPeerConnection. An unchanged repeated offer could therefore
not replay its stored answer. Its readiness flag also crossed the SDK and
WebView callback threads as a plain Boolean.

A controlled comparison uses the same PeerLink connection and signal sequence
with unknown and known sender media. Both pairs remain usable; the unknown
view describes the repeated offer afresh, while the known view replays its
original answer. All 16 negotiation disagreement controls pass, including
the existing media-change and rollback guards.

The capture caller now supplies its actual audio/video sender IDs and uses an
AtomicBoolean readiness flag. Failure observations retain at most 16 synthetic
section-shape rows with at most 12 fields each and sanitised 12-character mids.
They exclude raw SDP, ICE addresses/passwords, identities and media. No worker
sampling or export diagnostics are added.

Production negotiation and the pinned browser implementation are unchanged.
Qualification requires the original complete 22-case capture suite, its
45-second media and 20-second camera-change deadlines, original two-way
recording assertions, and all sixteen jobs at the integrated commit. The
controlled caller result alone does not establish the hosted failure's cause.

package dev.forgesworn.kithmoot.relay

/**
 * What the pool knows about one relay an event was offered to, as the web
 * client's `RelayAckEvidence` (`src/relay-pool.ts`). Evidence only: the
 * verdict belongs to the caller (see `session/DeliveryState.kt`).
 */
data class RelayAckEvidence(
    val url: String,
    /** This relay can be asked for the event back. A write-only relay never can. */
    val readable: Boolean,
    /** Asked for this event back after its write: true when the relay returned it,
     *  false when it ended the request without it, null while not asked or not answered. */
    val found: Boolean? = null,
    /** This relay said OK to the event. One that has not may still hold it, and is asked by name. */
    val acked: Boolean,
    /** When this relay last said OK to the event, in milliseconds; 0 when it has not. */
    val at: Long,
    /** Since its socket last opened this relay has returned an event of the same kind when asked. */
    val keeps: Boolean,
    /** This relay has said OK to an event of the same kind and not returned it. */
    val lost: Boolean,
)

/** Which relays an event went to, and what each was seen to do with it afterwards. */
data class PublishEvidence(
    val kind: Int,
    val relays: List<RelayAckEvidence>,
    /** Write relays still working on this event: offered, not yet answered, while its publication is open. */
    val pending: Int = 0,
)

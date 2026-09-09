package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex

/**
 * Every drop key a room's members may use from the lookback to one epoch
 * ahead, kept as a lookup by tag so a wrap is matched with a set lookup and
 * no cryptography; and the counters this device has spent, so a key is never
 * used twice in an epoch. The room case of `nostr-deaddrop`'s key table,
 * written from its README.
 *
 * A counter seen on the wire for this device's own member is spent for this
 * device too, whichever device drew it: two devices of one person that can
 * see each other's drops repeat a tag only inside the relay's propagation
 * delay. Disjoint ranges per device remain the rule; that is the backstop.
 */
class QuietKeys(
    private val epochSeconds: Long = DeadDrop.EPOCH_SECONDS,
    private val lookbackEpochs: Int = 48,
    private val random: (Int) -> ByteArray = Entropy::bytes,
) {
    class Hit(val member: String, val key: DeadDrop.DropKey)
    class UsedCounters(val epoch: Long, val counters: Set<Int>)
    class EpochExhausted(val epochIndex: Long, val max: Int) : IllegalStateException("all $max drop keys for epoch $epochIndex are used; the next epoch has fresh ones")

    private var ikm: DeadDrop.Ikm? = null
    private var ikmHex = ""
    private val members = LinkedHashSet<String>()
    private val table = HashMap<String, Hit>()
    private val derived = HashMap<String, MutableSet<Long>>()
    private val tagsOf = HashMap<String, MutableSet<String>>()
    private var currentEpoch = -1L
    private val used = HashMap<String, Pair<Long, MutableSet<Int>>>()

    /** The room key moved, or the roster did. Keys derive from the new ikm from now on; used counters survive an unchanged ikm and are forgotten with a new one. */
    @Synchronized
    fun set(ikm: DeadDrop.Ikm, members: Collection<String>) {
        val hex = ikm.bytes.toHex()
        if (hex != ikmHex) { used.clear(); table.clear(); derived.clear(); tagsOf.clear() }
        this.ikm = ikm
        ikmHex = hex
        val next = members.map { it.lowercase() }.toCollection(LinkedHashSet())
        for (m in this.members.toList()) if (m !in next) remove(m)
        this.members.clear(); this.members.addAll(next)
        if (currentEpoch >= 0) deriveMissing(currentEpoch)
    }

    private fun remove(member: String) {
        tagsOf.remove(member)?.forEach { table.remove(it) }
        derived.remove(member)
        used.remove(member)
    }

    /** Bring the table up to now. Cheap when the epoch has not moved. */
    @Synchronized
    fun refresh(now: Long) {
        val e = DeadDrop.epochIndexAt(now, epochSeconds)
        if (e == currentEpoch) return
        currentEpoch = e
        for ((member, tags) in tagsOf) {
            val old = tags.filter { (table[it]?.key?.epochIndex ?: Long.MAX_VALUE) < e - lookbackEpochs }
            for (t in old) { table.remove(t); tags.remove(t) }
            derived[member]?.removeAll { it < e - lookbackEpochs }
        }
        deriveMissing(e)
    }

    private fun deriveMissing(e: Long) {
        val ikm = this.ikm ?: return
        for (m in members) {
            val done = derived.getOrPut(m) { HashSet() }
            val tags = tagsOf.getOrPut(m) { HashSet() }
            for (i in maxOf(0L, e - lookbackEpochs)..(e + 1)) {
                if (!done.add(i)) continue
                for (k in 0 until DeadDrop.MAX_PER_EPOCH_ROOM) {
                    val key = DeadDrop.deriveDropKey(ikm, i, m, k)
                    if (table.containsKey(key.publicKey)) continue
                    table[key.publicKey] = Hit(m, key)
                    tags.add(key.publicKey)
                }
            }
        }
    }

    @Synchronized
    fun lookup(tag: String): Hit? = table[tag.lowercase()]

    @Synchronized
    fun size(): Int = table.size

    /**
     * A key to send on now for this member: a random counter in `range`
     * never used in this epoch by this device. Throws [EpochExhausted] when
     * the range is spent; the caller waits for the next epoch.
     */
    @Synchronized
    fun sendKey(member: String, now: Long, range: IntRange = 0 until DeadDrop.MAX_PER_EPOCH_ROOM): DeadDrop.DropKey {
        val ikm = checkNotNull(this.ikm) { "no room key" }
        val m = member.lowercase()
        require(range.first >= 0 && range.last < DeadDrop.MAX_PER_EPOCH_ROOM && !range.isEmpty()) { "counter range must lie inside [0, ${DeadDrop.MAX_PER_EPOCH_ROOM})" }
        val e = DeadDrop.epochIndexAt(now, epochSeconds)
        val u = usedFor(m, e)
        val free = range.count { it !in u }
        if (free == 0) throw EpochExhausted(e, range.count())
        val span = range.count()
        val limit = 65536 - (65536 % span)
        var counter: Int
        do {
            val b = random(2)
            val r = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
            if (r >= limit) { counter = -1; continue }
            counter = range.first + (r % span)
        } while (counter < 0 || counter in u)
        u.add(counter)
        return DeadDrop.deriveDropKey(ikm, e, m, counter)
    }

    private fun usedFor(member: String, e: Long): MutableSet<Int> {
        val entry = used[member]
        if (entry == null || entry.first != e) { val fresh = HashSet<Int>(); used[member] = e to fresh; return fresh }
        return entry.second
    }

    /** A counter seen on the wire for `member` in the current epoch is spent here too. Another epoch is ignored. */
    @Synchronized
    fun markUsed(member: String, epochIndex: Long, counter: Int, now: Long) {
        if (epochIndex != DeadDrop.epochIndexAt(now, epochSeconds) || counter < 0) return
        usedFor(member.lowercase(), epochIndex).add(counter)
    }

    @Synchronized
    fun exportUsed(): Map<String, UsedCounters> = used.mapValues { (_, v) -> UsedCounters(v.first, v.second.toSet()) }

    /** Restore what [exportUsed] gave before a restart; another epoch's entries are ignored. */
    @Synchronized
    fun importUsed(state: Map<String, UsedCounters>, now: Long) {
        val e = DeadDrop.epochIndexAt(now, epochSeconds)
        for ((member, u) in state) if (u.epoch == e) used[member.lowercase()] = e to u.counters.filter { it >= 0 }.toMutableSet()
    }
}

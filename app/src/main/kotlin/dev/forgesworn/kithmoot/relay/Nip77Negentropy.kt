package dev.forgesworn.kithmoot.relay

import java.security.MessageDigest

/**
 * A bounded NIP-77 Negentropy V1 initiator.
 *
 * This deliberately reconciles only `(created_at, event id)` records.  It has
 * no access to event contents and therefore cannot turn an ID comparison into
 * an implicit fetch or publication.
 */
data class Nip77Record(val createdAt: ULong, val id: ByteArray) {
    init {
        require(createdAt != ULong.MAX_VALUE) { "NIP-77 reserves the maximum timestamp" }
        require(id.size == ID_BYTES) { "A NIP-77 event id must be 32 bytes" }
    }

    internal fun copyForProtocol() = Nip77Record(createdAt, id.copyOf())

    companion object { const val ID_BYTES = 32 }
}

data class Nip77Reconciliation(
    /** IDs this client has and the relay does not. */
    val have: List<ByteArray>,
    /** IDs the relay has and this client does not. */
    val need: List<ByteArray>,
    /** The next NEG-MSG payload, or null when reconciliation is complete. */
    val nextMessage: ByteArray?,
)

/** A protocol error is terminal for the surrounding NEG subscription. */
class Nip77ProtocolException(message: String) : IllegalArgumentException(message)

/**
 * V1 implementation of the Negentropy appendix in NIP-77.
 *
 * The storage is frozen at construction.  The caller must create a fresh
 * instance for every explicit reconciliation action, so an account, filter or
 * box change cannot continue an old authority snapshot.
 */
class Nip77Negentropy(records: Collection<Nip77Record>, private val frameLimit: Int = MAX_MESSAGE_BYTES) {
    private val records = records.map { it.copyForProtocol() }
        .also {
            require(it.size <= MAX_RECORDS) { "NIP-77 is limited to $MAX_RECORDS local records" }
            require(frameLimit in MIN_FRAME_BYTES..MAX_MESSAGE_BYTES) { "Invalid NIP-77 frame limit" }
        }
        .sortedWith(::compareRecords)
        .fold(mutableListOf<Nip77Record>()) { distinct, record ->
            if (distinct.lastOrNull()?.let { compareRecords(it, record) == 0 } != true) distinct += record
            distinct
        }

    private var initiated = false
    private var lastTimestampIn = 0uL
    private var lastTimestampOut = 0uL

    fun initiate(): ByteArray {
        check(!initiated) { "NIP-77 initial message was already made" }
        initiated = true
        lastTimestampOut = 0uL
        return byteArrayOf(PROTOCOL_VERSION) + splitRange(0, records.size, Bound.infinity())
    }

    /** Process one relay NEG-MSG and return the next message, if any. */
    fun reconcile(message: ByteArray): Nip77Reconciliation {
        check(initiated) { "NIP-77 must begin with NEG-OPEN" }
        require(message.size in 1..frameLimit) { "Invalid NIP-77 message size" }
        lastTimestampIn = 0u
        lastTimestampOut = 0u
        val input = Cursor(message)
        val version = input.byte().toInt() and 0xff
        if (version !in 0x60..0x6f) throw Nip77ProtocolException("Invalid NIP-77 protocol version")
        if (version != (PROTOCOL_VERSION.toInt() and 0xff)) {
            throw Nip77ProtocolException("The relay does not support NIP-77 V1")
        }

        val output = Bytes().apply { byte(PROTOCOL_VERSION) }
        val have = mutableListOf<ByteArray>()
        val need = mutableListOf<ByteArray>()
        var previous = Bound.zero()
        var previousIndex = 0
        var skip = false

        while (!input.exhausted()) {
            val current = decodeBound(input)
            if (compareBounds(previous, current) > 0) throw Nip77ProtocolException("NIP-77 ranges are not ordered")
            val lower = previousIndex
            var upper = lowerBound(previousIndex, records.size, current)
            val rangeOutput = Bytes()
            when (val mode = input.varint()) {
                MODE_SKIP -> skip = true
                MODE_FINGERPRINT -> {
                    val theirs = input.bytes(FINGERPRINT_BYTES)
                    if (!theirs.contentEquals(fingerprint(lower, upper))) {
                        if (skip) {
                            skip = false
                            encodeBound(rangeOutput, previous)
                            rangeOutput.varint(MODE_SKIP)
                        }
                        rangeOutput.append(splitRange(lower, upper, current))
                    } else {
                        skip = true
                    }
                }
                MODE_IDS -> {
                    val count = input.varint().boundedInt(MAX_RECORDS.toULong(), "NIP-77 ID count")
                    val theirs = linkedMapOf<String, ByteArray>()
                    repeat(count) {
                        val id = input.bytes(Nip77Record.ID_BYTES)
                        theirs[id.hex()] = id
                    }
                    for (index in lower until upper) {
                        val ours = records[index].id
                        if (theirs.remove(ours.hex()) == null) have += ours.copyOf()
                    }
                    need += theirs.values.map(ByteArray::copyOf)
                    skip = true
                }
                else -> throw Nip77ProtocolException("Unexpected NIP-77 range mode $mode")
            }

            if (wouldExceed(output.size + rangeOutput.size)) {
                encodeBound(output, Bound.infinity())
                output.varint(MODE_FINGERPRINT)
                output.append(fingerprint(upper, records.size))
                break
            }
            output.append(rangeOutput.toByteArray())
            previousIndex = upper
            previous = current
        }

        val next = output.toByteArray().takeUnless { it.size == 1 }
        return Nip77Reconciliation(have, need, next)
    }

    private fun splitRange(lower: Int, upper: Int, upperBound: Bound): ByteArray {
        val output = Bytes()
        val count = upper - lower
        if (count < DOUBLE_BUCKETS) {
            encodeBound(output, upperBound)
            output.varint(MODE_IDS)
            output.varint(count.toULong())
            for (index in lower until upper) output.append(records[index].id)
            return output.toByteArray()
        }

        val perBucket = count / BUCKETS
        val extras = count % BUCKETS
        var current = lower
        repeat(BUCKETS) { bucket ->
            val size = perBucket + if (bucket < extras) 1 else 0
            val next = current + size
            val boundary = if (next == upper) upperBound else minimalBound(records[next - 1], records[next])
            encodeBound(output, boundary)
            output.varint(MODE_FINGERPRINT)
            output.append(fingerprint(current, next))
            current = next
        }
        return output.toByteArray()
    }

    private fun wouldExceed(size: Int) = size > frameLimit - FRAME_HEADROOM

    private fun decodeBound(input: Cursor): Bound {
        val encoded = input.varint()
        val timestamp = if (encoded == 0uL) ULong.MAX_VALUE else {
            val offset = encoded - 1uL
            val value = lastTimestampIn + offset
            if (value < lastTimestampIn) throw Nip77ProtocolException("NIP-77 timestamp overflow")
            value
        }
        lastTimestampIn = timestamp
        val length = input.varint().boundedInt(Nip77Record.ID_BYTES.toULong(), "NIP-77 ID prefix")
        return Bound(timestamp, input.bytes(length))
    }

    private fun encodeBound(output: Bytes, bound: Bound) {
        val encoded = if (bound.timestamp == ULong.MAX_VALUE) {
            lastTimestampOut = ULong.MAX_VALUE
            0uL
        } else {
            if (lastTimestampOut == ULong.MAX_VALUE || bound.timestamp < lastTimestampOut) {
                throw Nip77ProtocolException("NIP-77 output ranges are not ordered")
            }
            val delta = bound.timestamp - lastTimestampOut
            lastTimestampOut = bound.timestamp
            delta + 1uL
        }
        output.varint(encoded)
        output.varint(bound.prefix.size.toULong())
        output.append(bound.prefix)
    }

    private fun lowerBound(first: Int, last: Int, value: Bound): Int {
        var low = first
        var high = last
        while (low < high) {
            val middle = low + (high - low) / 2
            if (compareRecordBound(records[middle], value) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    private fun fingerprint(lower: Int, upper: Int): ByteArray {
        val sum = ByteArray(Nip77Record.ID_BYTES)
        for (index in lower until upper) {
            var carry = 0
            for (offset in sum.indices) {
                val total = (sum[offset].toInt() and 0xff) + (records[index].id[offset].toInt() and 0xff) + carry
                sum[offset] = total.toByte()
                carry = total ushr 8
            }
        }
        val count = Bytes().apply { append(sum); varint((upper - lower).toULong()) }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(count).copyOf(FINGERPRINT_BYTES)
    }

    private fun minimalBound(previous: Nip77Record, current: Nip77Record): Bound {
        if (previous.createdAt != current.createdAt) return Bound(current.createdAt, ByteArray(0))
        var shared = 0
        while (shared < Nip77Record.ID_BYTES && previous.id[shared] == current.id[shared]) shared++
        return Bound(current.createdAt, current.id.copyOf(shared + 1))
    }

    private data class Bound(val timestamp: ULong, val prefix: ByteArray) {
        fun fullId() = prefix.copyOf(Nip77Record.ID_BYTES)
        companion object {
            fun zero() = Bound(0uL, ByteArray(0))
            fun infinity() = Bound(ULong.MAX_VALUE, ByteArray(0))
        }
    }

    private class Cursor(private val data: ByteArray) {
        private var position = 0
        fun exhausted() = position == data.size
        fun byte(): Byte {
            if (position >= data.size) throw Nip77ProtocolException("NIP-77 message ends prematurely")
            return data[position++]
        }
        fun bytes(size: Int): ByteArray {
            if (size < 0 || position > data.size - size) throw Nip77ProtocolException("NIP-77 message ends prematurely")
            return data.copyOfRange(position, position + size).also { position += size }
        }
        fun varint(): ULong {
            val encoded = Bytes()
            var value = 0uL
            repeat(10) {
                val next = byte().toInt() and 0xff
                encoded.byte(next.toByte())
                if (value > (ULong.MAX_VALUE shr 7)) throw Nip77ProtocolException("NIP-77 varint overflow")
                value = (value shl 7) or (next and 0x7f).toULong()
                if (next and 0x80 == 0) {
                    if (!encoded.toByteArray().contentEquals(encodeVarint(value))) {
                        throw Nip77ProtocolException("NIP-77 varint is not canonical")
                    }
                    return value
                }
            }
            throw Nip77ProtocolException("NIP-77 varint is too long")
        }
    }

    private class Bytes {
        private val bytes = ArrayList<Byte>()
        val size get() = bytes.size
        fun byte(value: Byte) { bytes += value }
        fun append(value: ByteArray) { value.forEach(::byte) }
        fun varint(value: ULong) { append(encodeVarint(value)) }
        fun toByteArray() = bytes.toByteArray()
    }

    companion object {
        const val MAX_RECORDS = 127
        const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MIN_FRAME_BYTES = 4096
        private const val FRAME_HEADROOM = 200
        private const val FINGERPRINT_BYTES = 16
        private const val PROTOCOL_VERSION: Byte = 0x61
        private const val MODE_SKIP = 0uL
        private const val MODE_FINGERPRINT = 1uL
        private const val MODE_IDS = 2uL
        private const val BUCKETS = 16
        private const val DOUBLE_BUCKETS = BUCKETS * 2

        private fun compareRecords(left: Nip77Record, right: Nip77Record): Int =
            left.createdAt.compareTo(right.createdAt).takeIf { it != 0 } ?: compareIds(left.id, right.id)

        private fun compareRecordBound(record: Nip77Record, bound: Bound): Int =
            record.createdAt.compareTo(bound.timestamp).takeIf { it != 0 } ?: compareIds(record.id, bound.fullId())

        private fun compareBounds(left: Bound, right: Bound): Int =
            left.timestamp.compareTo(right.timestamp).takeIf { it != 0 } ?: compareIds(left.fullId(), right.fullId())

        private fun compareIds(left: ByteArray, right: ByteArray): Int {
            for (index in left.indices) {
                val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return 0
        }

        private fun encodeVarint(value: ULong): ByteArray {
            if (value == 0uL) return byteArrayOf(0)
            var source = value
            val output = ArrayList<Byte>()
            while (source > 0u) {
                output += (source and 0x7fu).toByte()
                source = source shr 7
            }
            output.reverse()
            for (index in 0 until output.lastIndex) output[index] = (output[index].toInt() or 0x80).toByte()
            return output.toByteArray()
        }

        private fun ULong.boundedInt(max: ULong, label: String): Int {
            if (this > max) throw Nip77ProtocolException("$label exceeds its bound")
            return toInt()
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

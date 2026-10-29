package dev.forgesworn.kithmoot.lab

import kotlinx.serialization.json.*
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Lab-only capture primitives. Never logs or retains a captured plaintext value. */
internal object MetadataPrimitives {
    val roles = listOf("aliceRootHex", "aliceRootNpub", "bobRootHex", "bobRootNpub", "aliceDevice", "bobDevice",
        "aliceRzTarget", "bobRzTarget", "pNode", "wNode", "ordinaryRoomId", "mlsSessionId", "inviteContext", "messageAlice", "messageBob")
    fun wellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) { if (i == value.length || !value[i++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    fun mac(salt: ByteArray, domain: String): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(salt, "HmacSHA256")); update(domain.toByteArray(Charsets.UTF_8)); update(0.toByte())
    }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun digest(salt: ByteArray, domain: String, bytes: ByteArray) = hex(mac(salt, domain).doFinal(bytes))
    fun digest(salt: ByteArray, domain: String, value: String) = digest(salt, domain, value.toByteArray(Charsets.UTF_8))
    fun origin(url: String): String {
        require(wellFormed(url)); val u = URI(url); val scheme = requireNotNull(u.scheme).lowercase()
        require(scheme in setOf("http", "https", "ws", "wss", "native"))
        val host = requireNotNull(u.host).lowercase()
        val port = if (u.port < 0 || (scheme in setOf("https", "wss") && u.port == 443) || (scheme in setOf("http", "ws") && u.port == 80)) "" else ":${u.port}"
        return "$scheme://$host$port"
    }

    /** KMP presence (0/1), not occurrence counts; state survives arbitrary chunk boundaries. */
    class Presence(private val term: ByteArray) {
        private val failure = IntArray(term.size)
        private var matched = 0
        var found = false; private set
        init {
            require(term.isNotEmpty())
            var j = 0
            for (i in 1 until term.size) {
                while (j > 0 && term[i] != term[j]) j = failure[j - 1]
                if (term[i] == term[j]) j++
                failure[i] = j
            }
        }
        fun byte(value: Byte) {
            if (found) return
            while (matched > 0 && term[matched] != value) matched = failure[matched - 1]
            if (term[matched] == value) matched++
            if (matched == term.size) found = true
        }
    }

    /** WHATWG UTF-8 replacement semantics used by Node Buffer.toString('utf8').
     * Only <=4 pending encoded bytes survive a chunk; malformed bytes never become '?' silently. */
    class NodeUtf8(private val emit: (Byte) -> Unit) {
        private var needed = 0; private var seen = 0
        private var lower = 0x80; private var upper = 0xbf
        private val pending = ByteArray(4)
        private fun replacement() { for (b in byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte())) emit(b) }
        fun byte(value: Byte) {
            val b = value.toInt() and 255
            if (needed == 0) {
                when {
                    b <= 0x7f -> emit(value)
                    b in 0xc2..0xdf -> { needed = 1; pending[0] = value }
                    b in 0xe0..0xef -> { needed = 2; pending[0] = value; if (b == 0xe0) lower = 0xa0; if (b == 0xed) upper = 0x9f }
                    b in 0xf0..0xf4 -> { needed = 3; pending[0] = value; if (b == 0xf0) lower = 0x90; if (b == 0xf4) upper = 0x8f }
                    else -> replacement()
                }
                return
            }
            if (b !in lower..upper) {
                needed = 0; seen = 0; lower = 0x80; upper = 0xbf
                replacement(); byte(value); return
            }
            lower = 0x80; upper = 0xbf; pending[++seen] = value
            if (seen == needed) { for (i in 0..seen) emit(pending[i]); needed = 0; seen = 0 }
        }
        fun finish() { if (needed != 0) replacement(); needed = 0; seen = 0; pending.fill(0) }
    }

    /** Raw bytes feed the wire HMAC; UTF-8 replacement is ONLY the literal matching view. */
    class Value(salt: ByteArray, terms: Map<String, String>) {
        private val hmac = mac(salt, "wire")
        private val matchers = roles.associateWith { Presence(requireNotNull(terms[it]).toByteArray(Charsets.UTF_8)) }
        private val decoder = NodeUtf8 { b -> matchers.values.forEach { it.byte(b) } }
        var bytes = 0L; private set
        private var ended = false
        fun write(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
            check(!ended); require(offset >= 0 && count >= 0 && offset <= data.size - count)
            hmac.update(data, offset, count); bytes += count
            for (i in offset until offset + count) decoder.byte(data[i])
        }
        fun finish(): Pair<String, Map<String, Int>> {
            check(!ended); ended = true; decoder.finish()
            return hex(hmac.doFinal()) to matchers.mapValues { if (it.value.found) 1 else 0 }
        }
    }

    fun presence(terms: Map<String, String>, value: String) = roles.associateWith { if (value.contains(requireNotNull(terms[it]))) 1 else 0 }

    /** JSON.stringify-compatible for protocol strings, safe integer numbers and object key ordering.
     * Non-integral/unsafe numeric JSON is explicit unsupported capture, never a false match result. */
    fun stringify(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonArray -> value.joinToString(",", "[", "]") { stringify(it) }
        is JsonObject -> {
            val keys = value.keys.sortedWith(compareBy<String> { if (arrayIndex(it) != null) 0 else 1 }
                .thenComparator { a, b -> if (arrayIndex(a) != null && arrayIndex(b) != null) arrayIndex(a)!!.compareTo(arrayIndex(b)!!) else 0 })
            keys.joinToString(",", "{", "}") { JsonPrimitive(it.also { key -> require(wellFormed(key)) { "unsupported JSON Unicode" } }).toString() + ":" + stringify(value.getValue(it)) }
        }
        is JsonPrimitive -> when {
            value.isString -> value.toString().also { require(wellFormed(value.content)) { "unsupported JSON Unicode" } }
            value.booleanOrNull != null -> value.content
            else -> {
                val number = value.content.toBigDecimalOrNull() ?: error("unsupported JSON number")
                val integer = try { number.toBigIntegerExact() } catch (_: ArithmeticException) { error("unsupported JSON number") }
                require(integer.abs() <= java.math.BigInteger("9007199254740991")) { "unsupported JSON number" }
                integer.toString()
            }
        }
    }
    private fun arrayIndex(key: String): Long? = key.toLongOrNull()?.takeIf { it in 0..4294967294L && it.toString() == key }
}

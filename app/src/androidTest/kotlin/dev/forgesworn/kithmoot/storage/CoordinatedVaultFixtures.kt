package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.WitnessAnswer
import dev.forgesworn.kithmoot.account.WitnessChannel
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * An in-process restore witness for instrumentation tests (P3-03b-2, C8):
 * bothy's `read` and `advance` semantics over vennel's canonical CBOR
 * requests, answered with real 170-byte receipts signed with Ed25519
 * (BouncyCastle) over SHA-256("VMLS/1 witness receipt" || bytes[0..106]),
 * which the VMLS engine verifies against the pinned key.
 */
class FakeEd25519Witness(random: SecureRandom = SecureRandom()) {
    enum class Mode { Up, Down, LoseAnswer, WrongKey }

    class Subject(var seq: Long, var digest: ByteArray, var retired: Boolean = false)

    private val key = Ed25519PrivateKeyParameters(random)
    private val other = Ed25519PrivateKeyParameters(random)
    /** The pinned witness key: the box's Link node id in production. */
    val publicKey: ByteArray = key.generatePublicKey().encoded
    val subjects = mutableMapOf<String, Subject>()
    @Volatile var mode = Mode.Up
    var advances = 0

    fun enrol(subject: String, digest: String) {
        subjects[subject] = Subject(0, digest.hexToBytes())
    }

    fun seq(subject: String): Long = subjects.getValue(subject).seq

    val channel = object : WitnessChannel {
        override suspend fun read(request: ByteArray): WitnessAnswer = synchronized(this@FakeEd25519Witness) { answer(request, advance = false) }
        override suspend fun advance(request: ByteArray): WitnessAnswer = synchronized(this@FakeEd25519Witness) { answer(request, advance = true) }
    }

    private fun answer(request: ByteArray, advance: Boolean): WitnessAnswer {
        if (mode == Mode.Down) return WitnessAnswer.Unavailable
        val fields = Cbor(request).map(if (advance) 6 else 3)
        require((fields.getValue(1) as Long) == 1L)
        val subject = fields.getValue(2) as ByteArray
        val sub = subjects[subject.toHex()] ?: return WitnessAnswer.Refused
        val status = if (!advance) {
            if (sub.retired) 2 else 0
        } else {
            advances++
            val expectedSeq = fields.getValue(3) as Long
            val expected = fields.getValue(4) as ByteArray
            val next = fields.getValue(5) as ByteArray
            when {
                sub.retired -> 2
                expectedSeq > sub.seq -> { sub.retired = true; 2 }
                sub.seq == expectedSeq + 1 && sub.digest.contentEquals(next) -> 0
                sub.seq != expectedSeq || !sub.digest.contentEquals(expected) -> 1
                else -> { sub.seq = expectedSeq + 1; sub.digest = next; 0 }
            }
        }
        val challenge = fields.getValue(if (advance) 6 else 3) as ByteArray
        val signed = ByteBuffer.allocate(106).put(1).put(status.toByte()).put(subject).putLong(sub.seq).put(sub.digest).put(challenge).array()
        val digest = MessageDigest.getInstance("SHA-256").run { update("VMLS/1 witness receipt".toByteArray(Charsets.US_ASCII)); update(signed); digest() }
        val signature = Ed25519Signer().run {
            init(true, if (mode == Mode.WrongKey) other else key)
            update(digest, 0, digest.size)
            generateSignature()
        }
        if (mode == Mode.LoseAnswer) return WitnessAnswer.Unavailable
        return WitnessAnswer.Receipt(signed + signature)
    }

    /** Just enough canonical CBOR: a map of small integer keys to uints or byte strings. */
    private class Cbor(private val bytes: ByteArray) {
        private var at = 0

        fun map(entries: Int): Map<Int, Any> {
            require(bytes[at++].toInt() and 0xff == 0xa0 + entries)
            val out = (0 until entries).associate { (uint().toInt()) to value() }
            require(at == bytes.size)
            return out
        }

        private fun value(): Any = when (bytes[at].toInt() and 0xe0) {
            0x00 -> uint()
            0x40 -> {
                val size = head(0x40).toInt()
                bytes.copyOfRange(at, at + size).also { at += size }
            }
            else -> error("unexpected CBOR")
        }

        private fun uint(): Long = head(0x00)

        private fun head(major: Int): Long {
            val first = bytes[at++].toInt() and 0xff
            require(first and 0xe0 == major)
            val info = first and 0x1f
            val width = when (info) { in 0..23 -> return info.toLong(); 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("bad CBOR") }
            var value = 0L
            repeat(width) { value = (value shl 8) or (bytes[at++].toLong() and 0xff) }
            return value
        }
    }
}

/**
 * Test-only software keys in a directory, so a whole profile (files and keys)
 * can be copied to a second directory (W06-W08). AndroidKeyStore keys cannot
 * be exported, so this tests the coordinator logic, not the Keystore.
 */
class FileSealKeys(private val directory: File) : SealKeys {
    init { directory.mkdirs() }

    override fun get(alias: String): SecretKey? = file(alias).takeIf { it.isFile }?.let { SecretKeySpec(it.readBytes(), "AES") }
    override fun create(alias: String): SecretKey {
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        file(alias).writeBytes(raw)
        return SecretKeySpec(raw, "AES")
    }
    override fun delete(alias: String) { file(alias).delete() }
    override fun aliases(): List<String> = directory.listFiles().orEmpty().map { it.name }

    private fun file(alias: String) = File(directory, alias)
}

/** Records each key deletion, and can fail the first one to stand in for a crash at that point. */
class RecordingSealKeys(private val inner: SealKeys, private val onDelete: (String) -> Unit = {}) : SealKeys by inner {
    var failNextDelete = false

    override fun delete(alias: String) {
        onDelete(alias)
        if (failNextDelete) {
            failNextDelete = false
            throw IllegalStateException("killed before the stale key was deleted")
        }
        inner.delete(alias)
    }
}

/** The unsigned `leaf-binding/1` body the engine would hand the vault to sign (as the JVM tests' `VmlsEncode`). */
object TestBindings {
    fun unsigned(leafId: ByteArray, signatureKey: ByteArray, credential: NostrEvent, device: String, expiresAt: Long, homeBox: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            head(5, 7)
            uint(1); uint(1)
            uint(2); bytes("vmls1".toByteArray(Charsets.US_ASCII) + leafId)
            uint(3); bytes(signatureKey)
            uint(4)
            head(5, 5)
            uint(1); bytes(credential.pubkey.hexToBytes())
            uint(2); uint(credential.createdAt)
            uint(3); head(4, credential.tags.size.toLong())
            for (tag in credential.tags) { head(4, tag.size.toLong()); tag.forEach { text(it) } }
            uint(4); text(credential.content)
            uint(5); bytes(credential.sig.hexToBytes())
            uint(5); bytes(device.hexToBytes())
            uint(6); uint(expiresAt)
            uint(7); bytes(homeBox)
        }.toByteArray()

    private fun ByteArrayOutputStream.head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> write(m or value.toInt())
            value < 0x100 -> { write(m or 24); write(value.toInt()) }
            value < 0x10000 -> { write(m or 25); be(value, 2) }
            value < 0x100000000 -> { write(m or 26); be(value, 4) }
            else -> { write(m or 27); be(value, 8) }
        }
    }

    private fun ByteArrayOutputStream.be(value: Long, width: Int) {
        for (i in width - 1 downTo 0) write(((value ushr (8 * i)) and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.uint(value: Long) = head(0, value)
    private fun ByteArrayOutputStream.uint(value: Int) = head(0, value.toLong())
    private fun ByteArrayOutputStream.bytes(b: ByteArray) { head(2, b.size.toLong()); write(b) }
    private fun ByteArrayOutputStream.text(s: String) { val b = s.toByteArray(Charsets.UTF_8); head(3, b.size.toLong()); write(b) }
}

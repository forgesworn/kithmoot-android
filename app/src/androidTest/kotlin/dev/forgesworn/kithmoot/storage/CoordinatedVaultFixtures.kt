package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.CoordinatedVaultStores
import dev.forgesworn.kithmoot.account.PersonaCoordination
import dev.forgesworn.kithmoot.account.PersonaFile
import dev.forgesworn.kithmoot.account.WitnessAnswer
import dev.forgesworn.kithmoot.account.WitnessChannel
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
 *
 * With [saved], its key and subjects live in that directory, outside the
 * vault's, and each change is saved before its answer is returned, so the
 * witness survives the force-stop of a kill test (W01-W04).
 */
class FakeEd25519Witness(random: SecureRandom = SecureRandom(), private val saved: File? = null) {
    /**
     * [Refuse] answers as a box that refuses the writer; [ReplayRead] answers
     * every read with the last receipt it signed for a read before, whatever
     * the challenge.
     */
    enum class Mode { Up, Down, LoseAnswer, WrongKey, Refuse, ReplayRead }

    class Subject(var seq: Long, var digest: ByteArray, var retired: Boolean = false)

    private val key = saved?.let { File(it, "key") }?.takeIf { it.isFile }?.let { Ed25519PrivateKeyParameters(it.readBytes(), 0) }
        ?: Ed25519PrivateKeyParameters(random).also { created -> saved?.let { File(it.apply { mkdirs() }, "key").writeBytes(created.encoded) } }
    private val other = Ed25519PrivateKeyParameters(random)
    /** The pinned witness key: the box's Link node id in production. */
    val publicKey: ByteArray = key.generatePublicKey().encoded
    val subjects = mutableMapOf<String, Subject>()
    @Volatile var mode = Mode.Up
    var advances = 0
    /** Every read and advance that reached the channel, answered or not. */
    var readCalls = 0
    var advanceCalls = 0
    /** Requests this fake could not parse: a broken fake would otherwise look like a held vault. */
    var malformed = 0
    private var lastRead: ByteArray? = null

    init {
        saved?.let { File(it, "subjects") }?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.forEach { line ->
            val (subject, seq, digest, retired) = line.split(' ')
            subjects[subject] = Subject(seq.toLong(), digest.hexToBytes(), retired.toBoolean())
        }
    }

    fun enrol(subject: String, digest: String) {
        synchronized(this) {
            subjects[subject] = Subject(0, digest.hexToBytes())
            save()
        }
    }

    fun seq(subject: String): Long = subjects.getValue(subject).seq

    val channel = object : WitnessChannel {
        override suspend fun read(request: ByteArray): WitnessAnswer = synchronized(this@FakeEd25519Witness) { readCalls++; parsed(request, advance = false) }
        override suspend fun advance(request: ByteArray): WitnessAnswer = synchronized(this@FakeEd25519Witness) { advanceCalls++; parsed(request, advance = true) }
    }

    private fun parsed(request: ByteArray, advance: Boolean): WitnessAnswer = try {
        answer(request, advance)
    } catch (error: IllegalArgumentException) {
        malformed++
        throw error
    }

    private fun answer(request: ByteArray, advance: Boolean): WitnessAnswer {
        if (mode == Mode.Down) return WitnessAnswer.Unavailable
        if (mode == Mode.Refuse) return WitnessAnswer.Refused
        if (mode == Mode.ReplayRead && !advance) lastRead?.let { return WitnessAnswer.Receipt(it.copyOf()) }
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
        save()
        val receipt = signed + signature
        if (!advance) lastRead = receipt.copyOf()
        if (mode == Mode.LoseAnswer) return WitnessAnswer.Unavailable
        return WitnessAnswer.Receipt(receipt)
    }

    /** Written whole and renamed into place: a force-stop never leaves half a file. */
    private fun save() {
        val directory = saved ?: return
        val next = File(directory, "subjects.new")
        next.writeText(subjects.entries.joinToString("\n") { (s, sub) -> "$s ${sub.seq} ${sub.digest.toHex()} ${sub.retired}" })
        check(next.renameTo(File(directory, "subjects")))
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
 * A kill at a chosen point. It is not an [Exception], so no handler in the
 * vault answers it as a failure it can recover from: everything after the
 * point simply never runs. The force-stop that follows is the real kill.
 */
class Killed(point: String) : Error("killed $point")

/**
 * Stores whose coordinated files can be killed just after a chosen write (an
 * injected decorator: nothing in the debug APK). The hook reads the shape of
 * what is written, never the number of writes: a state write with the stage
 * still set can come between the advance and the promotion.
 */
class HookedStores(private val inner: CoordinatedVaultStores) : CoordinatedVaultStores by inner {
    enum class Point {
        /** Just after a write that stages a candidate. */
        AfterStage,
        /** Just after the write that promotes a staged candidate. */
        AfterPromotion,
        /** The staging write itself fails: the stage is lost. */
        FailStage,
    }

    @Volatile private var armed: Point? = null
    @Volatile private var sawStaged = false
    /** The coordinated file name the last hook fired on. */
    @Volatile var hit: String? = null
        private set
    /** Per file: whether its last write, killed or not, still carried a staged candidate. */
    val lastWriteStaged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Per file: the sealed persona record of the last candidate staged there. */
    val lastStagedRecord = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun arm(point: Point) { armed = point; sawStaged = false; hit = null }

    override fun coordinated(name: String, aad: ByteArray): RoomStorage {
        val storage = inner.coordinated(name, aad)
        return object : RoomStorage by storage {
            override fun write(value: ByteArray) {
                val point = armed
                val candidate = PersonaFile.decode(value, value.copyOfRange(1, 33).toHex()).staged
                val staged = candidate != null
                if (point == Point.FailStage && staged) { fire(name); throw IOException("the stage was lost") }
                lastWriteStaged[name] = staged
                candidate?.get(PersonaCoordination.RECORD_HEX)?.let { lastStagedRecord[name] = it.copyOf() }
                storage.write(value)
                when (point) {
                    Point.AfterStage -> if (staged) { fire(name); throw Killed("after the stage") }
                    Point.AfterPromotion -> if (staged) sawStaged = true else if (sawStaged) { fire(name); throw Killed("after the promotion") }
                    else -> Unit
                }
            }
        }
    }

    private fun fire(name: String) { armed = null; hit = name }
}

/** Seal keys whose next deletion is a kill: after a commit, before its stale key goes. */
class HookedKeys(private val inner: SealKeys) : SealKeys by inner {
    @Volatile var killNextDelete = false

    override fun delete(alias: String) {
        if (killNextDelete) {
            killNextDelete = false
            throw Killed("before $alias was deleted")
        }
        inner.delete(alias)
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

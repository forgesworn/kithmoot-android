package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual platform operations; the only seams are causal gates in app-owned AtomicFile fields. */
@RunWith(AndroidJUnit4::class)
class AtomicFileOverlapTest {
    private lateinit var context: Context
    private lateinit var alias: String
    private lateinit var storage: EncryptedRoomStorage
    private val before = "first disposable storage value".toByteArray()
    private val replacement = "replacement disposable storage value".toByteArray()
    private val after = "second disposable storage value".toByteArray()
    private val base get() = File(context.noBackupFilesDir, "$alias.vault")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alias = "kithmoot.test.atomic-overlap.${UUID.randomUUID()}"
        storage = EncryptedRoomStorage(context, alias)
    }

    @After fun cleanup() {
        storage.reset()
        EncryptedRoomStorage(context, "$alias.other").reset()
        AtomicFile(File(context.noBackupFilesDir, "$alias.raw")).delete()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keys.aliases().toList().none { it == alias || it.startsWith("$alias.") })
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().none { it.name.startsWith(alias) })
        before.fill(0); replacement.fill(0); after.fill(0)
    }

    @Test fun real_atomic_file_reader_can_remove_another_instances_staged_replacement() {
        val raw = File(context.noBackupFilesDir, "$alias.raw")
        val writer = AtomicFile(raw)
        val reader = AtomicFile(raw)
        writer.startWrite().also { it.write(before); writer.finishWrite(it) }
        val stream = writer.startWrite()
        try {
            stream.write(replacement); stream.fd.sync()
            assertTrue(File(raw.path + ".new").isFile)
            assertArrayEquals(before, reader.openRead().use { it.readBytes() })
            assertFalse(File(raw.path + ".new").exists())
            writer.finishWrite(stream)
            assertArrayEquals(before, reader.readFully())
            assertFalse(File(raw.path + ".new").exists())
            println("NATIVE_ATOMIC_OVERLAP case=raw-platform stagingRemoved=true priorRetained=true replacementCommitted=false platformActual=true")
        } finally {
            writer.failWrite(stream)
        }
    }

    @Test fun same_path_read_waits_for_verified_commit_and_reads_the_replacement() {
        storage.write(before)
        val competing = EncryptedRoomStorage(context, alias)
        val result = runOverlap(competing, "read") { competing.read() }
        try {
            assertSerialized(result)
            assertArrayEquals(replacement, result.value)
            assertArrayEquals(replacement, EncryptedRoomStorage(context, alias).read())
            assertNoStaging()
            report("same-read")
        } finally { result.value?.fill(0) }
    }

    @Test fun same_path_second_write_waits_until_the_first_commit_is_verified() {
        storage.write(before)
        val competing = EncryptedRoomStorage(context, alias)
        val result = runOverlap(competing, "write") { competing.write(after); null }
        assertSerialized(result)
        assertArrayEquals(after, EncryptedRoomStorage(context, alias).read())
        assertNoStaging()
        report("same-write")
    }

    @Test fun same_path_reset_waits_for_commit_then_removes_ciphertext_and_key() {
        storage.write(before)
        val competing = EncryptedRoomStorage(context, alias)
        val result = runOverlap(competing, "reset") { competing.reset(); null }
        assertSerialized(result)
        assertNoStaging()
        assertFalse(base.exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        val fresh = EncryptedRoomStorage(context, alias)
        assertNull(fresh.read())
        fresh.write(after)
        assertArrayEquals(after, fresh.read())
        report("same-reset")
    }

    @Test fun unrelated_store_can_read_and_write_while_the_first_store_is_staged() {
        storage.write(before)
        val competing = EncryptedRoomStorage(context, "$alias.other")
        competing.write(after)
        val result = runOverlap(competing, "read", independent = true) {
            val old = competing.read()
            try { assertArrayEquals(after, old) } finally { old?.fill(0) }
            competing.write(before)
            competing.read()
        }
        try {
            assertEquals(Order.COMPLETED, result.order)
            assertTrue(result.enteredWhileHeld)
            assertTrue(result.stagingSurvived)
            assertNull(result.writerError)
            assertNull(result.competingError)
            assertArrayEquals(before, result.value)
            assertArrayEquals(replacement, EncryptedRoomStorage(context, alias).read())
            assertNoStaging()
            println("NATIVE_ATOMIC_OVERLAP case=different-path concurrentCompleted=true stagingPreserved=true workerClosed=true platformActual=true")
        } finally { result.value?.fill(0) }
    }

    private enum class Order { BLOCKED_AT_STORAGE, PLATFORM_ENTERED, COMPLETED }
    private data class Result(val order: Order, val enteredWhileHeld: Boolean,
        val stagingSurvived: Boolean, val writerError: Throwable?, val competingError: Throwable?,
        val value: ByteArray?)

    private fun assertSerialized(result: Result) {
        // Only static exception classes are included in a red assertion, never private exception messages.
        assertEquals("writerError=${result.writerError?.javaClass?.simpleName}; competingError=${result.competingError?.javaClass?.simpleName}",
            Order.BLOCKED_AT_STORAGE, result.order)
        assertFalse(result.enteredWhileHeld)
        assertTrue(result.stagingSurvived)
        assertNull(result.writerError)
        assertNull(result.competingError)
    }

    private fun report(case: String) {
        println("NATIVE_ATOMIC_OVERLAP case=$case sharedPathBlocked=true stagingPreserved=true committedVerified=true workerClosed=true platformActual=true")
    }

    private fun assertNoStaging() {
        assertFalse(File(base.path + ".new").exists())
        assertFalse(File(base.path + ".bak").exists())
    }

    private class ObservedFile(base: File, private val staged: CountDownLatch? = null,
        private val release: CountDownLatch? = null, private val entered: CountDownLatch? = null,
    ) : AtomicFile(base) {
        override fun finishWrite(stream: FileOutputStream?) {
            if (staged != null) {
                staged.countDown()
                check(requireNotNull(release).await(30, TimeUnit.SECONDS)) { "Staged transaction gate timed out" }
            }
            super.finishWrite(stream)
        }
        override fun openRead(): FileInputStream {
            entered?.countDown()
            return super.openRead()
        }
        override fun startWrite(): FileOutputStream {
            entered?.countDown()
            return super.startWrite()
        }
        override fun delete() {
            entered?.countDown()
            super.delete()
        }
    }

    private fun replaceAtomicFile(target: EncryptedRoomStorage, observed: AtomicFile) {
        EncryptedRoomStorage::class.java.getDeclaredField("file").apply { isAccessible = true }.set(target, observed)
    }

    private fun runOverlap(competing: EncryptedRoomStorage, method: String,
        independent: Boolean = false, action: () -> ByteArray?,
    ): Result {
        val staged = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val writerError = AtomicReference<Throwable?>()
        val competingError = AtomicReference<Throwable?>()
        val value = AtomicReference<ByteArray?>()
        replaceAtomicFile(storage, ObservedFile(base, staged = staged, release = release))
        val competingBase = if (independent) File(context.noBackupFilesDir, "$alias.other.vault") else base
        replaceAtomicFile(competing, ObservedFile(competingBase, entered = entered))
        val writer = Thread({
            try { storage.write(replacement) } catch (error: Throwable) { writerError.set(error) }
        }, "atomic-overlap-writer")
        val worker = Thread({
            ready.countDown()
            try { value.set(action()) } catch (error: Throwable) { competingError.set(error) }
            finally { done.countDown() }
        }, "atomic-overlap-competitor")
        var order: Order? = null
        var enteredWhileHeld = false
        var stagingSurvived = false
        try {
            writer.start()
            assertTrue("Writer must actually reach staged platform transaction", staged.await(15, TimeUnit.SECONDS))
            assertTrue(File(base.path + ".new").isFile)
            worker.start()
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            if (independent) {
                assertTrue("Different-path operation must finish before releasing first writer", done.await(15, TimeUnit.SECONDS))
                order = Order.COMPLETED
            } else {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (order == null && System.nanoTime() < deadline) {
                    val top = worker.stackTrace.firstOrNull()
                    order = when {
                        entered.count == 0L -> Order.PLATFORM_ENTERED
                        done.count == 0L -> Order.COMPLETED
                        worker.state == Thread.State.BLOCKED && top?.className == EncryptedRoomStorage::class.java.name &&
                            top.methodName == method -> Order.BLOCKED_AT_STORAGE
                        else -> null
                    }
                    if (order == null) Thread.yield()
                }
                assertNotNull("No causal competing-entry/completion/storage-monitor observation", order)
            }
            enteredWhileHeld = entered.count == 0L
            stagingSurvived = File(base.path + ".new").isFile
        } finally {
            release.countDown()
            writer.join(15000)
            if (worker.state != Thread.State.NEW) worker.join(15000)
            assertFalse("Writer survived fixture gate cleanup", writer.isAlive)
            assertFalse("Competing operation survived fixture gate cleanup", worker.isAlive)
        }
        return Result(requireNotNull(order), enteredWhileHeld, stagingSurvived,
            writerError.get(), competingError.get(), value.get())
    }
}

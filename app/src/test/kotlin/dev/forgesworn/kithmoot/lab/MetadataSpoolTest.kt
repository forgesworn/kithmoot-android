package dev.forgesworn.kithmoot.lab

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class MetadataSpoolTest {
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("kithmoot-metadata-spool-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun concurrentCallbacksUseBackpressureAndRetainMoreThan10000Records() = temporary { root ->
        val spool = MetadataSpool(File(root, "records"), writer = { directory, name, body ->
            if (name.endsWith(".ndjson")) Thread.sleep(1)
            File(directory, name).writeBytes(body)
        })
        val threads = 4
        val each = 2513
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val sequences = ConcurrentHashMap<Int, Int>()
        val workers = (0 until threads).map { worker ->
            Thread {
                ready.countDown(); start.await()
                for (index in 0 until each) {
                    val id = worker * each + index
                    sequences[id] = spool.append("""{"id":$id}""")
                }
            }
        }
        workers.forEach { it.start() }; ready.await(); start.countDown(); workers.forEach { it.join() }
        val manifest = spool.close()
        assertEquals(threads * each, manifest.getValue("acceptedRecords").jsonPrimitive.int)
        assertEquals(threads * each, manifest.getValue("committedRecords").jsonPrimitive.int)
        assertEquals(0, manifest.getValue("droppedRecords").jsonPrimitive.int)
        assertFalse(manifest.getValue("overflow").jsonPrimitive.boolean)
        assertEquals(sequences.size, threads * each)
        assertEquals((0 until threads * each).toSet(), sequences.values.toSet())
        var sequence = 0
        val restored = mutableSetOf<Int>()
        for (index in manifest.getValue("chunks").jsonArray.indices) {
            val chunk = spool.chunk(index)
            for (line in chunk.getValue("body").jsonPrimitive.content.trim().lines()) {
                val entry = Json.parseToJsonElement(line).jsonObject
                assertEquals(sequence++, entry.getValue("sequence").jsonPrimitive.int)
                restored += entry.getValue("record").jsonObject.getValue("id").jsonPrimitive.int
            }
        }
        assertEquals(sequences.keys, restored)
        spool.delete()
    }

    @Test fun commitFailureLeavesNoHealthyManifestAndStaysClosed() = temporary { root ->
        val directory = File(root, "records")
        val spool = MetadataSpool(directory, writer = { parent, name, body ->
            if (name == "COMMITTED") throw java.io.IOException("synthetic commit failure")
            File(parent, name).writeBytes(body)
        })
        spool.append("""{"id":0}""")
        assertThrows(IllegalStateException::class.java) { spool.close() }
        assertFalse(File(directory, "manifest.json").exists())
        assertFalse(File(directory, "COMMITTED").exists())
        assertFalse(spool.snapshot().getValue("complete").jsonPrimitive.boolean)
        assertEquals("write failed", spool.snapshot().getValue("failure").jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java) { spool.append("""{"id":1}""") }
    }

    @Test fun capacityRefusalFailsClosed() = temporary { root ->
        val directory = File(root, "records")
        val spool = MetadataSpool(directory, maxRecords = 1)
        spool.append("""{"id":0}""")
        assertThrows(IllegalStateException::class.java) { spool.append("""{"id":1}""") }
        val manifest = spool.close()
        assertTrue(manifest.getValue("overflow").jsonPrimitive.boolean)
        assertEquals(1, manifest.getValue("droppedRecords").jsonPrimitive.int)
        assertThrows(IllegalStateException::class.java) { spool.chunk(0) }
    }

    @Test fun chainMatchesTheNodeGeneratedVector() = temporary { root ->
        val spool = MetadataSpool(File(root, "records"), chunkRecords = 1)
        spool.append("""{"id":0}"""); spool.append("""{"id":1}""")
        val manifest = spool.close()
        val chunks = manifest.getValue("chunks").jsonArray
        assertEquals("46ab171d9a650db837d23143a54a69cb85b8e982ce9b2797a1886cdd1bd35a72",
            chunks[0].jsonObject.getValue("chainSha256").jsonPrimitive.content)
        assertEquals("31085541c00595ba44b80cea4cb62abf6160f682ee1c1debee4bce1aa1f7bccc",
            chunks[1].jsonObject.getValue("chainSha256").jsonPrimitive.content)
        assertEquals("31085541c00595ba44b80cea4cb62abf6160f682ee1c1debee4bce1aa1f7bccc",
            manifest.getValue("finalChainSha256").jsonPrimitive.content)
    }
}

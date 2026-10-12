package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.serialization.json.*
import kotlin.test.*

class RoomLogoCacheTest {
    private val now = 1_800_000_000L
    private val image = requireNotNull(LogoImages.read(Json.parseToJsonElement(requireNotNull(javaClass
        .getResourceAsStream("/room-logo-chat-v1.json")).bufferedReader().use { it.readText() }).jsonObject
        .getValue("vectors").jsonArray.first().jsonObject.getValue("expected").jsonObject.getValue("roomLogo")))
    private val logo = RoomLogoRecord(image, "a".repeat(32), now * 1000, now, "b".repeat(64))
    private fun room(at: Long = now): SavedRoom {
        val secret = Entropy.bytes(32)
        val who = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
        val host = createRoomInvitation(true)
        val relays = listOf("wss://own.example")
        return SavedRoom.create(secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays),
            relays, "Workshop", at, host, host.invitation.canonicalInviter)
    }

    @Test fun `a delayed accepted display cannot resurrect an image after removal`() {
        val saved = room(); val memory = RoomLogoMemory()
        val removed = logo.copy(image = null, at = logo.at + 1000, sentAt = now + 1)
        val oldEntered = java.util.concurrent.CountDownLatch(1)
        val releaseOld = java.util.concurrent.CountDownLatch(1)
        val newDisplayed = java.util.concurrent.CountDownLatch(1)
        val shown = java.util.concurrent.atomic.AtomicReference<LogoImage?>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val old = executor.submit {
                memory.rememberForDisplay(saved, logo, 2, now) { accepted ->
                    oldEntered.countDown()
                    check(releaseOld.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    shown.set(accepted?.record?.image)
                }
            }
            assertTrue(oldEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val newer = executor.submit {
                memory.rememberForDisplay(saved, removed, 2, now) { accepted ->
                    shown.set(accepted?.record?.image)
                    newDisplayed.countDown()
                }
            }
            // Allow an unguarded newer display to overtake the paused old one.
            // A guarded newer display instead waits until the old one finishes.
            newDisplayed.await(1, java.util.concurrent.TimeUnit.SECONDS)
            releaseOld.countDown()
            old.get(5, java.util.concurrent.TimeUnit.SECONDS)
            newer.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNull(assertNotNull(memory.seed(saved, now)).record.image)
            assertNull(shown.get(), "A delayed older display must not undo an accepted removal")
        } finally { releaseOld.countDown(); executor.shutdownNow() }
    }

    @Test fun `cache survives storage with its epoch and without borrowed authorship`() {
        val repository = RoomRepository(MemoryStorage()); val original = room().withSharedLogo(logo, 2)
        repository.save(original)
        val restored = assertNotNull(repository.get(original.id))
        assertEquals(2, restored.sharedLogo?.epoch)
        assertEquals(image.toJson(), restored.sharedLogo?.record?.image?.toJson())
        assertNull(restored.sharedLogo?.record?.by)
        assertEquals(original.openedAt, restored.openedAt)
        assertEquals(original.name, restored.name)
    }

    @Test fun `removal retains its ordering key instead of becoming a missing cache`() {
        val saved = room().withSharedLogo(logo.copy(image = null), 1)
        val restored = SavedRoom.decode(saved.json)
        assertNull(assertNotNull(restored.sharedLogo).record.image)
        assertEquals(logo.id, restored.sharedLogo?.record?.id)
        assertEquals(logo.at, restored.sharedLogo?.record?.at)
    }

    @Test fun `invalid optional images cannot make a saved admission unreadable`() {
        val saved = room().withSharedLogo(logo, 1)
        val cache = saved.json.getValue("sharedLogo").jsonObject
        val variants = listOf(
            JsonObject(cache - "image"), JsonObject(cache + ("epoch" to JsonPrimitive("1"))),
            JsonObject(cache + ("image" to JsonObject(image.toJson() + ("url" to JsonPrimitive("https://tracker.invalid"))))),
            JsonObject(cache + ("image" to JsonObject(image.toJson() + ("sha256" to JsonPrimitive("0".repeat(64))))))
        )
        for (bad in variants) {
            val restored = SavedRoom.decode(JsonObject(saved.json + ("sharedLogo" to bad)))
            assertEquals(saved.id, restored.id); assertEquals(saved.participant, restored.participant)
            assertNull(restored.sharedLogo)
            assertNull(restored.summary(now).logo)
        }
    }

    @Test fun `learning temporary lifetime or destruction strips persisted artwork permanently`() {
        val ordinary = room().withSharedLogo(logo, 0)
        val temporary = ordinary.withRoomLifetime(now + 60, false, now)
        assertFalse("sharedLogo" in temporary.json)
        assertFalse("sharedLogo" in temporary.withSharedLogo(logo, 0).json)
        assertFalse("sharedLogo" in ordinary.withDestruct().json)
        val refreshed = ordinary.retainingHistory(temporary)
        assertEquals(now + 60, refreshed.ends)
        assertNull(refreshed.sharedLogo)
        assertFalse("sharedLogo" in refreshed.json)
    }

    @Test fun `thumbnail eviction leaves all admission capabilities intact`() {
        val repository = RoomRepository(MemoryStorage())
        val originals = (0..64).map { room(now + it).withSharedLogo(logo, 0) }
        originals.forEach(repository::save)
        assertEquals(65, repository.list().size)
        val loaded = originals.map { assertNotNull(repository.get(it.id)) }
        assertEquals(64, loaded.count { it.sharedLogo?.record?.image != null })
        loaded.zip(originals).forEach { (saved, original) ->
            assertEquals(original.joinUrl, saved.joinUrl)
            assertEquals(original.participant, saved.participant)
            assertEquals(original.devicePubkey, saved.devicePubkey)
        }
        assertNull(loaded.first().sharedLogo)
        assertNotNull(loaded.last().sharedLogo)
    }

    @Test fun `memory artwork is scoped to the saved identity and expires without disk bytes`() {
        val saved = room().withRoomLifetime(now + 60, true, now)
        val memory = RoomLogoMemory()
        memory.remember(saved, logo, 2, now)
        assertEquals(image.toJson(), memory.seed(saved, now)?.record?.image?.toJson())
        assertEquals(image.toJson(), memory.decorate(listOf(saved.summary(now)), now).single().logo?.toJson())
        assertFalse("sharedLogo" in saved.json)
        val otherIdentity = JsonObject(saved.json.getValue("identity").jsonObject +
            ("deviceKey" to JsonPrimitive("03".repeat(32))))
        val replaced = SavedRoom.decode(JsonObject(saved.json + ("identity" to otherIdentity)))
        assertNull(memory.seed(replaced, now))
        memory.remember(saved, logo, 2, now)
        assertNull(memory.seed(saved, now + 60))
        assertNull(memory.decorate(listOf(saved.summary(now + 60)), now + 60).single().logo)
    }

    @Test fun `a late observation cannot replace a newer memory image or current epoch removal`() {
        val saved = room(); val memory = RoomLogoMemory()
        val removed = logo.copy(image = null, at = logo.at + 1000, sentAt = now + 1)
        memory.remember(saved, removed, 2, now)
        assertNull(assertNotNull(memory.remember(saved, logo, 1, now)).record.image)
        assertNull(assertNotNull(memory.remember(saved, logo, 2, now)).record.image)
        assertNull(assertNotNull(memory.seed(saved, now)).record.image)
        memory.remember(saved, null, 2, now)
        assertNotNull(memory.seed(saved, now))
        memory.forget(saved.id)
        assertNull(memory.seed(saved, now))
    }

    @Test fun `a second view cannot replace a newer disk cache with an older observation`() {
        val removed = logo.copy(image = null, at = logo.at + 1000, sentAt = now + 1)
        val saved = room().withSharedLogo(removed, 2)
        val memory = RoomLogoMemory()
        assertNull(assertNotNull(memory.remember(saved, logo, 2, now)).record.image)
        assertNull(assertNotNull(memory.remember(saved, logo, 1, now)).record.image)
        assertNull(memory.decorate(listOf(saved.summary(now)), now).single().logo)
        assertNull(memory.remember(saved, null, 3, now))
    }
}

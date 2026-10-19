package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.*

/** The inbox's mention mark, which only Chats' Mentions filter reads. Local storage; nothing on the wire. */
class BackgroundInboxMentionTest {
    private class Store(var bytes: ByteArray? = null) : RoomStorage {
        override fun read() = bytes?.copyOf()
        override fun write(value: ByteArray) { bytes = value.copyOf() }
        override fun reset() { bytes = null }
    }
    private val room = "1".repeat(64)
    private val me = "a".repeat(64)
    private val device = "d".repeat(64)
    private val other = "b".repeat(64)

    @Test fun `a message naming this person is kept as a mention, others are not, across a reread`() {
        val store = Store()
        val inbox = BackgroundInbox(store, room, me, device)
        assertTrue(inbox.record("e1", 100, ChatMessage("m1", other, "c".repeat(64), "for you", 100, mentions = listOf(me))))
        assertTrue(inbox.record("e2", 101, ChatMessage("m2", other, "c".repeat(64), "for nobody", 101)))
        val unread = BackgroundInbox(store, room, me, device).state().unread
        assertEquals(listOf(true, false), unread.map { it.mentioned })
    }

    @Test fun `a record written before the mark reads as no mention`() {
        val old = """{"v":1,"room":"$room","participant":"$me","device":"$device","cursor":5,"readThrough":0,""" +
            """"latestMessageAt":5,"seen":["e1"],"unread":[{"id":"m1","participant":"$other","sentAt":5}]}"""
        val unread = BackgroundInbox(Store(old.toByteArray()), room, me, device).state().unread
        assertEquals(listOf(BackgroundInbox.Unread("m1", other, 5, mentioned = false)), unread)
    }
}

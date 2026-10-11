package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProjectInvitationReviewTest {
    private val owner = LocalSigner(ByteArray(32) { 1 }).pubkey
    private val room = "ab".repeat(32)
    private val link = encodeInvitationUrl("https://fixture.invalid/j/", createRoomInvitation(true).invitation, emptyList())
    private fun project(id: Int = 1, room: String = this.room): SharedProject {
        val ref = ProjectReference(owner, id.toString(16).padStart(64, '0'))
        val definition = buildJsonObject {
            put("name", "Project $id"); put("archived", false); put("authorityRevision", 1)
            put("members", JsonArray(listOf(buildJsonObject { put("pubkey", owner); put("kind", "person"); put("epoch", 1) })))
            put("rooms", JsonArray(listOf(ProjectRoomChoice(room, "Workshop", link).toJson())))
        }
        return SharedProject(ref, listOf("cd".repeat(32)), 1, definition, Projects.authority(ref, definition), true, false, false)
    }
    private fun state(vararg projects: SharedProject) = StartState(account = AccountView(owner, npubOf(owner), "Owner", "local"),
        projects = ProjectAccountSnapshot(projects.toList(), ready = true))

    @Test fun `a known persistent invitation captures only display context and the reviewed account`() {
        val p = project(); val state = state(p); val review = projectInvitationReview(link, state)!!
        assertEquals(owner, review.account); assertEquals(npubOf(owner), review.npub)
        assertEquals(p.reference, review.project); assertEquals(room, review.room)
        assertTrue(projectInvitationReviewStillMatches(review, link, state))
        assertEquals(state(p), state, "A preview cannot change project membership or room storage")
        assertTrue(state.savedRooms.isEmpty())
    }

    @Test fun `unknown temporary anonymous and unjoined invitations do not invent a project preview`() {
        val p = project(); val state = state(p)
        val temporary = encodeInvitationUrl("https://fixture.invalid/j/", createRoomInvitation(false).invitation, emptyList())
        for (url in listOf("https://fixture.invalid/j/#unknown", temporary, "broken")) assertNull(projectInvitationReview(url, state))
        assertNull(projectInvitationReview(link, state.copy(anonymousMode = true)))
        assertNull(projectInvitationReview(link, state.copy(account = null)))
        assertNull(projectInvitationReview(link, state.copy(projects = state.projects.copy(ready = false))))
        for (hidden in listOf(p.copy(joined = false), p.copy(withdrawn = true), p.copy(conflicted = true))) {
            assertNull(projectInvitationReview(link, state(hidden)))
        }
    }

    @Test fun `withdrawal cannot substitute another project sharing the invitation`() {
        val first = project(); val second = project(2)
        val review = projectInvitationReview(link, state(first))!!
        assertNull(projectInvitationReview(link, state(first, second)))
        assertTrue(projectInvitationReviewStillMatches(review, link, state(first, second)))
        assertFalse(projectInvitationReviewStillMatches(review, link, state(second, first.copy(withdrawn = true))))
        assertFalse(projectInvitationReviewStillMatches(review, link, state(second)))
    }

    @Test fun `a changed account room or fragment invalidates review while a display name refresh does not`() {
        val state = state(project()); val review = projectInvitationReview(link, state)!!
        val other = LocalSigner(ByteArray(32) { 2 }).pubkey
        assertFalse(projectInvitationReviewStillMatches(review, link, state.copy(account = AccountView(other, npubOf(other), "Other", "local"))))
        assertFalse(projectInvitationReviewStillMatches(review, link, state(project(room = "ef".repeat(32)))))
        assertFalse(projectInvitationReviewStillMatches(review, "https://fixture.invalid/j/#changed", state))
        assertTrue(projectInvitationReviewStillMatches(review, link, state.copy(account = state.account!!.copy(short = "Changed display name"))))
    }
}

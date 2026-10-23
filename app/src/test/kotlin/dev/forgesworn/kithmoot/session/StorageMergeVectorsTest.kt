package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * fold-kit 0.14.0's storage merge vectors, read from the protocol module's
 * copy, against `requestPersistentAdmission`: whatever order a relay hands the
 * copies in, the newest copy that names storage stands, `[]` included, a copy
 * naming none says nothing, and storage merges apart from relays.
 */
class StorageMergeVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(File(System.getProperty("kithmoot.storageVectors")).readText()).jsonObject
    private fun vec(name: String) = root.getValue("groups").jsonObject.getValue("storage").jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    private suspend fun check(name: String, expectations: JsonObject) {
        val input = vec(name).getValue("input").jsonObject
        val inv = input.getValue("invitation").jsonObject
        val invitation = RoomInvitation(inv.getValue("bearerHex").jsonPrimitive.content.hexToBytes(), inv.getValue("inviter").jsonPrimitive.content, true)
        val copies = input.getValue("copies").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
        assertTrue(expectations.isNotEmpty())
        for ((order, outcome) in expectations) {
            val events = order.split(',').map { NostrEvent.fromJson(copies.getValue(it).getValue("event").jsonObject) }
            val admission = requestPersistentAdmission(invitation) { events }
            val lenient = outcome.jsonObject.getValue("lenient").jsonObject
            assertEquals(lenient.getValue("secretHex").jsonPrimitive.content, admission.secret.toHex(), "$name $order secret")
            assertEquals(lenient["storage"]?.jsonArray?.map { it.jsonPrimitive.content }, admission.storage, "$name $order storage")
            assertEquals(lenient["storageAt"]?.jsonPrimitive?.long, admission.storageAt, "$name $order storageAt")
            assertEquals(lenient["relays"]?.jsonArray?.map { it.jsonPrimitive.content }, admission.relays, "$name $order relays")
        }
    }

    @Test fun `copies merge to the newest that names storage, in every order, withdrawals and ties included`() = runTest {
        val expected = vec("storage-merge").getValue("expected").jsonObject
        for (case in listOf("newest", "withdrawn", "olderEmptyLoses", "tie")) check("storage-merge", expected.getValue(case).jsonObject)
    }

    @Test fun `storage and relays each come from their own newest copy`() = runTest {
        check("storage-relays-independent", vec("storage-relays-independent").getValue("expected").jsonObject.getValue("orders").jsonObject)
    }

    @Test fun `a copy from before storage existed beside one with it still gives the list`() = runTest {
        val v = vec("storage-strict-old-reader")
        val input = v.getValue("input").jsonObject
        val names = input.getValue("copies").jsonArray.joinToString(",") { it.jsonObject.getValue("name").jsonPrimitive.content }
        check("storage-strict-old-reader", JsonObject(mapOf(names to JsonObject(mapOf("lenient" to v.getValue("expected").jsonObject.getValue("lenient"))))))
    }
}

package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.BoxRequest
import dev.forgesworn.kithmoot.account.BoxRequestReply
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkPathState
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The box client against a scripted Link transport and signer (P3-03b-3a). */
class VmlsBoxClientTest {
    private val random = SecureRandom()
    private val box = bytes(32).toHex()
    private val sent = mutableListOf<LinkJsonRequest>()
    private val signed = mutableListOf<BoxRequest>()
    private var answer: (LinkJsonRequest) -> LinkJsonResponse = { error("no answer scripted") }
    private var refuseSigning: VaultRefusal? = null

    private val transport = LinkJsonTransport { request ->
        sent += request
        CompletableFuture.supplyAsync { answer(request) }
    }
    private val signer = BoxRequestSigner { request ->
        signed += request
        refuseSigning?.let { VaultResult.Refused(it) } ?: VaultResult.Ok(BoxRequestReply("d".repeat(64), "Nostr signed-${signed.size}", 1))
    }
    private val client = VmlsBoxClient(transport, "route-1", box, signer, timeoutMillis = 2_000)

    private fun reply(status: Int, body: String) = { _: LinkJsonRequest -> LinkJsonResponse(status, body.toByteArray(), PATH) }
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    // 121 bytes, so its base64 is padded and a stripped form is not canonical.
    private fun envelope() = bytes(121)

    // ---- requests ----

    @Test fun `a deposit is signed over the exact body and sent on the route, the receipt checked against the envelope`() = runBlocking<Unit> {
        val mailbox = bytes(32); val env = envelope()
        answer = reply(201, """{"v":1,"code":"stored","server_time":5,"receipt":"${Digests.sha256(env).toHex()}"}""")
        val result = client.deposit(mailbox, env)
        val ok = assertIs<BoxAnswer.Ok<Deposited>>(result)
        assertEquals(false, ok.value.duplicate)
        assertEquals(5L, ok.serverTime)
        assertNull(ok.value.welcomeAcknowledged)
        assertEquals(BoxRequest(box, "PUT", "/vmls/v1/mailboxes/${mailbox.toHex()}/records", Digests.sha256(env).toHex()), signed.single())
        val request = sent.single()
        assertEquals("route-1", request.routeId)
        assertEquals("Nostr signed-1", request.authorization)
        assertTrue(request.body.contentEquals(env))
    }

    @Test fun `a deposit answer naming another receipt, an unknown field or the wrong status is not trusted`() = runBlocking<Unit> {
        val env = envelope(); val good = Digests.sha256(env).toHex()
        for ((status, body) in listOf(
            201 to """{"v":1,"code":"stored","server_time":5,"receipt":"${bytes(32).toHex()}"}""",
            201 to """{"v":1,"code":"stored","server_time":5,"receipt":"$good","extra":1}""",
            200 to """{"v":1,"code":"stored","server_time":5,"receipt":"$good"}""",
            201 to """{"v":2,"code":"stored","server_time":5,"receipt":"$good"}""",
            201 to """{"v":1,"code":"stored","server_time":"5","receipt":"$good"}""",
            201 to """{"v":1,"code":"stored","server_time":5,"receipt":"${good.uppercase()}"}""",
            201 to "not json",
        )) {
            answer = reply(status, body)
            assertEquals(BoxAnswer.Malformed, client.deposit(bytes(32), env), body)
        }
        answer = reply(200, """{"v":1,"code":"duplicate","server_time":5,"receipt":"$good","welcome":{"acknowledged":true}}""")
        val dup = assertIs<BoxAnswer.Ok<Deposited>>(client.deposit(bytes(32), env)).value
        assertTrue(dup.duplicate)
        assertEquals(true, dup.welcomeAcknowledged)
    }

    /** A 197-byte slot receipt laid out as vmls-core's `SlotReceipt`, with a placeholder signature. */
    private fun signedReceipt(slot: ByteArray, attempt: Long, receipt: String, node: String = box): String {
        val out = ByteArray(197)
        out[0] = 1
        node.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(out, 1)
        bytes(32).copyInto(out, 33)
        slot.copyInto(out, 65)
        for (i in 0 until 4) out[97 + i] = (attempt shr (24 - 8 * i)).toByte()
        receipt.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(out, 101)
        bytes(64).copyInto(out, 133)
        return b64(out)
    }

    @Test fun `a slot deposit carries the winner's facts, checked against its own signed receipt`() = runBlocking<Unit> {
        val slot = bytes(32); val env = envelope(); val receipt = Digests.sha256(env).toHex()
        answer = reply(201, """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 3, receipt)}"}""")
        val won = assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(slot, 3, env)).value
        assertEquals(SlotDeposited.Outcome.Won, won.outcome)
        assertEquals(197, won.signedReceipt!!.size)
        // Not yet durable: no signed receipt is attached.
        answer = reply(200, """{"v":1,"code":"duplicate","server_time":5,"attempt":3,"receipt":"$receipt"}""")
        assertNull(assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(slot, 3, env)).value.signedReceipt)
        // Taken names another attempt and envelope, with or without its signed receipt.
        val other = bytes(32).toHex()
        answer = reply(409, """{"v":1,"code":"taken","server_time":5,"attempt":1,"receipt":"$other","signed_receipt":"${signedReceipt(slot, 1, other)}"}""")
        val taken = assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(slot, 3, env)).value
        assertEquals(SlotDeposited.Outcome.Taken, taken.outcome)
        assertEquals(1L, taken.attempt)
        answer = reply(409, """{"v":1,"code":"taken","server_time":5,"attempt":1,"receipt":"$other"}""")
        assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(slot, 3, env))
        for ((status, body) in listOf(
            201 to """{"v":1,"code":"won","server_time":5,"attempt":4,"receipt":"$receipt"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"${bytes(32).toHex()}"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${b64(bytes(196))}"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 3, receipt).dropLast(1)}"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":4294967296,"receipt":"$receipt"}""",
            // The signed receipt names another slot, attempt, envelope or box than the answer.
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt(bytes(32), 3, receipt)}"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 2, receipt)}"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 3, other)}"}""",
            201 to """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 3, receipt, node = bytes(32).toHex())}"}""",
            409 to """{"v":1,"code":"taken","server_time":5,"attempt":1,"receipt":"$other","signed_receipt":"${signedReceipt(slot, 2, other)}"}""",
        )) {
            answer = reply(status, body)
            assertEquals(BoxAnswer.Malformed, client.depositSlot(slot, 3, env), body)
        }
        // A 409 that is not `taken` is the box's refusal.
        answer = reply(409, """{"v":1,"code":"conflict","server_time":5}""")
        assertEquals(BoxAnswer.Refused(409, "conflict", 5), client.depositSlot(slot, 3, env))
        answer = reply(409, """{"v":1,"code":"consumed","server_time":5}""")
        assertEquals(BoxAnswer.Refused(409, "consumed", 5), client.deposit(bytes(32), env))
    }

    @Test fun `a slot status is read at the asked attempt and every state is checked`() = runBlocking<Unit> {
        val slot = bytes(32); val env = envelope(); val receipt = Digests.sha256(env).toHex()
        answer = reply(200, """{"v":1,"code":"empty","server_time":5}""")
        assertEquals(SlotState.State.Empty, assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value.state)
        assertEquals("/vmls/v1/slots/${slot.toHex()}/7/status", sent.last().path)
        assertEquals("""{"v":1}""", String(sent.last().body))
        answer = reply(200, """{"v":1,"code":"filled","server_time":5,"attempt":7,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 7, receipt)}","envelope":"${b64(env)}"}""")
        val filled = assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value
        assertTrue(filled.envelope!!.contentEquals(env))
        answer = reply(200, """{"v":1,"code":"void","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 2, receipt)}"}""")
        assertEquals(2L, assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value.attempt)
        answer = reply(200, """{"v":1,"code":"expired","server_time":5,"attempt":7,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 7, receipt)}"}""")
        assertEquals(SlotState.State.Expired, assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value.state)
        // A filled 1 MiB-bucket commit answers more than ordinary JSON's bound, and is still read.
        val big = bytes(1_048_576 + 44); val bigReceipt = Digests.sha256(big).toHex()
        answer = reply(200, """{"v":1,"code":"filled","server_time":5,"attempt":7,"receipt":"$bigReceipt","signed_receipt":"${signedReceipt(slot, 7, bigReceipt)}","envelope":"${b64(big)}"}""")
        assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7))
        for (body in listOf(
            """{"v":1,"code":"filled","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 2, receipt)}","envelope":"${b64(env)}"}""",
            """{"v":1,"code":"filled","server_time":5,"attempt":7,"receipt":"${bytes(32).toHex()}","signed_receipt":"${signedReceipt(slot, 7, receipt)}","envelope":"${b64(env)}"}""",
            """{"v":1,"code":"filled","server_time":5,"attempt":7,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 6, receipt)}","envelope":"${b64(env)}"}""",
            """{"v":1,"code":"void","server_time":5,"attempt":2,"receipt":"$receipt"}""",
            """{"v":1,"code":"void","server_time":5,"attempt":7,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 7, receipt)}"}""",
            """{"v":1,"code":"void","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 2, receipt)}","envelope":"${b64(env)}"}""",
            """{"v":1,"code":"expired","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"${signedReceipt(slot, 2, receipt)}"}""",
            """{"v":1,"code":"empty","server_time":5,"attempt":2}""",
            """{"v":1,"code":"pending","server_time":5}""",
        )) {
            answer = reply(200, body)
            assertEquals(BoxAnswer.Malformed, client.slotStatus(slot, 7), body)
        }
    }

    @Test fun `a fetch asks for exact mailboxes and trusts only records from them`() = runBlocking<Unit> {
        val a = bytes(32); val b = bytes(32); val env = envelope(); val receipt = Digests.sha256(env).toHex()
        answer = reply(200, """{"v":1,"code":"ok","server_time":5,"records":[{"mailbox":"${a.toHex()}","receipt":"$receipt","envelope":"${b64(env)}"}],"next":"abc_-="}""")
        val page = assertIs<BoxAnswer.Ok<FetchPage>>(client.fetch(listOf(a, b), after = "cur")).value
        assertEquals(1, page.records.size)
        assertEquals("abc_-=", page.next)
        assertEquals("""{"v":1,"mailboxes":["${a.toHex()}","${b.toHex()}"],"after":"cur"}""", String(sent.last().body))
        answer = reply(200, """{"v":1,"code":"ok","server_time":5,"records":[],"next":null}""")
        assertNull(assertIs<BoxAnswer.Ok<FetchPage>>(client.fetch(listOf(a))).value.next)
        // The box's real cursor: URL-safe unpadded base64.
        val cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(36))
        answer = reply(200, """{"v":1,"code":"ok","server_time":5,"records":[],"next":"$cursor"}""")
        assertEquals(cursor, assertIs<BoxAnswer.Ok<FetchPage>>(client.fetch(listOf(a))).value.next)
        val many = (1..65).joinToString(",") { """{"mailbox":"${a.toHex()}","receipt":"$receipt","envelope":"${b64(env)}"}""" }
        answer = reply(200, """{"v":1,"code":"ok","server_time":5,"records":[$many],"next":null}""")
        assertEquals(BoxAnswer.Malformed, client.fetch(listOf(a)))
        for (body in listOf(
            """{"v":1,"code":"ok","server_time":5,"records":[{"mailbox":"${bytes(32).toHex()}","receipt":"$receipt","envelope":"${b64(env)}"}],"next":null}""",
            """{"v":1,"code":"ok","server_time":5,"records":[{"mailbox":"${a.toHex()}","receipt":"${bytes(32).toHex()}","envelope":"${b64(env)}"}],"next":null}""",
            """{"v":1,"code":"ok","server_time":5,"records":[{"mailbox":"${a.toHex()}","receipt":"$receipt","envelope":"${b64(env).trimEnd('=')}"}],"next":null}""",
            """{"v":1,"code":"ok","server_time":5,"records":[{"mailbox":"${a.toHex()}","receipt":"$receipt","envelope":"${b64(env)}","x":1}],"next":null}""",
            """{"v":1,"code":"ok","server_time":5,"records":{},"next":null}""",
            """{"v":1,"code":"ok","server_time":5,"records":[],"next":"has space"}""",
        )) {
            answer = reply(200, body)
            assertEquals(BoxAnswer.Malformed, client.fetch(listOf(a)), body)
        }
    }

    @Test fun `an ack names records and its count is bounded by them`() = runBlocking<Unit> {
        val items = listOf(AckItem(bytes(32), bytes(32)), AckItem(bytes(32), bytes(32)))
        answer = reply(200, """{"v":1,"code":"marked","server_time":5,"acked":2}""")
        val acked = assertIs<BoxAnswer.Ok<Acked>>(client.ack(items)).value
        assertEquals(false, acked.deleted)
        assertEquals(2L, acked.acked)
        assertTrue(String(sent.last().body).startsWith("""{"v":1,"records":[{"mailbox":""""))
        answer = reply(200, """{"v":1,"code":"deleted","server_time":5,"acked":1}""")
        assertTrue(assertIs<BoxAnswer.Ok<Acked>>(client.ack(items)).value.deleted)
        for (acked in listOf("3", "-1", "\"2\"", null)) {
            answer = reply(200, if (acked == null) """{"v":1,"code":"deleted","server_time":5}""" else """{"v":1,"code":"deleted","server_time":5,"acked":$acked}""")
            assertEquals(BoxAnswer.Malformed, client.ack(items), acked)
        }
    }

    @Test fun `capabilities hand the engine the raw body`() = runBlocking<Unit> {
        val body = """{"v":1,"security_contract":1,"slot_receipts":1,"fork_evidence":1,"restore_fence":1,"installation":"${bytes(32).toHex()}"}"""
        answer = reply(200, body)
        assertEquals(body, String(assertIs<BoxAnswer.Ok<ByteArray>>(client.capabilities()).value))
        assertEquals(Digests.sha256(ByteArray(0)).toHex(), signed.last().payload)
        assertEquals(0, sent.last().body.size)
    }

    @Test fun `a package is registered with Bothy's exact body, and withdrawn with an empty one`() = runBlocking<Unit> {
        val id = bytes(32); val welcome = bytes(32); val sealed = envelope()
        answer = reply(201, """{"v":1,"code":"registered","server_time":5}""")
        assertEquals(true, assertIs<BoxAnswer.Ok<Registered>>(client.registerPackage(id, welcome, 1_900_000_000, sealed)).value.fresh)
        val body = """{"v":1,"welcome_mailbox":"${welcome.toHex()}","expires_at":1900000000,"ciphertext":"${b64(sealed)}"}"""
        assertEquals(body, String(sent.last().body))
        assertEquals(BoxRequest(box, "PUT", "/vmls/v1/packages/${id.toHex()}", Digests.sha256(body.toByteArray()).toHex()), signed.last())
        answer = reply(200, """{"v":1,"code":"unchanged","server_time":5}""")
        assertEquals(false, assertIs<BoxAnswer.Ok<Registered>>(client.registerPackage(id, welcome, 1_900_000_000, sealed)).value.fresh)
        // A code at the wrong status, or an extra field, is not the box's answer.
        for ((status, raw) in listOf(200 to "registered", 201 to "unchanged")) {
            answer = reply(status, """{"v":1,"code":"$raw","server_time":5}""")
            assertEquals(BoxAnswer.Malformed, client.registerPackage(id, welcome, 1_900_000_000, sealed))
        }
        answer = reply(201, """{"v":1,"code":"registered","server_time":5,"package":"x"}""")
        assertEquals(BoxAnswer.Malformed, client.registerPackage(id, welcome, 1_900_000_000, sealed))
        answer = reply(403, """{"v":1,"code":"authority","server_time":5}""")
        assertEquals(BoxAnswer.Refused(403, "authority", 5), client.registerPackage(id, welcome, 1_900_000_000, sealed))
        assertFailsWith<IllegalArgumentException> { client.registerPackage(id, welcome, 1, ByteArray(64 * 1024 + 1)) }
        assertFailsWith<IllegalArgumentException> { client.registerPackage(id, welcome, 1, ByteArray(0)) }

        answer = reply(200, """{"v":1,"code":"withdrawn","server_time":6}""")
        assertEquals(BoxAnswer.Ok(Unit, 6L), client.withdrawPackage(id))
        assertEquals(BoxRequest(box, "DELETE", "/vmls/v1/packages/${id.toHex()}", Digests.sha256(ByteArray(0)).toHex()), signed.last())
        assertEquals(0, sent.last().body.size)
        answer = reply(201, """{"v":1,"code":"withdrawn","server_time":6}""")
        assertEquals(BoxAnswer.Malformed, client.withdrawPackage(id))
    }

    @Test fun `a joiner's package is checked against Bothy's bounds before it is registered`() {
        val now = 1_900_000_000L
        val week = VmlsBoxClient.MAX_PACKAGE_LIFETIME_SECONDS
        assertTrue(VmlsBoxClient.packageAcceptable(now, now + 1, ByteArray(1)))
        assertTrue(VmlsBoxClient.packageAcceptable(now, now + week, ByteArray(64 * 1024)))
        assertEquals(false, VmlsBoxClient.packageAcceptable(now, now, ByteArray(1)))
        assertEquals(false, VmlsBoxClient.packageAcceptable(now, now + week + 1, ByteArray(1)))
        assertEquals(false, VmlsBoxClient.packageAcceptable(now, now + 1, ByteArray(0)))
        assertEquals(false, VmlsBoxClient.packageAcceptable(now, now + 1, ByteArray(64 * 1024 + 1)))
    }

    // ---- refusals and failures ----

    @Test fun `refusals keep the box's code, a body-less refusal is its status alone`() = runBlocking<Unit> {
        answer = reply(401, """{"v":1,"code":"replay","server_time":9}""")
        assertEquals(BoxAnswer.Refused(401, "replay", 9), client.capabilities())
        answer = reply(503, """{"v":1,"code":"restore-fenced","server_time":9}""")
        assertEquals(BoxAnswer.Refused(503, "restore-fenced", 9), client.capabilities())
        answer = { LinkJsonResponse(404, ByteArray(0), PATH) }
        assertEquals(BoxAnswer.Refused(404, null, null), client.capabilities())
        answer = reply(403, """{"v":1,"code":"Not A Code","server_time":9}""")
        assertEquals(BoxAnswer.Malformed, client.capabilities())
        answer = { LinkJsonResponse(204, ByteArray(0), PATH) }
        assertEquals(BoxAnswer.Malformed, client.capabilities())
        answer = { LinkJsonResponse(200, ByteArray(0), PATH) }
        assertEquals(BoxAnswer.Malformed, client.capabilities())
    }

    @Test fun `a refused signature sends nothing, and a transport failure or timeout is unreachable`() = runBlocking<Unit> {
        refuseSigning = VaultRefusal.WitnessPending
        assertEquals(BoxAnswer.NotSigned(VaultRefusal.WitnessPending), client.capabilities())
        assertTrue(sent.isEmpty())
        refuseSigning = null
        answer = { throw IllegalStateException("link down") }
        assertEquals(BoxAnswer.Unreachable, client.capabilities())
        val slow = VmlsBoxClient({ CompletableFuture<LinkJsonResponse>() }, "route-1", box, signer, timeoutMillis = 50)
        assertEquals(BoxAnswer.Unreachable, slow.capabilities())
        // A signer that throws is a fault: it propagates, and nothing is sent.
        val count = sent.size
        val faulty = VmlsBoxClient(transport, "route-1", box, { error("vault fault") })
        assertFailsWith<IllegalStateException> { faulty.capabilities() }
        assertEquals(count, sent.size)
    }

    @Test fun `every retry is signed afresh`() = runBlocking<Unit> {
        answer = reply(401, """{"v":1,"code":"replay","server_time":9}""")
        client.ack(listOf(AckItem(bytes(32), bytes(32))))
        client.ack(listOf(AckItem(bytes(32), bytes(32))))
        assertEquals(listOf("Nostr signed-1", "Nostr signed-2"), sent.map { it.authorization })
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private companion object { val PATH = LinkPathState("direct", null, "1.2.3.4:5", "test") }
}

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

    @Test fun `a slot deposit carries the winner's facts and checks a win is this envelope at this attempt`() = runBlocking<Unit> {
        val env = envelope(); val receipt = Digests.sha256(env).toHex(); val signedReceipt = b64(bytes(197))
        answer = reply(201, """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"$signedReceipt"}""")
        val won = assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(bytes(32), 3, env)).value
        assertEquals(SlotDeposited.Outcome.Won, won.outcome)
        assertEquals(197, won.signedReceipt!!.size)
        // Not yet durable: no signed receipt is attached.
        answer = reply(200, """{"v":1,"code":"duplicate","server_time":5,"attempt":3,"receipt":"$receipt"}""")
        assertNull(assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(bytes(32), 3, env)).value.signedReceipt)
        // Taken names another attempt and envelope.
        answer = reply(409, """{"v":1,"code":"taken","server_time":5,"attempt":1,"receipt":"${bytes(32).toHex()}","signed_receipt":"$signedReceipt"}""")
        val taken = assertIs<BoxAnswer.Ok<SlotDeposited>>(client.depositSlot(bytes(32), 3, env)).value
        assertEquals(SlotDeposited.Outcome.Taken, taken.outcome)
        assertEquals(1L, taken.attempt)
        // A "win" at another attempt, a short signed receipt, a non-canonical one: not trusted.
        for (body in listOf(
            """{"v":1,"code":"won","server_time":5,"attempt":4,"receipt":"$receipt"}""",
            """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${b64(bytes(196))}"}""",
            """{"v":1,"code":"won","server_time":5,"attempt":3,"receipt":"$receipt","signed_receipt":"${signedReceipt.dropLast(1)}"}""",
            """{"v":1,"code":"won","server_time":5,"attempt":4294967296,"receipt":"$receipt"}""",
        )) {
            answer = reply(201, body)
            assertEquals(BoxAnswer.Malformed, client.depositSlot(bytes(32), 3, env), body)
        }
        // A 409 that is not `taken` is the box's refusal.
        answer = reply(409, """{"v":1,"code":"conflict","server_time":5}""")
        assertEquals(BoxAnswer.Refused(409, "conflict", 5), client.depositSlot(bytes(32), 3, env))
    }

    @Test fun `a slot status is read at the asked attempt and every state is checked`() = runBlocking<Unit> {
        val slot = bytes(32); val env = envelope(); val receipt = Digests.sha256(env).toHex(); val sr = b64(bytes(197))
        answer = reply(200, """{"v":1,"code":"empty","server_time":5}""")
        assertEquals(SlotState.State.Empty, assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value.state)
        assertEquals("/vmls/v1/slots/${slot.toHex()}/7/status", sent.last().path)
        assertEquals("""{"v":1}""", String(sent.last().body))
        answer = reply(200, """{"v":1,"code":"filled","server_time":5,"attempt":7,"receipt":"$receipt","signed_receipt":"$sr","envelope":"${b64(env)}"}""")
        val filled = assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value
        assertTrue(filled.envelope!!.contentEquals(env))
        answer = reply(200, """{"v":1,"code":"void","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"$sr"}""")
        assertEquals(2L, assertIs<BoxAnswer.Ok<SlotState>>(client.slotStatus(slot, 7)).value.attempt)
        for (body in listOf(
            """{"v":1,"code":"filled","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"$sr","envelope":"${b64(env)}"}""",
            """{"v":1,"code":"filled","server_time":5,"attempt":7,"receipt":"${bytes(32).toHex()}","signed_receipt":"$sr","envelope":"${b64(env)}"}""",
            """{"v":1,"code":"void","server_time":5,"attempt":2,"receipt":"$receipt"}""",
            """{"v":1,"code":"void","server_time":5,"attempt":2,"receipt":"$receipt","signed_receipt":"$sr","envelope":"${b64(env)}"}""",
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
        answer = reply(200, """{"v":1,"code":"deleted","server_time":5,"acked":3}""")
        assertEquals(BoxAnswer.Malformed, client.ack(items))
    }

    @Test fun `capabilities hand the engine the raw body`() = runBlocking<Unit> {
        val body = """{"v":1,"security_contract":1,"slot_receipts":1,"fork_evidence":1,"restore_fence":1,"installation":"${bytes(32).toHex()}"}"""
        answer = reply(200, body)
        assertEquals(body, String(assertIs<BoxAnswer.Ok<ByteArray>>(client.capabilities()).value))
        assertEquals(Digests.sha256(ByteArray(0)).toHex(), signed.last().payload)
        assertEquals(0, sent.last().body.size)
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

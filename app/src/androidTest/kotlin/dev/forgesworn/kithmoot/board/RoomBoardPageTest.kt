package dev.forgesworn.kithmoot.board

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The pinned room board page in a real WebView, with this test as the app at the other end of its port: Girnel's
 * room view mounts over KithMoot's keyless board, a card typed into it reaches the app as a part to seal, and the
 * page asks for nothing outside its pinned files.
 */
class RoomBoardPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val self = "a".repeat(64)

    private fun hex(bytes: Int) = Random.nextBytes(bytes).joinToString("") { "%02x".format(it) }

    /** Everything the page said, in order, and the app's scripted answers to it. */
    private inner class App : RoomBoardPage.Listener {
        val heard = CopyOnWriteArrayList<JsonObject>()
        val records = HashMap<String, MutableList<String>>()
        lateinit var page: RoomBoardPage
        var gone = false
        private fun send(body: JsonObject) = page.post(JsonObject(mapOf("v" to JsonPrimitive(1)) + body).toString())
        private fun reply(re: String, body: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
            send(JsonObject(mapOf("t" to JsonPrimitive("reply"), "re" to JsonPrimitive(re), "ok" to JsonPrimitive(true)) + body))
        override fun onMessage(message: String) {
            val m = Json.parseToJsonElement(message).jsonObject
            heard += m
            val re = m["re"]?.jsonPrimitive?.content ?: ""
            when (m.getValue("t").jsonPrimitive.content) {
                "ready" -> {
                    send(buildJsonObject { put("t", "start"); put("self", self); put("invite", false); put("assign", false); put("files", false) })
                    send(buildJsonObject { put("t", "members"); put("members", buildJsonArray { add(buildJsonObject { put("pubkey", self); put("name", "Ada") }) }) })
                }
                "copy.read" -> reply(re, mapOf("unread" to JsonPrimitive(0)))
                "listen" -> send(buildJsonObject { put("t", "caughtUp"); put("gen", m.getValue("gen").jsonPrimitive.int) })
                "seal" -> reply(re, mapOf("event" to JsonPrimitive(hex(32)), "createdAt" to JsonPrimitive(System.currentTimeMillis() / 1000)))
                "publish" -> reply(re)
                "copy.add" -> {
                    val pieces = records.getOrPut(re) { mutableListOf() }
                    pieces += m.getValue("data").jsonPrimitive.content
                    if (pieces.size == m.getValue("count").jsonPrimitive.int) reply(re, mapOf("record" to JsonPrimitive(hex(32))))
                }
                "copy.remove" -> reply(re)
            }
        }
        override fun onGone() { gone = true }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** [script] in the page's own frame, as a string, waited for. */
    private fun evaluate(page: RoomBoardPage, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        onMain { page.view.evaluateJavascript(script) { result = it ?: ""; done.countDown() } }
        assertTrue("The page did not answer", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(what: String, seconds: Long = 30, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!check()) {
            assertTrue("Timed out: $what", System.nanoTime() < until)
            Thread.sleep(100)
        }
    }

    @Test fun girnels_room_view_runs_over_the_keyless_board_and_hands_the_app_each_change_to_seal() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = App()
        onMain { app.page = RoomBoardPage(context, app); app.page.open() }
        try {
            val page = app.page
            waitFor("the board to be read") { app.heard.any { it["t"]?.jsonPrimitive?.content == "status" && it["ready"]?.jsonPrimitive?.content == "true" } }
            // Girnel's own room view, mounted by the page over the board, with the three columns every room starts from.
            val view = "document.getElementById('view').contentDocument"
            waitFor("Girnel's room view to mount") { evaluate(page, "!!$view?.getElementById('add-todo')") == "true" }
            val text = evaluate(page, "$view.body.innerText")
            assertTrue(text, text.contains("To do") && text.contains("Doing") && text.contains("Done"))
            assertTrue(text, text.contains("Shared with everyone in this room."))

            evaluate(page, """(() => { const i = $view.getElementById('add-todo'); i.value = 'Written on the phone';
                i.dispatchEvent(new Event('input', { bubbles: true })); i.form.requestSubmit(); return true })()""")
            waitFor("the card to reach the app as a part to seal") { app.heard.any { it["t"]?.jsonPrimitive?.content == "seal" } }
            val seal = app.heard.first { it["t"]?.jsonPrimitive?.content == "seal" }
            val part = seal["part"] as? JsonObject
            assertNotNull("A part", part)
            part!!
            assertEquals("A part in its one honest shape", listOf("blob", "count", "data", "index", "kind", "v"), part.keys.sorted())
            assertEquals("1", part.getValue("v").jsonPrimitive.content)
            assertTrue(part.getValue("kind").jsonPrimitive.content in setOf("update", "snapshot"))
            waitFor("the card to be shared") {
                app.heard.lastOrNull { it["t"]?.jsonPrimitive?.content == "status" }?.let { it["ready"].toString() == "true" && it["pending"].toString() == "false" } == true
            }
            waitFor("the card to show") { evaluate(page, "$view.body.innerText").contains("Written on the phone") }
            waitFor("the board to keep its copy with the app") { app.heard.any { it["t"]?.jsonPrimitive?.content == "copy.add" } }

            // What the page asked of the app: only what the contract names.
            val asked = app.heard.map { it.getValue("t").jsonPrimitive.content }.toSet()
            assertTrue(asked.toString(), asked.all { it in setOf("ready", "listen", "unlisten", "seal", "publish", "copy.read", "copy.add", "copy.remove", "status") })
            var refused: List<String> = emptyList()
            onMain { refused = page.refused }
            assertEquals("Every request was for a pinned file", emptyList<String>(), refused)
            assertTrue("The renderer stayed up", !app.gone)
        } finally {
            onMain { app.page.destroy() }
        }
    }
}

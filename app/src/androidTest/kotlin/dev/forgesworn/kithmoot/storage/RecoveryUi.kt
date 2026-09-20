package dev.forgesworn.kithmoot.storage

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Whole-activity acceptance uses Android's accessibility tree and real clock.
 * No Compose test dispatcher replaces the app's asynchronous state collectors. */
internal class RecoveryUi(private val useSwipeFallback: Boolean = true) {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    fun await(description: String, timeoutMs: Long = 60_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for $description. Visible UI:\n" + nodes().mapNotNull {
                    (it.text ?: it.contentDescription ?: it.hintText)?.toString()
                }.joinToString("\n"))
            }
            SystemClock.sleep(50)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }

    fun hasText(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    fun hasDescription(text: String) = nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == text }

    private fun button(text: String): AccessibilityNodeInfo? = nodes().asSequence()
        .filter { it.text?.toString() == text || it.contentDescription?.toString() == text }
        .mapNotNull { child ->
            var node: AccessibilityNodeInfo? = child
            while (node != null && !node.isClickable) node = node.parent
            node?.takeIf { !it.isEditable && it.isVisibleToUser }
        }.firstOrNull()

    private fun field(label: String): AccessibilityNodeInfo? = nodes().firstOrNull { node ->
        node.isEditable && node.isVisibleToUser && (
            node.hintText?.toString() == label || node.text?.toString() == label ||
                (0 until node.childCount).any { node.getChild(it)?.text?.toString() == label }
            )
    }

    private fun <T> reveal(find: () -> T?): T {
        find()?.let { return it }
        // A fresh instrumentation process can attach before Compose exposes
        // its scroll container. Do not spend the backward scroll attempts on
        // an empty tree and then scroll past the heading as it first appears.
        await("rendered screen content") { find() != null || nodes().any { it.isScrollable } }
        // Home controls can sit above or below the current scroll position.
        repeat(8) {
            find()?.let { return it }
            val scroll = scrollContainer() ?: return@repeat
            if (scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) SystemClock.sleep(120)
            find()?.let { return it }
            if (useSwipeFallback) runCatching { onView(isRoot()).perform(swipeDown()) }
            SystemClock.sleep(120)
        }
        repeat(12) {
            find()?.let { return it }
            val scroll = scrollContainer() ?: return@repeat
            if (scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) SystemClock.sleep(120)
            find()?.let { return it }
            if (useSwipeFallback) runCatching { onView(isRoot()).perform(swipeUp()) }
            SystemClock.sleep(120)
        }
        var found: T? = null
        await("visible control") { found = find(); found != null }
        return found!!
    }

    private fun scrollContainer(): AccessibilityNodeInfo? = nodes()
        .filter { it.isScrollable }
        .maxByOrNull { node -> Rect().also(node::getBoundsInScreen).height() }

    fun click(text: String) {
        // Locate the control even while an asynchronous operation keeps it
        // disabled. Scrolling past it then waiting at the bottom misses the
        // later enabled state, particularly with animations disabled in CI.
        repeat(8) {
            reveal { button(text) }
            // Removing another row can move this control offscreen while it
            // is becoming enabled. A missing node means reveal it again;
            // waiting at the old scroll position can never make it reappear.
            await("$text to become enabled or move") {
                val target = button(text)
                target == null || target.isEnabled
            }
            val target = button(text)
            if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        }
        throw AssertionError("$text kept moving before Android accepted the click")
    }

    fun replace(label: String, value: String) {
        val target = reveal { field(label) }
        assertTrue("$label must accept text", target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    fun assertEnabled(text: String, enabled: Boolean) {
        reveal { button(text) }
        // Error text can render before entry's Main-thread finally block
        // releases the busy guard. Observe the resulting control state.
        await("$text enabled must be $enabled") { button(text)?.isEnabled == enabled }
    }

    fun home() {
        reveal { nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == "KithMoot" } }
        await("home to finish loading") { hasText("KithMoot") && !hasDescription("Loading rooms") }
    }

    fun room() = await("room controls") { hasDescription("Leave room") }
}

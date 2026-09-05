package dev.forgesworn.kithmoot.storage

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Whole-activity acceptance uses Android's accessibility tree and real clock.
 * No Compose test dispatcher replaces the app's asynchronous state collectors. */
internal class RecoveryUi {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
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
        // Home controls can sit above or below the current scroll position.
        repeat(8) {
            val scroll = nodes().firstOrNull { it.isScrollable } ?: return@repeat
            if (scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) SystemClock.sleep(120)
        }
        repeat(12) {
            find()?.let { return it }
            val scroll = nodes().firstOrNull { it.isScrollable } ?: return@repeat
            if (scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) SystemClock.sleep(120)
        }
        var found: T? = null
        await("visible control") { found = find(); found != null }
        return found!!
    }

    fun click(text: String) {
        val target = reveal { button(text)?.takeIf { it.isEnabled } }
        assertTrue("$text must be enabled", target.isEnabled)
        assertTrue("$text must accept a click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun replace(label: String, value: String) {
        val target = reveal { field(label) }
        assertTrue("$label must accept text", target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    fun assertEnabled(text: String, enabled: Boolean) {
        assertTrue("$text enabled must be $enabled", reveal { button(text) }.isEnabled == enabled)
    }

    fun home() {
        reveal { nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == "KithMoot" } }
        await("home to finish loading") { hasText("KithMoot") && !hasDescription("Loading rooms") }
    }

    fun room() = await("room controls") { hasText("Leave") }
}

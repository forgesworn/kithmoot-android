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

/** Whole-activity acceptance uses Android's accessibility tree and real clock.
 * No Compose test dispatcher replaces the app's asynchronous state collectors. */
internal class RecoveryUi(private val useSwipeFallback: Boolean = true) {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** Confirm the chosen radio option before the next accessibility action. */
    fun checked(label: String): Boolean {
        var node = nodes().firstOrNull { it.text?.toString() == label }
        while (node != null) {
            if (node.isCheckable) return node.isChecked
            if (node.isClickable) return node.isSelected || (0 until node.childCount)
                .mapNotNull(node::getChild).any { it.isCheckable && it.isChecked }
            node = node.parent
        }
        return false
    }

    fun await(description: String, timeoutMs: Long = 60_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!declineNotificationAsk() && !predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for $description. Visible UI:\n" + nodes().mapNotNull {
                    (it.text ?: it.contentDescription ?: it.hintText)?.toString()
                }.joinToString("\n"))
            }
            SystemClock.sleep(50)
        }
    }

    /**
     * The first room opened asks about notifications (see KithMootApp), and
     * on a fresh install or after a restart it can be up whatever the test is
     * waiting for: its dialog window hides the screen beneath from the tree.
     * Declined as a person would. Always false, so the wait goes on.
     */
    private fun declineNotificationAsk(): Boolean {
        if (nodes().any { it.text?.toString() == "Know when someone writes" }) {
            button("Not now")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
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
    fun enabled(text: String) = button(text)?.isEnabled == true
    fun hasDescription(text: String) = nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == text }

    fun descriptionBounds(text: String): Rect = Rect().also { bounds ->
        nodes().first { it.isVisibleToUser && it.contentDescription?.toString() == text }.getBoundsInScreen(bounds)
    }

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
        // Down the page first, then back up: a long page can have been left scrolled past the control.
        repeat(24) { attempt ->
            find()?.let { return it }
            val scroll = scrollContainer() ?: return@repeat
            val forward = attempt < 12
            val direction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scroll.performAction(direction)) SystemClock.sleep(120)
            find()?.let { return it }
            if (useSwipeFallback && forward) runCatching { onView(isRoot()).perform(swipeUp()) }
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
        reveal { button(text) }
        await("$text to become enabled") { button(text)?.isEnabled == true }
        // Removing a row can replace Compose's accessibility node between
        // lookup and dispatch. Re-query only when Android refused the click.
        await("$text to accept a click") {
            val target = button(text)
            target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    /** The system back gesture, as a person pressing back: one page up in Settings. */
    fun back() {
        check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) { "Back was refused" }
    }

    fun replace(label: String, value: String, awaitValue: Boolean = false) {
        reveal { field(label) }
        // As with click: a field found mid-recomposition, or while home is
        // briefly busy, can refuse the first attempt. Re-query until it takes.
        await("$label to accept text") {
            val target = field(label)
            if (awaitValue && target?.text?.toString() == value) return@await true
            // A multiline field reached through lazy task detail may accept
            // an accessibility action before its editor has focus. Exercise
            // the same focused editor a person taps and verify its value.
            if (awaitValue && target?.isFocused == false) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }) && !awaitValue
        }
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

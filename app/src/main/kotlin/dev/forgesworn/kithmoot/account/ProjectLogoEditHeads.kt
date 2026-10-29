package dev.forgesworn.kithmoot.account

import java.util.concurrent.atomic.AtomicReference

/** Only a durably accepted local write can bridge the preview's reviewed heads. */
internal class ProjectLogoEditHeads(initial: List<String>) {
    private data class Heads(val reviewed: List<String>, val accepted: List<String>? = null)
    private val heads = AtomicReference(Heads(initial.toList()))
    fun reviewed(): List<String> = heads.get().reviewed
    fun matches(actual: List<String>): Boolean = heads.get().let { actual == it.reviewed || actual == it.accepted }
    fun accepted(head: String) { heads.updateAndGet { it.copy(accepted = listOf(head)) } }
    fun completed(head: String) { heads.set(Heads(listOf(head))) }
}

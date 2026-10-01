package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.ui.start.roomLabel

/** A Ring me room whose credential has lapsed or is about to, by the name the person sees. */
data class AtRiskRoom(val id: String, val name: String) {
    companion object {
        fun of(id: String, name: String?) = AtRiskRoom(id, roomLabel(name, id))
    }
}

/** "Untitled test", or "2 rooms": what the notice and the banner call the rooms that cannot ring. */
fun roomsPhrase(names: List<String>): String = when (names.size) {
    0 -> "your rooms"
    1 -> names.single()
    else -> "${names.size} rooms"
}

/** The notification that asks the person to confirm this phone with their signer. */
data class ReachabilityNoticeText(val title: String, val text: String)

fun reachabilityNoticeText(names: List<String>) = ReachabilityNoticeText(
    title = "KithMoot can't ring you for calls",
    text = "Tap to confirm with your signer so calls in ${roomsPhrase(names)} can ring this phone.",
)

/** What the banner says and its one button. */
data class ReachabilityBanner(val message: String, val detail: String, val action: String)

/**
 * The signer app's name when it is a name. The account keeps the phone's label
 * for a NIP-55 app, or its package name when the phone would not say; a bunker
 * has neither. Those read as "your signer".
 */
fun signerDisplayName(label: String?): String {
    val name = label?.trim().orEmpty()
    val looksLikePackage = '.' in name && ' ' !in name
    return if (name.isEmpty() || looksLikePackage) "your signer" else name
}

/**
 * The banner for [atRisk] rooms, or null when there is nothing to say. On the
 * rooms list [inRoom] is null and every room is named; inside a room it is
 * that room's id, and the banner appears only if that room is one of them.
 * Pure, so the wording and the condition are unit-tested.
 */
fun reachabilityBanner(atRisk: List<AtRiskRoom>, signerLabel: String?, inRoom: String? = null): ReachabilityBanner? {
    val rooms = if (inRoom == null) atRisk else atRisk.filter { it.id == inRoom }
    if (rooms.isEmpty()) return null
    return ReachabilityBanner(
        message = "Calls in ${roomsPhrase(rooms.map { it.name })} can't ring this phone.",
        detail = "${signerDisplayName(signerLabel).replaceFirstChar { it.uppercase() }} has to confirm this phone again.",
        action = "Confirm with ${signerDisplayName(signerLabel)}",
    )
}

/** A banner, or a settings row, with the one button that puts it right, and whether pressing it is under way. */
class ReachabilityPrompt(val banner: ReachabilityBanner, val busy: Boolean, val onAction: () -> Unit)

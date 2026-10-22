package dev.forgesworn.kithmoot.ui

import android.content.SharedPreferences
import dev.forgesworn.kithmoot.session.FileDestination
import dev.forgesworn.kithmoot.session.FileServerVia
import dev.forgesworn.kithmoot.session.fileHost
import dev.forgesworn.kithmoot.session.publicFileServer
import dev.forgesworn.kithmoot.session.resolveFileDestination
import dev.forgesworn.kithmoot.session.storedFileServer

/** This device's choice of where files go, as Settings and the composer show it. */
data class FileChoice(
    /** Where a file picked now would go. */
    val destination: FileDestination = FileDestination.Off(chosen = false),
    /** The person's own server, when they typed one, for the Settings field. */
    val ownServer: String = "",
    /** A stored choice of ForgeSworn's storage was removed when the app started: the notice says so once. */
    val retired: Boolean = false,
)

fun fileChoiceOf(prefs: SharedPreferences, retired: Boolean): FileChoice {
    val stored = storedFileServer(prefs)
    val own = stored?.takeIf { it.via == FileServerVia.TYPED && publicFileServer(it.origin) == null }?.origin.orEmpty()
    return FileChoice(resolveFileDestination(prefs), own, retired)
}

/** Settings › Privacy › Where files go, the row's subtitle (the web's spec 9.5). */
fun FileChoice.summary(): String = when (val d = destination) {
    is FileDestination.Off -> if (d.chosen) "Nowhere" else "Choose a server"
    is FileDestination.Default -> "A public server · ${fileHost(d.origin)}"
    is FileDestination.Server ->
        if (publicFileServer(d.origin) != null) "A public server · ${fileHost(d.origin)}" else "My own server · ${fileHost(d.origin)}"
}

/** Room info › Privacy and connection (spec 9.6), or null when there is nothing to say. */
fun FileChoice.roomLine(): String? = when (val d = destination) {
    is FileDestination.Server -> "Files you send in this room go to ${fileHost(d.origin)}."
    is FileDestination.Default -> "Files you send in this room go to ${fileHost(d.origin)}."
    is FileDestination.Off -> if (d.chosen) "Files: you’ve turned uploads off on this device." else null
}

/**
 * The one-time notice, waiting on the person: [origin] is the default server it
 * names; [image] is the picked image it holds, or null for a long paste that
 * goes as a document when the person presses Send.
 */
data class FileNotice(val origin: String, val image: android.net.Uri?)

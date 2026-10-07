package dev.forgesworn.kithmoot.ui.settings

/**
 * Where Settings is open to. Hoisted in `KithMootApp` beside the home page so
 * that it survives a trip to the developer pages and a rotation.
 */
enum class SettingsPage {
    ROOT, ACCOUNT, PROFILE, NOTIFICATIONS, DISPLAY, PRIVACY, CONNECTIONS, RELAYS, DM_RELAYS, UPDATES, DEVELOPER;

    /** The page back goes to; null for the root, whose back leaves Settings. */
    val parent: SettingsPage?
        get() = when (this) {
            ROOT -> null
            PROFILE -> ACCOUNT
            RELAYS, DM_RELAYS -> CONNECTIONS
            else -> ROOT
        }

    /** The root-list row this page sits under, for marking the selection on a wide screen. */
    val section: SettingsPage get() = if (isThirdLevel) parent!!.section else this

    /** A page reached from another page rather than from the root list. */
    val isThirdLevel: Boolean get() = parent != null && parent != ROOT
}

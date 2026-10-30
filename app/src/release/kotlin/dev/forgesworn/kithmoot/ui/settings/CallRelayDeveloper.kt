package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable

/** A release build has no call relay screen preview: the Developer page never calls this, and it draws nothing. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun CallRelayDeveloper(panel: CallRelayPanel) = Unit

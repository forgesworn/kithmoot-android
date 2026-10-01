package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.service.ReachabilityBanner

/**
 * Says, for as long as it is true, that calls cannot ring this phone, and gives
 * the one button that fixes it. Not dismissible: the notification that says the
 * same is, and a dismissed notice left people silently unreachable.
 *
 * @param clearStatusBar the banner is the topmost thing on screen, so it
 * carries the status bar's padding inside its own colour.
 */
@Composable
fun ReachabilityBannerView(
    banner: ReachabilityBanner,
    confirming: Boolean,
    onConfirm: () -> Unit,
    clearStatusBar: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(
            Modifier.then(if (clearStatusBar) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(banner.message, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(banner.detail, style = MaterialTheme.typography.bodyMedium)
            Button(
                onConfirm,
                Modifier.heightIn(min = 48.dp).align(Alignment.End),
                enabled = !confirming,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) { Text(if (confirming) "Waiting for your signer…" else banner.action) }
        }
    }
}

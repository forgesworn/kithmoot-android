package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle

/** The widest a page's rows run: near 75 characters a line, so a switch stays beside its label. */
private val ContentMaxWidth = 640.dp

/**
 * One frame for every settings page on a narrow screen: a pinned bar with the
 * back arrow, the page title and an optional action, over a scrolling body held
 * to [ContentMaxWidth] and centred. The scaffold pads for cutouts and
 * navigation buttons on every side, so landscape rows never sit beneath them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title, style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
            )
        },
    ) { padding ->
        SettingsScroll(Modifier.padding(padding), centred = true, content = content)
    }
}

/** A scrolling column of rows held to [ContentMaxWidth], centred on a narrow screen and start-aligned in a wide pane. */
@Composable
fun SettingsScroll(modifier: Modifier = Modifier, centred: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(bottom = 32.dp), content = content)
    }
}

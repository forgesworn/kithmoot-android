package dev.forgesworn.kithmoot.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.launch

internal enum class ArtworkTab(val title: String) { EMOJI("Emoji"), STICKERS("Stickers"), GIFS("GIFs") }

/** Only known local artwork can enter recents; membership controls the picker, not received messages. */
internal fun visibleRecentEmoji(values: List<String>, memberPack: Boolean): List<String> = values
    .filter { value -> EmojiCatalog.accepts(value) && (memberPack || CULT_EMOJIS.none { it.first == value }) &&
        EmojiCatalog.entries.none { it.first == value && it.second.contains("flag", true) } }
    .distinct().take(24)

@Composable
internal fun ArtworkTray(
    onClose: () -> Unit,
    memberPackAvailable: () -> Boolean,
    unlockMemberPacks: suspend () -> Boolean,
    skinTone: Int,
    onSkinTone: (Int) -> Unit,
    chooseEmoji: (String) -> Unit,
    chooseMedia: (CatalogueImage) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ArtworkTab = ArtworkTab.EMOJI,
    mediaEnabled: Boolean = true,
    reactionsOnly: Boolean = false,
    compactSearch: Boolean = false,
    onSearchChanged: (Boolean) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var section by rememberSaveable { mutableStateOf(EmojiSection.FAMILIAR) }
    var recentOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var available by remember { mutableStateOf(memberPackAvailable()) }
    var menuOpen by remember { mutableStateOf(false) }
    var toneOpen by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<CatalogueImage?>(null) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("local-artwork-recents", android.content.Context.MODE_PRIVATE) }
    var recents by remember { mutableStateOf(preferences.getString("emoji", "")!!.split('|')) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val member = available && memberPackAvailable()
    fun closeSearch() { query = ""; searchOpen = false; focus.clearFocus(); keyboard?.hide() }
    BackHandler { if (searchOpen) closeSearch() else onClose() }
    DisposableEffect(Unit) { onDispose { onSearchChanged(false) } }
    LaunchedEffect(searchOpen, compactSearch) { onSearchChanged(searchOpen); if (searchOpen) { searchFocus.requestFocus(); keyboard?.show() } }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (searchOpen && compactSearch) {
                    OutlinedTextField(query, { query = it.take(80) }, placeholder = { Text("Search artwork") }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("artwork-search").focusRequester(searchFocus))
                } else {
                if (reactionsOnly) Text("React with emoji", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall)
                else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ArtworkTab.entries.forEach { option ->
                        TextButton(onClick = { tab = option; preview = null }, enabled = option == ArtworkTab.EMOJI || mediaEnabled,
                            modifier = Modifier.semantics { selected = tab == option },
                            colors = ButtonDefaults.textButtonColors(containerColor = if (tab == option) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)) {
                            Text(option.title)
                        }
                    }
                }
                IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }) { Icon(Icons.Default.Search, "Search artwork") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Artwork options") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Unlock Nostr packs") }, enabled = !unlocking, onClick = {
                            menuOpen = false; unlocking = true; status = "Confirm this account in your signer…"
                            scope.launch {
                                try { available = unlockMemberPacks(); status = if (available) "Nostr pack unlocked." else "No member packs found for this account." }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (failure: Exception) { status = failure.message ?: "The pack could not be unlocked." }
                                finally { unlocking = false }
                            }
                        })
                    }
                }
                }
                IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); onClose() }) { Icon(Icons.Default.Close, "Close artwork picker") }
            }
            if (searchOpen && !compactSearch) OutlinedTextField(query, { query = it.take(80) }, placeholder = { Text("Search artwork") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("artwork-search").focusRequester(searchFocus))
            else if (!searchOpen && tab == ArtworkTab.EMOJI) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(recentOpen, { recentOpen = true }, label = { Text("Recent") })
                        EmojiSection.entries.filter { it != EmojiSection.MEMBER || member }.forEach { option ->
                            FilterChip(!recentOpen && section == option, { recentOpen = false; section = option }, label = { Text(option.title) })
                        }
                    }
                    Box {
                        IconButton(onClick = { toneOpen = true }, modifier = Modifier.semantics { contentDescription = "Hand colour: ${SKIN_TONE_NAMES[skinTone]}" }) { PackEmoji(withSkinTone("👍", skinTone), Modifier.size(28.dp)) }
                        DropdownMenu(toneOpen, { toneOpen = false }) {
                            HUMAN_SKIN_TONES.indices.forEach { tone -> DropdownMenuItem(text = { Text(SKIN_TONE_NAMES[tone]) }, leadingIcon = { PackEmoji(withSkinTone("👍", tone), Modifier.size(28.dp)) }, onClick = { onSkinTone(tone); toneOpen = false }) }
                        }
                    }
                }
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            if (tab == ArtworkTab.EMOJI) {
                val choices = if (recentOpen && query.isBlank()) visibleRecentEmoji(recents, member).map { it to "" }
                    else emojiPickerEntries(section, query, skinTone, member)
                if (choices.isEmpty()) Text(if (recentOpen && query.isBlank()) "Your recently used emoji appear here." else "No matching emoji.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
                    items(choices, key = { it.first }) { (emoji, words) ->
                        Box(Modifier.height(48.dp).clickable {
                            chooseEmoji(emoji)
                            recents = (listOf(emoji) + recents).distinct().take(24)
                            preferences.edit().putString("emoji", recents.joinToString("|")).apply()
                        }.semantics { contentDescription = emojiPickerDescription(emoji, words) }, contentAlignment = Alignment.Center) { PackEmoji(emoji, Modifier.size(36.dp)) }
                    }
                }
            } else {
                val results = remember(query, tab) { searchMediaCatalogue(query, tab == ArtworkTab.STICKERS) }
                if (results.isEmpty()) Text("No matching artwork.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                    items(results, key = { it.asset }) { image ->
                        Column(Modifier.clickable { preview = image }.padding(4.dp).semantics { contentDescription = "Preview ${image.name}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                            CatalogueThumbnail(image, Modifier.size(100.dp))
                            Text(image.name.removeSuffix(".png").removeSuffix(".gif"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    preview?.let { image ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(image.name.removeSuffix(".png").removeSuffix(".gif")) }, text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CatalogueThumbnail(image, Modifier.fillMaxWidth().height(240.dp))
                Text("Our artwork. Browsing stays on this device.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { chooseMedia(image); preview = null }) { Text("Add to message") } }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Back to artwork") } })
    }
}

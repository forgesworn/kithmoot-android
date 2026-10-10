package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.session.*

/** Stable phone destinations. Cards are read-only projections; their buttons
 * deliberately enter the origin's ordinary authenticated controls. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(snapshot: WorkspaceSnapshot, initialInbox: Boolean, onClose: () -> Unit,
    onProjects: () -> Unit, onOrigin: (WorkspaceOrigin) -> Unit, onRefresh: () -> Unit,
    modifier: Modifier = Modifier) {
    var inbox by rememberSaveable { mutableStateOf(initialInbox) }
    var project by rememberSaveable(snapshot.account) { mutableStateOf<String?>(null) }
    var projectsOpen by remember { mutableStateOf(false) }
    var unavailableOpen by rememberSaveable { mutableStateOf(false) }
    val projects = snapshot.rooms.mapNotNull { room -> room.project?.let { it to (room.projectName ?: it) } }.distinctBy { it.first }
    val rooms = snapshot.rooms.filter { project == null || (it.project ?: "") == project }
    val account = snapshot.account
    val count = if (account == null) 0 else rooms.sumOf { room ->
        if (room.activity.error != null) 0 else room.activity.work.assignments.count {
            if (inbox) workspaceDecision(it, account) != null else it.status !in setOf("accepted", "cancelled")
        } + if (inbox) workspaceMentions(room.activity.messages, account, workspacePeople(room), room.inbox).size else 0
    }
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(if (inbox) "Inbox" else "Work across projects", style = dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle(),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, navigationIcon = {
            TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }, actions = { TextButton(onRefresh, Modifier.heightIn(min = 48.dp)) { Text("Refresh") } })
    }) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(if (inbox) 0 else 1) {
                Tab(inbox, { inbox = true }, text = { Text("Inbox") })
                Tab(!inbox, { inbox = false }, text = { Text("Work") })
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton({ projectsOpen = true }, Modifier.heightIn(min = 48.dp)) {
                        Text(if (project == null) "All projects" else projects.find { it.first == project }?.second ?: "No project")
                    }
                    DropdownMenu(projectsOpen, { projectsOpen = false }) {
                        DropdownMenuItem(text = { Text("All projects") }, onClick = { project = null; projectsOpen = false })
                        projects.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { project = key; projectsOpen = false }) }
                        DropdownMenuItem(text = { Text("No project") }, onClick = { project = ""; projectsOpen = false })
                    }
                }
                TextButton(onProjects, Modifier.heightIn(min = 48.dp)) { Text("Projects") }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("$count ${if (inbox) { if (count == 1) "item needs attention" else "items need attention" }
                        else if (count == 1) "active task" else "active tasks"}", style = MaterialTheme.typography.titleMedium)
                    Text("Recent activity from rooms you have opened as this account. Older work and missing history need checking in the room.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                snapshot.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (account == null) item { Text("Sign in with Nostr to see your work across projects.") }
                else {
                    rooms.filter { it.activity.error == null }.forEach { room ->
                        val people = workspacePeople(room)
                        val tasks = room.activity.work.assignments.filter {
                            if (inbox) workspaceDecision(it, account) != null else it.status !in setOf("accepted", "cancelled")
                        }
                        if (room.activity.work.pendingSends > 0) item(key = "pending:${room.room}") {
                            WorkspaceCard(room, "Saved updates need checking in this room", "", "Open room") {
                                onOrigin(WorkspaceOrigin(account, room.room))
                            }
                        }
                        if (!room.activity.work.ready || room.activity.work.pendingHistory > 0) item(key = "loading:${room.room}") {
                            Text("${room.name}: loading recent work or missing task history", style = MaterialTheme.typography.bodySmall)
                        }
                        items(tasks, key = { "task:${room.room}:${it.id}" }) { task ->
                            val person = people.find { it.participant == task.owner }
                            val who = if (task.owner == account) "You" else person?.name ?: shortNpub(task.owner)
                            val detail = "Responsible: $who${if (person?.agent == true) " (agent)" else ""} · ${task.status}\n" +
                                (workspaceDecision(task, account) ?: task.next)
                            WorkspaceCard(room, task.objective, detail, "Open task in room") {
                                onOrigin(WorkspaceOrigin(account, room.room, assignment = task.id))
                            }
                        }
                        if (inbox) items(workspaceMentions(room.activity.messages, account, people, room.inbox),
                            key = { "message:${room.room}:${it.ref.key}" }) { message ->
                            val who = people.find { it.participant == message.ref.participant }?.name ?: shortNpub(message.ref.participant)
                            WorkspaceCard(room, "$who · ${message.reason}", message.text.take(400), "Open message in room") {
                                onOrigin(WorkspaceOrigin(account, room.room, message = message.ref))
                            }
                        }
                    }
                    if (count == 0) item { Text("No matching items in the recent activity loaded here. Open a room to check older work.") }
                    val unavailable = rooms.filter { it.activity.error != null }
                    if (unavailable.isNotEmpty()) item {
                        TextButton({ unavailableOpen = !unavailableOpen }, Modifier.heightIn(min = 48.dp)) {
                            Text("Check activity in ${unavailable.size} rooms")
                        }
                    }
                    if (unavailableOpen) items(unavailable, key = { "unavailable:${it.room}" }) { room ->
                        WorkspaceCard(room, room.name, room.activity.error.orEmpty(), "Open room") {
                            onOrigin(WorkspaceOrigin(account, room.room))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(room: WorkspaceRoomActivity, title: String, detail: String, button: String, onOpen: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${room.projectName ?: "No project"} · ${room.name}", style = MaterialTheme.typography.bodySmall)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onOpen, Modifier.heightIn(min = 48.dp)) { Text(button) }
        }
    }
}

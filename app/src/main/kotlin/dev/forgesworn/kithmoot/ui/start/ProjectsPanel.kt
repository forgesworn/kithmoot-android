package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.protocol.SharedProject
import dev.forgesworn.kithmoot.ui.StartState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class ProjectActions(
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val follow: (SharedProject, Boolean) -> Unit = { _, _ -> },
    val open: (SharedProject, ProjectRoomChoice) -> Unit = { _, _ -> },
    val save: suspend (SharedProject?, JsonObject) -> Boolean = { _, _ -> false },
    val rooms: suspend () -> List<ProjectRoomChoice> = { emptyList() },
)

@Composable
fun ProjectsPanel(state: StartState, actions: ProjectActions) {
    val account = state.account
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SharedProject?>(null) }
    val enabled = state.projects.ready && !state.projectsBusy && !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Projects", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (account != null) TextButton(actions.refresh, enabled = !state.projects.syncing && !state.projectsBusy) { Text("Sync") }
        }
        if (account == null) {
            Text("Sign in below to bring your projects, people and rooms onto this phone.")
        } else {
            Text("Your shared projects follow this account between devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.projects.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Syncing projects" })
            (state.projectError ?: state.projects.error)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.projects.pendingSends > 0) {
                Text("${state.projects.pendingSends} project updates waiting to send", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(actions.retry, enabled = enabled) { Text("Retry project updates") }
            }
            Button({ selected = null; editing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("New project") }
            if (state.projects.ready && state.projects.projects.isEmpty()) Text("Start a project here, or ask its owner to invite this account.")
            for (project in state.projects.projects) {
                var peopleShown by remember(project.key) { mutableStateOf(false) }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(project.name ?: if (project.withdrawn) "Invitation withdrawn" else "Project needs review",
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text("Owner ${shortNpub(project.reference.owner)}", style = MaterialTheme.typography.bodySmall)
                        val members = project.definition?.get("members")?.jsonArray.orEmpty()
                        val agents = members.count { it.jsonObject["kind"] == JsonPrimitive("agent") }
                        val rooms = project.roomChoices()
                        if (project.definition != null) {
                            val peopleLabel = if (members.size - agents == 1) "1 person" else "${members.size - agents} people"
                            val agentLabel = if (agents == 1) "1 agent" else "$agents agents"
                            val roomLabel = if (rooms.size == 1) "1 room" else "${rooms.size} rooms"
                            Text("$peopleLabel · $agentLabel · $roomLabel", style = MaterialTheme.typography.bodyMedium)
                            if (peopleShown) for (member in members) {
                                val m = member.jsonObject
                                val key = m.getValue("pubkey").jsonPrimitive.content
                                val name = m["name"]?.jsonPrimitive?.content
                                Text((name?.let { "$it · " } ?: "") + shortNpub(key) + if (m["kind"] == JsonPrimitive("agent")) " · agent" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when {
                            project.withdrawn -> Text("The owner removed this account from the project.")
                            project.conflicted -> Text("Conflicting updates need the owner's review. Room shortcuts are paused.")
                            project.archived -> Text("Archived")
                            !project.joined -> Button({ actions.follow(project, true) }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "Join ${project.name}" }) { Text("Join project") }
                            else -> {
                                if (rooms.isEmpty()) Text("No rooms shared yet.")
                                for (room in rooms) OutlinedButton({ actions.open(project, room) }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Open ${room.name} in ${project.name}" }) {
                                    Text(room.name)
                                }
                            }
                        }
                        if (!project.withdrawn && !project.conflicted) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ peopleShown = !peopleShown }) { Text(if (peopleShown) "Hide people" else "People and agents") }
                            if (project.reference.owner == account.pubkey) TextButton({ selected = project; editing = true }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "Edit ${project.name}" }) { Text("Edit project") }
                            else if (project.joined) TextButton({ actions.follow(project, false) }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "Leave ${project.name}" }) { Text("Leave project") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editing && account != null) ProjectEditor(account.pubkey, selected, state, actions) { editing = false }
}

/** Preserve known collaborator names and invitations that are not saved locally on this phone. */
internal fun projectDefinition(owner: String, original: SharedProject?, name: String, people: String, agents: String,
    rooms: List<ProjectRoomChoice>, archived: Boolean,
): JsonObject {
    val clean = DisplayName.sanitise(name)
    require(clean != null && clean == name.trim()) { "Use a project name of up to 32 characters." }
    fun keys(raw: String): List<String> = raw.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }.map {
        requireNotNull(publicKeyFrom(it)) { "Use a person's or agent's public npub." }
    }
    val humans = keys(people).filter { it != owner }; val bots = keys(agents)
    require(owner !in bots && (humans + bots).distinct().size == humans.size + bots.size) { "Each person or agent can appear only once." }
    val previous = original?.definition?.get("members")?.jsonArray?.map { it.jsonObject }.orEmpty()
    fun member(key: String, kind: String) = buildJsonObject {
        put("pubkey", key); put("kind", kind); put("epoch", 1)
        previous.find { it["pubkey"] == JsonPrimitive(key) && it["kind"] == JsonPrimitive(kind) }?.get("name")?.let { put("name", it) }
    }
    return buildJsonObject {
        put("name", clean); put("archived", archived); put("authorityRevision", 1)
        put("members", JsonArray(listOf(member(owner, "person")) + humans.map { member(it, "person") } + bots.map { member(it, "agent") }))
        put("rooms", JsonArray(rooms.map { it.toJson() }))
    }
}

@Composable
private fun ProjectEditor(owner: String, original: SharedProject?, state: StartState, actions: ProjectActions, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val members = original?.definition?.get("members")?.jsonArray.orEmpty()
    fun initial(kind: String) = members.map { it.jsonObject }.filter { it["kind"] == JsonPrimitive(kind) && it["pubkey"] != JsonPrimitive(owner) }
        .joinToString("\n") { npubOf(it.getValue("pubkey").jsonPrimitive.content) }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var people by remember { mutableStateOf(initial("person")) }
    var agents by remember { mutableStateOf(initial("agent")) }
    var archived by remember { mutableStateOf(original?.archived == true) }
    var options by remember { mutableStateOf(original?.roomChoices().orEmpty()) }
    var selected by remember { mutableStateOf(options.map { it.room }.toSet()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { options = (actions.rooms() + options).distinctBy { it.room } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Saved room invitations could not be read." }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!state.projectsBusy) dismiss() }, title = { Text(if (original == null) "New project" else "Edit project") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Project name") }, singleLine = true, enabled = !state.projectsBusy)
                Text("You remain the project owner. Invite people and agents by their public npub.")
                OutlinedTextField(people, { people = it.take(5000) }, label = { Text("People's npubs") }, minLines = 2, maxLines = 4, enabled = !state.projectsBusy)
                OutlinedTextField(agents, { agents = it.take(5000) }, label = { Text("Agents' npubs") }, minLines = 2, maxLines = 4, enabled = !state.projectsBusy)
                Text("Shared rooms", style = MaterialTheme.typography.titleMedium)
                Text("Selected rooms share an invitation with every project member.", style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!loading && options.isEmpty()) Text("Start or join a persistent room to add it here.")
                for (room in options) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(room.room in selected, { chosen -> selected = if (chosen) selected + room.room else selected - room.room },
                        enabled = !state.projectsBusy, modifier = Modifier.semantics { contentDescription = "Share ${room.name} with project" })
                    Text(room.name)
                }
                if (original != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(archived, { archived = it }, enabled = !state.projectsBusy)
                    Text("Archive project")
                }
                (error ?: state.projectError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = { TextButton({
            scope.launch {
                error = null
                val definition = try { projectDefinition(owner, original, name, people, agents, options.filter { it.room in selected }, archived) }
                catch (e: Exception) { error = e.message; return@launch }
                if (actions.save(original, definition)) dismiss()
            }
        }, enabled = state.projects.ready && !state.projectsBusy && !loading) { Text(if (state.projectsBusy) "Saving…" else "Save project") } },
        dismissButton = { TextButton(dismiss, enabled = !state.projectsBusy) { Text("Cancel") } },
    )
}

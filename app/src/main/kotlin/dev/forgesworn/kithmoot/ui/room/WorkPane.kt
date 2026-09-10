package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.SharedAssignment
import dev.forgesworn.kithmoot.session.assignmentText
import dev.forgesworn.kithmoot.ui.RoomState
import kotlinx.serialization.json.*

/** Quiet progress, concrete decisions, and the evidence being accepted. */
@Composable
fun WorkPane(state:RoomState,onSubmit:(String?,JsonObject,String?)->Unit,onRetry:()->Unit,onRefresh:()->Unit) {
    var creating by rememberSaveable(state.roomId){mutableStateOf(false)}
    var submittedAt by rememberSaveable(state.roomId){mutableStateOf(-1L)}
    LaunchedEffect(state.workCompleted){if(submittedAt>=0&&state.workCompleted>submittedAt){creating=false;submittedAt=-1}}
    val canUpdate=state.work.ready&&!state.workBusy&&state.work.pendingSends==0&&!state.secondary&&state.movedOn==null
    val decisions=state.work.assignments.filter{it.creator==state.selfParticipant&&it.needsDecision}
    val others=state.work.assignments.filter{it !in decisions}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Text("Shared work",style=MaterialTheme.typography.headlineSmall)
            Text("Keep the conversation here. Bring decisions back when they need you.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick={creating=!creating},enabled=canUpdate){Text(if(creating)"Close new task" else "New task")}
                TextButton(onClick=onRefresh,enabled=!state.workBusy&&state.movedOn==null){Text(if(!state.work.ready&&state.work.error!=null)"Reconnect shared work" else "Refresh agents")}
            }
        }
        if(state.workBusy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        val error=state.workError?:state.work.error
        if(error!=null)item{Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
        if(state.work.pendingSends>0)item{
            ElevatedCard {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("An update is waiting for confirmation",style=MaterialTheme.typography.titleMedium)
                Text("Its exact signed version is saved on this device.")
                OutlinedButton(onClick=onRetry,enabled=state.work.ready&&!state.workBusy&&state.movedOn==null){Text("Retry saved update")}
            }}
        } else if(!state.work.ready&&error==null)item{Text("Loading shared work…",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(state.secondary)item{Text("This paired device can read shared work. Use your signing device to send decisions.",style=MaterialTheme.typography.bodyMedium)}
        if(creating)item{
            NewAssignment(state,canUpdate){operation->submittedAt=state.workCompleted;onSubmit(null,operation,null)}
        }
        if(decisions.isNotEmpty())item{Text("Needs you · ${decisions.size}",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)}
        items(decisions,key={it.id}){assignment->AssignmentCard(assignment,state,canUpdate,onSubmit)}
        if(others.isNotEmpty())item{Text("Room work",style=MaterialTheme.typography.titleMedium)}
        items(others,key={it.id}){assignment->AssignmentCard(assignment,state,canUpdate,onSubmit)}
        if(state.work.ready&&state.work.assignments.isEmpty()&&!creating)item{
            OutlinedCard {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("No shared work yet",style=MaterialTheme.typography.titleMedium)
                Text("Choose an available agent action, describe the outcome and say what a good result needs to include.")
            }}
        }
    }
}

@Composable
private fun NewAssignment(state:RoomState,enabled:Boolean,onCreate:(JsonObject)->Unit) {
    val actions=state.workActions.filter{a->state.tiles.any{it.participant==a.owner}}
    var selected by rememberSaveable(state.roomId){mutableStateOf("")}
    var expanded by remember{mutableStateOf(false)}
    var objective by rememberSaveable(state.roomId){mutableStateOf("")}
    var criteria by rememberSaveable(state.roomId){mutableStateOf("")}
    var inputJson by rememberSaveable(state.roomId,selected){mutableStateOf("{}")}
    val action=actions.find{"${it.owner}:${it.id}"==selected}
    val inputs=Json.parseToJsonElement(inputJson).jsonObject
    val fields=action?.definition?.get("inputs")?.jsonArray.orEmpty()
    val valid=action!=null&&objective.isNotBlank()&&objective.length<=1000&&criteria.isNotBlank()&&criteria.length<=2000&&fields.all{field->val f=field.jsonObject;val value=inputs.assignmentText(f.assignmentText("id")!!).orEmpty();value.length<=1000&&(!f.getValue("required").jsonPrimitive.boolean||value.isNotBlank())}
    ElevatedCard {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("New shared task",style=MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick={expanded=true},enabled=enabled&&actions.isNotEmpty()){Text(action?.let{"${it.agentName} · ${it.label}"}?:"Choose an agent action")}
            DropdownMenu(expanded,onDismissRequest={expanded=false}){
                actions.forEach{a->DropdownMenuItem(text={Text("${a.agentName} · ${a.label}")},onClick={selected="${a.owner}:${a.id}";expanded=false})}
            }
        }
        if(actions.isEmpty())Text("No agent actions are available from people currently in this room.",style=MaterialTheme.typography.bodyMedium)
        action?.let{Text(it.description,style=MaterialTheme.typography.bodyMedium)}
        OutlinedTextField(objective,{objective=it},label={Text("What needs doing?")},enabled=enabled,modifier=Modifier.fillMaxWidth(),minLines=2)
        OutlinedTextField(criteria,{criteria=it},label={Text("What makes it done?")},enabled=enabled,modifier=Modifier.fillMaxWidth(),minLines=2)
        fields.forEach{value->val f=value.jsonObject;val id=f.assignmentText("id")!!
            OutlinedTextField(inputs.assignmentText(id).orEmpty(),{inputJson=JsonObject(inputs+(id to JsonPrimitive(it))).toString()},label={Text(f.assignmentText("label")!!)},enabled=enabled,modifier=Modifier.fillMaxWidth())
        }
        Button(onClick={val chosen=action?:return@Button;onCreate(buildJsonObject{put("op","create");put("objective",objective);put("criteria",criteria);put("owner",chosen.owner);put("action",chosen.id);put("inputs",JsonObject(inputs.filterValues{it.jsonPrimitive.content.isNotBlank()}))})},enabled=enabled&&valid){Text("Share task")}
    }}
}

@Composable
private fun AssignmentCard(a:SharedAssignment,state:RoomState,enabled:Boolean,onSubmit:(String?,JsonObject,String?)->Unit) {
    var response by rememberSaveable(a.id,a.head){mutableStateOf("")}
    var owner by rememberSaveable(a.id,a.head){mutableStateOf("")}
    var ownersOpen by remember{mutableStateOf(false)}
    var manage by rememberSaveable(a.id,a.head){mutableStateOf(false)}
    val mine=a.creator==state.selfParticipant
    val ownerLabel=if(a.owner==state.selfParticipant)"You" else state.workActions.firstOrNull{it.owner==a.owner}?.agentName
        ?:state.tiles.firstOrNull{it.participant==a.owner}?.cardName?.takeIf{it.isNotBlank()}
        ?:dev.forgesworn.kithmoot.account.npubOf(a.owner).let{it.take(12)+"…"+it.takeLast(6)}
    fun send(op:String,extra:JsonObject=JsonObject(emptyMap()))=onSubmit(a.id,JsonObject(mapOf("op" to JsonPrimitive(op))+extra),a.head)
    ElevatedCard {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(a.objective,style=MaterialTheme.typography.titleMedium)
        Text("Assigned to $ownerLabel",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when(a.status){"offered"->"Waiting to start";"running"->"In progress";"blocked"->"Decision needed";"review"->"Ready for review";"stopping"->"Waiting for stop confirmation";"stopped"->"Ready to hand over";"accepted"->"Accepted";"cancelled"->"Cancelled";else->"Conflicting updates"},color=if(a.needsDecision)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelLarge)
        Text(a.criteria,style=MaterialTheme.typography.bodyMedium)
        a.fields.assignmentText("progress")?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodyMedium)}
        a.question?.let{Text(it,style=MaterialTheme.typography.bodyLarge)}
        a.result?.let{result->
            Text(result.assignmentText("summary").orEmpty(),style=MaterialTheme.typography.bodyLarge)
            Text("Evidence",style=MaterialTheme.typography.labelLarge)
            Text(result.assignmentText("evidence").orEmpty(),style=MaterialTheme.typography.bodyMedium)
        }
        if(mine&&a.status in setOf("blocked","review","running","offered","stopped")) {
            if(a.status=="review")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={send("accept",buildJsonObject{put("result",a.result!!.getValue("id"))})},enabled=enabled){Text("Accept result")}
                TextButton(onClick={manage=!manage},enabled=enabled){Text("Request changes")}
            }
            if(a.status in setOf("running","offered","blocked"))TextButton(onClick={manage=!manage},enabled=enabled){Text(if(manage)"Close task controls" else "Manage task")}
            if(a.status in setOf("blocked","stopped")||manage)OutlinedTextField(response,{response=it},label={Text(when(a.status){"blocked"->"Your answer";"review"->"What needs changing?";"stopped"->"Handoff note";else->"Reason to stop"})},enabled=enabled,modifier=Modifier.fillMaxWidth(),minLines=2)
            val hasResponse=response.isNotBlank()&&response.length<=2000
            if(a.status=="blocked")Button(onClick={send("answer",buildJsonObject{put("text",response)})},enabled=enabled&&hasResponse){Text("Send answer")}
            if(a.status=="review"&&manage)OutlinedButton(onClick={send("reject",buildJsonObject{put("result",a.result!!.getValue("id"));put("reason",response)})},enabled=enabled&&hasResponse){Text("Send requested changes")}
            if(a.status=="stopped") {
                Box{OutlinedButton(onClick={ownersOpen=true},enabled=enabled){Text(if(owner.isEmpty())"Choose next owner" else "Owner selected")}
                    DropdownMenu(ownersOpen,{ownersOpen=false}){state.tiles.forEach{tile->DropdownMenuItem(text={Text(if(tile.isSelf)"You" else tile.cardName?.takeIf{it.isNotBlank()}?:dev.forgesworn.kithmoot.account.npubOf(tile.participant).let{it.take(12)+"…"+it.takeLast(6)})},onClick={owner=tile.participant;ownersOpen=false})}}}
                Button(onClick={send("assign",buildJsonObject{put("owner",owner);put("reason",response)})},enabled=enabled&&hasResponse&&owner.isNotBlank()){Text("Hand over")}
            }
            if(manage&&a.status in setOf("offered","running","blocked","review"))Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={send("stop",buildJsonObject{put("purpose","cancel");put("reason",response)})},enabled=enabled&&hasResponse){Text("Cancel task")}
                TextButton(onClick={send("stop",buildJsonObject{put("purpose","handoff");put("reason",response)})},enabled=enabled&&hasResponse){Text("Stop to hand over")}
            }
        }
        if(a.status !in setOf("accepted","cancelled","review","blocked"))Text(a.next,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}

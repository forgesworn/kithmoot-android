package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.mls.ExperimentalInvitationTarget
import dev.forgesworn.kithmoot.mls.VmlsBoxes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Development ceremony. Every byte exchange remains private and explicit. */
@Composable
internal fun ExperimentalInvitationControls(boxes: VmlsBoxes, persona: String, signer: () -> dev.forgesworn.kithmoot.account.ParticipantSigner?) {
    val state by boxes.state.collectAsState()
    val consent by boxes.invitationConsent.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var session by remember(persona) { mutableStateOf("") }
    var name by remember { mutableStateOf("Experimental room") }
    var box by remember { mutableStateOf("") }
    var guestHomeCode by remember { mutableStateOf("") }
    var targetPersona by remember { mutableStateOf("") }
    var targetDevice by remember { mutableStateOf("") }
    var targetRendezvous by remember { mutableStateOf("") }
    var targetHome by remember { mutableStateOf("") }
    var trustedInviter by remember { mutableStateOf("") }
    var authenticated by remember(persona, trustedInviter) { mutableStateOf(false) }
    var authorization by remember(persona) { mutableStateOf("") }
    var response by remember(persona) { mutableStateOf("") }
    var registrationRequest by remember(persona) { mutableStateOf("") }
    var registrationReview by remember { mutableStateOf("") }
    var registrationReceipt by remember(persona) { mutableStateOf("") }
    var removeLeaf by remember { mutableStateOf("") }
    var review by remember { mutableStateOf("") }
    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Operation refused." }
            finally { busy = false }
        }
    }
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Experimental invitation profile", style = MaterialTheme.typography.titleMedium)
            Text("Development only. All current members may Add or Remove, including removing the creator. Node keeper authority is separate. Use an authenticated private channel; this panel publishes nothing to relays or a directory.")
            OutlinedTextField(guestHomeCode, { guestHomeCode = it.trim() }, label = { Text("Guest home pairing code") }, maxLines = 4)
            Button({ run {
                val actor = requireNotNull(signer()).also { check(it.pubkey == persona) }
                boxes.pairPrivateInvitationHome(actor, guestHomeCode) { signer() === actor && actor.pubkey == persona }
                guestHomeCode = ""
                "Home paired. Ask its keeper to authorize this phone's MLS device separately, then check the box. Pairing grants no storage rights."
            } }, enabled = !busy) { Text("Pair guest home without storage grant") }
            OutlinedTextField(name, { name = it }, label = { Text("Local room name") })
            OutlinedTextField(box, { box = it.trim() }, label = { Text("Paired group home node (hex)") })
            Text("Paired homes: ${state.boxes.joinToString { it.box }}")
            Button({ run { boxes.createInvitationRoom(persona, box, name).also { session = it } } }, enabled = !busy) { Text("Create separate experimental room") }
            OutlinedTextField(session, { session = it.trim() }, label = { Text("Local session id (hex)") })
            Text("Issue to the exact device agreed privately")
            OutlinedTextField(targetPersona, { targetPersona = it.trim() }, label = { Text("Intended persona") })
            OutlinedTextField(targetDevice, { targetDevice = it.trim() }, label = { Text("Intended MLS device") })
            OutlinedTextField(targetRendezvous, { targetRendezvous = it.trim() }, label = { Text("Intended rendezvous key") })
            OutlinedTextField(targetHome, { targetHome = it.trim() }, label = { Text("Intended leaf home") })
            Button({ run {
                boxes.issuePrivateInvitation(persona, session, ExperimentalInvitationTarget(targetPersona, targetDevice, targetRendezvous, targetHome, System.currentTimeMillis() / 1000 + 3600)).also { authorization = it }
                "Invitation protected. Select and copy it only into the authenticated private ceremony."
            } }, enabled = !busy) { Text("Issue private invitation") }
            OutlinedTextField(authorization, { authorization = it.trim(); review = "" }, label = { Text("Private invitation bytes (base64)") }, maxLines = 6)
            OutlinedTextField(trustedInviter, { trustedInviter = it.trim() }, label = { Text("Inviter persona from independent trusted source") })
            Row {
                Checkbox(authenticated, { authenticated = it })
                Text("I selected this persona through an authenticated contact or private ceremony, independently of the pasted invitation.")
            }
            Button({ run { boxes.reviewPrivateInvitation(persona, trustedInviter, authorization).also { review = it } } }, enabled = !busy && authenticated) { Text("Review intended context") }
            if (review.isNotEmpty()) {
                SelectionContainer { Text(review) }
                Button({ run { boxes.acceptPrivateInvitation(persona, trustedInviter, authorization, name).also { session = it } } }, enabled = !busy && authenticated) { Text("Confirm context and accept invitation") }
            }
            Button({ run { boxes.exportPrivateInvitationResponse(persona, session).also { response = it }; "Original protected response ready for private return." } }, enabled = !busy) { Text("Export original response / retry") }
            OutlinedTextField(response, { response = it.trim() }, label = { Text("Private response bytes (base64)") }, maxLines = 6)
            Button({ run { boxes.importPrivateInvitationResponse(persona, session, authorization, response) } }, enabled = !busy) { Text("Durably accept response") }
            Button({ run { boxes.exportInvitationRegistrationRequest(persona, session, authorization, response).also { registrationRequest = it }; "Copy this minimal request privately to an independently authorized node keeper." } }, enabled = !busy) { Text("Export keeper registration request") }
            OutlinedTextField(registrationRequest, { registrationRequest = it.trim(); registrationReview = "" }, label = { Text("Private keeper request (base64)") }, maxLines = 4)
            Text("Independent keeper: import only this request. Group membership and the private invitation are unnecessary for registration.")
            Button({ run { boxes.reviewInvitationRegistrationRequest(registrationRequest).also { registrationReview = it } } }, enabled = !busy) { Text("Review keeper request") }
            if (registrationReview.isNotEmpty()) {
                SelectionContainer { Text(registrationReview) }
                Button({ run { boxes.registerInvitationRequest(persona, registrationRequest).also { registrationReceipt = it }; "Return this signed receipt privately to the MLS member." } }, enabled = !busy) { Text("Authorize this keeper registration") }
            }
            OutlinedTextField(registrationReceipt, { registrationReceipt = it.trim() }, label = { Text("Node-signed registration receipt (base64)") }, maxLines = 4)
            Button({ run { boxes.addPrivateInvitationMember(persona, session, authorization, response, registrationReceipt) } }, enabled = !busy) { Text("Verify receipt and authorize member MLS Add") }
            OutlinedTextField(removeLeaf, { removeLeaf = it.trim() }, label = { Text("Member leaf to remove, including creator") })
            Button({ run { boxes.removeInvitationMember(persona, session, removeLeaf) } }, enabled = !busy) { Text("Authorize current-member Remove") }
            Text("Existing room controls drive Welcome, required Update and messages. This development UI currently uses a single paired home for delivery.")
            if (message.isNotEmpty()) SelectionContainer { Text(message) }
        }
    }
    consent?.let { ask ->
        val c = ask.context
        AlertDialog(
            onDismissRequest = { boxes.answerInvitationConsent(ask, false) },
            title = { Text("Sign this private invitation?") },
            text = { SelectionContainer { Text("Persona: ${ask.persona}\nDevice: ${ask.device}\nOriginal credential: ${ask.credentialId}\nGroup: ${c.group}\nGroup home: ${c.groupHome}\nInstallation: ${c.installation}\nInviter rendezvous: ${c.inviterRendezvous}\nTarget persona: ${c.joinerPersona}\nTarget device: ${c.joinerDevice}\nTarget rendezvous: ${c.joinerRendezvous}\nTarget leaf home: ${c.leafHome}\nReturn: ${c.returnNode ?: "authenticated private OOB"}\nExpires: ${c.expiresAt}\nThis approval applies only to this invitation.") } },
            confirmButton = { TextButton({ boxes.answerInvitationConsent(ask, true) }) { Text("Sign invitation") } },
            dismissButton = { TextButton({ boxes.answerInvitationConsent(ask, false) }) { Text("Refuse") } },
        )
    }
}

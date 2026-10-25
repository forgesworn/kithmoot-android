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
internal fun ExperimentalAdminInvitationControls(boxes: VmlsBoxes, persona: String, signer: () -> dev.forgesworn.kithmoot.account.ParticipantSigner?) {
    val state by boxes.state.collectAsState()
    val consent by boxes.adminInvitationConsent.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var session by remember(persona) { mutableStateOf("") }
    var name by remember { mutableStateOf("Admin2 development room") }
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
    var roles by remember(persona, session) { mutableStateOf<dev.forgesworn.kithmoot.mls.ExperimentalAdminRoles?>(null) }
    var roleIntent by remember(persona, session) { mutableStateOf<Pair<dev.forgesworn.kithmoot.mls.ExperimentalAdminRoles, dev.forgesworn.kithmoot.mls.ExperimentalAdminDevice>?>(null) }
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
            Text("Designated device admins (Admin2)", style = MaterialTheme.typography.titleMedium)
            Text("Development only. Only the current designated device admins may invite, Add, Remove, promote or demote. A role belongs to one exact MLS device leaf. Node keeper authority is separate. Use an authenticated private channel; this panel publishes nothing to relays or a directory.")
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
            Button({ run { boxes.createAdminInvitationRoom(persona, box, name).also { session = it } } }, enabled = !busy) { Text("Create separate Admin2 room") }
            OutlinedTextField(session, { session = it.trim() }, label = { Text("Local session id (hex)") })
            Button({ run { roles = boxes.currentAdminRoles(persona, session); "Applied native MLS roles loaded." } }, enabled = !busy) { Text("Refresh current device admin roles") }
            roles?.let { current ->
                Text("MLS epoch: ${current.epoch}\nAdmin set: ${current.commitment}\nThis device is ${if (current.ownIsAdmin) "a group admin" else "a member"}. Node keeper rights are separate.")
                current.devices.forEach { member ->
                    Text("Persona: ${member.persona}\nDevice: ${member.device}\nLeaf: ${member.leaf}\nRole: ${if (member.admin) "Group admin" else "Member"}")
                    if (current.ownIsAdmin) TextButton({ roleIntent = current to member }, enabled = !busy && (!member.admin || current.devices.count { it.admin } > 1)) {
                        Text(if (member.admin) "Demote this device" else "Promote this device")
                    }
                }
            }
            Text("Issue to the exact device agreed privately. The invitation lasts six hours. Joining needs more than one hour of validity remaining. Expiry must fit the inviter’s current room binding.")
            OutlinedTextField(targetPersona, { targetPersona = it.trim() }, label = { Text("Intended persona") })
            OutlinedTextField(targetDevice, { targetDevice = it.trim() }, label = { Text("Intended MLS device") })
            OutlinedTextField(targetRendezvous, { targetRendezvous = it.trim() }, label = { Text("Intended rendezvous key") })
            OutlinedTextField(targetHome, { targetHome = it.trim() }, label = { Text("Intended leaf home") })
            Button({ run {
                boxes.issuePrivateAdminInvitation(persona, session, ExperimentalInvitationTarget(targetPersona, targetDevice, targetRendezvous, targetHome, System.currentTimeMillis() / 1000 + 6 * 3600)).also { authorization = it }
                "Invitation protected. Select and copy it only into the authenticated private ceremony."
            } }, enabled = !busy) { Text("Issue private invitation") }
            OutlinedTextField(authorization, { authorization = it.trim(); review = "" }, label = { Text("Private invitation bytes (base64)") }, maxLines = 6)
            OutlinedTextField(trustedInviter, { trustedInviter = it.trim() }, label = { Text("Inviter persona from independent trusted source") })
            Row {
                Checkbox(authenticated, { authenticated = it })
                Text("I selected this persona through an authenticated contact or private ceremony, independently of the pasted invitation.")
            }
            Button({ run { boxes.reviewPrivateAdminInvitation(persona, trustedInviter, authorization).also { review = it } } }, enabled = !busy && authenticated) { Text("Review intended context") }
            if (review.isNotEmpty()) {
                SelectionContainer { Text(review) }
                Button({ run { boxes.acceptPrivateAdminInvitation(persona, trustedInviter, authorization, name).also { session = it } } }, enabled = !busy && authenticated) { Text("Confirm context and accept invitation") }
            }
            Button({ run { boxes.exportPrivateAdminInvitationResponse(persona, session).also { response = it }; "Original protected response ready for private return." } }, enabled = !busy) { Text("Export original response / retry") }
            OutlinedTextField(response, { response = it.trim() }, label = { Text("Private response bytes (base64)") }, maxLines = 6)
            Button({ run { boxes.importPrivateAdminInvitationResponse(persona, session, authorization, response) } }, enabled = !busy) { Text("Durably accept response") }
            Button({ run { boxes.exportAdminInvitationRegistrationRequest(persona, session, authorization, response).also { registrationRequest = it }; "Copy this minimal request privately to an independently authorized node keeper." } }, enabled = !busy) { Text("Export keeper registration request") }
            OutlinedTextField(registrationRequest, { registrationRequest = it.trim(); registrationReview = "" }, label = { Text("Private keeper request (base64)") }, maxLines = 4)
            Text("Independent keeper: import only this request. Group membership and the private invitation are unnecessary for registration.")
            Button({ run { boxes.reviewInvitationRegistrationRequest(registrationRequest).also { registrationReview = it } } }, enabled = !busy) { Text("Review keeper request") }
            if (registrationReview.isNotEmpty()) {
                SelectionContainer { Text(registrationReview) }
                Button({ run { boxes.registerInvitationRequest(persona, registrationRequest).also { registrationReceipt = it }; "Return this signed receipt privately to the MLS member." } }, enabled = !busy) { Text("Authorize this keeper registration") }
            }
            OutlinedTextField(registrationReceipt, { registrationReceipt = it.trim() }, label = { Text("Node-signed registration receipt (base64)") }, maxLines = 4)
            Button({ run { boxes.addPrivateAdminInvitationMember(persona, session, authorization, response, registrationReceipt) } }, enabled = !busy) { Text("Verify receipt and authorize admin MLS Add") }
            OutlinedTextField(removeLeaf, { removeLeaf = it.trim() }, label = { Text("Exact member device leaf to remove") })
            Button({ run { boxes.removeAdminInvitationMember(persona, session, removeLeaf) } }, enabled = !busy) { Text("Authorize admin MLS Remove") }
            Text("Existing room controls drive Welcome, required Update and messages. This development UI currently uses a single paired home for delivery.")
            if (message.isNotEmpty()) SelectionContainer { Text(message) }
        }
    }
    roleIntent?.let { (reviewed, member) ->
        AlertDialog(onDismissRequest = { roleIntent = null },
            title = { Text(if (member.admin) "Demote this device?" else "Promote this device?") },
            text = { SelectionContainer { Text("Persona: ${member.persona}\nDevice: ${member.device}\nExact leaf: ${member.leaf}\nReviewed admin set: ${reviewed.commitment}\nThis changes only this device's MLS role. It grants no node storage rights.") } },
            confirmButton = { TextButton({ roleIntent = null; run { boxes.changeAdminRole(persona, session, member.leaf, !member.admin, reviewed.commitment).also { roles = null } } }) { Text("Confirm device role") } },
            dismissButton = { TextButton({ roleIntent = null }) { Text("Cancel") } })
    }
    consent?.let { ask ->
        val c = ask.context
        AlertDialog(
            onDismissRequest = { boxes.answerAdminInvitationConsent(ask, false) },
            title = { Text("Sign this private invitation?") },
            text = { SelectionContainer { Text("Profile: ${c.profile}\nPolicy: ${c.policy}\nAdmin set: ${c.adminSetCommitment}\nInvitation: ${c.invitationId}\nPersona: ${ask.persona}\nDevice: ${ask.device}\nOriginal credential: ${ask.credentialId}\nGroup: ${c.group}\nGroup home: ${c.groupHome}\nInstallation: ${c.installation}\nInviter rendezvous: ${c.inviterRendezvous}\nTarget persona: ${c.joinerPersona}\nTarget device: ${c.joinerDevice}\nTarget rendezvous: ${c.joinerRendezvous}\nTarget leaf home: ${c.leafHome}\nReturn: ${c.returnNode ?: "authenticated private OOB"}\nInvitation expires: ${c.expiresAt}\nSigning deadline: ${ask.deadline}\nThis approval applies only to this invitation.") } },
            confirmButton = { TextButton({ boxes.answerAdminInvitationConsent(ask, true) }) { Text("Sign invitation") } },
            dismissButton = { TextButton({ boxes.answerAdminInvitationConsent(ask, false) }) { Text("Refuse") } },
        )
    }
}

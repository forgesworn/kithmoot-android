package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.PersonaCoordination
import dev.forgesworn.kithmoot.account.RestoreWitness
import dev.forgesworn.kithmoot.account.WitnessEnrolment
import dev.forgesworn.kithmoot.ui.qr.QrCode
import dev.forgesworn.kithmoot.ui.qr.QrScanner

/**
 * The debug-only "Restore witness" screen (P3-03b-2, C5): enrols the signed-in
 * account's MLS vault at its keeper's own box, end to end (B3). Reachable only
 * when the build has a [RestoreWitness], which release builds never do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreWitnessScreen(witness: RestoreWitness, persona: String?, onBack: () -> Unit) {
    val state by witness.state.collectAsState()
    LaunchedEffect(persona) { witness.open(persona) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Restore witness", style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Debug builds only. Your own Bothy box witnesses every change to this account's vault, " +
                    "so a restored or copied phone is fenced instead of carrying on.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val busy = state.busy
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (persona == null) {
                Text("Sign in to enrol this account's vault.")
                return@Column
            }
            when (val enrolment = state.enrolment.takeIf { state.persona == persona }) {
                null -> CircularProgressIndicator()
                WitnessEnrolment.None -> {
                    Heading("1. Start")
                    Text("This mints the vault's own installation and Link identity for this account. Nothing is sent yet.")
                    Button({ witness.prepare(persona) }, enabled = !busy) { Text("Start enrolment") }
                }
                is WitnessEnrolment.Prepared -> PairStep(witness, persona, enrolment.writer, busy)
                is WitnessEnrolment.Paired -> {
                    Heading("3. Take genesis")
                    Labelled("This phone's writer", enrolment.writer)
                    Labelled("Your box", enrolment.box)
                    Button({ witness.begin(persona) }, enabled = !busy) { Text("Take genesis") }
                }
                is WitnessEnrolment.Enrolled -> if (state.status is CoordinationStatus.Fenced) {
                    // The witness fenced it (a copy lost, or the box is behind): never the enrol line again.
                    Heading("Fenced")
                    Text(
                        "Your box refused this vault as a copy or a rollback " +
                            "(${(state.status as CoordinationStatus.Fenced).reason}). It will not be used again. " +
                            "Replace this installation, then ask your box's keeper to retire it.",
                    )
                    ReplaceButton(busy, primary = true) { witness.replace(persona) }
                } else {
                    Heading("4. Enrol on your box")
                    Text(statusLine(state.status), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    enrolment.line?.let { line ->
                        if (state.status !is CoordinationStatus.Active) {
                            Text("Run this on your box, then check:")
                            Command(line, "The enrol line as a QR code")
                            // A restore or copy taken before genesis finished reuses an
                            // enrolled installation or writer: only the box can tell.
                            Text(
                                "If your box refuses the enrol line itself (an installation id already used, or a writer that still " +
                                    "serves a live subject), this phone was restored or copied from an earlier " +
                                    "enrolment. Replace this installation and enrol afresh.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button({ witness.checkNow(persona) }, enabled = !busy) { Text("Check now") }
                    ReplaceButton(busy) { witness.replace(persona) }
                }
                is WitnessEnrolment.Fenced -> {
                    Heading("Fenced")
                    Text(
                        "This phone's vault will not be used again (${enrolment.reason}). " +
                            "Ask your box's keeper to retire it, then enrol afresh.",
                    )
                    enrolment.subject?.let { subject ->
                        Command(PersonaCoordination.retireLine(subject), "The retire line as a QR code")
                        Text(
                            "If the box this phone paired with answers \"refused\", it never enrolled this subject, so there is nothing to retire.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button({ witness.keeperRetired(persona, subject) }, enabled = !busy) { Text("The keeper has retired it") }
                    } ?: Text("Its subject is unknown here: the keeper retires it by this phone's writer on the box.")
                    TextButton({ witness.checkNow(persona) }, enabled = !busy) { Text("Check now") }
                }
            }
        }
    }
}

@Composable
private fun PairStep(witness: RestoreWitness, persona: String, writer: String, busy: Boolean) {
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    Heading("2. Pair with your box")
    Labelled("This phone's writer", writer)
    Text("On your box, run `bothyd witness pair`, then scan or paste the code it shows. It lasts ten minutes and works once.")
    if (scanning) {
        QrScanner(
            accept = { it.startsWith("bothy:") },
            onDecoded = { scanning = false; witness.pair(persona, it) },
            prompt = "Allow the camera to scan the box's witness code.",
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
        TextButton({ scanning = false }) { Text("Cancel scan") }
    } else {
        Button({ scanning = true }, enabled = !busy) { Text("Scan code") }
    }
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Or paste the code") }, singleLine = true)
    Button({ witness.pair(persona, code) }, enabled = !busy && code.isNotBlank()) { Text("Pair") }
}

@Composable
private fun ReplaceButton(busy: Boolean, primary: Boolean = false, onReplace: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (primary) Button({ confirming = true }, enabled = !busy) { Text("Replace this installation") }
    else TextButton({ confirming = true }, enabled = !busy) { Text("Replace this installation") }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Replace this installation?") },
        text = { Text("This phone's vault for this account is cleared. Your box's keeper must retire it before you can enrol again.") },
        confirmButton = { TextButton({ confirming = false; onReplace() }) { Text("Replace") } },
        dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Labelled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Command(line: String, qrDescription: String) {
    SelectionContainer { Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    QrCode(line, qrDescription, Modifier.size(240.dp))
}

private fun statusLine(status: CoordinationStatus?): String = when (status) {
    CoordinationStatus.Active -> "Active: your box confirms this vault."
    is CoordinationStatus.Pending -> if (status.refused) "Pending: your box refused this phone. Has the keeper run the enrol line?" else "Pending: your box has not confirmed this vault yet."
    CoordinationStatus.NotEnrolled -> "Not enrolled."
    is CoordinationStatus.Fenced -> "Fenced (${status.reason})."
    null -> "Checking…"
}

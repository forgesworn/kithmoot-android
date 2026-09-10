package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.WebAppAddress

@Composable
fun SiteAddressDialog(current: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    var saveFailed by remember { mutableStateOf(false) }
    val parsed = runCatching { WebAppAddress.parse(value) }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Site settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a KithMoot site you trust. It is used for shared room links, contact cards and returning from browser sign-in.")
            OutlinedTextField(value, { value = it; saveFailed = false }, Modifier.fillMaxWidth(),
                label = { Text("Site address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = parsed == null,
                supportingText = { Text("Use an HTTPS address without a path, such as https://chat.example.") })
            if (saveFailed) Text("The site address could not be saved. Try again.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton({
            val address = parsed ?: return@TextButton
            if (onSave(address.origin)) onDismiss() else saveFailed = true
        }, enabled = parsed != null) { Text("Save site") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

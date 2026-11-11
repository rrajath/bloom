package com.rrajath.grove.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Single-field name dialog: notebook and folder names on the Notebooks
 * screen, the attachment folder in Settings § Attachments.
 */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    contextLabel: String? = null,
    placeholder: String = "notebook.org",
    // Returns a user-facing reason the name is invalid, or null when it's fine.
    // When set, the confirm button stays disabled until the name validates.
    validate: ((String) -> String?)? = null,
    // Persistent hint under the field, shown while there's no validation error.
    helperText: String? = null,
    // A fixed extension (e.g. ".org") shown as a non-editable suffix inside the
    // field; the field then holds just the stem, and it's re-appended on confirm.
    filenameSuffix: String? = null,
) {
    val c = MaterialTheme.grove
    var name by remember {
        mutableStateOf(filenameSuffix?.let { initial.removeSuffix(it) } ?: initial)
    }
    // Normalise so the callback always gets exactly one trailing suffix, whether
    // the user left it off or typed it in themselves.
    val resolved = filenameSuffix?.let { name.removeSuffix(it) + it } ?: name
    val error = if (name.isBlank()) null else validate?.invoke(resolved)
    val canConfirm = name.isNotBlank() && error == null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink) },
        text = {
            Column {
                if (contextLabel != null) {
                    Text(
                        contextLabel,
                        fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text(placeholder, fontFamily = PlexMono, color = c.ink3) },
                    suffix = filenameSuffix?.let {
                        { Text(it, fontFamily = PlexMono, color = c.ink3) }
                    },
                    textStyle = TextStyle(fontFamily = PlexMono, color = c.ink),
                    modifier = Modifier.testTag("name_dialog_field"),
                    supportingText = if (error != null || helperText != null) {
                        {
                            Text(
                                error ?: helperText!!,
                                fontFamily = PlexSans, fontSize = 12.sp,
                                color = if (error != null) c.red else c.ink3,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(resolved) },
                enabled = canConfirm,
            ) { Text(confirmLabel, color = if (canConfirm) c.accent else c.ink3, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) }
        },
    )
}

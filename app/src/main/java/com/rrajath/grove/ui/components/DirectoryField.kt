package com.rrajath.grove.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.capture.FilenameValidation
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.asString

/**
 * A vault-directory field with an inline autocomplete list of existing directories and
 * [FilenameValidation.errorForDirectory] inline errors, for a Roam template's target
 * directory. Same expanding-list shape as [NotebookFileField]. A blank [value] means the
 * vault root, always offered as the first suggestion. Typing a directory that doesn't
 * exist yet is accepted as-is — it's created on the first capture into it, same as
 * [com.rrajath.grove.vault.Vault.createNotebook] already does for notebook files.
 */
@Composable
fun DirectoryField(
    value: String,
    onValueChange: (String) -> Unit,
    directories: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.directory_vault_root),
) {
    val c = MaterialTheme.grove
    var menuOpen by remember { mutableStateOf(false) }
    val filteredDirectories by remember(directories, value) {
        derivedStateOf {
            if (value.isBlank()) directories
            else directories.filter { it.contains(value, ignoreCase = true) }
        }
    }
    val error = FilenameValidation.errorForDirectory(value)

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); menuOpen = true },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> menuOpen = state.isFocused },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(error.asString(), color = c.red, fontFamily = PlexSans, fontSize = 12.sp)
                }
            },
            textStyle = TextStyle(fontFamily = PlexMono),
            placeholder = { Text(placeholder, fontFamily = PlexMono, color = c.ink3) },
            trailingIcon = {
                Text(
                    "▾", fontFamily = PlexMono, fontSize = 16.sp, color = c.ink2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuOpen = !menuOpen }
                        .padding(8.dp),
                )
            },
        )
        AnimatedVisibility(menuOpen) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.surface2)
                    .padding(6.dp)
                    .heightIn(max = 176.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.directory_vault_root), fontFamily = PlexMono, fontSize = 13.5.sp, color = c.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable {
                            onValueChange("")
                            menuOpen = false
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
                filteredDirectories.forEach { dir ->
                    Text(
                        dir, fontFamily = PlexMono, fontSize = 13.5.sp, color = c.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                onValueChange(dir)
                                menuOpen = false
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

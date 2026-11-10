package com.rrajath.grove.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.capture.FilenameValidation
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * An `.org` filename field with an inline autocomplete list of existing vault notebooks and
 * [FilenameValidation] inline errors. Originally the capture-template editor's "Target file"
 * field; shared with Settings › Sharing's target-file field so both pick a notebook the same way.
 *
 * An inline expanding list (not a `DropdownMenu` popup) so the suggestions push content below
 * them instead of overlapping the field, and so the IME isn't stolen from the field on every
 * keystroke as the suggestion list recomposes.
 */
@Composable
fun NotebookFileField(
    value: String,
    onValueChange: (String) -> Unit,
    notebooks: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String = "notebook.org",
) {
    val c = MaterialTheme.grove
    var menuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Hold the selection ourselves so a value set from outside the keyboard (picking a suggestion,
    // clearing) lands the cursor at the end instead of where it sat in the typed prefix.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (fieldValue.text != value) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    val filteredNotebooks by remember(notebooks, value) {
        derivedStateOf {
            if (value.isBlank()) notebooks
            else notebooks.filter { it.contains(value, ignoreCase = true) }
        }
    }
    val error = FilenameValidation.errorFor(value)

    Column(modifier) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != value) onValueChange(it.text)
                menuOpen = true
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state -> menuOpen = state.isFocused },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(error, color = c.red, fontFamily = PlexSans, fontSize = 12.sp)
                }
            },
            textStyle = TextStyle(fontFamily = PlexMono),
            placeholder = { Text(placeholder, fontFamily = PlexMono, color = c.ink3) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isNotEmpty()) {
                        Text(
                            "×", fontFamily = PlexMono, fontSize = 16.sp, color = c.ink3,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onValueChange("")
                                    menuOpen = true
                                    focusRequester.requestFocus()
                                    keyboard?.show()
                                }
                                .padding(8.dp),
                        )
                    }
                    Text(
                        "▾", fontFamily = PlexMono, fontSize = 16.sp, color = c.ink2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { menuOpen = !menuOpen }
                            .padding(8.dp),
                    )
                }
            },
        )
        AnimatedVisibility(menuOpen && filteredNotebooks.isNotEmpty()) {
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
                filteredNotebooks.forEach { nb ->
                    Text(
                        nb, fontFamily = PlexMono, fontSize = 13.5.sp, color = c.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                onValueChange(nb)
                                menuOpen = false
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

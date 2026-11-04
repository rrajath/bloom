package com.rrajath.grove.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rrajath.grove.ui.theme.grove

/**
 * The `green` Material check from the Notebooks top bar's "everything is
 * synced" status, shared so other "this one is current" marks (the Agenda
 * widget view picker's selected row) look identical to it.
 */
@Composable
fun SyncedCheckIcon(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.grove.green,
) {
    Icon(Icons.Default.Check, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

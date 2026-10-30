package com.rrajath.grove.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.rrajath.grove.ui.theme.grove

/**
 * The app's popup menu (long-press menus, overflow menus, link menus). Sits on
 * `surface2` with a 1dp `line2` border so it stands apart from `surface` screens
 * and the drawer, which a plain `surface` menu blended into. Use this instead of
 * Material's [DropdownMenu]; `NoRawDropdownMenuTest` enforces it.
 */
@Composable
fun GroveDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MaterialTheme.grove
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        containerColor = c.surface2,
        border = BorderStroke(1.dp, c.line2),
        content = content,
    )
}

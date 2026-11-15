package com.rrajath.grove.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
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

/**
 * A [GroveDropdownMenu] item for a file action the vault's provider may rule
 * out. Available: a plain item ([destructive] labels in `red`). Blocked
 * ([reason] set): disabled, label in `ink3`, with the reason on a second
 * line (PlexSans 12sp `ink3`), so it reads as "can't" rather than missing.
 */
@Composable
fun ProviderGatedMenuItem(
    label: String,
    reason: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val c = MaterialTheme.grove
    val blocked = reason != null
    androidx.compose.material3.DropdownMenuItem(
        text = {
            androidx.compose.foundation.layout.Column(
                if (blocked) Modifier.padding(vertical = 6.dp) else Modifier,
            ) {
                androidx.compose.material3.Text(
                    label,
                    fontFamily = com.rrajath.grove.ui.theme.PlexSans,
                    color = when {
                        blocked -> c.ink3
                        destructive -> c.red
                        else -> c.ink
                    },
                )
                if (reason != null) {
                    androidx.compose.material3.Text(
                        reason,
                        fontFamily = com.rrajath.grove.ui.theme.PlexSans,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = c.ink3,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        enabled = !blocked,
        onClick = onClick,
    )
}

// i18n: all user-visible text comes from string resources
package com.rrajath.grove.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.asString

/**
 * Settings > WebDAV. The password field is prefilled from the Keystore-backed
 * store on this screen only; the URL and username live in DataStore, and the
 * whole configuration is deliberately excluded from settings backups.
 */
@Composable
fun SettingsWebDavScreen(
    state: WebDavViewModel.UiState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val c = MaterialTheme.grove
    SettingsPageScaffold(
        title = stringResource(R.string.settings_page_webdav_title),
        onBack = onBack,
    ) {
        SectionLabel(stringResource(R.string.webdav_section_server))
        SettingsGroup {
            ToggleRow(
                label = stringResource(R.string.webdav_enable),
                description = stringResource(R.string.webdav_enable_desc),
                checked = state.enabled,
                onToggle = onEnabledChange,
            )
            RowDivider()
            WebDavTextField(
                label = R.string.webdav_url,
                value = state.url,
                onValueChange = onUrlChange,
                hint = R.string.webdav_url_hint,
            )
            RowDivider()
            WebDavTextField(
                label = R.string.webdav_username,
                value = state.username,
                onValueChange = onUsernameChange,
            )
            RowDivider()
            WebDavTextField(
                label = R.string.webdav_password,
                value = state.password,
                onValueChange = onPasswordChange,
                password = true,
            )
            if (state.passwordStored) {
                Text(
                    stringResource(R.string.webdav_password_stored),
                    fontFamily = PlexSans,
                    fontSize = 11.5.sp,
                    color = c.ink3,
                    modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 10.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WebDavAction(stringResource(R.string.action_save), !state.busy, onSave)
            WebDavAction(stringResource(R.string.webdav_test), !state.busy, onTest)
            WebDavAction(stringResource(R.string.webdav_sync_now), !state.busy, onSyncNow)
        }

        val status = state.status
        if (status != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                status.asString(),
                fontFamily = PlexSans,
                fontSize = 13.sp,
                color = c.ink2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel(stringResource(R.string.webdav_section_how))
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.webdav_help),
                    fontFamily = PlexSans,
                    fontSize = 12.5.sp,
                    color = c.ink2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.webdav_https_only),
                    fontFamily = PlexSans,
                    fontSize = 12.5.sp,
                    color = c.ink2,
                )
            }
        }
    }
}

@Composable
private fun WebDavTextField(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes hint: Int? = null,
    password: Boolean = false,
) {
    val c = MaterialTheme.grove
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = {
            Text(stringResource(label), fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink2)
        },
        placeholder = if (hint != null) {
            { Text(stringResource(hint), fontFamily = PlexMono, fontSize = 11.sp, color = c.ink3) }
        } else {
            null
        },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
        ),
        textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, color = c.ink),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

@Composable
private fun WebDavAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) c.accentSoft else c.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            fontFamily = PlexSans,
            fontSize = 13.sp,
            color = if (enabled) c.accent else c.ink3,
        )
    }
}

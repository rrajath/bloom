package com.rrajath.grove.ui.vault

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.StorageNoticeBanner
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.vault.VaultProvider
import kotlinx.coroutines.launch

/** Docs section on folders served by cloud apps, opened by the Settings note's "Learn more". */
const val PROVIDER_WARNING_DOCS_URL = "https://bloom.rrajath.com/features/sync/#folders-from-cloud-apps"

/**
 * The name of the app serving [treeUri] (e.g. "Seafile"), or null when it can't be
 * resolved. The app holding a URI grant to a provider can see it under package
 * visibility rules, so no `<queries>` entry is needed.
 */
fun providerAppLabel(context: Context, treeUri: String): String? {
    val authority = VaultProvider.authorityOf(treeUri) ?: return null
    val pm = context.packageManager
    return runCatching {
        pm.resolveContentProvider(authority, 0)?.applicationInfo?.loadLabel(pm)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** [providerAppLabel], or "this storage provider" when the app can't be named. */
@Composable
fun rememberProviderName(treeUri: String): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.provider_warning_fallback_name)
    return remember(treeUri) { providerAppLabel(context, treeUri) } ?: fallback
}

/**
 * One-time warning for a vault folder served by another app (see
 * [VaultProvider.shouldShowWarningDialog]). There's no outside-tap or back dismissal:
 * the user picks either [onChooseAnother] (opens the folder picker; the dialog returns
 * if they cancel) or [onUseAnyway] (records the answer for this folder).
 * Same M3 `AlertDialog` styling as `ExactAlarmPromptDialog`.
 */
@Composable
fun ProviderWarningDialog(
    treeUri: String,
    onChooseAnother: () -> Unit,
    onUseAnyway: () -> Unit,
) {
    val c = MaterialTheme.grove
    val provider = rememberProviderName(treeUri)
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.surface,
        title = {
            Text(
                stringResource(R.string.provider_warning_title),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Text(
                stringResource(R.string.provider_warning_body, stringResource(R.string.app_name), provider),
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onChooseAnother) {
                Text(
                    stringResource(R.string.provider_warning_choose_another),
                    fontFamily = PlexSans, color = c.accent, fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onUseAnyway) {
                Text(stringResource(R.string.provider_warning_use_anyway), fontFamily = PlexSans, color = c.ink2)
            }
        },
    )
}

/**
 * Notebooks banner for a non-local vault, until dismissed for that folder. Reads and
 * writes settings itself, so any screen can drop it in. Renders nothing otherwise.
 */
@Composable
fun ProviderWarningBanner(modifier: Modifier = Modifier) {
    // Screen tests may host Notebooks under a plain Application: no banner there.
    val repo = (LocalContext.current.applicationContext as? GroveApplication)?.settingsRepository ?: return
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = GroveSettings())
    val uri = settings.vaultTreeUri
    if (uri == null || !VaultProvider.shouldShowBanner(settings)) return
    val scope = rememberCoroutineScope()
    StorageNoticeBanner(
        message = stringResource(R.string.provider_warning_banner, rememberProviderName(uri)),
        actionLabel = stringResource(R.string.provider_warning_dismiss),
        onAction = { scope.launch { repo.dismissProviderBanner(uri) } },
        modifier = modifier,
    )
}

/**
 * Settings › Sync: the warning under the Folder row for a non-local vault, in `amber`
 * (the warning colour) at the row description's 12sp, with an `accent` "Learn more".
 */
@Composable
fun ProviderWarningNote(treeUri: String) {
    val c = MaterialTheme.grove
    val uriHandler = LocalUriHandler.current
    val provider = rememberProviderName(treeUri).replaceFirstChar { it.uppercase() }
    Column {
        Text(
            stringResource(R.string.provider_warning_settings_note, provider),
            fontFamily = PlexSans, fontSize = 12.sp, color = c.amber,
        )
        Text(
            stringResource(R.string.provider_warning_learn_more),
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.accent,
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable { uriHandler.openUri(PROVIDER_WARNING_DOCS_URL) }
                .padding(vertical = 2.dp),
        )
    }
}

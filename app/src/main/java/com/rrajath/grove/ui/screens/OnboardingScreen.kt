package com.rrajath.grove.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.components.BrandMark
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Onboarding per design spec §1. The primary CTA opens the SAF folder picker;
 * "later" skips; the Notebooks screen offers the picker again.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onFolderPicked: (String) -> Unit,
) {
    val c = MaterialTheme.grove
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            onFolderPicked(uri.toString())
            onDone()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        BrandMark(tileSize = 74.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = c.ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            fontFamily = PlexSans,
            fontSize = 15.sp,
            color = c.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        Spacer(Modifier.weight(1f))

        // Syncthing setup card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(R.string.onboarding_recommended), fg = c.green, bg = c.greenSoft)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.onboarding_syncthing_title),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, color = c.ink,
                )
            }
            Spacer(Modifier.height(14.dp))
            OnboardingStep(1, stringResource(R.string.onboarding_step_install))
            OnboardingStep(2, stringResource(R.string.onboarding_step_share))
            OnboardingStep(3, stringResource(R.string.onboarding_step_point, stringResource(R.string.app_name)))
        }

        Spacer(Modifier.height(26.dp))

        // Primary CTA
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.accent)
                .clickable(onClick = { folderPicker.launch(null) }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.onboarding_choose_folder),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, color = c.accentInk,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.onboarding_later),
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            )
        }
    }
}

@Composable
private fun OnboardingStep(number: Int, text: String) {
    val c = MaterialTheme.grove
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, color = c.accent,
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text,
            fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink2,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

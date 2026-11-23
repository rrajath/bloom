package com.rrajath.grove.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * Uppercases a label with the UI language's rules (Turkish i, German ß). All-caps
 * section headers are stored in normal case in the string resources and capitalized
 * here, so translators never write them in capitals.
 */
@Composable
@ReadOnlyComposable
fun String.uppercaseForUi(): String =
    uppercase(ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT)

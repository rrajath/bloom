// i18n: status messages come from string resources; remaining literals are internal reasons
package com.rrajath.grove.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.ui.util.UiText
import com.rrajath.grove.webdav.WebDavAuthException
import com.rrajath.grove.webdav.WebDavConfig
import com.rrajath.grove.webdav.WebDavHttpException
import com.rrajath.grove.webdav.WebDavRateLimitedException
import com.rrajath.grove.webdav.WebDavUrl
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings > WebDAV: connection form, connectivity check and a manual sync. */
class WebDavViewModel(private val app: GroveApplication) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val url: String = "",
        val username: String = "",
        /** Held in memory for this screen only; never written to DataStore or exported. */
        val password: String = "",
        val passwordStored: Boolean = false,
        val busy: Boolean = false,
        val status: UiText? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        viewModelScope.launch {
            val settings = app.settingsRepository.settings.first()
            val stored = runCatching { app.webDavSecrets.load() }.getOrNull()
            _ui.value = _ui.value.copy(
                enabled = settings.webdavEnabled,
                url = settings.webdavUrl,
                username = settings.webdavUsername,
                password = stored.orEmpty(),
                passwordStored = stored != null,
            )
        }
    }

    fun onUrlChange(value: String) = _ui.update { it.copy(url = value, status = null) }
    fun onUsernameChange(value: String) = _ui.update { it.copy(username = value, status = null) }
    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, status = null) }

    fun setEnabled(enabled: Boolean) {
        // Capture the config before the switch flips: configProvider() returns
        // null once WebDAV is off, so this is the last chance to push edits that
        // exist only in the mirror. App-scoped so leaving the screen can't cancel it.
        val finalConfig = if (enabled) null else currentConfig()
        _ui.update {
            it.copy(
                enabled = enabled,
                status = if (enabled) null else UiText.res(R.string.webdav_status_disabled),
            )
        }
        viewModelScope.launch {
            app.settingsRepository.setWebDavEnabled(enabled)
            if (enabled) {
                app.webDavSync.requestSync("webdav enabled")
            } else if (finalConfig != null) {
                app.appScope.launch {
                    runCatching { app.webDavSync.sync(finalConfig) }
                        .onFailure { android.util.Log.w("webdav", "final push failed: ${it.message}") }
                }
            }
        }
    }

    fun save() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.webdav_status_saving)) }
        val ok = try {
            persist()
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        if (!ok) {
            _ui.update { it.copy(busy = false) }
            return@launch
        }
        _ui.update { it.copy(busy = false, status = UiText.res(R.string.webdav_status_saved)) }
        app.webDavSync.requestSync("webdav saved")
    }

    /** Checks settings before the switch is on, so a user can validate credentials first. */
    fun test() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.webdav_status_testing)) }
        val config = try {
            if (!persist()) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            currentConfig()
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        if (config == null) {
            _ui.update { it.copy(busy = false, status = UiText.res(R.string.webdav_error_url)) }
            return@launch
        }
        val count = try {
            app.webDavSync.test(config)
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        _ui.update { it.copy(busy = false, status = UiText.res(R.string.webdav_status_test_ok, count)) }
    }

    fun syncNow() = viewModelScope.launch {
        if (!_ui.value.enabled) {
            _ui.update { it.copy(status = UiText.res(R.string.webdav_error_disabled)) }
            return@launch
        }
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.webdav_status_syncing)) }
        val ok = try {
            persist()
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        if (!ok) {
            _ui.update { it.copy(busy = false) }
            return@launch
        }
        val result = try {
            app.webDavSync.sync()
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        _ui.update {
            it.copy(
                busy = false,
                status = UiText.res(
                    R.string.webdav_status_sync_ok,
                    result.pulled,
                    result.pushed,
                    result.conflicts,
                ),
            )
        }
        // Rebuild the note index from the freshly reconciled mirror.
        app.syncManager.requestSync("webdav manual")
    }

    /** Validates and stores URL/username/password. Returns false (with a status) on bad input. */
    private suspend fun persist(): Boolean {
        val state = _ui.value
        val normalized = WebDavUrl.normalize(state.url)
        if (normalized == null) {
            val message = if (state.url.trim().startsWith("http://", ignoreCase = true)) {
                R.string.webdav_error_https
            } else {
                R.string.webdav_error_url
            }
            _ui.update { it.copy(status = UiText.res(message)) }
            return false
        }
        if (state.username.isBlank() || state.password.isBlank()) {
            _ui.update { it.copy(status = UiText.res(R.string.webdav_error_credentials)) }
            return false
        }
        app.settingsRepository.setWebDavConfig(normalized, state.username.trim())
        app.webDavSecrets.save(state.password)
        _ui.update { it.copy(url = normalized, passwordStored = true) }
        return true
    }

    private fun currentConfig(): WebDavConfig? {
        val state = _ui.value
        val url = WebDavUrl.normalize(state.url) ?: return null
        return WebDavConfig(url, state.username.trim(), state.password)
    }

    /** Translate low-level network exceptions into something the user can act on. */
    private fun failure(error: Throwable): UiText {
        val detail = error.message?.takeIf { it.isNotBlank() }.orEmpty()
        return when {
            error is WebDavAuthException ->
                UiText.res(R.string.webdav_error_auth, detail)
            error is UnknownHostException ->
                UiText.res(R.string.webdav_error_dns, detail)
            error is SSLException ->
                UiText.res(R.string.webdav_error_tls, detail)
            error is TimeoutCancellationException || error is SocketTimeoutException ->
                UiText.res(R.string.webdav_error_timeout, detail)
            error is ConnectException ->
                UiText.res(R.string.webdav_error_network, detail)
            error is WebDavRateLimitedException ->
                UiText.res(R.string.webdav_error_rate_limited, error.statusCode, error.retryAfterSeconds, detail)
            error is WebDavHttpException ->
                UiText.res(R.string.webdav_error_http, detail)
            else -> detail.takeIf { it.isNotBlank() }?.let { UiText.raw(it) }
                ?: UiText.res(R.string.webdav_error_unknown)
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as GroveApplication
                return WebDavViewModel(app) as T
            }
        }
    }
}

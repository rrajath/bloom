// i18n: status messages come from string resources; remaining literals are internal reasons
package com.rrajath.grove.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.ai.AiConfig
import com.rrajath.grove.ai.AiConfigProblem
import com.rrajath.grove.ai.AiEndpoint
import com.rrajath.grove.ai.AiException
import com.rrajath.grove.ai.OpenAiCompatibleClient
import com.rrajath.grove.ui.util.UiText
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings > AI task breakdown: provider form, encrypted key and a round-trip check. */
class AiSettingsViewModel(private val app: GroveApplication) : ViewModel() {

    data class UiState(
        val baseUrl: String = AiConfig.DEFAULT_BASE_URL,
        val model: String = AiConfig.DEFAULT_MODEL,
        /** Held in memory for this screen only; never written to DataStore or exported. */
        val apiKey: String = "",
        val keyStored: Boolean = false,
        val busy: Boolean = false,
        val status: UiText? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        viewModelScope.launch {
            val settings = app.settingsRepository.settings.first()
            val stored = runCatching { app.aiSecrets.load() }.getOrNull()
            _ui.value = _ui.value.copy(
                baseUrl = settings.aiBaseUrl,
                model = settings.aiModel,
                apiKey = stored.orEmpty(),
                keyStored = stored != null,
            )
        }
    }

    fun onBaseUrlChange(value: String) = _ui.update { it.copy(baseUrl = value, status = null) }
    fun onModelChange(value: String) = _ui.update { it.copy(model = value, status = null) }
    fun onApiKeyChange(value: String) = _ui.update { it.copy(apiKey = value, status = null) }

    fun save() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.ai_status_saving)) }
        val stored = try {
            persist() != null
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        _ui.update {
            it.copy(
                busy = false,
                status = if (stored) UiText.res(R.string.ai_status_saved) else it.status,
            )
        }
    }

    fun test() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.ai_status_testing)) }
        val config = try {
            persist()
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        if (config == null) {
            _ui.update { it.copy(busy = false) }
            return@launch
        }
        val reply = try {
            OpenAiCompatibleClient(config).testConnection()
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
            return@launch
        }
        _ui.update { it.copy(busy = false, status = UiText.res(R.string.ai_status_test_ok, reply)) }
    }

    /** Validates and stores URL/model/key. Returns the usable config, or null with a status. */
    private suspend fun persist(): AiConfig? {
        val state = _ui.value
        val raw = AiConfig(state.baseUrl, state.model, state.apiKey)
        val problem = AiEndpoint.validate(raw)
        if (problem != null) {
            _ui.update { it.copy(status = UiText.res(problem.messageRes())) }
            return null
        }
        val normalized = AiEndpoint.normalize(state.baseUrl.trim()) ?: return null
        val model = state.model.trim()
        val key = state.apiKey.trim()
        app.settingsRepository.setAiConfig(normalized, model)
        app.aiSecrets.save(key)
        _ui.update {
            it.copy(baseUrl = normalized, model = model, apiKey = key, keyStored = true, status = null)
        }
        return AiConfig(normalized, model, key)
    }

    private fun AiConfigProblem.messageRes(): Int = when (this) {
        AiConfigProblem.BASE_URL -> R.string.ai_error_url
        AiConfigProblem.INSECURE_URL -> R.string.ai_error_https
        AiConfigProblem.MODEL -> R.string.ai_error_model
        AiConfigProblem.API_KEY -> R.string.ai_error_key
    }

    private fun failure(error: Throwable): UiText {
        val detail = error.message?.takeIf { it.isNotBlank() }.orEmpty()
        return when {
            error is AiException && (error.statusCode == 401 || error.statusCode == 403) ->
                UiText.res(R.string.ai_error_auth, detail)
            error is TimeoutCancellationException || error is SocketTimeoutException ->
                UiText.res(R.string.ai_error_timeout, detail)
            error is UnknownHostException || error is ConnectException ->
                UiText.res(R.string.ai_error_network, detail)
            error is AiException ->
                UiText.res(R.string.ai_error_http, error.statusCode ?: 0, detail)
            else -> UiText.res(R.string.ai_error_unknown, detail)
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as GroveApplication
                return AiSettingsViewModel(app) as T
            }
        }
    }
}

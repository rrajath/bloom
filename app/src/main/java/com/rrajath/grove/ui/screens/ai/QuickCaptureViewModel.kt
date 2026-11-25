// i18n: status messages come from string resources; remaining literals are internal reasons
package com.rrajath.grove.ui.screens.ai

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
import com.rrajath.grove.ai.CaptureGranularity
import com.rrajath.grove.ai.InboxAppender
import com.rrajath.grove.ai.OpenAiCompatibleClient
import com.rrajath.grove.ai.QuickCaptureEngine
import com.rrajath.grove.ui.util.UiText
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One-shot natural-language capture from the Notebooks home screen.
 *
 * The natural-language text is the single source of truth: toggling the shape
 * re-runs capture with a different [CaptureGranularity], so switching between
 * "one TODO" and "TODO with subtasks" never loses the user's words. The org
 * preview stays editable and is inserted through the same vault path as the
 * wizard.
 */
class QuickCaptureViewModel(private val app: GroveApplication) : ViewModel() {

    data class UiState(
        val text: String = "",
        val granularity: CaptureGranularity = CaptureGranularity.AUTO,
        val orgText: String = "",
        val busy: Boolean = false,
        val status: UiText? = null,
        val inserted: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun onTextChange(value: String) = _ui.update { it.copy(text = value, status = null, inserted = false) }

    fun onGranularityChange(value: CaptureGranularity) {
        if (_ui.value.granularity == value) return
        _ui.update { it.copy(granularity = value, status = null) }
        // Keep the preview in sync with the newly chosen shape when there is
        // already a result on screen; an empty composer is just a setting.
        if (_ui.value.orgText.isNotBlank() && _ui.value.text.isNotBlank()) generate()
    }

    fun onOrgTextChange(value: String) = _ui.update { it.copy(orgText = value, status = null) }

    fun generate() {
        val text = _ui.value.text.trim()
        if (text.isEmpty()) {
            _ui.update { it.copy(status = UiText.res(R.string.ai_quick_required)) }
            return
        }
        launchStep {
            val engine = engine() ?: return@launchStep
            val plan = engine.capture(text, _ui.value.granularity)
            _ui.update {
                it.copy(
                    orgText = engine.render(plan),
                    status = UiText.res(R.string.ai_quick_generated),
                    inserted = false,
                )
            }
        }
    }

    /**
     * Append the generated org to [targetFile] (inbox.org from the list screen,
     * the currently open file from a note/file screen).
     *
     * [beforeWrite] flushes an editor's unsaved buffer under its own save lock;
     * returning false aborts the insert so a stale buffer can never overwrite
     * what we append. [afterWrite] lets the caller fold the new text back into
     * its editor buffer / reload its document after the disk write.
     */
    fun insert(
        targetFile: String = InboxAppender.TARGET_FILE,
        beforeWrite: suspend () -> Boolean = { true },
        afterWrite: (String) -> Unit = {},
    ) {
        launchStep {
            val settings = app.settingsRepository.settings.first()
            if (settings.vaultTreeUri == null && !settings.webdavEnabled) {
                _ui.update { it.copy(status = UiText.res(R.string.ai_wizard_no_vault)) }
                return@launchStep
            }
            if (!beforeWrite()) {
                _ui.update { it.copy(status = UiText.res(R.string.ai_quick_pending_unsaved)) }
                return@launchStep
            }
            val vault = app.vault.filterNotNull().first()
            val target = targetFile.ifBlank { InboxAppender.TARGET_FILE }
            if (vault.open(target) == null) vault.createNotebook(target)
            val current = vault.open(target)?.text.orEmpty()
            val newText = InboxAppender.append(current, _ui.value.orgText)
            vault.save(target, newText)
            app.syncManager.requestReindex(target, newText, "ai quick capture")
            afterWrite(newText)
            _ui.update {
                it.copy(
                    inserted = true,
                    status = UiText.res(R.string.ai_quick_done, target),
                )
            }
        }
    }

    fun clear() {
        _ui.value = UiState(granularity = _ui.value.granularity)
    }

    private suspend fun engine(): QuickCaptureEngine? {
        val settings = app.settingsRepository.settings.first()
        val key = runCatching { app.aiSecrets.load() }.getOrNull().orEmpty()
        val problem = AiEndpoint.validate(AiConfig(settings.aiBaseUrl, settings.aiModel, key))
        if (problem != null) {
            _ui.update { it.copy(status = UiText.res(problem.messageRes())) }
            return null
        }
        val normalized = AiEndpoint.normalize(settings.aiBaseUrl) ?: return null
        return QuickCaptureEngine(
            OpenAiCompatibleClient(AiConfig(normalized, settings.aiModel.trim(), key.trim())),
            LocalDate.now(),
        )
    }

    private fun launchStep(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.ai_quick_busy)) }
        try {
            block()
            _ui.update { it.copy(busy = false) }
        } catch (error: Throwable) {
            _ui.update { it.copy(busy = false, status = failure(error)) }
        }
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
                return QuickCaptureViewModel(app) as T
            }
        }
    }
}

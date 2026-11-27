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
import com.rrajath.grove.ai.DecomposeEngine
import com.rrajath.grove.ai.DecomposeStage
import com.rrajath.grove.ai.InboxAppender
import com.rrajath.grove.ai.OpenAiCompatibleClient
import com.rrajath.grove.ai.Qa
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
 * Four-step decomposition wizard. Each confirmation triggers exactly one model
 * request; a failure keeps the current stage, the already confirmed answers and
 * the chosen approach, so [retry] only repeats the failed step.
 */
class DecomposeViewModel(private val app: GroveApplication) : ViewModel() {

    data class UiState(
        val stage: DecomposeStage = DecomposeStage.TITLE,
        val title: String = "",
        val questions: List<String> = emptyList(),
        val answers: List<String> = emptyList(),
        val approaches: List<com.rrajath.grove.ai.Approach> = emptyList(),
        val selectedApproach: Int? = null,
        val designBlocks: List<String> = emptyList(),
        val confirmedBlocks: Int = 0,
        val orgText: String = "",
        val busy: Boolean = false,
        val status: UiText? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** One engine per run: it owns the conversation transcript across stages. */
    private var engine: DecomposeEngine? = null
    private var retryAction: (() -> Unit)? = null

    fun onTitleChange(value: String) = _ui.update { it.copy(title = value, status = null) }

    fun onAnswerChange(index: Int, value: String) = _ui.update { state ->
        if (index !in state.answers.indices) state
        else state.copy(
            answers = state.answers.toMutableList().also { it[index] = value },
            status = null,
        )
    }

    fun onSelectApproach(index: Int) = _ui.update { state ->
        if (index !in state.approaches.indices) state
        else state.copy(selectedApproach = index, status = null)
    }

    fun onOrgTextChange(value: String) = _ui.update { it.copy(orgText = value, status = null) }

    fun start() {
        retryAction = ::start
        val title = _ui.value.title.trim()
        if (title.isEmpty()) {
            _ui.update { it.copy(status = UiText.res(R.string.ai_wizard_title_required)) }
            return
        }
        launchStep {
            val e = ensureEngine() ?: return@launchStep
            val questions = e.clarify(title)
            _ui.update {
                it.copy(
                    stage = DecomposeStage.CLARIFY,
                    questions = questions,
                    answers = List(questions.size) { "" },
                    status = null,
                )
            }
        }
    }

    fun confirmAnswers() {
        retryAction = ::confirmAnswers
        val e = engine ?: return
        launchStep {
            val state = _ui.value
            val approaches = e.compare(state.title, qa(state))
            _ui.update {
                it.copy(
                    stage = DecomposeStage.COMPARE,
                    approaches = approaches,
                    selectedApproach = if (approaches.isEmpty()) null else 0,
                    status = null,
                )
            }
        }
    }

    fun confirmApproach() {
        retryAction = ::confirmApproach
        val e = engine ?: return
        launchStep {
            val state = _ui.value
            val approach = state.approaches.getOrNull(state.selectedApproach ?: -1)
                ?: throw AiException("No implementation path selected")
            val blocks = e.design(state.title, qa(state), approach)
            _ui.update {
                it.copy(
                    stage = DecomposeStage.DESIGN,
                    designBlocks = blocks,
                    confirmedBlocks = 0,
                    status = null,
                )
            }
        }
    }

    fun confirmDesignBlock() {
        retryAction = ::confirmDesignBlock
        val state = _ui.value
        val next = state.confirmedBlocks + 1
        if (next < state.designBlocks.size) {
            _ui.update { it.copy(confirmedBlocks = next, status = null) }
        } else {
            generatePlan()
        }
    }

    fun insert() {
        retryAction = ::insert
        launchStep {
            val settings = app.settingsRepository.settings.first()
            if (settings.vaultTreeUri == null && !settings.webdavEnabled) {
                _ui.update { it.copy(status = UiText.res(R.string.ai_wizard_no_vault)) }
                return@launchStep
            }
            // Same write path as capture: Vault.save reindexes through SyncTrigger,
            // which in turn feeds Room, widgets and the WebDAV push (if enabled).
            val vault = app.vault.filterNotNull().first()
            val target = InboxAppender.TARGET_FILE
            if (vault.open(target) == null) vault.createNotebook(target)
            val current = vault.open(target)?.text.orEmpty()
            val newText = InboxAppender.append(current, _ui.value.orgText)
            vault.save(target, newText)
            app.syncManager.requestReindex(target, newText, "ai task breakdown")
            _ui.update {
                it.copy(
                    stage = DecomposeStage.DONE,
                    status = UiText.res(R.string.ai_wizard_done, target),
                )
            }
        }
    }

    fun retry() {
        retryAction?.invoke()
    }

    fun reset() {
        engine = null
        retryAction = null
        _ui.value = UiState()
    }

    private fun generatePlan() {
        val e = engine ?: return
        retryAction = { generatePlan() }
        launchStep {
            val state = _ui.value
            val approach = state.approaches.getOrNull(state.selectedApproach ?: -1)
                ?: throw AiException("No implementation path selected")
            val plan = e.generate(state.title, qa(state), approach, state.designBlocks)
            _ui.update {
                it.copy(
                    stage = DecomposeStage.GENERATE,
                    orgText = e.render(plan),
                    status = null,
                )
            }
        }
    }

    private fun qa(state: UiState): List<Qa> =
        state.questions.zip(state.answers).map { Qa(it.first, it.second.trim()) }

    private suspend fun ensureEngine(): DecomposeEngine? {
        engine?.let { return it }
        val settings = app.settingsRepository.settings.first()
        val key = runCatching { app.aiSecrets.load() }.getOrNull().orEmpty()
        val problem = AiEndpoint.validate(AiConfig(settings.aiBaseUrl, settings.aiModel, key))
        if (problem != null) {
            _ui.update { it.copy(status = UiText.res(problem.messageRes())) }
            return null
        }
        val normalized = AiEndpoint.normalize(settings.aiBaseUrl) ?: return null
        return DecomposeEngine(
            OpenAiCompatibleClient(AiConfig(normalized, settings.aiModel.trim(), key.trim())),
            LocalDate.now(),
        ).also { engine = it }
    }

    private fun launchStep(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = UiText.res(R.string.ai_wizard_busy)) }
        try {
            block()
            // The block sets its own status (including the insertion result);
            // only clear the busy flag here.
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
                return DecomposeViewModel(app) as T
            }
        }
    }
}

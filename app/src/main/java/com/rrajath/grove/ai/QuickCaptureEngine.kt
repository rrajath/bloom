// i18n: internal reasons only; all user-visible text lives in string resources
package com.rrajath.grove.ai

import java.time.LocalDate

/**
 * One-shot natural-language capture: a single model round produces either one
 * TODO or a TODO with subtasks, carrying any DEADLINE / SCHEDULED / LOCATION /
 * priority the user actually mentioned.
 *
 * Pure JVM so a fake [AiClient] can drive it in unit tests. The ViewModel may
 * call [capture] again with a different [CaptureGranularity] when the user
 * toggles the shape; the original text is the only source of truth, so no
 * confirmed state is lost between switches.
 */
class QuickCaptureEngine(
    private val client: AiClient,
    private val today: LocalDate = LocalDate.now(),
) {

    suspend fun capture(text: String, granularity: CaptureGranularity): DecomposePlan {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw AiException("Nothing to capture")
        val reply = client.complete(
            listOf(
                QuickCapturePrompts.system(),
                AiMessage.user(QuickCapturePrompts.user(trimmed, granularity, today)),
            ),
        )
        return AiTagPolicy.enrichQuick(OrgTaskParser.parse(reply, allowSingle = true), trimmed)
    }

    fun render(plan: DecomposePlan): String = OrgTaskRenderer.render(plan)
}

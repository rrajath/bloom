// i18n: internal reasons only; all user-visible text lives in string resources
package com.rrajath.grove.ai

import java.time.LocalDate

/**
 * One-shot natural-language capture: a single model round produces one or more
 * TODOs (one per item the user described), each optionally with subtasks,
 * carrying any DEADLINE / SCHEDULED / LOCATION / priority the user mentioned.
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

    /**
     * One plan per top-level TODO the model returned, in input order. A
     * multi-line note ("打疫苗" on one line, "装 Bloom" on the next) therefore
     * becomes two sibling trees instead of only the last one surviving.
     */
    suspend fun capture(text: String, granularity: CaptureGranularity): List<DecomposePlan> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw AiException("Nothing to capture")
        val reply = client.complete(
            listOf(
                QuickCapturePrompts.system(),
                AiMessage.user(QuickCapturePrompts.user(trimmed, granularity, today)),
            ),
        )
        val plans = OrgTaskParser.parseMany(reply, allowSingle = true)
        // With several items the full note would mark every tree urgent, so
        // each tree is scored on its own title instead.
        val single = plans.size == 1
        return plans.map { AiTagPolicy.enrichQuick(it, if (single) trimmed else it.title) }
    }

    fun render(plans: List<DecomposePlan>): String = OrgTaskRenderer.render(plans)
}

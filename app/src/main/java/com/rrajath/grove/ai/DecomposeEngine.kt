// i18n: internal reasons and model-facing text, never shown as UI text
package com.rrajath.grove.ai

import java.time.LocalDate

/**
 * Orchestrates the four-step wizard. One instance belongs to one decomposition
 * run and keeps the whole conversation transcript, so every later stage sees
 * the questions, answers, chosen path and design the user already confirmed.
 *
 * The ViewModel calls exactly one method per user confirmation; callers must
 * serialize calls (the wizard's busy flag does that). All of this is pure JVM,
 * so a fake [AiClient] can drive an entire run in a unit test.
 */
class DecomposeEngine(
    private val client: AiClient,
    private val today: LocalDate = LocalDate.now(),
) {

    private val transcript = mutableListOf(DecomposePrompts.system())

    suspend fun clarify(title: String): List<String> =
        AiResponseParsers.questions(ask(DecomposePrompts.clarifyUser(title.trim())))

    suspend fun compare(title: String, qa: List<Qa>): List<Approach> =
        AiResponseParsers.approaches(ask(DecomposePrompts.compareUser(title, qa)))

    suspend fun design(title: String, qa: List<Qa>, approach: Approach): List<String> =
        AiResponseParsers.blocks(ask(DecomposePrompts.designUser(title, qa, approach)))

    suspend fun generate(
        title: String,
        qa: List<Qa>,
        approach: Approach,
        blocks: List<String>,
    ): DecomposePlan {
        val reply = ask(DecomposePrompts.generateUser(title, qa, approach, blocks, today))
        return AiTagPolicy.enrich(OrgTaskParser.parse(reply), title, today)
    }

    fun render(plan: DecomposePlan): String = OrgTaskRenderer.render(plan)

    fun reset() {
        transcript.clear()
        transcript += DecomposePrompts.system()
    }

    private suspend fun ask(prompt: String): String {
        transcript += AiMessage.user(prompt)
        val reply = client.complete(transcript.toList())
        transcript += AiMessage.assistant(reply)
        return reply
    }
}

package com.rrajath.grove.ai

/** The four conversational gates of the decompose wizard, plus the result screens. */
enum class DecomposeStage { TITLE, CLARIFY, COMPARE, DESIGN, GENERATE, DONE }

data class Qa(val question: String, val answer: String)

data class Approach(val title: String, val detail: String)

/** A `** TODO` subtask with its `***` checkpoints. */
data class DecomposeTask(
    val title: String,
    val tags: List<String> = emptyList(),
    /** Already-formatted org timestamp, e.g. `<2026-12-02 Wed>`. */
    val deadlineStamp: String? = null,
    val checkpoints: List<String> = emptyList(),
)

/** A `* TODO` top-level task and its subtasks. */
data class DecomposePlan(
    val title: String,
    val tags: List<String> = emptyList(),
    val priority: Char? = null,
    val deadlineStamp: String? = null,
    val tasks: List<DecomposeTask> = emptyList(),
)

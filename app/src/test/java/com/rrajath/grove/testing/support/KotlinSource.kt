package com.rrajath.grove.testing.support

import java.io.File

/** One string literal found in Kotlin source: 1-based [line], [offset] of its opening quote, decoded [text]. */
data class KotlinLiteral(val line: Int, val offset: Int, val text: String)

/** The `app/` module directory, whether Gradle runs the test from the repo root or from `app/`. */
val appModuleDir: File =
    File("app").takeIf { File(it, "src/main").isDirectory } ?: File(".")

/**
 * Every string literal in [src], skipping comments and char literals. Handles `"…"`,
 * raw `"""…"""` and escapes; `${…}` template code is kept as part of the literal,
 * which is fine for the source-scanning guard tests that use this.
 */
fun kotlinStringLiterals(src: String): List<KotlinLiteral> {
    val out = mutableListOf<KotlinLiteral>()
    var i = 0
    var line = 1
    fun advance(n: Int = 1) {
        repeat(n) {
            if (i < src.length && src[i] == '\n') line++
            i++
        }
    }
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') advance()
            src.startsWith("/*", i) -> {
                advance(2)
                while (i < src.length && !src.startsWith("*/", i)) advance()
                advance(2)
            }
            src.startsWith("\"\"\"", i) -> {
                val startOffset = i
                advance(3)
                val startLine = line
                val start = i
                while (i < src.length && !src.startsWith("\"\"\"", i)) advance()
                out += KotlinLiteral(startLine, startOffset, src.substring(start, minOf(i, src.length)))
                advance(3)
            }
            src[i] == '"' -> {
                val startOffset = i
                advance()
                val startLine = line
                val sb = StringBuilder()
                while (i < src.length && src[i] != '"' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(src[i + 1])
                        advance(2)
                    } else {
                        sb.append(src[i])
                        advance()
                    }
                }
                out += KotlinLiteral(startLine, startOffset, sb.toString())
                advance()
            }
            src[i] == '\'' -> {
                advance()
                while (i < src.length && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\') advance(2) else advance()
                }
                advance()
            }
            else -> advance()
        }
    }
    return out
}

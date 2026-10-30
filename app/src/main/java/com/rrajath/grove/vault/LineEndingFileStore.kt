package com.rrajath.grove.vault

import java.util.concurrent.ConcurrentHashMap

/**
 * Normalizes line endings at the vault boundary. Everything above the store
 * (parser, editor, index, mutations) assumes `\n`; a file saved with Windows
 * CRLF endings would otherwise leave a `\r` on every line, which defeats the
 * parser's `matchEntire` regexes (no headlines, empty property drawers) and
 * shifts the editor's syntax highlighting one character per line.
 *
 * [read] returns `\n`-only text and remembers which files were CRLF; [write]
 * converts back for those files, so editing a CRLF file in the app doesn't
 * rewrite every line of it on the desktop side. A file is CRLF when its first
 * line ends in `\r\n` (the same first-terminator rule Emacs uses). Writes to
 * any other file are normalized to `\n`, so pasted or shared CRLF text can't
 * leave mixed endings behind.
 */
class LineEndingFileStore(private val delegate: FileStore) : FileStore by delegate {

    private val crlf: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun read(name: String): String {
        val raw = delegate.read(name)
        if (isCrlf(raw)) crlf.add(name) else crlf.remove(name)
        return normalizeLineEndings(raw)
    }

    override suspend fun write(name: String, content: String) {
        val lf = normalizeLineEndings(content)
        delegate.write(name, if (name in crlf) lf.replace("\n", "\r\n") else lf)
    }

    override suspend fun rename(oldName: String, newName: String): Boolean {
        val renamed = delegate.rename(oldName, newName)
        if (renamed && crlf.remove(oldName)) crlf.add(newName)
        return renamed
    }

    override suspend fun delete(name: String): Boolean {
        crlf.remove(name)
        return delegate.delete(name)
    }

    private fun isCrlf(text: String): Boolean {
        val nl = text.indexOf('\n')
        return nl > 0 && text[nl - 1] == '\r'
    }
}

/** [text] with every `\r\n` turned into `\n`. */
internal fun normalizeLineEndings(text: String): String =
    if ('\r' in text) text.replace("\r\n", "\n") else text

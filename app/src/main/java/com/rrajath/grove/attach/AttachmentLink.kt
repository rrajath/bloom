package com.rrajath.grove.attach

/** Picks a file's badge color in read mode, previews and the attach sheet. */
enum class AttachmentCategory { DOCUMENT, IMAGE, AUDIO, VIDEO, ARCHIVE, GENERIC }

/** What a local attachment path is relative to. */
enum class AttachmentBase {
    /** `attachment:` links: the owner's attach dir. */
    ATTACH_DIR,

    /** `file:` / bare relative links: the org file's directory. */
    ORG_DIR,
}

/** A link that points at a local, non-`.org` file. */
data class LocalAttachment(
    val base: AttachmentBase,
    /** Path as written in the link, relative to [base] (search option stripped). */
    val path: String,
    /** Rendered as an inline image (no description, image extension) rather than a chip. */
    val isImage: Boolean,
    /** Chip label: the link description, else the file name. */
    val label: String,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val extension: String get() = attachmentExtension(fileName)
    val category: AttachmentCategory get() = categoryOf(extension)

    /** Badge text: the uppercased extension (max 4 chars), or `FILE` with none. */
    val badge: String get() = badgeFor(fileName)
}

object AttachmentLink {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val FILE_SCHEMES = listOf("file+sys:", "file+emacs:", "file:")
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

    fun isImageExtension(ext: String): Boolean = ext.lowercase() in IMAGE_EXTENSIONS

    /**
     * Classifies a link target. Returns null for links that aren't local files:
     * `.org` targets (in-app navigation), other schemes (`https:`, `id:`, …),
     * `#custom-id`, `*heading`, and fuzzy text.
     */
    fun classify(rawTarget: String, description: String?): LocalAttachment? {
        val target = rawTarget.trim()
        val desc = description?.trim()?.takeIf { it.isNotEmpty() }
        val (base, rawPath) = when {
            target.startsWith("attachment:", ignoreCase = true) ->
                AttachmentBase.ATTACH_DIR to target.substring("attachment:".length)
            else -> {
                val scheme = FILE_SCHEMES.firstOrNull { target.startsWith(it, ignoreCase = true) }
                when {
                    scheme != null -> AttachmentBase.ORG_DIR to target.substring(scheme.length)
                    SCHEME.containsMatchIn(target) -> return null
                    looksLikePath(target) -> AttachmentBase.ORG_DIR to target
                    else -> return null
                }
            }
        }
        val path = rawPath.substringBefore("::").trim()
        if (path.isEmpty() || path.endsWith("/")) return null
        if (path.endsWith(".org", ignoreCase = true)) return null
        val name = path.substringAfterLast('/')
        val image = desc == null && isImageExtension(attachmentExtension(name))
        return LocalAttachment(base, path, image, desc ?: name)
    }

    private fun looksLikePath(target: String): Boolean =
        target.startsWith("./") || target.startsWith("../") || target.startsWith("/") ||
            target.startsWith("~/") || target.contains('/')
}

/** Lowercased extension of [fileName] (after the last dot), or "" when none. */
fun attachmentExtension(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot <= 0 || dot == fileName.length - 1) "" else fileName.substring(dot + 1).lowercase()
}

fun badgeFor(fileName: String): String =
    attachmentExtension(fileName).takeIf { it.isNotEmpty() }?.uppercase()?.take(4) ?: "FILE"

fun categoryOf(extension: String): AttachmentCategory = when (extension.lowercase()) {
    "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "csv", "xls", "xlsx", "ppt", "pptx", "epub" ->
        AttachmentCategory.DOCUMENT
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tiff", "tif" ->
        AttachmentCategory.IMAGE
    "m4a", "mp3", "wav", "ogg", "flac", "aac", "opus" -> AttachmentCategory.AUDIO
    "mp4", "mov", "mkv", "webm", "3gp", "avi" -> AttachmentCategory.VIDEO
    "zip", "tar", "gz", "tgz", "7z", "rar" -> AttachmentCategory.ARCHIVE
    else -> AttachmentCategory.GENERIC
}

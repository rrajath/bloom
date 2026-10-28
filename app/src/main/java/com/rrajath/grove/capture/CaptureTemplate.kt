package com.rrajath.grove.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Where a capture template inserts its entry (PRD §7.4). */
@Serializable
sealed class TargetLocation {
    @Serializable
    @SerialName("top")
    data object TopOfFile : TargetLocation()

    @Serializable
    @SerialName("bottom")
    data object BottomOfFile : TargetLocation()

    /**
     * Insert as child of a heading, found by [id] (recommended, robust) or by
     * exact [title]. [id] matches a heading's `:ID:` first, then its
     * `:CUSTOM_ID:`. [customId] is the legacy field older templates saved;
     * it's still honored (via [headingKey]) but the editor now writes [id].
     * [appendLast] = insert after the last existing child.
     */
    @Serializable
    @SerialName("under_heading")
    data class UnderHeading(
        val title: String? = null,
        val customId: String? = null,
        val appendLast: Boolean = true,
        val id: String? = null,
    ) : TargetLocation() {
        /** The id to resolve the heading by: [id], else the legacy [customId]. */
        val headingKey: String? get() = id ?: customId
    }

    /**
     * Year → Month → Day tree; entry under the day heading. Keeps the serial
     * name of the old "Datetree (datetime)" location so existing templates and
     * older builds read it unchanged; the retired "datetree_date" location is
     * migrated in [TemplateSerializer.decode].
     */
    @Serializable
    @SerialName("datetree_datetime")
    data object Datetree : TargetLocation()

    val isDatetree: Boolean get() = this is Datetree

    fun describe(): String = when (this) {
        is TopOfFile -> "top of file"
        is BottomOfFile -> "bottom of file"
        is UnderHeading -> "under " + (headingKey?.let { "#$it" } ?: title ?: "heading")
        is Datetree -> "datetree"
    }
}

/** Dash-separated form of a template name for its bloom://capture/{slug} deep link. */
fun templateSlug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** What a capture template produces: an insert into an existing file, or a Roam node. */
@Serializable
enum class TemplateKind { PLAIN, ROAM_NODE }

@Serializable
data class CaptureTemplate(
    val id: String,
    val name: String,
    /**
     * Monogram palette key ("green", "blue", …) for the template's icon tile; null
     * derives one from the id. The glyph itself is the first letter of [name].
     */
    val color: String? = null,
    val targetFile: String,
    val location: TargetLocation,
    val template: String,
    val kind: TemplateKind = TemplateKind.PLAIN,
    /** Vault-relative directory for a Roam node's computed filename; "" = vault root. */
    val roamDirectory: String = "",
    /** Stem-only pattern (no `.org`) for a Roam node's computed filename. */
    val filenamePattern: String = "%<%Y%m%d%H%M%S>-%(slug)",
    /**
     * Expanded as a new file's contents on the first capture to a resolved filename.
     * The file-level `:ID:` drawer is added automatically ([RoamFileId]), not written here.
     */
    val newFileTemplate: String = "#+title: %?",
)

object TemplateSerializer {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(templates: List<CaptureTemplate>): String =
        json.encodeToString(TemplateListSurrogate(templates))

    fun decode(text: String): List<CaptureTemplate> =
        runCatching {
            json.decodeFromJsonElement<TemplateListSurrogate>(migrate(json.parseToJsonElement(text))).templates
        }.getOrDefault(emptyList())

    /**
     * Rewrites templates saved with the retired "Datetree (date)" location
     * (`datetree_date`) to [TargetLocation.Datetree]. That location expanded
     * `%T`/`%U` without a time, so their bodies swap to `%t`/`%u` to keep
     * every capture identical. Must run before decoding: an unknown location
     * type would fail the whole list, and decode falls back to no templates.
     */
    private fun migrate(root: JsonElement): JsonElement {
        val obj = root as? JsonObject ?: return root
        val templates = obj["templates"] as? JsonArray ?: return root
        val migrated = templates.map { t ->
            val template = t as? JsonObject ?: return@map t
            val location = template["location"] as? JsonObject ?: return@map t
            if ((location["type"] as? JsonPrimitive)?.contentOrNull != LEGACY_DATETREE_DATE) return@map t
            JsonObject(
                template.mapValues { (key, value) ->
                    when (key) {
                        "location" -> JsonObject(location + ("type" to JsonPrimitive(DATETREE_SERIAL_NAME)))
                        "template", "newFileTemplate" ->
                            (value as? JsonPrimitive)?.contentOrNull
                                ?.let { JsonPrimitive(PlaceholderExpander.dateOnlyStamps(it)) }
                                ?: value
                        else -> value
                    }
                }
            )
        }
        return JsonObject(obj + ("templates" to JsonArray(migrated)))
    }

    private const val LEGACY_DATETREE_DATE = "datetree_date"
    private const val DATETREE_SERIAL_NAME = "datetree_datetime"

    @Serializable
    private data class TemplateListSurrogate(val templates: List<CaptureTemplate>)
}

/** Built-in defaults (PRD §7.5); editable/deletable like any other template. */
object DefaultTemplates {
    val all: List<CaptureTemplate> = listOf(
        CaptureTemplate(
            id = "builtin-journal",
            name = "Journal Entry",
            targetFile = "journal.org",
            location = TargetLocation.Datetree,
            // First line of the entry is its heading; the cursor lands right
            // after the stamp, on the heading, ready for input.
            template = "%U %cursor",
        ),
        CaptureTemplate(
            id = "builtin-quick-note",
            name = "Quick Note",
            targetFile = "inbox.org",
            location = TargetLocation.BottomOfFile,
            // Bare heading marker; the cursor lands right after it so the
            // heading text is the first thing typed.
            template = "* %cursor",
        ),
        CaptureTemplate(
            id = "builtin-todo",
            name = "TODO",
            targetFile = "tasks.org",
            location = TargetLocation.BottomOfFile,
            template = "* TODO %^{Title}\n%cursor",
        ),
    )
}

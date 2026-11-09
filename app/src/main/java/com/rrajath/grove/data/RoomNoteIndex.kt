package com.rrajath.grove.data

import com.rrajath.grove.attach.attachDirProperty
import com.rrajath.grove.attach.fileAttachDirProperty
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.sync.KnownNotebook
import com.rrajath.grove.sync.NoteIndex
import com.rrajath.grove.sync.NotebookStub

/** [NoteIndex] over Room: parses notebook text into row entities. */
class RoomNoteIndex(
    private val db: GroveDatabase,
    private val keywords: () -> OrgKeywords = { OrgKeywords.DEFAULT },
    /**
     * Notified with the freshly parsed [com.rrajath.grove.org.OrgDocument] right
     * after each notebook is (re)indexed, so e.g. reminder reconciliation can run
     * per-file instead of waiting for the whole vault to finish syncing.
     */
    private val onIndexed: suspend (fileName: String, doc: com.rrajath.grove.org.OrgDocument) -> Unit = { _, _ -> },
) : NoteIndex {

    private val dao get() = db.indexDao()

    override suspend fun knownNotebooks(): Map<String, KnownNotebook> =
        dao.notebookSyncStates().associate {
            it.fileName to KnownNotebook(it.revision, it.conflictFileName, it.isIndexed)
        }

    override suspend fun stubNotebooks(stubs: List<NotebookStub>) {
        dao.insertNotebookStubs(
            stubs.map {
                NotebookEntity(
                    fileName = it.fileName,
                    revision = it.revision,
                    noteCount = 0,
                    lastModified = it.lastModified,
                    conflictFileName = it.conflictFileName,
                    title = null,
                    isIndexed = false,
                )
            }
        )
    }

    override suspend fun indexNotebook(
        fileName: String,
        revision: String,
        text: String,
        lastModified: Long,
        conflictFileName: String?,
    ) {
        val doc = OrgParser.parse(text, keywords())
        val inheritedTagsAll = doc.inheritedTagsAll()
        val notes = doc.headlines.mapIndexed { i, h ->
            NoteEntity(
                fileName = fileName,
                lineIndex = h.lineIndex,
                level = h.level,
                title = h.title,
                keyword = h.keyword,
                priority = h.priority?.toString(),
                tags = h.tags.joinToString(":"),
                inheritedTags = inheritedTagsAll[i].joinToString(":"),
                scheduled = h.planning.scheduled?.format(),
                deadline = h.planning.deadline?.format(),
                closed = h.planning.closed?.format(),
                activeTimestamps = h.activeTimestamps
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" ") { it.format() },
                orgId = h.id,
                customId = h.customId,
                createdAt = h.properties["CREATED"],
                body = doc.bodyOf(h).joinToString("\n"),
                isDone = h.keyword != null && doc.keywords.isDone(h.keyword),
                lastModified = lastModified,
                dirProperty = h.attachDirProperty(),
            )
        } + introNote(doc, fileName, lastModified)
        dao.replaceNotebook(
            NotebookEntity(
                fileName = fileName,
                revision = revision,
                // Top-level headings only: subheadings are part of their note. A
                // intro (if any) counts as one more note.
                noteCount = doc.headlines.count { it.level == 1 } +
                    (if (doc.hasIntro) 1 else 0),
                lastModified = lastModified,
                conflictFileName = conflictFileName,
                title = doc.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }?.second,
                orgId = doc.filePropertyDrawer.firstOrNull { it.first.equals(":ID:", ignoreCase = true) }?.second,
                dirProperty = doc.fileAttachDirProperty(),
            ),
            notes,
        )
        onIndexed(fileName, doc)
    }

    /**
     * The file's intro (its heading-less content) as a single index row (or empty when the
     * file has no such content). Keyed at [INTRO_LINE_INDEX] so it round-trips
     * through search results and `NoteRef` like any other note.
     */
    private fun introNote(doc: OrgDocument, fileName: String, lastModified: Long): List<NoteEntity> {
        if (!doc.hasIntro) return emptyList()
        return listOf(
            NoteEntity(
                fileName = fileName,
                lineIndex = INTRO_LINE_INDEX,
                level = 0,
                title = doc.introTitle,
                keyword = null,
                priority = null,
                tags = "",
                inheritedTags = doc.fileTags.joinToString(":"),
                scheduled = null,
                deadline = null,
                closed = null,
                activeTimestamps = null,
                orgId = null,
                customId = null,
                createdAt = null,
                body = doc.introBody.joinToString("\n").trim(),
                isDone = false,
                lastModified = lastModified,
            )
        )
    }

    override suspend fun setConflict(fileName: String, conflictFileName: String?) {
        dao.setConflict(fileName, conflictFileName)
    }

    override suspend fun removeNotebook(fileName: String) {
        dao.removeNotebook(fileName)
    }
}

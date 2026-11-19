package com.rrajath.grove.testing

import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.RoomNoteIndex
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.vault.FileStore

/**
 * Writes fixture `.org` files into a [FileStore] and, optionally, populates a
 * [GroveDatabase] index from them. Shared by the integration tests, the Maestro
 * debug-vault hook (M5), and the benchmark module (M6) so every layer starts
 * from the same vault contents.
 */
object TestVaultSeeder {

    /** Write every entry of [files] into [store], overwriting any existing content. */
    suspend fun seed(store: FileStore, files: Map<String, String> = OrgFixtures.all) {
        files.forEach { (name, content) -> store.write(name, content) }
    }

    /**
     * Index every non-dot-dir `.org` file currently in [store] into [db], the
     * same parse-to-rows path a real sync uses ([RoomNoteIndex.indexNotebook]),
     * so a ViewModel test can exercise search / agenda queries against a
     * populated index without standing up the whole `SyncManager`.
     *
     * Call [seed] first (or build the store with initial content).
     */
    suspend fun index(
        db: GroveDatabase,
        store: FileStore,
        keywords: OrgKeywords = OrgKeywords.DEFAULT,
    ) {
        val roomIndex = RoomNoteIndex(db, { keywords })
        store.list()
            .filter { it.name.endsWith(".org") }
            .forEach { entry ->
                roomIndex.indexNotebook(
                    fileName = entry.name,
                    revision = "${entry.lastModified}:${entry.size}",
                    text = store.read(entry.name),
                    lastModified = entry.lastModified,
                    conflictFileName = null,
                )
            }
    }
}

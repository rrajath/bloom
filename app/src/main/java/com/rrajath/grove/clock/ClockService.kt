package com.rrajath.grove.clock

import com.rrajath.grove.data.RunningClockRow
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.clocks
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.vault.AutoArchive
import com.rrajath.grove.vault.StateChangeResult
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDateTime

/**
 * A heading with an open clock, as the index last saw it. [start] is the clock's
 * identity: [ClockService] re-finds the open `CLOCK:` line by it, so a clock
 * survives lines shifting above it, and an external edit that closed it is
 * noticed instead of overwritten.
 */
data class RunningClock(
    val fileName: String,
    val lineIndex: Int,
    val title: String,
    val keyword: String?,
    val start: LocalDateTime,
)

/** One file a clock action rewrote: [before] is what an undo writes back, [doc] the parse of [after]. */
data class FileWrite(val fileName: String, val before: String, val after: String, val doc: OrgDocument)

/** What the user picked for an open clock in the resolve dialog. */
sealed interface ClockFix {
    /** Close it at [at] (`org-resolve-clocks`' keep / keep-until). */
    data class OutAt(val at: LocalDateTime) : ClockFix

    /** Delete it (`C`). */
    data object Cancel : ClockFix

    /** Leave it open, dangling, next to the new clock. */
    data object Keep : ClockFix
}

sealed interface ClockResult {
    /** The files were written; [writes] carries their before/after text for undo. */
    data class Done(val writes: List<FileWrite>) : ClockResult

    /** The heading's own clock was already running and nothing else needed closing. */
    data object Continues : ClockResult

    /** Clocking in would close [clocks] that look dangling; ask how to fix each first. */
    data class NeedsResolution(val clocks: List<RunningClock>) : ClockResult

    /** The heading or its open clock is gone from the file (edited elsewhere). */
    data object NotFound : ClockResult
}

/**
 * Every clock edit goes through here, so one running clock per vault holds the
 * way `org-clock-in` keeps one per Emacs: clocking in closes the clock already
 * running (at the same minute, so no time falls between them) and only asks
 * first when that clock looks dangling (see [STALE_AFTER]) or several are open.
 *
 * Running state is never stored here. [running] maps the index's open-clock
 * rows, which come from the `.org` files like everything else in it, so a clock
 * started in Emacs and synced in shows up the same as one started in Bloom.
 * Each action re-reads its files ([Vault.open]) instead of trusting a cached
 * document, saves them, and hands them to [reindex], so the index (and with it
 * [running], the notification and the widget) catches up at once.
 */
class ClockService(
    private val vault: StateFlow<Vault?>,
    runningRows: Flow<List<RunningClockRow>>,
    private val settings: SettingsSource,
    private val reindex: suspend (fileName: String, text: String) -> Unit,
    scope: CoroutineScope,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    /** Open clocks, newest start first; the first is "the" running clock. */
    val running: StateFlow<List<RunningClock>> = runningRows
        .map { rows -> rows.mapNotNull { it.toRunningClock() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mutex = Mutex()

    /**
     * Clock in on the heading at [lineIndex] of [fileName]. [base] stands in for
     * that file's disk text, when the caller holds unsaved edits to it that this
     * write should keep. [fixes] answers an earlier [ClockResult.NeedsResolution].
     */
    suspend fun clockIn(
        fileName: String,
        lineIndex: Int,
        base: OrgDocument? = null,
        fixes: Map<RunningClock, ClockFix> = emptyMap(),
    ): ClockResult = mutex.withLock {
        val vault = awaitVault() ?: return@withLock ClockResult.NotFound
        val settings = settings.settings.first()
        val at = now().withSecond(0).withNano(0)
        val batch = Batch(vault, base?.let { fileName to it })
        val doc = batch.doc(fileName) ?: return@withLock ClockResult.NotFound
        val target = doc.headlines.firstOrNull { it.lineIndex == lineIndex } ?: return@withLock ClockResult.NotFound

        // Every other open clock, re-checked against the files themselves. The
        // target's own open clock is matched by its start, not its indexed
        // line, which a stale index may have wrong.
        val own = doc.clocks(target).filter { it.isRunning }.mapTo(HashSet()) { it.start }
        val others = running.value
            .filterNot { it.fileName == fileName && it.start in own }
            .filter { batch.locate(it) != null }
        val unanswered = others.filter { fixes.fixFor(it) == null }
        val ask = others.size > 1 || others.any { Duration.between(it.start, at) >= STALE_AFTER }
        if (ask && unanswered.isNotEmpty()) return@withLock ClockResult.NeedsResolution(unanswered)

        // The new clock first: the others are re-found by their start stamps,
        // so lines this inserts above them don't matter.
        val switchTo = settings.clockInState?.takeIf { it in doc.keywords.all }
        OrgMutations.clockIn(doc, target, at, switchTo)?.let { batch.put(fileName, it) }

        for (other in others) {
            when (val fix = fixes.fixFor(other) ?: ClockFix.OutAt(at)) {
                is ClockFix.OutAt -> closeInto(batch, settings, other, fix.at)
                ClockFix.Cancel -> cancelInto(batch, other)
                ClockFix.Keep -> Unit
            }
        }
        commit(batch).takeIf { it.isNotEmpty() }?.let { ClockResult.Done(it) } ?: ClockResult.Continues
    }

    /**
     * Clock out [clock] at [at] (`org-clock-out`), switching state per Settings.
     * [base], as for [clockIn], stands in for the clock's file.
     */
    suspend fun clockOut(
        clock: RunningClock,
        at: LocalDateTime = now(),
        base: OrgDocument? = null,
    ): ClockResult = mutex.withLock {
        val vault = awaitVault() ?: return@withLock ClockResult.NotFound
        val batch = Batch(vault, base?.let { clock.fileName to it })
        if (!closeInto(batch, settings.settings.first(), clock, at.withSecond(0).withNano(0))) {
            return@withLock ClockResult.NotFound
        }
        ClockResult.Done(commit(batch))
    }

    /** Cancel [clock] (`org-clock-cancel`): its open line goes, nothing is logged. */
    suspend fun cancel(clock: RunningClock, base: OrgDocument? = null): ClockResult = mutex.withLock {
        val vault = awaitVault() ?: return@withLock ClockResult.NotFound
        val batch = Batch(vault, base?.let { clock.fileName to it })
        if (!cancelInto(batch, clock)) return@withLock ClockResult.NotFound
        ClockResult.Done(commit(batch))
    }

    /**
     * Close [clock] at [at] into [batch], then switch its state to
     * `clockOutState` when set (`org-clock-out-switch-to-state`), with the same
     * done/repeat/auto-archive semantics as any other state change, and with
     * clock-out-on-done off, as Emacs binds it there. False when the clock is gone.
     */
    private suspend fun closeInto(batch: Batch, settings: GroveSettings, clock: RunningClock, at: LocalDateTime): Boolean {
        val (doc, h) = batch.locate(clock) ?: return false
        val closed = OrgMutations.clockOut(doc, h, at, settings.discardZeroClocks, start = clock.start) ?: return false
        batch.put(clock.fileName, closed)
        val switchTo = settings.clockOutState?.takeIf { it in doc.keywords.all } ?: return true
        val redoc = batch.doc(clock.fileName)!!
        val again = redoc.headlines.firstOrNull { it.lineIndex == h.lineIndex } ?: return true
        if (again.keyword == switchTo) return true
        when (
            val result = AutoArchive.apply(
                batch.vault, settings.copy(clockOutWhenDone = false), redoc, clock.fileName, again, switchTo, at,
            )
        ) {
            is StateChangeResult.Plain -> batch.put(clock.fileName, result.text)
            is StateChangeResult.Archived -> {
                batch.put(result.sourceFile, result.sourceText)
                if (result.destFile != result.sourceFile) {
                    batch.put(result.destFile, result.destText, before = result.destTextBefore)
                }
            }
        }
        return true
    }

    private suspend fun cancelInto(batch: Batch, clock: RunningClock): Boolean {
        val (doc, h) = batch.locate(clock) ?: return false
        batch.put(clock.fileName, OrgMutations.cancelClock(doc, h, start = clock.start) ?: return false)
        return true
    }

    private suspend fun commit(batch: Batch): List<FileWrite> {
        val writes = batch.writes()
        for (w in writes) {
            batch.vault.save(w.fileName, w.after, w.doc)
            reindex(w.fileName, w.after)
        }
        return writes
    }

    // A cold start from a notification action can beat the settings read that
    // produces the vault, as the widget's actions found; wait briefly for it.
    private suspend fun awaitVault(): Vault? = vault.value ?: withTimeoutOrNull(5_000) { vault.filterNotNull().first() }

    /**
     * The working copies of the files one action touches, so several edits to
     * one file chain on each other, plus each file's text before the first one.
     */
    private class Batch(val vault: Vault, private val base: Pair<String, OrgDocument>?) {
        val docs = LinkedHashMap<String, OrgDocument>()
        private val before = HashMap<String, String>()

        suspend fun doc(fileName: String): OrgDocument? = docs[fileName]
            ?: (base?.takeIf { it.first == fileName }?.second ?: vault.open(fileName))?.also {
                docs[fileName] = it
                before[fileName] = it.text
            }

        fun put(fileName: String, text: String, before: String? = null) {
            val keywords = docs[fileName]?.keywords ?: docs.values.first().keywords
            if (before != null) this.before.putIfAbsent(fileName, before)
            docs[fileName] = OrgParser.parse(text, keywords)
        }

        /**
         * [clock]'s heading in its file now: the one at its indexed line when an
         * open clock with its start is still there, else whichever heading holds one.
         */
        suspend fun locate(clock: RunningClock): Pair<OrgDocument, OrgHeadline>? {
            val doc = doc(clock.fileName) ?: return null
            fun holds(h: OrgHeadline) = doc.clocks(h).any { it.isRunning && it.start == clock.start }
            val h = doc.headlines.firstOrNull { it.lineIndex == clock.lineIndex }?.takeIf(::holds)
                ?: doc.headlines.firstOrNull(::holds)
                ?: return null
            return doc to h
        }

        fun writes(): List<FileWrite> = docs.mapNotNull { (name, doc) ->
            before[name]?.takeIf { it != doc.text }?.let { FileWrite(name, it, doc.text, doc) }
        }
    }

    companion object {
        /**
         * An open clock this old when another clock-in would close it is likely
         * dangling (forgotten, or left by a crashed Emacs), so it is resolved by
         * hand instead of logging the whole span.
         */
        val STALE_AFTER: Duration = Duration.ofHours(12)
    }
}

/** The answer for [clock], matched by file and start: its indexed line may have moved since. */
private fun Map<RunningClock, ClockFix>.fixFor(clock: RunningClock): ClockFix? =
    entries.firstOrNull { it.key.fileName == clock.fileName && it.key.start == clock.start }?.value

/** An index row as a [RunningClock]; null for a start the index holds but can't be read back. */
internal fun RunningClockRow.toRunningClock(): RunningClock? {
    val start = runCatching { LocalDateTime.parse(clockStart) }.getOrNull() ?: return null
    return RunningClock(fileName, lineIndex, title, keyword, start)
}

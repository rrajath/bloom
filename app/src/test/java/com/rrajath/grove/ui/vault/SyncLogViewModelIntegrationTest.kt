package com.rrajath.grove.ui.vault

import android.app.Application
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.SyncLogEntity
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.support.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layer-1 integration coverage for [SyncLogViewModel]: the sync-log rows in Room
 * stream into the exposed flow newest-first, `total` tracks the full count, and
 * `loadMore` grows the page past the first [SyncLogViewModel.PAGE_SIZE].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SyncLogViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)

    private suspend fun log(count: Int, level: String = "INFO") {
        repeat(count) { i ->
            db.syncLogDao().insert(
                SyncLogEntity(timestamp = 1_000L + i, level = level, message = "entry $i"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `entries stream from Room newest-first and total counts them all`() = runTest {
        log(3)

        val vm = SyncLogViewModel(db)
        // entries/total are SharingStarted.WhileSubscribed — collect to make live.
        backgroundScope.launch { vm.entries.collect {} }
        backgroundScope.launch { vm.total.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("entry 2", "entry 1", "entry 0"), vm.entries.value.map { it.message })
        assertEquals(3, vm.total.value)
    }

    @Test
    fun `the first page is capped at PAGE_SIZE and loadMore reveals the rest`() = runTest {
        val overflow = SyncLogViewModel.PAGE_SIZE + 1
        log(overflow)

        val vm = SyncLogViewModel(db)
        backgroundScope.launch { vm.entries.collect {} }
        backgroundScope.launch { vm.total.collect {} }
        advanceUntilIdle()

        assertEquals(SyncLogViewModel.PAGE_SIZE, vm.entries.value.size)
        assertEquals(overflow, vm.total.value)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(overflow, vm.entries.value.size)
    }
}

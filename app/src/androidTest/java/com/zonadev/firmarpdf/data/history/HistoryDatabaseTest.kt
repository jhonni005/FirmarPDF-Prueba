package com.zonadev.firmarpdf.data.history

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDatabaseTest {
    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            HistoryDatabase::class.java,
        ).build()
        repository = HistoryRepository(database.signedPdfDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun newDatabaseHasNoSampleResults() = runBlocking {
        assertTrue(withTimeout(5_000) { repository.results.first() }.isEmpty())
    }

    @Test
    fun resultsAreObservableNewestFirstAndRoundTripKinds() = runBlocking {
        repository.recordNewResult(result("older", 100))
        repository.recordNewResult(result("newer", 300, ResultKind.UPDATED_SOURCE))
        repository.recordNewResult(result("middle", 200))

        val results = withTimeout(5_000) { repository.results.first { it.size == 3 } }
        assertEquals(listOf("newer.pdf", "middle.pdf", "older.pdf"), results.map { it.displayName })
        assertEquals(ResultKind.UPDATED_SOURCE, results.first().resultKind)
        assertEquals(ResultKind.NEW_DOCUMENT, results.last().resultKind)
        assertTrue(results.all { it.id > 0 })
    }

    @Test
    fun repeatedUriCannotReplaceAnExistingResult() = runBlocking {
        repository.recordNewResult(result("same", 100))
        try {
            repository.recordNewResult(result("same", 200))
            fail("A duplicate URI must be rejected")
        } catch (_: SQLiteConstraintException) {
            // El registro válido original debe conservarse.
        }
        val results = withTimeout(5_000) { repository.results.first() }
        assertEquals(1, results.size)
        assertEquals(100L, results.single().signedAt)
    }

    private fun result(name: String, time: Long, kind: ResultKind = ResultKind.NEW_DOCUMENT) =
        SignedPdfEntity(
            resultUri = "content://test.documents/$name",
            displayName = "$name.pdf",
            signedAt = time,
            resultKind = kind,
        )
}

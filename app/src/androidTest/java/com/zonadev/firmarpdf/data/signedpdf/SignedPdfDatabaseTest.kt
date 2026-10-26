package com.zonadev.firmarpdf.data.signedpdf

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
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
import java.util.UUID
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfEntity

@RunWith(AndroidJUnit4::class)
class SignedPdfDatabaseTest {
    private lateinit var database: SignedPdfDatabase
    private lateinit var repository: SignedPdfRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SignedPdfDatabase::class.java,
        ).build()
        repository = SignedPdfRepository(database.signedPdfDao())
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

    @Test
    fun migrationOneToTwoKeepsSignedRowAndSeparatesRecentPdfs() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "signed_pdf_rename_test_${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        try {
            // SQL y hash del esquema v1 exportado antes del cambio de nombres.
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE signed_pdfs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, resultUri TEXT NOT NULL, displayName TEXT NOT NULL, signedAt INTEGER NOT NULL, resultKind TEXT NOT NULL)")
                old.execSQL("CREATE UNIQUE INDEX index_signed_pdfs_resultUri ON signed_pdfs (resultUri)")
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES (42, '360fc0bb3119eb7704b952858936cada')")
                old.execSQL("INSERT INTO signed_pdfs VALUES (7, 'content://test.documents/existing', 'existing.pdf', 100, 'NEW_DOCUMENT')")
                old.version = 1
            }
            val reopened = Room.databaseBuilder(context, SignedPdfDatabase::class.java, databaseName)
                .addMigrations(SignedPdfDatabase.MIGRATION_1_2).build()
            try {
                val existing = withTimeout(5_000) { reopened.signedPdfDao().observeResults().first() }.single()
                assertEquals(7L, existing.id)
                assertEquals("existing.pdf", existing.displayName)
                assertEquals("content://test.documents/existing", existing.resultUri)
                assertEquals(100L, existing.signedAt)
                assertEquals(ResultKind.NEW_DOCUMENT, existing.resultKind)
                assertEquals(2, reopened.openHelper.readableDatabase.version)
                assertTrue(reopened.recentPdfDao().observeRecents().first().isEmpty())
                val recent = RecentPdfEntity("content://test.documents/recent", "recent.pdf", 55L, 200L)
                reopened.recentPdfDao().upsert(recent)
                reopened.recentPdfDao().upsert(recent.copy(lastOpenedAt = 300L))
                assertEquals(300L, reopened.recentPdfDao().observeRecents().first().single().lastOpenedAt)
                assertEquals(existing, reopened.signedPdfDao().observeResults().first().single())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun result(name: String, time: Long, kind: ResultKind = ResultKind.NEW_DOCUMENT) =
        SignedPdfEntity(
            resultUri = "content://test.documents/$name",
            displayName = "$name.pdf",
            signedAt = time,
            resultKind = kind,
        )
}

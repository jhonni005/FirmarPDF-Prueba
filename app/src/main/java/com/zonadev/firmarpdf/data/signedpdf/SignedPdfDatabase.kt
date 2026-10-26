package com.zonadev.firmarpdf.data.signedpdf

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfEntity
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfDao

@Database(entities = [SignedPdfEntity::class, RecentPdfEntity::class], version = 2, exportSchema = true)
abstract class SignedPdfDatabase : RoomDatabase() {
    abstract fun signedPdfDao(): SignedPdfDao
    abstract fun recentPdfDao(): RecentPdfDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recent_pdfs (uri TEXT NOT NULL, displayName TEXT NOT NULL, documentModifiedAt INTEGER, lastOpenedAt INTEGER NOT NULL, unavailable INTEGER NOT NULL, PRIMARY KEY(uri))")
            }
        }
    }
}

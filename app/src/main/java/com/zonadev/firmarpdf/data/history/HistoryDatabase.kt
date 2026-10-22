package com.zonadev.firmarpdf.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SignedPdfEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun signedPdfDao(): SignedPdfDao
}

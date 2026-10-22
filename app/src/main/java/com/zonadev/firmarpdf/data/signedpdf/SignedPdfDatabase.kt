package com.zonadev.firmarpdf.data.signedpdf

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SignedPdfEntity::class], version = 1, exportSchema = true)
abstract class SignedPdfDatabase : RoomDatabase() {
    abstract fun signedPdfDao(): SignedPdfDao
}

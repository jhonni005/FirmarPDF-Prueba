package com.zonadev.firmarpdf.data.signedpdf

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ResultKind { NEW_DOCUMENT, UPDATED_SOURCE }

@Entity(tableName = "signed_pdfs", indices = [Index(value = ["resultUri"], unique = true)])
data class SignedPdfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val resultUri: String,
    val displayName: String,
    /** Fecha Unix en milisegundos. */
    val signedAt: Long,
    val resultKind: ResultKind,
)

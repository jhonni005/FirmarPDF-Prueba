package com.zonadev.firmarpdf.data.recentpdf

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_pdfs")
data class RecentPdfEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val documentModifiedAt: Long?,
    val lastOpenedAt: Long,
    val unavailable: Boolean = false,
)

package com.zonadev.firmarpdf.data.history

class HistoryRepository(private val dao: SignedPdfDao) {
    val results = dao.observeResults()

    suspend fun recordNewResult(result: SignedPdfEntity): Long = dao.insert(result)
}

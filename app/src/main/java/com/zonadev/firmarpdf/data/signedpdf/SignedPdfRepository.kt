package com.zonadev.firmarpdf.data.signedpdf

class SignedPdfRepository(private val dao: SignedPdfDao) {
    val results = dao.observeResults()

    suspend fun recordNewResult(result: SignedPdfEntity): Long = dao.insert(result)
}

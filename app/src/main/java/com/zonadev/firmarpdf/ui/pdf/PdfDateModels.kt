package com.zonadev.firmarpdf.ui.pdf

import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PdfDateFormat(private val pattern: String) {
    DAY_MONTH_YEAR("dd/MM/uuuu"), DASHED("dd-MM-uuuu"), LONG("d 'de' MMMM 'de' uuuu"), ISO("uuuu-MM-dd");

    fun format(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("es")))
}

/** Valores capturados al elegir: una futura exportación deberá usar text, nunca LocalDate.now(). */
data class PdfDateAnnotation(
    val date: LocalDate,
    val format: PdfDateFormat,
    val text: String,
    val placement: PdfSignaturePlacement,
)

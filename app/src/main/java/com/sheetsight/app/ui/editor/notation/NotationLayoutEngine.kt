package com.sheetsight.app.ui.editor.notation

/** Groups parsed measures into bounded, lazily rendered systems. */
object NotationLayoutEngine {
    // Preserve the source score's system breaks. Eight is only a safety cap for
    // MusicXML that contains no print/new-system layout hints; the old cap of
    // four made ordinary piano systems much more sparse than printed music.
    private const val MAX_MEASURES_PER_SYSTEM = 8

    internal fun layout(parsed: ParsedNotationScore): NotationDocument {
        val grouped = mutableListOf<List<NotationMeasure>>()
        var current = mutableListOf<NotationMeasure>()
        parsed.measures.forEach { measure ->
            if (current.isNotEmpty() && (measure.startsNewSystem || measure.startsNewPage || current.size >= MAX_MEASURES_PER_SYSTEM)) {
                grouped += current.toList()
                current = mutableListOf()
            }
            current += measure
        }
        if (current.isNotEmpty()) grouped += current.toList()

        return NotationDocument(
            systems = grouped.mapIndexed { index, measures ->
                NotationSystem(
                    index = index,
                    measures = measures,
                    staffCount = measures.maxOf { it.staffs.size },
                    startsNewPage = measures.first().startsNewPage
                )
            },
            statistics = parsed.statistics,
            unsupportedElements = parsed.unsupportedElements,
            detectedTempoBpm = parsed.detectedTempoBpm
        )
    }
}

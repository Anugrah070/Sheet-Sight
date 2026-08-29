package com.sheetsight.app.data.practice

import com.sheetsight.app.data.omr.musicxml.MusicXmlNotationParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlTextDecoder
import com.sheetsight.app.domain.practice.PracticeSequence
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationLayoutEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Reuses the Editor's hardened MusicXML parser and notation conversion. */
@Singleton
class PracticeMusicXmlLoader @Inject constructor() {
    fun load(fileName: String, bytes: ByteArray): LoadedPracticeScore {
        val parsed = MusicXmlNotationParser.parse(MusicXmlParser.parseBytes(bytes))
        val durationSemanticsReliable = parsed.unsupportedElements.keys.none { element ->
            element == "note/tie" || element == "note/notations"
        }
        return LoadedPracticeScore(
            sequence = PracticeSequenceFactory.create(
                fileName = fileName,
                measures = parsed.measures,
                detectedTempoBpm = parsed.detectedTempoBpm,
                durationSemanticsReliable = durationSemanticsReliable
            ),
            notation = NotationLayoutEngine.layout(parsed),
            musicXml = MusicXmlTextDecoder.decode(bytes)
        )
    }
}

data class LoadedPracticeScore(
    val sequence: PracticeSequence,
    val notation: NotationDocument,
    val musicXml: String
)

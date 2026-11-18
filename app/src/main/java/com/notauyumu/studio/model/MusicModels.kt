package com.notauyumu.studio.model

enum class ReferenceVoiceFilter(
    val label: String,
    val minFreq: Float,
    val maxFreq: Float
) {
    SCORE_GUIDED("PDF Rehberli Ayır", 65f, 1400f),
    ALL("Tümü (Solo)", 65f, 1400f),
    BASS("Bas Ayır (75-250 Hz)", 75f, 250f),
    TENOR("Tenor Ayır (120-480 Hz)", 120f, 480f),
    ALTO("Alto Ayır (175-700 Hz)", 175f, 700f),
    SOPRANO("Soprano Ayır (260-1200 Hz)", 260f, 1200f)
}

enum class ClefType(val label: String) {
    TREBLE("Sol Anahtarı"),
    BASS("Fa Anahtarı"),
    ALTO("Do (Alto) Anahtarı"),
    TENOR("Do (Tenor) Anahtarı")
}

enum class ToleranceLevel(val label: String, val cents: Int) {
    EASY("Kolay (±50c)", 50),
    NORMAL("Normal (±30c)", 30),
    PRO("Profesyonel (±15c)", 15)
}

enum class PitchStatus {
    HIT, NEAR, MISS, SILENT, REST
}

data class NoteEvent(
    val measure: Int,
    val note: String,
    val midi: Int?,
    val durationBeats: Float,
    val startBeat: Float = 0f,
    val isRest: Boolean = false
)

data class PartData(
    val id: String,
    val name: String,
    val clef: ClefType,
    val notes: List<NoteEvent>
)

data class ScoreData(
    val title: String,
    val parts: List<PartData>,
    val bpm: Int = 80,
    val beatsPerMeasure: Int = 4
)

data class ReferencePitchPoint(
    val timeSec: Float,
    val frequency: Float?,
    val midiFloat: Float?
)

data class PitchFrame(
    val beat: Float,
    val timeSec: Float,
    val frequency: Float?,
    val midiFloat: Float?,
    val rms: Float,
    val targetMidi: Int?,
    val referenceMidi: Float?,
    val centsOff: Float?,
    val status: PitchStatus
)

data class NoteAccuracyReport(
    val index: Int,
    val measure: Int,
    val note: String,
    val midi: Int,
    val durationBeats: Float,
    val accuracyPercent: Int,
    val avgCentsOff: Int?,
    val meanAbsCents: Int?
)

data class PerformanceSummary(
    val partId: String,
    val partName: String,
    val overallAccuracy: Int,
    val referenceMatchPercent: Int?,
    val avgCentsOff: Int,
    val meanAbsCentsError: Int,
    val maxCentsError: Int,
    val wrongNoteRatePercent: Int,
    val silentRatePercent: Int,
    val weakMeasures: List<Int>,
    val tendencyLabel: String,
    val noteBreakdown: List<NoteAccuracyReport>,
    val totalTargetNotes: Int,
    val hitNotesCount: Int
)

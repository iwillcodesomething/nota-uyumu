package com.notauyumu.studio.model

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
    val parts: List<PartData>
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
    val avgCentsOff: Int?
)

data class PerformanceSummary(
    val partId: String,
    val partName: String,
    val overallAccuracy: Int,
    val referenceMatchPercent: Int?,
    val avgCentsOff: Int,
    val tendencyLabel: String,
    val noteBreakdown: List<NoteAccuracyReport>,
    val totalTargetNotes: Int,
    val hitNotesCount: Int
)

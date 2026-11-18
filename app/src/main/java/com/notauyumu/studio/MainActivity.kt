package com.notauyumu.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.notauyumu.studio.audio.AudioEngine
import com.notauyumu.studio.audio.ReferenceAudioAnalyzer
import com.notauyumu.studio.audio.ReferenceSynth
import com.notauyumu.studio.model.ClefType
import com.notauyumu.studio.model.PerformanceSummary
import com.notauyumu.studio.model.PitchFrame
import com.notauyumu.studio.model.PitchStatus
import com.notauyumu.studio.model.ReferencePitchPoint
import com.notauyumu.studio.model.ToleranceLevel
import com.notauyumu.studio.score.PdfOcrEngine
import com.notauyumu.studio.ui.PitchCanvas
import com.notauyumu.studio.ui.ScoreSummaryModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF020617),
                    surface = Color(0xFF0F172A),
                    primary = Color(0xFF34D399)
                )
            ) {
                NotaUyumuStudioApp()
            }
        }
    }
}

@Composable
fun NotaUyumuStudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var score by remember { mutableStateOf(PdfOcrEngine.DEFAULT_SATB_SCORE) }
    var selectedPartId by remember { mutableStateOf(score.parts.first().id) }
    var weakMeasuresFilter by remember { mutableStateOf<List<Int>?>(null) }
    var pdfBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var currentPage by remember { mutableIntStateOf(0) }

    // Referans Ses Kaydı Durumu
    var referenceUri by remember { mutableStateOf<Uri?>(null) }
    var referenceName by remember { mutableStateOf<String?>(null) }
    var referenceContour by remember { mutableStateOf<List<ReferencePitchPoint>>(emptyList()) }

    // Hazırlık & Eşzamanlı Kayıt Ayarları
    var bpm by remember { mutableIntStateOf(80) }
    var transposeOctaves by remember { mutableIntStateOf(0) }
    var tolerance by remember { mutableStateOf(ToleranceLevel.NORMAL) }
    var customToleranceCents by remember { mutableIntStateOf(30) }
    var passingAccuracyPercent by remember { mutableIntStateOf(60) }
    var rmsThreshold by remember { mutableFloatStateOf(0.015f) }
    var playGuideDuringRecord by remember { mutableStateOf(false) }
    var playMetronomeDuringRecord by remember { mutableStateOf(false) }
    var playFirstNoteOnStart by remember { mutableStateOf(true) }
    var forceBuiltInMic by remember { mutableStateOf(true) }
    var playOtherPartsAccompaniment by remember { mutableStateOf(false) }
    var isFreeTunerMode by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var countInBeat by remember { mutableStateOf<Int?>(null) }
    var currentBeat by remember { mutableFloatStateOf(0f) }

    val pitchHistory = remember { mutableStateListOf<PitchFrame>() }
    var liveTargetMidi by remember { mutableStateOf<Int?>(null) }
    var liveDetectedNote by remember { mutableStateOf<String?>(null) }
    var liveFrequency by remember { mutableStateOf<Float?>(null) }
    var liveCentsOff by remember { mutableStateOf<Float?>(null) }
    var liveStatus by remember { mutableStateOf(PitchStatus.SILENT) }

    var summary by remember { mutableStateOf<PerformanceSummary?>(null) }
    var clefMenuExpanded by remember { mutableStateOf(false) }
    var tolMenuExpanded by remember { mutableStateOf(false) }

    val audioEngine = remember { AudioEngine() }
    val synth = remember { ReferenceSynth() }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var timerJob by remember { mutableStateOf<Job?>(null) }

    fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    DisposableEffect(Unit) {
        onDispose {
            audioEngine.stop()
            synth.stopAll()
            releaseMediaPlayer()
            timerJob?.cancel()
        }
    }

    val baseSelectedPart = remember(score, selectedPartId) {
        score.parts.find { it.id == selectedPartId } ?: score.parts.first()
    }
    val selectedPart = remember(baseSelectedPart, weakMeasuresFilter) {
        val filter = weakMeasuresFilter
        if (filter.isNullOrEmpty()) baseSelectedPart
        else {
            var cursor = 0f
            val filteredNotes = baseSelectedPart.notes.filter { it.measure in filter }.map { n ->
                val item = n.copy(startBeat = cursor)
                cursor += n.durationBeats
                item
            }
            baseSelectedPart.copy(name = "${baseSelectedPart.name} (Hatalı Ölçüler)", notes = filteredNotes)
        }
    }

    fun stopAll(showReport: Boolean) {
        audioEngine.stop()
        synth.stopAll()
        releaseMediaPlayer()
        timerJob?.cancel()
        timerJob = null
        isRecording = false
        isPreviewPlaying = false
        isFreeTunerMode = false
        countInBeat = null
        if (showReport && pitchHistory.isNotEmpty()) {
            summary = PdfOcrEngine.computePerformanceSummary(
                selectedPart,
                pitchHistory.toList(),
                customToleranceCents,
                transposeOctaves,
                passingAccuracyPercent
            )
        }
    }

    fun toggleFreeTuner() {
        if (isFreeTunerMode) {
            stopAll(false)
            isFreeTunerMode = false
            return
        }
        stopAll(false)
        isFreeTunerMode = true
        pitchHistory.clear()
        currentBeat = 0f
        audioEngine.rmsThreshold = rmsThreshold
        val tunerStartMs = System.currentTimeMillis()
        try {
            audioEngine.start(context = context, forceBuiltInMic = forceBuiltInMic) { sample ->
                val tSec = (System.currentTimeMillis() - tunerStartMs) / 1000f
                val beat = tSec * (bpm / 60f)
                val nearestMidi = sample.midiFloat?.let { kotlin.math.round(it).toInt() }
                val cents = if (sample.midiFloat != null && nearestMidi != null) (sample.midiFloat - nearestMidi) * 100f else null
                val st = when {
                    cents == null -> PitchStatus.SILENT
                    abs(cents) <= customToleranceCents -> PitchStatus.HIT
                    abs(cents) <= maxOf(60, customToleranceCents * 2) -> PitchStatus.NEAR
                    else -> PitchStatus.MISS
                }
                val frame = PitchFrame(beat, tSec, sample.frequency, sample.midiFloat, sample.rms, nearestMidi, null, cents, st)
                scope.launch(Dispatchers.Main) {
                    currentBeat = beat
                    pitchHistory.add(frame)
                    liveTargetMidi = nearestMidi
                    liveDetectedNote = sample.detectedNote
                    liveFrequency = sample.frequency
                    liveCentsOff = cents
                    liveStatus = st
                }
            }
        } catch (e: Exception) {
            isFreeTunerMode = false
            statusMsg = "Mikrofon başlatılamadı: ${e.message}"
        }
    }

    fun startRecordingFlow() {
        if (isRecording || countInBeat != null) {
            stopAll(true)
            return
        }
        statusMsg = null
        summary = null
        pitchHistory.clear()
        currentBeat = -1f
        var recordStartMs = 0L

        audioEngine.rmsThreshold = rmsThreshold
        try {
            audioEngine.start(context = context, forceBuiltInMic = forceBuiltInMic) { sample ->
                if (currentBeat < 0f || recordStartMs == 0L) return@start
                val beatNow = currentBeat
                val timeSecNow = (System.currentTimeMillis() - recordStartMs) / 1000f
                val activeNote = selectedPart.notes.find { beatNow >= it.startBeat && beatNow < it.startBeat + it.durationBeats }
                val tMidi = if (activeNote != null && !activeNote.isRest && activeNote.midi != null) {
                    activeNote.midi + transposeOctaves * 12
                } else null

                val refMidi = if (referenceContour.isNotEmpty()) {
                    ReferenceAudioAnalyzer.getReferenceMidiAtTime(referenceContour, timeSecNow)
                } else null

                val effectiveTarget = refMidi ?: tMidi?.toFloat()
                var cents: Float? = null
                val st = when {
                    activeNote?.isRest == true && refMidi == null -> PitchStatus.REST
                    sample.midiFloat != null && effectiveTarget != null -> {
                        cents = (sample.midiFloat - effectiveTarget) * 100f
                        val absC = abs(cents)
                        when {
                            absC <= customToleranceCents -> PitchStatus.HIT
                            absC <= maxOf(60, customToleranceCents * 2) -> PitchStatus.NEAR
                            else -> PitchStatus.MISS
                        }
                    }
                    sample.midiFloat != null -> PitchStatus.MISS
                    else -> PitchStatus.SILENT
                }

                val frame = PitchFrame(
                    beat = beatNow,
                    timeSec = timeSecNow,
                    frequency = sample.frequency,
                    midiFloat = sample.midiFloat,
                    rms = sample.rms,
                    targetMidi = tMidi,
                    referenceMidi = refMidi,
                    centsOff = cents,
                    status = st
                )
                scope.launch(Dispatchers.Main) {
                    pitchHistory.add(frame)
                    liveTargetMidi = if (referenceContour.isNotEmpty()) refMidi?.toInt() else tMidi
                    liveDetectedNote = sample.detectedNote
                    liveFrequency = sample.frequency
                    liveCentsOff = cents
                    liveStatus = st
                }
            }
        } catch (e: Exception) {
            statusMsg = "Mikrofon başlatılamadı: ${e.message}"
            return
        }

        val msPerBeat = (60_000L / bpm)
        val partTotalBeats = selectedPart.notes.lastOrNull()?.let { it.startBeat + it.durationBeats } ?: 0f
        val refTotalBeats = referenceContour.lastOrNull()?.let { it.timeSec * (bpm / 60f) } ?: 0f
        val totalBeats = maxOf(partTotalBeats, refTotalBeats)

        timerJob = scope.launch {
            val countBeats = score.beatsPerMeasure.coerceIn(2, 6)
            if (playFirstNoteOnStart && referenceUri == null) {
                synth.playFirstNote(selectedPart.notes, transposeOctaves)
                if (!forceBuiltInMic) audioEngine.suppressFor(1350L)
            }
            for (c in countBeats downTo 1) {
                countInBeat = c
                if (!forceBuiltInMic) audioEngine.suppressFor(180L)
                synth.playMetronomeClick(c == countBeats)
                delay(msPerBeat)
            }
            countInBeat = null
            isRecording = true
            recordStartMs = System.currentTimeMillis()

            // Gösterirken eşzamanlı rehber ses (referans kayıt veya sentez) çalma
            if (playOtherPartsAccompaniment && score.parts.size > 1) {
                val others = score.parts.filter { it.id != selectedPart.id }
                synth.startOtherPartsAccompaniment(others, bpm, transposeOctaves)
            }
            if (playGuideDuringRecord) {
                if (referenceUri != null) {
                    try {
                        releaseMediaPlayer()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(context, referenceUri!!)
                            setVolume(0.45f, 0.45f)
                            prepare()
                            start()
                        }
                    } catch (_: Exception) {
                    }
                } else {
                    synth.startGuideDuringRecording(selectedPart.notes, bpm, transposeOctaves)
                }
            }

            var lastMetronomeBeat = -1
            while (isActive) {
                val beat = ((System.currentTimeMillis() - recordStartMs) / 1000f) * (bpm / 60f)
                currentBeat = beat
                val intBeat = beat.toInt()
                if (playMetronomeDuringRecord && intBeat > lastMetronomeBeat) {
                    lastMetronomeBeat = intBeat
                    if (!forceBuiltInMic) audioEngine.suppressFor(160L)
                    synth.playMetronomeClick(intBeat % score.beatsPerMeasure == 0)
                }
                if (beat >= totalBeats) {
                    stopAll(true)
                    break
                }
                delay(16L)
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecordingFlow()
        else statusMsg = "Gerçek zamanlı perde analizi için mikrofon izni gereklidir."
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            statusMsg = "Nota dosyası yerel OMR motoruyla analiz ediliyor..."
            try {
                val name = resolveFileName(context, uri)
                if (name.lowercase().endsWith(".pdf")) {
                    val res = withContext(Dispatchers.Default) {
                        PdfOcrEngine.parsePdfFromUri(context, uri, name)
                    }
                    pdfBitmaps = res.pageBitmaps
                    currentPage = 0
                    score = res.score
                    selectedPartId = res.score.parts.first().id
                    bpm = res.score.bpm
                    statusMsg = "${res.score.parts.size} parti yerel OMR ile ayrıştırıldı."
                } else {
                    val parsed = withContext(Dispatchers.Default) {
                        PdfOcrEngine.parseMusicXmlFromUri(context, uri, name)
                    }
                    pdfBitmaps = emptyList()
                    score = parsed
                    selectedPartId = parsed.parts.first().id
                    bpm = parsed.bpm
                    statusMsg = "${parsed.parts.size} parti MusicXML dosyasından yüklendi."
                }
                currentBeat = 0f
                pitchHistory.clear()
            } catch (e: Exception) {
                statusMsg = "Hata: ${e.message}"
            }
        }
    }

    val wavExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            audioEngine.exportWavToUri(context, uri)
            statusMsg = "Ses kaydı WAV olarak başarıyla dışa aktarıldı."
        } catch (e: Exception) {
            statusMsg = "Dışa aktarma hatası: ${e.message}"
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            statusMsg = "Referans ses kaydı YIN algoritmasıyla analiz ediliyor..."
            try {
                val name = resolveFileName(context, uri)
                val contour = withContext(Dispatchers.Default) {
                    ReferenceAudioAnalyzer.analyzeReferenceAudio(context, uri, rmsThreshold)
                }
                referenceUri = uri
                referenceName = name
                referenceContour = contour
                statusMsg = "Referans kayıt ($name) analiz edildi (${contour.count { it.midiFloat != null }} perde noktası)."
            } catch (e: Exception) {
                statusMsg = "Ses dosyası analiz hatası: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Üst Başlık ve Dosya Yükleme
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("NotaUyumu Studio", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    referenceName?.let { "${score.title} • Ref: $it" } ?: score.title,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) }
                ) {
                    Text(if (referenceUri != null) "Ref Değiştir" else "Ref Ses Yükle", fontSize = 11.sp)
                }
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf", "text/xml", "application/xml", "*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color(0xFF020617))
                ) {
                    Text("PDF / XML", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        statusMsg?.let { msg ->
            Text(
                msg,
                color = Color(0xFF7DD3FC),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x1F38BDF8))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // Parti Seçimi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            score.parts.forEach { part ->
                val selected = part.id == selectedPart.id
                FilterChip(
                    selected = selected,
                    onClick = {
                        stopAll(false)
                        selectedPartId = part.id
                        weakMeasuresFilter = null
                        currentBeat = 0f
                        pitchHistory.clear()
                    },
                    label = { Text(part.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF10B981),
                        selectedLabelColor = Color(0xFF020617)
                    )
                )
            }
        }

        // Hazırlık & Eşzamanlı Kayıt Kontrolleri
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = {
                stopAll(false)
                synth.playFirstNote(selectedPart.notes, transposeOctaves)
            }) { Text("İlk Ses", fontSize = 11.sp) }

            OutlinedButton(onClick = {
                if (isPreviewPlaying) stopAll(false)
                else {
                    stopAll(false)
                    isPreviewPlaying = true
                    synth.playPart(
                        selectedPart.notes, bpm, transposeOctaves,
                        onBeatProgress = { b -> scope.launch(Dispatchers.Main) { currentBeat = b } },
                        onComplete = { scope.launch(Dispatchers.Main) { isPreviewPlaying = false; currentBeat = 0f } }
                    )
                }
            }) { Text(if (isPreviewPlaying) "Durdur" else "Partiyi Dinle", fontSize = 11.sp) }

            FilterChip(
                selected = playFirstNoteOnStart,
                onClick = { playFirstNoteOnStart = !playFirstNoteOnStart },
                label = { Text(if (playFirstNoteOnStart) "İlk Ses: Açık" else "İlk Ses: Kapalı", fontSize = 11.sp) }
            )

            FilterChip(
                selected = playGuideDuringRecord,
                onClick = { playGuideDuringRecord = !playGuideDuringRecord },
                label = { Text(if (playGuideDuringRecord) "Sesi Çal: Açık" else "Sesi Çal: Kapalı", fontSize = 11.sp) }
            )

            FilterChip(
                selected = forceBuiltInMic,
                onClick = { forceBuiltInMic = !forceBuiltInMic },
                label = { Text(if (forceBuiltInMic) "Mik: Ana (Kulaklık)" else "Mik: Hoparlör AEC", fontSize = 11.sp) }
            )

            if (!weakMeasuresFilter.isNullOrEmpty()) {
                FilterChip(
                    selected = true,
                    onClick = { weakMeasuresFilter = null; currentBeat = 0f; pitchHistory.clear() },
                    label = { Text("Tüm Parçaya Dön", fontSize = 11.sp) }
                )
            }

            FilterChip(
                selected = isFreeTunerMode,
                onClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) toggleFreeTuner()
                    else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                label = { Text(if (isFreeTunerMode) "Isınma: Açık" else "Serbest Isınma", fontSize = 11.sp) }
            )

            if (score.parts.size > 1) {
                FilterChip(
                    selected = playOtherPartsAccompaniment,
                    onClick = { playOtherPartsAccompaniment = !playOtherPartsAccompaniment },
                    label = { Text(if (playOtherPartsAccompaniment) "Koro Eşliği: Açık" else "Koro Eşliği", fontSize = 11.sp) }
                )
            }

            FilterChip(
                selected = playMetronomeDuringRecord,
                onClick = { playMetronomeDuringRecord = !playMetronomeDuringRecord },
                label = { Text("Metronom", fontSize = 11.sp) }
            )

            Box {
                OutlinedButton(onClick = { tolMenuExpanded = true }) {
                    Text(tolerance.label, fontSize = 11.sp)
                }
                DropdownMenu(expanded = tolMenuExpanded, onDismissRequest = { tolMenuExpanded = false }) {
                    ToleranceLevel.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(t.label) }, onClick = { tolerance = t; customToleranceCents = t.cents; tolMenuExpanded = false })
                    }
                }
            }

            listOf(-1 to "-1 Okt", 0 to "Orijinal", 1 to "+1 Okt").forEach { (oct, lbl) ->
                FilterChip(
                    selected = transposeOctaves == oct,
                    onClick = { if (!isRecording) transposeOctaves = oct },
                    label = { Text(lbl, fontSize = 11.sp) }
                )
            }

            Button(
                onClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (hasPerm) startRecordingFlow()
                    else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording || countInBeat != null) Color(0xFFEF4444) else Color(0xFF34D399),
                    contentColor = if (isRecording || countInBeat != null) Color.White else Color(0xFF020617)
                )
            ) {
                Text(
                    if (isRecording || countInBeat != null) "Durdur ve Puanla" else "Kaydı Başlat",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Doğruluk Toleransı & Başarı Oranı Belirleme
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("±${customToleranceCents}c", color = Color(0xFF7DD3FC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = customToleranceCents.toFloat(),
                onValueChange = { if (!isRecording) customToleranceCents = it.toInt() },
                valueRange = 10f..100f,
                modifier = Modifier.weight(1f)
            )
            Text("Hedef %$passingAccuracyPercent", color = Color(0xFF34D399), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = passingAccuracyPercent.toFloat(),
                onValueChange = { if (!isRecording) passingAccuracyPercent = it.toInt() },
                valueRange = 40f..95f,
                modifier = Modifier.weight(1f)
            )
        }

        // Tempo & Noise Gate Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$bpm BPM", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = bpm.toFloat(),
                onValueChange = { if (!isRecording) bpm = it.toInt() },
                valueRange = 40f..200f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color(0xFF34D399), activeTrackColor = Color(0xFF34D399))
            )
            Text("Eşik", color = Color(0xFF94A3B8), fontSize = 11.sp)
            Slider(
                value = rmsThreshold,
                onValueChange = { rmsThreshold = it; audioEngine.rmsThreshold = it },
                valueRange = 0.005f..0.06f,
                modifier = Modifier.weight(0.6f)
            )
        }

        // Üst Bölge: PDF Sayfası / Nota Özeti
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Aktif Parti: ${selectedPart.name}",
                    color = Color(0xFF34D399),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pdfBitmaps.size > 1) {
                        OutlinedButton(onClick = { if (currentPage > 0) currentPage-- }, enabled = currentPage > 0) {
                            Text("‹", fontSize = 12.sp)
                        }
                        Text("${currentPage + 1}/${pdfBitmaps.size}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        OutlinedButton(onClick = { if (currentPage < pdfBitmaps.lastIndex) currentPage++ }, enabled = currentPage < pdfBitmaps.lastIndex) {
                            Text("›", fontSize = 12.sp)
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { clefMenuExpanded = true }) {
                            Text(selectedPart.clef.label, fontSize = 11.sp)
                        }
                        DropdownMenu(expanded = clefMenuExpanded, onDismissRequest = { clefMenuExpanded = false }) {
                            ClefType.entries.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.label) },
                                    onClick = {
                                        clefMenuExpanded = false
                                        if (!isRecording) {
                                            score = score.copy(
                                                parts = score.parts.map { p ->
                                                    if (p.id == selectedPart.id) PdfOcrEngine.remapPartClef(p, c) else p
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (pdfBitmaps.isNotEmpty()) {
                Image(
                    bitmap = pdfBitmaps[currentPage].asImageBitmap(),
                    contentDescription = "Nota Sayfası",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    selectedPart.notes.forEach { n ->
                        val active = isRecording && currentBeat >= n.startBeat && currentBeat < n.startBeat + n.durationBeats
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0x3310B981) else Color(0xFF1E293B))
                                .border(1.dp, if (active) Color(0xFF34D399) else Color(0xFF334155), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (n.isRest) "Ö${n.measure} ES" else "Ö${n.measure} ${n.note}",
                                color = if (n.isRest) Color(0xFF64748B) else Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Alt Bölge: 60 FPS Canlı Piano Roll
        PitchCanvas(
            notes = selectedPart.notes,
            currentBeat = currentBeat,
            bpm = bpm,
            isRecording = isRecording,
            countInBeat = countInBeat,
            transposeOctaves = transposeOctaves,
            toleranceCents = customToleranceCents,
            pitchHistory = pitchHistory,
            referenceContour = referenceContour,
            targetMidi = liveTargetMidi,
            detectedNote = liveDetectedNote,
            frequency = liveFrequency,
            centsOff = liveCentsOff,
            status = liveStatus,
            modifier = Modifier.weight(1f)
        )
    }

    summary?.let { s ->
        ScoreSummaryModal(
            summary = s,
            parts = score.parts,
            hasAudioExport = audioEngine.hasRecordedAudio(),
            onClose = { summary = null },
            onRetry = {
                summary = null
                startRecordingFlow()
            },
            onPracticeWeakSpots = { measures ->
                summary = null
                weakMeasuresFilter = measures
                currentBeat = 0f
                pitchHistory.clear()
                statusMsg = "Hatalı ölçüler (${measures.joinToString(", ")}) çalışma moduna alındı."
            },
            onShareReport = {
                val weakText = if (s.weakMeasures.isEmpty()) "Yok" else s.weakMeasures.joinToString(", ")
                val shareText = buildString {
                    appendLine("NotaUyumu Studio — Koro Çalışma Raporu")
                    appendLine("Eser: ${score.title}")
                    appendLine("Parti: ${s.partName}")
                    appendLine("Doğruluk: %${s.overallAccuracy} (${s.hitNotesCount}/${s.totalTargetNotes} nota)")
                    s.referenceMatchPercent?.let { appendLine("Referans Kayıt Uyumu: %$it") }
                    appendLine("Hata Şiddeti: Ort. ±${s.meanAbsCentsError} cent (Maks ${s.maxCentsError} cent)")
                    appendLine("Hatalı Ölçüler: $weakText")
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(intent, "Raporu Paylaş"))
            },
            onExportWav = {
                val safeName = selectedPart.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                wavExportLauncher.launch("NotaUyumu_${safeName}.wav")
            },
            onSwitchPart = { newPartId ->
                summary = null
                weakMeasuresFilter = null
                selectedPartId = newPartId
                currentBeat = 0f
                pitchHistory.clear()
            }
        )
    }
}

private fun resolveFileName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            return cursor.getString(idx) ?: "nota.pdf"
        }
    }
    return "nota.pdf"
}

package com.notauyumu.studio.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notauyumu.core.ArrangementBuilder
import com.notauyumu.core.BackingTrack
import com.notauyumu.core.Grade
import com.notauyumu.core.MixSettings
import com.notauyumu.core.Note
import com.notauyumu.core.PerformanceReport
import com.notauyumu.core.Pitch
import com.notauyumu.core.PitchSample
import com.notauyumu.core.Score
import com.notauyumu.core.ScoreFormatException
import com.notauyumu.core.Scorer
import com.notauyumu.core.ScoringSettings
import com.notauyumu.core.VoiceLine
import com.notauyumu.studio.audio.PracticeEngine
import com.notauyumu.studio.data.Library
import com.notauyumu.studio.data.PageLoader
import com.notauyumu.studio.data.PieceEntry
import com.notauyumu.studio.data.PracticePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface Screen {
    data object Library : Screen
    data object Piece : Screen
    data object Practice : Screen
    data object Result : Screen
}

enum class Mode { LISTEN, SING }

/** Çizim için zenginleştirilmiş canlı ölçüm. [shownMidi] oktav katlaması uygulanmış perdedir. */
data class LiveSample(val timeSec: Double, val shownMidi: Double?, val grade: Grade)

class LoadedPiece(val entry: PieceEntry, val score: Score) {
    var backing: BackingTrack? = null
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val library = Library(app)

    var screen by mutableStateOf<Screen>(Screen.Library); private set
    var entries by mutableStateOf(library.entries()); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    var piece by mutableStateOf<LoadedPiece?>(null); private set
    var lineId by mutableStateOf<String?>(null); private set
    var prefs by mutableStateOf(library.loadPrefs()); private set
    /** Çalışılacak ölçü aralığı (1 tabanlı, kapalı). */
    var range by mutableStateOf(1..1); private set

    // --- oturum durumu ---
    var mode by mutableStateOf(Mode.SING); private set
    var session: SessionView? by mutableStateOf(null); private set
    /** Çizimi tetiklemek için her karede artar. */
    var frame by mutableLongStateOf(0L); private set
    var report by mutableStateOf<PerformanceReport?>(null); private set
    var newRecord by mutableStateOf(false); private set

    private var engine: PracticeEngine? = null

    // --- ses kaydından içe aktarma ---
    /** Seçilmiş ama türü henüz sorulmamış ses dosyası. */
    var pendingAudio by mutableStateOf<Uri?>(null)
    /** İçe aktarma sürüyorsa ilerleme (0..1) ve aşama. */
    var importProgress by mutableStateOf<Pair<Float, String>?>(null); private set

    /** İçe aktarma penceresinin başlığı. */
    var importTitle by mutableStateOf("Kayıt çözümleniyor"); private set

    /** PDF ya da fotoğraf(lar)dan nota okur; tamamen telefonda. */
    fun importScanned(uris: List<Uri>, kind: PageLoader.Kind = PageLoader.Kind.IMAGE) {
        if (uris.isEmpty() || importProgress != null) return
        importTitle = "Nota okunuyor"
        importProgress = 0f to "Sayfalar açılıyor"
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val entry = withContext(Dispatchers.Default) {
                    val report: (Float, String) -> Unit = { f, label -> viewModelScope.launch(Dispatchers.Main) { importProgress = f to label } }
                    val pages = if (kind == PageLoader.Kind.PDF) PageLoader.pdfPages(ctx, uris.first()) { report(it * 0.05f, "PDF sayfaları açılıyor") }
                    else uris.mapIndexed { i, u -> report(0.05f * i / uris.size, "Fotoğraf ${i + 1} açılıyor"); PageLoader.image(ctx, u) }
                    val title = library.displayNameOf(uris.first())?.substringBeforeLast('.')?.takeIf { kind == PageLoader.Kind.PDF } ?: "Fotoğraftan okunan nota"
                    library.importScanned(title, pages) { f, label -> report(0.05f + f * 0.95f, label) }
                }
                entries = library.entries()
                importProgress = null
                openPiece(entry)
            } catch (e: ScoreFormatException) {
                message = e.message
            } catch (e: OutOfMemoryError) {
                message = "Telefonun belleği yetmedi. Daha az sayfayla deneyin."
            } catch (e: Throwable) {
                message = "Nota okunamadı: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importProgress = null
            }
        }
    }

    fun importRecording(uri: Uri, kind: Library.RecordingKind, voices: Int) {
        importTitle = "Kayıt çözümleniyor"
        pendingAudio = null
        if (importProgress != null) return
        importProgress = 0f to "Başlıyor"
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.Default) {
                    library.importRecording(uri, kind, voices) { f, label -> viewModelScope.launch(Dispatchers.Main) { importProgress = f to label } }
                }
                entries = library.entries()
                importProgress = null
                openPiece(entry)
            } catch (e: ScoreFormatException) {
                message = e.message
            } catch (e: Throwable) {
                message = "Kayıt çözümlenemedi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importProgress = null
            }
        }
    }

    // ---------------------------------------------------------------- kitaplık

    fun import(uri: Uri) {
        if (busy || importProgress != null) return
        val kind = runCatching { PageLoader.kindOf(getApplication(), uri) }.getOrDefault(PageLoader.Kind.MUSICXML)
        if (kind != PageLoader.Kind.MUSICXML) { importScanned(listOf(uri), kind); return }
        busy = true
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { library.import(uri) }
                entries = library.entries()
                openPiece(entry)
            } catch (e: ScoreFormatException) {
                message = e.message
            } catch (e: Exception) {
                message = "Dosya açılamadı."
            } finally {
                busy = false
            }
        }
    }

    fun openPiece(entry: PieceEntry) {
        viewModelScope.launch {
            busy = true
            try {
                val raw = withContext(Dispatchers.IO) { library.load(entry.id) }
                val override = library.baseTempo(entry.id)
                val score = if (override != null && raw.tempoBpm > 0) raw.scaled(override / raw.tempoBpm) else raw
                piece = LoadedPiece(entry, score)
                val last = library.lastLine(entry.id)
                lineId = score.lines.firstOrNull { it.id == last }?.id ?: score.lines.first().id
                range = 1..score.measures.size
                screen = Screen.Piece
            } catch (e: Exception) {
                message = (e as? ScoreFormatException)?.message ?: "Eser açılamadı."
            } finally {
                busy = false
            }
        }
    }

    fun deletePiece(entry: PieceEntry) {
        library.delete(entry.id)
        entries = library.entries()
        if (piece?.entry?.id == entry.id) { piece = null; screen = Screen.Library }
    }

    fun best(lineId: String): Double? = piece?.let { library.best(it.entry.id, lineId) }

    // ---------------------------------------------------------------- ayarlar

    fun selectLine(id: String) {
        lineId = id
        piece?.let { library.setLastLine(it.entry.id, id) }
    }

    /** Notadan okunamayan tempoyu eser için ayarlar (♩/dk). */
    fun setBaseTempo(bpm: Int) {
        val p = piece ?: return
        if (p.score.tempoBpm <= 0) return
        val clamped = bpm.coerceIn(30, 240).toDouble()
        library.setBaseTempo(p.entry.id, clamped)
        piece = LoadedPiece(p.entry, p.score.scaled(clamped / p.score.tempoBpm)).also { it.backing = p.backing }
    }

    fun updatePrefs(p: PracticePrefs) { prefs = p; library.savePrefs(p) }

    fun selectRange(r: IntRange) {
        val n = piece?.score?.measures?.size ?: return
        val a = r.first.coerceIn(1, n); val b = r.last.coerceIn(a, n)
        range = a..b
    }

    // ---------------------------------------------------------------- oturum

    fun back() {
        when (screen) {
            Screen.Practice -> stopSession(abandon = true)
            Screen.Result -> screen = Screen.Piece
            Screen.Piece -> { screen = Screen.Library }
            Screen.Library -> Unit
        }
    }

    fun startSession(m: Mode) {
        val p = piece ?: return
        val line = p.score.lines.firstOrNull { it.id == lineId } ?: return
        stopEngine()
        val scaled = p.score.scaled(prefs.tempoPercent / 100.0)
        val sLine = scaled.lines.first { it.id == line.id }
        val from = scaled.measures[range.first - 1].startSec
        val until = scaled.measures[range.last - 1].endSec
        // Hoparlörden çalarken mikrofon kendi partini duyup "doğru söylüyorsun" sanmasın: kendi partin kapanır,
        // ön sayımda yalnızca başlangıç notası verilir. Kulaklıkta tam eşlik.
        val speaker = m == Mode.SING && !headphonesConnected()
        val mix = MixSettings(
            ownGain = when { m == Mode.LISTEN -> maxOf(prefs.ownGain, 0.6f); speaker -> 0f; else -> prefs.ownGain },
            othersGain = if (speaker) prefs.othersGain * 0.6f else prefs.othersGain,
            metronome = prefs.metronome,
            countIn = true,
        )
        // Kayıttan çıkarılmış eserde özgün kayıt eşlik eder (yalnızca %100 tempoda; hoparlörde söylerken kapalı)
        val useBacking = p.score.fromRecording && prefs.tempoPercent == 100 && prefs.backingGain > 0f && !speaker
        val backing = if (useBacking) (p.backing ?: library.backing(p.entry.id).also { p.backing = it }) else null
        val finalMix = if (backing != null) mix.copy(backingGain = prefs.backingGain, metronome = false) else mix.copy(metronome = mix.metronome && !p.score.fromRecording)
        val arrangement = ArrangementBuilder.build(scaled, line.id, finalMix, from, until)
        val eng = PracticeEngine(arrangement, finalMix, listenToMic = m == Mode.SING, latencySec = prefs.latencyMs / 1000.0, backing = backing, backingFrom = from, backingUntil = until)
        engine = eng
        mode = m
        report = null
        newRecord = false
        session = SessionView(scaled, sLine, from, until, arrangement.countInSec, ScoringSettings(prefs.toleranceCents.toDouble(), prefs.forgiveOctave), speaker)
        screen = Screen.Practice
        eng.start()
    }

    /** Her ekran karesinde çağrılır: mikrofon ölçümlerini toplar, bitişi algılar. */
    fun tick() {
        val eng = engine ?: return
        val s = session ?: return
        s.now = eng.scoreTimeSec()
        while (true) {
            val p = eng.samples.poll() ?: break
            s.add(p)
        }
        eng.failure?.let { message = it; stopSession(abandon = true); return }
        frame++
        if (eng.finished) stopSession(abandon = false)
    }

    /** [abandon] = geri tuşu: rapor üretme. Aksi hâlde (bitti ya da "Bitir") rapor üret. */
    fun stopSession(abandon: Boolean) {
        val s = session
        val wasSing = mode == Mode.SING
        stopEngine()
        if (s == null || abandon || !wasSing) { screen = Screen.Piece; return }
        val until = minOf(s.until, s.now)
        if (until - s.from < 1.5 || s.raw.isEmpty()) { screen = Screen.Piece; message = "Değerlendirme için biraz daha uzun söylemelisin."; return }
        busy = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                Scorer.evaluate(s.line, s.score.measures, s.raw.toList(), s.settings, s.from, until)
            }
            val p = piece
            newRecord = p != null && r.notes.isNotEmpty() && range == 1..p.score.measures.size &&
                library.recordScore(p.entry.id, s.line.id, r.overall)
            report = r
            busy = false
            screen = Screen.Result
        }
    }

    fun onAppHidden() { if (screen == Screen.Practice) stopSession(abandon = true) }

    fun practiceMeasures(r: IntRange) { selectRange(r); screen = Screen.Piece }

    private fun headphonesConnected(): Boolean {
        val am = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val types = mutableSetOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (android.os.Build.VERSION.SDK_INT >= 31) types += android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private fun stopEngine() { engine?.stop(); engine = null }

    override fun onCleared() { stopEngine() }
}

/** Ekranın okuduğu canlı oturum verisi. Yalnızca ana iş parçacığında değiştirilir. */
class SessionView(
    val score: Score,
    val line: VoiceLine,
    val from: Double,
    val until: Double,
    val countIn: Double,
    val settings: ScoringSettings,
    /** Kulaklık yok: kendi partin çalınmıyor. */
    val speakerMode: Boolean = false,
) {
    var now: Double = from - countIn
    val raw = ArrayList<PitchSample>()
    val live = ArrayList<LiveSample>()
    /** Nota başına canlı isabet sayacı: (isabet, toplam). Anahtar: chordGroup. */
    val noteStats = HashMap<Int, IntArray>()
    var lastSungMidi: Double? = null; private set
    var lastCents: Double? = null; private set

    fun targetsAt(t: Double): List<Note> = Scorer.targetsAt(line, t)

    fun add(p: PitchSample) {
        raw += p
        val targets = targetsAt(p.timeSec)
        val grade = Scorer.grade(p.midi, targets.map { it.midi }, settings)
        val m = p.midi
        var shown = m
        var cents: Double? = null
        if (m != null && targets.isNotEmpty()) {
            val nearest = targets.minBy { abs(Pitch.centsOff(m, it.midi, settings.forgiveOctave)) }
            cents = Pitch.centsOff(m, nearest.midi, settings.forgiveOctave)
            if (settings.forgiveOctave) shown = nearest.midi + cents / 100.0
            val st = noteStats.getOrPut(nearest.chordGroup) { IntArray(2) }
            st[1]++
            if (grade == Grade.HIT) st[0]++
        } else if (targets.isNotEmpty()) {
            val st = noteStats.getOrPut(targets.first().chordGroup) { IntArray(2) }
            st[1]++
        }
        lastSungMidi = m
        lastCents = cents
        live += LiveSample(p.timeSec, shown, grade)
        if (live.size > 6000) live.subList(0, 1000).clear()
    }

    fun noteAccuracy(n: Note): Double? = noteStats[n.chordGroup]?.let { if (it[1] >= 3) it[0].toDouble() / it[1] else null }

    fun measureLabelAt(t: Double): String = score.measureAt(t)?.label ?: ""

    fun progress(): Float = ((now - from) / (until - from)).toFloat().coerceIn(0f, 1f)

    fun countdown(): Int? = if (now < from) ((from - now) / (countIn / maxOf(1, beatsInCountIn())) ).toInt() + 1 else null

    private fun beatsInCountIn(): Int = score.measureAt(from)?.beats?.size?.coerceIn(2, 6) ?: 4

    fun liveCentsLabel(): String? = lastCents?.let { c -> (if (c >= 0) "+" else "−") + abs(c).roundToInt() }
}

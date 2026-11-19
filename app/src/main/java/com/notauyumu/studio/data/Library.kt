package com.notauyumu.studio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.notauyumu.core.BackingTrack
import com.notauyumu.core.Measure
import com.notauyumu.core.MusicXmlParser
import com.notauyumu.core.Note
import com.notauyumu.core.Score
import com.notauyumu.core.ScoreFormatException
import com.notauyumu.core.VoiceLine
import com.notauyumu.core.align.ScoreAligner
import com.notauyumu.core.omr.GrayImage
import com.notauyumu.core.omr.HomrModel
import com.notauyumu.core.omr.OmrPipeline
import com.notauyumu.core.transcribe.BasicPitch
import com.notauyumu.core.transcribe.OrtBasicPitchModel
import com.notauyumu.core.transcribe.VoiceSplitter
import com.notauyumu.core.transcribe.VoiceTracker
import com.notauyumu.studio.audio.AudioDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class PieceEntry(
    val id: String,
    val title: String,
    val composer: String?,
    val lineNames: List<String>,
    val addedAt: Long,
    val isDemo: Boolean,
    val fromRecording: Boolean = false,
)

/** Kullanıcı ayarları; tüm eserler için ortak. */
data class PracticePrefs(
    val tempoPercent: Int = 100,
    val toleranceCents: Int = 35,
    val forgiveOctave: Boolean = true,
    val ownGain: Float = 0.9f,
    val othersGain: Float = 0.35f,
    val metronome: Boolean = true,
    val latencyMs: Int = 120,
    val backingGain: Float = 0.7f,
)

/**
 * Eser kitaplığı: içe aktarılan dosyalar uygulamanın kendi klasörüne kopyalanır,
 * özet bilgiler ve en iyi skorlar SharedPreferences'ta JSON olarak tutulur.
 */
class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "scores").apply { mkdirs() }

    companion object {
        const val DEMO_ID = "demo-isinma-korali"
        /** Nota + kayıt eşleştirmesiyle oluşturulan eserlerin kaynak etiketi. */
        const val ALIGNED_LABEL = "Nota + kayıt"
        private const val DEMO_ASSET = "isinma-korali.musicxml"
    }

    fun entries(): List<PieceEntry> {
        val list = ArrayList<PieceEntry>()
        list += demoEntry()
        val arr = JSONArray(prefs.getString("pieces", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val names = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            list += PieceEntry(o.getString("id"), o.getString("title"), o.optString("composer").ifEmpty { null }, names, o.getLong("addedAt"), false, o.optBoolean("rec"))
        }
        return list.sortedWith(compareBy<PieceEntry> { it.isDemo }.thenByDescending { it.addedAt })
    }

    private fun demoEntry() = PieceEntry(DEMO_ID, "Isınma Korali", "Örnek eser · 4 ses", listOf("Soprano", "Alto", "Tenor", "Bas"), 0, true)

    fun load(id: String): Score {
        if (id != DEMO_ID && File(dir, "$id.json").exists()) return readRecordingScore(JSONObject(File(dir, "$id.json").readText()))
        val bytes = if (id == DEMO_ID) context.assets.open(DEMO_ASSET).use { it.readBytes() }
        else File(dir, id).readBytes()
        return MusicXmlParser.parse(bytes)
    }

    /** Kayıttan çıkarılmış eserin özgün sesi (eşlik için). Yoksa null. */
    fun backing(id: String): BackingTrack? {
        val f = File(dir, "$id.pcm")
        if (!f.exists()) return null
        val bytes = f.readBytes()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return BackingTrack(FloatArray(sb.remaining()) { sb.get() / 32768f }, AudioDecoder.TARGET_RATE)
    }

    enum class RecordingKind { SINGLE, TUTTI }

    /**
     * Ses kaydından eser oluşturur: çözme → Basic Pitch → ses ayırma. Tamamen telefonda çalışır.
     * [progress]: 0..1 ve aşama adı.
     */
    fun importRecording(uri: Uri, kind: RecordingKind, voices: Int, progress: (Float, String) -> Unit): PieceEntry {
        val (audio, act) = transcribe(uri, progress)
        progress(0.92f, "Partiler ayrılıyor")
        val events = BasicPitch.notes(act, minMidi = VoiceSplitter.VOICE_MIN_MIDI, maxMidi = VoiceSplitter.VOICE_MAX_MIDI)
        if (events.isEmpty()) throw ScoreFormatException("Kayıtta nota bulunamadı. Daha net ya da daha yüksek sesli bir kayıt deneyin.")
        val count = if (kind == RecordingKind.SINGLE) 1 else if (voices in 2..6) voices else VoiceTracker.estimateVoices(act)
        val lines = if (kind == RecordingKind.SINGLE) listOf(VoiceSplitter.monophonic(events)) else VoiceTracker.split(act, count)
        val names = VoiceSplitter.voiceNames(count)
        val title = displayName(uri)?.substringBeforeLast('.') ?: "Ses kaydı"
        val score = VoiceSplitter.toScore(title, lines, names, audio.size.toDouble() / AudioDecoder.TARGET_RATE)
        if (score.lines.isEmpty()) throw ScoreFormatException("Kayıttan parti çıkarılamadı.")

        val id = UUID.randomUUID().toString()
        File(dir, "$id.json").writeText(writeRecordingScore(score).toString())
        writePcm(id, audio)
        val o = JSONObject()
            .put("id", id).put("title", score.title).put("composer", if (kind == RecordingKind.SINGLE) "Ses kaydı · tek parti" else "Ses kaydı · tutti")
            .put("lines", JSONArray(score.lines.map { it.name })).put("addedAt", System.currentTimeMillis()).put("rec", true)
        prefs.edit().putString("pieces", JSONArray(prefs.getString("pieces", "[]")).put(o).toString()).apply()
        progress(1f, "Hazır")
        return PieceEntry(id, score.title, o.getString("composer"), score.lines.map { it.name }, o.getLong("addedAt"), false, true)
    }

    /** Kaydı çözer, normalize eder ve Basic Pitch ile nota olasılıklarını çıkarır (ilerlemenin %0–90'ı). */
    private fun transcribe(uri: Uri, progress: (Float, String) -> Unit): Pair<FloatArray, BasicPitch.Activations> {
        progress(0f, "Kayıt okunuyor")
        val audio = AudioDecoder.decode(context, uri) { progress(it * 0.2f, "Kayıt okunuyor") }
        // Sesi normalize et (çok kısık kayıtlar için)
        val peak = audio.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-4f)
        if (peak < 0.5f) { val g = 0.7f / peak; for (i in audio.indices) audio[i] *= g }
        val modelBytes = context.assets.open("basic-pitch/nmp.onnx").use { it.readBytes() }
        val act = OrtBasicPitchModel(modelBytes).use { m ->
            BasicPitch.run(m, audio) { progress(0.2f + it * 0.7f, "Notalar çıkarılıyor") }
        }
        return audio to act
    }

    private fun writePcm(id: String, audio: FloatArray) {
        val pcm = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in audio) pcm.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        File(dir, "$id.pcm").writeBytes(pcm.array())
    }

    /**
     * Nota + referans kaydı: kayıt notaya eşlenir (kayıt eserin ortasından başlayabilir, tempo farklı ve
     * değişken olabilir), nota kaydın zamanına taşınır, kayıt senkron eşlik olur. Notası kayıtla açıkça
     * oktav farklı çıkan partiler (ör. okunamayan 8vb anahtarı) düzeltilir. Yeni bir eser olarak eklenir.
     */
    fun importAligned(scoreId: String, uri: Uri, progress: (Float, String) -> Unit): PieceEntry {
        val raw = load(scoreId)
        val override = baseTempo(scoreId)
        val score = if (override != null && raw.tempoBpm > 0) raw.scaled(override / raw.tempoBpm) else raw
        val (audio, act) = transcribe(uri, progress)
        progress(0.92f, "Kayıt notayla eşleştiriliyor")
        val match = ScoreAligner.alignAnyKey(score, act)
            ?: throw ScoreFormatException("Kayıt bu notayla eşleştirilemedi: kayıtta müzik bulunamadı ya da kayıt notadan çok daha uzun.")
        val al = match.alignment
        if (al.cost > 0.22) throw ScoreFormatException("Bu kayıt seçili notaya benzemiyor (uyumsuzluk %${(al.cost * 100).roundToInt()}). Doğru eseri ve kaydı seçtiğinden emin ol.")
        val retimed = ScoreAligner.retime(ScoreAligner.transpose(score, match.semitones), al)
        if (retimed.measures.size < 2) throw ScoreFormatException("Kayıt notanın çok kısa bir bölümüne denk geliyor.")
        progress(0.96f, "Partiler kayıtla karşılaştırılıyor")
        val (fixed, fixes) = ScoreAligner.correct(retimed, act)
        val agree = ScoreAligner.agreement(fixed, act)
        val shifted = fixes.map { it.lineId }.toSet()
        val lines = fixed.lines.map { l ->
            val pct = ((agree[l.id] ?: 0.0) * 100).roundToInt()
            l.copy(name = l.name + " · uyum %$pct" + if (l.id in shifted) " · oktav düzeltildi" else "")
        }
        val avg = (agree.values.average() * 100).roundToInt()
        val key = when {
            match.semitones == 0 -> ""
            else -> " · kayıt notadan ${kotlin.math.abs(match.semitones)} yarım ses ${if (match.semitones > 0) "yüksek" else "pes"}"
        }
        val composer = "$ALIGNED_LABEL · ${ScoreAligner.rangeLabel(fixed)} · uyum %$avg$key"
        val out = fixed.copy(title = "${score.title} · kayıtla", composer = composer, lines = lines)

        val id = UUID.randomUUID().toString()
        File(dir, "$id.json").writeText(writeRecordingScore(out).toString())
        writePcm(id, audio)
        val o = JSONObject()
            .put("id", id).put("title", out.title).put("composer", composer)
            .put("lines", JSONArray(out.lines.map { it.name })).put("addedAt", System.currentTimeMillis()).put("rec", true)
        prefs.edit().putString("pieces", JSONArray(prefs.getString("pieces", "[]")).put(o).toString()).apply()
        progress(1f, "Hazır")
        return PieceEntry(id, out.title, composer, out.lines.map { it.name }, o.getLong("addedAt"), false, true)
    }

    /** Kayıttan ya da PDF/fotoğraftan çıkarılmış eserin saklama biçimi. */
    private fun writeRecordingScore(s: Score): JSONObject = JSONObject()
        .put("title", s.title)
        .put("composer", s.composer ?: "")
        .put("tempo", s.tempoBpm)
        .put("rec", s.fromRecording)
        .put("measures", JSONArray(s.measures.map { JSONArray(listOf(it.startSec, it.endSec, JSONArray(it.beats), it.label)) }))
        .put("lines", JSONArray(s.lines.map { l ->
            JSONObject().put("id", l.id).put("name", l.name)
                .put("notes", JSONArray(l.notes.map { n -> JSONArray(listOf(n.startSec, n.durationSec, n.midi, n.chordGroup)) }))
        }))

    private fun readRecordingScore(o: JSONObject): Score {
        val ma = o.getJSONArray("measures")
        val measures = (0 until ma.length()).map { i ->
            val m = ma.getJSONArray(i)
            val beats = m.optJSONArray(2)?.let { b -> (0 until b.length()).map { b.getDouble(it) } } ?: emptyList()
            Measure(i + 1, m.optString(3, "${i + 1}"), m.getDouble(0), m.getDouble(1), beats)
        }
        fun measureOf(t: Double) = measures.indexOfLast { it.startSec <= t + 1e-6 }.coerceAtLeast(0) + 1
        var group = 0
        val la = o.getJSONArray("lines")
        val lines = (0 until la.length()).map { i ->
            val l = la.getJSONObject(i)
            val na = l.getJSONArray("notes")
            VoiceLine(l.getString("id"), l.getString("name"), l.getString("name"), (0 until na.length()).map { k ->
                val n = na.getJSONArray(k)
                Note(n.getInt(2), n.getDouble(0), n.getDouble(1), measureOf(n.getDouble(0)), null, if (n.length() > 3) n.getInt(3) else ++group)
            })
        }
        val rec = o.optBoolean("rec", true)
        return Score(o.getString("title"), o.optString("composer").ifEmpty { if (rec) "Ses kaydından" else null }, lines, measures, o.optDouble("tempo", 0.0), fromRecording = rec)
    }

    /** PDF / fotoğraf: homr modeliyle telefonda okunur. [pages] sayfa görüntüleri. */
    fun importScanned(title: String, pages: List<GrayImage>, progress: (Float, String) -> Unit): PieceEntry {
        val (enc, dec) = homrModelFiles { f -> progress(f * 0.05f, "Nota okuyucu hazırlanıyor") }
        val score = HomrModel(enc.path, dec.path).use { m ->
            OmrPipeline.read(title, pages, OmrPipeline.Reader { m.read(it) }) { f, msg -> progress(0.05f + f * 0.95f, msg) }
        }
        val id = UUID.randomUUID().toString()
        File(dir, "$id.json").writeText(writeRecordingScore(score).toString())
        val o = JSONObject()
            .put("id", id).put("title", score.title).put("composer", "PDF / fotoğraftan okundu")
            .put("lines", JSONArray(score.lines.map { it.name })).put("addedAt", System.currentTimeMillis()).put("rec", false)
        prefs.edit().putString("pieces", JSONArray(prefs.getString("pieces", "[]")).put(o).toString()).apply()
        return PieceEntry(id, score.title, o.getString("composer"), score.lines.map { it.name }, o.getLong("addedAt"), false)
    }

    /** Uygulamaya gömülü homr modellerini (ilk kullanımda) dosya sistemine çıkarır; ONNX Runtime dosya yolu ister. */
    private fun homrModelFiles(progress: (Float) -> Unit): Pair<File, File> {
        val d = File(context.filesDir, "homr").apply { mkdirs() }
        val names = listOf("encoder.onnx", "decoder.onnx")
        val files = names.map { File(d, it) }
        names.forEachIndexed { i, n ->
            val f = files[i]
            val expected = context.assets.openFd("homr/$n").use { it.length }
            if (!f.exists() || f.length() != expected) {
                val tmp = File(d, "$n.part")
                context.assets.open("homr/$n").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                tmp.renameTo(f)
            }
            progress((i + 1f) / names.size)
        }
        return files[0] to files[1]
    }

    /** Dosyayı okur, doğrular ve kitaplığa ekler. Geçersizse [ScoreFormatException] fırlatır. */
    fun import(uri: Uri): PieceEntry {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ScoreFormatException("Dosya açılamadı.")
        if (bytes.size > 20 * 1024 * 1024) throw ScoreFormatException("Dosya çok büyük (20 MB üstü).")
        val name = displayName(uri)?.substringBeforeLast('.') ?: "Adsız eser"
        val score = MusicXmlParser.parse(bytes, name)
        val id = UUID.randomUUID().toString()
        File(dir, id).writeBytes(bytes)
        val o = JSONObject()
            .put("id", id).put("title", score.title).put("composer", score.composer ?: "")
            .put("lines", JSONArray(score.lines.map { it.name })).put("addedAt", System.currentTimeMillis())
        val arr = JSONArray(prefs.getString("pieces", "[]")).put(o)
        prefs.edit().putString("pieces", arr.toString()).apply()
        return PieceEntry(id, score.title, score.composer, score.lines.map { it.name }, o.getLong("addedAt"), false)
    }

    fun delete(id: String) {
        if (id == DEMO_ID) return
        val arr = JSONArray(prefs.getString("pieces", "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("id") != id) out.put(arr.getJSONObject(i))
        File(dir, id).delete(); File(dir, "$id.json").delete(); File(dir, "$id.pcm").delete()
        val best = JSONObject(prefs.getString("best", "{}"))
        best.keys().asSequence().toList().filter { it.startsWith("$id|") }.forEach { best.remove(it) }
        prefs.edit().putString("pieces", out.toString()).putString("best", best.toString()).apply()
    }

    fun best(pieceId: String, lineId: String): Double? {
        val o = JSONObject(prefs.getString("best", "{}"))
        return if (o.has("$pieceId|$lineId")) o.getDouble("$pieceId|$lineId") else null
    }

    /** Yeni rekor ise kaydeder ve true döner. */
    fun recordScore(pieceId: String, lineId: String, value: Double): Boolean {
        val o = JSONObject(prefs.getString("best", "{}"))
        val key = "$pieceId|$lineId"
        val old = if (o.has(key)) o.getDouble(key) else -1.0
        if (value <= old) return false
        o.put(key, value)
        prefs.edit().putString("best", o.toString()).apply()
        return true
    }

    /** Eser için kullanıcının girdiği nota temposu (♩/dk); yoksa null. */
    fun baseTempo(pieceId: String): Double? = prefs.getFloat("bpm|$pieceId", -1f).takeIf { it > 0 }?.toDouble()
    fun setBaseTempo(pieceId: String, bpm: Double) = prefs.edit().putFloat("bpm|$pieceId", bpm.toFloat()).apply()

    fun lastLine(pieceId: String): String? = prefs.getString("lastLine|$pieceId", null)
    fun setLastLine(pieceId: String, lineId: String) = prefs.edit().putString("lastLine|$pieceId", lineId).apply()

    fun loadPrefs(): PracticePrefs {
        val d = PracticePrefs()
        return PracticePrefs(
            tempoPercent = prefs.getInt("tempo", d.tempoPercent),
            toleranceCents = prefs.getInt("tolerance", d.toleranceCents),
            forgiveOctave = prefs.getBoolean("octave", d.forgiveOctave),
            ownGain = prefs.getFloat("own", d.ownGain),
            othersGain = prefs.getFloat("others", d.othersGain),
            metronome = prefs.getBoolean("metronome", d.metronome),
            latencyMs = prefs.getInt("latency", d.latencyMs),
            backingGain = prefs.getFloat("backing", d.backingGain),
        )
    }

    fun savePrefs(p: PracticePrefs) {
        prefs.edit().putInt("tempo", p.tempoPercent).putInt("tolerance", p.toleranceCents)
            .putBoolean("octave", p.forgiveOctave).putFloat("own", p.ownGain).putFloat("others", p.othersGain)
            .putBoolean("metronome", p.metronome).putInt("latency", p.latencyMs).putFloat("backing", p.backingGain).apply()
    }

    fun displayNameOf(uri: Uri): String? = displayName(uri)

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

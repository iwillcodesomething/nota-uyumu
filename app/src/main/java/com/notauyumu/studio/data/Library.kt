package com.notauyumu.studio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.notauyumu.core.MusicXmlParser
import com.notauyumu.core.Score
import com.notauyumu.core.ScoreFormatException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PieceEntry(
    val id: String,
    val title: String,
    val composer: String?,
    val lineNames: List<String>,
    val addedAt: Long,
    val isDemo: Boolean,
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
        private const val DEMO_ASSET = "isinma-korali.musicxml"
    }

    fun entries(): List<PieceEntry> {
        val list = ArrayList<PieceEntry>()
        list += demoEntry()
        val arr = JSONArray(prefs.getString("pieces", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val names = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            list += PieceEntry(o.getString("id"), o.getString("title"), o.optString("composer").ifEmpty { null }, names, o.getLong("addedAt"), false)
        }
        return list.sortedWith(compareBy<PieceEntry> { it.isDemo }.thenByDescending { it.addedAt })
    }

    private fun demoEntry() = PieceEntry(DEMO_ID, "Isınma Korali", "Örnek eser · 4 ses", listOf("Soprano", "Alto", "Tenor", "Bas"), 0, true)

    fun load(id: String): Score {
        val bytes = if (id == DEMO_ID) context.assets.open(DEMO_ASSET).use { it.readBytes() }
        else File(dir, id).readBytes()
        return MusicXmlParser.parse(bytes)
    }

    /** Dosyayı okur, doğrular ve kitaplığa ekler. Geçersizse [ScoreFormatException] fırlatır. */
    fun import(uri: Uri): PieceEntry {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ScoreFormatException("Dosya açılamadı.")
        if (bytes.size > 20 * 1024 * 1024) throw ScoreFormatException("Dosya çok büyük (20 MB üstü).")
        if (bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte())
            throw ScoreFormatException("Bu bir PDF. PDF notalar güvenilir biçimde okunamıyor; eseri MuseScore'da açıp MusicXML olarak dışa aktarın.")
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
        File(dir, id).delete()
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
        )
    }

    fun savePrefs(p: PracticePrefs) {
        prefs.edit().putInt("tempo", p.tempoPercent).putInt("tolerance", p.toleranceCents)
            .putBoolean("octave", p.forgiveOctave).putFloat("own", p.ownGain).putFloat("others", p.othersGain)
            .putBoolean("metronome", p.metronome).putInt("latency", p.latencyMs).apply()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

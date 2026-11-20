package com.notauyumu.core.omr

import com.notauyumu.core.Score
import com.notauyumu.core.ScoreFormatException

/** Sayfa görüntülerinden (PDF sayfası ya da fotoğraf) eser çıkarır. Tamamen cihazda çalışır. */
object OmrPipeline {
    fun interface Reader { fun read(staffImage: GrayImage): List<OmrSymbol> }

    /** [progress]: (0..1, açıklama). */
    fun read(title: String, pages: List<GrayImage>, reader: Reader, progress: (Float, String) -> Unit = { _, _ -> }): Score {
        val prepared = pages.mapIndexed { i, p ->
            progress(0.02f + 0.08f * i / pages.size, "Sayfa ${i + 1} hazırlanıyor")
            val page = StaffFinder.preparePage(p)
            val staves = StaffFinder.find(page)
            Triple(page, staves, StaffFinder.systems(page, staves))
        }
        val total = prepared.sumOf { it.second.size }
        if (total == 0) throw ScoreFormatException("Sayfada porte bulunamadı. Notanın düz, net ve tamamı görünecek şekilde çekildiğinden emin olun.")
        var done = 0
        val t0 = System.nanoTime()
        fun eta(): String {
            if (done < 2) return ""
            val left = (System.nanoTime() - t0) / 1e9 / done * (total - done)
            return when {
                left < 60 -> " · yaklaşık ${(left / 10).toInt() * 10 + 10} sn kaldı"
                else -> " · yaklaşık ${(left / 60 + 0.5).toInt()} dk kaldı"
            }
        }
        val systems = ArrayList<List<List<OmrSymbol>>>()
        prepared.forEachIndexed { pi, (page, staves, sys) ->
            for (system in sys) {
                systems += system.map { st ->
                    progress(0.1f + 0.88f * done / total, "Sayfa ${pi + 1}: porte ${done + 1} / $total okunuyor${eta()}")
                    val symbols = reader.read(StaffFinder.modelInput(page, staves, staves.indexOf(st)))
                    done++
                    symbols
                }
            }
        }
        val score = OmrScoreBuilder.build(title, systems)
        if (score.lines.isEmpty()) throw ScoreFormatException("Notalar okunamadı. Daha net bir görüntü deneyin.")
        progress(1f, "Hazır")
        return score
    }
}

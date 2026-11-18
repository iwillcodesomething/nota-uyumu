package com.notauyumu.studio.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notauyumu.studio.audio.YinDetector
import com.notauyumu.studio.model.NoteEvent
import com.notauyumu.studio.model.PitchFrame
import com.notauyumu.studio.model.PitchStatus
import com.notauyumu.studio.model.ReferencePitchPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun PitchCanvas(
    notes: List<NoteEvent>,
    currentBeat: Float,
    bpm: Int,
    isRecording: Boolean,
    countInBeat: Int?,
    transposeOctaves: Int,
    toleranceCents: Int,
    pitchHistory: List<PitchFrame>,
    referenceContour: List<ReferencePitchPoint>,
    targetMidi: Int?,
    detectedNote: String?,
    frequency: Float?,
    centsOff: Float?,
    status: PitchStatus,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 24f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
    }
    val noteTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#E2E8F0")
            textSize = 26f
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF070B14))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
    ) {
        // Canlı HUD Çubuğu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val targetLabel = targetMidi?.let { YinDetector.midiToNoteName(it.toFloat()) } ?: "Es"
                Text("Hedef: $targetLabel", color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("|", color = Color(0xFF475569), fontSize = 12.sp)
                Text("Sesin: ${detectedNote ?: "—"}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = frequency?.let { "${String.format("%.1f", it)} Hz" } ?: "— Hz",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            val badgeBg = when (status) {
                PitchStatus.HIT -> Color(0x3322C55E)
                PitchStatus.NEAR -> Color(0x33EAB308)
                PitchStatus.MISS -> Color(0x33EF4444)
                else -> Color(0xFF1E293B)
            }
            val badgeFg = when (status) {
                PitchStatus.HIT -> Color(0xFF86EFAC)
                PitchStatus.NEAR -> Color(0xFFFDE047)
                PitchStatus.MISS -> Color(0xFFFCA5A5)
                else -> Color(0xFFCBD5E1)
            }
            val devText = if (centsOff != null && targetMidi != null) {
                val r = centsOff.roundToInt()
                val dir = if (r > 0) "sürtone" else if (r < 0) "detone" else "isabet"
                "${if (r > 0) "+" else ""}$r c $dir"
            } else "Bekleniyor"

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(devText, color = badgeFg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 60 FPS Piano Roll Canvas
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val playable = notes.filter { !it.isRest && it.midi != null }.map { it.midi!! + transposeOctaves * 12 }
                val minMidi = (playable.minOrNull() ?: 60) - 5f
                val maxMidi = (playable.maxOrNull() ?: 72) + 5f
                val midiSpan = max(12f, maxMidi - minMidi)

                val leftAxis = 76f
                val playheadX = leftAxis + (w - leftAxis) * 0.25f
                val pxPerBeat = 95f

                fun midiToY(m: Float): Float = h - 30f - ((m - minMidi) / midiSpan) * (h - 60f)

                // Yatay ızgara ve sol eksen nota etiketleri
                for (m in floor(minMidi).toInt()..ceil(maxMidi).toInt()) {
                    val y = midiToY(m.toFloat())
                    val isC = ((m % 12) + 12) % 12 == 0
                    drawLine(
                        color = if (isC) Color(0x3894A3B8) else Color(0x28334155),
                        start = Offset(leftAxis, y),
                        end = Offset(w, y),
                        strokeWidth = if (isC) 2f else 1f
                    )
                    if (isC || m % 2 == 0) {
                        drawContext.canvas.nativeCanvas.drawText(
                            YinDetector.midiToNoteName(m.toFloat()),
                            leftAxis - 10f,
                            y + 8f,
                            labelPaint
                        )
                    }
                }

                val blockH = ((h - 60f) / midiSpan).coerceIn(22f, 38f)
                val minVisBeat = currentBeat - (playheadX - leftAxis) / pxPerBeat
                val maxVisBeat = currentBeat + (w - playheadX) / pxPerBeat

                // Hedef nota blokları
                for (n in notes) {
                    if (n.isRest || n.midi == null) continue
                    val endB = n.startBeat + n.durationBeats
                    if (endB < minVisBeat || n.startBeat > maxVisBeat) continue

                    val tMidi = n.midi + transposeOctaves * 12
                    val x = playheadX + (n.startBeat - currentBeat) * pxPerBeat
                    val bw = max(16f, n.durationBeats * pxPerBeat - 6f)
                    val y = midiToY(tMidi.toFloat()) - blockH / 2f
                    val active = isRecording && currentBeat >= n.startBeat && currentBeat < endB

                    val tolPx = (toleranceCents / 100f) * ((h - 60f) / midiSpan)
                    drawRect(
                        color = if (active) Color(0x2410B981) else Color(0x1438BDF8),
                        topLeft = Offset(x, midiToY(tMidi.toFloat()) - tolPx),
                        size = Size(bw, tolPx * 2f)
                    )

                    drawRoundRect(
                        color = if (active) Color(0x6010B981) else Color(0xB8334155),
                        topLeft = Offset(x, y),
                        size = Size(bw, blockH),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    drawRoundRect(
                        color = if (active) Color(0xFF34D399) else Color(0xFF64748B),
                        topLeft = Offset(x, y),
                        size = Size(bw, blockH),
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = if (active) 3f else 1.5f)
                    )

                    if (x + 10f >= leftAxis) {
                        drawContext.canvas.nativeCanvas.drawText(
                            YinDetector.midiToNoteName(tMidi.toFloat()),
                            x + 10f,
                            midiToY(tMidi.toFloat()) + 9f,
                            noteTextPaint
                        )
                    }
                }

                // Yüklenen Referans Ses Kaydının Perde Eğrisi (Kesikli Camgöbeği Rehber Çizgi)
                if (referenceContour.size > 1) {
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    for (i in 1 until referenceContour.size) {
                        val p = referenceContour[i - 1]
                        val c = referenceContour[i]
                        if (p.midiFloat == null || c.midiFloat == null) continue
                        if (c.timeSec - p.timeSec > 0.12f) continue

                        val beat1 = p.timeSec * (bpm / 60f)
                        val beat2 = c.timeSec * (bpm / 60f)
                        val x1 = playheadX + (beat1 - currentBeat) * pxPerBeat
                        val x2 = playheadX + (beat2 - currentBeat) * pxPerBeat
                        if (x2 < leftAxis || x1 > w) continue

                        drawLine(
                            color = Color(0xAA38BDF8),
                            start = Offset(x1, midiToY(p.midiFloat)),
                            end = Offset(x2, midiToY(c.midiFloat)),
                            strokeWidth = 4f,
                            pathEffect = dashEffect
                        )
                    }
                }

                // Kullanıcının Akan Ses Eğrisi (Pitch Curve)
                if (pitchHistory.size > 1) {
                    for (i in 1 until pitchHistory.size) {
                        val prev = pitchHistory[i - 1]
                        val curr = pitchHistory[i]
                        if (prev.midiFloat == null || curr.midiFloat == null) continue
                        if (curr.beat - prev.beat > 0.35f) continue

                        val x1 = playheadX + (prev.beat - currentBeat) * pxPerBeat
                        val y1 = midiToY(prev.midiFloat)
                        val x2 = playheadX + (curr.beat - currentBeat) * pxPerBeat
                        val y2 = midiToY(curr.midiFloat)
                        if (x2 < leftAxis || x1 > w) continue

                        val col = when (curr.status) {
                            PitchStatus.HIT -> Color(0xFF22C55E)
                            PitchStatus.NEAR -> Color(0xFFEAB308)
                            PitchStatus.MISS -> Color(0xFFEF4444)
                            else -> Color(0xFF64748B)
                        }
                        drawLine(col, Offset(x1, y1), Offset(x2, y2), strokeWidth = 6f, cap = StrokeCap.Round)
                    }
                }

                // Playhead çizgisi
                drawLine(Color(0xFF38BDF8), Offset(playheadX, 0f), Offset(playheadX, h), strokeWidth = 3f)
            }

            if (countInBeat != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC020617)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HAZIRLAN — METRONOM", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(countInBeat.toString(), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

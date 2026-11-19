package com.notauyumu.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notauyumu.core.Grade
import com.notauyumu.core.Pitch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val VISIBLE_SEC = 5.0
private const val PLAYHEAD = 0.28f

@Composable
fun PracticeScreen(vm: AppViewModel) {
    val s = vm.session ?: return
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(s) {
        while (true) { withFrameNanos { }; vm.tick() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            Column(Modifier.weight(1f)) {
                Text(s.line.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        vm.mode != Mode.SING -> "Dinliyorsun" + tempoText(s)
                        s.speakerMode -> "Hoparlör: kendi partin kapalı · kulaklık takarsan eşlik eder"
                        else -> "Söylüyorsun" + tempoText(s)
                    },
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
                )
            }
            FrameText(vm) { (if (s.score.fromRecording) "Bölüm " else "Ölçü ") + s.measureLabelAt(maxOf(s.now, s.from)) }
            Spacer(Modifier.width(16.dp))
        }
        FrameProgress(vm, s)
        PianoRoll(vm, s, Modifier.weight(1f).fillMaxWidth())
        Hud(vm, s)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            if (vm.mode == Mode.SING) Button(
                onClick = { vm.stopSession(abandon = false) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
            ) { Icon(Icons.Outlined.Flag, null); Spacer(Modifier.width(8.dp)); Text("Bitir ve değerlendir") }
            else Button(
                onClick = { vm.stopSession(abandon = true) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
            ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Durdur") }
        }
    }
}

private fun tempoText(s: SessionView) = if (s.score.tempoBpm > 0) " · ♩ = ${s.score.tempoBpm.roundToInt()}" else ""

@Composable
private fun FrameText(vm: AppViewModel, text: () -> String) {
    @Suppress("UNUSED_VARIABLE") val f = vm.frame
    Text(text(), style = MaterialTheme.typography.labelLarge, color = Palette.Amber)
}

@Composable
private fun FrameProgress(vm: AppViewModel, s: SessionView) {
    @Suppress("UNUSED_VARIABLE") val f = vm.frame
    LinearProgressIndicator(
        progress = { s.progress() },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = Palette.Amber, trackColor = Palette.Line, drawStopIndicator = {},
    )
}

@Composable
private fun PianoRoll(vm: AppViewModel, s: SessionView, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val lo = run { val l = s.line.lowestMidi - 3; val h = s.line.highestMidi + 3; if (h - l < 14) l - (14 - (h - l)) / 2 else l }
    val hi = maxOf(s.line.highestMidi + 3, lo + 14)
    Canvas(modifier.background(Palette.Ink)) {
        @Suppress("UNUSED_VARIABLE") val f = vm.frame // her karede yeniden çiz
        val now = s.now
        val pps = size.width / VISIBLE_SEC.toFloat()
        val px = size.width * PLAYHEAD
        val rows = hi - lo + 1
        val rowH = size.height / rows
        fun x(t: Double) = px + ((t - now) * pps).toFloat()
        fun y(m: Double) = size.height - ((m - lo + 0.5) * rowH).toFloat()
        val tStart = now - PLAYHEAD * VISIBLE_SEC
        val tEnd = now + (1 - PLAYHEAD) * VISIBLE_SEC

        // Satırlar: siyah tuşlar hafif koyu, her Do'da çizgi + etiket
        for (m in lo..hi) {
            val pc = Math.floorMod(m, 12)
            val top = y(m + 0.5)
            if (pc in intArrayOf(1, 3, 6, 8, 10)) drawRect(Palette.Surface.copy(alpha = 0.55f), Offset(0f, top), Size(size.width, rowH))
            if (pc == 0) {
                drawLine(Palette.Line, Offset(0f, top + rowH), Offset(size.width, top + rowH), 1f)
                label(tm, Pitch.name(m), Offset(6.dp.toPx(), top + rowH / 2), Palette.Muted, 10, center = false)
            }
        }
        // Ölçü çizgileri
        for (m in s.score.measures) {
            if (m.startSec < tStart - 1 || m.startSec > tEnd) continue
            val mx = x(m.startSec)
            drawLine(Palette.Line, Offset(mx, 0f), Offset(mx, size.height), 1.5f)
            label(tm, m.label, Offset(mx + 4.dp.toPx(), 10.dp.toPx()), Palette.Muted, 10, center = false)
        }
        // Çalışılan aralık dışını karart
        if (s.from > tStart) drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 0f), Size(x(s.from).coerceAtLeast(0f), size.height))
        if (s.until < tEnd) { val ux = x(s.until); drawRect(Color.Black.copy(alpha = 0.35f), Offset(ux, 0f), Size(size.width - ux, size.height)) }

        // Hedef notalar
        val radius = CornerRadius(6.dp.toPx())
        val barH = rowH * 0.78f
        for (n in s.line.notes) {
            if (n.endSec < tStart || n.startSec > tEnd) continue
            val left = x(n.startSec) + 1.5f
            val right = x(n.endSec) - 1.5f
            val cy = y(n.midi.toDouble())
            val active = now >= n.startSec && now < n.endSec
            val past = n.endSec <= now
            val color = when {
                active -> Palette.Amber
                past && vm.mode == Mode.SING -> s.noteAccuracy(n)?.let { Palette.accuracy(it).copy(alpha = 0.75f) } ?: Palette.Target.copy(alpha = 0.3f)
                past -> Palette.Target.copy(alpha = 0.3f)
                else -> Palette.Target.copy(alpha = 0.55f)
            }
            drawRoundRect(color, Offset(left, cy - barH / 2), Size((right - left).coerceAtLeast(2f), barH), radius)
            if (right - left > 30.dp.toPx() && barH > 11.dp.toPx())
                label(tm, Pitch.solfege(n.midi), Offset(left + 6.dp.toPx(), cy), if (active) Palette.Ink else Palette.Ink.copy(alpha = 0.8f), 11, center = false, bold = true)
            n.lyric?.let { label(tm, it, Offset(left + 2.dp.toPx(), cy + barH / 2 + 8.dp.toPx()), if (active) Palette.Text else Palette.Muted, 11, center = false) }
        }

        // Söylenen perde izi
        if (vm.mode == Mode.SING) {
            val stroke = 4.dp.toPx()
            var prev: LiveSample? = null
            for (p in s.live) {
                if (p.timeSec < tStart) { prev = p; continue }
                val pv = prev
                if (pv != null && pv.shownMidi != null && p.shownMidi != null && p.timeSec - pv.timeSec < 0.08) {
                    drawLine(
                        Palette.grade(p.grade), Offset(x(pv.timeSec), y(pv.shownMidi)), Offset(x(p.timeSec), y(p.shownMidi)),
                        stroke, StrokeCap.Round,
                    )
                }
                prev = p
            }
            val last = s.live.lastOrNull()
            val lastMidi = last?.shownMidi
            if (last != null && lastMidi != null && now - last.timeSec < 0.4) {
                val c = Offset(px, y(lastMidi))
                drawCircle(Palette.grade(last.grade).copy(alpha = 0.25f), 14.dp.toPx(), c)
                drawCircle(Palette.grade(last.grade), 6.dp.toPx(), c)
            }
        }
        // Okuma çizgisi
        drawLine(Palette.Amber.copy(alpha = 0.7f), Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())

        s.countdown()?.let { c ->
            drawRect(Color.Black.copy(alpha = 0.35f))
            label(tm, "$c", Offset(size.width / 2, size.height / 2), Palette.Amber, 72, center = true, bold = true)
            label(tm, if (vm.mode == Mode.SING) "Hazırlan…" else "Dinle…", Offset(size.width / 2, size.height / 2 + 60.dp.toPx()), Palette.Text, 16, center = true)
        }
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Int, center: Boolean, bold: Boolean = false) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center))
    val x = if (center) at.x - layout.size.width / 2 else at.x
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2))
}

@Composable
private fun Hud(vm: AppViewModel, s: SessionView) {
    @Suppress("UNUSED_VARIABLE") val f = vm.frame
    val target = s.targetsAt(s.now).firstOrNull()
    val sing = vm.mode == Mode.SING
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
            .clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(84.dp)) {
            Text("HEDEF", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Text(target?.let { Pitch.name(it.midi) } ?: "—", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text(target?.lyric ?: " ", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1)
        }
        if (sing) {
            Box(Modifier.weight(1f).height(56.dp).padding(horizontal = 8.dp)) { CentsMeter(s, target != null) }
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
                Text("SEN", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                val sung = s.lastSungMidi
                Text(sung?.let { Pitch.nearestName(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
                Text(s.liveCentsLabel()?.let { "$it cent" } ?: " ", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                "Partini dinle, sonra “Söyle” ile dene.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.End, modifier = Modifier.weight(2f),
            )
        }
    }
}

@Composable
private fun CentsMeter(s: SessionView, hasTarget: Boolean) {
    val tol = s.settings.toleranceCents.toFloat()
    val cents = s.lastCents
    Canvas(Modifier.fillMaxSize()) {
        val range = 100f
        val mid = size.width / 2
        fun cx(c: Float) = mid + (c.coerceIn(-range, range) / range) * (size.width / 2)
        val cy = size.height / 2
        drawLine(Palette.Line, Offset(0f, cy), Offset(size.width, cy), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Palette.Hit.copy(alpha = 0.45f), Offset(cx(-tol), cy), Offset(cx(tol), cy), 10.dp.toPx(), StrokeCap.Round)
        drawLine(Palette.Muted, Offset(mid, cy - 10.dp.toPx()), Offset(mid, cy + 10.dp.toPx()), 1.5.dp.toPx())
        if (cents != null && hasTarget) {
            val g = when { abs(cents) <= tol -> Grade.HIT; abs(cents) <= tol * 2 -> Grade.NEAR; else -> Grade.MISS }
            val nx = cx(cents.toFloat())
            drawLine(Palette.grade(g), Offset(nx, cy - 16.dp.toPx()), Offset(nx, cy + 16.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("pes", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Text("tiz", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

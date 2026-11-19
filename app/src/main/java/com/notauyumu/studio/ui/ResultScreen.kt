package com.notauyumu.studio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notauyumu.core.Scorer
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(vm: AppViewModel) {
    val r = vm.report ?: return
    val piece = vm.piece ?: return
    val pct = (r.overall * 100).roundToInt()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(r) { anim.animateTo(r.overall.toFloat(), tween(900)) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            Text(r.lineName, style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(200.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 14.dp.toPx()
                    val inset = w / 2
                    val sz = Size(size.width - w, size.height - w)
                    drawArc(Palette.Line, 135f, 270f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                    drawArc(Palette.accuracy(r.overall), 135f, 270f * anim.value, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%$pct", style = MaterialTheme.typography.displaySmall)
                    Text(verdict(r.overall), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
            }
            if (vm.newRecord) Text("Bu parti için yeni en iyi skorun!", color = Palette.Amber, style = MaterialTheme.typography.titleMedium)

            Insight(Scorer.tendencyText(r.tendencyCents))
            if (r.voicedRatio < 0.45) Insight("Mikrofon seni notaların yalnızca %${(r.voicedRatio * 100).roundToInt()}'inde duydu. Telefonu biraz yaklaştır ya da daha gür söyle.")

            if (r.measures.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("Ölçü ölçü")
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.measures.forEach { m ->
                            Column(
                                Modifier.width(46.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Palette.accuracy(m.accuracy).copy(alpha = 0.22f))
                                    .clickable { vm.practiceMeasures(m.measureIndex..m.measureIndex) }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(m.label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                                Text("${(m.accuracy * 100).roundToInt()}", style = MaterialTheme.typography.titleMedium, color = Palette.accuracy(m.accuracy))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Bir ölçüye dokunarak yalnızca onu çalışabilirsin.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
            }

            val weak = r.weakestMeasures()
            if (weak.isNotEmpty()) {
                val lo = weak.minOf { it.measureIndex }
                val hi = weak.maxOf { it.measureIndex }
                val label = if (lo == hi) "${weak.first().label}. ölçü" else "${piece.score.measures[lo - 1].label}–${piece.score.measures[hi - 1].label}. ölçüler"
                OutlinedButton(onClick = { vm.practiceMeasures(lo..hi) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Repeat, null); Spacer(Modifier.width(8.dp)); Text("Zorlandığın yeri çalış: $label")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { vm.startSession(Mode.SING) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.Ink),
        ) { Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(8.dp)); Text("Tekrar söyle", style = MaterialTheme.typography.labelLarge) }
    }
}

private fun verdict(a: Double) = when {
    a >= 0.9 -> "Harika"
    a >= 0.75 -> "Çok iyi"
    a >= 0.55 -> "Gelişiyor"
    else -> "Biraz daha çalışalım"
}

@Composable
private fun Insight(text: String?) {
    if (text == null) return
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).padding(14.dp),
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start,
    )
}

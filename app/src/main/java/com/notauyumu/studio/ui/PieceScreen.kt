package com.notauyumu.studio.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.notauyumu.core.VoiceLine
import kotlin.math.roundToInt

@Composable
fun PieceScreen(vm: AppViewModel) {
    val piece = vm.piece ?: return
    val score = piece.score
    val p = vm.prefs
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startSession(Mode.SING) else vm.message = "Söyleme modu için mikrofon izni gerekli."
    }
    fun sing() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startSession(Mode.SING)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            Spacer(Modifier.weight(1f))
            if (!piece.entry.isDemo) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "Sil", tint = Palette.Muted) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(score.title, style = MaterialTheme.typography.headlineSmall)
            val minutes = score.durationSec.roundToInt().let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
            val rec = score.fromRecording
            Text(
                (if (rec) listOfNotNull(score.composer, "${score.measures.size} bölüm", minutes)
                else listOfNotNull(score.composer, "${score.measures.size} ölçü", "♩ = ${score.tempoBpm.roundToInt()}", minutes)).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )

            if (rec) Text(
                "Bu partiler ses kaydından otomatik çıkarıldı. Bazı notalar eksik ya da hatalı olabilir; şüphelendiğin yerde orijinal kaydı dinle.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
            SectionLabel("Partini seç")
            score.lines.forEach { line ->
                LineRow(line, selected = line.id == vm.lineId, best = vm.best(line.id)) { vm.selectLine(line.id) }
            }

            SectionLabel("Çalışma")
            SettingCard {
                val bpm = (score.tempoBpm * p.tempoPercent / 100).roundToInt()
                LabeledValue("Tempo", if (rec) "%${p.tempoPercent}" else "%${p.tempoPercent}  ·  ♩ = $bpm")
                Slider(
                    value = p.tempoPercent.toFloat(), onValueChange = { vm.updatePrefs(p.copy(tempoPercent = (it / 5).roundToInt() * 5)) },
                    valueRange = 40f..130f, steps = 17, colors = sliderColors(),
                )
                if (score.measures.size > 1) {
                    val r = vm.range
                    val first = score.measures[r.first - 1].label
                    val last = score.measures[r.last - 1].label
                    LabeledValue(if (rec) "Bölümler" else "Ölçüler", if (r.first == 1 && r.last == score.measures.size) "Tümü ($first–$last)" else "$first – $last")
                    RangeSlider(
                        value = r.first.toFloat()..r.last.toFloat(),
                        onValueChange = { vm.selectRange(it.start.roundToInt()..it.endInclusive.roundToInt()) },
                        valueRange = 1f..score.measures.size.toFloat(),
                        steps = (score.measures.size - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(thumbColor = Palette.Amber, activeTrackColor = Palette.Amber, inactiveTrackColor = Palette.Line, activeTickColor = Palette.Ink.copy(alpha = 0.3f), inactiveTickColor = Palette.Line),
                    )
                }
                LabeledValue("Değerlendirme", "")
                val options = listOf(50 to "Rahat", 35 to "Normal", 20 to "Titiz")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (c, label) ->
                        SegmentedButton(
                            selected = p.toleranceCents == c,
                            onClick = { vm.updatePrefs(p.copy(toleranceCents = c)) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.Amber.copy(alpha = 0.18f), activeContentColor = Palette.Amber, inactiveContainerColor = Palette.Surface),
                        ) { Text("$label ±$c¢") }
                    }
                }
            }

            SectionLabel("Ses")
            SettingCard {
                LabeledValue("Kendi partin", gainLabel(p.ownGain))
                Slider(p.ownGain, { vm.updatePrefs(p.copy(ownGain = it)) }, colors = sliderColors())
                LabeledValue("Diğer partiler", gainLabel(p.othersGain))
                Slider(p.othersGain, { vm.updatePrefs(p.copy(othersGain = it)) }, colors = sliderColors())
                if (rec) {
                    LabeledValue("Orijinal kayıt", if (p.tempoPercent != 100) "yalnız %100 tempoda" else gainLabel(p.backingGain))
                    Slider(p.backingGain, { vm.updatePrefs(p.copy(backingGain = it)) }, colors = sliderColors(), enabled = p.tempoPercent == 100)
                } else SwitchRow("Metronom", p.metronome) { vm.updatePrefs(p.copy(metronome = it)) }
                SwitchRow("Oktav farkını hata sayma", p.forgiveOctave) { vm.updatePrefs(p.copy(forgiveOctave = it)) }
                TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Gelişmiş ayarları gizle" else "Gelişmiş ayarlar", color = Palette.Muted) }
                if (showAdvanced) {
                    LabeledValue("Gecikme telafisi", "${p.latencyMs} ms")
                    Slider(p.latencyMs.toFloat(), { vm.updatePrefs(p.copy(latencyMs = (it / 10).roundToInt() * 10)) }, valueRange = 0f..400f, colors = sliderColors())
                    Text(
                        "Sesin hep notadan geç başlıyor gibi görünüyorsa artır, erken görünüyorsa azalt. Bluetooth kulaklıkta genelde 250–350 ms gerekir.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Outlined.Headphones, null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Kablolu kulaklıkla çalışırsan uygulama yalnızca seni duyar; sonuçlar çok daha doğru olur.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.startSession(Mode.LISTEN) }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Dinle")
            }
            Button(
                onClick = { sing() }, modifier = Modifier.weight(1.4f).height(56.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.Ink),
            ) { Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(6.dp)); Text("Söyle", style = MaterialTheme.typography.labelLarge) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Eser silinsin mi?") },
        text = { Text("“${score.title}” ve kayıtlı skorları bu telefondan silinecek.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePiece(piece.entry) }) { Text("Sil", color = Palette.Miss) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
    )
}

private fun gainLabel(g: Float) = if (g <= 0.01f) "Kapalı" else "%${(g * 100).roundToInt()}"

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Palette.Amber, activeTrackColor = Palette.Amber, inactiveTrackColor = Palette.Line, activeTickColor = Palette.Ink.copy(alpha = 0.3f), inactiveTickColor = Palette.Line)

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.Amber)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Amber, checkedThumbColor = Palette.Ink))
    }
}

@Composable
private fun LineRow(line: VoiceLine, selected: Boolean, best: Double?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Palette.Amber.copy(alpha = 0.10f) else Palette.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Amber else Palette.Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.size(12.dp).clip(CircleShape)
                .background(if (selected) Palette.Amber else Palette.Line)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.titleMedium)
            Text("${line.rangeLabel}  ·  ${line.notes.size} nota", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        if (best != null) Column(horizontalAlignment = Alignment.End) {
            Text("%${(best * 100).roundToInt()}", style = MaterialTheme.typography.titleMedium, color = Palette.accuracy(best))
            Text("en iyi", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
    }
}

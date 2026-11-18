package com.notauyumu.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.notauyumu.studio.model.PartData
import com.notauyumu.studio.model.PerformanceSummary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreSummaryModal(
    summary: PerformanceSummary,
    parts: List<PartData>,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSwitchPart: (String) -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Performans Analiz Raporu", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "${summary.partName} Partisi — ${summary.hitNotesCount} / ${summary.totalTargetNotes} hedef nota isabetli",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )

            val scoreCol = when {
                summary.overallAccuracy >= 80 -> Color(0xFF34D399)
                summary.overallAccuracy >= 55 -> Color(0xFFFBBF24)
                else -> Color(0xFFF87171)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF020617))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("${summary.partName} Doğruluk", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Text("%${summary.overallAccuracy}", color = scoreCol, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                    summary.referenceMatchPercent?.let { refPct ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Referans Kayıt Uyumu", color = Color(0xFF7DD3FC), fontSize = 11.sp)
                            Text("%$refPct", color = Color(0xFF38BDF8), fontSize = 28.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(summary.tendencyLabel, color = Color(0xFFE2E8F0), fontSize = 12.sp)
            }

            Text("Ölçü ve Nota Bazlı Döküm", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 190.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(summary.noteBreakdown) { item ->
                    val barCol = when {
                        item.accuracyPercent >= 75 -> Color(0xFF22C55E)
                        item.accuracyPercent >= 45 -> Color(0xFFEAB308)
                        else -> Color(0xFFEF4444)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF020617))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ö${item.measure}  ${item.note}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFF1E293B))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(item.accuracyPercent / 100f)
                                    .height(6.dp)
                                    .background(barCol)
                            )
                        }
                        Text(
                            "%${item.accuracyPercent}",
                            color = barCol,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (parts.size > 1) {
                Text("Diğer Partiye Geç", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    parts.forEach { p ->
                        val isCur = p.id == summary.partId
                        OutlinedButton(onClick = { onSwitchPart(p.id) }, enabled = !isCur) {
                            Text(p.name, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.padding(end = 8.dp)) {
                    Text("Kapat")
                }
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399), contentColor = Color(0xFF020617))
                ) {
                    Text("Tekrar Dene", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

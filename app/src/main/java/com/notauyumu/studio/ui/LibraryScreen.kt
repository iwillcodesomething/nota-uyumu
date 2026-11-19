package com.notauyumu.studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notauyumu.studio.data.PieceEntry

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Nota Uyumu", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Text(
                "Partini seç, söyle; perdeni anında gör.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.Ink),
            ) {
                Icon(Icons.Outlined.FileOpen, null)
                Spacer(Modifier.width(10.dp))
                Text("Nota dosyası aç  ·  MusicXML", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("Eserlerin")
        }
        items(vm.entries, key = { it.id }) { e -> PieceCard(e) { vm.openPiece(e) } }
        item {
            Spacer(Modifier.height(8.dp))
            HelpCard()
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun PieceCard(e: PieceEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (e.isDemo) Palette.Amber.copy(alpha = 0.15f) else Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.LibraryMusic, null, tint = if (e.isDemo) Palette.Amber else Palette.Muted) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(e.composer, e.lineNames.joinToString(" · ").takeIf { it.isNotEmpty() && !e.isDemo })
            Text(sub.joinToString("  —  "), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.Muted)
    }
}

@Composable
private fun HelpCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface.copy(alpha = 0.6f)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lightbulb, null, tint = Palette.Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Nota dosyası nereden bulunur?", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Koro şefinden ya da MuseScore'dan eserin MusicXML (.mxl, .musicxml) hâlini isteyin. " +
                "MuseScore'da: Dosya → Dışa aktar → MusicXML. musescore.com'daki birçok koro eseri bu biçimde indirilebilir.\n\n" +
                "PDF notalar desteklenmez; resimden nota okumak güvenilir sonuç vermiyor.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
        )
    }
}

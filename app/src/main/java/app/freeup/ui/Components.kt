package app.freeup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freeup.engine.Quality

/** Small rounded label, e.g. "−63%" on a thumbnail. */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = content,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
}

/** Three-way quality picker drawn as cards, each with a one-line summary. */
@Composable
fun QualityPicker(selected: Quality, onSelect: (Quality) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q ->
                val isSelected = q == selected
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .border(
                            BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            shape,
                        )
                        .clickable(role = Role.RadioButton) { onSelect(q) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        q.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        q.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            selected.caveat,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/** Phone storage as a bar: used space, with the part videos take and what FreeUp could give back. */
@Composable
fun StorageBar(storage: StorageInfo, videoBytes: Long, savingBytes: Long, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    val total = storage.totalBytes.coerceAtLeast(1).toFloat()
    val used = (storage.totalBytes - storage.freeBytes).coerceAtLeast(0)
    val otherUsed = (used - videoBytes).coerceAtLeast(0)
    val videoKept = (videoBytes - savingBytes).coerceAtLeast(0)
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Segment(otherUsed / total, MaterialTheme.colorScheme.outline)
            Segment(videoKept / total, MaterialTheme.colorScheme.primary)
            Segment(savingBytes / total, accents.saving)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(MaterialTheme.colorScheme.primary, "Videos ${formatBytes(videoBytes)}")
            if (savingBytes > 0) Legend(accents.saving, "Can free ${formatBytes(savingBytes)}")
            Spacer(Modifier.weight(1f))
            Text(
                "${formatBytes(storage.freeBytes)} free",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(fraction: Float, color: Color) {
    if (fraction <= 0f) return
    Box(Modifier.fillMaxHeight().weight(fraction.coerceAtLeast(0.002f), fill = true).background(color))
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Amber note for things to know before compressing. */
@Composable
fun CautionCard(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accents.cautionContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = accents.caution, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = accents.onCautionContainer)
        }
        lines.forEach { line ->
            Row(Modifier.padding(top = 8.dp)) {
                Text("•", color = accents.onCautionContainer, modifier = Modifier.padding(end = 8.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = accents.onCautionContainer)
            }
        }
    }
}

/** Before and after sizes as two bars. */
@Composable
fun BeforeAfter(before: Long, after: Long, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    val ratio = if (before > 0) (after.toFloat() / before).coerceIn(0.02f, 1f) else 1f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SizeBar("Now", formatBytes(before), 1f, MaterialTheme.colorScheme.outline)
        SizeBar("After", "~${formatBytes(after)}", ratio, accents.saving)
    }
}

@Composable
private fun SizeBar(label: String, value: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        Box(Modifier.weight(1f).height(14.dp)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
        Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp).width(72.dp))
    }
}

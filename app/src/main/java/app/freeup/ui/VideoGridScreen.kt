package app.freeup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freeup.engine.Plan
import app.freeup.engine.Quality
import kotlin.math.roundToInt

private enum class Filter(val label: String) { WORTH("Can save"), ALL("All"), SKIPPED("Skipped") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGridScreen(
    state: UiState,
    onToggle: (Long) -> Unit,
    onCompressAnyway: (Long) -> Unit,
    onSelectTop: () -> Unit,
    onClear: () -> Unit,
    onReview: () -> Unit,
    onQuality: (Quality) -> Unit,
    onAllowMore: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var sheetFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val shown = when (filter) {
        Filter.WORTH -> state.rows.filter { it.compress != null }
        Filter.SKIPPED -> state.rows.filter { it.skip != null || it.forced }
        Filter.ALL -> state.rows
    }

    Scaffold(
        bottomBar = { if (state.selectedRows.isNotEmpty()) SelectionBar(state, onClear, onReview) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading && state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.statusBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header(state) }
            item(span = { GridItemSpan(maxLineSpan) }) { Hero(state, onSelectTop, onAllowMore) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Quality", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                    QualityPicker(state.quality, onQuality)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Filter.entries.forEach { f ->
                        val count = when (f) {
                            Filter.WORTH -> state.rows.count { it.compress != null }
                            Filter.SKIPPED -> state.rows.count { it.skip != null || it.forced }
                            Filter.ALL -> state.rows.size
                        }
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} · $count") })
                    }
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (filter == Filter.WORTH) "Nothing here at this quality. Try Smallest, or tap a skipped video to compress it anyway." else "No videos here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(shown, key = { it.video.id }) { row ->
                VideoCell(
                    row = row,
                    selected = row.video.id in state.selected && row.compress != null,
                    onClick = { if (row.compress != null && !row.forced) onToggle(row.video.id) else sheetFor = row.video.id },
                    onLongClick = { sheetFor = row.video.id },
                )
            }
        }
    }

    val sheetRow = state.rows.firstOrNull { it.video.id == sheetFor }
    if (sheetRow != null) {
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            VideoSheet(
                row = sheetRow,
                quality = state.quality,
                selected = sheetRow.video.id in state.selected,
                onToggle = { onToggle(sheetRow.video.id); sheetFor = null },
                onCompressAnyway = { onCompressAnyway(sheetRow.video.id); sheetFor = null },
                onClose = { sheetFor = null },
            )
        }
    }
}

@Composable
private fun Header(state: UiState) {
    Column(Modifier.padding(top = 12.dp)) {
        Text("FreeUp", style = MaterialTheme.typography.headlineLarge)
        Text(
            "${state.rows.size} videos on this phone",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.storage.totalBytes > 0) {
            Spacer(Modifier.height(14.dp))
            StorageBar(state.storage, state.totalSize, state.totalSaving)
        }
    }
}

@Composable
private fun Hero(state: UiState, onSelectTop: () -> Unit, onAllowMore: () -> Unit) {
    val accents = LocalAccents.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.access == Access.PARTIAL) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("FreeUp can only see the videos you picked.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onAllowMore) { Text("Change") }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(accents.heroStart, accents.heroEnd)))
                .padding(20.dp),
        ) {
            if (state.totalSaving > 0) {
                Text("You can free up", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                Text(formatBytes(state.totalSaving), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "from ${state.compressibleCount} ${if (state.compressibleCount == 1) "video" else "videos"} at ${state.quality.label}" +
                        if (state.freedSoFar > 0) " · ${formatBytes(state.freedSoFar)} freed so far" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSelectTop) { Text("Select the 10 biggest savings") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(accents.saving, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = accents.onSaving, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("All caught up", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (state.freedSoFar > 0) "FreeUp has freed ${formatBytes(state.freedSoFar)}. " else "") +
                        "Compressing the rest at ${state.quality.label} wouldn't save enough to be worth the quality loss. " +
                        "Pick Smallest for more, or tap any video to compress it anyway.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
            if (state.skipCounts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Skipped: " + state.skipCounts.joinToString(" · ") { (reason, n) -> "$n ${reason.label.lowercase()}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCell(row: VideoRow, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val accents = LocalAccents.current
    val compress = row.compress
    val shape = RoundedCornerShape(16.dp)
    val ring = when {
        selected && row.forced -> accents.caution
        selected -> MaterialTheme.colorScheme.primary
        else -> null
    }
    Column(Modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(if (ring != null) Modifier.border(BorderStroke(3.dp, ring), shape) else Modifier),
        ) {
            VideoThumbnail(row.video, Modifier.fillMaxSize().alpha(if (compress != null) 1f else 0.5f))
            Box(
                Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
            Text(
                formatDuration(row.video.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            Pill(formatBytes(row.video.sizeBytes), Color.Black.copy(alpha = 0.55f), Color.White, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (compress != null && row.video.sizeBytes > 0) {
                val pct = (row.saving * 100.0 / row.video.sizeBytes).roundToInt()
                Pill(
                    "−$pct%",
                    if (row.forced) accents.caution else accents.saving,
                    if (row.forced) Color.Black else accents.onSaving,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(ring!!, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (row.forced) Icons.Filled.Warning else Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = if (row.forced) Color.Black else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else if (compress == null) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp),
                )
            }
        }
        Text(
            text = when {
                row.forced -> "Anyway · saves ${formatBytes(row.saving)}"
                compress != null -> "saves ${formatBytes(row.saving)}"
                else -> row.skip!!.reason.label
            },
            style = MaterialTheme.typography.labelMedium,
            color = when {
                row.forced -> accents.caution
                compress != null -> accents.saving
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun VideoSheet(
    row: VideoRow,
    quality: Quality,
    selected: Boolean,
    onToggle: () -> Unit,
    onCompressAnyway: () -> Unit,
    onClose: () -> Unit,
) {
    val accents = LocalAccents.current
    val v = row.video
    val skip = row.skip
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(v, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(videoFacts(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))

        val plan = row.compress ?: row.anyway
        if (plan != null) {
            BeforeAfter(v.sizeBytes, plan.estimatedBytes)
            if (plan.maxShortSide != null) {
                Text(
                    "Resolution ${v.width}×${v.height} → ${plan.outWidth}×${plan.outHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        when {
            // A video FreeUp would compress on its own.
            row.compress != null && !row.forced -> {
                Text(quality.caveat, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) { Text(if (selected) "Don't compress" else "Select") }
            }
            // Already chosen with "Compress anyway".
            row.forced -> {
                val reason = row.compress!!.overridden!!
                CautionCard("You chose to compress this anyway", listOfNotNull(reason.caveat, quality.caveat))
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) { Text("Don't compress") }
            }
            // Skipped, but you may override.
            skip != null && row.anyway != null -> {
                Text("Why FreeUp skipped it", style = MaterialTheme.typography.titleSmall)
                Text(skip.reason.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                CautionCard("If you compress it anyway", listOfNotNull(skip.reason.caveat, quality.caveat, "The original is replaced and can't be restored."))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onCompressAnyway,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accents.caution, contentColor = Color.Black),
                ) { Text("Compress anyway · saves ~${formatBytes(v.sizeBytes - row.anyway.estimatedBytes)}") }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Keep as it is") }
            }
            // Can't be compressed at all.
            skip != null -> {
                Text(skip.reason.label, style = MaterialTheme.typography.titleSmall)
                Text(skip.reason.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("OK") }
            }
        }
    }
}

private fun videoFacts(row: VideoRow): String {
    val v = row.video
    val parts = mutableListOf("${v.width}×${v.height}")
    row.probe?.fps?.let { parts += "${it.roundToInt()} fps" }
    row.probe?.videoMime?.let { parts += codecName(it) }
    if (v.durationMs > 0) parts += "%.1f Mbps".format(v.sizeBytes * 8.0 / v.durationMs / 1000)
    parts += formatDuration(v.durationMs)
    if (row.probe?.isHdr == true) parts += "HDR"
    return parts.joinToString(" · ")
}

private fun codecName(mime: String) = when (mime) {
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/dolby-vision" -> "Dolby Vision"
    else -> mime.removePrefix("video/")
}

@Composable
private fun SelectionBar(state: UiState, onClear: () -> Unit, onReview: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${state.selectedRows.size} selected", style = MaterialTheme.typography.titleSmall)
                Text("frees ~${formatBytes(state.selectedSaving)}", style = MaterialTheme.typography.bodyMedium, color = LocalAccents.current.saving)
            }
            TextButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onReview) { Text("Review") }
        }
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    val accents = LocalAccents.current
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(accents.heroStart, accents.heroEnd))),
            )
            Spacer(Modifier.height(24.dp))
            Text("Keep every video.\nFree up the space.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "FreeUp compresses your videos on this phone so they take far less space and still look the same. Nothing is uploaded anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Allow access to videos") }
        }
    }
}

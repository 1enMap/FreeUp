package app.freeup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGridScreen(
    state: UiState,
    onToggle: (Long) -> Unit,
    onSelectTop: () -> Unit,
    onClear: () -> Unit,
    onReview: () -> Unit,
    onAllowMore: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("FreeUp") }, scrollBehavior = scroll) },
        bottomBar = {
            if (state.selectedRows.isNotEmpty()) SelectionBar(state, onClear, onReview)
        },
    ) { padding ->
        if (state.loading && state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Summary(state, onSelectTop, onAllowMore)
            }
            if (state.rows.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No videos found on this phone.",
                        modifier = Modifier.padding(vertical = 48.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.rows, key = { it.video.id }) { row ->
                VideoCell(row, selected = row.video.id in state.selected, onClick = { onToggle(row.video.id) })
            }
        }
    }
}

@Composable
private fun Summary(state: UiState, onSelectTop: () -> Unit, onAllowMore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        if (state.access == Access.PARTIAL) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "FreeUp can only see the videos you picked.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onAllowMore) { Text("Change") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "${state.rows.size} videos · ${formatBytes(state.totalSize)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    if (state.totalSaving > 0) "About ${formatBytes(state.totalSaving)} can be freed" else "Nothing worth compressing yet",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (state.totalSaving > 0) {
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = onSelectTop) { Text("Select the 10 biggest wins") }
                }
            }
        }
        Text(
            "Largest first. Tap videos to select them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VideoCell(row: VideoRow, selected: Boolean, onClick: () -> Unit) {
    val compress = row.compress
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .clip(shape)
            .then(if (compress != null) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (compress != null) 1f else 0.55f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape) else Modifier),
        ) {
            VideoThumbnail(row.video, Modifier.fillMaxSize())
            Badge(formatDuration(row.video.durationMs), Modifier.align(Alignment.BottomStart).padding(6.dp))
            Badge(formatBytes(row.video.sizeBytes), Modifier.align(Alignment.TopStart).padding(6.dp))
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            text = when (val p = row.plan) {
                is app.freeup.engine.Plan.Compress -> {
                    val pct = (row.saving * 100.0 / row.video.sizeBytes).roundToInt()
                    "−$pct% · saves ${formatBytes(row.saving)}"
                }
                is app.freeup.engine.Plan.Skip -> p.reason.label
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (compress != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun SelectionBar(state: UiState, onClear: () -> Unit, onReview: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${state.selectedRows.size} selected", style = MaterialTheme.typography.titleSmall)
                Text(
                    "saves ~${formatBytes(state.selectedSaving)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onReview) { Text("Review") }
        }
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Free up space without deleting your videos", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.size(12.dp))
            Text(
                "FreeUp compresses your videos on this phone so they take far less space and still look the same. Nothing is uploaded anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text("Allow access to videos") }
        }
    }
}

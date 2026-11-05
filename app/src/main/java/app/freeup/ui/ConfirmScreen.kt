package app.freeup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freeup.engine.Quality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    state: UiState,
    onBack: () -> Unit,
    onQuality: (Quality) -> Unit,
    onStart: () -> Unit,
) {
    val accents = LocalAccents.current
    val rows = state.selectedRows
    val after = state.selectedSize - state.selectedSaving
    val expectations = buildList {
        add(state.quality.caveat)
        state.selectedOverrides.forEach { (reason, n) ->
            reason.caveat?.let { add("${reason.label} ($n ${if (n == 1) "video" else "videos"}, compressed anyway): $it") }
        }
        add("Originals are replaced and can't be restored. Each video is checked first, and if anything looks wrong its original is kept.")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                    Button(onClick = onStart, enabled = rows.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Compress ${rows.size} ${if (rows.size == 1) "video" else "videos"}")
                    }
                    Text(
                        "Keeps going if you leave the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
                ) {
                    Text("You'll free about", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(state.selectedSaving), style = MaterialTheme.typography.displaySmall, color = accents.saving)
                    Spacer(Modifier.height(16.dp))
                    BeforeAfter(state.selectedSize, after)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.measuringSpeed) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Measuring your phone's speed…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("Takes ${formatEta(state.etaSeconds)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Text("Quality", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                QualityPicker(state.quality, onQuality)
            }
            item { CautionCard("What to expect", expectations) }
            item {
                Text("${rows.size} ${if (rows.size == 1) "video" else "videos"}", style = MaterialTheme.typography.titleMedium)
            }
            items(rows, key = { it.video.id }) { row ->
                val plan = row.compress!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VideoThumbnail(row.video, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(row.video.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(formatDuration(row.video.durationMs))
                                if (plan.maxShortSide != null) append(" · ${row.video.height.coerceAtMost(row.video.width)}p → ${plan.maxShortSide}p")
                                if (plan.toneMapHdr) append(" · HDR → SDR")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (plan.overridden != null) {
                            Pill("Compress anyway · ${plan.overridden.label}", accents.cautionContainer, accents.onCautionContainer, Modifier.padding(top = 4.dp))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatBytes(row.video.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("~${formatBytes(plan.estimatedBytes)}", style = MaterialTheme.typography.labelLarge, color = if (plan.overridden != null) accents.caution else accents.saving)
                    }
                }
            }
        }
    }
}

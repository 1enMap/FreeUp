package app.freeup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.freeup.data.JobItem
import app.freeup.data.JobState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(progress: BatchProgress, onStop: () -> Unit, onDone: () -> Unit) {
    val running = progress.running
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (running) "Compressing" else "Done") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (running) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                        Text(
                            "Stopping leaves the video being worked on untouched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                        )
                    } else {
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(
                            "Freed ${formatBytes(progress.freed)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            buildString {
                                append("${progress.finished} of ${progress.items.size} videos")
                                if (running && progress.etaSeconds != null) append(" · ${formatEta(progress.etaSeconds)} left")
                            },
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (running) {
                            Spacer(Modifier.size(12.dp))
                            LinearProgressIndicator(progress = { progress.overall }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            items(progress.items, key = { it.id }) { item ->
                ItemRow(item, if (item.id == progress.currentItemId) progress.currentFraction else null)
                HorizontalDivider(Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun ItemRow(item: JobItem, fraction: Float?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(statusText(item, fraction), style = MaterialTheme.typography.bodySmall, color = statusColor(item))
        }
        if (fraction != null && item.state == JobState.QUEUED) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (item.reason != null) {
            Text(
                item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun statusText(item: JobItem, fraction: Float?): String = when (item.state) {
    JobState.QUEUED -> if (fraction != null) "${(fraction * 100).roundToInt()}%" else "Waiting"
    JobState.ENCODED, JobState.WRITING -> "Saving…"
    JobState.DONE -> "${formatBytes(item.originalSize)} → ${formatBytes(item.newSize ?: 0)}"
    JobState.SKIPPED -> "Kept original"
    JobState.FAILED -> "Not compressed"
    JobState.CANCELLED -> "Stopped"
    else -> ""
}

@Composable
private fun statusColor(item: JobItem): Color = when (item.state) {
    JobState.DONE -> MaterialTheme.colorScheme.tertiary
    JobState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

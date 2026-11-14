package app.freeup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.freeup.data.JobItem
import app.freeup.data.JobState
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(progress: BatchProgress, onStop: () -> Unit, onDone: () -> Unit) {
    val accents = LocalAccents.current
    val running = progress.running
    Scaffold(
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                    if (running) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stop") }
                        Text(
                            "Stopping leaves the video being worked on untouched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                        )
                    } else {
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.statusBarsPadding(),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (running) "Compressing" else "Done", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(20.dp))
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (running) progress.overall else 1f },
                            modifier = Modifier.size(200.dp),
                            color = accents.saving,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeWidth = 14.dp,
                            strokeCap = StrokeCap.Round,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatBytes(progress.freed), style = MaterialTheme.typography.headlineMedium)
                            Text("freed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        buildString {
                            append("${progress.finished} of ${progress.items.size} videos")
                            if (running && progress.etaSeconds != null) append(" · ${formatEta(progress.etaSeconds)} left")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (running) {
                        Text(
                            "You can leave the app. Progress is in your notifications.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            items(progress.items, key = { it.id }) { item ->
                ItemRow(item, if (item.id == progress.currentItemId) progress.currentFraction else null)
            }
        }
    }
}

@Composable
private fun ItemRow(item: JobItem, fraction: Float?) {
    val accents = LocalAccents.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            VideoThumbnail(item.mediaId, item.uri.toUri(), Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            if (item.state == JobState.DONE) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).background(accents.saving, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, contentDescription = null, tint = accents.onSaving, modifier = Modifier.size(12.dp)) }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(statusText(item, fraction), style = MaterialTheme.typography.bodySmall, color = statusColor(item))
            if (fraction != null && item.state == JobState.QUEUED) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(50)),
                    color = accents.saving,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            if (item.reason != null) {
                Text(item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun statusText(item: JobItem, fraction: Float?): String = when (item.state) {
    JobState.QUEUED -> if (fraction != null) "Compressing · ${(fraction * 100).roundToInt()}%" else "Waiting · ${formatBytes(item.originalSize)}"
    JobState.ENCODED, JobState.WRITING -> "Saving…"
    JobState.DONE -> {
        val saved = item.originalSize - (item.newSize ?: item.originalSize)
        "${formatBytes(item.originalSize)} → ${formatBytes(item.newSize ?: 0)} · freed ${formatBytes(saved)}"
    }
    JobState.AWAITING_DELETE -> "MP4 saved · ${formatBytes(item.newSize ?: 0)}"
    JobState.SKIPPED -> "Kept the original"
    JobState.FAILED -> "Not compressed"
    JobState.CANCELLED -> "Stopped, original kept"
    else -> ""
}

@Composable
private fun statusColor(item: JobItem): Color = when (item.state) {
    JobState.DONE -> LocalAccents.current.saving
    JobState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

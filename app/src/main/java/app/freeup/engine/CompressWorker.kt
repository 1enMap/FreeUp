package app.freeup.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.freeup.FreeUpApp
import app.freeup.R
import app.freeup.data.AppDb
import app.freeup.data.JobItem
import app.freeup.data.JobState
import app.freeup.ui.MainActivity
import app.freeup.ui.formatBytes
import app.freeup.ui.formatEta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compresses every queued video, one at a time, in a foreground service so it keeps going after you
 * leave the app. Each file goes QUEUED → ENCODED (checked copy on disk) → WRITING → DONE, saved in
 * Room, so a killed process resumes without losing a video.
 */
class CompressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dao = AppDb.get(context).jobs()
    private val speed = SpeedModel(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Volatile private var currentFraction = 0f
    @Volatile private var status = "Starting…"

    override suspend fun doWork(): Result {
        runCatching { setForeground(foregroundInfo("Starting…", 0f, null)) }
        try {
            while (true) {
                val item = dao.nextActive() ?: break
                waitWhileHot()
                when (item.state) {
                    JobState.QUEUED -> encode(item)?.let { write(it, resumed = false) }
                    JobState.ENCODED -> write(item, resumed = false)
                    JobState.WRITING -> write(item, resumed = true)
                }
            }
        } catch (e: CancellationException) {
            // Android 11 can't tell why we were stopped; treat it as Stop, which is by far the usual cause.
            val stoppedByUser = Build.VERSION.SDK_INT < 31 || stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
            if (stoppedByUser) {
                withContext(NonCancellable) { cancelRemaining() }
            }
            throw e
        }
        notifyFinished()
        return Result.success()
    }

    private suspend fun encode(item: JobItem): JobItem? = coroutineScope {
        val queued = dao.allActive()
        val index = queued.indexOfFirst { it.id == item.id }
        val label = "${item.name} · ${index + 1} of ${queued.size}"

        // The batch write grant is lost if the app is force-stopped or the phone restarts. Check
        // before spending minutes encoding a video we wouldn't be allowed to replace.
        val canWrite = applicationContext.checkUriPermission(
            item.uri.toUri(), Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!canWrite) {
            dao.update(item.copy(state = JobState.FAILED, reason = "Permission expired, select it again", finishedAt = now()))
            return@coroutineScope null
        }

        val freeBytes = StatFs(applicationContext.noBackupFilesDir.path).availableBytes
        if (freeBytes < item.estimatedSize * 1.3 + 50L * 1024 * 1024) {
            dao.update(item.copy(state = JobState.SKIPPED, reason = "Not enough free space to work", finishedAt = now()))
            return@coroutineScope null
        }

        val work = File(applicationContext.noBackupFilesDir, "work/${item.id}.mp4")
        val started = SystemClock.elapsedRealtime()
        dao.update(item.copy(startedAt = System.currentTimeMillis()))
        currentFraction = 0f
        status = label

        // Push progress to the notification and the app once a second.
        val ticker = launch {
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                val eta = etaSeconds(queued.drop(index), currentFraction, elapsed)
                setProgress(workDataOf(KEY_ITEM to item.id, KEY_FRACTION to currentFraction, KEY_ETA to eta))
                runCatching { setForeground(foregroundInfo(status, currentFraction, eta)) }
                delay(1_000)
            }
        }

        try {
            val metadata = SourceMetadata(
                item.creationTimeMs ?: item.dateTakenMs,
                if (item.latitude != null && item.longitude != null) item.latitude to item.longitude else null,
            )
            val encodeOnce = suspend {
                Transcoder.transcode(
                    context = applicationContext,
                    input = item.uri.toUri(),
                    output = work,
                    videoMime = item.videoMime,
                    videoBitrate = item.targetBitrate,
                    metadata = metadata,
                    maxShortSide = item.maxShortSide,
                    toneMapHdr = item.toneMapHdr,
                    onProgress = { currentFraction = it },
                )
            }
            try {
                encodeOnce()
            } catch (e: EncoderStalledException) {
                // Give the hardware encoder a moment to be released, then start this video over.
                status = "$label · retrying"
                currentFraction = 0f
                delay(5_000)
                encodeOnce()
            }
        } catch (e: CancellationException) {
            work.delete()
            throw e
        } catch (e: Exception) {
            work.delete()
            val reason = if (e is EncoderStalledException) "The encoder got stuck, try again later" else "Couldn't compress this video"
            dao.update(item.copy(state = JobState.FAILED, reason = reason, finishedAt = now()))
            return@coroutineScope null
        } finally {
            ticker.cancel()
        }

        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        speed.record(SpeedModel.workOf(item.width, item.height, item.fps, item.durationMs) / seconds)

        val failure = Verifier.check(work, item.durationMs, item.originalSize)
        if (failure != null) {
            work.delete()
            dao.update(item.copy(state = JobState.SKIPPED, reason = failure, finishedAt = now()))
            return@coroutineScope null
        }
        item.copy(state = JobState.ENCODED, workFile = work.path, newSize = work.length()).also { dao.update(it) }
    }

    /** Never cancelled half-way: once the original is truncated, the new file must land. */
    private suspend fun write(item: JobItem, resumed: Boolean) = withContext(NonCancellable + Dispatchers.IO) {
        val file = item.workFile?.let(::File)
        if (file == null || !file.exists()) {
            if (resumed) {
                dao.update(item.copy(state = JobState.FAILED, reason = "Lost the compressed copy", finishedAt = now()))
            } else {
                dao.update(item.copy(state = JobState.QUEUED, workFile = null, newSize = null))
            }
            return@withContext
        }
        status = "Saving ${item.name}"
        runCatching { setForeground(foregroundInfo(status, 1f, null)) }
        val writing = item.copy(state = JobState.WRITING).also { dao.update(it) }
        val uri = item.uri.toUri()
        try {
            Replacer.replaceInPlace(applicationContext, uri, file, item.dateTakenMs)
            dao.update(writing.copy(state = JobState.DONE, newSize = file.length(), finishedAt = now()))
            file.delete()
        } catch (e: SecurityException) {
            if (resumed) {
                // The original may already be cut short and the grant is gone: keep the good copy.
                saveAsNew(writing, file)
            } else {
                // Nothing was written: the original is intact.
                dao.update(writing.copy(state = JobState.FAILED, reason = "Permission expired, select it again", finishedAt = now()))
                file.delete()
            }
        } catch (e: Exception) {
            val retried = runCatching { Replacer.replaceInPlace(applicationContext, uri, file, item.dateTakenMs) }
            if (retried.isSuccess) {
                dao.update(writing.copy(state = JobState.DONE, newSize = file.length(), finishedAt = now()))
                file.delete()
            } else {
                saveAsNew(writing, file)
            }
        }
    }

    private suspend fun saveAsNew(item: JobItem, file: File) {
        val saved = runCatching { Replacer.saveAsNew(applicationContext, file, item.name, item.dateTakenMs) }
        if (saved.isSuccess) {
            dao.update(item.copy(state = JobState.FAILED, reason = "Saved as a new video in Movies/FreeUp", finishedAt = now()))
            file.delete()
        } else {
            // Keep the file on disk; the next run tries again.
            dao.update(item.copy(state = JobState.WRITING))
        }
    }

    private suspend fun cancelRemaining() {
        dao.cancelQueued(now())
        for (item in dao.allActive().filter { it.state == JobState.ENCODED }) {
            item.workFile?.let { File(it).delete() }
            dao.update(item.copy(state = JobState.CANCELLED, workFile = null, finishedAt = now()))
        }
    }

    private suspend fun waitWhileHot() {
        val power = applicationContext.getSystemService(PowerManager::class.java)
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            status = "Phone is hot, pausing to cool down"
            runCatching { setForeground(foregroundInfo(status, currentFraction, null)) }
            delay(30_000)
        }
    }

    /** Remaining seconds: the current file from its live rate, the rest from the learned speed. */
    private fun etaSeconds(remaining: List<JobItem>, fraction: Float, elapsedSec: Double): Long {
        val current = remaining.firstOrNull() ?: return 0
        val modelCurrent = speed.secondsFor(current.width, current.height, current.fps, current.durationMs)
        val liveCurrent = if (fraction > 0.05f) elapsedSec / fraction else modelCurrent
        val scale = if (fraction > 0.05f && modelCurrent > 0) liveCurrent / modelCurrent else 1.0
        val rest = remaining.drop(1).sumOf { speed.secondsFor(it.width, it.height, it.fps, it.durationMs) } * scale
        return (liveCurrent * (1 - fraction) + rest).toLong().coerceAtLeast(0)
    }

    private fun foregroundInfo(text: String, fraction: Float, etaSec: Long?): ForegroundInfo {
        val content = buildString {
            append(text)
            if (etaSec != null && etaSec > 0) append(" · ").append(formatEta(etaSec)).append(" left")
        }
        val notification = NotificationCompat.Builder(applicationContext, FreeUpApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Compressing videos")
            .setContentText(content)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(NOTIFICATION_PROGRESS, notification, type)
    }

    private suspend fun notifyFinished() {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val batch = dao.latestBatch()
        val done = batch.filter { it.state == JobState.DONE }
        if (batch.isEmpty()) return
        val freed = done.sumOf { it.originalSize - (it.newSize ?: it.originalSize) }
        val text = if (done.isEmpty()) {
            "No videos were compressed"
        } else {
            "Freed ${formatBytes(freed)} from ${done.size} ${if (done.size == 1) "video" else "videos"}"
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, FreeUpApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Done")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notifications.notify(NOTIFICATION_DONE, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEY_ITEM = "item"
        const val KEY_FRACTION = "fraction"
        const val KEY_ETA = "eta"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_DONE = 2
    }
}

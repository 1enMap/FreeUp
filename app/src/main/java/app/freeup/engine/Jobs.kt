package app.freeup.engine

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.freeup.data.AppDb
import app.freeup.data.JobItem
import kotlinx.coroutines.flow.Flow

object Jobs {
    private const val WORK = "compress"

    suspend fun start(context: Context, items: List<JobItem>) {
        AppDb.get(context).jobs().insertAll(items)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            // A run that is already going picks the new items up; if it's just ending, this runs next.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CompressWorker>().build(),
        )
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)
}

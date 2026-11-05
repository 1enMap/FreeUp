package app.freeup.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Where each file is in the compress pipeline. Saved so a killed process can pick up safely. */
object JobState {
    const val QUEUED = "queued"

    /** Compressed copy written to [JobItem.workFile] and checked; original not touched yet. */
    const val ENCODED = "encoded"

    /** Overwriting the original. If we die here, the checked copy in [JobItem.workFile] is rewritten on restart. */
    const val WRITING = "writing"
    const val DONE = "done"
    const val SKIPPED = "skipped"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val ACTIVE = listOf(QUEUED, ENCODED, WRITING)
}

@Entity(tableName = "job_items", indices = [Index("batchId"), Index("mediaId")])
data class JobItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val position: Int,
    val mediaId: Long,
    val uri: String,
    val name: String,
    val originalSize: Long,
    val estimatedSize: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val targetBitrate: Int,
    val videoMime: String,
    /** Scale the short side down to this (720 = 720p), or null to keep the resolution. */
    val maxShortSide: Int? = null,
    /** Convert HDR to standard range: chosen with "Compress anyway" on an HDR video. */
    val toneMapHdr: Boolean = false,
    /** The skip reason you overrode with "Compress anyway", if any. */
    val overridden: String? = null,
    val dateTakenMs: Long?,
    val creationTimeMs: Long?,
    val latitude: Float?,
    val longitude: Float?,
    val state: String = JobState.QUEUED,
    val newSize: Long? = null,
    val reason: String? = null,
    val workFile: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/** A video this app already compressed, and the size it had afterwards. */
data class DoneRecord(val mediaId: Long, val newSize: Long)

@Dao
interface JobDao {
    @Insert
    suspend fun insertAll(items: List<JobItem>)

    @Update
    suspend fun update(item: JobItem)

    @Query("SELECT * FROM job_items WHERE batchId = :batchId ORDER BY position")
    fun observeBatch(batchId: Long): Flow<List<JobItem>>

    @Query("SELECT * FROM job_items WHERE batchId = (SELECT MAX(batchId) FROM job_items) ORDER BY position")
    suspend fun latestBatch(): List<JobItem>

    @Query("SELECT MAX(batchId) FROM job_items")
    fun observeLatestBatchId(): Flow<Long?>

    @Query("SELECT * FROM job_items WHERE state IN (:states) ORDER BY batchId, position LIMIT 1")
    suspend fun nextActive(states: List<String> = JobState.ACTIVE): JobItem?

    @Query("SELECT * FROM job_items WHERE state IN (:states) ORDER BY batchId, position")
    suspend fun allActive(states: List<String> = JobState.ACTIVE): List<JobItem>

    @Query("UPDATE job_items SET state = :cancelled, finishedAt = :now WHERE state = :queued")
    suspend fun cancelQueued(now: Long, cancelled: String = JobState.CANCELLED, queued: String = JobState.QUEUED)

    /** Space freed by every video FreeUp has replaced, as far as its history goes back. */
    @Query("SELECT COALESCE(SUM(originalSize - newSize), 0) FROM job_items WHERE state = :done AND newSize IS NOT NULL")
    fun observeFreed(done: String = JobState.DONE): Flow<Long>

    @Query("SELECT mediaId, newSize FROM job_items WHERE state = :done AND newSize IS NOT NULL")
    fun observeDone(done: String = JobState.DONE): Flow<List<DoneRecord>>
}

@Database(entities = [JobItem::class], version = 2)
abstract class AppDb : RoomDatabase() {
    abstract fun jobs(): JobDao

    companion object {
        @Volatile private var instance: AppDb? = null

        /** Adds the 720p, HDR and "Compress anyway" options; keeps the job history. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE job_items ADD COLUMN maxShortSide INTEGER")
                db.execSQL("ALTER TABLE job_items ADD COLUMN toneMapHdr INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE job_items ADD COLUMN overridden TEXT")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "freeup.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

package app.freeup.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(private val context: Context) {

    suspend fun loadVideos(): List<Video> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.ORIENTATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.CAPTURE_FRAMERATE,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val videos = mutableListOf<Video>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Video.Media.SIZE} > 0 AND ${MediaStore.Video.Media.IS_PENDING} = 0",
            null,
            "${MediaStore.Video.Media.SIZE} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val orientation = c.getColumnIndexOrThrow(MediaStore.Video.Media.ORIENTATION)
            val mime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val taken = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val bucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val captureFps = c.getColumnIndexOrThrow(MediaStore.Video.Media.CAPTURE_FRAMERATE)
            val relPath = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val videoId = c.getLong(id)
                val rotated = c.getInt(orientation) % 180 != 0
                val w = c.getInt(width)
                val h = c.getInt(height)
                videos += Video(
                    id = videoId,
                    uri = ContentUris.withAppendedId(collection, videoId),
                    name = c.getString(name) ?: "video",
                    sizeBytes = c.getLong(size),
                    durationMs = c.getLong(duration),
                    // Pixel count is what matters; the shown orientation doesn't change it.
                    width = if (rotated) h else w,
                    height = if (rotated) w else h,
                    mime = c.getString(mime),
                    dateTakenMs = if (c.isNull(taken)) null else c.getLong(taken),
                    bucket = c.getString(bucket),
                    relativePath = c.getString(relPath),
                    captureFps = if (c.isNull(captureFps)) null else c.getFloat(captureFps),
                )
            }
        }
        videos
    }
}

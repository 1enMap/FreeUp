package app.freeup.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

object Replacer {

    /**
     * Overwrites the original video at [uri] with [compressed], in place, so it keeps its MediaStore
     * entry, album and gallery position. Needs the write grant from MediaStore.createWriteRequest.
     * Reads the result back and compares hashes before returning; throws if anything differs.
     */
    fun replaceInPlace(context: Context, uri: Uri, compressed: File, dateTakenMs: Long?) {
        val resolver = context.contentResolver
        val expected = sha256(compressed.inputStream())
        val pfd = resolver.openFileDescriptor(uri, "rwt") ?: error("Couldn't open the original for writing")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                compressed.inputStream().use { input -> input.copyTo(out, 1 shl 20) }
                out.flush()
                out.fd.sync()
            }
        }
        val written = resolver.openInputStream(uri)?.let(::sha256) ?: error("Couldn't read back the new file")
        check(written.contentEquals(expected)) { "The written file doesn't match" }
        restoreDate(context, uri, dateTakenMs)
    }

    /**
     * Last resort when the write grant was lost after the original was already truncated: save the
     * checked compressed copy as a new video so nothing is lost.
     */
    fun saveAsNew(context: Context, compressed: File, displayName: String, dateTakenMs: Long?): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FreeUp")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            dateTakenMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Couldn't create a new video")
        resolver.openOutputStream(uri)!!.use { out -> compressed.inputStream().use { it.copyTo(out, 1 shl 20) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /**
     * Saves [compressed] as a new .mp4 next to the original (same folder, same name with .mp4),
     * checks it, and returns its uri. The original is not touched.
     */
    fun saveConverted(context: Context, compressed: File, originalName: String, relativePath: String?, dateTakenMs: Long?): Uri {
        val resolver = context.contentResolver
        val name = originalName.substringBeforeLast('.', originalName) + ".mp4"
        val path = relativePath?.takeIf { it.isNotBlank() } ?: "${Environment.DIRECTORY_MOVIES}/"
        // Android only lets each collection write to certain top-level folders.
        val collection = when {
            path.startsWith(Environment.DIRECTORY_DOWNLOADS) -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            path.startsWith(Environment.DIRECTORY_DCIM) || path.startsWith(Environment.DIRECTORY_MOVIES) ||
                path.startsWith(Environment.DIRECTORY_PICTURES) -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> null
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (collection != null) path else "${Environment.DIRECTORY_MOVIES}/FreeUp")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            dateTakenMs?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val target = collection ?: MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(target, values) ?: error("Couldn't create the MP4 file")
        try {
            val expected = sha256(compressed.inputStream())
            resolver.openOutputStream(uri)!!.use { out -> compressed.inputStream().use { it.copyTo(out, 1 shl 20) } }
            val written = resolver.openInputStream(uri)?.let(::sha256) ?: error("Couldn't read back the new file")
            check(written.contentEquals(expected)) { "The written file doesn't match" }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** True if [uri] is a finished file of exactly [expectedSize] bytes. */
    fun isComplete(context: Context, uri: Uri, expectedSize: Long): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize == expectedSize }
    }.getOrDefault(false)

    /** Deletes the original after a conversion. Throws SecurityException if Android wants you to confirm. */
    fun deleteOriginal(context: Context, uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    /** The media scanner re-reads the file after a write; put the original capture date back. */
    private fun restoreDate(context: Context, uri: Uri, dateTakenMs: Long?) {
        if (dateTakenMs == null) return
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.DATE_TAKEN, dateTakenMs) },
                null,
                null,
            )
        }
    }

    private fun sha256(input: InputStream): ByteArray = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest()
    }
}

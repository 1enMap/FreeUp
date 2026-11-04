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

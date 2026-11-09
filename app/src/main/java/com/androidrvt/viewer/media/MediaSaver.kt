package com.androidrvt.viewer.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves captures to the phone's gallery; Android 8-9 keep them in the app's own folder. */
class MediaSaver(private val context: Context) {
    val recordingDirectory: File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir

    /** Returns a description of where the snapshot went. */
    fun saveSnapshot(bitmap: Bitmap): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "RVT_$stamp.jpg"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val file = File(directory, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            return file.path
        }
        val location = "${Environment.DIRECTORY_PICTURES}/$ALBUM"
        writeToGallery(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/jpeg", location) { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        }
        return "$location/$name"
    }

    /** Returns a description of where the video went, or null if nothing was recorded. */
    fun publishRecording(file: File): String? {
        if (!file.exists() || file.length() < MIN_RECORDING_BYTES) {
            file.delete()
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return file.path

        val location = "${Environment.DIRECTORY_MOVIES}/$ALBUM"
        writeToGallery(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, file.name, "video/mp4", location) { stream ->
            file.inputStream().use { it.copyTo(stream) }
        }
        file.delete()
        return "$location/${file.name}"
    }

    private fun writeToGallery(
        collection: Uri,
        name: String,
        mimeType: String,
        location: String,
        write: (java.io.OutputStream) -> Unit,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, location)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Gallery refused the new file")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Could not open the gallery file")
            stream.use(write)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private companion object {
        const val ALBUM = "AndroidRvt"
        const val JPEG_QUALITY = 95
        const val MIN_RECORDING_BYTES = 1024L
    }
}

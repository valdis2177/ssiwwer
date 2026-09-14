package com.example.systemaudiorecorder

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

object RecordingRepository {
    private const val RELATIVE_DIR = "Music/SystemAudioRecorder/"

    fun load(context: Context): List<Recording> {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val out = mutableListOf<Recording>()
        resolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
            arrayOf(RELATIVE_DIR),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += Recording(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "Запись.m4a",
                    durationMs = c.getLong(durationCol),
                    sizeBytes = c.getLong(sizeCol),
                    dateAddedSeconds = c.getLong(dateCol)
                )
            }
        }
        return out
    }

    fun rename(context: Context, recording: Recording, requestedName: String): Boolean {
        val base = requestedName.trim().removeSuffix(".m4a").ifBlank { return false }
        val safe = base.replace(Regex("[\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.m4a")
        }
        return context.contentResolver.update(recording.uri, values, null, null) > 0
    }

    fun delete(context: Context, recording: Recording): Boolean =
        context.contentResolver.delete(recording.uri, null, null) > 0
}

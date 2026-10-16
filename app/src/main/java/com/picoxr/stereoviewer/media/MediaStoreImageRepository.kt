package com.picoxr.stereoviewer.media

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class MediaStoreImageRepository(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver

    fun observeChanges(): Flow<Unit> =
        callbackFlow {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            contentResolver.registerContentObserver(collection, true, observer)
            trySend(Unit)
            awaitClose { contentResolver.unregisterContentObserver(observer) }
        }

    suspend fun loadImages(limit: Int = DEFAULT_LIMIT): List<GalleryMedia> =
        withContext(Dispatchers.IO) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection =
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.MIME_TYPE,
                )
            val queryArgs =
                Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Images.Media.IS_PENDING} = 0",
                    )
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.Images.Media.DATE_ADDED} DESC",
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }

            buildList {
                contentResolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val pathColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateAddedColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val dateColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val mimeColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val width = cursor.getInt(widthColumn)
                        val height = cursor.getInt(heightColumn)
                        if (width <= 0 || height <= 0) continue

                        add(
                            GalleryMedia(
                                id = id,
                                uriString = ContentUris.withAppendedId(collection, id).toString(),
                                displayName = cursor.getString(nameColumn) ?: "Untitled",
                                width = width,
                                height = height,
                                relativePath = cursor.getString(pathColumn).orEmpty(),
                                sizeBytes = cursor.getLong(sizeColumn),
                                dateModifiedSeconds = cursor.getLong(dateColumn),
                                mimeType = cursor.getString(mimeColumn),
                                dateAddedSeconds = cursor.getLong(dateAddedColumn),
                            )
                        )
                    }
                }
            }.withGeneratedFisheyeNextToSource()
        }

    companion object {
        const val DEFAULT_LIMIT = 500
    }
}

package com.picoxr.stereoviewer.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryThumbnailLoader(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver
    private val cache =
        object : LruCache<String, Bitmap>(CACHE_SIZE_KB) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
        }

    suspend fun load(
        media: GalleryMedia,
        edgePx: Int = THUMBNAIL_EDGE_PX,
    ): Bitmap? {
        val cacheKey = "${media.id}:$edgePx"
        cache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                    contentResolver.loadThumbnail(
                        Uri.parse(media.uriString),
                        Size(edgePx, edgePx),
                        null,
                    )
                }
                .getOrNull()
                ?.also { cache.put(cacheKey, it) }
        }
    }

    companion object {
        private const val THUMBNAIL_EDGE_PX = 384
        const val PREVIEW_EDGE_PX = 1024
        private const val CACHE_SIZE_KB = 96 * 1024
    }
}

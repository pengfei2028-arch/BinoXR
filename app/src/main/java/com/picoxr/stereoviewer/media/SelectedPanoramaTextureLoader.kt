package com.picoxr.stereoviewer.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.resource.SurfaceRenderTexture
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_TEXTURE_EDGE_PX = 8192
private const val MAX_TEXTURE_PIXELS = 8192L * 4096L
private const val PACKED_TEXTURE_EDGE_PX = 6144
private const val MAX_PACKED_SOURCE_PIXELS = 8192L * 8192L
private const val EIGHT_K_CLASS_EDGE_PX = 7680
private const val LOG_TAG = "PicoStereoViewer"

data class PanoramaTexturePair(
    val left: TextureResource?,
    val right: TextureResource?,
    val sharesSingleTexture: Boolean,
    val isFullQuality: Boolean,
    val dimensionMode: VideoDimensionMode,
    val eyeOrderSwapped: Boolean,
    val surfaceRenderTexture: SurfaceRenderTexture? = null,
) {
    init {
        require(
            surfaceRenderTexture != null ||
                (left != null && right != null)
        ) {
            "A panorama requires either a surface or texture resources"
        }
    }
}

object SelectedPanoramaTextureLoader {
    private data class PreparedPanorama(
        val mediaId: Long,
        val textures: PanoramaTexturePair,
    )

    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prepared: PreparedPanorama? = null

    suspend fun prepare(
        context: Context,
        selection: MediaSelection,
    ) {
        mutex.withLock {
            if (prepared?.mediaId == selection.media.id) return@withLock
            prepared?.textures?.close()
            prepared = null
            prepared =
                PreparedPanorama(
                    mediaId = selection.media.id,
                    textures = loadFresh(context, selection),
                )
        }
    }

    suspend fun acquire(
        context: Context,
        selection: MediaSelection,
    ): PanoramaTexturePair =
        mutex.withLock {
            prepared?.takeIf { it.mediaId == selection.media.id }?.let {
                prepared = null
                return@withLock it.textures
            }
            prepared?.textures?.close()
            prepared = null
            loadFresh(context, selection)
        }

    suspend fun loadFullQuality(
        context: Context,
        selection: MediaSelection,
    ): PanoramaTexturePair =
        loadFresh(context, selection)

    suspend fun loadWithSwappedEyes(
        context: Context,
        selection: MediaSelection,
    ): PanoramaTexturePair =
        when (selection.classification.format) {
            GalleryMediaFormat.Stereo360TopBottom ->
                loadSplitTopBottom(context, selection.media, swapEyes = true)
            GalleryMediaFormat.Stereo180SideBySide ->
                loadSplitSideBySide(context, selection.media, swapEyes = true)
            else -> loadFresh(context, selection)
        }

    suspend fun discard(mediaId: Long? = null) {
        mutex.withLock {
            val cached = prepared ?: return@withLock
            if (mediaId == null || cached.mediaId == mediaId) {
                cached.textures.close()
                prepared = null
            }
        }
    }

    fun discardAsync(mediaId: Long? = null) {
        cleanupScope.launch {
            discard(mediaId)
        }
    }

    private suspend fun loadFresh(
        context: Context,
        selection: MediaSelection,
    ): PanoramaTexturePair =
        when (selection.classification.format) {
            GalleryMediaFormat.Stereo360TopBottom ->
                loadPacked(
                    context,
                    selection.media,
                    VideoDimensionMode.TOP_AND_DOWN,
                )
            GalleryMediaFormat.Stereo180SideBySide ->
                loadPacked(
                    context,
                    selection.media,
                    VideoDimensionMode.SIDE_BY_SIDE,
                )
            GalleryMediaFormat.Mono360,
            GalleryMediaFormat.Flat2D ->
                loadMono(context, selection.media)
            else ->
                error(
                    "Unsupported Full Space format: ${selection.classification.format.label}"
                )
        }

    private suspend fun loadSplitTopBottom(
        context: Context,
        media: GalleryMedia,
        swapEyes: Boolean,
    ): PanoramaTexturePair {
        val topBitmap =
            decodeRegion(
                context = context,
                media = media,
                region = Rect(0, 0, media.width, media.height / 2),
            )
        val leftTexture =
            try {
                TextureResource.create(topBitmap)
            } finally {
                topBitmap.recycle()
            }

        return try {
            val bottomBitmap =
                decodeRegion(
                    context = context,
                    media = media,
                    region = Rect(0, media.height / 2, media.width, media.height),
                )
            val rightTexture =
                try {
                    TextureResource.create(bottomBitmap)
                } finally {
                    bottomBitmap.recycle()
                }
            PanoramaTexturePair(
                left = if (swapEyes) rightTexture else leftTexture,
                right = if (swapEyes) leftTexture else rightTexture,
                sharesSingleTexture = false,
                isFullQuality = true,
                dimensionMode = VideoDimensionMode.MULTIPLE_VIEW,
                eyeOrderSwapped = swapEyes,
            )
        } catch (error: Throwable) {
            leftTexture.close()
            throw error
        }
    }

    private suspend fun loadMono(
        context: Context,
        media: GalleryMedia,
    ): PanoramaTexturePair {
        val startedAt = SystemClock.elapsedRealtime()
        val sampleSize = calculateTextureSampleSize(media.width, media.height)
        val bitmap =
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(media.uriString)).use { input ->
                    requireNotNull(input) { "Cannot open ${media.displayName}" }
                    requireNotNull(
                        BitmapFactory.decodeStream(
                            input,
                            null,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                if (sampleSize > 1) {
                                    inSampleSize = sampleSize
                                }
                            },
                        )
                    ) {
                        "Cannot decode ${media.displayName}"
                    }
                }
            }.asArgb8888()
        val decodedAt = SystemClock.elapsedRealtime()
        val texture =
            try {
                TextureResource.create(bitmap)
            } finally {
                bitmap.recycle()
            }
        Log.i(
            LOG_TAG,
            "Texture load mono id=${media.id} sample=$sampleSize " +
                "decodeMs=${decodedAt - startedAt} " +
                "uploadMs=${SystemClock.elapsedRealtime() - decodedAt}",
        )
        return PanoramaTexturePair(
            left = texture,
            right = texture,
            sharesSingleTexture = true,
            isFullQuality = true,
            dimensionMode = VideoDimensionMode.MONO,
            eyeOrderSwapped = false,
        )
    }

    private suspend fun loadPacked(
        context: Context,
        media: GalleryMedia,
        dimensionMode: VideoDimensionMode,
    ): PanoramaTexturePair {
        val startedAt = SystemClock.elapsedRealtime()
        val sampleSize = calculatePackedTextureSampleSize(media.width, media.height)
        val bitmap =
            decodeWholeImage(
                context = context,
                media = media,
                sampleSize = sampleSize,
                targetMaxEdge = PACKED_TEXTURE_EDGE_PX,
            )
        val decodedAt = SystemClock.elapsedRealtime()
        var surfaceRenderTexture: SurfaceRenderTexture? = null
        try {
            val surfaceStartedAt = SystemClock.elapsedRealtime()
            try {
                surfaceRenderTexture =
                    SurfaceRenderTexture(
                        width = bitmap.width,
                        height = bitmap.height,
                        maxBufferCount = 1,
                    )
                val surface =
                    requireNotNull(surfaceRenderTexture.acquireSurface()) {
                        "SurfaceRenderTexture did not provide a Surface"
                    }
                check(surface.isValid) { "SurfaceRenderTexture Surface is invalid" }
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                Log.i(
                    LOG_TAG,
                    "Texture load packed surface id=${media.id} mode=$dimensionMode " +
                        "sample=$sampleSize result=${bitmap.width}x${bitmap.height} " +
                        "decodeMs=${decodedAt - startedAt} " +
                        "drawSurfaceMs=${SystemClock.elapsedRealtime() - surfaceStartedAt}",
                )
                return PanoramaTexturePair(
                    left = null,
                    right = null,
                    sharesSingleTexture = true,
                    isFullQuality = true,
                    dimensionMode = dimensionMode,
                    eyeOrderSwapped = false,
                    surfaceRenderTexture = surfaceRenderTexture,
                )
            } catch (surfaceError: Throwable) {
                surfaceRenderTexture?.close()
                surfaceRenderTexture = null
                Log.w(
                    LOG_TAG,
                    "Packed surface path failed for id=${media.id}; " +
                        "falling back to TextureResource",
                    surfaceError,
                )
            }

            val fallbackStartedAt = SystemClock.elapsedRealtime()
            val texture = TextureResource.create(bitmap)
            Log.i(
                LOG_TAG,
                "Texture load packed fallback id=${media.id} mode=$dimensionMode " +
                    "sample=$sampleSize decodeMs=${decodedAt - startedAt} " +
                    "uploadMs=${SystemClock.elapsedRealtime() - fallbackStartedAt}",
            )
            return PanoramaTexturePair(
                left = texture,
                right = texture,
                sharesSingleTexture = true,
                isFullQuality = true,
                dimensionMode = dimensionMode,
                eyeOrderSwapped = false,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun decodeWholeImage(
        context: Context,
        media: GalleryMedia,
        sampleSize: Int,
        targetMaxEdge: Int? = null,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val sampledMaxEdge = maxOf(media.width, media.height).ceilDiv(sampleSize)
            context.contentResolver.openInputStream(Uri.parse(media.uriString)).use { input ->
                requireNotNull(input) { "Cannot open ${media.displayName}" }
                requireNotNull(
                        BitmapFactory.decodeStream(
                            input,
                            null,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                if (sampleSize > 1) {
                                    inSampleSize = sampleSize
                                }
                                if (targetMaxEdge != null && sampledMaxEdge > targetMaxEdge) {
                                    inScaled = true
                                    inDensity = sampledMaxEdge
                                    inTargetDensity = targetMaxEdge
                                }
                            },
                        )
                    ) {
                        "Cannot decode ${media.displayName}"
                    }
                    .asArgb8888()
            }
        }

    private suspend fun loadSplitSideBySide(
        context: Context,
        media: GalleryMedia,
        swapEyes: Boolean,
    ): PanoramaTexturePair {
        val leftBitmap =
            decodeRegion(
                context = context,
                media = media,
                region = Rect(0, 0, media.width / 2, media.height),
            )
        val leftTexture =
            try {
                TextureResource.create(leftBitmap)
            } finally {
                leftBitmap.recycle()
            }

        return try {
            val rightBitmap =
                decodeRegion(
                    context = context,
                    media = media,
                    region = Rect(media.width / 2, 0, media.width, media.height),
                )
            val rightTexture =
                try {
                    TextureResource.create(rightBitmap)
                } finally {
                    rightBitmap.recycle()
                }
            PanoramaTexturePair(
                left = if (swapEyes) rightTexture else leftTexture,
                right = if (swapEyes) leftTexture else rightTexture,
                sharesSingleTexture = false,
                isFullQuality = true,
                dimensionMode = VideoDimensionMode.MULTIPLE_VIEW,
                eyeOrderSwapped = swapEyes,
            )
        } catch (error: Throwable) {
            leftTexture.close()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun decodeRegion(
        context: Context,
        media: GalleryMedia,
        region: Rect,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val sampleSize = calculateTextureSampleSize(region.width(), region.height())
            val startedAt = SystemClock.elapsedRealtime()
            context.contentResolver.openInputStream(Uri.parse(media.uriString)).use { input ->
                requireNotNull(input) { "Cannot open ${media.displayName}" }
                val decoder =
                    requireNotNull(BitmapRegionDecoder.newInstance(input, false)) {
                        "Cannot create region decoder for ${media.displayName}"
                    }
                try {
                    requireNotNull(
                            decoder.decodeRegion(
                                region,
                                BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                    if (sampleSize > 1) {
                                        inSampleSize = sampleSize
                                    }
                                },
                            )
                        ) {
                            "Cannot decode ${media.displayName}"
                        }
                        .asArgb8888()
                        .also {
                            Log.i(
                                LOG_TAG,
                                "Texture decode region id=${media.id} " +
                                    "source=${region.width()}x${region.height()} " +
                                    "result=${it.width}x${it.height} sample=$sampleSize " +
                                    "decodeMs=${SystemClock.elapsedRealtime() - startedAt}",
                            )
                        }
                } finally {
                    decoder.recycle()
                }
            }
        }

    private fun Bitmap.asArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        val converted = copy(Bitmap.Config.ARGB_8888, false)
        recycle()
        return converted
    }

    private fun PanoramaTexturePair.close() {
        left?.close()
        if (!sharesSingleTexture) {
            right?.close()
        }
        surfaceRenderTexture?.close()
    }
}

internal fun calculateTextureSampleSize(
    width: Int,
    height: Int,
): Int {
    var sampleSize = 1
    while (
        width.ceilDiv(sampleSize) > MAX_TEXTURE_EDGE_PX ||
            height.ceilDiv(sampleSize) > MAX_TEXTURE_EDGE_PX ||
            width.toLong() * height.toLong() >
                MAX_TEXTURE_PIXELS * sampleSize.toLong() * sampleSize.toLong()
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun calculatePackedTextureSampleSize(
    width: Int,
    height: Int,
): Int {
    var sampleSize = 1
    while (
        width.ceilDiv(sampleSize) > MAX_TEXTURE_EDGE_PX ||
            height.ceilDiv(sampleSize) > MAX_TEXTURE_EDGE_PX ||
            width.toLong() * height.toLong() >
                MAX_PACKED_SOURCE_PIXELS * sampleSize.toLong() * sampleSize.toLong()
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

fun GalleryMedia.requiresHighResolutionLoading(): Boolean =
    maxOf(width, height) >= EIGHT_K_CLASS_EDGE_PX

private fun Int.ceilDiv(divisor: Int): Int =
    (this + divisor - 1) / divisor

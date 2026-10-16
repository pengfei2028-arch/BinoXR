package com.picoxr.stereoviewer.conversion

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.picoxr.stereoviewer.media.GalleryMedia
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object FisheyeConversionService {
    private const val SOURCE_MAX_EDGE = 2048
    private const val OUTPUT_ALGORITHM_VERSION = "PCV2"
    private val conversionMutex = Mutex()
    private val estimatorMutex = Mutex()
    private var estimator: DepthEstimator? = null

    suspend fun convertSingle(
        context: Context,
        media: GalleryMedia,
        eyeSize: Int = 2048,
        onStage: (ConversionStage) -> Unit = {},
    ): ConversionResult =
        withContext(Dispatchers.Default) {
            conversionMutex.withLock {
                coroutineScope {
                val startedAt = SystemClock.elapsedRealtime()
                var peakPssKb = Debug.getPss()
                val memorySampler =
                    launch(Dispatchers.Default) {
                        while (isActive) {
                            peakPssKb = max(peakPssKb, Debug.getPss())
                            delay(50)
                        }
                    }
                try {
                    val existing = findExistingOutput(context, media, eyeSize)
                    if (existing != null) {
                        return@coroutineScope ConversionResult(
                            media = existing,
                            convergence = Float.NaN,
                            eyeSize = eyeSize,
                            metrics =
                                ConversionMetrics(
                                    modelLoadMs = 0,
                                    decodeMs = 0,
                                    depthMs = 0,
                                    stereoAndProjectionMs = 0,
                                    saveAndVerifyMs = 0,
                                    totalMs = SystemClock.elapsedRealtime() - startedAt,
                                    sampledPeakPssKb = peakPssKb,
                                ),
                            reusedExistingOutput = true,
                        )
                    }

                    onStage(ConversionStage.LoadingModel)
                    val modelStartedAt = SystemClock.elapsedRealtime()
                    val depthEstimator =
                        estimatorMutex.withLock {
                            estimator ?: OnnxDepthEstimator.create(context).also { estimator = it }
                        }
                    val modelLoadMs = SystemClock.elapsedRealtime() - modelStartedAt

                    onStage(ConversionStage.Decoding)
                    val decodeStartedAt = SystemClock.elapsedRealtime()
                    val source = decodeSource(context, media)
                    val decodeMs = SystemClock.elapsedRealtime() - decodeStartedAt
                    try {
                        onStage(ConversionStage.EstimatingDepth)
                        val depthStartedAt = SystemClock.elapsedRealtime()
                        val depth = depthEstimator.estimate(source)
                        val depthMs = SystemClock.elapsedRealtime() - depthStartedAt

                        val renderStartedAt = SystemClock.elapsedRealtime()
                        val rendered =
                            StereoFisheyeRenderer.render(
                                source = source,
                                depth = depth,
                                eyeSize = eyeSize,
                                onStage = onStage,
                            )
                        val renderMs = SystemClock.elapsedRealtime() - renderStartedAt
                        try {
                            onStage(ConversionStage.Saving)
                            val saveStartedAt = SystemClock.elapsedRealtime()
                            val output =
                                saveAndVerify(
                                    context = context,
                                    source = media,
                                    bitmap = rendered.bitmap,
                                    eyeSize = eyeSize,
                                    onVerify = { onStage(ConversionStage.Verifying) },
                                )
                            val saveMs = SystemClock.elapsedRealtime() - saveStartedAt
                            return@coroutineScope ConversionResult(
                                media = output,
                                convergence = rendered.convergence,
                                eyeSize = eyeSize,
                                metrics =
                                    ConversionMetrics(
                                        modelLoadMs = modelLoadMs,
                                        decodeMs = decodeMs,
                                        depthMs = depthMs,
                                        stereoAndProjectionMs = renderMs,
                                        saveAndVerifyMs = saveMs,
                                        totalMs = SystemClock.elapsedRealtime() - startedAt,
                                        sampledPeakPssKb = peakPssKb,
                                    ),
                                reusedExistingOutput = false,
                            )
                        } finally {
                            rendered.bitmap.recycle()
                        }
                    } finally {
                        source.recycle()
                    }
                } finally {
                    memorySampler.cancel()
                }
                }
            }
        }

    private suspend fun decodeSource(
        context: Context,
        media: GalleryMedia,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(media.uriString)
            val orientation =
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot read ${media.displayName}" }
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            val sampleSize = calculateDecodeSampleSize(media.width, media.height)
            val decoded =
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot read ${media.displayName}" }
                    requireNotNull(
                        BitmapFactory.decodeStream(
                            input,
                            null,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inSampleSize = sampleSize
                            },
                        )
                    ) {
                        "Cannot decode ${media.displayName}"
                    }
                }
            val oriented = applyExifOrientation(decoded, orientation)
            val scale =
                minOf(
                    1f,
                    SOURCE_MAX_EDGE.toFloat() / max(oriented.width, oriented.height),
                )
            if (scale >= 1f) {
                oriented
            } else {
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * scale).toInt().coerceAtLeast(1),
                    (oriented.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also {
                    if (it !== oriented) oriented.recycle()
                }
            }
        }

    private fun calculateDecodeSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (max(width, height) / (sampleSize * 2) >= SOURCE_MAX_EDGE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(
        source: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix =
            Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        setRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    else -> return source
                }
            }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        ).also {
            if (it !== source) source.recycle()
        }
    }

    private suspend fun findExistingOutput(
        context: Context,
        source: GalleryMedia,
        eyeSize: Int,
    ): GalleryMedia? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val name = outputName(source, eyeSize)
            val relativePath = outputRelativePath(source)
            val projection =
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED,
                )
            val selection =
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Images.Media.IS_PENDING} = 0"
            resolver.query(
                collection,
                projection,
                selection,
                arrayOf(name, relativePath),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val id = cursor.getLong(0)
                GalleryMedia(
                    id = id,
                    uriString = ContentUris.withAppendedId(collection, id).toString(),
                    displayName = name,
                    width = eyeSize * 2,
                    height = eyeSize,
                    relativePath = relativePath,
                    sizeBytes = cursor.getLong(1),
                    dateAddedSeconds = cursor.getLong(2),
                    dateModifiedSeconds = cursor.getLong(3),
                    mimeType = "image/png",
                )
            }
        }

    private suspend fun saveAndVerify(
        context: Context,
        source: GalleryMedia,
        bitmap: Bitmap,
        eyeSize: Int,
        onVerify: () -> Unit,
    ): GalleryMedia =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val name = outputName(source, eyeSize)
            val relativePath = outputRelativePath(source)
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.DATE_TAKEN, source.dateModifiedSeconds * 1000L)
                }
            val uri =
                requireNotNull(resolver.insert(collection, values)) {
                    "Cannot create MediaStore output"
                }
            try {
                resolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "Cannot open output stream" }
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "PNG encoding failed"
                    }
                }

                onVerify()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot reopen generated image" }
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                check(bounds.outWidth == eyeSize * 2 && bounds.outHeight == eyeSize) {
                    "Generated image verification failed: ${bounds.outWidth}x${bounds.outHeight}"
                }

                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                val id = ContentUris.parseId(uri)
                GalleryMedia(
                    id = id,
                    uriString = uri.toString(),
                    displayName = name,
                    width = eyeSize * 2,
                    height = eyeSize,
                    relativePath = relativePath,
                    sizeBytes = querySize(context, uri),
                    dateModifiedSeconds = System.currentTimeMillis() / 1000L,
                    mimeType = "image/png",
                    dateAddedSeconds = source.dateAddedSeconds,
                )
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

    private fun querySize(
        context: Context,
        uri: Uri,
    ): Long =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L

    fun outputName(
        source: GalleryMedia,
        eyeSize: Int,
    ): String {
        val stem = source.displayName.substringBeforeLast('.', source.displayName)
        return "${stem}_BinoXR_FISHEYE_SBS180_${OUTPUT_ALGORITHM_VERSION}_${eyeSize}.png"
    }

    internal fun outputRelativePath(source: GalleryMedia): String {
        val normalized = source.relativePath.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return "Pictures/BinoXR/"

        val primaryDirectory = normalized.substringBefore('/')
        return if (
            primaryDirectory.equals("DCIM", ignoreCase = true) ||
                primaryDirectory.equals("Pictures", ignoreCase = true)
        ) {
            "$normalized/"
        } else {
            "Pictures/$normalized/"
        }
    }
}

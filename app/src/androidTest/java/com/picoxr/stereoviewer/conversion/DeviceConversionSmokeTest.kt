package com.picoxr.stereoviewer.conversion

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picoxr.stereoviewer.media.DetectionMode
import com.picoxr.stereoviewer.media.GalleryMedia
import com.picoxr.stereoviewer.media.GalleryMediaFormat
import com.picoxr.stereoviewer.media.MediaFormatClassifier
import com.picoxr.stereoviewer.media.MediaSelection
import com.picoxr.stereoviewer.media.SelectedPanoramaTextureLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceConversionSmokeTest {
    @Test
    fun convertSingleImageTo1024FisheyeSbs() {
        runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        val testStem = "${TEST_INPUT_STEM}_${System.currentTimeMillis()}"
        val source = createTestInput(context, "$testStem.png")

        val result =
            FisheyeConversionService.convertSingle(
                context = context,
                media = source,
                eyeSize = 1024,
                onStage = { Log.i(BENCHMARK_TAG, "stage=${it.name}") },
            )

        assertEquals(2048, result.media.width)
        assertEquals(1024, result.media.height)
        assertEquals(
            GalleryMediaFormat.Stereo180SideBySide,
            MediaFormatClassifier.classify(result.media, DetectionMode.Smart).format,
        )
        assertTrue(result.media.sizeBytes > 0L)
        verifyViewerTexturePath(context, result.media)
        Log.i(
            BENCHMARK_TAG,
            "result=1024 totalMs=${result.metrics.totalMs} " +
                "modelMs=${result.metrics.modelLoadMs} decodeMs=${result.metrics.decodeMs} " +
                "depthMs=${result.metrics.depthMs} " +
                "renderMs=${result.metrics.stereoAndProjectionMs} " +
                "saveMs=${result.metrics.saveAndVerifyMs} " +
                "peakPssKb=${result.metrics.sampledPeakPssKb} " +
                "convergence=${result.convergence} uri=${result.media.uriString}",
        )

        val result2048 =
            FisheyeConversionService.convertSingle(
                context = context,
                media = source,
                eyeSize = 2048,
                onStage = { Log.i(BENCHMARK_TAG, "stage2048=${it.name}") },
            )
        assertEquals(4096, result2048.media.width)
        assertEquals(2048, result2048.media.height)
        assertTrue(result2048.media.sizeBytes > 0L)
        verifyViewerTexturePath(context, result2048.media)
        Log.i(
            BENCHMARK_TAG,
            "result=2048 totalMs=${result2048.metrics.totalMs} " +
                "modelMs=${result2048.metrics.modelLoadMs} " +
                "decodeMs=${result2048.metrics.decodeMs} " +
                "depthMs=${result2048.metrics.depthMs} " +
                "renderMs=${result2048.metrics.stereoAndProjectionMs} " +
                "saveMs=${result2048.metrics.saveAndVerifyMs} " +
                "peakPssKb=${result2048.metrics.sampledPeakPssKb} " +
                "convergence=${result2048.convergence} uri=${result2048.media.uriString}",
        )
        }

    }

    private suspend fun verifyViewerTexturePath(
        context: Context,
        media: GalleryMedia,
    ) {
        val classification = MediaFormatClassifier.classify(media, DetectionMode.Smart)
        val selection =
            MediaSelection(
                media = media,
                classification = classification,
                folderMedia = listOf(media),
                detectionMode = DetectionMode.Smart,
            )
        SelectedPanoramaTextureLoader.prepare(context, selection)
        SelectedPanoramaTextureLoader.discard(media.id)
        Log.i(
            BENCHMARK_TAG,
            "viewerReady=${media.width}x${media.height} " +
                "format=${classification.format.name}",
        )
    }

    private fun createTestInput(
        context: Context,
        displayName: String,
    ): GalleryMedia {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val uri =
            requireNotNull(
                resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BinoXRTest/")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    },
                )
            )
        try {
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(24, 38, 62))
            paint.color = Color.rgb(235, 84, 72)
            canvas.drawCircle(420f, 350f, 220f, paint)
            paint.color = Color.rgb(58, 186, 132)
            canvas.drawRect(680f, 150f, 1120f, 610f, paint)
            paint.color = Color.WHITE
            paint.textSize = 92f
            canvas.drawText("BinoXR", 480f, 660f, paint)
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return requireNotNull(findMedia(context, displayName))
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun findMedia(
        context: Context,
        displayName: String,
    ): GalleryMedia? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return context.contentResolver.query(
            collection,
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
            ),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            GalleryMedia(
                id = id,
                uriString = ContentUris.withAppendedId(collection, id).toString(),
                displayName = cursor.getString(1),
                width = cursor.getInt(2),
                height = cursor.getInt(3),
                relativePath = cursor.getString(4).orEmpty(),
                sizeBytes = cursor.getLong(5),
                dateAddedSeconds = cursor.getLong(6),
                dateModifiedSeconds = cursor.getLong(7),
                mimeType = cursor.getString(8),
            )
        }
    }

    private companion object {
        const val TEST_INPUT_STEM = "bino_test_input"
        const val BENCHMARK_TAG = "BinoXRBenchmark"
    }
}

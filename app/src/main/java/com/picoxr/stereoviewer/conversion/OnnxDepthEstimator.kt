package com.picoxr.stereoviewer.conversion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val LOG_TAG = "BinoXRConversion"
private const val MODEL_ASSET = "models/depth-anything-v2-small-fp16.onnx"
private const val MODEL_BYTES = 49_642_442L
private const val TARGET_LONG_SIDE = 518
private const val PATCH_SIZE = 14

class OnnxDepthEstimator private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : DepthEstimator {
    private val inputName = session.inputNames.first()
    private val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
    private val fixedHeight =
        inputInfo?.shape?.getOrNull(2)?.takeIf { it > 0L }?.toInt()
    private val fixedWidth =
        inputInfo?.shape?.getOrNull(3)?.takeIf { it > 0L }?.toInt()

    override fun estimate(source: Bitmap): DepthMap {
        val inputHeight: Int
        val inputWidth: Int
        if (fixedHeight != null && fixedWidth != null) {
            inputHeight = fixedHeight
            inputWidth = fixedWidth
        } else {
            val scale = TARGET_LONG_SIDE.toFloat() / max(source.width, source.height)
            inputHeight =
                max(
                    PATCH_SIZE,
                    (source.height * scale / PATCH_SIZE).roundToInt() * PATCH_SIZE,
                )
            inputWidth =
                max(
                    PATCH_SIZE,
                    (source.width * scale / PATCH_SIZE).roundToInt() * PATCH_SIZE,
                )
        }

        val resized =
            Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (resized !== source) resized.recycle()

        val planeSize = inputWidth * inputHeight
        val input = FloatArray(planeSize * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 0xFF) / 255f
            val green = ((pixel shr 8) and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            input[index] = (red - 0.485f) / 0.229f
            input[planeSize + index] = (green - 0.456f) / 0.224f
            input[planeSize * 2 + index] = (blue - 0.406f) / 0.225f
        }

        val inferred =
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()),
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { outputs ->
                    val output = outputs[0] as OnnxTensor
                    val shape = output.info.shape
                    val outputHeight = shape[shape.size - 2].toInt()
                    val outputWidth = shape[shape.size - 1].toInt()
                    val values = FloatArray(outputHeight * outputWidth)
                    output.floatBuffer.get(values)
                    DepthMap(outputWidth, outputHeight, normalizeAndSmooth(values, outputWidth, outputHeight))
                }
            }
        return resizeDepth(inferred, source.width, source.height)
    }

    override fun close() {
        session.close()
    }

    companion object {
        fun create(context: Context): OnnxDepthEstimator {
            val startedAt = SystemClock.elapsedRealtime()
            val model = materializeModel(context.applicationContext)
            val environment = OrtEnvironment.getEnvironment()
            val options =
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(4)
                    setInterOpNumThreads(1)
                }
            val session = environment.createSession(model.absolutePath, options)
            val estimator = OnnxDepthEstimator(environment, session)
            Log.i(
                LOG_TAG,
                "DAv2 Small loaded in ${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "input=${estimator.inputInfo?.shape?.contentToString()}",
            )
            return estimator
        }

        private fun materializeModel(context: Context): File {
            val directory = File(context.filesDir, "models").apply { mkdirs() }
            val target = File(directory, MODEL_ASSET.substringAfterLast('/'))
            if (target.length() == MODEL_BYTES) return target

            val pending = File(directory, "${target.name}.pending")
            context.assets.open(MODEL_ASSET).use { input ->
                pending.outputStream().buffered().use(input::copyTo)
            }
            check(pending.length() == MODEL_BYTES) {
                "Depth model size mismatch: ${pending.length()}"
            }
            if (target.exists()) check(target.delete()) { "Cannot replace stale depth model" }
            check(pending.renameTo(target)) { "Cannot commit depth model" }
            return target
        }
    }
}

private fun normalizeAndSmooth(
    values: FloatArray,
    width: Int,
    height: Int,
): FloatArray {
    val sorted = values.copyOf().apply { sort() }
    val low = sorted[(sorted.lastIndex * 0.01f).roundToInt()]
    val high = sorted[(sorted.lastIndex * 0.99f).roundToInt()]
    if (high - low < 1e-6f) return FloatArray(values.size)

    val normalized =
        FloatArray(values.size) { index ->
            ((values[index] - low) / (high - low)).coerceIn(0f, 1f)
        }
    val smoothed = FloatArray(values.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val center = normalized[y * width + x]
            var weighted = 0f
            var totalWeight = 0f
            for (dy in -2..2) {
                val sampleY = (y + dy).coerceIn(0, height - 1)
                for (dx in -2..2) {
                    val sampleX = (x + dx).coerceIn(0, width - 1)
                    val sample = normalized[sampleY * width + sampleX]
                    val spatialWeight = if (dx == 0 && dy == 0) 4f else 1f
                    val rangeWeight = (1f - kotlin.math.abs(sample - center) / 0.12f)
                        .coerceIn(0f, 1f)
                    val weight = spatialWeight * rangeWeight
                    weighted += sample * weight
                    totalWeight += weight
                }
            }
            smoothed[y * width + x] =
                if (totalWeight > 0f) weighted / totalWeight else center
        }
    }
    return smoothed
}

private fun resizeDepth(
    source: DepthMap,
    targetWidth: Int,
    targetHeight: Int,
): DepthMap {
    if (source.width == targetWidth && source.height == targetHeight) return source
    val output = FloatArray(targetWidth * targetHeight)
    val scaleX = source.width.toFloat() / targetWidth
    val scaleY = source.height.toFloat() / targetHeight
    for (y in 0 until targetHeight) {
        val sourceY = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, source.height - 1f)
        val y0 = floor(sourceY).toInt()
        val y1 = (y0 + 1).coerceAtMost(source.height - 1)
        val fy = sourceY - y0
        for (x in 0 until targetWidth) {
            val sourceX = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, source.width - 1f)
            val x0 = floor(sourceX).toInt()
            val x1 = (x0 + 1).coerceAtMost(source.width - 1)
            val fx = sourceX - x0
            val top =
                source.values[y0 * source.width + x0] * (1f - fx) +
                    source.values[y0 * source.width + x1] * fx
            val bottom =
                source.values[y1 * source.width + x0] * (1f - fx) +
                    source.values[y1 * source.width + x1] * fx
            output[y * targetWidth + x] = top * (1f - fy) + bottom * fy
        }
    }
    return DepthMap(targetWidth, targetHeight, output)
}

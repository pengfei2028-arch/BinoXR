package com.picoxr.stereoviewer.conversion

import android.graphics.Bitmap
import com.picoxr.stereoviewer.media.GalleryMedia

enum class ConversionStage(val label: String) {
    LoadingModel("Loading depth model"),
    Decoding("Decoding source"),
    EstimatingDepth("Estimating depth"),
    GeneratingLeftEye("Generating left eye"),
    ProjectingLeftEye("Projecting left fisheye"),
    GeneratingRightEye("Generating right eye"),
    ProjectingRightEye("Projecting right fisheye"),
    Saving("Saving result"),
    Verifying("Verifying result"),
}

data class DepthMap(
    val width: Int,
    val height: Int,
    val values: FloatArray,
) {
    init {
        require(values.size == width * height)
    }
}

interface DepthEstimator : AutoCloseable {
    fun estimate(source: Bitmap): DepthMap
}

data class ConversionMetrics(
    val modelLoadMs: Long,
    val decodeMs: Long,
    val depthMs: Long,
    val stereoAndProjectionMs: Long,
    val saveAndVerifyMs: Long,
    val totalMs: Long,
    val sampledPeakPssKb: Long,
    val maxUiFrameGapMs: Long = 0L,
    val uiFramesOver33Ms: Int = 0,
)

data class ConversionResult(
    val media: GalleryMedia,
    val convergence: Float,
    val eyeSize: Int,
    val metrics: ConversionMetrics,
    val reusedExistingOutput: Boolean,
)

package com.picoxr.stereoviewer.media

import kotlin.math.abs

enum class ProjectionType {
    Equirect360,
    Fisheye180,
    Unknown,
}

enum class StereoLayout {
    TopBottom,
    SideBySide,
    Unknown,
}

enum class Eye {
    Left,
    Right,
}

data class StereoFormat(
    val projectionType: ProjectionType,
    val stereoLayout: StereoLayout,
) {
    companion object {
        val Equirect360TopBottom =
            StereoFormat(ProjectionType.Equirect360, StereoLayout.TopBottom)
        val Fisheye180SideBySide =
            StereoFormat(ProjectionType.Fisheye180, StereoLayout.SideBySide)
        val Unknown = StereoFormat(ProjectionType.Unknown, StereoLayout.Unknown)
    }
}

data class StereoImageDescriptor(
    val id: String,
    val sourceUri: String,
    val width: Int,
    val height: Int,
    val metadataFormat: StereoFormat? = null,
    val userFormatOverride: StereoFormat? = null,
    val swapEyes: Boolean = false,
) {
    val resolvedFormat: StereoFormat
        get() =
            userFormatOverride
                ?: metadataFormat
                ?: StereoFormatDetector.detect(width = width, height = height)
}

object StereoFormatDetector {
    const val DEFAULT_RELATIVE_TOLERANCE = 0.08

    fun detect(
        width: Int,
        height: Int,
        relativeTolerance: Double = DEFAULT_RELATIVE_TOLERANCE,
    ): StereoFormat {
        require(width > 0 && height > 0) { "Image dimensions must be positive." }
        require(relativeTolerance in 0.0..<1.0) {
            "Relative tolerance must be in the range [0, 1)."
        }

        val aspectRatio = width.toDouble() / height.toDouble()
        return when {
            aspectRatio.isNear(target = 1.0, relativeTolerance) ->
                StereoFormat.Equirect360TopBottom
            aspectRatio.isNear(target = 2.0, relativeTolerance) ->
                StereoFormat.Fisheye180SideBySide
            else -> StereoFormat.Unknown
        }
    }

    private fun Double.isNear(target: Double, relativeTolerance: Double): Boolean =
        abs(this - target) / target <= relativeTolerance
}

data class NormalizedImageRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

object StereoSourceRegionMapper {
    fun regionFor(
        layout: StereoLayout,
        outputEye: Eye,
        swapEyes: Boolean,
    ): NormalizedImageRegion {
        val sourceEye = if (swapEyes) outputEye.opposite() else outputEye
        return when (layout) {
            StereoLayout.TopBottom ->
                if (sourceEye == Eye.Left) {
                    NormalizedImageRegion(x = 0f, y = 0f, width = 1f, height = 0.5f)
                } else {
                    NormalizedImageRegion(x = 0f, y = 0.5f, width = 1f, height = 0.5f)
                }
            StereoLayout.SideBySide ->
                if (sourceEye == Eye.Left) {
                    NormalizedImageRegion(x = 0f, y = 0f, width = 0.5f, height = 1f)
                } else {
                    NormalizedImageRegion(x = 0.5f, y = 0f, width = 0.5f, height = 1f)
                }
            StereoLayout.Unknown ->
                error("Cannot map an unknown stereo layout.")
        }
    }

    private fun Eye.opposite(): Eye = if (this == Eye.Left) Eye.Right else Eye.Left
}

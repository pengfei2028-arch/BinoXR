package com.picoxr.stereoviewer.media

import kotlin.math.abs

data class GalleryMedia(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val relativePath: String,
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    val mimeType: String?,
    val dateAddedSeconds: Long = dateModifiedSeconds,
)

enum class DetectionMode {
    Smart,
    StrictVr,
}

enum class GalleryMediaFormat(val label: String) {
    Stereo360TopBottom("360 3D"),
    Stereo180SideBySide("180 3D"),
    Mono360("360"),
    Flat2D("2D"),
    Unknown("?"),
}

enum class DetectionConfidence {
    High,
    Medium,
    Low,
}

data class MediaClassification(
    val format: GalleryMediaFormat,
    val confidence: DetectionConfidence,
    val reason: String,
)

object MediaFormatClassifier {
    private const val RELATIVE_TOLERANCE = 0.08
    private const val HIGH_RES_SQUARE_MIN_EDGE = 3_000

    fun classify(
        media: GalleryMedia,
        mode: DetectionMode,
    ): MediaClassification {
        val ratio = media.width.toDouble() / media.height.toDouble()
        return if (mode == DetectionMode.StrictVr) {
            classifyStrict(ratio)
        } else {
            classifySmart(media, ratio)
        }
    }

    private fun classifyStrict(ratio: Double): MediaClassification =
        when {
            ratio.isNear(1.0) ->
                MediaClassification(
                    GalleryMediaFormat.Stereo360TopBottom,
                    DetectionConfidence.Medium,
                    "Strict 1:1 rule",
                )
            ratio.isNear(2.0) ->
                MediaClassification(
                    GalleryMediaFormat.Stereo180SideBySide,
                    DetectionConfidence.Medium,
                    "Strict 2:1 rule",
                )
            else ->
                MediaClassification(
                    GalleryMediaFormat.Unknown,
                    DetectionConfidence.Low,
                    "Unsupported strict ratio",
                )
        }

    private fun classifySmart(
        media: GalleryMedia,
        ratio: Double,
    ): MediaClassification {
        val evidence = "${media.relativePath}/${media.displayName}".lowercase()
        val hasFisheyeEvidence =
            listOf("fisheye", "sbs", "vr180", "180", "fish", "鱼眼").any(evidence::contains)
        val hasStereo360Evidence =
            listOf("stereo360", "360_tb", "topbottom", "overunder").any(evidence::contains)
        val hasMono360Evidence =
            listOf("360", "panorama", "pano", "equirect", "spherical", "全景")
                .any(evidence::contains)

        return when {
            ratio.isNear(2.0) && hasFisheyeEvidence ->
                MediaClassification(
                    GalleryMediaFormat.Stereo180SideBySide,
                    DetectionConfidence.High,
                    "2:1 with fisheye evidence",
                )
            ratio.isNear(2.0) && hasMono360Evidence ->
                MediaClassification(
                    GalleryMediaFormat.Mono360,
                    DetectionConfidence.High,
                    "2:1 with panorama evidence",
                )
            ratio.isNear(2.0) ->
                MediaClassification(
                    GalleryMediaFormat.Flat2D,
                    DetectionConfidence.Medium,
                    "Unmarked 2:1 image",
                )
            ratio.isNear(1.0) &&
                (hasStereo360Evidence ||
                    minOf(media.width, media.height) >= HIGH_RES_SQUARE_MIN_EDGE) ->
                MediaClassification(
                    GalleryMediaFormat.Stereo360TopBottom,
                    DetectionConfidence.Medium,
                    "High-resolution 1:1 stereo candidate",
                )
            ratio.isNear(1.0) ->
                MediaClassification(
                    GalleryMediaFormat.Flat2D,
                    DetectionConfidence.Medium,
                    "Low-resolution square image",
                )
            else ->
                MediaClassification(
                    GalleryMediaFormat.Flat2D,
                    DetectionConfidence.High,
                    "Conventional flat-image ratio",
                )
        }
    }

    private fun Double.isNear(target: Double): Boolean =
        abs(this - target) / target <= RELATIVE_TOLERANCE
}

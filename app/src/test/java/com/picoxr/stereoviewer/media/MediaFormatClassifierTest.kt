package com.picoxr.stereoviewer.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatClassifierTest {
    @Test
    fun strictModeUsesOriginalAspectRules() {
        assertEquals(
            GalleryMediaFormat.Stereo360TopBottom,
            MediaFormatClassifier.classify(media(width = 8000, height = 8000), DetectionMode.StrictVr)
                .format,
        )
        assertEquals(
            GalleryMediaFormat.Stereo180SideBySide,
            MediaFormatClassifier.classify(media(width = 4096, height = 2048), DetectionMode.StrictVr)
                .format,
        )
    }

    @Test
    fun smartModeUsesFisheyeEvidenceForTwoToOneImages() {
        val media =
            media(
                width = 4096,
                height = 2048,
                name = "scene_fisheye_sbs.png",
                path = "0913/",
            )

        assertEquals(
            GalleryMediaFormat.Stereo180SideBySide,
            MediaFormatClassifier.classify(media, DetectionMode.Smart).format,
        )
    }

    @Test
    fun smartModeUsesPanoramaEvidenceForTwoToOneImages() {
        assertEquals(
            GalleryMediaFormat.Mono360,
            MediaFormatClassifier.classify(
                    media(width = 4000, height = 2000, name = "panorama.jpg"),
                    DetectionMode.Smart,
                )
                .format,
        )
    }

    @Test
    fun smartModeKeepsUnmarkedTwoToOneAsFlatImage() {
        assertEquals(
            GalleryMediaFormat.Flat2D,
            MediaFormatClassifier.classify(
                    media(width = 4000, height = 2000, name = "wide_landscape.jpg"),
                    DetectionMode.Smart,
                )
                .format,
        )
    }

    @Test
    fun smartModeKeepsSmallSquareAsFlatImage() {
        assertEquals(
            GalleryMediaFormat.Flat2D,
            MediaFormatClassifier.classify(
                    media(width = 1024, height = 1024, name = "poster.png"),
                    DetectionMode.Smart,
                )
                .format,
        )
    }

    @Test
    fun smartModeTreatsHighResolutionSquareAsStereoCandidate() {
        assertEquals(
            GalleryMediaFormat.Stereo360TopBottom,
            MediaFormatClassifier.classify(
                    media(width = 8000, height = 8000, name = "scene.png"),
                    DetectionMode.Smart,
                )
                .format,
        )
    }

    private fun media(
        width: Int,
        height: Int,
        name: String = "test.png",
        path: String = "Pictures/",
    ) =
        GalleryMedia(
            id = 1,
            uriString = "content://test/1",
            displayName = name,
            width = width,
            height = height,
            relativePath = path,
            sizeBytes = 1,
            dateModifiedSeconds = 1,
            mimeType = "image/png",
        )
}

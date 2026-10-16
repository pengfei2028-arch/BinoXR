package com.picoxr.stereoviewer.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StereoImageDescriptorTest {
    @Test
    fun squareImageDefaultsToEquirect360TopBottom() {
        assertEquals(
            StereoFormat.Equirect360TopBottom,
            StereoFormatDetector.detect(width = 8000, height = 8000),
        )
    }

    @Test
    fun nearSquareImageUsesConfiguredTolerance() {
        assertEquals(
            StereoFormat.Equirect360TopBottom,
            StereoFormatDetector.detect(width = 8000, height = 7500),
        )
    }

    @Test
    fun twoToOneImageDefaultsToFisheye180SideBySide() {
        assertEquals(
            StereoFormat.Fisheye180SideBySide,
            StereoFormatDetector.detect(width = 8000, height = 4000),
        )
    }

    @Test
    fun fourToOneImageRequiresUserSelection() {
        assertEquals(
            StereoFormat.Unknown,
            StereoFormatDetector.detect(width = 8000, height = 2000),
        )
    }

    @Test
    fun userOverrideTakesPriorityOverMetadataAndDetection() {
        val descriptor =
            StereoImageDescriptor(
                id = "test",
                sourceUri = "content://test",
                width = 8000,
                height = 8000,
                metadataFormat = StereoFormat.Equirect360TopBottom,
                userFormatOverride = StereoFormat.Fisheye180SideBySide,
            )

        assertEquals(StereoFormat.Fisheye180SideBySide, descriptor.resolvedFormat)
    }

    @Test
    fun topBottomMapsTopToLeftByDefault() {
        assertEquals(
            NormalizedImageRegion(x = 0f, y = 0f, width = 1f, height = 0.5f),
            StereoSourceRegionMapper.regionFor(
                layout = StereoLayout.TopBottom,
                outputEye = Eye.Left,
                swapEyes = false,
            ),
        )
    }

    @Test
    fun swappingTopBottomMapsBottomToLeft() {
        assertEquals(
            NormalizedImageRegion(x = 0f, y = 0.5f, width = 1f, height = 0.5f),
            StereoSourceRegionMapper.regionFor(
                layout = StereoLayout.TopBottom,
                outputEye = Eye.Left,
                swapEyes = true,
            ),
        )
    }

    @Test
    fun sideBySideMapsLeftHalfToLeftByDefault() {
        assertEquals(
            NormalizedImageRegion(x = 0f, y = 0f, width = 0.5f, height = 1f),
            StereoSourceRegionMapper.regionFor(
                layout = StereoLayout.SideBySide,
                outputEye = Eye.Left,
                swapEyes = false,
            ),
        )
    }

    @Test
    fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StereoFormatDetector.detect(width = 0, height = 8000)
        }
    }
}

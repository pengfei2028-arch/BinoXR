package com.picoxr.stereoviewer.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TextureSampleSizeTest {
    @Test
    fun keepsEightKByFourKAtFullResolution() {
        assertEquals(1, calculateTextureSampleSize(8192, 4096))
    }

    @Test
    fun halvesSixteenKByEightKToGpuBudget() {
        assertEquals(2, calculateTextureSampleSize(16384, 8192))
    }

    @Test
    fun halvesEightKSquareToPixelBudget() {
        assertEquals(2, calculateTextureSampleSize(8192, 8192))
    }

    @Test
    fun quartersSixteenKSquareToPixelBudget() {
        assertEquals(4, calculateTextureSampleSize(16384, 16384))
    }

    @Test
    fun keepsPackedEightKSquareAtFullResolution() {
        assertEquals(1, calculatePackedTextureSampleSize(8000, 8000))
    }

    @Test
    fun halvesPackedSixteenKSquareBeforeTargetScaling() {
        assertEquals(2, calculatePackedTextureSampleSize(16384, 16384))
    }
}

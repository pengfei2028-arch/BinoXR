package com.picoxr.stereoviewer.conversion

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StereoFisheyeRendererTest {
    @Test
    fun nearestValidIndices_findLocalRightNeighbor() {
        val sourceX = intArrayOf(0, -1, 2, -1, 4)
        val left = IntArray(sourceX.size)
        val right = IntArray(sourceX.size)

        fillNearestValidIndices(
            sourceX = sourceX,
            rowOffset = 0,
            width = sourceX.size,
            left = left,
            right = right,
        )

        assertArrayEquals(intArrayOf(0, 0, 2, 2, 4), left)
        assertArrayEquals(intArrayOf(0, 2, 2, 4, 4), right)
    }
}

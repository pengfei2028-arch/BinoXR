package com.picoxr.stereoviewer.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchProgressTest {
    @Test
    fun fraction_combinesCompletedItemsAndCurrentStage() {
        val progress =
            BatchProgress(
                completed = 1,
                total = 4,
                succeeded = 1,
                failed = 0,
                skipped = 0,
                currentName = "image.jpg",
                stage = ConversionStage.ProjectingRightEye,
            )

        assertEquals((1f + 0.88f) / 4f, progress.fraction, 0.0001f)
    }

    @Test
    fun fraction_isMonotonicAcrossRenderStages() {
        val stages =
            listOf(
                ConversionStage.GeneratingLeftEye,
                ConversionStage.ProjectingLeftEye,
                ConversionStage.GeneratingRightEye,
                ConversionStage.ProjectingRightEye,
                ConversionStage.Saving,
                ConversionStage.Verifying,
            )
        val fractions =
            stages.map { stage ->
                BatchProgress(0, 1, 0, 0, 0, "image.jpg", stage).fraction
            }

        assertTrue(fractions.zipWithNext().all { (first, second) -> second > first })
    }
}

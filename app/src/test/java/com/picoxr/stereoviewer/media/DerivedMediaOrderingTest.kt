package com.picoxr.stereoviewer.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DerivedMediaOrderingTest {
    @Test
    fun generatedFisheyeIsPlacedImmediatelyAfterItsSource() {
        val source = media(1, "holiday.jpg")
        val unrelated = media(2, "later.jpg")
        val generated = media(3, "holiday_BinoXR_FISHEYE_SBS180_PCV2_2048.png")

        val ordered =
            listOf(generated, unrelated, source).withGeneratedFisheyeNextToSource()

        assertEquals(listOf(2L, 1L, 3L), ordered.map { it.id })
    }

    @Test
    fun generatedFisheyeInAnotherFolderDoesNotAttachToSameNamedSource() {
        val source = media(1, "holiday.jpg", "Pictures/A/")
        val generated =
            media(
                2,
                "holiday_BinoXR_FISHEYE_SBS180_PCV2_2048.png",
                "Pictures/B/",
            )

        val ordered = listOf(generated, source).withGeneratedFisheyeNextToSource()

        assertEquals(listOf(1L, 2L), ordered.map { it.id })
    }

    @Test
    fun generatedFisheyeInWritableMirrorFolderAttachesToLegacyRootSource() {
        val source = media(1, "holiday.jpg", "0913/")
        val generated =
            media(
                2,
                "holiday_BinoXR_FISHEYE_SBS180_PCV2_2048.png",
                "Pictures/0913/",
            )

        val ordered = listOf(generated, source).withGeneratedFisheyeNextToSource()

        assertEquals(listOf(1L, 2L), ordered.map { it.id })
        assertEquals(source.galleryFolderKey(), generated.galleryFolderKey())
    }

    @Test
    fun insertionPlacesNewOutputAfterCurrentSource() {
        val source = media(1, "holiday.jpg")
        val next = media(2, "later.jpg")
        val generated = media(3, "holiday_BinoXR_FISHEYE_SBS180_PCV2_2048.png")

        val ordered =
            listOf(source, next).insertingGeneratedFisheye(
                sourceId = source.id,
                generated = generated,
            )

        assertEquals(listOf(1L, 3L, 2L), ordered.map { it.id })
    }

    private fun media(
        id: Long,
        name: String,
        path: String = "Pictures/",
    ) =
        GalleryMedia(
            id = id,
            uriString = "content://test/$id",
            displayName = name,
            width = 2048,
            height = 1024,
            relativePath = path,
            sizeBytes = 1,
            dateModifiedSeconds = id,
            mimeType = "image/png",
        )
}

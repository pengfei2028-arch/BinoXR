package com.picoxr.stereoviewer.conversion

import com.picoxr.stereoviewer.media.GalleryMedia
import org.junit.Assert.assertEquals
import org.junit.Test

class FisheyeConversionServiceTest {
    @Test
    fun invalidTopLevelFolderIsMappedUnderPictures() {
        assertEquals(
            "Pictures/0913/",
            FisheyeConversionService.outputRelativePath(media("0913/")),
        )
    }

    @Test
    fun writableMediaStoreFolderIsPreserved() {
        assertEquals(
            "DCIM/Camera/",
            FisheyeConversionService.outputRelativePath(media("DCIM/Camera/")),
        )
    }

    private fun media(path: String) =
        GalleryMedia(
            id = 1,
            uriString = "content://test/1",
            displayName = "photo.jpg",
            width = 2048,
            height = 1024,
            relativePath = path,
            sizeBytes = 1,
            dateModifiedSeconds = 1,
            mimeType = "image/jpeg",
        )
}

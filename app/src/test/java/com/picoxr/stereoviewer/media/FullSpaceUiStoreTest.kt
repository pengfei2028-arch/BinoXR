package com.picoxr.stereoviewer.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FullSpaceUiStoreTest {
    @Before
    fun resetStore() {
        FullSpaceUiStore.reset()
    }

    @Test
    fun projectionOverrideIsRestoredPerImageWithinSession() {
        FullSpaceUiStore.toggleFlatAs360(mediaId = 1L)

        FullSpaceUiStore.restoreProjectionOverride(mediaId = 2L)
        assertFalse(FullSpaceUiStore.state.value.flatAs360)

        FullSpaceUiStore.restoreProjectionOverride(mediaId = 1L)
        assertTrue(FullSpaceUiStore.state.value.flatAs360)
    }

    @Test
    fun resetClearsProjectionOverridesForNextSession() {
        FullSpaceUiStore.toggleFlatAs360(mediaId = 1L)

        FullSpaceUiStore.reset()
        FullSpaceUiStore.restoreProjectionOverride(mediaId = 1L)

        assertFalse(FullSpaceUiStore.state.value.flatAs360)
    }
}

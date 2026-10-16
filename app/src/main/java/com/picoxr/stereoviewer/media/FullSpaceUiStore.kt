package com.picoxr.stereoviewer.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FullSpaceUiState(
    val statusMessage: String = "",
    val switchingMedia: Boolean = false,
    val convertingMedia: Boolean = false,
    val swapEyes: Boolean = false,
    val flatAs360: Boolean = false,
)

object FullSpaceUiStore {
    private val mutableState = MutableStateFlow(FullSpaceUiState())
    private val flatProjectionOverrides = mutableMapOf<Long, Boolean>()

    val state = mutableState.asStateFlow()

    fun reset() {
        flatProjectionOverrides.clear()
        mutableState.value = FullSpaceUiState()
    }

    fun setLoading(
        loading: Boolean,
        message: String = "",
    ) {
        mutableState.update {
            it.copy(
                switchingMedia = loading,
                statusMessage = message,
            )
        }
    }

    fun setFailure(message: String) {
        mutableState.update {
            it.copy(
                switchingMedia = false,
                convertingMedia = false,
                statusMessage = message,
            )
        }
    }

    fun setConverting(
        converting: Boolean,
        message: String = "",
    ) {
        mutableState.update {
            it.copy(
                convertingMedia = converting,
                statusMessage = message,
            )
        }
    }

    fun toggleEyeOrder() {
        mutableState.update { it.copy(swapEyes = !it.swapEyes) }
    }

    fun toggleFlatAs360(mediaId: Long) {
        mutableState.update {
            val enabled = !it.flatAs360
            flatProjectionOverrides[mediaId] = enabled
            it.copy(flatAs360 = enabled)
        }
    }

    fun restoreProjectionOverride(mediaId: Long) {
        mutableState.update {
            it.copy(flatAs360 = flatProjectionOverrides[mediaId] ?: false)
        }
    }

    fun projectionOverrideFor(mediaId: Long): Boolean =
        flatProjectionOverrides[mediaId] ?: false
}

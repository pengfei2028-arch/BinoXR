package com.picoxr.stereoviewer.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class MediaSelection(
    val media: GalleryMedia,
    val classification: MediaClassification,
    val folderMedia: List<GalleryMedia>,
    val detectionMode: DetectionMode,
)

object MediaSelectionStore {
    const val PREVIEW_TOKEN_KEY = "media_preview_token"

    private val mutableSelection = MutableStateFlow<MediaSelection?>(null)
    private val previewSelections = ConcurrentHashMap<String, MediaSelection>()
    private val nextPreviewId = AtomicLong()

    val selection = mutableSelection.asStateFlow()
    val current: MediaSelection?
        get() = mutableSelection.value

    fun select(
        media: GalleryMedia,
        detectionMode: DetectionMode,
        folderMedia: List<GalleryMedia> = listOf(media),
    ): MediaSelection {
        return MediaSelection(
                media = media,
                classification = MediaFormatClassifier.classify(media, detectionMode),
                folderMedia = folderMedia,
                detectionMode = detectionMode,
            )
            .also {
                mutableSelection.value = it
            }
    }

    fun createPreview(
        media: GalleryMedia,
        detectionMode: DetectionMode,
        folderMedia: List<GalleryMedia> = listOf(media),
    ): String {
        val token = "preview-${media.id}-${nextPreviewId.incrementAndGet()}"
        previewSelections[token] =
            MediaSelection(
                media = media,
                classification = MediaFormatClassifier.classify(media, detectionMode),
                folderMedia = folderMedia,
                detectionMode = detectionMode,
            )
        return token
    }

    fun preview(token: String?): MediaSelection? =
        token?.let(previewSelections::get)

    fun releasePreview(token: String?) {
        token?.let(previewSelections::remove)
    }

    fun activate(selection: MediaSelection) {
        mutableSelection.value = selection
    }

    fun selectFromCurrentFolder(media: GalleryMedia): MediaSelection {
        val previous = requireNotNull(current) { "No active media selection" }
        return select(
            media = media,
            detectionMode = previous.detectionMode,
            folderMedia = previous.folderMedia,
        )
    }
}

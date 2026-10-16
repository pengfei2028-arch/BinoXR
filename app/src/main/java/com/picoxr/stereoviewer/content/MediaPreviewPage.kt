package com.picoxr.stereoviewer.content

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import com.picoxr.stereoviewer.FULL_SPACE_DOCK_WINDOW_ID
import com.picoxr.stereoviewer.FULL_SPACE_DOCK_WINDOW_TAG
import com.picoxr.stereoviewer.MAIN_WINDOW_ID
import com.picoxr.stereoviewer.MILESTONE_ZERO_STAGE_ID
import com.picoxr.stereoviewer.conversion.ConversionResult
import com.picoxr.stereoviewer.conversion.ConversionStage
import com.picoxr.stereoviewer.conversion.FisheyeConversionService
import com.picoxr.stereoviewer.conversion.UiFrameMonitor
import com.picoxr.stereoviewer.media.GalleryMediaFormat
import com.picoxr.stereoviewer.media.GalleryThumbnailLoader
import com.picoxr.stereoviewer.media.MediaFormatClassifier
import com.picoxr.stereoviewer.media.MediaSelection
import com.picoxr.stereoviewer.media.MediaSelectionStore
import com.picoxr.stereoviewer.media.SelectedPanoramaTextureLoader
import com.picoxr.stereoviewer.media.insertingGeneratedFisheye
import com.picoxr.stereoviewer.media.requiresHighResolutionLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val LOG_TAG = "PicoStereoViewer"
private val PreviewBackground = Color(0xFF101113)
private val PreviewSurface = Color(0xFF1A1B1E)
private val PreviewText = Color(0xFFF4F4F5)
private val PreviewMutedText = Color(0xFFA7A7AB)
private val PreviewGreen = Color(0xFF30A472)

private sealed interface PreparationState {
    data object PreviewOnly : PreparationState
    data object Preparing : PreparationState
    data class Ready(val milliseconds: Long) : PreparationState
    data class Failed(val message: String) : PreparationState
}

private sealed interface ConversionState {
    data object Idle : ConversionState
    data class Running(val stage: ConversionStage) : ConversionState
    data class Ready(val result: ConversionResult) : ConversionState
    data class Failed(val message: String) : ConversionState
}

@Composable
fun MediaPreviewPage(bundle: Bundle?) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()
    val previewToken = remember(bundle) {
        bundle?.getString(MediaSelectionStore.PREVIEW_TOKEN_KEY)
    }
    val initialSelection = remember(previewToken) {
        MediaSelectionStore.preview(previewToken)
    }

    if (initialSelection == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(PreviewBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text("No image selected", color = PreviewMutedText)
        }
        return
    }

    DisposableEffect(previewToken) {
        onDispose {
            MediaSelectionStore.releasePreview(previewToken)
        }
    }

    var selected by
        remember(previewToken) {
            mutableStateOf(initialSelection)
        }
    val thumbnailLoader = remember(context) { GalleryThumbnailLoader(context) }
    var preparationState by
        remember(selected.media.id) {
            mutableStateOf<PreparationState>(PreparationState.PreviewOnly)
        }
    var conversionState by
        remember(previewToken) {
            mutableStateOf<ConversionState>(ConversionState.Idle)
        }
    val canOpenInFullSpace = selected.supportsCurrentFullSpaceRenderer()

    LaunchedEffect(selected.media.id, selected.classification.format) {
        if (!canOpenInFullSpace) {
            preparationState = PreparationState.PreviewOnly
            return@LaunchedEffect
        }
        preparationState = PreparationState.Preparing
        val startedAt = SystemClock.elapsedRealtime()
        try {
            SelectedPanoramaTextureLoader.prepare(context, selected)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            preparationState = PreparationState.Ready(elapsed)
            Log.i(LOG_TAG, "Panorama preload ready id=${selected.media.id} elapsedMs=$elapsed")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            preparationState =
                PreparationState.Failed(error.message ?: error.javaClass.simpleName)
            Log.e(LOG_TAG, "Panorama preload failed id=${selected.media.id}", error)
        }
    }

    MediaPreviewContent(
        selection = selected,
        thumbnailLoader = thumbnailLoader,
        preparationState = preparationState,
        conversionState = conversionState,
        onClose = {
            SelectedPanoramaTextureLoader.discardAsync(selected.media.id)
            navigator.closeWindowContainer()
        },
        onConvert = {
            if (conversionState is ConversionState.Running) return@MediaPreviewContent
            val sourceSelection = selected
            val frameMonitor = UiFrameMonitor().apply { start() }
            conversionState = ConversionState.Running(ConversionStage.LoadingModel)
            coroutineScope.launch {
                try {
                    val result =
                        FisheyeConversionService.convertSingle(
                            context = context,
                            media = sourceSelection.media,
                            eyeSize = 2048,
                            onStage = { stage ->
                                coroutineScope.launch {
                                    conversionState = ConversionState.Running(stage)
                                }
                            },
                        )
                    val frameMetrics = frameMonitor.stop()
                    val measured =
                        result.copy(
                            metrics =
                                result.metrics.copy(
                                    maxUiFrameGapMs = frameMetrics.maxGapMs,
                                    uiFramesOver33Ms = frameMetrics.framesOver33Ms,
                                )
                        )
                    selected =
                        MediaSelection(
                            media = measured.media,
                            classification =
                                MediaFormatClassifier.classify(
                                    measured.media,
                                    sourceSelection.detectionMode,
                                ),
                            folderMedia =
                                sourceSelection.folderMedia.insertingGeneratedFisheye(
                                    sourceId = sourceSelection.media.id,
                                    generated = measured.media,
                                ),
                            detectionMode = sourceSelection.detectionMode,
                        )
                    SelectedPanoramaTextureLoader.discardAsync(sourceSelection.media.id)
                    conversionState = ConversionState.Ready(measured)
                } catch (cancellation: CancellationException) {
                    frameMonitor.stop()
                    throw cancellation
                } catch (error: Throwable) {
                    frameMonitor.stop()
                    conversionState =
                        ConversionState.Failed(
                            error.message ?: error.javaClass.simpleName
                        )
                    Log.e(LOG_TAG, "Single-image fisheye conversion failed", error)
                }
            }
        },
        onOpen = {
            coroutineScope.launch {
                MediaSelectionStore.activate(selected)
                when (
                    val result =
                        navigator.openStage(
                            id = MILESTONE_ZERO_STAGE_ID,
                            style = StageStyle.Full,
                        )
                ) {
                    OpenStageResult.Allowed -> {
                        Log.i(LOG_TAG, "Preview opened Full Space id=${selected.media.id}")
                        navigator.openWindowContainer(
                            FULL_SPACE_DOCK_WINDOW_ID,
                            FULL_SPACE_DOCK_WINDOW_TAG,
                        )
                        navigator.minimizeWindowContainer(MAIN_WINDOW_ID)
                        navigator.closeWindowContainer()
                    }
                    OpenStageResult.NotAllowed ->
                        preparationState = PreparationState.Failed("Full Space request denied")
                    is OpenStageResult.Error ->
                        preparationState =
                            PreparationState.Failed(
                                "Full Space error ${result.code}: ${result.reason}"
                            )
                }
            }
        },
    )
}

@Composable
private fun MediaPreviewContent(
    selection: MediaSelection,
    thumbnailLoader: GalleryThumbnailLoader,
    preparationState: PreparationState,
    conversionState: ConversionState,
    onClose: () -> Unit,
    onConvert: () -> Unit,
    onOpen: () -> Unit,
) {
    val media = selection.media
    val preview by
        produceState<Bitmap?>(initialValue = null, key1 = media.id) {
            value = thumbnailLoader.load(media, GalleryThumbnailLoader.PREVIEW_EDGE_PX)
        }
    val ready = preparationState is PreparationState.Ready

    Row(
        modifier =
            Modifier.fillMaxSize()
                .background(PreviewBackground)
                .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier =
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .background(PreviewSurface, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = media.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(
            modifier = Modifier.width(260.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = media.displayName,
                style = PicoTheme.typography.titleMedium,
                color = PreviewText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = selection.classification.format.label,
                style = PicoTheme.typography.labelLarge,
                color = PreviewGreen,
            )
            Text(
                text = "${media.width} x ${media.height}",
                style = PicoTheme.typography.bodySmall,
                color = PreviewMutedText,
            )
            Text(
                text = media.relativePath.ifBlank { "/" },
                style = PicoTheme.typography.bodySmall,
                color = PreviewMutedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (
                preparationState is PreparationState.Preparing &&
                    media.requiresHighResolutionLoading()
            ) {
                HighResolutionLoadingIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Text(
                text = preparationState.label(),
                style = PicoTheme.typography.bodySmall,
                color =
                    if (preparationState is PreparationState.Failed) {
                        Color(0xFFFF8A80)
                    } else {
                        PreviewMutedText
                    },
            )
            when (conversionState) {
                ConversionState.Idle -> Unit
                is ConversionState.Running ->
                    Text(
                        text = conversionState.stage.label,
                        style = PicoTheme.typography.bodySmall,
                        color = PreviewGreen,
                    )
                is ConversionState.Ready ->
                    Text(
                        text = conversionState.result.metrics.summary(),
                        style = PicoTheme.typography.bodySmall,
                        color = PreviewMutedText,
                    )
                is ConversionState.Failed ->
                    Text(
                        text = conversionState.message,
                        style = PicoTheme.typography.bodySmall,
                        color = Color(0xFFFF8A80),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (selection.classification.format == GalleryMediaFormat.Flat2D) {
                Button(
                    onClick = onConvert,
                    enabled = conversionState !is ConversionState.Running,
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonDefaults.Small,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (conversionState is ConversionState.Running) {
                                    PreviewSurface
                                } else {
                                    PreviewGreen
                                },
                            contentColor = Color.White,
                        ),
                ) {
                    if (conversionState is ConversionState.Running) {
                        HighResolutionLoadingIndicator()
                    } else {
                        Text("转 180 3D")
                    }
                }
            }
            Button(
                onClick = onOpen,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonDefaults.Small,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = PreviewGreen,
                        contentColor = Color.White,
                    ),
            ) {
                Text(if (ready) "View in Full Space" else "Preparing...")
            }
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonDefaults.Small,
            ) {
                Text("Close")
            }
        }
    }
}

private fun MediaSelection.supportsCurrentFullSpaceRenderer(): Boolean =
    classification.format == GalleryMediaFormat.Stereo360TopBottom ||
        classification.format == GalleryMediaFormat.Stereo180SideBySide ||
        classification.format == GalleryMediaFormat.Mono360 ||
        classification.format == GalleryMediaFormat.Flat2D

private fun PreparationState.label(): String =
    when (this) {
        PreparationState.PreviewOnly -> "Preview only"
        PreparationState.Preparing -> "Preparing image..."
        is PreparationState.Ready -> "Ready in ${milliseconds} ms"
        is PreparationState.Failed -> message
    }

private fun com.picoxr.stereoviewer.conversion.ConversionMetrics.summary(): String =
    "Generated in ${totalMs} ms\n" +
        "Model ${modelLoadMs} · Depth ${depthMs} · Render ${stereoAndProjectionMs} · " +
        "Save ${saveAndVerifyMs} ms\n" +
        "Peak PSS ${sampledPeakPssKb / 1024} MB · UI max gap ${maxUiFrameGapMs} ms"

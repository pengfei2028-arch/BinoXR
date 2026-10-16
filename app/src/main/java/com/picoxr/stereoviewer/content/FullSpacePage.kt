package com.picoxr.stereoviewer.content

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.annotation.ExperimentalSpatialApi
import com.pico.spatial.core.container.SpaceState
import com.pico.spatial.core.container.getSelfSpaceState
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.DisplayMode
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.picoxr.stereoviewer.FULL_SPACE_DOCK_WINDOW_ID
import com.picoxr.stereoviewer.FULL_SPACE_DOCK_WINDOW_TAG
import com.picoxr.stereoviewer.MAIN_WINDOW_ID
import com.picoxr.stereoviewer.conversion.ConversionStage
import com.picoxr.stereoviewer.conversion.FisheyeConversionService
import com.picoxr.stereoviewer.media.FullSpaceUiStore
import com.picoxr.stereoviewer.media.GalleryMedia
import com.picoxr.stereoviewer.media.GalleryMediaFormat
import com.picoxr.stereoviewer.media.GalleryThumbnailLoader
import com.picoxr.stereoviewer.media.MediaClassification
import com.picoxr.stereoviewer.media.MediaFormatClassifier
import com.picoxr.stereoviewer.media.MediaSelection
import com.picoxr.stereoviewer.media.MediaSelectionStore
import com.picoxr.stereoviewer.media.PanoramaTexturePair
import com.picoxr.stereoviewer.media.SelectedPanoramaTextureLoader
import com.picoxr.stereoviewer.media.insertingGeneratedFisheye
import com.picoxr.stereoviewer.media.requiresHighResolutionLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val LOG_TAG = "PicoStereoViewer"
private const val EQUIRECTANGULAR_MESH_PATH = "stereo/media3_equirect_sphere.obj"
private const val VR180_MESH_PATH = "stereo/vr180_equidistant_hemisphere.obj"
private const val FLAT_IMAGE_MESH_PATH = "stereo/flat_image_plane.obj"
private const val FLAT_IMAGE_DISTANCE_METERS = 2.2f
private const val FLAT_IMAGE_CENTER_HEIGHT_METERS = 1.3f
private const val FLAT_IMAGE_MAX_WIDTH_METERS = 2.2f
private const val FLAT_IMAGE_MAX_HEIGHT_METERS = 1.25f
private const val PINCH_TIPS_ON_METERS = 0.025f
private const val PINCH_TIPS_OFF_METERS = 0.04f
private const val INDEX_FROM_PALM_MIN_METERS = 0.055f
private const val THUMBSTICK_NAVIGATION_ON = 0.65f
private const val THUMBSTICK_NAVIGATION_OFF = 0.25f
private val DockStripWidth = 660.dp
private val DockThumbnailWidth = 120.dp
private val DockSurface = Color(0xE6FFFFFF)
private val DockText = Color(0xFF171717)
private val DockMutedText = Color(0xFF606266)
private val DockGreen = Color(0xFF30A472)

@OptIn(ExperimentalSpatialApi::class)
@Composable
fun FullSpacePage() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val resources = remember { FullSpaceResources() }
    val handTrackingProvider = remember { HandTrackingProvider() }
    val controllerTrackingProvider = remember { ControllerTrackingProvider() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val selection by MediaSelectionStore.selection.collectAsState()
    val uiState by FullSpaceUiStore.state.collectAsState()
    val initialSelection = remember { MediaSelectionStore.current }
    var resourcesReady by remember { mutableStateOf(false) }
    var loadedMediaId by remember { mutableStateOf<Long?>(null) }
    val navigateAdjacent by
        rememberUpdatedState<(Int) -> Unit> { direction ->
            selectAdjacentFullSpaceMedia(direction)
        }
    val controllerActionListener =
        remember {
            var thumbstickLatched = false
            ControllerTrackingProvider.ControllerActionListener { action ->
                val leftX = action.left.thumbstickValue.x
                val rightX = action.right.thumbstickValue.x
                val horizontal = if (abs(leftX) >= abs(rightX)) leftX else rightX

                if (abs(horizontal) <= THUMBSTICK_NAVIGATION_OFF) {
                    thumbstickLatched = false
                } else if (!thumbstickLatched && abs(horizontal) >= THUMBSTICK_NAVIGATION_ON) {
                    thumbstickLatched = true
                    val direction = if (horizontal < 0f) -1 else 1
                    mainHandler.post { navigateAdjacent(direction) }
                }
            }
        }

    LaunchedEffect(Unit) {
        FullSpaceUiStore.reset()
        Log.i(LOG_TAG, "FullSpacePage composed spaceState=${context.getSelfSpaceState()}")
    }
    DisposableEffect(handTrackingProvider) {
        val startResult = handTrackingProvider.start()
        Log.i(
            LOG_TAG,
            "Hand tracking started result=$startResult support=${handTrackingProvider.supportState}",
        )
        onDispose {
            handTrackingProvider.stop()
            Log.i(LOG_TAG, "Hand tracking stopped")
        }
    }
    DisposableEffect(controllerTrackingProvider) {
        controllerTrackingProvider.addControllerActionListener(controllerActionListener)
        val startResult = controllerTrackingProvider.start()
        Log.i(LOG_TAG, "Controller tracking started result=$startResult")
        onDispose {
            controllerTrackingProvider.removeControllerActionListener(controllerActionListener)
            controllerTrackingProvider.stop()
            mainHandler.removeCallbacksAndMessages(null)
            Log.i(LOG_TAG, "Controller tracking stopped")
        }
    }
    LaunchedEffect(handTrackingProvider) {
        var leftPinchLatched = false
        var rightPinchLatched = false
        handTrackingProvider.dataFlow.collect { tracking ->
            val leftMetrics = tracking.left.pinchMetrics()
            val rightMetrics = tracking.right.pinchMetrics()
            val leftPinching = leftMetrics.isStandardPinch()
            val rightPinching = rightMetrics.isStandardPinch()
            if (
                (leftPinching && !leftPinchLatched) ||
                    (rightPinching && !rightPinchLatched)
            ) {
                val restoredTagged =
                    navigator.restoreWindowContainer(
                        FULL_SPACE_DOCK_WINDOW_ID,
                        FULL_SPACE_DOCK_WINDOW_TAG,
                    )
                val restoredById =
                    if (restoredTagged) {
                        false
                    } else {
                        navigator.restoreWindowContainer(FULL_SPACE_DOCK_WINDOW_ID)
                    }
                Log.i(
                    LOG_TAG,
                    "Standard pinch restore tagged=$restoredTagged byId=$restoredById " +
                        "left=$leftMetrics right=$rightMetrics",
                )
            }
            leftPinchLatched =
                when {
                    leftPinching -> true
                    leftMetrics.isReleased() -> false
                    else -> leftPinchLatched
                }
            rightPinchLatched =
                when {
                    rightPinching -> true
                    rightMetrics.isReleased() -> false
                    else -> rightPinchLatched
                }
        }
    }
    LaunchedEffect(selection?.media?.id, resourcesReady) {
        val current = selection ?: return@LaunchedEffect
        if (!resourcesReady || current.media.id == loadedMediaId) {
            return@LaunchedEffect
        }
        FullSpaceUiStore.setLoading(true, "Loading ${current.media.displayName}...")
        try {
            val flatAs360 = FullSpaceUiStore.projectionOverrideFor(current.media.id)
            val nextMesh = loadMesh(current, flatAs360)
            val nextTextures =
                try {
                    SelectedPanoramaTextureLoader.acquire(context, current)
                } catch (error: Throwable) {
                    nextMesh.close()
                    throw error
                }
            resources.replace(
                selection = current,
                flatAs360 = flatAs360,
                mesh = nextMesh,
                textures = nextTextures,
            )
            loadedMediaId = current.media.id
            FullSpaceUiStore.setLoading(false)
            Log.i(
                LOG_TAG,
                "System Dock switched media id=${current.media.id} " +
                    "format=${current.classification.format}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            FullSpaceUiStore.setFailure(
                "Load failed: ${error.message ?: error.javaClass.simpleName}"
            )
            Log.e(LOG_TAG, "System Dock media switch failed", error)
        }
    }
    LaunchedEffect(uiState.swapEyes, selection?.media?.id, resourcesReady, loadedMediaId) {
        val current = selection ?: return@LaunchedEffect
        if (
            !resourcesReady ||
                current.media.id != loadedMediaId ||
                !current.classification.format.isStereo() ||
                resources.isEyeOrderApplied(current.media.id, uiState.swapEyes)
        ) {
            return@LaunchedEffect
        }
        FullSpaceUiStore.setLoading(true, "Switching eye order...")
        try {
            val nextTextures =
                if (uiState.swapEyes) {
                    SelectedPanoramaTextureLoader.loadWithSwappedEyes(context, current)
                } else {
                    SelectedPanoramaTextureLoader.loadFullQuality(context, current)
                }
            resources.replaceTextures(current, nextTextures)
            FullSpaceUiStore.setLoading(false)
            Log.i(
                LOG_TAG,
                "Eye order ready id=${current.media.id} swapEyes=${uiState.swapEyes}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            FullSpaceUiStore.setFailure(
                "Eye order switch failed: ${error.message ?: error.javaClass.simpleName}"
            )
            Log.e(LOG_TAG, "Eye order switch failed", error)
        }
    }
    LaunchedEffect(uiState.flatAs360, resourcesReady, selection?.media?.id) {
        val current = selection ?: return@LaunchedEffect
        if (
            !resourcesReady ||
                current.media.id != loadedMediaId ||
                current.classification.format != GalleryMediaFormat.Flat2D ||
                resources.isFlatProjectionApplied(current.media.id, uiState.flatAs360)
        ) {
            return@LaunchedEffect
        }
        runCatching {
                val projectionMesh =
                    loadMesh(
                        format =
                            if (uiState.flatAs360) {
                                GalleryMediaFormat.Mono360
                            } else {
                                GalleryMediaFormat.Flat2D
                            }
                    )
                resources.replaceProjection(
                    selection = current,
                    flatAs360 = uiState.flatAs360,
                    mesh = projectionMesh,
                )
                Log.i(LOG_TAG, "2D projection override 360=${uiState.flatAs360}")
            }
            .onFailure {
                FullSpaceUiStore.setFailure(
                    "Projection switch failed: ${it.message ?: it.javaClass.simpleName}"
                )
                Log.e(LOG_TAG, "Failed to switch 2D projection", it)
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            navigator.closeWindowContainer(
                FULL_SPACE_DOCK_WINDOW_ID,
                FULL_SPACE_DOCK_WINDOW_TAG,
            )
            resources.release()
            Log.i(LOG_TAG, "Full Space resources released")
        }
    }

    SpatialView(
        modifier = Modifier.fillMaxSize(),
        update = { _, _ -> },
        initial = { content, _ ->
            runCatching {
                    val selected = requireNotNull(initialSelection) { "No image selected" }
                    val mesh = loadMesh(selected)
                    val textures =
                        try {
                            SelectedPanoramaTextureLoader.acquire(context, selected)
                        } catch (error: Throwable) {
                            mesh.close()
                            throw error
                        }
                    val material = createPanoramaMaterial(textures)
                    val panorama =
                        Entity().apply {
                            components.set(selected.fullSpaceTransform())
                            components.set(
                                mediaVideoComponent(
                                    mesh = mesh,
                                    material = material,
                                    format = selected.classification.format,
                                )
                            )
                            content.addEntity(this)
                        }
                    resources.bind(
                        panorama = panorama,
                        mesh = mesh,
                        material = material,
                        textures = textures,
                        selection = selected,
                        flatAs360 = false,
                    )
                    loadedMediaId = selected.media.id
                    resourcesReady = true
                    FullSpaceUiStore.setLoading(false)
                    Log.i(
                        LOG_TAG,
                        "Initial Full Space media id=${selected.media.id} " +
                            "format=${selected.classification.format}",
                    )
                }
                .onFailure {
                    FullSpaceUiStore.setFailure(
                        "Load failed: ${it.message ?: it.javaClass.simpleName}"
                    )
                    Log.e(LOG_TAG, "Failed to initialize Full Space media", it)
                }
        },
    )
}

@Composable
fun FullSpaceDockPage() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val conversionScope = rememberCoroutineScope()
    val selection by MediaSelectionStore.selection.collectAsState()
    val uiState by FullSpaceUiStore.state.collectAsState()
    val thumbnailLoader = remember(context) { GalleryThumbnailLoader(context) }
    val exitScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    LaunchedEffect(context, navigator) {
        while (true) {
            delay(250L)
            if (context.getSelfSpaceState() == SpaceState.SHARED_SPACE) {
                Log.i(LOG_TAG, "Shared Space detected; closing orphaned Full Space Dock")
                navigator.closeWindowContainer()
                break
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exitScope.launch {
                Log.i(LOG_TAG, "System Dock closed; returning to Shared Space")
                navigator.restoreWindowContainer(MAIN_WINDOW_ID)
                navigator.closeStage()
            }
        }
    }

    selection?.let { current ->
        FullSpaceDock(
            selection = current,
            thumbnailLoader = thumbnailLoader,
            statusMessage = uiState.statusMessage,
            switchingMedia = uiState.switchingMedia,
            convertingMedia = uiState.convertingMedia,
            swapEyes = uiState.swapEyes,
            flatAs360 = uiState.flatAs360,
            onSwapEyes = FullSpaceUiStore::toggleEyeOrder,
            onToggleFlatAs360 = {
                FullSpaceUiStore.toggleFlatAs360(current.media.id)
            },
            onConvert = {
                if (uiState.switchingMedia || uiState.convertingMedia) {
                    return@FullSpaceDock
                }
                val sourceSelection = current
                FullSpaceUiStore.setConverting(true, ConversionStage.LoadingModel.label)
                conversionScope.launch {
                    try {
                        val result =
                            FisheyeConversionService.convertSingle(
                                context = context,
                                media = sourceSelection.media,
                                eyeSize = 2048,
                                onStage = { stage ->
                                    conversionScope.launch {
                                        FullSpaceUiStore.setConverting(true, stage.label)
                                    }
                                },
                            )
                        val updatedFolder =
                            sourceSelection.folderMedia.insertingGeneratedFisheye(
                                sourceId = sourceSelection.media.id,
                                generated = result.media,
                            )
                        FullSpaceUiStore.restoreProjectionOverride(result.media.id)
                        MediaSelectionStore.select(
                            media = result.media,
                            detectionMode = sourceSelection.detectionMode,
                            folderMedia = updatedFolder,
                        )
                        FullSpaceUiStore.setConverting(false)
                    } catch (cancellation: CancellationException) {
                        FullSpaceUiStore.setConverting(false)
                        throw cancellation
                    } catch (error: Throwable) {
                        FullSpaceUiStore.setFailure(
                            "Conversion failed: ${error.message ?: error.javaClass.simpleName}"
                        )
                        Log.e(LOG_TAG, "Full Space conversion failed", error)
                    }
                }
            },
            onSelect = { media ->
                if (
                    uiState.switchingMedia ||
                        uiState.convertingMedia ||
                        media.id == current.media.id
                ) {
                    return@FullSpaceDock
                }
                val classification =
                    MediaFormatClassifier.classify(media, current.detectionMode)
                if (!classification.format.isFullSpaceSupported()) {
                    FullSpaceUiStore.setFailure("${classification.format.label} is preview-only")
                    return@FullSpaceDock
                }
                FullSpaceUiStore.restoreProjectionOverride(media.id)
                MediaSelectionStore.selectFromCurrentFolder(media)
            },
        )
    }
}

@Composable
private fun FullSpaceDock(
    selection: MediaSelection,
    thumbnailLoader: GalleryThumbnailLoader,
    statusMessage: String,
    switchingMedia: Boolean,
    convertingMedia: Boolean,
    swapEyes: Boolean,
    flatAs360: Boolean,
    onSwapEyes: () -> Unit,
    onToggleFlatAs360: () -> Unit,
    onConvert: () -> Unit,
    onSelect: (GalleryMedia) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = selection.folderMedia.indexOfFirst { it.id == selection.media.id }

    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect

        var selectedItem =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        if (selectedItem == null) {
            listState.scrollToItem(currentIndex)
            withFrameNanos { }
            selectedItem =
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        }

        val item = selectedItem ?: return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val viewportCenter =
            (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
        val itemCenter = item.offset + item.size / 2
        val centerDelta = (itemCenter - viewportCenter).toFloat()
        if (abs(centerDelta) > 1f) {
            listState.animateScrollBy(centerDelta)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = statusMessage.ifBlank { selection.media.displayName },
                modifier = Modifier.weight(1f),
                style = PicoTheme.typography.labelMedium,
                color = if (switchingMedia || convertingMedia) DockGreen else DockText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (switchingMedia && selection.media.requiresHighResolutionLoading()) {
                HighResolutionLoadingIndicator()
            }
            if (selection.classification.format.isStereo()) {
                Button(
                    onClick = onSwapEyes,
                    enabled = !switchingMedia,
                    size = ButtonDefaults.Min,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (swapEyes) DockGreen else DockSurface,
                            contentColor = DockText,
                        ),
                ) {
                    Text("L/R")
                }
            }
            if (selection.classification.format == GalleryMediaFormat.Flat2D) {
                Button(
                    onClick = onToggleFlatAs360,
                    enabled = !switchingMedia && !convertingMedia,
                    size = ButtonDefaults.Min,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (flatAs360) DockGreen else DockSurface,
                            contentColor = DockText,
                        ),
                ) {
                    Text("360")
                }
                if (convertingMedia) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        HighResolutionLoadingIndicator()
                    }
                } else {
                    Button(
                        onClick = onConvert,
                        enabled = !switchingMedia,
                        size = ButtonDefaults.Min,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = DockGreen,
                                contentColor = Color.White,
                            ),
                    ) {
                        Text("转 180 3D")
                    }
                }
            }
        }
        LazyRow(
            modifier =
                Modifier.width(DockStripWidth)
                    .height(104.dp)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to Color.Transparent,
                                            0.08f to Color.Black,
                                            0.92f to Color.Black,
                                            1f to Color.Transparent,
                                        ),
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            state = listState,
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(selection.folderMedia, key = { it.id }) { media ->
                val classification =
                    MediaFormatClassifier.classify(media, selection.detectionMode)
                DockThumbnail(
                    media = media,
                    classification = classification,
                    thumbnailLoader = thumbnailLoader,
                    selected = media.id == selection.media.id,
                    enabled =
                        !switchingMedia &&
                            !convertingMedia &&
                            classification.format.isFullSpaceSupported(),
                    onClick = { onSelect(media) },
                )
            }
        }
    }
}

@Composable
private fun DockThumbnail(
    media: GalleryMedia,
    classification: MediaClassification,
    thumbnailLoader: GalleryThumbnailLoader,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bitmap by
        produceState<Bitmap?>(initialValue = null, key1 = media.id) {
            value = thumbnailLoader.load(media)
        }
    Box(
        modifier =
            Modifier.size(width = DockThumbnailWidth, height = 96.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 3.dp,
                            color = DockGreen,
                            shape = RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                )
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = media.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                text = classification.format.label,
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .background(Color(0xD9FFFFFF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                style = PicoTheme.typography.labelSmall,
                color = if (enabled) DockText else DockMutedText,
            )
        }
    }
}

private suspend fun loadMesh(
    selection: MediaSelection,
    flatAs360: Boolean = false,
): MeshResource =
    loadMesh(
        if (flatAs360 && selection.classification.format == GalleryMediaFormat.Flat2D) {
            GalleryMediaFormat.Mono360
        } else {
            selection.classification.format
        }
    )

private suspend fun loadMesh(format: GalleryMediaFormat): MeshResource =
    withContext(Dispatchers.IO) {
        MeshResource.load(format.meshPath())
    }

private fun GalleryMediaFormat.meshPath(): String =
    when (this) {
        GalleryMediaFormat.Flat2D -> FLAT_IMAGE_MESH_PATH
        GalleryMediaFormat.Stereo180SideBySide -> VR180_MESH_PATH
        GalleryMediaFormat.Stereo360TopBottom,
        GalleryMediaFormat.Mono360 -> EQUIRECTANGULAR_MESH_PATH
        else -> error("Unsupported Full Space format: $label")
    }

private fun GalleryMediaFormat.isFullSpaceSupported(): Boolean =
    this == GalleryMediaFormat.Stereo360TopBottom ||
        this == GalleryMediaFormat.Stereo180SideBySide ||
        this == GalleryMediaFormat.Mono360 ||
        this == GalleryMediaFormat.Flat2D

private fun selectAdjacentFullSpaceMedia(direction: Int) {
    if (
        direction == 0 ||
            FullSpaceUiStore.state.value.switchingMedia ||
            FullSpaceUiStore.state.value.convertingMedia
    ) {
        return
    }
    val current = MediaSelectionStore.current ?: return
    val currentIndex = current.folderMedia.indexOfFirst { it.id == current.media.id }
    if (currentIndex < 0) return

    var nextIndex = currentIndex + direction
    while (nextIndex in current.folderMedia.indices) {
        val media = current.folderMedia[nextIndex]
        val classification = MediaFormatClassifier.classify(media, current.detectionMode)
        if (classification.format.isFullSpaceSupported()) {
            FullSpaceUiStore.restoreProjectionOverride(media.id)
            MediaSelectionStore.selectFromCurrentFolder(media)
            return
        }
        nextIndex += direction
    }
}

internal fun GalleryMediaFormat.isStereo(): Boolean =
    this == GalleryMediaFormat.Stereo360TopBottom ||
        this == GalleryMediaFormat.Stereo180SideBySide

private data class PinchMetrics(
    val tipDistanceMeters: Float,
    val indexFromPalmMeters: Float,
)

private fun HandPose?.pinchMetrics(): PinchMetrics? {
    this ?: return null
    val thumbTip = this[HandJoint.Index.THUMB_TIP].position
    val indexTip = this[HandJoint.Index.INDEX_TIP].position
    val palm = this[HandJoint.Index.PALM].position
    return PinchMetrics(
        tipDistanceMeters = Vector3.distance(thumbTip, indexTip),
        indexFromPalmMeters = Vector3.distance(indexTip, palm),
    )
}

private fun PinchMetrics?.isStandardPinch(): Boolean =
    this != null &&
        tipDistanceMeters <= PINCH_TIPS_ON_METERS &&
        indexFromPalmMeters >= INDEX_FROM_PALM_MIN_METERS

private fun PinchMetrics?.isReleased(): Boolean =
    this == null ||
        tipDistanceMeters >= PINCH_TIPS_OFF_METERS ||
        indexFromPalmMeters < INDEX_FROM_PALM_MIN_METERS

private fun MediaSelection.fullSpaceTransform(flatAs360: Boolean = false): TransformComponent {
    if (classification.format != GalleryMediaFormat.Flat2D || flatAs360) {
        return TransformComponent().setPosition(Vector3(0f, 1.5f, 0f))
    }

    val aspectRatio = media.width.toFloat() / media.height.toFloat()
    val width: Float
    val height: Float
    if (aspectRatio >= 1f) {
        width = (FLAT_IMAGE_MAX_HEIGHT_METERS * aspectRatio).coerceAtMost(
            FLAT_IMAGE_MAX_WIDTH_METERS
        )
        height = width / aspectRatio
    } else {
        height = FLAT_IMAGE_MAX_HEIGHT_METERS
        width = height * aspectRatio
    }
    return TransformComponent()
        .setPosition(Vector3(0f, FLAT_IMAGE_CENTER_HEIGHT_METERS, -FLAT_IMAGE_DISTANCE_METERS))
        .setScaleVector(Vector3(width, height, 1f))
}

private fun mediaVideoComponent(
    mesh: MeshResource,
    material: VideoMaterial,
    format: GalleryMediaFormat,
): VideoComponent =
    VideoComponent(mesh, material).apply {
        setDisplayMode(
            if (
                format == GalleryMediaFormat.Flat2D ||
                    format == GalleryMediaFormat.Mono360
            ) {
                DisplayMode.MONO
            } else {
                DisplayMode.STEREO
            }
        )
    }

@OptIn(ExperimentalSpatialApi::class)
private fun VideoMaterial.setTextures(textures: PanoramaTexturePair) {
    setDimensionMode(textures.dimensionMode)
    textures.surfaceRenderTexture?.let {
        bindSurfaceRenderTexture(it)
        return
    }
    if (getBindSurfaceRenderTexture() != null) {
        unbindSurfaceRenderTexture()
    }
    val left = requireNotNull(textures.left) { "Left panorama texture is missing" }
    if (textures.sharesSingleTexture) {
        setTexture(left)
    } else {
        setTexture(
            left,
            requireNotNull(textures.right) { "Right panorama texture is missing" },
        )
    }
}

@OptIn(ExperimentalSpatialApi::class)
private fun createPanoramaMaterial(textures: PanoramaTexturePair): VideoMaterial {
    val material =
        VideoMaterial(
            blendingMode = BlendingMode.OPAQUE,
            videoDimensionMode = textures.dimensionMode,
            cullingMode = MaterialCullingMode.NONE,
        )
    return try {
        material.setTextures(textures)
        material
    } catch (error: Throwable) {
        material.close()
        throw error
    }
}

private class FullSpaceResources {
    private var panorama: Entity? = null
    private var mesh: MeshResource? = null
    private var material: VideoMaterial? = null
    private var textures: PanoramaTexturePair? = null
    private var appliedSwapEyes: Boolean? = null
    private var appliedSelection: MediaSelection? = null
    private var appliedFlatAs360: Boolean? = null

    fun isFlatProjectionApplied(
        mediaId: Long,
        flatAs360: Boolean,
    ): Boolean =
        appliedSelection?.media?.id == mediaId && appliedFlatAs360 == flatAs360

    fun isEyeOrderApplied(
        mediaId: Long,
        swapEyes: Boolean,
    ): Boolean =
        appliedSelection?.media?.id == mediaId && appliedSwapEyes == swapEyes

    fun bind(
        panorama: Entity,
        mesh: MeshResource,
        material: VideoMaterial,
        textures: PanoramaTexturePair,
        selection: MediaSelection,
        flatAs360: Boolean,
    ) {
        this.panorama = panorama
        this.mesh = mesh
        this.material = material
        this.textures = textures
        appliedFlatAs360 = flatAs360
        appliedSelection = selection
        appliedSwapEyes = textures.eyeOrderSwapped
    }

    @OptIn(ExperimentalSpatialApi::class)
    fun replace(
        selection: MediaSelection,
        flatAs360: Boolean,
        mesh: MeshResource,
        textures: PanoramaTexturePair,
    ) {
        val panorama = requireNotNull(panorama) { "Panorama entity is not ready" }
        val nextMaterial = createPanoramaMaterial(textures)
        try {
            panorama.components.set(selection.fullSpaceTransform(flatAs360))
            panorama.components.set(
                mediaVideoComponent(
                    mesh = mesh,
                    material = nextMaterial,
                    format =
                        if (flatAs360) {
                            GalleryMediaFormat.Mono360
                        } else {
                            selection.classification.format
                        },
                )
            )
        } catch (error: Throwable) {
            nextMaterial.close()
            mesh.close()
            textures.close()
            throw error
        }

        this.material?.close()
        this.mesh?.close()
        this.textures?.close()
        this.material = nextMaterial
        this.mesh = mesh
        this.textures = textures
        appliedSelection = selection
        appliedFlatAs360 = flatAs360
        appliedSwapEyes = textures.eyeOrderSwapped
    }

    @OptIn(ExperimentalSpatialApi::class)
    fun replaceProjection(
        selection: MediaSelection,
        flatAs360: Boolean,
        mesh: MeshResource,
    ) {
        val panorama = requireNotNull(panorama) { "Panorama entity is not ready" }
        val material = requireNotNull(material) { "Panorama material is not ready" }
        try {
            panorama.components.set(selection.fullSpaceTransform(flatAs360))
            panorama.components.set(
                mediaVideoComponent(
                    mesh = mesh,
                    material = material,
                    format =
                        if (flatAs360) {
                            GalleryMediaFormat.Mono360
                        } else {
                            GalleryMediaFormat.Flat2D
                        },
                )
            )
        } catch (error: Throwable) {
            mesh.close()
            throw error
        }

        this.mesh?.close()
        this.mesh = mesh
        appliedSelection = selection
        appliedFlatAs360 = flatAs360
    }

    @OptIn(ExperimentalSpatialApi::class)
    fun replaceTextures(
        selection: MediaSelection,
        nextTextures: PanoramaTexturePair,
    ) {
        val panorama = requireNotNull(panorama) { "Panorama entity is not ready" }
        val mesh = requireNotNull(mesh) { "Panorama mesh is not ready" }
        val nextMaterial = createPanoramaMaterial(nextTextures)
        try {
            panorama.components.set(
                mediaVideoComponent(
                    mesh = mesh,
                    material = nextMaterial,
                    format = selection.classification.format,
                )
            )
        } catch (error: Throwable) {
            nextMaterial.close()
            nextTextures.close()
            throw error
        }
        material?.close()
        textures?.close()
        material = nextMaterial
        textures = nextTextures
        appliedSelection = selection
        appliedSwapEyes = nextTextures.eyeOrderSwapped
    }

    fun release() {
        panorama?.destroy()
        material?.close()
        mesh?.close()
        textures?.close()
        panorama = null
        material = null
        mesh = null
        textures = null
        appliedSwapEyes = null
        appliedSelection = null
        appliedFlatAs360 = null
    }

    private fun PanoramaTexturePair.close() {
        left?.close()
        if (!sharesSingleTexture) {
            right?.close()
        }
        surfaceRenderTexture?.close()
    }
}

package com.picoxr.stereoviewer.content

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
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
import com.pico.spatial.core.container.getSelfSpaceState
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.picoxr.stereoviewer.MEDIA_PREVIEW_WINDOW_ID
import com.picoxr.stereoviewer.conversion.BatchConversionResult
import com.picoxr.stereoviewer.conversion.BatchFailure
import com.picoxr.stereoviewer.conversion.BatchFisheyeConverter
import com.picoxr.stereoviewer.conversion.BatchProgress
import com.picoxr.stereoviewer.conversion.FisheyeConversionService
import com.picoxr.stereoviewer.media.DetectionMode
import com.picoxr.stereoviewer.media.GalleryMedia
import com.picoxr.stereoviewer.media.GalleryThumbnailLoader
import com.picoxr.stereoviewer.media.MediaClassification
import com.picoxr.stereoviewer.media.MediaFormatClassifier
import com.picoxr.stereoviewer.media.MediaSelectionStore
import com.picoxr.stereoviewer.media.MediaStoreImageRepository
import com.picoxr.stereoviewer.media.galleryFolderKey
import com.picoxr.stereoviewer.media.sharesGalleryFolder
import com.picoxr.stereoviewer.media.withGeneratedFisheyeNextToSource
import com.picoxr.stereoviewer.platform.ImageAccessController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val LOG_TAG = "PicoStereoViewer"
private const val MEDIA_STORE_REFRESH_DELAY_MS = 300L
private val PageBackground = Color(0xFF101113)
private val Surface = Color(0xFF1A1B1E)
private val SurfaceRaised = Color(0xFF242529)
private val PrimaryText = Color(0xFFF4F4F5)
private val SecondaryText = Color(0xFFA7A7AB)
private val Border = Color(0xFF38393E)
private val HoverGreen = Color(0xFF30A472)
private val SelectedBorder = Color(0xFFF4F4F5)

private enum class GalleryViewMode {
    Waterfall,
    Files,
}

private enum class GalleryTimeSection(val title: String) {
    Today("今天"),
    Yesterday("昨天"),
    Earlier("早些时候"),
}

private enum class FileTimeSection(val title: String) {
    Today("今天"),
    Yesterday("昨天"),
    LastWeek("上周"),
    Earlier("早些时候"),
}

private sealed interface BatchUiState {
    data object Idle : BatchUiState
    data class Running(val progress: BatchProgress) : BatchUiState
    data class Completed(val result: BatchConversionResult) : BatchUiState
    data class Cancelled(val progress: BatchProgress?) : BatchUiState
}

@Composable
fun HomePage() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val repository = remember(context) { MediaStoreImageRepository(context) }
    val thumbnailLoader = remember(context) { GalleryThumbnailLoader(context) }
    val hasImageAccess by ImageAccessController.granted.collectAsState()

    var images by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(GalleryViewMode.Waterfall) }
    val detectionMode = DetectionMode.Smart
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedMedia by remember { mutableStateOf<GalleryMedia?>(null) }

    LaunchedEffect(Unit) {
        Log.i(LOG_TAG, "HomePage composed spaceState=${context.getSelfSpaceState()}")
        ImageAccessController.refresh(context)
    }
    LaunchedEffect(hasImageAccess) {
        if (hasImageAccess) {
            repository.observeChanges().collectLatest {
                delay(MEDIA_STORE_REFRESH_DELAY_MS)
                isLoading = images.isEmpty()
                loadError = null
                runCatching { repository.loadImages() }
                    .onSuccess {
                        images = it
                        Log.i(LOG_TAG, "MediaStore loaded ${it.size} images")
                    }
                    .onFailure {
                        loadError = it.message ?: it.javaClass.simpleName
                        Log.e(LOG_TAG, "MediaStore query failed", it)
                    }
                isLoading = false
            }
        }
    }

    val openPreview: (GalleryMedia) -> Unit = { media ->
        selectedMedia = media
        val previewToken =
            MediaSelectionStore.createPreview(
            media = media,
            detectionMode = detectionMode,
            folderMedia = images.filter { it.sharesGalleryFolder(media) },
        )
        navigator.openWindowContainer(
            id = MEDIA_PREVIEW_WINDOW_ID,
            tag = previewToken,
            bundle =
                Bundle().apply {
                    putString(MediaSelectionStore.PREVIEW_TOKEN_KEY, previewToken)
                },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            GalleryHeader(
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
            )

            when {
                !hasImageAccess ->
                    PermissionPanel(
                        modifier = Modifier.weight(1f),
                        onRequestPermission = {
                            context.findActivity()?.let(ImageAccessController::request)
                        }
                    )
                isLoading ->
                    GalleryMessage(
                        message = "Loading media library...",
                        modifier = Modifier.weight(1f),
                    )
                loadError != null ->
                    GalleryMessage(
                        message = "Library error: $loadError",
                        modifier = Modifier.weight(1f),
                    )
                images.isEmpty() ->
                    GalleryMessage(
                        message = "No images found",
                        modifier = Modifier.weight(1f),
                    )
                viewMode == GalleryViewMode.Waterfall ->
                    WaterfallGallery(
                        images = images,
                        detectionMode = detectionMode,
                        thumbnailLoader = thumbnailLoader,
                        selectedMedia = selectedMedia,
                        onSelect = openPreview,
                        modifier = Modifier.weight(1f),
                    )
                else ->
                    FileManagerGallery(
                        images = images,
                        detectionMode = detectionMode,
                        thumbnailLoader = thumbnailLoader,
                        selectedFolder = selectedFolder,
                        selectedMedia = selectedMedia,
                        onFolderSelect = { selectedFolder = it },
                        onMediaSelect = openPreview,
                        modifier = Modifier.weight(1f),
                    )
            }
    }
}

@Composable
private fun GalleryHeader(
    viewMode: GalleryViewMode,
    onViewModeChange: (GalleryViewMode) -> Unit,
) {
    val waterfallInteraction = remember { MutableInteractionSource() }
    val filesInteraction = remember { MutableInteractionSource() }
    val waterfallHovered by waterfallInteraction.collectIsHoveredAsState()
    val filesHovered by filesInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "BinoXR",
            style = PicoTheme.typography.titleLarge,
            color = PrimaryText,
        )
        Spacer(modifier = Modifier.weight(1f))
        SegmentControl(
            modifier = Modifier.width(220.dp),
            backgroundColor = Surface,
        ) {
            SegmentItem(
                selected = viewMode == GalleryViewMode.Waterfall,
                onClick = { onViewModeChange(GalleryViewMode.Waterfall) },
                interactionSource = waterfallInteraction,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor =
                            if (waterfallHovered) HoverGreen else Color.Transparent,
                        itemContentColor = PrimaryText,
                        selectedItemBackgroundColor = HoverGreen,
                        selectedItemContentColor = Color.White,
                    ),
                title = { Text("Waterfall") },
            )
            SegmentItem(
                selected = viewMode == GalleryViewMode.Files,
                onClick = { onViewModeChange(GalleryViewMode.Files) },
                interactionSource = filesInteraction,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = if (filesHovered) HoverGreen else Color.Transparent,
                        itemContentColor = PrimaryText,
                        selectedItemBackgroundColor = HoverGreen,
                        selectedItemContentColor = Color.White,
                    ),
                title = { Text("Files") },
            )
        }
    }
}

@Composable
private fun PermissionPanel(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Photo access required",
            style = PicoTheme.typography.titleMedium,
            color = PrimaryText,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRequestPermission, size = ButtonDefaults.Small) {
            Text("Choose photos")
        }
    }
}

@Composable
private fun GalleryMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(message, style = PicoTheme.typography.bodyLarge, color = SecondaryText)
    }
}

@Composable
private fun WaterfallGallery(
    images: List<GalleryMedia>,
    detectionMode: DetectionMode,
    thumbnailLoader: GalleryThumbnailLoader,
    selectedMedia: GalleryMedia?,
    onSelect: (GalleryMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections =
        remember(images) {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val grouped =
                images.sortedByDescending { it.dateAddedSeconds }.groupBy {
                    it.timeSection(today, zone)
                }
            GalleryTimeSection.entries.mapNotNull { section ->
                grouped[section]?.let { section to it }
            }
        }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(3),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        sections.forEach { (section, sectionImages) ->
            item(
                key = "time-${section.name}",
                span = StaggeredGridItemSpan.FullLine,
            ) {
                Text(
                    text = section.title,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                    style = PicoTheme.typography.titleMedium,
                    color = PrimaryText,
                )
            }
            items(items = sectionImages, key = { it.id }) { media ->
                GalleryCard(
                    media = media,
                    classification = MediaFormatClassifier.classify(media, detectionMode),
                    thumbnailLoader = thumbnailLoader,
                    selected = selectedMedia?.id == media.id,
                    onClick = { onSelect(media) },
                )
            }
        }
    }
}

@Composable
private fun GalleryCard(
    media: GalleryMedia,
    classification: MediaClassification,
    thumbnailLoader: GalleryThumbnailLoader,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val previewRatio = (media.width.toFloat() / media.height.toFloat()).coerceIn(0.72f, 1.55f)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(Surface, RoundedCornerShape(16.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) SelectedBorder else Border,
                    shape = RoundedCornerShape(16.dp),
                )
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(previewRatio)) {
            MediaThumbnail(
                media = media,
                thumbnailLoader = thumbnailLoader,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = classification.format.label,
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xD9101113), RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                style = PicoTheme.typography.labelSmall,
                color = PrimaryText,
            )
        }
        Text(
            text = media.displayName,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            style = PicoTheme.typography.bodySmall,
            color = PrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileManagerGallery(
    images: List<GalleryMedia>,
    detectionMode: DetectionMode,
    thumbnailLoader: GalleryThumbnailLoader,
    selectedFolder: String?,
    selectedMedia: GalleryMedia?,
    onFolderSelect: (String?) -> Unit,
    onMediaSelect: (GalleryMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var batchState by remember { mutableStateOf<BatchUiState>(BatchUiState.Idle) }
    var batchJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose { batchJob?.cancel() }
    }

    val folders =
        remember(images) {
            images.groupBy { it.galleryFolderKey() }
                .map { (path, folderImages) ->
                    Triple(
                        path,
                        folderImages.size,
                        folderImages.maxOfOrNull { it.dateAddedSeconds } ?: 0L,
                    )
                }
                .sortedByDescending { it.third }
        }
    val visibleImages =
        remember(images, selectedFolder) {
            selectedFolder?.let { folder ->
                images.filter { it.galleryFolderKey() == folder }
            }?.sortedByDescending { it.dateAddedSeconds }
                ?.withGeneratedFisheyeNextToSource()
                ?: images
        }
    val allImageSections =
        remember(visibleImages, selectedFolder) {
            if (selectedFolder != null) {
                emptyList()
            } else {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val grouped = visibleImages.groupBy { it.fileTimeSection(today, zone) }
                FileTimeSection.entries.mapNotNull { section ->
                    grouped[section]?.let { section to it }
                }
            }
        }
    val convertibleImages =
        remember(visibleImages, selectedFolder) {
            if (selectedFolder == null) {
                emptyList()
            } else {
                val existingNames = visibleImages.mapTo(hashSetOf()) {
                    it.displayName.lowercase()
                }
                visibleImages.filter { media ->
                    MediaFormatClassifier.classify(media, DetectionMode.Smart).format ==
                            com.picoxr.stereoviewer.media.GalleryMediaFormat.Flat2D
                        && FisheyeConversionService.outputName(media, 2048).lowercase() !in
                            existingNames
                }
            }
        }
    val startBatch: (List<GalleryMedia>) -> Unit = { targets ->
        batchJob?.cancel()
        batchJob =
            coroutineScope.launch {
                var latestProgress: BatchProgress? = null
                try {
                    val result =
                        BatchFisheyeConverter.convert(
                            context = context,
                            inputs = targets,
                            eyeSize = 2048,
                            onProgress = { progress ->
                                latestProgress = progress
                                batchState = BatchUiState.Running(progress)
                            },
                        )
                    batchState = BatchUiState.Completed(result)
                    if (result.failures.isEmpty()) {
                        delay(800)
                        batchState = BatchUiState.Idle
                    }
                } catch (cancellation: CancellationException) {
                    batchState = BatchUiState.Cancelled(latestProgress)
                }
            }
    }

    Row(
        modifier = modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)),
    ) {
        LazyColumn(
            modifier = Modifier.width(190.dp).fillMaxHeight().padding(vertical = 8.dp),
        ) {
            item {
                FolderRow(
                    label = "All images",
                    count = images.size,
                    selected = selectedFolder == null,
                    onClick = { onFolderSelect(null) },
                )
            }
            items(folders, key = { it.first }) { (folder, count, _) ->
                FolderRow(
                    label = folder.trimEnd('/').substringAfterLast('/'),
                    count = count,
                    selected = selectedFolder == folder,
                    onClick = { onFolderSelect(folder) },
                )
            }
        }
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Border))
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (selectedFolder != null) {
                FolderBatchBar(
                    convertibleCount = convertibleImages.size,
                    state = batchState,
                    onStart = { startBatch(convertibleImages) },
                    onCancel = { batchJob?.cancel() },
                    onRetry = { failures ->
                        startBatch(failures.map { it.media })
                    },
                )
            }
            FileHeader()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (selectedFolder == null) {
                    allImageSections.forEach { (section, sectionImages) ->
                        item(key = "file-time-${section.name}") {
                            FileDateHeader(section.title)
                        }
                        items(sectionImages, key = { it.id }) { media ->
                            FileRow(
                                media = media,
                                classification =
                                    MediaFormatClassifier.classify(media, detectionMode),
                                thumbnailLoader = thumbnailLoader,
                                selected = selectedMedia?.id == media.id,
                                onClick = { onMediaSelect(media) },
                            )
                        }
                    }
                } else {
                    items(visibleImages, key = { it.id }) { media ->
                        FileRow(
                            media = media,
                            classification = MediaFormatClassifier.classify(media, detectionMode),
                            thumbnailLoader = thumbnailLoader,
                            selected = selectedMedia?.id == media.id,
                            onClick = { onMediaSelect(media) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDateHeader(title: String) {
    Text(
        text = title,
        modifier =
            Modifier.fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        style = PicoTheme.typography.titleMedium,
        color = PrimaryText,
    )
}

@Composable
private fun FolderBatchBar(
    convertibleCount: Int,
    state: BatchUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (List<BatchFailure>) -> Unit,
) {
    val progressFraction =
        when (state) {
            BatchUiState.Idle -> 0f
            is BatchUiState.Running -> state.progress.fraction
            is BatchUiState.Completed -> 1f
            is BatchUiState.Cancelled -> state.progress?.fraction ?: 0f
        }
    val status =
        when (state) {
            BatchUiState.Idle ->
                if (convertibleCount > 0) {
                    "$convertibleCount 张图片可转换 · 单眼 2K · 双目 4K"
                } else {
                    "此文件夹没有待转换图片"
                }
            is BatchUiState.Running -> {
                val progress = state.progress
                val stage = progress.stage?.label ?: "Preparing"
                "${(progress.fraction * 100).toInt()}% · " +
                    "${progress.completed}/${progress.total} · $stage" +
                    progress.currentName?.let { " · $it" }.orEmpty()
            }
            is BatchUiState.Completed ->
                "完成 ${state.result.succeeded} · 跳过 ${state.result.skipped} · " +
                    "失败 ${state.result.failures.size}"
            is BatchUiState.Cancelled ->
                state.progress?.let {
                    "已取消 · 完成 ${it.completed}/${it.total} · 失败 ${it.failed}"
                } ?: "已取消"
        }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(topEnd = 16.dp))
                .background(SurfaceRaised),
    ) {
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                    .background(HoverGreen),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                style = PicoTheme.typography.bodySmall,
                color = if (progressFraction > 0f) Color.White else SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                is BatchUiState.Running ->
                    Button(
                        onClick = onCancel,
                        size = ButtonDefaults.Small,
                    ) {
                        Text("取消")
                    }
                is BatchUiState.Completed ->
                    if (state.result.failures.isNotEmpty()) {
                        Button(
                            onClick = { onRetry(state.result.failures) },
                            size = ButtonDefaults.Small,
                        ) {
                            Text("重试失败")
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = convertibleCount > 0,
                            size = ButtonDefaults.Small,
                        ) {
                            Text("批量转化鱼眼")
                        }
                    }
                else ->
                    Button(
                        onClick = onStart,
                        enabled = convertibleCount > 0,
                        size = ButtonDefaults.Small,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = HoverGreen,
                                contentColor = Color.White,
                            ),
                    ) {
                        Text("批量转化鱼眼")
                    }
            }
        }
    }
}

@Composable
private fun FolderRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val containerColor =
        when {
            selected -> HoverGreen
            hovered -> HoverGreen.copy(alpha = 0.55f)
            else -> Color.Transparent
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = PicoTheme.typography.bodySmall,
            color = if (selected || hovered) Color.White else SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = count.toString(),
            style = PicoTheme.typography.labelSmall,
            color = if (selected || hovered) Color.White else SecondaryText,
        )
    }
}

@Composable
private fun FileHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Name",
            modifier = Modifier.weight(1f),
            style = PicoTheme.typography.labelSmall,
            color = SecondaryText,
        )
        Text(
            "Format",
            modifier = Modifier.width(78.dp),
            style = PicoTheme.typography.labelSmall,
            color = SecondaryText,
        )
        Text(
            "Size",
            modifier = Modifier.width(104.dp),
            style = PicoTheme.typography.labelSmall,
            color = SecondaryText,
        )
    }
}

@Composable
private fun FileRow(
    media: GalleryMedia,
    classification: MediaClassification,
    thumbnailLoader: GalleryThumbnailLoader,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(58.dp)
                .background(if (selected) SurfaceRaised else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MediaThumbnail(
            media = media,
            thumbnailLoader = thumbnailLoader,
            modifier = Modifier.size(width = 60.dp, height = 44.dp),
        )
        Text(
            text = media.displayName,
            modifier = Modifier.weight(1f),
            style = PicoTheme.typography.bodySmall,
            color = PrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = classification.format.label,
            modifier = Modifier.width(68.dp),
            style = PicoTheme.typography.labelSmall,
            color = PrimaryText,
        )
        Text(
            text = "${media.width} x ${media.height}",
            modifier = Modifier.width(104.dp),
            style = PicoTheme.typography.labelSmall,
            color = SecondaryText,
        )
    }
}

@Composable
private fun MediaThumbnail(
    media: GalleryMedia,
    thumbnailLoader: GalleryThumbnailLoader,
    modifier: Modifier = Modifier,
) {
    val bitmap by
        produceState<Bitmap?>(initialValue = null, key1 = media.id) {
            value = thumbnailLoader.load(media)
        }
    Box(modifier = modifier.background(SurfaceRaised), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = media.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun GalleryMedia.timeSection(
    today: LocalDate,
    zone: ZoneId,
): GalleryTimeSection {
    val addedDate = Instant.ofEpochSecond(dateAddedSeconds).atZone(zone).toLocalDate()
    return when (addedDate) {
        today -> GalleryTimeSection.Today
        today.minusDays(1) -> GalleryTimeSection.Yesterday
        else -> GalleryTimeSection.Earlier
    }
}

private fun GalleryMedia.fileTimeSection(
    today: LocalDate,
    zone: ZoneId,
): FileTimeSection {
    val addedDate = Instant.ofEpochSecond(dateAddedSeconds).atZone(zone).toLocalDate()
    return when {
        addedDate == today -> FileTimeSection.Today
        addedDate == today.minusDays(1) -> FileTimeSection.Yesterday
        !addedDate.isBefore(today.minusDays(7)) -> FileTimeSection.LastWeek
        else -> FileTimeSection.Earlier
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

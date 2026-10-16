package com.picoxr.stereoviewer.media

private val GENERATED_FISHEYE_SUFFIX =
    Regex("""_BinoXR_FISHEYE_SBS180_PCV2_(\d+)\.png$""", RegexOption.IGNORE_CASE)

fun List<GalleryMedia>.withGeneratedFisheyeNextToSource(): List<GalleryMedia> {
    if (size < 2) return this

    val generatedBySource =
        filter { it.generatedFisheyeSourceStem() != null }
            .groupBy { media ->
                media.logicalFolderPath() to requireNotNull(media.generatedFisheyeSourceStem())
            }
    val generatedIds = generatedBySource.values.flatten().mapTo(mutableSetOf()) { it.id }

    return buildList(size) {
        for (media in this@withGeneratedFisheyeNextToSource) {
            if (media.id in generatedIds) continue
            add(media)
            generatedBySource[media.logicalFolderPath() to media.fileStem()]
                .orEmpty()
                .sortedWith(
                    compareByDescending<GalleryMedia> {
                            it.generatedFisheyeEyeSize() ?: Int.MIN_VALUE
                        }
                        .thenByDescending { it.dateModifiedSeconds }
                        .thenByDescending { it.id }
                )
                .forEach(::add)
        }

        this@withGeneratedFisheyeNextToSource
            .filter { it.id in generatedIds && none { added -> added.id == it.id } }
            .forEach(::add)
    }
}

fun List<GalleryMedia>.insertingGeneratedFisheye(
    sourceId: Long,
    generated: GalleryMedia,
): List<GalleryMedia> {
    val withoutGenerated = filterNot { it.id == generated.id }
    val sourceIndex = withoutGenerated.indexOfFirst { it.id == sourceId }
    if (sourceIndex < 0) return (withoutGenerated + generated).withGeneratedFisheyeNextToSource()

    return buildList(withoutGenerated.size + 1) {
        addAll(withoutGenerated.subList(0, sourceIndex + 1))
        add(generated)
        addAll(withoutGenerated.subList(sourceIndex + 1, withoutGenerated.size))
    }.withGeneratedFisheyeNextToSource()
}

fun GalleryMedia.sharesGalleryFolder(other: GalleryMedia): Boolean =
    galleryFolderKey().equals(other.galleryFolderKey(), ignoreCase = true)

fun GalleryMedia.galleryFolderKey(): String =
    logicalFolderPath().ifBlank { "/" }

private fun GalleryMedia.generatedFisheyeSourceStem(): String? =
    GENERATED_FISHEYE_SUFFIX.find(displayName)?.let { match ->
        displayName.substring(0, match.range.first)
    }

private fun GalleryMedia.generatedFisheyeEyeSize(): Int? =
    GENERATED_FISHEYE_SUFFIX.find(displayName)?.groupValues?.get(1)?.toIntOrNull()

private fun GalleryMedia.fileStem(): String =
    displayName.substringBeforeLast('.', displayName)

private fun GalleryMedia.logicalFolderPath(): String {
    val normalized = relativePath.replace('\\', '/').trim('/')
    return if (normalized.startsWith("Pictures/", ignoreCase = true)) {
        normalized.substring("Pictures/".length)
    } else {
        normalized
    }
}

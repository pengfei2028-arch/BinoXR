package com.picoxr.stereoviewer.conversion

import android.content.Context
import com.picoxr.stereoviewer.media.GalleryMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BatchProgress(
    val completed: Int,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val currentName: String?,
    val stage: ConversionStage?,
) {
    val fraction: Float
        get() {
            if (total <= 0) return 0f
            val stageFraction =
                when (stage) {
                    ConversionStage.LoadingModel -> 0.05f
                    ConversionStage.Decoding -> 0.10f
                    ConversionStage.EstimatingDepth -> 0.45f
                    ConversionStage.GeneratingLeftEye -> 0.55f
                    ConversionStage.ProjectingLeftEye -> 0.68f
                    ConversionStage.GeneratingRightEye -> 0.78f
                    ConversionStage.ProjectingRightEye -> 0.88f
                    ConversionStage.Saving -> 0.95f
                    ConversionStage.Verifying -> 0.99f
                    null -> 0f
                }
            return ((completed + stageFraction) / total).coerceIn(0f, 1f)
        }
}

data class BatchFailure(
    val media: GalleryMedia,
    val message: String,
)

data class BatchConversionResult(
    val succeeded: Int,
    val skipped: Int,
    val failures: List<BatchFailure>,
)

object BatchFisheyeConverter {
    suspend fun convert(
        context: Context,
        inputs: List<GalleryMedia>,
        eyeSize: Int = 2048,
        onProgress: (BatchProgress) -> Unit,
    ): BatchConversionResult {
        var succeeded = 0
        var skipped = 0
        val failures = mutableListOf<BatchFailure>()
        onProgress(
            BatchProgress(
                completed = 0,
                total = inputs.size,
                succeeded = 0,
                failed = 0,
                skipped = 0,
                currentName = null,
                stage = null,
            )
        )

        inputs.forEachIndexed { index, media ->
            currentCoroutineContext().ensureActive()
            try {
                val result =
                    FisheyeConversionService.convertSingle(
                        context = context,
                        media = media,
                        eyeSize = eyeSize,
                        onStage = { stage ->
                            onProgress(
                                BatchProgress(
                                    completed = index,
                                    total = inputs.size,
                                    succeeded = succeeded,
                                    failed = failures.size,
                                    skipped = skipped,
                                    currentName = media.displayName,
                                    stage = stage,
                                )
                            )
                        },
                    )
                if (result.reusedExistingOutput) skipped += 1 else succeeded += 1
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failures +=
                    BatchFailure(
                        media = media,
                        message = error.message ?: error.javaClass.simpleName,
                    )
            }
            onProgress(
                BatchProgress(
                    completed = index + 1,
                    total = inputs.size,
                    succeeded = succeeded,
                    failed = failures.size,
                    skipped = skipped,
                    currentName = null,
                    stage = null,
                )
            )
        }
        return BatchConversionResult(
            succeeded = succeeded,
            skipped = skipped,
            failures = failures,
        )
    }
}

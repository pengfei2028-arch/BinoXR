package com.picoxr.stereoviewer.conversion

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal object StereoFisheyeRenderer {
    private const val SOURCE_HORIZONTAL_FOV_DEG = 75f
    private const val DISPARITY_DEG = 0.9f
    private const val SUPERSAMPLE = 2
    private const val HOLE_FEATHER = 0.5f
    private const val EDGE_MIRROR = 0.10f
    private const val EDGE_DARKEN = 0.25f
    private const val FILL_PROXY_MAX_SIZE = 512

    data class Result(
        val bitmap: Bitmap,
        val convergence: Float,
    )

    private data class WarpedEye(
        val pixels: IntArray,
        val holes: BooleanArray,
    )

    private data class FillPlan(
        val size: Int,
        val samples: IntArray,
        val valid: BooleanArray,
        val circle: BooleanArray,
        val nearestX: IntArray,
        val nearestY: IntArray,
        val distance: FloatArray,
    )

    private data class ProjectionMap(
        val size: Int,
        val samples: IntArray,
        val circle: BooleanArray,
    )

    fun render(
        source: Bitmap,
        depth: DepthMap,
        eyeSize: Int,
        onStage: (ConversionStage) -> Unit = {},
    ): Result {
        require(source.width == depth.width && source.height == depth.height)
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)

        val convergence = median(depth.values)
        val maxDisparity = DISPARITY_DEG * source.width / SOURCE_HORIZONTAL_FOV_DEG
        val projectionMap = buildProjectionMap(source.width, source.height, eyeSize)
        val fillPlan =
            buildFillPlan(
                source.width,
                source.height,
                min(eyeSize, FILL_PROXY_MAX_SIZE),
            )

        onStage(ConversionStage.GeneratingLeftEye)
        var flat =
            warpEye(
                sourcePixels,
                depth.values,
                source.width,
                source.height,
                convergence,
                maxDisparity * 0.5f,
            )
        onStage(ConversionStage.ProjectingLeftEye)
        val leftFish =
            projectEquidistant(
                flat.pixels,
                source.width,
                source.height,
                eyeSize,
                projectionMap,
                fillPlan,
            )
        flat = WarpedEye(IntArray(0), BooleanArray(0))

        onStage(ConversionStage.GeneratingRightEye)
        flat =
            warpEye(
                sourcePixels,
                depth.values,
                source.width,
                source.height,
                convergence,
                maxDisparity * -0.5f,
            )
        onStage(ConversionStage.ProjectingRightEye)
        val rightFish =
            projectEquidistant(
                flat.pixels,
                source.width,
                source.height,
                eyeSize,
                projectionMap,
                fillPlan,
            )

        val packed = IntArray(eyeSize * eyeSize * 2)
        for (y in 0 until eyeSize) {
            val eyeOffset = y * eyeSize
            val packedOffset = y * eyeSize * 2
            leftFish.copyInto(packed, packedOffset, eyeOffset, eyeOffset + eyeSize)
            rightFish.copyInto(
                packed,
                packedOffset + eyeSize,
                eyeOffset,
                eyeOffset + eyeSize,
            )
        }
        return Result(
            bitmap = Bitmap.createBitmap(packed, eyeSize * 2, eyeSize, Bitmap.Config.ARGB_8888),
            convergence = convergence,
        )
    }

    private fun warpEye(
        source: IntArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        convergence: Float,
        disparityScale: Float,
    ): WarpedEye {
        val superWidth = width * SUPERSAMPLE
        val superSize = superWidth * height
        val sourceX = IntArray(superSize) { -1 }
        val targetDepth = FloatArray(superSize) { Float.NEGATIVE_INFINITY }
        val scaledDisparity = disparityScale * SUPERSAMPLE

        for (y in 0 until height) {
            val sourceRow = y * width
            val targetRow = y * superWidth
            for (x in 0 until superWidth) {
                val depthValue = depth[sourceRow + x / SUPERSAMPLE]
                val targetX = (x + (depthValue - convergence) * scaledDisparity).roundToInt()
                if (targetX !in 0 until superWidth) continue
                val targetIndex = targetRow + targetX
                if (depthValue >= targetDepth[targetIndex]) {
                    targetDepth[targetIndex] = depthValue
                    sourceX[targetIndex] = x
                }
            }
        }

        val output = IntArray(width * height)
        val holes = BooleanArray(output.size)
        val leftValid = IntArray(superWidth)
        val rightValid = IntArray(superWidth)
        for (y in 0 until height) {
            val targetRow = y * superWidth
            val outputRow = y * width
            fillNearestValidIndices(sourceX, targetRow, superWidth, leftValid, rightValid)

            for (x in 0 until width) {
                val firstX = x * SUPERSAMPLE
                var mixed = 0
                var holeSamples = 0
                for (subPixel in 0 until SUPERSAMPLE) {
                    val targetX = firstX + subPixel
                    val targetIndex = targetRow + targetX
                    var sampleX = sourceX[targetIndex]
                    if (sampleX < 0) {
                        holeSamples += 1
                        val left = leftValid[targetX]
                        val right = rightValid[targetX]
                        val useLeft =
                            when {
                                left < 0 -> false
                                right < 0 -> true
                                else -> targetDepth[targetRow + left] <= targetDepth[targetRow + right]
                            }
                        sampleX =
                            if (useLeft) {
                                sourceX[targetRow + left] - (targetX - left)
                            } else {
                                sourceX[targetRow + right] + (right - targetX)
                            }
                    }
                    val sourceCoordinate =
                        ((sampleX.coerceIn(0, superWidth - 1) + 0.5f) / SUPERSAMPLE) - 0.5f
                    val color =
                        bilinearSample(
                            source,
                            width,
                            height,
                            sourceCoordinate.coerceIn(0f, width - 1f),
                            y.toFloat(),
                        )
                    mixed = if (subPixel == 0) color else blendRgb(mixed, color, 0.5f)
                }
                val outputIndex = outputRow + x
                output[outputIndex] = mixed
                holes[outputIndex] = holeSamples > 0
            }
        }
        softenHoles(output, holes, width, height)
        return WarpedEye(output, holes)
    }

    private fun softenHoles(
        pixels: IntArray,
        holes: BooleanArray,
        width: Int,
        height: Int,
    ) {
        if (!holes.any { it }) return
        val dilated = FloatArray(holes.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var found = false
                for (dy in -1..1) {
                    val sampleY = y + dy
                    if (sampleY !in 0 until height) continue
                    for (dx in -1..1) {
                        val sampleX = x + dx
                        if (sampleX in 0 until width && holes[sampleY * width + sampleX]) {
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
                if (found) dilated[y * width + x] = 1f
            }
        }
        val mask = gaussian5(dilated, width, height)
        val blurred = gaussian5Rgb(pixels, width, height)
        for (index in pixels.indices) {
            val amount = (mask[index] * HOLE_FEATHER).coerceIn(0f, 1f)
            if (amount > 0f) pixels[index] = blendRgb(pixels[index], blurred[index], amount)
        }
    }

    private fun projectEquidistant(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        outputSize: Int,
        projectionMap: ProjectionMap,
        fillPlan: FillPlan,
    ): IntArray {
        val output = IntArray(outputSize * outputSize) { 0xFF000000.toInt() }
        projectValidPixels(source, sourceWidth, sourceHeight, output, projectionMap)

        val proxy = IntArray(fillPlan.size * fillPlan.size) { 0xFF000000.toInt() }
        projectValidPixels(
            source,
            sourceWidth,
            sourceHeight,
            proxy,
            ProjectionMap(fillPlan.size, fillPlan.samples, fillPlan.circle),
        )
        val filledProxy = fillOutsideProxy(proxy, fillPlan)

        for (y in 0 until outputSize) {
            for (x in 0 until outputSize) {
                val index = y * outputSize + x
                if (!projectionMap.circle[index] || projectionMap.samples[index] >= 0) continue
                val proxyX = (x + 0.5f) * fillPlan.size / outputSize - 0.5f
                val proxyY = (y + 0.5f) * fillPlan.size / outputSize - 0.5f
                output[index] =
                    bilinearSample(
                        filledProxy,
                        fillPlan.size,
                        fillPlan.size,
                        proxyX.coerceIn(0f, fillPlan.size - 1f),
                        proxyY.coerceIn(0f, fillPlan.size - 1f),
                    )
            }
        }
        return output
    }

    private fun projectValidPixels(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        output: IntArray,
        projectionMap: ProjectionMap,
    ) {
        for (index in output.indices) {
            val packed = projectionMap.samples[index]
            if (packed < 0) continue
            val x = (packed and 0xFFFF) / 16f
            val y = (packed ushr 16) / 16f
            output[index] = bilinearSample(source, sourceWidth, sourceHeight, x, y)
        }
    }

    private fun buildFillPlan(
        sourceWidth: Int,
        sourceHeight: Int,
        size: Int,
    ): FillPlan {
        val projectionMap = buildProjectionMap(sourceWidth, sourceHeight, size)
        val valid = BooleanArray(size * size) { projectionMap.samples[it] >= 0 }
        val nearestX = IntArray(valid.size) { -1 }
        val nearestY = IntArray(valid.size) { -1 }
        for (index in valid.indices) {
            if (valid[index]) {
                nearestX[index] = index % size
                nearestY[index] = index / size
            }
        }
        repeat(2) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    relaxNearest(x, y, x - 1, y, size, nearestX, nearestY)
                    relaxNearest(x, y, x - 1, y - 1, size, nearestX, nearestY)
                    relaxNearest(x, y, x, y - 1, size, nearestX, nearestY)
                    relaxNearest(x, y, x + 1, y - 1, size, nearestX, nearestY)
                }
            }
            for (y in size - 1 downTo 0) {
                for (x in size - 1 downTo 0) {
                    relaxNearest(x, y, x + 1, y, size, nearestX, nearestY)
                    relaxNearest(x, y, x + 1, y + 1, size, nearestX, nearestY)
                    relaxNearest(x, y, x, y + 1, size, nearestX, nearestY)
                    relaxNearest(x, y, x - 1, y + 1, size, nearestX, nearestY)
                }
            }
        }
        val distance = FloatArray(valid.size)
        for (index in valid.indices) {
            if (nearestX[index] >= 0) {
                val dx = index % size - nearestX[index]
                val dy = index / size - nearestY[index]
                distance[index] = sqrt((dx * dx + dy * dy).toFloat())
            }
        }
        return FillPlan(
            size,
            projectionMap.samples,
            valid,
            projectionMap.circle,
            nearestX,
            nearestY,
            distance,
        )
    }

    private fun buildProjectionMap(
        sourceWidth: Int,
        sourceHeight: Int,
        outputSize: Int,
    ): ProjectionMap {
        val samples = IntArray(outputSize * outputSize) { -1 }
        val circle = BooleanArray(samples.size)
        val focalLength =
            sourceWidth * 0.5f /
                tan(SOURCE_HORIZONTAL_FOV_DEG * 0.5f * PI.toFloat() / 180f)
        for (y in 0 until outputSize) {
            val normalizedY = (y + 0.5f) / outputSize * 2f - 1f
            for (x in 0 until outputSize) {
                val normalizedX = (x + 0.5f) / outputSize * 2f - 1f
                val radius = sqrt(normalizedX * normalizedX + normalizedY * normalizedY)
                val index = y * outputSize + x
                if (radius > 1f) continue
                circle[index] = true
                val theta = radius * PI.toFloat() * 0.5f
                val directionZ = cos(theta)
                if (directionZ <= 1e-6f) continue
                val radialX = if (radius > 1e-6f) normalizedX / radius else 0f
                val radialY = if (radius > 1e-6f) normalizedY / radius else 0f
                val sinTheta = sin(theta)
                val sourceX =
                    focalLength * sinTheta * radialX / directionZ + sourceWidth * 0.5f
                val sourceY =
                    focalLength * sinTheta * radialY / directionZ + sourceHeight * 0.5f
                if (!sourceX.isFinite() || !sourceY.isFinite()) continue
                if (sourceX !in 0f..(sourceWidth - 1f) ||
                    sourceY !in 0f..(sourceHeight - 1f)
                ) {
                    continue
                }
                val fixedX = (sourceX * 16f).roundToInt().coerceIn(0, 0xFFFF)
                val fixedY = (sourceY * 16f).roundToInt().coerceIn(0, 0x7FFF)
                samples[index] = fixedX or (fixedY shl 16)
            }
        }
        return ProjectionMap(outputSize, samples, circle)
    }

    private fun relaxNearest(
        x: Int,
        y: Int,
        neighborX: Int,
        neighborY: Int,
        size: Int,
        nearestX: IntArray,
        nearestY: IntArray,
    ) {
        if (neighborX !in 0 until size || neighborY !in 0 until size) return
        val index = y * size + x
        val neighbor = neighborY * size + neighborX
        val candidateX = nearestX[neighbor]
        val candidateY = nearestY[neighbor]
        if (candidateX < 0) return
        val candidateDx = x - candidateX
        val candidateDy = y - candidateY
        val candidateDistance = candidateDx * candidateDx + candidateDy * candidateDy
        val currentDistance =
            if (nearestX[index] < 0) {
                Int.MAX_VALUE
            } else {
                val currentDx = x - nearestX[index]
                val currentDy = y - nearestY[index]
                currentDx * currentDx + currentDy * currentDy
            }
        if (candidateDistance < currentDistance) {
            nearestX[index] = candidateX
            nearestY[index] = candidateY
        }
    }

    private fun fillOutsideProxy(
        image: IntArray,
        plan: FillPlan,
    ): IntArray {
        val size = plan.size
        val radius = size * 0.5f
        val mirrorDistance = max(2f, EDGE_MIRROR * radius)
        val blurRamp = max(4f, 0.30f * radius)
        val base = image.copyOf()

        for (index in base.indices) {
            if (!plan.circle[index] || plan.valid[index]) continue
            val x = index % size
            val y = index / size
            val nearestX = plan.nearestX[index]
            val nearestY = plan.nearestY[index]
            if (nearestX < 0) continue
            val nearestColor = image[nearestY * size + nearestX]
            val reflectedX = (nearestX * 2 - x).coerceIn(0, size - 1)
            val reflectedY = (nearestY * 2 - y).coerceIn(0, size - 1)
            val reflectedIndex = reflectedY * size + reflectedX
            val reflectedColor =
                if (plan.valid[reflectedIndex]) image[reflectedIndex] else nearestColor
            val mirrorWeight = (1f - plan.distance[index] / mirrorDistance).coerceIn(0f, 1f)
            base[index] = blendRgb(nearestColor, reflectedColor, mirrorWeight)
        }

        val blur1 = boxBlurRgb(base, size, size, max(1, (0.02f * radius).roundToInt()))
        val blur2 = boxBlurRgb(base, size, size, max(1, (0.06f * radius).roundToInt()))
        val blur3 = boxBlurRgb(base, size, size, max(1, (0.18f * radius).roundToInt()))
        val levels = arrayOf(base, blur1, blur2, blur3)
        val output = image.copyOf()
        for (index in output.indices) {
            if (!plan.circle[index]) {
                output[index] = 0xFF000000.toInt()
                continue
            }
            if (plan.valid[index]) continue
            val progress = (plan.distance[index] / blurRamp).coerceIn(0f, 1f)
            val levelPosition = progress * (levels.size - 1)
            val low = floor(levelPosition).toInt().coerceIn(0, levels.size - 2)
            val fraction = levelPosition - low
            val softened = blendRgb(levels[low][index], levels[low + 1][index], fraction)
            val darken = 1f - EDGE_DARKEN * smoothStep(progress)
            output[index] = scaleRgb(softened, darken)
        }
        return output
    }

    private fun gaussian5(
        values: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val weights = intArrayOf(1, 4, 6, 4, 1)
        val horizontal = FloatArray(values.size)
        val output = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (offset in -2..2) {
                    sum += values[y * width + (x + offset).coerceIn(0, width - 1)] * weights[offset + 2]
                }
                horizontal[y * width + x] = sum / 16f
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (offset in -2..2) {
                    sum += horizontal[(y + offset).coerceIn(0, height - 1) * width + x] * weights[offset + 2]
                }
                output[y * width + x] = sum / 16f
            }
        }
        return output
    }

    private fun gaussian5Rgb(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val first = boxBlurRgb(pixels, width, height, 1)
        return boxBlurRgb(first, width, height, 1)
    }

    private fun boxBlurRgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ): IntArray {
        val horizontal = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = pixels[y * width + offset.coerceIn(0, width - 1)]
                red += (color shr 16) and 0xFF
                green += (color shr 8) and 0xFF
                blue += color and 0xFF
            }
            for (x in 0 until width) {
                horizontal[y * width + x] =
                    (0xFF shl 24) or
                        ((red / diameter) shl 16) or
                        ((green / diameter) shl 8) or
                        (blue / diameter)
                val remove = pixels[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = pixels[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                red += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                green += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                blue += (add and 0xFF) - (remove and 0xFF)
            }
        }
        for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = horizontal[offset.coerceIn(0, height - 1) * width + x]
                red += (color shr 16) and 0xFF
                green += (color shr 8) and 0xFF
                blue += color and 0xFF
            }
            for (y in 0 until height) {
                output[y * width + x] =
                    (0xFF shl 24) or
                        ((red / diameter) shl 16) or
                        ((green / diameter) shl 8) or
                        (blue / diameter)
                val remove = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                red += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                green += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                blue += (add and 0xFF) - (remove and 0xFF)
            }
        }
        return output
    }

    private fun bilinearSample(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
    ): Int {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = blendRgb(pixels[y0 * width + x0], pixels[y0 * width + x1], fx)
        val bottom = blendRgb(pixels[y1 * width + x0], pixels[y1 * width + x1], fx)
        return blendRgb(top, bottom, fy)
    }

    private fun blendRgb(
        first: Int,
        second: Int,
        amount: Float,
    ): Int {
        val inverse = 1f - amount
        val red =
            (((first shr 16) and 0xFF) * inverse + ((second shr 16) and 0xFF) * amount)
                .roundToInt()
                .coerceIn(0, 255)
        val green =
            (((first shr 8) and 0xFF) * inverse + ((second shr 8) and 0xFF) * amount)
                .roundToInt()
                .coerceIn(0, 255)
        val blue =
            ((first and 0xFF) * inverse + (second and 0xFF) * amount)
                .roundToInt()
                .coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun scaleRgb(
        color: Int,
        scale: Float,
    ): Int {
        val red = (((color shr 16) and 0xFF) * scale).roundToInt().coerceIn(0, 255)
        val green = (((color shr 8) and 0xFF) * scale).roundToInt().coerceIn(0, 255)
        val blue = ((color and 0xFF) * scale).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

    private fun median(values: FloatArray): Float {
        val copy = values.copyOf()
        copy.sort()
        val middle = copy.size / 2
        return if (copy.size % 2 == 0) {
            (copy[middle - 1] + copy[middle]) * 0.5f
        } else {
            copy[middle]
        }
    }
}

internal fun fillNearestValidIndices(
    sourceX: IntArray,
    rowOffset: Int,
    width: Int,
    left: IntArray,
    right: IntArray,
) {
    require(left.size >= width && right.size >= width)
    var last = -1
    for (x in 0 until width) {
        if (sourceX[rowOffset + x] >= 0) last = x
        left[x] = last
    }
    var next = -1
    for (x in width - 1 downTo 0) {
        if (sourceX[rowOffset + x] >= 0) next = x
        right[x] = next
    }
}

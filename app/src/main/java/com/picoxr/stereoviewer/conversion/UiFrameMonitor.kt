package com.picoxr.stereoviewer.conversion

import android.view.Choreographer

class UiFrameMonitor : Choreographer.FrameCallback {
    private var running = false
    private var previousFrameNanos = 0L
    private var maxGapNanos = 0L
    private var framesOver33Ms = 0

    fun start() {
        if (running) return
        running = true
        previousFrameNanos = 0L
        maxGapNanos = 0L
        framesOver33Ms = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop(): FrameMetrics {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        return FrameMetrics(
            maxGapMs = maxGapNanos / 1_000_000L,
            framesOver33Ms = framesOver33Ms,
        )
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (previousFrameNanos != 0L) {
            val gap = frameTimeNanos - previousFrameNanos
            if (gap > maxGapNanos) maxGapNanos = gap
            if (gap > 33_333_333L) framesOver33Ms += 1
        }
        previousFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}

data class FrameMetrics(
    val maxGapMs: Long,
    val framesOver33Ms: Int,
)

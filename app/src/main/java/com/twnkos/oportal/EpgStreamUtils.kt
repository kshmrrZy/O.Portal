package com.twnkos.oportal

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal class ProgressInputStream(
    input: InputStream,
    private val totalBytes: Long,
    private val onProgress: (Int) -> Unit
) : FilterInputStream(input) {
    private var consumedBytes: Long = 0L
    private var lastProgress: Int = -1
    private var lastEmitAtMs: Long = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            updateProgress(1)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) {
            updateProgress(count)
        }
        return count
    }

    private fun updateProgress(delta: Int) {
        consumedBytes += delta
        val progress = if (totalBytes > 0L) {
            ((consumedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            // Unknown length (streamed gzip XML): climb toward 95% by ~256KiB so UI leaves 0%.
            (1 + (consumedBytes / (256L * 1024L)).toInt()).coerceIn(1, 95)
        }
        val now = System.currentTimeMillis()
        // Throttle UI churn on weak TV boxes (Tanix etc.) — still emit on every % change if spaced.
        // Also heartbeat every 2s so a slow catalog/parse never looks frozen at the same %.
        val due = progress == 100 ||
            (progress != lastProgress && now - lastEmitAtMs >= 350L) ||
            (now - lastEmitAtMs >= 2000L)
        if (due) {
            onProgress(progress)
            lastProgress = progress
            lastEmitAtMs = now
        }
    }
}

internal class SizeLimitedInputStream(
    input: InputStream,
    private val limitBytes: Long
) : FilterInputStream(input) {
    private var consumedBytes: Long = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            consumedBytes += 1L
            validateLimit()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) {
            consumedBytes += count.toLong()
            validateLimit()
        }
        return count
    }

    private fun validateLimit() {
        if (limitBytes > 0L && consumedBytes > limitBytes) {
            throw IOException("Input exceeded safe limit: $consumedBytes bytes")
        }
    }
}

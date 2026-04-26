package com.example.o_portal_ott

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
        if (totalBytes <= 0L) return
        consumedBytes += delta
        val progress = ((consumedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        if (progress != lastProgress) {
            onProgress(progress)
            lastProgress = progress
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

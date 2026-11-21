package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ImageDownscalerTest {
    @Test
    fun imageWithinLimitKeepsItsSize() {
        assertEquals(1200 to 800, ImageDownscaler.fitWithin(1200, 800))
    }

    @Test
    fun longEdgeIsScaledDownKeepingAspectRatio() {
        assertEquals(1568 to 1176, ImageDownscaler.fitWithin(4000, 3000))
        assertEquals(1176 to 1568, ImageDownscaler.fitWithin(3000, 4000))
    }

    @Test
    fun extremeAspectRatioNeverCollapsesToZero() {
        assertEquals(1568 to 1, ImageDownscaler.fitWithin(100_000, 10))
    }

    @Test
    fun downscaleFileReturnsNullWhenMissingEmptyOrFallbackExceedsLimit() {
        val dir = createTempDirectory("downscaler-test").toFile()
        try {
            val missing = File(dir, "missing.jpg")
            assertNull(ImageDownscaler.downscaleFile(missing, "image/jpeg"))

            val empty = File(dir, "empty.jpg").apply { writeBytes(ByteArray(0)) }
            assertNull(ImageDownscaler.downscaleFile(empty, "image/jpeg"))

            val largeGif = File(dir, "anim.gif").apply { writeBytes(ByteArray(64) { 1 }) }
            assertNull(ImageDownscaler.downscaleFile(largeGif, "image/gif", maxFallbackBytes = 32))
            assertEquals(64, ImageDownscaler.downscaleFile(largeGif, "image/gif", maxFallbackBytes = 64)?.bytes?.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}

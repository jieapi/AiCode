package com.aicode.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * 发给模型的图片先缩小再编码。
 *
 * 模型侧会把超过长边上限（Anthropic 约 1568px）的图缩小后再看，原图多传的字节不带来任何信息，
 * 却会在每一轮历史回放里重复占用请求体。只处理发给模型的副本，工作区里的原文件不动。
 */
object ImageDownscaler {
    const val MAX_EDGE = 1568
    private const val SMALL_BYTES = 300 * 1024
    private const val JPEG_QUALITY = 85
    private const val OOM_RETRIES = 2
    private const val MAX_SAMPLE = 32

    class Result(val mimeType: String, val bytes: ByteArray)

    /** 长边不超过 [maxEdge] 的等比目标尺寸；已在范围内返回原尺寸。 */
    internal fun fitWithin(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** 解码不了、动图、已经够小或重编码后更大时，原样返回。 */
    fun downscale(bytes: ByteArray, mimeType: String): Result =
        downscaleCore(
            sourceSize = bytes.size.toLong(),
            mimeType = mimeType,
            readBounds = { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) },
            decodeSampled = { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) },
            readOrientation = { readOrientation(bytes) },
            readOriginalBytes = { bytes },
            maxFallbackBytes = Long.MAX_VALUE
        ) ?: Result(mimeType, bytes)

    /**
     * 按文件路径采样解码并缩小，避免先把数十 MB 的相机原图整份读进 Java 堆。
     * 若无法缩小（动图 / 解码失败 / 重编码反而更大）且原文件超过 [maxFallbackBytes]，
     * 直接返回 null 而不触发整文件读入。
     */
    fun downscaleFile(
        file: File,
        mimeType: String,
        maxFallbackBytes: Long = Long.MAX_VALUE
    ): Result? {
        if (!file.isFile) return null
        val size = file.length()
        if (size <= 0L) return null
        val path = file.absolutePath
        return downscaleCore(
            sourceSize = size,
            mimeType = mimeType,
            readBounds = { opts -> BitmapFactory.decodeFile(path, opts) },
            decodeSampled = { opts -> BitmapFactory.decodeFile(path, opts) },
            readOrientation = { readOrientation(file) },
            readOriginalBytes = { file.readBytes() },
            maxFallbackBytes = maxFallbackBytes
        )
    }

    private fun downscaleCore(
        sourceSize: Long,
        mimeType: String,
        readBounds: (BitmapFactory.Options) -> Unit,
        decodeSampled: (BitmapFactory.Options) -> Bitmap?,
        readOrientation: () -> Int,
        readOriginalBytes: () -> ByteArray,
        maxFallbackBytes: Long
    ): Result? {
        fun fallback(): Result? =
            if (sourceSize > maxFallbackBytes) null else Result(mimeType, readOriginalBytes())

        if (mimeType.equals("image/gif", ignoreCase = true)) return fallback()
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            readBounds(bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return fallback()
            if (maxOf(width, height) <= MAX_EDGE && sourceSize <= SMALL_BYTES) return fallback()

            val (targetW, targetH) = fitWithin(width, height)
            var sample = 1
            while (sample < MAX_SAMPLE && width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) {
                sample *= 2
            }
            var decoded: Bitmap? = null
            for (attempt in 0 until OOM_RETRIES) {
                try {
                    decoded = decodeSampled(BitmapFactory.Options().apply { inSampleSize = sample })
                    break
                } catch (_: OutOfMemoryError) {
                    sample = (sample * 2).coerceAtMost(MAX_SAMPLE)
                }
            }
            if (decoded == null) return fallback()
            val oriented = applyOrientation(decoded, readOrientation())
            val scaled = if (maxOf(oriented.width, oriented.height) > MAX_EDGE) {
                val (w, h) = fitWithin(oriented.width, oriented.height)
                Bitmap.createScaledBitmap(oriented, w, h, true)
            } else oriented
            try {
                val keepAlpha = scaled.hasAlpha() && !mimeType.equals("image/jpeg", ignoreCase = true)
                val out = ByteArrayOutputStream()
                val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                scaled.compress(format, JPEG_QUALITY, out)
                val encoded = out.toByteArray()
                if (encoded.size >= sourceSize) fallback()
                else Result(if (keepAlpha) "image/png" else "image/jpeg", encoded)
            } finally {
                if (scaled !== oriented) scaled.recycle()
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        } catch (_: OutOfMemoryError) {
            fallback()
        } catch (_: Exception) {
            fallback()
        }
    }

    /** 重编码会丢掉 EXIF，朝向必须先落到像素上，否则手机竖拍的照片会横着发给模型。 */
    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** 缩小后的 base64 与实际 mime。 */
    fun downscaleToBase64(bytes: ByteArray, mimeType: String): Pair<String, String> {
        val result = downscale(bytes, mimeType)
        return result.mimeType to Base64.getEncoder().encodeToString(result.bytes)
    }

    /** 直接从本地文件缩小并编码 base64，不先把原图整份读入字节数组。 */
    fun downscaleFileToBase64(file: File, mimeType: String): Pair<String, String>? {
        val result = downscaleFile(file, mimeType) ?: return null
        return result.mimeType to Base64.getEncoder().encodeToString(result.bytes)
    }
}

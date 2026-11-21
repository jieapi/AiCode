package com.aicode.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aicode.core.util.FileLogger

/**
 * 全局统一的位图采样解码。
 *
 * 项目没有引入 Coil/Glide（F-Droid 可复现构建下刻意保持依赖精简），所有图片都是手写
 * [BitmapFactory] 解码。此处收敛采样与 OOM 兜底逻辑，避免各处各写一份、各带一份 bug。
 */
private const val TAG = "ImageDecode"

/** 缩略图档位：聊天附件预览卡只有 76dp，采样到 180 已有余量。 */
const val THUMBNAIL_MAX_EDGE = 180

/** Markdown 正文内嵌图片档位：显示高度上限 320dp。 */
const val MARKDOWN_MAX_EDGE = 1024

/** 全屏查看器的总像素上限：ARGB_8888 下峰值约 16MB。 */
private const val VIEWER_MAX_PIXELS = 4_000_000L

/** 解码 OOM 后的降采样重试次数。 */
private const val OOM_RETRIES = 3

/** inSampleSize 上限，防兜底循环在畸形尺寸下失控。 */
private const val MAX_SAMPLE = 32

/**
 * 按**最大边**算 [BitmapFactory.Options.inSampleSize]。
 *
 * 判定用 `||` 而不是 `&&`：`&&` 实际约束的是短边，4000×300 这类长截图会整个逃过采样、
 * 按全尺寸解码（约 4.8MB）—— 而 AI 产出的长网页截图、终端长输出截图正好是这个形状。
 */
fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (sample < MAX_SAMPLE && (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge)) {
        sample *= 2
    }
    return sample
}

/**
 * 在最大边约束之上再按总像素兜底：ARGB_8888 下 `maxPixels × 4` 字节就是解码峰值内存，
 * 极端长宽比的图（最大边没超限但总像素巨大）靠这一层挡住。
 */
fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Int {
    var sample = calculateInSampleSize(width, height, maxEdge)
    if (width <= 0 || height <= 0 || maxPixels <= 0) return sample
    while (sample < MAX_SAMPLE && (width / sample).toLong() * (height / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}

/** 采样解码本地文件。文件不存在、不是图片或解码失败均返回 null。 */
fun decodeSampledBitmap(path: String, maxEdge: Int, maxPixels: Long = Long.MAX_VALUE): ImageBitmap? {
    val bounds = decodeBounds(path) { BitmapFactory.decodeFile(path, it) } ?: return null
    return decodeWithOomRetry(path, bounds, maxEdge, maxPixels) { BitmapFactory.decodeFile(path, it) }
}

/** 采样解码内存中的图片字节。 */
fun decodeSampledBitmap(bytes: ByteArray, maxEdge: Int, maxPixels: Long = Long.MAX_VALUE): ImageBitmap? {
    val what = "byte[${bytes.size}]"
    val decode: (BitmapFactory.Options) -> Bitmap? = { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    val bounds = decodeBounds(what, decode) ?: return null
    return decodeWithOomRetry(what, bounds, maxEdge, maxPixels, decode)
}

/** 只读图片宽高，不真正分配像素内存。 */
private fun decodeBounds(what: String, decode: (BitmapFactory.Options) -> Bitmap?): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        decode(options)
    } catch (e: Exception) {
        FileLogger.w(TAG, "读取图片边界失败: $what", e)
        return null
    }
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/**
 * 按采样档位解码，OOM 时降一档重试。
 *
 * [OutOfMemoryError] 是 `Error` 不是 `Exception`，`runCatching {}` 与 `catch (e: Exception)` 都
 * 抓不到 —— 不显式接住的话，一张超大图就能把 App 崩掉。
 */
private fun decodeWithOomRetry(
    what: String,
    bounds: Pair<Int, Int>,
    maxEdge: Int,
    maxPixels: Long,
    decode: (BitmapFactory.Options) -> Bitmap?
): ImageBitmap? {
    var sample = calculateInSampleSize(bounds.first, bounds.second, maxEdge, maxPixels)
    repeat(OOM_RETRIES) {
        try {
            return decode(BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            FileLogger.w(TAG, "解码 OOM，inSampleSize $sample -> ${sample * 2}: $what")
            sample *= 2
        } catch (e: Exception) {
            FileLogger.w(TAG, "解码失败: $what", e)
            return null
        }
    }
    FileLogger.w(TAG, "解码连续 OOM，放弃: $what")
    return null
}

/** 解码档位：最大边（像素）+ 总像素上限。 */
data class ImageDecodeSpec(val maxEdge: Int, val maxPixels: Long)

/**
 * 缩略图内存缓存：聊天列表里带图消息滑出视口就会被回收，滑回来时每次都要重新读盘解码原图。
 * 按路径索引：附件落盘时已保证文件名唯一，不会被原地替换；也因此 [peek] 在组合阶段调用时不做任何文件 IO。
 * 缩略图最大边 180px，单张约 130KB，条数上限即内存上限。
 */
object ThumbnailCache {
    private const val MAX_ENTRIES = 96

    private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 命中则同步返回（可作 produceState 初值，避免闪占位图）。 */
    fun peek(path: String): ImageBitmap? = synchronized(cache) { cache[path] }

    /** 取缓存，未命中则解码并写入；解码失败不缓存。应在后台线程调用。 */
    fun load(path: String): ImageBitmap? {
        peek(path)?.let { return it }
        val decoded = decodeSampledBitmap(path, THUMBNAIL_MAX_EDGE) ?: return null
        synchronized(cache) { cache[path] = decoded }
        return decoded
    }

    /** 响应系统内存压力裁剪缓存：后台隐藏时留一半热图，低内存时全清。 */
    fun trim(clearAll: Boolean) {
        synchronized(cache) {
            if (clearAll) {
                cache.clear()
                return
            }
            val target = cache.size / 2
            val iterator = cache.entries.iterator()
            while (cache.size > target && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    internal fun size(): Int = synchronized(cache) { cache.size }
}

/**
 * 全屏查看器的解码档位，跟当前窗口走而不写死。
 *
 * 查看器允许放大看细节，故取窗口长边的 1.5 倍，夹在 1024..2048。写死 2048 在 1080p 手机上
 * 是纯浪费（屏幕才 2.6MP），在 720p 低端机上则是实打实的 OOM 风险。
 */
@Composable
fun rememberViewerDecodeSpec(): ImageDecodeSpec {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(config.screenWidthDp, config.screenHeightDp, density.density) {
        val longEdgeDp = maxOf(config.screenWidthDp, config.screenHeightDp)
        val longEdgePx = with(density) { longEdgeDp.dp.toPx() }.toInt()
        ImageDecodeSpec(
            maxEdge = (longEdgePx * 3 / 2).coerceIn(1024, 2048),
            maxPixels = VIEWER_MAX_PIXELS
        )
    }
}

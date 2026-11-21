package com.aicode.feature.agent.presentation.component

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.aicode.core.util.FileLogger
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 数学感知的 [ImageTransformer]：识别 [MarkdownPreprocessor] 生成的数学链接，用 jlatexmath 渲染成位图；
 * 其余（普通图片）链接一律委托给 [delegate]。
 *
 * @param delegate 处理非数学图片链接的下游 transformer（通常是本地图片渲染器或 NoOp）。
 * @param baseTextSizeSp 行内公式的基准字号（sp），与所在文本正文字号对齐。
 */
/**
 * 公式排版尺寸的进程级缓存。measure 需要构建 [JLatexMathDrawable]（解析 TeX + 排版），
 * 是主线程上的重活。LazyColumn 里公式 item 滚出视口会被 dispose，仅靠 Composable 内的
 * remember 缓存，滚回来重新组合就得再算一遍——快速 fling 时反复进出反复排版造成卡顿。
 * 这里按 (latex, textSizePx) 做进程级缓存，同一公式只排版测量一次。
 */
private const val TAG = "MarkdownMath"

private object LatexMeasureCache {
    private data class Key(val latex: String, val textSizePx: Float)

    // 上限取得大：每条只存一个 android.util.Size（两个 int），内存可忽略；而 fling 快速滚过
    // 长历史时，上限太小会把旧公式挤掉、导致滚回去又在主线程重新排版。512 足够盖住常见会话。
    private const val MAX = 512
    private val sizes = object : LinkedHashMap<Key, android.util.Size>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, android.util.Size>?): Boolean = size > MAX
    }

    fun peek(latex: String, textSizePx: Float): android.util.Size? =
        synchronized(sizes) { sizes[Key(latex, textSizePx)] }

    fun put(latex: String, textSizePx: Float, size: android.util.Size) {
        synchronized(sizes) { sizes[Key(latex, textSizePx)] = size }
    }

    fun getOrMeasure(latex: String, textSizePx: Float, measure: () -> android.util.Size): android.util.Size =
        synchronized(sizes) {
            val key = Key(latex, textSizePx)
            // 主线程同步 build drawable 拿尺寸是重活（实测复杂公式单次 20~113ms）。正常情况下缓存已
            // 由后台预热（renderLatex 完成后回写 measure 缓存）填好，这里直接命中；只有预热还没跑到时才同步兜底。
            sizes[key] ?: measure().also { sizes[key] = it }
        }
}

private object LatexBitmapCache {
    private data class Key(
        val link: String,
        val colorArgb: Int,
        val textSizePx: Float,
    )

    // 限制并发：JLatexMathDrawable.build（TeX 排版）很吃 CPU。预热会一次性提交上百个 render，
    // 若直接丢给 Dispatchers.Default（线程数=核数）会把线程池打满：单个 render 实测飙到 250ms+，
    // 而且同跑在 Default 上的 Markdown 解析/代码高亮被饿死，CPU 饱和又拖累主线程/渲染线程掉帧。
    // 限到 2 并发：公式逐步出现，不抢光所有 CPU。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + renderDispatcher)
    private const val MAX = 256
    private val bitmaps = object : LinkedHashMap<Key, Deferred<Bitmap?>>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Deferred<Bitmap?>>?): Boolean = size > MAX
    }
    // Deferred.getCompletionExceptionOrNull 仍标着 ExperimentalCoroutinesApi（本文件上面已 opt-in 过一次）。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getOrStartBitmap(
        link: String,
        colorArgb: Int,
        textSizePx: Float,
        render: suspend () -> Bitmap?
    ): Deferred<Bitmap?> = synchronized(bitmaps) {
        val key = Key(link, colorArgb, textSizePx)
        bitmaps[key] ?: scope.async { render() }.also { deferred ->
            bitmaps[key] = deferred
            deferred.invokeOnCompletion {
                if (deferred.getCompletionExceptionOrNull() != null) {
                    synchronized(bitmaps) {
                        if (bitmaps[key] === deferred) bitmaps.remove(key)
                    }
                }
            }
        }
    }

    /** 预热：仅启动后台渲染（写入缓存），不等结果。已在缓存则不重复启动。 */
    fun prewarm(link: String, colorArgb: Int, textSizePx: Float, render: suspend () -> Bitmap?) {
        getOrStartBitmap(link, colorArgb, textSizePx, render)
    }

    /** 响应内存压力释放公式位图缓存（尺寸表 [LatexMeasureCache] 保留，避免重排版跳动）。 */
    fun trim(clearAll: Boolean) {
        synchronized(bitmaps) {
            if (clearAll) {
                bitmaps.clear()
                return
            }
            val target = bitmaps.size / 2
            val iterator = bitmaps.entries.iterator()
            while (bitmaps.size > target && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }
}

internal fun trimLatexBitmapMemoryCache(clearAll: Boolean) {
    LatexBitmapCache.trim(clearAll)
}

internal class MathImageTransformer(
    private val delegate: ImageTransformer,
    private val baseTextSizeSp: Float,
) : ImageTransformer {

    // 重写占位尺寸：上滑抽搐的真正根因。库默认 placeholderConfig 在图片真实尺寸未知（首帧）时
    // 会给一个 200dp 正方形（或受容器限制的边长正方形）占位；公式真实高度只有约 50dp，渲染完成
    // 后占位从 200dp 骤降到真实高度——上滑时这个收缩发生在视口上方，触发 LazyColumn 顶部锚点补偿，
    // 表现为“往回拽/抽搐”。公式真实尺寸我们自己有（LatexMeasureCache），直接返回精确占位，占位从
    // 第一帧就等于最终高度，上滑不再补偿。非公式链接委托 delegate。
    override fun placeholderConfig(
        link: String,
        density: androidx.compose.ui.unit.Density,
        containerSize: androidx.compose.ui.geometry.Size,
        imageWidth: com.mikepenz.markdown.model.ImageWidth,
        imageSize: androidx.compose.ui.geometry.Size,
        imageSizeChanged: ((link: String, androidx.compose.ui.geometry.Size) -> Unit)?,
    ): com.mikepenz.markdown.model.PlaceholderConfig {
        val spec = MarkdownPreprocessor.decodeMathLink(link)
            ?: return super.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
        val latex = spec.first
        val textSizePx = with(density) { baseTextSizeSp.sp.toPx() }
        val sizePx = LatexMeasureCache.getOrMeasure(latex, textSizePx) { measureLatex(latex, textSizePx) }
        val sizeDp = with(density) {
            androidx.compose.ui.geometry.Size(sizePx.width.toDp().value, sizePx.height.toDp().value)
        }
        return com.mikepenz.markdown.model.PlaceholderConfig(
            size = sizeDp,
            verticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.Bottom,
        )
    }

    @Composable
    override fun transform(link: String): ImageData? {
        val spec = MarkdownPreprocessor.decodeMathLink(link) ?: return delegate.transform(link)
        val (latex, block) = spec

        val density = LocalDensity.current
        val colorArgb = LocalContentColor.current.toArgb()
        // 块级公式与正文使用同一字号，避免块级公式无必要地放大。
        val textSizePx = with(density) { baseTextSizeSp.sp.toPx() }
        // 首次组合时同步取得真实尺寸，避免用字符长度估算造成公式忽大忽小、宽度过大时
        // 从中间开始显示。尺寸一旦确定就不再异步替换，因此不会改写 LazyColumn 锚点。
        val layoutSize = remember(link, textSizePx) {
            LatexMeasureCache.getOrMeasure(latex, textSizePx) { measureLatex(latex, textSizePx) }
        }
        val renderTask = remember(link, colorArgb, textSizePx) {
            LatexBitmapCache.getOrStartBitmap(link, colorArgb, textSizePx) {
                renderLatex(latex, textSizePx, colorArgb)
            }
        }
        val bitmap by produceState<Bitmap?>(initialValue = null, renderTask) {
            value = renderTask.await()
        }
        // 位图完成前后始终使用同一个布局尺寸。否则 Markdown 的 inline placeholder 会
        // 从 0 高度变为公式高度，快速 fling 时每个公式完成都会改写 LazyColumn 的锚点。
        val painter = remember(bitmap, layoutSize) {
            FixedLatexPainter(bitmap?.asImageBitmap(), layoutSize.width, layoutSize.height)
        }
        val widthDp = with(density) { layoutSize.width.toDp() }
        val heightDp = with(density) { layoutSize.height.toDp() }

        val base = if (block) {
            Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())
        } else {
            Modifier
        }
        return ImageData(
            painter = painter,
            modifier = base.then(Modifier.size(widthDp, heightDp)),
            contentScale = ContentScale.Fit,
        )
    }

    private fun measureLatex(latex: String, textSizePx: Float): android.util.Size {
        return try {
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .padding(2)
                .build()
            android.util.Size(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1)
            )
        } catch (e: Exception) {
            android.util.Size(textSizePx.roundToInt().coerceAtLeast(1), textSizePx.roundToInt().coerceAtLeast(1))
        }
    }
    private class FixedLatexPainter(
        private val bitmap: androidx.compose.ui.graphics.ImageBitmap?,
        widthPx: Int,
        heightPx: Int,
    ) : Painter() {
        override val intrinsicSize: Size = Size(widthPx.toFloat(), heightPx.toFloat())

        override fun DrawScope.onDraw() {
            bitmap?.let { image ->
                val scale = minOf(
                    size.width / image.width,
                    size.height / image.height,
                )
                val drawWidth = (image.width * scale).roundToInt().coerceAtLeast(1)
                val drawHeight = (image.height * scale).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = image,
                    dstSize = IntSize(drawWidth, drawHeight),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        ((size.width - drawWidth) / 2f).roundToInt(),
                        ((size.height - drawHeight) / 2f).roundToInt(),
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * 在后台预热一整段 processed markdown 里的所有 LaTeX 公式：解析出数学链接、逐个启动
         * 后台渲染（写入 bitmap 缓存）。渲染完成后会回写 measure 缓存，于是主线程后续的
         * getOrMeasure 会直接命中、不再同步 build drawable——这是消除 fling 抽搐（主线程单次
         * measure 实测高达 113ms）的关键。缓存已存则不重复启动。
         *
         * 在 IO/Default 线程调用，不阻塞组合。
         */
        fun prewarm(processedMarkdown: String, textSizePx: Float, colorArgb: Int) {
            if (!processedMarkdown.contains("aicode-math-")) return
            // 从 Markdown 图片链接 ![](aicode-math-xxx://enc) 中抽取 link
            for (match in MATH_LINK_REGEX.findAll(processedMarkdown)) {
                val link = match.groupValues[1]
                val spec = MarkdownPreprocessor.decodeMathLink(link) ?: continue
                val latex = spec.first
                if (LatexMeasureCache.peek(latex, textSizePx) != null) continue
                LatexBitmapCache.prewarm(link, colorArgb, textSizePx) {
                    renderLatex(latex, textSizePx, colorArgb)
                }
            }
        }

        private val MATH_LINK_REGEX = Regex("""!\[]\((aicode-math-(?:block|inline)://[^)]+)\)""")

        private fun renderLatex(latex: String, textSizePx: Float, colorArgb: Int): Bitmap? {
            return try {
                val drawable = JLatexMathDrawable.builder(latex)
                    .textSize(textSizePx)
                    .color(colorArgb)
                    .padding(2)
                    .build()
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                // 回写 measure 缓存：后台 build 既已得到真实尺寸，主线程就不必再 build 一次。
                LatexMeasureCache.put(latex, textSizePx, android.util.Size(w, h))
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bitmap
            } catch (e: Exception) {
                FileLogger.w(TAG, "LaTeX 渲染失败: $latex", e)
                null
            }
        }
    }
}

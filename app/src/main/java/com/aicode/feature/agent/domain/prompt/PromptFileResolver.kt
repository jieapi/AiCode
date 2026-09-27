package com.aicode.feature.agent.domain.prompt

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单个提示词片段的文件解析器，供 [SystemPromptProvider] 与无循环依赖需求的
 * 其它组件（如 MemoryCurator）共用：
 *
 * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
 * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
 * - 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
 *
 * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
 */
@Singleton
class PromptFileResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {
    private val customDir: File
        get() = File(containerInstaller.aicodeDir, "prompts.custom")

    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun resolve(name: String): String {
        PromptFragmentResolver.parseNumber(name)
            ?.let { number -> readFileOrNull(customFragmentsByNumber[number])?.let { return it } }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.aicodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "PromptFileResolver"
    }
}

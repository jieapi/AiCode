package com.aicode.feature.agent.domain.skill

/**
 * 解析后的单个 Skill 模型。
 *
 * @param name 技能名称（供大模型调用的唯一标识）
 * @param description 技能描述（何时使用该技能）
 * @param requiredTools 该技能所需的专属工具列表（可选）
 * @param dirPath 技能所在目录的容器路径（本地/远程统一视角，如 `~/workspace/.aicode/skills/foo`）
 * @param instructions 技能指令正文（剥离 Frontmatter 后的内容）
 */
data class Skill(
    val name: String,
    val description: String,
    val requiredTools: List<String> = emptyList(),
    val dirPath: String? = null,
    val instructions: String
)

/** 设置页新建/编辑技能时提交的表单快照。 */
data class SkillForm(
    val name: String,
    val description: String,
    val instructions: String,
    /** 手写技能里的 `required_tools`，编辑页不暴露但原样写回，避免保存后丢字段。 */
    val requiredTools: List<String> = emptyList()
)

/** 保存技能失败的原因。 */
enum class SkillSaveError {
    /** 名称为空、过长或含不能做目录名的字符。 */
    INVALID_NAME,

    /** 指令正文为空：这种技能加载出来对 AI 没有任何指导价值。 */
    EMPTY_INSTRUCTIONS,

    /** 同作用域已有同名技能。 */
    NAME_CONFLICT,

    /** 写盘失败。 */
    IO_FAILED
}

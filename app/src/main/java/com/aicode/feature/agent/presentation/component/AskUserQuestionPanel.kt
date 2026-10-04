package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.tool.question.PendingUserQuestion
import com.aicode.feature.agent.domain.tool.question.QuestionItem
import com.aicode.feature.agent.domain.tool.question.SingleAnswer
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp


/** 「其他」选项在选中集合内的内部哨兵：用 AI 不可能传出的控制字符前缀，避免与预设选项 label 撞车。 */
private const val OTHER_SENTINEL = "\u0000__other__"

/**
 * AI 向用户提问的面板：展示 1-4 个结构化问题，每个带 2-4 个预设选项 + 一个「其他」自由输入选项。
 *
 * 风格对齐 [ToolPermissionPanel]：内联 Surface，不用 AlertDialog。
 *
 * @param question 待回答的问题请求。
 * @param onConfirm 用户点击确认后回传答案。
 * @param onSkip 用户点击「补充」，返回空答案——表示用户想补充说明而非在预设选项中做选择。
 */
@Composable
fun AskUserQuestionPanel(
    question: PendingUserQuestion,
    onConfirm: (UserQuestionAnswer) -> Unit,
    onSkip: () -> Unit,
    forceCollapse: Boolean = false
) {
    // 每个问题的已选 label 集合
    val selectedMap = remember(question.id) {
        mutableStateMapOf<Int, MutableList<String>>().apply {
            question.questions.forEachIndexed { idx, _ -> this[idx] = mutableStateListOf() }
        }
    }
    // 每个问题的「其他」自由文本
    val customTexts = remember(question.id) {
        mutableStateMapOf<Int, String>().apply {
            question.questions.forEachIndexed { idx, _ -> this[idx] = "" }
        }
    }
    // 面板折叠状态：默认展开；多问题时长面板可收起，避免挡住 AI 输出
    var expanded by remember(question.id) { mutableStateOf(true) }
    // 余额面板展开时同帧收起本面板，避免叠加顶开输入框
    val effectiveExpanded = expanded && !forceCollapse

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md)
        ) {
            // 面板标题：点击折叠/展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.ask_confirm_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "askUserQuestion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(Spacing.xs))
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp
                )
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(Modifier.height(Spacing.md))

                    // 逐个渲染问题
                    question.questions.forEachIndexed { idx, q ->
                        if (idx > 0) Spacer(Modifier.height(Spacing.md))
                        QuestionCard(
                            item = q,
                            selected = selectedMap[idx] ?: mutableListOf(),
                            customText = customTexts[idx] ?: "",
                            onSelectionChanged = { newSelection ->
                                selectedMap[idx] = newSelection.toMutableList() as MutableList<String>
                            },
                            onCustomTextChanged = { customTexts[idx] = it }
                        )
                    }

                    Spacer(Modifier.height(Spacing.md))

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.ask_supplement),
                            onClick = onSkip,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Neutral
                        )
                        AgentActionButton(
                            text = stringResource(R.string.ask_confirm),
                            onClick = {
                                val answers = question.questions.mapIndexed { i, q ->
                                    val sel = selectedMap[i] ?: emptyList<String>()
                                    val custom = customTexts[i]?.takeIf { it.isNotBlank() && OTHER_SENTINEL in sel }
                                    SingleAnswer(
                                        question = q.question,
                                        selected = sel.filter { it != OTHER_SENTINEL },
                                        customText = custom
                                    )
                                }
                                onConfirm(UserQuestionAnswer(answers))
                            },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个问题的卡片区域。
 */
@Composable
private fun QuestionCard(
    
    item: QuestionItem,
    selected: List<String>,
    customText: String,
    onSelectionChanged: (List<String>) -> Unit,
    onCustomTextChanged: (String) -> Unit
) {
    Column {
        // 标题行：header 标签 + 问题文本
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.header.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(Radius.xs)
                ) {
                    Text(
                        text = item.header,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = item.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // 选项列表：预设选项 + 追加的「其他」自由输入项（内部用哨兵标识，避免与 AI 传入的同名选项冲突）
        val allOptions = item.options.map { it.label } + OTHER_SENTINEL

        allOptions.forEachIndexed { optIdx, label ->
            val isOther = label == OTHER_SENTINEL
            val isSelected = label in selected
            val description = if (!isOther) {
                item.options.getOrNull(optIdx)?.description ?: ""
            } else {
                stringResource(R.string.ask_custom_answer)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xs))
                    .clickable {
                        val newSelection = if (item.multiSelect) {
                            if (isSelected) selected - label else selected + label
                        } else {
                            listOf(label)
                        }
                        onSelectionChanged(newSelection)
                    }
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.multiSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            val newSelection = if (checked) selected + label else selected - label
                            onSelectionChanged(newSelection)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectionChanged(listOf(label)) },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isOther) stringResource(R.string.common_other) else label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 「其他」被选中时展开文本输入框
            if (isOther && isSelected) {
                Spacer(Modifier.height(Spacing.xs))
                AppTextField(
                    value = customText,
                    onValueChange = onCustomTextChanged,
                    placeholder = stringResource(R.string.ask_input_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        }
    }


}

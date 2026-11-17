package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import com.aicode.R

/** 解析后的待办列表快照 */
internal data class ParsedTodoResult(
    val total: Int,
    val completed: Int,
    val items: List<ParsedTodoItem>
)

internal data class ParsedTodoItem(
    val id: String,
    val subject: String,
    val description: String,
    val status: String,  // pending / in_progress / completed
    val priority: Int,
    val order: Int
)

/** 从工具结果的 JSON 文本中解析出待办列表 */
internal fun parseTodoResult(content: String): ParsedTodoResult? {
    return try {
        // 先剥掉 Success(data=...) / Error(...) 外壳
        val s = content.withoutToolStatusPrefix()
        val jsonStr = when {
            s.startsWith("{") -> {
                val outer = Json.parseToJsonElement(s).jsonObject
                if (outer["status"]?.jsonPrimitive?.contentOrNull in setOf("success", "partial")) {
                    outer["data"]?.toString() ?: s
                } else {
                    s
                }
            }
            s.startsWith("Success(data=") -> s.removePrefix("Success(data=").removeSuffix(")")
            s.startsWith("Partial(data=") -> {
                val inner = s.removePrefix("Partial(data=")
                val msgIdx = inner.lastIndexOf(", message=")
                if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
            }
            else -> s
        }
        val json = Json { ignoreUnknownKeys = true }
        val element = json.parseToJsonElement(jsonStr)
        val obj = element as? JsonObject ?: return null
        val total = obj["total"]?.toString()?.toIntOrNull() ?: return null
        val completed = obj["completed"]?.toString()?.toIntOrNull() ?: 0
        val itemsArr = obj["items"]?.jsonArray ?: return null
        val items = itemsArr.mapNotNull { item ->
            val itemObj = item?.jsonObject ?: return@mapNotNull null
            ParsedTodoItem(
                id = itemObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                subject = itemObj["subject"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                description = itemObj["description"]?.jsonPrimitive?.content ?: "",
                status = itemObj["status"]?.jsonPrimitive?.content ?: "pending",
                priority = itemObj["priority"]?.jsonPrimitive?.intOrNull ?: 0,
                order = itemObj["order"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }.sortedBy { it.order }
        ParsedTodoResult(total = total, completed = completed, items = items)
    } catch (_: Exception) {
        // 解析失败时静默返回 null，UI 会 fallback 到普通文本
        null
    }
}

/** 待办列表结构化卡片 */
@Composable
internal fun TodoCard(items: List<ParsedTodoItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            TodoItemRow(item = item)
            if (item != items.last()) {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

/** 单条待办项行：状态图标 + 标题 + 状态标签 */
@Composable
internal fun TodoItemRow(item: ParsedTodoItem) {
    val isCompleted = item.status == "completed"
    val isInProgress = item.status == "in_progress"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态图标
        when {
            isCompleted -> {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = stringResource(R.string.todo_completed),
                    tint = DiffAddText,
                    modifier = Modifier.size(16.dp)
                )
            }
            isInProgress -> {
                val alpha by rememberChatInfiniteFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(650),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "todo-progress-dot-alpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = item.subject,
            color = if (isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f)
        )
        if (isInProgress) {
            Spacer(Modifier.width(Spacing.xs))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = stringResource(R.string.todo_in_progress),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
    if (item.description.isNotBlank() && !isCompleted) {
        Spacer(Modifier.height(2.dp))
        Row {
            Spacer(Modifier.width(24.dp))
            Text(
                text = item.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

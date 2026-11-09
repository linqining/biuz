package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzEmptyState
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.ChatMessage
import cn.biuz.android.protocol.MessageRole
import cn.biuz.android.protocol.ToolCallStatus
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/**
 * 屏 07 · 执行输出（模型轨迹）—— iOS `Features/Tasks/TaskOutputView.swift` 的 Android 对位。
 *
 * 数据面全部只读：
 * - 轨迹：`conversation.messages(sessionId)` 的行投影（思考 / 工具 / 回复三类，保序）；
 * - 只读元数据三读：`getTaskConfigOptions` / `getTaskModelSelection` / `getTaskTokenUsage`；
 * - 写面仅「停止任务」（v4 stop，桌面代执行），回执失败原文上屏。
 *
 * v1.39 死 UI 口径：不渲染后台终端分段（移动端无只读 task terminal 接口）。
 */
@Composable
fun TaskOutputScreen(
    session: AppSession,
    sessionId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val conversationStore = surface.conversation
    val taskStore = surface.task
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var configOptions by remember { mutableStateOf<Map<String, String>?>(null) }
    var modelSelection by remember { mutableStateOf<String?>(null) }
    var tokenUsage by remember { mutableStateOf<String?>(null) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var stopError by remember { mutableStateOf<String?>(null) }
    var taskTitleState by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        messages = conversationStore.messages(sessionId)
        taskTitleState = runCatching { taskStore.tasks().firstOrNull { it.id == sessionId }?.title }.getOrNull()
        configOptions = runCatching { taskStore.taskConfigOptions(sessionId) }.getOrNull()
        modelSelection = runCatching { taskStore.taskModelSelection(sessionId) }.getOrNull()
        tokenUsage = runCatching {
            taskStore.taskTokenUsage(sessionId)?.let { usage ->
                "total ${usage.total} · in ${usage.input} · out ${usage.output}"
            }
        }.getOrNull()
        // 行增量：任一事件后重读（内存读，成本低）
        conversationStore.observeConversations().collect {
            messages = conversationStore.messages(sessionId)
        }
    }

    val trajectory = remember(messages) { trajectoryEntries(messages) }
    val taskTitle = taskTitleState

    Column(modifier = Modifier.fillMaxWidth().background(BiuZBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp),
            )
            Text(
                text = stringResource(R.string.task_output_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        androidx.compose.material3.HorizontalDivider(color = BiuZBorder)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(BiuZSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Text(
                text = taskTitle ?: stringResource(R.string.task_output_untitled),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )

            // 只读元数据（连接态三读；nil 不渲染）
            val meta = buildList {
                modelSelection?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.task_output_model, it)) }
                tokenUsage?.let { add(stringResource(R.string.task_output_tokens, it)) }
                configOptions?.takeIf { it.isNotEmpty() }?.let { options ->
                    options.forEach { (key, value) ->
                        // getTaskModelSelection 已给权威模型显示名，选项里的同名行不重复渲染
                        if (key.equals("Model", ignoreCase = true)) return@forEach
                        add("$key · $value")
                    }
                }
            }
            if (meta.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .background(BiuZBgCard)
                        .border(1.dp, BiuZBorder, BiuZRadius.medium)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    meta.forEach { line ->
                        Text(text = line, fontSize = 12.sp, color = BiuZText2)
                    }
                }
            }

            if (trajectory.isEmpty()) {
                BiuzEmptyState(
                    title = stringResource(R.string.task_output_empty_title),
                    hint = stringResource(R.string.task_output_empty_hint),
                    modifier = Modifier.testTag("07-trajectory-empty"),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .background(BiuZBgCard)
                        .border(1.dp, BiuZBorder, BiuZRadius.medium)
                        .testTag("07-trajectory"),
                ) {
                    trajectory.forEachIndexed { index, entry ->
                        if (index > 0) {
                            androidx.compose.material3.HorizontalDivider(color = BiuZBorder)
                        }
                        TrajectoryRow(entry)
                    }
                }
            }

            stopError?.let { BiuzInlineNotice(message = it) }

            TextButton(
                onClick = { showStopConfirm = true },
                enabled = !stopping,
            ) {
                Text(
                    text = stringResource(R.string.task_output_stop),
                    color = BiuZRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(stringResource(R.string.task_output_stop_confirm_title)) },
            text = { Text(stringResource(R.string.task_output_stop_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    stopping = true
                    stopError = null
                    scope.launch {
                        val error = taskStore.stop(sessionId)
                        stopping = false
                        stopError = error
                    }
                }) { Text(stringResource(R.string.task_output_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 轨迹条目（渲染用投影）。 */
private data class TrajectoryEntry(
    val id: String,
    val label: String,
    val accent: androidx.compose.ui.graphics.Color,
    val text: String,
    val meta: String,
)

private fun trajectoryEntries(messages: List<ChatMessage>): List<TrajectoryEntry> {
    val entries = ArrayList<TrajectoryEntry>()
    messages.forEach { message ->
        message.thinking?.text?.takeIf { it.isNotBlank() }?.let { thought ->
            entries.add(
                TrajectoryEntry(
                    id = "${message.id}-think",
                    label = "思考",
                    accent = androidx.compose.ui.graphics.Color(0xFFA78BFA),
                    text = thought,
                    meta = "",
                ),
            )
        }
        message.toolCall?.let { tool ->
            val statusText = when (tool.status) {
                ToolCallStatus.failed -> "失败"
                ToolCallStatus.running -> "运行中"
                else -> "完成"
            }
            entries.add(
                TrajectoryEntry(
                    id = "${message.id}-tool",
                    label = "工具",
                    accent = BiuZBlue,
                    text = tool.target,
                    meta = listOfNotNull(statusText.takeIf { it.isNotBlank() }, tool.duration)
                        .joinToString(" · "),
                ),
            )
        }
        if (message.role == MessageRole.agent && message.toolCall == null && message.text.isNotBlank()) {
            entries.add(
                TrajectoryEntry(
                    id = "${message.id}-text",
                    label = "回复",
                    accent = BiuZText3,
                    text = message.text,
                    meta = "",
                ),
            )
        }
    }
    return entries
}

@Composable
private fun TrajectoryRow(entry: TrajectoryEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(6.dp).clip(CircleShape).background(entry.accent),
            )
            Text(text = entry.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = entry.accent)
            if (entry.meta.isNotEmpty()) {
                Text(text = entry.meta, fontSize = 11.sp, color = BiuZText3)
            }
        }
        if (entry.label == "回复") {
            // 回复正文走 markdown（与聊天正文同渲染器；iOS TaskOutputView 同款富文本）
            cn.biuz.android.app.ui.chat.MarkdownText(
                text = entry.text,
                fontSize = 13f,
                codeFontSize = 11f,
            )
        } else {
            Text(
                text = entry.text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = BiuZText2,
                maxLines = 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = if (entry.label == "工具") FontFamily.Monospace else FontFamily.Default),
            )
        }
    }
}

package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZOnOrange
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.store.ConversationEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 会话列表（Tab2 根同位）：Store 装配面消费屏。
 * 数据源 = [AppSession.storeSurface]（连接态 sessions-index 订阅直供；未连接/失败态
 * Empty 三件如实空读）。观察流以装配世代 [cn.biuz.android.connection.StoreSurface.epoch]
 * 为 key 重建——Store 换源即重绑，防永久抱空 store 实例（红线）。
 *
 * 呈现（design-spec 屏 04 + 交接裁决）：
 * - LazyColumn 分组「置顶 → 今天 → 昨天 → 更早」（组织态读 membership——isPinned 由
 *   Store 层本地 override → tasks-index membership → 行字段三源 join 定案，UI 直读）；
 * - 行 = 标题首字符圆徽（会话 icon 裁决：icon 用标题首字符）+ 标题 + 摘要 + 时间；
 * - 行级指示（桌面侧栏逐字 parity）：isRunning 双源合成转圈（蓝）+「等待确认」标（橙，
 *   pendingApprovalCount>0）；归档行由 Store 投影滤除；
 * - 未连接空态如实（§1.6 表）：未连接桌面端 / 连接后同步桌面端的会话与消息 / 连接桌面端。
 * 读面失败三态呈现：last-good 列表与失败文案共存，不折叠成空态（工程纪律 §5-12）。
 */
@Composable
fun SessionsScreen(
    session: AppSession,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBackToConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Store 换源重绑：列表态/失败文案均以 epoch 为 key（实例级重建，防抱旧 Store）
    var conversations by remember(surface.epoch) { mutableStateOf<List<Conversation>>(emptyList()) }
    var loadFailure by remember(surface.epoch) { mutableStateOf<String?>(null) }
    LaunchedEffect(surface.epoch) {
        // conversations() 触发 sessions-index 订阅（帧 handler 先于 subscribe 注册由
        // Store 内部保证）并取首屏投影；随后按事件流增量维护列表
        conversations = surface.conversation.conversations()
        loadFailure = surface.sessionsIndex?.conversationsLoadFailure
        surface.conversation.observeConversations().collect { event ->
            when (event) {
                is ConversationEvent.ConversationsReplaced -> conversations = event.conversations
                is ConversationEvent.ConversationUpdated -> {
                    conversations = conversations.map { row ->
                        if (row.id == event.conversation.id) event.conversation else row
                    }
                }
                else -> Unit
            }
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    // 分组边界（今天 00:00 / 昨天 00:00；组合期计算一次，长驻列表跨天偏差可接受）
    val dayBounds = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -1)
        todayStart to cal.timeInMillis
    }

    // LazyColumn key 需唯一非空：空 id 行（未连接态 Empty Store 防御位）不入列
    val rows = conversations.filter { it.id.isNotEmpty() }
    val sections = remember(rows, dayBounds) {
        groupConversationsByRecency(
            rows = rows,
            todayStartMs = dayBounds.first,
            yesterdayStartMs = dayBounds.second,
        )
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = BiuZSpacing.lg),
        ) {
            Spacer(Modifier.height(BiuZSpacing.xl))
            Text(
                text = "会话",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(BiuZSpacing.md))

            when (val m = mode) {
                is AppSession.Mode.Disconnected -> {
                    // 橙横幅：断线保留快照（列表继续显示断线前数据），重连同入口
                    Text(
                        text = "与桌面端的连接已断开 · 显示断线前的数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Button(
                        onClick = { scope.launch { session.connect(m.server) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        Text("重新连接")
                    }
                    Spacer(Modifier.height(BiuZSpacing.md))
                }

                is AppSession.Mode.ConnectFailed -> {
                    // 红横幅：失败态空数据如实（重连入口同页；详细对照见连接页失败卡）
                    Text(
                        text = "桌面端连接失败 · ${m.error.headline}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Button(
                        onClick = { scope.launch { session.connect(m.server) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        Text("重试连接")
                    }
                    Spacer(Modifier.height(BiuZSpacing.md))
                }

                else -> Unit
            }

            // 读面失败三态：与 last-good 列表共存（§5-12）
            loadFailure?.let { failure ->
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(BiuZSpacing.sm))
            }

            when {
                // 未连接空态（§1.6 表：未连接桌面端 / 连接后同步… / 连接桌面端 CTA）
                rows.isEmpty() && (mode is AppSession.Mode.Demo || mode is AppSession.Mode.ConnectFailed) -> {
                    NotConnectedEmpty(
                        onConnect = onBackToConnect,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 连接态首屏：订阅快照未到 = 居中 spinner（5.9 加载态口径，无骨架屏）
                rows.isEmpty() && loadFailure == null && mode is AppSession.Mode.Connected -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        Text(
                            text = "正在同步会话…",
                            style = MaterialTheme.typography.labelMedium,
                            color = BiuZText3,
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        sections.forEach { section ->
                            item(key = "header-${section.label}") {
                                Text(
                                    text = section.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BiuZText3,
                                    modifier = Modifier.padding(
                                        top = BiuZSpacing.md,
                                        bottom = BiuZSpacing.xs,
                                    ),
                                )
                            }
                            items(section.rows, key = { it.id }) { conversation ->
                                SessionRow(
                                    conversation = conversation,
                                    timeLabel = conversationRowTimeLabel(
                                        updatedAtMs = conversation.updatedAt,
                                        todayStartMs = dayBounds.first,
                                        yesterdayStartMs = dayBounds.second,
                                        timeFormat = timeFormat,
                                    ),
                                    onOpen = { onOpenChat(conversation.id) },
                                )
                            }
                        }
                        item { Spacer(Modifier.height(BiuZSpacing.xl)) }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(onClick = onOpenSettings),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "设置 →",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.lg))
        }
    }
}

// MARK: - 会话行（首字符圆徽 + 标题/摘要 + 运行转圈/等待确认标 + 时间）

@Composable
private fun SessionRow(
    conversation: Conversation,
    timeLabel: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onOpen)
            .padding(vertical = BiuZSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 会话 icon（裁决）：标题首字符圆徽
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = conversation.title.firstOrNull()?.toString() ?: "·",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.size(BiuZSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 运行转圈（桌面侧栏逐字 parity；isRunning 为 Store 层双源合成：
                // sessions-index 实时 phase 优先 ∪ task-index status 补源）
                if (conversation.isRunning) {
                    Spacer(Modifier.size(BiuZSpacing.xs))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = BiuZBlue,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = conversation.summary,
                style = MaterialTheme.typography.bodySmall,
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(BiuZSpacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            if (timeLabel.isNotEmpty()) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = BiuZText3,
                )
            }
            // 「等待确认」标（桌面侧栏同源口径：pendingApprovalCount = permission + userInput）
            if (conversation.pendingApprovalCount > 0) {
                Spacer(Modifier.height(BiuZSpacing.xs))
                Text(
                    text = "等待确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = BiuZOnOrange,
                    modifier = Modifier
                        .background(BiuZOrange, RoundedCornerShape(999.dp))
                        .padding(horizontal = BiuZSpacing.sm, vertical = 2.dp),
                )
            }
        }
    }
}

// MARK: - 未连接空态（§1.6 统一骨架：icon 位 + 结论 + 解释 + 唯一主 CTA）

@Composable
private fun NotConnectedEmpty(
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "会",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.md))
        Text(
            text = "未连接桌面端",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = "连接后同步桌面端的会话与消息",
            style = MaterialTheme.typography.bodySmall,
            color = BiuZText3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.lg))
        Button(
            onClick = onConnect,
            modifier = Modifier.height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            Text("连接桌面端")
        }
    }
}

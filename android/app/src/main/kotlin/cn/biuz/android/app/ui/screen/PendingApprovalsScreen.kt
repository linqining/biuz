package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.connection.AppSession
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import kotlinx.coroutines.delay

/**
 * R1 待批收件箱（2026-11-25 与 iOS PendingApprovalsView R1 同构；移动端 v1 = 跨会话
 * 摘要收件箱）：sessions-index pendingApprovalCount>0 的会话逐行呈现（会话级摘要行
 * + 点按进会话展开审批卡——iOS 手风琴按需订阅的 Android 等效承载：进会话即挂
 * 会话订阅与审批卡，不在此页做逐会话订阅，避免冷启挂多路帧流焐热 revision
 * ——iOS test15 同教训）。
 * 数据源 = sessionsIndex.currentConversations()（装配面只读快照；3s 轻轮询随
 * sessions-index 帧更新）。未连接 = 如实空态。
 */
@Composable
fun PendingApprovalsScreen(
    session: AppSession,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val connected = mode is AppSession.Mode.Connected

    var rows by remember { mutableStateOf(listOf<cn.biuz.android.protocol.Conversation>()) }

    LaunchedEffect(connected, surface.epoch) {
        if (!connected) {
            rows = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            val snapshot = runCatching {
                surface.sessionsIndex?.currentConversations().orEmpty()
            }.getOrDefault(emptyList())
            rows = snapshot.filter { it.pendingApprovalCount > 0 }
                .sortedByDescending { it.updatedAt }
            delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BiuZBg)
            .statusBarsPadding(),
    ) {
        // 顶栏（返回 + 标题 + 摘要）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = BiuZSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(horizontal = BiuZSpacing.md)
                    .testTag("11-act-back"),
            )
            Text(
                text = "待批",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                modifier = Modifier.weight(1f),
            )
            if (rows.isNotEmpty()) {
                Text(
                    text = "${rows.size} 个会话待处理",
                    fontSize = 12.sp,
                    color = BiuZOrange,
                    modifier = Modifier.padding(end = BiuZSpacing.md)
                        .testTag("11-pending-summary"),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            if (!connected) {
                Text(
                    text = "未连接桌面端 · 连接后同步各会话的待审批",
                    fontSize = 13.sp,
                    color = BiuZText3,
                    modifier = Modifier.padding(vertical = BiuZSpacing.lg)
                        .testTag("11-pending-not-connected"),
                )
            } else if (rows.isEmpty()) {
                Text(
                    text = "没有待审批的会话",
                    fontSize = 13.sp,
                    color = BiuZText3,
                    modifier = Modifier.padding(vertical = BiuZSpacing.lg)
                        .testTag("11-pending-empty"),
                )
            } else {
                rows.forEach { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BiuZRadius.medium)
                            .background(BiuZBgCard)
                            .clickable { onOpenChat(conversation.id) }
                            .padding(BiuZSpacing.md)
                            .testTag("11-pending-row-${conversation.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = conversation.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = BiuZText,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "进入会话处理审批",
                                fontSize = 11.sp,
                                color = BiuZText3,
                            )
                        }
                        Text(
                            text = "${conversation.pendingApprovalCount} 项待批",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BiuZAccent,
                        )
                    }
                }
            }
        }
    }
}

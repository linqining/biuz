package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 会话列表占位（Tab2 根同位）：真实列表（bootstrap.tasks 多工作区 + sessions-index）
 * 随 store 模块（M2–M4）接线。当前仅提供导航骨架出口。
 */
@Composable
fun SessionsScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Text(
                text = "列表占位：未连接态（store 模块实装后按「读面失败如实呈现」纪律接线）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(BiuZSpacing.lg))
            // 导航占位：验证 chat/{sessionId} 路由通（非业务假数据）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable { onOpenChat("placeholder") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "打开 chat/{sessionId} 占位路由 →",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.md))
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
            Spacer(Modifier.weight(1f))
            Text(
                text = "会话 sessionId 为桌面端规范 id（sess_*），深链截断会被拒（sessionNotFound）",
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
            )
            Spacer(Modifier.height(BiuZSpacing.lg))
        }
    }
}

package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 连接页（L1 同位）：未连接态入口。当前为占位——
 * 真实连接链路（server-info 发现 → WS → v4 握手）随 connection 模块（M2）接线。
 */
@Composable
fun ConnectScreen(
    onOpenScan: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = BiuZSpacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BiuZ",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(BiuZSpacing.sm))
            Text(
                text = "移动遥控台 · 把任务 biu 地发给你的 coding agent",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                // 商标裁决（branding/naming.md）：仅以描述性「works with ZCode」表达社区版身份
                text = "A mobile remote console that works with ZCode",
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xl))
            Button(
                onClick = onOpenScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = BiuZRadius.medium,
            ) {
                Text("扫码连接桌面端")
            }
            Spacer(Modifier.height(BiuZSpacing.sm))
            OutlinedButton(
                onClick = onOpenSessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = BiuZRadius.medium,
            ) {
                Text("进入会话列表（骨架导航占位）")
            }
            Spacer(Modifier.height(BiuZSpacing.lg))
            Text(
                text = "骨架占位：连接链路未接线（connection 模块 M2 实装）",
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
            )
        }
    }
}

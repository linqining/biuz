package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 扫码取景器占位（L1-S 同位）：CAMERA 权限已在 Manifest 声明，
 * 真实取景器 + 解析随扫码里程碑实装（MVP 兜底 = 剪贴板/手动输入，见 design-spec §9.0）。
 */
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(BiuZSpacing.lg),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "扫码连接",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.sm))
            Text(
                text = "相机取景器占位：对准桌面端出示的连接二维码\n（识别与解析随扫码里程碑实装）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.lg))
            Text(
                text = "兜底路径：剪贴板一键填充 / 手动输入地址",
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

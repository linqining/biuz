package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.connection.AppSession
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZFont
import cn.biuz.android.app.ui.theme.BiuZOnSeal
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.ctaPress
import kotlinx.coroutines.launch

/**
 * 连不上时的整屏页（2026-11-25 与 iOS `ReconnectLoadingPage` 同裁决）：
 * 用户报「断网应该自动重连，像 web 那样有个 loading 页」+「连不上什么功能都没意义」。
 *
 * 此前 Android 断线只出橙横幅，三 tab 照常可点、点进去是空列表。现由本页整屏接管：
 * 品牌位 → 状态 → 明细（电脑名 + 失败原因原文）→ 主 CTA（正在重连=取消；失败/断线=
 * 立即重连）。唯一越权出口是「查看离线缓存」（连上后由宿主复位）。
 */
@Composable
fun ReconnectGateScreen(
    session: AppSession,
    mode: AppSession.Mode,
    onBrowseOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val server = when (mode) {
        is AppSession.Mode.Connected -> mode.server
        is AppSession.Mode.Disconnected -> mode.server
        is AppSession.Mode.ConnectFailed -> mode.server
        is AppSession.Mode.Connecting -> mode.server
        else -> null
    }
    val connecting = mode is AppSession.Mode.Connecting
    val failure = (mode as? AppSession.Mode.ConnectFailed)?.error?.headline
    val title = when {
        connecting -> stringResource(R.string.gate_connecting)
        mode is AppSession.Mode.ConnectFailed -> stringResource(R.string.gate_failed)
        else -> stringResource(R.string.gate_disconnected)
    }
    val detail = buildString {
        server?.displayName?.takeIf { it.isNotBlank() }?.let { append(it) }
        if (!failure.isNullOrBlank()) {
            if (isNotEmpty()) append(" · ")
            append(failure)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BiuZBg)
            .padding(horizontal = BiuZSpacing.xl)
            .testTag("13-connection-gate"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.brand_seal),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        Text("朱批", fontSize = BiuZFont.title.sp, fontWeight = FontWeight.SemiBold, color = BiuZSeal)
        Spacer(Modifier.height(BiuZSpacing.lg))
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = BiuZSeal,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.md))
        Text(title, fontSize = BiuZFont.subtitle.sp, fontWeight = FontWeight.SemiBold, color = BiuZText)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = detail,
                fontSize = BiuZFont.micro.sp,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("13-gate-detail"),
            )
        }
        Spacer(Modifier.height(BiuZSpacing.xl))
        val interaction = remember {
            androidx.compose.foundation.interaction.MutableInteractionSource()
        }
        if (connecting) {
            Text(
                text = stringResource(R.string.action_cancel),
                fontSize = BiuZFont.subtitle.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText2,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                    .ctaPress(interaction)
                    .androidxClickable(interaction) { session.cancelConnecting() }
                    .padding(top = BiuZSpacing.md)
                    .testTag("13-gate-cancel"),
            )
        } else {
            Text(
                text = stringResource(R.string.gate_reconnect),
                fontSize = BiuZFont.subtitle.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZOnSeal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(BiuZSeal, BiuZRadius.medium)
                    .ctaPress(interaction)
                    .androidxClickable(interaction) {
                        val target = server
                        if (target != null) {
                            busy = true
                            scope.launch {
                                session.connect(target)
                                busy = false
                            }
                        }
                    }
                    .padding(top = BiuZSpacing.md)
                    .testTag("13-gate-reconnect"),
            )
        }
        Spacer(Modifier.height(BiuZSpacing.sm))
        Text(
            text = stringResource(R.string.gate_offline),
            fontSize = BiuZFont.bodyCompact.sp,
            color = BiuZText3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .height(44.dp)
                .androidxClickable(remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }) { onBrowseOffline() }
                .padding(top = BiuZSpacing.sm)
                .testTag("13-gate-offline"),
        )
    }
}

/** 统一走 indication=null 的 clickable（与按压实配套；ripple 在纸底上像污渍）。 */
private fun Modifier.androidxClickable(
    interaction: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

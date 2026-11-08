package cn.biuz.android.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBlueDim
import cn.biuz.android.app.ui.theme.BiuZGreen
import cn.biuz.android.app.ui.theme.BiuZGreenDim
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZOrangeDim
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZRedDim
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 空态 / 加载态 / 行内提示 —— 三端公共件层（W1 地基）。
 *
 * 对位关系：
 * - 空态  ← web Root 空态分支 + iOS `Components.swift:175 EmptyStateView`
 * - 加载态 ← web `RootStartupLoading` + iOS `Components.swift:217 CenterLoadingView`
 * - 行内提示 ← web 读面 `inlineFailureText` 横幅 / 写面错误行 + iOS 同款横幅
 *
 * 纪律（AGENTS.md §5-12）：读面失败必须「last-good + 失败文案」三态呈现，不得折叠成空态；
 * 写面失败把服务端 `RPCError.message` 原文上屏，禁止只显示本地化兜底文案。
 * 本层只做呈现，不承载业务状态——状态由各 Store/ViewModel 持有。
 */

/** 行内提示的语义档位；色值取 qoder 色板的状态色（Color.kt）。 */
enum class BiuzNoticeKind { Info, Success, Warning, Error }

@Composable
fun BiuzEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    tag: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BiuZSpacing.xl)
            .let { if (tag != null) it.testTag(tag) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(text = title, color = BiuZText, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(text = hint, color = BiuZText3, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        action?.invoke()
    }
}

@Composable
fun BiuzLoadingState(
    modifier: Modifier = Modifier,
    text: String? = null,
    tag: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BiuZSpacing.xl)
            .let { if (tag != null) it.testTag(tag) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = BiuZText2,
            strokeWidth = 2.dp,
        )
        if (text != null) {
            Text(text = text, color = BiuZText3, fontSize = 13.sp)
        }
    }
}

/**
 * 行内提示条：默认挂在与内容同列的流里（不遮挡内容），用于
 * 「读面 last-good + 失败文案」「写面错误行常驻直到成功」两种形态。
 * `onRetry` 非空时右侧出现重试入口。
 */
@Composable
fun BiuzInlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    kind: BiuzNoticeKind = BiuzNoticeKind.Error,
    tag: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val accent: Color = when (kind) {
        BiuzNoticeKind.Info -> BiuZBlue
        BiuzNoticeKind.Success -> BiuZGreen
        BiuzNoticeKind.Warning -> BiuZOrange
        BiuzNoticeKind.Error -> BiuZRed
    }
    val surface: Color = when (kind) {
        BiuzNoticeKind.Info -> BiuZBlueDim
        BiuzNoticeKind.Success -> BiuZGreenDim
        BiuzNoticeKind.Warning -> BiuZOrangeDim
        BiuzNoticeKind.Error -> BiuZRedDim
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(surface, BiuZRadius.small)
            .border(1.dp, accent.copy(alpha = 0.5f), BiuZRadius.small)
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(accent, CircleShape),
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = accent,
            fontSize = 13.sp,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.state_action_retry), color = accent, fontSize = 13.sp)
            }
        }
    }
}

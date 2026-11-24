package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 设置族（设置/语言/关于/用量统计）共享脚手架与行组件。
 * 设计语言对位 iOS 设置页：分组标题（12sp semibold text3）+ 卡片容器（r-l）+
 * 48dp 行高；identifier 沿用 iOS a11y 词面（12-row-* / 12-*-…）作为 E2E 锚点契约
 * （testTag 承载——Android E2E 基建落位后直接复用同一锚点词表，§5-14 同纪律）。
 */

/** 设置族页面脚手架：状态栏安全区 + 返回钮 + 居中标题（iOS navigationTitle inline 同位）。 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // APP-DIFF[D003] platform: web 用 dvh 视口高度（含地址栏收放）；原生无地址栏，
            // 各屏用 statusBarsPadding/imePadding 让出系统栏与键盘（安全区 inset 对位）
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(BiuZSpacing.lg))
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = BiuZSpacing.sm),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            content()
        }
    }
}

/** 分组容器（标题 + 卡片体内行集合）。 */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = BiuZText3,
            modifier = Modifier.padding(start = BiuZSpacing.sm, bottom = BiuZSpacing.sm),
        )
        Column(
            // 卡面与页面底同色（纸），分组靠 12% 墨描边——卡面若再靠「比纸白一档」区分，
            // 真机上整页就是白的（2026-11-24 二次报障）。
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.large)
                .border(1.dp, BiuZBorder, BiuZRadius.large),
        ) {
            content()
        }
    }
}

/** 导航行（标题/副标题 + 右侧值 + chevron；iOS navRow 同构，行高 48dp）。 */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String?,
    testTagId: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = BiuZSpacing.md)
            .testTag(testTagId),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = BiuZText3,
                    maxLines = 1,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.size(BiuZSpacing.sm))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = BiuZText3,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(BiuZSpacing.xs))
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = BiuZText3,
        )
    }
}

/** 开关行（iOS toggleRow 同构：标题/副标题 + 右侧 Switch）。 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    testTagId: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = BiuZSpacing.md)
            .testTag(testTagId),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                maxLines = 1,
            )
        }
        Switch(
            checked = isOn,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = Color(0xFF04120A), // BiuZOnAccent：开关选中圆钮深色
            ),
        )
    }
}

/** 选项行（语言页档位：标题 + 选中勾；iOS 12-language-* 行同构）。 */
@Composable
internal fun SettingsCheckRow(
    title: String,
    selected: Boolean,
    testTagId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onClick() }
            .padding(horizontal = BiuZSpacing.md)
            .testTag(testTagId),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 等宽说明行（mono 小字；iOS T.mono 对位）。 */
@Composable
internal fun SettingsMonoLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = BiuZText3,
        modifier = modifier,
    )
}

/** 在线探测点（ProbeDot 对位：绿=可达 / 灰=不可达 / 半透明=未探测）。 */
@Composable
internal fun ProbeDot(
    reachable: Boolean?,
    modifier: Modifier = Modifier,
) {
    val color = when (reachable) {
        null -> BiuZText3.copy(alpha = BiuZAlpha.half)
        true -> MaterialTheme.colorScheme.primary
        false -> BiuZText3
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

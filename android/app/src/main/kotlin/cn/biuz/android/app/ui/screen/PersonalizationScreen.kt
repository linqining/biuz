package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2

/**
 * 屏 027 · 个性化——**web 权威口径的「仅本地桌面端」指引页**，零服务调用。
 *
 * 依据（上游取证 2026-11-10）：PersonalizationSection.tsx:147-150
 * `if (!isDesktop) return <LocalOnlyPersonalizationHint/>`——web 手机远控壳对这页
 * 只渲染指引文案、不发起任何读写（SettingsPage.tsx:2099-2102 注释：手机远控的
 * base services 可能归属远程 Source，编辑会写错机器）。原生按同口径只读，
 * 不提供编辑器（写 ~/.zcode/AGENTS.md 属桌面本地面）。
 */
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(BiuZBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = BiuZSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onBack() }
                    .padding(horizontal = BiuZSpacing.md),
            )
            Text(
                text = stringResource(R.string.personalization_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        HorizontalDivider(color = BiuZBorder)

        Column(
            modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.lg),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgCard)
                    .padding(BiuZSpacing.lg)
                    .testTag("27-local-only"),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.personalization_description),
                    fontSize = 13.sp,
                    color = BiuZText2,
                )
                Text(
                    text = stringResource(R.string.personalization_local_only),
                    fontSize = 13.sp,
                    color = BiuZText,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.settings.AppAppearance
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 外观设置（`/remote/v4#settings/appearance`）—— iOS `AppearanceSettingsView`
 * （SettingsView.swift:939-980）同构：三档 `跟随系统 / 朱批 · 浅色 / 朱批 · 深色` + 勾选 + 说明。
 * 选择即时生效（主题重组换肤）并持久化（`settings.appearance`）。
 */
@Composable
fun AppearanceScreen(
    settings: AppSettingsStore,
    onBack: () -> Unit,
) {
    val current by settings.appearance.collectAsStateWithLifecycle()

    @Composable
    fun label(appearance: AppAppearance): String = when (appearance) {
        AppAppearance.SYSTEM -> stringResource(R.string.appearance_system)
        AppAppearance.LIGHT -> "朱批 · 浅色"
        AppAppearance.DARK -> "朱批 · 深色"
    }

    Column(modifier = Modifier.fillMaxSize().background(BiuZBg).statusBarsPadding()) {
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
                text = stringResource(R.string.appearance_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        androidx.compose.material3.HorizontalDivider(color = BiuZBorder)

        Column(modifier = Modifier.padding(horizontal = BiuZSpacing.md)) {
            AppAppearance.entries.forEach { appearance ->
                val selected = appearance == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.small)
                        .clickable { settings.setAppearance(appearance) }
                        .padding(horizontal = 8.dp, vertical = 14.dp)
                        .testTag("13-appearance-${appearance.stored}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label(appearance),
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = BiuZText,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text(text = "✓", fontSize = 14.sp, color = BiuZText)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = BiuZBorder)
            }
            Text(
                text = stringResource(R.string.appearance_hint),
                fontSize = 12.sp,
                color = BiuZText3,
                modifier = Modifier.padding(top = BiuZSpacing.sm),
            )
        }
    }
}

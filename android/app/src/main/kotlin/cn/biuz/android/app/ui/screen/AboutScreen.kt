package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import cn.biuz.android.app.BuildConfig
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3

/**
 * 关于页（本轮任务面）：品牌自我指称 + works-with 描述 + 版本 + 数据边界页脚。
 * 商标红线（branding/naming.md + brand/seal/README.md）：应用名 朱批 / Seal（2026-11-21 朱批品牌升级）；「ZCode」仅允许出现在描述性
 * works with ZCode 短语内，禁独立字样——iOS 原文「为 ZCode 社区版桌面端打造的
 * 移动遥控台」按红线改为 works-with 表述（与连接页品牌区同口径）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(BiuZSpacing.xxl))
            // 品牌名（应用自我指称）
            Text(
                text = "朱批",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("13-brand-name"),
            )
            Spacer(Modifier.height(BiuZSpacing.sm))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.app_works_with),
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("13-works-with"),
            )
            Spacer(Modifier.height(BiuZSpacing.lg))
            // 版本（iOS CFBundleShortVersionString 对应 BuildConfig.versionName）
            Text(
                text = stringResource(R.string.about_version_fmt, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelLarge,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("13-version"),
            )
            Spacer(Modifier.weight(1f))
            // 执行边界页脚（12-foot-data-source 同词面）
            Text(
                text = stringResource(R.string.app_foot_data_source),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("13-foot-data-source"),
            )
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.app_foot_boundary),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(BiuZSpacing.xl))
        }
    }
}

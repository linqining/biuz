package cn.biuz.android.app.ui.screen

import android.app.Activity
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import cn.biuz.android.app.R
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.settings.AppLanguagePreference
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * 语言设置（iOS LanguageSettingsView 同位）：当前生效语言 + 三档选择（跟随系统 /
 * 简体中文 / English）+ 说明卡。
 *
 * 生效链路（Android 对应 iOS AppleLanguages 重启生效口径）：
 * 1. 选择落 [AppSettingsStore.setLanguage]（SharedPreferences 持久化）；
 * 2. 当场 `Activity.recreate()`——MainActivity.attachBaseContext 按存档档位以
 *    createConfigurationContext 注入 locale，重建后全树 stringResource 命中新表
 *    （Android 可即时生效，优于 iOS 的进程重启；偏差如实记录于交付 deviations）；
 * 3. 跟随系统档 = 清固定档，回落系统首选语言。
 */
@Composable
fun LanguageSettingsScreen(
    settings: AppSettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language by settings.language.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 当前生效语言（跟随系统档解析系统首选语言；iOS effectiveLanguageLabel 同构）
    val effective = when (language) {
        AppLanguagePreference.SYSTEM ->
            AppLanguagePreference.fromSystemLanguage(Locale.getDefault().language)
        else -> language
    }

    SettingsPageScaffold(
        title = stringResource(R.string.language_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.lg),
        ) {
            Spacer(Modifier.height(BiuZSpacing.lg))

            // 当前界面语言行（12-language-system）
            SettingsNavRow(
                title = stringResource(R.string.language_current_label),
                subtitle = null,
                value = when (effective) {
                    AppLanguagePreference.SYSTEM -> stringResource(R.string.language_opt_system)
                    AppLanguagePreference.CHINESE -> stringResource(R.string.language_opt_chinese)
                    AppLanguagePreference.ENGLISH -> stringResource(R.string.language_opt_english)
                },
                testTagId = "12-language-system",
            )
            Spacer(Modifier.height(BiuZSpacing.md))

            // 三档选择卡（12-language-*）
            SettingsGroup(title = stringResource(R.string.language_title)) {
                SettingsCheckRow(
                    title = stringResource(R.string.language_opt_system),
                    selected = language == AppLanguagePreference.SYSTEM,
                    testTagId = "12-language-system-opt",
                    onClick = { applyLanguage(context, settings, AppLanguagePreference.SYSTEM) },
                )
                SettingsCheckRow(
                    title = stringResource(R.string.language_opt_chinese),
                    selected = language == AppLanguagePreference.CHINESE,
                    testTagId = "12-language-zh-Hans",
                    onClick = { applyLanguage(context, settings, AppLanguagePreference.CHINESE) },
                )
                SettingsCheckRow(
                    title = stringResource(R.string.language_opt_english),
                    selected = language == AppLanguagePreference.ENGLISH,
                    testTagId = "12-language-en",
                    onClick = { applyLanguage(context, settings, AppLanguagePreference.ENGLISH) },
                )
            }
            Spacer(Modifier.height(BiuZSpacing.md))

            // 说明卡（跟随系统语义 + 覆盖范围；iOS 尾部说明卡同位）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.large)
                    .padding(BiuZSpacing.md),
            ) {
                Text(
                    text = stringResource(R.string.language_note_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = BiuZText3,
                )
                Spacer(Modifier.height(BiuZSpacing.xs))
                Text(
                    text = stringResource(R.string.language_note_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.xl))
        }
    }
}

/**
 * 应用语言档位：同档幂等跳过；异档持久化 + Activity 重建（locale 注入在
 * attachBaseContext，重建即生效——iOS 需重启进程，Android 当场切）。
 */
private fun applyLanguage(
    context: android.content.Context,
    settings: AppSettingsStore,
    preference: AppLanguagePreference,
) {
    if (settings.language.value == preference) return
    settings.setLanguage(preference)
    (context as? Activity)?.recreate()
}

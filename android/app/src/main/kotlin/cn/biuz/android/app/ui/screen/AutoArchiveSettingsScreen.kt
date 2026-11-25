package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.AutoArchiveSettings
import kotlinx.coroutines.launch

/**
 * G43 自动归档设置页（iOS `AutoArchiveSettingsPage` 同构，2026-11-25 从归档列表页
 * 内联块迁出为独立设置子页）。
 *
 * 为什么迁出：原实现是嵌在归档列表搜索框下方的自绘卡（13sp 标题 + 12sp 胶囊 chips），
 * 与设置族「分组标题 + 纸底卡 + 48/56dp 行」的语言不一致（2026-11-25 用户报
 * 「自动归档…页面设计风格不符」）。归档页只留列表本体，设置项回设置页分组。
 *
 * 数据面：`autoArchiveSettings()` / `setAutoArchiveSetting(enabled, olderThanDays)`
 * （setting 频道两键，见 RemoteConversationStore）；写直接下发不确认（web toggle
 * 变更即发），busy 期禁改 + 失败原文上屏，写永不重试防双写。
 * 键与档位【实证·上游仓 validationAppSettings.ts:555-557 +
 * TASK_AUTO_ARCHIVE_DAY_OPTIONS=[3,7,14,30] settingsPageHelpers.ts:69】。
 */
@Composable
fun AutoArchiveSettingsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val store = surface.conversation
    val scope = rememberCoroutineScope()
    val connected = mode is AppSession.Mode.Connected

    var loading by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf<AutoArchiveSettings?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        errorText = null
        settings = runCatching { store.autoArchiveSettings() }.getOrNull()
        if (settings == null) {
            errorText = if (connected) "设置读取失败（连接中断或桌面端不支持）" else "连接桌面端后可读写"
        }
        loading = false
    }

    LaunchedEffect(connected) { load() }

    fun write(enabled: Boolean?, days: Int?) {
        if (busy) return
        busy = true
        errorText = null
        scope.launch {
            val failure = store.setAutoArchiveSetting(enabled = enabled, olderThanDays = days)
            busy = false
            if (failure != null) {
                errorText = "写入失败：$failure"
            } else {
                settings = settings?.copy(
                    enabled = enabled ?: settings?.enabled ?: false,
                    olderThanDays = days ?: settings?.olderThanDays ?: 30,
                ) ?: AutoArchiveSettings(enabled = enabled == true, olderThanDays = days ?: 30)
            }
        }
    }

    SettingsPageScaffold(title = "自动归档", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.lg),
        ) {
            Spacer(Modifier.height(BiuZSpacing.sm))
            when {
                loading && settings == null -> Text(
                    text = "读取中…",
                    fontSize = 13.sp,
                    color = BiuZText3,
                    modifier = Modifier
                        .padding(vertical = BiuZSpacing.offsetLg)
                        .testTag("12-auto-archive-loading"),
                )

                settings == null -> Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    Text(
                        text = errorText ?: "设置读取失败",
                        fontSize = 13.sp,
                        color = BiuZRed,
                        modifier = Modifier
                            .padding(vertical = BiuZSpacing.lg)
                            .testTag("12-auto-archive-failed"),
                    )
                    Text(
                        text = "重试",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { scope.launch { load() } }
                            .padding(BiuZSpacing.sm)
                            .testTag("12-auto-archive-retry"),
                    )
                }

                else -> {
                    val current = settings
                    SettingsGroup(title = "自动归档") {
                        SettingsToggleRow(
                            title = "自动归档旧任务",
                            subtitle = "超过保留时长的旧任务自动移入归档",
                            isOn = current?.enabled == true,
                            onToggle = { newValue -> write(enabled = newValue, days = null) },
                            testTagId = "12-auto-archive-switch",
                            enabled = !busy,
                        )
                        if (current?.enabled == true) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "归档保留时长",
                                style = MaterialTheme.typography.labelMedium,
                                color = BiuZText3,
                                modifier = Modifier.padding(
                                    start = BiuZSpacing.md,
                                    top = BiuZSpacing.sm,
                                    bottom = BiuZSpacing.xxs,
                                ),
                            )
                            listOf(3, 7, 14, 30).forEachIndexed { index, days ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SettingsCheckRow(
                                    title = "$days 天",
                                    selected = current?.olderThanDays == days,
                                    testTagId = "12-auto-archive-day-$days",
                                    onClick = { write(enabled = null, days = days) },
                                )
                            }
                        }
                    }
                    errorText?.let {
                        Text(text = it, fontSize = 12.sp, color = BiuZRed)
                    }
                    Text(
                        text = "该设置写入桌面端，桌面侧同步生效。",
                        fontSize = 11.sp,
                        color = BiuZText3,
                    )
                    Spacer(Modifier.height(BiuZSpacing.xl))
                }
            }
        }
    }
}

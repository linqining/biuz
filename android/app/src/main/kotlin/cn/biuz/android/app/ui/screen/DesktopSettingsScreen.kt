package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzEmptyState
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.components.BiuzNoticeKind
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.DesktopSettingsError
import cn.biuz.android.connection.DesktopSettingsRow
import cn.biuz.android.connection.loadDesktopSettings
import cn.biuz.android.connection.updateDesktopSetting
import cn.biuz.android.protocol.JSONValue
import kotlinx.coroutines.launch

/**
 * 屏 028 · 桌面设置同步（iOS `P2ExtrasViews.swift:2298` DesktopSettingsPage 的 Android 对位）。
 *
 * 数据面：`setting.get` 读 + `setting.update({设置名: 新值})` 单键补丁写
 * （[loadDesktopSettings]/[updateDesktopSetting]，channel=setting【实证·上游仓 channels.ts:106】）。
 * 行渲染：boolean→toggle；string/int/double→可点编辑；readonly→仅展示；
 * toggle 与编辑一律先确认弹层再下发（桌面配置写即时生效，影响所有运行中会话）；
 * 取消不落本地值（行值恒读 entries，自动回弹——iOS 同契约）。
 * 四态：未连接（引导 U-7）/ 加载中 / 列表 / 失败+重试；空/失败诚实占位不给假数据。
 *
 * §5-15 纪律（呈现期捕获传参）：确认弹层回调捕获 `pc`（呈现期值），先发命令后清
 * pendingChange；禁止回调内回读宿主状态（先清后发 = 永不发出）。
 */
@Composable
fun DesktopSettingsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<DesktopSettingsRow>>(emptyList()) }
    var notConnected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DesktopSettingsRow?>(null) }
    var editText by remember { mutableStateOf("") }
    var pendingChange by remember { mutableStateOf<PendingChange?>(null) }

    suspend fun load() {
        loading = true
        failure = null
        notConnected = false
        try {
            // 白名单过滤：只留有中文名、移动端可调的设置（其余是桌面内部状态，
            // 原样上屏是一列英文 camelCase 键——见 keyNameRes 注释）
            // 展示顺序 = keyNameRes 声明顺序（mapOf 保插入序）：通用 → 终端网络 → 浏览器
            // → Agent → 工具分组 → 归档套餐。表外键排最后（理论上不会出现——上面已过滤）
            val order = keyNameRes.keys.withIndex().associate { (i, k) -> k to i }
            rows = session.loadDesktopSettings()
                .filter { it.key in keyNameRes }
                .sortedWith(compareBy({ order[it.key] ?: Int.MAX_VALUE }, { it.key }))
        } catch (e: DesktopSettingsError.NotConnected) {
            notConnected = true
            rows = emptyList()
        } catch (e: DesktopSettingsError.Failed) {
            failure = e.message
        } catch (e: Throwable) {
            failure = e.message ?: e::class.simpleName ?: ""
        } finally {
            loading = false
        }
    }

    val mode by session.mode.collectAsStateWithLifecycle()
    val connected = mode is AppSession.Mode.Connected
    LaunchedEffect(connected) {
        if (connected) {
            load()
        } else {
            notConnected = true
            rows = emptyList()
            loading = false
        }
    }

    // 键 → 中文名（一次性解析；对话框/提示/行标题统一用它——键名只留 testTag 供排障）。
    // mapValues 是 inline，可在其 lambda 里取 stringResource。
    val keyDisplayNames = keyNameRes.mapValues { (_, res) -> stringResource(res) }
    val updatedTemplate = stringResource(R.string.desktop_notice_updated)
    fun dispatch(change: PendingChange) {
        if (busyKey != null) return
        busyKey = change.key
        notice = null
        scope.launch {
            val error = session.updateDesktopSetting(change.key, change.newValue)
            busyKey = null
            if (error == null) {
                notice = updatedTemplate.format(keyDisplayNames[change.key] ?: change.key)
                noticeIsError = false
                load()
            } else {
                // 失败：行值回弹（本地未改，读自 entries）+ 错误提示常驻上屏
                notice = error
                noticeIsError = true
            }
        }
    }

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
                text = stringResource(R.string.desktop_settings_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.desktop_not_connected),
                hint = stringResource(R.string.desktop_not_connected_hint),
                modifier = Modifier.testTag("12-desktop-not-connected"),
            )

            loading && rows.isEmpty() -> BiuzLoadingState(
                text = stringResource(R.string.desktop_loading),
                tag = "12-desktop-loading",
            )

            failure != null -> Column(
                modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.desktop_load_failed) + " · " + failure,
                    onRetry = { scope.launch { load() } },
                    tag = "12-desktop-load-error",
                )
            }

            rows.isEmpty() -> BiuzEmptyState(
                title = stringResource(R.string.desktop_empty),
                hint = failure ?: stringResource(R.string.desktop_empty_hint),
                modifier = Modifier.testTag("12-desktop-empty"),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                if (notice != null) {
                    BiuzInlineNotice(
                        message = notice.orEmpty(),
                        kind = if (noticeIsError) BiuzNoticeKind.Error else BiuzNoticeKind.Info,
                        modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
                        tag = "12-desktop-notice",
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = BiuZSpacing.md, end = BiuZSpacing.md,
                        top = BiuZSpacing.sm, bottom = BiuZSpacing.xl,
                    ),
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.medium)
                                .background(BiuZBgCard)
                                .border(1.dp, BiuZBorder, BiuZRadius.medium),
                        ) {
                            rows.forEachIndexed { index, entry ->
                                SettingRowView(
                                    entry = entry,
                                    busy = busyKey == entry.key,
                                    onToggle = { newValue ->
                                        pendingChange = PendingChange(entry.key, JSONValue.Bool(newValue))
                                    },
                                    onEdit = {
                                        editText = editSeed(entry.value)
                                        editing = entry
                                    },
                                )
                                if (index != rows.lastIndex) {
                                    HorizontalDivider(color = BiuZBorder)
                                }
                            }
                        }
                        Spacer(Modifier.height(BiuZSpacing.xs))
                        Text(
                            text = stringResource(R.string.desktop_footer),
                            fontSize = 11.sp,
                            color = BiuZText3,
                            modifier = Modifier.padding(horizontal = BiuZSpacing.xs).testTag("12-desktop-footer"),
                        )
                    }
                }
            }
        }
    }

    // 编辑弹层（string/number 项；int/double 校验可解析，其余按字符串下发）
    editing?.let { entry ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.desktop_edit_title, keyDisplayNames[entry.key] ?: entry.key)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.desktop_edit_message,
                            keyDisplayNames[entry.key] ?: entry.key,
                            displayValueText(entry.value),
                        ),
                        fontSize = 12.sp,
                        color = BiuZText2,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (entry.value is JSONValue.Str) {
                                KeyboardType.Text
                            } else {
                                KeyboardType.Decimal
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("12-desktop-field-value"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editText.trim()
                        editing = null
                        // 空输入不提交不上屏错误（iOS 同口径：guard !text.isEmpty）
                        if (trimmed.isEmpty()) return@TextButton
                        val commit = commitEdit(entry, trimmed)
                        if (commit != null) {
                            pendingChange = commit
                        } else {
                            notice = commitInvalidMessage(keyDisplayNames[entry.key] ?: entry.key, entry, trimmed)
                            noticeIsError = true
                        }
                    },
                    modifier = Modifier.testTag("12-desktop-act-save"),
                ) { Text(stringResource(R.string.desktop_act_update)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.desktop_act_cancel))
                }
            },
        )
    }

    // 修改确认弹层（硬要求：桌面配置写即时生效）。呈现期捕获 pc，先发后清（§5-15）。
    pendingChange?.let { pc ->
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            title = { Text(stringResource(R.string.desktop_confirm_title, keyDisplayNames[pc.key] ?: pc.key)) },
            text = { Text(stringResource(R.string.desktop_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingChange = null
                        dispatch(pc)
                    },
                    modifier = Modifier.testTag("12-desktop-confirm-update"),
                ) { Text(stringResource(R.string.desktop_act_update)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChange = null }) {
                    Text(stringResource(R.string.desktop_act_cancel))
                }
            },
        )
    }
}

/** 待确认的修改（确认弹层呈现中；null = 无）。 */
private data class PendingChange(val key: String, val newValue: JSONValue)

/**
 * 设置键 → 中文名映射资源（iOS DesktopSettingsPage.keyNames 同表同序，
 * 【实证·上游仓 packages/ui/src/i18n/locales/zh-CN.ts +
 * validationAppSettings.ts:533+ appSettingsObjectSchema】）。
 *
 * **白名单制**：上游 `setting.get` 回整份 `AppSettings`，含大量 renderer 内部状态
 * （recentProjects / lastWorkspaceSession / sourceServers / connectionProfiles /
 * *MigrationInitialized / *Version / *UpdatedAt）。这些既不是可调设置也没有中文名，
 * 原样上屏就是一列英文 camelCase 键（2026-11-25 用户报「桌面设置还有些英文没有对应的
 * 中文」）。只有本表收录的键才渲染；未知键（含桌面后续新增项）不展示，收录后再开。
 */
private val keyNameRes: Map<String, Int> = mapOf(
    // 通用
    "locale" to R.string.desktop_key_locale,
    "localePreference" to R.string.desktop_key_localePreference,
    "receivePreviewUpdates" to R.string.desktop_key_receivePreviewUpdates,
    "autoDownloadAndInstallUpdates" to R.string.desktop_key_autoDownloadAndInstallUpdates,
    "mobilePushEnabled" to R.string.desktop_key_mobilePushEnabled,
    "closeToTrayOnWindows" to R.string.desktop_key_closeToTrayOnWindows,
    "keepAwakeWhileRunning" to R.string.desktop_key_keepAwakeWhileRunning,
    "desktopZoomLevel" to R.string.desktop_key_desktopZoomLevel,
    "desktopChromiumHardwareAccelerationEnabled" to R.string.desktop_key_desktopChromiumHardwareAccelerationEnabled,
    "dataBaseDir" to R.string.desktop_key_dataBaseDir,
    // 终端与网络
    "terminalInheritSystemProfile" to R.string.desktop_key_terminalInheritSystemProfile,
    "terminalFontFamily" to R.string.desktop_key_terminalFontFamily,
    "integratedTerminalShell" to R.string.desktop_key_integratedTerminalShell,
    "httpProxy" to R.string.desktop_key_httpProxy,
    "httpProxyNoProxy" to R.string.desktop_key_httpProxyNoProxy,
    "httpProxyCaCertPath" to R.string.desktop_key_httpProxyCaCertPath,
    // 浏览器
    "embeddedBrowserAllowInsecureCertificates" to R.string.desktop_key_embeddedBrowserAllowInsecureCertificates,
    "embeddedBrowserViewportPreference" to R.string.desktop_key_embeddedBrowserViewportPreference,
    "fileTreeHtmlClickOpensBrowser" to R.string.desktop_key_fileTreeHtmlClickOpensBrowser,
    "computerUseComposerEntryHidden" to R.string.desktop_key_computerUseComposerEntryHidden,
    // Agent 行为
    "zcodeInteractionBehavior" to R.string.desktop_key_zcodeInteractionBehavior,
    "askUserQuestionAutoResolutionEnabled" to R.string.desktop_key_askUserQuestionAutoResolutionEnabled,
    "pluginAutoRecommendationEnabled" to R.string.desktop_key_pluginAutoRecommendationEnabled,
    "modelIoFullRetentionEnabled" to R.string.desktop_key_modelIoFullRetentionEnabled,
    "messageStreamShowReasoning" to R.string.desktop_key_messageStreamShowReasoning,
    "messageStreamShowTodos" to R.string.desktop_key_messageStreamShowTodos,
    "nativeSearchEnhancementsEnabled" to R.string.desktop_key_nativeSearchEnhancementsEnabled,
    "toolSearchMode" to R.string.desktop_key_toolSearchMode,
    "agentAlwaysBackgroundEnabled" to R.string.desktop_key_agentAlwaysBackgroundEnabled,
    "totalTokensReminderEnabled" to R.string.desktop_key_totalTokensReminderEnabled,
    "replyLanguage" to R.string.desktop_key_replyLanguage,
    "memoryEnabled" to R.string.desktop_key_memoryEnabled,
    "proactiveSuggestionsEnabled" to R.string.desktop_key_proactiveSuggestionsEnabled,
    "dynamicWorkflowMode" to R.string.desktop_key_dynamicWorkflowMode,
    // 工具分组
    "toolGroupingExploreEnabled" to R.string.desktop_key_toolGroupingExploreEnabled,
    "toolGroupingTerminalEnabled" to R.string.desktop_key_toolGroupingTerminalEnabled,
    "toolGroupingChangesEnabled" to R.string.desktop_key_toolGroupingChangesEnabled,
    // 归档与套餐
    "taskAutoArchiveEnabled" to R.string.desktop_key_taskAutoArchiveEnabled,
    "taskAutoArchiveOlderThanDays" to R.string.desktop_key_taskAutoArchiveOlderThanDays,
    "providerFamilyDomain" to R.string.desktop_key_providerFamilyDomain,
    "providerFamilyConnectionSelections" to R.string.desktop_key_providerFamilyConnectionSelections,
)

/** 行渲染（boolean→toggle；string/number→可点编辑；readonly→无箭头）。 */
@Composable
private fun SettingRowView(
    entry: DesktopSettingsRow,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val mapped = keyNameRes[entry.key]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiuZSpacing.md)
            .heightIn(min = 48.dp)
            .testTag("12-desktop-setting-row-${entry.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // 只显示中文名：原始键名副行已撤（2026-11-25 用户报英文残留）。
            // 键名仍留在 testTag（12-desktop-setting-row-<key>）供排障与 E2E 定位。
            Text(
                text = mapped?.let { stringResource(it) } ?: entry.key,
                fontSize = 15.sp,
                color = BiuZText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("12-desktop-busy-${entry.key}"),
                strokeWidth = 2.dp,
                color = BiuZAccent,
            )
        } else {
            TrailingView(entry = entry, onToggle = onToggle, onEdit = onEdit)
        }
    }
}

@Composable
private fun TrailingView(
    entry: DesktopSettingsRow,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    when (val value = entry.value) {
        is JSONValue.Bool ->
            // 开关不即时下发：触发确认弹层后再改（取消自动回弹：值恒读 entries）
            Switch(
                checked = value.value,
                onCheckedChange = { newValue ->
                    if (newValue != value.value) onToggle(newValue)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = BiuZAccent),
                modifier = Modifier.testTag("12-desktop-toggle-${entry.key}"),
            )
        else -> if (entry.readonly) {
            Text(
                text = displayValueText(value),
                fontSize = 11.sp,
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onEdit() }
                    .padding(horizontal = BiuZSpacing.tiny, vertical = BiuZSpacing.sm)
                    .testTag("12-desktop-edit-${entry.key}"),
            ) {
                Text(
                    text = displayValueText(value),
                    fontSize = 11.sp,
                    color = BiuZText2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = "›", fontSize = 14.sp, color = BiuZText3, modifier = Modifier.padding(start = BiuZSpacing.xxs))
            }
        }
    }
}

/** 显示值（bool→开/关；string 枚举中文化；未命中回退原值——词表之外内部态如实显示）。 */
@Composable
private fun displayValueText(value: JSONValue): String = when (value) {
    is JSONValue.Bool -> stringResource(if (value.value) R.string.desktop_value_on else R.string.desktop_value_off)
    is JSONValue.Int -> value.value.toString()
    is JSONValue.Double -> value.value.toString()
    is JSONValue.Str ->
        if (value.value.isEmpty()) stringResource(R.string.desktop_value_empty)
        else localizedEnumText(value.value)
    else -> "-"
}

/**
 * 常见枚举档位中文化（未命中回退原词，不猜测——真实的自由文本如字体名/语言名/路径
 * 保持原样）。档位值来源【实证·上游仓】：zcodeInteractionBehavior queue/guide
 * （validationAppSettings.ts:52）；toolSearchMode 三档（settings.toolSearch.*）；
 * dynamicWorkflowMode disabled/onDemand/alwaysOn（dynamic-workflow-feature.ts:9）。
 */
@Composable
private fun localizedEnumText(s: String): String = when (s) {
    "zh-CN" -> stringResource(R.string.desktop_value_zh_cn)
    "en-US" -> "English"
    "system" -> stringResource(R.string.desktop_value_system)
    "queue" -> stringResource(R.string.desktop_value_queue)
    "guide" -> stringResource(R.string.desktop_value_guide)
    "zai" -> stringResource(R.string.desktop_value_zai)
    "bigmodel" -> stringResource(R.string.desktop_value_bigmodel)
    "auto" -> stringResource(R.string.desktop_value_auto)
    "local" -> stringResource(R.string.desktop_value_local)
    "off" -> stringResource(R.string.desktop_value_off_choice)
    "disabled" -> stringResource(R.string.desktop_value_off_choice)
    "onDemand" -> stringResource(R.string.desktop_value_on_demand)
    "alwaysOn" -> stringResource(R.string.desktop_value_always_on)
    "shell" -> stringResource(R.string.desktop_value_shell)
    "cmd" -> "CMD"
    "git-bash" -> "Git Bash"
    "powershell", "pwsh" -> "PowerShell"
    else -> s
}

/** 编辑弹层初值（bool/复合形态不进编辑弹层，走 toggle 或只读展示）。 */
private fun editSeed(value: JSONValue): String = when (value) {
    is JSONValue.Int -> value.value.toString()
    is JSONValue.Double -> value.value.toString()
    is JSONValue.Str -> value.value
    else -> ""
}

/**
 * 编辑提交（纯函数）：按原值形态规整——int/double 校验可解析（不可解析返回 null，
 * 由调用方上屏错误），其余按字符串下发。调用方先 gate 空输入。
 */
private fun commitEdit(entry: DesktopSettingsRow, text: String): PendingChange? = when (val v = entry.value) {
    is JSONValue.Int -> text.toIntOrNull()?.let { PendingChange(entry.key, JSONValue.Int(it)) }
    is JSONValue.Double -> text.toDoubleOrNull()?.let { PendingChange(entry.key, JSONValue.Double(it)) }
    is JSONValue.Str -> PendingChange(entry.key, JSONValue.Str(text))
    else -> null // 复合形态不进编辑链（防御；入口已 gate）
}

/** int/double 校验失败的错误文案（commitEdit 返回 null 且非空输入时；名字传中文名）。 */
private fun commitInvalidMessage(displayName: String, entry: DesktopSettingsRow, text: String): String = when (entry.value) {
    is JSONValue.Int -> "「$displayName」需要整数（输入：$text）"
    is JSONValue.Double -> "「$displayName」需要数字（输入：$text）"
    else -> "修改失败 · 无法解析输入"
}

package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.settings.AppAppearance
import cn.biuz.android.app.settings.AppLanguagePreference
import cn.biuz.android.app.settings.BrandTapGate
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import kotlinx.coroutines.delay

/**
 * 设置（iOS SettingsView 同位）：数据与统计/关于分组 + 开发者隐藏开关 +
 * 品牌页脚。行集合随里程碑扩列（IM Bot/设备与配对/模型/外观等 iOS 行的 Android
 * 落位随各自服务面移植接线）。
 *
 * 开发者模式（§5-17 裁决 Android 落位）：
 * - 品牌页脚行「BiuZ · works with ZCode」**连点 7 次**翻转（隐藏开关，[BrandTapGate]；
 *   无超时窗、触发即清零），翻转落持久化（[AppSettingsStore.setDeveloperMode]）；
 * - 「开发者」分组仅开启态出现（总开关自持）；分组内 toggle 与连点同一写入点；
 * - 有效口径 = 持久化 || -ZCodeDevMode 启动参数（[AppSettingsStore.effectiveDeveloperMode]），
 *   连接页手动连接族（手动输入/桌面端指引/令牌说明）与扫码页失败态手动族入口
 *   统一由它 gating（连接页/扫码页消费同一 StateFlow）。
 *
 * HIDDEN 入口预留（隐藏入口待恢复清单，恢复条件见各注记；§6 裁决原文固化）：
 * - H1 反馈工单（iOS SettingsView.swift:434-438）——feedback.list 双重未取证
 *   （web feedbackService 仅 create/comment/upload 族无 list）；恢复条件 =
 *   feedback.list 真机探针回执成形后还原 navRow + 路由。
 * - H2 startSavedWorkflow 启动入口（iOS P2ExtrasViews.swift:1091/:1125）——不在
 *   web 枚举；恢复条件 = 真机 accepted 回执。工作流库/错峰任务行随 :store 对应
 *   读面移植时接线，不属本里程碑。
 */
@Composable
fun SettingsScreen(
    session: AppSession,
    settings: AppSettingsStore,
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenArchives: () -> Unit = {},
    onOpenCapability: (String) -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenBehavior: () -> Unit = {},
    onOpenDesktopSettings: () -> Unit = {},
    onOpenAutomations: () -> Unit = {},
    onOpenPersonalization: () -> Unit = {},
    onOpenSubagents: () -> Unit = {},
    onOpenHooks: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val developerMode by settings.developerMode.collectAsStateWithLifecycle()
    val language by settings.language.collectAsStateWithLifecycle()
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    val effectiveDeveloperMode = developerMode || settings.developerModeStartupArgument

    // 品牌行连点闸门（7 次翻转；无超时窗——iOS handleBrandTap 同契约）
    val brandTapGate = remember { BrandTapGate() }
    // 切换公告（2.5s 自动退场；具名可空变量 + LaunchedEffect 计时）
    var developerModeAnnouncement by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(developerModeAnnouncement) {
        if (developerModeAnnouncement != null) {
            delay(2_500)
            developerModeAnnouncement = null
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_title),
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

            // 账号分组（Relay V2 账户层；2026-11-24 用户报障「没有退出登录的按钮，登录按钮
            // 不明显」——退出入口此前只在设备页「切换账号」小字（iOS 同构），设置页无账号区。
            // 已登录：账号行 + 实心退出按钮（复用设备页切换账号确认弹窗文案）；未登录：实心登录 CTA）
            SettingsGroup(title = stringResource(R.string.settings_group_account)) {
                val oauthVersion by session.oauthSessionVersion.collectAsStateWithLifecycle()
                androidx.compose.runtime.key(oauthVersion) {
                    val account = session.oauthUserInfo
                    if (account != null) {
                        var showLogoutConfirm by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = account.displayName.take(1),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(Modifier.width(BiuZSpacing.sm))
                                Text(
                                    text = "${account.displayName} · Z.ai",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(BiuZSpacing.sm))
                            // 危险按钮 = 红描边，
                            // 描边/字色显式取印系 red 令牌，不用 Material errorContainer 实底
                            OutlinedButton(
                                onClick = { showLogoutConfirm = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("13-btn-logout"),
                                shape = BiuZRadius.medium,
                                border = BorderStroke(1.dp, BiuZRed.copy(alpha = BiuZAlpha.half)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BiuZRed),
                            ) {
                                Text(stringResource(R.string.settings_logout))
                            }
                        }
                        if (showLogoutConfirm) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showLogoutConfirm = false },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            showLogoutConfirm = false
                                            session.logout()
                                        },
                                        modifier = Modifier.testTag("13-logout-confirm"),
                                    ) {
                                        androidx.compose.material3.Text(
                                            stringResource(R.string.switch_account_confirm),
                                        )
                                    }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(
                                        onClick = { showLogoutConfirm = false },
                                    ) {
                                        androidx.compose.material3.Text(
                                            stringResource(R.string.switch_account_cancel),
                                        )
                                    }
                                },
                                title = { androidx.compose.material3.Text(stringResource(R.string.switch_account_title)) },
                                text = { androidx.compose.material3.Text(stringResource(R.string.switch_account_desc)) },
                            )
                        }
                    } else {
                        Button(
                            onClick = onOpenLogin,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                                .height(44.dp)
                                .testTag("13-btn-login"),
                            shape = BiuZRadius.medium,
                        ) {
                            Text(stringResource(R.string.login_entry))
                        }
                    }
                }
            }
            Spacer(Modifier.height(BiuZSpacing.md))

            // 数据与统计分组
            SettingsGroup(title = stringResource(R.string.settings_group_data)) {
                // 018 项目管理（web「项目排在归档管理之前」同位；readTree 只读第一波）
                SettingsNavRow(
                    title = stringResource(R.string.projects_title),
                    icon = "📁",
                    subtitle = stringResource(R.string.settings_row_projects_sub),
                    testTagId = "13-row-projects",
                    onClick = onOpenProjects,
                )
                SettingsNavRow(
                    title = stringResource(R.string.settings_row_archives),
                    icon = "🗄",
                    subtitle = stringResource(R.string.settings_row_archives_sub),
                    testTagId = "13-row-archives",
                    onClick = onOpenArchives,
                )
                SettingsNavRow(
                    title = stringResource(R.string.settings_row_usage),
                    icon = "📊",
                    subtitle = stringResource(R.string.settings_row_usage_sub),
                    testTagId = "12-row-usage",
                    onClick = onOpenUsage,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.md))

            // 基础设置分组（外观 / 语言 / G7 消息流待办卡开关）
            SettingsGroup(title = stringResource(R.string.settings_group_basic)) {
                // G7（2026-11-25 与 iOS 同步）：消息流内待办卡开关（web
                // messageStreamShowTodos default(false) 同值同义；本机偏好）
                val showTodosInStream by settings.showTodosInStream.collectAsStateWithLifecycle()
                SettingsToggleRow(
                    title = stringResource(R.string.settings_row_stream_todos),
                    subtitle = stringResource(R.string.settings_row_stream_todos_sub),
                    isOn = showTodosInStream,
                    onToggle = { enabled -> settings.setShowTodosInStream(enabled) },
                    testTagId = "12-row-stream-todos",
                )
                // G41（2026-11-25 与 iOS 同步）：行为设置（桌面行为五键策展面——
                // setting 频道直写；导航面经 onOpenBehavior 参数挂 BiuZApp）
                SettingsNavRow(
                    title = stringResource(R.string.settings_row_behavior),
                    icon = "⚙",
                    subtitle = stringResource(R.string.settings_row_behavior_sub),
                    value = null,
                    testTagId = "12-row-behavior",
                    onClick = onOpenBehavior,
                )
                SettingsNavRow(
                    title = stringResource(R.string.settings_row_appearance),
                    icon = "🌗",
                    subtitle = null,
                    value = when (appearance) {
                        AppAppearance.SYSTEM -> stringResource(R.string.appearance_system)
                        AppAppearance.LIGHT -> "朱批 · 浅色"
                        AppAppearance.DARK -> "朱批 · 深色"
                    },
                    testTagId = "13-row-appearance",
                    onClick = onOpenAppearance,
                )
                SettingsNavRow(
                    title = stringResource(R.string.language_title),
                    icon = "🌐",
                    subtitle = null,
                    value = when (language) {
                        AppLanguagePreference.SYSTEM -> stringResource(R.string.language_opt_system)
                        AppLanguagePreference.CHINESE -> stringResource(R.string.language_opt_chinese)
                        AppLanguagePreference.ENGLISH -> stringResource(R.string.language_opt_english)
                    },
                    testTagId = "12-row-language",
                    onClick = onOpenLanguage,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.md))

            // 关于分组
            // Agent 能力组（iOS 12-row-memory/skills/mcp/plugins 同位；连接态只读清单）
            SettingsGroup(title = stringResource(R.string.settings_group_capabilities)) {
                // 027 个性化（web Agent 能力组第一项同位；「仅本地桌面端」指引页）
                SettingsNavRow(
                    title = stringResource(R.string.personalization_title),
                    icon = "🧬",
                    subtitle = stringResource(R.string.settings_row_personalization_sub),
                    testTagId = "13-row-personalization",
                    onClick = onOpenPersonalization,
                )
                SettingsNavRow(
                    title = stringResource(R.string.cap_memory_title),
                    icon = "🧠",
                    subtitle = stringResource(R.string.settings_row_memory_sub),
                    testTagId = "13-row-memory",
                    onClick = { onOpenCapability("memory") },
                )
                // 026 子智能体（web Agent 能力组同位；subagents.list 只读清单）
                SettingsNavRow(
                    title = stringResource(R.string.subagents_title),
                    icon = "🤖",
                    subtitle = stringResource(R.string.settings_row_subagents_sub),
                    testTagId = "13-row-subagents",
                    onClick = onOpenSubagents,
                )
                SettingsNavRow(
                    title = stringResource(R.string.cap_skills_title),
                    icon = "✨",
                    subtitle = stringResource(R.string.settings_row_skills_sub),
                    testTagId = "13-row-skills",
                    onClick = { onOpenCapability("skills") },
                )
                SettingsNavRow(
                    title = stringResource(R.string.cap_mcp_title),
                    icon = "🖧",
                    subtitle = stringResource(R.string.settings_row_mcp_sub),
                    testTagId = "13-row-mcp",
                    onClick = { onOpenCapability("mcp") },
                )
                SettingsNavRow(
                    title = stringResource(R.string.cap_plugins_title),
                    icon = "🧩",
                    subtitle = stringResource(R.string.settings_row_plugins_sub),
                    testTagId = "13-row-plugins",
                    onClick = { onOpenCapability("plugins") },
                )
                // 023 自动化（iOS 12-row-automation 同位；只读清单 + 运行历史）
                SettingsNavRow(
                    title = stringResource(R.string.automation_title),
                    icon = "⏰",
                    subtitle = stringResource(R.string.settings_row_automation_sub),
                    testTagId = "12-row-automation",
                    onClick = onOpenAutomations,
                )
                // 024 钩子（web Agent 能力组同位；hooks.loadHooks 只读清单）
                SettingsNavRow(
                    title = stringResource(R.string.hooks_title),
                    icon = "⚓",
                    subtitle = stringResource(R.string.settings_row_hooks_sub),
                    testTagId = "13-row-hooks",
                    onClick = onOpenHooks,
                )
                // 028 桌面设置同步（iOS 12-row-desktop-settings 同位；连接态二分副标题）
                val connectedForDesktopSettings =
                    session.mode.collectAsStateWithLifecycle().value is AppSession.Mode.Connected
                SettingsNavRow(
                    title = stringResource(R.string.desktop_settings_title),
                    icon = "🖥",
                    subtitle = stringResource(
                        if (connectedForDesktopSettings) {
                            R.string.desktop_settings_sub_connected
                        } else {
                            R.string.desktop_settings_sub_disconnected
                        },
                    ),
                    testTagId = "12-row-desktop-settings",
                    onClick = onOpenDesktopSettings,
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_group_about)) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_row_about),
                    subtitle = stringResource(R.string.settings_row_about_sub),
                    testTagId = "12-row-about",
                    onClick = onOpenAbout,
                )
            }
            // HIDDEN(H1 反馈工单)：入口行隐藏（feedback.list 双重未取证——web
            // feedbackService 仅 create/comment/upload 族无 list，app 侧形状亦未探针）。
            // 恢复条件：feedback.list 真机探针回执成形后还原 navRow
            // （title=反馈工单，testTag=12-row-feedback）+ 路由与只读页。
            // SettingsGroup(title = …) {
            //     SettingsNavRow(
            //         title = stringResource(R.string.settings_row_feedback),
            //         subtitle = stringResource(R.string.settings_row_feedback_sub),
            //         testTagId = "12-row-feedback",
            //         onClick = onOpenFeedbackTickets,
            //     )
            // }
            // HIDDEN(H2 startSavedWorkflow 启动)：不在 web 枚举；恢复条件 = 真机
            // accepted 回执（工作流库行与运行详情随 :store 读面移植另行接线）。
            Spacer(Modifier.height(BiuZSpacing.md))

            // 开发者分组（仅开启态出现；总开关自持）
            if (effectiveDeveloperMode) {
                SettingsGroup(title = stringResource(R.string.settings_dev_group_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_row_dev_mode),
                        subtitle = stringResource(R.string.settings_row_dev_mode_sub),
                        isOn = developerMode,
                        onToggle = { enabled ->
                            settings.setDeveloperMode(enabled)
                            developerModeAnnouncement = enabled
                        },
                        testTagId = "12-row-dev-mode",
                    )
                }
                Spacer(Modifier.height(BiuZSpacing.md))
            }

            // 桌面宿主信息行（P3-11B system.info）未接线——随 connection 只读信息面
            // 扩列时补（iOS 12-desktop-host；恢复时置于页脚上方）

            // 数据边界页脚（12-foot-data-source）
            Text(
                text = stringResource(R.string.app_foot_data_source),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BiuZSpacing.lg)
                    .testTag("12-foot-data-source"),
            )
            Spacer(Modifier.height(BiuZSpacing.sm))

            // 开发者模式切换公告（12-dev-announce；2.5s 自动退场）
            developerModeAnnouncement?.let { turnedOn ->
                Text(
                    text = stringResource(
                        if (turnedOn) R.string.settings_dev_announce_on else R.string.settings_dev_announce_off,
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("12-dev-announce"),
                )
                Spacer(Modifier.height(BiuZSpacing.sm))
            }

            // 品牌页脚行（12-brand-name）：连点 7 次翻转开发者模式的隐藏开关锚点。
            // 商标红线（branding/naming.md）：仅以描述性 works with ZCode 表达社区版
            // 身份——iOS 原行含独立「ZCode」字样，Android 按红线改 works-with 表述。
            Text(
                text = stringResource(R.string.settings_foot_brand),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (brandTapGate.tap()) {
                            val turnedOn = !settings.developerMode.value
                            settings.setDeveloperMode(turnedOn)
                            developerModeAnnouncement = turnedOn
                        }
                    }
                    .padding(vertical = BiuZSpacing.sm)
                    .testTag("12-brand-name"),
            )
            // 底部缓冲（iOS padding(.bottom, 72) 同口径：页脚不被遮挡）
            Spacer(Modifier.height(BiuZSpacing.xxl))
        }
    }
}

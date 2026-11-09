package cn.biuz.android.app.ui
import androidx.compose.ui.platform.testTag

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.ui.screen.AboutScreen
import cn.biuz.android.app.ui.screen.ArchivesScreen
import cn.biuz.android.app.ui.screen.AutomationsScreen
import cn.biuz.android.app.ui.screen.AppearanceScreen
import cn.biuz.android.app.ui.screen.CapabilityListScreen
import cn.biuz.android.app.ui.screen.ChatScreen
import cn.biuz.android.app.ui.screen.ConnectScreen
import cn.biuz.android.app.ui.screen.DevicesScreen
import cn.biuz.android.app.ui.screen.DesktopSettingsScreen
import cn.biuz.android.app.ui.screen.SharePreviewScreen
import cn.biuz.android.app.ui.screen.FilePreviewScreen
import cn.biuz.android.app.ui.screen.FileTreeScreen
import cn.biuz.android.app.ui.screen.FilesScreen
import cn.biuz.android.app.ui.screen.LanguageSettingsScreen
import cn.biuz.android.app.ui.screen.LoginScreen
import cn.biuz.android.app.ui.screen.ScanScreen
import cn.biuz.android.app.ui.screen.SessionsScreen
import cn.biuz.android.app.ui.screen.TaskOutputScreen
import cn.biuz.android.app.ui.screen.SettingsScreen
import cn.biuz.android.app.ui.screen.UsageStatsScreen
import cn.biuz.android.connection.AppSession
import kotlinx.coroutines.launch

/** AppSession 装配面（深层占位屏后续经此取 Store 面而不必逐层传参）。 */
val LocalAppSession = staticCompositionLocalOf<AppSession> {
    error("AppSession 未装配（BiuzApplication/MainActivity 接线缺失）")
}

/** 应用设置装配面（开发者模式/语言偏好；BiuzApplication 构造，全导航树可达）。 */
val LocalAppSettings = staticCompositionLocalOf<AppSettingsStore> {
    error("AppSettingsStore 未装配（BiuzApplication/MainActivity 接线缺失）")
}

/**
 * 导航宿主（Routes：connect（连接页，未连接拦截入口）→ scan（扫码）/ sessions（会话列表）
 * → chat/{sessionId}（会话流）/ settings（设置）→ settings/{language,about,usage}（设置族）。
 *
 * 连接生命周期接线（iOS RootView 数据源切换同语义）：
 * - 冷启动一次 bootstrap（已存服务器自动重连；未配置连接页为根）；
 * - mode=Connected → 会话列表为根（先装配 Store 再切 mode，装配面经 [AppSession.storeSurface]
 *   以 epoch 为 key 重绑——视图按 store 实例重建，防永久抱空 store）；
 * - mode=Demo（删除服务器等）→ 连接页为根；断线/连接失败不导航（快照/错误横幅原地呈现）。
 *
 * 开发者模式（§5-17）：设置页品牌行连点 7 次翻转持久化开关（[AppSettingsStore]），
 * 有效口径 = 持久化 || -ZCodeDevMode 启动参数；连接页/扫码页手动连接族统一消费同一
 * StateFlow（M2 壳期的 BuildConfig.DEBUG 等效与连接页品牌行临时手势已撤——见
 * AppSettingsStore KDoc）。
 */
@Composable
fun BiuZApp(
    session: AppSession,
    settings: AppSettingsStore,
    /** 009：冷启/热链带进来的分享 code（null = 非分享进入）。 */
    initialShareCode: String? = null,
) {
    val navController = rememberNavController()
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    CompositionLocalProvider(
        LocalAppSession provides session,
        LocalAppSettings provides settings,
    ) {
        // 冷启动装配（iOS RootView .task { await session.bootstrap() } 同位；幂等由
        // AppSession 生命周期保证——本组合即宿主根，仅执行一次）
        LaunchedEffect(session) {
            session.bootstrap()
        }
        val mode by session.mode.collectAsStateWithLifecycle()
        LaunchedEffect(mode) {
            val current = navController.currentDestination?.route
            when (mode) {
                is AppSession.Mode.Connected -> {
                    if (current == Routes.CONNECT) {
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                }
                is AppSession.Mode.Demo -> {
                    if (current != null && current != Routes.CONNECT) {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                }
                else -> Unit
            }
        }
        // 008-版本提示：桌面版本与链接声明不一致 → 阻断式提示（web 同口径，确认后重连）
        val versionMismatch by session.versionMismatch.collectAsStateWithLifecycle()
        versionMismatch?.let { desktopVersion ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { /* 阻断式：只能重新加载 */ },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            session.confirmVersionReload()
                            uiScope.launch { session.reconnect() }
                        },
                        modifier = androidx.compose.ui.Modifier.testTag("08-version-reload"),
                    ) {
                        androidx.compose.material3.Text(
                            androidx.compose.ui.res.stringResource(cn.biuz.android.app.R.string.version_notice_reload),
                        )
                    }
                },
                title = {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(cn.biuz.android.app.R.string.version_notice_title),
                    )
                },
                text = {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(
                            cn.biuz.android.app.R.string.version_notice_body,
                            desktopVersion,
                        ),
                    )
                },
            )
        }
        // 009 分享深链：有 code 即导航到只读预览（不要求先连接桌面）
        androidx.compose.runtime.LaunchedEffect(initialShareCode) {
            if (initialShareCode != null) navController.navigate(Routes.share(initialShareCode))
        }
        NavHost(navController = navController, startDestination = Routes.CONNECT) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    session = session,
                    settings = settings,
                    onOpenScan = { navController.navigate(Routes.SCAN) },
                    onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    onOpenDevices = { navController.navigate(Routes.DEVICES) },
                )
            }
            // 001-登录（Relay V2 账户层：拿 JWT → 设备目录自动落点）
            composable(Routes.LOGIN) {
                LoginScreen(
                    session = session,
                    onClose = { navController.popBackStack() },
                    onLoggedIn = { navController.navigate(Routes.DEVICES) },
                )
            }
            // 003-设备列表（登录后自动发现已配对电脑；点选即连 V2）
            // 009-分享落地（只读预览；公开分享未登录也可看）
            composable(Routes.SHARE) { entry ->
                SharePreviewScreen(
                    session = session,
                    shareCode = entry.arguments?.getString("code").orEmpty(),
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.DEVICES) {
                DevicesScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onConnected = {
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    session = session,
                    settings = settings,
                    onClose = { navController.popBackStack() },
                )
            }
            // 设置能力清单（记忆/技能/MCP/插件；iOS RemoteCapabilityListPage 对位，只读）
            composable(
                route = Routes.CAPABILITY,
                arguments = listOf(androidx.navigation.navArgument("kind") { type = androidx.navigation.NavType.StringType }),
            ) { entry ->
                val kind = when (entry.arguments?.getString("kind").orEmpty()) {
                    "skills" -> cn.biuz.android.connection.CapabilityKind.skills
                    "mcp" -> cn.biuz.android.connection.CapabilityKind.mcp
                    "plugins" -> cn.biuz.android.connection.CapabilityKind.plugins
                    else -> cn.biuz.android.connection.CapabilityKind.memory
                }
                CapabilityListScreen(
                    session = session,
                    kind = kind,
                    onBack = { navController.popBackStack() },
                )
            }
            // 015 外观（跟随系统 / 浅色 / 深色；即时生效 + 持久化）
            composable(Routes.APPEARANCE) {
                AppearanceScreen(settings = settings, onBack = { navController.popBackStack() })
            }
            // 019 归档管理（web settings/archives；文案对齐 archives.* 键）
            composable(Routes.ARCHIVES) {
                ArchivesScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                )
            }
            // 屏 07 执行输出（模型轨迹；iOS TaskOutputView 对位）
            composable(
                route = Routes.TASK_OUTPUT,
                arguments = listOf(androidx.navigation.navArgument("sessionId") { type = androidx.navigation.NavType.StringType }),
            ) { entry ->
                TaskOutputScreen(
                    session = session,
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SESSIONS) {
                SessionsScreen(
                    session = session,
                    onOpenChat = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onBackToConnect = {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenFiles = { navController.navigate(Routes.FILES) },
                    onOpenDevices = { navController.navigate(Routes.DEVICES) },
                )
            }
            // 文件页（屏 08 Diff 审查根 → 屏 09 目录树 → 文件预览；iOS FileTreeView/
            // DiffReviewView 直译对位）
            composable(Routes.FILES) {
                FilesScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onOpenTree = { navController.navigate(Routes.FILES_TREE) },
                )
            }
            composable(Routes.FILES_TREE) {
                FileTreeScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onOpenPreview = { node ->
                        navController.navigate(
                            Routes.filesPreview(
                                path = node.path,
                                name = node.name,
                                size = node.size?.toLong(),
                            ),
                        )
                    },
                )
            }
            composable(Routes.FILES_PREVIEW) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                val size = backStackEntry.arguments?.getString("size")?.toLongOrNull()?.takeIf { it >= 0 }
                FilePreviewScreen(
                    session = session,
                    path = path,
                    name = name,
                    size = size,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CHAT) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                ChatScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenTaskOutput = { navController.navigate(Routes.taskOutput(sessionId)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    session = session,
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onOpenLanguage = { navController.navigate(Routes.LANGUAGE) },
                    onOpenUsage = { navController.navigate(Routes.USAGE) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenArchives = { navController.navigate(Routes.ARCHIVES) },
                    onOpenCapability = { kind -> navController.navigate(Routes.capability(kind)) },
                    onOpenAppearance = { navController.navigate(Routes.APPEARANCE) },
                    onOpenDesktopSettings = { navController.navigate(Routes.DESKTOP_SETTINGS) },
                    onOpenAutomations = { navController.navigate(Routes.AUTOMATIONS) },
                )
            }
            // 028 桌面设置同步（setting.get/update；iOS DesktopSettingsPage 对位）
            composable(Routes.DESKTOP_SETTINGS) {
                DesktopSettingsScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                )
            }
            // 023 自动化（定时任务清单 + 运行历史；iOS AutomationsView 对位，只读）
            composable(Routes.AUTOMATIONS) {
                AutomationsScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.LANGUAGE) {
                LanguageSettingsScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.USAGE) {
                UsageStatsScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onGoConnect = {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

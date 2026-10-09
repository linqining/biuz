package cn.biuz.android.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.biuz.android.app.BuildConfig
import cn.biuz.android.app.ui.screen.ChatScreen
import cn.biuz.android.app.ui.screen.ConnectScreen
import cn.biuz.android.app.ui.screen.ScanScreen
import cn.biuz.android.app.ui.screen.SessionsScreen
import cn.biuz.android.app.ui.screen.SettingsScreen
import cn.biuz.android.connection.AppSession

/** AppSession 装配面（深层占位屏后续经此取 Store 面而不必逐层传参）。 */
val LocalAppSession = staticCompositionLocalOf<AppSession> {
    error("AppSession 未装配（BiuzApplication/MainActivity 接线缺失）")
}

/**
 * 导航宿主（Routes：connect（连接页，未连接拦截入口）→ scan（扫码）/ sessions（会话列表）
 * → chat/{sessionId}（会话流）/ settings（设置））。
 *
 * 连接生命周期接线（iOS RootView 数据源切换同语义）：
 * - 冷启动一次 bootstrap（已存服务器自动重连；未配置连接页为根）；
 * - mode=Connected → 会话列表为根（先装配 Store 再切 mode，装配面经 [AppSession.storeSurface]
 *   以 epoch 为 key 重绑——视图按 store 实例重建，防永久抱空 store）；
 * - mode=Demo（删除服务器等）→ 连接页为根；断线/连接失败不导航（快照/错误横幅原地呈现）。
 */
@Composable
fun BiuZApp(session: AppSession) {
    val navController = rememberNavController()
    // 开发者模式（交接 §5-17）：effectiveDeveloperMode = BuildConfig.DEBUG（本期调试开关等效）
    // || 连接页品牌行连点 7 次的本地 override（iOS 设置页品牌行落位；设置页随设置里程碑接线后迁移）。
    var developerModeOverride by rememberSaveable { mutableStateOf(false) }
    val developerMode = BuildConfig.DEBUG || developerModeOverride
    CompositionLocalProvider(LocalAppSession provides session) {
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
        NavHost(navController = navController, startDestination = Routes.CONNECT) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    session = session,
                    onOpenScan = { navController.navigate(Routes.SCAN) },
                    onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                    developerMode = developerMode,
                    onToggleDeveloperModeOverride = { developerModeOverride = !developerModeOverride },
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    session = session,
                    onClose = { navController.popBackStack() },
                    developerMode = developerMode,
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
                )
            }
            composable(Routes.CHAT) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                ChatScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

package cn.biuz.android.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.biuz.android.app.ui.screen.ChatScreen
import cn.biuz.android.app.ui.screen.ConnectScreen
import cn.biuz.android.app.ui.screen.ScanScreen
import cn.biuz.android.app.ui.screen.SessionsScreen
import cn.biuz.android.app.ui.screen.SettingsScreen

/**
 * 导航骨架：connect（连接页，未连接拦截入口）→ scan（扫码）/ sessions（会话列表）
 * → chat/{sessionId}（会话流）/ settings（设置）。五个目的地均为占位屏。
 */
@Composable
fun BiuZApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                onOpenScan = { navController.navigate(Routes.SCAN) },
                onOpenSessions = { navController.navigate(Routes.SESSIONS) },
            )
        }
        composable(Routes.SCAN) {
            ScanScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.SESSIONS) {
            SessionsScreen(
                onOpenChat = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
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

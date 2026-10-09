package cn.biuz.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.biuz.android.app.ui.BiuZApp
import cn.biuz.android.app.ui.theme.BiuZTheme

/**
 * 单 Activity 壳：全部界面走 Compose Navigation（routes 见 ui/Routes）。
 * AppSession 装配在 [BiuzApplication]（应用级单例，进程内共享连接与 Store 面），
 * 导航宿主经 CompositionLocal / 显式参数拿到装配面。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as BiuzApplication).appSession
        setContent {
            BiuZTheme {
                BiuZApp(session = session)
            }
        }
    }
}

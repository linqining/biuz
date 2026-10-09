package cn.biuz.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.biuz.android.app.ui.BiuZApp
import cn.biuz.android.app.ui.theme.BiuZTheme

/**
 * 单 Activity 壳：全部界面走 Compose Navigation（routes 见 ui/Routes）。
 * 业务接线（connection/store 装配）随 M2 落位，当前为导航骨架。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BiuZTheme {
                BiuZApp()
            }
        }
    }
}

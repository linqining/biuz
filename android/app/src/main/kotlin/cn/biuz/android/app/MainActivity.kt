package cn.biuz.android.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.biuz.android.app.settings.AppLanguagePreference
import cn.biuz.android.app.ui.BiuZApp
import cn.biuz.android.app.ui.theme.BiuZTheme
import java.util.Locale

/**
 * 单 Activity 壳：全部界面走 Compose Navigation（routes 见 ui/Routes）。
 * AppSession/AppSettings 装配在 [BiuzApplication]（应用级单例，进程内共享连接与
 * Store 面），导航宿主经 CompositionLocal / 显式参数拿到装配面。
 *
 * 语言生效链路（语言页选择 → 持久化 → 本基类注入 locale → Activity 重建即生效）：
 * attachBaseContext 按存档档位 createConfigurationContext 包基上下文——跟随系统档
 * 不包（回落系统首选），zh-Hans/en 固定档以 LocaleList 覆盖（minSdk 24 = LocaleList
 * 可用下限）。iOS 同位为 AppleLanguages 持久化 + 重启生效；Android 当场 recreate
 * 生效（偏差记录于交付 deviations）。
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as BiuzApplication
        val session = application.appSession
        val settings = application.appSettings
        setContent {
            BiuZTheme {
                BiuZApp(session = session, settings = settings)
            }
        }
    }

    /** 存档语言档 → locale 包裹上下文（system 档原样返回）。 */
    private fun wrapLocale(base: Context): Context {
        val application = application as? BiuzApplication ?: return base
        val preference = AppLanguagePreference.fromStored(
            application.appSettings.language.value.stored,
        )
        if (preference == AppLanguagePreference.SYSTEM) return base
        val configuration = Configuration(base.resources.configuration)
        val locale = when (preference) {
            AppLanguagePreference.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguagePreference.ENGLISH -> Locale.ENGLISH
            AppLanguagePreference.SYSTEM -> return base
        }
        configuration.setLocales(android.os.LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }
}

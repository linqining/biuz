package cn.biuz.android.app

import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.settings.AppAppearance
import cn.biuz.android.app.settings.AppLanguagePreference
import cn.biuz.android.app.ui.BiuZApp
import cn.biuz.android.app.ui.theme.BiuZTheme
import cn.biuz.android.connection.SharePreviewClient
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
 *
 * **系统栏图标明暗跟 App 主题，而不是系统深浅**（2026-11-24 默认浅色后的必修项）：
 * `enableEdgeToEdge()` 缺省按系统深浅选图标明暗；朱批默认浅色后会出现「系统深色 + App
 * 浅色」组合，缺省行为给出白色状态栏图标压在纸底上（真机目检不可辨）。故首帧前按存档
 * 偏好先定一次 [applySystemBars]，外观偏好变化时在组合内再同步。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 外观偏好 → 实际深浅档（system 档随系统；固定档恒该档）。纯函数，单测直测。 */
        internal fun resolveDarkTheme(appearance: AppAppearance, systemDark: Boolean): Boolean =
            when (appearance) {
                AppAppearance.LIGHT -> false
                AppAppearance.DARK -> true
                AppAppearance.SYSTEM -> systemDark
            }
    }

    /** 009 分享深链的当次 code（冷启读 intent，热链由 onNewIntent 更新并触发重组）。 */
    private val shareCodeState = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as BiuzApplication
        val session = application.appSession
        val settings = application.appSettings
        shareCodeState.value = SharePreviewClient.shareCodeFrom(intent?.dataString)
        // 首帧前按存档偏好定系统栏图标明暗（system 档此时读资源配置里的系统深浅）
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBars(resolveDarkTheme(settings.appearance.value, systemDark))
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle()
            // system 档随系统深浅；固定档恒该档（iOS AppearanceMode.colorScheme 同构）
            val darkTheme = resolveDarkTheme(appearance, isSystemInDarkTheme())
            LaunchedEffect(darkTheme) { applySystemBars(darkTheme) }
            BiuZTheme(darkTheme = darkTheme) {
                // G42 界面字号缩放（web --ui-font-size 同构）：fontScale 乘数只缩 sp
                // 文本，不动 dp 图标/间距/圆角（uiFontSize.ts applyUiFontSizePx :24 同口径）
                val currentDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = currentDensity.density,
                        fontScale = currentDensity.fontScale * cn.biuz.android.app.ui.theme.UiFontScale.multiplier,
                    ),
                ) {
                    BiuZApp(
                        session = session,
                        settings = settings,
                        initialShareCode = shareCodeState.value,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 应用在后台时点分享链接：更新 code 触发重组导航（不清已连接状态）
        SharePreviewClient.shareCodeFrom(intent.dataString)?.let { shareCodeState.value = it }
    }

    /**
     * 系统栏图标明暗随 App 主题：浅色主题 = 深色图标（`SystemBarStyle.light`），
     * 深色主题 = 浅色图标（`SystemBarStyle.dark`）。状态栏透明（内容延伸到栏下），
     * 导航栏 scrim 取 androidx.activity 的缺省值（三键导航时保证图标可辨）。
     */
    private fun applySystemBars(darkTheme: Boolean) {
        // 导航栏 scrim = androidx.activity `EdgeToEdge.kt` 缺省值（不放 companion：
        // JVM 单测访问 companion 的纯函数时不该触发 android.graphics 类初始化）
        val navBarLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val navBarDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
        val navigationBarStyle = if (darkTheme) {
            SystemBarStyle.dark(navBarDarkScrim)
        } else {
            SystemBarStyle.light(navBarLightScrim, navBarDarkScrim)
        }
        val statusBarStyle = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = statusBarStyle, navigationBarStyle = navigationBarStyle)
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

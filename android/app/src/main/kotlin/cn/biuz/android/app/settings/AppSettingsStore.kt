package cn.biuz.android.app.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用偏好持久化后端（iOS AppSettings UserDefaults 承接的抽象面）：
 * 生产实现在 :app（SharedPreferences，见 BiuzApplication），单测注入内存实现。
 * 仅存档位原词，不做任何业务推导（推导集中在 [AppSettingsStore] 纯逻辑面）。
 */
interface AppSettingsPersistence {
    fun loadDeveloperMode(): Boolean
    fun saveDeveloperMode(enabled: Boolean)
    fun loadLanguage(): String?
    fun saveLanguage(raw: String?)
    fun loadAppearance(): String?
    fun saveAppearance(raw: String?)
    /** G7 通用布尔键读写（缺省实现 = 键不支持，读恒默认、写静默——旧持久化实现免改）。 */
    fun loadBoolean(key: String, default: Boolean): Boolean = default
    fun saveBoolean(key: String, value: Boolean) {}
}

/**
 * 应用语言偏好（iOS AppLanguagePreference 直译，SettingsView.swift:989-1010）：
 * system = 清覆盖跟随系统；zh-Hans / en 固定档。raw 词表与 iOS AppleLanguages 首元素
 * 前缀判定同口径（zh 前缀→中文、en 前缀→English）。
 */
enum class AppLanguagePreference(val stored: String) {
    SYSTEM("system"),
    CHINESE("zh-Hans"),
    ENGLISH("en"),
    ;

    companion object {
        /** 存档词反解（未知词宽容回 system——存档残缺不崩）。 */
        fun fromStored(raw: String?): AppLanguagePreference = when (raw) {
            CHINESE.stored -> CHINESE
            ENGLISH.stored -> ENGLISH
            else -> SYSTEM
        }

        /** 系统首选语言码前缀 → 偏好档展示（iOS AppLanguagePreference.current() 同构）。 */
        fun fromSystemLanguage(language: String?): AppLanguagePreference = when {
            language == null -> SYSTEM
            language.startsWith("zh", ignoreCase = true) -> CHINESE
            language.startsWith("en", ignoreCase = true) -> ENGLISH
            else -> SYSTEM
        }
    }
}

/**
 * 外观偏好（iOS `AppearanceMode`：system/light/dark 同词表，`Theme.swift:138-147`）。
 * 生效面在 [cn.biuz.android.app.MainActivity] 的 BiuZTheme(darkTheme=…)；**缺省 light**
 * （2026-11-24 用户裁决：朱批默认浅色主题；跟随系统仍是可选项）。
 */
enum class AppAppearance(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** 存档词反解（未设置/未知词宽容回 light——存档残缺不崩）。 */
        fun fromStored(raw: String?): AppAppearance = when (raw) {
            SYSTEM.stored -> SYSTEM
            LIGHT.stored -> LIGHT
            DARK.stored -> DARK
            else -> LIGHT
        }
    }
}

/**
 * 品牌行连点 7 次闸门（iOS SettingsView.handleBrandTap :170-180 直译）：
 * 计数无超时窗（逐次点击均计数）、达到阈值即翻转并清零——经典隐藏开关契约。
 * 纯逻辑单测锁定（阈值 7、触发即清零、未达阈值不触发）。
 */
class BrandTapGate(private val threshold: Int = BRAND_TAP_THRESHOLD) {

    var count: Int = 0
        private set

    /** 记一次点按。返回 true = 达到阈值（应翻转开发者模式），计数已同步清零。 */
    fun tap(): Boolean {
        count += 1
        if (count >= threshold) {
            count = 0
            return true
        }
        return false
    }

    fun reset() {
        count = 0
    }

    companion object {
        /** iOS「连点 7 次」词面阈值（SettingsView 品牌行 12-brand-name 同数）。 */
        const val BRAND_TAP_THRESHOLD = 7
    }
}

/**
 * 应用设置装配（iOS AppSettingsModel 的最小承接面，本轮只落两条真实偏好：
 * 开发者模式 + 语言；其余外观/通知偏好随对应里程碑扩列）。
 *
 * 开发者模式有效口径（iOS AppSettingsModel.effectiveDeveloperMode 同构）：
 * **持久化开关 || -ZCodeDevMode 启动参数**。启动参数经构造参数注入（AppSession
 * .startupArguments 的 -ZCodeDevMode 词面；E2E/诊断进程用它冷启直开）。
 * 注：M2 壳期曾以 BuildConfig.DEBUG 等效调试开关——设置页 7 连击落位后撤出：
 * DEBUG 恒开会让「默认简洁态」在全部调试构建失守（iOS test16 简洁态回归门同型口径），
 * dev 构建需开发者模式时以连点 7 次或 -ZCodeDevMode 取得。
 */
class AppSettingsStore(
    private val persistence: AppSettingsPersistence,
    /** `-ZCodeDevMode` 启动参数在场判定（AppSession.startupArguments.contains 同值传入）。 */
    val developerModeStartupArgument: Boolean = false,
) {

    private val _developerMode = MutableStateFlow(persistence.loadDeveloperMode())

    /** 持久化开发者模式（设置页品牌行 7 连击 / 开发者分组 toggle 同一写入点）。 */
    val developerMode: StateFlow<Boolean>
        get() = _developerMode

    /** 有效开发者模式 = 持久化 || -ZCodeDevMode（手动连接族 gating 唯一判定源）。 */
    val effectiveDeveloperMode: Boolean
        get() = _developerMode.value || developerModeStartupArgument

    fun setDeveloperMode(enabled: Boolean) {
        _developerMode.value = enabled
        persistence.saveDeveloperMode(enabled)
    }

    // G7 消息流待办卡开关（web messageStreamShowTodos default(false) 同值同义；
    // 本机偏好——桌面侧同名设置走「桌面设置」面同步）
    private val _showTodosInStream =
        MutableStateFlow(persistence.loadBoolean("showTodosInStream", default = false))
    val showTodosInStream: StateFlow<Boolean>
        get() = _showTodosInStream

    fun setShowTodosInStream(enabled: Boolean) {
        _showTodosInStream.value = enabled
        persistence.saveBoolean("showTodosInStream", enabled)
    }

    private val _language = MutableStateFlow(AppLanguagePreference.fromStored(persistence.loadLanguage()))

    /** 应用语言偏好（语言页唯一写入点；生效面在 MainActivity attachBaseContext 注入）。 */
    val language: StateFlow<AppLanguagePreference>
        get() = _language

    fun setLanguage(preference: AppLanguagePreference) {
        _language.value = preference
        persistence.saveLanguage(preference.stored)
    }

    private val _appearance = MutableStateFlow(AppAppearance.fromStored(persistence.loadAppearance()))

    /** 外观偏好（外观页唯一写入点；即时生效 + 持久化）。 */
    val appearance: StateFlow<AppAppearance>
        get() = _appearance

    fun setAppearance(value: AppAppearance) {
        _appearance.value = value
        persistence.saveAppearance(value.stored)
    }
}

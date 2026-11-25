package cn.biuz.android.app

import android.app.Application
import android.webkit.WebView
import android.content.Context
import cn.biuz.android.app.settings.AppSettingsPersistence
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.ServerRegistryPersistence

/**
 * 应用壳：AppSession/AppSettings 应用级单例装配（iOS AppSession 挂 ZCodeMobileApp
 * 环境的对应物）。MainActivity/Compose 导航宿主经 `(application as BiuzApplication)`
 * 取用。
 *
 * 服务器注册表持久化以 SharedPreferences 承接（iOS Keychain 对应物；ServerConfig
 * 编解码复用连接层 toJson/fromJson，本文件不自备第二种编解码）。
 * 偏好（开发者模式/语言）以独立 prefs 文件承接（iOS AppSettings UserDefaults 对应物；
 * 存档键与 iOS UserDefaults 键名对齐：settings.developerMode / AppleLanguages 词面档位）。
 */
class BiuzApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 仅 debug：允许 DevTools 协议连 WebView（联调/自动化读 DOM 与截图用；release 不开）
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        // G50 会话草稿持久化（web composerDraftStore 同构；VM 无 Context，应用级装配）
        cn.biuz.android.app.ui.chat.ComposerDraftStore.init(this)
        // 批4 模型选择器「最近使用」（iOS composer.modelRecents.v1 同词面）
        cn.biuz.android.app.ui.chat.ModelRecentsStore.init(this)
        // G42 界面字号缩放（web uiFontSize.ts 同词面；BiuZFont getter 读乘数）
        cn.biuz.android.app.ui.theme.UiFontScale.init(this)
        // G18 prompt 历史（iOS cyclePromptHistory 同构——全局发送史 cap 50）
        cn.biuz.android.app.ui.chat.PromptHistoryStore.init(this)
    }

    val appSettings: AppSettingsStore by lazy {
        AppSettingsStore(
            persistence = SharedPreferencesAppSettingsPersistence(this),
            // -ZCodeDevMode 启动参数（iOS ProcessInfo.arguments 对应物）：Android 无
            // 进程级 argv 等价面（E2E 基建落位时经 intent extras → 本构造参数接线，
            // AppSession.startupArguments 同词面契约），当前正式路径恒 false =
            // 开发者模式唯一入口是设置页品牌行连点 7 次（§5-17 默认简洁态）。
            developerModeStartupArgument = false,
        )
    }

    val appSession: AppSession by lazy {
        AppSession(
            registryPersistence = SharedPreferencesServerRegistryPersistence(this),
            // OAuth 账户凭据（iOS Keychain 对应物）：Keystore 加密后落 SharedPreferences。
            // 与服务器注册表是两套独立凭据，退出登录不互相清除。
            oauthPersistence = KeystoreOAuthCredentialPersistence(this),
        )
    }
}

/** iOS AppSettings 的 UserDefaults 面 → SharedPreferences（键名对齐 iOS 同名词面）。 */
internal class SharedPreferencesAppSettingsPersistence(context: Application) :
    AppSettingsPersistence {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun loadDeveloperMode(): Boolean = prefs.getBoolean(KEY_DEVELOPER_MODE, false)

    override fun saveDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    override fun loadLanguage(): String? = prefs.getString(KEY_LANGUAGE, null)

    override fun saveLanguage(raw: String?) {
        prefs.edit().putString(KEY_LANGUAGE, raw).apply()
    }

    override fun loadAppearance(): String? = prefs.getString(KEY_APPEARANCE, null)

    override fun saveAppearance(raw: String?) {
        prefs.edit().putString(KEY_APPEARANCE, raw).apply()
    }

    private companion object {
        const val PREFS_NAME = "cn.biuz.android.settings"
        const val KEY_DEVELOPER_MODE = "settings.developerMode"
        const val KEY_LANGUAGE = "settings.language"
        const val KEY_APPEARANCE = "settings.appearance"
    }
}

/** iOS ServerRegistry 的 Keychain+UserDefaults 双面 → SharedPreferences 单源（无镜像自愈面）。 */
internal class SharedPreferencesServerRegistryPersistence(context: Application) :
    ServerRegistryPersistence {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadServers(): List<cn.biuz.android.connection.ServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val json = try {
            cn.biuz.android.protocol.JSONValue.parse(raw)
        } catch (_: cn.biuz.android.protocol.JsonParseException) {
            return emptyList()
        }
        return json.arrayValue?.mapNotNull { item ->
            cn.biuz.android.connection.ServerConfig.fromJson(item)
        } ?: emptyList()
    }

    override fun saveServers(servers: List<cn.biuz.android.connection.ServerConfig>) {
        val encoded = cn.biuz.android.protocol.JSONValue.Arr(servers.map { it.toJson() }).data
        prefs.edit().putString(KEY_SERVERS, encoded?.decodeToString()).apply()
    }

    override fun loadSelectedId(): String? = prefs.getString(KEY_SELECTED, null)

    override fun saveSelectedId(id: String?) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    private companion object {
        const val PREFS_NAME = "cn.biuz.android.serverRegistry"
        const val KEY_SERVERS = "server.configs.v1" // iOS KeychainStore.swift:167 同名键
        const val KEY_SELECTED = "server.selected.v1" // iOS KeychainStore.swift:168 同名键
    }
}

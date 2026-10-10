package cn.biuz.android.app

import android.app.Application
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
        )
    }
}

/** iOS AppSettings 的 UserDefaults 面 → SharedPreferences（键名对齐 iOS 同名词面）。 */
internal class SharedPreferencesAppSettingsPersistence(context: Application) :
    AppSettingsPersistence {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadDeveloperMode(): Boolean = prefs.getBoolean(KEY_DEVELOPER_MODE, false)

    override fun saveDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    override fun loadLanguage(): String? = prefs.getString(KEY_LANGUAGE, null)

    override fun saveLanguage(raw: String?) {
        prefs.edit().putString(KEY_LANGUAGE, raw).apply()
    }

    private companion object {
        const val PREFS_NAME = "cn.biuz.android.settings"
        const val KEY_DEVELOPER_MODE = "settings.developerMode"
        const val KEY_LANGUAGE = "settings.language"
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

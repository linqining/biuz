package cn.biuz.android.app

import android.app.Application
import android.content.Context
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.ServerRegistryPersistence

/**
 * 应用壳：AppSession 应用级单例装配（iOS AppSession 挂 ZCodeMobileApp 环境的对应物）。
 * MainActivity/Compose 导航宿主经 `(application as BiuzApplication).appSession` 取用。
 *
 * 服务器注册表持久化以 SharedPreferences 承接（iOS Keychain 对应物；ServerConfig
 * 编解码复用连接层 toJson/fromJson，本文件不自备第二种编解码）。
 */
class BiuzApplication : Application() {

    val appSession: AppSession by lazy {
        AppSession(
            registryPersistence = SharedPreferencesServerRegistryPersistence(this),
        )
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

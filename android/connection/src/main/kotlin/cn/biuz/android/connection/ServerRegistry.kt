package cn.biuz.android.connection

// 直译自 ios/ZCodeMobileApp/Sources/Services/Keychain/KeychainStore.swift 的
// ServerRegistry 段（:166-232「服务器注册表（最近连接 + Keychain 令牌）」）。
// 平台适配（逐处注明）：
// - iOS Keychain（server.configs.v1）+ UserDefaults（selected/mirror）→ 可插拔持久化接口
//   [ServerRegistryPersistence]：:app 以 SharedPreferences 承接（AppSession 装配注入），
//   纯 JVM 测试用 [InMemoryServerRegistryPersistence]；
// - iOS 的 UserDefaults 镜像自愈面（syncMirror，模拟器重装清 Keychain 的平台缺陷对策）
//   不移植——Android SharedPreferences 单源即持久，无对应缺陷；
// - ServerConfig 的 JSON 编解码复用其 toJson/fromJson（键序 = 属性声明序；encodeIfPresent
//   缺键省略口径），本文件不自备第二种编解码。

/**
 * 服务器注册表持久化后端（iOS Keychain+UserDefaults 双面的抽象）。
 * 实现方自行保证线程安全（:app 的 SharedPreferences 实现天然进程内安全）。
 */
interface ServerRegistryPersistence {
    fun loadServers(): List<ServerConfig>

    fun saveServers(servers: List<ServerConfig>)

    fun loadSelectedId(): String?

    fun saveSelectedId(id: String?)
}

/** 纯内存后端（纯 JVM 单测/无持久化场景缺省；服务器清单不跨进程）。 */
class InMemoryServerRegistryPersistence : ServerRegistryPersistence {
    private val lock = Any()
    private var servers: List<ServerConfig> = emptyList()
    private var selectedId: String? = null

    override fun loadServers(): List<ServerConfig> = synchronized(lock) { servers }

    override fun saveServers(servers: List<ServerConfig>) {
        synchronized(lock) { this.servers = servers }
    }

    override fun loadSelectedId(): String? = synchronized(lock) { selectedId }

    override fun saveSelectedId(id: String?) {
        synchronized(lock) { this.selectedId = id }
    }
}

/**
 * 服务器注册表（最近连接优先：新服务器插入队首；selectedServerID 记忆最近连接台）。
 * 语义与 iOS ServerRegistry 一比一（servers / upsert / remove / selectedServerID /
 * selectedServer）。
 */
class ServerRegistry(private val persistence: ServerRegistryPersistence) {

    val servers: List<ServerConfig>
        get() = persistence.loadServers()

    /** 已在册按 id 原位更新；新服务器插入队首（iOS `all.insert(config, at: 0)` 同口径）。 */
    fun upsert(config: ServerConfig) {
        val all = servers.toMutableList()
        val index = all.indexOfFirst { it.id == config.id }
        if (index >= 0) all[index] = config else all.add(0, config)
        persistence.saveServers(all)
    }

    fun remove(id: String) {
        persistence.saveServers(servers.filter { it.id != id })
        if (selectedServerId == id) {
            persistence.saveSelectedId(null)
        }
    }

    var selectedServerId: String?
        get() = persistence.loadSelectedId()
        set(value) = persistence.saveSelectedId(value)

    val selectedServer: ServerConfig?
        get() = selectedServerId?.let { id -> servers.firstOrNull { it.id == id } }

    /** E2E 复位（iOS performE2EStateResetIfNeeded 的 -ZCodeE2EResetState 分支直译）。 */
    fun clear() {
        persistence.saveServers(emptyList())
        persistence.saveSelectedId(null)
    }
}

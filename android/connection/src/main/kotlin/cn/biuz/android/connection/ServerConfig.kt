package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.relay.RelayLinkConfig

// 直译自 ios/ZCodeMobileApp/Sources/Services/Keychain/KeychainStore.swift 的 ServerConfig
// （:85-122）。机械适配（逐处注明）：
// - Swift Codable → 手写 JSON 编解码（JSONValue 承载，编解码键序 = 属性声明序）；
//   iOS 合成 Codable 对 Optional 键 encodeIfPresent（nil 键不写出）、解码显式 null 与
//   缺键同视——Kotlin 侧 toJson 跳过 nil 键、fromJson 经 `dict[key]?.stringValue`
//   天然同口径（缺键/JSONValue.Null 均得 null）；
// - Swift `Date?` → Long?（epoch 毫秒，Models.kt 文件头约定）；
// - Keychain 持久化面（服务器注册表 ServerRegistry）归后续装配里程碑，本文件只承载
//   连接层凭据模型与 JSON 往返（旧 Keychain 数据向后兼容解码的验收面）。
// - `String(localized:)` → 同串字面量（同 Models.kt 文件头约定）。

/**
 * 服务器配置（连接层凭据 = 服务器密码；iOS 仅存 Keychain，Android 对应
 * Keystore/EncryptedSharedPreferences——装配层承接）。
 * relay 非空 = 云中继模式（wss://…/ws?mid=…，host/port 仅作展示占位）。
 */
data class ServerConfig(
    val id: String,
    val name: String?,
    /** "192.168.1.24" 或完整 origin */
    val host: String,
    val port: Int,
    val useTLS: Boolean,
    /** 空字符串 = --no-token 免鉴权 */
    val token: String,
    /** epoch 毫秒（Swift Date? → Long?） */
    val lastConnectedAt: Long? = null,
    val preferredWorkspacePath: String? = null,
    /**
     * 云中继配置（remote/v4 配对链接解析产物；null = 局域网直连）。
     * passHash 属连接层凭据，随 ServerConfig 整体仅存 Keychain/Keystore。
     */
    val relay: RelayLinkConfig? = null,
) {
    val baseURL: String
        get() = "${if (useTLS) "https" else "http"}://$host:$port"

    val wsBaseURL: String
        get() = "${if (useTLS) "wss" else "ws"}://$host:$port"

    val displayAddress: String
        get() {
            relay?.let { return it.endpointHost ?: "云中继" }
            return "$host:$port"
        }

    /** 展示名（中继优先机器名；连接卡片/横幅共用） */
    val displayName: String
        get() = name ?: relay?.machineName ?: "我的桌面端"

    /** Swift `encode(to:)` 对应物：键序 = 属性声明序；nil 键省略（encodeIfPresent 口径）。 */
    fun toJson(): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["id"] = JSONValue.Str(id)
        name?.let { fields["name"] = JSONValue.Str(it) }
        fields["host"] = JSONValue.Str(host)
        fields["port"] = JSONValue.Int(port)
        fields["useTLS"] = JSONValue.Bool(useTLS)
        fields["token"] = JSONValue.Str(token)
        lastConnectedAt?.let { fields["lastConnectedAt"] = JSONValue.fromAny(it) ?: JSONValue.Null }
        preferredWorkspacePath?.let { fields["preferredWorkspacePath"] = JSONValue.Str(it) }
        relay?.let { fields["relay"] = relayToJson(it) }
        return JSONValue.Obj(fields)
    }

    companion object {
        /**
         * Swift `init(from:)` 对应物：必需标量缺失返回 null（调用方按解码失败口径处理）；
         * lastConnectedAt 毫秒值越 Int32 时线缆为整值 double，经 doubleValue 统一读取
         * （JSONValue.intValue/doubleValue 宽容读双形态）。
         */
        fun fromJson(json: JSONValue): ServerConfig? {
            val dict = json.objectValue ?: return null
            val id = dict["id"]?.stringValue ?: return null
            val host = dict["host"]?.stringValue ?: return null
            val port = dict["port"]?.intValue ?: return null
            val useTLS = dict["useTLS"]?.boolValue ?: return null
            val token = dict["token"]?.stringValue ?: return null
            return ServerConfig(
                id = id,
                name = dict["name"]?.stringValue,
                host = host,
                port = port,
                useTLS = useTLS,
                token = token,
                lastConnectedAt = dict["lastConnectedAt"]?.doubleValue?.toLong(),
                preferredWorkspacePath = dict["preferredWorkspacePath"]?.stringValue,
                relay = dict["relay"]?.objectValue?.let { relayFromJson(it) },
            )
        }

        private fun relayToJson(link: RelayLinkConfig): JSONValue {
            val fields = LinkedHashMap<String, JSONValue>()
            fields["wssURL"] = JSONValue.Str(link.wssURL)
            link.machineName?.let { fields["machineName"] = JSONValue.Str(it) }
            fields["deviceSid"] = JSONValue.Str(link.deviceSid)
            fields["passHash"] = JSONValue.Str(link.passHash)
            link.desktopAppVersion?.let { fields["desktopAppVersion"] = JSONValue.Str(it) }
            return JSONValue.Obj(fields)
        }

        private fun relayFromJson(dict: Map<String, JSONValue>): RelayLinkConfig? {
            val wssURL = dict["wssURL"]?.stringValue ?: return null
            val deviceSid = dict["deviceSid"]?.stringValue ?: return null
            val passHash = dict["passHash"]?.stringValue ?: return null
            return RelayLinkConfig(
                wssURL = wssURL,
                machineName = dict["machineName"]?.stringValue,
                deviceSid = deviceSid,
                passHash = passHash,
                desktopAppVersion = dict["desktopAppVersion"]?.stringValue,
            )
        }
    }
}

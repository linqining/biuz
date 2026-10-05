import Foundation
import Security

// MARK: - Keychain 基础库（账户层 tokenSet 与连接层服务器令牌均仅存 Keychain，9.5）

enum KeychainStore {

    enum KeychainError: Error {
        case failure(OSStatus)
    }

    /// 与 bundle id（cn.biuz.mobile）对齐的 Keychain service 标识。
    /// 切换前的旧条目（service=cn.zcode.mobile）对新标识不可见——本机需重新 OAuth 登录与
    /// 重新配对桌面端；属品牌切换的预期一次性成本（工程未上架、无外部存量用户）。
    /// E2E 不受影响：每用例以 -ZCodeE2EResetState 清空凭据态，不依赖存量 Keychain。
    private static let service = "cn.biuz.mobile"

    static func save(_ data: Data, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery.merge(attributes) { _, new in new }
            status = SecItemAdd(addQuery as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            throw KeychainError.failure(status)
        }
    }

    static func load(account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            return result as? Data
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError.failure(status)
        }
    }

    static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: Codable 便捷层

    static func saveCodable<T: Encodable>(_ value: T, account: String) throws {
        let data = try JSONEncoder().encode(value)
        try save(data, account: account)
    }

    static func loadCodable<T: Decodable>(_ type: T.Type, account: String) throws -> T? {
        guard let data = try load(account: account) else { return nil }
        return try JSONDecoder().decode(T.self, from: data)
    }
}

// MARK: - 账户层凭据（tokenSet + userInfo，对应 browserOAuthCredentialRepo.ts 的 Keychain 化）

/// 服务器配置（连接层凭据 = 服务器密码，仅存 Keychain；9.5）
/// relay 非空 = 云中继模式（wss://…/ws?mid=…，host/port 仅作展示占位）
struct ServerConfig: Codable, Equatable, Identifiable {
    var id: String
    var name: String?
    var host: String       // "192.168.1.24" 或完整 origin
    var port: Int
    var useTLS: Bool
    var token: String      // 空字符串 = --no-token 免鉴权
    var lastConnectedAt: Date?
    var preferredWorkspacePath: String?
    /// 云中继配置（remote/v4 配对链接解析产物；nil = 局域网直连）。
    /// passHash 属连接层凭据，随 ServerConfig 整体仅存 Keychain。
    var relay: RelayLinkConfig?

    var baseURL: String {
        "\(useTLS ? "https" : "http")://\(host):\(port)"
    }

    var wsBaseURL: String {
        "\(useTLS ? "wss" : "ws")://\(host):\(port)"
    }

    var displayAddress: String {
        if let relay {
            return relay.endpointHost ?? "云中继"
        }
        return "\(host):\(port)"
    }

    /// 展示名（中继优先机器名；连接卡片/横幅共用）
    var displayName: String {
        name ?? relay?.machineName ?? "我的桌面端"
    }
}

enum OAuthCredentialStore {
    private static let tokenSetKey = "oauth.tokenSet.v1"
    private static let userInfoKey = "oauth.userInfo.v1"

    static func save(tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo) throws {
        do {
            try KeychainStore.saveCodable(tokenSet, account: tokenSetKey)
            try KeychainStore.saveCodable(userInfo, account: userInfoKey)
        } catch {
            throw OAuthError.keychainFailed
        }
    }

    static func load() -> (tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo)? {
        guard let tokenSet = try? KeychainStore.loadCodable(OAuthTokenSet.self, account: tokenSetKey),
              let userInfo = try? KeychainStore.loadCodable(OAuthUserInfo.self, account: userInfoKey) else {
            return nil
        }
        return (tokenSet, userInfo)
    }

    static var tokenSet: OAuthTokenSet? {
        try? KeychainStore.loadCodable(OAuthTokenSet.self, account: tokenSetKey)
    }

    static func clear() {
        KeychainStore.delete(account: tokenSetKey)
        KeychainStore.delete(account: userInfoKey)
    }

    /// 掩码展示（末 4 位，9.5）
    static func mask(_ secret: String) -> String {
        guard secret.count > 4 else { return "••••" }
        return "••••••••" + String(secret.suffix(4))
    }

    /// 是否已过期（9.5 首发口径：过期即要求重新登录，不做静默续期）
    static func isExpired(_ tokenSet: OAuthTokenSet) -> Bool {
        guard let expiresAt = tokenSet.expiresAt else { return false }
        return Date() >= expiresAt
    }
}

// MARK: - 服务器注册表（最近连接 + Keychain 令牌）

enum ServerRegistry {
    private static let serversKey = "server.configs.v1"
    private static let selectedKey = "server.selected.v1"

    private static let mirrorKey = "server.configs.mirror.v1"

    static var servers: [ServerConfig] {
        if let stored = (try? KeychainStore.loadCodable([ServerConfig].self, account: serversKey)) ?? nil {
            return stored
        }
        // 自愈：部分模拟器运行时上重装会清空应用 Keychain（真机更新无此问题）。
        // Keychain 读空但 UserDefaults 镜像存在 → 回填 Keychain 并沿用（镜像内容与
        // Keychain 同源，由 upsert/remove 同步维护）。
        if let mirrored = UserDefaults.standard.data(forKey: mirrorKey),
           let list = try? JSONDecoder().decode([ServerConfig].self, from: mirrored), !list.isEmpty {
            try? KeychainStore.saveCodable(list, account: serversKey)
            return list
        }
        return []
    }

    static func upsert(_ config: ServerConfig) {
        var all = servers
        if let index = all.firstIndex(where: { $0.id == config.id }) {
            all[index] = config
        } else {
            all.insert(config, at: 0)
        }
        try? KeychainStore.saveCodable(all, account: serversKey)
        syncMirror(all)
    }

    static func remove(id: String) {
        let remaining = servers.filter { $0.id != id }
        try? KeychainStore.saveCodable(remaining, account: serversKey)
        syncMirror(remaining)
        if selectedServerID == id {
            UserDefaults.standard.removeObject(forKey: selectedKey)
        }
    }

    /// UserDefaults 镜像（模拟器重装清 Keychain 的自愈源；真机不依赖）
    private static func syncMirror(_ list: [ServerConfig]) {
        if let data = try? JSONEncoder().encode(list) {
            UserDefaults.standard.set(data, forKey: mirrorKey)
        }
    }

    static func clearMirror() {
        UserDefaults.standard.removeObject(forKey: mirrorKey)
    }

    static var selectedServerID: String? {
        get { UserDefaults.standard.string(forKey: selectedKey) }
        set { UserDefaults.standard.set(newValue, forKey: selectedKey) }
    }

    static var selectedServer: ServerConfig? {
        guard let id = selectedServerID else { return nil }
        return servers.first { $0.id == id }
    }
}

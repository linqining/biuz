import Foundation
import Security

// MARK: - Keychain 基础库（账户层 tokenSet 与连接层服务器令牌均仅存 Keychain，9.5）
//
// 读取语义（2026-11-19，web v3.18.2 凭证安全对齐）：`read(account:)` 返回
// `CredentialReadOutcome` 而不是 Data?/throw 二态——「没有 / 不可用 / 确坏」三分，
// 由上层按 `CredentialReadPolicy` 做「降级还是重新登录」的明确决策。
// 权威源：`packages/shared/src/credential-security/systemCredentialCipher.ts:61-75`
// （密文损坏与系统密钥不可用必须是两个错误码，否则恢复逻辑会误删登录）。

enum KeychainStore {

    enum KeychainError: Error {
        case failure(OSStatus)
        /// 语义化失败（携带「不可用 / 需授权 / 损坏」原因，供调用方决定降级或重新登录）
        case semantic(CredentialFailureReason)
    }

    /// 最近一次读取失败的语义原因（可观测面：设置页/诊断读它上屏，而不是显示成「未登录」）。
    /// 只存语义枚举，不存 OSStatus 之外的任何凭据材料。
    private(set) static var lastFailure: CredentialFailureReason?

    private static func note(_ outcome: CredentialReadOutcome) -> CredentialReadOutcome {
        switch outcome {
        case .value:
            lastFailure = nil
        case .absent:
            lastFailure = nil
        case let .failed(reason):
            lastFailure = reason
        }
        return outcome
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
        switch read(account: account) {
        case let .value(payload):
            return payload
        case .absent:
            return nil
        case let .failed(reason):
            // 旧调用点保持「抛错」语义，但错误里带着**语义原因**（上层可据此判断是否可重试）
            throw KeychainError.semantic(reason)
        }
    }

    /// 语义化读取（推荐入口）：`errSecSuccess` → 值；`errSecItemNotFound` → 没有；
    /// 其余一切 → 失败，并按 `CredentialReadPolicy` 归类（绝不把「不可用」当成「损坏」）。
    static func read(account: String) -> CredentialReadOutcome {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        let payload = (status == errSecSuccess) ? (result as? Data) : nil
        return note(CredentialReadPolicy.classify(status: status, payload: payload))
    }

    /// 语义化 Codable 读取：解码失败按 web `open()` 认证失败同档 → corrupt（唯一可清理档）。
    /// 返回原始存储字节；调用方仍需自行 `JSONDecoder().decode`（一次读取不重复解两次）。
    static func readCodable<T: Decodable>(_ type: T.Type, account: String) -> CredentialReadOutcome {
        switch read(account: account) {
        case let .value(payload):
            do {
                _ = try JSONDecoder().decode(T.self, from: payload)
                return .value(payload)
            } catch {
                return note(CredentialReadPolicy.classifyDecodingFailure())
            }
        case .absent:
            return .absent
        case let .failed(reason):
            return .failed(reason)
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
        switch readCodable(type, account: account) {
        case let .value(payload):
            return try JSONDecoder().decode(T.self, from: payload)
        case .absent:
            return nil
        case let .failed(reason):
            throw KeychainError.semantic(reason)
        }
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

    /// 兼容入口（旧调用点语义不变：成功/没有 → 值，失败 → nil）。
    /// **新代码请用 `readCredentials()`**——它会区分「没有 / 不可用 / 损坏」，
    /// 避免把「钥匙串锁定」显示成「未登录」并触发误清理。
    static func load() -> (tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo)? {
        switch readCredentials() {
        case let .value(readResult):
            return (tokenSet: readResult.tokenSet, userInfo: readResult.userInfo)
        case .absent, .failed:
            return nil
        }
    }

    /// 语义化读取（web `loadSessionSnapshot` 的三分同构）：
    /// - `.absent`：本机没有登录凭据 → 登录页
    /// - `.failed(.unavailable/.authFailed)`：**保留** Keychain 数据 + 暴露原因，可重试
    /// - `.failed(.corrupt)`：能读到但解不开 → 唯一允许清理的分支（web `isCredentialDecryptError`）
    static func readCredentials() -> OAuthCredentialReadOutcome {
        let tokenSetOutcome = KeychainStore.readCodable(OAuthTokenSet.self, account: tokenSetKey)
        let userInfoOutcome = KeychainStore.readCodable(OAuthUserInfo.self, account: userInfoKey)
        // 任一侧失败优先按「不可用 > 损坏」排序上抛（web：密钥不可用不得冒充损坏）
        for outcome in [tokenSetOutcome, userInfoOutcome] {
            if case let .failed(reason) = outcome {
                return .failed(reason)
            }
        }
        guard let tokenSetData = tokenSetOutcome.data, let userInfoData = userInfoOutcome.data else {
            return .absent
        }
        do {
            return .value(
                tokenSet: try JSONDecoder().decode(OAuthTokenSet.self, from: tokenSetData),
                userInfo: try JSONDecoder().decode(OAuthUserInfo.self, from: userInfoData)
            )
        } catch {
            // 解不开 = 确坏（web `open()` 认证失败同档）
            return .failed(.corrupt)
        }
    }

    /// 只有 corrupt 时才允许清理（web 清理白名单）；其他失败一律保留数据。
    @discardableResult
    static func purgeIfRecoverable(_ reason: CredentialFailureReason) -> Bool {
        guard CredentialReadPolicy.canPurge(reason) else { return false }
        clear()
        return true
    }

    static var tokenSet: OAuthTokenSet? {
        switch KeychainStore.readCodable(OAuthTokenSet.self, account: tokenSetKey) {
        case let .value(payload):
            return try? JSONDecoder().decode(OAuthTokenSet.self, from: payload)
        case .absent, .failed:
            return nil
        }
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
        // 2026-11-19：读失败先按语义分类（`systemCredentialCipher.ts:67-75` 同纪律）——
        // 「钥匙串不可用/需授权」必须与「里面真的没有」区分开：前者**保留**数据、
        // 只把原因暴露给诊断面；只有真正读到值才用值，读到空才走镜像自愈。
        switch KeychainStore.readCodable([ServerConfig].self, account: serversKey) {
        case let .value(payload):
            if let list = try? JSONDecoder().decode([ServerConfig].self, from: payload) {
                lastReadFailure = nil
                return list
            }
            // 能读到但解不开：结构非法（确坏档），记录原因后照常走镜像降级，不清任何数据
            lastReadFailure = .corrupt
            return mirroredServers()
        case .absent:
            lastReadFailure = nil
            // 自愈：部分模拟器运行时上重装会清空应用 Keychain（真机更新无此问题）。
            // Keychain 读空但 UserDefaults 镜像存在 → 回填 Keychain 并沿用（镜像内容与
            // Keychain 同源，由 upsert/remove 同步维护）。
            return mirroredServers()
        case let .failed(reason):
            lastReadFailure = reason
            // 保留数据：能读到镜像就用镜像顶住，读不到就返回空但**不清理**任何东西
            return mirroredServers()
        }
    }

    /// 最近一次注册表读取失败原因（诊断/设置页可读；nil = 正常）。
    private(set) static var lastReadFailure: CredentialFailureReason?

    /// UserDefaults 镜像（读不到 Keychain 时的降级读面，与写面同源维护）。
    private static func mirroredServers() -> [ServerConfig] {
        guard let mirrored = UserDefaults.standard.data(forKey: mirrorKey),
              let list = try? JSONDecoder().decode([ServerConfig].self, from: mirrored) else {
            return []
        }
        return list
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

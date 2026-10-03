import Foundation

// MARK: - OAuth 模型（packages/web/src/auth/zaiWebOAuthProvider.ts 对应）

enum OAuthProviderID: String, CaseIterable, Identifiable {
    case zai
    case bigmodel
    var id: String { rawValue }

    /// 渠道徽章文案（O3-A：ZAI → .pill done）
    var label: String {
        switch self {
        case .zai: return "ZAI · chat.z.ai"
        case .bigmodel: return "BigModel"
        }
    }
}

/// tokenSet：accessToken（Provider access_token）/ zcodeJwtToken / expiresAt（9.5）
struct OAuthTokenSet: Codable, Equatable {
    var accessToken: String
    var zcodeJwtToken: String
    var expiresAt: Date?
}

/// userInfo：toUserInfo 兼容 user.id/user_id、name/email、avatar/avatarUrl 与 data:image base64
struct OAuthUserInfo: Codable, Equatable {
    var id: String
    var username: String
    var displayName: String
    var avatarUrl: String?
}

/// OAuth 错误五态（O3-B 对照：取消 / error 参数 / state / 交换 / Keychain）
enum OAuthError: Error, Equatable {
    case userCancelled            // 用户取消（Sheet ✕ / 下拉 / 页内取消）
    case serverError(String)      // 授权服务器返回 error 参数
    case stateMismatch            // state 缺失或不匹配
    case exchangeFailed(String)   // 交换失败（超时 / HTTP 非 200 / code≠0）
    case keychainFailed           // Keychain 写入失败

    var title: String {
        switch self {
        case .userCancelled: return "你取消了本次授权"
        case .serverError: return "授权服务器返回错误"
        case .stateMismatch: return "授权回调校验失败"
        case .exchangeFailed: return "令牌交换失败"
        case .keychainFailed: return "凭据写入失败"
        }
    }
}

/// 回调参数（parseCallbackParams：code 或 authCode 双兼容；state 必填）
struct OAuthCallbackParams {
    var code: String?
    var error: String?
    var state: String
}

// MARK: - 配置（webZaiOAuthConfig.ts 对应 + 移动端绑定假设）

/// 端点配置。origin 可经启动参数覆盖（E2E/联调指向本地替身）：
/// `-ZCodeOAuthZaiOrigin http://127.0.0.1:8787` / `-ZCodeOAuthTokenOrigin …` / `-ZCodeOAuthBigModelOrigin …`
struct OAuthConfig {
    /// ZAI 授权入口 origin，缺省 https://chat.z.ai（webZaiOAuthConfig.ts:26-30）
    var zaiOrigin: String
    /// ZCode 端点 origin：tokenUrl "/api/v1/oauth/token" 为相对路径，挂在 web 端部署 origin
    /// （webZaiOAuthConfig.ts:56 + zcodeEndpoint.ts:1 DEFAULT_ZCODE_ENDPOINT_ORIGIN）
    var tokenOrigin: String
    /// BigModel 授权入口 origin，缺省 https://bigmodel.cn（webZaiOAuthConfig.ts:40-43）
    var bigModelOrigin: String
    /// client_id：web 端取 VITE_ZAI_OAUTH_CLIENT_ID，缺省样例值（webZaiOAuthConfig.ts:58）
    var clientID: String
    /// BigModel appId，缺省 "zcode"（webZaiOAuthConfig.ts:61）
    var bigModelAppID: String
    /// 移动端回调（绑定假设 9.7.7：web 用 webShareCallbackUrl；移动端自定义 scheme）
    var redirectURI: String

    /// 移动端缺省配置；启动参数覆盖用于本地替身测试
    static func resolve(launchArguments: [String] = ProcessInfo.processInfo.arguments) -> OAuthConfig {
        func arg(_ name: String) -> String? {
            guard let index = launchArguments.firstIndex(of: "-\(name)") else { return nil }
            guard launchArguments.count > index + 1 else { return nil }
            return launchArguments[index + 1]
        }
        let zaiOrigin = arg("ZCodeOAuthZaiOrigin") ?? "https://chat.z.ai"
        let tokenOrigin = arg("ZCodeOAuthTokenOrigin") ?? "https://zcode.z.ai"
        let bigModelOrigin = arg("ZCodeOAuthBigModelOrigin") ?? "https://bigmodel.cn"
        let clientID = arg("ZCodeOAuthClientID") ?? "client_P8X5CMWmlaRO9gyO-KSqtg"
        let bigModelAppID = arg("ZCodeOAuthBigModelAppID") ?? "zcode"
        // 服务端按 client_id 校验 redirect_uri：复用 web 客户端注册的回调地址
        // （zcodeEndpoint.ts:265 webShareCallbackUrl）；本地替身测试经启动参数覆盖。
        let redirect = arg("ZCodeOAuthRedirectURI") ?? "https://zcode.z.ai/cn/share/callback"
        return OAuthConfig(
            zaiOrigin: zaiOrigin, tokenOrigin: tokenOrigin, bigModelOrigin: bigModelOrigin,
            clientID: clientID, bigModelAppID: bigModelAppID, redirectURI: redirect)
    }
}

// MARK: - state 编解码（oauthStateCodec.ts 移植：base64url(JSON{nonce,...})）

/// 服务端令牌交换会解析并校验 state 的结构（裸随机串 → `{"detail":"invalid state"}`），
/// 因此必须与 web 参考实现同构：base64url(JSON)，nonce 必填，app_return_to/return_to 可选。
enum OAuthStateCodec {
    /// 构造授权 state（对应 buildOAuthState；载荷只含必填的 nonce，可选字段不发送）。
    /// 编码与 web 逐字节一致：base64url、去 padding；URL 转义交给 URLComponents。
    static func buildState(nonce: String) -> String {
        guard let json = try? JSONSerialization.data(withJSONObject: ["nonce": nonce],
                                                     options: [.sortedKeys]) else {
            return nonce
        }
        return json.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// 解析回调 state 并取回 nonce（对应 parseOAuthState；解析失败返回 nil）
    static func nonce(in state: String) -> String? {
        guard let percentDecoded = state.removingPercentEncoding else { return nil }
        var base64 = percentDecoded
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        guard let json = Data(base64Encoded: base64),
              let dict = (try? JSONSerialization.jsonObject(with: json)) as? [String: Any],
              let nonce = dict["nonce"] as? String, !nonce.isEmpty else {
            return nil
        }
        return nonce
    }
}

// MARK: - Provider（zaiWebOAuthProvider.ts 移植）

struct ZaiOAuthProvider {

    private let config: OAuthConfig
    private let urlSession: URLSession

    init(config: OAuthConfig = .resolve(), urlSession: URLSession = .shared) {
        self.config = config
        self.urlSession = urlSession
    }

    // MARK: ① 发起授权（buildAuthorizeUrl 分支）

    /// 令牌端点 host（供调用方过滤授权 WebView 会话 cookie）
    var tokenOriginHost: String {
        URL(string: config.tokenOrigin)?.host ?? config.tokenOrigin
    }

    /// ZAI：{origin}/api/oauth/authorize?redirect_uri=&response_type=code&client_id=&state=
    /// BigModel：{origin}/login?redirect=&appId=&state=（参数名 redirect ≠ redirect_uri）
    func buildAuthorizeURL(provider: OAuthProviderID, state: String) -> URL? {
        var components: URLComponents
        var query: [URLQueryItem]
        switch provider {
        case .zai:
            guard let url = URL(string: config.zaiOrigin) else { return nil }
            components = URLComponents(url: URL(string: url.appendingPathComponent("api/oauth/authorize").absoluteString)!, resolvingAgainstBaseURL: false)!
            query = [
                URLQueryItem(name: "redirect_uri", value: config.redirectURI),
                URLQueryItem(name: "response_type", value: "code"),
                URLQueryItem(name: "client_id", value: config.clientID),
                URLQueryItem(name: "state", value: state),
            ]
        case .bigmodel:
            guard let url = URL(string: config.bigModelOrigin) else { return nil }
            components = URLComponents(url: URL(string: url.appendingPathComponent("login").absoluteString)!, resolvingAgainstBaseURL: false)!
            query = [
                URLQueryItem(name: "redirect", value: config.redirectURI),
                URLQueryItem(name: "appId", value: config.bigModelAppID),
                URLQueryItem(name: "state", value: state),
            ]
        }
        components.queryItems = query
        return components.url
    }

    // MARK: ③ 回调解析（parseCallbackParams：state 必填，缺失即报错）

    func parseCallbackParams(from url: URL) -> Result<OAuthCallbackParams, OAuthError> {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return .failure(.stateMismatch)
        }
        let query = components.queryItems ?? []
        func value(_ name: String) -> String? {
            query.first { $0.name == name }?.value
        }
        guard let state = value("state")?.trimmingCharacters(in: .whitespaces), !state.isEmpty else {
            return .failure(.stateMismatch) // state 必填，缺失即报错
        }
        var params = OAuthCallbackParams(code: nil, error: nil, state: state)
        // code / authCode 双兼容
        params.code = value("code") ?? value("authCode")
        params.error = value("error")
        return .success(params)
    }

    /// 回调 URL 识别（拦截判定）：
    /// - 注册的 web 回调路径（/cn/share/callback、/share/callback，host 须与 redirectURI 一致）；
    /// - 兼容旧自定义 scheme（zcode://oauth/callback）。
    func isCallbackURL(_ url: URL) -> Bool {
        let path = url.path.lowercased()
        let isWebCallbackPath = path == "/cn/share/callback" || path == "/share/callback"
        if let redirect = URL(string: config.redirectURI),
           redirect.scheme?.lowercased() == "https" {
            return isWebCallbackPath
                && url.host?.lowercased() == redirect.host?.lowercased()
                && (url.scheme?.lowercased() == "https" || url.scheme?.lowercased() == "http")
        }
        guard let redirect = URL(string: config.redirectURI) else { return false }
        return url.scheme?.lowercased() == redirect.scheme?.lowercased()
            && url.host?.lowercased() == redirect.host?.lowercased()
            && url.path.hasPrefix(redirect.path.isEmpty ? "/" : redirect.path)
    }

    // MARK: ④ 交换令牌（exchangeToken + normalizeTokenResponse）

    func exchangeToken(provider: OAuthProviderID, code: String, state: String, cookies: [HTTPCookie] = []) async throws -> (OAuthTokenSet, OAuthUserInfo) {
        guard let endpoint = URL(string: config.tokenOrigin)?.appendingPathComponent("api/v1/oauth/token") else {
            throw OAuthError.exchangeFailed("无效的令牌端点")
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // web 参考实现在浏览器同源上下文交换（自动携带会话 cookie）；移动端把
        // 授权 WebView 会话中的 cookie 透传给交换请求，保持会话连续性。
        if !cookies.isEmpty {
            let fields = HTTPCookie.requestHeaderFields(with: cookies)
            for (key, value) in fields where key == "Cookie" {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }
        let payload: [String: String] = [
            "provider": provider.rawValue,
            "code": code,
            "redirect_uri": config.redirectURI,
            "state": state,
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.data(for: request)
        } catch {
            throw OAuthError.exchangeFailed("网络错误：\(error.localizedDescription)")
        }
        guard let http = response as? HTTPURLResponse else {
            throw OAuthError.exchangeFailed("无效响应")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw OAuthError.exchangeFailed("HTTP \(http.statusCode)")
        }

        return try normalizeTokenResponse(data: data, provider: provider)
    }

    /// normalizeTokenResponse（zaiWebOAuthProvider.ts:112-160）：code≠0 即失败；
    /// token 取 data.token；accessToken 按 provider 取 data.zai / data.bigmodel.access_token。
    private func normalizeTokenResponse(data: Data, provider: OAuthProviderID) throws -> (OAuthTokenSet, OAuthUserInfo) {
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: data),
              case .object(let dict) = json else {
            throw OAuthError.exchangeFailed("响应不是 JSON")
        }
        if let code = dict["code"]?.intValue, code != 0 {
            let msg = dict["msg"]?.stringValue ?? "OAuth token exchange failed"
            throw OAuthError.exchangeFailed(msg)
        }
        guard let dataDict = dict["data"]?.objectValue,
              let zcodeJwtToken = dataDict["token"]?.stringValue?.trimmingCharacters(in: .whitespaces),
              !zcodeJwtToken.isEmpty else {
            throw OAuthError.exchangeFailed("响应缺少 data.token")
        }
        let accessToken: String?
        switch provider {
        case .zai:
            accessToken = dataDict["zai"]?.objectValue?["access_token"]?.stringValue?
                .trimmingCharacters(in: .whitespaces)
        case .bigmodel:
            accessToken = dataDict["bigmodel"]?.objectValue?["access_token"]?.stringValue?
                .trimmingCharacters(in: .whitespaces)
        }
        guard let accessToken, !accessToken.isEmpty else {
            throw OAuthError.exchangeFailed("响应缺少 data.\(provider.rawValue).access_token")
        }

        let expiresAt: Date?
        if let expiresIn = dataDict["expires_in"]?.doubleValue, expiresIn.isFinite, expiresIn > 0 {
            expiresAt = Date().addingTimeInterval(expiresIn)
        } else {
            expiresAt = nil
        }

        // toUserInfo：主形态 user.id/username/displayName；回退形态 user_id/name/email/avatar
        var userInfo: OAuthUserInfo?
        if let user = dataDict["user"] {
            userInfo = Self.toUserInfo(user)
        }
        if userInfo == nil {
            userInfo = OAuthUserInfo(id: "unknown", username: "user", displayName: "User", avatarUrl: nil)
        }

        return (OAuthTokenSet(accessToken: accessToken, zcodeJwtToken: zcodeJwtToken, expiresAt: expiresAt), userInfo!)
    }

    /// toUserInfo 移植（zaiWebOAuthProvider.ts:68-102）
    static func toUserInfo(_ value: JSONValue) -> OAuthUserInfo? {
        guard case .object(let user) = value else { return nil }

        if let id = user["id"]?.stringValue,
           let username = user["username"]?.stringValue,
           let displayName = user["displayName"]?.stringValue {
            return OAuthUserInfo(
                id: id, username: username, displayName: displayName,
                avatarUrl: user["avatarUrl"]?.stringValue)
        }

        let id = user["user_id"]?.stringValue ?? "unknown"
        let name = user["name"]?.stringValue?.trimmingCharacters(in: .whitespaces) ?? ""
        let email = user["email"]?.stringValue?.trimmingCharacters(in: .whitespaces) ?? ""
        let username = name.isEmpty ? (email.isEmpty ? id : email) : name
        if name.isEmpty && email.isEmpty && id == "unknown" { return nil }
        return OAuthUserInfo(
            id: id, username: username, displayName: username,
            avatarUrl: normalizeBackendAvatarUrl(user["avatar"]))
    }

    /// normalizeBackendAvatarUrl 移植：data:image base64 / http(s) / 裸 base64 兜底
    static func normalizeBackendAvatarUrl(_ value: JSONValue?) -> String? {
        guard let raw = value?.stringValue else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return nil }
        if trimmed.lowercased().hasPrefix("data:image/") || trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://") {
            return trimmed
        }
        let base64Allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        if trimmed.count >= 16, trimmed.unicodeScalars.allSatisfy({ base64Allowed.contains($0) }) {
            return "data:image/png;base64,\(trimmed)"
        }
        return trimmed
    }
}

import Foundation

// MARK: - 连接链接解析（L1-K 完整链接自动拆解：scheme ∈ http/https/ws/wss，token 段剥离）

enum ConnectURLParser {

    struct Parsed: Equatable {
        var host: String
        var port: Int
        var useTLS: Bool
        var token: String?
    }

    enum ParseError: Error, Equatable {
        case missingScheme      // 缺 scheme
        case missingPort        // 缺端口
        case unrecognizable     // 无法识别

        var message: String {
            switch self {
            case .missingScheme, .unrecognizable:
                return "无法识别的地址：请粘贴完整链接或输入 host:port（缺 scheme/端口）"
            case .missingPort:
                return "无法识别的地址：缺少端口（桌面端默认 3030）"
            }
        }
    }

    // MARK: 云中继链接（remote/v4 配对链接；逆向结论⑦）

    /// 识别中继链接：`https://<host>/remote/v4?sid=…&hash=…&mid=…&name=…&app_version=…`
    /// → RelayLinkConfig{wssURL(`wss://<host>/ws?mid=…`), machineName}。
    /// - sid/hash 不进 WS URL（仅用于 auth 帧）；hash 为 URL 解码后的 base64 原字符串（HMAC 密钥）
    /// - wssURL 保留 mid 参数语义（桌面端 _Vn.connect 仅设置 mid；无 mid 时不带参数也可 101）
    static func parseRelayLink(_ input: String) -> RelayLinkConfig? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        for token in trimmed.split(whereSeparator: { $0.isWhitespace }) {
            if let link = parseRelayLinkToken(String(token)) { return link }
        }
        return parseRelayLinkToken(trimmed)
    }

    private static func parseRelayLinkToken(_ piece: String) -> RelayLinkConfig? {
        guard let components = URLComponents(string: piece),
              components.scheme?.lowercased() == "https",
              components.path.lowercased().hasPrefix("/remote/"),
              !piece.lowercased().contains("token=") else {
            return nil
        }
        let query = components.queryItems ?? []
        func value(_ name: String) -> String? {
            query.first { $0.name.lowercased() == name }?.value.flatMap { $0.isEmpty ? nil : $0 }
        }
        guard let sid = value("sid"), let hash = value("hash") else { return nil }
        let mid = value("mid")

        var wss = URLComponents()
        wss.scheme = "wss"
        wss.host = components.host
        wss.path = "/ws"
        if let mid {
            wss.queryItems = [URLQueryItem(name: "mid", value: mid)]
        }
        guard let wssURL = wss.url?.absoluteString else { return nil }
        return RelayLinkConfig(
            wssURL: wssURL,
            machineName: value("name"),
            deviceSid: sid,
            passHash: hash,
            desktopAppVersion: value("app_version"))
    }

    /// 剪贴板/输入框统一识别形态：中继链接优先，其次局域网直连链接
    enum ParsedLink: Equatable {
        case relay(RelayLinkConfig)
        case direct(Parsed)

        var isRelay: Bool {
            if case .relay = self { return true }
            return false
        }

        /// 横幅 meta 行（中继：机器名 · 云中继）
        var relaySummary: String {
            guard case .relay(let link) = self else { return "" }
            return "\(link.machineName ?? "桌面端") · 云中继 · \(link.endpointHost ?? "")"
        }

        var directHost: String {
            if case .direct(let parsed) = self { return parsed.host }
            return ""
        }

        var directPort: Int {
            if case .direct(let parsed) = self { return parsed.port }
            return 0
        }
    }

    static func extractLink(from text: String) -> ParsedLink? {
        if let relay = parseRelayLink(text) {
            return .relay(relay)
        }
        if let parsed = extractConnectLink(from: text) {
            return .direct(parsed)
        }
        return nil
    }

    /// 解析规则（设计稿 L1-K）：
    /// - 完整链接：scheme://host:port/?token=… → 地址取 host:port，token 段剥离
    /// - 仅 host:port：缺省补 http://（端口 3030 缺省）
    /// - scheme 支持 http/https/ws/wss（https/wss 走反向代理）
    static func parse(_ input: String) -> Result<Parsed, ParseError> {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .failure(.unrecognizable) }

        // 1) 尝试完整 URL（带 scheme）
        var candidate = trimmed
        if candidate.lowercased().hasPrefix("http://") || candidate.lowercased().hasPrefix("https://")
            || candidate.lowercased().hasPrefix("ws://") || candidate.lowercased().hasPrefix("wss://") {
            var useTLS = false
            var schemePart = ""
            if candidate.lowercased().hasPrefix("https://") {
                useTLS = true; schemePart = "https://"
            } else if candidate.lowercased().hasPrefix("wss://") {
                useTLS = true; schemePart = "wss://"
            } else if candidate.lowercased().hasPrefix("http://") {
                schemePart = "http://"
            } else {
                schemePart = "ws://"
            }
            candidate = String(candidate.dropFirst(schemePart.count))

            // 剥离 path/query；token 从 query 提取
            var token: String?
            if let queryIndex = candidate.firstIndex(of: "?") {
                let query = String(candidate[candidate.index(after: queryIndex)...])
                candidate = String(candidate[..<queryIndex])
                for pair in query.split(separator: "&") {
                    let kv = pair.split(separator: "=", maxSplits: 1)
                    if kv.count == 2, kv[0] == "token" {
                        token = String(kv[1]).removingPercentEncoding ?? String(kv[1])
                    }
                }
            }
            candidate = candidate.trimmingCharacters(in: CharacterSet(charactersIn: "/"))

            let hostPort = candidate.split(separator: ":", omittingEmptySubsequences: false)
            guard hostPort.count >= 1, !hostPort[0].isEmpty else { return .failure(.unrecognizable) }
            let host = String(hostPort[0])
            guard hostPort.count >= 2, let port = Int(hostPort[1]), port > 0, port < 65536 else {
                return .failure(.missingPort)
            }
            return .success(Parsed(host: host, port: port, useTLS: useTLS, token: token))
        }

        // 2) 仅 host:port → 补 http://；缺端口按校验态报错（L1-K 样例：「192.168.1.24」→ 缺端口）
        let hostPort = trimmed.split(separator: ":", omittingEmptySubsequences: false)
        if hostPort.count == 1, !hostPort[0].isEmpty {
            return .failure(.missingPort)
        }
        if hostPort.count >= 2, let port = Int(hostPort[1]), port > 0, port < 65536, !hostPort[0].isEmpty {
            return .success(Parsed(host: String(hostPort[0]), port: port, useTLS: false, token: nil))
        }
        return .failure(.unrecognizable)
    }

    /// 从剪贴板文本识别连接链接形态（http(s)://…/?token=…）
    static func extractConnectLink(from text: String) -> Parsed? {
        for token in text.split(whereSeparator: { $0.isWhitespace }) {
            let piece = String(token)
            if piece.lowercased().hasPrefix("http://") || piece.lowercased().hasPrefix("https://")
                || piece.lowercased().hasPrefix("ws://") || piece.lowercased().hasPrefix("wss://"),
               case .success(let parsed) = parse(piece),
               parsed.token != nil {
                return parsed
            }
        }
        if case .success(let parsed) = parse(text), parsed.token != nil {
            return parsed
        }
        return nil
    }
}

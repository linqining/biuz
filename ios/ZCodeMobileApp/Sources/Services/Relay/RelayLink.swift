import Foundation
import CryptoKit

// MARK: - 云中继链接配置（remote/v4 配对链接 → WS 端点 + 会话凭据）

/// 配对链接解析产物。
/// - V1（旧桌面）：`https://<host>/remote/v4?sid=…&hash=…&mid=…`，WS `wss://<host>/ws?mid=…`，
///   `auth_challenge → auth_response`（HMAC-SHA256(passHash)）。
/// - V2（3.16.2 起）：`https://<host>/remote/v4?sid=…&mid=…&name=…&app_version=…`（无 hash），
///   JWT 换一次性 token 后连 `wss://<host>/api/v1/remote/ws2?token=…`，`auth_init` 不带 role。
///   上游契约：docs/web-remote-control/relay-v2-pairing.md §HTTP/§WebSocket。
struct RelayLinkConfig: Codable, Equatable {
    /// wss 端点（V1：`/ws?mid=…`；V2：`/api/v1/remote/ws2`，token 在建连时追加）
    var wssURL: String
    /// 配对链接 name 参数（桌面机器名，UI 展示用）
    var machineName: String?
    /// 配对链接 sid 参数（桌面设备凭据，auth 帧 device_sid）
    var deviceSid: String
    /// V1 配对链接 hash（HMAC 密钥）；V2 无此字段
    var passHash: String?
    /// 配对链接 app_version 参数（桌面版本，诊断对照 bootstrap-response.desktopAppVersion）
    var desktopAppVersion: String?
    /// 1/nil = V1（兼容 3.16.2 之前桌面）；2 = V2（3.16.2 起）
    var protocolVersion: Int?
    /// V2 网关路由提示 mid（ws2?mid=…）
    var deviceMid: String?

    var isV2: Bool { protocolVersion == 2 }

    var endpointHost: String? {
        URLComponents(string: wssURL)?.host
    }

    /// V2 HTTP 面 origin（/api/v1/remote/token 挂在同一 host）
    var httpOrigin: String? {
        guard var components = URLComponents(string: wssURL) else { return nil }
        components.scheme = "https"
        components.path = ""
        components.query = nil
        return components.url?.absoluteString
    }
}

// MARK: - 关闭/失败原因（src-D3H6NV7w.js TN close code 表 + error.code 表）

/// 中继连接终态/中间态原因（人类可读口径进 L2/L3 日志）
enum RelayCloseReason: Equatable {
    case sessionNotFound          // WS 4004
    case sessionConflict          // WS 4009 / error KICKED
    case desktopDisconnected      // WS 4010
    case sessionExpired           // WS 4011
    case workspaceClosed          // WS 4012
    case invalidMobileConnection  // WS 4013 / error AUTH_FAILED|WRONG_PARAM / waiting 30s 超时
    case deviceOffline            // error DEVICE_OFFLINE（15s 宽限重连）
    case relayUnavailable(String) // 其余（网络/服务器 INTERNAL 等）

    var isTerminal: Bool {
        switch self {
        case .sessionNotFound, .sessionConflict, .sessionExpired, .invalidMobileConnection:
            return true
        // 4010/4012/DEVICE_OFFLINE/网络类：桌面侧暂态，可重连恢复
        case .desktopDisconnected, .workspaceClosed, .deviceOffline, .relayUnavailable:
            return false
        }
    }

    /// 错误码行（L3 codeLine 口径）
    var codeText: String {
        switch self {
        case .sessionNotFound: return "SESSION-NOT-FOUND · WS 4004"
        case .sessionConflict: return "SESSION-CONFLICT · WS 4009 / KICKED"
        case .desktopDisconnected: return "DESKTOP-DISCONNECTED · WS 4010"
        case .sessionExpired: return "SESSION-EXPIRED · WS 4011"
        case .workspaceClosed: return "WORKSPACE-CLOSED · WS 4012"
        case .invalidMobileConnection: return "INVALID-MOBILE-CONNECTION · WS 4013 / AUTH_FAILED"
        case .deviceOffline: return String(localized: "DEVICE-OFFLINE · 宽限重连")
        case .relayUnavailable(let detail): return "RELAY-UNAVAILABLE · \(detail)"
        }
    }

    static func from(closeCode: Int) -> RelayCloseReason? {
        switch closeCode {
        case 4004: return .sessionNotFound
        case 4009: return .sessionConflict
        case 4010: return .desktopDisconnected
        case 4011: return .sessionExpired
        case 4012: return .workspaceClosed
        case 4013: return .invalidMobileConnection
        default: return nil
        }
    }

    static func from(errorCode: String, message: String) -> RelayCloseReason {
        switch errorCode {
        case "KICKED": return .sessionConflict
        case "DEVICE_OFFLINE": return .deviceOffline
        case "AUTH_FAILED", "WRONG_PARAM": return .invalidMobileConnection
        default: return .relayUnavailable(message.isEmpty ? errorCode : "\(errorCode) \(message)")
        }
    }
}

// MARK: - 鉴权 proof（lVn @ src-D3H6NV7w.js:336279）

enum RelayAuth {

    /// proof = base64url 无填充(HMAC-SHA256(key=UTF8(passHash), msg=`${nonce}|terminal|${deviceSid}`))
    /// 实测向量：nonce=lFr2Po5uGyuNti2Xuw7LfcrN / hash=juuBpa…o4= / sid=d_5BHs7zpSQADkcFSNhfud5v
    ///       → TVEonHQISlbmNucXTEYrnfL0D3v00eO075w66jFq8Es（ws_probe.py 实测，auth_ack matched）
    static func proof(passHash: String, nonce: String, role: String, deviceSid: String) -> String {
        let message = Data("\(nonce)|\(role)|\(deviceSid)".utf8)
        let key = SymmetricKey(data: Data(passHash.utf8))
        let digest = HMAC<SHA256>.authenticationCode(for: message, using: key)
        return base64URLNoPadding(Data(digest))
    }

    static func base64URLNoPadding(_ data: Data) -> String {
        Data(data).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

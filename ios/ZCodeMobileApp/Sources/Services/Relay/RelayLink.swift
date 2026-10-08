import Foundation
import CryptoKit

// MARK: - 云中继链接配置（remote/v4 配对链接 → WS 端点 + 会话凭据）

/// 配对链接解析产物（逆向结论⑦）：`https://<host>/remote/v4?sid=…&hash=…&mid=…`
/// - sid/hash 不进 WS URL：仅用于 auth_init/auth_response 帧（hash 保留为字符串作 HMAC 密钥，
///   不做 base64 解码）；WS 端点 `wss://<host>/ws?mid=<mid>`（src-D3H6NV7w.js@179516 xh + Lh）。
struct RelayLinkConfig: Codable, Equatable {
    /// wss 端点（query 原样保留 mid 参数语义）
    var wssURL: String
    /// 配对链接 name 参数（桌面机器名，UI 展示用）
    var machineName: String?
    /// 配对链接 sid 参数（桌面会话凭据，auth 帧 device_sid）
    var deviceSid: String
    /// 配对链接 hash 参数（URL 解码后的 base64 原字符串，HMAC-SHA256 密钥；仅存 Keychain）
    var passHash: String
    /// 配对链接 app_version 参数（桌面版本，诊断对照 bootstrap-response.desktopAppVersion）
    var desktopAppVersion: String?

    var endpointHost: String? {
        URLComponents(string: wssURL)?.host
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

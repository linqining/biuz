import Foundation
import Security

// MARK: - 凭据读取语义（web v3.18.2 `systemCredentialCipher.ts` 的同构面）
//
// 权威源（`packages/shared/src/credential-security/systemCredentialCipher.ts`，`fec97590`）：
// - 「系统密钥不可用」是**独立**错误码，不是「密文损坏」：:67-75 `unavailable()` 注释原文
//   「系统库锁定/缺失不能冒充密文损坏，否则既有 OAuth 恢复逻辑会误删登录」。
// - 「密文/格式确坏」才允许清理：:61-66 `decryptError()` → `CREDENTIAL_DECRYPT_ERROR_CODE`；
//   调用方 `packages/shared/src/oauth.ts:42-56 isCredentialDecryptError` 是唯一清理白名单，
//   `services/src/credential/credentialService.ts:249-283` 还会在锁内二次确认「仍然坏」才清。
// - 三种读结果在语义上必须分开：本来就**没有**（未登录）/ **不可用**（换设备、库锁定、
//   用户拒绝授权）/ **确坏**（能读到但解不开）。
// - 来源钉住（:23-24、:241-249、:276、:305）：一旦来源固定为 os 就不再降级到弱密钥。
//   iOS 的 Keychain 就是唯一来源、没有「弱密钥降级路径」，故钉住条件恒成立——
//   该差异登记在 APP-DIFF[D014]，本文件不引入冗余的来源状态机。
//
// 本文件是**纯逻辑**（无 Keychain 调用、无 IO），单元测试可直接穷举口味。

/// 凭据读取失败的语义分类（与 Android `OAuthCredentialReadFailure`、小程序
/// `credential-store.js` 的 `REASON_*` 同一判定口径）。
///
/// APP-DIFF[D016] platform: 失败语义的类型形状按端习惯（Swift enum / Kotlin sealed class /
/// JS 常量+纯函数），web 用两个稳定错误码；**清理白名单与判定顺序逐条一致**。
enum CredentialFailureReason: String, Equatable, CaseIterable {
    /// 本机没有这条记录（= web 的 undefined/null，**不是错误**，走登录页）
    case itemNotFound = "item-not-found"
    /// 系统库不可用：锁定、未初始化、服务缺失、entitlement 缺失等（**保留数据**，可重试）
    case unavailable = "unavailable"
    /// 需要用户交互/授权被拒（Keychain 与 web 的 `unavailable` 同档：**保留数据**；
    /// 本地细分只为给出「解锁后再试」这类可执行文案，不改变是否清理的判定）
    case authFailed = "auth-failed"
    /// 能读到但解不开（密文损坏 / 结构非法）——**唯一**允许清理的分支
    case corrupt = "corrupt"

    /// 与 web 稳定错误码的映射（`oauth.ts:39`、`validation.ts:1006`）。
    /// iOS 不跨进程回传，保留字符串只为诊断日志与三端口径核对。
    var webErrorCode: String {
        switch self {
        case .corrupt: return "ZCODE_CREDENTIAL_DECRYPT_FAILED"
        case .unavailable, .authFailed: return "CREDENTIAL_SECURE_STORAGE_UNAVAILABLE"
        case .itemNotFound: return "CREDENTIAL_ITEM_NOT_FOUND"
        }
    }

    /// 上层必须据此做的决策（web `isCredentialDecryptError` 白名单的同构）。
    var recovery: CredentialRecovery {
        switch self {
        case .itemNotFound:
            return .requireLogin
        case .unavailable, .authFailed:
            // web 纪律：库锁定/缺密钥**不能**当登录失效，数据与登录态都必须留住
            return .keepDataAndRetry
        case .corrupt:
            return .purgeAndRequireLogin
        }
    }
}

/// 读取失败后的处置方案（三端同名同义）。
enum CredentialRecovery: String, Equatable {
    /// 没有凭据：进登录页（无数据可保护）
    case requireLogin = "require-login"
    /// 保留数据 + 暴露可观测失败：不清理、不登出，下次读取/解锁后重试
    case keepDataAndRetry = "keep-data-and-retry"
    /// 确定无法恢复：清理该凭据并进登录页（仅 corrupt 可达）
    case purgeAndRequireLogin = "purge-and-require-login"
}

/// 一次读取的结果（成功/没有/失败三分，失败携带语义原因）。
enum CredentialReadOutcome: Equatable {
    case value(Data)
    case absent
    case failed(CredentialFailureReason)

    var data: Data? {
        if case let .value(payload) = self { return payload }
        return nil
    }

    var failure: CredentialFailureReason? {
        if case let .failed(reason) = self { return reason }
        return nil
    }
}

/// 读判定纯函数集合（无 IO；`OSStatus` 由调用方从 Keychain 取）。
enum CredentialReadPolicy {

    /// `SecItemCopyMatching` 结果 → 语义结果。与 web 的判定顺序一致：
    /// 成功 → 值；`errSecItemNotFound` → 没有；其他一切 → 失败（且**默认按不可用处理**，
    /// 绝不默认按损坏处理——web :67-75）。
    static func classify(status: OSStatus, payload: Data?) -> CredentialReadOutcome {
        if status == errSecSuccess {
            guard let payload, !payload.isEmpty else {
                // 成功却没数据：库实现异常，按「结构非法」处理（web：能读到但解不开 → corrupt）
                return .failed(.corrupt)
            }
            return .value(payload)
        }
        if status == errSecItemNotFound { return .absent }
        return .failed(reason(for: status))
    }

    /// `OSStatus` → 失败原因。
    /// - `errSecDecode`（存储内容无法解码）→ corrupt（web 只有这一档允许清理）
    /// - 用户交互/授权类 → authFailed（保留数据；见 `CredentialFailureReason.authFailed` 注释）
    /// - 其余（含 `errSecNotAvailable`、`errSecInteractionNotAllowed` 以外的未知码）→ unavailable
    static func reason(for status: OSStatus) -> CredentialFailureReason {
        switch status {
        case errSecDecode:
            return .corrupt
        case errSecAuthFailed, errSecUserCanceled, errSecInteractionNotAllowed,
             errSecInteractionRequired:
            return .authFailed
        default:
            return .unavailable
        }
    }

    /// Codable 解码失败 → corrupt（能读到但解不开：web `open()` 的认证失败同档）。
    static func classifyDecodingFailure() -> CredentialReadOutcome {
        .failed(.corrupt)
    }

    /// 是否允许清理（web 清理白名单的唯一判据）。
    static func canPurge(_ reason: CredentialFailureReason) -> Bool {
        reason.recovery == .purgeAndRequireLogin
    }

    /// 面向用户的说明（只描述下一步，不暴露任何密钥材料）。
    static func message(for reason: CredentialFailureReason) -> String {
        switch reason {
        case .itemNotFound:
            return ""
        case .unavailable:
            return String(localized: "系统钥匙串暂时不可用（可能已锁定）；登录态已保留，解锁后重试即可")
        case .authFailed:
            return String(localized: "钥匙串需要授权/解锁后才能读取登录凭据；登录态已保留")
        case .corrupt:
            return String(localized: "本机保存的登录凭据已损坏，无法解密；请重新登录")
        }
    }
}

/// 账户层（tokenSet + userInfo）读取结果：三分 + 具体值。
/// 单独成类型是因为它跨两个 Keychain 账户，且上层要拿到值本身。
enum OAuthCredentialReadOutcome: Equatable {
    case value(tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo)
    case absent
    case failed(CredentialFailureReason)

    var credentials: (tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo)? {
        if case let .value(tokenSet, userInfo) = self { return (tokenSet, userInfo) }
        return nil
    }

    var failure: CredentialFailureReason? {
        if case let .failed(reason) = self { return reason }
        return nil
    }
}

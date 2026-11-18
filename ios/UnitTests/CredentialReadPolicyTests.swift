import XCTest
@testable import ZCodeMobile

/// 凭据读取语义单测（web v3.18.2 凭证安全对齐，2026-11-19）
///
/// 权威源：`packages/shared/src/credential-security/systemCredentialCipher.ts:61-75`
/// ——「密文损坏」与「系统钥匙串不可用」必须是两个不同的错误码，
/// 否则上层恢复逻辑会把「库锁定」当成登录失效误删数据。
/// 清理白名单唯一判据：`packages/shared/src/oauth.ts:42-56 isCredentialDecryptError`。
final class CredentialReadPolicyTests: XCTestCase {

    // MARK: ① OSStatus → 语义三分

    func testItemNotFoundIsAbsentNotFailure() {
        XCTAssertEqual(CredentialReadPolicy.classify(status: errSecItemNotFound, payload: nil), .absent)
        XCTAssertEqual(
            CredentialReadPolicy.classify(status: errSecItemNotFound, payload: Data("x".utf8)),
            .absent,
            "没有记录时不得因 payload 判损坏",
        )
    }

    func testSuccessWithPayloadIsValue() {
        let payload = Data("token".utf8)
        XCTAssertEqual(CredentialReadPolicy.classify(status: errSecSuccess, payload: payload), .value(payload))
    }

    func testSuccessWithoutPayloadIsCorrupt() {
        // 成功却没数据 = 库实现异常 → 结构非法（corrupt 是唯一可清理档）
        XCTAssertEqual(CredentialReadPolicy.classify(status: errSecSuccess, payload: nil), .failed(.corrupt))
        XCTAssertEqual(CredentialReadPolicy.classify(status: errSecSuccess, payload: Data()), .failed(.corrupt))
    }

    func testInteractionAndAuthStatusAreAuthFailed() {
        for status in [errSecAuthFailed, errSecUserCanceled, errSecInteractionNotAllowed, errSecInteractionRequired] {
            XCTAssertEqual(CredentialReadPolicy.reason(for: status), .authFailed, "status=\(status)")
        }
    }

    func testDecodeStatusIsCorrupt() {
        XCTAssertEqual(CredentialReadPolicy.reason(for: errSecDecode), .corrupt)
    }

    func testUnknownStatusDefaultsToUnavailableNeverCorrupt() {
        // web :67-75：系统库锁定/缺失不能冒充密文损坏——未知码一律按不可用（保留数据）
        for status: OSStatus in [errSecNotAvailable, -1, -25300 - 1, 99999] {
            let reason = CredentialReadPolicy.reason(for: status)
            XCTAssertNotEqual(reason, .corrupt, "status=\(status) 不得判损坏")
            XCTAssertEqual(reason, .unavailable, "status=\(status) 应判不可用")
        }
    }

    // MARK: ② 处置方案（清理白名单）

    func testRecoveryMapping() {
        XCTAssertEqual(CredentialFailureReason.itemNotFound.recovery, .requireLogin)
        XCTAssertEqual(CredentialFailureReason.unavailable.recovery, .keepDataAndRetry)
        XCTAssertEqual(CredentialFailureReason.authFailed.recovery, .keepDataAndRetry)
        XCTAssertEqual(CredentialFailureReason.corrupt.recovery, .purgeAndRequireLogin)
    }

    func testOnlyCorruptCanPurge() {
        for reason in CredentialFailureReason.allCases {
            XCTAssertEqual(
                CredentialReadPolicy.canPurge(reason),
                reason == .corrupt,
                "只有 corrupt 允许清理：\(reason)",
            )
        }
    }

    // MARK: ③ 三端同口径的稳定错误码

    func testWebErrorCodesAligned() {
        XCTAssertEqual(CredentialFailureReason.corrupt.webErrorCode, "ZCODE_CREDENTIAL_DECRYPT_FAILED")
        XCTAssertEqual(CredentialFailureReason.unavailable.webErrorCode, "CREDENTIAL_SECURE_STORAGE_UNAVAILABLE")
        XCTAssertEqual(CredentialFailureReason.authFailed.webErrorCode, "CREDENTIAL_SECURE_STORAGE_UNAVAILABLE")
    }

    // MARK: ④ 文案（不暴露材料、可执行）

    func testMessagesAreActionableAndNotLeaking() {
        XCTAssertEqual(CredentialReadPolicy.message(for: .itemNotFound), "", "没有记录不需要额外提示")
        for reason in [CredentialFailureReason.unavailable, .authFailed, .corrupt] {
            let message = CredentialReadPolicy.message(for: reason)
            XCTAssertFalse(message.isEmpty, "\(reason) 必须有可执行提示")
            XCTAssertFalse(message.contains("token"), "文案不得出现凭据字面量")
        }
    }

    // MARK: ⑤ 账户层读取结果（值/没有/失败三分）

    func testOAuthOutcomeAccessors() {
        let tokenSet = OAuthTokenSet(accessToken: "a", zcodeJwtToken: "j", expiresAt: nil)
        let user = OAuthUserInfo(id: "u", username: "u", displayName: "U", avatarUrl: nil)
        let value = OAuthCredentialReadOutcome.value(tokenSet: tokenSet, userInfo: user)
        XCTAssertEqual(value.credentials?.tokenSet.zcodeJwtToken, "j")
        XCTAssertNil(value.failure)

        let failed = OAuthCredentialReadOutcome.failed(.unavailable)
        XCTAssertNil(failed.credentials, "不可用时不得返回半截凭据")
        XCTAssertEqual(failed.failure, .unavailable)

        XCTAssertNil(OAuthCredentialReadOutcome.absent.credentials)
        XCTAssertNil(OAuthCredentialReadOutcome.absent.failure)
    }

    func testDecodingFailureIsCorrupt() {
        XCTAssertEqual(CredentialReadPolicy.classifyDecodingFailure(), .failed(.corrupt))
    }
}

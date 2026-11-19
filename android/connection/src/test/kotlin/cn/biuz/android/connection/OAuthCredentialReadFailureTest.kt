package cn.biuz.android.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 凭据读取语义对拍（web v3.18.2 `systemCredentialCipher.ts:61-75`，`fec97590`）
 *
 * 对齐面：
 * - 「系统密钥不可用」与「密文确坏」必须是两个不同错误码，只有后者允许清理
 *   （`packages/shared/src/oauth.ts:42-56 isCredentialDecryptError` 是唯一清理白名单）；
 * - 原实现 `catch { clear(); null }` 会把三种完全不同的读失败一次性毁掉用户凭据，
 *   本用例逐条锁死「哪些原因不得清理」。
 */
class OAuthCredentialReadFailureTest {

    // ---- 清理白名单（最具破坏性的判定，逐条锁死） ----

    @Test
    fun onlyCorruptReasonsMayPurge() {
        val mayPurge = listOf(
            OAuthCredentialReadFailure.CiphertextCorrupt("AEADBadTagException"),
            OAuthCredentialReadFailure.PayloadInvalid("json-parse-failed"),
        )
        val mustKeep = listOf(
            OAuthCredentialReadFailure.StorageUnavailable("IOException"),
            OAuthCredentialReadFailure.EnvelopeMalformed("blob-too-short:3"),
            OAuthCredentialReadFailure.KeystoreUnavailable("KeyPermanentlyInvalidatedException"),
            OAuthCredentialReadFailure.KeyMissing("keystore-entry-absent"),
        )
        mayPurge.forEach { assertTrue(CredentialReadPolicy.canPurge(it), "应允许清理：$it") }
        mustKeep.forEach {
            assertTrue(!CredentialReadPolicy.canPurge(it), "不得清理（必须保留数据）：$it")
        }
    }

    @Test
    fun recoveryMatchesCanPurge() {
        val reasons = listOf(
            OAuthCredentialReadFailure.StorageUnavailable("x"),
            OAuthCredentialReadFailure.EnvelopeMalformed("x"),
            OAuthCredentialReadFailure.KeystoreUnavailable("x"),
            OAuthCredentialReadFailure.KeyMissing("x"),
            OAuthCredentialReadFailure.CiphertextCorrupt("x"),
            OAuthCredentialReadFailure.PayloadInvalid("x"),
        )
        reasons.forEach { reason ->
            val expected = if (CredentialReadPolicy.canPurge(reason)) {
                CredentialRecovery.PurgeAndRequireLogin
            } else {
                CredentialRecovery.KeepDataAndRetry
            }
            assertEquals(expected, CredentialReadPolicy.recovery(reason), "恢复方案漂移：$reason")
        }
    }

    // ---- 与 web 稳定错误码对齐 ----

    @Test
    fun webErrorCodesAligned() {
        listOf(
            OAuthCredentialReadFailure.CiphertextCorrupt("x"),
            OAuthCredentialReadFailure.PayloadInvalid("x"),
        ).forEach {
            assertEquals("ZCODE_CREDENTIAL_DECRYPT_FAILED", it.webErrorCode, "$it")
        }
        listOf(
            OAuthCredentialReadFailure.StorageUnavailable("x"),
            OAuthCredentialReadFailure.EnvelopeMalformed("x"),
            OAuthCredentialReadFailure.KeystoreUnavailable("x"),
            OAuthCredentialReadFailure.KeyMissing("x"),
        ).forEach {
            assertEquals("CREDENTIAL_SECURE_STORAGE_UNAVAILABLE", it.webErrorCode, "$it")
        }
    }

    // ---- 异常 → 原因的判定顺序（密钥先于密文；web :403-425 同序） ----

    @Test
    fun classifyPrefersKeyProblemsOverCiphertextCorruption() {
        // 密钥缺失 + 认证失败同时成立时，必须报「密钥不可用」（否则会误清用户数据）
        val both = CredentialReadPolicy.classifyDecryptFailure(
            envelopeMalformed = false,
            keyUnavailable = false,
            keyMissing = true,
            authenticatedEncryptionFailed = true,
            detail = "both",
        )
        assertIs<OAuthCredentialReadFailure.KeyMissing>(both)

        val keyUnavailable = CredentialReadPolicy.classifyDecryptFailure(
            envelopeMalformed = true,
            keyUnavailable = true,
            keyMissing = false,
            authenticatedEncryptionFailed = true,
            detail = "both",
        )
        assertIs<OAuthCredentialReadFailure.KeystoreUnavailable>(keyUnavailable)
    }

    @Test
    fun classifyFallsBackToStorageUnavailableNotCorrupt() {
        // 未知异常默认落到「保留数据」侧（web：未知系统库错误一律按不可用）
        val unknown = CredentialReadPolicy.classifyDecryptFailure(
            envelopeMalformed = false,
            keyUnavailable = false,
            keyMissing = false,
            authenticatedEncryptionFailed = false,
            detail = "weird",
        )
        assertIs<OAuthCredentialReadFailure.StorageUnavailable>(unknown)
        assertTrue(!CredentialReadPolicy.canPurge(unknown))
    }

    @Test
    fun classifyMapsEachSingleCause() {
        assertIs<OAuthCredentialReadFailure.EnvelopeMalformed>(
            CredentialReadPolicy.classifyDecryptFailure(true, false, false, false, "d"),
        )
        assertIs<OAuthCredentialReadFailure.CiphertextCorrupt>(
            CredentialReadPolicy.classifyDecryptFailure(false, false, false, true, "d"),
        )
    }

    // ---- 文案（可执行、不泄露材料） ----

    @Test
    fun messagesAreActionableAndDoNotLeakMaterial() {
        val reasons = listOf(
            OAuthCredentialReadFailure.StorageUnavailable("x"),
            OAuthCredentialReadFailure.EnvelopeMalformed("x"),
            OAuthCredentialReadFailure.KeystoreUnavailable("x"),
            OAuthCredentialReadFailure.KeyMissing("x"),
            OAuthCredentialReadFailure.CiphertextCorrupt("x"),
            OAuthCredentialReadFailure.PayloadInvalid("x"),
        )
        reasons.forEach { reason ->
            val message = CredentialReadPolicy.message(reason)
            assertTrue(message.isNotEmpty(), "必须有可执行提示：$reason")
            assertTrue(!message.contains("biuz.oauth.v1"), "文案不得暴露密钥别名：$reason")
            assertTrue(!message.contains("com."), "文案不得暴露包名/异常类：$reason")
        }
    }

    // ---- 存储层三分（Absent/Value/Failed） ----

    @Test
    fun storeDoesNotClearOnKeystoreUnavailable() {
        val backing = RecordingPersistence(
            OAuthCredentialReadOutcome.Failed(
                OAuthCredentialReadFailure.KeystoreUnavailable("KeyPermanentlyInvalidatedException"),
            ),
        )
        val store = OAuthCredentialStore(backing)
        assertNull(store.tokenSet, "读不出凭据")
        assertNotNull(store.lastReadFailure, "必须暴露可观测原因")
        assertEquals(0, backing.clearCalls, "密钥不可用时不得清理数据")
    }

    @Test
    fun storeClearsOnlyOnCorruptPayload() {
        val backing = RecordingPersistence(
            OAuthCredentialReadOutcome.Value("""{"accessToken":"a"}"""), // 缺 zcodeJwtToken
        )
        val store = OAuthCredentialStore(backing)
        assertNull(store.tokenSet)
        assertIs<OAuthCredentialReadFailure.PayloadInvalid>(store.lastReadFailure)
        assertEquals(1, backing.clearCalls, "结构非法（确坏）才允许清理")
    }

    @Test
    fun storeKeepsPayloadWhenValueIsValid() {
        val backing = RecordingPersistence(
            OAuthCredentialReadOutcome.Value(
                """{"accessToken":"at","zcodeJwtToken":"jwt","userId":"u","username":"n","displayName":"N"}""",
            ),
        )
        val store = OAuthCredentialStore(backing)
        assertEquals("jwt", store.tokenSet?.zcodeJwtToken)
        assertNull(store.lastReadFailure)
        assertEquals(0, backing.clearCalls)
    }

    @Test
    fun reloadRecoversAfterKeyReturns() {
        val backing = RecordingPersistence(
            OAuthCredentialReadOutcome.Failed(OAuthCredentialReadFailure.StorageUnavailable("flaky")),
        )
        val store = OAuthCredentialStore(backing)
        assertNotNull(store.lastReadFailure)
        backing.outcome = OAuthCredentialReadOutcome.Value(
            """{"accessToken":"at","zcodeJwtToken":"jwt"}""",
        )
        val outcome = store.reload()
        assertIs<OAuthCredentialReadOutcome.Value>(outcome)
        assertNull(store.lastReadFailure, "恢复后原因必须清空")
        assertEquals("jwt", store.tokenSet?.zcodeJwtToken)
    }

    @Test
    fun absentOutcomeIsNotAFailure() {
        val store = OAuthCredentialStore(RecordingPersistence(OAuthCredentialReadOutcome.Absent))
        assertNull(store.tokenSet)
        assertNull(store.lastReadFailure, "没有凭据 ≠ 读取失败")
    }

    /** 记录 clear 次数的假后端（可注入指定读结果，模拟密钥失效/密文损坏）。 */
    private class RecordingPersistence(
        var outcome: OAuthCredentialReadOutcome,
    ) : OAuthCredentialPersistence {
        var clearCalls = 0
        override fun load(): String? = outcome.payloadOrNull
        override fun loadOutcome(): OAuthCredentialReadOutcome = outcome
        override fun save(payload: String): Boolean = true
        override fun clear() {
            clearCalls += 1
            outcome = OAuthCredentialReadOutcome.Absent
        }
    }
}

package cn.biuz.android.connection

// 凭据读取语义（web v3.18.2 `systemCredentialCipher.ts` 的 Kotlin 同构面，2026-11-19）
//
// 权威源（`packages/shared/src/credential-security/systemCredentialCipher.ts`，`fec97590`）：
// - 「系统密钥不可用」是**独立**错误码，不是「密文损坏」：:67-75 `unavailable()` 注释原文
//   「系统库锁定/缺失不能冒充密文损坏，否则既有 OAuth 恢复逻辑会误删登录」。
// - 「密文/格式确坏」才允许清理：:61-66 `decryptError()` → `CREDENTIAL_DECRYPT_ERROR_CODE`；
//   唯一清理白名单是 `packages/shared/src/oauth.ts:42-56 isCredentialDecryptError`，
//   且 `services/src/credential/credentialService.ts:249-283` 还要在锁内二次确认「仍然坏」。
// - 来源钉住（:23-24、:241-249、:276、:305）：来源固定后不再降级到弱密钥。
//   Android Keystore 是唯一来源、无降级路径 → 钉住条件恒成立，差异登记 APP-DIFF[D014]。
//
// 本文件是**纯逻辑**（无 Android API 调用、无 IO），:app 的 Keystore 实现把异常映射到
// 这里的枚举，策略判定集中在 `CredentialReadPolicy`，方便纯 JVM 单测穷举。

/**
 * 凭据读取失败原因（与 iOS `CredentialFailureReason`、小程序 `credential-store.js`
 * 的 `REASON_*` 同一判定口径）。
 *
 * APP-DIFF[D016] platform: 失败语义用 Kotlin sealed class（iOS 用 Swift enum、小程序用
 * 常量+纯函数），web 用两个稳定错误码；**清理白名单与判定顺序逐条一致**。
 */
sealed class OAuthCredentialReadFailure {
    /** 底层存储读取抛异常（SharedPreferences/IO）。数据未知，**保留**不清理。 */
    data class StorageUnavailable(val detail: String) : OAuthCredentialReadFailure()

    /** 存储里有值但**不是合法 base64 / 长度不足**：无法区分「损坏」与「内容是被截断的密钥」。 */
    data class EnvelopeMalformed(val detail: String) : OAuthCredentialReadFailure()

    /**
     * Android Keystore 密钥不可用：`KeyPermanentlyInvalidatedException`（换锁屏/清凭据）、
     * `UnrecoverableKeyException`、密钥被系统删除等——**换设备/密钥丢失语义**，
     * 与 web 的 `CREDENTIAL_SECURE_STORAGE_UNAVAILABLE` 同档：保留数据、要求重新登录，
     * 但**不得**当作「数据损坏」来清理。
     */
    data class KeystoreUnavailable(val detail: String) : OAuthCredentialReadFailure()

    /** Keystore 密钥本机就取不到（别名不存在且无法生成 / 生成失败）。 */
    data class KeyMissing(val detail: String) : OAuthCredentialReadFailure()

    /** GCM 认证失败（`AEADBadTagException`）或 padding 异常：密文**确坏**，唯一允许清理的一档。 */
    data class CiphertextCorrupt(val detail: String) : OAuthCredentialReadFailure()

    /** 解出来的明文不是预期结构（JSON 解析失败 / 必填字段缺失）：能读到但解不开 → 确坏。 */
    data class PayloadInvalid(val detail: String) : OAuthCredentialReadFailure()

    /**
     * 与 web 稳定错误码的映射（`oauth.ts:39`、`validation.ts:1006`）。保留字符串用于
     * 诊断日志与三端口径核对；Android 不跨进程回传错误码。
     */
    val webErrorCode: String
        get() = when (this) {
            is CiphertextCorrupt, is PayloadInvalid -> "ZCODE_CREDENTIAL_DECRYPT_FAILED"
            is KeystoreUnavailable, is KeyMissing, is StorageUnavailable, is EnvelopeMalformed ->
                "CREDENTIAL_SECURE_STORAGE_UNAVAILABLE"
        }
}

/** 读取失败后的处置方案（三端同名同义）。 */
enum class CredentialRecovery {
    /** 保留数据 + 暴露可观测失败：不清理、不登出，下次读取/解锁后重试。 */
    KeepDataAndRetry,

    /** 确定无法恢复：清理该凭据并进登录页（仅确坏可达）。 */
    PurgeAndRequireLogin,
}

/** 一次凭据读取的结果（成功/没有/失败三分）。 */
sealed class OAuthCredentialReadOutcome {
    /** 读到并解析成功的载荷（不透明字符串，结构由 OAuthCredentialStore 解释）。 */
    data class Value(val payload: String) : OAuthCredentialReadOutcome()

    /** 本来就没有（未登录；不算错误）。 */
    data object Absent : OAuthCredentialReadOutcome()

    /** 失败（携带语义原因，上层据此决策）。 */
    data class Failed(val reason: OAuthCredentialReadFailure) : OAuthCredentialReadOutcome()

    val payloadOrNull: String?
        get() = (this as? Value)?.payload

    val failureOrNull: OAuthCredentialReadFailure?
        get() = (this as? Failed)?.reason
}

/** 读判定纯函数集合（无 IO）。 */
object CredentialReadPolicy {

    /**
     * 是否允许清理（web 清理白名单的唯一判据）。
     * **只有确坏**才允许清；密钥不可用/存储不可用一律保留数据。
     */
    fun canPurge(reason: OAuthCredentialReadFailure): Boolean = when (reason) {
        is OAuthCredentialReadFailure.CiphertextCorrupt,
        is OAuthCredentialReadFailure.PayloadInvalid,
        -> true
        is OAuthCredentialReadFailure.StorageUnavailable,
        is OAuthCredentialReadFailure.EnvelopeMalformed,
        is OAuthCredentialReadFailure.KeystoreUnavailable,
        is OAuthCredentialReadFailure.KeyMissing,
        -> false
    }

    /** 失败原因 → 处置方案（与 [canPurge] 同源，避免两处口径漂移）。 */
    fun recovery(reason: OAuthCredentialReadFailure): CredentialRecovery =
        if (canPurge(reason)) CredentialRecovery.PurgeAndRequireLogin
        else CredentialRecovery.KeepDataAndRetry

    /**
     * 面向用户的说明（只描述下一步，不暴露任何密钥材料/别名）。
     */
    fun message(reason: OAuthCredentialReadFailure): String = when (reason) {
        is OAuthCredentialReadFailure.StorageUnavailable ->
            "本地存储暂时不可用，登录态已保留；请稍后重试"
        is OAuthCredentialReadFailure.EnvelopeMalformed ->
            "本机凭据内容不完整（可能写入中断），登录态未清理；请重新登录"
        is OAuthCredentialReadFailure.KeystoreUnavailable ->
            "本机密钥库凭据已失效（可能更换了设备锁屏或清除过凭据），已保存的登录无法恢复；请重新登录"
        is OAuthCredentialReadFailure.KeyMissing ->
            "本机密钥库中找不到凭据密钥，已保存的登录无法恢复；请重新登录"
        is OAuthCredentialReadFailure.CiphertextCorrupt ->
            "本机保存的登录凭据已损坏，无法解密；请重新登录"
        is OAuthCredentialReadFailure.PayloadInvalid ->
            "本机保存的登录凭据结构异常，无法解析；请重新登录"
    }

    /**
     * 异常 → 失败原因（:app 的 Keystore 实现调用；**保持纯函数**，不依赖 Android 类型，
     * 由调用方把异常类名/是否可恢复的事实折算成布尔与类型名传入）。
     *
     * 判定顺序与 web `open()`（:82-96）一致：先看容器格式，再看密钥可用性，
     * 最后才是认证失败（确坏）。
     */
    fun classifyDecryptFailure(
        envelopeMalformed: Boolean,
        keyUnavailable: Boolean,
        keyMissing: Boolean,
        authenticatedEncryptionFailed: Boolean,
        detail: String,
    ): OAuthCredentialReadFailure = when {
        keyMissing -> OAuthCredentialReadFailure.KeyMissing(detail)
        keyUnavailable -> OAuthCredentialReadFailure.KeystoreUnavailable(detail)
        envelopeMalformed -> OAuthCredentialReadFailure.EnvelopeMalformed(detail)
        authenticatedEncryptionFailed -> OAuthCredentialReadFailure.CiphertextCorrupt(detail)
        // 既不是密钥问题也不是认证失败 → 落到「存储不可用」（保留数据，默认安全侧）
        else -> OAuthCredentialReadFailure.StorageUnavailable(detail)
    }
}

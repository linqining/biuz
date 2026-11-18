package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue

/**
 * OAuth 凭据持久化端口（对应 iOS Keychain；Android 由 app 模块用 Keystore 加密后落
 * SharedPreferences 实现，见 `KeystoreOAuthCredentialPersistence`）。
 *
 * 只存一段不透明字符串：JSON 结构由本类的 [OAuthCredentialStore] 自己负责，
 * 持久化实现不需要理解 token 形状。
 *
 * 读取语义（2026-11-19，web v3.18.2 凭证安全对齐）：[loadOutcome] 是**推荐入口**，
 * 它区分「没有 / 密钥不可用 / 密文确坏」三种读失败（web `systemCredentialCipher.ts:61-75`）；
 * [load] 只为兼容老实现保留，**不携带失败原因**，新实现不应只覆盖 [load]。
 */
interface OAuthCredentialPersistence {
    /**
     * 兼容读面（旧实现只有这一个）：返回 null 表示「没有或读不出」，调用方无法分辨原因。
     */
    fun load(): String?

    fun save(payload: String): Boolean

    fun clear()

    /**
     * 语义化读面。默认实现退化为 [load]（无原因信息），Keystore 实现必须覆盖它，
     * 把异常映射成 [OAuthCredentialReadFailure] 后由 [OAuthCredentialStore] 决策是否清理。
     */
    fun loadOutcome(): OAuthCredentialReadOutcome {
        val payload = load() ?: return OAuthCredentialReadOutcome.Absent
        return OAuthCredentialReadOutcome.Value(payload)
    }
}

/** 内存实现（单测/无持久化环境；生产由 :app 注入 Keystore 加密实现）。 */
class InMemoryOAuthCredentialPersistence(private var payload: String? = null) : OAuthCredentialPersistence {
    override fun load(): String? = payload
    override fun save(payload: String): Boolean {
        this.payload = payload
        return true
    }
    override fun clear() {
        payload = null
    }
}

/**
 * 账户凭据存储（iOS `Services/Keychain/KeychainStore.swift:119-160` 的 OAuthCredentialStore 同构）。
 *
 * key：`oauth.tokenSet.v1` / `oauth.userInfo.v1`（iOS 同名），Android 合成一段 JSON 落盘。
 * 过期策略：首发口径过期即要求重新登录，不做静默续期。
 */
class OAuthCredentialStore(private val persistence: OAuthCredentialPersistence) {

    data class Credentials(val tokenSet: OAuthTokenSet, val userInfo: OAuthUserInfo)

    var tokenSet: OAuthTokenSet? = null
        private set

    var userInfo: OAuthUserInfo? = null
        private set

    /**
     * 最近一次读取失败原因（可观测面：登录/设置页据此显示「密钥失效」而不是「未登录」）。
     * null = 正常或本来就没有凭据。
     */
    var lastReadFailure: OAuthCredentialReadFailure? = null
        private set

    init {
        loadFromPersistence()
    }

    fun save(tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo): OAuthTokenSet {
        val payload = JSONValue.Obj(
            mapOf(
                "accessToken" to JSONValue.Str(tokenSet.accessToken),
                "zcodeJwtToken" to JSONValue.Str(tokenSet.zcodeJwtToken),
                "expiresAt" to (tokenSet.expiresAt?.let { JSONValue.fromAny(it) } ?: JSONValue.Null),
                "userId" to JSONValue.Str(userInfo.id),
                "username" to JSONValue.Str(userInfo.username),
                "displayName" to JSONValue.Str(userInfo.displayName),
                "avatarUrl" to (userInfo.avatarUrl?.let { JSONValue.Str(it) } ?: JSONValue.Null),
            ),
        ).data ?: throw OAuthException.CredentialWriteFailed()

        // 先落盘再改内存：写入失败时内存态不得领先于持久化（否则重启即丢登录）
        if (!persistence.save(String(payload, Charsets.UTF_8))) {
            throw OAuthException.CredentialWriteFailed()
        }
        this.tokenSet = tokenSet
        this.userInfo = userInfo
        return tokenSet
    }

    fun load(): Credentials? {
        val ts = tokenSet ?: return null
        val ui = userInfo ?: return null
        return Credentials(ts, ui)
    }

    fun clear() {
        persistence.clear()
        tokenSet = null
        userInfo = null
    }

    /** 是否已过期（无 expiresAt 视为不过期；未登录视为不过期）。 */
    fun isExpired(set: OAuthTokenSet? = tokenSet, now: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = set?.expiresAt ?: return false
        return now >= expiresAt
    }

    /** 掩码展示（末 4 位）。 */
    fun mask(secret: String): String {
        if (secret.length <= 4) return "••••"
        return "••••••••" + secret.takeLast(4)
    }

    private fun loadFromPersistence() {
        when (val outcome = persistence.loadOutcome()) {
            is OAuthCredentialReadOutcome.Value -> adoptPayload(outcome.payload)
            OAuthCredentialReadOutcome.Absent -> lastReadFailure = null
            is OAuthCredentialReadOutcome.Failed -> handleFailure(outcome.reason)
        }
    }

    /**
     * 读失败处置（web `isCredentialDecryptError` 白名单同构）：
     * **只有确坏才清理**；密钥不可用/存储不可用一律**保留**数据并暴露原因
     * （原实现 catch 后无条件 `clear()`，等于一次性毁掉用户凭据——
     * `systemCredentialCipher.ts:67-75` 明文禁止这样做）。
     */
    private fun handleFailure(reason: OAuthCredentialReadFailure) {
        lastReadFailure = reason
        if (CredentialReadPolicy.canPurge(reason)) {
            persistence.clear()
        }
    }

    /**
     * 重新读取（登录页/设置页「重试」入口）。语义同构造期：不可用只记录原因。
     * 返回当前结果，供调用方直接上屏。
     */
    fun reload(): OAuthCredentialReadOutcome {
        val outcome = persistence.loadOutcome()
        when (outcome) {
            is OAuthCredentialReadOutcome.Value -> adoptPayload(outcome.payload)
            OAuthCredentialReadOutcome.Absent -> {
                tokenSet = null
                userInfo = null
                lastReadFailure = null
            }
            is OAuthCredentialReadOutcome.Failed -> {
                tokenSet = null
                userInfo = null
                handleFailure(outcome.reason)
            }
        }
        return outcome
    }

    /** 解析载荷；解不开属于「能读到但解不开」→ 确坏（唯一可清理档）。 */
    private fun adoptPayload(raw: String) {
        val parsed = runCatching { JSONValue.parse(raw) }.getOrNull()
        if (parsed == null) {
            lastReadFailure = OAuthCredentialReadFailure.PayloadInvalid("json-parse-failed")
            if (CredentialReadPolicy.canPurge(lastReadFailure!!)) persistence.clear()
            return
        }
        val accessToken = parsed["accessToken"]?.stringValue
        val jwt = parsed["zcodeJwtToken"]?.stringValue
        if (accessToken.isNullOrEmpty() || jwt.isNullOrEmpty()) {
            lastReadFailure = OAuthCredentialReadFailure.PayloadInvalid("missing-required-field")
            if (CredentialReadPolicy.canPurge(lastReadFailure!!)) persistence.clear()
            return
        }
        lastReadFailure = null
        tokenSet = OAuthTokenSet(
            accessToken = accessToken,
            zcodeJwtToken = jwt,
            expiresAt = parsed["expiresAt"]?.doubleValue?.toLong()
                ?: parsed["expiresAt"]?.intValue?.toLong(),
        )
        val id = parsed["userId"]?.stringValue
        val username = parsed["username"]?.stringValue
        val displayName = parsed["displayName"]?.stringValue
        if (!id.isNullOrEmpty() && !username.isNullOrEmpty() && !displayName.isNullOrEmpty()) {
            userInfo = OAuthUserInfo(
                id = id,
                username = username,
                displayName = displayName,
                avatarUrl = parsed["avatarUrl"]?.stringValue,
            )
        }
    }
}

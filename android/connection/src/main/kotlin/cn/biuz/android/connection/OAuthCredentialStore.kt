package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue

/**
 * OAuth 凭据持久化端口（对应 iOS Keychain；Android 由 app 模块用 Keystore 加密后落
 * SharedPreferences 实现，见 `KeystoreOAuthCredentialPersistence`）。
 *
 * 只存一段不透明字符串：JSON 结构由本类的 [OAuthCredentialStore] 自己负责，
 * 持久化实现不需要理解 token 形状。
 */
interface OAuthCredentialPersistence {
    fun load(): String?
    fun save(payload: String): Boolean
    fun clear()
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
        val raw = persistence.load() ?: return
        val parsed = runCatching { JSONValue.parse(raw) }.getOrNull() ?: return
        val accessToken = parsed["accessToken"]?.stringValue
        val jwt = parsed["zcodeJwtToken"]?.stringValue
        if (accessToken.isNullOrEmpty() || jwt.isNullOrEmpty()) return
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

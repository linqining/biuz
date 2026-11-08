package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Z.ai / BigModel OAuth 客户端（iOS `Services/OAuth/ZaiOAuthProvider.swift:136-346` 同构移植，
 * 上游参考实现 `packages/web/src/auth/zaiWebOAuthProvider.ts`）。
 *
 * 职责：① 构造授权 URL ② 解析回调参数 ③ 用 code 换 token 并归一化回执。
 * 不做 UI、不落凭据（凭据由 [OAuthCredentialStore] 负责）。
 */
class ZaiOAuthClient(
    private val config: OAuthConfig = OAuthConfig.resolve(),
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /** ① 授权入口：ZAI `{origin}/api/oauth/authorize`；BigModel `{origin}/login`（参数名 redirect ≠ redirect_uri）。 */
    fun buildAuthorizeUrl(provider: OAuthProviderId, state: String): String {
        return when (provider) {
            OAuthProviderId.ZAI -> {
                val base = "${trimTrailingSlash(config.zaiOrigin)}/api/oauth/authorize"
                base + "?" + listOf(
                    "redirect_uri" to config.redirectUri,
                    "response_type" to "code",
                    "client_id" to config.clientId,
                    "state" to state,
                ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            }
            OAuthProviderId.BIGMODEL -> {
                val base = "${trimTrailingSlash(config.bigModelOrigin)}/login"
                base + "?" + listOf(
                    "redirect" to config.redirectUri,
                    "appId" to config.bigModelAppId,
                    "state" to state,
                ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            }
        }
    }

    /** ③ 回调解析：state 必填（缺失即 StateMismatch），code / authCode 双兼容。 */
    fun parseCallbackParams(url: String): OAuthCallbackParams {
        val query = queryParams(url)
        val state = query["state"]?.trim().orEmpty()
        if (state.isEmpty()) throw OAuthException.StateMismatch()
        return OAuthCallbackParams(
            code = query["code"] ?: query["authCode"],
            error = query["error"],
            state = state,
        )
    }

    /** 回调 URL 判定（注册的 web 回调路径 / 兼容自定义 scheme）。 */
    fun isCallbackUrl(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        val path = (target.path ?: "").lowercase()
        val redirect = runCatching { URI(config.redirectUri) }.getOrNull()
        if (redirect?.scheme?.lowercase() == "https") {
            val isWebCallbackPath = path == "/cn/share/callback" || path == "/share/callback"
            return isWebCallbackPath &&
                target.host?.lowercase() == redirect.host?.lowercase() &&
                (target.scheme?.lowercase() == "https" || target.scheme?.lowercase() == "http")
        }
        if (redirect == null) return false
        return target.scheme?.lowercase() == redirect.scheme?.lowercase() &&
            target.host?.lowercase() == redirect.host?.lowercase() &&
            path.startsWith(((redirect.path ?: "")).ifEmpty { "/" })
    }

    /**
     * ④ 用授权码换令牌。
     *
     * 服务端在同源上下文校验会话；移动端把授权 WebView 的 Cookie 透传给交换请求保持连续性
     * （iOS 同口径 `exchangeToken(... cookies:)`）。
     */
    @Throws(OAuthException::class)
    fun exchangeToken(
        provider: OAuthProviderId,
        code: String,
        state: String,
        cookieHeader: String? = null,
    ): OAuthCredentialStore.Credentials {
        val endpoint = "${trimTrailingSlash(config.tokenOrigin)}/api/v1/oauth/token"
        val payload = JSONValue.Obj(
            mapOf(
                "provider" to JSONValue.Str(provider.rawValue),
                "code" to JSONValue.Str(code),
                "redirect_uri" to JSONValue.Str(config.redirectUri),
                "state" to JSONValue.Str(state),
            ),
        ).data ?: throw OAuthException.ExchangeFailed("无效的请求体")

        val builder = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)

        val (body, status) = try {
            httpClient.newCall(builder.build()).execute().use { response ->
                response.body?.string() to response.code
            }
        } catch (err: Exception) {
            throw OAuthException.ExchangeFailed("网络错误：${err.message ?: err::class.simpleName}")
        }
        if (status !in 200..299) throw OAuthException.ExchangeFailed("HTTP $status")
        return normalizeTokenResponse(body, provider)
    }

    /**
     * 归一化令牌回执（`normalizeTokenResponse`）：code≠0 即失败；token 取 `data.token`；
     * accessToken 按 provider 取 `data.zai|bigmodel.access_token`。
     */
    fun normalizeTokenResponse(body: String?, provider: OAuthProviderId): OAuthCredentialStore.Credentials {
        val json = body?.let { runCatching { JSONValue.parse(it) }.getOrNull() }
            ?: throw OAuthException.ExchangeFailed("响应不是 JSON")
        val code = json["code"]?.intValue
        if (code != null && code != 0) {
            throw OAuthException.ExchangeFailed(json["msg"]?.stringValue ?: "OAuth token exchange failed")
        }
        val data = json["data"] ?: throw OAuthException.ExchangeFailed("响应缺少 data")
        val jwt = data["token"]?.stringValue?.trim().orEmpty()
        if (jwt.isEmpty()) throw OAuthException.ExchangeFailed("响应缺少 data.token")

        val accessToken = when (provider) {
            OAuthProviderId.ZAI -> data["zai"]?.objectValue?.get("access_token")?.stringValue
            OAuthProviderId.BIGMODEL -> data["bigmodel"]?.objectValue?.get("access_token")?.stringValue
        }?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            throw OAuthException.ExchangeFailed("响应缺少 data.${provider.rawValue}.access_token")
        }

        val expiresIn = data["expires_in"]?.doubleValue
        val expiresAt = if (expiresIn != null && expiresIn.isFinite() && expiresIn > 0) {
            System.currentTimeMillis() + (expiresIn * 1000).toLong()
        } else {
            null
        }

        val userInfo = data["user"]?.let { toUserInfo(it) }
            ?: OAuthUserInfo(id = "unknown", username = "user", displayName = "User", avatarUrl = null)

        return OAuthCredentialStore.Credentials(
            tokenSet = OAuthTokenSet(accessToken = accessToken, zcodeJwtToken = jwt, expiresAt = expiresAt),
            userInfo = userInfo,
        )
    }

    /** `toUserInfo`：主形态 user.id/username/displayName；回退形态 user_id/name/email/avatar。 */
    fun toUserInfo(value: JSONValue): OAuthUserInfo? {
        val user = value.objectValue ?: return null
        val id = user["id"]?.stringValue
        val username = user["username"]?.stringValue
        val displayName = user["displayName"]?.stringValue
        if (id != null && username != null && displayName != null) {
            return OAuthUserInfo(
                id = id,
                username = username,
                displayName = displayName,
                avatarUrl = user["avatarUrl"]?.stringValue,
            )
        }

        val fallbackId = user["user_id"]?.stringValue ?: "unknown"
        val name = user["name"]?.stringValue?.trim().orEmpty()
        val email = user["email"]?.stringValue?.trim().orEmpty()
        val fallbackUsername = when {
            name.isNotEmpty() -> name
            email.isNotEmpty() -> email
            else -> fallbackId
        }
        if (name.isEmpty() && email.isEmpty() && fallbackId == "unknown") return null
        return OAuthUserInfo(
            id = fallbackId,
            username = fallbackUsername,
            displayName = fallbackUsername,
            avatarUrl = normalizeAvatarUrl(user["avatar"]?.stringValue),
        )
    }

    /** 头像归一化：data:image / http(s) 直接用；裸 base64 补 data URI；其余原样。 */
    fun normalizeAvatarUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("data:image/") || lower.startsWith("http://") || lower.startsWith("https://")) {
            return trimmed
        }
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        if (trimmed.length >= 16 && trimmed.all { it in allowed }) {
            return "data:image/png;base64,$trimmed"
        }
        return trimmed
    }

    private fun queryParams(url: String): Map<String, String> {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun trimTrailingSlash(value: String): String =
        if (value.endsWith("/")) value.dropLast(1) else value

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

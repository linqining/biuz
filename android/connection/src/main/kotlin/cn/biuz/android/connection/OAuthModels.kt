package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import java.util.Base64

/**
 * OAuth 账户层模型（iOS `Services/OAuth/ZaiOAuthProvider.swift:5-132` 同构移植）。
 *
 * 分层：账户凭据（本文件 + [OAuthCredentialStore]）与连接凭据（`ServerConfig.token/relay`）
 * 是**两套独立凭据**——退出登录只清账户层，不动已保存服务器（iOS `AppSession.swift:1508-1514`）。
 */
enum class OAuthProviderId(val rawValue: String) {
    ZAI("zai"),
    BIGMODEL("bigmodel");

    /** 渠道徽章文案（iOS `OAuthProviderID.label`）。 */
    val label: String
        get() = when (this) {
            ZAI -> "ZAI · chat.z.ai"
            BIGMODEL -> "BigModel"
        }

    companion object {
        fun fromRaw(value: String?): OAuthProviderId? =
            entries.firstOrNull { it.rawValue == value }
    }
}

/** tokenSet：accessToken（Provider access_token）/ zcodeJwtToken / expiresAt（epoch 毫秒）。 */
data class OAuthTokenSet(
    val accessToken: String,
    val zcodeJwtToken: String,
    val expiresAt: Long? = null,
)

/** userInfo（iOS `OAuthUserInfo`；`toUserInfo` 兼容 id/user_id、name/email、avatar/avatarUrl）。 */
data class OAuthUserInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

/**
 * OAuth 错误五态（iOS `OAuthError`）：取消 / 授权服务器 error / state 校验 / 交换失败 / 凭据写入失败。
 * Kotlin 侧用异常子类承载（`RPCSerialization` 的 Rejection 同先例：message 为原文）。
 */
sealed class OAuthException(message: String) : Exception(message) {
    /** 用户取消（页面返回/关闭授权） */
    class UserCancelled : OAuthException("你取消了本次授权")
    /** 授权服务器返回 error 参数 */
    class ServerError(detail: String) : OAuthException(detail)
    /** state 缺失或不匹配 */
    class StateMismatch : OAuthException("授权回调校验失败")
    /** 交换失败（超时 / HTTP 非 2xx / code≠0） */
    class ExchangeFailed(detail: String) : OAuthException(detail)
    /** 凭据写入失败 */
    class CredentialWriteFailed : OAuthException("凭据写入失败")
}

/** 回调参数（`parseCallbackParams`：code / authCode 双兼容；state 必填）。 */
data class OAuthCallbackParams(
    val code: String?,
    val error: String?,
    val state: String,
)

/**
 * 端点配置（iOS `OAuthConfig.resolve`，缺省值与 web `webZaiOAuthConfig.ts` 一致）。
 * 启动参数覆盖用于 E2E/联调指向本地替身（`-ZCodeOAuthZaiOrigin` 等）。
 */
data class OAuthConfig(
    val zaiOrigin: String = "https://chat.z.ai",
    val tokenOrigin: String = "https://zcode.z.ai",
    val bigModelOrigin: String = "https://bigmodel.cn",
    val clientId: String = "client_P8X5CMWmlaRO9gyO-KSqtg",
    val bigModelAppId: String = "zcode",
    /** 服务端按 client_id 校验 redirect_uri：复用 web 客户端注册的回调地址。 */
    val redirectUri: String = "https://zcode.z.ai/cn/share/callback",
) {
    val tokenOriginHost: String
        get() = runCatching { java.net.URI(tokenOrigin).host }.getOrNull() ?: tokenOrigin

    companion object {
        /** 从启动参数解析（`-Name value` 形式，与 iOS `resolve(launchArguments:)` 同构）。 */
        fun resolve(arguments: List<String> = emptyList()): OAuthConfig {
            fun arg(name: String): String? {
                val index = arguments.indexOf("-$name")
                if (index < 0 || index + 1 >= arguments.size) return null
                return arguments[index + 1]
            }
            val defaults = OAuthConfig()
            return OAuthConfig(
                zaiOrigin = arg("ZCodeOAuthZaiOrigin") ?: defaults.zaiOrigin,
                tokenOrigin = arg("ZCodeOAuthTokenOrigin") ?: defaults.tokenOrigin,
                bigModelOrigin = arg("ZCodeOAuthBigModelOrigin") ?: defaults.bigModelOrigin,
                clientId = arg("ZCodeOAuthClientID") ?: defaults.clientId,
                bigModelAppId = arg("ZCodeOAuthBigModelAppID") ?: defaults.bigModelAppId,
                redirectUri = arg("ZCodeOAuthRedirectURI") ?: defaults.redirectUri,
            )
        }
    }
}

/**
 * state 编解码（iOS `OAuthStateCodec` / web `oauthStateCodec.ts` 同构）。
 *
 * **服务端令牌交换会解析并校验 state 结构**：裸随机串会得到 `{"detail":"invalid state"}`，
 * 因此必须是 base64url(JSON{"nonce":...})、去 padding。
 */
object OAuthStateCodec {

    fun buildState(nonce: String): String {
        // 复用 protocol 的序列化器，避免手拼 JSON（键序/转义口径与全仓一致）
        val payload = JSONValue.Obj(mapOf("nonce" to JSONValue.Str(nonce)))
        val bytes = payload.data ?: return nonce
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 解析回调 state 取 nonce（URL 转义 → base64url 解码 → JSON）；失败返回 null。 */
    fun nonce(state: String): String? {
        val decodedUrl = runCatching { java.net.URLDecoder.decode(state, "UTF-8") }.getOrNull() ?: state
        val base64 = decodedUrl.replace('-', '+').replace('_', '/')
        val padded = base64 + "=".repeat((4 - base64.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        val json = runCatching { JSONValue.parse(bytes) }.getOrNull() ?: return null
        val nonce = json["nonce"]?.stringValue
        return if (!nonce.isNullOrEmpty()) nonce else null
    }
}

package cn.biuz.android.relay

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.relay.RelayLinkConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Relay V2 传输契约（iOS `Services/Relay/RelayTransport.swift:262-379` 同构移植）。
 *
 * 与 V1 的差别（relay-v2-pairing.md）：
 * 1. 建连 URL 是 `wss://<host>/api/v1/remote/ws2?token=<一次性 token>&mid=<mid>`；
 * 2. 每次（重）连都要用 Z.ai JWT 现换一次性 token（token 绑定用户+角色，60s 有效、WS Upgrade 前原子消费）；
 * 3. `auth_init` **不带 role**，`meta` 上报终端身份，`client_ts` 为**秒**（V1 是毫秒，V2 严格拒绝毫秒）；
 * 4. 心跳 5s ±1s、ACK 超时 15s（V1 是 10s ±2s / 30s）。
 *
 * 本文件只放纯构造 + 一次 HTTP 调用，便于单测；状态机仍在 [RelayTransport]。
 */
object RelayV2 {

    /** V2 建连 URL：替换 `token`/`mid` 查询项后追加本次新 token。 */
    fun buildSocketUrl(baseWssUrl: String, token: String, mid: String?): String {
        val base = if (baseWssUrl.contains("://")) baseWssUrl else "wss://$baseWssUrl"
        val queryIndex = base.indexOf('?')
        val prefix = if (queryIndex >= 0) base.substring(0, queryIndex) else base
        val existing = if (queryIndex >= 0) base.substring(queryIndex + 1) else ""
        val kept = if (existing.isEmpty()) {
            emptyList()
        } else {
            existing.split('&').filter { pair ->
                pair.isNotEmpty() && !pair.startsWith("token=") && !pair.startsWith("mid=")
            }
        }
        val params = ArrayList<String>(kept)
        params.add("token=" + percentEncode(token))
        if (!mid.isNullOrEmpty()) params.add("mid=" + percentEncode(mid))
        return prefix + "?" + params.joinToString("&")
    }

    /** V2 `auth_init`：无 role，meta 上报终端身份，client_ts 为秒。 */
    fun authInit(
        config: RelayLinkConfig,
        clientTsSeconds: Long,
        platform: String,
        appVersion: String,
        clientName: String,
    ): LinkedHashMap<String, JSONValue> = linkedMapOf(
        "type" to JSONValue.Str("auth_init"),
        "device_sid" to JSONValue.Str(config.deviceSid),
        "meta" to JSONValue.Obj(
            linkedMapOf(
                "platform" to JSONValue.Str(platform),
                "version" to JSONValue.Str(appVersion),
                "name" to JSONValue.Str(clientName),
            ),
        ),
        "client_ts" to jsonLong(clientTsSeconds),
    )

    /** V2 心跳三元组（interval / jitter / ack 超时）。 */
    fun heartbeatSeconds(isV2: Boolean): Triple<Double, Double, Double> =
        if (isV2) Triple(5.0, 1.0, 15.0) else Triple(10.0, 2.0, 30.0)

    /** 令牌回执解析：`{code:0,data:{token:"…"}}`；其余形态返回 null。 */
    fun parseTokenResponse(body: String?): String? {
        val json = body?.let { runCatching { JSONValue.parse(it) }.getOrNull() } ?: return null
        val code = json["code"]?.intValue
        if (code != null && code != 0) return null
        val token = json["data"]?.objectValue?.get("token")?.stringValue?.trim().orEmpty()
        return if (token.isEmpty()) null else token
    }

    /**
     * JWT → `POST {origin}/api/v1/remote/token {role:"terminal"}` → 一次性 token。
     * 401 单独上抛 [RelayTokenException.AuthRequired]，让 UI 提示重新登录。
     */
    @Throws(RelayTokenException::class)
    fun fetchToken(
        httpOrigin: String,
        jwt: String,
        httpClient: OkHttpClient = defaultHttpClient(),
    ): String {
        val base = if (httpOrigin.endsWith("/")) httpOrigin.dropLast(1) else httpOrigin
        val request = Request.Builder()
            .url("$base/api/v1/remote/token")
            .header("Authorization", "Bearer $jwt")
            .post("""{"role":"terminal"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val (body, status) = try {
            httpClient.newCall(request).execute().use { response ->
                response.body?.string() to response.code
            }
        } catch (err: Exception) {
            throw RelayTokenException.RequestFailed("中继 token 请求失败：${err.message ?: err::class.simpleName}")
        }
        if (status == 401) {
            throw RelayTokenException.AuthRequired("云中继登录已失效，请重新登录 Z.ai 账号")
        }
        if (status !in 200..299) {
            throw RelayTokenException.RequestFailed("中继 token 请求失败（HTTP $status）")
        }
        return parseTokenResponse(body)
            ?: throw RelayTokenException.RequestFailed("中继 token 回执无效")
    }

    fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun percentEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun jsonLong(value: Long): JSONValue =
        if (value in Int.MIN_VALUE..Int.MAX_VALUE) JSONValue.Int(value.toInt()) else JSONValue.Double(value.toDouble())
}

/** V2 token 获取失败分类（UI 据此提示「重新登录」或「网络重试」）。 */
sealed class RelayTokenException(message: String) : Exception(message) {
    class AuthRequired(detail: String) : RelayTokenException(detail)
    class RequestFailed(detail: String) : RelayTokenException(detail)
}

package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 会话分享预览（009-分享落地）。
 *
 * 契约来源：iOS `RemoteExtrasViews.swift:34-110`（SharePreviewStore）——
 * `GET https://zcode.z.ai/api/v1/shares/{code}/preview`，可选 `Authorization: Bearer <zcodeJwtToken>`
 * （**公开分享未登录也可看**，所以无 token 时裸发）。
 *
 * 行模型宽容：`text` / `inputText` / `summaryText` 三键任一；单行认不出只丢该行并计数，
 * 不整份失败（桌面传输层不理解行语义，同口径）。
 */
data class SharePreviewMeta(
    val title: String? = null,
    val ownerName: String? = null,
    val createdAtMs: Long? = null,
)

data class SharePreviewRow(
    val kind: String,
    val text: String,
    val isUser: Boolean,
)

data class SharePreview(
    val meta: SharePreviewMeta,
    val rows: List<SharePreviewRow>,
    val unsupportedRowCount: Int,
)

/** 拉取失败分类：未授权（401/403）与其它 HTTP / 网络错误要区分给文案。 */
sealed class SharePreviewError(message: String) : Exception(message) {
    /** 401/403：私有分享需要登录（或当前账号无权） */
    class Unauthorized(val status: Int) : SharePreviewError("该分享需要登录后查看（HTTP $status）")
    /** 其它非 2xx */
    class Http(val status: Int) : SharePreviewError("分享预览获取失败（HTTP $status）· 链接可能已失效")
    /** 网络/解析异常 */
    class Network(detail: String) : SharePreviewError(detail)
}

class SharePreviewClient(
    private val endpointOrigin: String = "https://zcode.z.ai",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    @Throws(SharePreviewError::class)
    suspend fun load(shareCode: String, jwt: String? = null): SharePreview = withContext(Dispatchers.IO) {
        val base = if (endpointOrigin.endsWith("/")) endpointOrigin.dropLast(1) else endpointOrigin
        val builder = Request.Builder()
            .url("$base/api/v1/shares/$shareCode/preview")
            .get()
        if (!jwt.isNullOrBlank()) builder.header("Authorization", "Bearer $jwt")
        val (body, status) = try {
            httpClient.newCall(builder.build()).execute().use { it.body?.string() to it.code }
        } catch (err: Exception) {
            throw SharePreviewError.Network("网络错误：${err.message ?: err::class.simpleName}")
        }
        if (status == 401 || status == 403) throw SharePreviewError.Unauthorized(status)
        if (status !in 200..299) throw SharePreviewError.Http(status)
        parse(body) ?: throw SharePreviewError.Network("分享预览解析失败 · 响应不是 JSON 对象")
    }

    companion object {
        /** 解析回执；顶层不是对象返回 null（其余字段全宽容）。 */
        fun parse(body: String?): SharePreview? {
            val json = body?.let { runCatching { JSONValue.parse(it) }.getOrNull() } ?: return null
            val dict = json.objectValue ?: return null
            val share = dict["share"]?.objectValue
            val meta = SharePreviewMeta(
                title = share?.get("title")?.stringValue,
                ownerName = share?.get("ownerName")?.stringValue
                    ?: share?.get("owner")?.objectValue?.get("displayName")?.stringValue,
                createdAtMs = share?.get("createdAt")?.intValue?.toLong(),
            )
            val rows = ArrayList<SharePreviewRow>()
            var unsupported = 0
            (dict["rows"]?.arrayValue ?: emptyList()).forEach { item ->
                val d = item.objectValue
                if (d == null) {
                    unsupported += 1
                    return@forEach
                }
                val text = d["text"]?.stringValue
                    ?: d["inputText"]?.stringValue
                    ?: d["summaryText"]?.stringValue
                if (text.isNullOrEmpty()) {
                    unsupported += 1
                    return@forEach
                }
                val kind = d["kind"]?.stringValue ?: "unknown"
                rows.add(SharePreviewRow(kind = kind, text = text, isUser = kind == "userInput"))
            }
            return SharePreview(meta = meta, rows = rows, unsupportedRowCount = unsupported)
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        /**
         * 从深链解析 share code。三种形态：
         * - 自定义 scheme：`zcode://share/<code>`（host=share，path=/<code>）
         * - 网页路径：`https://<host>/share/<code>`
         * - 带地区段：`https://<host>/cn/share/<code>`
         * 回调路径（`/cn/share/callback`）不是分享 code，返回 null。
         */
        fun shareCodeFrom(link: String?): String? {
            val raw = link?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val afterScheme = raw.substringAfter("://", "")
            if (afterScheme.isEmpty()) return null
            val authorityAndPath = afterScheme.substringBefore('?')
            val slash = authorityAndPath.indexOf('/')
            val host = (if (slash >= 0) authorityAndPath.substring(0, slash) else authorityAndPath).lowercase()
            val path = if (slash >= 0) authorityAndPath.substring(slash + 1) else ""
            val segments = path.split('/').filter { it.isNotEmpty() }
            val code = when {
                host == "share" -> segments.firstOrNull()          // zcode://share/<code>
                segments.firstOrNull() == "share" -> segments.getOrNull(1)
                segments.firstOrNull() == "cn" && segments.getOrNull(1) == "share" -> segments.getOrNull(2)
                else -> null
            }
            return code?.takeIf { it.isNotEmpty() && it != "callback" }
        }
    }
}

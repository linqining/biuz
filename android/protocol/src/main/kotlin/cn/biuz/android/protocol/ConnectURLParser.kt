package cn.biuz.android.protocol

import cn.biuz.android.protocol.relay.RelayLinkConfig
import java.io.ByteArrayOutputStream

// 直译自 ios/ZCodeMobileApp/Sources/Services/Remote/ConnectURLParser.swift（186 行）。
// 机械适配（逐处注明）：
// - Foundation URLComponents → 本文件手写 UrlComponents（最小定界解析：scheme://host/
//   path/query；query 项百分号解码为宽容口径——合法 %XX 解码、非法序列原样保留，
//   对应 Foundation queryItems 的 percent-decoded 值语义；「hash 为 URL 解码后的
//   base64 原字符串」即由该解码承载）。+ 号不解为空格（queryItems ≠ 表单编码口径）。
// - Character.isWhitespace 与 Swift Character.isWhitespace（Unicode White_Space 属性）
//   的差集仅 NBSP 族三个字符，isUnicodeWhitespace() 补齐。
// - removingPercentEncoding（严格口径：非法 % 序列 → nil）→ percentDecodeStrictOrNull。
// - Swift Result<Parsed, ParseError> → sealed ParseResult（success/failure 同构）。
// - String(localized:) → 同串字面量（同 Models.kt 文件头约定）。

// MARK: - 连接链接解析（L1-K 完整链接自动拆解：scheme ∈ http/https/ws/wss，token 段剥离）

object ConnectURLParser {

    /** Swift `struct Parsed: Equatable`（:7-12）。 */
    data class Parsed(
        val host: String,
        val port: Int,
        val useTLS: Boolean,
        val token: String?,
    )

    /** Swift `enum ParseError`（:14-27）。 */
    enum class ParseError {
        missingScheme, // 缺 scheme
        missingPort, // 缺端口
        unrecognizable, // 无法识别
        ;

        val message: String
            get() = when (this) {
                missingScheme, unrecognizable ->
                    "无法识别的地址：请粘贴完整链接或输入 host:port（缺 scheme/端口）"
                missingPort ->
                    "无法识别的地址：缺少端口（桌面端默认 3030）"
            }
    }

    /** Swift `Result<Parsed, ParseError>` 对应物（Kotlin Result 需 Throwable，sealed 同构更直译）。 */
    sealed class ParseResult {
        data class Success(val parsed: Parsed) : ParseResult()
        data class Failure(val error: ParseError) : ParseResult()
    }

    // MARK: 云中继链接（remote/v4 配对链接；逆向结论⑦）

    /**
     * 识别中继链接：`https://<host>/remote/v4?sid=…&hash=…&mid=…&name=…&app_version=…`
     * → RelayLinkConfig{wssURL(`wss://<host>/ws?mid=…`), machineName}。（:31-42）
     * - sid/hash 不进 WS URL（仅用于 auth 帧）；hash 为 URL 解码后的 base64 原字符串（HMAC 密钥）
     * - wssURL 保留 mid 参数语义（桌面端 _Vn.connect 仅设置 mid；无 mid 时不带参数也可 101）
     */
    fun parseRelayLink(input: String): RelayLinkConfig? {
        val trimmed = input.trimWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return null
        for (token in trimmed.splitWhereUnicodeWhitespace()) {
            parseRelayLinkToken(token)?.let { return it }
        }
        return parseRelayLinkToken(trimmed)
    }

    private fun parseRelayLinkToken(piece: String): RelayLinkConfig? {
        val components = UrlComponents.parse(piece) ?: return null
        if (components.scheme?.lowercase() != "https") return null
        if (!components.path.orEmpty().lowercase().startsWith("/remote/")) return null
        if (piece.lowercase().contains("token=")) return null
        val query = components.queryItems
        fun value(name: String): String? =
            query.firstOrNull { it.name.lowercase() == name }?.value?.takeIf { it.isNotEmpty() }
        val sid = value("sid") ?: return null
        val hash = value("hash")
        val mid = value("mid")

        val host = components.host ?: return null
        // V1（有 hash）：wss://host/ws?mid=…
        // V2（无 hash，3.16.2 起）：wss://host/api/v1/remote/ws2，token 在建连时追加
        //   （iOS RelayLink.swift:9-24 / relay-v2-pairing.md §WebSocket）
        val isV2 = hash == null
        var wss = if (isV2) "wss://$host/api/v1/remote/ws2" else "wss://$host/ws"
        if (mid != null && !isV2) {
            // Swift 经 URLComponents 重建 query（Foundation 按需重编码）；此处对解码值
            // 做 unreserved 全编码——服务端解码同值，含特殊字符 mid 反而更保守。
            wss += "?mid=" + percentEncodeQueryValue(mid)
        }
        return RelayLinkConfig(
            wssURL = wss,
            machineName = value("name"),
            deviceSid = sid,
            passHash = hash,
            desktopAppVersion = value("app_version"),
            protocolVersion = if (isV2) 2 else 1,
            deviceMid = mid,
        )
    }

    /** 剪贴板/输入框统一识别形态：中继链接优先，其次局域网直连链接（:75-99）。 */
    sealed class ParsedLink {
        data class Relay(val link: RelayLinkConfig) : ParsedLink()
        data class Direct(val parsed: Parsed) : ParsedLink()

        val isRelay: Boolean
            get() = this is Relay

        /** 横幅 meta 行（中继：机器名 · 云中继） */
        val relaySummary: String
            get() {
                val link = (this as? Relay)?.link ?: return ""
                return "${link.machineName ?: "桌面端"} · 云中继 · ${link.endpointHost ?: ""}"
            }

        val directHost: String
            get() = (this as? Direct)?.parsed?.host ?: ""

        val directPort: Int
            get() = (this as? Direct)?.parsed?.port ?: 0
    }

    fun extractLink(from: String): ParsedLink? {
        parseRelayLink(from)?.let { return ParsedLink.Relay(it) }
        extractConnectLink(from)?.let { return ParsedLink.Direct(it) }
        return null
    }

    /**
     * 解析规则：（:111-168）
     * - 完整链接：scheme://host:port/?token=… → 地址取 host:port，token 段剥离
     * - 仅 host:port：缺省补 http://（端口 3030 缺省）
     * - scheme 支持 http/https/ws/wss（https/wss 走反向代理）
     */
    fun parse(input: String): ParseResult {
        val trimmed = input.trimWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return ParseResult.Failure(ParseError.unrecognizable)

        // 1) 尝试完整 URL（带 scheme）
        var candidate = trimmed
        if (candidate.lowercase().startsWith("http://") || candidate.lowercase().startsWith("https://") ||
            candidate.lowercase().startsWith("ws://") || candidate.lowercase().startsWith("wss://")
        ) {
            var useTLS = false
            var schemePart = ""
            if (candidate.lowercase().startsWith("https://")) {
                useTLS = true; schemePart = "https://"
            } else if (candidate.lowercase().startsWith("wss://")) {
                useTLS = true; schemePart = "wss://"
            } else if (candidate.lowercase().startsWith("http://")) {
                schemePart = "http://"
            } else {
                schemePart = "ws://"
            }
            candidate = candidate.substring(schemePart.length)

            // 剥离 path/query；token 从 query 提取
            var token: String? = null
            val queryIndex = candidate.indexOf('?')
            if (queryIndex >= 0) {
                val query = candidate.substring(queryIndex + 1)
                candidate = candidate.substring(0, queryIndex)
                for (pair in query.split('&')) {
                    if (pair.isEmpty()) continue
                    val kv = pair.split('=', limit = 2).filter { it.isNotEmpty() }
                    if (kv.size == 2 && kv[0] == "token") {
                        token = percentDecodeStrictOrNull(kv[1]) ?: kv[1]
                    }
                }
            }
            candidate = candidate.trim { it == '/' }

            val hostPort = candidate.split(":") // omittingEmptySubsequences: false
            if (hostPort.isEmpty() || hostPort[0].isEmpty()) {
                return ParseResult.Failure(ParseError.unrecognizable)
            }
            val host = hostPort[0]
            val port = hostPort.getOrNull(1)?.toIntOrNull()
            if (hostPort.size < 2 || port == null || port <= 0 || port >= 65536) {
                return ParseResult.Failure(ParseError.missingPort)
            }
            return ParseResult.Success(Parsed(host = host, port = port, useTLS = useTLS, token = token))
        }

        // 2) 仅 host:port → 补 http://；缺端口按校验态报错（L1-K 样例：「192.168.1.24」→ 缺端口）
        val hostPort = trimmed.split(":") // omittingEmptySubsequences: false
        if (hostPort.size == 1 && hostPort[0].isNotEmpty()) {
            return ParseResult.Failure(ParseError.missingPort)
        }
        val port = hostPort.getOrNull(1)?.toIntOrNull()
        if (hostPort.size >= 2 && port != null && port > 0 && port < 65536 && hostPort[0].isNotEmpty()) {
            return ParseResult.Success(Parsed(host = hostPort[0], port = port, useTLS = false, token = null))
        }
        return ParseResult.Failure(ParseError.unrecognizable)
    }

    /** 从剪贴板文本识别连接链接形态（http(s)://…/?token=…）（:170-185）。 */
    fun extractConnectLink(from: String): Parsed? {
        for (token in from.splitWhereUnicodeWhitespace()) {
            if (token.lowercase().startsWith("http://") || token.lowercase().startsWith("https://") ||
                token.lowercase().startsWith("ws://") || token.lowercase().startsWith("wss://")
            ) {
                val parsed = (parse(token) as? ParseResult.Success)?.parsed
                if (parsed != null && parsed.token != null) {
                    return parsed
                }
            }
        }
        val whole = (parse(from) as? ParseResult.Success)?.parsed
        if (whole != null && whole.token != null) {
            return whole
        }
        return null
    }
}

// MARK: - Foundation 适配工具（本文件 + relay/RelayLink.kt 共用；internal = 模块内可见）

/** Swift `Character.isWhitespace`（Unicode White_Space 属性）：补 Java 差集（NBSP 族三字符）。 */
internal fun Char.isUnicodeWhitespace(): Boolean =
    Character.isWhitespace(this) || this == '\u00A0' || this == '\u2007' || this == '\u202F'

/** `trimmingCharacters(in: .whitespacesAndNewlines)` 对应物。 */
internal fun String.trimWhitespacesAndNewlines(): String = trim { it.isUnicodeWhitespace() }

/** `split(whereSeparator: { $0.isWhitespace })`（省略空段）对应物。 */
internal fun String.splitWhereUnicodeWhitespace(): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (ch in this) {
        if (ch.isUnicodeWhitespace()) {
            if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        } else {
            sb.append(ch)
        }
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

/**
 * `removingPercentEncoding` 严格口径：合法 %XX 解码；出现非法 % 序列返回 null
 * （调用方按 Swift `?? 原串` 兜底）。
 */
internal fun percentDecodeStrictOrNull(raw: String): String? {
    if (!raw.contains('%')) return raw
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '%') {
            // Foundation removingPercentEncoding 严格口径：任何非法 % 序列（含尾部
            // 残缺 "%"/"%4"）→ 整串 nil，不做「已合法段部分解码」（2026-10-09 本轮
            // 修正：尾残缺曾落字面 '%'，与 iOS「含无效序列则全弃」不一致）
            if (i + 3 > raw.length) return null
            val v = raw.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null && raw[i + 1].isHexAscii() && raw[i + 2].isHexAscii()) {
                bytes.write(v)
                i += 3
                continue
            }
            return null // 非法 % 序列
        }
        writeCharUtf8(ch, bytes)
        i++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

private fun Char.isHexAscii(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun writeCharUtf8(ch: Char, out: ByteArrayOutputStream) {
    if (ch.code < 0x80) {
        out.write(ch.code)
    } else {
        out.write(ch.toString().toByteArray(Charsets.UTF_8))
    }
}

/** RFC 3986 unreserved 全编码（wss URL 重建用；见 ConnectURLParser.parseRelayLinkToken 注）。 */
private fun percentEncodeQueryValue(value: String): String {
    val sb = StringBuilder()
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c in '0'.code..'9'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        ) {
            sb.append(c.toChar())
        } else {
            sb.append("%02X".format(c))
        }
    }
    return sb.toString()
}

/**
 * Foundation URLComponents 的最小等价承载（ConnectURLParser.swift 逐处引用面：
 * scheme/host/path/queryItems；RelayLinkConfig.endpointHost 复用取 host）。
 * 定界规则：`scheme://authority[path][?query][#fragment]`；query 项按 '&' 切、
 * 首个 '=' 分名值，名值均做宽容百分号解码（合法 %XX 解码、非法序列原样保留、
 * '+' 不转空格——与 queryItems percent-decoded 语义一致）。解析失败返回 null
 * （对应 `URLComponents(string:)` → nil）。
 */
internal class UrlComponents private constructor(
    val scheme: String?,
    val host: String?,
    val path: String?,
    val queryItems: List<QueryItem>,
) {
    /** Swift URLQueryItem 对应物（name/value 为百分号解码后值；无 '=' 时 value 为 null）。 */
    class QueryItem(val name: String, val value: String?)

    companion object {
        private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

        fun parse(raw: String): UrlComponents? {
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = raw.substring(0, schemeEnd)
            if (!SCHEME_REGEX.matches(scheme)) return null
            var rest = raw.substring(schemeEnd + 3)
            // fragment 剥离（本链路不消费，仅定界）
            val fragmentIdx = rest.indexOf('#')
            if (fragmentIdx >= 0) rest = rest.substring(0, fragmentIdx)

            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            val afterAuthority = rest.substring(authorityEnd)

            // host：去 userinfo（最后一个 '@' 之前）与 ':port' 段（本链路无 IPv6 字面量）
            val atIdx = authority.lastIndexOf('@')
            val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
            val colonIdx = hostPort.indexOf(':')
            var host: String? = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            if (host?.isEmpty() == true) host = null // 空authority（"https:///p"）→ Foundation host=nil

            val path = if (afterAuthority.startsWith("/")) afterAuthority.substringBefore('?') else null
            val rawQuery = afterAuthority.substringAfter('?', "")
            val queryItems = if (rawQuery.isEmpty()) emptyList() else parseQuery(rawQuery)
            return UrlComponents(scheme = scheme, host = host, path = path, queryItems = queryItems)
        }

        private fun parseQuery(rawQuery: String): List<QueryItem> {
            val items = ArrayList<QueryItem>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue // 空段跳过（Foundation 同口径）
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    items.add(QueryItem(decodeLenient(pair), null))
                } else {
                    items.add(
                        QueryItem(
                            name = decodeLenient(pair.substring(0, eq)),
                            value = decodeLenient(pair.substring(eq + 1)),
                        )
                    )
                }
            }
            return items
        }

        /** queryItems 口径的宽容解码：合法 %XX 解码，非法序列原样保留；'+' 不转空格。 */
        private fun decodeLenient(raw: String): String {
            if (!raw.contains('%')) return raw
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch == '%' && i + 3 <= raw.length &&
                    raw[i + 1].isHexAscii() && raw[i + 2].isHexAscii()
                ) {
                    bytes.write(raw.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    writeCharUtf8(ch, bytes)
                    i++
                }
            }
            // UTF-8 容忍解码（坏序列替换符通过——宽容解析原则，不炸）
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}

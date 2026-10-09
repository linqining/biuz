package cn.biuz.android.protocol

import java.io.ByteArrayOutputStream

// 直译自 ios/ZCodeMobileApp/Sources/Services/RPC/RPCSerialization.swift（377 行）。
// 上游：packages/rpc/src/serialization.ts（VQL / 类型标签 / 嵌套 Uint8Array 标记）。
// 唯一环境适配：Swift Codable（Foundation JSONEncoder/JSONDecoder）由本文件手写 JsonCodec
// 等价承载——protocol 模块依赖骨架仅 kotlin-stdlib + kotlinx-coroutines（禁改 build 文件）。
// 其余逻辑与 Swift 原文逐行对应，未增删协议语义。

/** Kotlin 无 inout：Swift `offset: inout Int`（RPCSerialization.swift:236/:321）的对应物。 */
class ReadCursor(@JvmField var pos: Int)

/** JSON 解析失败（Foundation JSONDecoder 抛错的对应物；调用方按 Swift `try?` 口径兜底为 null）。 */
class JsonParseException(message: String) : Exception(message)

/**
 * JSONValue —— 动态 JSON 值：宽松 JSON 容器（v4 协议未冻结，客户端按宽容解析）。
 * 【RPCSerialization.swift:6-137】
 *
 * 【下游统一引用】本类型与其 API 是后续 schema 引擎（ProtocolSchema/CommandSchemas）与
 * 边界组件（ReadOnlyGate/Store 层）的唯一 JSON 形态源，API 面小而稳定：
 * - 构造：Null/Bool/Int/Double/Str/Arr/Obj 案构造器 + JSONObjectBuilder；
 * - 取值：stringValue/intValue/doubleValue/boolValue/arrayValue/objectValue；
 * - 宽容键读取：get(key) / get(index)（越界/缺键返回 null，对应 Swift 下标 `subscript(safe:)`）；
 * - 序列化：parse(bytes/text)（Codable init(from:) 对应物）/ data（encode(to:) 对应物）。
 * 下游一律引用本类型承载 JSON，禁止自备第三种 JSON 树或引入其他 JSON 库。
 *
 * 对端 schema 处于草稿态（core.ts「未冻结」声明），解析失败不应整帧丢弃。
 * Int 值域口径：Kotlin Int 为 32 位（Codable 主路 Swift 原文即把 Int32 值域外整数落
 * .double，RPCSerialization.swift:70-71；线缆侧 (data|0)===data 亦为 32 位口径）。
 */
sealed class JSONValue {
    data object Null : JSONValue()

    data class Bool(val value: Boolean) : JSONValue()

    data class Int(val value: kotlin.Int) : JSONValue()

    data class Double(val value: kotlin.Double) : JSONValue()

    data class Str(val value: String) : JSONValue()

    data class Arr(val items: List<JSONValue>) : JSONValue()

    /** object 用 LinkedHashMap 承载（插入序确定；Foundation 字典序未定义，值内容等价）。 */
    data class Obj(val fields: Map<String, JSONValue>) : JSONValue()

    val isNull: Boolean
        get() = this is Null

    val stringValue: String?
        get() = (this as? Str)?.value

    /** 宽容整数读取：int 直取；integral double 取整；数字字符串可解析则取（:22-29）。 */
    val intValue: kotlin.Int?
        get() = when (this) {
            is Int -> value
            is Double -> if (value == roundedHalfAway(value)) value.toInt() else null
            is Str -> value.toIntOrNull()
            else -> null
        }

    val doubleValue: kotlin.Double?
        get() = when (this) {
            is Int -> value.toDouble()
            is Double -> value
            else -> null
        }

    val boolValue: Boolean?
        get() = (this as? Bool)?.value

    val arrayValue: List<JSONValue>?
        get() = (this as? Arr)?.items

    val objectValue: Map<String, JSONValue>?
        get() = (this as? Obj)?.fields

    /** 对象键读取（`subscript(key:)`，:54-56）。 */
    operator fun get(key: String): JSONValue? = objectValue?.get(key)

    /** 数组下标读取（`subscript(safe index:)`，:58-60 + :139-143；越界返回 null）。 */
    operator fun get(index: kotlin.Int): JSONValue? = arrayValue?.getOrNull(index)

    /** encode(to:) 对应物（Foundation JSONEncoder；非有限 double → null，见 JsonCodec）。 */
    val data: ByteArray?
        get() = JsonCodec.writeOrNull(this)

    companion object {
        /** Codable `init(from:)` 对应物；失败抛 [JsonParseException]（调用方按 `try?` 兜底）。 */
        fun parse(data: ByteArray): JSONValue = JsonCodec.parse(data)

        fun parse(text: String): JSONValue = parse(text.toByteArray(Charsets.UTF_8))

        /**
         * `from(any:)` 对应物（:98-120）。Foundation NSNumber 桥接在 Kotlin 无对应物，
         * 按 Kotlin 类型直映射；Swift 原文 BOOL 先于整数判定（NSNumber 桥接陷阱）在 Kotlin
         * 无歧义，注释保留为出处。Swift `case let n as NSNumber` 的 64 位整数落 .int 与
         * 本 .int 的 Int32 值域不一致——越界 Long 按 Codable 主路口径落 .double（32 位线缆口径）。
         */
        fun fromAny(value: Any?): JSONValue? {
            return when (value) {
                null -> Null // NSNull 对应
                is Boolean -> Bool(value)
                is kotlin.Int -> Int(value)
                is kotlin.Long ->
                    if (value >= kotlin.Int.MIN_VALUE && value <= kotlin.Int.MAX_VALUE) {
                        Int(value.toInt())
                    } else {
                        Double(value.toDouble())
                    }
                is kotlin.Short -> Int(value.toInt())
                is kotlin.Byte -> Int(value.toInt())
                is kotlin.Float -> Double(value.toDouble())
                is kotlin.Double -> Double(value)
                is String -> Str(value)
                is List<*> -> Arr(value.mapNotNull { fromAny(it) }) // compactMap：不可转换元素跳过
                is Map<*, *> -> {
                    // Swift `case let o as [String: Any]`：键非 String 时整体 cast 失败返回 nil（同口径）
                    for (k in value.keys) if (k !is String) return null
                    val fields = LinkedHashMap<String, JSONValue>()
                    for ((k, v) in value) {
                        val converted = fromAny(v) ?: continue // Swift：不可转换值跳过（if let v）
                        fields[k as String] = converted
                    }
                    Obj(fields)
                }
                else -> null
            }
        }
    }
}

/** Swift Double.rounded()（.5 远离零）。kotlin.math.round 为向 +∞ 取整，不复用。 */
private fun roundedHalfAway(x: kotlin.Double): kotlin.Double {
    val f = kotlin.math.floor(kotlin.math.abs(x) + 0.5)
    return if (x < 0) -f else f
}

/**
 * 手写 JSON 编解码（Foundation JSONEncoder/JSONDecoder 的等价承载）。
 * - 解析：RFC 8259；无小数/指数的整数先按 Int64 读，Int32 值域内落 .int、越界落 .double
 *   （RPCSerialization.swift:70-71 口径）；有小数/指数恒落 .double；Int64 溢出 → Double
 *   （Foundation decode(Int64) 失败回落 decode(Double) 同口径）。
 * - 序列化：键序 = 插入序（Foundation 字典序未定义，Kotlin 取确定性插入序，值内容不受影响）；
 *   整值 double 以整型字面量写出（NSNumber / JS JSON.stringify 同口径，1.0 → "1"）；
 *   非有限 double 返回 null（Foundation 抛错 → Swift `try?` 得 nil，同口径）。
 */
private object JsonCodec {

    fun parse(data: ByteArray): JSONValue {
        val parser = JsonParser(data)
        return parser.parseDocument()
    }

    fun writeOrNull(value: JSONValue): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!writeValue(value, out)) return null
        return out.toByteArray()
    }

    private fun writeValue(value: JSONValue, out: ByteArrayOutputStream): Boolean {
        return when (value) {
            is JSONValue.Null -> {
                out.write("null".toByteArray(Charsets.UTF_8))
                true
            }
            is JSONValue.Bool -> {
                out.write((if (value.value) "true" else "false").toByteArray(Charsets.UTF_8))
                true
            }
            is JSONValue.Int -> {
                out.write(value.value.toString().toByteArray(Charsets.UTF_8))
                true
            }
            is JSONValue.Double -> writeDouble(value.value, out)
            is JSONValue.Str -> {
                writeString(value.value, out)
                true
            }
            is JSONValue.Arr -> {
                out.write('['.code)
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.write(','.code)
                    if (!writeValue(item, out)) return false
                }
                out.write(']'.code)
                true
            }
            is JSONValue.Obj -> {
                out.write('{'.code)
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.write(','.code)
                    first = false
                    writeString(k, out)
                    out.write(':'.code)
                    if (!writeValue(v, out)) return false
                }
                out.write('}'.code)
                true
            }
        }
    }

    private fun writeDouble(d: kotlin.Double, out: ByteArrayOutputStream): Boolean {
        if (d.isNaN() || d.isInfinite()) return false
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) {
            out.write(d.toLong().toString().toByteArray(Charsets.UTF_8))
        } else {
            out.write(d.toString().toByteArray(Charsets.UTF_8))
        }
        return true
    }

    private fun writeString(s: String, out: ByteArrayOutputStream) {
        out.write('"'.code)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c == '"'.code || c == '\\'.code -> {
                    out.write('\\'.code)
                    out.write(c)
                }
                c == '\n'.code -> {
                    out.write('\\'.code)
                    out.write('n'.code)
                }
                c == '\r'.code -> {
                    out.write('\\'.code)
                    out.write('r'.code)
                }
                c == '\t'.code -> {
                    out.write('\\'.code)
                    out.write('t'.code)
                }
                c == '\b'.code -> {
                    out.write('\\'.code)
                    out.write('b'.code)
                }
                c == 0x0C -> {
                    out.write('\\'.code)
                    out.write('f'.code)
                }
                c < 0x20 -> out.write("\\u%04x".format(c).toByteArray(Charsets.UTF_8))
                else -> out.write(c)
            }
        }
        out.write('"'.code)
    }
}

/** RFC 8259 递归下降解析器（JsonCodec 专用；坏 UTF-8 序列以替换符宽容通过）。 */
private class JsonParser(private val data: ByteArray) {
    var pos = 0

    fun parseDocument(): JSONValue {
        skipWs()
        val value = parseValue()
        skipWs()
        if (pos != data.size) throw JsonParseException("trailing data at $pos")
        return value
    }

    private fun byteAt(i: kotlin.Int): kotlin.Int? =
        if (i < data.size) (data[i].toInt() and 0xFF) else null

    private fun peekIs(c: Char): kotlin.Boolean = pos < data.size && data[pos].toInt() == c.code

    private fun peekIsDigit(): kotlin.Boolean =
        pos < data.size && data[pos].toInt() in '0'.code..'9'.code

    private fun skipWs() {
        while (pos < data.size) {
            when (data[pos].toInt()) {
                ' '.code, '\t'.code, '\n'.code, '\r'.code -> pos++
                else -> return
            }
        }
    }

    private fun parseValue(): JSONValue {
        val c = byteAt(pos) ?: throw JsonParseException("unexpected end of input")
        return when {
            c == '{'.code -> parseObject()
            c == '['.code -> parseArray()
            c == '"'.code -> JSONValue.Str(parseString())
            c == 't'.code -> parseLiteral("true", JSONValue.Bool(true))
            c == 'f'.code -> parseLiteral("false", JSONValue.Bool(false))
            c == 'n'.code -> parseLiteral("null", JSONValue.Null)
            c == '-'.code || c in '0'.code..'9'.code -> parseNumber()
            else -> throw JsonParseException("unexpected char at $pos")
        }
    }

    private fun parseLiteral(word: String, value: JSONValue): JSONValue {
        if (pos + word.length > data.size) throw JsonParseException("bad literal at $pos")
        for (i in word.indices) {
            if (data[pos + i].toInt() != word[i].code) throw JsonParseException("bad literal at $pos")
        }
        pos += word.length
        return value
    }

    private fun parseObject(): JSONValue {
        pos++ // '{'
        val fields = LinkedHashMap<String, JSONValue>()
        skipWs()
        if (peekIs('}')) {
            pos++
            return JSONValue.Obj(fields)
        }
        while (true) {
            skipWs()
            if (!peekIs('"')) throw JsonParseException("expected key at $pos")
            val key = parseString()
            skipWs()
            if (!peekIs(':')) throw JsonParseException("expected ':' at $pos")
            pos++
            skipWs()
            fields[key] = parseValue() // 重复键：后值覆盖（Foundation 同口径）
            skipWs()
            when {
                peekIs(',') -> pos++
                peekIs('}') -> {
                    pos++
                    return JSONValue.Obj(fields)
                }
                else -> throw JsonParseException("expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(): JSONValue {
        pos++ // '['
        val items = ArrayList<JSONValue>()
        skipWs()
        if (peekIs(']')) {
            pos++
            return JSONValue.Arr(items)
        }
        while (true) {
            skipWs()
            items.add(parseValue())
            skipWs()
            when {
                peekIs(',') -> pos++
                peekIs(']') -> {
                    pos++
                    return JSONValue.Arr(items)
                }
                else -> throw JsonParseException("expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (true) {
            val c = byteAt(pos) ?: throw JsonParseException("unterminated string")
            when {
                c == '"'.code -> {
                    pos++
                    return sb.toString()
                }
                c == '\\'.code -> {
                    pos++
                    readEscape(sb)
                }
                c < 0x20 -> throw JsonParseException("raw control char in string at $pos")
                c < 0x80 -> {
                    sb.append(c.toChar())
                    pos++
                }
                else -> {
                    // UTF-8 多字节序列：收集首字节后的连续续字节一次解码
                    val start = pos
                    pos++
                    while (pos < data.size && (data[pos].toInt() and 0xC0) == 0x80) pos++
                    sb.append(String(data, start, pos - start, Charsets.UTF_8))
                }
            }
        }
    }

    private fun readEscape(sb: StringBuilder) {
        val c = byteAt(pos) ?: throw JsonParseException("bad escape")
        pos++
        when (c) {
            '"'.code -> sb.append('"')
            '\\'.code -> sb.append('\\')
            '/'.code -> sb.append('/')
            'b'.code -> sb.append('\b')
            'f'.code -> sb.append('\u000C') // \f（form feed，Kotlin 无转义字面量）
            'n'.code -> sb.append('\n')
            'r'.code -> sb.append('\r')
            't'.code -> sb.append('\t')
            'u'.code -> {
                val hi = readHex4()
                if (hi in 0xD800..0xDBFF) {
                    // 代理对：\uXXXX\uYYYY；不成对按宽容口径原样保留高位
                    if (pos + 1 < data.size &&
                        data[pos].toInt() == '\\'.code && data[pos + 1].toInt() == 'u'.code
                    ) {
                        val loStart = pos
                        pos += 2
                        val lo = readHex4()
                        if (lo in 0xDC00..0xDFFF) {
                            sb.appendCodePoint(((hi - 0xD800) shl 10) + (lo - 0xDC00) + 0x10000)
                        } else {
                            sb.append(hi.toChar())
                            pos = loStart
                        }
                    } else {
                        sb.append(hi.toChar())
                    }
                } else {
                    sb.append(hi.toChar())
                }
            }
            else -> throw JsonParseException("bad escape at $pos")
        }
    }

    private fun readHex4(): kotlin.Int {
        if (pos + 4 > data.size) throw JsonParseException("bad \\u escape")
        var v = 0
        repeat(4) {
            val c = data[pos].toInt()
            v = (v shl 4) or when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> throw JsonParseException("bad hex digit at $pos")
            }
            pos++
        }
        return v
    }

    private fun parseNumber(): JSONValue {
        val start = pos
        if (peekIs('-')) pos++
        if (!peekIsDigit()) throw JsonParseException("bad number at $pos")
        if (peekIs('0')) {
            pos++
        } else {
            while (peekIsDigit()) pos++
        }
        var isDouble = false
        if (peekIs('.')) {
            isDouble = true
            pos++
            if (!peekIsDigit()) throw JsonParseException("bad number at $pos")
            while (peekIsDigit()) pos++
        }
        if (peekIs('e') || peekIs('E')) {
            isDouble = true
            pos++
            if (peekIs('+') || peekIs('-')) pos++
            if (!peekIsDigit()) throw JsonParseException("bad number at $pos")
            while (peekIsDigit()) pos++
        }
        val text = String(data, start, pos - start, Charsets.UTF_8)
        if (!isDouble) {
            text.toLongOrNull()?.let { l ->
                // Int32 值域内落 .int、越界落 .double（RPCSerialization.swift:70-71 口径）
                return if (l >= kotlin.Int.MIN_VALUE && l <= kotlin.Int.MAX_VALUE) {
                    JSONValue.Int(l.toInt())
                } else {
                    JSONValue.Double(l.toDouble())
                }
            }
            // Int64 溢出 → Double（Foundation decode(Int64) 失败回落 decode(Double) 同口径）
        }
        val d = text.toDoubleOrNull() ?: throw JsonParseException("bad number '$text'")
        return JSONValue.Double(d)
    }
}

// MARK: - RPC 值（packages/rpc/src/serialization.ts 的 Swift 移植，RPCSerialization.swift:149-201）

/**
 * 序列化值域：与 zcode RPC serialize/deserialize 的类型标签一一对应。
 * Undefined=0 / String=1 / Buffer=2 / VSBuffer=3 / Array=4 / Object=5(JSON) / Int=6(VQL)。
 */
sealed class RPCValue {
    data object Undefined : RPCValue()

    data class Str(val value: String) : RPCValue()

    class Buffer(val value: ByteArray) : RPCValue() {
        override fun equals(other: Any?): kotlin.Boolean = other is Buffer && value.contentEquals(other.value)

        override fun hashCode(): kotlin.Int = value.contentHashCode()
    }

    class VsBuffer(val value: ByteArray) : RPCValue() {
        override fun equals(other: Any?): kotlin.Boolean = other is VsBuffer && value.contentEquals(other.value)

        override fun hashCode(): kotlin.Int = value.contentHashCode()
    }

    data class Arr(val items: List<RPCValue>) : RPCValue()

    data class Int(val value: kotlin.Int) : RPCValue()

    /** Object 标签：JSON 编解码（嵌套 Uint8Array 以 {__zcode_rpc_nested_uint8array_v1, base64} 恢复） */
    data class Obj(val value: JSONValue) : RPCValue()

    companion object {
        /** 便捷构造：把 JSON 兼容值包成 object 标签（RPC 参数主体形态）（:160-162）。 */
        fun json(value: JSONValue): RPCValue = Obj(value)

        /** JSON object 构建器入口（:164-166）。 */
        fun jsonObject(block: JSONObjectBuilder.() -> kotlin.Unit): RPCValue =
            Obj(JSONObjectBuilder.build(block))
    }

    /** 整数值访问（header 元素 [type, id, …] 直读）（:169-174）。 */
    val intValue: kotlin.Int?
        get() = when (this) {
            is Int -> value
            else -> jsonValue?.intValue
        }

    val stringValue: String?
        get() = when (this) {
            is Str -> value
            else -> jsonValue?.stringValue
        }

    /** 解析为 JSON（object/array 值）；其余返回 null（:182-200）。 */
    val jsonValue: JSONValue?
        get() {
            return when (this) {
                is Obj -> value
                is Arr -> {
                    val converted = ArrayList<JSONValue>(items.size)
                    for (item in items) {
                        when (item) {
                            is RPCValue.Str -> converted.add(JSONValue.Str(item.value))
                            is RPCValue.Int -> converted.add(JSONValue.Int(item.value))
                            is RPCValue.Buffer, is RPCValue.VsBuffer -> return null
                            is RPCValue.Undefined -> converted.add(JSONValue.Null)
                            is RPCValue.Obj -> converted.add(item.value)
                            is RPCValue.Arr -> return null
                        }
                    }
                    JSONValue.Arr(converted)
                }
                else -> null
            }
        }
}

/** JSON object 构建器（避免手写字典字面量的噪音，:204-228）。 */
class JSONObjectBuilder {
    val fields: LinkedHashMap<String, JSONValue> = LinkedHashMap()

    /** null 值跳过（键不写入）——Swift 各 set 重载同口径。 */
    fun set(key: String, value: JSONValue?) {
        if (value != null) fields[key] = value
    }

    fun set(key: String, value: String?) {
        if (value != null) fields[key] = JSONValue.Str(value)
    }

    fun set(key: String, value: kotlin.Int?) {
        if (value != null) fields[key] = JSONValue.Int(value)
    }

    fun set(key: String, value: kotlin.Boolean?) {
        if (value != null) fields[key] = JSONValue.Bool(value)
    }

    companion object {
        fun build(populate: JSONObjectBuilder.() -> kotlin.Unit): JSONValue {
            val builder = JSONObjectBuilder()
            builder.populate()
            return JSONValue.Obj(builder.fields)
        }
    }
}

// MARK: - 序列化（serialization.ts 逐行移植，RPCSerialization.swift:232-377）

object RPCSerialization {

    // MARK: VQL（Variable-Length Quantity：7 bit 数据 + 高位续传标记）

    /** 返回 Long：Swift Int 为 64 位，`n > 63` 守卫下 VQL 可表达 63 位值（长度判定不得截断）。 */
    fun readIntVQL(data: ByteArray, offset: ReadCursor): kotlin.Long? {
        var value = 0L
        var n = 0
        while (true) {
            if (offset.pos >= data.size) return null
            val byte = data[offset.pos].toInt() and 0xFF
            offset.pos += 1
            value = value or ((byte and 0b0111_1111).toLong() shl n)
            if ((byte and 0b1000_0000) == 0) {
                return value
            }
            n += 7
            if (n > 63) return null
        }
    }

    /** Swift `Int`（64 位）入参：.int 序列化把负数按二补数无符号展开后可达 2^32-1。 */
    fun writeIntVQL(value: kotlin.Long): ByteArray {
        var v = value // UInt64(bitPattern: Int64(value))：负数按位模式展开为无符号
        if (v == 0L) return byteArrayOf(0x00)
        val bytes = ArrayList<Byte>()
        while (v != 0L) {
            var byte = (v and 0b0111_1111).toByte()
            v = v ushr 7
            if (v != 0L) byte = (byte.toInt() or 0b1000_0000).toByte()
            bytes.add(byte)
        }
        return bytes.toByteArray()
    }

    // MARK: serialize

    fun serialize(value: RPCValue): ByteArray {
        val out = ByteArrayOutputStream()
        appendValue(value, out)
        return out.toByteArray()
    }

    private fun appendString(string: String, tag: kotlin.Int, out: ByteArrayOutputStream) {
        val utf8 = string.toByteArray(Charsets.UTF_8)
        out.write(tag)
        out.write(writeIntVQL(utf8.size.toLong()))
        out.write(utf8)
    }

    private fun appendValue(value: RPCValue, out: ByteArrayOutputStream) {
        when (value) {
            is RPCValue.Undefined -> out.write(0)
            is RPCValue.Str -> appendString(value.value, 1, out)
            is RPCValue.Buffer -> {
                out.write(2)
                out.write(writeIntVQL(value.value.size.toLong()))
                out.write(value.value)
            }
            is RPCValue.VsBuffer -> {
                out.write(3)
                out.write(writeIntVQL(value.value.size.toLong()))
                out.write(value.value)
            }
            is RPCValue.Arr -> {
                out.write(4)
                out.write(writeIntVQL(value.items.size.toLong()))
                for (item in value.items) {
                    appendValue(item, out)
                }
            }
            is RPCValue.Int -> {
                // 与 JS (data|0)===data 口径一致：32 位整数走 VQL；负数按二补数无符号展开
                val truncated = value.value.toLong() and 0xFFFF_FFFFL
                out.write(6)
                out.write(writeIntVQL(truncated))
            }
            is RPCValue.Obj -> {
                val encoded = encodeJSONWithNestedBinary(value.value)
                // Swift `String(data:encoding:) ?? "null"`：编码产物恒为合法 UTF-8，兜底不可达
                appendString(String(encoded, Charsets.UTF_8), 5, out)
            }
        }
    }

    /** Object 走 JSON；此处的 JSON 已在构造期把嵌套二进制换成了标记对象（见 jsonValue(from:)）。 */
    private fun encodeJSONWithNestedBinary(value: JSONValue): ByteArray {
        // Swift `try? encoder.encode` → 失败落 "null"（JsonCodec 非有限 double 返回 null 同口径）
        return JsonCodec.writeOrNull(value) ?: "null".toByteArray(Charsets.UTF_8)
    }

    // MARK: deserialize

    /** 从 data 的 offset 起反序列化一个值；推进 cursor。失败返回 null（:321-370）。 */
    fun deserialize(data: ByteArray, offset: ReadCursor): RPCValue? {
        if (offset.pos >= data.size) return null
        val tag = data[offset.pos].toInt() and 0xFF
        offset.pos += 1
        when (tag) {
            0 -> return RPCValue.Undefined
            1 -> {
                val length = readIntVQL(data, offset) ?: return null
                if (offset.pos.toLong() + length > data.size) return null
                val slice = data.copyOfRange(offset.pos, offset.pos + length.toInt())
                offset.pos += length.toInt()
                // Swift `String(data:encoding:) ?? ""`：坏 UTF-8 时 Kotlin 以替换符宽容通过
                return RPCValue.Str(String(slice, Charsets.UTF_8))
            }
            2 -> {
                val length = readIntVQL(data, offset) ?: return null
                if (offset.pos.toLong() + length > data.size) return null
                val slice = data.copyOfRange(offset.pos, offset.pos + length.toInt())
                offset.pos += length.toInt()
                return RPCValue.Buffer(slice)
            }
            3 -> {
                val length = readIntVQL(data, offset) ?: return null
                if (offset.pos.toLong() + length > data.size) return null
                val slice = data.copyOfRange(offset.pos, offset.pos + length.toInt())
                offset.pos += length.toInt()
                return RPCValue.VsBuffer(slice)
            }
            4 -> {
                val count = readIntVQL(data, offset) ?: return null
                val items = ArrayList<RPCValue>(minOf(count, 4096L).toInt())
                for (i in 0L until count) {
                    val item = deserialize(data, offset) ?: return null
                    items.add(item)
                }
                return RPCValue.Arr(items)
            }
            5 -> {
                val length = readIntVQL(data, offset) ?: return null
                if (offset.pos.toLong() + length > data.size) return null
                val slice = data.copyOfRange(offset.pos, offset.pos + length.toInt())
                offset.pos += length.toInt()
                val json = try {
                    JsonCodec.parse(slice)
                } catch (e: JsonParseException) {
                    return null
                }
                return RPCValue.Obj(json)
            }
            6 -> {
                val value = readIntVQL(data, offset) ?: return null
                // 负数按 32 位二补数还原
                return RPCValue.Int(value.toInt())
            }
            else -> return null
        }
    }

    // MARK: 嵌套 Uint8Array 标记说明

    // TS 端 Object JSON 内嵌 Uint8Array 会编码为 {__zcode_rpc_nested_uint8array_v1: true, base64: "…"}；
    // 本客户端当前业务面（file.readFileRange 顶层 Buffer 标签等）不消费嵌套二进制，
    // 解析后保持标记对象原样，由消费方按 marker 自行解码（serialization.ts:136,220-250 口径）。
}

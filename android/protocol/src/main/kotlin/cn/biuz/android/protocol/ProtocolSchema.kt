package cn.biuz.android.protocol

// 直译自 ios/ZCodeMobileApp/Sources/Services/RPC/ProtocolSchema.swift（315 行）。
// zod 语义最小校验引擎（「构造与校验同源」基建，2026-10-07 架构审查轮）。
//
// 上游客户端与服务端 import 同一个 zod schema 包（@zcode/shared/zcode-protocol-v4），
// 消息形状在构造期即被同一份 schema 约束【实证·上游仓 command.ts:340
// parseCommandEnvelope 信封+payload 联合校验收口】。本文件是该机制在 Kotlin 侧的
// 镜像：schema 定义（CommandSchemas.kt）、发送构造（ConversationCommandFactory）
// 与回执解析（CommandAck）三方共用，客户端不再「手拼载荷碰运气」。
//
// zod 语义映射（上游 zod 4.6.5）：
// - z.object 默认 **strip**：未知键静默剥离后 parse 成功（command.ts:60-63 自注
//   「旧 CLI 的 z.object 会静默丢弃该键 fail-closed」）；`.strict()` 才拒未知键
//   （transport.ts 参数层/附件四方法全 strict）。
// - 本引擎 strip 模式把被剥离键记入 warnings（上游是静默的；A4 静默剥离陷阱的
//   本地显性化），strict 模式记入 errors。
// - denyKeys：服务端注入的可信字段（connectionId/clientMode/workflowRunDeltas，
//   上游 withTrustedConnection 强制删除伪造键再盖真值
//   zcodeAgentConnectionScope.ts:94-105）——客户端构造携带即违规，任何模式下都是
//   硬错误（先例：v1.14 附件多带 connectionId 被桌面 strict 拒）。
//
// 机械映射约定（全文件统一，与 Models.kt 同口径）：
// - Swift `indirect enum PSchema` → Kotlin sealed class（case 首字母大写；
//   string→Str / patternedString→PatternedStr / integer→Integer / number→Num /
//   boolean→Bool / anyOf→AnyOf / array→Arr / object→Obj / strictBase64→StrictBase64 /
//   anyJSON→AnyJson，companion 便捷构造保原文语义名）；
// - Swift `String(localized:)` → 同串字面量（iOS 键 = 串本身，见 Models.kt 文件头约定）；
// - Swift `String.count`（grapheme cluster）→ Kotlin `String.length`（UTF-16 code unit）：
//   上游正则/超限语义以 JS 字符为单位，length 口径反而更贴近上游（值域判定不受影响）。

// MARK: 值域 pattern（附件四方法 strict 参数；手写校验避免正则依赖）

/**
 * 值域 pattern（Swift 原文 :24-60）。
 * Kotlin 无 Swift `Character.isASCII` → `code < 128`；`isNumber` → `Char.isDigit()`
 * （ASCII 集合内两类别判定一致；先验 ASCII 门在位，语义不变）。
 */
enum class PPattern {
    uploadId, // ^[A-Za-z0-9][A-Za-z0-9._:-]*$（ASCII；≤128）
    fileName, // ^[^\0\r\n]+$（非空、无 NUL/CR/LF；≤255）
    mime, // ^type/subtype$（RFC token 字符集，ASCII；3..255）
    sha256Checksum, // ^sha256:[0-9a-f]{64}$
    ;

    fun matches(s: String): Boolean {
        return when (this) {
            uploadId -> {
                // ASCII 判定（§11.3：Swift isLetter/isNumber 接受 Unicode 字母数字，
                // 上游 regex 纯 ASCII——非 ASCII 值本地放行、服务端 zod 拒，错误从
                // 构造期退化到桌面端拒收）+ ≤128 上限（transport.ts:826 .max(128)）
                val first = s.firstOrNull() ?: return false
                if (first.code >= 128) return false
                if (!(first.isLetter() || first.isDigit())) return false
                s.length <= 128 &&
                    s.all { c ->
                        c.code < 128 && (c.isLetter() || c.isDigit() || "._:-".contains(c))
                    }
            }
            fileName ->
                s.isNotEmpty() && s.length <= 255 &&
                    !s.contains("\u0000") && !s.contains("\r") && !s.contains("\n")
            mime -> {
                val slash = s.indexOf('/')
                // Swift `slash != s.startIndex` 与 `s[..<slash].count >= 1` 同为 slash > 0
                if (slash <= 0) return false
                // `s[s.index(after: slash)...].count >= 1`
                if (s.length - slash - 1 < 1) return false
                if (s.length < 3 || s.length > 255) return false
                val type = s.substring(0, slash)
                val subtype = s.substring(slash + 1)
                tokenValid(type) && tokenValid(subtype)
            }
            sha256Checksum -> {
                if (!s.startsWith("sha256:") || s.length != 7 + 64) return false
                s.drop(7).all { c -> c in '0'..'9' || c in 'a'..'f' }
            }
        }
    }

    private fun tokenValid(token: String): Boolean {
        val first = token.firstOrNull() ?: return false
        if (first.code >= 128) return false
        if (!(first.isLetter() || first.isDigit())) return false
        return token.all { c -> c.code < 128 && (c.isLetter() || c.isDigit() || "!#$&^_.+-".contains(c)) }
    }
}

// MARK: schema 树

/**
 * schema 树（Swift 原文 :64-83；case 名映射见文件头约定）。
 * 信封 `sessionId` 的「键必在场 + 值可 null」组合由 [PField.requiredNullable] 表达
 * （command.ts:323-336）。
 */
sealed class PSchema {
    /** Swift `.string(minLength:)`。 */
    data class Str(val minLength: Int) : PSchema()

    /** Swift `.patternedString(_)`。 */
    data class PatternedStr(val pattern: PPattern) : PSchema()

    /** Swift `.integer(min:max:)`；null = 无界。 */
    data class Integer(val min: Int?, val max: Int?) : PSchema()

    /** Swift `.number`。 */
    data object Num : PSchema()

    /** Swift `.boolean`。 */
    data object Bool : PSchema()

    /** Swift `.anyOf(Set<String>)`。 */
    data class AnyOf(val values: Set<String>) : PSchema()

    /** Swift `.array(_, maxItems:)`；maxItems null = 无上限。 */
    data class Arr(val element: PSchema, val maxItems: Int?) : PSchema()

    /** Swift `.object(PObjectShape)`。 */
    data class Obj(val shape: PObjectShape) : PSchema()

    /**
     * 严格 base64（自带完整 padding；'=' 只许出现在尾部 ≤2 位；长度为 4 的倍数），
     * 且解码字节数 ≤ maxBytes。上游附件 chunk superRefine 同语义
     * （transport.ts:882-928，错误 message 逐字 "invalid base64"）。
     */
    data class StrictBase64(val maxBytes: Int) : PSchema()

    /** Swift `.anyJSON`。 */
    data object AnyJson : PSchema()

    companion object {
        /** Swift `static var string`。 */
        val string: PSchema
            get() = Str(minLength = 0)

        /** Swift `static var nonEmptyString`。 */
        val nonEmptyString: PSchema
            get() = Str(minLength = 1)

        /** Swift `static var integer`。 */
        val integer: PSchema
            get() = Integer(min = null, max = null)

        /** Swift `static func enumeration(_ values: String...)`。 */
        fun enumeration(vararg values: String): PSchema = AnyOf(values.toSet())
    }
}

/**
 * 对象成员：optional（键可缺席）与 nullable（值可 null）独立控制——
 * 信封 `sessionId` 即「键必在场 + 值可 null」组合（command.ts:323-336）。（Swift :87-106）
 */
data class PField(val schema: PSchema, val optional: Boolean, val nullable: Boolean) {
    companion object {
        fun required(schema: PSchema): PField = PField(schema = schema, optional = false, nullable = false)

        fun optional(schema: PSchema): PField = PField(schema = schema, optional = true, nullable = false)

        /** 键恒在场、值可 null。 */
        fun requiredNullable(schema: PSchema): PField = PField(schema = schema, optional = false, nullable = true)

        /** 键可缺席、在场时值也可 null（beforeQueueItemId=null=队尾等）。 */
        fun optionalNullable(schema: PSchema): PField = PField(schema = schema, optional = true, nullable = true)
    }
}

/**
 * 对象形状（Swift :108-126）。
 * 注：`deny` 默认取全局可信字段黑名单（可传空集显式关闭——信封/行游标等自有校验面）；
 * Kotlin 默认参数引用同包 [CommandSchemas.trustedFieldDenyList]，与 Swift 同构。
 * Swift init 标签 `deny:` + 存储属性 `denyKeys` 在 Kotlin 主构造不可同名双写——
 * 构造参数 `deny`（调用名与 Swift 标签一致）+ 属性 `denyKeys`（Swift 存储名一致），
 * 故不使用 data class（校验面无形状等值需求）。
 */
class PObjectShape(
    /** LinkedHashMap 承载声明序（Swift Dictionary 无序，校验输出顺序本就未定义；值内容等价）。 */
    val fields: Map<String, PField>,
    /** true=未知键硬错（zod .strict()）；false=剥离+警告（zod 默认 strip）。 */
    val strict: Boolean = false,
    deny: Set<String> = CommandSchemas.trustedFieldDenyList,
) {
    val denyKeys: Set<String> = deny

    companion object {
        fun empty(): PObjectShape = PObjectShape(fields = emptyMap(), deny = emptySet())
    }
}

// MARK: 校验结果

/**
 * 校验结果（Swift :130-141）。
 * value = 规范化值（strip 模式下移除未知键后的重建）；任何硬错误时为 null。
 */
data class PValidation(
    val value: JSONValue?,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()

    companion object {
        fun failure(errors: List<String>): PValidation =
            PValidation(value = null, errors = errors, warnings = emptyList())
    }
}

// MARK: 校验器

/** 校验器（Swift enum PValidator 命名空间 :145-315 → Kotlin object）。 */
object PValidator {

    fun validate(value: JSONValue, schema: PSchema, path: String = "$"): PValidation {
        return when (schema) {
            is PSchema.AnyJson -> PValidation(value = value, errors = emptyList(), warnings = emptyList())

            is PSchema.Str -> {
                val s = value.stringValue
                    ?: return PValidation.failure(listOf("$path: 期望 string，实际 ${describe(value)}"))
                if (s.length < schema.minLength) {
                    return PValidation.failure(listOf("$path: 长度 ${s.length} < min(${schema.minLength})"))
                }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.PatternedStr -> {
                val s = value.stringValue
                    ?: return PValidation.failure(listOf("$path: 期望 string，实际 ${describe(value)}"))
                if (!schema.pattern.matches(s)) {
                    return PValidation.failure(
                        listOf("$path: 不匹配 ${schema.pattern} 约束（值前缀 ${s.take(32)}）"),
                    )
                }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.Integer -> {
                val i = value.intValue
                    ?: return PValidation.failure(listOf("$path: 期望 integer，实际 ${describe(value)}"))
                schema.min?.let { min -> if (i < min) return PValidation.failure(listOf("$path: $i < min($min)")) }
                schema.max?.let { max -> if (i > max) return PValidation.failure(listOf("$path: $i > max($max)")) }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.Num -> {
                if (value.doubleValue == null) {
                    return PValidation.failure(listOf("$path: 期望 number，实际 ${describe(value)}"))
                }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.Bool -> {
                if (value.boolValue == null) {
                    return PValidation.failure(listOf("$path: 期望 boolean，实际 ${describe(value)}"))
                }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.AnyOf -> {
                val s = value.stringValue
                if (s == null || !schema.values.contains(s)) {
                    return PValidation.failure(
                        listOf(
                            "$path: 值 ${describe(value)} 不在词表 [${schema.values.sorted().joinToString("|")}]",
                        ),
                    )
                }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.Arr -> {
                val array = value.arrayValue
                    ?: return PValidation.failure(listOf("$path: 期望 array，实际 ${describe(value)}"))
                schema.maxItems?.let { maxItems ->
                    if (array.size > maxItems) {
                        return PValidation.failure(listOf("$path: 元素数 ${array.size} > max($maxItems)"))
                    }
                }
                val errors = ArrayList<String>()
                val warnings = ArrayList<String>()
                val normalized = ArrayList<JSONValue>()
                for ((index, item) in array.withIndex()) {
                    val child = validate(item, schema = schema.element, path = "$path[$index]")
                    errors += child.errors
                    warnings += child.warnings
                    child.value?.let { normalized.add(it) }
                }
                if (errors.isNotEmpty()) return PValidation.failure(errors)
                PValidation(value = JSONValue.Arr(normalized), errors = emptyList(), warnings = warnings)
            }

            is PSchema.StrictBase64 -> {
                val s = value.stringValue
                    ?: return PValidation.failure(listOf("$path: 期望 string，实际 ${describe(value)}"))
                val byteLength = strictBase64ByteLength(s)
                    ?: return PValidation.failure(
                        listOf("$path: invalid base64（必须自带完整 padding、'=' 仅限尾部 ≤2 位、长度为 4 的倍数）"),
                    )
                if (byteLength > schema.maxBytes) {
                    return PValidation.failure(listOf("$path: 解码 $byteLength 字节 > max(${schema.maxBytes})"))
                }
                PValidation(value = value, errors = emptyList(), warnings = emptyList())
            }

            is PSchema.Obj -> validateObject(value, shape = schema.shape, path = path)
        }
    }

    private fun validateObject(
        value: JSONValue,
        shape: PObjectShape,
        path: String,
    ): PValidation {
        val dict = value.objectValue
            ?: return PValidation.failure(listOf("$path: 期望 object，实际 ${describe(value)}"))
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val output = LinkedHashMap<String, JSONValue>()

        for ((key, field) in shape.fields) {
            val present = dict[key]
            if (present == null) {
                if (!field.optional) {
                    errors.add("$path.$key: 缺失必填键")
                }
                continue
            }
            if (present.isNull) {
                if (field.nullable) {
                    output[key] = JSONValue.Null
                } else {
                    errors.add("$path.$key: 不接受 null")
                }
                continue
            }
            val child = validate(present, schema = field.schema, path = "$path.$key")
            errors += child.errors
            warnings += child.warnings
            child.value?.let { output[key] = it }
        }

        for (key in dict.keys) {
            if (shape.fields.containsKey(key)) continue
            when {
                shape.denyKeys.contains(key) ->
                    // 服务端注入字段：客户端携带即违规（上游会剥真值，携带=构造 bug）
                    errors.add("$path.$key: 服务端注入字段，客户端不得构造")
                shape.strict -> errors.add("$path.$key: 未知键（strict）")
                else ->
                    // zod strip 语义：剥离后放行，但本地显性告警（上游此路是静默的）
                    warnings.add("$path.$key: 未知键已剥离（strip，服务端同语义）")
            }
        }

        if (errors.isNotEmpty()) return PValidation.failure(errors)
        return PValidation(value = JSONValue.Obj(output), errors = emptyList(), warnings = warnings)
    }

    /**
     * 严格 base64 字节长度（无效返回 null）：字母表 A–Z a–z 0–9 + /，'=' 仅尾部 ≤2，
     * 总长为 4 的倍数。与上游 decodedBase64ByteLength 同口径（transport.ts:882-928）。
     * （Swift :282-302）
     */
    fun strictBase64ByteLength(s: String): Int? {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        if (utf8.isEmpty() || utf8.size % 4 != 0) return null
        var pad = 0
        // 尾部 padding 计数（≤2）
        for (i in utf8.size - 1 downTo utf8.size - 2) {
            if (utf8[i].toInt() == '='.code) pad += 1 else break
        }
        // '=' 只允许出现在尾部 padding 位置
        for (i in 0 until utf8.size - pad) {
            if (utf8[i].toInt() == '='.code) return null
        }
        for (i in 0 until utf8.size - pad) {
            if (!isAlphabet(utf8[i])) return null
        }
        return utf8.size / 4 * 3 - pad
    }

    private fun isAlphabet(b: Byte): Boolean {
        val c = b.toInt()
        return (c in 'A'.code..'Z'.code) ||
            (c in 'a'.code..'z'.code) ||
            (c in '0'.code..'9'.code) ||
            c == '+'.code || c == '/'.code
    }

    private fun describe(value: JSONValue): String {
        return when (value) {
            is JSONValue.Null -> "null"
            is JSONValue.Bool -> "boolean"
            is JSONValue.Int -> "number(${value.value})"
            is JSONValue.Double -> "number(${value.value})"
            is JSONValue.Str -> "string(\"${value.value.take(24)}\")"
            is JSONValue.Arr -> "array(${value.items.size})"
            is JSONValue.Obj -> "object(${value.fields.size} 键)"
        }
    }
}

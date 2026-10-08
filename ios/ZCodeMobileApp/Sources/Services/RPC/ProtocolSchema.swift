import Foundation

// MARK: - zod 语义最小校验引擎（「构造与校验同源」基建，2026-10-07 架构审查轮）
//
// 上游客户端与服务端 import 同一个 zod schema 包（@zcode/shared/zcode-protocol-v4），
// 消息形状在构造期即被同一份 schema 约束【实证·上游仓 command.ts:340
// parseCommandEnvelope 信封+payload 联合校验收口】。本文件是该机制在 Swift 侧的
// 镜像：schema 定义（CommandSchemas.swift）、发送构造（ConversationCommandFactory）
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

// MARK: 值域 pattern（附件四方法 strict 参数；手写校验避免正则依赖）

enum PPattern: Sendable {
    case uploadId      // ^[A-Za-z0-9][A-Za-z0-9._:-]*$（ASCII；≤128）
    case fileName      // ^[^\0\r\n]+$（非空、无 NUL/CR/LF；≤255）
    case mime          // ^type/subtype$（RFC token 字符集，ASCII；3..255）
    case sha256Checksum // ^sha256:[0-9a-f]{64}$

    func matches(_ s: String) -> Bool {
        switch self {
        case .uploadId:
            // ASCII 判定（§11.3：Swift isLetter/isNumber 接受 Unicode 字母数字，
            // 上游 regex 纯 ASCII——非 ASCII 值本地放行、服务端 zod 拒，错误从
            // 构造期退化到桌面端拒收）+ ≤128 上限（transport.ts:826 .max(128)）
            guard let first = s.first else { return false }
            guard first.isASCII, first.isLetter || first.isNumber else { return false }
            return s.count <= 128
                && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || "._:-".contains($0)) }
        case .fileName:
            return !s.isEmpty && s.count <= 255
                && !s.contains("\0") && !s.contains("\r") && !s.contains("\n")
        case .mime:
            guard let slash = s.firstIndex(of: "/"), slash != s.startIndex,
                  s[..<slash].count >= 1, s[s.index(after: slash)...].count >= 1,
                  s.count >= 3, s.count <= 255 else { return false }
            let type = String(s[..<slash])
            let subtype = String(s[s.index(after: slash)...])
            return tokenValid(type) && tokenValid(subtype)
        case .sha256Checksum:
            guard s.hasPrefix("sha256:"), s.count == 7 + 64 else { return false }
            return s.dropFirst(7).allSatisfy { ($0 >= "0" && $0 <= "9") || ($0 >= "a" && $0 <= "f") }
        }
    }

    private func tokenValid(_ token: String) -> Bool {
        guard let first = token.first, first.isASCII, first.isLetter || first.isNumber else { return false }
        return token.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || "!#$&^_.+-".contains($0)) }
    }
}

// MARK: schema 树

indirect enum PSchema: Sendable {
    case string(minLength: Int)
    case patternedString(PPattern)
    case integer(min: Int?, max: Int?)
    case number
    case boolean
    case anyOf(Set<String>)
    case array(PSchema, maxItems: Int?)
    case object(PObjectShape)
    /// 严格 base64（自带完整 padding；'=' 只许出现在尾部 ≤2 位；长度为 4 的倍数），
    /// 且解码字节数 ≤ maxBytes。上游附件 chunk superRefine 同语义
    /// （transport.ts:882-928，错误 message 逐字 "invalid base64"）。
    case strictBase64(maxBytes: Int)
    case anyJSON

    static var string: PSchema { .string(minLength: 0) }
    static var nonEmptyString: PSchema { .string(minLength: 1) }
    static var integer: PSchema { .integer(min: nil, max: nil) }
    static func enumeration(_ values: String...) -> PSchema { .anyOf(Set(values)) }
}

/// 对象成员：optional（键可缺席）与 nullable（值可 null）独立控制——
/// 信封 `sessionId` 即「键必在场 + 值可 null」组合（command.ts:323-336）。
struct PField: Sendable {
    var schema: PSchema
    var optional: Bool
    var nullable: Bool

    static func required(_ schema: PSchema) -> PField {
        PField(schema: schema, optional: false, nullable: false)
    }
    static func optional(_ schema: PSchema) -> PField {
        PField(schema: schema, optional: true, nullable: false)
    }
    /// 键恒在场、值可 null
    static func requiredNullable(_ schema: PSchema) -> PField {
        PField(schema: schema, optional: false, nullable: true)
    }
    /// 键可缺席、在场时值也可 null（beforeQueueItemId=null=队尾等）
    static func optionalNullable(_ schema: PSchema) -> PField {
        PField(schema: schema, optional: true, nullable: true)
    }
}

struct PObjectShape: Sendable {
    var fields: [String: PField]
    /// true=未知键硬错（zod .strict()）；false=剥离+警告（zod 默认 strip）
    var strict: Bool
    /// 服务端注入字段黑名单：客户端构造携带即硬错（两种模式下一致）
    var denyKeys: Set<String>

    /// deny 默认取全局可信字段黑名单（可传空集显式关闭——信封/行游标等自有校验面）
    init(
        fields: [String: PField], strict: Bool = false,
        deny: Set<String> = CommandSchemas.trustedFieldDenyList
    ) {
        self.fields = fields
        self.strict = strict
        self.denyKeys = deny
    }

    static func empty() -> PObjectShape { PObjectShape(fields: [:], deny: []) }
}

// MARK: 校验结果

struct PValidation: Sendable {
    /// 规范化值（strip 模式下移除未知键后的重建）；任何硬错误时为 nil
    var value: JSONValue?
    var errors: [String]
    var warnings: [String]

    var isValid: Bool { errors.isEmpty }

    static func failure(_ errors: [String]) -> PValidation {
        PValidation(value: nil, errors: errors, warnings: [])
    }
}

// MARK: 校验器

enum PValidator {

    static func validate(_ value: JSONValue, schema: PSchema, path: String = "$") -> PValidation {
        switch schema {
        case .anyJSON:
            return PValidation(value: value, errors: [], warnings: [])

        case .string(let minLength):
            guard let s = value.stringValue else {
                return .failure([String(localized: "\(path): 期望 string，实际 \(describe(value))")])
            }
            guard s.count >= minLength else {
                return .failure([String(localized: "\(path): 长度 \(s.count) < min(\(minLength))")])
            }
            return PValidation(value: value, errors: [], warnings: [])

        case .patternedString(let pattern):
            guard let s = value.stringValue else {
                return .failure([String(localized: "\(path): 期望 string，实际 \(describe(value))")])
            }
            guard pattern.matches(s) else {
                return .failure([String(localized: "\(path): 不匹配 \(pattern) 约束（值前缀 \(s.prefix(32))）")])
            }
            return PValidation(value: value, errors: [], warnings: [])

        case .integer(let min, let max):
            guard let i = value.intValue else {
                return .failure([String(localized: "\(path): 期望 integer，实际 \(describe(value))")])
            }
            if let min, i < min { return .failure(["\(path): \(i) < min(\(min))"]) }
            if let max, i > max { return .failure(["\(path): \(i) > max(\(max))"]) }
            return PValidation(value: value, errors: [], warnings: [])

        case .number:
            guard value.doubleValue != nil else {
                return .failure([String(localized: "\(path): 期望 number，实际 \(describe(value))")])
            }
            return PValidation(value: value, errors: [], warnings: [])

        case .boolean:
            guard value.boolValue != nil else {
                return .failure([String(localized: "\(path): 期望 boolean，实际 \(describe(value))")])
            }
            return PValidation(value: value, errors: [], warnings: [])

        case .anyOf(let values):
            guard let s = value.stringValue, values.contains(s) else {
                return .failure([
                    String(localized: "\(path): 值 \(describe(value)) 不在词表 [\(values.sorted().joined(separator: "|"))]")])
            }
            return PValidation(value: value, errors: [], warnings: [])

        case .array(let element, let maxItems):
            guard let array = value.arrayValue else {
                return .failure([String(localized: "\(path): 期望 array，实际 \(describe(value))")])
            }
            if let maxItems, array.count > maxItems {
                return .failure([String(localized: "\(path): 元素数 \(array.count) > max(\(maxItems))")])
            }
            var errors: [String] = []
            var warnings: [String] = []
            var normalized: [JSONValue] = []
            for (index, item) in array.enumerated() {
                let child = validate(item, schema: element, path: "\(path)[\(index)]")
                errors += child.errors
                warnings += child.warnings
                if let v = child.value { normalized.append(v) }
            }
            guard errors.isEmpty else { return .failure(errors) }
            return PValidation(value: .array(normalized), errors: [], warnings: warnings)

        case .strictBase64(let maxBytes):
            guard let s = value.stringValue else {
                return .failure([String(localized: "\(path): 期望 string，实际 \(describe(value))")])
            }
            guard let byteLength = strictBase64ByteLength(s) else {
                return .failure([String(localized: "\(path): invalid base64（必须自带完整 padding、'=' 仅限尾部 ≤2 位、长度为 4 的倍数）")])
            }
            guard byteLength <= maxBytes else {
                return .failure([String(localized: "\(path): 解码 \(byteLength) 字节 > max(\(maxBytes))")])
            }
            return PValidation(value: value, errors: [], warnings: [])

        case .object(let shape):
            return validateObject(value, shape: shape, path: path)
        }
    }

    private static func validateObject(
        _ value: JSONValue, shape: PObjectShape, path: String
    ) -> PValidation {
        guard let dict = value.objectValue else {
            return .failure([String(localized: "\(path): 期望 object，实际 \(describe(value))")])
        }
        var errors: [String] = []
        var warnings: [String] = []
        var output: [String: JSONValue] = [:]

        for (key, field) in shape.fields {
            guard let present = dict[key] else {
                if !field.optional {
                    errors.append(String(localized: "\(path).\(key): 缺失必填键"))
                }
                continue
            }
            if present.isNull {
                if field.nullable {
                    output[key] = .null
                } else {
                    errors.append(String(localized: "\(path).\(key): 不接受 null"))
                }
                continue
            }
            let child = validate(present, schema: field.schema, path: "\(path).\(key)")
            errors += child.errors
            warnings += child.warnings
            if let v = child.value { output[key] = v }
        }

        for key in dict.keys where shape.fields[key] == nil {
            if shape.denyKeys.contains(key) {
                // 服务端注入字段：客户端携带即违规（上游会剥真值，携带=构造 bug）
                errors.append(String(localized: "\(path).\(key): 服务端注入字段，客户端不得构造"))
            } else if shape.strict {
                errors.append(String(localized: "\(path).\(key): 未知键（strict）"))
            } else {
                // zod strip 语义：剥离后放行，但本地显性告警（上游此路是静默的）
                warnings.append("\(path).\(key): 未知键已剥离（strip，服务端同语义）")
            }
        }

        guard errors.isEmpty else { return .failure(errors) }
        return PValidation(value: .object(output), errors: [], warnings: warnings)
    }

    /// 严格 base64 字节长度（无效返回 nil）：字母表 A–Z a–z 0–9 + /，'=' 仅尾部 ≤2，
    /// 总长为 4 的倍数。与上游 decodedBase64ByteLength 同口径（transport.ts:882-928）。
    static func strictBase64ByteLength(_ s: String) -> Int? {
        let utf8 = Array(s.utf8)
        guard !utf8.isEmpty, utf8.count % 4 == 0 else { return nil }
        var pad = 0
        // 尾部 padding 计数（≤2）
        for i in stride(from: utf8.count - 1, through: utf8.count - 2, by: -1) {
            if utf8[i] == UInt8(ascii: "=") { pad += 1 } else { break }
        }
        // '=' 只允许出现在尾部 padding 位置
        for byte in utf8[0..<(utf8.count - pad)] where byte == UInt8(ascii: "=") {
            return nil
        }
        func isAlphabet(_ b: UInt8) -> Bool {
            (b >= UInt8(ascii: "A") && b <= UInt8(ascii: "Z"))
                || (b >= UInt8(ascii: "a") && b <= UInt8(ascii: "z"))
                || (b >= UInt8(ascii: "0") && b <= UInt8(ascii: "9"))
                || b == UInt8(ascii: "+") || b == UInt8(ascii: "/")
        }
        guard utf8[0..<(utf8.count - pad)].allSatisfy(isAlphabet) else { return nil }
        return utf8.count / 4 * 3 - pad
    }

    private static func describe(_ value: JSONValue) -> String {
        switch value {
        case .null: return "null"
        case .bool: return "boolean"
        case .int(let i): return "number(\(i))"
        case .double(let d): return "number(\(d))"
        case .string(let s): return "string(\"\(s.prefix(24))\")"
        case .array(let a): return "array(\(a.count))"
        case .object(let o): return String(localized: "object(\(o.count) 键)")
        }
    }
}

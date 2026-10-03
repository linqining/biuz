import Foundation

// MARK: - JSONValue（宽松 JSON 容器；v4 协议未冻结，客户端按宽容解析）

/// 动态 JSON 值：对端 schema 处于草稿态（core.ts「未冻结」声明），解析失败不应整帧丢弃。
enum JSONValue: Codable, Equatable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    var isNull: Bool { if case .null = self { return true }; return false }

    var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    var intValue: Int? {
        switch self {
        case .int(let i): return i
        case .double(let d) where d == d.rounded(): return Int(d)
        case .string(let s) where Int(s) != nil: return Int(s)
        default: return nil
        }
    }

    var doubleValue: Double? {
        switch self {
        case .int(let i): return Double(i)
        case .double(let d): return d
        default: return nil
        }
    }

    var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    var objectValue: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    subscript(key: String) -> JSONValue? {
        objectValue?[key]
    }

    subscript(index: Int) -> JSONValue? {
        arrayValue?[safe: index]
    }

    // MARK: Codable

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else if let i = try? container.decode(Int64.self) {
            self = i >= Int(Int32.min) && i <= Int(Int32.max) ? .int(Int(i)) : .double(Double(i))
        } else if let d = try? container.decode(Double.self) {
            self = .double(d)
        } else if let s = try? container.decode(String.self) {
            self = .string(s)
        } else if let a = try? container.decode([JSONValue].self) {
            self = .array(a)
        } else if let o = try? container.decode([String: JSONValue].self) {
            self = .object(o)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported JSON value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let b): try container.encode(b)
        case .int(let i): try container.encode(i)
        case .double(let d): try container.encode(d)
        case .string(let s): try container.encode(s)
        case .array(let a): try container.encode(a)
        case .object(let o): try container.encode(o)
        }
    }

    static func from(any: Any) -> JSONValue? {
        switch any {
        case is NSNull: return .null
        case let n as NSNumber:
            // BOOL 需在整数前判定（NSNumber 桥接陷阱）
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            let objCType = String(cString: n.objCType)
            switch objCType {
            case "c", "B": return .bool(n.boolValue)
            case "i", "s", "l", "q", "I", "S", "L", "Q": return .int(n.intValue)
            default: return .double(n.doubleValue)
            }
        case let s as String: return .string(s)
        case let a as [Any]: return .array(a.compactMap { from(any: $0) })
        case let o as [String: Any]:
            var result: [String: JSONValue] = [:]
            for (key, value) in o {
                if let v = from(any: value) { result[key] = v }
            }
            return .object(result)
        default: return nil
        }
    }

    var asAny: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .int(let i): return i
        case .double(let d): return d
        case .string(let s): return s
        case .array(let a): return a.map(\.asAny)
        case .object(let o): return o.mapValues(\.asAny)
        }
    }

    var data: Data? {
        try? JSONEncoder().encode(self)
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

// MARK: - RPC 值（packages/rpc/src/serialization.ts 的 Swift 移植）

/// 序列化值域：与 zcode RPC serialize/deserialize 的类型标签一一对应。
/// Undefined=0 / String=1 / Buffer=2 / VSBuffer=3 / Array=4 / Object=5(JSON) / Int=6(VQL)。
enum RPCValue {
    case undefined
    case string(String)
    case buffer(Data)
    case vsbuffer(Data)
    case array([RPCValue])
    case int(Int)
    /// Object 标签：JSON 编解码（嵌套 Uint8Array 以 {__zcode_rpc_nested_uint8array_v1, base64} 恢复）
    case object(JSONValue)

    /// 便捷构造：把 JSON 兼容值包成 object 标签（RPC 参数主体形态）。
    static func json(_ value: JSONValue) -> RPCValue {
        .object(value)
    }

    static func jsonObject(_ build: (inout JSONObjectBuilder) -> Void) -> RPCValue {
        .object(JSONObjectBuilder.build(build))
    }

    /// 整数值访问（header 元素 [type, id, …] 直读）
    var intValue: Int? {
        switch self {
        case .int(let i): return i
        default: return jsonValue?.intValue
        }
    }

    var stringValue: String? {
        if case .string(let s) = self { return s }
        return jsonValue?.stringValue
    }

    /// 解析为 JSON（object/array 值）；其余返回 nil。
    var jsonValue: JSONValue? {
        switch self {
        case .object(let v): return v
        case .array(let items):
            var converted: [JSONValue] = []
            for item in items {
                switch item {
                case .string(let s): converted.append(.string(s))
                case .int(let i): converted.append(.int(i))
                case .buffer, .vsbuffer: return nil
                case .undefined: converted.append(.null)
                case .object(let o): converted.append(o)
                case .array: return nil
                }
            }
            return .array(converted)
        default: return nil
        }
    }
}

/// JSON object 构建器（避免手写字典字面量的噪音）
struct JSONObjectBuilder {
    var fields: [String: JSONValue] = [:]

    mutating func set(_ key: String, _ value: JSONValue?) {
        if let value { fields[key] = value }
    }

    mutating func set(_ key: String, _ value: String?) {
        if let value { fields[key] = .string(value) }
    }

    mutating func set(_ key: String, _ value: Int?) {
        if let value { fields[key] = .int(value) }
    }

    mutating func set(_ key: String, _ value: Bool?) {
        if let value { fields[key] = .bool(value) }
    }

    static func build(_ populate: (inout JSONObjectBuilder) -> Void) -> JSONValue {
        var builder = JSONObjectBuilder()
        populate(&builder)
        return .object(builder.fields)
    }
}

// MARK: - 序列化（serialization.ts 逐行移植）

enum RPCSerialization {

    // MARK: VQL（Variable-Length Quantity：7 bit 数据 + 高位续传标记）

    static func readIntVQL(_ data: Data, _ offset: inout Int) -> Int? {
        var value = 0
        var n = 0
        while true {
            guard offset < data.count else { return nil }
            let byte = data[data.startIndex + offset]
            offset += 1
            value |= Int(byte & 0b0111_1111) << n
            if byte & 0b1000_0000 == 0 {
                return value
            }
            n += 7
            if n > 63 { return nil }
        }
    }

    static func writeIntVQL(_ value: Int) -> Data {
        var v = UInt64(bitPattern: Int64(value))
        if v == 0 { return Data([0x00]) }
        var bytes: [UInt8] = []
        while v != 0 {
            var byte = UInt8(v & 0b0111_1111)
            v >>= 7
            if v != 0 { byte |= 0b1000_0000 }
            bytes.append(byte)
        }
        return Data(bytes)
    }

    // MARK: serialize

    static func serialize(_ value: RPCValue) -> Data {
        var out = Data()
        append(value, to: &out)
        return out
    }

    private static func appendString(_ string: String, tag: UInt8, to out: inout Data) {
        let utf8 = Data(string.utf8)
        out.append(tag)
        out.append(writeIntVQL(utf8.count))
        out.append(utf8)
    }

    private static func append(_ value: RPCValue, to out: inout Data) {
        switch value {
        case .undefined:
            out.append(0)
        case .string(let s):
            appendString(s, tag: 1, to: &out)
        case .buffer(let data):
            out.append(2)
            out.append(writeIntVQL(data.count))
            out.append(data)
        case .vsbuffer(let data):
            out.append(3)
            out.append(writeIntVQL(data.count))
            out.append(data)
        case .array(let items):
            out.append(4)
            out.append(writeIntVQL(items.count))
            for item in items {
                append(item, to: &out)
            }
        case .int(let i):
            // 与 JS (data|0)===data 口径一致：32 位整数走 VQL；负数按二补数无符号展开
            let truncated = UInt32(bitPattern: Int32(truncatingIfNeeded: i))
            out.append(6)
            out.append(writeIntVQL(Int(truncated)))
        case .object(let json):
            let encoded = encodeJSONWithNestedBinary(json)
            appendString(String(data: encoded, encoding: .utf8) ?? "null", tag: 5, to: &out)
        }
    }

    /// Object 走 JSON；此处的 JSON 已在构造期把嵌套二进制换成了标记对象（见 jsonValue(from:)）。
    private static func encodeJSONWithNestedBinary(_ value: JSONValue) -> Data {
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(value) else { return Data("null".utf8) }
        return data
    }

    // MARK: deserialize

    /// 从 data 的 offset 起反序列化一个值；返回 (值, 新 offset)。失败返回 nil。
    static func deserialize(_ data: Data, _ offset: inout Int) -> RPCValue? {
        guard offset < data.count else { return nil }
        let tag = data[data.startIndex + offset]
        offset += 1
        switch tag {
        case 0:
            return .undefined
        case 1:
            guard let length = readIntVQL(data, &offset),
                  offset + length <= data.count else { return nil }
            let slice = data.subdata(in: (data.startIndex + offset)..<(data.startIndex + offset + length))
            offset += length
            return .string(String(data: slice, encoding: .utf8) ?? "")
        case 2:
            guard let length = readIntVQL(data, &offset),
                  offset + length <= data.count else { return nil }
            let slice = data.subdata(in: (data.startIndex + offset)..<(data.startIndex + offset + length))
            offset += length
            return .buffer(slice)
        case 3:
            guard let length = readIntVQL(data, &offset),
                  offset + length <= data.count else { return nil }
            let slice = data.subdata(in: (data.startIndex + offset)..<(data.startIndex + offset + length))
            offset += length
            return .vsbuffer(slice)
        case 4:
            guard let count = readIntVQL(data, &offset) else { return nil }
            var items: [RPCValue] = []
            items.reserveCapacity(min(count, 4096))
            for _ in 0..<count {
                guard let item = deserialize(data, &offset) else { return nil }
                items.append(item)
            }
            return .array(items)
        case 5:
            guard let length = readIntVQL(data, &offset),
                  offset + length <= data.count else { return nil }
            let slice = data.subdata(in: (data.startIndex + offset)..<(data.startIndex + offset + length))
            offset += length
            guard let json = try? JSONDecoder().decode(JSONValue.self, from: slice) else { return nil }
            return .object(json)
        case 6:
            guard let value = readIntVQL(data, &offset) else { return nil }
            // 负数按 32 位二补数还原
            let asInt32 = Int(Int32(bitPattern: UInt32(truncatingIfNeeded: value)))
            return .int(asInt32)
        default:
            return nil
        }
    }

    // MARK: 嵌套 Uint8Array 标记说明

    /// TS 端 Object JSON 内嵌 Uint8Array 会编码为 {__zcode_rpc_nested_uint8array_v1: true, base64: "…"}；
    /// 本客户端当前业务面（file.readFileRange 顶层 Buffer 标签等）不消费嵌套二进制，
    /// 解析后保持标记对象原样，由消费方按 marker 自行解码（serialization.ts:136,220-250 口径）。
}

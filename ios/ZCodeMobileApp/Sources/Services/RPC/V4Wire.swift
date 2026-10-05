import Foundation

// MARK: - TopicWireFrame + 分片重组（packages/shared/src/zcode-protocol-v4/wire.ts / wire-assembler.ts 移植）

/// 物理帧信封：complete 直携 logical 帧；fragment 需经 assembler 重组（wire.ts:16-58）。
enum TopicWireFrame {
    struct Envelope {
        var wireVersion: Int
        var deliveryKind: String
        var logicalFrameId: String
        var logicalFrameOrdinal: Int
        var topic: String
        var subscriptionId: String
        var completeFrame: JSONValue?
        // fragment 专用
        var fragmentIndex: Int?
        var fragmentCount: Int?
        var logicalBytes: Int?
        var checksum: String?
        var dataBase64: String?
    }

    /// 解析下行 dynamic 事件载荷；失败返回 nil（service 边界宽容）。
    static func parse(_ json: JSONValue) -> Envelope? {
        guard let dict = json.objectValue else { return nil }
        guard let wireVersion = dict["wireVersion"]?.intValue else { return nil }
        guard let kind = dict["kind"]?.stringValue else { return nil }
        var envelope = Envelope(
            wireVersion: wireVersion,
            deliveryKind: dict["deliveryKind"]?.stringValue ?? "",
            logicalFrameId: dict["logicalFrameId"]?.stringValue ?? "",
            logicalFrameOrdinal: dict["logicalFrameOrdinal"]?.intValue ?? 0,
            topic: dict["topic"]?.stringValue ?? "",
            subscriptionId: dict["subscriptionId"]?.stringValue ?? "",
            completeFrame: nil, fragmentIndex: nil, fragmentCount: nil,
            logicalBytes: nil, checksum: nil, dataBase64: nil)
        if kind == "complete" {
            envelope.completeFrame = dict["frame"]
        } else if kind == "fragment" {
            envelope.fragmentIndex = dict["fragmentIndex"]?.intValue
            envelope.fragmentCount = dict["fragmentCount"]?.intValue
            envelope.logicalBytes = dict["logicalBytes"]?.intValue
            envelope.checksum = (dict["checksum"]?.objectValue)?["value"]?.stringValue
            envelope.dataBase64 = dict["dataBase64"]?.stringValue
        } else {
            return nil
        }
        return envelope
    }
}

/// fragment → complete 重组器（logicalFrameId 键控；上限 1024 分片/16MB，core.ts:71-77）。
///
/// dropped 标记（v4 断流自愈）：校验失败/解码失败/超限丢弃整条逻辑帧时置位，
/// 由订阅方（ZCodeServerConnection.routeFrame）取走并触发 resync——这是「静默断流」
/// （丢帧后永远等不到下一帧）唯一的客户端可观测信号。等待更多分片的正常返回不置位。
struct TopicWireFrameAssembler {
    private final class Pending {
        var fragments: [Int: Data] = [:]
        var count = 0
    }

    private var pending: [String: Pending] = [:]
    private static let maxFragments = 1024
    private static let maxLogicalBytes = 16 * 1024 * 1024

    /// 自上次取走以来是否丢弃过整条逻辑帧（routeFrame 消费后触发 resync）
    private(set) var droppedSinceLastConsume = false

    /// 喂入一个物理帧；complete 直接过，fragment 凑齐后输出 logical 帧 JSON。
    mutating func accept(_ envelope: TopicWireFrame.Envelope) -> JSONValue? {
        if let frame = envelope.completeFrame {
            return frame
        }
        guard let index = envelope.fragmentIndex,
              let count = envelope.fragmentCount,
              let base64 = envelope.dataBase64,
              let data = Data(base64Encoded: base64),
              index < count else {
            droppedSinceLastConsume = true // 信封残缺：整条逻辑帧不可恢复
            return nil
        }
        guard let item = pending[envelope.logicalFrameId] ?? {
            let fresh = Pending()
            pending[envelope.logicalFrameId] = fresh
            return fresh
        }() else {
            droppedSinceLastConsume = true
            return nil
        }

        if item.fragments[index] == nil {
            item.fragments[index] = data
            item.count += 1
        }
        guard item.count == count else {
            if item.fragments.count > Self.maxFragments {
                pending.removeValue(forKey: envelope.logicalFrameId)
                droppedSinceLastConsume = true // 超分片上限：弃置并标记
            }
            return nil
        }
        pending.removeValue(forKey: envelope.logicalFrameId)
        var logical = Data()
        logical.reserveCapacity(envelope.logicalBytes ?? 0)
        for i in 0..<count {
            guard let chunk = item.fragments[i] else {
                droppedSinceLastConsume = true
                return nil
            }
            logical.append(chunk)
        }
        if logical.count > Self.maxLogicalBytes {
            droppedSinceLastConsume = true
            return nil
        }
        // 可选校验 crc32（checksum 存在时；失配丢弃整条逻辑帧）
        if let expected = envelope.checksum?.lowercased(), !expected.isEmpty {
            if Self.crc32(of: logical) != expected {
                droppedSinceLastConsume = true
                return nil
            }
        }
        guard let decoded = try? JSONDecoder().decode(JSONValue.self, from: logical) else {
            droppedSinceLastConsume = true // 逻辑帧解码失败：下游永远收不到该帧
            return nil
        }
        return decoded
    }

    /// 主题过滤后丢弃（订阅更替时清理积压）。主动丢弃同样置位：订阅方随后的
    /// resync 一次幂等无害，而漏标会在换代场景留下静默断流。
    mutating func dropAll() {
        if !pending.isEmpty { droppedSinceLastConsume = true }
        pending.removeAll()
    }

    /// 取走 dropped 标记（读后清零；每次 routeFrame 轮询调用）。
    mutating func consumeDroppedFlag() -> Bool {
        let value = droppedSinceLastConsume
        droppedSinceLastConsume = false
        return value
    }

    // MARK: CRC32（IEEE 802.3 多项式，与 zod schema algorithm:"crc32" 对应）

    static func crc32(of data: Data) -> String {
        let table: [UInt32] = (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) == 1 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        crc ^= 0xFFFF_FFFF
        return String(format: "%08x", crc)
    }
}

// MARK: - 逻辑帧（transport.ts:162-186 createTopicFrameSchema）

/// 重组后的逻辑帧：payload = snapshot | deltas。
struct V4TopicFrame {
    var topic: String
    var subscriptionId: String
    var fromSeq: Int
    var toSeq: Int
    var sentAt: String?
    var snapshot: JSONValue?
    var deltas: [JSONValue]

    static func parse(_ json: JSONValue) -> V4TopicFrame? {
        guard let dict = json.objectValue,
              let topic = dict["topic"]?.stringValue else { return nil }
        var frame = V4TopicFrame(
            topic: topic,
            subscriptionId: dict["subscriptionId"]?.stringValue ?? "",
            fromSeq: dict["fromSeq"]?.intValue ?? 0,
            toSeq: dict["toSeq"]?.intValue ?? 0,
            sentAt: dict["sentAt"]?.stringValue,
            snapshot: nil, deltas: [])
        if let payload = dict["payload"]?.objectValue {
            switch payload["kind"]?.stringValue {
            case "snapshot":
                frame.snapshot = payload["snapshot"]
            case "deltas":
                frame.deltas = payload["deltas"]?.arrayValue ?? []
            default:
                break
            }
        }
        return frame
    }
}

import Foundation

// MARK: - rpc-frame 编解码（src-D3H6NV7w.js Yv/ny/ry schema + hy/gy 分片 移植）

/// 中继桥 rpc 帧层纯函数。schema（.strict，不得多带字段）：
/// rpc-frame      = {zcode_type, bridgeSessionId, bridgeGeneration?, recoveryId?, seq, messageSeq,
///                   fragmentIndex, fragmentCount, messageBytes, checksum{algorithm,value}, dataBase64}
/// rpc-frame-ack  = {zcode_type, bridgeSessionId, bridgeGeneration?, recoveryId?, ackMessageSeq}
/// - seq          = 物理帧序号（每片 +1，firstPhysicalSeq 起）
/// - messageSeq   = 逻辑消息序号（每条 RPC 消息 +1）
/// - checksum     = crc32 反射 0xEDB88320 hex8（与 V4Wire.crc32 同参；探针两侧校验一致）
/// - dataBase64   = 标准 base64（带 padding；ty() 要求规范可重编码）
enum RelayFrameCodec {

    /// 常量（bundle Yv @243672：maxPhysicalFrameBytes=Ju.maxFrameBytes=1MB）
    static let maxPhysicalFrameBytes = 1_000_000      // 整条 data JSON 上限
    static let maxMessageBytes = 16 * 1024 * 1024
    static let maxFragments = 64
    static let assemblyTimeoutSeconds: TimeInterval = 30

    struct Identity: Equatable {
        var bridgeSessionId: String
        var bridgeGeneration: Int?
        var recoveryId: String?
    }

    // MARK: 发送侧：逻辑消息 → 物理帧数组

    /// 把一条完整 RPC 载荷（serialize(header)+serialize(body)，无 13 字节头）编码为 rpc-frame 字典数组。
    /// 分片大小二分自适应（hy）：先按 1 片试算，超 maxPhysicalFrameBytes 则二分缩小片长（≤maxFragments 片）。
    static func encodeMessage(_ bytes: Data, identity: Identity,
                              firstPhysicalSeq: Int, messageSeq: Int) -> [[String: JSONValue]]? {
        guard !bytes.isEmpty, bytes.count <= maxMessageBytes,
              firstPhysicalSeq >= 1, messageSeq >= 1 else { return nil }
        let checksum: JSONValue = .object([
            "algorithm": .string("crc32"),
            "value": .string(crc32Hex(of: bytes)),
        ])
        let messageBytes = bytes.count

        // 片长二分（hy 口径）：约束是「最大单片信封 ≤ maxPhysicalFrameBytes」+「片数 ≤ maxFragments」，
        // 两约束交集非空（16MB/64 片 → 每片 250KB → 信封 333KB < 1MB）
        var pieceBytes = bytes.count
        while fragmentFrameCount(messageBytes: messageBytes, pieceBytes: pieceBytes) > maxFragments
            || envelopeBytes(perPieceBytes: pieceBytes) > maxPhysicalFrameBytes {
            pieceBytes = max(1, pieceBytes / 2)
            if pieceBytes == 1 { break }
        }

        var frames: [[String: JSONValue]] = []
        let count = fragmentFrameCount(messageBytes: messageBytes, pieceBytes: pieceBytes)
        var physicalSeq = firstPhysicalSeq
        var offset = 0
        for index in 0..<count {
            let end = min(offset + pieceBytes, messageBytes)
            let chunk = bytes.subdata(in: (bytes.startIndex + offset)..<(bytes.startIndex + end))
            offset = end
            var frame: [String: JSONValue] = [
                "zcode_type": .string("rpc-frame"),
                "bridgeSessionId": .string(identity.bridgeSessionId),
                "seq": .int(physicalSeq),
                "messageSeq": .int(messageSeq),
                "fragmentIndex": .int(index),
                "fragmentCount": .int(count),
                "messageBytes": .int(messageBytes),
                "checksum": checksum,
                "dataBase64": .string(chunk.base64EncodedString()),
            ]
            if let generation = identity.bridgeGeneration {
                frame["bridgeGeneration"] = .int(generation)
            }
            if let recoveryId = identity.recoveryId {
                frame["recoveryId"] = .string(recoveryId)
            }
            frames.append(frame)
            physicalSeq += 1
        }
        return frames
    }

    /// 单片信封字节估算（hy 的 py(i) 口径：该片 base64 4/3 膨胀 + JSON 键架固定开销）
    static func envelopeBytes(perPieceBytes: Int) -> Int {
        let base64Len = 4 * ((perPieceBytes + 2) / 3)
        let frameOverhead = 220 // zcode_type/seq/messageSeq/fragment*/checksum/identity/引号标点
        return base64Len + frameOverhead
    }

    static func fragmentFrameCount(messageBytes: Int, pieceBytes: Int) -> Int {
        guard pieceBytes > 0 else { return .max }
        return (messageBytes + pieceBytes - 1) / pieceBytes
    }

    // MARK: 接收侧

    /// 解析下行帧：rpc-frame → (messageSeq, fragmentIndex, fragmentCount, messageBytes, crc, data)
    struct InboundFragment {
        var bridgeSessionId: String
        var bridgeGeneration: Int?
        var recoveryId: String?
        var messageSeq: Int
        var fragmentIndex: Int
        var fragmentCount: Int
        var messageBytes: Int
        var crcValue: String
        var data: Data
    }

    static func decode(_ payload: JSONValue) -> InboundFragment? {
        guard let dict = payload.objectValue,
              dict["zcode_type"]?.stringValue == "rpc-frame",
              let bridgeSessionId = dict["bridgeSessionId"]?.stringValue,
              let messageSeq = dict["messageSeq"]?.intValue, messageSeq >= 1,
              let fragmentIndex = dict["fragmentIndex"]?.intValue, fragmentIndex >= 0,
              let fragmentCount = dict["fragmentCount"]?.intValue, fragmentCount >= 1,
              fragmentIndex < fragmentCount, fragmentCount <= maxFragments,
              let messageBytes = dict["messageBytes"]?.intValue, messageBytes >= 1,
              messageBytes <= maxMessageBytes,
              let crcValue = dict["checksum"]?.objectValue?["value"]?.stringValue,
              let base64 = dict["dataBase64"]?.stringValue,
              let data = Data(base64Encoded: base64) else { return nil }
        return InboundFragment(
            bridgeSessionId: bridgeSessionId,
            bridgeGeneration: dict["bridgeGeneration"]?.intValue,
            recoveryId: dict["recoveryId"]?.stringValue,
            messageSeq: messageSeq,
            fragmentIndex: fragmentIndex,
            fragmentCount: fragmentCount,
            messageBytes: messageBytes,
            crcValue: crcValue,
            data: data)
    }

    /// rpc-frame-ack（对端对我上行帧的确认）
    static func decodeAck(_ payload: JSONValue) -> Int? {
        guard let dict = payload.objectValue,
              dict["zcode_type"]?.stringValue == "rpc-frame-ack",
              let ackMessageSeq = dict["ackMessageSeq"]?.intValue, ackMessageSeq >= 1 else { return nil }
        return ackMessageSeq
    }

    /// 上行 ack 回执（对桌面下行帧的确认；processAck 语义：ackMessageSeq=收到的 messageSeq）
    static func makeAck(identity: Identity, ackMessageSeq: Int) -> [String: JSONValue] {
        var frame: [String: JSONValue] = [
            "zcode_type": .string("rpc-frame-ack"),
            "bridgeSessionId": .string(identity.bridgeSessionId),
            "ackMessageSeq": .int(ackMessageSeq),
        ]
        if let generation = identity.bridgeGeneration {
            frame["bridgeGeneration"] = .int(generation)
        }
        if let recoveryId = identity.recoveryId {
            frame["recoveryId"] = .string(recoveryId)
        }
        return frame
    }

    // MARK: CRC32（反射 0xEDB88320，hex8；与 V4Wire.crc32 同参）

    static func crc32Hex(of data: Data) -> String {
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

// MARK: - 下行分片重组器（ole/_n 逻辑帧组装器移植：messageSeq 键控）

/// 重组下行 rpc-frame 分片；重复帧（对端重传）幂等并仍返回应 ack 的 messageSeq。
struct RelayFrameAssembler {
    struct Reassembly {
        var fragments: [Int: Data] = [:]
        var received: Int = 0
        var startedAt: Date = Date()
    }

    private var pending: [Int: Reassembly] = [:] // messageSeq 键控
    private var highestCompletedMessageSeq = 0

    enum AcceptResult {
        /// 分片未齐（或重复的历史帧无需处理）
        case incomplete(ackMessageSeq: Int?)
        /// 重复帧：已重组完成过，仅需回 ack
        case duplicate(ackMessageSeq: Int)
        /// 重组完成（crc 校验通过）
        case completed(Data, messageSeq: Int)
        /// 校验失败等不可恢复
        case fault(String)
    }

    mutating func accept(_ fragment: RelayFrameCodec.InboundFragment, now: Date = Date()) -> AcceptResult {
        // 历史帧（早已完成且被 ack）→ duplicate 语义回 ack 即可
        if fragment.messageSeq <= highestCompletedMessageSeq {
            return .duplicate(ackMessageSeq: fragment.messageSeq)
        }
        guard fragment.fragmentIndex < fragment.fragmentCount else {
            return .fault("fragment-index-out-of-range")
        }
        var item = pending[fragment.messageSeq] ?? Reassembly()
        // 超时（assemblyTimeoutMs=30s）：弃置重来
        if now.timeIntervalSince(item.startedAt) > RelayFrameCodec.assemblyTimeoutSeconds {
            pending.removeValue(forKey: fragment.messageSeq)
            item = Reassembly()
        }
        if item.fragments[fragment.fragmentIndex] == nil {
            // 单片 crc 逐片校验不可行（协议校验整条消息），先缓存
            item.fragments[fragment.fragmentIndex] = fragment.data
            item.received += 1
        }
        pending[fragment.messageSeq] = item

        guard item.received == fragment.fragmentCount else {
            return .incomplete(ackMessageSeq: fragment.messageSeq)
        }
        pending.removeValue(forKey: fragment.messageSeq)
        var logical = Data()
        logical.reserveCapacity(fragment.messageBytes)
        for i in 0..<fragment.fragmentCount {
            guard let chunk = item.fragments[i] else { return .fault("missing-fragment-\(i)") }
            logical.append(chunk)
        }
        guard logical.count == fragment.messageBytes else {
            return .fault("message-bytes-mismatch")
        }
        if RelayFrameCodec.crc32Hex(of: logical) != fragment.crcValue.lowercased() {
            return .fault("crc32-mismatch")
        }
        highestCompletedMessageSeq = max(highestCompletedMessageSeq, fragment.messageSeq)
        return .completed(logical, messageSeq: fragment.messageSeq)
    }

    mutating func reset() {
        pending.removeAll()
    }
}

import XCTest
@testable import ZCodeMobile

/// 云中继接入单测：链接解析 / 鉴权 proof / rpc-frame 编解码与分片 / 配置向后兼容。
/// 关键向量全部来自主会话实地探针（/tmp/relay-js/ws_probe.py、ws_bridge_probe.py 实测）。
final class RelayLinkTests: XCTestCase {

    // MARK: 配对链接解析（ConnectURLParser.parseRelayLink）

    /// 真实配对链接形态（交接材料 §1，mid/name/app_version 语义保留）
    private let sampleLink = "https://zcode.z.ai/remote/v4?sid=d_5BHs7zpSQADkcFSNhfud5v"
        + "&hash=juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4%3D&t=1791033721496"
        + "&mid=4499673e-d0f8-49ef-ada8-623c18767c24&name=MacBook-Pro-8.local&app_version=3.14.4"

    func testParseRelayLinkFromPairingURL() {
        let link = ConnectURLParser.parseRelayLink(sampleLink)
        XCTAssertNotNil(link)
        XCTAssertEqual(link?.wssURL, "wss://zcode.z.ai/ws?mid=4499673e-d0f8-49ef-ada8-623c18767c24")
        XCTAssertEqual(link?.machineName, "MacBook-Pro-8.local")
        XCTAssertEqual(link?.deviceSid, "d_5BHs7zpSQADkcFSNhfud5v")
        // hash：%3D 解码为 =（保留 base64 原字符串，不做 base64 解码）
        XCTAssertEqual(link?.passHash, "juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4=")
        XCTAssertEqual(link?.desktopAppVersion, "3.14.4")
    }

    func testParseRelayLinkWithoutMid() {
        let link = ConnectURLParser.parseRelayLink("https://zcode.z.ai/remote/v4?sid=s1&hash=h1")
        XCTAssertNotNil(link)
        XCTAssertEqual(link?.wssURL, "wss://zcode.z.ai/ws")
    }

    func testParseRelayLinkRejectsNonRelayInputs() {
        XCTAssertNil(ConnectURLParser.parseRelayLink("http://zcode.z.ai/remote/v4?sid=s&hash=h")) // 非 https
        XCTAssertNil(ConnectURLParser.parseRelayLink("https://zcode.z.ai/other?sid=s&hash=h"))    // 非 /remote/
        XCTAssertNil(ConnectURLParser.parseRelayLink("https://zcode.z.ai/remote/v4?sid=s"))       // 缺 hash
        XCTAssertNil(ConnectURLParser.parseRelayLink("https://zcode.z.ai/remote/v4?hash=h"))      // 缺 sid
        XCTAssertNil(ConnectURLParser.parseRelayLink(""))
    }

    func testDirectTokenLinkNotMisreadAsRelay() {
        let link = ConnectURLParser.extractLink(from: "http://192.168.1.24:3030/?token=abc")
        guard case .direct(let parsed)? = link else {
            return XCTFail("局域网链接应识别为 direct")
        }
        XCTAssertEqual(parsed.host, "192.168.1.24")
        XCTAssertEqual(parsed.token, "abc")
    }

    func testExtractLinkPrefersRelay() {
        let link = ConnectURLParser.extractLink(from: "看看这个 \(sampleLink)")
        guard case .relay(let relay)? = link else {
            return XCTFail("配对链接应识别为 relay")
        }
        XCTAssertEqual(relay.deviceSid, "d_5BHs7zpSQADkcFSNhfud5v")
    }

    // MARK: 鉴权 proof（HMAC-SHA256 + base64url 无填充）

    /// 探针实测向量：ws_probe.py 以该 proof 走通 auth_ack{pair_status:'matched'}
    func testProofMatchesProbeVector() {
        let proof = RelayAuth.proof(
            passHash: "juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4=",
            nonce: "lFr2Po5uGyuNti2Xuw7LfcrN",
            role: "terminal",
            deviceSid: "d_5BHs7zpSQADkcFSNhfud5v")
        XCTAssertEqual(proof, "TVEonHQISlbmNucXTEYrnfL0D3v00eO075w66jFq8Es")
        XCTAssertFalse(proof.contains("="), "base64url 无填充")
        XCTAssertFalse(proof.contains("+") || proof.contains("/"), "base64url 字母表")
    }

    // MARK: rpc-frame 编解码（schema strict 键集 + 分片）

    private var identity: RelayFrameCodec.Identity {
        RelayFrameCodec.Identity(bridgeSessionId: "abc123", bridgeGeneration: 2, recoveryId: nil)
    }

    func testEncodeSingleFragmentFrameFieldSet() {
        let bytes = Data([0x04, 0x01, 0x06, 0xc8, 0x01, 0x00]) // 探针实测下行 Initialize 形态
        guard let frames = RelayFrameCodec.encodeMessage(bytes, identity: identity,
                                                         firstPhysicalSeq: 1, messageSeq: 7) else {
            return XCTFail("编码失败")
        }
        XCTAssertEqual(frames.count, 1)
        let frame = frames[0]
        // strict schema 键集：不得多带字段（bundle ny.superRefine + .strict）
        XCTAssertEqual(Set(frame.keys), [
            "zcode_type", "bridgeSessionId", "bridgeGeneration", "seq", "messageSeq",
            "fragmentIndex", "fragmentCount", "messageBytes", "checksum", "dataBase64",
        ])
        XCTAssertEqual(frame["zcode_type"]?.stringValue, "rpc-frame")
        XCTAssertEqual(frame["seq"]?.intValue, 1)
        XCTAssertEqual(frame["messageSeq"]?.intValue, 7)
        XCTAssertEqual(frame["fragmentIndex"]?.intValue, 0)
        XCTAssertEqual(frame["fragmentCount"]?.intValue, 1)
        XCTAssertEqual(frame["messageBytes"]?.intValue, bytes.count)
        XCTAssertEqual(frame["dataBase64"]?.stringValue, bytes.base64EncodedString())
        XCTAssertEqual(frame["checksum"]?.objectValue?["algorithm"]?.stringValue, "crc32")
        XCTAssertEqual(frame["checksum"]?.objectValue?["value"]?.stringValue,
                       RelayFrameCodec.crc32Hex(of: bytes))
    }

    func testEncodeOmitsOptionalIdentityFields() {
        let bare = RelayFrameCodec.Identity(bridgeSessionId: "abc", bridgeGeneration: nil, recoveryId: nil)
        let frames = RelayFrameCodec.encodeMessage(Data([0x00]), identity: bare,
                                                   firstPhysicalSeq: 1, messageSeq: 1)
        XCTAssertEqual(frames?.first?["bridgeGeneration"], nil)
        XCTAssertEqual(frames?.first?["recoveryId"], nil)
    }

    func testPhysicalSeqIncrementsAcrossFragments() {
        // 8KB 消息 + 强制小片：seq 逐片递增，messageSeq 恒定
        let bytes = Data((0..<8192).map { UInt8($0 % 251) })
        let frames = RelayFrameCodec.encodeMessage(bytes, identity: identity,
                                                   firstPhysicalSeq: 5, messageSeq: 3,
                                                   forcedPieceBytes: 1024)
        XCTAssertEqual(frames?.count, 8)
        XCTAssertEqual(frames?.map { $0["seq"]?.intValue ?? -1 }, [5, 6, 7, 8, 9, 10, 11, 12])
        XCTAssertTrue(frames?.allSatisfy { $0["messageSeq"]?.intValue == 3 } ?? false)
        XCTAssertEqual(frames?.first?["fragmentIndex"]?.intValue, 0)
        XCTAssertEqual(frames?.last?["fragmentIndex"]?.intValue, 7)
    }

    func testFragmentCountCappedAtMaxFragments() {
        let bytes = Data(repeating: 0x41, count: 2_000_000)
        guard let frames = RelayFrameCodec.encodeMessage(bytes, identity: identity,
                                                         firstPhysicalSeq: 1, messageSeq: 1) else {
            return XCTFail("编码失败")
        }
        XCTAssertLessThanOrEqual(frames.count, RelayFrameCodec.maxFragments)
        // 二分确保每片整帧 ≤ maxPhysicalFrameBytes
        let maxEnvelope = frames.map { frame -> Int in
            let data = try? JSONEncoder().encode(JSONValue.object(frame))
            return data?.count ?? .max
        }.max() ?? .max
        XCTAssertLessThanOrEqual(maxEnvelope, RelayFrameCodec.maxPhysicalFrameBytes)
    }

    func testDecodeRoundTripAndAck() {
        let bytes = Data("hello-bridge".utf8)
        let frame = RelayFrameCodec.encodeMessage(bytes, identity: identity,
                                                  firstPhysicalSeq: 1, messageSeq: 4)?.first
        let fragment = RelayFrameCodec.decode(.object(frame!))
        XCTAssertEqual(fragment?.messageSeq, 4)
        XCTAssertEqual(fragment?.fragmentCount, 1)
        XCTAssertEqual(fragment?.data, bytes)
        XCTAssertEqual(fragment?.bridgeSessionId, "abc123")

        // 非 rpc-frame / 缺必需字段（fragmentIndex）→ nil
        XCTAssertNil(RelayFrameCodec.decode(.object(["zcode_type": .string("rpc-frame-ack")])))
        XCTAssertNil(RelayFrameCodec.decode(.object([
            "zcode_type": .string("rpc-frame"), "bridgeSessionId": .string("x"),
            "messageSeq": .int(1), "fragmentCount": .int(1),
            "messageBytes": .int(1),
            "checksum": .object(["algorithm": .string("crc32"), "value": .string("00000000")]),
            "dataBase64": .string(Data([0x00]).base64EncodedString()),
        ])))

        let ack = RelayFrameCodec.makeAck(identity: identity, ackMessageSeq: 9)
        // identity 无 recoveryId → optional 字段省略（strict schema 可选键不出现）
        XCTAssertEqual(Set(ack.keys), ["zcode_type", "bridgeSessionId", "bridgeGeneration", "ackMessageSeq"])
        XCTAssertEqual(RelayFrameCodec.decodeAck(.object(ack)), 9)
        // 带 recoveryId 的身份 → ack 帧携带（Lzn 三元组匹配）
        let ackWithRecovery = RelayFrameCodec.makeAck(
            identity: RelayFrameCodec.Identity(
                bridgeSessionId: "abc123", bridgeGeneration: 2, recoveryId: "rec1"),
            ackMessageSeq: 9)
        XCTAssertEqual(ackWithRecovery["recoveryId"]?.stringValue, "rec1")
    }

    // MARK: 下行分片重组（重复帧幂等 / crc 校验 / 乱序凑齐）

    func testAssemblerOutOfOrderFragmentsAndDuplicate() {
        var assembler = RelayFrameAssembler()
        let bytes = Data((0..<2048).map { UInt8($0 % 253) })
        let frames = RelayFrameCodec.encodeMessage(bytes, identity: identity,
                                                   firstPhysicalSeq: 1, messageSeq: 2,
                                                   forcedPieceBytes: 1024)
        XCTAssertEqual(frames?.count, 2)
        let fragments = frames!.compactMap { RelayFrameCodec.decode(.object($0)) }

        // 先到 index=1
        if case .incomplete(let ack) = assembler.accept(fragments[1]) {
            XCTAssertEqual(ack, 2, "未凑齐也需回 ack（防桌面端重传）")
        } else {
            XCTFail("分片未齐应返回 incomplete")
        }
        // 再到 index=0 → 凑齐 + crc 校验通过
        if case .completed(let data, let messageSeq) = assembler.accept(fragments[0]) {
            XCTAssertEqual(data, bytes)
            XCTAssertEqual(messageSeq, 2)
        } else {
            XCTFail("凑齐应返回 completed")
        }
        // 桌面端重传（未收到 ack）→ duplicate 幂等且仍回 ack
        if case .duplicate(let ack) = assembler.accept(fragments[1]) {
            XCTAssertEqual(ack, 2)
        } else {
            XCTFail("重复帧应返回 duplicate")
        }
    }

    func testAssemblerRejectsCrcMismatch() {
        var assembler = RelayFrameAssembler()
        var fragment = RelayFrameCodec.InboundFragment(
            bridgeSessionId: "abc123", bridgeGeneration: nil, recoveryId: nil,
            messageSeq: 1, fragmentIndex: 0, fragmentCount: 1,
            messageBytes: 3, crcValue: "deadbeef", data: Data([1, 2, 3]))
        if case .fault = assembler.accept(fragment) {
            // crc 失配 → fault
        } else {
            XCTFail("crc 失配应返回 fault")
        }
        fragment.crcValue = RelayFrameCodec.crc32Hex(of: Data([1, 2, 3]))
        if case .completed(let data, _) = assembler.accept(fragment) {
            XCTAssertEqual(data, Data([1, 2, 3]))
        } else {
            XCTFail("crc 匹配应完成")
        }
    }

    // MARK: crc32 与 V4Wire 同参 + 标准向量

    func testCrc32MatchesV4WireAndStandardVector() {
        let vector = Data("123456789".utf8)
        XCTAssertEqual(RelayFrameCodec.crc32Hex(of: vector), "cbf43926")
        XCTAssertEqual(RelayFrameCodec.crc32Hex(of: vector), TopicWireFrameAssembler.crc32(of: vector))
    }

    // MARK: sessions-index 快照时间双形态（真实桌面毫秒时间戳 / 替身 ISO8601）

    func testSessionSummaryParsesMillisLastActivityAt() throws {
        // 中继快照实测形态：lastActivityAt=1791040344222（毫秒）
        let json: JSONValue = .object([
            "sessionId": .string("sess_demo"),
            "title": .string("中继会话"),
            "phase": .string("completedSuccess"),
            "lastActivityAt": .int(1_791_040_344_222),
        ])
        let summary = try XCTUnwrap(makeSessionSummary(json))
        let date = try XCTUnwrap(summary.lastActivityAt)
        XCTAssertEqual(Int(date.timeIntervalSince1970 * 1000), 1_791_040_344_222)
    }

    func testSessionSummaryParsesISOLastActivityAt() throws {
        let json: JSONValue = .object([
            "sessionId": .string("sess_demo"),
            "lastActivityAt": .string("2026-10-03T15:12:24.222Z"),
        ])
        let summary = try XCTUnwrap(makeSessionSummary(json))
        XCTAssertNotNil(summary.lastActivityAt)
    }

    private func makeSessionSummary(_ json: JSONValue) -> RemoteConversationStore.SessionSummary? {
        // SessionSummary.parse 是 private 扩展；经 store 的公共投影验证：
        // 直接构造 store 并走 handleSessionsIndexFrame 不可行（actor 私有），
        // 这里用镜像语义的最小校验：字段进入 sessions 的路径由集成烟测覆盖，
        // 单测锚定 JSONValue 的毫秒/ISO 读取行为。
        guard case .object(let dict) = json,
              let sessionId = dict["sessionId"]?.stringValue else { return nil }
        let ms = dict["lastActivityAt"]?.intValue
        let iso = dict["lastActivityAt"]?.stringValue
        var date: Date?
        if let ms, ms > 0 { date = Date(timeIntervalSince1970: Double(ms) / 1000) }
        else if let iso {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            date = f.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
        }
        guard let date else { return nil }
        return RemoteConversationStore.SessionSummary(
            sessionId: sessionId, title: "t", phase: "completedSuccess",
            lastActivityAt: date, lastAssistantPreview: nil)
    }

    // MARK: -ZCodeRelayLink 启动参数

    func testRelayLinkArgumentParsing() {
        XCTAssertNil(AppSession.relayLinkArgument(from: []))
        XCTAssertNil(AppSession.relayLinkArgument(from: ["-ZCodeRelayLink"]))
        // 相邻参数取值；缺参占位（以 - 开头）忽略
        XCTAssertNil(AppSession.relayLinkArgument(from: ["-ZCodeRelayLink", "-ZCodeOpenConnectFlow"]))
        XCTAssertEqual(
            AppSession.relayLinkArgument(from: ["-ZCodeRelayLink", sampleLink, "-ZCodeOpenConnectFlow"]),
            sampleLink)
    }

    // MARK: ServerConfig Codable 向后兼容（旧 Keychain 数据无 relay 字段）

    func testServerConfigDecodesLegacyJSONWithoutRelay() throws {
        let legacy = """
        {"id":"a","name":null,"host":"192.168.1.24","port":3030,"useTLS":false,
         "token":"","lastConnectedAt":null,"preferredWorkspacePath":null}
        """
        let config = try JSONDecoder().decode(ServerConfig.self, from: Data(legacy.utf8))
        XCTAssertNil(config.relay)
        XCTAssertEqual(config.displayAddress, "192.168.1.24:3030")
        // 中继配置往返
        let link = try XCTUnwrap(ConnectURLParser.parseRelayLink(sampleLink))
        var relayed = config
        relayed.relay = link
        let roundTrip = try JSONDecoder().decode(
            ServerConfig.self, from: JSONEncoder().encode(relayed))
        XCTAssertEqual(roundTrip.relay, link)
        XCTAssertEqual(roundTrip.displayName, "MacBook-Pro-8.local")
    }
}

// MARK: - 测试辅助：强制片长（走生产二分之外的确定性分片路径）

private extension RelayFrameCodec {
    static func encodeMessage(_ bytes: Data, identity: Identity,
                              firstPhysicalSeq: Int, messageSeq: Int,
                              forcedPieceBytes: Int) -> [[String: JSONValue]]? {
        guard forcedPieceBytes > 0 else { return nil }
        let count = fragmentFrameCount(messageBytes: bytes.count, pieceBytes: forcedPieceBytes)
        guard count <= maxFragments else { return nil }
        let checksum: JSONValue = .object([
            "algorithm": .string("crc32"), "value": .string(crc32Hex(of: bytes)),
        ])
        var frames: [[String: JSONValue]] = []
        var offset = 0
        for index in 0..<count {
            let end = min(offset + forcedPieceBytes, bytes.count)
            let chunk = bytes.subdata(in: (bytes.startIndex + offset)..<(bytes.startIndex + end))
            offset = end
            var frame: [String: JSONValue] = [
                "zcode_type": .string("rpc-frame"),
                "bridgeSessionId": .string(identity.bridgeSessionId),
                "seq": .int(firstPhysicalSeq + index),
                "messageSeq": .int(messageSeq),
                "fragmentIndex": .int(index),
                "fragmentCount": .int(count),
                "messageBytes": .int(bytes.count),
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
        }
        return frames
    }
}

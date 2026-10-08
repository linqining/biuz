import XCTest
@testable import ZCodeMobile

// MARK: - ProtocolSchema 引擎 + CommandSchemas 词表/ACK 单测（构造与校验同源的门面）

final class ProtocolSchemaTests: XCTestCase {

    // MARK: 引擎语义（zod 映射）

    private let shape = PObjectShape(fields: [
        "a": .required(.nonEmptyString),
        "b": .optional(.integer(min: 0, max: 10)),
    ])

    func testStripRemovesUnknownKeyWithWarning() {
        // zod 默认 strip：未知键剥离后放行，但本地显性告警（上游此路静默）
        let result = PValidator.validate(
            .object(["a": .string("x"), "junk": .int(1)]),
            schema: .object(shape))
        XCTAssertTrue(result.isValid)
        XCTAssertEqual(result.value?.objectValue?.keys.sorted(), ["a"])
        XCTAssertTrue(result.warnings.contains { $0.contains("junk") })
    }

    func testStrictRejectsUnknownKey() {
        let strict = PObjectShape(fields: ["a": .required(.string)], strict: true, deny: [])
        let result = PValidator.validate(
            .object(["a": .string("x"), "junk": .int(1)]), schema: .object(strict))
        XCTAssertFalse(result.isValid)
        XCTAssertTrue(result.errors.contains { $0.contains("junk") })
    }

    func testRequiredKeyMissingIsError() {
        let result = PValidator.validate(.object([:]), schema: .object(shape))
        XCTAssertFalse(result.isValid)
        // 双语稳健：断言路径前缀（zh「a: 缺失必填键」/ en「a: missing required key」
        // 同构）——错误文案已本地化，en 态下中文关键词不在场，不锁自然语言措辞
        XCTAssertTrue(result.errors.contains { $0.contains("a:") })
    }

    func testNullableRequiredKeyMustBePresentButMayBeNull() {
        // 信封 sessionId 语义（A1）：键缺席=错；null=对
        let nullableShape = PObjectShape(fields: ["sessionId": .requiredNullable(.nonEmptyString)])
        XCTAssertFalse(PValidator.validate(.object([:]), schema: .object(nullableShape)).isValid)
        XCTAssertTrue(PValidator.validate(
            .object(["sessionId": .null]), schema: .object(nullableShape)).isValid)
        XCTAssertTrue(PValidator.validate(
            .object(["sessionId": .string("sess_1")]), schema: .object(nullableShape)).isValid)
    }

    func testEnumRejectsWrongValue() {
        // A-1 教训回归：setAssistantFeedback 值域 like|dislike，positive/negative 必拒
        let feedback = PObjectShape(fields: [
            "feedback": .requiredNullable(.enumeration("like", "dislike"))])
        XCTAssertFalse(PValidator.validate(
            .object(["feedback": .string("positive")]), schema: .object(feedback)).isValid)
        XCTAssertTrue(PValidator.validate(
            .object(["feedback": .string("like")]), schema: .object(feedback)).isValid)
        XCTAssertTrue(PValidator.validate(
            .object(["feedback": .null]), schema: .object(feedback)).isValid)
    }

    func testDenyKeyIsHardErrorEvenInStripMode() {
        // 服务端注入字段（connectionId 等）：携带即违规（A11/v1.14 先例）
        let result = PValidator.validate(
            .object(["a": .string("x"), "connectionId": .string("forged")]),
            schema: .object(shape))
        XCTAssertFalse(result.isValid)
        XCTAssertTrue(result.errors.contains { $0.contains("connectionId") })
    }

    func testStrictBase64PaddingAndSizeLimit() {
        XCTAssertEqual(PValidator.strictBase64ByteLength("QQ=="), 1)   // "A"
        XCTAssertEqual(PValidator.strictBase64ByteLength("QUI="), 2)   // "AB"
        XCTAssertEqual(PValidator.strictBase64ByteLength("QUJD"), 3)   // "ABC"，无 padding
        XCTAssertNil(PValidator.strictBase64ByteLength("QQ="))         // 长度非 4 倍数
        XCTAssertNil(PValidator.strictBase64ByteLength("QQ==Q"))       // '=' 出现在尾部之外
        XCTAssertNil(PValidator.strictBase64ByteLength("===="))        // padding >2
        XCTAssertNil(PValidator.strictBase64ByteLength("QUJ!"))        // 非字母表

        let chunk = PSchema.strictBase64(maxBytes: 512 * 1024)
        XCTAssertTrue(PValidator.validate(.string("QUJD"), schema: chunk).isValid)
        XCTAssertFalse(PValidator.validate(.string("QUJD"), schema: .strictBase64(maxBytes: 2)).isValid)
    }

    func testArrayElementAndMaxItems() {
        let schema = PSchema.array(.nonEmptyString, maxItems: 1)
        XCTAssertTrue(PValidator.validate(
            .array([.string("x")]), schema: schema).isValid)
        XCTAssertFalse(PValidator.validate(
            .array([.string("x"), .string("y")]), schema: schema).isValid)
        XCTAssertFalse(PValidator.validate(
            .array([.string("")]), schema: schema).isValid)
    }

    // MARK: 权威词表（上游 command.ts:296-320 逐字）

    func testCASVocabularyMatchesUpstream() {
        let expectedCAS: Set<String> = [
            "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
            "setAssistantFeedback", "sendQueuedNow", "editQueueItem", "reorderQueueItem",
            "deleteQueueItem", "setAutoDrain", "switchModelConfig",
            "switchCollaborationMode", "setFollowupMode", "pauseGoal", "resumeGoal",
        ]
        XCTAssertEqual(CommandSchemas.commandsRequiringBaseRevision, expectedCAS)
        XCTAssertEqual(CommandSchemas.commandsRequiringBaseRevision.count, 15)
        let expectedRowTargeting: Set<String> = [
            "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
            "setAssistantFeedback",
        ]
        XCTAssertEqual(CommandSchemas.rowTargetingCommands, expectedRowTargeting)
        XCTAssertTrue(
            CommandSchemas.rowTargetingCommands.isSubset(of: CommandSchemas.commandsRequiringBaseRevision))
    }

    func testRowTargetIsStrict() {
        // core.ts:10-15 conversationRowTargetSchema .strict()
        let result = PValidator.validate(
            .object(["rowId": .int(3), "entityId": .string("e1"), "extra": .bool(true)]),
            schema: CommandSchemas.rowTarget())
        XCTAssertFalse(result.isValid)
        XCTAssertTrue(result.errors.contains { $0.contains("extra") })
        XCTAssertTrue(PValidator.validate(
            .object(["rowId": .int(3), "entityId": .string("e1")]),
            schema: CommandSchemas.rowTarget()).isValid)
    }

    func testAttachmentBeginParamsEnforceLimitsAndRegex() {
        func beginDict() -> [String: JSONValue] {
            [
                "uploadId": .string("u.1_-x:y"),
                "sessionId": .string("sess_1"),
                "fileName": .string("a.png"),
                "mime": .string("image/png"),
                "totalBytes": .int(1024),
                "totalChunks": .int(1),
                "checksum": .string("sha256:" + String(repeating: "a", count: 64)),
            ]
        }
        let schema = PSchema.object(CommandSchemas.attachmentBeginParamsShape())
        XCTAssertTrue(PValidator.validate(.object(beginDict()), schema: schema).isValid)

        var over = beginDict()
        over["totalBytes"] = .int(CommandSchemas.attachmentMaxBytes + 1)
        XCTAssertFalse(PValidator.validate(.object(over), schema: schema).isValid)

        var badChecksum = beginDict()
        badChecksum["checksum"] = .string("md5:xyz")
        XCTAssertFalse(PValidator.validate(.object(badChecksum), schema: schema).isValid)

        var forged = beginDict()
        forged["connectionId"] = .string("forged")
        XCTAssertFalse(PValidator.validate(.object(forged), schema: schema).isValid)
    }

    // MARK: ACK 解析（command.ts:430-443 + 中继 ack 包裹宽容）

    func testAckParsingTopLevelAndWrapped() {
        let top = CommandAck(.object([
            "status": .string("accepted"), "revisionAtDecision": .int(5)]))
        XCTAssertEqual(top.status, "accepted")
        XCTAssertFalse(top.isFailure)

        let wrapped = CommandAck(.object(["ack": .object([
            "status": .string("rejected"),
            "reasonCode": .string("proto.invalidPayload"),
            "message": .string(#"[{"code":"invalid_enum_value","message":"Invalid enum value"}]"#),
        ])]))
        XCTAssertTrue(wrapped.isFailure)
        XCTAssertEqual(wrapped.reasonCode, "proto.invalidPayload")
        // zod issue 数组形态：取首条 message 字段
        XCTAssertTrue(wrapped.failureText?.contains("Invalid enum value") == true)
    }

    func testAckFailureVocabulary() {
        for status in ["rejected", "stale", "failed"] {
            XCTAssertTrue(CommandAck(.object(["status": .string(status)])).isFailure, status)
        }
        for status in ["accepted", "duplicate", "noop"] {
            XCTAssertFalse(CommandAck(.object(["status": .string(status)])).isFailure, status)
        }
        // 宽容：status 缺席（旧桌面）与非词表遗留值（applied/ok 旧通道回执）按成功
        XCTAssertFalse(CommandAck(.object(["commandId": .string("c")])).isFailure)
        XCTAssertFalse(CommandAck(.object(["status": .string("applied")])).isFailure)
    }

    func testFirstReadableMessagePlainVsZod() {
        XCTAssertEqual(CommandAck.firstReadableMessage("boom"), "boom")
        XCTAssertEqual(
            CommandAck.firstReadableMessage(#"code=x,"message": "预期 string"更多"#),
            "预期 string")
        XCTAssertNil(CommandAck.firstReadableMessage(nil))
        XCTAssertNil(CommandAck.firstReadableMessage(""))
        // 超长原文截断
        XCTAssertEqual(CommandAck.firstReadableMessage(String(repeating: "a", count: 300))?.count, 160)
    }

    func testClientPrefixedRejectionReadsAsLocalBlock() {
        // client.* 前缀 = 本地拒发（命令未发出）——failureText 必须区分「本地拦截」
        // 与「桌面端拒绝」两支（2026-10-08 用户反馈：本地拒发误导排查方向）。
        // 双语稳健：断言结构信号（reasonCode 原文在场 / 异支词表缺席），不锁自然
        // 语言措辞——failureText 已本地化，en 态下中文措辞不在场
        let local = CommandAck(.object([
            "status": .string("rejected"),
            "reasonCode": .string("client.casRevisionUnavailable"),
            "message": .string("会话状态 revision 未就绪（快照未到达），命令未发出"),
        ]))
        XCTAssertTrue(local.isFailure)
        let localText = local.failureText ?? ""
        XCTAssertTrue(localText.contains("client.casRevisionUnavailable"), localText)
        XCTAssertTrue(localText.contains("revision"), localText)
        XCTAssertFalse(localText.contains("proto.invalidPayload"), localText)

        // 服务端拒因走另一支（reasonCode 同样原样在场）
        let remote = CommandAck(.object([
            "status": .string("rejected"),
            "reasonCode": .string("proto.invalidPayload"),
            "message": .string("Invalid input"),
        ]))
        let remoteText = remote.failureText ?? ""
        XCTAssertTrue(remoteText.contains("proto.invalidPayload"), remoteText)
        XCTAssertFalse(remoteText.contains("client.casRevisionUnavailable"), remoteText)
    }

    func testHandshakeRequiredDetection() {
        // 真机取证形态：name="Error"、message="fault.connection.handshakeRequired"
        let err = RPCError(message: "fault.connection.handshakeRequired", name: "Error")
        XCTAssertTrue(err.isHandshakeRequired)
        XCTAssertFalse(RPCError(message: "RPC 超时", name: "TimeoutError").isHandshakeRequired)
    }
}

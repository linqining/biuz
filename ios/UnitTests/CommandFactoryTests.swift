import XCTest
@testable import ZCodeMobile

// MARK: - ConversationCommandFactory 单测（单点 builder：构造即校验）

final class CommandFactoryTests: XCTestCase {

    private func makeRequest(
        type: String,
        sessionId: String? = "sess_1",
        payload: JSONValue = .object([:]),
        casRevision: (revision: Int, logEpoch: String)? = nil,
        commandId: String = "cmd-fixed-id"
    ) -> ConversationCommandRequest {
        ConversationCommandRequest(
            type: type,
            sessionId: sessionId,
            payload: payload,
            casRevision: casRevision,
            workspacePath: "/tmp/ws",
            workspaceIdentity: nil,
            commandId: commandId)
    }

    private func build(
        _ request: ConversationCommandRequest
    ) -> Result<ConversationCommandFactory.Built, ConversationCommandFactory.Rejection> {
        ConversationCommandFactory.build(
            request, clientId: "zcode-mobile", issuedAt: 1_760_000_000_000)
    }

    // MARK: 嵌套信封形态（§7.1 实证）

    func testBuildsNestedOuterWithWorkspaceEnvelope() throws {
        let result = build(makeRequest(
            type: "sendText", payload: .object(["text": .string("hi")])))
        let built = try XCTUnwrap(result.getOrNil())
        let outer = try XCTUnwrap(built.outer.objectValue)
        XCTAssertEqual(outer["workspacePath"], .string("/tmp/ws"))
        XCTAssertNil(outer["workspaceIdentity"]) // identity 缺席不携键
        let envelope = try XCTUnwrap(outer["envelope"]?.objectValue)
        XCTAssertEqual(envelope["type"], .string("sendText"))
        XCTAssertEqual(envelope["sessionId"], .string("sess_1"))
        XCTAssertEqual(envelope["clientId"], .string("zcode-mobile"))
        XCTAssertEqual(envelope["commandId"], .string("cmd-fixed-id"))
        XCTAssertEqual(envelope["payload"]?.objectValue?["text"], .string("hi"))
        XCTAssertEqual(envelope["issuedAt"]?.intValue, 1_760_000_000_000)
        XCTAssertNil(envelope["baseRevision"]) // 非 CAS 不携
    }

    func testCreateSessionCarriesNullSessionId() throws {
        // A1：sessionId 键恒在场，createSession 传 null
        let result = build(makeRequest(
            type: "createSession", sessionId: nil,
            payload: .object(["workspaceId": .string("/tmp/ws")])))
        let envelope = try XCTUnwrap(result.getOrNil()?.outer.objectValue?["envelope"]?.objectValue)
        XCTAssertEqual(envelope["sessionId"], .null)
    }

    // MARK: CAS 门（构造时即拒）

    func testCASCommandMissingRevisionRejectedLocally() {
        let result = build(makeRequest(
            type: "switchModelConfig",
            payload: .object([
                "provider": .string("p"), "model": .string("m"), "thought": .string("")])))
        guard case .failure(let rejection) = result else {
            return XCTFail("CAS 命令缺水位应在构造期被拒")
        }
        XCTAssertEqual(rejection.reasonCode, "client.casRevisionUnavailable")
        XCTAssertEqual(rejection.syntheticAck.objectValue?["status"], .string("rejected"))
    }

    func testCASCommandWithRevisionBuilds() throws {
        let result = build(makeRequest(
            type: "switchModelConfig",
            payload: .object([
                "provider": .string("p"), "model": .string("m"), "thought": .string("")]),
            casRevision: (revision: 42, logEpoch: "epoch-1")))
        let envelope = try XCTUnwrap(result.getOrNil()?.outer.objectValue?["envelope"]?.objectValue)
        XCTAssertEqual(envelope["baseRevision"], .int(42))
        XCTAssertEqual(envelope["baseLogEpoch"], .string("epoch-1"))
    }

    func testEveryCASVocabularyMemberIsGated() {
        // 词表内全部命令缺水位必拒（pauseGoal/retryTurn 冷启动首击教训 P1③ 的结构化防线）
        let target = JSONValue.object(["rowId": .int(1), "entityId": .string("e1")])
        let validPayloads: [String: JSONValue] = [
            "applyFileRewind": .object(["target": target]),
            "forkAssistant": .object(["target": target]),
            "editUserQuery": .object(["target": target, "newText": .string("n")]),
            "retryTurn": .object(["target": target]),
            "setAssistantFeedback": .object(["target": target, "feedback": .null]),
            "sendQueuedNow": .object(["queueItemId": .string("q")]),
            "editQueueItem": .object(["queueItemId": .string("q"), "newText": .string("n")]),
            "reorderQueueItem": .object([
                "queueItemId": .string("q"), "beforeQueueItemId": .null]),
            "deleteQueueItem": .object(["queueItemId": .string("q")]),
            "setAutoDrain": .object(["autoDrain": .bool(true)]),
            "switchModelConfig": .object([
                "provider": .string("p"), "model": .string("m"), "thought": .string("")]),
            "switchCollaborationMode": .object(["mode": .string("build")]),
            "setFollowupMode": .object(["mode": .string("queue")]),
            "pauseGoal": .object([:]),
            "resumeGoal": .object([:]),
        ]
        XCTAssertEqual(Set(validPayloads.keys), CommandSchemas.commandsRequiringBaseRevision)
        for type in CommandSchemas.commandsRequiringBaseRevision.sorted() {
            let result = build(makeRequest(type: type, payload: validPayloads[type]!))
            guard case .failure(let rejection) = result else {
                XCTFail("\(type) 缺水位应被拒")
                continue
            }
            XCTAssertEqual(rejection.reasonCode, "client.casRevisionUnavailable", type)
        }
    }

    // MARK: payload 规范化（strip 语义 + 可信字段黑名单）

    func testUnknownPayloadKeyStrippedAndWarned() throws {
        let result = build(makeRequest(
            type: "sendText",
            payload: .object(["text": .string("hi"), "staleKey": .bool(true)])))
        let built = try XCTUnwrap(result.getOrNil())
        let payload = try XCTUnwrap(
            built.outer.objectValue?["envelope"]?.objectValue?["payload"]?.objectValue)
        XCTAssertNil(payload["staleKey"])          // 剥离（服务端同语义）
        XCTAssertEqual(payload["text"], .string("hi"))
        XCTAssertTrue(built.warnings.contains { $0.contains("staleKey") })
    }

    func testTrustedFieldRejected() {
        // connectionId 由桌面 facade 注入，客户端携带即违规（v1.14 真机被拒先例）
        let result = build(makeRequest(
            type: "sendText",
            payload: .object(["text": .string("hi"), "connectionId": .string("forged")])))
        guard case .failure(let rejection) = result else {
            return XCTFail("可信字段应硬拒")
        }
        XCTAssertEqual(rejection.reasonCode, "client.schemaViolation")
    }

    func testWrongEnumRejected() {
        let target = JSONValue.object(["rowId": .int(1), "entityId": .string("e1")])
        let bad = build(makeRequest(
            type: "setAssistantFeedback",
            payload: .object(["target": target, "feedback": .string("positive")]))
        )
        guard case .failure(let rejection) = bad else {
            return XCTFail("positive/negative 词表应被拒（A-1 先例）")
        }
        XCTAssertEqual(rejection.reasonCode, "client.schemaViolation")
        XCTAssertTrue(rejection.message.contains("feedback"))

        let good = build(makeRequest(
            type: "setAssistantFeedback",
            payload: .object(["target": target, "feedback": .null]),
            casRevision: (revision: 1, logEpoch: "e")))
        _ = try? good.get()
    }

    func testRequiredPayloadFieldMissingRejected() {
        let result = build(makeRequest(
            type: "reorderQueueItem",
            payload: .object(["queueItemId": .string("q1")]), // 缺 beforeQueueItemId
            casRevision: (revision: 1, logEpoch: "e")))
        guard case .failure(let rejection) = result else {
            return XCTFail("缺必填键应拒")
        }
        XCTAssertTrue(rejection.message.contains("beforeQueueItemId"))
    }

    func testReorderQueueItemNullTailAllowed() throws {
        let result = build(makeRequest(
            type: "reorderQueueItem",
            payload: .object([
                "queueItemId": .string("q1"),
                "beforeQueueItemId": .null]), // null=移到队尾
            casRevision: (revision: 1, logEpoch: "e")))
        let payload = try XCTUnwrap(
            result.getOrNil()?.outer.objectValue?["envelope"]?.objectValue?["payload"]?.objectValue)
        XCTAssertEqual(payload["beforeQueueItemId"], .null)
    }

    // MARK: 幂等与未移植面

    func testCommandIdEchoedForRetryInvariance() throws {
        // A12：重试必须复用同一 commandId——工厂原样透传，不自行生成
        let built = try XCTUnwrap(build(makeRequest(
            type: "stop",
            payload: .object([:]),
            commandId: "retry-same-id")).getOrNil())
        XCTAssertEqual(
            built.outer.objectValue?["envelope"]?.objectValue?["commandId"],
            .string("retry-same-id"))
    }

    func testUnportedTypeBuildsWithWarning() throws {
        let result = build(makeRequest(
            type: "sendGoalCommand", payload: .object(["text": .string("g")])))
        let built = try XCTUnwrap(result.getOrNil())
        XCTAssertEqual(
            built.outer.objectValue?["envelope"]?.objectValue?["type"],
            .string("sendGoalCommand"))
        XCTAssertTrue(built.warnings.contains { $0.contains("未移植") })
    }

    func testSwitchModelConfigEmptyThoughtAllowed() throws {
        // 上游无 min：thought 空串合法（移动端无档位时发 ""）
        let result = build(makeRequest(
            type: "switchModelConfig",
            payload: .object([
                "provider": .string("p"), "model": .string("m"), "thought": .string("")]),
            casRevision: (revision: 1, logEpoch: "e")))
        _ = try XCTUnwrap(result.getOrNil())
    }
}

private extension Result {
    /// 惰性求值辅助：成功返回值、失败返回 nil（失败细节由各用例的 failure 分支断言）
    func getOrNil() -> Success? {
        if case .success(let value) = self { return value }
        return nil
    }
}

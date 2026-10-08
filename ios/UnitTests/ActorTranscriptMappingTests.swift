import XCTest
@testable import ZCodeMobile

/// 子代理转录行映射门（G-021 同构渲染轮，2026-10-08）：toolCallRowMessage 是主会话
/// rebuildMessages 与 actorTranscript 共用的唯一 toolCall 行映射——两处漂移在此拦下。
/// 只读转录（includeRetryCursor=false）不带 entityId，工具卡「重试」门槛（G-015）
/// 使转录内天然无重试死入口。
final class ActorTranscriptMappingTests: XCTestCase {
    private let row = JSONValue.object([
        "rowId": .int(3),
        "kind": .string("toolCall"),
        "toolCallId": .string("tool-e2e-1"),
        "toolName": .string("Bash"),
        "status": .string("success"),
        "inputText": .string("grep -rn useUserInfo packages/shared"),
        "output": .object(["text": .string("index.tsx:46:export function useUserInfo")]),
        "display": .object(["addedLines": .int(2), "removedLines": .int(1)]),
        "entityId": .string("row-entity-abc"),
    ]).objectValue!

    func testFullMappingCarriesCommandAndOutput() {
        let message = RemoteConversationStore.toolCallRowMessage(
            row, rowId: 3, id: "transcript-s-3", includeRetryCursor: false)
        let call = message.toolCall
        XCTAssertNotNil(call)
        XCTAssertEqual(call?.kind, .bash)
        XCTAssertEqual(call?.target, "grep -rn useUserInfo packages/shared")
        XCTAssertEqual(call?.output, "index.tsx:46:export function useUserInfo")
        XCTAssertEqual(call?.addedLines, 2)
        XCTAssertEqual(call?.removedLines, 1)
        XCTAssertEqual(call?.status, .done)
        XCTAssertEqual(message.id, "transcript-s-3")
    }

    func testReadOnlyTranscriptOmitsRetryCursor() {
        let transcript = RemoteConversationStore.toolCallRowMessage(
            row, rowId: 3, id: "transcript-s-3", includeRetryCursor: false)
        XCTAssertNil(transcript.toolCall?.entityId, "只读转录不带 entityId（无重试死入口）")

        let main = RemoteConversationStore.toolCallRowMessage(
            row, rowId: 3, id: "row-3", includeRetryCursor: true)
        XCTAssertEqual(main.toolCall?.entityId, "row-entity-abc", "主会话保留重试游标")
    }

    func testFailedStatusMapsFailed() {
        var failedRow = row
        failedRow["status"] = .string("error")
        let message = RemoteConversationStore.toolCallRowMessage(
            failedRow, rowId: 3, id: "row-3", includeRetryCursor: false)
        XCTAssertEqual(message.toolCall?.status, .failed)
    }

    func testMissingInputFallsBackToToolName() {
        var bare = row
        bare["inputText"] = nil
        bare["output"] = nil
        bare["display"] = nil
        bare["toolCallId"] = nil
        let message = RemoteConversationStore.toolCallRowMessage(
            bare, rowId: 7, id: "row-7", includeRetryCursor: false)
        XCTAssertEqual(message.toolCall?.target, "Bash", "inputText 缺席回退 toolName（宽容解析）")
        XCTAssertNil(message.toolCall?.output)
        XCTAssertEqual(message.toolCall?.id, "tool-7", "toolCallId 缺席回退 rowId 合成")
    }
}

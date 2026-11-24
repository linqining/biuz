import XCTest
@testable import ZCodeMobile

// MARK: - 工具族判定（2026-11-24 与 Android ToolDisplay.familyOf 同序）
//
// 真机曾把 `TodoList` 显示成「读取」、`TodoWrite` 显示成「编辑」——因为族判定按
// read/write 先匹配，而 "TodoList" 含 "list"、"TodoWrite" 含 "write"。
// todo 必须优先于 read/write（web toolIdentity 的 todo 族优先级同样最高）。

final class ToolFamilyTests: XCTestCase {

    func testTodoWinsOverReadAndWrite() {
        XCTAssertEqual(RemoteConversationStore.mapToolKind("TodoList"), .todo)
        XCTAssertEqual(RemoteConversationStore.mapToolKind("TodoWrite"), .todo)
        XCTAssertEqual(RemoteConversationStore.mapToolKind("todo_read"), .todo)
    }

    func testOtherFamiliesUnchanged() {
        XCTAssertEqual(RemoteConversationStore.mapToolKind("Bash"), .bash)
        XCTAssertEqual(RemoteConversationStore.mapToolKind("Read"), .read)
        XCTAssertEqual(RemoteConversationStore.mapToolKind("Edit"), .edit)
        XCTAssertEqual(RemoteConversationStore.mapToolKind("AskUserQuestion"), .ask)
        XCTAssertEqual(RemoteConversationStore.mapToolKind("browser_navigate"), .browser)
    }

    func testShellStillBeatsTodoWhenNameSaysBoth() {
        // 极端名（既含 todo 又含 bash）：按判定顺序 todo 先命中——与 Android 一致
        XCTAssertEqual(RemoteConversationStore.mapToolKind("todo_bash"), .todo)
    }
}

import XCTest
@testable import ZCodeMobile

/// 屏 024/026/018 读面解析单测（与 android/connection RemoteSettingsPagesParsersTest
/// 同口径镜像；上游形状证据见 RemoteSettingsExtrasViews.swift 各段头注）。
final class RemoteSettingsParsersTests: XCTestCase {

    /// iOS JSONValue 是 Codable（无 .parse 静态入口），测试统一走 JSONDecoder。
    private func json(_ text: String) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
    }

    // ---- 024 hooks.loadHooks ----

    func testHookEntriesParseFieldsAndGroups() throws {
        let raw = """
        [
          {"id":"h1","event":"SessionStart","type":"command","command":"echo hi",
           "enabled":true,"editable":true,
           "location":{"source":"zcode","scope":"user"}},
          {"id":"h2","event":"PreToolUse","type":"process","command":"tool",
           "enabled":false,"location":{"source":"claude","scope":"project"}},
          {"id":"h3","event":"Stop","type":"command","command":"notify","enabled":true,
           "workspaceHook":{"trustState":"pending_trust"}},
          {"event":"Stop"}
        ]
        """
        let rows = RemoteSettingsParsers.parseHookEntries(try json(raw))
        XCTAssertEqual(rows.count, 3)
        XCTAssertEqual(rows[0].group, .installed)
        XCTAssertEqual(rows[0].scope, "user")
        XCTAssertEqual(rows[1].group, .legacy)
        // editable 缺席 → 默认 true（上游 save 过滤口径 editable !== false）
        XCTAssertTrue(rows[1].editable)
        XCTAssertTrue(rows[2].needsTrust)
        XCTAssertNil(rows[2].source)
    }

    func testHookNeedsTrustFalseForTrustedPersistent() throws {
        let raw = """
        [{"id":"h","event":"Stop","type":"command","command":"x","enabled":true,
          "workspaceHook":{"trustState":"trusted_persistent"}}]
        """
        let rows = RemoteSettingsParsers.parseHookEntries(try json(raw))
        XCTAssertFalse(rows[0].needsTrust)
    }

    // ---- 026 subagents.list ----

    func testSubagentEntriesParseFields() throws {
        let raw = """
        [
          {"id":"a1","name":"reviewer","description":"审查代码","scope":"user",
           "source":"user","enabled":true,"tools":["read","grep"]},
          {"id":"a2","name":"explore"}
        ]
        """
        let rows = RemoteSettingsParsers.parseSubagentEntries(try json(raw))
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].name, "reviewer")
        XCTAssertFalse(rows[0].toolsAll)
        XCTAssertEqual(rows[0].tools?.count, 2)
        // tools 缺席 = 全部工具；enabled 缺席默认 true；description 缺省空
        XCTAssertTrue(rows[1].toolsAll)
        XCTAssertTrue(rows[1].enabled)
        XCTAssertEqual(rows[1].description, "")
        // 缺 name 丢弃
        let dropped = RemoteSettingsParsers.parseSubagentEntries(try json(#"[{"id":"a3"}]"#))
        XCTAssertTrue(dropped.isEmpty)
    }

    // ---- 018 project.readTree ----

    func testProjectEntriesParseFields() throws {
        let raw = """
        [
          {"id":"p1","name":"zcode_mobile","hostId":"local","pinned":true,
           "deleted":false,"position":2,
           "roots":[{"rootPath":"/Users/mac/projects/zcode_mobile","isPrimary":true}],
           "primaryRootStatus":"missing","nameOverridden":true},
          {"id":"p2"}
        ]
        """
        let rows = RemoteSettingsParsers.parseProjectEntries(try json(raw))
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].name, "zcode_mobile")
        XCTAssertEqual(rows[0].roots, ["/Users/mac/projects/zcode_mobile"])
        XCTAssertTrue(rows[0].pinned)
        XCTAssertEqual(rows[0].primaryRootStatus, "missing")
        XCTAssertFalse(rows[0].deleted)
    }

    // ---- 时间双形态 ----

    func testEpochMillisDualForm() {
        XCTAssertEqual(RemoteSettingsParsers.epochMillis(.double(1_731_200_000_000)), 1_731_200_000_000)
        XCTAssertEqual(RemoteSettingsParsers.epochMillis(.double(1_731_200_000)), 1_731_200_000_000)
        XCTAssertNil(RemoteSettingsParsers.epochMillis(.double(123)))
        XCTAssertNil(RemoteSettingsParsers.epochMillis(nil))
    }
}

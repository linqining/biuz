import XCTest
@testable import ZCodeMobile

// MARK: - ExitPlanMode 计划预览卡提取/标题单测（官方 web planToolCall 的移动端镜像；
// 形状门收敛口径见协议接口文档 §10.1）

final class PlanToolCallParsingTests: XCTestCase {

    // MARK: 提取（官方 extractPlanToolCallContent 的形状门收敛形态）

    func testOfficialExitPlanModeInput() {
        // 官方 input schema：{allowedPrompts?, plan}；planFilePath 缺省
        let target = ##"{"allowedPrompts":[{"prompt":"run tests","tool":"Bash"}],"plan":"# 计划标题\n\n正文"}"##
        let parsed = PlanToolCallSupport.parse(target)
        XCTAssertEqual(parsed?.markdown, "# 计划标题\n\n正文")
        XCTAssertNil(parsed?.planFilePath)
    }

    func testPlanFilePathExtracted() {
        let target = #"{"plan":"正文","planFilePath":"/Users/mac/proj/plan.md"}"#
        let parsed = PlanToolCallSupport.parse(target)
        XCTAssertEqual(parsed?.markdown, "正文")
        XCTAssertEqual(parsed?.planFilePath, "/Users/mac/proj/plan.md")
    }

    func testEmptyPlanReturnsNil() {
        // plan 空串 = 无有效计划 → 回退普通工具卡（官方 hasMarkdown 同口径）
        XCTAssertNil(PlanToolCallSupport.parse(#"{"plan":""}"#))
        XCTAssertNil(PlanToolCallSupport.parse(#"{"plan":"   "}"#))
    }

    func testTextFieldDoesNotTriggerPlanCard() {
        // 形状门收敛：官方 switch-mode 族内认 text/content 兜底，但移动端无 toolName，
        // 只认 `plan` 键——MCP {text:…} 类载荷不得误判成计划卡
        XCTAssertNil(PlanToolCallSupport.parse(#"{"text":"普通文本工具输入"}"#))
        XCTAssertNil(PlanToolCallSupport.parse(#"{"content":"x"}"#))
    }

    func testNonJSONObjectReturnsNil() {
        // bash 命令/数组/裸串不进计划卡
        XCTAssertNil(PlanToolCallSupport.parse("grep -rn timeout server/"))
        XCTAssertNil(PlanToolCallSupport.parse(#"[{"text":"数组载荷"}]"#))
        XCTAssertNil(PlanToolCallSupport.parse(#"{"plan": 42}"#))
        XCTAssertNil(PlanToolCallSupport.parse(""))
    }

    func testMalformedJSONReturnsNil() {
        XCTAssertNil(PlanToolCallSupport.parse(#"{"plan": 不是JSON}"#))
    }

    // MARK: 标题（官方 getPlanDirectoryTitle：首个 H1，否则首个非空行去装饰）

    func testTitleFromFirstH1() {
        XCTAssertEqual(
            PlanToolCallSupport.planTitle("前言\n\n# 真标题\n## 二级\n# 第二个H1"),
            "真标题")
    }

    func testTitleFallbackFirstNonEmptyLineStripped() {
        XCTAssertEqual(PlanToolCallSupport.planTitle("### 三级标题开头\n正文"), "三级标题开头")
        XCTAssertEqual(PlanToolCallSupport.planTitle("> 引用开头\n正文"), "引用开头")
        XCTAssertEqual(PlanToolCallSupport.planTitle("- 列表开头\n正文"), "列表开头")
    }

    func testTitleEmptyReturnsNil() {
        XCTAssertNil(PlanToolCallSupport.planTitle(""))
    }

    // MARK: 文件名尾段（官方 getPlanFileLabel = getPathLeaf）

    func testPlanFileLabelLeaf() {
        XCTAssertEqual(PlanToolCallSupport.planFileLabel("/Users/mac/proj/plan.md"), "plan.md")
        XCTAssertEqual(PlanToolCallSupport.planFileLabel("C:\\repo\\plan.md"), "plan.md")
        XCTAssertEqual(PlanToolCallSupport.planFileLabel("plan.md"), "plan.md")
    }
}

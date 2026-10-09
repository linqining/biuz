import XCTest
@testable import ZCodeMobile

// MARK: - userselect 尾部块解析单测（官方 web conversationSelectionReference 的
// 移动端镜像：剥离 + 卡片化；围栏合同见协议接口文档 §10）

final class UserSelectionParsingTests: XCTestCase {

    // MARK: 官方形态【实证·上游仓 conversationSelectionReference.ts:56,145-158】

    func testOfficialBlockWithVisibleText() {
        let raw = "看看这些引用\n\n# userselect:\n```userselect\n[{\"text\":\"挂起 20 项的分类\"}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "看看这些引用")
        XCTAssertEqual(parsed?.selections, [
            UserSelectionReference(text: "挂起 20 项的分类", id: "userselect-0"),
        ])
    }

    func testOfficialBlockPureSelectionEmptyVisibleText() {
        // 官方：visibleContent 为空时整条消息就是纯块（buildPromptWithConversationSelections :157）
        let raw = "# userselect:\n```userselect\n[{\"text\":\"纯引用消息\"}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "")
        XCTAssertEqual(parsed?.selections.count, 1)
    }

    func testOfficialBlockMultipleItemsWithPath() {
        let raw = "正文\n\n# userselect:\n```userselect\n[{\"text\":\"第一段\",\"path\":\"/Users/mac/proj/a.swift\"},{\"text\":\"第二段\"}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "正文")
        XCTAssertEqual(parsed?.selections.count, 2)
        XCTAssertEqual(parsed?.selections[0].path, "/Users/mac/proj/a.swift")
        XCTAssertNil(parsed?.selections[1].path)
    }

    func testTrailingWhitespaceAfterClosingFenceTolerated() {
        // 上游 `\s*$`：闭合围栏后允许尾随空白
        let raw = "正文\n\n# userselect:\n```userselect\n[{\"text\":\"x\"}]\n```\n\n  "
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "正文")
        XCTAssertEqual(parsed?.selections.first?.text, "x")
    }

    func testBlockNotAtTailIsNotStripped() {
        // 官方锚定语义：块后还有正文 → 不剥离（web 同构，UserInputRowView 只认尾部块）
        let raw = "# userselect:\n```userselect\n[{\"text\":\"x\"}]\n```\n后面还有正文"
        XCTAssertNil(UserSelectionSupport.parse(raw))
    }

    // MARK: legacy 围栏【实证·上游仓 conversationSelectionReference.ts:57-58】

    func testLegacyBlockPattern() {
        let raw = "正文\n\n# Conversation selections:\n```zcode-conversation-selections\n[{\"text\":\"旧拼写\"}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "正文")
        XCTAssertEqual(parsed?.selections.first?.text, "旧拼写")
    }

    // MARK: 裸围栏【宽容·未取证】防御桌面变体

    func testBareFenceWithoutHeaderTolerated() {
        let raw = "正文\n\n```userselect\n[{\"text\":\"无头形态\"}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "正文")
        XCTAssertEqual(parsed?.selections.first?.text, "无头形态")
    }

    func testBareFenceAsWholeMessage() {
        let raw = "```userselect\n[{\"text\":\"整条即块\"}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.visibleText, "")
        XCTAssertEqual(parsed?.selections.first?.text, "整条即块")
    }

    // MARK: 坏载荷回退（不崩不吞：按普通消息原样渲染）

    func testMalformedJSONReturnsNil() {
        let raw = "正文\n\n# userselect:\n```userselect\n[{text: 不是JSON}]\n```"
        XCTAssertNil(UserSelectionSupport.parse(raw))
    }

    func testEmptyArrayReturnsNil() {
        let raw = "正文\n\n# userselect:\n```userselect\n[]\n```"
        XCTAssertNil(UserSelectionSupport.parse(raw))
    }

    func testItemsWithoutValidTextSkipped() {
        // 宽容：未知键忽略、text 缺失/空串的条目跳过；有有效条目即成卡
        let raw = "# userselect:\n```userselect\n[{\"note\":\"无 text 字段\"},{\"text\":\"\"},{\"text\":\"有效\",\"extra\":1}]\n```"
        let parsed = UserSelectionSupport.parse(raw)
        XCTAssertEqual(parsed?.selections.count, 1)
        XCTAssertEqual(parsed?.selections.first?.text, "有效")
    }

    func testPlainTextWithoutFenceReturnsNil() {
        XCTAssertNil(UserSelectionSupport.parse("普通消息，没有围栏"))
        XCTAssertNil(UserSelectionSupport.parse(""))
    }

    // MARK: 卡片 label 三规则【实证·上游仓 ConversationSelectionReferenceChip.tsx:24-38】

    func testCardLabelSingleWithPathUsesFilename() {
        let items = [UserSelectionReference(
            text: "x", path: "/Users/mac/proj/gateway.ts", id: "userselect-0")]
        XCTAssertEqual(UserSelectionSupport.cardLabel(items), "gateway.ts · 引用")
    }

    func testCardLabelSingleWithWindowsStylePath() {
        // 官方取尾段兼容反斜杠：path.split(/[\\/]/)
        let items = [UserSelectionReference(
            text: "x", path: "C:\\repo\\src\\main.ts", id: "userselect-0")]
        XCTAssertEqual(UserSelectionSupport.cardLabel(items), "main.ts · 引用")
    }

    func testCardLabelMultipleMixedUsesCount() {
        let items = [
            UserSelectionReference(text: "a", path: "/p/f.swift", id: "userselect-0"),
            UserSelectionReference(text: "b", id: "userselect-1"),
        ]
        XCTAssertEqual(UserSelectionSupport.cardLabel(items), "2 条引用")
    }

    func testCardLabelPureConversationUsesConversationCount() {
        let items = [
            UserSelectionReference(text: "a", id: "userselect-0"),
            UserSelectionReference(text: "b", id: "userselect-1"),
        ]
        XCTAssertEqual(UserSelectionSupport.cardLabel(items), "2 条对话引用")
    }
}

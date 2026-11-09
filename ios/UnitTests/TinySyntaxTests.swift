import XCTest
import SwiftUI
@testable import ZCodeMobile

/// 代码块语法高亮（P-005）：语言归一 / 关键词 / 字符串 / 注释 / 数字 / 未知语言原样。
final class TinySyntaxTests: XCTestCase {

    private func kinds(_ code: String, _ language: String?) -> [(String, TinySyntax.Kind)] {
        let tokens = TinySyntax.tokens(in: code, language: language)
        return tokens.map { (String(code[$0.range]), $0.kind) }
    }

    func testLanguageAliasesCanonicalize() {
        XCTAssertEqual(TinySyntax.canonical("tsx"), "typescript")
        XCTAssertEqual(TinySyntax.canonical("PY"), "python")
        XCTAssertEqual(TinySyntax.canonical("bash"), "shell")
        XCTAssertNil(TinySyntax.canonical("brainfuck"), "未知语言不高亮（不猜语义）")
        XCTAssertNil(TinySyntax.canonical(nil))
    }

    func testSwiftKeywordsStringsCommentsNumbers() {
        let code = """
        let name = "BiuZ" // 注释
        if count > 42 { print(name) }
        """
        let tokens = kinds(code, "swift")
        XCTAssertTrue(tokens.contains { $0.0 == "let" && $0.1 == .keyword })
        XCTAssertTrue(tokens.contains { $0.0 == "if" && $0.1 == .keyword })
        XCTAssertTrue(tokens.contains { $0.0 == "\"BiuZ\"" && $0.1 == .string })
        XCTAssertTrue(tokens.contains { $0.0 == "// 注释" && $0.1 == .comment })
        XCTAssertTrue(tokens.contains { $0.0 == "42" && $0.1 == .number })
    }

    func testPythonUsesHashComments() {
        let tokens = kinds("def f():  # 说明\n    return 1", "python")
        XCTAssertTrue(tokens.contains { $0.0 == "def" && $0.1 == .keyword })
        XCTAssertTrue(tokens.contains { $0.0 == "# 说明" && $0.1 == .comment })
        XCTAssertTrue(tokens.contains { $0.0 == "return" && $0.1 == .keyword })
    }

    func testIdentifiersContainingKeywordAreNotHighlighted() {
        // `letter` 不是 `let`；`nullable` 不是 `null`
        let tokens = kinds("let letter = nullable", "swift")
        XCTAssertFalse(tokens.contains { $0.0 == "letter" })
        XCTAssertFalse(tokens.contains { $0.0 == "nullable" })
        XCTAssertTrue(tokens.contains { $0.0 == "let" && $0.1 == .keyword })
    }

    func testUnclosedStringDoesNotSwallowRest() {
        let tokens = kinds("x = \"unterminated\ny = 2", "typescript")
        XCTAssertFalse(tokens.contains { $0.1 == .string })
        XCTAssertTrue(tokens.contains { $0.0 == "2" && $0.1 == .number }, "未闭合字符串不得吃掉后续数字")
    }

    func testUnknownLanguageYieldsNoTokens() {
        XCTAssertTrue(TinySyntax.tokens(in: "let a = 1", language: "brainfuck").isEmpty)
        XCTAssertTrue(TinySyntax.tokens(in: "let a = 1", language: nil).isEmpty)
    }

    func testAttributedRangesMapBack() {
        let code = "let a = 1"
        let attributed = TinySyntax.highlight(code, language: "swift", color: { _ in .red })
        XCTAssertEqual(String(attributed.characters), code, "着色不得改变文本内容")
    }
    func testSQLKeywordsCaseInsensitiveWithDashComments() {
        let code = """
        SELECT token, value FROM palette WHERE theme = 'dark';
        -- bg #0a0b0d
        """
        XCTAssertEqual(TinySyntax.canonical("postgres"), "sql")
        let tokens = kinds(code, "sql")
        XCTAssertTrue(tokens.contains { $0.0 == "SELECT" && $0.1 == .keyword })
        XCTAssertTrue(tokens.contains { $0.0 == "FROM" && $0.1 == .keyword })
        XCTAssertTrue(tokens.contains { $0.0 == "WHERE" && $0.1 == .keyword })
        XCTAssertTrue(tokens.contains { $0.0 == "'dark'" && $0.1 == .string })
        XCTAssertTrue(tokens.contains { $0.0 == "-- bg #0a0b0d" && $0.1 == .comment })
        // 小写同样命中
        XCTAssertTrue(kinds("select 1", "sql").contains { $0.0 == "select" && $0.1 == .keyword })
    }
}

import XCTest
@testable import ZCodeMobile

/// 白板模型单测（屏 036；web whiteboardStore.ts 变更语义同构锁定；Android
/// WhiteboardMutationsTest 镜像）。
final class WhiteboardMutationsTests: XCTestCase {

    private func stroke() -> WhiteboardStroke {
        WhiteboardStroke(tool: .pen, color: "#111827", width: 4, points: [WhiteboardPoint(x: 1, y: 2)])
    }

    func testAddStrokeAppendsAndClearsRedoStack() {
        var board = WhiteboardDocument(name: "白板")
        board = WhiteboardMutations.addStroke(board, stroke())
        board = WhiteboardMutations.undo(board)
        XCTAssertEqual(board.strokes.count, 0)
        XCTAssertEqual(board.undoneStrokes.count, 1)
        // 新笔画清空 redo 栈（whiteboardStore.ts:156-168）
        board = WhiteboardMutations.addStroke(board, stroke())
        XCTAssertEqual(board.strokes.count, 1)
        XCTAssertEqual(board.undoneStrokes.count, 0)
        XCTAssertFalse(board.canRedo)
    }

    func testUndoRedoRoundTrip() {
        var board = WhiteboardDocument(name: "白板")
        let s1 = stroke()
        let s2 = stroke()
        board = WhiteboardMutations.addStroke(board, s1)
        board = WhiteboardMutations.addStroke(board, s2)
        board = WhiteboardMutations.undo(board)
        XCTAssertEqual(board.strokes.map(\.id), [s1.id])
        board = WhiteboardMutations.undo(board)
        XCTAssertEqual(board.strokes.count, 0)
        XCTAssertTrue(board.canRedo)
        // undo 空栈原样返回
        XCTAssertEqual(WhiteboardMutations.undo(board), board)
        board = WhiteboardMutations.redo(board)
        XCTAssertEqual(board.strokes.count, 1)
        board = WhiteboardMutations.redo(board)
        XCTAssertEqual(board.strokes.map(\.id), [s1.id, s2.id])
        XCTAssertEqual(board.undoneStrokes.count, 0)
    }

    func testClearEmptiesBothStacks() {
        var board = WhiteboardDocument(name: "白板")
        board = WhiteboardMutations.addStroke(board, stroke())
        board = WhiteboardMutations.undo(board)
        board = WhiteboardMutations.clear(board)
        XCTAssertEqual(board.strokes.count, 0)
        XCTAssertEqual(board.undoneStrokes.count, 0)
        XCTAssertFalse(board.canUndo)
        XCTAssertFalse(board.canRedo)
    }

    func testRenameTrimsAndIgnoresBlank() {
        let board = WhiteboardDocument(name: "白板")
        XCTAssertEqual(WhiteboardMutations.rename(board, "  设计稿  ").name, "设计稿")
        XCTAssertEqual(WhiteboardMutations.rename(board, "   "), board)
    }

    func testStoreIsolatesBoardsPerWorkspaceKey() {
        let store = WhiteboardBoardStore()
        let key = "test-key-\(UUID().uuidString)"
        let boardA = store.board(for: key)
        let boardB = store.board(for: key + "-b")
        XCTAssertNotEqual(boardA.id, boardB.id)
        store.mutate(key) { WhiteboardMutations.addStroke($0, stroke()) }
        XCTAssertEqual(store.board(for: key).strokes.count, 1)
        XCTAssertEqual(store.board(for: key + "-b").strokes.count, 0)
    }

    func testDocumentDefaultsMatchWebConstants() {
        let board = WhiteboardDocument(name: "白板")
        XCTAssertEqual(board.width, 1280)
        XCTAssertEqual(board.height, 800)
        XCTAssertEqual(WhiteboardDocument.backgroundColor, "#ffffff")
        XCTAssertEqual(WhiteboardDocument.defaultColor, "#111827")
        XCTAssertEqual(
            WhiteboardDocument.swatches,
            ["#111827", "#2563eb", "#16a34a", "#dc2626", "#ca8a04", "#7c3aed"])
        XCTAssertEqual(WhiteboardDocument.minStrokeWidth, 2.0)
        XCTAssertEqual(WhiteboardDocument.maxStrokeWidth, 18.0)
        XCTAssertTrue(board.id.hasPrefix("whiteboard:"))
        XCTAssertTrue(stroke().id.hasPrefix("stroke:"))
    }

    func testExportPNGProducesImage() {
        var board = WhiteboardDocument(name: "白板")
        board = WhiteboardMutations.addStroke(
            board,
            WhiteboardStroke(
                tool: .pen, color: "#111827", width: 6,
                points: [WhiteboardPoint(x: 100, y: 100), WhiteboardPoint(x: 400, y: 400)]))
        board = WhiteboardMutations.addStroke(
            board,
            WhiteboardStroke(tool: .eraser, color: "#111827", width: 8, points: [WhiteboardPoint(x: 600, y: 600)]))
        let data = exportPNG(board)
        XCTAssertFalse(data.isEmpty, "PNG 导出应产出非空字节")
        XCTAssertNotNil(UIImage(data: data), "导出字节应为合法 PNG")
        if let image = UIImage(data: data) {
            XCTAssertEqual(Int(image.size.width * image.scale), 1280)
            XCTAssertEqual(Int(image.size.height * image.scale), 800)
        }
    }
}

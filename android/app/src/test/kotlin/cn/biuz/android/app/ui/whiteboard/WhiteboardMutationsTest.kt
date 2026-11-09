package cn.biuz.android.app.ui.whiteboard

// 白板模型单测（屏 036；web whiteboardStore.ts 变更语义同构锁定）：
// addStroke=追加+清 redo 栈 / undo=尾搬 / redo=尾搬回 / clear=双清 /
// rename=trim 空串忽略；store 按 workspace 键隔离。

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WhiteboardMutationsTest {

    private fun stroke(tool: WhiteboardTool = WhiteboardTool.pen) =
        WhiteboardStroke(tool = tool, color = "#111827", width = 4.0, points = listOf(WhiteboardPoint(1.0, 2.0)))

    @Test
    fun addStrokeAppendsAndClearsRedoStack() {
        var board = WhiteboardDocument(name = "白板")
        board = WhiteboardMutations.addStroke(board, stroke())
        board = WhiteboardMutations.undo(board)
        assertEquals(0, board.strokes.size)
        assertEquals(1, board.undoneStrokes.size)
        // 新笔画清空 redo 栈（whiteboardStore.ts:156-168）
        board = WhiteboardMutations.addStroke(board, stroke())
        assertEquals(1, board.strokes.size)
        assertEquals(0, board.undoneStrokes.size)
        assertFalse(board.canRedo)
    }

    @Test
    fun undoRedoRoundTrip() {
        var board = WhiteboardDocument(name = "白板")
        val s1 = stroke()
        val s2 = stroke()
        board = WhiteboardMutations.addStroke(board, s1)
        board = WhiteboardMutations.addStroke(board, s2)
        board = WhiteboardMutations.undo(board)
        assertEquals(listOf(s1.id), board.strokes.map { it.id })
        board = WhiteboardMutations.undo(board)
        assertEquals(0, board.strokes.size)
        assertTrue(board.canRedo)
        // undo 空栈原样返回（不更新时间戳语义——值相等即可）
        val unchanged = WhiteboardMutations.undo(board)
        assertEquals(board, unchanged)
        board = WhiteboardMutations.redo(board)
        assertEquals(1, board.strokes.size)
        board = WhiteboardMutations.redo(board)
        assertEquals(listOf(s1.id, s2.id), board.strokes.map { it.id })
        assertEquals(0, board.undoneStrokes.size)
    }

    @Test
    fun clearEmptiesBothStacks() {
        var board = WhiteboardDocument(name = "白板")
        board = WhiteboardMutations.addStroke(board, stroke())
        board = WhiteboardMutations.undo(board)
        board = WhiteboardMutations.clear(board)
        assertEquals(0, board.strokes.size)
        assertEquals(0, board.undoneStrokes.size)
        assertFalse(board.canUndo)
        assertFalse(board.canRedo)
    }

    @Test
    fun renameTrimsAndIgnoresBlank() {
        val board = WhiteboardDocument(name = "白板")
        val renamed = WhiteboardMutations.rename(board, "  设计稿  ")
        assertEquals("设计稿", renamed.name)
        assertEquals(board, WhiteboardMutations.rename(board, "   "))
    }

    @Test
    fun storeIsolatesBoardsPerWorkspaceKey() {
        val boardA = WhiteboardBoardStore.boardFor("ws-a")
        val boardB = WhiteboardBoardStore.boardFor("ws-b")
        assertNotEquals(boardA.id, boardB.id)
        WhiteboardBoardStore.mutate("ws-a") { WhiteboardMutations.addStroke(it, stroke()) }
        assertEquals(1, WhiteboardBoardStore.boardFor("ws-a").strokes.size)
        assertEquals(0, WhiteboardBoardStore.boardFor("ws-b").strokes.size)
        // 同键复取同一板（进程内存活，web zustand 同口径）
        assertEquals(1, WhiteboardBoardStore.boardFor("ws-a").strokes.size)
    }

    @Test
    fun documentDefaultsMatchWebConstants() {
        val board = WhiteboardDocument(name = "白板")
        assertEquals(1280, board.width)
        assertEquals(800, board.height)
        assertEquals("#ffffff", WhiteboardDocument.BACKGROUND_COLOR)
        assertEquals("#111827", WhiteboardDocument.DEFAULT_COLOR)
        assertEquals(
            listOf("#111827", "#2563eb", "#16a34a", "#dc2626", "#ca8a04", "#7c3aed"),
            WhiteboardDocument.SWATCHES,
        )
        assertEquals(2.0, WhiteboardDocument.MIN_STROKE_WIDTH)
        assertEquals(18.0, WhiteboardDocument.MAX_STROKE_WIDTH)
        assertTrue(board.id.startsWith("whiteboard:"))
        assertTrue(stroke().id.startsWith("stroke:"))
    }
}

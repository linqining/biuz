package cn.biuz.android.app.ui.whiteboard

import java.util.UUID

/**
 * 白板数据模型（屏 036）——web `packages/ui/src/lib/whiteboard.ts:5-34` 的 Kotlin 同构移植。
 *
 * 协议/行为事实（取证 2026-11-10，交接文档 §7-B7 固化）：
 * - **纯客户端自包含、零 RPC**：web `store/whiteboardStore.ts` 是纯内存 zustand
 *   （按 `workspaceIdentity||workspacePath` 键），无持久化、无服务端同步、进程死即失；
 * - 画布 1280×800（WHITEBOARD_CANVAS_WIDTH/HEIGHT :40-41）、白底 #ffffff、默认墨色 #111827；
 * - 渲染（drawWhiteboardStroke :126-152）：eraser=画背景色（非 composite-out）、
 *   round cap/join、折线段；单点=实心圆 r=width/2；
 * - 六色板与笔宽：WhiteboardPane.tsx:28 `["#111827","#2563eb","#16a34a","#dc2626","#ca8a04","#7c3aed"]`、
 *   滑条 2–18（:299-300）；
 * - 变更语义（whiteboardStore.ts:141-204）：addStroke=追加+**清空 redo 栈**；
 *   undo=strokes 尾搬 undoneStrokes；redo=undoneStrokes 尾搬回 strokes；
 *   clear=双栈全清；rename=trim 后空串忽略。
 * - 「添加到聊天」= PNG dataURL 导出 + 自定义事件（web）；原生 v1 对位 = 导出 PNG
 *   走系统分享（composer 附件直插登记 P-031 待波次）。
 */
enum class WhiteboardTool(val wire: String) {
    pen("pen"),
    eraser("eraser"),
}

data class WhiteboardPoint(val x: Double, val y: Double)

data class WhiteboardStroke(
    val id: String = "stroke:" + UUID.randomUUID().toString(),
    val tool: WhiteboardTool,
    val color: String,
    val width: Double,
    val points: List<WhiteboardPoint>,
)

data class WhiteboardDocument(
    val id: String = "whiteboard:" + UUID.randomUUID().toString(),
    val name: String,
    val width: Int = CANVAS_WIDTH,
    val height: Int = CANVAS_HEIGHT,
    val strokes: List<WhiteboardStroke> = emptyList(),
    val undoneStrokes: List<WhiteboardStroke> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = undoneStrokes.isNotEmpty()

    companion object {
        const val CANVAS_WIDTH = 1280
        const val CANVAS_HEIGHT = 800
        const val BACKGROUND_COLOR = "#ffffff"
        const val DEFAULT_COLOR = "#111827"
        /** 六色板（WhiteboardPane.tsx:28 原序）。 */
        val SWATCHES = listOf("#111827", "#2563eb", "#16a34a", "#dc2626", "#ca8a04", "#7c3aed")
        const val MIN_STROKE_WIDTH = 2.0
        const val MAX_STROKE_WIDTH = 18.0
    }
}

/** 纯变更函数（web store 三个 mutation 的同构；单测锁定面）。 */
object WhiteboardMutations {

    /** addStroke：追加 + 清空 redo 栈（whiteboardStore.ts:156-168）。 */
    fun addStroke(board: WhiteboardDocument, stroke: WhiteboardStroke): WhiteboardDocument =
        board.copy(
            strokes = board.strokes + stroke,
            undoneStrokes = emptyList(),
            updatedAt = System.currentTimeMillis(),
        )

    /** undo：strokes 尾搬 undoneStrokes（:170-188；空栈原样返回不更新时间戳）。 */
    fun undo(board: WhiteboardDocument): WhiteboardDocument {
        val last = board.strokes.lastOrNull() ?: return board
        return board.copy(
            strokes = board.strokes.dropLast(1),
            undoneStrokes = board.undoneStrokes + last,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** redo：undoneStrokes 尾搬回 strokes（:189-207；空栈原样返回）。 */
    fun redo(board: WhiteboardDocument): WhiteboardDocument {
        val last = board.undoneStrokes.lastOrNull() ?: return board
        return board.copy(
            strokes = board.strokes + last,
            undoneStrokes = board.undoneStrokes.dropLast(1),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** clear：双栈全清（:209-218）。 */
    fun clear(board: WhiteboardDocument): WhiteboardDocument =
        board.copy(
            strokes = emptyList(),
            undoneStrokes = emptyList(),
            updatedAt = System.currentTimeMillis(),
        )

    /** rename：trim 后空串忽略（:139-155 同口径）。 */
    fun rename(board: WhiteboardDocument, rawName: String): WhiteboardDocument {
        val name = rawName.trim()
        if (name.isEmpty()) return board
        return board.copy(name = name, updatedAt = System.currentTimeMillis())
    }
}

/**
 * 会话级内存白板仓（web zustand 全局 store 的 Android 对位：**进程内存活、
 * 不持久化**——web 同口径，App 杀掉即清，与「零 RPC 纯客户端」事实一致）。
 * 键 = workspaceIdentity ?: workspacePath（buildWhiteboardWorkspaceKey :43-49）。
 */
object WhiteboardBoardStore {

    /** workspace 键 → 活动板（web 是 boardsById 多板 + tab 系统；移动端单屏单板，
     *  每 workspace 保留最近活动的一块——重开屏不丢笔画，与 web 面板关闭再开同体验）。 */
    private val boards = LinkedHashMap<String, WhiteboardDocument>()

    @Synchronized
    fun boardFor(workspaceKey: String): WhiteboardDocument =
        boards.getOrPut(workspaceKey) {
            WhiteboardDocument(name = defaultName())
        }

    /** 换 workspace（会话切换）时取不同板；键同 web 口径。 */
    @Synchronized
    fun mutate(workspaceKey: String, mutation: (WhiteboardDocument) -> WhiteboardDocument): WhiteboardDocument {
        val next = mutation(boardFor(workspaceKey))
        boards[workspaceKey] = next
        return next
    }

    private fun defaultName(): String = "白板"
}

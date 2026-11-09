import Foundation

// MARK: - 屏 036 白板数据模型（web lib/whiteboard.ts:5-34 同构；Android
// WhiteboardModel.kt 镜像。行为事实与常量出处见 Android 侧头注 /
// 交接文档 §7-B7：零 RPC 纯客户端、1280×800 白底、六色板、笔宽 2–18、
// eraser=画背景色、addStroke 清 redo 栈）

enum WhiteboardTool: String {
    case pen
    case eraser
}

struct WhiteboardPoint: Equatable {
    var x: Double
    var y: Double
}

struct WhiteboardStroke: Equatable, Identifiable {
    var id: String = "stroke:" + UUID().uuidString
    var tool: WhiteboardTool
    var color: String
    var width: Double
    var points: [WhiteboardPoint]
}

struct WhiteboardDocument: Equatable {
    var id: String = "whiteboard:" + UUID().uuidString
    var name: String
    var width: Int = WhiteboardDocument.canvasWidth
    var height: Int = WhiteboardDocument.canvasHeight
    var strokes: [WhiteboardStroke] = []
    var undoneStrokes: [WhiteboardStroke] = []
    var createdAt: Double = Date().timeIntervalSince1970 * 1000
    var updatedAt: Double = Date().timeIntervalSince1970 * 1000

    var canUndo: Bool { !strokes.isEmpty }
    var canRedo: Bool { !undoneStrokes.isEmpty }

    static let canvasWidth = 1280
    static let canvasHeight = 800
    static let backgroundColor = "#ffffff"
    static let defaultColor = "#111827"
    /// 六色板（WhiteboardPane.tsx:28 原序）
    static let swatches = ["#111827", "#2563eb", "#16a34a", "#dc2626", "#ca8a04", "#7c3aed"]
    static let minStrokeWidth = 2.0
    static let maxStrokeWidth = 18.0
}

/// 纯变更函数（web store 三个 mutation 同构；单测锁定面）
enum WhiteboardMutations {
    /// addStroke：追加 + 清空 redo 栈（whiteboardStore.ts:156-168）
    static func addStroke(_ board: WhiteboardDocument, _ stroke: WhiteboardStroke) -> WhiteboardDocument {
        var next = board
        next.strokes.append(stroke)
        next.undoneStrokes = []
        next.updatedAt = Date().timeIntervalSince1970 * 1000
        return next
    }

    /// undo：strokes 尾搬 undoneStrokes（:170-188；空栈原样返回）
    static func undo(_ board: WhiteboardDocument) -> WhiteboardDocument {
        guard let last = board.strokes.last else { return board }
        var next = board
        next.strokes.removeLast()
        next.undoneStrokes.append(last)
        next.updatedAt = Date().timeIntervalSince1970 * 1000
        return next
    }

    /// redo：undoneStrokes 尾搬回 strokes（:189-207；空栈原样返回）
    static func redo(_ board: WhiteboardDocument) -> WhiteboardDocument {
        guard let last = board.undoneStrokes.last else { return board }
        var next = board
        next.undoneStrokes.removeLast()
        next.strokes.append(last)
        next.updatedAt = Date().timeIntervalSince1970 * 1000
        return next
    }

    /// clear：双栈全清（:209-218）
    static func clear(_ board: WhiteboardDocument) -> WhiteboardDocument {
        var next = board
        next.strokes = []
        next.undoneStrokes = []
        next.updatedAt = Date().timeIntervalSince1970 * 1000
        return next
    }

    /// rename：trim 后空串忽略（:139-155 同口径）
    static func rename(_ board: WhiteboardDocument, _ rawName: String) -> WhiteboardDocument {
        let name = rawName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return board }
        var next = board
        next.name = name
        next.updatedAt = Date().timeIntervalSince1970 * 1000
        return next
    }
}

/// 会话级内存白板仓（web zustand 全局 store 对位：进程内存活、不持久化；
/// 键 = workspaceIdentity ?: workspacePath（buildWhiteboardWorkspaceKey :43-49））
final class WhiteboardBoardStore {
    static let shared = WhiteboardBoardStore()
    private var boards: [String: WhiteboardDocument] = [:]
    private let lock = NSLock()

    func board(for workspaceKey: String) -> WhiteboardDocument {
        lock.lock()
        defer { lock.unlock() }
        if let board = boards[workspaceKey] { return board }
        let fresh = WhiteboardDocument(name: String(localized: "白板"))
        boards[workspaceKey] = fresh
        return fresh
    }

    func mutate(_ workspaceKey: String, _ mutation: (WhiteboardDocument) -> WhiteboardDocument) -> WhiteboardDocument {
        lock.lock()
        defer { lock.unlock() }
        let current = boards[workspaceKey] ?? WhiteboardDocument(name: String(localized: "白板"))
        let next = mutation(current)
        boards[workspaceKey] = next
        return next
    }
}

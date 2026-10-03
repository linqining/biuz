import Foundation

enum MessageRole: String, Codable { case user, agent }
enum MessageStatus: String, Codable { case done, streaming }
enum ToolCallStatus: String, Codable { case running, done, failed }
enum ToolKind: String, Codable { case bash, edit, read, ask, browser }

/// 工具调用卡（会话流内嵌）
struct ToolCall: Identifiable, Codable, Equatable {
    var id: String
    var kind: ToolKind
    var target: String
    var status: ToolCallStatus
    var duration: String?
    var addedLines: Int?
    var removedLines: Int?
    var output: String?
    var diff: [DiffLine]?
}

enum TodoState: String, Codable { case done, now, todo }

struct TodoItem: Identifiable, Codable, Equatable {
    var id: String
    var title: String
    var state: TodoState
}

struct AgentQuestion: Codable, Equatable {
    var text: String
    var quickReplies: [String]
}

/// 连接态待处理交互投影（conversation state.pendingInteractions 的移动端映射）：
/// permission = 权限审批卡（命令/路径/影响结构化排版）；userInput / elicitation =
/// Agent 提问（快捷回复/输入框应答）。字段宽容解析（id/kind 必有，其余可缺）。
struct RemotePendingInteraction: Identifiable, Equatable {
    var id: String
    var kind: String
    var title: String?
    var command: String?
    var path: String?
    var impact: String?
    var options: [String] = []

    /// 是否权限审批类（批准/拒绝下发 resolveInteraction）
    var isPermission: Bool { kind == "permission" }
}

/// 会话消息：用户气泡 / Agent 正文 / 内嵌工具卡、todo 卡、提问卡
struct ChatMessage: Identifiable, Equatable {
    var id: String
    var role: MessageRole
    var text: String
    var status: MessageStatus = .done
    var toolCall: ToolCall?
    var todos: [TodoItem]?
    var question: AgentQuestion?
    var timestamp: Date

    init(id: String, role: MessageRole, text: String,
         status: MessageStatus = .done, timestamp: Date,
         toolCall: ToolCall? = nil, todos: [TodoItem]? = nil, question: AgentQuestion? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.status = status
        self.timestamp = timestamp
        self.toolCall = toolCall
        self.todos = todos
        self.question = question
    }
}

struct Conversation: Identifiable, Equatable {
    var id: String
    var title: String
    var summary: String
    var directory: String
    var updatedAt: Date
    var unreadCount: Int = 0
    var isPinned: Bool = false
    var isRunning: Bool = false
    var isArchived: Bool = false
    var taskProgress: Double?
    var todoSummary: String?
}

enum TaskStatus: String, Codable { case waiting, running, done, failed }

struct TaskRecord: Identifiable, Hashable {
    var id: String
    var title: String
    var summary: String
    var directory: String
    var status: TaskStatus
    var todoDone: Int
    var todoTotal: Int
    var progress: Double
    var tools: [String]
    var updatedAt: Date
    var lastLog: String?
    var pendingCommand: String?
    var pendingImpact: String?
}

struct FileNode: Identifiable, Hashable {
    var id: String
    var name: String
    var path: String
    var isDirectory: Bool
    var size: Int?
    var children: [FileNode]?
}

enum DiffLineKind: String, Codable { case hunk, add, del, ctx }

struct DiffLine: Identifiable, Codable, Equatable {
    var id: String
    var kind: DiffLineKind
    var oldNumber: Int?
    var newNumber: Int?
    var text: String
}

struct DiffFile: Identifiable, Equatable {
    var id: String
    var path: String
    var language: String
    var added: Int
    var removed: Int
    var lines: [DiffLine]
    var isApproved: Bool = false
    var isRejected: Bool = false
}

struct TerminalLine: Identifiable, Equatable {
    var id: Int
    var label: String?
    var text: String
    var isCommand: Bool = false
    var isSuccess: Bool = false
}

enum ExecutorKind: String, CaseIterable, Identifiable, Codable {
    case cloudSandbox
    case pairedMac
    var id: String { rawValue }
    var label: String {
        switch self {
        case .cloudSandbox: return "云端沙盒"
        case .pairedMac: return "我的 Mac"
        }
    }
    var subtitle: String {
        switch self {
        case .cloudSandbox: return "隔离环境 · 自动配置"
        case .pairedMac: return "通过本机 Host 执行"
        }
    }
}

enum ThoughtLevel: String, CaseIterable, Identifiable, Codable {
    case off, low, medium, high
    var id: String { rawValue }
    var label: String {
        switch self {
        case .off: return "关闭"
        case .low: return "低"
        case .medium: return "中"
        case .high: return "高"
        }
    }
}

struct AppSettings: Equatable, Codable {
    var appearance: AppearanceMode = .system
    var notificationsEnabled: Bool = true
    var model: String = "GLM-5.3"
    var thoughtLevel: ThoughtLevel = .medium
    var language: String = "跟随系统"
}

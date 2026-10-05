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
    /// G-015：retryTurn 精确游标（行元数据 entityId，宽容解析；nil = 无游标不渲染重试）
    var entityId: String?
    /// G-020：workflow 启动类工具调用关联的 runId（宽容解析；nil = 非启动轮）
    var workflowRunId: String?
}

/// todo/plan 步骤状态：done/now/todo 为既有三态；failed 为 BiuZ 扩展
/// （映射 Qoder Error 态；桌面 v4 state.todos 的 status 仅 pending|in_progress|completed，
/// failed 供宽容解析 error|failed 等扩展值使用）
enum TodoState: String, Codable { case done, now, todo, failed }

struct TodoItem: Identifiable, Codable, Equatable {
    var id: String
    var title: String
    var state: TodoState
}

// MARK: - 思考/推理内容（屏 05 折叠块；项 4）

/// reasoning 行 → 折叠块数据。state：流式中/完成/中断（中断为移动端呈现的显式态）。
/// startedAt 仅在该行被观察到流式时记录（历史快照一次性到达的 done 行无时长，
/// duration 为 streaming→done 的实测用时，nil = 未知，UI 退化为仅字数摘要）。
enum ThinkingState: String, Codable { case streaming, done, interrupted }

struct ThinkingContent: Equatable {
    var text: String
    var state: ThinkingState
    var startedAt: Date?
    var duration: TimeInterval?
}

struct AgentQuestion: Codable, Equatable {
    var text: String
    var quickReplies: [String]
}

// MARK: - 桌面 workflow 运行进度（要求 5 / G-007 / G-008 · 只读展示）
// 权威 schema（桌面开源 v3.14.3 只读克隆）：
// - 通路 A：sessions-index.ts:44 workflowActivity（sessionWorkflowActivitySchema，
//   sessions-index-workflow-activity.ts:68）——{runs[≤4]}，run 摘要 {runId, name?, status 五态,
//   phases[{name, status 四态, alongside?}], currentPhase?, agentsWorking}；
// - 通路 B：workflow-runs.ts:508 workflowRunsStateSchema——{revision, runs[workflowRunSchema]}，
//   冷快照必带（snapshot.ts:497-499）、state.updated 键级替换（delta.ts:50）、专属增量 op 仅
//   workflowRun.updated / workflowRun.removed 两个（delta.ts:122/140，header/条目整替换）；
// - run 五态 pending|running|completed|errored|stopped（workflow-runs.ts:365）；
//   站点灯四态 pending|running|done|failed（STATUS_DOT 词汇，sessions-index-workflow-activity.ts:30）；
//   节点七相位 queued|dispatched|executing|waiting|repairing|nudged|settled（workflow-runs.ts:226）；
//   子代理实例 actors[] {siteId, ordinal, name?, status: waiting|running|completed, phaseName?}。

/// 站点灯四态（桌面 STATUS_DOT 同词汇；节点/阶段通用）
enum WorkflowStepStatus: String, Codable {
    case pending, running, done, failed

    /// 宽容映射桌面端 status/state/phase 词表（未知值归 pending）
    static func map(_ raw: String?) -> WorkflowStepStatus {
        switch (raw ?? "").lowercased() {
        case "running", "in_progress", "active", "executing", "dispatched",
             "waiting", "repairing", "nudged":
            // 七相位中 dispatched/executing/waiting/repairing/nudged 均视为进行中烧站点
            return .running
        case "done", "completed", "success", "succeeded", "finished": return .done
        case "failed", "error", "errored", "cancelled", "aborted": return .failed
        default: return .pending
        }
    }

    /// run 五态原词 → 站点灯四态（stopped 桌面语义为「当前站回退 pending」）
    static func mapRunStatus(_ raw: String?) -> WorkflowStepStatus {
        switch (raw ?? "").lowercased() {
        case "running": return .running
        case "completed": return .done
        case "errored": return .failed
        case "stopped": return .pending
        default: return .pending
        }
    }
}

/// 通路 A：sessions-index workflowActivity 的 run 摘要（侧栏迷你轨道数据；有界 ≤4 run）
struct SessionWorkflowPhase: Identifiable, Equatable {
    var id: String { name }
    var name: String
    var status: WorkflowStepStatus
    /// 进入本站时仍在跑的其他站下标（并行双线段依据；声明表才有）
    var alongside: [Int] = []
}

struct SessionWorkflowRunSummary: Identifiable, Equatable {
    var id: String   // runId
    var name: String?
    /// 五态原词（pending|running|completed|errored|stopped）
    var rawStatus: String
    var phases: [SessionWorkflowPhase]
    var currentPhase: String?
    var agentsWorking: Int

    /// 是否存活（桌面 isSessionWorkflowRunLive：pending|running）
    var isLive: Bool { rawStatus == "pending" || rawStatus == "running" }
}

/// 通路 A 投影（无 run 时 nil，行不渲染占位）
struct WorkflowActivitySummary: Equatable {
    var runs: [SessionWorkflowRunSummary]
}

/// 阶段节点（详情面板节点链；含子代理节点：isSubagent=true 时渲染为子代理卡片）
struct WorkflowNodeSummary: Identifiable, Equatable {
    var id: String
    var label: String
    var status: WorkflowStepStatus
    var isSubagent: Bool = false
    var summary: String?
}

/// 子代理实例（通路 B actors[] 权威投影：waiting|running|completed）
struct WorkflowActorSummary: Identifiable, Equatable {
    var id: String   // siteId#ordinal
    var name: String?
    /// 原词 waiting|running|completed
    var rawStatus: String
    var phaseName: String?
    /// G-021：子代理会话 id（桌面 workflowRunActorSchema.sessionId；nil = 无下钻入口）
    var sessionId: String?

    var status: WorkflowStepStatus {
        switch rawStatus {
        case "running": return .running
        case "completed": return .done
        default: return .pending
        }
    }
}

/// 会话 workflow run 只读投影（UI 不新增发送命令）。
/// resumable/truncated 为 CLI 算好透传的字段（桌面基准：UI 绝不自推导），只读展示。
struct WorkflowRunSummary: Equatable {
    var id: String
    var name: String
    /// 桌面五态原词（pending|running|completed|errored|stopped）
    var rawStatus: String
    var stopReason: String?
    var resumable: Bool
    var truncated: Bool
    var nodes: [WorkflowNodeSummary]
    var actors: [WorkflowActorSummary]
    var artifactsCount: Int = 0
    var pendingQuestionsCount: Int = 0
    var concurrency: Int?
    var concurrencyCeiling: Int?

    /// 站点灯四态映射（stopped→pending，UI 另以文案区分「已停止」）
    var status: WorkflowStepStatus { WorkflowStepStatus.mapRunStatus(rawStatus) }
    var isLive: Bool { rawStatus == "pending" || rawStatus == "running" }

    /// 进度点计数（done 节点 / 总节点）
    var doneCount: Int { nodes.filter { $0.status == .done }.count }
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
    /// 计划审批（G-017）：renderContext.kind == "plan_approval" 时的计划文本
    var planText: String?

    /// 是否权限审批类（批准/拒绝下发 resolveInteraction）
    var isPermission: Bool { kind == "permission" }
    /// 是否计划审批类（G-017：桌面 ElicitationDialog renderContext.kind="plan_approval"）
    var isPlanApproval: Bool { kind == "plan_approval" || kind == "plan" || kind == "plan-approval" }
}

/// 会话消息：用户气泡 / Agent 正文 / 内嵌思考折叠块、工具卡、todo 卡、提问卡
struct ChatMessage: Identifiable, Equatable {
    var id: String
    var role: MessageRole
    var text: String
    var status: MessageStatus = .done
    var toolCall: ToolCall?
    var todos: [TodoItem]?
    var question: AgentQuestion?
    var thinking: ThinkingContent?
    /// 附件引用（G-014：会话流图片/文件缩略图数据源；桌面行 attachments/ref 宽容解析，
    /// 空数组 = 无附件 → 不渲染任何占位块）
    var attachments: [String] = []
    var timestamp: Date

    init(id: String, role: MessageRole, text: String,
         status: MessageStatus = .done, timestamp: Date,
         toolCall: ToolCall? = nil, todos: [TodoItem]? = nil, question: AgentQuestion? = nil,
         thinking: ThinkingContent? = nil, attachments: [String] = []) {
        self.id = id
        self.role = role
        self.text = text
        self.status = status
        self.timestamp = timestamp
        self.toolCall = toolCall
        self.todos = todos
        self.question = question
        self.thinking = thinking
        self.attachments = attachments
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
    /// 会话来源（项 5 来源过滤 chips 数据口径）："mac" = 已配对桌面端（局域网/云中继均属
    /// 我的 Mac），"cloud" = 云端沙盒（BiuZ 尚无云端会话数据源，预留），nil = 未知/演示态。
    var source: String?
    /// 通路 A：sessions-index 行自带 workflowActivity（G-007；随既有订阅到达零新增订阅）。
    /// 无 run 时 nil → 行不渲染占位。
    var workflowActivity: WorkflowActivitySummary?

    /// 项目名（列表分组键）：directory 末段；directory 为空视为未绑定项目（走日期分组）
    var projectName: String? {
        let trimmed = directory.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return trimmed.split(separator: "/").last.map(String.init) ?? trimmed
    }
}

/// 设备列表项（项 2 机器层 / 项 6 执行目标选择器共用数据源）：
/// 云端沙盒固定一项 + 已配对 Mac（ServerRegistry 中继服务器）逐台。
/// Codable：执行目标选择器 per-conversation 持久化（UserDefaults JSON）。
struct DeviceOption: Identifiable, Equatable, Codable {
    var id: String
    var name: String
    var kind: ExecutorKind

    static let cloudSandbox = DeviceOption(id: "cloud", name: String(localized: "云端沙盒"), kind: .cloudSandbox)
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
        case .cloudSandbox: return String(localized: "云端沙盒")
        case .pairedMac: return String(localized: "我的 Mac")
        }
    }
    var subtitle: String {
        switch self {
        case .cloudSandbox: return String(localized: "隔离环境 · 自动配置")
        case .pairedMac: return String(localized: "通过本机 Host 执行")
        }
    }
}

enum ThoughtLevel: String, CaseIterable, Identifiable, Codable {
    case off, low, medium, high
    var id: String { rawValue }
    var label: String {
        switch self {
        case .off: return String(localized: "关闭")
        case .low: return String(localized: "低")
        case .medium: return String(localized: "中")
        case .high: return String(localized: "高")
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

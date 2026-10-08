import Foundation

/// 会话事件一步（模拟 Agent 回复脚本）
enum ReplyStep {
    case text(String)
    case tool(ToolCall)
    case todos([TodoItem])
    case question(AgentQuestion)
}

/// 内存实现：全部数据常驻内存，回复经 AsyncStream 逐字推送，接口边界见 `ConversationStore`。
actor MockConversationStore: @preconcurrency ConversationStore {

    private var conversations: [Conversation] = []
    private var messages: [String: [ChatMessage]] = [:]
    private var continuations: [UUID: AsyncStream<ConversationEvent>.Continuation] = [:]

    init() {
        seed()
    }

    func conversations() async -> [Conversation] {
        sortedConversations()
    }

    func observeConversations() -> AsyncStream<ConversationEvent> {
        AsyncStream { continuation in
            let key = UUID()
            continuations[key] = continuation
            continuation.yield(.conversationsReplaced(sortedConversations()))
            continuation.onTermination = { _ in
                Task { await self.removeContinuation(key) }
            }
        }
    }

    func messages(in conversationID: String) async -> [ChatMessage] {
        messages[conversationID] ?? []
    }

    /// G-021：演示态上下文用量随消息量动态增长（非硬编码常量），供会话流工具行展示
    func sessionContextUsage(in conversationID: String) async -> ContextUsageInfo? {
        let count = messages[conversationID]?.count ?? 0
        let size = 128_000
        let used = min(Int(Double(size) * 0.9), 18_000 + count * 3_800)
        return ContextUsageInfo(used: used, size: size)
    }

    func send(_ text: String, in conversationID: String) async -> Bool {
        let userMessage = ChatMessage(
            id: UUID().uuidString, role: .user, text: text, timestamp: Date())
        append(userMessage, to: conversationID)

        let replyID = UUID().uuidString
        let steps = planReply(for: text)
        setRunning(true, conversationID: conversationID)

        let task = Task { [weak self] in
            await self?.runReply(steps, conversationID: conversationID, replyID: replyID)
        }
        _ = task
        return true // 演示态恒受理
    }

    func answerQuestion(_ reply: String, in conversationID: String, questionID: String) async {
        let userMessage = ChatMessage(
            id: UUID().uuidString, role: .user, text: reply, timestamp: Date())
        append(userMessage, to: conversationID)

        let steps: [ReplyStep] = [
            .text("收到，按「\(reply)」处理。我会先更新执行计划，再继续推进剩余步骤。"),
            .tool(ToolCall(
                id: UUID().uuidString, kind: .bash,
                target: "swift test --filter SessionStoreTests",
                status: .running, duration: nil, output: nil, diff: nil)),
        ]
        setRunning(true, conversationID: conversationID)
        Task {
            await self.runReply(steps, conversationID: conversationID, replyID: UUID().uuidString)
        }
    }

    func createConversation(title: String, directory: String, executor: ExecutorKind,
                            modelSelection: NewSessionModelSelection? = nil) async -> Conversation {
        var conversation = Conversation(
            id: UUID().uuidString, title: title.isEmpty ? "新会话" : title,
            summary: "刚刚创建 · \(executor.label)",
            directory: directory, updatedAt: Date(),
            isRunning: false)
        // G-006 演示口径：新建会话按执行端赋来源（云端沙盒 → cloud / 我的 Mac → mac），
        // 使演示态来源过滤三档与新建链路语义一致
        conversation.source = executor == .cloudSandbox ? "cloud" : "mac"
        conversations.insert(conversation, at: 0)
        messages[conversation.id] = []
        yield(.conversationUpdated(conversation))
        yield(.conversationsReplaced(sortedConversations()))

        let welcome: [ReplyStep] = [
            .text(directory.isEmpty
                  ? "会话已创建，执行端：\(executor.label)，未绑定项目（纯对话）。\n\n告诉我你想做什么，我会按步骤推进并在关键操作前请求批准。"
                  : "会话已创建，执行端：\(executor.label)，工作目录 `\(directory)`。\n\n告诉我你想做什么，我会按步骤推进并在关键操作前请求批准。"),
        ]
        Task {
            await self.runReply(welcome, conversationID: conversation.id, replyID: UUID().uuidString)
        }
        return conversation
    }

    func setPinned(_ pinned: Bool, conversationID: String) async {
        update(id: conversationID) { $0.isPinned = pinned }
    }

    func setArchived(_ archived: Bool, conversationID: String) async {
        update(id: conversationID) { $0.isArchived = archived }
    }

    func markRead(conversationID: String) async {
        update(id: conversationID) { $0.unreadCount = 0 }
    }

    // MARK: - 行长按菜单（P1-5 演示态本地实现：不改既有行为，仅新增能力）

    func renameConversation(_ title: String, conversationID: String) async {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        update(id: conversationID) { $0.title = trimmed }
    }

    func markUnread(conversationID: String) async {
        update(id: conversationID) { $0.unreadCount = max($0.unreadCount, 1) }
    }

    // MARK: - 桌面 workflow 运行进度（要求 5 演示投影：运行中会话 c1 提供演示 run，
    // 其余会话无数据不渲染；演示工作流面板为只读展示，不改变既有交互路径）

    func workflowRun(in conversationID: String) async -> WorkflowRunSummary? {
        guard conversationID == "c1" else { return nil }
        return WorkflowRunSummary(
            id: "demo-workflow-run",
            name: "持久层重构 · 分步工作流",
            rawStatus: "running",
            stopReason: nil,
            resumable: false,
            truncated: false,
            nodes: [
                WorkflowNodeSummary(id: "n0", label: "梳理 SessionStore 调用面",
                                    status: .done, summary: "3 处同步读写 · 2 处单例直连"),
                WorkflowNodeSummary(id: "n1", label: "抽取 SessionStoreProtocol",
                                    status: .done, summary: "+5 / -2 已批准"),
                WorkflowNodeSummary(id: "n2", label: "迁移调用方",
                                    status: .running),
                WorkflowNodeSummary(id: "n3", label: "回归测试与提交",
                                    status: .pending),
            ],
            actors: [
                WorkflowActorSummary(id: "demo-site-0#1", name: "迁移调用方（子代理）",
                                     rawStatus: "running", phaseName: "迁移调用方"),
            ],
            artifactsCount: 2,
            pendingQuestionsCount: 0,
            concurrency: 1,
            concurrencyCeiling: 3)
    }

    /// 后台工作演示投影（c1）：1 运行中置顶 + 2 已结束折叠——面板「运行中优先」
    /// 口径的目检数据（状态四态词表与上游 backgroundWorkSummarySchema 一致）
    func backgroundWorks(in conversationID: String) async -> [BackgroundWorkSummary] {
        guard conversationID == "c1" else { return [] }
        let now = Date()
        return [
            BackgroundWorkSummary(
                workId: "demo-work-run-1", title: "全量回归 swift test",
                kind: "bash", rawStatus: "running",
                cancellable: true, resumable: false,
                runId: nil, sessionId: nil,
                startedAt: now.addingTimeInterval(-187), endedAt: nil, blocked: false),
            BackgroundWorkSummary(
                workId: "demo-work-run-2", title: "生成周报草稿",
                kind: "bash", rawStatus: "resultPending",
                cancellable: false, resumable: false,
                runId: nil, sessionId: nil,
                startedAt: now.addingTimeInterval(-540), endedAt: now.addingTimeInterval(-420),
                blocked: false),
            BackgroundWorkSummary(
                workId: "demo-work-run-3", title: "依赖图扫描",
                kind: "bash", rawStatus: "failed",
                cancellable: false, resumable: true,
                runId: nil, sessionId: nil,
                startedAt: now.addingTimeInterval(-3600), endedAt: now.addingTimeInterval(-3480),
                blocked: false),
        ]
    }

    // MARK: - 回复脚本

    private func runReply(_ steps: [ReplyStep], conversationID: String, replyID: String) async {
        for step in steps {
            switch step {
            case .text(let full):
                var message = ChatMessage(
                    id: replyID + UUID().uuidString, role: .agent, text: "",
                    status: .streaming, timestamp: Date())
                append(message, to: conversationID)
                // 模拟流式输出：每 24ms 推进 2~4 个字符
                var index = full.startIndex
                while index < full.endIndex {
                    let chunkCount = Int.random(in: 2...4)
                    var end = index
                    for _ in 0..<chunkCount where end < full.endIndex {
                        end = full.index(after: end)
                    }
                    message.text = String(full[full.startIndex..<end])
                    replace(message, in: conversationID)
                    index = end
                    try? await Task.sleep(nanoseconds: 24_000_000)
                }
                message.status = .done
                replace(message, in: conversationID)

            case .tool(var call):
                var message = ChatMessage(
                    id: replyID + UUID().uuidString, role: .agent, text: "",
                    status: .done, timestamp: Date(), toolCall: call)
                append(message, to: conversationID)
                try? await Task.sleep(nanoseconds: UInt64.random(in: 700_000_000...1_300_000_000))
                call.status = .done
                switch call.kind {
                case .bash:
                    call.duration = String(format: "%.1fs", Double.random(in: 0.3...2.4))
                    call.output = mockBashOutput(command: call.target)
                case .edit:
                    call.addedLines = call.addedLines ?? Int.random(in: 3...18)
                    call.removedLines = call.removedLines ?? Int.random(in: 1...6)
                    call.diff = call.diff ?? Self.sampleDiff(prefix: call.id)
                default:
                    call.duration = String(format: "%.1fs", Double.random(in: 0.2...1.2))
                }
                message.toolCall = call
                replace(message, in: conversationID)

            case .todos(let items):
                let message = ChatMessage(
                    id: replyID + UUID().uuidString, role: .agent, text: "",
                    status: .done, timestamp: Date(), todos: items)
                append(message, to: conversationID)

            case .question(let question):
                let message = ChatMessage(
                    id: replyID + UUID().uuidString, role: .agent, text: "",
                    status: .done, timestamp: Date(), question: question)
                append(message, to: conversationID)
                update(id: conversationID) { $0.unreadCount += 1 }
                yield(.conversationsReplaced(sortedConversations()))
            }
        }
        setRunning(false, conversationID: conversationID)
    }

    private func planReply(for userText: String) -> [ReplyStep] {
        [
            .text("收到。围绕「\(userText.prefix(24))」，我先检查当前工作区状态，再动手修改。"),
            .tool(ToolCall(id: UUID().uuidString, kind: .bash,
                           target: "git status --short && ls Sources", status: .running)),
            .text("工作区有未提交改动。我把改动拆成以下步骤推进："),
            .todos([
                TodoItem(id: UUID().uuidString, title: "梳理现状与影响面", state: .done),
                TodoItem(id: UUID().uuidString, title: "实施核心修改", state: .now),
                TodoItem(id: UUID().uuidString, title: "补齐测试并回归", state: .todo),
            ]),
            .tool(ToolCall(id: UUID().uuidString, kind: .edit,
                           target: "Sources/Core/SessionStore.swift",
                           status: .running, addedLines: 12, removedLines: 4)),
            .text("核心修改已完成并通过本地验证。接下来需要执行一条有副作用的命令："),
            .tool(ToolCall(id: UUID().uuidString, kind: .bash,
                           target: "swift build --product ZCodeCore && swift test", status: .running)),
            .question(AgentQuestion(
                text: "要现在把改动提交到 `feat/session-protocol` 分支吗？",
                quickReplies: ["提交", "先不提交", "给我看完整 diff"])),
        ]
    }

    private func mockBashOutput(command: String) -> String {
        """
        $ \(command)
         M Sources/Core/SessionStore.swift
        SessionStore.swift  TaskRunner.swift
        """
    }

    static func sampleDiff(prefix: String) -> [DiffLine] {
        var index = 0
        func line(_ kind: DiffLineKind, _ old: Int?, _ new: Int?, _ text: String) -> DiffLine {
            index += 1
            return DiffLine(id: "\(prefix)-\(index)", kind: kind,
                            oldNumber: old, newNumber: new, text: text)
        }
        return [
            line(.hunk, nil, nil, "@@ -18,7 +18,9 @@ final class SessionStore {"),
            line(.ctx, 18, 18, "  func sessions() -> [Session] {"),
            line(.del, 19, nil, "-    return fileStore.loadSync()"),
            line(.add, nil, 19, "+    func sessions() async throws -> [Session] {"),
            line(.add, nil, 20, "+    guard let data = try await fileStore.load() else { return [] }"),
            line(.add, nil, 21, "+    return try decoder.decode([Session].self, from: data)"),
            line(.ctx, 20, 22, "  }"),
            line(.hunk, nil, nil, "@@ -31,4 +33,6 @@ extension SessionStore {"),
            line(.del, 31, nil, "-    static let shared = SessionStore()"),
            line(.add, nil, 33, "+    let fileStore: KeyValueFileStore"),
            line(.add, nil, 34, "+    init(fileStore: KeyValueFileStore = JSONFileStore.default) {"),
            line(.add, nil, 35, "+        self.fileStore = fileStore"),
            line(.ctx, 32, 36, "  }"),
        ]
    }

    private func sortedConversations() -> [Conversation] {
        conversations
            .filter { !$0.isArchived }
            .sorted { a, b in
                if a.isPinned != b.isPinned { return a.isPinned }
                return a.updatedAt > b.updatedAt
            }
    }

    private func update(id: String, _ mutate: (inout Conversation) -> Void) {
        guard let index = conversations.firstIndex(where: { $0.id == id }) else { return }
        mutate(&conversations[index])
        yield(.conversationUpdated(conversations[index]))
        yield(.conversationsReplaced(sortedConversations()))
    }

    private func setRunning(_ running: Bool, conversationID: String) {
        update(id: conversationID) {
            $0.isRunning = running
            $0.updatedAt = Date()
        }
    }

    private func append(_ message: ChatMessage, to conversationID: String) {
        messages[conversationID, default: []].append(message)
        yield(.messageAppended(conversationID: conversationID, message: message))
    }

    private func replace(_ message: ChatMessage, in conversationID: String) {
        guard let index = messages[conversationID]?.firstIndex(where: { $0.id == message.id }) else { return }
        messages[conversationID]?[index] = message
        yield(.messageUpdated(conversationID: conversationID, message: message))
    }

    private func yield(_ event: ConversationEvent) {
        continuations.values.forEach { $0.yield(event) }
    }

    private func removeContinuation(_ key: UUID) {
        continuations[key] = nil
    }

    // MARK: - 预置数据

    private func seed() {
        let now = Date()
        let minutes: (Int) -> Date = { now.addingTimeInterval(TimeInterval(-$0 * 60)) }

        // G-006：seed 会话补 source 演示值——mac/cloud 两源并存，演示态来源过滤三档
        // 各自非空可验（「全部」档默认不变）；项目 directory 五组并存供多项目分组断言
        conversations = [
            Conversation(
                id: "c1", title: "重构会话持久层",
                summary: "已完成协议抽取与 async 化，等待你确认调用方迁移范围。",
                directory: "~/work/zcode", updatedAt: minutes(12),
                isPinned: true, isRunning: true,
                taskProgress: 0.6, todoSummary: "todo 3/5 · 12 分钟"),
            Conversation(
                id: "c2", title: "修复登录超时问题",
                summary: "Agent: 已定位到网关 504，准备重试策略。",
                directory: "", updatedAt: minutes(96),
                unreadCount: 2),
            Conversation(
                id: "c3", title: "生成周报 · 第 40 周",
                summary: "已生成 WEEK-40.md 并同步到桌面端。",
                directory: "~/work/notes", updatedAt: minutes(60 * 26),
                taskProgress: 1.0),
            Conversation(
                id: "c4", title: "API v3 迁移评估",
                summary: "Agent: 梳理出 14 个待迁移端点，其中 3 个破坏性变更。",
                directory: "~/work/api", updatedAt: minutes(60 * 30),
                unreadCount: 1),
            Conversation(
                id: "c5", title: "首页性能调优",
                summary: "Agent: 长列表改懒加载，帧率 42 → 58fps。",
                directory: "~/work/zcode-mobile", updatedAt: minutes(60 * 3),
                isRunning: true, taskProgress: 0.25, todoSummary: "todo 1/4 · 45 分钟"),
            Conversation(
                id: "c6", title: "补齐单元测试",
                summary: "覆盖率 61% → 78%，剩余模块明日继续。",
                directory: "~/work/zcode", updatedAt: minutes(60 * 50),
                taskProgress: 1.0),
        ]
        // 来源演示值（构造后统一赋，避免逐条构造参数膨胀）：c3/c4 云端沙盒源，其余我的 Mac 源
        for index in conversations.indices {
            conversations[index].source = ["c3", "c4"].contains(conversations[index].id) ? "cloud" : "mac"
        }
        // G-007 演示：运行中会话 c1/c5 携带 workflowActivity 演示值（行迷你轨道可验；
        // c3 无 workflowActivity → 不渲染占位）。c1 另带一条已完成 run 作 live-only
        // 过滤负向载体：行轨道只画运行中 run，已完成行不渲染（2026-10-08 用户口径）
        conversations[0].workflowActivity = WorkflowActivitySummary(runs: [
            SessionWorkflowRunSummary(
                id: "demo-activity-run",
                name: "持久层重构 · 分步工作流",
                rawStatus: "running",
                phases: [
                    SessionWorkflowPhase(name: "梳理调用面", status: .done),
                    SessionWorkflowPhase(name: "抽取协议", status: .done),
                    SessionWorkflowPhase(name: "迁移调用方", status: .running),
                    SessionWorkflowPhase(name: "回归验证", status: .pending),
                    SessionWorkflowPhase(name: "准备提交", status: .pending,
                                         alongside: [3]),
                    SessionWorkflowPhase(name: "发布说明", status: .pending),
                    SessionWorkflowPhase(name: "清理临时分支", status: .pending),
                ],
                currentPhase: "迁移调用方",
                agentsWorking: 2),
            SessionWorkflowRunSummary(
                id: "demo-activity-run-done",
                name: "已收尾旧工作流",
                rawStatus: "completed",
                phases: [
                    SessionWorkflowPhase(name: "方案确认", status: .done),
                    SessionWorkflowPhase(name: "归档完成", status: .done),
                ],
                currentPhase: "归档完成",
                agentsWorking: 0),
        ])
        conversations[4].workflowActivity = WorkflowActivitySummary(runs: [
            SessionWorkflowRunSummary(
                id: "demo-activity-run-2",
                name: "首页性能调优",
                rawStatus: "running",
                phases: [
                    SessionWorkflowPhase(name: "性能画像", status: .done),
                    SessionWorkflowPhase(name: "懒加载改造", status: .running),
                ],
                currentPhase: "懒加载改造",
                agentsWorking: 1),
        ])

        messages["c1"] = [
            ChatMessage(
                id: "c1-m0", role: .user,
                text: "帮我把 SessionStore 从单例改成协议注入，顺便把同步 IO 换成 async。",
                timestamp: minutes(58)),
            ChatMessage(
                id: "c1-m1", role: .agent,
                text: "好的。先看当前 `SessionStore` 的结构与调用点：",
                timestamp: minutes(57)),
            ChatMessage(
                id: "c1-m2", role: .agent, text: "", timestamp: minutes(57),
                toolCall: ToolCall(
                    id: "c1-t1", kind: .bash, target: "grep -rn \"SessionStore.shared\" Sources",
                    status: .done, duration: "0.6s",
                    output: "$ grep -rn \"SessionStore.shared\" Sources\nSources/App/AppRouter.swift:42\nSources/Features/ChatViewModel.swift:18\n2 matches")),
            ChatMessage(
                id: "c1-m3", role: .agent,
                text: "找到 3 处同步读写、2 处单例直连。执行计划如下：",
                timestamp: minutes(56)),
            ChatMessage(
                id: "c1-m4", role: .agent, text: "", timestamp: minutes(56),
                todos: [
                    TodoItem(id: "c1-todo1", title: "抽取 SessionStoreProtocol", state: .done),
                    TodoItem(id: "c1-todo2", title: "同步 IO 改 async/await", state: .done),
                    TodoItem(id: "c1-todo3", title: "JSONFileStore 注入实现", state: .done),
                    TodoItem(id: "c1-todo4", title: "迁移 2 处单例调用方", state: .now),
                    TodoItem(id: "c1-todo5", title: "回归测试与提交", state: .todo),
                ]),
            ChatMessage(
                id: "c1-m5", role: .agent, text: "", timestamp: minutes(31),
                toolCall: ToolCall(
                    id: "c1-t2", kind: .edit, target: "Sources/Core/SessionStore.swift",
                    status: .done, duration: "1.4s", addedLines: 5, removedLines: 2,
                    diff: Self.sampleDiff(prefix: "c1-t2"))),
            ChatMessage(
                id: "c1-m6", role: .agent,
                text: "协议抽取完成，diff 如上（+5 / -2）。还剩一个决定：旧调用方有 2 处仍直接持有 `SessionStore.shared`。",
                timestamp: minutes(13)),
            ChatMessage(
                id: "c1-m7", role: .agent, text: "", timestamp: minutes(12),
                question: AgentQuestion(
                    text: "要我一并迁移这 2 处调用方吗？",
                    quickReplies: ["迁移全部调用方", "保留单例兼容", "先看完整 diff"])),
        ]

        messages["c2"] = [
            ChatMessage(
                id: "c2-m0", role: .user, text: "登录接口偶发 504，帮我查一下重试策略。",
                timestamp: minutes(100)),
            ChatMessage(
                id: "c2-m1", role: .agent,
                text: "已定位：网关在读超时后返回 504，当前客户端没有对幂等 GET 做重试。建议加入指数退避（250ms 起，最多 3 次）。",
                timestamp: minutes(96)),
        ]

        for conversation in conversations where messages[conversation.id] == nil {
            messages[conversation.id] = [
                ChatMessage(
                    id: conversation.id + "-m0", role: .user,
                    text: conversation.title + "，请开始。",
                    timestamp: conversation.updatedAt.addingTimeInterval(-1200)),
                ChatMessage(
                    id: conversation.id + "-m1", role: .agent,
                    text: conversation.summary,
                    timestamp: conversation.updatedAt),
            ]
        }
    }
}

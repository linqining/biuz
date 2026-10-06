import Foundation

// MARK: - 远端任务存储：zcode-task 频道读面 + 会话行投影

/// 真实实现：TaskStore 协议按 mappingToApp 第 4 条落地。
/// - tasks() ← zcodeTaskService.listTaskList（真相源 tasks-index.sqlite）
/// - terminalStream/trajectoryLines ← conversation 行投影（toolCall/reasoning）
/// - 命令面（v3 纠偏口径）：approve/reject/stop 真实下发——审批经
///   conversationStore.currentPendingInteraction 解析真实 interactionId 后走 v4
///   resolveInteraction；stop 走 v4 stop 信封（sessionId=taskId）。均属「客户端发
///   命令、桌面代执行」；文件直写/配置写仍由 ReadOnlyGate 出口拦截。
actor RemoteTaskStore: @preconcurrency TaskStore {

    private weak var connection: ZCodeServerConnection?
    private weak var conversationStore: RemoteConversationStore?
    private let workspace: ServerWorkspaceInfo

    nonisolated var isReadOnly: Bool { true }

    private var cache: [TaskRecord] = []
    private var continuations: [UUID: AsyncStream<[TaskRecord]>.Continuation] = [:]
    private var terminalContinuations: [String: [UUID: AsyncStream<TerminalLine>.Continuation]] = [:]
    /// onDynamicTaskEvent 订阅（任务活性服务端推送；named gap「状态变化只能手动下拉刷新」修复）
    private var taskEventsSubscription: EventSubscription?
    private var taskEventsEnsureTask: Task<Void, Never>?

    init(connection: ZCodeServerConnection, workspace: ServerWorkspaceInfo,
         conversationStore: RemoteConversationStore?) {
        self.connection = connection
        self.workspace = workspace
        self.conversationStore = conversationStore
    }

    func tasks() async -> [TaskRecord] {
        await ensureTaskEventsSubscribed()
        await refresh()
        return cache
    }

    func observeTasks() -> AsyncStream<[TaskRecord]> {
        AsyncStream { continuation in
            let key = UUID()
            continuations[key] = continuation
            continuation.yield(cache)
            // 订阅即建立服务端推送（断线随 Store 重建重订阅：Store 与连接同生命周期）
            if taskEventsSubscription == nil {
                taskEventsEnsureTask = Task { await self.ensureTaskEventsSubscribed() }
            }
            continuation.onTermination = { _ in
                Task { await self.removeContinuation(key) }
            }
        }
    }

    /// onDynamicTaskEvent 订阅（workspace 维度）：事件更新 cache 并 yieldTasks；
    /// 事件不可完整映射时回退 refresh()（listTaskList 轻量全量）。
    private func ensureTaskEventsSubscribed() async {
        guard taskEventsSubscription == nil, let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        taskEventsSubscription = await connection.listen(
            "zcode-task", "onDynamicTaskEvent", .json(.object(builder.fields))) { [weak self] payload in
            guard let self else { return }
            Task { await self.handleTaskEvent(payload) }
        }
    }

    /// 事件 → cache 更新。ZCodeStreamEvent 家族宽容解析：携带完整 task 形态
    /// （taskId + workspacePath）就地 upsert；仅携带 taskId 则标记并回退 refresh。
    /// 状态迁移（→done/waiting/failed）挂钩本地通知（NotificationService 内部去重）。
    private func handleTaskEvent(_ payload: RPCValue) async {
        guard let dict = payload.jsonValue?.objectValue else { return }
        // 事件体外层可能直接是 task，也可能包在 task/event 字段里
        var taskJSON: [String: JSONValue]?
        if let nested = dict["task"]?.objectValue {
            taskJSON = nested
        } else if dict["taskId"]?.stringValue != nil, dict["workspacePath"]?.stringValue != nil {
            taskJSON = dict
        }
        if let taskJSON, let record = Self.mapTask(.object(taskJSON)) {
            var previousStatus: TaskStatus?
            if let index = cache.firstIndex(where: { $0.id == record.id }) {
                // 保留本地未变字段（todo 投影等事件可能缺省）
                var merged = record
                merged.todoDone = record.todoDone == 0 ? cache[index].todoDone : record.todoDone
                merged.todoTotal = record.todoTotal == 0 ? cache[index].todoTotal : record.todoTotal
                previousStatus = cache[index].status
                cache[index] = merged
            } else {
                cache.append(record)
            }
            // 任务状态/待审批本地通知（开关关闭或 3 秒去重窗口内由 NotificationService 抑制）
            if let previousStatus, previousStatus != record.status {
                let changeSummary = record.pendingImpact
                let title = record.title
                let status = record.status
                Task { @MainActor in
                    NotificationService.shared.handleTaskStatusChange(
                        taskID: record.id, taskTitle: title, status: status,
                        changeSummary: changeSummary)
                }
            }
            yieldTasks()
        } else if dict["taskId"]?.stringValue != nil || dict["sessionId"]?.stringValue != nil {
            await refresh()
        }
    }

    private func removeContinuation(_ key: UUID) {
        continuations.removeValue(forKey: key)
    }

    /// zcodeTaskService.listTaskList（zcodeTaskListTypes.ts:12-28）
    func refresh() async {
        guard let connection else { return }
        let query = RPCValue.jsonObject { builder in
            builder.set("kind", "timeline")
            builder.set("workspaceScopes", .array([.object([
                "workspacePath": .string(workspace.path),
            ])]))
            builder.set("sortBy", "updated")
            builder.set("limit", 50)
        }
        do {
            let result = try await connection.call("zcode-task", "listTaskList", query)
            guard let dict = result.jsonValue?.objectValue,
                  let items = dict["items"]?.arrayValue else { return }
            cache = items.compactMap { Self.mapTask($0) }
            yieldTasks()
        } catch {
            // 保持上一次快照（离线兜底由装配层处理）
        }
    }

    private func yieldTasks() {
        for continuation in continuations.values {
            continuation.yield(cache)
        }
    }

    static func mapTask(_ json: JSONValue) -> TaskRecord? {
        guard let dict = json.objectValue,
              let taskId = dict["taskId"]?.stringValue,
              let workspacePath = dict["workspacePath"]?.stringValue else { return nil }
        let status: TaskStatus
        switch dict["status"]?.stringValue {
        case "running": status = .running
        case "completed": status = .done
        case "error": status = .failed
        default: status = .waiting
        }
        var record = TaskRecord(
            id: taskId,
            title: dict["title"]?.stringValue ?? String(localized: "未命名任务"),
            summary: dict["lastError"]?.objectValue?["message"]?.stringValue
                ?? dict["changeSummary"].map { _ in String(localized: "有新的文件变更待审查") }
                ?? "",
            directory: workspacePath,
            status: status,
            todoDone: 0,
            todoTotal: 0,
            progress: status == .done ? 1 : 0,
            tools: [],
            updatedAt: Date(timeIntervalSince1970: Double(dict["updatedAt"]?.intValue ?? 0) / 1000),
            lastLog: dict["lastError"]?.objectValue?["detail"]?.stringValue,
            pendingCommand: nil,
            pendingImpact: nil)
        if let change = dict["changeSummary"]?.objectValue {
            let fileCount = change["fileCount"]?.intValue ?? 0
                let added = change["added"]?.intValue ?? 0
                let removed = change["removed"]?.intValue ?? 0
                record.pendingImpact = String(
                    format: String(localized: "文件 %lld · +%lld/-%lld"),
                    fileCount, added, removed)
        }
        return record
    }

    // MARK: 动作面（v3 纠偏口径：客户端发命令、桌面代执行）

    /// 批准：解析该任务（会话）最新 permission 类挂起交互 → v4 resolveInteraction
    /// {interactionId, answer:{approved:true, scope}}。真实 interactionId 缺失时
    /// 静默放弃（无对象可批，避免误 resolve）。
    func approve(taskID: String) async {
        await resolvePermission(taskID: taskID, approved: true, scope: "once")
    }

    /// 拒绝：同 approve（approved=false）。
    func reject(taskID: String) async {
        await resolvePermission(taskID: taskID, approved: false, scope: "once")
    }

    /// 权限应答共用支路：interactionId 来自 conversation state.pendingInteractions
    /// 投影；answer 注入 approved + scope（三档授权范围由 UI 层注入，此处默认仅本次）。
    func resolvePermission(taskID: String, approved: Bool, scope: String) async {
        guard let conversationStore else { return }
        guard let interaction = await conversationStore.currentPendingInteraction(
            taskID, kinds: ["permission"]) else { return }
        guard let interactionId = RemoteConversationStore.interactionId(of: interaction) else { return }
        await conversationStore.resolveInteractionRaw(
            taskID,
            interactionId: interactionId,
            answer: .object([
                "approved": .bool(approved),
                "scope": .string(scope),
            ]))
    }

    /// 停止任务：委托 conversationStore.stopTurn（统一 sendCommand 信封——嵌套形态、
    /// 握手 clientId、epoch 毫秒 issuedAt、workspace 信封齐全）。状态回流由
    /// onDynamicTaskEvent 驱动卡片/状态条翻转。桌面代执行命令，边界内允许。
    func stop(taskID: String) async {
        await conversationStore?.stopTurn(sessionId: taskID)
    }

    /// 失败重试：retryTurn 需 target{rowId, entityId}（rewind 后重喂 agent 的精确
    /// 游标），任务详情语境拿不到该游标——本轮不接（UI 无重试入口，保持协议位）。
    func retry(taskID: String) async {
        // retryTurn 游标缺失，暂不下发（诚实不实现，不虚构「已重试」）
    }

    /// 服务端全文搜索（P2）：listTaskList 透传 searchQuery（服务端
    /// searchable_text/snippets 检索，纯只读）。空 query 退化为普通刷新。
    func searchTasks(_ query: String) async -> [TaskRecord] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            await refresh()
            return cache
        }
        guard let connection else { return [] }
        let searchQuery = RPCValue.jsonObject { builder in
            builder.set("kind", "timeline")
            builder.set("workspaceScopes", .array([.object([
                "workspacePath": .string(workspace.path),
            ])]))
            builder.set("sortBy", "updated")
            builder.set("limit", 50)
            builder.set("searchQuery", trimmed)
        }
        do {
            let result = try await connection.call("zcode-task", "listTaskList", searchQuery)
            guard let dict = result.jsonValue?.objectValue,
                  let items = dict["items"]?.arrayValue else { return [] }
            return items.compactMap { Self.mapTask($0) }
        } catch {
            return []
        }
    }

    // MARK: 终端输出 / 轨迹投影

    func terminalStream(taskID: String) -> AsyncStream<TerminalLine> {
        AsyncStream { continuation in
            let key = UUID()
            terminalContinuations[taskID, default: [:]][key] = continuation
            continuation.onTermination = { _ in
                Task { await self.removeTerminalContinuation(taskID: taskID, key: key) }
            }
        }
    }

    private func removeTerminalContinuation(taskID: String, key: UUID) {
        terminalContinuations[taskID]?.removeValue(forKey: key)
    }

    func trajectoryLines(taskID: String) async -> [TerminalLine] {
        // reasoning + toolCall 行投影（mappingToApp：trajectoryLines ← reasoning+toolCall）
        guard let conversationStore else { return [] }
        let rows = await conversationStore.messages(in: taskID)
        var lines: [TerminalLine] = []
        var id = 0
        for message in rows {
            if message.role == .agent && !message.text.isEmpty && message.toolCall == nil {
                lines.append(TerminalLine(id: id, label: "reasoning", text: message.text))
                id += 1
            }
            if let tool = message.toolCall {
                lines.append(TerminalLine(id: id, label: tool.kind.rawValue.uppercased(),
                                          text: "\(tool.target)\(tool.output.map { " → \($0)" } ?? "")"))
                id += 1
            }
        }
        return lines
    }

    // MARK: 只读元数据（连接态展示：配置/模型/Token 用量；不调任何 set*）

    /// getTaskConfigOptions：option 名 → 当前值（currentValue 兼容 string/boolean）
    func taskConfigOptions(taskID: String) async -> [String: String]? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("taskId", taskID)
        do {
            let result = try await connection.call(
                "zcode-task", "getTaskConfigOptions", .json(.object(builder.fields)))
            let items = result.jsonValue?.arrayValue
                ?? result.jsonValue?["options"]?.arrayValue
                ?? result.jsonValue?["configOptions"]?.arrayValue
                ?? []
            var options: [String: String] = [:]
            for item in items {
                guard let dict = item.objectValue else { continue }
                let name = dict["name"]?.stringValue ?? dict["id"]?.stringValue
                guard let name else { continue }
                switch dict["currentValue"] {
                case .string(let value): options[name] = value
                case .bool(let value): options[name] = value ? String(localized: "开") : String(localized: "关")
                default: options[name] = "--"
                }
            }
            return options.isEmpty ? nil : options
        } catch {
            return nil
        }
    }

    /// getTaskModelSelection：当前绑定模型（只读展示；不调 setModel）
    func taskModelSelection(taskID: String) async -> String? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("taskId", taskID)
        do {
            let result = try await connection.call(
                "zcode-task", "getTaskModelSelection", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else { return nil }
            // ModelSelection {providerId, modelId, options:{reasoningLevel}}
            if let modelId = dict["modelId"]?.stringValue {
                let reasoning = dict["options"]?.objectValue?["reasoningLevel"]?.stringValue
                return reasoning.map { "\(modelId) · \($0)" } ?? modelId
            }
            return dict["model"]?.stringValue ?? dict["name"]?.stringValue
        } catch {
            return nil
        }
    }

    /// getTaskTokenUsage：累计用量（ZCodeTaskTokenUsageResult：input/output/totalTokens）
    func taskTokenUsage(taskID: String) async -> TaskTokenUsage? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("taskId", taskID)
        builder.set("workspacePath", workspace.path)
        do {
            let result = try await connection.call(
                "zcode-task", "getTaskTokenUsage", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else { return nil }
            let input = dict["inputTokens"]?.intValue ?? dict["input"]?.intValue ?? 0
            let output = dict["outputTokens"]?.intValue ?? dict["output"]?.intValue ?? 0
            let total = dict["totalTokens"]?.intValue ?? dict["total"]?.intValue ?? (input + output)
            guard input > 0 || output > 0 || total > 0 else { return nil }
            return TaskTokenUsage(input: input, output: output, total: total)
        } catch {
            return nil
        }
    }
}

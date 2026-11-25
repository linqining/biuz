import Foundation

// MARK: - 远端任务存储：zcode-task 频道读面 + 会话行投影

/// 真实实现：TaskStore 协议按 mappingToApp 第 4 条落地。
/// - tasks() ← zcodeTaskService.listTaskList（真相源 tasks-index.sqlite）
/// - trajectoryLines ← conversation 行投影（toolCall/reasoning）；无 terminalStream
///   （上游 terminal 频道需 create/write 自建 PTY，手机直写被 ReadOnlyGate 拦截、
///   且无「读任务终端」方法——v1.39 死 UI 移除后协议面一并撤除）
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
    /// onDynamicTaskEvent 订阅（任务活性服务端推送；named gap「状态变化只能手动下拉刷新」修复）
    private var taskEventsSubscription: EventSubscription?
    private var taskEventsEnsureTask: Task<Void, Never>?
    /// onDynamicWorkspaceEvent 订阅（任务列表结构变化推送：task_created 等触发
    /// 列表全量重拉——web 同款即时刷新；只订当前活跃工作区，多工作区靠
    /// bootstrap.tasks 合并口径不变）
    private var workspaceEventsSubscription: EventSubscription?

    init(connection: ZCodeServerConnection, workspace: ServerWorkspaceInfo,
         conversationStore: RemoteConversationStore?) {
        self.connection = connection
        self.workspace = workspace
        self.conversationStore = conversationStore
    }

    func tasks() async -> [TaskRecord] {
        await ensureTaskEventsSubscribed()
        await ensureWorkspaceEventsSubscribed()
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
            if workspaceEventsSubscription == nil {
                Task { await self.ensureWorkspaceEventsSubscribed() }
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

    /// onDynamicWorkspaceEvent 订阅（web 对齐 2026-10-06，bundle 消费点逐字取证）：
    /// 入参 `{workspacePath, workspaceIdentity?}`（identity 在场才携）；事件载荷
    /// `{type:'workspace_task_list_changed', workspacePath, workspaceIdentity,
    /// reason:'task_created'|…}`——列表结构变化事件不携完整 task 行，web 处理即
    /// 全量拉新（`task_created → A(), M.current()`），移动端同构回退 refresh()。
    /// 只订当前活跃工作区（web 按 workspaceKey 逐区订阅的裁剪——移动端多工作区
    /// 经 bootstrap.tasks 合并呈现，逐区订阅成本高）。handler 注册先于
    /// eventListen 帧（ChannelClient.listen :255-271 实证 eventHandlers 先落表）。
    private func ensureWorkspaceEventsSubscribed() async {
        guard workspaceEventsSubscription == nil, let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        if let identity = workspace.workspaceIdentity, !identity.isEmpty {
            builder.set("workspaceIdentity", identity)
        }
        workspaceEventsSubscription = await connection.listen(
            "zcode-task", "onDynamicWorkspaceEvent", .json(.object(builder.fields))) { [weak self] payload in
            guard let self else { return }
            Task { await self.handleWorkspaceEvent(payload) }
        }
    }

    /// workspace_task_list_changed → 全量重拉（列表增删/结构变化；与 handleTaskEvent
    /// 的就地 upsert 互补——本事件无完整行可映射）。reason 宽容透传 diag 供取证。
    private func handleWorkspaceEvent(_ payload: RPCValue) async {
        guard let dict = payload.jsonValue?.objectValue,
              dict["type"]?.stringValue == "workspace_task_list_changed" else { return }
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            let reason = dict["reason"]?.stringValue ?? "?"
            UserDefaults.standard.set(
                "workspace_task_list_changed reason=\(reason)", forKey: "diag.task.wsEvent")
            UserDefaults.standard.synchronize()
        }
        await refresh()
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
    /// {interactionId, answer:{optionId}}（A-3 web 实证形态）。U-4：optionId 由
    /// UI 层拼好透传（四族 allowOnce/allowAlways）。U-5：结果三态如实回传。
    @discardableResult
    func approve(taskID: String, optionId: String) async -> TaskDecisionOutcome {
        await resolvePermission(taskID: taskID, optionId: optionId)
    }

    /// 拒绝：同 approve（rejectOnce/rejectAlways 由 optionId 承载方向）。
    @discardableResult
    func reject(taskID: String, optionId: String) async -> TaskDecisionOutcome {
        await resolvePermission(taskID: taskID, optionId: optionId)
    }

    /// 权限应答共用支路：interactionId 来自 conversation state.pendingInteractions
    /// 投影；answer 恒为对象 {optionId}（A-3：approved/scope 是 wire 不存在的键，
    /// 被 strip 后 answer 退化空对象 = 假成功）。interaction 不在场 →
    /// .interactionMissing（U-5：不再静默 return，调用方如实提示）。
    @discardableResult
    func resolvePermission(taskID: String, optionId: String) async -> TaskDecisionOutcome {
        guard let conversationStore else { return .undelivered }
        guard let interaction = await conversationStore.currentPendingInteraction(
            taskID, kinds: ["permission"]) else { return .interactionMissing }
        guard let interactionId = RemoteConversationStore.interactionId(of: interaction) else {
            return .interactionMissing
        }
        let ack = await conversationStore.resolveInteractionRaw(
            taskID,
            interactionId: interactionId,
            answer: .object(["optionId": .string(optionId)]))
        guard let ack else { return .undelivered }
        let status = ack["status"]?.stringValue
            ?? ack.objectValue?["ack"]?.objectValue?["status"]?.stringValue
        switch status {
        case nil, "accepted", "noop", "applied", "ok":
            return .accepted
        default:
            let reason = ack["reasonCode"]?.stringValue ?? status ?? "?"
            return .rejected(String(localized: "桌面端拒绝（\(reason)）"))
        }
    }

    /// 停止任务：委托 conversationStore.stopTurn（统一 sendCommand 信封——嵌套形态、
    /// 握手 clientId、epoch 毫秒 issuedAt、workspace 信封齐全）。状态回流由
    /// onDynamicTaskEvent 驱动卡片/状态条翻转。桌面代执行命令，边界内允许。
    func stop(taskID: String) async -> String? {
        guard let conversationStore else { return String(localized: "未连接桌面端，停止指令未送达") }
        // M2 写面如实回传：stopTurn 回执 nil = 命令未送达（连接异常/被拒），此前静默
        let ack = await conversationStore.stopTurn(sessionId: taskID)
        return ack == nil ? String(localized: "停止指令未送达 · 连接恢复后重试") : nil
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
            // 键名 = search（ZCodeTaskListQuery.search?，zcodeTaskListTypes.ts:17——
            // 2026-10-08 回归审核轮 §11.1#7：此前发 searchQuery 被服务端忽略，
            // 搜索静默返回未过滤的最近 50 条假结果）
            builder.set("search", trimmed)
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

    // MARK: 轨迹投影（v1.39：伪终端流已随死 UI 撤除）

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

    // MARK: 桌面分组视图结构（task-groups.read，任务看板对齐桌面分组）

    /// 桌面分组结构只读拉取。v1.39 重接上游真实 API【实证·上游仓
    /// packages/services/src/task-groups/contract.ts:38 + index.ts:47】：
    /// `task-groups.read()` **无参**（channels.ts:124；移动端 allowlist
    /// windowHostAttachmentRpcPolicy.ts:40 在位）——旧 `zcode-task.
    /// listGroupedTaskViewStructure` 在上游源码零命中，恒静默无效。
    /// 失败/解析不出 → nil（消费侧回退本地状态分组并轻提示）。
    func groupedTaskViewStructure() async -> DesktopTaskGrouping? {
        guard let connection else { return nil }
        do {
            let result = try await connection.call("task-groups", "read", .undefined)
            return Self.parseGroupedTaskView(result.jsonValue)
        } catch {
            // 保持 nil → TaskBoardView 回退本地状态分组（groupingUnavailable 轻提示）
            return nil
        }
    }

    /// 回执解析（v1.39 task-groups 重接，【实证·上游仓 contract.ts:19-23 +
    /// repository.ts:52-79】）：形状
    /// `{groups:[{id,title,color,createdAt,updatedAt}], members:[{groupId,
    /// workspaceKey,taskId,sortOrder}], order:[{type:"group"|"task",key,sortOrder}]}`
    /// ——成员在顶层 members、按自带 groupId 归组、组内序取 sortOrder；组排序取
    /// order 中 type=="group" 的条目（key=groupId）sortOrder，无条目的组按回执序殿后。
    /// members 只携 workspaceKey（= identity?.trim() || workspacePath，
    /// task-realtime-core.ts:84-89）——不带 workspacePath/identity 拆解键。
    /// 回执无 groups 键/非对象 → nil（空 groups 数组是合法态）。
    static func parseGroupedTaskView(_ value: JSONValue?) -> DesktopTaskGrouping? {
        let top = value?.objectValue ?? [:]
        // 宽容：部分服务面把返回值包一层 {result:{…}}（v4 命令 ack 先例）；结构本体仍以顶层为准
        let root = top["groups"] != nil ? top : (top["result"]?.objectValue ?? top)
        guard let rawGroups = root["groups"]?.arrayValue else { return nil }
        // 顶层 members 按 groupId 归组（sortOrder 升序 = 桌面组内序）
        var membersByGroup: [String: [(order: Int, ref: DesktopTaskGrouping.TaskRef)]] = [:]
        for member in root["members"]?.arrayValue ?? [] {
            guard let entry = member.objectValue,
                  let groupId = entry["groupId"]?.stringValue,
                  let taskId = entry["taskId"]?.stringValue else { continue }
            let ref = DesktopTaskGrouping.TaskRef(
                workspaceKey: entry["workspaceKey"]?.stringValue ?? "",
                taskId: taskId)
            membersByGroup[groupId, default: []].append(
                (entry["sortOrder"]?.intValue ?? Int.max, ref))
        }
        // 组排序：order 组条目 sortOrder 升序；无排序条目的组按回执序殿后
        var groupOrder: [String: Int] = [:]
        for node in root["order"]?.arrayValue ?? [] {
            guard let entry = node.objectValue,
                  entry["type"]?.stringValue == "group",
                  let key = entry["key"]?.stringValue else { continue }
            groupOrder[key] = entry["sortOrder"]?.intValue ?? 0
        }
        let groups = rawGroups.enumerated().compactMap { index, groupJSON -> (group: DesktopTaskGrouping.Group, sort: Int, fallback: Int)? in
            guard let group = parseGroupedTaskViewGroup(groupJSON) else { return nil }
            return (group, groupOrder[group.id] ?? Int.max, index)
        }
        return DesktopTaskGrouping(groups: groups.sorted { lhs, rhs in
            lhs.sort != rhs.sort ? lhs.sort < rhs.sort : lhs.fallback < rhs.fallback
        }.map { entry in
            var group = entry.group
            let refs = (membersByGroup[group.id] ?? [])
                .sorted { $0.order < $1.order }
                .map(\.ref)
            group.taskRefs = refs
            group.taskIDs = refs.map(\.taskId)
            return group
        })
    }

    /// 组条目：{id, title, color, createdAt, updatedAt}（contract.ts:5-11；color
    /// 不在移动端展示面，不投影）。
    private static func parseGroupedTaskViewGroup(_ json: JSONValue) -> DesktopTaskGrouping.Group? {
        guard let dict = json.objectValue else { return nil }
        guard let id = dict["id"]?.stringValue ?? dict["groupId"]?.stringValue, !id.isEmpty else {
            return nil
        }
        return DesktopTaskGrouping.Group(
            id: id,
            name: dict["title"]?.stringValue ?? id,
            color: dict["color"]?.stringValue,
            taskRefs: [],
            taskIDs: [])
    }
}

/// task-groups.read 回执投影（v1.39 重接上游真实 API；旧 zcode-task.
/// listGroupedTaskViewStructure 形状已废弃——该命令上游源码零命中）。
struct DesktopTaskGrouping: Equatable, Sendable {
    /// 组成员条目（回执 members 只携 workspaceKey + taskId，不拆解路径/身份；
    /// 写面 move 的任务引用由调用方按会话归属工作区构造）
    struct TaskRef: Equatable, Sendable {
        let workspaceKey: String
        let taskId: String
    }
    struct Group: Equatable, Sendable {
        /// groupId（TaskGroup.id——写面 save/move/ungroup 的真实定址键）
        let id: String
        /// 组名（TaskGroup.title；title 缺席时以 id 兜底显示）
        let name: String
        /// G36 组色（TaskGroup.color 七档词表 gray/red/orange/yellow/green/blue/purple；
        /// contract.ts:4 union【实证·上游仓】；缺席 = nil 灰点）
        var color: String?
        /// 组成员（回执 members 序；sortOrder 升序）
        var taskRefs: [TaskRef]
        /// 成员 taskId（taskRefs 投影；消费侧 TaskBoardView 按 id 对齐缓存、
        /// move 链按 id 定位 sourceGroupId）
        var taskIDs: [String]
    }
    let groups: [Group]
}

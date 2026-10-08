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

    // MARK: 桌面分组视图结构（P2-6：listGroupedTaskViewStructure，任务看板对齐桌面分组）

    /// 桌面分组结构只读拉取。B-2 修正（web 实证 bundle，2026-10-06）：入参恒为
    /// `{workspaceScopes:[{workspacePath, workspaceIdentity?}]}`（web 调用点
    /// `listGroupedTaskViewStructure({workspaceScopes:i})`，条目含 workspaceIdentity）
    /// ——原「先 workspaceScopes 后扁平 {workspacePath} 双形态探针」系零取证时期的
    /// 猜测序，扁平形态不在 web 调用面，撤除。失败/解析不出 → nil（消费侧回退本地
    /// 状态分组并轻提示）。
    func groupedTaskViewStructure() async -> DesktopTaskGrouping? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("workspaceScopes", .array([.object(
            Self.taskWorkspaceScopeJSON(workspace))]))
        do {
            let result = try await connection.call(
                "zcode-task", "listGroupedTaskViewStructure", .json(.object(builder.fields)))
            return Self.parseGroupedTaskView(result.jsonValue)
        } catch {
            // 保持 nil → TaskBoardView 回退本地状态分组（groupingUnavailable 轻提示）
            return nil
        }
    }

    /// workspaceScopes 条目（B-2/B-3 共用；web K3 形状 `{workspacePath, workspaceIdentity?}`，
    /// identity 缺席省键 = web JSON.stringify 丢 undefined 的同行为）
    static func taskWorkspaceScopeJSON(_ workspace: ServerWorkspaceInfo) -> [String: JSONValue] {
        var scope: [String: JSONValue] = ["workspacePath": .string(workspace.path)]
        if let identity = workspace.workspaceIdentity, !identity.isEmpty {
            scope["workspaceIdentity"] = .string(identity)
        }
        return scope
    }

    /// 回执解析（B-2 重写，web 实证【移植·bundle 逆向】2026-10-06）：形状
    /// `{groups:[{id,title,color,…}], members:[{groupId,workspacePath,
    /// workspaceIdentity,taskId}], topLevelOrders:[…]}`
    /// ——成员在**顶层 members 数组**、按自带 groupId 归组（组对象内无任何成员键；
    /// 旧解析在组内找 tasks/taskIds/items/order 恒空 → 每组 taskIDs 恒空 → 桌面
    /// 分组恒回退本地四组，即审查报告 B-2「RPC 成功的静默假成功」）。
    /// topLevelOrders 携带组排序（`{type:'group',groupId,sortOrder}`；任务条目
    /// `{type:'task',workspaceKey,taskId,sortOrder}` 仅排序用，成员归属仍以 members
    /// 为准）。回执无 groups 键/非对象 → nil（解析失败与「桌面无分组」区分：空
    /// groups 数组是合法态，返回空 groups 的 grouping）。
    static func parseGroupedTaskView(_ value: JSONValue?) -> DesktopTaskGrouping? {
        guard let dict = value?.objectValue, let rawGroups = dict["groups"]?.arrayValue else {
            return nil
        }
        // 顶层 members 按 groupId 归组（保持 members 数组序 = web 组内任务序）
        var membersByGroup: [String: [DesktopTaskGrouping.TaskRef]] = [:]
        for member in dict["members"]?.arrayValue ?? [] {
            guard let entry = member.objectValue,
                  let groupId = entry["groupId"]?.stringValue,
                  let taskId = entry["taskId"]?.stringValue,
                  let workspacePath = entry["workspacePath"]?.stringValue else { continue }
            membersByGroup[groupId, default: []].append(DesktopTaskGrouping.TaskRef(
                workspacePath: workspacePath,
                workspaceIdentity: entry["workspaceIdentity"]?.stringValue,
                taskId: taskId))
        }
        // 组排序：topLevelOrders 组条目 sortOrder 升序；无排序条目的组按回执序殿后
        var groupOrder: [String: Int] = [:]
        for order in dict["topLevelOrders"]?.arrayValue ?? [] {
            guard let entry = order.objectValue,
                  entry["type"]?.stringValue == "group",
                  let groupId = entry["groupId"]?.stringValue else { continue }
            groupOrder[groupId] = entry["sortOrder"]?.intValue ?? 0
        }
        let groups = rawGroups.enumerated().compactMap { index, groupJSON -> (group: DesktopTaskGrouping.Group, sort: Int, fallback: Int)? in
            guard let group = parseGroupedTaskViewGroup(groupJSON) else { return nil }
            let order = groupOrder[group.id].map { (sort: $0, fallback: index) }
                ?? (sort: Int.max, fallback: index)
            return (group: group, sort: order.sort, fallback: order.fallback)
        }
        return DesktopTaskGrouping(groups: groups.sorted { lhs, rhs in
            lhs.sort != rhs.sort ? lhs.sort < rhs.sort : lhs.fallback < rhs.fallback
        }.map { entry in
            // 成员归组：顶层 members 按自带 groupId 领取（组对象内无成员键——B-2）
            var group = entry.group
            group.taskRefs = membersByGroup[group.id] ?? []
            group.taskIDs = group.taskRefs.map(\.taskId)
            return group
        })
    }

    /// 组条目：web 组对象 {id, title, color, …}（bundle 消费点 e.group.id/
    /// group.title/group.color）。collapsed 不在 web 形状内，宽容读
    /// collapsed/isCollapsed（未取证，仅作本地折叠初值）。
    private static func parseGroupedTaskViewGroup(_ json: JSONValue) -> DesktopTaskGrouping.Group? {
        guard let dict = json.objectValue else { return nil }
        guard let id = dict["id"]?.stringValue ?? dict["groupId"]?.stringValue, !id.isEmpty else {
            return nil
        }
        let name = dict["title"]?.stringValue ?? id
        let collapsed = dict["collapsed"]?.boolValue ?? dict["isCollapsed"]?.boolValue ?? false
        return DesktopTaskGrouping.Group(
            id: id,
            name: name,
            collapsed: collapsed,
            taskRefs: [],
            taskIDs: [])
    }
}

/// P2-6：桌面分组视图结构投影（zcode-task.listGroupedTaskViewStructure 回执）。
/// B-2 重写（web 实证【移植·bundle 逆向】2026-10-06）：回执
/// `{groups:[{id,title,color,…}], members:[{groupId,workspacePath,workspaceIdentity,
/// taskId}], topLevelOrders:[…]}`——成员按自带 groupId 归组、组排序取 topLevelOrders
/// 组条目 sortOrder。taskRefs 保留完整工作区引用（B-3 全量视图写回所需）。
struct DesktopTaskGrouping: Equatable, Sendable {
    /// 组成员/写回条目（web 形状 {workspacePath, workspaceIdentity?, taskId}）
    struct TaskRef: Equatable, Sendable {
        let workspacePath: String
        let workspaceIdentity: String?
        let taskId: String
    }
    struct Group: Equatable, Sendable {
        /// groupId（web 组对象 id；真实组标识——写面禁止以组名充当，B-3）
        let id: String
        /// 组名（web title；title 缺席时以 id 兜底显示）
        let name: String
        /// 桌面折叠标记（宽容读 collapsed/isCollapsed；未取证——仅作本地折叠初值）
        let collapsed: Bool
        /// 组成员完整引用（回执 members 序；B-3 全量视图写回的数据基线）
        var taskRefs: [TaskRef]
        /// 成员 taskId（taskRefs 投影；消费侧 TaskBoardView 按 id 对齐缓存）
        var taskIDs: [String]
    }
    let groups: [Group]
}

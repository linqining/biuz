import Foundation

// MARK: - 远端会话存储：sessions-index 订阅 + conversation v4 行模型 → Store 协议

/// 真实实现：ConversationStore 协议按 mappingToApp 逐项落地。
/// - conversations()/observeConversations() ← subscribeSessionsIndexV4("sessions-index/<workspaceId>")
/// - messages(in:) ← subscribeConversationV4 + conversationRowsRangeV4
/// - 命令面（v3 纠偏口径）：send/answerQuestion/createConversation(firstInput)/
///   resolveInteractionRaw 均为「客户端发命令、桌面代执行」，真实下发（ReadOnlyGate
///   对 applyFileRewind 等文件直写类保持出口拦截）；置顶/归档/未读/重命名走
///   zcode-task 元数据写双写，远端失败回滚本地态。
actor RemoteConversationStore: @preconcurrency ConversationStore {

    private weak var connection: ZCodeServerConnection?
    private let workspace: ServerWorkspaceInfo

    /// v4 会话域 RPC 的 workspace 信封（桌面 Zod 强校验：`workspace.workspacePath` /
    /// `workspace.workspaceKey` 缺一即拒 ZCodeProtocolClientError；identity 缺席以 path 兜底）。
    /// 网关 2026-10 起强制——此前仅 sessionId 的调用被拒（订阅/历史/命令/附件全断）。
    private func applySessionTarget(_ builder: inout JSONObjectBuilder, sessionID: String?) {
        if let sessionID {
            builder.set("sessionId", sessionID)
        }
        builder.set("workspace", .object([
            "workspacePath": .string(workspace.path),
            "workspaceKey": .string(workspace.workspaceIdentity ?? workspace.path),
        ]))
    }

    nonisolated var isReadOnly: Bool { true }

    private var sessions: [String: SessionSummary] = [:]
    private var continuations: [UUID: AsyncStream<ConversationEvent>.Continuation] = [:]

    /// 会话行模型（rowId 键控）与派生消息
    private var rows: [String: [Int: RowRecord]] = [:]
    private var messages: [String: [ChatMessage]] = [:]
    private var snapshotState: [String: JSONValue] = [:] // state.updated patch 合并目标
    /// 桌面 workflow run 最新负载（要求 5：快照/workflowRun.updated 带内事件双通道；
    /// workflowRunFetched 记录 RPC 兜底已尝试，失败不重复请求）
    private var workflowRunStates: [String: JSONValue] = [:]
    private var workflowRunFetched: Set<String> = []
    /// G-008 通路 B：conversation state.workflowRuns（workflowRunsStateSchema {revision, runs[]}）。
    /// 冷快照必带（snapshot.ts:497-499）、state.updated 键级整体替换（delta.ts:50）、
    /// workflowRun.updated/removed 增量（delta.ts:122/140，header/条目整替换，绝无字段级深合并）
    private var workflowRunTables: [String: (revision: Int, runs: [JSONValue])] = [:]
    /// reasoning 行流式计时（消息ID → 流式开始时刻；streaming→done 时换算 duration）。
    /// 历史快照一次性到达的 done 行无开始时刻（首见即 done），UI 退化为仅字数摘要。
    private var reasoningStartedAt: [String: Date] = [:]
    private var reasoningDuration: [String: TimeInterval] = [:]
    private var pendingInteractions: [String: JSONValue] = [:] // sessionId → 最新 pendingInteractions 数组
    private var conversationSubscriptions: [String: EventSubscription] = [:]
    /// v4 订阅回执 subscriptionId（assembler dropped 时发 resyncConversationV4 的凭据）
    private var conversationSubscriptionIds: [String: String] = [:]
    /// conversation 订阅水位（快照 logEpoch + toSeq；resync base，缺 logEpoch 时传 null 全量）
    private var conversationWatermarks: [String: (logEpoch: String?, seq: Int)] = [:]
    private var sessionsIndexSubscription: EventSubscription?
    private var sessionsIndexSubscriptionId: String?
    private var sessionsIndexWatermark: (logEpoch: String?, seq: Int) = (nil, 0)
    private var localPinnedOverrides: [String: Bool] = [:]
    private var localArchivedOverrides: [String: Bool] = [:]
    /// 重命名/未读的本地即时反馈 override（renameTask/setTaskUnread 写失败回滚）
    private var localTitleOverrides: [String: String] = [:]
    private var localUnreadFlags: [String: Bool] = [:]
    /// 乐观回显的在途用户消息（conversationID → [消息ID: (文本, 锚点=发送时已知行数)]）；
    /// 服务端 userInput 行抵达（同文本）后去重移除，避免重连/快照后出现双气泡
    private var pendingLocalSends: [String: [String: (text: String, anchor: Int)]] = [:]
    /// 本地归档动作缓存（archiveTask/unarchiveTask 写后的即时呈现；listArchivedTasks 合并）
    private var localArchivedCache: [String: Conversation] = [:]
    /// workspace-config / model-selection 只读投影（连接态数据源）
    private var workspaceConfigState = WorkspaceConfigInfo()
    private var workspaceConfigHandlerRegistered = false
    private var modelSelectionCache: ModelSelectionInfo?
    private var modelSelectionSubscribed = false
    private var modelSelectionSubscription: EventSubscription?
    private var modelSelectionContinuations: [UUID: AsyncStream<ModelSelectionInfo?>.Continuation] = [:]

    struct RowRecord {
        var rowId: Int
        var json: JSONValue
    }

    struct SessionSummary {
        var sessionId: String
        var title: String
        var phase: String
        var lastActivityAt: Date?
        var lastAssistantPreview: String?
        var pendingPermissionCount: Int = 0
        var pendingUserInputCount: Int = 0
        /// sessions-index 投影（setTaskPinned/archiveTask 写后的服务端回推；缺席 = 未投影）
        var pinned: Bool?
        var archived: Bool?
        /// 会话自带归属工作区（行内 workspacePath/workspace 字段；要求 4 分组键真源——
        /// 桌面侧栏多项目并存，不得以当前连接的 workspace 兜底，nil = 无法判定归属 → 列表归「其它」组）
        var workspacePath: String?
        /// 通路 A（G-007）：行自带 workflowActivity（sessions-index.ts:44 sessionWorkflowActivitySchema，
        /// 随既有订阅到达零新增订阅）；原始 JSON 保存，投影解析见 parseWorkflowActivity
        var workflowActivity: JSONValue?
    }

    init(connection: ZCodeServerConnection, workspace: ServerWorkspaceInfo) {
        self.connection = connection
        self.workspace = workspace
    }

    // MARK: 会话列表

    func conversations() async -> [Conversation] {
        await ensureSessionsIndexSubscribed()
        let list = sortedConversations()
        // 诊断：会话清单（完整 id+标题前缀）转储，供 -ZCodeOpenConversationId 取 id
        let dump = list.prefix(12).map { "\($0.id)=\($0.title.prefix(16))" }
            .joined(separator: " | ")
        UserDefaults.standard.set(dump, forKey: "diag.sessions")
        return list
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

    private func removeContinuation(_ key: UUID) {
        continuations.removeValue(forKey: key)
    }

    private func sortedConversations() -> [Conversation] {
        sessions.values
            .map { summary in
                var conversation = Conversation(
                    id: summary.sessionId,
                    title: localTitleOverrides[summary.sessionId] ?? summary.title,
                    summary: summary.lastAssistantPreview ?? String(localized: "暂无输出"),
                    // 要求 4 修复：分组键用每个会话自带的工作区字段（sessions-index 行携带），
                    // 不再以当前连接的 workspace.path 兜底——兜底曾使全部会话挤进同一项目组
                    // （真机实测：多项目只显示 mtt_mobile 一组）。无法判定归属 → 空串 → 「其它」组
                    directory: summary.workspacePath ?? "",
                    updatedAt: summary.lastActivityAt ?? Date.distantPast)
                conversation.isRunning = summary.phase == "running" || summary.phase == "prewarming"
                // G-007 通路 A：行迷你轨道数据（无 run 时 nil，行不渲染占位）
                conversation.workflowActivity = Self.parseWorkflowActivity(summary.workflowActivity)
                // 来源过滤（G-006）数据口径：连接态会话均来自当前桌面端（局域网/云中继
                // 均属"我的 Mac"）；云端沙盒执行端尚无会话数据源（档位在无 cloud 数据时置灰）
                conversation.source = "mac"
                // 本地 override 优先（操作即时反馈），服务端投影次之（setTaskPinned/archiveTask 回推）
                conversation.isPinned = localPinnedOverrides[summary.sessionId] ?? summary.pinned ?? false
                conversation.isArchived = localArchivedOverrides[summary.sessionId] ?? summary.archived ?? false
                let pending = summary.pendingPermissionCount + summary.pendingUserInputCount
                // 未读 = 待交互数；「标记未读」本地置位（服务端无未读投影时仍可呈现）
                conversation.unreadCount = localUnreadFlags[summary.sessionId] == true
                    ? max(pending, 1) : pending
                if summary.pendingPermissionCount > 0 {
                    conversation.taskProgress = nil
                    conversation.todoSummary = "\(summary.pendingPermissionCount) 项待审批"
                }
                return conversation
            }
            .filter { !$0.isArchived }
            .sorted { lhs, rhs in
                if lhs.isPinned != rhs.isPinned { return lhs.isPinned }
                return lhs.updatedAt > rhs.updatedAt
            }
    }

    private func yieldConversationsReplaced() {
        let event = ConversationEvent.conversationsReplaced(sortedConversations())
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }

    // MARK: sessions-index 订阅

    private func ensureSessionsIndexSubscribed() async {
        guard sessionsIndexSubscription == nil, let connection else { return }
        let topic = "sessions-index/\(workspace.path)"
        do {
            // 服务端签名要求 topic + workspacePath（zcodeAgentPluginParams.ts:8-11）
            let arg = RPCValue.jsonObject { builder in
                builder.set("topic", topic)
                builder.set("workspacePath", workspace.path)
            }
            // handler 先于订阅注册：中继桥快照帧在 subscribe 回执前即推，
            // routeFrame 对未注册 topic 的帧会丢弃（workspace-config 的 replay 机制同一动因）
            await connection.setFrameHandler(topic: topic) { [weak self] frame in
                Task { await self?.handleSessionsIndexFrame(frame) }
            }
            await connection.setFrameDropHandler(key: "sessions") { [weak self] in
                guard let self else { return }
                Task { await self.resyncSessionsIndex() }
            }
            let reply = try await connection.call("zcode-agent", "subscribeSessionsIndexV4", arg)
            // 回执形态：{ack:{subscriptionId,mode,logEpoch}}（中继桥实测）；顶层兼容局域网
            let ack = reply.jsonValue?["ack"]?.objectValue ?? reply.jsonValue?.objectValue
            sessionsIndexSubscriptionId = ack?["subscriptionId"]?.stringValue
            await connection.log(.ok, "subscribeSessionsIndexV4 · \(sessionsIndexSubscriptionId ?? "nil")")
            sessionsIndexSubscription = EventSubscription { [weak self] in
                guard let self else { return }
                Task {
                    await self.disposeSessionsIndex()
                }
            }
        } catch {
            // 订阅失败兜底（named gap「订阅失败列表恒空」）：listSessions 只读拉一次列表。
            // 快照覆盖语义：订阅成功后帧仍以快照为准。
            await connection.log(.info, "subscribeSessionsIndexV4 失败，回退 listSessions：\(error.localizedDescription)")
            await fallbackListSessions()
        }
    }

    /// listSessions 兜底读（zcode-agent.listSessions，workspacePath 界定范围）。
    /// 回执宽容解析：顶层数组或 {sessions:[…]} 两种形态。
    private func fallbackListSessions() async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        do {
            let result = try await connection.call("zcode-agent", "listSessions", .json(.object(builder.fields)))
            let items = result.jsonValue?.arrayValue
                ?? result.jsonValue?["sessions"]?.arrayValue
                ?? []
            var next: [String: SessionSummary] = [:]
            for item in items {
                if let summary = SessionSummary.parse(item) {
                    next[summary.sessionId] = summary
                }
            }
            guard !next.isEmpty else { return }
            sessions = next
            yieldConversationsReplaced()
        } catch {
            // 兜底亦失败：列表保持空态（离线可用的兜底由装配层负责）
        }
    }

    /// assembler dropped → resyncSessionsIndexV4（subscriptionId + 水位 base；无 logEpoch 传 null 全量）
    private func resyncSessionsIndex() async {
        guard let connection, let subscriptionId = sessionsIndexSubscriptionId else { return }
        var builder = JSONObjectBuilder()
        builder.set("subscriptionId", subscriptionId)
        if let logEpoch = sessionsIndexWatermark.logEpoch {
            builder.set("base", .object(["logEpoch": .string(logEpoch), "seq": .int(sessionsIndexWatermark.seq)]))
        } else {
            builder.set("base", JSONValue.null)
        }
        _ = try? await connection.call(
            "zcode-agent", "resyncSessionsIndexV4", .json(.object(builder.fields)))
    }

    private func disposeSessionsIndex() async {
        guard let connection, sessionsIndexSubscription != nil else { return }
        await connection.removeFrameHandler(topic: "sessions-index/\(workspace.path)")
        sessionsIndexSubscription = nil
        sessionsIndexSubscriptionId = nil
        let arg = RPCValue.jsonObject { builder in
            builder.set("topic", "sessions-index/\(workspace.path)")
            builder.set("workspacePath", workspace.path)
        }
        _ = try? await connection.call("zcode-agent", "unsubscribeSessionsIndexV4", arg)
    }

    private func handleSessionsIndexFrame(_ frame: V4TopicFrame) {
        sessionsIndexWatermark.seq = frame.toSeq
        if let snapshot = frame.snapshot {
            sessionsIndexWatermark.logEpoch = snapshot.objectValue?["logEpoch"]?.stringValue
                ?? sessionsIndexWatermark.logEpoch
            applySessionsSnapshot(snapshot)
        }
        for delta in frame.deltas {
            guard case .object(let dict) = delta else { continue }
            switch dict["op"]?.stringValue {
            case "session.upserted":
                if let sessionJSON = dict["session"], let summary = SessionSummary.parse(sessionJSON) {
                    sessions[summary.sessionId] = summary
                }
            case "session.removed":
                if let sessionId = dict["sessionId"]?.stringValue {
                    sessions.removeValue(forKey: sessionId)
                }
            default:
                break
            }
        }
        yieldConversationsReplaced()
    }

    private func applySessionsSnapshot(_ snapshot: JSONValue) {
        guard case .object(let dict) = snapshot else { return }
        var next: [String: SessionSummary] = [:]
        for item in dict["sessions"]?.arrayValue ?? [] {
            if let summary = SessionSummary.parse(item) {
                next[summary.sessionId] = summary
            }
        }
        sessions = next
    }

    // MARK: 消息

    func messages(in conversationID: String) async -> [ChatMessage] {
        await ensureConversationSubscribed(conversationID)
        // 拉历史分页（rowsRange 向前，limit ≤200）
        if rows[conversationID]?.isEmpty ?? true {
            await loadHistory(conversationID: conversationID, beforeRowId: nil)
        }
        return messages[conversationID] ?? []
    }

    /// 向上分页（named gap 补全：beforeRowId 此前无调用入口）：
    /// 取 rows 最小 rowId 为游标拉更早一页，rowId 字典天然拼接去重；
    /// 返回是否还有更早数据。结果经 messagesReplaced 事件推给 UI。
    @discardableResult
    func loadOlder(conversationID: String) async -> Bool {
        guard let oldest = rows[conversationID]?.keys.min() else { return false }
        let hasMore = await loadHistory(conversationID: conversationID, beforeRowId: oldest)
        rebuildMessages(conversationID)
        yieldToAll(.messagesReplaced(
            conversationID: conversationID, messages: messages[conversationID] ?? []))
        return hasMore
    }

    private func ensureConversationSubscribed(_ conversationID: String) async {
        guard conversationSubscriptions[conversationID] == nil, let connection else { return }
        let topic = "conversation/\(conversationID)"
        do {
            // 服务端签名要求 topic + sessionId + workspace 信封（zcodeAgent.ts:144-146）
            let arg = RPCValue.jsonObject { builder in
                builder.set("topic", topic)
                applySessionTarget(&builder, sessionID: conversationID)
            }
            let reply = try await connection.call("zcode-agent", "subscribeConversationV4", arg)
            conversationSubscriptionIds[conversationID] = reply.jsonValue?["subscriptionId"]?.stringValue
            await connection.setFrameHandler(topic: topic) { [weak self] frame in
                Task { await self?.handleConversationFrame(conversationID, frame: frame) }
            }
            // 丢帧自愈：conversation assembler 是单键（多会话共用），dropped 时对全部
            // 已保存 subscriptionId 的订阅逐个 resync（幂等；无水位传 null 走全量快照）
            await connection.setFrameDropHandler(key: "conversation") { [weak self] in
                guard let self else { return }
                Task { await self.resyncAllConversations() }
            }
            conversationSubscriptions[conversationID] = EventSubscription { [weak self] in
                guard let self else { return }
                Task { await self.disposeConversation(conversationID) }
            }
            UserDefaults.standard.set(
                "sub ok rows=\(rows[conversationID]?.count ?? -1)",
                forKey: "diag.conv.\(conversationID.prefix(14))")
        } catch {
            let detail: String
            if let rpcError = error as? RPCError {
                detail = "name=\(rpcError.name) msg=\(rpcError.message) detail=\(rpcError.detail.map(String.init(describing:)) ?? "nil")"
            } else {
                detail = String(describing: error)
            }
            UserDefaults.standard.set(
                "sub ERR \(detail)",
                forKey: "diag.conv.\(conversationID.prefix(14))")
            // 订阅失败兜底第二步（调研 plan）：readSession（只读恢复）对账展示态，
            // 修正 pendingInteractionSummary 等角标；消息仍以可用流/分页为准。
            await reconcileViaReadSession(conversationID)
        }
    }

    /// readSession 对账（runtimePolicy=existing-only：只读恢复，不拉起 Agent）
    private func reconcileViaReadSession(_ conversationID: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: conversationID)
        builder.set("runtimePolicy", "existing-only")
        do {
            let result = try await connection.call("zcode-agent", "readSession", .json(.object(builder.fields)))
            guard let dict = result.jsonValue?.objectValue else { return }
            // 回执宽容：pendingInteractionSummary 可能在顶层或 session 包裹内
            let summary = dict["pendingInteractionSummary"]
                ?? dict["session"]?.objectValue?["pendingInteractionSummary"]
            if let summary {
                pendingInteractions[conversationID] = .array(summary.arrayValue ?? [])
                if var sessionSummary = sessions[conversationID],
                   let pending = summary.objectValue {
                    sessionSummary.pendingPermissionCount = pending["permissionCount"]?.intValue ?? 0
                    sessionSummary.pendingUserInputCount = pending["userInputCount"]?.intValue ?? 0
                    sessions[conversationID] = sessionSummary
                    yieldConversationsReplaced()
                }
            }
        } catch {
            // 对账失败：维持现有展示态
        }
    }

    private func resyncAllConversations() async {
        guard let connection else { return }
        for (conversationID, subscriptionId) in conversationSubscriptionIds {
            var builder = JSONObjectBuilder()
            builder.set("subscriptionId", subscriptionId)
            if let logEpoch = conversationWatermarks[conversationID]?.logEpoch {
                let seq = conversationWatermarks[conversationID]?.seq ?? 0
                builder.set("base", .object(["logEpoch": .string(logEpoch), "seq": .int(seq)]))
            } else {
                builder.set("base", JSONValue.null)
            }
            _ = try? await connection.call(
                "zcode-agent", "resyncConversationV4", .json(.object(builder.fields)))
        }
    }

    private func disposeConversation(_ conversationID: String) async {
        guard let connection, conversationSubscriptions.removeValue(forKey: conversationID) != nil else { return }
        await connection.removeFrameHandler(topic: "conversation/\(conversationID)")
        conversationSubscriptionIds.removeValue(forKey: conversationID)
        conversationWatermarks.removeValue(forKey: conversationID)
        let arg = RPCValue.jsonObject { builder in
            builder.set("topic", "conversation/\(conversationID)")
            applySessionTarget(&builder, sessionID: conversationID)
        }
        _ = try? await connection.call("zcode-agent", "unsubscribeConversationV4", arg)
    }

    /// 拉一页历史行；返回是否还有更早数据（回执 hasMore 缺席时以「非空页」近似）。
    @discardableResult
    private func loadHistory(conversationID: String, beforeRowId: Int?) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: conversationID)
        builder.set("limit", 200)
        if let beforeRowId {
            builder.set("beforeRowId", beforeRowId)
        }
        do {
            let result = try await connection.call(
                "zcode-agent", "conversationRowsRangeV4", .json(.object(buildFields(builder))))
            guard let dict = result.jsonValue?.objectValue else { return false }
            let pageRows = (dict["rows"]?.arrayValue ?? []).compactMap { row -> RowRecord? in
                guard let rowId = row.objectValue?["rowId"]?.intValue else { return nil }
                return RowRecord(rowId: rowId, json: row)
            }
            var table = rows[conversationID] ?? [:]
            for record in pageRows { table[record.rowId] = record }
            rows[conversationID] = table
            rebuildMessages(conversationID)
            // hasMore 显式字段优先；缺席时非空页视为可能还有更早数据（loadOlder 再探一页）
            return dict["hasMore"]?.boolValue ?? (!pageRows.isEmpty)
        } catch {
            // 历史拉取失败：以实时流为准（诊断：落盘错误详情，空列表类问题取证）
            let detail: String
            if let rpcError = error as? RPCError {
                detail = "name=\(rpcError.name) msg=\(rpcError.message) detail=\(rpcError.detail.map(String.init(describing:)) ?? "nil")"
            } else {
                detail = String(describing: error)
            }
            UserDefaults.standard.set(
                "rowsRange ERR \(detail)",
                forKey: "diag.rowsrange.\(conversationID.prefix(14))")
            return false
        }
    }

    private func buildFields(_ builder: JSONObjectBuilder) -> [String: JSONValue] {
        builder.fields
    }

    // MARK: 行模型 → ChatMessage 映射

    private func handleConversationFrame(_ conversationID: String, frame: V4TopicFrame) {
        // 水位记录（resync base）：logEpoch 仅快照携带，缺席沿用旧值
        let previousEpoch = conversationWatermarks[conversationID]?.logEpoch
        conversationWatermarks[conversationID] = (
            frame.snapshot?.objectValue?["logEpoch"]?.stringValue ?? previousEpoch,
            frame.toSeq
        )
        if let snapshot = frame.snapshot {
            if let dict = snapshot.objectValue {
                if let rowsArray = dict["rows"]?.arrayValue {
                    var table = rows[conversationID] ?? [:]
                    for row in rowsArray {
                        if let rowId = row.objectValue?["rowId"]?.intValue {
                            table[rowId] = RowRecord(rowId: rowId, json: row)
                        }
                    }
                    rows[conversationID] = table
                }
                if let state = dict["state"] {
                    snapshotState[conversationID] = state
                    refreshPendingInteractions(conversationID, state: state)
                    // G-008：冷快照必带 workflowRuns（snapshot.ts:497-499——漏这一处，
                    // 刷新/重连后正在跑的 run 会静默消失）；键级整体替换
                    if let runsState = state.objectValue?["workflowRuns"] {
                        applyWorkflowRunsState(conversationID, runsState)
                    }
                }
                // 要求 5：快照内 workflowRun 单数键（旧实现兼容；缺席保持既有缓存）
                if let run = Self.extractWorkflowRun(dict) {
                    workflowRunStates[conversationID] = run
                }
            }
        }
        for delta in frame.deltas {
            applyDelta(conversationID, delta)
        }
        rebuildMessages(conversationID)
    }

    private func applyDelta(_ conversationID: String, _ delta: JSONValue) {
        guard case .object(let dict) = delta else { return }
        switch dict["op"]?.stringValue {
        case "row.appended", "row.upserted":
            guard let row = dict["row"], let rowId = row.objectValue?["rowId"]?.intValue else { return }
            rows[conversationID, default: [:]][rowId] = RowRecord(rowId: rowId, json: row)
        case "row.removed":
            guard let fromRowId = dict["fromRowId"]?.intValue else { return }
            var table = rows[conversationID] ?? [:]
            for key in table.keys where key >= fromRowId {
                table.removeValue(forKey: key)
            }
            rows[conversationID] = table
        case "row.delta":
            // 流式逐字追加：仅允许作用于流式态行（服务端保证）；path ∈ text|inputText|output.text|summaryText
            guard let rowId = dict["rowId"]?.intValue,
                  let append = dict["append"]?.stringValue else { return }
            guard var record = rows[conversationID]?[rowId],
                  case .object(var rowDict) = record.json else { return }
            let path = dict["path"]?.stringValue ?? "text"
            appendText(&rowDict, path: path, append: append)
            record.json = .object(rowDict)
            rows[conversationID]?[rowId] = record
        case "state.updated":
            guard let patch = dict["patch"]?.objectValue else { return }
            var current = snapshotState[conversationID]?.objectValue ?? [:]
            for (key, value) in patch {
                current[key] = value // 键级整体替换，不深合并
            }
            let merged = JSONValue.object(current)
            snapshotState[conversationID] = merged
            refreshPendingInteractions(conversationID, state: merged)
            // G-008：state.updated 的 workflowRuns 键（delta.ts:50；键级整体替换，无深合并）
            if let runsState = patch["workflowRuns"] {
                applyWorkflowRunsState(conversationID, runsState)
            }
            // state.patch 内也可能携带 workflowRun 单数键（旧实现兼容）
            if let run = patch["workflowRun"] {
                workflowRunStates[conversationID] = run
            }
        case "workflowRun.updated":
            // G-008：专属增量 op 之一（delta.ts:122）——header 键整键替换（run patch +
            // cleared 清除）+ actors/nodes 条目按 (siteId, ordinal) 整条替换/移除；
            // 绝无字段级深合并
            applyWorkflowRunUpdated(conversationID, dict)
        case "workflowRun.removed":
            // G-008：run 被生产者淘汰（delta.ts:140；只有生产者淘汰且必须说出来）
            if let runId = dict["runId"]?.stringValue,
               var table = workflowRunTables[conversationID] {
                table.runs.removeAll {
                    $0.objectValue?["runId"]?.stringValue == runId
                }
                if let revision = dict["revision"]?.intValue { table.revision = revision }
                workflowRunTables[conversationID] = table
            }
            workflowRunStates.removeValue(forKey: conversationID)
        default:
            break
        }
    }

    /// streamablePath：text | inputText | output.text | summaryText（core.ts 口径）
    private func appendText(_ row: inout [String: JSONValue], path: String, append: String) {
        switch path {
        case "text", "inputText", "summaryText":
            let existing = row[path]?.stringValue ?? ""
            row[path] = .string(existing + append)
        case "output.text":
            var output = row["output"]?.objectValue ?? [:]
            let existing = output["text"]?.stringValue ?? ""
            output["text"] = .string(existing + append)
            row["output"] = .object(output)
        default:
            break
        }
    }

    private func refreshPendingInteractions(_ conversationID: String, state: JSONValue) {
        if let array = state.objectValue?["pendingInteractions"]?.arrayValue {
            pendingInteractions[conversationID] = .array(array)
        }
    }

    /// 行集合 → ChatMessage 序列（mappingToApp 第 2 条）。
    /// 乐观回显去重：服务端同文本 userInput 行抵达后移除在途回显（防快照/重连双气泡）。
    private func rebuildMessages(_ conversationID: String) {
        let table = rows[conversationID] ?? [:]
        var result: [ChatMessage] = []
        var serverUserTexts: Set<String> = []
        for rowId in table.keys.sorted() {
            guard let record = table[rowId],
                  let row = record.json.objectValue,
                  let kind = row["kind"]?.stringValue else { continue }
            switch kind {
            case "userInput":
                let text = row["text"]?.stringValue ?? ""
                serverUserTexts.insert(text)
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .user,
                    text: text,
                    timestamp: Date(),
                    // G-014：桌面随行下发的图片/文件附件（截图类用户消息常见）
                    attachments: Self.extractAttachmentRefs(row)))
            case "assistantText":
                let state = row["state"]?.stringValue ?? "complete"
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: row["text"]?.stringValue ?? "",
                    status: state == "streaming" ? .streaming : .done,
                    timestamp: Date()))
            case "reasoning":
                // 项 4：reasoning → ThinkingContent 折叠块（不再 💭 前缀平铺进正文流）
                let state = row["state"]?.stringValue ?? "complete"
                let text = row["text"]?.stringValue ?? ""
                guard !text.isEmpty else { continue }
                let messageID = "row-\(rowId)"
                let thinkingState: ThinkingState
                switch state {
                case "streaming":
                    thinkingState = .streaming
                    if reasoningStartedAt[messageID] == nil {
                        reasoningStartedAt[messageID] = Date()
                    }
                case "error", "cancelled", "interrupted", "aborted":
                    thinkingState = .interrupted
                default:
                    thinkingState = .done
                    if let startedAt = reasoningStartedAt[messageID] {
                        reasoningDuration[messageID] = Date().timeIntervalSince(startedAt)
                    }
                }
                result.append(ChatMessage(
                    id: messageID, role: .agent,
                    text: "",
                    status: thinkingState == .streaming ? .streaming : .done,
                    timestamp: Date(),
                    thinking: ThinkingContent(
                        text: text,
                        state: thinkingState,
                        startedAt: reasoningStartedAt[messageID],
                        duration: reasoningDuration[messageID])))
            case "todo", "todos", "plan", "taskPlan", "todoList":
                // 行级 plan/todo 兜底（当前桌面 v4 的 todos 挂在会话 state 而非行；
                // 若服务端未来下发行形态则按宽容 schema 解析）
                if let todos = Self.parseTodoRow(row, rowId: rowId), !todos.isEmpty {
                    result.append(ChatMessage(
                        id: "row-\(rowId)", role: .agent,
                        text: "",
                        status: .done,
                        timestamp: Date(),
                        todos: todos))
                }
            case "toolCall":
                let status = row["status"]?.stringValue ?? "running"
                let outputText = row["output"]?.objectValue?["text"]?.stringValue
                    ?? row["outputPreview"]?.objectValue?["text"]?.stringValue
                    ?? row["progress"]?.objectValue?["text"]?.stringValue
                // G-020：workflow 启动类工具调用（CreateWorkflow/AmendWorkflow/StartSavedWorkflow）
                // 宽容取关联 runId（行 result/回执内 runId 键）；G-015：行元数据 entityId 作重试游标
                let toolName = (row["toolName"]?.stringValue ?? "").lowercased()
                let workflowRunId = toolName.contains("workflow")
                    ? (row["result"]?.objectValue?["runId"]?.stringValue
                        ?? row["runId"]?.stringValue
                        ?? row["output"]?.objectValue?["runId"]?.stringValue)
                    : nil
                let toolCall = ToolCall(
                    id: row["toolCallId"]?.stringValue ?? "tool-\(rowId)",
                    kind: Self.mapToolKind(row["toolName"]?.stringValue),
                    target: row["inputText"]?.stringValue ?? row["toolName"]?.stringValue ?? "",
                    status: Self.mapToolStatus(status),
                    duration: nil,
                    addedLines: row["display"]?.objectValue?["addedLines"]?.intValue,
                    removedLines: row["display"]?.objectValue?["removedLines"]?.intValue,
                    output: outputText,
                    diff: nil,
                    entityId: row["entityId"]?.stringValue ?? row["turnId"]?.stringValue,
                    workflowRunId: workflowRunId)
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: "",
                    status: status == "running" || status == "inputStreaming" || status == "pendingApproval" ? .streaming : .done,
                    timestamp: Date(),
                    toolCall: toolCall))
            case "subagent":
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: "🤖 子智能体 · \(row["subagentType"]?.stringValue ?? "")：\(row["summaryText"]?.stringValue ?? "")",
                    timestamp: Date()))
            case "artifact":
                let name = row["displayName"]?.stringValue
                    ?? row["name"]?.stringValue ?? "产物"
                let type = row["artifactType"]?.stringValue
                    ?? row["type"]?.stringValue ?? "file"
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: "📦 产物 · \(name)（\(type)）",
                    timestamp: Date(),
                    attachments: Self.extractAttachmentRefs(row)))
            default:
                // turnHeader / hookInvocation / timelineMarker 不进消息流
                break
            }
        }
        // 诊断：行数/消息数/行类型直方图（空列表类问题现场取证）
        let kindHist = Dictionary(
            grouping: table.values.compactMap { $0.json.objectValue?["kind"]?.stringValue },
            by: { $0 })
            .map { "\($0.key):\($0.value.count)" }
            .sorted()
            .joined(separator: ",")
        UserDefaults.standard.set(
            "rows=\(table.count) msg=\(result.count) kinds=[\(kindHist)]",
            forKey: "diag.convbuild.\(conversationID.prefix(14))")
        // 在途回显：同文本服务端行已抵达 → 消费掉；未抵达的按锚点插入保持时间序
        // （服务端不回推用户行时，回显应位于其发送后的助手回复之前）
        if var outstanding = pendingLocalSends[conversationID], !outstanding.isEmpty {
            var consumedIDs: [String] = []
            for (echoID, entry) in outstanding where serverUserTexts.contains(entry.text) {
                consumedIDs.append(echoID)
            }
            for echoID in consumedIDs {
                outstanding.removeValue(forKey: echoID)
            }
            pendingLocalSends[conversationID] = outstanding
            var insertedCount = 0
            for (echoID, entry) in outstanding.sorted(by: { $0.value.anchor < $1.value.anchor }) {
                let index = min(max(0, entry.anchor + insertedCount), result.count)
                result.insert(ChatMessage(id: echoID, role: .user, text: entry.text, timestamp: Date()),
                              at: index)
                insertedCount += 1
            }
        }
        // 项 3：会话 state.todos（桌面 v4 口径：todos 挂在 state 而非独立行）→ 流末尾
        // 常驻流程面板消息（id 固定 "state-todos"，随 state.updated 增量刷新）。
        // 置于回显插入之后：回显锚点语义仍基于行数，不受面板消息影响。
        if let stateTodos = Self.parseStateTodos(snapshotState[conversationID]) {
            result.removeAll { $0.id == "state-todos" }
            result.append(ChatMessage(
                id: "state-todos", role: .agent,
                text: "",
                status: .done,
                timestamp: Date(),
                todos: stateTodos))
        }
        // 回显被服务端行消费 / row.removed 等导致列表收缩时，append/update 差分事件
        // 无法表达删除——补发 messagesReplaced 全量事件（ChatViewModel 幂等替换）
        let didShrink = result.count < (lastYieldedMessages[conversationID]?.count ?? 0)
        messages[conversationID] = result
        yieldMessageEvents(conversationID, latest: result)
        if didShrink {
            yieldToAll(.messagesReplaced(
                conversationID: conversationID, messages: messages[conversationID] ?? []))
        }
    }

    /// 行 → 附件引用列表（G-014 宽容解析：attachments 数组（字符串或 {ref} 对象）、
    /// 顶层 ref、artifact 行的 ref 字段；无附件返回空数组）
    nonisolated static func extractAttachmentRefs(_ row: [String: JSONValue]) -> [String] {
        var refs: [String] = []
        func push(_ value: JSONValue?) {
            guard let value else { return }
            if let s = value.stringValue, !s.isEmpty { refs.append(s) }
            if let r = value.objectValue?["ref"]?.stringValue, !r.isEmpty { refs.append(r) }
        }
        if let list = row["attachments"]?.arrayValue { list.forEach(push) }
        if refs.isEmpty { push(row["ref"]) }
        return refs
    }

    private func yieldMessageEvents(_ conversationID: String, latest: [ChatMessage]) {
        let previous = lastYieldedMessages[conversationID] ?? []
        // 简化事件语义：首帧 replaced，其后逐条 append/update（ChatViewModel 幂等去重）
        if previous.isEmpty {
            for message in latest {
                yieldToAll(.messageAppended(conversationID: conversationID, message: message))
            }
        } else {
            for (index, message) in latest.enumerated() where index >= previous.count {
                yieldToAll(.messageAppended(conversationID: conversationID, message: message))
            }
            for message in latest where previous.contains(where: { $0.id == message.id && $0 != message }) {
                yieldToAll(.messageUpdated(conversationID: conversationID, message: message))
            }
        }
        lastYieldedMessages[conversationID] = latest
    }

    private var lastYieldedMessages: [String: [ChatMessage]] = [:]

    private func yieldToAll(_ event: ConversationEvent) {
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }

    /// toolName → ToolKind（bash/edit/read/ask/browser 五类；未知归 bash）
    private static func mapToolKind(_ toolName: String?) -> ToolKind {
        switch (toolName ?? "").lowercased() {
        case let name where name.contains("bash") || name.contains("shell") || name.contains("terminal"):
            return .bash
        case let name where name.contains("edit") || name.contains("write") || name.contains("patch") || name.contains("apply"):
            return .edit
        case let name where name.contains("read") || name.contains("grep") || name.contains("glob") || name.contains("search") || name.contains("list"):
            return .read
        case let name where name.contains("ask") || name.contains("question"):
            return .ask
        case let name where name.contains("browser") || name.contains("navigate"):
            return .browser
        default:
            return .bash
        }
    }

    /// inputStreaming|pendingApproval|running → .running；success → .done；error|cancelled → .failed
    private static func mapToolStatus(_ status: String) -> ToolCallStatus {
        switch status {
        case "success": return .done
        case "error", "cancelled": return .failed
        default: return .running
        }
    }

    // MARK: plan/todo 步骤解析（项 3）

    /// 会话 state.todos → [TodoItem]（桌面 v4 schema：todos[] = {content,
    /// status: pending|in_progress|completed, priority}，src-wmk2orCZ.js:154965 yp）。
    /// 宽容兼容 title/label 与 error|failed 扩展态；空/缺失返回 nil（不渲染面板）。
    static func parseStateTodos(_ state: JSONValue?) -> [TodoItem]? {
        guard let dict = state?.objectValue else { return nil }
        let items = dict["todos"]?.arrayValue
            ?? dict["todoGroups"]?.objectValue?["todos"]?.arrayValue
            ?? []
        return parseTodoItems(items, idPrefix: "state-todo")
    }

    /// 行级 todo/plan 兜底解析：行内 todos/items/steps/plan 数组，或行本身即单条 todo
    static func parseTodoRow(_ row: [String: JSONValue], rowId: Int) -> [TodoItem]? {
        let items = row["todos"]?.arrayValue
            ?? row["items"]?.arrayValue
            ?? row["steps"]?.arrayValue
            ?? row["plan"]?.arrayValue
        if let items, !items.isEmpty {
            return parseTodoItems(items, idPrefix: "row-todo-\(rowId)")
        }
        // 单条形态：行自身携带 content/title + status
        if let title = row["content"]?.stringValue ?? row["title"]?.stringValue ?? row["label"]?.stringValue,
           !title.isEmpty {
            let status = row["status"]?.stringValue ?? row["state"]?.stringValue ?? "pending"
            return [TodoItem(
                id: row["id"]?.stringValue ?? "row-todo-\(rowId)",
                title: title,
                state: mapTodoStatus(status))]
        }
        return nil
    }

    private static func parseTodoItems(_ items: [JSONValue], idPrefix: String) -> [TodoItem]? {
        var todos: [TodoItem] = []
        for (index, item) in items.enumerated() {
            guard let dict = item.objectValue else { continue }
            // 步骤标题：content（桌面 v4）为主，title/label/text 兼容
            guard let title = dict["content"]?.stringValue
                ?? dict["title"]?.stringValue
                ?? dict["label"]?.stringValue
                ?? dict["text"]?.stringValue,
                  !title.isEmpty else { continue }
            todos.append(TodoItem(
                id: dict["id"]?.stringValue ?? "\(idPrefix)-\(index)",
                title: title,
                state: mapTodoStatus(
                    dict["status"]?.stringValue ?? dict["state"]?.stringValue ?? "pending")))
        }
        return todos.isEmpty ? nil : todos
    }

    /// 步骤状态宽容映射：completed→done；in_progress/running→now；failed/error→failed（BiuZ 扩展）
    static func mapTodoStatus(_ raw: String) -> TodoState {
        switch raw.lowercased() {
        case "completed", "done", "success": return .done
        case "in_progress", "running", "active", "now": return .now
        case "failed", "error", "cancelled", "aborted": return .failed
        default: return .todo
        }
    }

    // MARK: 命令面（v3 纠偏口径：客户端发命令、桌面代执行）

    /// sendConversationCommandV4 信封构造
    private func sendCommand(_ type: String, sessionId: String?, payload: JSONValue) async -> JSONValue? {
        guard let connection else { return nil }
        let envelope = RPCValue.jsonObject { builder in
            builder.set("commandId", UUID().uuidString)
            builder.set("clientId", "zcode-mobile")
            applySessionTarget(&builder, sessionID: sessionId)
            builder.set("type", type)
            builder.set("payload", payload)
            builder.set("issuedAt", ISO8601DateFormatter().string(from: Date()))
        }
        do {
            let ack = try await connection.call("zcode-agent", "sendConversationCommandV4", envelope)
            return ack.jsonValue
        } catch {
            return nil
        }
    }

    /// 发送消息：sendText 信封真实下发（桌面端 agent 开跑）+ 本地乐观回显。
    /// 服务端 userInput 行抵达（同文本）后经 rebuildMessages 去重，避免双气泡；
    /// 回显按「发送时已知行数」锚点插入保持时间序（服务端不回推用户行时也不乱序）；
    /// 下发失败（连接断开/边界拦截）撤销回显，如实反馈未送达。
    func send(_ text: String, in conversationID: String) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        let echoID = "local-send-\(UUID().uuidString)"
        let anchor = rows[conversationID]?.count ?? 0
        pendingLocalSends[conversationID, default: [:]][echoID] = (text: trimmed, anchor: anchor)
        let echo = ChatMessage(id: echoID, role: .user, text: trimmed, timestamp: Date())
        messages[conversationID, default: []].append(echo)
        yieldToAll(.messageAppended(conversationID: conversationID, message: echo))
        let ack = await sendCommand("sendText", sessionId: conversationID, payload: .object([
            "text": .string(trimmed),
        ]))
        if ack == nil {
            // 未送达：撤销乐观回显（在途表同步清除），下次重试不产生重影
            pendingLocalSends[conversationID]?.removeValue(forKey: echoID)
            messages[conversationID]?.removeAll { $0.id == echoID }
            yieldToAll(.messagesReplaced(
                conversationID: conversationID, messages: messages[conversationID] ?? []))
            return false
        }
        return true
    }

    /// 提问应答：解析最新 userInput/elicitation 类挂起交互 → resolveInteraction
    /// （interactionId + answer 文本）真实下发；无挂起交互则静默（无对象可应答）。
    func answerQuestion(_ reply: String, in conversationID: String, questionID: String) async {
        guard let interaction = currentPendingInteraction(conversationID, kinds: ["userInput", "elicitation", "question"]) else {
            return
        }
        guard let interactionId = Self.interactionId(of: interaction) else { return }
        await resolveInteractionRaw(
            conversationID, interactionId: interactionId, answer: .string(reply))
    }

    /// 新建会话：标题/首条指令非空时以 createSession+firstInput 一次下发（桌面端
    /// 立即开跑首条 turn，边界内允许）；为空时保持 draft 空会话 + 转正写。
    func createConversation(title: String, directory: String, executor: ExecutorKind) async -> Conversation {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        // 项 2：directory 参数承载项目层选择（屏 03 项目胶囊）→ createSession workspaceId；
        // 未绑定（空）时回退连接时装配的 workspace（既有口径不变）
        let trimmedDirectory = directory.trimmingCharacters(in: .whitespacesAndNewlines)
        var payload: [String: JSONValue] = [
            "workspaceId": .string(trimmedDirectory.isEmpty ? workspace.path : trimmedDirectory)
        ]
        if !trimmed.isEmpty {
            payload["firstInput"] = .object(["text": .string(trimmed)])
        }
        let ack = await sendCommand("createSession", sessionId: nil, payload: .object(payload))
        let sessionId = ack?["result"]?.objectValue?["sessionId"]?.stringValue
            ?? ack?["sessionId"]?.stringValue
            ?? UUID().uuidString
        if trimmed.isEmpty {
            // draft 空会话：promote 转正写 task index，重启后不丢
            await promoteDeferredDraftSession(sessionId: sessionId)
        }
        let conversation = Conversation(
            id: sessionId, title: trimmed.isEmpty ? String(localized: "新会话") : trimmed,
            summary: trimmed.isEmpty ? String(localized: "空会话 · 可在输入框发起首条任务") : String(localized: "已发送首条指令 · 桌面端执行中"),
            directory: directory, updatedAt: Date(),
            isRunning: !trimmed.isEmpty)
        yieldToAll(.conversationUpdated(conversation))
        yieldConversationsReplaced()
        return conversation
    }

    /// draft 转正（named gap：移动端新建空会话重启后丢失）：promoteDeferredDraftSession
    /// 把 v4 draft 会话写入 task index（session 类元数据写，不驱动 agent），
    /// 使其持久化并在桌面端可见。失败静默（draft 语义保留，可重建）。
    private func promoteDeferredDraftSession(sessionId: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("sessionId", sessionId)
        _ = try? await connection.call(
            "zcode-session", "promoteDeferredDraftSession", .json(.object(builder.fields)))
    }

    // MARK: 列表态变更（本地 override + zcode-task 双写）

    /// 置顶：本地 override 即时反馈 + setTaskPinned 远端写（修复重启后置顶丢失）。
    /// 远端失败回滚本地态；服务端 sessions-index 投影 pinned 时以快照兜底。
    func setPinned(_ pinned: Bool, conversationID: String) async {
        let previous = localPinnedOverrides[conversationID] ?? false
        localPinnedOverrides[conversationID] = pinned
        yieldConversationsReplaced()
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", workspace.path)
        builder.set("pinned", pinned)
        do {
            _ = try await connection.call("zcode-task", "setTaskPinned", .json(.object(builder.fields)))
        } catch {
            localPinnedOverrides[conversationID] = previous
            yieldConversationsReplaced()
        }
    }

    /// 归档/取消归档：archiveTask / unarchiveTask（取消归档此前误发 archiveTask，
    /// named gap P1-6 修复）。本地 override 即时反馈 + 归档缓存维护，失败回滚。
    func setArchived(_ archived: Bool, conversationID: String) async {
        let previous = localArchivedOverrides[conversationID] ?? false
        localArchivedOverrides[conversationID] = archived
        if archived {
            // 归档行动作即写入本地缓存（「已归档」分区即时呈现，不依赖 listArchivedTasks 回执）
            var conversation: Conversation?
            if let summary = sessions[conversationID] {
                conversation = Conversation(
                    id: summary.sessionId,
                    title: localTitleOverrides[conversationID] ?? summary.title,
                    summary: summary.lastAssistantPreview ?? String(localized: "已归档"),
                    directory: workspace.path,
                    updatedAt: summary.lastActivityAt ?? Date.distantPast)
            }
            if conversation == nil {
                conversation = localArchivedCache[conversationID]
            }
            if var cached = conversation {
                cached.isArchived = true
                localArchivedCache[conversationID] = cached
            }
        } else {
            localArchivedCache.removeValue(forKey: conversationID)
        }
        yieldConversationsReplaced()
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", workspace.path)
        do {
            if archived {
                _ = try await connection.call("zcode-task", "archiveTask", .json(.object(builder.fields)))
            } else {
                _ = try await connection.call("zcode-task", "unarchiveTask", .json(.object(builder.fields)))
            }
        } catch {
            localArchivedOverrides[conversationID] = previous
            yieldConversationsReplaced()
        }
    }

    /// 重命名（P1-5）：renameTask 双写 + 本地标题 override 即时反馈，失败回滚。
    func renameConversation(_ title: String, conversationID: String) async {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let previous = localTitleOverrides[conversationID]
        localTitleOverrides[conversationID] = trimmed
        yieldConversationsReplaced()
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", workspace.path)
        builder.set("title", trimmed)
        do {
            _ = try await connection.call("zcode-task", "renameTask", .json(.object(builder.fields)))
        } catch {
            if let previous {
                localTitleOverrides[conversationID] = previous
            } else {
                localTitleOverrides.removeValue(forKey: conversationID)
            }
            yieldConversationsReplaced()
        }
    }

    /// 标记未读（P1-5）：本地置位 + setTaskUnread unread:true（compare-and-clear 的反向）。
    func markUnread(conversationID: String) async {
        localUnreadFlags[conversationID] = true
        yieldConversationsReplaced()
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", workspace.path)
        builder.set("unread", true)
        _ = try? await connection.call("zcode-task", "setTaskUnread", .json(.object(builder.fields)))
    }

    /// 已读清零：本地清零 + setTaskUnread（compare-and-clear 防并发覆盖；
    /// 失败不回滚——未读态会在下一次快照/增量恢复，本地清零无破坏性）。
    func markRead(conversationID: String) async {
        if var summary = sessions[conversationID] {
            summary.pendingPermissionCount = 0
            summary.pendingUserInputCount = 0
            sessions[conversationID] = summary
        }
        localUnreadFlags[conversationID] = nil
        yieldConversationsReplaced()
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", workspace.path)
        builder.set("unread", false)
        _ = try? await connection.call("zcode-task", "setTaskUnread", .json(.object(builder.fields)))
    }

    /// 已归档会话（P1-6）：listArchivedTasks 只读拉取 + 本地归档动作缓存合并。
    /// 失败时以本地缓存呈现（本次会话内归档的行不丢）。
    func archivedConversations() async -> [Conversation] {
        var result: [String: Conversation] = localArchivedCache
        if let connection {
            var builder = JSONObjectBuilder()
            builder.set("workspacePath", workspace.path)
            if let result0 = try? await connection.call(
                "zcode-task", "listArchivedTasks", .json(.object(builder.fields))),
               let items = result0.jsonValue?["items"]?.arrayValue ?? result0.jsonValue?.arrayValue {
                for item in items {
                    guard let taskId = item.objectValue?["taskId"]?.stringValue ?? item.objectValue?["sessionId"]?.stringValue else { continue }
                    let summary = SessionSummary.parse(item)
                    let conversation = Conversation(
                        id: taskId,
                        title: summary?.title ?? item.objectValue?["title"]?.stringValue ?? "已归档会话",
                        summary: summary?.lastAssistantPreview ?? item.objectValue?["lastAssistantPreview"]?.stringValue ?? String(localized: "已归档"),
                        directory: workspace.path,
                        updatedAt: summary?.lastActivityAt ?? Date.distantPast,
                        isArchived: true)
                    result[taskId] = conversation
                }
            }
        }
        return result.values.sorted { $0.updatedAt > $1.updatedAt }
    }

    // MARK: 交互辅助

    func currentPendingInteraction(_ conversationID: String, kinds: [String]) -> JSONValue? {
        guard let array = pendingInteractions[conversationID]?.arrayValue else { return nil }
        return array.first { item in
            guard let kind = item.objectValue?["kind"]?.stringValue else { return false }
            return kinds.contains(kind)
        }
    }

    /// pendingInteractions 数组 → 移动端投影（ChatView 审批卡数据源）
    func pendingInteractionList(in conversationID: String) async -> [RemotePendingInteraction] {
        guard let array = pendingInteractions[conversationID]?.arrayValue else { return [] }
        return array.compactMap(Self.parseInteraction)
    }

    /// 交互投影解析（宽容：id/interactionId 二选一；命令/路径/影响/title/options 就近取）
    nonisolated static func parseInteraction(_ json: JSONValue) -> RemotePendingInteraction? {
        guard let dict = json.objectValue else { return nil }
        let payload = dict["payload"]?.objectValue ?? [:]
        guard let id = Self.interactionId(of: json),
              let kind = dict["kind"]?.stringValue ?? payload["kind"]?.stringValue else { return nil }
        var interaction = RemotePendingInteraction(id: id, kind: kind)
        interaction.title = dict["title"]?.stringValue
            ?? dict["toolName"]?.stringValue
            ?? payload["title"]?.stringValue
            ?? payload["toolName"]?.stringValue
        interaction.command = dict["command"]?.stringValue
            ?? dict["inputText"]?.stringValue
            ?? payload["command"]?.stringValue
            ?? payload["inputText"]?.stringValue
            ?? payload["text"]?.stringValue
        interaction.path = dict["path"]?.stringValue
            ?? dict["workspacePath"]?.stringValue
            ?? payload["path"]?.stringValue
            ?? payload["workspacePath"]?.stringValue
        interaction.impact = dict["impact"]?.stringValue
            ?? dict["description"]?.stringValue
            ?? payload["impact"]?.stringValue
            ?? payload["description"]?.stringValue
        interaction.options = (dict["options"]?.arrayValue ?? payload["options"]?.arrayValue ?? [])
            .compactMap { $0.stringValue ?? $0.objectValue?["label"]?.stringValue }
        // G-017：plan_approval 计划文本（payload.renderContext.plan / 顶层 renderContext / plan 字段）
        let renderContext = payload["renderContext"]?.objectValue
            ?? dict["renderContext"]?.objectValue
        if renderContext?["kind"]?.stringValue == "plan_approval",
           let plan = renderContext?["plan"]?.stringValue, !plan.isEmpty {
            interaction.planText = plan
        } else if let plan = payload["plan"]?.stringValue ?? dict["plan"]?.stringValue, !plan.isEmpty {
            interaction.planText = plan
        }
        return interaction
    }

    /// 交互 id 宽容解析（id / interactionId / payload.interactionId）
    nonisolated static func interactionId(of json: JSONValue) -> String? {
        guard let dict = json.objectValue else { return nil }
        return dict["id"]?.stringValue
            ?? dict["interactionId"]?.stringValue
            ?? dict["payload"]?.objectValue?["interactionId"]?.stringValue
    }

    /// 交互应答信封下发：resolveInteraction（interactionId + answer）。answer 形态
    /// 由调用方构造（权限={approved,scope}；提问=文本）；payload 顶层同步冗余
    /// approved/scope/text 字段以兼容不同服务端解析口径。
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async {
        var payload: [String: JSONValue] = [
            "interactionId": .string(interactionId),
            "answer": answer,
        ]
        switch answer {
        case .object(let dict):
            for (key, value) in dict where key != "interactionId" {
                payload[key] = value
            }
        case .string(let text):
            payload["text"] = .string(text)
        default:
            break
        }
        _ = await sendCommand("resolveInteraction", sessionId: conversationID, payload: .object(payload))
    }

    // MARK: 会话全文检索（G-018：listTaskList searchQuery 透传 + snippets 摘要）

    /// 桌面基准（taskIndexRepo.ts:317-330）：searchable_text 全文匹配，命中给 snippets[]。
    /// 会话与任务在桌面同源（zcode-task 索引），故会话侧检索复用 listTaskList searchQuery。
    /// 失败返回空数组（调用方回退本地过滤，不崩）。
    func searchSessions(_ query: String) async -> [Conversation] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let connection else { return [] }
        var builder = JSONObjectBuilder()
        builder.set("workspacePath", workspace.path)
        builder.set("searchQuery", trimmed)
        guard let result = try? await connection.call(
            "zcode-task", "listTaskList", .json(.object(builder.fields))),
            let items = result.jsonValue?["items"]?.arrayValue ?? result.jsonValue?.arrayValue else {
            return []
        }
        return items.compactMap { item in
            guard let d = item.objectValue,
                  let id = d["taskId"]?.stringValue ?? d["sessionId"]?.stringValue else { return nil }
            var conversation = Conversation(
                id: id,
                title: d["title"]?.stringValue ?? String(localized: "未命名会话"),
                summary: d["lastAssistantPreview"]?.stringValue ?? "",
                directory: d["workspacePath"]?.stringValue ?? workspace.path,
                updatedAt: (d["lastActivityAt"]?.intValue).map {
                    Date(timeIntervalSince1970: Double($0) / 1000)
                } ?? .distantPast)
            // snippets 命中摘要（验收②）：join 展示首条命中片段
            if let snippets = d["snippets"]?.arrayValue?.compactMap(\.stringValue), !snippets.isEmpty {
                conversation.summary = snippets.first ?? conversation.summary
                conversation.todoSummary = String(localized: "全文命中")
            }
            return conversation
        }
    }

    // MARK: 桌面 workflow 运行进度（要求 5 / G-008 · 只读；不新增任何发送命令）
    //
    // 数据面（桌面开源 v3.14.3 权威 schema）：
    // 通路 B 主源——conversation state.workflowRuns（workflowRunsStateSchema {revision, runs[]}）：
    //   冷快照必带、state.updated 键级替换、专属增量 op 仅 workflowRun.updated/removed 两个
    //   （header 键整键替换 + actors/nodes 条目按 (siteId, ordinal) 整条替换，绝无字段级深合并）。
    // 旧兼容——workflowRun 单数键（上轮要求 5 实现）与 conversationWorkflowRunsV4 只读兜底。
    // resumable/truncated 等 CLI 算好字段只透传展示，绝不自推导（桌面基准纪律）。

    func workflowRun(in conversationID: String) async -> WorkflowRunSummary? {
        // ① 通路 B 表（live run 优先，其后按表序）
        if let table = workflowRunTables[conversationID], !table.runs.isEmpty {
            let parsed = table.runs.compactMap(Self.parseWorkflowRun)
            return parsed.first { $0.isLive } ?? parsed.first
        }
        // ② 单数键旧兼容
        if let cached = workflowRunStates[conversationID] {
            return Self.parseWorkflowRun(cached)
        }
        // ③ 只读 RPC 兜底（一次，失败不重复）
        guard let connection, !workflowRunFetched.contains(conversationID) else { return nil }
        workflowRunFetched.insert(conversationID)
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: conversationID)
        builder.set("workspacePath", workspace.path)
        guard let result = try? await connection.call(
            "zcode-agent", "conversationWorkflowRunsV4", .json(.object(builder.fields))) else {
            return nil
        }
        // 回执宽容：顶层 {runs:[…]} / {result:{runs|workflowRun}} / 单 run 对象
        let value = result.jsonValue
        let runs = value?["runs"]?.arrayValue
            ?? value?["result"]?.objectValue?["runs"]?.arrayValue
            ?? value?["result"]?.objectValue?["workflowRuns"]?.objectValue?["runs"]?.arrayValue
        let run = runs?.last ?? value?["result"]?.objectValue?["workflowRun"] ?? value?["workflowRun"]
        // G-019：历史 run（无实时 nodes/actors）→ 走 RunEvents 分页重建（通路 C）
        if let run, let parsed = Self.parseWorkflowRun(run) {
            workflowRunStates[conversationID] = run
            return parsed
        }
        if let runId = run?.objectValue?["runId"]?.stringValue
            ?? run?.objectValue?["id"]?.stringValue {
            if let rebuilt = await rebuildWorkflowRunFromEvents(conversationID, runId: runId) {
                return rebuilt
            }
        }
        return nil
    }

    /// workflowRuns 状态键 → 表（整键替换；无 runs 数组形态忽略）
    private func applyWorkflowRunsState(_ conversationID: String, _ value: JSONValue) {
        guard let dict = value.objectValue,
              let runs = dict["runs"]?.arrayValue else { return }
        workflowRunTables[conversationID] = (
            revision: dict["revision"]?.intValue ?? 0,
            runs: runs
        )
    }

    /// workflowRun.updated（delta.ts:122）：header 键整键替换（run patch + cleared 清除）+
    /// actors/nodes 条目按 (siteId, ordinal) 整条 upsert/移除。runId 不在表中且带 run 键 → 新建条目。
    private func applyWorkflowRunUpdated(_ conversationID: String, _ dict: [String: JSONValue]) {
        guard let runId = dict["runId"]?.stringValue else { return }
        var table = workflowRunTables[conversationID] ?? (revision: 0, runs: [])
        if let revision = dict["revision"]?.intValue { table.revision = revision }
        var index = table.runs.firstIndex {
            $0.objectValue?["runId"]?.stringValue == runId
        }
        // ① 新建条目（run 完整对象在场且表中无此 run）
        if index == nil, let fullRun = dict["run"]?.objectValue {
            table.runs.append(.object(fullRun))
            index = table.runs.count - 1
        }
        guard var entry = index.flatMap({ table.runs[$0].objectValue }) else { return }

        // ② header 键整键替换：run patch 逐键覆盖 + cleared 键删除（无深合并）
        if let runPatch = dict["run"]?.objectValue {
            for (key, value) in runPatch { entry[key] = value }
        }
        for key in dict["cleared"]?.arrayValue?.compactMap(\.stringValue) ?? [] {
            entry.removeValue(forKey: key)
        }
        // ③ actors/nodes 条目按 (siteId, ordinal) 整条替换/移除（workflow-runs-delta.ts 规则）
        func upsertEntries(_ key: String, incoming: [JSONValue]?, removed: [JSONValue]?) {
            var list = entry[key]?.arrayValue ?? []
            for ref in removed ?? [] {
                guard let d = ref.objectValue,
                      let siteId = d["siteId"]?.stringValue,
                      let ordinal = d["ordinal"]?.intValue else { continue }
                list.removeAll {
                    $0.objectValue?["siteId"]?.stringValue == siteId
                        && $0.objectValue?["ordinal"]?.intValue == ordinal
                }
            }
            for item in incoming ?? [] {
                guard let d = item.objectValue,
                      let siteId = d["siteId"]?.stringValue,
                      let ordinal = d["ordinal"]?.intValue else { continue }
                if let at = list.firstIndex(where: {
                    $0.objectValue?["siteId"]?.stringValue == siteId
                        && $0.objectValue?["ordinal"]?.intValue == ordinal
                }) {
                    list[at] = item   // 条目整条替换
                } else {
                    list.append(item)
                }
            }
            entry[key] = .array(list)
        }
        upsertEntries("actors", incoming: dict["actors"]?.arrayValue,
                      removed: dict["removedActors"]?.arrayValue)
        upsertEntries("nodes", incoming: dict["nodes"]?.arrayValue,
                      removed: dict["removedNodes"]?.arrayValue)

        if let index {
            table.runs[index] = .object(entry)
            workflowRunTables[conversationID] = table
        }
    }

    /// 从快照/op 信封提取 run 负载（多键宽容；无 run 形态返回 nil）
    nonisolated static func extractWorkflowRun(_ dict: [String: JSONValue]) -> JSONValue? {
        let candidate = dict["workflowRun"]
            ?? dict["run"]
            ?? dict["payload"]
            ?? dict["delta"]
        if let candidate { return candidate }
        // 信封自身即 run 对象形态（含 runId/status 等特征键）
        let hasRunShape = dict["runId"] != nil || dict["workflowRunId"] != nil
            || (dict["status"] != nil && dict["nodes"] != nil)
        return hasRunShape ? JSONValue.object(dict) : nil
    }

    /// G-007 通路 A：workflowActivity → 会话行迷你轨道投影（sessionWorkflowActivitySchema：
    /// {runs[≤4]}，run 摘要 {runId, name?, status 五态, phases[{name,status 四态,alongside?}],
    /// currentPhase?, agentsWorking}；宽容解析，无有效 run 返回 nil）
    nonisolated static func parseWorkflowActivity(_ json: JSONValue?) -> WorkflowActivitySummary? {
        guard let runs = json?.objectValue?["runs"]?.arrayValue, !runs.isEmpty else { return nil }
        let parsed: [SessionWorkflowRunSummary] = runs.compactMap { run in
            guard let dict = run.objectValue,
                  let runId = dict["runId"]?.stringValue ?? dict["id"]?.stringValue else { return nil }
            let phases: [SessionWorkflowPhase] = (dict["phases"]?.arrayValue ?? []).compactMap { phase in
                guard let d = phase.objectValue,
                      let name = d["name"]?.stringValue, !name.isEmpty else { return nil }
                return SessionWorkflowPhase(
                    name: name,
                    status: WorkflowStepStatus.map(d["status"]?.stringValue),
                    alongside: d["alongside"]?.arrayValue?.compactMap(\.intValue) ?? [])
            }
            return SessionWorkflowRunSummary(
                id: runId,
                name: dict["name"]?.stringValue,
                rawStatus: dict["status"]?.stringValue ?? "pending",
                phases: phases,
                currentPhase: dict["currentPhase"]?.stringValue,
                agentsWorking: dict["agentsWorking"]?.intValue ?? 0)
        }
        return parsed.isEmpty ? nil : WorkflowActivitySummary(runs: parsed)
    }

    /// run 负载 → 只读投影（G-008 增强：五态原词/actors 子代理实例/阶段链按声明表推导/
    /// 容量计数/concurrency/truncated/resumable 透传；宽容解析，无阶段且无节点返回 nil）
    nonisolated static func parseWorkflowRun(_ json: JSONValue) -> WorkflowRunSummary? {
        guard let dict = json.objectValue else { return nil }
        let id = dict["runId"]?.stringValue
            ?? dict["workflowRunId"]?.stringValue
            ?? dict["id"]?.stringValue ?? "workflow-run"
        let name = dict["name"]?.stringValue
            ?? dict["workflowName"]?.stringValue
            ?? dict["title"]?.stringValue
            ?? dict["displayName"]?.stringValue
            ?? String(localized: "工作流")
        let rawStatus = dict["status"]?.stringValue ?? "pending"
        let currentPhase = dict["currentPhase"]?.stringValue

        // 阶段链（阶段三表取一）：① 声明表 phases[{name,rounds}] → phaseNames[]；
        // ② 退化 = 已进入站（节点 phaseName 首现序）+ 当前站
        var stationNames: [String] = (dict["phases"]?.arrayValue ?? []).compactMap {
            $0.objectValue?["name"]?.stringValue
        }
        if stationNames.isEmpty {
            stationNames = dict["phaseNames"]?.arrayValue?.compactMap(\.stringValue) ?? []
        }
        let rawNodes = dict["nodes"]?.arrayValue ?? []
        if stationNames.isEmpty {
            var seen: [String] = []
            for node in rawNodes {
                if let phaseName = node.objectValue?["phaseName"]?.stringValue, !seen.contains(phaseName) {
                    seen.append(phaseName)
                }
            }
            stationNames = seen
            if let currentPhase, !stationNames.contains(currentPhase) {
                stationNames.append(currentPhase)
            }
        }

        // 每站状态：节点证据优先（outcome failed/cancelled → failed；七相位中
        // dispatched/executing/waiting/repairing/nudged → running；全部 settled ok → done），
        // 无节点时控制流已进入（currentPhase）→ running，否则 pending
        var nodes: [WorkflowNodeSummary] = []
        for (index, station) in stationNames.enumerated() {
            let stationNodes = rawNodes.filter {
                $0.objectValue?["phaseName"]?.stringValue == station
            }
            var status: WorkflowStepStatus = .pending
            if !stationNodes.isEmpty {
                if stationNodes.contains(where: {
                    let outcome = $0.objectValue?["outcome"]?.stringValue
                    return outcome == "failed" || outcome == "cancelled"
                }) {
                    status = .failed
                } else if stationNodes.contains(where: {
                    guard let phase = $0.objectValue?["phase"]?.stringValue else { return false }
                    return ["dispatched", "executing", "waiting", "repairing", "nudged"].contains(phase)
                }) {
                    status = .running
                } else if stationNodes.allSatisfy({
                    $0.objectValue?["phase"]?.stringValue == "settled"
                        && $0.objectValue?["outcome"]?.stringValue == "ok"
                }) {
                    status = .done
                }
            } else if currentPhase == station {
                status = .running
            }
            nodes.append(WorkflowNodeSummary(
                id: "station-\(index)-\(station)",
                label: station,
                status: status,
                isSubagent: false,
                summary: nil))
        }

        // 子代理实例（actors[]：waiting|running|completed + phaseName）
        let actors: [WorkflowActorSummary] = (dict["actors"]?.arrayValue ?? []).compactMap { actor in
            guard let d = actor.objectValue,
                  let siteId = d["siteId"]?.stringValue,
                  let ordinal = d["ordinal"]?.intValue else { return nil }
            return WorkflowActorSummary(
                id: "\(siteId)#\(ordinal)",
                name: d["name"]?.stringValue,
                rawStatus: d["status"]?.stringValue ?? "waiting",
                phaseName: d["phaseName"]?.stringValue,
                sessionId: d["sessionId"]?.stringValue)
        }

        guard !nodes.isEmpty || !actors.isEmpty else { return nil }
        return WorkflowRunSummary(
            id: id,
            name: name,
            rawStatus: rawStatus,
            stopReason: dict["stopReason"]?.stringValue,
            resumable: dict["resumable"]?.boolValue ?? false,
            truncated: dict["truncated"]?.boolValue ?? false,
            nodes: nodes,
            actors: actors,
            artifactsCount: dict["artifacts"]?.arrayValue?.count ?? 0,
            pendingQuestionsCount: dict["pendingQuestions"]?.arrayValue?.count ?? 0,
            concurrency: dict["concurrency"]?.intValue
                ?? dict["concurrency"]?.objectValue?["active"]?.intValue,
            concurrencyCeiling: dict["concurrencyCeiling"]?.intValue)
    }

    // MARK: P2 批次：retryTurn / fork / 分组写面 / RunEvents 重建 / 子代理转录
    //
    // 边界口径（与前批一致）：均为「客户端发命令、桌面代执行」或索引元数据写；
    // 文件直写类维持 ReadOnlyGate 拦截。

    /// G-015：失败 turn 重试——携行元数据精确游标 {rowId, entityId} 下发 retryTurn
    /// （sendConversationCommandV4 execution 词表成员，ReadOnlyGate command 类放行）。
    /// entityId 缺失时调用方不渲染入口；此实现再兜一层（不虚构「已重试」）。
    func retryTurn(_ conversationID: String, rowId: Int, entityId: String?) async {
        guard let entityId, !entityId.isEmpty else { return }
        var target: [String: JSONValue] = ["rowId": .int(rowId)]
        target["entityId"] = .string(entityId)
        _ = await sendCommand(
            "retryTurn",
            sessionId: conversationID,
            payload: .object(["target": .object(target)]))
    }

    /// G-018：会话派生——forkAssistant（session 类放行分支）；回执宽容取新会话 id
    /// （result.sessionId / sessionId / result.id），成功后经既有订阅流自然刷新列表
    /// （桌面 sessions-index upserted 携带 parentSessionId 派生关系）。
    func forkConversation(_ conversationID: String) async -> String? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: conversationID)
        guard let result = try? await connection.call(
            "zcode-agent", "forkAssistant", .json(.object(builder.fields))) else {
            return nil
        }
        let value = result.jsonValue
        return value?["result"]?.objectValue?["sessionId"]?.stringValue
            ?? value?["sessionId"]?.stringValue
            ?? value?["result"]?.objectValue?["id"]?.stringValue
    }

    // MARK: 会话分组管理写面（G-017；均为索引元数据写，桌面代执行合法）。
    // 参数名宽容：桌面组 schema 未逐字段取证，回执按宽松键解析；失败静默返回 nil。

    func createTaskGroup(named name: String, color: String?) async -> String? {
        guard let connection else { return nil }
        var builder = JSONObjectBuilder()
        builder.set("name", name)
        if let color { builder.set("color", color) }
        guard let result = try? await connection.call(
            "zcode-task", "createTaskGroup", .json(.object(builder.fields))) else {
            return nil
        }
        return result.jsonValue?["groupId"]?.stringValue
            ?? result.jsonValue?["result"]?.objectValue?["groupId"]?.stringValue
            ?? result.jsonValue?["id"]?.stringValue
    }

    func renameTaskGroup(_ groupID: String, to name: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("groupId", groupID)
        builder.set("name", name)
        _ = try? await connection.call(
            "zcode-task", "renameTaskGroup", .json(.object(builder.fields)))
    }

    func updateTaskGroupColor(_ groupID: String, color: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("groupId", groupID)
        builder.set("color", color)
        _ = try? await connection.call(
            "zcode-task", "updateTaskGroupColor", .json(.object(builder.fields)))
    }

    func deleteTaskGroup(_ groupID: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("groupId", groupID)
        _ = try? await connection.call(
            "zcode-task", "deleteTaskGroup", .json(.object(builder.fields)))
    }

    /// 组内顺序 / 会话入组：applyGroupedTaskViewOrder（order 条目 {taskId, groupId}，
    /// groupID nil = 移出分组）。组结构回显依赖桌面组读面（方法名未在矩阵/桌面基准取证），
    /// 移动端暂以桌面同步为准。
    func applyGroupedTaskViewOrder(groupID: String?, order: [(taskID: String, groupID: String?)]) async {
        guard let connection, !order.isEmpty else { return }
        var builder = JSONObjectBuilder()
        if let groupID { builder.set("groupId", groupID) }
        builder.set("order", .array(order.map { entry in
            var item: [String: JSONValue] = ["taskId": .string(entry.taskID)]
            item["groupId"] = entry.groupID.map { .string($0) } ?? .null
            return .object(item)
        }))
        _ = try? await connection.call(
            "zcode-task", "applyGroupedTaskViewOrder", .json(.object(builder.fields)))
    }

    /// G-019 通路 C：历史 run 事件分页重建（实时表为空时兜底）——
    /// conversationWorkflowRunEventsV4（journal-backed）宽容拉 ≤2 页，按 type 名重建
    /// 阶段链（phase-entered/phase-* 事件的 phase 名首现序）与 actor 概要，事件计数入面板。
    func rebuildWorkflowRunFromEvents(_ conversationID: String, runId: String) async -> WorkflowRunSummary? {
        guard let connection else { return nil }
        var events: [JSONValue] = []
        var cursor: JSONValue = .null
        for _ in 0..<2 {
            var builder = JSONObjectBuilder()
            builder.set("runId", runId)
            builder.set("limit", 200)
            if cursor != .null {
                builder.set("cursor", cursor)
            }
            guard let result = try? await connection.call(
                "zcode-agent", "conversationWorkflowRunEventsV4", .json(.object(builder.fields))) else {
                break
            }
            let page = result.jsonValue?["events"]?.arrayValue
                ?? result.jsonValue?["result"]?.objectValue?["events"]?.arrayValue
                ?? []
            events.append(contentsOf: page)
            // 分页游标宽容取键；无游标/空页即停
            let next = result.jsonValue?["nextCursor"]
                ?? result.jsonValue?["result"]?.objectValue?["nextCursor"]
                ?? .null
            if next == .null || page.isEmpty { break }
            cursor = next
        }
        guard !events.isEmpty else { return nil }

        var stationNames: [String] = []
        var actors: [WorkflowActorSummary] = []
        var rawStatus: String?
        var stopReason: String?
        for event in events {
            guard let d = event.objectValue,
                  let type = d["type"]?.stringValue ?? d["kind"]?.stringValue else { continue }
            let payload = d["payload"]?.objectValue ?? d["data"]?.objectValue ?? [:]
            switch true {
            case type.contains("phase"):
                if let name = payload["phase"]?.stringValue ?? payload["phaseName"]?.stringValue,
                   !stationNames.contains(name) {
                    stationNames.append(name)
                }
            case type.contains("actor"):
                if let siteId = payload["siteId"]?.stringValue,
                   let ordinal = payload["ordinal"]?.intValue,
                   !actors.contains(where: { $0.id == "\(siteId)#\(ordinal)" }) {
                    actors.append(WorkflowActorSummary(
                        id: "\(siteId)#\(ordinal)",
                        name: payload["name"]?.stringValue,
                        rawStatus: payload["status"]?.stringValue ?? "waiting",
                        phaseName: payload["phaseName"]?.stringValue ?? payload["phase"]?.stringValue,
                        sessionId: payload["sessionId"]?.stringValue))
                }
            case type.contains("run-settled"), type.contains("settled"):
                rawStatus = payload["status"]?.stringValue ?? rawStatus
                stopReason = payload["stopReason"]?.stringValue ?? stopReason
            default:
                break
            }
        }
        guard !stationNames.isEmpty || !actors.isEmpty else { return nil }
        let nodes = stationNames.enumerated().map { index, name in
            WorkflowNodeSummary(id: "event-station-\(index)-\(name)", label: name, status: .done)
        }
        return WorkflowRunSummary(
            id: runId,
            name: String(localized: "工作流"),
            rawStatus: rawStatus ?? "completed",
            stopReason: stopReason,
            resumable: false,
            truncated: true,
            nodes: nodes,
            actors: actors,
            artifactsCount: 0,
            pendingQuestionsCount: 0,
            concurrency: nil,
            concurrencyCeiling: nil)
    }

    /// G-021：子代理只读转录——按 actor.sessionId 拉一页 rowsRange（无新协议，纯只读），
    /// 轻量行映射（userInput/assistantText/reasoning/toolCall 概要文本）。
    func actorTranscript(sessionId: String, limit: Int) async -> [ChatMessage] {
        guard let connection else { return [] }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: sessionId)
        builder.set("limit", max(1, min(limit, 200)))
        guard let result = try? await connection.call(
            "zcode-agent", "conversationRowsRangeV4", .json(.object(builder.fields))),
            let rows = result.jsonValue?["rows"]?.arrayValue ?? result.jsonValue?.arrayValue else {
            return []
        }
        var messages: [ChatMessage] = []
        for (index, row) in rows.enumerated() {
            guard let d = row.objectValue else { continue }
            let rowId = d["rowId"]?.intValue ?? index
            let kind = d["kind"]?.stringValue ?? ""
            let text: String?
            switch kind {
            case "userInput":
                text = d["text"]?.stringValue.map { String(localized: "用户：\($0)") }
            case "assistantText":
                text = d["text"]?.stringValue
            case "reasoning":
                text = d["text"]?.stringValue.map { String(localized: "（思考）\($0)") }
            case "toolCall":
                text = String(localized: "工具 \(d["toolName"]?.stringValue ?? "")")
            default:
                text = nil
            }
            if let text, !text.isEmpty {
                messages.append(ChatMessage(
                    id: "transcript-\(sessionId)-\(rowId)",
                    role: kind == "userInput" ? .user : .agent,
                    text: text,
                    timestamp: Date()))
            }
        }
        return messages
    }

    // MARK: 会话上下文用量（G-021：state.runtime.contextUsage 只读投影）
    //
    // 桌面 schema（zcode-protocol-legacy-types.ts:492-500）：{used, size, cost?, cache?, breakdown?}；
    // v4 conversation state 经 state.updated patch 合并在 snapshotState，宽容双路径读取
    // （state.runtime.contextUsage 优先，退化 state.contextUsage）。nil = 桌面未回报 → UI 不渲染。

    func sessionContextUsage(in conversationID: String) async -> ContextUsageInfo? {
        guard let dict = snapshotState[conversationID]?.objectValue else { return nil }
        let usage = dict["runtime"]?.objectValue?["contextUsage"]?.objectValue
            ?? dict["contextUsage"]?.objectValue
        guard let used = usage?["used"]?.intValue,
              let size = usage?["size"]?.intValue, size > 0 else { return nil }
        return ContextUsageInfo(used: used, size: size)
    }

    // MARK: 附件预览读（G-014：attachmentReadV4 分块聚合）

    /// 桌面 schema（zcode-protocol-v4/transport.ts:955-1024）：
    /// params {sessionId, ref, target?, attachmentIndex?, offset, limit≤chunkMax}，
    /// result {dataBase64, mediaType(image/*|video/*|application/pdf), totalBytes, nextOffset?}。
    /// 分块循环聚合，4MB 上限保护；失败返回 nil（UI 不渲染占位死块）。
    func attachmentPreview(sessionID: String, ref: String) async -> AttachmentPreview? {
        guard let connection else { return nil }
        var chunks: [String] = []
        var mediaType: String?
        var totalBytes = 0
        var offset = 0
        let chunkLimit = 512 * 1024
        for _ in 0..<8 { // 上限 8 块 ≈ 4MB
            var builder = JSONObjectBuilder()
            applySessionTarget(&builder, sessionID: sessionID)
            builder.set("ref", ref)
            builder.set("offset", offset)
            builder.set("limit", chunkLimit)
            do {
                let result = try await connection.call("zcode-agent", "attachmentReadV4", .json(.object(builder.fields)))
                guard let dict = result.jsonValue?.objectValue,
                      let base64 = dict["dataBase64"]?.stringValue else { return nil }
                chunks.append(base64)
                if mediaType == nil { mediaType = dict["mediaType"]?.stringValue }
                totalBytes = dict["totalBytes"]?.intValue ?? totalBytes
                guard let next = dict["nextOffset"]?.intValue else { break }
                offset = next
            } catch {
                return nil
            }
        }
        guard let mediaType else { return nil }
        let joined = chunks.joined().replacingOccurrences(of: "\n", with: "")
        guard let data = Data(base64Encoded: joined) else { return nil }
        return AttachmentPreview(ref: ref, data: data, mediaType: mediaType, totalBytes: totalBytes)
    }

    // MARK: workspace-config 只读投影（ChatView chips 数据源）

    func workspaceConfig() async -> WorkspaceConfigInfo? {
        await ensureWorkspaceConfigHandler()
        if workspaceConfigState.options.isEmpty, workspaceConfigState.slashCommands.isEmpty {
            return nil
        }
        return workspaceConfigState
    }

    /// 注册 workspace-config 帧处理器（connection 侧已连接即订阅，handler 晚注册由重放缓存兜底）
    private func ensureWorkspaceConfigHandler() async {
        guard !workspaceConfigHandlerRegistered else { return }
        workspaceConfigHandlerRegistered = true
        let topic = "workspace-config/\(workspace.path)"
        await connection?.setFrameHandler(topic: topic) { [weak self] frame in
            Task { await self?.handleWorkspaceConfigFrame(frame) }
        }
    }

    /// 快照整体替换 / delta 唯一 op（config.updated）整体替换（conflated 最新态纪律，绝不深合并）
    func handleWorkspaceConfigFrame(_ frame: V4TopicFrame) {
        if let config = frame.snapshot?.objectValue?["config"] {
            applyWorkspaceConfig(config)
        }
        for delta in frame.deltas {
            guard let dict = delta.objectValue,
                  dict["op"]?.stringValue == "config.updated",
                  let config = dict["config"] else { continue }
            applyWorkspaceConfig(config)
        }
    }

    private func applyWorkspaceConfig(_ config: JSONValue) {
        var info = WorkspaceConfigInfo()
        for option in config["configOptions"]?.arrayValue ?? [] {
            guard let dict = option.objectValue,
                  let id = dict["id"]?.stringValue else { continue }
            let current: String
            switch dict["currentValue"] {
            case .string(let value): current = value
            case .bool(let value): current = value ? "开" : "关"
            default: current = "--"
            }
            info.options.append(WorkspaceConfigInfo.Option(
                id: id,
                name: dict["name"]?.stringValue ?? id,
                currentValue: current,
                values: (dict["options"]?.arrayValue ?? []).compactMap {
                    $0.objectValue?["name"]?.stringValue ?? $0.objectValue?["value"]?.stringValue
                }))
        }
        for command in config["slashCommands"]?.arrayValue ?? [] {
            guard let dict = command.objectValue,
                  let name = dict["name"]?.stringValue else { continue }
            info.slashCommands.append(WorkspaceConfigInfo.SlashCommand(
                name: name,
                description: dict["description"]?.stringValue ?? ""))
        }
        workspaceConfigState = info
    }

    // MARK: model-selection 只读视图（桌面端模型/思考档展示，不调 set*）

    func modelSelectionView() async -> ModelSelectionInfo? {
        if let modelSelectionCache { return modelSelectionCache }
        guard let connection else { return nil }
        // getView 入参 {selection: null}（不带当前选择 → 返回缺省视图 + preferredSelection）
        var builder = JSONObjectBuilder()
        builder.set("selection", JSONValue.null)
        do {
            let result = try await connection.call(
                "model-selection", "getView", .json(.object(builder.fields)))
            guard let view = result.jsonValue?.objectValue else { return nil }
            let info = Self.parseModelSelectionView(view)
            modelSelectionCache = info
            yieldModelSelection()
            return info
        } catch {
            return nil
        }
    }

    func observeModelSelection() -> AsyncStream<ModelSelectionInfo?> {
        AsyncStream { continuation in
            let key = UUID()
            modelSelectionContinuations[key] = continuation
            continuation.yield(modelSelectionCache)
            if !modelSelectionSubscribed {
                modelSelectionSubscribed = true
                Task { await ensureModelSelectionSubscribed() }
            }
            continuation.onTermination = { _ in
                Task { await self.removeModelSelectionContinuation(key) }
            }
        }
    }

    private func removeModelSelectionContinuation(_ key: UUID) {
        modelSelectionContinuations.removeValue(forKey: key)
    }

    private func ensureModelSelectionSubscribed() async {
        guard let connection, modelSelectionSubscription == nil else { return }
        // onDidChange：固定事件（无参），收到即失效缓存重拉
        modelSelectionSubscription = await connection.listen(
            "model-selection", "onDidChange", .undefined) { [weak self] _ in
            guard let self else { return }
            Task { await self.refreshModelSelection() }
        }
    }

    private func refreshModelSelection() async {
        modelSelectionCache = nil
        _ = await modelSelectionView()
    }

    private func yieldModelSelection() {
        for continuation in modelSelectionContinuations.values {
            continuation.yield(modelSelectionCache)
        }
    }

    /// getView 回执 → ModelSelectionInfo（宽容解析：providers[].models 兼容字符串/对象形态）
    nonisolated static func parseModelSelectionView(_ view: [String: JSONValue]) -> ModelSelectionInfo {
        var info = ModelSelectionInfo()
        var models: [String] = []
        var thoughtLevels: [String] = []
        for provider in view["providers"]?.arrayValue ?? [] {
            guard let providerDict = provider.objectValue else { continue }
            for model in providerDict["models"]?.arrayValue ?? [] {
                if let name = model.stringValue {
                    models.append(name)
                    continue
                }
                guard let modelDict = model.objectValue else { continue }
                let label = modelDict["label"]?.stringValue
                    ?? modelDict["name"]?.stringValue
                    ?? modelDict["modelId"]?.stringValue
                    ?? modelDict["id"]?.stringValue
                if let label { models.append(label) }
                for level in modelDict["modelThoughtLevels"]?.arrayValue ?? [] {
                    if let levelName = level.stringValue, !thoughtLevels.contains(levelName) {
                        thoughtLevels.append(levelName)
                    }
                }
            }
        }
        info.models = models
        info.thoughtLevels = thoughtLevels
        // 当前绑定优先 preferredSelection（{providerId, modelId, options:{reasoningLevel}}），
        // 退化 effective.selection 同构
        let selection = view["preferredSelection"]?.objectValue
            ?? view["effective"]?.objectValue?["selection"]?.objectValue
        info.activeModel = selection?["modelId"]?.stringValue
        info.activeThoughtLevel = selection?["options"]?.objectValue?["reasoningLevel"]?.stringValue
        return info
    }
}

private extension RemoteConversationStore.SessionSummary {
    /// lastActivityAt 双形态兼容：真实桌面推毫秒时间戳（中继快照实测 1791040344222），
    /// 替身/早期形态为 ISO8601 字符串——两种都解析
    static func parseLastActivityAt(_ dict: [String: JSONValue]) -> Date? {
        if let ms = dict["lastActivityAt"]?.intValue, ms > 0 {
            return Date(timeIntervalSince1970: Double(ms) / 1000)
        }
        if let iso = dict["lastActivityAt"]?.stringValue {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: iso) { return date }
            return ISO8601DateFormatter().date(from: iso)
        }
        return nil
    }

    static func parse(_ json: JSONValue) -> RemoteConversationStore.SessionSummary? {
        guard let dict = json.objectValue,
              let sessionId = dict["sessionId"]?.stringValue else { return nil }
        var summary = RemoteConversationStore.SessionSummary(
            sessionId: sessionId,
            title: dict["title"]?.stringValue ?? String(localized: "未命名会话"),
            phase: dict["phase"]?.stringValue ?? "completedSuccess",
            lastActivityAt: parseLastActivityAt(dict),
            lastAssistantPreview: dict["lastAssistantPreview"]?.stringValue)
        if let pending = dict["pendingInteractionSummary"]?.objectValue {
            summary.pendingPermissionCount = pending["permissionCount"]?.intValue ?? 0
            summary.pendingUserInputCount = pending["userInputCount"]?.intValue ?? 0
        }
        // setTaskPinned/archiveTask 的 sessions-index 投影（缺席 = 服务端未投影，本地 override 兜底）
        summary.pinned = dict["pinned"]?.boolValue
        summary.archived = dict["archived"]?.boolValue ?? dict["isArchived"]?.boolValue
        // 要求 4：会话自带归属工作区（宽容多键：workspacePath / workspace 字符串 / workspace.path 嵌套；
        // listArchivedTasks 行已有 workspacePath 字段先例）。缺席 = 无法判定归属 → nil → 「其它」组
        summary.workspacePath = dict["workspacePath"]?.stringValue
            ?? dict["workspace"]?.stringValue
            ?? dict["workspace"]?.objectValue?["path"]?.stringValue
        // G-007 通路 A：行自带 workflowActivity（sessionWorkflowActivitySchema；无 run 时缺席）
        summary.workflowActivity = dict["workflowActivity"]
        return summary
    }
}

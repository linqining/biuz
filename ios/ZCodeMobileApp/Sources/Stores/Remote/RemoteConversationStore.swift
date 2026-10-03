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

    nonisolated var isReadOnly: Bool { true }

    private var sessions: [String: SessionSummary] = [:]
    private var continuations: [UUID: AsyncStream<ConversationEvent>.Continuation] = [:]

    /// 会话行模型（rowId 键控）与派生消息
    private var rows: [String: [Int: RowRecord]] = [:]
    private var messages: [String: [ChatMessage]] = [:]
    private var snapshotState: [String: JSONValue] = [:] // state.updated patch 合并目标
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
    }

    init(connection: ZCodeServerConnection, workspace: ServerWorkspaceInfo) {
        self.connection = connection
        self.workspace = workspace
    }

    // MARK: 会话列表

    func conversations() async -> [Conversation] {
        await ensureSessionsIndexSubscribed()
        return sortedConversations()
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
                    summary: summary.lastAssistantPreview ?? "暂无输出",
                    directory: workspace.path,
                    updatedAt: summary.lastActivityAt ?? Date.distantPast)
                conversation.isRunning = summary.phase == "running" || summary.phase == "prewarming"
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
            // 服务端签名要求 topic + sessionId（zcodeAgent.ts:144-146）
            let arg = RPCValue.jsonObject { builder in
                builder.set("topic", topic)
                builder.set("sessionId", conversationID)
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
        } catch {
            // 订阅失败兜底第二步（调研 plan）：readSession（只读恢复）对账展示态，
            // 修正 pendingInteractionSummary 等角标；消息仍以可用流/分页为准。
            await reconcileViaReadSession(conversationID)
        }
    }

    /// readSession 对账（runtimePolicy=existing-only：只读恢复，不拉起 Agent）
    private func reconcileViaReadSession(_ conversationID: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("sessionId", conversationID)
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
            builder.set("sessionId", conversationID)
        }
        _ = try? await connection.call("zcode-agent", "unsubscribeConversationV4", arg)
    }

    /// 拉一页历史行；返回是否还有更早数据（回执 hasMore 缺席时以「非空页」近似）。
    @discardableResult
    private func loadHistory(conversationID: String, beforeRowId: Int?) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        builder.set("sessionId", conversationID)
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
            // 历史拉取失败：以实时流为准
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
                    timestamp: Date()))
            case "assistantText":
                let state = row["state"]?.stringValue ?? "complete"
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: row["text"]?.stringValue ?? "",
                    status: state == "streaming" ? .streaming : .done,
                    timestamp: Date()))
            case "reasoning":
                let state = row["state"]?.stringValue ?? "complete"
                let text = row["text"]?.stringValue ?? ""
                guard !text.isEmpty else { continue }
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: "💭 " + text,
                    status: state == "streaming" ? .streaming : .done,
                    timestamp: Date()))
            case "toolCall":
                let status = row["status"]?.stringValue ?? "running"
                let outputText = row["output"]?.objectValue?["text"]?.stringValue
                    ?? row["outputPreview"]?.objectValue?["text"]?.stringValue
                    ?? row["progress"]?.objectValue?["text"]?.stringValue
                let toolCall = ToolCall(
                    id: row["toolCallId"]?.stringValue ?? "tool-\(rowId)",
                    kind: Self.mapToolKind(row["toolName"]?.stringValue),
                    target: row["inputText"]?.stringValue ?? row["toolName"]?.stringValue ?? "",
                    status: Self.mapToolStatus(status),
                    duration: nil,
                    addedLines: row["display"]?.objectValue?["addedLines"]?.intValue,
                    removedLines: row["display"]?.objectValue?["removedLines"]?.intValue,
                    output: outputText,
                    diff: nil)
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
                    timestamp: Date()))
            default:
                // turnHeader / hookInvocation / timelineMarker 不进消息流
                break
            }
        }
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

    // MARK: 命令面（v3 纠偏口径：客户端发命令、桌面代执行）

    /// sendConversationCommandV4 信封构造
    private func sendCommand(_ type: String, sessionId: String?, payload: JSONValue) async -> JSONValue? {
        guard let connection else { return nil }
        let envelope = RPCValue.jsonObject { builder in
            builder.set("commandId", UUID().uuidString)
            builder.set("clientId", "zcode-mobile")
            if let sessionId {
                builder.set("sessionId", sessionId)
            } else {
                builder.set("sessionId", JSONValue.null)
            }
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
    func send(_ text: String, in conversationID: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
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
        }
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
        var payload: [String: JSONValue] = ["workspaceId": .string(workspace.path)]
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
            id: sessionId, title: trimmed.isEmpty ? "新会话" : trimmed,
            summary: trimmed.isEmpty ? "空会话 · 可在输入框发起首条任务" : "已发送首条指令 · 桌面端执行中",
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
                    summary: summary.lastAssistantPreview ?? "已归档",
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
                        summary: summary?.lastAssistantPreview ?? item.objectValue?["lastAssistantPreview"]?.stringValue ?? "已归档",
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
            title: dict["title"]?.stringValue ?? "未命名会话",
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
        return summary
    }
}

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

    /// v4 会话域 RPC 的 workspace 目标（对齐 Web 客户端 wire 形状：扁平
    /// `workspacePath` + `workspaceIdentity?`；桌面端代理派生
    /// `workspaceKey = identity || path` 并自行组装信封——嵌套 `workspace:{...}`
    /// 会被参数规范化丢弃，触发 ZCodeProtocolClientError）。
    /// **按会话归属 workspace 寻址**【实证·上游仓 zcodeAgentService.subscribeConversationV4：
    /// `getReadOnlyClient(params)` 以 params.workspacePath 选 CLI 进程承接订阅，冷订阅并把
    /// 该 workspace 作为会话恢复的权威身份】——此前恒用连接 workspace，中继桥绑定桌面当前
    /// 窗口 workspace（本例 poker_protocol）而会话建在另一 workspace（zcode_mobile）时，
    /// 订阅落在错误 workspace 的 CLI 上：历史快照能读（全局 db 冷恢复），运行中 turn 的
    /// 行增量永不抵达 → 真机报障 2026-10-07「手机端没有回复」（桌面 18:36:41 已完成回复，
    /// 手机侧订阅流静默）。归属未知（深链冷启 sessions 表未填）回退连接 workspace = 旧口径。
    private func applySessionTarget(_ builder: inout JSONObjectBuilder, sessionID: String?) {
        if let sessionID {
            builder.set("sessionId", sessionID)
        }
        let target = workspaceTarget(for: sessionID, overridePath: nil)
        builder.set("workspacePath", target.path)
        if let identity = target.identity, !identity.isEmpty {
            builder.set("workspaceIdentity", identity)
        }
    }

    nonisolated var isReadOnly: Bool { true }

    private var sessions: [String: SessionSummary] = [:]
    private var continuations: [UUID: AsyncStream<ConversationEvent>.Continuation] = [:]
    /// 跨区任务索引聚合节流时间戳（P0：30s TTL，见 mergeGlobalTaskIndex）
    private var globalIndexMergedAt: Date?

    /// 会话行模型（rowId 键控）与派生消息
    private var rows: [String: [Int: RowRecord]] = [:]
    private var messages: [String: [ChatMessage]] = [:]
    private var snapshotState: [String: JSONValue] = [:] // state.updated patch 合并目标
    /// 会话 state revision（快照/state.updated 各自携带；switchModelConfig/pauseGoal 等
    /// CAS 类命令信封必须携带，缺失被桌面端拒收 "CAS command require base revision"）
    private var conversationStateRevisions: [String: Int] = [:]
    /// 会话加载失败文本（read 面透出：订阅/历史拉取失败此前只落 diag 键，UI 完全
    /// 不可见——真机报障「二级页面消息区空白且无提示」2026-10-06 现场实证：sub ERR
    /// 桥退化重建（rpc-transport-fault）+ rowsRange ERR SendFailed 双失败即空白）。
    /// 键级覆盖：任一环节成功即清除；订阅与拉取都成功而行为空 = 正常空会话（nil）。
    private var loadFailures: [String: String] = [:]
    /// 会话列表级失败（M1：sessions-index 订阅失败——列表拉取失败与真空不可区分；
    /// 成功订阅/有 bootstrap 缓存时为 nil，UI 据此出错误页或「缓存横幅」）
    private var listLoadFailure: String?
    /// 桌面 workflow run 最新负载（要求 5：快照/workflowRun.updated 带内事件双通道；
    /// workflowRunFetched 记录 RPC 兜底已尝试，失败不重复请求）
    private var workflowRunStates: [String: JSONValue] = [:]
    private var workflowRunFetched: Set<String> = []
    /// 产物/计划 API 取证一次性门槛（diag 专用）
    private var workflowApiDiagDone: Set<String> = []
    /// state.workflowRuns 间歇缺席的一次性全量 resync 门槛
    private var workflowResyncTriggered: Set<String> = []
    /// 订阅回执竞态屏障（官方 ackActivationBarrier 镜像）：订阅回执前到达的帧
    /// 先暂存、subId 落库后原序回放；订阅失败整体丢弃——「快照帧先于回执」竞态的
    /// 确定性收口（v1.21 的 handler 先注册是其弱形式，本屏障补齐激活语义）
    private let frameBarrier = AckActivationBarrier()
    /// forceFullResync（base=null）已发出、下一帧快照按全量语义整表替换的会话集
    /// （此前 merge 残留幽灵行——桌面已移除的行在全量 resync 后仍显示，P2）
    private var fullResyncSnapshotsPending: Set<String> = []

    /// 按 Web 构造取证缺失 API 的真实响应（conversationWorkflowRunArtifactsV4 /
    /// ArtifactDataV4 / conversationPlansV4；Web: {...workspace, sessionId, runId}）。
    /// runId 为空时退化为 RPC limit 变体 + workflow 工具卡行样本取证
    private func workflowApiDiagDump(conversationID: String, runId: String) async {
        guard let connection else { return }
        if runId.isEmpty {
            // ① RPC limit 变体（不带 limit 返回空 → 怀疑服务端缺省 0）
            for (tag, extra) in [("nolimit", false), ("limit50", true)] {
                var b = JSONObjectBuilder()
                applySessionTarget(&b, sessionID: conversationID)
                if extra { b.set("limit", 50) }
                if let r = try? await connection.call(
                    "zcode-agent", "conversationWorkflowRunsV4", .json(.object(b.fields))) {
                    UserDefaults.standard.set(
                        "\(tag): \(String(describing: r.jsonValue).prefix(1600))",
                        forKey: "diag.wf.rpc.\(tag)")
                }
            }
            // ② 消息行里的 workflow 工具卡样本（Web 对齐渲染的数据源候选）
            let table = rows[conversationID] ?? [:]
            let wfRow = table.values.sorted { $0.rowId < $1.rowId }.first { record in
                guard let d = record.json.objectValue,
                      d["kind"]?.stringValue == "toolCall" else { return false }
                let name = (d["toolName"]?.stringValue ?? "") + (d["inputText"]?.stringValue ?? "")
                    + (d["display"]?.objectValue?["title"]?.stringValue ?? "")
                return name.localizedCaseInsensitiveContains("workflow") || name.contains("工作流")
            }
            if let wfRow {
                UserDefaults.standard.set(
                    "workflow toolCall 行: \(String(describing: wfRow.json).prefix(2400))",
                    forKey: "diag.wf.toolrow")
            } else {
                UserDefaults.standard.set("行内无 workflow toolCall（table=\(table.count)）", forKey: "diag.wf.toolrow")
            }
            // ③ plans 取证
            var pb = JSONObjectBuilder()
            applySessionTarget(&pb, sessionID: conversationID)
            if let rp = try? await connection.call(
                "zcode-agent", "conversationPlansV4", .json(.object(pb.fields))) {
                UserDefaults.standard.set(
                    String(describing: rp.jsonValue).prefix(2200), forKey: "diag.wf.plans")
            } else {
                UserDefaults.standard.set("RPC 失败", forKey: "diag.wf.plans")
            }
            return
        }
        var b = JSONObjectBuilder()
        applySessionTarget(&b, sessionID: conversationID)
        b.set("runId", runId)
        if let result = try? await connection.call(
            "zcode-agent", "conversationWorkflowRunArtifactsV4", .json(.object(b.fields))) {
            UserDefaults.standard.set(
                String(describing: result.jsonValue).prefix(2200), forKey: "diag.wf.artifacts")
            let arts = result.jsonValue?["artifacts"]?.arrayValue
                ?? result.jsonValue?["result"]?.objectValue?["artifacts"]?.arrayValue ?? []
            if let first = arts.first?.objectValue,
               let artId = first["artifactId"]?.stringValue ?? first["id"]?.stringValue {
                var b2 = JSONObjectBuilder()
                applySessionTarget(&b2, sessionID: conversationID)
                b2.set("runId", runId)
                b2.set("artifactId", artId)
                b2.set("limit", 3)
                if let r2 = try? await connection.call(
                    "zcode-agent", "conversationWorkflowRunArtifactDataV4", .json(.object(b2.fields))) {
                    UserDefaults.standard.set(
                        "artifactId=\(artId) · \(String(describing: r2.jsonValue).prefix(1400))",
                        forKey: "diag.wf.artdata")
                }
            }
        } else {
            UserDefaults.standard.set("RPC 失败", forKey: "diag.wf.artifacts")
        }
        var pb = JSONObjectBuilder()
        applySessionTarget(&pb, sessionID: conversationID)
        if let rp = try? await connection.call(
            "zcode-agent", "conversationPlansV4", .json(.object(pb.fields))) {
            UserDefaults.standard.set(
                String(describing: rp.jsonValue).prefix(2200), forKey: "diag.wf.plans")
        } else {
            UserDefaults.standard.set("RPC 失败", forKey: "diag.wf.plans")
        }
    }
    /// G-008 通路 B：conversation state.workflowRuns（workflowRunsStateSchema {revision, runs[]}）。
    /// 冷快照必带（snapshot.ts:497-499）、state.updated 键级整体替换（delta.ts:50）、
    /// workflowRun.updated/removed 增量（delta.ts:122/140，header/条目整替换，绝无字段级深合并）
    private var workflowRunTables: [String: (revision: Int, runs: [JSONValue])] = [:]
    /// reasoning 行流式计时（消息ID → 流式开始时刻；streaming→done 时换算 duration）。
    /// 历史快照一次性到达的 done 行无开始时刻（首见即 done），UI 退化为仅字数摘要。
    private var reasoningStartedAt: [String: Date] = [:]
    private var reasoningDuration: [String: TimeInterval] = [:]
    private var pendingInteractions: [String: JSONValue] = [:]
    /// 会话级模型选择（state.modelSelection 键级替换；桌面 composer 变更随 delta 到达）
    private var sessionModelSelections: [String: JSONValue] = [:] // sessionId → 最新 pendingInteractions 数组
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
    /// 归档行自带的归属工作区（listArchivedTasks 行 workspacePath 字段；unarchiveTask
    /// 写目标以行归属为准——web 同款 `{taskId, workspacePath: 行.workspacePath,
    /// workspaceIdentity?}`，不得以当前连接工作区冒充，⑥ 2026-10-06）
    private var archivedTaskWorkspaces: [String: (path: String, identity: String?)] = [:]
    /// bootstrap.tasks 派生的工作区清单（⑥归档拉取范围 + 写目标身份反查；setBootstrapTasks
    /// 时经 AppSession.deriveBootstrapWorkspaces 派生，与切换器枚举同源同公式）
    private var bootstrapWorkspaces: [ServerWorkspaceInfo] = []
    // MARK: tasks-index membership join（用户报障 2026-10-07「置顶 4 项只见 1 项/归档第三次丢失」
    // 根因修复）：上游 sessionSummarySchema【实证·上游仓 zcode-protocol-v4/sessions-index.ts】
    // **不含 pinned/archived 字段**——sessions-index 行永不携带组织态，此前 dict["pinned"]/
    // dict["archived"] 恒 nil，置顶只剩内存 override（重启即丢）。web 权威口径【实证·上游仓
    // taskListMembershipSets.ts】："tasks-index.sqlite 提供行集合和 membership，sessions-index
    // 只补实时 activity/detail"——listPinnedTasks/listArchivedTasks 逐 scope 并发拉取后客户端 join
    private var pinnedTaskIds: Set<String> = []
    private var archivedTaskIds: Set<String> = []
    /// 最近一次归档/取消归档写失败原文（空串 = 无；列表页如实提示，写面禁止静默）
    private var _lastArchiveFailureText = ""
    func lastArchiveFailureText() async -> String { _lastArchiveFailureText }
    /// workspace-config / model-selection 只读投影（连接态数据源）
    private var workspaceConfigState = WorkspaceConfigInfo()
    /// 模型 → 可用思考档（workspace-config configOptions 的对象形态 values；
    /// web 端 _ce schema 同源，chips 思考菜单词表）
    private var workspaceConfigThoughtByModel: [String: [String]] = [:]
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

    /// bootstrap-response 的 tasks 清单（跨工作区全量任务索引，2026-10-06 实测
    /// 250 行/26 个 distinct workspacePath——web「所有项目目录」的真实数据源）。
    /// AppSession 装配时经 actor 回写口注入。
    private var bootstrapTasksJSON: JSONValue?

    /// actor 隔离回写口（AppSession 装配时调用）。注入后立即合并——列表首跑
    /// （conversations()）早于装配 Task 的竞态在此收口，合并完推流刷新
    func setBootstrapTasks(_ json: JSONValue?) {
        bootstrapTasksJSON = json
        bootstrapWorkspaces = AppSession.deriveBootstrapWorkspaces(json)
        mergeBootstrapTaskIndex()
        yieldConversationsReplaced()
    }

    func conversations() async -> [Conversation] {
        await ensureSessionsIndexSubscribed()
        await mergeBootstrapTaskIndex()
        await mergeGlobalTaskIndex()
        // membership join 后台刷新（置顶/归档组织态；不阻塞首屏——完成后经
        // conversationsReplaced 事件二次刷新。sessions-index 行无 pinned/archived 字段，
        // 不拉 tasks-index 则桌面置顶/归档在移动端永久失明，用户报障 2026-10-07）
        Task { [weak self] in
            guard let self else { return }
            await self.refreshTaskMembership()
            await self.yieldConversationsReplaced()
        }
        let list = sortedConversations()
        // 诊断：会话清单（完整 id+标题前缀）转储，供 -ZCodeOpenConversationId 取 id
        let dump = list.prefix(12).map { "\($0.id)=\($0.title.prefix(16))" }
            .joined(separator: " | ")
        UserDefaults.standard.set("total=\(list.count) · " + dump, forKey: "diag.sessions")
        return list
    }

    /// tasks-index membership（pin/archive 组织态权威源）逐 scope **并发**拉取
    /// 【实证·上游仓 taskListMembershipSets.ts：`e.scopes.map(...)` 并发 + 单区失败跳过】；
    /// 完成后写 pinnedTaskIds/archivedTaskIds 供 sortedConversations join。scope 清单
    /// 与归档拉取同源（连接层采集 ∪ bootstrap 派生 ∪ 当前工作区保底）。
    func refreshTaskMembership() async {
        guard let connection else { return }
        let scopes = Self.membershipScopes(
            all: allWorkspaces, bootstrap: bootstrapWorkspaces, current: workspace)
        var pinned: Set<String> = []
        var archived: Set<String> = []
        await withTaskGroup(of: (Bool, [String]).self) { group in
            for scope in scopes {
                let method = scope.pinned ? "listPinnedTasks" : "listArchivedTasks"
                group.addTask { [connection] in
                    var builder = JSONObjectBuilder()
                    builder.set("workspacePath", scope.path)
                    if let identity = scope.identity, !identity.isEmpty {
                        builder.set("workspaceIdentity", identity)
                    }
                    do {
                        let result = try await connection.call(
                            "zcode-task", method, .json(.object(builder.fields)))
                        let dict = result.jsonValue?.objectValue ?? [:]
                        let items = dict["items"]?.arrayValue ?? dict["tasks"]?.arrayValue
                            ?? result.jsonValue?.arrayValue ?? []
                        let ids = items.compactMap {
                            $0.objectValue?["taskId"]?.stringValue
                                ?? $0.objectValue?["sessionId"]?.stringValue
                        }
                        return (scope.pinned, ids)
                    } catch {
                        return (scope.pinned, []) // 单区失败跳过（web 同构）
                    }
                }
            }
            for await (isPinned, ids) in group {
                if isPinned { pinned.formUnion(ids) } else { archived.formUnion(ids) }
            }
        }
        pinnedTaskIds = pinned
        archivedTaskIds = archived
    }

    /// membership/归档拉取的 scope 清单（去重；pin 与 archive 各半——web 两集合同 scope 面）
    nonisolated private static func membershipScopes(
        all: [ServerWorkspaceInfo], bootstrap: [ServerWorkspaceInfo],
        current: ServerWorkspaceInfo) -> [(path: String, identity: String?, pinned: Bool)] {
        var paths: [String] = []
        var seen: Set<String> = []
        for ws in all where !seen.contains(ws.path) {
            seen.insert(ws.path); paths.append(ws.path)
        }
        for ws in bootstrap where !seen.contains(ws.path) {
            seen.insert(ws.path); paths.append(ws.path)
        }
        if !seen.contains(current.path) { paths.append(current.path) }
        // 每个路径两问：listPinnedTasks + listArchivedTasks（pin 在前）
        return paths.flatMap { path -> [(path: String, identity: String?, pinned: Bool)] in
            var identity = all.first { $0.path == path }?.workspaceIdentity
                ?? bootstrap.first { $0.path == path }?.workspaceIdentity
            if identity == nil, path == current.path {
                identity = current.workspaceIdentity
            }
            return [
                (path, identity, true),
                (path, identity, false),
            ]
        }
    }

    /// 纯内存会话摘要（P0 缓存先行）：合并表即时投影，零 RPC——ChatView 首屏
    /// 标题用；跨区聚合与实时订阅仍由 conversations()/后台链负责
    func cachedConversation(_ conversationID: String) -> Conversation? {
        sortedConversations().first { $0.id == conversationID }
    }

    /// 会话列表级失败文本（M1 读面如实呈现：nil = 正常；非 nil = UI 出错误页/横幅）
    func conversationsLoadFailure() -> String? {
        listLoadFailure
    }

    /// bootstrap.tasks 直接入会话表（行形状与 listTaskList items 同源：taskId/title/
    /// workspacePath/lastActivityAt——web Nxe 去重逻辑同款字段）。跨工作区无需 scope
    /// RPC；只补缺（sessions-index/listTaskList 行优先，不覆盖既有）。
    private func mergeBootstrapTaskIndex() {
        guard let items = bootstrapTasksJSON?.arrayValue, !items.isEmpty else { return }
        var merged = 0
        var wsSeen = Set<String>()
        for item in items {
            guard var d = item.objectValue else { continue }
            if d["sessionId"] == nil, let taskId = d["taskId"]?.stringValue {
                d["sessionId"] = .string(taskId)
            }
            if let ws = d["workspacePath"]?.stringValue { wsSeen.insert(ws) }
            guard let summary = SessionSummary.parse(.object(d)) else { continue }
            if sessions[summary.sessionId] == nil {
                sessions[summary.sessionId] = summary
                merged += 1
            } else if sessions[summary.sessionId]?.workspacePath == nil,
                      let ws = summary.workspacePath {
                sessions[summary.sessionId]?.workspacePath = ws
            }
        }
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set(
                "rows=\(items.count) merged=\(merged) totalSessions=\(sessions.count) distinctWs=\(wsSeen.count)",
                forKey: "diag.tasks.bootstrap")
            UserDefaults.standard.synchronize()
        }
    }

    /// 多 workspace 合并（v1.5 取证：桌面桥对配对客户端回 workspace-list-response，
    /// 全部工作区清单经 ConnectSummary → ServerRemoteInfo.workspaces 到达装配层——
    /// 此前只取 activeWorkspaceKey，清单一直在路上而被丢弃）。对清单内每个工作区
    /// 逐个 listTaskList 聚合（workspace-list 页同源口径；无清单时回退单 workspace 基线）。
    /// 行的 workspacePath 为归属权威（分组用它）。失败逐区静默（其余区不劣化）。
    /// AppSession 装配后回写（连接期 ChannelClient 采集）。
    /// actor 隔离回写口（AppSession 装配时调用）
    func setAllWorkspaces(_ list: [ServerWorkspaceInfo]) {
        self.allWorkspaces = list
    }

    /// P1 重连缓存迁移：可跨 actor 传递的缓存快照（值类型均已跨 actor 返回过）
    struct ExportedCaches: Sendable {
        var sessions: [String: SessionSummary]
        var rows: [String: [Int: RowRecord]]
        var messages: [String: [ChatMessage]]
        var snapshotState: [String: JSONValue]
        var conversationStateRevisions: [String: Int]
        var allWorkspaces: [ServerWorkspaceInfo]
        var bootstrapTasksJSON: JSONValue?
    }

    /// 导出缓存（旧 store 实例上调用）：重连同工作区重建 Store 时行/消息投影与
    /// 会话合并表不丢——重连后首开命中缓存即渲染，不等订阅与全量拉取
    func exportCaches() -> ExportedCaches {
        ExportedCaches(
            sessions: sessions, rows: rows, messages: messages,
            snapshotState: snapshotState,
            conversationStateRevisions: conversationStateRevisions,
            allWorkspaces: allWorkspaces,
            bootstrapTasksJSON: bootstrapTasksJSON)
    }

    /// 采纳缓存（新 store 实例上调用）。订阅簿记（subscriptionIds/subscriptions）
    /// 有意不迁移：subscriptionId 属于旧连接，重新订阅拿新 id；重订阅快照按 rowId
    /// 字典键级合并去重（旧行保留、尾部窗口照常覆盖），与 web 冷/热判定同构。
    /// loadFailures 亦不迁移（重连=新一次加载尝试）
    func adoptCaches(_ caches: ExportedCaches) {
        sessions = caches.sessions
        rows = caches.rows
        messages = caches.messages
        snapshotState = caches.snapshotState
        conversationStateRevisions = caches.conversationStateRevisions
        allWorkspaces = caches.allWorkspaces
        bootstrapTasksJSON = caches.bootstrapTasksJSON
    }

    var allWorkspaces: [ServerWorkspaceInfo] = [] {
        didSet {
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    "all=\(allWorkspaces.count) [\(allWorkspaces.map(\.path).joined(separator: ", "))]",
                    forKey: "diag.ws.list")
                UserDefaults.standard.synchronize()
            }
        }
    }

    private func mergeGlobalTaskIndex() async {
        guard let connection else { return }
        // P0 节流（会话打开提速）：ChatView 首屏与列表刷新都触发本聚合（每区一次
        // listTaskList，26 区串行秒级）。30s 内不重打——新会话仍由 bootstrap 与
        // sessions-index 订阅实时补充，聚合只补归属/排序类字段
        if let mergedAt = globalIndexMergedAt,
           Date().timeIntervalSince(mergedAt) < 30 { return }
        globalIndexMergedAt = Date()
        let connected = workspace.path
        // 聚合范围：全工作区清单（去重保序，active 在前）；空则退化为连接 workspace
        var scopePaths: [String] = []
        var scopeIdentities: [String: String?] = [:]
        for ws in allWorkspaces where !scopePaths.contains(ws.path) {
            scopePaths.append(ws.path)
            scopeIdentities[ws.path] = ws.workspaceIdentity
        }
        if scopePaths.isEmpty {
            scopePaths = [connected]
            scopeIdentities[connected] = workspace.workspaceIdentity
        } else if !scopePaths.contains(connected) {
            // 连接 workspace 保底（清单缺失该区时索引仍可能有会话）
            scopePaths.append(connected)
            scopeIdentities[connected] = workspace.workspaceIdentity
        }

        var totalMerged = 0
        var allWorkspacesSeen = Set<String>()
        for path in scopePaths {
            var builder = JSONObjectBuilder()
            builder.set("kind", "timeline")
            var scope: [String: JSONValue] = ["workspacePath": .string(path)]
            if let identity = scopeIdentities[path] ?? nil, !identity.isEmpty {
                scope["workspaceIdentity"] = .string(identity)
            }
            builder.set("workspaceScopes", .array([.object(scope)]))
            do {
                let result = try await connection.call(
                    "zcode-task", "listTaskList", .json(.object(builder.fields)))
                let items = result.jsonValue?["items"]?.arrayValue
                    ?? result.jsonValue?.arrayValue ?? []
                var parsed: [(String, SessionSummary)] = []
                for item in items {
                    guard var d = item.objectValue else { continue }
                    // 任务行主键是 taskId；统一成 sessions-index 的 sessionId 口径
                    if d["sessionId"] == nil, let taskId = d["taskId"]?.stringValue {
                        d["sessionId"] = .string(taskId)
                    }
                    if let ws = d["workspacePath"]?.stringValue {
                        allWorkspacesSeen.insert(ws)
                    }
                    guard let summary = SessionSummary.parse(.object(d)) else { continue }
                    parsed.append((summary.sessionId, summary))
                }
                for (sessionId, summary) in parsed {
                    if sessions[sessionId] == nil {
                        sessions[sessionId] = summary
                        totalMerged += 1
                    } else if sessions[sessionId]?.workspacePath == nil,
                              let ws = summary.workspacePath {
                        // 已有 index 行：仅补 workspace 归属（index 行可能缺 workspaceId）
                        sessions[sessionId]?.workspacePath = ws
                    }
                }
            } catch {
                if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                    UserDefaults.standard.set(
                        "scope=\(path) err=\(String(describing: error).prefix(200))",
                        forKey: "diag.tasks.merge.\(path.suffix(24))")
                    UserDefaults.standard.synchronize()
                }
            }
        }
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set(
                "scopes=\(scopePaths.count) merged=\(totalMerged) totalSessions=\(sessions.count) workspaces=\(allWorkspacesSeen.sorted().joined(separator: ","))",
                forKey: "diag.tasks.merge")
            UserDefaults.standard.synchronize()
        }
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
                // 失败任务（桌面侧栏失败任务带「清理」；phase 词表 completedError 族宽容）
                conversation.isFailed = summary.phase.lowercased().contains("error")
                    || summary.phase.lowercased().contains("failed")
                // G-007 通路 A：行迷你轨道数据（无 run 时 nil，行不渲染占位）
                conversation.workflowActivity = Self.parseWorkflowActivity(summary.workflowActivity)
                // 来源过滤（G-006）数据口径：连接态会话均来自当前桌面端（局域网/云中继
                // 均属"我的 Mac"）；云端沙盒执行端尚无会话数据源（档位在无 cloud 数据时置灰）
                conversation.source = "mac"
                // 组织态三源 join【实证·上游仓 taskListMembershipSets】：本地 override（操作
                // 即时反馈）→ tasks-index membership（listPinnedTasks/listArchivedTasks 拉取，
                // sessions-index 行不含 pinned/archived 字段——schema 冻结）→ 行字段（宽容
                // 兼容未来服务端投影，缺席不碍事）
                conversation.isPinned = localPinnedOverrides[summary.sessionId]
                    ?? (pinnedTaskIds.contains(summary.sessionId) ? true : nil)
                    ?? summary.pinned ?? false
                conversation.isArchived = localArchivedOverrides[summary.sessionId]
                    ?? (archivedTaskIds.contains(summary.sessionId) ? true : nil)
                    ?? summary.archived ?? false
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
            // 回执形态：{ack:{subscriptionId,mode,logEpoch}}（中继桥实测）；顶层兼容局域网。
            // 取不到 subId = 丢帧自愈（resyncSessionsIndex）全链路失效——视为订阅
            // 失败走 listSessions 兜底，不得静默落 nil 仍走成功路径（AGENTS §5-7）
            let ack = reply.jsonValue?["ack"]?.objectValue ?? reply.jsonValue?.objectValue
            guard let subscriptionId = ack?["subscriptionId"]?.stringValue
                ?? reply.jsonValue?["subscriptionId"]?.stringValue else {
                listLoadFailure = String(localized: "会话实时同步订阅失败 · 回执缺 subscriptionId（自愈链不可用）")
                await connection.log(.info, "subscribeSessionsIndexV4 回执缺 subscriptionId：\(String(describing: reply.jsonValue).prefix(200))")
                await fallbackListSessions()
                return
            }
            sessionsIndexSubscriptionId = subscriptionId
            await connection.log(.ok, "subscribeSessionsIndexV4 · \(subscriptionId)")
            sessionsIndexSubscription = EventSubscription { [weak self] in
                guard let self else { return }
                Task {
                    await self.disposeSessionsIndex()
                }
            }
            listLoadFailure = nil
        } catch {
            // 订阅失败兜底（named gap「订阅失败列表恒空」）：listSessions 只读拉一次列表。
            // 快照覆盖语义：订阅成功后帧仍以快照为准。拒因走 loadFailureText
            // （RPCError.name+message——localizedDescription 对 RPCError 丢真实拒因，§5-12）
            let failureText = Self.loadFailureText(verb: "订阅会话列表", error: error)
            await connection.log(.info, "subscribeSessionsIndexV4 失败，回退 listSessions：\(failureText)")
            listLoadFailure = failureText
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
            // P0：订阅快照通常自带尾部行窗口（web 同构——首屏=快照，rowsRange 只管
            // 向旧翻页）。快照帧与 ack 有竞态（帧 handler 于 ack 后注册），短暂等
            // 快照落表再决定回退拉取，避免「快照 + rowsRange(200)」双份行载荷
            for _ in 0..<6 {
                if !(rows[conversationID]?.isEmpty ?? true) { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if rows[conversationID]?.isEmpty ?? true {
                await loadHistory(conversationID: conversationID, beforeRowId: nil)
            }
        }
        return messages[conversationID] ?? []
    }

    /// 向上分页（named gap 补全：beforeRowId 此前无调用入口）：
    /// 取 rows 最小 rowId 为游标拉更早一页，rowId 字典天然拼接去重；
    /// 返回是否还有更早数据。结果经 messagesReplaced 事件推给 UI。
    @discardableResult
    func loadOlder(conversationID: String) async -> Bool {
        guard let oldest = rows[conversationID]?.keys.min() else { return false }
        let beforeCount = rows[conversationID]?.count ?? 0
        var hasMore = await loadHistory(
            conversationID: conversationID, beforeRowId: oldest,
            timeout: Self.pageFetchTimeout)
        // 中继瞬断会让单页拉取静默失败（表未增长且报无更多）：退避重试一次
        if !hasMore, (rows[conversationID]?.count ?? 0) == beforeCount {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            hasMore = await loadHistory(
                conversationID: conversationID, beforeRowId: oldest,
                timeout: Self.pageFetchTimeout)
        }
        rebuildMessages(conversationID)
        yieldToAll(.messagesReplaced(
            conversationID: conversationID, messages: messages[conversationID] ?? []))
        return hasMore
    }

    private func ensureConversationSubscribed(_ conversationID: String) async {
        guard conversationSubscriptions[conversationID] == nil, let connection else {
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    "guard 拦截 already=\(conversationSubscriptions[conversationID] != nil) connection=\(connection != nil)",
                    forKey: "diag.sub.entry")
            }
            return
        }
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set("进入", forKey: "diag.sub.entry")
        }
        let topic = "conversation/\(conversationID)"
        do {
            // 帧 handler 先于订阅调用注册【工程纪律 3】+ ACK 激活屏障（官方
            // ackActivationBarrier.ts:34-64 同构）：回执前到达的帧经
            // routeConversationFrame 暂存、subId 落库后原序回放——「subscriptionId
            // 必须先入 store 再 activate 释放暂存帧」（conversationProjectionStore.ts:460-463）。
            // handler 只是本地路由表，先注册无副作用；订阅失败时残留无害（幂等落表，
            // 重订阅覆盖）。
            await frameBarrier.begin(conversationID)
            await connection.setFrameHandler(topic: topic) { [weak self] frame in
                Task { await self?.routeConversationFrame(conversationID, frame: frame) }
            }
            // 丢帧自愈：conversation assembler 是单键（多会话共用），dropped 时对全部
            // 已保存 subscriptionId 的订阅逐个 resync（幂等；无水位传 null 走全量快照）
            await connection.setFrameDropHandler(key: "conversation") { [weak self] in
                guard let self else { return }
                Task { await self.resyncAllConversations() }
            }
            // 服务端签名要求 topic + sessionId + workspace 信封（zcodeAgent.ts:144-146）
            let target = workspaceTarget(for: conversationID, overridePath: nil)
            // 帧面与订阅同 workspace【P0-1，2026-10-08 真机实据 sess_e8677b05】：订阅按
            // 归属寻址落 B 区 CLI，而 eventListen 恒绑连接区 A——上游 ownsFrame 按
            // workspaceKey 硬匹配，B 区帧永不投递（快照/revision/增量全丢）。幂等：
            // 已挂路直接返回。
            await connection.ensureConversationFrameStream(workspacePath: target.path)
            let arg = RPCValue.jsonObject { builder in
                builder.set("topic", topic)
                applySessionTarget(&builder, sessionID: conversationID)
            }
            UserDefaults.standard.set(
                String(describing: arg.jsonValue).prefix(600),
                forKey: "diag.wire.sub.\(conversationID.prefix(14))")
            let reply = try await connection.call("zcode-agent", "subscribeConversationV4", arg)
            // 回执形状取证（临时无条件；定位 ack 包裹解析问题后移除）。
            // synchronize：simctl terminate=SIGKILL 不冲洗 cfprefsd，不 sync 则取证值丢失
            UserDefaults.standard.set(
                String(describing: reply.jsonValue).prefix(400), forKey: "diag.sub.reply")
            UserDefaults.standard.synchronize()
            // 回执形态：{ack:{subscriptionId,mode,logEpoch}}（中继桥实测，同 sessions-index）；
            // 顶层兼容局域网直连。取不到 = 自愈链路失效（AGENTS §5-7：resync/丢帧自愈
            // 全依赖 subId），视为订阅失败走兜底链——此前静默落 nil 仍清 loadFailures
            // 走成功路径，连锁 ensureStateRevision 永不回填 revision → CAS 全族被
            // 本地拒发门永久拒发该会话（P1②，2026-10-07 审查轮亲证）
            let ack = reply.jsonValue?.objectValue?["ack"]?.objectValue
            guard let subscriptionId = ack?["subscriptionId"]?.stringValue
                ?? reply.jsonValue?["subscriptionId"]?.stringValue else {
                await frameBarrier.abort(conversationID)
                let detail = String(localized: "订阅会话失败 · 回执缺 subscriptionId（丢帧自愈链不可用）")
                loadFailures[conversationID] = detail
                UserDefaults.standard.set(
                    "sub ERR \(detail) reply=\(String(describing: reply.jsonValue).prefix(200))",
                    forKey: "diag.conv.\(conversationID.prefix(14))")
                await reconcileViaReadSession(conversationID)
                return
            }
            conversationSubscriptionIds[conversationID] = subscriptionId
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    "subId=\(subscriptionId) reply=\(String(describing: reply.jsonValue).prefix(280))",
                    forKey: "diag.sub.reply")
            }
            // 屏障放行：subId 已落库，暂存帧按原序回放；overflow=暂存超限（增量连续性
            // 不可保证）→ 全量 resync 重建（上游 initialFrameStagingOverflow 同语义）
            switch await frameBarrier.finish(conversationID) {
            case .frames(let stagedFrames):
                for frame in stagedFrames {
                    handleConversationFrame(conversationID, frame: frame)
                }
            case .overflow:
                await forceFullResync(conversationID: conversationID, subscriptionId: subscriptionId)
            case .empty:
                break
            }
            conversationSubscriptions[conversationID] = EventSubscription { [weak self] in
                guard let self else { return }
                Task { await self.disposeConversation(conversationID) }
            }
            UserDefaults.standard.set(
                "sub ok rows=\(rows[conversationID]?.count ?? -1)",
                forKey: "diag.conv.\(conversationID.prefix(14))")
            loadFailures[conversationID] = nil
        } catch {
            // 订阅未成立：暂存帧整体丢弃（未取得订阅的帧不可信）
            await frameBarrier.abort(conversationID)
            let detail: String
            if let rpcError = error as? RPCError {
                detail = "name=\(rpcError.name) msg=\(rpcError.message) detail=\(rpcError.detail.map(String.init(describing:)) ?? "nil")"
            } else {
                detail = String(describing: error)
            }
            UserDefaults.standard.set(
                "sub ERR \(detail)",
                forKey: "diag.conv.\(conversationID.prefix(14))")
            // 失败透出（read 面协议 conversationLoadFailure）：UI 错误态+重试的数据源
            loadFailures[conversationID] = Self.loadFailureText(verb: "订阅会话", error: error)
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
            let result = try await connection.call(
                "zcode-agent", "readSession", .json(.object(builder.fields)),
                timeout: Self.historyFetchTimeout)
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
            // workspace 信封随订阅同源（ownership 匹配，forceFullResync 同注）
            applySessionTarget(&builder, sessionID: conversationID)
            _ = try? await connection.call(
                "zcode-agent", "resyncConversationV4", .json(.object(builder.fields)))
        }
    }

    private func disposeConversation(_ conversationID: String) async {
        guard let connection, conversationSubscriptions.removeValue(forKey: conversationID) != nil else { return }
        await frameBarrier.abort(conversationID)
        await connection.removeFrameHandler(topic: "conversation/\(conversationID)")
        conversationSubscriptionIds.removeValue(forKey: conversationID)
        conversationWatermarks.removeValue(forKey: conversationID)
        let arg = RPCValue.jsonObject { builder in
            builder.set("topic", "conversation/\(conversationID)")
            applySessionTarget(&builder, sessionID: conversationID)
        }
        _ = try? await connection.call("zcode-agent", "unsubscribeConversationV4", arg)
    }

    /// 历史拉取/readSession 对账的 RPC 超时预算（connection.call 缺省 30s）。
    /// 用户真机实证（2026-10-06）：桌面端正执行大型 workflow 时初始 conversationRowsRangeV4
    /// 30s 超时（TimeoutError）——繁忙桌面单次调用可能超 30s，放大到 60s（仍有限，
    /// 不为绕超时无限挂起 UI）。
    static let historyFetchTimeout: TimeInterval = 60

    /// 向上翻页单页预算（用户 2026-10-06「加载更早消息一直等待」：翻页曾共用 60s
    /// 初始预算 + 自带重试，最长 ~2 分钟转圈）。翻页是用户滚顶触发的轻交互，
    /// 快速失败交给「再点一次」比重挂着好；初始全量拉取仍用 60s+自动重试
    static let pageFetchTimeout: TimeInterval = 20

    /// 超时错误判定（ChannelClient/RelayChannelClient 超时统一 name="TimeoutError"）
    nonisolated static func isTimeoutError(_ error: Error) -> Bool {
        (error as? RPCError)?.name == "TimeoutError"
    }

    /// 拉一页历史行；返回是否还有更早数据（回执 hasMore 缺席时以「非空页」近似）。
    @discardableResult
    private func loadHistory(
        conversationID: String, beforeRowId: Int?, retryOnTimeout: Bool = true,
        timeout: TimeInterval = RemoteConversationStore.historyFetchTimeout
    ) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: conversationID)
        builder.set("limit", 200)
        if let beforeRowId {
            builder.set("beforeRowId", beforeRowId)
        }
        do {
            let result = try await connection.call(
                "zcode-agent", "conversationRowsRangeV4", .json(.object(buildFields(builder))),
                timeout: timeout)
            guard let dict = result.jsonValue?.objectValue else { return false }
            let pageRows = (dict["rows"]?.arrayValue ?? []).compactMap { row -> RowRecord? in
                guard let rowId = row.objectValue?["rowId"]?.intValue else { return nil }
                return RowRecord(rowId: rowId, json: row)
            }
            var table = rows[conversationID] ?? [:]
            for record in pageRows { table[record.rowId] = record }
            rows[conversationID] = table
            rebuildMessages(conversationID)
            // 诊断：向上分页取证（游标/页行数/页行号范围/表总量/hasMore）
            let pageIds = pageRows.map(\.rowId).sorted()
            UserDefaults.standard.set(
                "cursor=\(beforeRowId.map(String.init) ?? "nil") page=\(pageRows.count)"
                    + (pageIds.isEmpty ? "" : " range=[\(pageIds.first!)..\(pageIds.last!)]")
                    + " table=\(table.count)"
                    + " hasMoreField=\(dict["hasMore"]?.boolValue.map(String.init) ?? "absent")"
                    + " respKeys=\(dict.keys.sorted().prefix(8))",
                forKey: "diag.rowsrange.ok")
            // hasMore 显式字段优先；缺席时非空页视为可能还有更早数据（loadOlder 再探一页）
            loadFailures[conversationID] = nil
            return dict["hasMore"]?.boolValue ?? (!pageRows.isEmpty)
        } catch {
            // 超时首败：1.2s 退避自动重试一次再落错误态（AGENTS §5.8 退避重试同口径；
            // 仅初始拉取重试——loadOlder 已有「表未增长」重试，叠加会三连发）。重试也超时
            // 才记录失败（手动重试按钮保留，ViewModel 退避链不受影响）
            if retryOnTimeout, beforeRowId == nil, Self.isTimeoutError(error) {
                UserDefaults.standard.set(
                    "rowsRange TIMEOUT 首败，1.2s 后重试一次",
                    forKey: "diag.rowsrange.\(conversationID.prefix(14))")
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                return await loadHistory(
                    conversationID: conversationID, beforeRowId: nil, retryOnTimeout: false)
            }
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
            // 失败透出（同订阅失败口径）：UI 错误态+重试的数据源
            loadFailures[conversationID] = Self.loadFailureText(verb: "拉取历史", error: error)
            return false
        }
    }

    /// 加载失败 → 用户可读文本（与 diag 键同行同源；RpcError 取 name+message，
    /// 其余原样描述。重试按钮经 messages(in:) 重入——订阅守卫对失败态放行、
    /// rows 空表重新分页拉取）
    nonisolated static func loadFailureText(verb: String, error: Error) -> String {
        if let rpcError = error as? RPCError {
            let base = String(localized: "\(verb)失败 · \(rpcError.name)：\(rpcError.message)")
            // 超时场景注明桌面端可能繁忙（繁忙桌面/大会话初始拉取易超时，已自动重试过仍失败）
            if rpcError.name == "TimeoutError" {
                return base + String(localized: " · 桌面端可能繁忙，可稍后重试")
            }
            return base
        }
        return String(localized: "\(verb)失败 · \(String(describing: error))")
    }

    /// 会话最近一次加载失败文本（nil = 无失败——含「订阅/拉取都成功但行为空」的
    /// 正常空会话；空/载/失败三态由 UI 据此区分）。瞬时传输故障（中继桥退化重建/
    /// 订阅超时）打开会话即触发，重试经 messages(in:) 重入。
    func conversationLoadFailure(in conversationID: String) async -> String? {
        loadFailures[conversationID]
    }

    private func buildFields(_ builder: JSONObjectBuilder) -> [String: JSONValue] {
        builder.fields
    }

    // MARK: 行模型 → ChatMessage 映射

    /// 帧路由（ACK 激活屏障口）：订阅回执未抵达期间到达的帧进暂存，subId 落库后
    /// 由 finish 原序回放（官方 conversationProjectionStore「subscriptionId 先入
    /// store 再 activate」同构）；已激活（subId 在案）直通处理。
    private func routeConversationFrame(_ conversationID: String, frame: V4TopicFrame) async {
        if conversationSubscriptionIds[conversationID] == nil,
           await frameBarrier.isInFlight(conversationID) {
            await frameBarrier.stage(conversationID, frame: frame)
            return
        }
        handleConversationFrame(conversationID, frame: frame)
    }

    private func handleConversationFrame(_ conversationID: String, frame: V4TopicFrame) {
        // 水位记录（resync base）：logEpoch 仅快照携带，缺席沿用旧值
        let previousEpoch = conversationWatermarks[conversationID]?.logEpoch
        conversationWatermarks[conversationID] = (
            frame.snapshot?.objectValue?["logEpoch"]?.stringValue ?? previousEpoch,
            frame.toSeq
        )
        if let snapshot = frame.snapshot {
            if let dict = snapshot.objectValue {
                // 快照顶层 revision（会话快照可能无 state 键但带顶层 revision——CAS 用）
                if let revision = dict["revision"]?.intValue {
                    conversationStateRevisions[conversationID] = revision
                }
                // 全量 resync 语义标记回收：forceFullResync（base=null）后的第一帧
                // 快照按权威全集整表替换（桌面已移除的行不残留——P2，2026-10-07
                // 审查轮）；常规订阅快照仍按 rowId 键级合并（尾部窗口覆盖，旧行
                // 保留——web 冷/热判定同构）
                let replaceWholeTable = fullResyncSnapshotsPending.remove(conversationID) != nil
                if let rowsArray = dict["rows"]?.arrayValue {
                    var table: [Int: RowRecord] = replaceWholeTable
                        ? [:] : (rows[conversationID] ?? [:])
                    for row in rowsArray {
                        if let rowId = row.objectValue?["rowId"]?.intValue {
                            table[rowId] = RowRecord(rowId: rowId, json: row)
                        }
                    }
                    rows[conversationID] = table
                } else if replaceWholeTable {
                    rows[conversationID] = [:]
                }
                if let state = dict["state"] {
                    snapshotState[conversationID] = state
                    if let revision = state.objectValue?["revision"]?.intValue {
                        conversationStateRevisions[conversationID] = revision
                    }
                    // 一次性取证：CAS baseRevision 的真实来源（快照顶层/state/帧序号；验收后移除）
                    if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
                       UserDefaults.standard.string(forKey: "diag.rev.dump") == nil {
                        let revisionLike = dict.filter { key, _ in
                            key.lowercased().contains("revision") || key.lowercased().contains("epoch") || key.lowercased().contains("seq")
                        }.map { "\($0)=\(String(describing: $1).prefix(40))" }.joined(separator: " ")
                        let stateRevisionLike = state.objectValue?.filter { key, _ in
                            key.lowercased().contains("revision") || key.lowercased().contains("epoch")
                        }.map { "\($0)=\(String(describing: $1).prefix(40))" }.joined(separator: " ")
                        UserDefaults.standard.set(
                            "frameSeq=\(frame.toSeq) top[\(revisionLike)] state[\(stateRevisionLike ?? "nil")]",
                            forKey: "diag.rev.dump")
                        UserDefaults.standard.synchronize()
                    }
                    refreshPendingInteractions(conversationID, state: state)
                    // 重连兜底（用户报障「重连之后审核弹窗没有显示」）：store 重连重建后
                    // pendingInteractions 缓存为空；若快照带 state 但该键缺席（挂起交互
                    // 在断线期间无变化→增量恢复不补发），全量 resync 一次补齐
                    if state.objectValue?["pendingInteractions"] == nil,
                       pendingInteractions[conversationID] == nil,
                       !pendingRecoveryRequested.contains(conversationID) {
                        pendingRecoveryRequested.insert(conversationID)
                        Task { [weak self] in
                            await self?.forceFullResyncForPending(conversationID)
                        }
                    }
                    // workspace hook 审核补拉（web 对齐）：state 声明有 hook 准入
                    // （workspaceHookAdmission 非空——web state schema 键，bundle 取证）
                    // 而 pendingInteractions 缺席/无 hook 交互时，主动
                    // requestWorkspaceHookReview 重发审核（web pending 补拉同路径）。
                    // admission 形状未取证（Vee 在共享 chunk）——digest 宽容读取，
                    // 读不出即静默跳过；每会话每次连接期至多一次。
                    if let admission = state.objectValue?["workspaceHookAdmission"]?.objectValue,
                       admission["bundleDigest"]?.stringValue?.isEmpty == false,
                       currentPendingInteraction(conversationID, kinds: ["workspaceHookReview"]) == nil,
                       !hookReviewRecoveryRequested.contains(conversationID) {
                        hookReviewRecoveryRequested.insert(conversationID)
                        Task { [weak self] in
                            await self?.requestWorkspaceHookReview(conversationID, base: admission)
                        }
                    }
                    // G-008：冷快照必带 workflowRuns（snapshot.ts:497-499——漏这一处，
                    // 刷新/重连后正在跑的 run 会静默消失）；键级整体替换
                    if let runsState = state.objectValue?["workflowRuns"] {
                        applyWorkflowRunsState(conversationID, runsState)
                    }
                    // 面板态（goal/plan/backgroundWorks/subagents）随快照到达
                    yieldPanelState(conversationID)
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
            if let revision = patch["revision"]?.intValue {
                conversationStateRevisions[conversationID] = revision
            }
            refreshPendingInteractions(conversationID, state: merged)
            // G-008：state.updated 的 workflowRuns 键（delta.ts:50；键级整体替换，无深合并）
            if let runsState = patch["workflowRuns"] {
                applyWorkflowRunsState(conversationID, runsState)
            }
            // 面板键（goal/plan/backgroundWorks/subagents/queue）任一到达即广播面板刷新
            if patch["goal"] != nil || patch["plan"] != nil
                || patch["backgroundWorks"] != nil || patch["subagents"] != nil
                || patch["queue"] != nil {
                yieldPanelState(conversationID)
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
        // 会话级模型选择（桌面 composer 改的是 state.modelSelection——workspace 级
        // model-selection.getView/onDidChange 不覆盖会话级变更，chips 同步以此为权威源）
        if let ms = state.objectValue?["modelSelection"], !ms.isNull {
            sessionModelSelections[conversationID] = ms
        }
    }

    /// 会话级模型选择（state.modelSelection：{providerId, modelId, options:{reasoningLevel}}）
    func sessionModelSelection(in conversationID: String) async -> (model: String, thought: String?)? {
        guard let ms = sessionModelSelections[conversationID]?.objectValue else { return nil }
        guard let model = ms["modelId"]?.stringValue ?? ms["model"]?.stringValue else { return nil }
        let thought = ms["options"]?.objectValue?["reasoningLevel"]?.stringValue
            ?? ms["thought"]?.stringValue
        return (model, thought)
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
                    attachments: Self.extractAttachmentRefs(row),
                    // P1-3 编辑重发行游标（userInput 行此前只取 text+attachments；
                    // 仅取 entityId，无 turnId 回退——C-1 修正：游标缺失返回 nil →
                    // UI 不渲染入口、store 拒发，retryTurn 同纪律）
                    entityId: Self.rowEntityId(row),
                    rowKind: "userInput"))
            case "assistantText":
                let state = row["state"]?.stringValue ?? "complete"
                // P1-3 反馈态服务端回读（web 实证 bundle bX(row)：行上 feedback
                // "like"|"dislike"|缺席三态；写命令 setAssistantFeedback 后服务端
                // 行增量回流本字段，重进会话快照行同样携带——缺失即 nil 不虚构）
                let feedback: Bool?
                switch row["feedback"]?.stringValue {
                case "like": feedback = true
                case "dislike": feedback = false
                default: feedback = nil
                }
                result.append(ChatMessage(
                    id: "row-\(rowId)", role: .agent,
                    text: row["text"]?.stringValue ?? "",
                    status: state == "streaming" ? .streaming : .done,
                    timestamp: Date(),
                    // P1-3 助手反馈行游标（仅取 entityId，C-1 修正同上）
                    entityId: Self.rowEntityId(row),
                    rowKind: "assistantText",
                    feedback: feedback))
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
                    entityId: row["entityId"]?.stringValue,
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
        // §11 diag.feedback.*：行级反馈态回读取证（点赞重载丢失修复的读路径验证；
        // 值=「rowId:like/dislike」列表，空列表=该会话行全无 feedback 字段）
        let feedbackRows = result
            .compactMap { message -> String? in
                guard let value = message.feedback else { return nil }
                return "\(message.id):\(value ? "like" : "dislike")"
            }
            .joined(separator: ",")
        UserDefaults.standard.set(
            feedbackRows.isEmpty ? "none" : feedbackRows,
            forKey: "diag.feedback.\(conversationID.prefix(14))")
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

    /// 行元数据游标提取（P1-3：setAssistantFeedback/editUserQuery/retryTurn/forkAssistant
    /// 的 entityId）。C-1 修正（web 一致性审查报告 §三）：web 语义是 entityId 缺失即
    /// 不构造 target、不出入口（bundle 实证 `e.entityId?{rowId,entityId}:null`，
    /// 无 turnId 回退——turnId 冒充 entityId 被服务端拒）。缺失/空串返回 nil。
    nonisolated static func rowEntityId(_ row: [String: JSONValue]) -> String? {
        guard let id = row["entityId"]?.stringValue, !id.isEmpty else { return nil }
        return id
    }

    private func yieldMessageEvents(_ conversationID: String, latest: [ChatMessage]) {
        let previous = lastYieldedMessages[conversationID] ?? []
        // 首帧 / 头部插入（loadOlder 前置历史）/ 收缩：尾部 append 语义表达不了，发全量 replaced
        if previous.isEmpty || previous.first?.id != latest.first?.id || latest.count < previous.count {
            yieldToAll(.messagesReplaced(conversationID: conversationID, messages: latest))
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

    /// sendConversationCommandV4 信封构造：web 端精确形态优先——
    /// {workspacePath, workspaceIdentity?, envelope:{commandId, clientId, type, payload,
    /// issuedAt(epoch ms), revision?}}（bundle: sendConversationCommandV4({...workspace, envelope: t})）。
    /// 实证：envelope.clientId 必须等于连接握手注册值（否则 fault.command.clientMismatch）、
    /// issuedAt 必须 epoch 毫秒数（否则 proto.invalidPayload）、平铺形态服务端直接 TypeError、
    /// CAS 类命令（pauseGoal/switchModelConfig 等）必须携带当前 state revision（否则
    /// "CAS command require base revision"）。平铺形态仅作旧版本兜底重试。
    /// 任务元数据写（setPinned/renameTask/markUnread/clearUnread/deleteTask）的
    /// 归属寻址：web 语义为行自带 workspace——目标会话 workspacePath 优先，未知
    /// 回退连接 workspace（§12-6 收口：跨工作区行的置顶/改名/未读此前落错区被拒）
    private func rowWorkspacePath(_ conversationID: String) -> String {
        sessions[conversationID]?.workspacePath.flatMap { $0.isEmpty ? nil : $0 }
            ?? workspace.path
    }

    /// 写命令的 workspace 寻址目标：目标会话自带 workspacePath 优先（跨工作区会话
    /// 写必须带会话自己的 workspace——web workspaceConnectionRegistry 同构）；
    /// override（createSession 的目标 workspace）次之；归属未知回退连接 workspace。
    /// identity 解析顺序：归属=连接 workspace 时用连接身份；否则查 server-info
    /// 工作区清单；解析不出则不携键（web scope 转换器口径）。
    private func workspaceTarget(for sessionId: String?, overridePath: String?) -> (path: String, identity: String?) {
        let rowPath = sessionId.flatMap { sessions[$0]?.workspacePath }
            .flatMap { $0.isEmpty ? nil : $0 }
        let path = overridePath ?? rowPath ?? workspace.path
        let identity: String?
        if path == workspace.path {
            identity = workspace.workspaceIdentity
        } else {
            identity = allWorkspaces.first { $0.path == path }?.workspaceIdentity
        }
        return (path, identity)
    }

    private func sendCommand(
        _ type: String, sessionId: String?, payload: JSONValue,
        casRevision: Bool = false, workspacePathOverride: String? = nil,
        commandId: String? = nil, timeout: TimeInterval = 30
    ) async -> JSONValue? {
        guard let connection else { return nil }
        // CAS 类命令携当前 state 定位：baseRevision（state.revision）+
        // baseLogEpoch（订阅水位——桌面校验两者必须同时在场，缺一必拒）
        var cas: (revision: Int, logEpoch: String)?
        if casRevision {
            cas = conversationStateRevisions[sessionId ?? ""].flatMap { revision in
                conversationWatermarks[sessionId ?? ""]?.logEpoch
                    .map { (revision: revision, logEpoch: $0) }
            }
        }
        // 信封单点构造（官方 commandFactory.ts:51-70 镜像）：构造即校验（payload
        // strip 规范化 + 可信字段黑名单 + CAS 词表门 + 信封 schema 复核）。本方法是
        // 全仓唯一合法的 sendConversationCommandV4 构造点（AGENTS §5-2）。workspace
        // 信封按目标会话归属寻址（v1.16 实证：此前恒带连接 workspace，跨工作区写全
        // 被桌面拒）；identity 缺席不携键（web scope 转换器同构）
        let target = workspaceTarget(for: sessionId, overridePath: workspacePathOverride)
        let request = ConversationCommandRequest(
            type: type,
            sessionId: sessionId,
            payload: payload,
            casRevision: cas,
            workspacePath: target.path,
            workspaceIdentity: target.identity,
            commandId: commandId ?? UUID().uuidString)
        let built: ConversationCommandFactory.Built
        switch ConversationCommandFactory.build(
            request,
            clientId: connection.registeredClientId,
            issuedAt: Int(Date().timeIntervalSince1970 * 1000)
        ) {
        case .failure(let rejection):
            // 本地拒发（client.casRevisionUnavailable / client.schemaViolation）：
            // 合成拒收回执走调用方既有失败呈现（假成功禁令 §5-12），不空耗注定
            // 被拒或违规的写。schemaViolation 落 diag 供取证（复用 diag.cmd.shape 键）
            if rejection.reasonCode == "client.schemaViolation" {
                UserDefaults.standard.set(
                    "\(type): \(rejection.message)", forKey: "diag.cmd.shape")
            }
            return rejection.syntheticAck
        case .success(let value):
            built = value
        }
        do {
            let ack = try await connection.call(
                "zcode-agent", "sendConversationCommandV4", .json(built.outer), timeout: timeout)
            if let json = ack.jsonValue {
                // 回执 revisionAtDecision 回填为最新 state revision（CAS 后续命令用）
                if let sessionId, let decided = json["revisionAtDecision"]?.intValue, decided > 0 {
                    conversationStateRevisions[sessionId] = decided
                }
                if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                    UserDefaults.standard.set(
                        "type=\(type) ok warnings=\(built.warnings.prefix(2).joined(separator: " | "))",
                        forKey: "diag.cmd.shape")
                }
            }
            _lastCommandErrorText = ""
            return ack.jsonValue
        } catch {
            // 「平铺形态兜底」已移除（2026-10-07 重构）：兜底用新 commandId 重发违反
            // A12 幂等纪律（首次若实际执行仅回执丢失 = 双写）；嵌套形态自 v1.20 起
            // 为多轮探针实证唯一主路（队列 CAS/stop/createSession 全 accepted）
            // 写失败错误原文落地（§5-12：RPCError.message 服务端 fault 原文，禁静默
            // 吞成 nil——此前 createSession 失败只见「创建请求未送达」，真实拒因丢失）
            _lastCommandErrorText = (error as? RPCError)?.message
                ?? String(describing: error).prefix(300).description
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set(
                    "type=\(type) envErr=\(String(describing: error).prefix(300))",
                    forKey: "diag.cmd.err")
                UserDefaults.standard.synchronize()
            }
            return nil
        }
    }

    /// 发送消息：sendText 信封真实下发（桌面端 agent 开跑）+ 本地乐观回显。
    /// 服务端 userInput 行抵达（同文本）后经 rebuildMessages 去重，避免双气泡；
    /// 回显按「发送时已知行数」锚点插入保持时间序（服务端不回推用户行时也不乱序）；
    /// 下发失败（连接断开/边界拦截）撤销回显，如实反馈未送达。
    func send(_ text: String, in conversationID: String) async -> Bool {
        await sendWithAttachments(
            text, attachments: [], requestedDelivery: nil, modelSelection: nil,
            in: conversationID)
    }

    /// 最近一次 sendText 桌面拒因（sendRejectionText 协议读面；成功发送即清除）
    private var sendRejections: [String: String] = [:]

    func sendRejectionText(in conversationID: String) async -> String? {
        sendRejections[conversationID]
    }

    /// sendText 回执判定【实证·上游仓 command.ts commandAckSchema】：status ∈
    /// accepted|rejected|stale|duplicate|noop|failed。经 CommandAck 统一解析
    /// （六态词表 + reasonCode/message——message 兼容普通 fault 文本与 zod issue
    /// 数组两形态；旧桌面缺 status 字段按成功，不破坏现有可用路径）。
    /// 返回 nil = 送达；非 nil = 拒因文案。
    nonisolated static func sendTextRejectionText(_ ack: JSONValue?) -> String? {
        guard ack?.objectValue != nil else { return String(localized: "连接中断，消息未送达") }
        return CommandAck(ack).failureText
    }

    /// 携附件发送（P1-1）：attachments 非空时 sendText payload 附加 `attachments`
    /// 对象数组。B-1（web 对齐）：元素键 `{ref, fileName, mime, bytes}`——bundle 三处
    /// 独立构造同形（上传收口/粘贴/选择器路径）、sendText schema
    /// `attachments: ta(Do).optional()`【移植·bundle 逆向】；首击回执待真机探针。
    /// ack 非 nil 时回执可查；未送达撤销乐观回显 + send 返回 false 如实反馈。
    /// requestedDelivery（P1-2 投递模式联动）：非空时随 sendText payload 下发——
    /// "queue" 形态已探针活体验证（runQueueCASProbeDiag），"guide" 属 sendText
    /// 三路 admission 词表【移植 session-flow.ts】；nil/空串不携带键。A-2 修正：
    /// now 档由调用方恒携 "startNow"（web delivery 枚举【实证】），不再保守缺省。
    /// modelSelection（P0 修复 2026-10-07）：新建会话 draft 路径的会话前选择随首条
    /// sendText 下发【实证·上游仓 command.ts sendText schema modelSelection 可选、
    /// 迁移注释明说第一方发送端显式携带；形状同 firstInput.modelSelection】。
    func sendWithAttachments(
        _ text: String, attachments: [OutgoingAttachment], requestedDelivery: String?,
        modelSelection: NewSessionModelSelection?,
        in conversationID: String) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        let echoID = "local-send-\(UUID().uuidString)"
        let anchor = rows[conversationID]?.count ?? 0
        pendingLocalSends[conversationID, default: [:]][echoID] = (text: trimmed, anchor: anchor)
        let echo = ChatMessage(id: echoID, role: .user, text: trimmed, timestamp: Date())
        messages[conversationID, default: []].append(echo)
        yieldToAll(.messageAppended(conversationID: conversationID, message: echo))
        var payload: [String: JSONValue] = ["text": .string(trimmed)]
        if let requestedDelivery, !requestedDelivery.isEmpty {
            payload["requestedDelivery"] = .string(requestedDelivery)
        }
        if let selection = modelSelection {
            // 形同 firstInput.modelSelection（web U7e）：档位缺席时整个 options 略去
            var selectionPayload: [String: JSONValue] = [
                "providerId": .string(selection.providerId),
                "modelId": .string(selection.modelId),
            ]
            if !selection.reasoningLevel.isEmpty {
                selectionPayload["options"] = .object(
                    ["reasoningLevel": .string(selection.reasoningLevel)])
            }
            payload["modelSelection"] = .object(selectionPayload)
        }
        if !attachments.isEmpty {
            // B-1：元素键 {ref, fileName, mime, bytes}（web wire 同形）
            payload["attachments"] = .array(attachments.map { att in
                .object([
                    "ref": .string(att.ref),
                    "fileName": .string(att.fileName),
                    "mime": .string(att.mime),
                    "bytes": .int(att.bytes),
                ])
            })
        }
        let ack = await sendCommand("sendText", sessionId: conversationID, payload: .object(payload))
        if let rejection = Self.sendTextRejectionText(ack) {
            // 未送达（连接断开/桌面拒收）：撤销乐观回显（在途表同步清除），下次重试
            // 不产生重影；拒因在案供 UI 错误行据实呈现（写面禁假成功 §5.12——
            // rejected 回执此前被当作「任务已发送」，用户侧表现为「发了没有回复」）
            pendingLocalSends[conversationID]?.removeValue(forKey: echoID)
            messages[conversationID]?.removeAll { $0.id == echoID }
            yieldToAll(.messagesReplaced(
                conversationID: conversationID, messages: messages[conversationID] ?? []))
            sendRejections[conversationID] = rejection
            return false
        }
        sendRejections[conversationID] = nil
        // 投影 watchdog（P0-2，官方 expectAcceptedInputProjection 同构：
        // conversationProjectionStore.ts:1247-1274 sendText ACK 后 2s 投影静默 →
        // requestRecovery）：sendText 被桌面接受但 2s 后行表仍无该会话的行
        // （ userInput 行/快照/增量任一未达——真机 sess_e8677b05 帧 workspace 错位
        // 的兜底出口），主动 same-sub 全量 resync 拉一次。幂等：行已增长即放弃。
        scheduleProjectionWatchdog(
            conversationID: conversationID, anchorRows: anchor + 1)
        return true
    }

    /// sendText 投影 watchdog：2s 后行数未越过锚点（服务端 userInput 行未达/快照
    /// 未重放）→ 强制全量 resync 一次。行在 watchdog 窗口内正常到达则无事发生。
    private func scheduleProjectionWatchdog(conversationID: String, anchorRows: Int) {
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let self else { return }
            guard (await self.rows[conversationID]?.count ?? 0) < anchorRows else { return }
            guard let subId = await self.conversationSubscriptionIds[conversationID] else { return }
            UserDefaults.standard.set(
                "sendText accepted 后 2s 投影静默（anchor=\(anchorRows) rows=\(await self.rows[conversationID]?.count ?? -1)）→ resync",
                forKey: "diag.watchdog.\(conversationID.prefix(14))")
            await self.forceFullResync(conversationID: conversationID, subscriptionId: subId)
        }
    }

    /// 停止当前 turn（任务页「停止」入口；RemoteTaskStore 委托至此）。
    /// 统一信封纪律——此前自造平铺信封（clientId 硬编码 + ISO issuedAt + 缺 workspace
    /// 信封）被桌面端拒绝（协议文档 §7.1 反面教材，已修）。
    /// §四口径清理：去掉多余的 reason:"user-requested"（web schema 非 strict 曾被
    /// strip 后 accepted，探针实证无害——现按 web 同形收敛为空 payload）。
    @discardableResult
    func stopTurn(sessionId: String) async -> JSONValue? {
        await sendCommand("stop", sessionId: sessionId, payload: .object([:]))
    }

    /// 提问应答：解析最新 userInput/elicitation 类挂起交互 → resolveInteraction
    /// （interactionId + answer 对象）。A-3 修正：answer 恒为对象——提问族
    /// `{freeText}`（web 实证 bundle：answer schema {optionId?, freeText?, action?,
    /// content?}，裸字符串被 zod expected object 拒）；无挂起交互则静默（无对象可应答）。
    func answerQuestion(_ reply: String, in conversationID: String, questionID: String) async {
        let trimmed = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let interaction = currentPendingInteraction(conversationID, kinds: ["userInput", "elicitation", "question"]) else {
            return
        }
        guard let interactionId = Self.interactionId(of: interaction) else { return }
        await resolveInteractionRaw(
            conversationID, interactionId: interactionId, answer: .object(["freeText": .string(trimmed)]))
    }

    /// 新建会话（去重入口）：同 (标题, 目录, 执行端, 模型选择) 的并发创建合并为
    /// 一次 createSession 下发、共享同一结果（用户报障 2026-10-08「相同请求没有
    /// 做去重拦截」——超时等待窗内重复点按「开始任务」曾产生多份桌面会话，A12
    /// 防双写）。真实流程在 performCreateConversation；请求完成后即出去重窗
    /// （完成后再次提交 = 用户有意的重试，不拦）。
    func createConversation(title: String, directory: String, executor: ExecutorKind,
                            modelSelection: NewSessionModelSelection? = nil) async -> Conversation {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDirectory = directory.trimmingCharacters(in: .whitespacesAndNewlines)
        let dedupKey = [trimmed, trimmedDirectory, executor.rawValue,
                        modelSelection?.providerId ?? "",
                        modelSelection?.modelId ?? "",
                        modelSelection?.reasoningLevel ?? ""].joined(separator: "\u{1F}")
        if let existing = inFlightCreates[dedupKey] {
            return await existing.value
        }
        let empty = Conversation(id: "", title: trimmed.isEmpty ? String(localized: "新会话") : trimmed,
                                 summary: "", directory: directory, updatedAt: Date())
        let task = Task<Conversation, Never> { [weak self] in
            guard let self else { return empty }
            let conversation = await self.performCreateConversation(
                title: title, directory: directory, executor: executor,
                modelSelection: modelSelection)
            await self.clearInFlightCreate(dedupKey)
            return conversation
        }
        inFlightCreates[dedupKey] = task
        return await task.value
    }

    private func clearInFlightCreate(_ key: String) {
        inFlightCreates[key] = nil
    }

    /// 在途创建去重账本（键见 createConversation；任务自清）
    private var inFlightCreates: [String: Task<Conversation, Never>] = [:]

    /// 新建会话实体流程：标题/首条指令非空时以 createSession+firstInput 一次下发（桌面端
    /// 立即开跑首条 turn，边界内允许）；为空时保持 draft 空会话 + 转正写。
    /// modelSelection 非空时随 firstInput.modelSelection 下发（会话前模型选择，
    /// 字段形同 web：{providerId, modelId, options:{reasoningLevel}}）。
    /// 失败如实上抛（返回空 id Conversation + lastCreateFailureText 原文）——
    /// 此前 sessionId 取不到时兜底 `UUID()` 假成功（用户报障「创建之后再电脑端
    /// 看不到」：桌面端 zod 拒收后移动端凭空造了个本地会话），已废除。
    private func performCreateConversation(title: String, directory: String, executor: ExecutorKind,
                                           modelSelection: NewSessionModelSelection? = nil) async -> Conversation {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        // 项 2：directory 参数承载项目层选择（屏 03 项目胶囊）→ createSession workspaceId；
        // 未绑定（空）时回退连接时装配的 workspace（既有口径不变）
        let trimmedDirectory = directory.trimmingCharacters(in: .whitespacesAndNewlines)
        var payload: [String: JSONValue] = [
            "workspaceId": .string(trimmedDirectory.isEmpty ? workspace.path : trimmedDirectory)
        ]
        if !trimmed.isEmpty {
            var firstInput: [String: JSONValue] = ["text": .string(trimmed)]
            if let selection = modelSelection {
                // 形同 web U7e：档位缺席时整个 options 略去（不send空串）
                var selectionPayload: [String: JSONValue] = [
                    "providerId": .string(selection.providerId),
                    "modelId": .string(selection.modelId)
                ]
                if !selection.reasoningLevel.isEmpty {
                    selectionPayload["options"] = .object([
                        "reasoningLevel": .string(selection.reasoningLevel)
                    ])
                }
                firstInput["modelSelection"] = .object(selectionPayload)
            }
            payload["firstInput"] = .object(firstInput)
        }
        guard connection != nil else {
            _lastCreateFailureText = String(localized: "未连接桌面端")
            return Conversation(id: "", title: trimmed.isEmpty ? String(localized: "新会话") : trimmed,
                                summary: "", directory: directory, updatedAt: Date())
        }
        let ack = await sendCommand(
            "createSession", sessionId: nil, payload: .object(payload),
            // 信封 workspacePath 与 workspaceId 同源（web 同构：create 在目标 workspace
            // 里建）——此前恒带连接 workspace，远控上下文绑在其他 workspace 时
            // createSession 被拒（真机报障「新建会话不行/建后找不到」）
            workspacePathOverride: trimmedDirectory.isEmpty ? nil : trimmedDirectory,
            // 创建为重命令（桌面端携 firstInput 立即开跑首条 turn）：30s 缺省预算在
            // 桌面繁忙/中继高延迟下超时（真机报障 2026-10-08「创建超时失败」）→ 60s
            // 专项预算（§9.1.4 历史拉取同族治理）。**不做超时自动重发**（写命令，
            // 回执丢失场景重发有双创建风险——如实失败 + 去重拦截 + 列表自然对账）
            timeout: 60)
        let sessionId = ack?["result"]?.objectValue?["sessionId"]?.stringValue
            ?? ack?["sessionId"]?.stringValue
        guard let sessionId, !sessionId.isEmpty else {
            // createSession 被拒/未送达：如实失败（上游 schema strict——firstInput
            // 载荷任一键不合形即整条拒收，如 providerId 空串 min(1)）。拒因原文上屏
            // （§5-12）：ack==nil = 传输层失败（最近一次 RPCError 原文）；ack 在场 =
            // 服务端六态回执（CommandAck.failureText 组装 reasonCode+message）
            if let ack {
                _lastCreateFailureText = CommandAck(ack).failureText
                    ?? String(localized: "桌面端回执缺少 sessionId（创建被拒，请检查模型选择后重试）")
            } else {
                let transport = await lastCommandErrorText().trimmingCharacters(in: .whitespacesAndNewlines)
                if transport.contains("超时") {
                    // 超时专属口径：请求可能已在桌面端落地（回执未归）——指引查列表，
                    // 不纵容连续重试（配合在途去重，重试语义 = 用户有意为之）
                    _lastCreateFailureText = String(localized: "创建请求超时 · 桌面端可能仍在处理，可稍后在会话列表查看，请勿连续重试")
                } else {
                    _lastCreateFailureText = transport.isEmpty
                        ? String(localized: "创建请求未送达 · 连接中断或桌面端拒收")
                        : String(localized: "创建请求未送达 · \(transport)")
                }
            }
            return Conversation(id: "", title: trimmed.isEmpty ? String(localized: "新会话") : trimmed,
                                summary: "", directory: directory, updatedAt: Date())
        }
        _lastCreateFailureText = ""
        _lastCommandErrorText = ""
        // 新会话即刻入 sessions 表（标题/归属/时间与创建参数同源）——否则列表一重载
        // （conversations() 走 sessions 投影）新建行就消失，直到桌面 sessions-index
        // 推送才回来（真机报障「新建会话后找不到」）
        var created = SessionSummary(
            sessionId: sessionId,
            title: trimmed.isEmpty ? String(localized: "新会话") : trimmed,
            phase: trimmed.isEmpty ? "draft" : "running",
            lastActivityAt: Date(),
            lastAssistantPreview: nil)
        created.workspacePath = trimmedDirectory.isEmpty ? workspace.path : trimmedDirectory
        sessions[sessionId] = created
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

    /// 最近一次 createConversation 失败原文（空串 = 无；连接态新建失败行数据源——
    /// requirement 方法实现，any 存在类型下经协议动态分派到达）
    private var _lastCreateFailureText = ""
    func lastCreateFailureText() async -> String { _lastCreateFailureText }

    /// 最近一次 sendCommand 传输层失败原文（RPCError.message / 本地拒发描述；成功即清）。
    /// 供调用方把「未送达」类笼统文案换成真实拒因（§5-12 写面禁静默吞错）。
    private var _lastCommandErrorText = ""
    func lastCommandErrorText() async -> String { _lastCommandErrorText }

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
        builder.set("workspacePath", rowWorkspacePath(conversationID))
        builder.set("pinned", pinned)
        do {
            _ = try await connection.call("zcode-task", "setTaskPinned", .json(.object(builder.fields)))
            // membership 集同步维护（sessions-index 行不含 pinned 字段——schema 冻结【实证·
            // 上游仓】，写后不等全量刷新；桌面侧 tasks-index.sqlite 为权威，下次 membership
            // 刷新对账）
            if pinned {
                pinnedTaskIds.insert(conversationID)
                archivedTaskIds.remove(conversationID)
            } else {
                pinnedTaskIds.remove(conversationID)
            }
        } catch {
            localPinnedOverrides[conversationID] = previous
            yieldConversationsReplaced()
        }
    }

    /// 归档/取消归档：archiveTask / unarchiveTask（取消归档此前误发 archiveTask，
    /// named gap P1-6 修复）。本地 override 即时反馈 + 归档缓存维护，失败回滚。
    /// ⑥写目标修正（2026-10-06 真机报障「归档的数据没了」）：workspacePath/
    /// workspaceIdentity 取**任务自带归属工作区**（web 同款【移植·bundle 逆向】：
    /// `archiveTask/unarchiveTask {taskId: t.taskId, workspacePath: t.workspacePath,
    /// ...t.workspaceIdentity?{workspaceIdentity}}` 多处同构）——主列表跨工作区后，
    /// 此前误发当前连接 workspace.path，跨区任务的归档落错工作区（或被桌面拒），
    /// 归档区随之查不到。归属反查序：sessions 行（sessions-index/bootstrap 合并）
    /// → listArchivedTasks 行缓存 → 当前工作区兜底（局域网单区行为不变）。
    func setArchived(_ archived: Bool, conversationID: String) async {
        let resolvedPath: String
        if let own = sessions[conversationID]?.workspacePath
            ?? archivedTaskWorkspaces[conversationID]?.path, !own.isEmpty {
            resolvedPath = own
        } else {
            resolvedPath = workspace.path
        }
        let targetIdentity = archivedTaskWorkspaces[conversationID]?.identity
            ?? bootstrapWorkspaces.first { $0.path == resolvedPath }?.workspaceIdentity
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
                    directory: resolvedPath,
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
        builder.set("workspacePath", resolvedPath)
        if let targetIdentity, !targetIdentity.isEmpty {
            builder.set("workspaceIdentity", targetIdentity)
        }
        do {
            if archived {
                _ = try await connection.call("zcode-task", "archiveTask", .json(.object(builder.fields)))
            } else {
                _ = try await connection.call("zcode-task", "unarchiveTask", .json(.object(builder.fields)))
            }
            // 写成功即维护 membership 集（sortedConversations join 数据源；不等下一次全量刷新）
            if archived {
                archivedTaskIds.insert(conversationID)
                pinnedTaskIds.remove(conversationID)
            } else {
                archivedTaskIds.remove(conversationID)
            }
            _lastArchiveFailureText = ""
        } catch {
            // 写失败完整回滚（override + 乐观缓存 + membership）——此前缓存不回滚：
            // 归档区本会话仍显示该行、重启后随缓存蒸发（用户报障「归档的会话又丢了」
            // 第三次的写失败路径），且失败原因不透出（写面禁止静默，§5.11）
            localArchivedOverrides[conversationID] = previous
            localArchivedCache.removeValue(forKey: conversationID)
            archivedTaskIds.remove(conversationID)
            if let rpcError = error as? RPCError {
                _lastArchiveFailureText = "\(rpcError.name): \(rpcError.message)"
            } else {
                _lastArchiveFailureText = String(describing: error).prefix(240).description
            }
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
        builder.set("workspacePath", rowWorkspacePath(conversationID))
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
    /// 写失败回滚本地置位（写面禁静默 §5-12——此前 `_ = try?` 吞错：本地已读、
    /// 快照回弹，用户点按无任何反馈；回滚=可见失败，下次快照真置位会再同步）。
    func markUnread(conversationID: String) async {
        localUnreadFlags[conversationID] = true
        yieldConversationsReplaced()
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", rowWorkspacePath(conversationID))
        builder.set("unread", true)
        do {
            _ = try await connection.call("zcode-task", "setTaskUnread", .json(.object(builder.fields)))
        } catch {
            localUnreadFlags[conversationID] = nil
            yieldConversationsReplaced()
        }
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
        builder.set("workspacePath", rowWorkspacePath(conversationID))
        builder.set("unread", false)
        _ = try? await connection.call("zcode-task", "setTaskUnread", .json(.object(builder.fields)))
    }

    /// 已归档会话（P1-6）：listArchivedTasks 只读拉取 + 本地归档动作缓存合并。
    /// 失败时以本地缓存呈现（本次会话内归档的行不丢）。取证口：diag.archived。
    /// ⑥重写（2026-10-06 真机报障「归档的数据没了」）：拉取范围 = 全工作区（连接层
    /// 采集清单 ∪ bootstrap.tasks 派生 ∪ 当前工作区，按 path 去重），web FTt 同构
    /// 【移植·bundle 逆向】（`e.scopes.map(s => listArchivedTasks(v4(s)))` 逐区并发后
    /// 扁平合并、单区失败跳过）——此前只查当前工作区，主列表是跨工作区的（bootstrap
    /// 合并），其它工作区归档的行从主列表（archived 滤除）与归档区（查不到）同时消失；
    /// 实测 diag.archived：listArchivedTasks(mtt_mobile) 回 `[]` 而用户确有归档行。
    /// 行内 workspaceIdentity 携带口径同 web v4 转换器（`{workspacePath,
    /// workspaceIdentity?}`，身份缺席不携键）。
    func archivedConversations() async -> [Conversation] {
        var result: [String: Conversation] = localArchivedCache
        guard let connection else {
            return result.values.sorted { $0.updatedAt > $1.updatedAt }
        }
        let scopes = Self.membershipScopes(
            all: allWorkspaces, bootstrap: bootstrapWorkspaces, current: workspace)
            .filter { !$0.pinned }
        var fetched = 0
        var firstError: String?
        // 逐 scope **并发**拉取（web `e.scopes.map(s => listArchivedTasks(...))` 同构；
        // 此前串行——26 区串行 RTT 累计数十秒，展开归档区长时间假空态，用户报障
        // 「归档的会话又丢了」的感知成因之一）
        await withTaskGroup(of: (String, [JSONValue], String?).self) { group in
            for scope in scopes {
                group.addTask { [connection] in
                    var builder = JSONObjectBuilder()
                    builder.set("workspacePath", scope.path)
                    if let identity = scope.identity, !identity.isEmpty {
                        builder.set("workspaceIdentity", identity)
                    }
                    do {
                        let result0 = try await connection.call(
                            "zcode-task", "listArchivedTasks", .json(.object(builder.fields)))
                        let dict = result0.jsonValue?.objectValue ?? [:]
                        let items = dict["items"]?.arrayValue ?? dict["tasks"]?.arrayValue
                            ?? result0.jsonValue?.arrayValue ?? []
                        return (scope.path, items, nil)
                    } catch {
                        return (scope.path, [], String(describing: error).prefix(240).description)
                    }
                }
            }
            for await (scopePath, items, error) in group {
                if let error, firstError == nil { firstError = "\(scopePath.suffix(40)): \(error)" }
                fetched += items.count
                for item in items {
                    guard let taskId = item.objectValue?["taskId"]?.stringValue ?? item.objectValue?["sessionId"]?.stringValue else { continue }
                    let summary = SessionSummary.parse(item)
                    // 归属以行自带字段为准（缺席回退查询 scope），unarchive 写目标据此反查
                    let rowPath = summary?.workspacePath
                        ?? item.objectValue?["workspacePath"]?.stringValue
                        ?? scopePath
                    let rowIdentity: String? = (item.objectValue?["workspaceIdentity"]?.stringValue)
                        .flatMap { $0.isEmpty ? nil : $0 }
                    archivedTaskWorkspaces[taskId] = (rowPath, rowIdentity)
                    let conversation = Conversation(
                        id: taskId,
                        title: summary?.title ?? item.objectValue?["title"]?.stringValue ?? "已归档会话",
                        summary: summary?.lastAssistantPreview ?? item.objectValue?["lastAssistantPreview"]?.stringValue ?? String(localized: "已归档"),
                        directory: rowPath,
                        updatedAt: summary?.lastActivityAt ?? Date.distantPast,
                        isArchived: true)
                    result[taskId] = conversation
                }
            }
        }
        // membership 集同步（主列表 isArchived 过滤 join 数据源）
        archivedTaskIds.formUnion(result.keys)
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set(
                "scopes=\(scopes.count) items=\(fetched)"
                    + (firstError.map { " err=\($0)" } ?? "")
                    + " cached=\(localArchivedCache.count)",
                forKey: "diag.archived")
            UserDefaults.standard.synchronize()
        }
        return result.values.sorted { $0.updatedAt > $1.updatedAt }
    }

    /// 删除任务（桌面失败任务「清理」同口径：zcode-task.deleteTask {taskId, workspacePath,
    /// workspaceIdentity?}；归档区删除同款。桌面代执行写，ReadOnlyGate 已放行）
    func deleteTask(_ conversationID: String) async -> Bool {
        guard let connection else { return false }
        var builder = JSONObjectBuilder()
        builder.set("taskId", conversationID)
        builder.set("workspacePath", rowWorkspacePath(conversationID))
        if let identity = workspace.workspaceIdentity, !identity.isEmpty {
            builder.set("workspaceIdentity", identity)
        }
        do {
            _ = try await connection.call(
                "zcode-task", "deleteTask", .json(.object(builder.fields)))
            return true
        } catch {
            return false
        }
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
            .compactMap { element -> RemoteInteractionOption? in
                // A-3（设计稿 §3.2 规则 1）：options 保留 (id,label) 对——应答时原样回传
                // optionId，按钮 label 仅作展示。服务端 options 元素形状【未取证】（现有
                // 解析只见过 label 键），按宽容链：对象取 id/optionId + label/title，
                // 裸字符串 id=label=该串。
                if let text = element.stringValue, !text.isEmpty {
                    return RemoteInteractionOption(id: text, label: text)
                }
                guard let object = element.objectValue else { return nil }
                let id = object["id"]?.stringValue ?? object["optionId"]?.stringValue
                let label = object["label"]?.stringValue ?? object["title"]?.stringValue
                guard let id, !id.isEmpty else { return nil }
                return RemoteInteractionOption(id: id, label: label ?? id)
            }
        // G-017：plan_approval 计划文本（payload.renderContext.plan / 顶层 renderContext / plan 字段）
        let renderContext = payload["renderContext"]?.objectValue
            ?? dict["renderContext"]?.objectValue
        if renderContext?["kind"]?.stringValue == "plan_approval",
           let plan = renderContext?["plan"]?.stringValue, !plan.isEmpty {
            interaction.planText = plan
        } else if let plan = payload["plan"]?.stringValue ?? dict["plan"]?.stringValue, !plan.isEmpty {
            interaction.planText = plan
        }
        // workspace hook 信任审核：payload 宽容解析审核项（列表键/元素键均未取证，
        // 共享 chunk schema——见 parseHookReviewItems 注释）
        if kind == "workspaceHookReview" {
            interaction.hookReviewItems = Self.parseHookReviewItems(payload: payload, dict: dict)
        }
        return interaction
    }

    /// workspaceHookReview 审核项宽容解析。元素 schema（web Hae/Nee 同层）在共享
    /// chunk、webshell2.js 未含【未取证】——列表键链 reviewItems|items|hooks|
    /// reviewFlowItems（payload 优先、顶层次之）；元素 id 链 reviewItemId|id|hookId
    /// （reviewItemId 为 web 应答键实证，排首位）；展示/信任态字段同宽容。
    /// 解析不出任何项 → 空数组（卡片呈「审核项未同步」+ 重发请求入口，不虚构条目）。
    nonisolated static func parseHookReviewItems(
        payload: [String: JSONValue], dict: [String: JSONValue]
    ) -> [WorkspaceHookReviewItem] {
        let rawItems = payload["reviewItems"]?.arrayValue
            ?? payload["items"]?.arrayValue
            ?? payload["hooks"]?.arrayValue
            ?? payload["reviewFlowItems"]?.arrayValue
            ?? dict["reviewItems"]?.arrayValue
            ?? dict["items"]?.arrayValue
        guard let rawItems else { return [] }
        return rawItems.compactMap { element in
            guard let object = element.objectValue else { return nil }
            guard let id = object["reviewItemId"]?.stringValue
                ?? object["id"]?.stringValue
                ?? object["hookId"]?.stringValue,
                !id.isEmpty else { return nil }
            return WorkspaceHookReviewItem(
                id: id,
                title: object["title"]?.stringValue
                    ?? object["name"]?.stringValue
                    ?? object["hookName"]?.stringValue,
                detail: object["summary"]?.stringValue
                    ?? object["description"]?.stringValue
                    ?? object["command"]?.stringValue,
                trustState: object["trustState"]?.stringValue)
        }
    }

    /// 交互 id 宽容解析（id / interactionId / payload.interactionId）
    nonisolated static func interactionId(of json: JSONValue) -> String? {
        guard let dict = json.objectValue else { return nil }
        return dict["id"]?.stringValue
            ?? dict["interactionId"]?.stringValue
            ?? dict["payload"]?.objectValue?["interactionId"]?.stringValue
    }

    /// 交互应答信封下发：resolveInteraction（interactionId + answer）。
    /// A-3 修正（web 实证 bundle）：answer 恒为对象，按交互族分形——权限审批
    /// `{optionId}`（按钮即选项）、计划确认 `{action:"accept"|"decline"|"cancel",
    /// content?}`、提问 `{freeText}` 或 `{optionId}`；payload 恒 `{interactionId,
    /// answer}` 两键，web 调用点即此形态（无顶层平铺——approved/scope/text 是
    /// wire 不存在的键，strip 后 answer 退化空对象 = 假成功）。
    /// 返回命令回执供调用方如实反馈（nil = 未送达；rejected 时携 reasonCode）。
    @discardableResult
    func resolveInteractionRaw(_ conversationID: String, interactionId: String, answer: JSONValue) async -> JSONValue? {
        await sendCommand(
            "resolveInteraction", sessionId: conversationID,
            payload: .object([
                "interactionId": .string(interactionId),
                "answer": answer,
            ]))
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

    /// 会话全部 workflow run（多 run 面板数据源；活 run 优先、同名取最新活 run、stale 仲裁降级）。
    // 数据源合并：通路 B 表（主）+ sessions-index activity 补表外 run（A' 摘要投影/事件重建）。
    // stale 仲裁（实测案例）：表内 run 可能滞留「运行中」而 sessions-index 已遗忘（被取代/
    // 清理）——activity 非空且不含该 id → 降级为 stopped，取消不再打到死 workId
    // （background_task_not_found）。多 run 场景手机端此前只显示第一个（用户实测反馈）。
    // 排序（2026-10-06 用户反馈口径）：活优先；分区内 startedAt/updatedAt 降序（缺席回退
    // 表序倒序）；同名多活仅最新保持活态（supersede 滞留旧 run 不再主导显示）。
    func workflowRuns(in conversationID: String) async -> [WorkflowRunSummary] {
        let wfDiag = UserDefaults.standard.string(forKey: "diag.wf.mode") != nil
        // ⓪ 镜像水合：快照尚未到/本次缺席时，用上次落盘的 run 状态先渲染（有真数据即被覆盖）
        if workflowRunTables[conversationID]?.runs.isEmpty ?? true,
           let mirror = loadWorkflowMirror(conversationID) {
            workflowRunTables[conversationID] = (revision: 0, runs: mirror)
        }
        var results: [WorkflowRunSummary] = []
        if let table = workflowRunTables[conversationID], !table.runs.isEmpty {
            results = table.runs.compactMap(Self.parseWorkflowRun)
        }
        let tableIds = Set(results.map(\.id))
        let activity = Self.parseWorkflowActivity(sessions[conversationID]?.workflowActivity)
        let activityIds = Set(activity?.runs.map(\.id) ?? [])
        // 表外 run 补充：activity 有而表没有（新 run 尚未随 state 快照/增量到达）
        if let activity {
            let firstLiveIndex = activity.runs.firstIndex(where: { $0.isLive })
            for (index, run) in activity.runs.enumerated() where !tableIds.contains(run.id) {
                // 首个活 run 走事件重建（通路 C，完整节点/actors）；其余降级摘要投影
                if index == firstLiveIndex, run.isLive, results.allSatisfy({ !$0.isLive }),
                   let rebuilt = await rebuildWorkflowRunFromEvents(conversationID, runId: run.id) {
                    results.append(rebuilt)
                    continue
                }
                results.append(Self.projectActivityRun(run))
            }
        }
        // stale 仲裁（doc 见函数头）
        if activity != nil, !activityIds.isEmpty {
            results = results.map { run in
                guard run.isLive, !activityIds.contains(run.id) else { return run }
                var demoted = run
                demoted.rawStatus = "stopped"
                return demoted
            }
        }
        // 同一 workflow 取最新活 run（用户真机反馈 2026-10-06：桌面 supersede 换 run 后
        // 旧 run 的 running 状态可能滞留，移动端面板被旧 run 的计划/进度主导）——
        // 同名多活时仅最新活 run 保持活态，其余降级 stopped（不再主导显示，取消命令
        // 也不再打到滞留活态的旧 workId）。「同一 workflow」以 name 相同为宽容近似
        // （runId 不同、同名；改名 supersede 不识别，仅回落到下方排序兜底）。
        let recency: (WorkflowRunSummary) -> Date = { $0.updatedAt ?? $0.startedAt ?? .distantPast }
        var demotedIds: Set<String> = []
        for (_, group) in Dictionary(grouping: results.filter(\.isLive), by: \.name) where group.count > 1 {
            // 最新活 run：时间戳新者优先；时间戳同/缺席按表序靠后者（桌面追加新 run 在尾，
            // workflowRun(in:) runs?.last 同先例）
            let newest = group.enumerated().max { lhs, rhs in
                let l = recency(lhs.element), r = recency(rhs.element)
                if l != r { return l < r }
                return lhs.offset < rhs.offset
            }
            guard let newest else { continue }
            for run in group where run.id != newest.element.id {
                demotedIds.insert(run.id)
            }
        }
        if !demotedIds.isEmpty {
            results = results.map { run in
                guard demotedIds.contains(run.id) else { return run }
                var demoted = run
                demoted.rawStatus = "stopped"
                return demoted
            }
        }
        // 活优先；分区内新 run 在前（startedAt/updatedAt 降序，缺席同分按表序倒序）——
        // 面板首卡即最新活 run，被替换旧 run 沉底为历史（UI 另有「历史」分界标注）
        let sorted = results.enumerated()
            .sorted { lhs, rhs in
                if lhs.element.isLive != rhs.element.isLive { return lhs.element.isLive }
                let l = recency(lhs.element), r = recency(rhs.element)
                if l != r { return l > r }
                return lhs.offset > rhs.offset
            }
            .map(\.element)
        if wfDiag {
            UserDefaults.standard.set(
                "multi 表=\(tableIds.count) activity=\(activityIds.count) → \(sorted.map { "\($0.id.prefix(14)):\($0.rawStatus)" }.joined(separator: ", "))",
                forKey: "diag.wf.multi")
            if !workflowApiDiagDone.contains(conversationID),
               let primary = sorted.first(where: \.isLive) ?? sorted.first {
                workflowApiDiagDone.insert(conversationID)
                Task { await self.workflowApiDiagDump(conversationID: conversationID, runId: primary.id) }
            }
        }
        return sorted
    }

    /// 通路 A 摘要投影（阶段链 only；列表内 activity-only run 用）
    nonisolated static func projectActivityRun(_ run: SessionWorkflowRunSummary) -> WorkflowRunSummary {
        WorkflowRunSummary(
            id: run.id,
            name: run.name ?? String(localized: "工作流"),
            rawStatus: run.rawStatus,
            stopReason: nil,
            resumable: false,
            truncated: false,
            nodes: run.phases.map {
                WorkflowNodeSummary(id: $0.id, label: $0.name, status: $0.status)
            },
            actors: [])
    }

    func workflowRun(in conversationID: String) async -> WorkflowRunSummary? {
        let wfDiag = UserDefaults.standard.string(forKey: "diag.wf.mode") != nil
        // 多源列表命中 → 首个（活优先）即为主 run
        if let primary = await workflowRuns(in: conversationID).first {
            if wfDiag {
                UserDefaults.standard.set(
                    "path1 命中 → name=\(primary.name) status=\(primary.rawStatus) nodes=\(primary.nodes.count) actors=\(primary.actors.count)",
                    forKey: "diag.wf.path")
            }
            return primary
        }
        if wfDiag {
            UserDefaults.standard.set(
                "path1 空 · 表=\(workflowRunTables[conversationID]?.runs.count ?? 0)",
                forKey: "diag.wf.path")
        }
        // ② 单数键旧兼容
        if let cached = workflowRunStates[conversationID] {
            return Self.parseWorkflowRun(cached)
        }
        // ②.5 终极自愈：表空 + 无缓存（快照 state.workflowRuns 间歇缺席）→ 一次性发
        // resync(base:null) 重取全量快照（含 state），帧到后经 applyWorkflowRunsState
        // 填表+落镜像；2.5s 后 nudge 一次 UI 刷新让 observe 重查。置于 ③ 之前：
        // RPC 抛错/空表/解析失败等一切后续路径都能被兜住
        if !workflowResyncTriggered.contains(conversationID),
           conversationSubscriptionIds[conversationID] != nil {
            workflowResyncTriggered.insert(conversationID)
            if wfDiag {
                UserDefaults.standard.set("触发表缺失全量 resync", forKey: "diag.wf.path")
            }
            let subId = conversationSubscriptionIds[conversationID]
            Task { [weak self] in
                guard let self, let subId else { return }
                await self.forceFullResync(conversationID: conversationID, subscriptionId: subId)
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                await self.nudgeWorkflowRefresh(conversationID: conversationID)
            }
        }
        // ③ 只读 RPC 兜底（一次，失败不重复）
        guard let connection, !workflowRunFetched.contains(conversationID) else { return nil }
        workflowRunFetched.insert(conversationID)
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: conversationID)
        builder.set("workspacePath", workspace.path)
        guard let result = try? await connection.call(
            "zcode-agent", "conversationWorkflowRunsV4", .json(.object(builder.fields))) else {
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set("RPC 兜底调用失败", forKey: "diag.wf.rpc")
            }
            return nil
        }
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set("RPC 响应: \(String(describing: result.jsonValue).prefix(1200))", forKey: "diag.wf.rpc")
            if !workflowApiDiagDone.contains(conversationID) {
                workflowApiDiagDone.insert(conversationID)
                Task { await self.workflowApiDiagDump(conversationID: conversationID, runId: "") }
            }
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

    /// 无水位全量 resync（服务端重发全量快照，含 state.workflowRuns）
    /// 重连 pending 兜底去重（每会话每次连接期最多一次全量补拉）
    private var pendingRecoveryRequested: Set<String> = []
    /// workspace hook 审核补拉去重（同口径：每会话每次连接期最多一次
    /// requestWorkspaceHookReview 主动补拉）
    private var hookReviewRecoveryRequested: Set<String> = []

    /// pending 兜底：base null 全量 resync（快照必带 state.pendingInteractions）；
    /// 订阅缺失时先走既有自愈链
    private func forceFullResyncForPending(_ conversationID: String) async {
        if conversationSubscriptionIds[conversationID] == nil {
            await ensureConversationSubscribed(conversationID)
        }
        guard let subId = conversationSubscriptionIds[conversationID] else { return }
        await forceFullResync(conversationID: conversationID, subscriptionId: subId)
    }

    private func forceFullResync(conversationID: String, subscriptionId: String) async {
        guard let connection else { return }
        var builder = JSONObjectBuilder()
        builder.set("subscriptionId", subscriptionId)
        builder.set("base", JSONValue.null)
        // workspace 信封必须与订阅一致【实证·上游仓 zcodeAgentConnectionScope.resyncOwned：
        // ownership 按 `workspaceKey(params)` 匹配订阅登记——订阅改按会话归属寻址后，
        // resync 缺 workspace/带错 workspace 都会 fault.subscription.notOwned】
        applySessionTarget(&builder, sessionID: conversationID)
        // 全量语义标记：下一帧快照 rows 整表替换（handleConversationFrame 回收）
        fullResyncSnapshotsPending.insert(conversationID)
        _ = try? await connection.call(
            "zcode-agent", "resyncConversationV4", .json(.object(builder.fields)))
    }

    /// resync 帧处理后 nudge：全量替换一次现有消息（幂等），驱动 observe 重查 workflowRun
    private func nudgeWorkflowRefresh(conversationID: String) {
        yieldToAll(.messagesReplaced(
            conversationID: conversationID, messages: messages[conversationID] ?? []))
    }

    /// workflowRuns 状态键 → 表（整键替换；无 runs 数组形态忽略）
    private func applyWorkflowRunsState(_ conversationID: String, _ value: JSONValue) {
        guard let dict = value.objectValue,
              let runs = dict["runs"]?.arrayValue else {
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
                UserDefaults.standard.set("state.workflowRuns 形态不合: \(String(describing: value).prefix(200))", forKey: "diag.wf.state")
            }
            return
        }
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            UserDefaults.standard.set("state.workflowRuns runs=\(runs.count) revision=\(dict["revision"]?.intValue ?? -1) sample=\(runs.first.map { String(describing: $0).prefix(400) } ?? "nil")", forKey: "diag.wf.state")
        }
        workflowRunTables[conversationID] = (
            revision: dict["revision"]?.intValue ?? 0,
            runs: runs
        )
        persistWorkflowMirror(conversationID, runs)
        // 取证门槛：表一填充即 dump 一次（不依赖 observe 的再查询时机）
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
           !workflowApiDiagDone.contains(conversationID) {
            let runId = runs.last?.objectValue?["runId"]?.stringValue
                ?? runs.last?.objectValue?["id"]?.stringValue
            if let runId {
                workflowApiDiagDone.insert(conversationID)
                Task { await self.workflowApiDiagDump(conversationID: conversationID, runId: runId) }
            }
        }
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
            persistWorkflowMirror(conversationID, table.runs)
        }
    }

    /// 工作流状态镜像（快照 state.workflowRuns 间歇缺席自愈；同 server.configs.mirror 模式）：
    /// 快照/增量一到即落盘（截尾 4 条），冷启动表空时水合，面板不再依赖投递时序
    private func persistWorkflowMirror(_ conversationID: String, _ runs: [JSONValue]) {
        let capped = Array(runs.suffix(4))
        guard let data = try? JSONEncoder().encode(capped),
            !data.isEmpty else { return }
        UserDefaults.standard.set(data, forKey: "wf.runs.mirror.\(conversationID)")
    }

    private func loadWorkflowMirror(_ conversationID: String) -> [JSONValue]? {
        guard let data = UserDefaults.standard.data(forKey: "wf.runs.mirror.\(conversationID)"),
              let runs = try? JSONDecoder().decode([JSONValue].self, from: data),
              !runs.isEmpty else { return nil }
        return runs
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

    /// run 时间字段双形态宽容解析（lastActivityAt 同口径：毫秒数优先，ISO 字符串兜底；
    /// startedAt/updatedAt 未在桌面 §10 词表取证——缺席返回 nil，排序回退表序）
    nonisolated static func workflowRunTimestamp(
        _ dict: [String: JSONValue], _ key: String
    ) -> Date? {
        // 数值时间戳：>1e11 视为毫秒（含带小数的毫秒），>1e8 视为秒；更小值不认（防脏数据）
        if let value = dict[key]?.doubleValue, value > 0 {
            if value > 100_000_000_000 { return Date(timeIntervalSince1970: value / 1000) }
            if value > 100_000_000 { return Date(timeIntervalSince1970: value) }
        }
        if let iso = dict[key]?.stringValue {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: iso) { return date }
            return ISO8601DateFormatter().date(from: iso)
        }
        return nil
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
            let tasksTotal = d["tasksTotal"]?.intValue
                ?? d["nodesTotal"]?.intValue
                ?? d["stations"]?.intValue
            let tasksSettled = d["tasksSettled"]?.intValue
                ?? d["nodesSettled"]?.intValue
            return WorkflowActorSummary(
                id: "\(siteId)#\(ordinal)",
                name: d["name"]?.stringValue,
                rawStatus: d["status"]?.stringValue ?? "waiting",
                phaseName: d["phaseName"]?.stringValue,
                sessionId: d["sessionId"]?.stringValue ?? d["childSessionId"]?.stringValue,
                tasksTotal: tasksTotal,
                tasksSettled: tasksSettled)
        }

        guard !nodes.isEmpty || !actors.isEmpty else { return nil }

        // 无站/无戳兜底【实证·上游仓 instance-phases.ts phasesOf + workflow-graph
        // withImplicitPhase 同构，2026-10-08 取证】：
        // ① 全程无站（旧 CLI 无 phase() 标记）但有实例 → 合成隐式「工作流」单站承接，
        //    否则实例无处渲染、面板只剩头部；
        // ② 有站且有词汇（任一节点/实例带 phaseName 出生戳）、存在无戳实例（首个标记
        //    前出生，或旧 CLI 不带戳）→ 表头合成「未分组」站承接——无戳 ↔ 无名站是
        //    同一事实的两面。
        let hasVocabulary = actors.contains { $0.phaseName != nil }
            || rawNodes.contains { $0.objectValue?["phaseName"]?.stringValue != nil }
        var finalNodes = nodes
        if finalNodes.isEmpty, !actors.isEmpty {
            finalNodes.append(WorkflowNodeSummary(
                id: "station-implicit",
                label: String(localized: "工作流"),
                status: WorkflowStepStatus.mapRunStatus(rawStatus)))
        } else if hasVocabulary, !finalNodes.isEmpty,
                  actors.contains(where: { $0.phaseName == nil }) {
            let unphased = actors.filter { $0.phaseName == nil }
            let unphasedStatus: WorkflowStepStatus
            if unphased.contains(where: { $0.status == .running }) {
                unphasedStatus = .running
            } else if unphased.contains(where: { $0.status == .failed }) {
                unphasedStatus = .failed
            } else if unphased.allSatisfy({ $0.status == .done }) {
                unphasedStatus = .done
            } else {
                unphasedStatus = .pending
            }
            finalNodes.insert(
                WorkflowNodeSummary(
                    id: WorkflowRunSummary.unphasedStationID,
                    label: String(localized: "未分组"),
                    status: unphasedStatus),
                at: 0)
        }

        // 子代理模型宽容解析（字符串直传；对象形态取 modelId|name|id）
        let subagentModel: String? = {
            switch dict["subagentModel"] {
            case .string(let s): return s
            case .object(let o):
                return o["modelId"]?.stringValue ?? o["name"]?.stringValue ?? o["id"]?.stringValue
            default: return nil
            }
        }()
        // workId：取消/恢复/设置命令的定位键（web 端 resumeWorkflowRun 以 runId 充当
        // workId；run 对象自带 workId 字段时优先）
        let workId = dict["workId"]?.stringValue ?? id
        return WorkflowRunSummary(
            id: id,
            name: name,
            rawStatus: rawStatus,
            stopReason: dict["stopReason"]?.stringValue,
            resumable: dict["resumable"]?.boolValue ?? false,
            truncated: dict["truncated"]?.boolValue ?? false,
            nodes: finalNodes,
            actors: actors,
            artifactsCount: dict["artifacts"]?.arrayValue?.count ?? 0,
            pendingQuestionsCount: dict["pendingQuestions"]?.arrayValue?.count ?? 0,
            concurrency: dict["concurrency"]?.intValue
                ?? dict["concurrency"]?.objectValue?["active"]?.intValue,
            concurrencyCeiling: dict["concurrencyCeiling"]?.intValue
                ?? dict["concurrency"]?.objectValue?["ceiling"]?.intValue
                ?? dict["maxConcurrency"]?.intValue,
            subagentModel: subagentModel,
            cancellable: dict["cancellable"]?.boolValue ?? true,
            workId: workId,
            hasPhaseVocabulary: hasVocabulary,
            startedAt: workflowRunTimestamp(dict, "startedAt"),
            updatedAt: workflowRunTimestamp(dict, "updatedAt"))
    }

    // MARK: 会话面板（goal / plan / btw 后台工作 / side 子代理）
    //
    // 数据源：conversation state.{goal,plan,backgroundWorks,subagents} 只读投影
    // （桌面 state patch schema 同键；快照/state.updated 键级替换，applyDelta 统一
    // 广播 panelStateUpdated）。控制面：pauseGoal/resumeGoal/cancelBackgroundWork/
    // resumeWorkflowRun/amendWorkflowRunSettings 全部走 sendConversationCommandV4
    // （web 端 v4-pane 同构造；ReadOnlyGate command 类放行）。

    /// 面板态变更广播（快照 state 与 state.updated 应用点各一处）
    private func yieldPanelState(_ conversationID: String) {
        // 一次性面板字段取证（diag.wf.mode 开启时）：goal/plan/works/subagents 原始 JSON
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
           !workflowApiDiagDone.contains("panels:\(conversationID)") {
            workflowApiDiagDone.insert("panels:\(conversationID)")
            let state = snapshotState[conversationID]?.objectValue ?? [:]
            let keys = state.keys.sorted().joined(separator: ",")
            UserDefaults.standard.set(
                "keys=\(keys) goal=\(String(describing: state["goal"]).prefix(300)) plan=\(String(describing: state["plan"]).prefix(300)) works=\(String(describing: state["backgroundWorks"]).prefix(300)) subs=\(String(describing: state["subagents"]).prefix(300))",
                forKey: "diag.state.panels")
        }
        yieldToAll(.panelStateUpdated(conversationID: conversationID))
    }

    func goalSummary(in conversationID: String) async -> RemoteGoalSummary? {
        guard let goal = snapshotState[conversationID]?.objectValue?["goal"] else { return nil }
        if case .null = goal { return nil }
        guard let d = goal.objectValue else { return nil }
        let text = d["text"]?.stringValue
            ?? d["description"]?.stringValue
            ?? d["content"]?.stringValue
            ?? d["prompt"]?.stringValue
            ?? d["goal"]?.stringValue
        guard let text, !text.isEmpty else { return nil }
        let status = d["status"]?.stringValue
        let paused = d["paused"]?.boolValue ?? (status == "paused" || status == "pausing")
        return RemoteGoalSummary(text: text, rawStatus: status, isPaused: paused)
    }

    func planPanel(in conversationID: String) async -> PlanPanelSummary? {
        guard let plan = snapshotState[conversationID]?.objectValue?["plan"] else { return nil }
        if case .null = plan { return nil }
        guard let d = plan.objectValue else { return nil }
        // 正文多形态：content/text/markdown/body 直取；entries/ steps 数组逐行拼
        var content = d["content"]?.stringValue
            ?? d["text"]?.stringValue
            ?? d["markdown"]?.stringValue
            ?? d["body"]?.stringValue
        if content == nil || content?.isEmpty == true {
            let entries = d["entries"]?.arrayValue ?? d["steps"]?.arrayValue ?? []
            let lines = entries.compactMap { e -> String? in
                guard let o = e.objectValue else { return e.stringValue }
                let title = o["title"]?.stringValue ?? o["text"]?.stringValue ?? ""
                let done = o["status"]?.stringValue == "done" || o["completed"]?.boolValue == true
                return (done ? "✓ " : "· ") + title
            }
            content = lines.isEmpty ? nil : lines.joined(separator: "\n")
        }
        guard let content, !content.isEmpty else { return nil }
        return PlanPanelSummary(
            title: d["title"]?.stringValue ?? d["name"]?.stringValue,
            content: content,
            rawStatus: d["status"]?.stringValue)
    }

    func backgroundWorks(in conversationID: String) async -> [BackgroundWorkSummary] {
        let raw = snapshotState[conversationID]?.objectValue?["backgroundWorks"]
        let array = raw?.arrayValue ?? raw?.objectValue?["works"]?.arrayValue ?? []
        return array.compactMap { item in
            guard let d = item.objectValue else { return nil }
            guard let workId = d["workId"]?.stringValue ?? d["id"]?.stringValue else { return nil }
            let status = d["status"]?.stringValue ?? d["state"]?.stringValue
            return BackgroundWorkSummary(
                workId: workId,
                title: d["title"]?.stringValue ?? d["name"]?.stringValue ?? d["kind"]?.stringValue,
                kind: d["kind"]?.stringValue ?? d["type"]?.stringValue,
                rawStatus: status,
                cancellable: d["cancellable"]?.boolValue ?? (status == "running"),
                resumable: d["resumable"]?.boolValue ?? false,
                runId: d["runId"]?.stringValue,
                sessionId: d["sessionId"]?.stringValue ?? d["childSessionId"]?.stringValue,
                startedAt: Self.workflowRunTimestamp(d, "startedAt"),
                endedAt: Self.workflowRunTimestamp(d, "endedAt"),
                blocked: d["blocked"]?.boolValue ?? false)
        }
    }

    func subagentSessions(in conversationID: String) async -> [SubagentSessionSummary] {
        let raw = snapshotState[conversationID]?.objectValue?["subagents"]
        // 桌面形态 {running:[...]}（web 端 subagents?.running.map(childSessionId)）；兼容裸数组
        let array = raw?.objectValue?["running"]?.arrayValue
            ?? raw?.objectValue?["all"]?.arrayValue
            ?? raw?.arrayValue ?? []
        return array.compactMap { item in
            guard let d = item.objectValue,
                  let childSessionId = d["childSessionId"]?.stringValue ?? d["sessionId"]?.stringValue
            else { return nil }
            return SubagentSessionSummary(
                childSessionId: childSessionId,
                agentId: d["agentId"]?.stringValue,
                agentType: d["agentType"]?.stringValue ?? d["type"]?.stringValue,
                name: d["name"]?.stringValue ?? d["agentName"]?.stringValue,
                rawStatus: d["status"]?.stringValue ?? "running")
        }
    }

    /// 目标暂停/继续（pauseGoal / resumeGoal；CAS 类命令必须携带当前 state revision）。
    /// 返回命令回执（nil = 未送达；回执内 status/error 供调用方反馈）
    @discardableResult
    func setGoalPaused(_ paused: Bool, conversationID: String) async -> JSONValue? {
        await sendCASWithRetry(
            paused ? "pauseGoal" : "resumeGoal", sessionId: conversationID,
            payload: .object([:]))
    }

    /// 取消后台工作（cancelBackgroundWork {workId}；workflow run 的 workId 即 runId）
    @discardableResult
    func cancelWork(_ conversationID: String, workId: String) async -> JSONValue? {
        await sendCommand(
            "cancelBackgroundWork", sessionId: conversationID,
            payload: .object(["workId": .string(workId)]))
    }

    /// 恢复工作流运行（resumeWorkflowRun {workId, name?}）
    @discardableResult
    func resumeWorkflowRun(_ conversationID: String, workId: String, name: String?) async -> JSONValue? {
        var payload: [String: JSONValue] = ["workId": .string(workId)]
        if let name, !name.isEmpty { payload["name"] = .string(name) }
        return await sendCommand("resumeWorkflowRun", sessionId: conversationID, payload: .object(payload))
    }

    /// 工作流运行设置（amendWorkflowRunSettings；nil 参数不携带该键，.some(nil) 显式置 null）
    @discardableResult
    func amendWorkflowRunSettings(
        _ conversationID: String, workId: String,
        subagentModel: String??, maxConcurrency: Int??) async -> JSONValue? {
        var payload: [String: JSONValue] = ["workId": .string(workId)]
        if let model = subagentModel {
            payload["subagentModel"] = model.map { .string($0) } ?? .null
        }
        if let limit = maxConcurrency {
            payload["maxConcurrency"] = limit.map { .int($0) } ?? .null
        }
        return await sendCommand("amendWorkflowRunSettings", sessionId: conversationID, payload: .object(payload))
    }

    // MARK: 排队消息（桌面 composer pending 队列；state.queue 投影 + 队列命令面）

    /// 排队队列只读投影（state.queue：{items:[{queueItemId, text, delivery{admitted}}], autoDrain}）
    func queueInfo(in conversationID: String) async -> ConversationQueueInfo? {
        guard let queue = snapshotState[conversationID]?.objectValue?["queue"] else { return nil }
        if case .null = queue { return nil }
        guard let d = queue.objectValue else { return nil }
        let items: [RemoteQueueItem] = (d["items"]?.arrayValue ?? []).compactMap { item in
            guard let o = item.objectValue else { return nil }
            guard let id = o["queueItemId"]?.stringValue
                ?? o["itemId"]?.stringValue
                ?? o["id"]?.stringValue else { return nil }
            let text = o["text"]?.stringValue
                ?? o["inputText"]?.stringValue
                ?? o["newText"]?.stringValue
                ?? ""
            let admitted = o["delivery"]?.objectValue?["admitted"]?.stringValue
            return RemoteQueueItem(id: id, text: text, admitted: admitted)
        }
        guard !items.isEmpty else { return nil }
        return ConversationQueueInfo(items: items, autoDrain: d["autoDrain"]?.boolValue ?? true)
    }

    /// CAS 命令发送 + stale 一次重试（探针实证 2026-10-06：队列准入/排空会在两条
    /// 命令间推进 revision，回填值落后即 `proto.staleRevision`/status "stale"；
    /// stale 回执自带最新 revisionAtDecision——sendCommand 已回填，原样重发一次即命中）
    /// CAS 命令发送 + stale 收敛自愈（官方 sendHostCasCommandV4 的探测-收敛循环
    /// 镜像）。**前置公共步已内建**（2026-10-07 重构下沉）：revision 缺失先
    /// resync+轮询——此前依赖调用点各自 ensureStateRevision，pauseGoal/retryTurn
    /// 漏接导致冷启动首击必撞本地拒发门（P1③）；commandId 全程不变（A12 幂等：
    /// 上游同 (sessionId, commandId) 重试回放 duplicate，不构成双写）。
    private func sendCASWithRetry(
        _ type: String, sessionId: String, payload: JSONValue) async -> JSONValue? {
        // 前置公共步（幂等：revision 在案即返回）
        await ensureStateRevision(sessionId)
        let commandId = UUID().uuidString
        var ack = await sendCommand(
            type, sessionId: sessionId, payload: payload, casRevision: true,
            commandId: commandId)
        guard ack?["status"]?.stringValue == "stale" else { return ack }
        // 原样重发一次（revisionAtDecision 已回填最新 revision——探针实证口径）
        ack = await sendCommand(
            type, sessionId: sessionId, payload: payload, casRevision: true,
            commandId: commandId)
        guard ack?["status"]?.stringValue == "stale" else { return ack }
        // 活跃会话兜底（用户报障 2026-10-07「胶囊切换都不行」：agent 运行中 revision
        // 持续前进，重发窗口内可再次 stale）——强制 resync 取权威 revision 后末次重发
        conversationStateRevisions[sessionId] = nil
        await triggerResync(sessionId)
        await ensureStateRevision(sessionId)
        return await sendCommand(
            type, sessionId: sessionId, payload: payload, casRevision: true,
            commandId: commandId)
    }

    /// CAS 前置公共步：revision 缺失（冷启动刚进会话/快照未带 state）先 resync
    /// 拉带 state 的快照并轮询等待（帧异步，固定 0.9s 曾不够）。探针实证：队列
    /// 命令缺此步时冷启动首击必被拒（"CAS commands require baseRevision and
    /// baseLogEpoch"）——switchModelConfig 同款前置，队列五件统一接入。
    /// 直发 forceFullResync 而非 triggerResync（后者 5s 节流——连续 CAS 场景
    /// ensureStateRevision 会被节流吞成纯等待，revision 永远等不到）；等待中段
    /// 再补发一次（首包快照与 resync 帧竞态的双保险）。
    private func ensureStateRevision(_ conversationID: String) async {
        guard conversationStateRevisions[conversationID] == nil else { return }
        if let subId = conversationSubscriptionIds[conversationID] {
            await forceFullResync(conversationID: conversationID, subscriptionId: subId)
        }
        for round in 0..<10 {
            if conversationStateRevisions[conversationID] != nil { break }
            try? await Task.sleep(nanoseconds: 300_000_000)
            if round == 4, conversationStateRevisions[conversationID] == nil,
               let subId = conversationSubscriptionIds[conversationID] {
                await forceFullResync(conversationID: conversationID, subscriptionId: subId)
            }
        }
    }

    /// 队列条目立即发送（CAS 类，携 state revision）
    @discardableResult
    func sendQueuedNow(_ conversationID: String, queueItemId: String) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "sendQueuedNow", sessionId: conversationID,
            payload: .object(["queueItemId": .string(queueItemId)]))
    }

    /// 队列条目文本编辑（CAS 类——桌面四件队列命令全部携 revision，缺一被拒）
    @discardableResult
    func editQueueItem(_ conversationID: String, queueItemId: String, newText: String) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "editQueueItem", sessionId: conversationID,
            payload: .object(["queueItemId": .string(queueItemId), "newText": .string(newText)]))
    }

    /// 队列条目删除（CAS 类）
    @discardableResult
    func deleteQueueItem(_ conversationID: String, queueItemId: String) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "deleteQueueItem", sessionId: conversationID,
            payload: .object(["queueItemId": .string(queueItemId)]))
    }

    /// 队列条目重排（beforeQueueItemId=nil = 移到队尾；CAS 类——置顶报
    /// "CAS commands require baseRevision" 的根因就是漏携）
    @discardableResult
    func reorderQueueItem(_ conversationID: String, queueItemId: String, beforeQueueItemId: String?) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "reorderQueueItem", sessionId: conversationID,
            payload: .object([
                "queueItemId": .string(queueItemId),
                "beforeQueueItemId": beforeQueueItemId.map { .string($0) } ?? .null,
            ]))
    }

    /// 自动排空开关（CAS 类）
    @discardableResult
    func setAutoDrain(_ conversationID: String, enabled: Bool) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "setAutoDrain", sessionId: conversationID,
            payload: .object(["autoDrain": .bool(enabled)]))
    }

    // MARK: v4 命令扩容（发送层；P1/P2 波次 UI 的命令数据源）
    //
    // 边界口径不变：全部「客户端发命令、桌面代执行」（ReadOnlyGate 对 v4 type 仅拦
    // applyFileRewind，本节 type 构造性放行；ReadOnlyGateTests 已断言其中
    // editUserQuery/compact/sendGoalCommand/startSavedWorkflow）。payload 形状除逐条
    // 注明【实证】外均为【宽容】：协议文档 §7.2 只记词表、字段名零取证（桌面端不在线
    // 无法活体补证），键名取仓内最强先例构造——接入 UI 前先探针定形（P1P2P3功能UI设计.md
    // 各节「实施第一步即探针」口径），禁止把本节键名当唯一真相回写文档。

    /// 协作模式切换（§2 设计稿「会话模式」胶囊）。§7.2:414 CAS 权威全集
    /// 成员【移植级】——ensureStateRevision + sendCASWithRetry（新 CAS 命令沿用纪律）。
    /// payload {mode}【实证 web bundle】：`switchCollaborationMode:Ta({mode:La([
    /// \`build\`,\`edit\`,\`plan\`,\`yolo\`])})`——4 档全集（Qme：build=Ask before
    /// changes / edit=Edit automatically / plan=Plan mode / yolo=Full access）；
    /// 键名 mode 同源实证。
    @discardableResult
    func switchCollaborationMode(_ conversationID: String, mode: String) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "switchCollaborationMode", sessionId: conversationID,
            payload: .object(["mode": .string(mode)]))
    }

    /// 投递/跟随模式（§2 设计稿投递 Menu：立即/排队/引导）。CAS 权威全集成员。
    /// A-2 修正（web 实证 bundle）：mode 枚举仅 `queue|guide`——「立即」不是
    /// followupMode 值（web 默认 queue，立即语义由 sendText 逐消息
    /// requestedDelivery:"startNow" 承载）。now 档由 ChatViewModel 拦截不下发
    /// （仅本地态持久化），本方法只应收到 queue/guide。
    /// payload {mode}【宽容：键名未取证】。
    @discardableResult
    func setFollowupMode(_ conversationID: String, mode: String) async -> JSONValue? {
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "setFollowupMode", sessionId: conversationID,
            payload: .object(["mode": .string(mode)]))
    }

    /// 助手消息轻反馈（§3 设计稿点赞/点踩反馈行）。row-target + CAS 双类成员
    /// （§7.2:414）。游标照 retryTurn 先例 {target:{rowId, entityId}}；value 三态：
    /// true=赞 / false=踩 / nil=取消（置 null）。A-1 修正（web 实证 bundle）：
    /// feedback 值域 `like|dislike`（可 null）——原 positive/negative 是协议文档
    /// 「未取证」候选被当事实实现，zod invalid_enum_value 全拒。entityId 缺失拒发
    /// （retryTurn 同纪律：行元数据精确游标缺失不得虚构）。
    @discardableResult
    func setAssistantFeedback(
        _ conversationID: String, rowId: Int, entityId: String?, value: Bool?) async -> JSONValue? {
        guard let entityId, !entityId.isEmpty else { return nil }
        await ensureStateRevision(conversationID)
        var payload: [String: JSONValue] = ["target": .object([
            "rowId": .int(rowId),
            "entityId": .string(entityId),
        ])]
        payload["feedback"] = value.map { .string($0 ? "like" : "dislike") } ?? .null
        return await sendCASWithRetry(
            "setAssistantFeedback", sessionId: conversationID, payload: .object(payload))
    }

    /// 编辑用户消息并重发（§3 设计稿「编辑重发」sheet；§7.2:404「rewind 后以新文本
    /// 重发，与 retryTurn 同族」——CAS + row-target 双类）。游标 = 消息行 rowId +
    /// 行元数据 entityId（CAS 双字段 baseRevision/baseLogEpoch 由信封层自取，非本 API
    /// 参数）；新文本键名取同族实证先例 editQueueItem 的 newText【宽容：editUserQuery
    /// 本体 payload 未取证，"text" 为备选】。C-2 修正：显式携 workspaceMode:
    /// "preserve"（web 实证 bundle——`preserve|rewind` 枚举、默认 preserve，web 调用
    /// 点恒带该键；rewind 语义入口接入时再切换）。entityId 缺失拒发（retryTurn 同纪律）。
    @discardableResult
    func editUserQuery(
        _ conversationID: String, rowId: Int, entityId: String?, newText: String) async -> JSONValue? {
        guard let entityId, !entityId.isEmpty else { return nil }
        await ensureStateRevision(conversationID)
        return await sendCASWithRetry(
            "editUserQuery", sessionId: conversationID,
            payload: .object([
                "target": .object([
                    "rowId": .int(rowId),
                    "entityId": .string(entityId),
                ]),
                "newText": .string(newText),
                "workspaceMode": .string("preserve"),
            ]))
    }

    /// 压缩上下文（§7A 设计稿 contextMeter 点击确认后下发）。非 CAS 全集成员——
    /// 普通信封直发；payload {}【宽容：设计稿推测形态，未取证】。
    @discardableResult
    func compact(_ conversationID: String) async -> JSONValue? {
        await sendCommand("compact", sessionId: conversationID, payload: .object([:]))
    }

    /// 挂起交互「稍后处理」（§7B 设计稿审批卡第三动作）。协议文档 0 记录（仅
    /// web接口对齐盘点报告.md:32/:82 词表提及）；payload {interactionId}【宽容：
    /// 键名照 resolveInteraction 实证先例，命令本体/回执/桌面重提醒行为全部未取证】。
    @discardableResult
    func snoozeInteractionAutoResolution(
        _ conversationID: String, interactionId: String) async -> JSONValue? {
        await sendCommand(
            "snoozeInteractionAutoResolution", sessionId: conversationID,
            payload: .object(["interactionId": .string(interactionId)]))
    }

    /// 目标下发（§5 设计稿 goal 面板「编辑目标」sheet）。§四口径清理：web CAS 词表
    /// （bundle Jle 15 命令实证）确无 sendGoalCommand——属 input 类，baseRevision
    /// 可选，改普通信封直发（原 ensureStateRevision+sendCASWithRetry 是超集做法）。
    /// payload {text}【宽容：键名取 state.goal 宽容解析键组首位
    /// （goalSummary 同序 text→description→content→prompt→goal），其余为备选】。
    @discardableResult
    func sendGoalCommand(_ conversationID: String, text: String) async -> JSONValue? {
        await sendCommand(
            "sendGoalCommand", sessionId: conversationID,
            payload: .object(["text": .string(text)]))
    }

    /// 启动已保存工作流（§4 设计稿「工作流库」行卡「启动」）。§7.2:406 词表在列、
    /// 非 CAS 全集成员——普通信封直发。conversationID 传 nil 时信封 sessionId=null
    /// （createSession 同路：工作流库页无会话上下文，会话由桌面创建，回执
    /// result.sessionId 宽容提取交调用方跳转）。payload {workflowId, args?}【宽容：
    /// 设计稿 §4.3 候选形 {workflowId|name, args} 全部未取证；args 为动态参数表单
    /// 键值，桌面 schema 待探针】。
    @discardableResult
    func startSavedWorkflow(
        _ conversationID: String?, workflowId: String, args: [String: JSONValue]?) async -> JSONValue? {
        var payload: [String: JSONValue] = ["workflowId": .string(workflowId)]
        if let args, !args.isEmpty { payload["args"] = .object(args) }
        return await sendCommand(
            "startSavedWorkflow", sessionId: conversationID, payload: .object(payload))
    }

    // MARK: workspace hook 信任审核（web 对齐 2026-10-06：respond/request/toggle/revoke 四命令）
    //
    // 背景：桌面工作区启用 hooks 时会话挂起 payload.kind='workspaceHookReview' 的
    // pendingInteraction（与 permission 审批卡同卡的姊妹 kind），web 以
    // respondWorkspaceHookReview 应答。四命令均非 CAS（bundle Jle 15 命令词表取证，
    // 均不在集内）——普通信封直发。
    //
    // 应答基座 M8e（bundle function M8e 定义逐字取证【移植·bundle 逆向】）：
    // {sessionId, taskId, runId, remoteSessionId?, workspaceIdentity, bundleDigest,
    //  reviewFlowId, generation, interactionId}——全部来自交互 payload 本身（web 调用
    // 点 `qU(a, o.sessionId, 'respondWorkspaceHookReview', {...M8e(o), decision})`，
    // o 即交互 payload），移动端从缓存 pendingInteractions 原样透传，不臆造字段。

    /// 当前挂起的 workspaceHookReview 交互 payload（M8e 字段族来源；无挂起返回 nil）
    private func workspaceHookReviewPayload(_ conversationID: String) -> [String: JSONValue]? {
        guard let interaction = currentPendingInteraction(conversationID, kinds: ["workspaceHookReview"]) else {
            return nil
        }
        return interaction.objectValue?["payload"]?.objectValue ?? interaction.objectValue
    }

    /// M8e 基座 + 附加键。基座严格取 M8e 九键（sessionId/taskId/runId/remoteSessionId/
    /// workspaceIdentity/bundleDigest/reviewFlowId/generation/interactionId）——web
    /// 是 `{...M8e(o), decision}` 构造新对象，**不是整个 payload 透传**；多余键
    /// （kind/summary/reviewItems 等）不携带，防 strict schema 拒收（A-4 同教训）。
    /// sessionId/interactionId 缺席时以会话 id/交互 id 兜底（信封 sessionId 恒在场
    /// 纪律同源）。
    private func hookReviewCommandPayload(
        _ conversationID: String, interactionId: String?,
        extra: [String: JSONValue]
    ) -> JSONValue? {
        guard let cached = workspaceHookReviewPayload(conversationID) else { return nil }
        var base: [String: JSONValue] = [:]
        for key in ["sessionId", "taskId", "runId", "remoteSessionId", "workspaceIdentity",
                    "bundleDigest", "reviewFlowId", "generation", "interactionId"] {
            if let value = cached[key], value != .null {
                base[key] = value
            }
        }
        if base["sessionId"] == nil { base["sessionId"] = .string(conversationID) }
        if base["interactionId"] == nil, let interactionId, !interactionId.isEmpty {
            base["interactionId"] = .string(interactionId)
        }
        for (key, value) in extra { base[key] = value }
        return .object(base)
    }

    /// 应答信任审核（web 实证唯一 action：`decision:{action:'trust_selected',
    /// reviewItemIds:[…]}`——reviewItemIds 为所选审核项 id 列表，web 逐项按钮即
    /// 单元素数组；decision 完整枚举 schema Hae 在共享 chunk 未取证，本端只发
    /// trust_selected）。payload 缓存不在场（交互已被桌面撤下/未同步）返回 nil，
    /// 调用方如实提示不虚构成功。非 CAS，普通信封直发。
    @discardableResult
    func respondWorkspaceHookReview(
        _ conversationID: String, reviewItemIds: [String]
    ) async -> JSONValue? {
        guard !reviewItemIds.isEmpty else { return nil }
        let interactionId = workspaceHookReviewPayload(conversationID)?["interactionId"]?.stringValue
        guard let payload = hookReviewCommandPayload(
            conversationID, interactionId: interactionId,
            extra: ["decision": .object([
                "action": .string("trust_selected"),
                "reviewItemIds": .array(reviewItemIds.map { .string($0) }),
            ])]) else { return nil }
        return await sendCommand(
            "respondWorkspaceHookReview", sessionId: conversationID, payload: payload)
    }

    /// 主动请求（重发）信任审核。web 两调用点取证：payload 恒 4 键
    /// `{sessionId, remoteSessionId?, workspaceIdentity, bundleDigest}`（remoteSessionId
    /// 在场才携）；已知 reasonCode：workspace_hooks_interaction_timeout（web 侧 5s
    /// 超时 N8e=5e3）/ workspace_hooks_require_trust_capable_host。digest 来源为
    /// 缓存交互 payload；无 digest 可携返回 nil 由调用方降级（不盲发空 digest）。
    /// schema Nee 在共享 chunk【未取证】。
    @discardableResult
    func requestWorkspaceHookReview(_ conversationID: String) async -> JSONValue? {
        await requestWorkspaceHookReview(
            conversationID, base: workspaceHookReviewPayload(conversationID) ?? [:])
    }

    /// 请求核心：base 为 M8e 字段族来源（挂起交互 payload 或 state.workspaceHookAdmission）
    private func requestWorkspaceHookReview(
        _ conversationID: String, base: [String: JSONValue]
    ) async -> JSONValue? {
        var payload: [String: JSONValue] = ["sessionId": .string(conversationID)]
        if let remoteSessionId = base["remoteSessionId"]?.stringValue, !remoteSessionId.isEmpty {
            payload["remoteSessionId"] = .string(remoteSessionId)
        }
        if let identity = base["workspaceIdentity"]?.stringValue, !identity.isEmpty {
            payload["workspaceIdentity"] = .string(identity)
        }
        guard let digest = base["bundleDigest"]?.stringValue, !digest.isEmpty else { return nil }
        payload["bundleDigest"] = .string(digest)
        return await sendCommand(
            "requestWorkspaceHookReview", sessionId: conversationID, payload: .object(payload))
    }

    /// 单条审核项信任开关（schema 取证：`{reviewItemId: string(min1,trim), enabled:
    /// bool}` + M8e 基座【移植·bundle 逆向】）。web bundle 仅 schema 无调用点——
    /// enabled 语义（信任粒度还是启用开关）未取证，UI 面以 respond 实证路径承载，
    /// 本方法暂无 UI 入口（见协议文档条目【宽容】注）。
    @discardableResult
    func toggleWorkspaceHookReviewItem(
        _ conversationID: String, reviewItemId: String, enabled: Bool
    ) async -> JSONValue? {
        guard !reviewItemId.isEmpty else { return nil }
        guard let payload = hookReviewCommandPayload(
            conversationID, interactionId: nil,
            extra: ["reviewItemId": .string(reviewItemId), "enabled": .bool(enabled)]) else {
            return nil
        }
        return await sendCommand(
            "toggleWorkspaceHookReviewItem", sessionId: conversationID, payload: payload)
    }

    /// 撤销已信任 hook 项（schema 取证：union 首臂 `{reviewItemIds: string[](min1)}` +
    /// M8e 基座；第二臂 ite 在共享 chunk形态未取证【宽容】）。写桌面信任账本——
    /// 调用方（UI）必须带确认弹层后才可达。web bundle 仅 schema 无调用点。
    @discardableResult
    func revokeWorkspaceHookTrust(
        _ conversationID: String, reviewItemIds: [String]
    ) async -> JSONValue? {
        guard !reviewItemIds.isEmpty else { return nil }
        guard let payload = hookReviewCommandPayload(
            conversationID, interactionId: nil,
            extra: ["reviewItemIds": .array(reviewItemIds.map { .string($0) })]) else {
            return nil
        }
        return await sendCommand(
            "revokeWorkspaceHookTrust", sessionId: conversationID, payload: payload)
    }

    // MARK: 诊断探针（-ZCodeDiagQueueCASProbe new：PTY 受限期间的活体验证入口）

    /// 队列 CAS 命令链路一次性实证（用户报障「置顶 require baseRevision and
    /// baseLogEpoch」+「新建会话模型思考强度不能选」的修复验证）：
    /// 探针会话（首条指令=极小 no-op 回复，session 需真实存在才能收命令——draft
    /// 被 proto.sessionNotFound 拒，探针实证）内依次 setAutoDrain(false) →
    /// sendText×2（requestedDelivery:"queue" 队列准入，第二条携 modelSelection——
    /// 与 firstInput.modelSelection 同构 schema，验收桌面是否接受该形态）→
    /// reorder 置顶 → edit → delete×2 → setAutoDrain(true) 回原 → deleteTask 清场。
    /// 每步 ack 落 diag.qcas.N 供回读。仅 "new" 目标生效（绝不触碰既有会话）。
    func runQueueCASProbeDiag(target: String) async {
        guard target == "new" else {
            UserDefaults.standard.set("refused: target=\(target) 仅支持 new", forKey: "diag.qcas.0")
            return
        }
        func note(_ n: Int, _ what: String, _ ack: JSONValue?) {
            UserDefaults.standard.set(
                "\(what) ack=\(String(describing: ack).prefix(3000))", forKey: "diag.qcas.\(n)")
            UserDefaults.standard.synchronize()
        }
        // ① 探针会话：带首条指令（极小 no-op，agent 只回 ok；draft 会话 sessionNotFound
        // 收不了任何命令——首轮实证）。直接 sendCommand 并落 raw ack 全文（排查
        // sessionId 提取链路：本地 UUID 兜底会让 sendText sessionNotFound）
        let rawAck = await sendCommand("createSession", sessionId: nil, payload: .object([
            "workspaceId": .string(workspace.path),
            "firstInput": .object(["text": .string(
                "只回复ok，勿执行任何操作——移动端验收探针会话（可删除）")]),
        ]))
        note(0, "createSession raw", rawAck)
        guard let sessionId = rawAck?["result"]?.objectValue?["sessionId"]?.stringValue
            ?? rawAck?["result"]?.objectValue?["session"]?.objectValue?["sessionId"]?.stringValue
            ?? rawAck?["sessionId"]?.stringValue else {
            note(13, "ABORT: createSession ack 无 sessionId 可提取", rawAck)
            return
        }
        _ = await messages(in: sessionId) // 建订阅（快照到位才有 revision）
        // 实证（v4/v5 轮）：全新会话 resync 拉不到 revision（state 随首个 turn 才建），
        // 首条 accepted 命令的 revisionAtDecision 回填才是引导路径（sendCommand 已做）
        // ② sendText 队列准入×2（趁首条 turn 在跑/刚收尾即入队；其二携 modelSelection
        // 验 schema——web Ce 同构）。sendText 非 CAS， accepted 回执回填 revision
        var payload1: [String: JSONValue] = [
            "text": .string("【移动端验收探针】队列链路测试消息一（探针自动清理）"),
            "requestedDelivery": .string("queue"),
        ]
        note(2, "sendText#1 queue", await sendCommand(
            "sendText", sessionId: sessionId, payload: .object(payload1)))
        // ③ 关自动排空（CAS #1；revision 已由上一条回执回填，冷启动引导完成）
        note(3, "setAutoDrain(false)", await setAutoDrain(sessionId, enabled: false))
        var payload2: [String: JSONValue] = [
            "text": .string("【移动端验收探针】队列链路测试消息二（探针自动清理）"),
            "requestedDelivery": .string("queue"),
        ]
        if let selection = await modelSelectionView(), let model = selection.activeModel {
            var selectionPayload: [String: JSONValue] = [
                "providerId": .string(selection.modelProviders[model] ?? ""),
                "modelId": .string(model),
            ]
            if let level = selection.activeThoughtLevel, !level.isEmpty {
                selectionPayload["options"] = .object(["reasoningLevel": .string(level)])
            }
            payload2["modelSelection"] = .object(selectionPayload)
        }
        note(4, "sendText#2 queue+modelSelection", await sendCommand(
            "sendText", sessionId: sessionId, payload: .object(payload2)))
        // state delta 异步回流：轮询等两条都进队（最多 ~5s）
        var queueNow: ConversationQueueInfo?
        for _ in 0..<10 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            queueNow = await queueInfo(in: sessionId)
            if queueNow?.items.count ?? 0 >= 2 { break }
        }
        if let queue = queueNow {
            let dump = queue.items.map { "\($0.id.prefix(8)):\(String($0.text.suffix(10)))" }
                .joined(separator: " | ")
            note(5, "queue(items=\(queue.items.count) autoDrain=\(queue.autoDrain)) \(dump)",
                 .object(["ok": .bool(true)]))
        } else {
            note(5, "queue EMPTY", .object(["ok": .bool(false)]))
        }
        // ④ 置顶（用户报障点）：把队尾条目移到队首之前（CAS #2）
        guard let queueBefore = queueNow, queueBefore.items.count >= 2 else {
            note(6, "ABORT: 队列不足两条（queue 准入未生效，后续 CAS 步跳过）",
                 .object(["ok": .bool(false)]))
            return
        }
        let tail = queueBefore.items.last!
        let head = queueBefore.items.first!
        note(6, "reorder 置顶(\(tail.id.prefix(8))→before \(head.id.prefix(8)))",
             await reorderQueueItem(sessionId, queueItemId: tail.id, beforeQueueItemId: head.id))
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        if let after = await queueInfo(in: sessionId) {
            let dump = after.items.map { "\($0.id.prefix(8)):\(String($0.text.suffix(10)))" }
                .joined(separator: " | ")
            note(7, "queue-after(items=\(after.items.count)) \(dump)", .object(["ok": .bool(true)]))
        } else {
            note(7, "queue-after EMPTY", .object(["ok": .bool(false)]))
        }
        // ⑤ 编辑 + 删除（CAS #3/#4）+ 恢复自动排空（CAS #5）
        note(8, "edit(\(head.id.prefix(8)))", await editQueueItem(
            sessionId, queueItemId: head.id,
            newText: "【移动端验收探针】已编辑（探针自动清理）"))
        note(9, "delete#1", await deleteQueueItem(sessionId, queueItemId: head.id))
        note(10, "delete#2", await deleteQueueItem(sessionId, queueItemId: tail.id))
        note(11, "setAutoDrain(true)", await setAutoDrain(sessionId, enabled: true))
        // ⑥ 清场：探针会话从 task index 删除（会话域订阅残留随连接生命周期回收）
        let cleaned = await deleteTask(sessionId)
        note(12, "deleteTask", .object(["ok": .bool(cleaned)]))
    }

    /// stop 命令活体验证探针（-ZCodeDiagStopProbe new；PTY 受限期间无点按路径）：
    /// `stop` 是唯一从未对真实桌面验证过的信封类型（曾由 RemoteTaskStore 自造平铺
    /// 信封，必被拒——修复后走统一 sendCommand）。流程：探针会话（首条指令让 agent
    /// 慢回复以拉长 turn）→ 等 turn 起来（revision 到位）→ stopTurn 下发 stop →
    /// ack 落 diag.stop.N → deleteTask 清场。仅 "new" 目标生效。
    func runStopProbeDiag(target: String) async {
        guard target == "new" else {
            UserDefaults.standard.set("refused: target=\(target) 仅支持 new", forKey: "diag.stop.0")
            return
        }
        func note(_ n: Int, _ what: String, _ ack: JSONValue?) {
            UserDefaults.standard.set(
                "\(what) ack=\(String(describing: ack).prefix(1200))", forKey: "diag.stop.\(n)")
            UserDefaults.standard.synchronize()
        }
        let rawAck = await sendCommand("createSession", sessionId: nil, payload: .object([
            "workspaceId": .string(workspace.path),
            "firstInput": .object(["text": .string(
                "请慢慢数数从1到30，每个数之间间隔约2秒，全部数完后只回复\"done\"——移动端验收探针会话（可删除）")]),
        ]))
        note(0, "createSession", rawAck)
        guard let sessionId = rawAck?["result"]?.objectValue?["sessionId"]?.stringValue else {
            note(3, "ABORT: 无 sessionId", rawAck)
            return
        }
        _ = await messages(in: sessionId)
        // 等 turn 起来：revision 到位（state 随首个 turn 创建）再停，保证停的是活 turn
        var revisionArrived = false
        await ensureStateRevision(sessionId)
        for _ in 0..<20 {
            if conversationStateRevisions[sessionId] != nil { revisionArrived = true; break }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        note(1, "revision-ready(\(revisionArrived))",
             .object(["revision": .int(conversationStateRevisions[sessionId] ?? 0)]))
        guard revisionArrived else {
            note(3, "ABORT: turn 未起来（无 revision）", .object(["ok": .bool(false)]))
            return
        }
        try? await Task.sleep(nanoseconds: 3_000_000_000) // 让 turn 确实在跑
        let stopAck = await stopTurn(sessionId: sessionId)
        note(2, "stopTurn", stopAck)
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        let cleaned = await deleteTask(sessionId)
        note(3, "deleteTask", .object(["ok": .bool(cleaned)]))
    }

    /// 探针残留清理（-ZCodeDiagCleanupProbe）：两段式——① listTaskList 标题匹配
    /// 「探针」条目：deleteTask(taskId)（task index 条目）+ deleteSession(sessionId)
    /// （会话本体，web 同款回收命令；只删 task 不删 session 时 sessions-index 仍投影
    /// ——2026-10-06 实证残留根因）；② 既有探针轮已知 sessionId 直删会话本体。
    /// 回执落 diag.cleanup。
    func runCleanupProbeDiag() async {
        func note(_ what: String) {
            UserDefaults.standard.set(what, forKey: "diag.cleanup")
            UserDefaults.standard.synchronize()
        }
        guard let connection else {
            note("ERR no connection")
            return
        }
        // 形状同 RemoteTaskStore.refresh（kind+workspaceScopes+sortBy+limit，缺类被拒）
        var builder = JSONObjectBuilder()
        builder.set("kind", "timeline")
        builder.set("workspaceScopes", .array([.object([
            "workspacePath": .string(workspace.path),
        ])]))
        builder.set("sortBy", "updated")
        builder.set("limit", 200)
        let result = try? await connection.call(
            "zcode-task", "listTaskList", .json(.object(builder.fields)))
        // ① 标题匹配条目：task 条目 + 会话本体都删
        var removed: [String] = []
        var sessionIds: [String] = [
            // ② 既有探针轮已知 sessionId（diag.qcas.0 / diag.stop.0 留痕）
            "sess_00874b4f-b144-4075-a0d3-1b39bd8b5bfa",
            "sess_81a9345e-44bb-4e61-9623-8c9b982d1940",
            "sess_ea37e618-fb66-41b9-bf8c-5baade22593f",
            "sess_31817bda-31a2-44a0-b76a-b9a7321eb0df",
        ]
        if let items = result?.jsonValue?["items"]?.arrayValue {
            for item in items {
                guard let d = item.objectValue else { continue }
                let title = d["title"]?.stringValue ?? ""
                guard title.contains("探针") || title.contains("请慢慢数数") else { continue }
                if let taskId = d["taskId"]?.stringValue {
                    let ok = await deleteTask(taskId)
                    removed.append("task \(taskId.prefix(10)) ok=\(ok)")
                }
                if let sessionId = d["sessionId"]?.stringValue { sessionIds.append(sessionId) }
            }
        }
        // deleteSession：会话本体回收（非 CAS，sessionId 走信封；accepted/noop 均为终态）
        for sessionId in sessionIds {
            let ack = await sendCommand("deleteSession", sessionId: sessionId, payload: .object([:]))
            removed.append("sess \(sessionId.prefix(12)) \(ack?["status"]?.stringValue ?? "nil")"
                + " reason=\(ack?["reasonCode"]?.stringValue ?? "-")"
                + " msg=\(String(describing: ack?["message"]).prefix(160))")
        }
        note("removed=[\(removed.joined(separator: " | "))]")
    }

    /// 主模型/思考强度切换（switchModelConfig {provider, model, thought}；web 端模型选择器
    /// 同构造，CAS 类命令携当前 state revision。thought 缺席传空串（schema K() 必填字符串）；
    /// 成功后 model-selection onDidChange 回流驱动 chips 同步）
    @discardableResult
    func switchModelConfig(
        _ conversationID: String, provider: String, model: String, thought: String?) async -> JSONValue? {
        // CAS 前置：revision 缺失时 resync + 轮询（探针实证的队列同款，公共步ensureStateRevision）
        await ensureStateRevision(conversationID)
        // stale 原样重发一次（sendCASWithRetry，队列五件/模式胶囊同款）——活跃会话
        // state revision 持续前进，单发必撞 proto.staleRevision（diag.wf.control.ui 实证
        // 2026-10-07：ack status=stale reasonCode=proto.staleRevision revisionAtDecision=12119，
        // 用户报障「composer 胶囊切换都不行」= 模型/思考两颗皆此因）
        return await sendCASWithRetry(
            "switchModelConfig", sessionId: conversationID,
            payload: .object([
                "provider": .string(provider),
                "model": .string(model),
                "thought": .string(thought ?? ""),
            ]))
    }

    /// 主动全量 resync（服务端重发快照含 state.workflowRuns；amend 停旧换新后拉新 run 用，
    /// 5s 节流防抖）
    private var lastResyncAt: [String: Date] = [:]
    func triggerResync(_ conversationID: String) async {
        if let last = lastResyncAt[conversationID], Date().timeIntervalSince(last) < 5 {
            return
        }
        lastResyncAt[conversationID] = Date()
        guard let subId = conversationSubscriptionIds[conversationID] else { return }
        await forceFullResync(conversationID: conversationID, subscriptionId: subId)
    }

    // MARK: P2 批次：retryTurn / fork / 分组写面 / RunEvents 重建 / 子代理转录
    //
    // 边界口径（与前批一致）：均为「客户端发命令、桌面代执行」或索引元数据写；
    // 文件直写类维持 ReadOnlyGate 拦截。

    /// G-015：失败 turn 重试——携行元数据精确游标 {rowId, entityId} 下发 retryTurn
    /// （execution 词表成员，ReadOnlyGate command 类放行）。entityId 缺失时调用方
    /// 不渲染入口；此实现再兜一层（不虚构「已重试」）。web vle/yle 双集合成员：
    /// 须携 baseRevision+baseLogEpoch（CAS+row-target 双类），否则桌面拒。
    /// 回执返回供调用方如实反馈（2026-10-07 重构：此前 `_ =` 丢弃——被拒时纯静默）
    @discardableResult
    func retryTurn(_ conversationID: String, rowId: Int, entityId: String?) async -> JSONValue? {
        guard let entityId, !entityId.isEmpty else { return nil }
        var target: [String: JSONValue] = ["rowId": .int(rowId)]
        target["entityId"] = .string(entityId)
        return await sendCASWithRetry(
            "retryTurn",
            sessionId: conversationID,
            payload: .object(["target": .object(target)]))
    }

    /// G-018：会话派生——forkAssistant。B-9 修正（web 实证 bundle）：主路改走
    /// sendConversationCommandV4 命令（payload {target:{rowId, entityId}}，CAS 词表
    /// Jle/Yle 双集成员——web 调用点 fr(`forkAssistant`,{target:e},…,revision,
    /// logEpoch) 恒携 CAS 双字段），回执 result 为判别联合：type=="forkAssistant"
    /// 时 result.sessionId 即新会话 id（type 缺席/不符时 web 不消费，如实返回 nil）。
    /// 行游标取该会话已加载行表中最新的携带 entityId 的行（列表级「派生会话」入口
    /// 无消息上下文；「从某条消息 fork」的显式游标入口待 UI 接入时扩展形参）。
    /// 回退：行表无可用游标（如 draft/未订阅行）时走原 channel RPC
    /// （"zcode-agent","forkAssistant"——文档记实证可用，但与 web 不同面，桌面收紧
    /// 即失效，仅作 v4 无 target 时的兜底）。
    func forkConversation(_ conversationID: String) async -> String? {
        let table = rows[conversationID] ?? [:]
        let cursor = table.keys.sorted().reversed()
            .compactMap { rowId -> (rowId: Int, entityId: String)? in
                guard let row = table[rowId]?.json.objectValue,
                      let entityId = row["entityId"]?.stringValue, !entityId.isEmpty else { return nil }
                return (rowId, entityId)
            }
            .first
        if let cursor {
            await ensureStateRevision(conversationID)
            let ack = await sendCASWithRetry(
                "forkAssistant", sessionId: conversationID,
                payload: .object(["target": .object([
                    "rowId": .int(cursor.rowId),
                    "entityId": .string(cursor.entityId),
                ])]))
            let result = ack?["result"]?.objectValue
            // web 精确口径：result 为判别联合（Xle），type=="forkAssistant" 时
            // sessionId 即新会话 id；type 缺席/不符时 web 不消费——如实返回 nil
            guard result?["type"]?.stringValue == "forkAssistant" else { return nil }
            return result?["sessionId"]?.stringValue
        }
        // 回退：channel RPC（B-9 前实现，payload 为会话定位字段组）
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

    // MARK: 会话分组管理写面（G-017；B-3/B-4/C-3/C-4 web 形状对齐 2026-10-06，
    // 审查报告 §二/§三【移植·bundle 逆向】）。均为索引元数据写、桌面代执行合法。
    // 返回值统一 nil=成功、非 nil=失败原因——写面禁止静默（失败必须回传 UI 如实提示）。

    /// workspaceScopes 条目：web K3 形状（视图内任务工作区去重集 {workspacePath,
    /// workspaceIdentity?}；identity 缺席省键 = web JSON.stringify 丢 undefined 同行为）。
    private var taskGroupWorkspaceScopes: [[String: JSONValue]] {
        [RemoteTaskStore.taskWorkspaceScopeJSON(workspace)]
    }

    /// C-4：createTaskGroup 零参调用（web `createTaskGroup()` 同形；原 {name,color}
    /// 形状为 app 臆测——组名/颜色不随建组下发）。回执即新组对象（web Ajt 直读
    /// result.id）；宽容读 groupId / result.groupId / result.id / id。
    private func createTaskGroupWire() async throws -> String {
        guard let connection else {
            throw RPCError(message: "未连接桌面端", name: "NotConnected")
        }
        let result = try await connection.call(
            "zcode-task", "createTaskGroup", .json(.object([:])))
        let value = result.jsonValue
        let groupId = value?["groupId"]?.stringValue
            ?? value?["result"]?.objectValue?["groupId"]?.stringValue
            ?? value?["result"]?.objectValue?["id"]?.stringValue
            ?? value?["id"]?.stringValue
        guard let groupId, !groupId.isEmpty else {
            throw RPCError(message: "建组回执缺 groupId", name: "MalformedResult")
        }
        return groupId
    }

    /// B-4：renameTaskGroup `{groupId, title, workspaceScopes}`（web 键名 title——
    /// 原 `name` 键恒无效；缺 workspaceScopes 同为必坏项）。回执为更新后组对象，
    /// 本面只关心成败。
    func renameTaskGroup(groupId: String, title: String) async -> String? {
        guard let connection else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("groupId", groupId)
        builder.set("title", title)
        builder.set("workspaceScopes", .array(taskGroupWorkspaceScopes.map { .object($0) }))
        do {
            _ = try await connection.call(
                "zcode-task", "renameTaskGroup", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "重命名分组失败（\(error.localizedDescription)）")
        }
    }

    /// C-3：updateTaskGroupColor 补 workspaceScopes（web 全带）。
    func updateTaskGroupColor(groupId: String, color: String) async -> String? {
        guard let connection else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("groupId", groupId)
        builder.set("color", color)
        builder.set("workspaceScopes", .array(taskGroupWorkspaceScopes.map { .object($0) }))
        do {
            _ = try await connection.call(
                "zcode-task", "updateTaskGroupColor", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "更新分组颜色失败（\(error.localizedDescription)）")
        }
    }

    /// C-3：deleteTaskGroup 补 workspaceScopes（web 全带；取消分组 = 先 apply 整视图
    /// 提组任务到顶层、再 delete，两步由调用方编排——本方法只做删除）。
    func deleteTaskGroup(groupId: String) async -> String? {
        guard let connection else { return String(localized: "未连接桌面端") }
        var builder = JSONObjectBuilder()
        builder.set("groupId", groupId)
        builder.set("workspaceScopes", .array(taskGroupWorkspaceScopes.map { .object($0) }))
        do {
            _ = try await connection.call(
                "zcode-task", "deleteTaskGroup", .json(.object(builder.fields)))
            return nil
        } catch {
            return String(localized: "删除分组失败（\(error.localizedDescription)）")
        }
    }

    /// B-3：applyGroupedTaskViewOrder 是**全量视图写** `{workspaceScopes,
    /// topLevelNodes:[…], groups:[{groupId, taskRefs:[{workspacePath,
    /// workspaceIdentity?, taskId}]}]}`（web Ijt）——旧 `{groupId?, order:[…]}`
    /// 形状整体不存在于 web，恒静默无效。topLevelNodes 组条目 {type:'group',
    /// groupId}、任务条目 {type:'task', task:{…}}（web Fjt 嵌套形态，与读面
    /// topLevelOrders 的平铺形状不同）；请求不带 sortOrder，数组序即顺序。
    private func applyGroupedTaskViewOrderWire(
        workspaceScopes: [[String: JSONValue]],
        topLevelNodes: [JSONValue],
        groups: [JSONValue],
    ) async throws {
        guard let connection else {
            throw RPCError(message: "未连接桌面端", name: "NotConnected")
        }
        var builder = JSONObjectBuilder()
        builder.set("workspaceScopes", .array(workspaceScopes.map { .object($0) }))
        builder.set("topLevelNodes", .array(topLevelNodes))
        builder.set("groups", .array(groups))
        _ = try await connection.call(
            "zcode-task", "applyGroupedTaskViewOrder", .json(.object(builder.fields)))
    }

    /// G-017 移入分组全链（B-3 修复主入口；旧实现丢弃 createTaskGroup 回执、以组名
    /// 字符串充当 groupId 下发 → apply 恒无效且无任何报错）：
    /// ① 拉当前分组结构（全量视图写的基线；apply 是整视图提交，拿不到基线就写 =
    /// 覆盖丢组，宁可如实失败）；
    /// ② 定位目标组——同名组（title 精确相等）复用真实 groupId；不存在则
    /// createTaskGroup 零参建组（C-4）→ renameTaskGroup 落名（B-4）；
    /// ③ 重建视图提交：全部既有组与成员保真（跨组移动 = 从原组 taskRefs 移除、
    /// 追加目标组——web 成员模型一任务至多一组），新组置于顶层首位（web Ajt 同
    /// 语义 sortOrder-1000 置顶）；顶层散任务节点不重建（移动端任务索引非全量，
    /// 【宽容】省略——组序与组内成员全量保真，顶层任务无显式序时桌面按 createdAt
    /// 兜底排序，web vjt 同口径）。
    /// 返回 nil=成功；非 nil=失败原因（UI 如实提示，禁止静默）。
    func moveConversationToGroup(_ conversationID: String, groupName: String) async -> String? {
        guard let connection else { return String(localized: "未连接桌面端") }
        // ① 当前结构基线
        var builder = JSONObjectBuilder()
        builder.set("workspaceScopes", .array(taskGroupWorkspaceScopes.map { .object($0) }))
        let structure: DesktopTaskGrouping
        do {
            let result = try await connection.call(
                "zcode-task", "listGroupedTaskViewStructure", .json(.object(builder.fields)))
            guard let parsed = RemoteTaskStore.parseGroupedTaskView(result.jsonValue) else {
                return String(localized: "桌面分组结构不可读，未移动（\(groupName)）")
            }
            structure = parsed
        } catch {
            return String(localized: "获取桌面分组结构失败（\(error.localizedDescription)）")
        }
        // ② 定位/建组（幂等：同名复用；新建 = 零参 create + rename 落名，两步真实回执）
        let targetGroupID: String
        let isNewGroup: Bool
        if let existing = structure.groups.first(where: { $0.name == groupName }) {
            targetGroupID = existing.id
            isNewGroup = false
        } else {
            do {
                targetGroupID = try await createTaskGroupWire()
            } catch {
                return String(localized: "创建分组失败（\(error.localizedDescription)）")
            }
            if let failure = await renameTaskGroup(groupId: targetGroupID, title: groupName) {
                // 补偿：删除刚建的组，防用户重试堆积桌面无名空组；补偿也失败时如实
                // 拼进失败文案（不静默吞——写面纪律），桌面真态以桌面为准
                var text = failure
                if let cleanup = await deleteTaskGroup(groupId: targetGroupID) {
                    text += String(localized: "；且清理新组失败（\(cleanup)）")
                }
                return text
            }
            isNewGroup = true
        }
        // ③ 重建全量视图：目标会话引用（当前工作区；会话列表本身按工作区订阅）
        let movedRef = DesktopTaskGrouping.TaskRef(
            workspacePath: workspace.path,
            workspaceIdentity: (workspace.workspaceIdentity?.isEmpty == false)
                ? workspace.workspaceIdentity : nil,
            taskId: conversationID)
        func scopeKey(_ ref: DesktopTaskGrouping.TaskRef) -> String {
            "\(ref.workspacePath)\u{0}\(ref.workspaceIdentity ?? "")"
        }
        var scopeSet: [String: [String: JSONValue]] = [:]
        func recordScope(_ ref: DesktopTaskGrouping.TaskRef) {
            scopeSet[scopeKey(ref)] = RemoteTaskStore.taskWorkspaceScopeJSON(
                ServerWorkspaceInfo(path: ref.workspacePath, label: nil,
                                    workspaceIdentity: ref.workspaceIdentity))
        }
        var groupsJSON: [JSONValue] = []
        if isNewGroup {
            // 新组置于组数组与顶层首位（web Ajt 置顶同语义）
            recordScope(movedRef)
            groupsJSON.append(.object([
                "groupId": .string(targetGroupID),
                "taskRefs": .array([Self.taskRefJSON(movedRef)]),
            ]))
        }
        for group in structure.groups {
            var refs = group.taskRefs.filter { $0.taskId != conversationID }
            if group.id == targetGroupID, !refs.contains(where: { $0.taskId == movedRef.taskId }) {
                refs.append(movedRef)
            }
            for ref in refs { recordScope(ref) }
            groupsJSON.append(.object([
                "groupId": .string(group.id),
                "taskRefs": .array(refs.map(Self.taskRefJSON)),
            ]))
        }
        // 顶层节点：组节点按结构序（新组已置首）；散任务节点【宽容】省略（见方法注释）
        let topLevelNodes: [JSONValue] = groupsJSON.compactMap { group in
            guard let id = group.objectValue?["groupId"]?.stringValue else { return nil }
            return .object(["type": .string("group"), "groupId": .string(id)])
        }
        do {
            try await applyGroupedTaskViewOrderWire(
                workspaceScopes: Array(scopeSet.values),
                topLevelNodes: topLevelNodes,
                groups: groupsJSON)
            return nil
        } catch {
            return String(localized: "移入分组失败（\(error.localizedDescription)）")
        }
    }

    /// taskRef 写面条目 {workspacePath, workspaceIdentity?, taskId}（web Fjt 同形）
    private static func taskRefJSON(_ ref: DesktopTaskGrouping.TaskRef) -> JSONValue {
        var entry: [String: JSONValue] = [
            "workspacePath": .string(ref.workspacePath),
            "taskId": .string(ref.taskId),
        ]
        if let identity = ref.workspaceIdentity, !identity.isEmpty {
            entry["workspaceIdentity"] = .string(identity)
        }
        return .object(entry)
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
            applySessionTarget(&builder, sessionID: conversationID)
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
                        sessionId: payload["sessionId"]?.stringValue ?? payload["childSessionId"]?.stringValue,
                        tasksTotal: nil,
                        tasksSettled: nil))
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
            concurrencyCeiling: nil,
            subagentModel: nil,
            cancellable: false)
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
        // 中继瞬断（Reconnecting）会打断分块读：首败退避 1.2s 重试一次
        if let first = await attachmentPreviewOnce(sessionID: sessionID, ref: ref) {
            return first
        }
        try? await Task.sleep(nanoseconds: 1_200_000_000)
        return await attachmentPreviewOnce(sessionID: sessionID, ref: ref)
    }

    private func attachmentPreviewOnce(sessionID: String, ref: String) async -> AttachmentPreview? {
        guard let connection else { return nil }
        var chunks: [String] = []
        var mediaType: String?
        var totalBytes = 0
        var offset = 0
        let chunkLimit = 512 * 1024
        defer { if DiagnosticAttachmentFlag.enabled { UserDefaults.standard.set("ref=\(ref) chunks=\(chunks.count) mediaType=\(mediaType ?? "nil") bytes=\(totalBytes)", forKey: "diag.attachment.last") } }
        for _ in 0..<8 { // 上限 8 块 ≈ 4MB
            var builder = JSONObjectBuilder()
            applySessionTarget(&builder, sessionID: sessionID)
            builder.set("ref", ref)
            builder.set("offset", offset)
            builder.set("limit", chunkLimit)
            do {
                let result = try await connection.call("zcode-agent", "attachmentReadV4", .json(.object(builder.fields)))
                guard let dict = result.jsonValue?.objectValue,
                      let base64 = dict["dataBase64"]?.stringValue else {
                    if DiagnosticAttachmentFlag.enabled {
                        UserDefaults.standard.set("no dataBase64: \(String(describing: result.jsonValue).prefix(300))", forKey: "diag.attachment.err")
                    }
                    return nil
                }
                chunks.append(base64)
                if mediaType == nil { mediaType = dict["mediaType"]?.stringValue }
                totalBytes = dict["totalBytes"]?.intValue ?? totalBytes
                guard let next = dict["nextOffset"]?.intValue else { break }
                offset = next
            } catch {
                if DiagnosticAttachmentFlag.enabled {
                    UserDefaults.standard.set("ERR \(String(describing: error).prefix(300))", forKey: "diag.attachment.err")
                }
                return nil
            }
        }
        guard let mediaType else { return nil }
        // 分块 base64 各自带 padding/可能夹杂空白：整串解码失败时按块独立解码拼接
        let joined = chunks.joined()
        var data = Data(base64Encoded: joined, options: [.ignoreUnknownCharacters])
        if data == nil || data?.isEmpty == true {
            var assembled = Data()
            for chunk in chunks {
                guard let part = Data(base64Encoded: chunk, options: [.ignoreUnknownCharacters]) else {
                    assembled = Data(); break
                }
                assembled.append(part)
            }
            data = assembled.isEmpty ? nil : assembled
        }
        guard let data else {
            if DiagnosticAttachmentFlag.enabled {
                UserDefaults.standard.set("base64 decode failed len=\(joined.count) chunks=\(chunks.count)", forKey: "diag.attachment.err")
            }
            return nil
        }
        return AttachmentPreview(ref: ref, data: data, mediaType: mediaType, totalBytes: totalBytes)
    }

    // MARK: 附件上传事务（A-4 对齐 web：Begin/Chunk/Commit/Abort 四条 channel RPC）
    //
    // web 事务【实证·上游开源仓 github.com/zai-org/ZCode，packages/ui/src/v4/
    // attachmentUploadTransaction.ts（2026-10-07 克隆取证）】：客户端参数里**没有
    // connectionId**——`common = {...workspace, sessionId, uploadId}`，connectionId
    // 由桌面 host facade 注入（zcodeAgentConnectionScope.withTrustedConnection：
    // 剥掉客户端伪造的 connectionId/clientMode 再写可信真值）。此前移动端自作主张
    // 多发 `connectionId: registeredClientId`（2026-10-06「bundle 不可见注入点」的
    // 猜测），远端面 strict 校验即拒——真机「附件不能上传」根因之一，已删，与 web
    // 客户端参数逐键对齐：
    // ① Begin `{workspacePath, workspaceIdentity?, sessionId, uploadId, fileName, mime,
    //    totalBytes, totalChunks, checksum:"sha256:"+64hex}` → 回执 state 判别联合
    //    staging{nextChunkIndex} | committed{nextChunkIndex, ref}；
    // ② Chunk `{...common, chunkIndex, dataBase64}` → `{uploadId, nextChunkIndex}`，
    //    进度判定 = nextChunkIndex === chunkIndex+1；
    // ③ Commit `{...common}` → `{ref}`；④ Abort 同 Commit 形（Begin 成功后的失败路径收口）。
    // 384KB/块（ATTACHMENT_UPLOAD_CHUNK_BYTES=384*1024）；uploadId 客户端生成
    // `upload-<uuid>`；限额真值【实证·上游仓 zcode-protocol-v4/core.ts
    // PROTOCOL_V4_LIMITS】：attachmentMaxBytes=20MB、attachmentUploadMaxChunks=64
    // （384KB×64=24MB > 20MB，字节上限先绑定）。
    // ReadOnlyGate：附件事务为桌面存储写非手机直写，zcode-agent 默认面放行
    // （ReadOnlyGate.swift:227 注释）。中继瞬断首败 1.2s 退避重试一次（AGENTS §5.8）；
    // 失败以 Result.failure 携 RPCError 文本回传调用方（禁止静默吞错，U-5 同口径）。

    /// channel 错误 → UI 可读文本（name+message 保留服务端原词，供失败行如实展示）
    nonisolated private static func attachmentWireError(_ error: Error) -> String {
        if let rpcError = error as? RPCError {
            return "\(rpcError.name): \(rpcError.message)"
        }
        return error.localizedDescription
    }

    /// §11 diag.attach.last：附件事务最近一次往返取证（方法 + 回执/错误原文前缀；
    /// 真机首击回执取证用，验收后随 diag 清理波次移除）
    nonisolated private static func attachmentDiag(method: String, detail: String) {
        UserDefaults.standard.set(
            "\(method) \(detail.prefix(400))", forKey: "diag.attach.last")
    }

    /// 未连接时的统一失败文本（连接句柄已释放——断线/拆除后）
    nonisolated private static let attachmentNotConnectedText = "NotConnected: 未连接桌面端"

    /// 开启（或幂等续接）上传事务。同 uploadId 重发 Begin 时桌面按已收块数回报
    /// staging.nextChunkIndex——调用方以此为续传起点（web PCe 循环同款起点语义）。
    func attachmentBeginV4(
        sessionID: String, uploadId: String, fileName: String, mime: String,
        totalBytes: Int, totalChunks: Int, checksum: String
    ) async -> Result<AttachmentBeginOutcome, AttachmentRPCError> {
        guard let connection else { return .failure(AttachmentRPCError(text: Self.attachmentNotConnectedText)) }
        var builder = JSONObjectBuilder()
        // 会话域 workspace 信封（实机实证 2026-10-06：缺 workspacePath 被
        // "Invalid params" 拒——web 由 channel 层注入同款信封，bundle strict
        // schema 只约束内层键，服务端实际校验含信封）
        applySessionTarget(&builder, sessionID: sessionID)
        builder.set("uploadId", uploadId)
        builder.set("sessionId", sessionID)
        builder.set("fileName", fileName)
        builder.set("mime", mime)
        builder.set("totalBytes", totalBytes)
        builder.set("totalChunks", totalChunks)
        builder.set("checksum", checksum)
        // 参数层 strict 校验（构造与校验同源：transport.ts:832-952 附件四方法全
        // .strict()——正则/限额在本地构造期即爆，替代「发出后服务端 zod 拒」）
        let paramCheck = PValidator.validate(
            .object(builder.fields),
            schema: .object(CommandSchemas.attachmentBeginParamsShape()),
            path: "attachmentBeginV4")
        guard paramCheck.isValid else {
            return .failure(AttachmentRPCError(
                text: "参数校验失败：" + paramCheck.errors.joined(separator: "; ")))
        }
        do {
            let ack = try await connection.call(
                "zcode-agent", "attachmentBeginV4", .json(.object(builder.fields)))
            Self.attachmentDiag(
                method: "begin", detail: String(describing: ack.jsonValue).prefix(400).description)
            return Self.parseBegin(ack.jsonValue)
        } catch {
            Self.attachmentDiag(
                method: "begin-err",
                detail: Self.attachmentWireError(error))
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            do {
                let ack = try await connection.call(
                    "zcode-agent", "attachmentBeginV4", .json(.object(builder.fields)))
                return Self.parseBegin(ack.jsonValue)
            } catch {
                return .failure(AttachmentRPCError(text: Self.attachmentWireError(error)))
            }
        }
    }

    /// Begin 回执解析：state 判别联合（committed 短路给 ref；staging 给续传起点；
    /// 形状不识别如实报错——本方法形状【移植·bundle 逆向】，首击待真机探针）
    nonisolated private static func parseBegin(_ json: JSONValue?) -> Result<AttachmentBeginOutcome, AttachmentRPCError> {
        guard let dict = json?.objectValue else {
            return .failure(AttachmentRPCError(text: "回执形状未识别：\(String(describing: json).prefix(160))"))
        }
        switch dict["state"]?.stringValue {
        case "committed":
            guard let ref = dict["ref"]?.stringValue, !ref.isEmpty else {
                return .failure(AttachmentRPCError(text: "committed 回执缺 ref：\(String(describing: dict).prefix(160))"))
            }
            return .success(.committed(ref: ref))
        case "staging":
            guard let next = dict["nextChunkIndex"]?.intValue else {
                return .failure(AttachmentRPCError(text: "staging 回执缺 nextChunkIndex：\(String(describing: dict).prefix(160))"))
            }
            return .success(.staging(nextChunkIndex: next))
        default:
            return .failure(AttachmentRPCError(text: "回执 state 未识别：\(String(describing: dict).prefix(160))"))
        }
    }

    /// 分块下发。成功值 = 回执 nextChunkIndex（调用方校验 === chunkIndex+1）。
    func attachmentChunkV4(
        sessionID: String, uploadId: String, chunkIndex: Int, dataBase64: String
    ) async -> Result<Int, AttachmentRPCError> {
        guard let connection else { return .failure(AttachmentRPCError(text: Self.attachmentNotConnectedText)) }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: sessionID)
        builder.set("uploadId", uploadId)
        builder.set("sessionId", sessionID)
        builder.set("chunkIndex", chunkIndex)
        builder.set("dataBase64", dataBase64)
        // 参数层 strict 校验（含 chunk base64 自带 padding + ≤512KiB 解码上限——
        // A11 不变量本地前移）
        let paramCheck = PValidator.validate(
            .object(builder.fields),
            schema: .object(CommandSchemas.attachmentChunkParamsShape()),
            path: "attachmentChunkV4")
        guard paramCheck.isValid else {
            return .failure(AttachmentRPCError(
                text: "参数校验失败：" + paramCheck.errors.joined(separator: "; ")))
        }
        do {
            let ack = try await connection.call(
                "zcode-agent", "attachmentChunkV4", .json(.object(builder.fields)))
            return Self.parseNextChunkIndex(ack.jsonValue)
        } catch {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            do {
                let ack = try await connection.call(
                    "zcode-agent", "attachmentChunkV4", .json(.object(builder.fields)))
                return Self.parseNextChunkIndex(ack.jsonValue)
            } catch {
                return .failure(AttachmentRPCError(text: Self.attachmentWireError(error)))
            }
        }
    }

    /// Chunk 回执解析：`{uploadId, nextChunkIndex}`（strict）——仅取进度游标
    nonisolated private static func parseNextChunkIndex(_ json: JSONValue?) -> Result<Int, AttachmentRPCError> {
        guard let next = json?.objectValue?["nextChunkIndex"]?.intValue else {
            return .failure(AttachmentRPCError(text: "回执缺 nextChunkIndex：\(String(describing: json).prefix(160))"))
        }
        return .success(next)
    }

    /// 事务收口。成功值 = 回执 ref（strict `{ref}`——sendText attachments 携带值）。
    func attachmentCommitV4(sessionID: String, uploadId: String) async -> Result<String, AttachmentRPCError> {
        guard let connection else { return .failure(AttachmentRPCError(text: Self.attachmentNotConnectedText)) }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: sessionID)
        builder.set("uploadId", uploadId)
        builder.set("sessionId", sessionID)
        let paramCheck = PValidator.validate(
            .object(builder.fields),
            schema: .object(CommandSchemas.attachmentFinishParamsShape()),
            path: "attachmentCommitV4")
        guard paramCheck.isValid else {
            return .failure(AttachmentRPCError(
                text: "参数校验失败：" + paramCheck.errors.joined(separator: "; ")))
        }
        do {
            let ack = try await connection.call(
                "zcode-agent", "attachmentCommitV4", .json(.object(builder.fields)))
            Self.attachmentDiag(
                method: "commit", detail: String(describing: ack.jsonValue).prefix(400).description)
            guard let ref = ack.jsonValue?.objectValue?["ref"]?.stringValue, !ref.isEmpty else {
                return .failure(AttachmentRPCError(text: "回执缺 ref：\(String(describing: ack.jsonValue).prefix(160))"))
            }
            return .success(ref)
        } catch {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            do {
                let ack = try await connection.call(
                    "zcode-agent", "attachmentCommitV4", .json(.object(builder.fields)))
                guard let ref = ack.jsonValue?.objectValue?["ref"]?.stringValue, !ref.isEmpty else {
                    return .failure(AttachmentRPCError(text: "回执缺 ref：\(String(describing: ack.jsonValue).prefix(160))"))
                }
                return .success(ref)
            } catch {
                return .failure(AttachmentRPCError(text: Self.attachmentWireError(error)))
            }
        }
    }

    /// 失败路径中止（web PCe catch 分支同款：Begin 成功后的事务失败尽力收口）。
    /// Abort 失败不掩盖原始错误——调用方将「中止未送达」并入失败提示，孤儿事务由
    /// 桌面端超时回收。
    func attachmentAbortV4(sessionID: String, uploadId: String) async -> Result<Void, AttachmentRPCError> {
        guard let connection else { return .failure(AttachmentRPCError(text: Self.attachmentNotConnectedText)) }
        var builder = JSONObjectBuilder()
        applySessionTarget(&builder, sessionID: sessionID)
        builder.set("uploadId", uploadId)
        builder.set("sessionId", sessionID)
        let paramCheck = PValidator.validate(
            .object(builder.fields),
            schema: .object(CommandSchemas.attachmentFinishParamsShape()),
            path: "attachmentAbortV4")
        guard paramCheck.isValid else {
            return .failure(AttachmentRPCError(
                text: "参数校验失败：" + paramCheck.errors.joined(separator: "; ")))
        }
        do {
            _ = try await connection.call(
                "zcode-agent", "attachmentAbortV4", .json(.object(builder.fields)))
            return .success(())
        } catch {
            return .failure(AttachmentRPCError(text: Self.attachmentWireError(error)))
        }
    }

    /// 附件读取诊断开关（e2e 可关；默认开，验收后移除）
    private enum DiagnosticAttachmentFlag { static let enabled = true }

    // MARK: workspace-config 只读投影（ChatView chips 数据源）

    func workspaceConfig() async -> WorkspaceConfigInfo? {
        await ensureWorkspaceConfigHandler()
        if workspaceConfigState.options.isEmpty, workspaceConfigState.slashCommands.isEmpty {
            return nil
        }
        return workspaceConfigState
    }

    /// 模型可用思考档。**优先 getView per-model 词表**（web v4 工具栏 yB 同构——
    /// `providers[].models[].config.optionSpecs.reasoningLevel.values`【实证·上游仓
    /// provider/facades.ts + bundle】）；workspace-config 词表退居次位：本机桌面
    /// v3.14.4 对手机不推 workspace-config（桌面日志 2026-10-07 零服务痕迹、
    /// §6「旧版本不支持时订阅静默失败」实证），留作新桌面兜底。入参为裸 modelId
    /// （chips/新建 sheet 同口径；getView label 相异时已双键）。
    func thoughtLevels(for model: String) async -> [String] {
        if let levels = modelSelectionCache?.thoughtByModel[model], !levels.isEmpty {
            return levels
        }
        await ensureWorkspaceConfigHandler()
        return workspaceConfigThoughtByModel[model] ?? []
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
        // 思考档词表源（web 端 Wxt：type=select 的 configOption.options，或 values[] 对象形态
        // {value, modelThoughtLevels,...}，_ce schema 同源）；供 chips 思考菜单使用
        for option in config["configOptions"]?.arrayValue ?? [] {
            guard let optionDict = option.objectValue else { continue }
            let entries = (optionDict["options"]?.arrayValue ?? [])
                + (optionDict["values"]?.arrayValue ?? [])
            for value in entries {
                guard let v = value.objectValue else { continue }
                let levels = v["modelThoughtLevels"]?.arrayValue?.compactMap(\.stringValue) ?? []
                guard !levels.isEmpty else { continue }
                // 键对齐【实证·上游仓 model-selection.ts:34 formatModelPickerValue】：
                // configOptions 模型条目 value 为复合串 `providerId/modelId[$reasoningLevel]`
                // （如 "account:zai-start-plan/GLM-5.3-Flash"），chips/新建 sheet 均以裸
                // modelId 查询——曾按复合串整串建键，查询必 miss → 思考档退化 getView/
                // 静态梯（静态梯含 medium，GLM-5.3 系列 variants 仅 [low,max,high]，首条
                // turn model_creation 被拒 Reasoning effort "medium" not supported，真机
                // 报障 2026-10-07 sess_ce4531a1）
                let rawKey = v["value"]?.stringValue ?? v["modelId"]?.stringValue
                if let modelId = Self.configOptionModelId(rawKey) {
                    workspaceConfigThoughtByModel[modelId] = levels
                }
            }
        }
        // 每次应用覆盖写（首个快照可能为空，config.updated 随后补全）
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            var shape = "options=\(config["configOptions"]?.arrayValue?.count ?? 0)"
            for option in config["configOptions"]?.arrayValue ?? [] {
                guard let d = option.objectValue else { continue }
                let optionValues = (d["options"]?.arrayValue ?? []).compactMap {
                    $0.objectValue?["value"]?.stringValue ?? $0.stringValue
                }
                shape += " | id=\(d["id"]?.stringValue ?? "?") type=\(d["type"]?.stringValue ?? "?") opts=[\(optionValues.prefix(8).joined(separator: ","))]"
            }
            shape += " || thoughtByModel=\(workspaceConfigThoughtByModel.map { "\($0.key.prefix(12)):\($0.value)" }.joined(separator: ",").prefix(300))"
            // slashCommands 取证（斜杠命令面数据源：桌面 config 下发的命令清单；
            // 空列表 = 该桌面未推送，移动端斜杠菜单退化为内建集）
            let pushed = (config["slashCommands"]?.arrayValue ?? []).compactMap {
                $0.objectValue?["name"]?.stringValue
            }
            shape += " || slash=\(pushed.joined(separator: ","))"
            UserDefaults.standard.set(String(shape.prefix(1600)), forKey: "diag.wc.dump")
            UserDefaults.standard.synchronize()
        }
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
                  let rawName = dict["name"]?.stringValue else { continue }
            // 名字归一化【实证·上游仓 slashCommandHelpers.normalizeSlashCommandValue】：
            // CLI 可能直接下发 "/init" 形态（带前导斜杠），不剥离则菜单渲染成
            // "//init"、前缀过滤永不命中——上游同款 strip 前导斜杠
            let name = rawName.trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: "^/+", with: "", options: .regularExpression)
            guard !name.isEmpty else { continue }
            info.slashCommands.append(WorkspaceConfigInfo.SlashCommand(
                name: name,
                description: dict["description"]?.stringValue ?? ""))
        }
        workspaceConfigState = info
    }

    /// configOptions 模型条目键归一为裸 modelId：`providerId/modelId` /
    /// `providerId/modelId$reasoningLevel`（$ 为档位分隔符，上游仓
    /// model-selection.ts:31 ZCODE_MODEL_REASONING_SEPARATOR）→ 取首个 `/` 之后、
    /// 剥离 `$` 档位后缀；无 `/` 按裸 modelId 原样返回（宽容旧桌面纯字符串形态）。
    /// 拆分规则逐字对齐上游 parseModelPickerValue（model-selection.ts:43）：
    /// `$` 在段首/段尾不视为分隔符，整段作 modelId。
    private static func configOptionModelId(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        guard let slash = raw.firstIndex(of: "/") else { return raw }
        let modelPart = raw[raw.index(after: slash)...]
        guard let dollar = modelPart.firstIndex(of: "$"),
              dollar != modelPart.startIndex,
              modelPart.index(after: dollar) != modelPart.endIndex else {
            return String(modelPart)
        }
        return String(modelPart[modelPart.startIndex..<dollar])
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
            // 一次性取证：providers[] 原始形态 + 顶层思考档字段定位（验收后移除）
            if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil,
               UserDefaults.standard.string(forKey: "diag.ms.dump") == nil {
                let providerSummaries = (view["providers"]?.arrayValue ?? []).map { provider -> String in
                    guard let d = provider.objectValue else { return "?" }
                    let ids = (d["models"]?.arrayValue ?? []).compactMap { $0.objectValue?["modelId"]?.stringValue ?? $0.stringValue }
                    let levels = d["models"]?.arrayValue?.compactMap {
                        $0.objectValue?["modelThoughtLevels"]?.arrayValue?.compactMap(\.stringValue)
                    } ?? []
                    return "pid=\(d["providerId"]?.stringValue ?? "?") models=\(ids) modelLevels=\(levels)"
                }
                let thoughtish = view.filter { key, _ in
                    let k = key.lowercased()
                    return k.contains("thought") || k.contains("reason") || k.contains("option") || k.contains("level")
                }.map { "\(String(describing: $1).prefix(300))" }.joined(separator: " ¦ ")
                UserDefaults.standard.set(
                    "topKeys=\(view.keys.sorted().joined(separator: "|")) thoughtish=\(thoughtish.prefix(900)) | \(providerSummaries.joined(separator: " | ").prefix(700))",
                    forKey: "diag.ms.dump")
                UserDefaults.standard.synchronize()
            }
            let info = Self.parseModelSelectionView(view)
            // 空清单不入缓存：缓存命中即短路重拉（空清单锁死会让重试面永远命中
            // 空缓存——模型/思考可选性修复的兜底面）；成功非空才广播
            if !info.models.isEmpty {
                modelSelectionCache = info
                yieldModelSelection()
            }
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
        var modelProviders: [String: String] = [:]
        // 套餐分组：providerId → 组名（web 端 model picker 同口径；provider 顺序保持）
        var groupsByPlan: [String: [String]] = [:]
        var planOrder: [String] = []
        func planName(for providerId: String) -> String? {
            if providerId.contains("individual-coding-plan") { return String(localized: "个人套餐") }
            if providerId.contains("start-plan") { return String(localized: "体验套餐") }
            if providerId.contains("team-coding-plan") { return String(localized: "团队套餐") }
            return nil
        }
        for provider in view["providers"]?.arrayValue ?? [] {
            guard let providerDict = provider.objectValue else { continue }
            let providerId = providerDict["providerId"]?.stringValue ?? ""
            let plan = planName(for: providerId)
            // 思考档兜底源：provider config.optionSpecs.reasoningLevel.values
            // （模型条目 modelThoughtLevels 缺席时的权威词表）
            let config = providerDict["config"]?.objectValue
                ?? providerDict["effectiveConfig"]?.objectValue
            for level in config?["optionSpecs"]?.objectValue?["reasoningLevel"]?
                .objectValue?["values"]?.arrayValue ?? [] {
                if let levelName = level.stringValue, !thoughtLevels.contains(levelName) {
                    thoughtLevels.append(levelName)
                }
            }
            for model in providerDict["models"]?.arrayValue ?? [] {
                var label: String?
                if let name = model.stringValue {
                    label = name
                } else if let modelDict = model.objectValue {
                    label = modelDict["label"]?.stringValue
                        ?? modelDict["name"]?.stringValue
                        ?? modelDict["modelId"]?.stringValue
                        ?? modelDict["id"]?.stringValue
                    // per-model 思考档词表【实证·上游仓 provider/facades.ts
                    // ModelSelectionModelView `{modelId, config}`；web v4 工具栏
                    // yB 唯一数据源 `config.optionSpecs.reasoningLevel.values`
                    // （bundle 逆向同构）】——本机桌面 v3.14.4 对手机不推
                    // workspace-config（桌面日志零服务痕迹），getView 是唯一可用
                    // 词表源；provider 级 optionSpecs 是旧猜测形态，仅并入并集兜底。
                    // modelId 为权威键；label 相异时双键（sheet 以 label 展示/查询）
                    if let modelId = modelDict["modelId"]?.stringValue {
                        var perModel = (modelDict["config"]?.objectValue?["optionSpecs"]?
                            .objectValue?["reasoningLevel"]?.objectValue?["values"]?
                            .arrayValue ?? []).compactMap(\.stringValue)
                        if perModel.isEmpty {
                            perModel = (modelDict["modelThoughtLevels"]?.arrayValue ?? [])
                                .compactMap(\.stringValue)
                        }
                        if !perModel.isEmpty {
                            info.thoughtByModel[modelId] = perModel
                            if let label, label != modelId {
                                info.thoughtByModel[label] = perModel
                            }
                        }
                    }
                    for level in modelDict["modelThoughtLevels"]?.arrayValue ?? [] {
                        if let levelName = level.stringValue, !thoughtLevels.contains(levelName) {
                            thoughtLevels.append(levelName)
                        }
                    }
                }
                guard let label else { continue }
                if !models.contains(label) { models.append(label) }
                if !providerId.isEmpty { modelProviders[label] = providerId }
                if let plan {
                    if groupsByPlan[plan] == nil { planOrder.append(plan) }
                    // 同组内去重，跨组保留（同一模型走不同套餐配额）
                    if !(groupsByPlan[plan]?.contains(label) ?? false) {
                        groupsByPlan[plan, default: []].append(label)
                    }
                }
            }
        }
        info.models = models
        info.thoughtLevels = thoughtLevels
        info.planGroups = planOrder.map { ModelPlanGroup(plan: $0, models: groupsByPlan[$0] ?? []) }
        info.modelProviders = modelProviders
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
    /// 时间字段宽容解析：sessions-index 行 lastActivityAt（毫秒数优先，ISO 字符串
    /// 兜底；中继快照实测 1791040344222 / 替身 ISO 双形态）；bootstrap.tasks /
    /// listTaskList 行上游 zcodeTaskMetaSchema **无 lastActivityAt**，携带
    /// updatedAt/createdAt（int 毫秒，validation.ts 实证）——缺此回退曾落
    /// distantPast，列表渲染「2025年前」（真机报障 2026-10-07）
    static func parseLastActivityAt(_ dict: [String: JSONValue]) -> Date? {
        for key in ["lastActivityAt", "updatedAt", "createdAt"] {
            if let ms = dict[key]?.intValue, ms > 0 {
                return Date(timeIntervalSince1970: Double(ms) / 1000)
            }
            if let iso = dict[key]?.stringValue {
                let formatter = ISO8601DateFormatter()
                formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
                if let date = formatter.date(from: iso) { return date }
                if let date = ISO8601DateFormatter().date(from: iso) { return date }
            }
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
        // listArchivedTasks 行已有 workspacePath 字段先例）。relay 下发的 sessions-index 行实测只带
        // workspaceId（2026-10-05 全键取证）——它即工作区路径型 id，作为分组键兜底。
        // 缺席 = 无法判定归属 → nil → 「其它」组
        summary.workspacePath = dict["workspacePath"]?.stringValue
            ?? dict["workspace"]?.stringValue
            ?? dict["workspace"]?.objectValue?["path"]?.stringValue
            ?? dict["workspaceId"]?.stringValue
        // G-007 通路 A：行自带 workflowActivity（sessionWorkflowActivitySchema；无 run 时缺席）
        summary.workflowActivity = dict["workflowActivity"]
        if UserDefaults.standard.string(forKey: "diag.wf.mode") != nil {
            let raw = dict["workflowActivity"].map { String(describing: $0).prefix(700) } ?? "缺席"
            UserDefaults.standard.set(
                "session=\(sessionId.prefix(20)) workspaceId=\(dict["workspaceId"]?.stringValue ?? "缺席") workflowActivity=\(raw) · 全键=\(dict.keys.sorted().prefix(16))",
                forKey: "diag.wf.activity.\(sessionId.prefix(14))")
        }
        return summary
    }
}

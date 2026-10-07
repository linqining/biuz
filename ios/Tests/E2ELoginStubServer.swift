import Foundation
import Network
import CommonCrypto

// MARK: - 本地测试替身服务（登录 + API 接入 e2e 专用）
//
/// 单端口（127.0.0.1 随机端口）同时承载三面协议，与被测 App 走真实网络栈：
/// ① HTTP：`GET /authorize` 与 `GET /api/oauth/authorize` 假授权页（302 → zcode://oauth/callback）；
///          `POST /api/v1/oauth/token` 令牌交换（成功 / code≠0 / HTTP 401 三分支）；
///          `GET /api/server-info?token=…` 配对探测。
/// ② WebSocket：`GET /ws?token=…` 升级（手工 101 握手），承载 zcode RPC 帧协议
///          （13 字节头 + 自定义序列化，对齐 Sources/Services/RPC/ 的 Swift 移植口径）。
/// ③ v4 帧：`helloConversationV4` / `initializeConversationV4` 握手、
///          `subscribeSessionsIndexV4` 快照事件、`conversationRowsRangeV4` 历史
///          （记录 (sessionId, beforeRowId) 游标）、`readSession` 只读对账回执、
///          `sendConversationCommandV4`（createSession 空会话 / 带 firstInput 旧形态）回执；
///          execution 分类命令计数（连接态只读边界断言：恒为 0）；
///          `failConversationSubscribe` 开关可拒绝 conversation 订阅（驱动 readSession 兜底）。
/// ④ 四项补全面（FeatureCompletionE2ETests 数据源）：
///          `GET /api/v1/relay/devices` 设备清单/中继链接契约面（账号级设备 API 未上线，
///          App 未消费，供链接形态断言与后续接入）；server-info workspaces 两项（项目
///          选择页多候选）；createSession 记录 payload.workspaceId（项目层下发断言）；
///          sess-e2e-plan 行级 plan 行（任务拆解卡）、sess-e2e-think 首窗 reasoning 行
///          （思考折叠默认收起）。
///
/// 线程模型：全部连接回调在私有串行队列；对外状态读写经 NSLock，供用例线程直接断言。
/// 生命周期：用例 setUp 启动（随机端口）、tearDown 关闭；每个用例拿到全新状态，无顺序依赖。
final class E2ELoginStubServer {

    // MARK: 对外可配置状态（用例内直接赋值）

    /// 令牌交换分支
    enum TokenExchangeMode: Equatable {
        case success                                   // {code:0, data:{token, zai.access_token, expires_in, user}}
        case businessError(code: Int, message: String) // HTTP 200 但 code≠0
        case http401                                   // HTTP 401
    }

    struct RecordedRequest {
        let method: String
        let path: String
        let query: [String: String]
        let headers: [String: String]
        let body: String
    }

    /// 凭据（经启动参数/表单注入被测 App 的「stub 凭据」与之对应）
    let pairingToken: String
    let oauthCode: String

    /// 令牌交换模式（用例内可切换，驱动异常分支）
    var exchangeMode: TokenExchangeMode {
        get { lock.lock(); defer { lock.unlock() }; return _exchangeMode }
        set { lock.lock(); _exchangeMode = newValue; lock.unlock() }
    }
    /// 假授权页是否篡改 state（回发 `<原state>-tampered`，驱动 state 不匹配拒绝）
    var tamperState: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _tamperState }
        set { lock.lock(); _tamperState = newValue; lock.unlock() }
    }
    /// true 时授权页返回 200 HTML 而非立即 302（页面驻留，供「用户取消」用例稳定点击 ✕）
    var holdAuthorizePage: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _holdAuthorizePage }
        set { lock.lock(); _holdAuthorizePage = newValue; lock.unlock() }
    }

    // MARK: 请求/事件记录（用例断言用）

    /// 全部 HTTP 请求（按到达顺序，含鉴权失败请求）
    var requests: [RecordedRequest] { lock.lock(); defer { lock.unlock() }; return _requests }
    /// 已应答的订阅 topic（sessions-index / conversation）
    var subscribedTopics: [String] { lock.lock(); defer { lock.unlock() }; return _subscribedTopics }
    /// subscribeConversationV4 请求的 workspace 寻址记录（topic → 携带的
    /// workspacePath/workspaceIdentity）：会话归属寻址回归断言面（真机报障
    /// 2026-10-07「手机端没有回复」——订阅恒用连接 workspace，跨工作区会话行增量
    /// 永不抵达；修复后必须携带会话行自带 workspacePath）
    var subscribeTargets: [(topic: String, workspacePath: String, workspaceIdentity: String?)] {
        lock.lock(); defer { lock.unlock() }; return _subscribeTargets
    }
    /// sessions-index 快照事件已下发次数
    var sessionsIndexEventFires: Int { lock.lock(); defer { lock.unlock() }; return _sessionsIndexEventFires }
    /// conversation 增量事件已下发次数
    var conversationEventFires: Int { lock.lock(); defer { lock.unlock() }; return _conversationEventFires }
    /// 配对鉴权失败次数（server-info / WS 升级携带错误令牌）
    var pairingAuthFailures: Int { lock.lock(); defer { lock.unlock() }; return _pairingAuthFailures }
    /// 最近一次通过鉴权的配对令牌
    var lastAcceptedPairingToken: String? { lock.lock(); defer { lock.unlock() }; return _lastAcceptedPairingToken }
    /// WS 连接建立（101 完成）次数
    var websocketUpgrades: Int { lock.lock(); defer { lock.unlock() }; return _websocketUpgrades }
    /// 收到的「仍被边界拦截的直写/配置写」命令数（v3 纠偏口径：file 频道非读白名单命令、
    /// applyFileRewind、git 写 8 项、terminal 4 项、zcode-task set*/restart、zcode-agent
    /// 直写族等；边界断言：连接态恒为 0。sendText/resolveInteraction/stop 等桌面代执行
    /// 命令不再计入——它们是边界内合法面）
    var blockedWriteCommandCount: Int { lock.lock(); defer { lock.unlock() }; return _blockedWriteCommandCount }
    /// 收到的 sendText 数（P0 纠偏核心断言：连接态发送必须真实到达替身，>0）
    var sendTextCount: Int { lock.lock(); defer { lock.unlock() }; return _sendTextCount }
    /// 收到的 resolveInteraction 数（审批/提问应答到达断言）
    var resolveInteractionCount: Int { lock.lock(); defer { lock.unlock() }; return _resolveInteractionCount }
    /// 最近一次 resolveInteraction 的信封（interactionId/answer payload 断言）
    var lastResolveInteraction: (interactionId: String, payload: [String: Any])? {
        lock.lock(); defer { lock.unlock() }; return _lastResolveInteraction
    }
    /// 收到的 v4 stop 数（停止任务到达断言）
    var stopCount: Int { lock.lock(); defer { lock.unlock() }; return _stopCount }
    /// 收到的 createSession 总数（连接态允许）
    var createSessionCount: Int { lock.lock(); defer { lock.unlock() }; return _createSessionCount }
    /// 收到的「携带 firstInput」createSession 数（P1-4 断言：新建会话带指令 ≥1）
    var createSessionWithFirstInputCount: Int { lock.lock(); defer { lock.unlock() }; return _createSessionWithFirstInputCount }
    /// 各次 createSession 携带的 workspaceId（按到达顺序；项 2 项目层选择断言：
    /// NewConversationSheet 项目胶囊 → createConversation directory → payload.workspaceId）
    var createSessionWorkspaceIds: [String] { lock.lock(); defer { lock.unlock() }; return _createSessionWorkspaceIds }
    /// 最近一次 createSession 的 workspaceId（nil = 未携带）
    var lastCreateSessionWorkspaceId: String? {
        lock.lock(); defer { lock.unlock() }
        return _createSessionWorkspaceIds.last
    }
    /// 最近一次 createSession 的 firstInput.modelSelection（会话前模型选择断言：
    /// NewConversationSheet 模型/思考等级行 → {providerId, modelId, options:{reasoningLevel}}；
    /// 元素为 StubRPC（objectValue 产物），断言经 stringValue/objectValue 提取）
    var lastCreateSessionModelSelection: [String: StubRPC]? {
        lock.lock(); defer { lock.unlock() }
        return _lastCreateSessionModelSelection
    }
    /// 设备清单/中继链接 API（替身侧契约面）被请求次数（项 1 断言）
    var relayDeviceListRequests: Int { lock.lock(); defer { lock.unlock() }; return _relayDeviceListRequests }
    /// 替身形态中继配对链接（设备清单 API 返回值；host=127.0.0.1 回环安全）。
    /// 保持解析必需的最小形态（scheme https + /remote/ 路径 + sid + hash + name），
    /// hash 保留 URL 编码的 base64 尾形（%3D）；mid/t/app_version 为可选参数不携带，
    /// 亦降低 e2e 长串 typeText 的丢字面
    var stubRelayLink: String {
        "https://127.0.0.1/remote/v4?sid=stub-relay-sid-e2e-4242&hash=stubRelayHash%3D&name=E2E-Relay-Mac"
    }
    /// 收到的 renameTask / unarchiveTask / listArchivedTasks 计数（P1-5/P1-6 断言）
    var renameTaskCount: Int { lock.lock(); defer { lock.unlock() }; return _renameTaskCount }
    var unarchiveTaskCount: Int { lock.lock(); defer { lock.unlock() }; return _unarchiveTaskCount }
    var listArchivedTasksCount: Int { lock.lock(); defer { lock.unlock() }; return _listArchivedTasksCount }
    /// 最近一次 renameTask 的新标题
    var lastRenameTitle: String? { lock.lock(); defer { lock.unlock() }; return _lastRenameTitle }
    /// 收到的 git 读面命令数（getChanges / getDiff；diff 页只读展示闭环断言用）
    var gitReadCommandCount: Int { lock.lock(); defer { lock.unlock() }; return _gitReadCommandCount }
    /// 收到的 zcode-task 读面命令数（listTaskList；任务列表闭环断言用）
    var taskReadCommandCount: Int { lock.lock(); defer { lock.unlock() }; return _taskReadCommandCount }
    /// 收到的 resyncV4 调用（conversation/sessions-index/workspace-config 的 subscriptionId；丢帧自愈断言用）
    var resyncCalls: [String] { lock.lock(); defer { lock.unlock() }; return _resyncCalls }
    /// RPC promise 调用序（(channel, command)；git.refresh → git.getChanges 时序断言用）
    var rpcCallLog: [(String, String)] { lock.lock(); defer { lock.unlock() }; return _rpcCallLog }
    /// git.refresh 调用次数
    var gitRefreshCount: Int { lock.lock(); defer { lock.unlock() }; return _gitRefreshCount }
    /// file-watcher watch/unwatch/disposeAll 计数与最近 watch 参数
    var fileWatcherWatchCount: Int { lock.lock(); defer { lock.unlock() }; return _fileWatcherWatchCount }
    var fileWatcherUnwatchCount: Int { lock.lock(); defer { lock.unlock() }; return _fileWatcherUnwatchCount }
    var fileWatcherDisposeAllCount: Int { lock.lock(); defer { lock.unlock() }; return _fileWatcherDisposeAllCount }
    var lastWatchRequest: (path: String, recursive: Bool)? { lock.lock(); defer { lock.unlock() }; return _lastWatchRequest }
    /// setTaskPinned 记录（taskId → pinned；置顶持久化断言 + sessions-index 回推投影）
    var recordedPinned: [String: Bool] { lock.lock(); defer { lock.unlock() }; return _recordedPinned }
    /// setTaskUnread / archiveTask 计数
    var setTaskUnreadCount: Int { lock.lock(); defer { lock.unlock() }; return _setTaskUnreadCount }
    var archiveTaskCount: Int { lock.lock(); defer { lock.unlock() }; return _archiveTaskCount }
    /// zcode-session.promoteDeferredDraftSession 计数（draft 转正断言）
    var promoteDeferredDraftCount: Int { lock.lock(); defer { lock.unlock() }; return _promoteDeferredDraftCount }
    /// conversationRowsRangeV4 请求记录（(sessionId, beforeRowId)；向上分页游标断言）
    var rowsRangeRequests: [(sessionId: String, beforeRowId: Int?)] {
        lock.lock(); defer { lock.unlock() }; return _rowsRangeRequests
    }
    /// true 时 subscribeConversationV4 回 promiseError（驱动客户端走 readSession 兜底对账）
    var failConversationSubscribe: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _failConversationSubscribe }
        set { lock.lock(); _failConversationSubscribe = newValue; lock.unlock() }
    }
    /// subscribeConversationV4 被替身拒绝（promiseError）次数
    var conversationSubscribeRejections: Int {
        lock.lock(); defer { lock.unlock() }; return _conversationSubscribeRejections
    }
    // MARK: 附件事务断言面（A-4）
    var attachmentBeginCount: Int { lock.lock(); defer { lock.unlock() }; return _attachmentBeginCount }
    var attachmentChunkCount: Int { lock.lock(); defer { lock.unlock() }; return _attachmentChunkCount }
    var attachmentCommitCount: Int { lock.lock(); defer { lock.unlock() }; return _attachmentCommitCount }
    var attachmentAbortCount: Int { lock.lock(); defer { lock.unlock() }; return _attachmentAbortCount }
    var attachmentShapeErrors: [String] { lock.lock(); defer { lock.unlock() }; return _attachmentShapeErrors }
    var lastGoalCommandText: String? { lock.lock(); defer { lock.unlock() }; return _lastGoalCommandText }
    var compactCommandCount: Int { lock.lock(); defer { lock.unlock() }; return _compactCommandCount }
    /// 最近一次 sendText 携带的 attachments 数组（元素键 {ref, fileName, mime, bytes} 断言）
    var lastSendTextAttachments: [[String: Any]] {
        lock.lock(); defer { lock.unlock() }; return _lastSendTextAttachments
    }
    var switchModelConfigCallCount: Int {
        lock.lock(); defer { lock.unlock() }; return _switchModelConfigCallCount
    }
    var pinnedTasksRequestCount: Int {
        lock.lock(); defer { lock.unlock() }; return _pinnedTasksRequestCount
    }
    var archivedTasksRequestCount: Int {
        lock.lock(); defer { lock.unlock() }; return _archivedTasksRequestCount
    }
    /// usage-stats 频道重置三步 RPC 到达记录（test13：确认后必发、取消必不发）
    var resetCardRPCCalls: [String] {
        lock.lock(); defer { lock.unlock() }; return _resetCardRPCCalls
    }
    /// useCodingPlanReset 的 resetType 序列（确认路径应恰含 FIVE_HOUR/WEEK 一次）
    var resetCardUseTypes: [String] {
        lock.lock(); defer { lock.unlock() }; return _resetCardUseTypes
    }

    /// 任一活跃连接是否监听了指定事件（onError / onDynamicChange 订阅断言用）
    func hasEventListener(_ event: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return channels.contains { channel in
            channel.eventId(for: event) != nil
        }
    }

    // MARK: 用例主动触发的下行事件（活性/自愈断言）

    /// onDynamicTaskEvent 帧实际送达的活跃连接数（区分「发出」与「到达 App」）
    var taskEventFireHits: Int { lock.lock(); defer { lock.unlock() }; return _taskEventFireHits }

    /// 下发 onDynamicTaskEvent（任务状态免刷新更新断言；仅在客户端已订阅时生效）。
    /// 只发活跃（open）channel：relaunch 后僵尸连接仍持有旧 eventListen id，若不过滤
    /// 会出现「命中僵尸 channel 的 hasEventListener=true 而活跃连接未注册」的假阳性。
    func fireTaskEvent(status: String) {
        lock.lock()
        let all = channels
        lock.unlock()
        for channel in all {
            guard channel.open, let eventId = channel.eventId(for: "onDynamicTaskEvent") else { continue }
            var task = Self.stubTasks[0]
            task["status"] = status
            let payload: [String: Any] = [
                "type": "task_status_changed", "taskId": task["taskId"] as? String ?? "", "task": task,
            ]
            channel.sendWSFrame(rpcFrame(header: [.int(204), .int(eventId)], body: StubRPC.object(payload)))
            lock.lock()
            _taskEventFireHits += 1
            lock.unlock()
        }
    }

    /// 下发 workspace-config 快照帧（chips 真实数据断言）
    func fireWorkspaceConfigFrame(workspacePath: String) {
        lock.lock()
        let all = channels
        lock.unlock()
        let topic = "workspace-config/\(workspacePath)"
        for channel in all {
            guard let eventId = channel.eventId(for: "onDynamicWorkspaceConfigFrame") else { continue }
            let frame: [String: Any] = [
                "wireVersion": 1,
                "kind": "complete",
                "logicalFrameId": UUID().uuidString,
                "logicalFrameOrdinal": 0,
                "topic": topic,
                "subscriptionId": "sub-wsconfig-e2e",
                "frame": [
                    "topic": topic,
                    "subscriptionId": "sub-wsconfig-e2e",
                    "fromSeq": 0, "toSeq": 1,
                    "sentAt": Self.iso8601.string(from: Date()),
                    "payload": ["kind": "snapshot", "snapshot": [
                        "protocolVersion": 1,
                        "workspaceId": workspacePath,
                        "logEpoch": "epoch-e2e-1",
                        "config": [
                            "configOptions": [
                                ["id": "model", "name": "模型", "type": "select", "currentValue": "GLM-5.3",
                                 "options": [["value": "GLM-5.3", "name": "GLM-5.3"], ["value": "GLM-5", "name": "GLM-5"]]],
                                ["id": "thought_level", "name": "思考档", "type": "select", "currentValue": "high"],
                            ],
                            "slashCommands": [["name": "/compact", "description": "压缩会话上下文"]],
                        ],
                    ]],
                ],
            ]
            channel.sendWSFrame(rpcFrame(header: [.int(204), .int(eventId)], body: StubRPC.object(frame)))
        }
    }

    /// 下发 zcode-task.onError（连接日志面板诊断断言）
    func fireOnError(message: String) {
        lock.lock()
        let all = channels
        lock.unlock()
        for channel in all {
            guard let eventId = channel.eventId(for: "onError") else { continue }
            channel.sendWSFrame(rpcFrame(header: [.int(204), .int(eventId)],
                                         body: StubRPC.object(["message": message])))
        }
    }

    /// 强制掐断全部已升级的 WS 通道（断线自愈用例：模拟桌面端断网/进程退出）。
    /// 直接 cancel 底层 TCP 连接（不发 close 帧）——客户端传输读失败走
    /// onClosed → AppSession .disconnected 断线横幅断言的触发源。
    func dropAllWebSocketChannels() {
        lock.lock()
        let all = channels
        lock.unlock()
        for channel in all where channel.isWebSocketUpgraded {
            channel.shutdown()
            removeChannel(channel)
        }
    }

    /// 注入坏 checksum 的 fragment 物理帧（assembler dropped → 客户端 resync 断言）。
    /// payload 本体是合法 JSON，但 checksum 与实际 crc 不符 → 客户端必须丢弃整条逻辑帧。
    func fireBadChecksumFragment(topic: String) {
        lock.lock()
        let all = channels
        lock.unlock()
        for channel in all {
            guard let eventId = channel.eventId(for: "onDynamicSessionsIndexFrame") else { continue }
            let logical = try! JSONSerialization.data(
                withJSONObject: ["topic": topic, "subscriptionId": "sub-bad", "fromSeq": 9, "toSeq": 10],
                options: [.sortedKeys])
            let frame: [String: Any] = [
                "wireVersion": 1,
                "kind": "fragment",
                "logicalFrameId": UUID().uuidString,
                "logicalFrameOrdinal": 0,
                "topic": topic,
                "subscriptionId": "sub-bad",
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "logicalBytes": logical.count,
                "checksum": ["algorithm": "crc32", "value": "00000000"],
                "dataBase64": logical.base64EncodedString(),
            ]
            channel.sendWSFrame(rpcFrame(header: [.int(204), .int(eventId)], body: StubRPC.object(frame)))
        }
    }

    // MARK: 私有状态

    private let lock = NSLock()
    private var _exchangeMode: TokenExchangeMode = .success
    private var _tamperState = false
    private var _holdAuthorizePage = false
    private var _requests: [RecordedRequest] = []
    private var _subscribedTopics: [String] = []
    private var _subscribeTargets: [(topic: String, workspacePath: String, workspaceIdentity: String?)] = []
    private var _sessionsIndexEventFires = 0
    private var _conversationEventFires = 0
    private var _pairingAuthFailures = 0
    private var _lastAcceptedPairingToken: String?
    private var _websocketUpgrades = 0
    private var _blockedWriteCommandCount = 0
    private var _sendTextCount = 0
    private var _resolveInteractionCount = 0
    private var _lastResolveInteraction: (interactionId: String, payload: [String: Any])?
    private var _stopCount = 0
    private var _createSessionCount = 0
    private var _createSessionWithFirstInputCount = 0
    private var _createSessionWorkspaceIds: [String] = []
    private var _lastCreateSessionModelSelection: [String: StubRPC]?
    private var _relayDeviceListRequests = 0
    private var _renameTaskCount = 0
    private var _unarchiveTaskCount = 0
    private var _listArchivedTasksCount = 0
    private var _lastRenameTitle: String?
    private var _gitReadCommandCount = 0
    private var _taskReadCommandCount = 0
    private var _resyncCalls: [String] = []
    private var _rpcCallLog: [(String, String)] = []
    private var _gitRefreshCount = 0
    private var _fileWatcherWatchCount = 0
    private var _fileWatcherUnwatchCount = 0
    private var _fileWatcherDisposeAllCount = 0
    private var _lastWatchRequest: (path: String, recursive: Bool)?
    private var _recordedPinned: [String: Bool] = [:]
    private var _setTaskUnreadCount = 0
    private var _archiveTaskCount = 0
    private var _promoteDeferredDraftCount = 0
    private var _rowsRangeRequests: [(sessionId: String, beforeRowId: Int?)] = []
    private var _failConversationSubscribe = false
    private var _conversationSubscribeRejections = 0
    private var _taskEventFireHits = 0
    // MARK: 附件事务替身状态（A-4 链路验收：严格形状校验【实证·上游仓
    // attachmentUploadTransaction.ts + zcode-protocol-v4/transport.ts schema】）
    /// 事务进行中状态（uploadId → 已收块数游标/总块数/checksum）
    private struct AttachTxn {
        var nextChunkIndex: Int
        var totalChunks: Int
        var checksum: String
        var fileName: String
        var mime: String
        var totalBytes: Int
    }
    private var _attachmentTxns: [String: AttachTxn] = [:]
    /// checksum → 已 commit 的 ref（幂等 committed 短路回执数据源）
    private var _attachmentCommitted: [String: String] = [:]
    private var _attachmentBeginCount = 0
    private var _attachmentChunkCount = 0
    private var _attachmentCommitCount = 0
    private var _attachmentAbortCount = 0
    /// 形状校验失败记录（含「客户端多带 connectionId」回归绊线——web 客户端参数
    /// 无此键，桌面 facade 会剥掉伪造值，多发即与 web 不对齐）
    private var _attachmentShapeErrors: [String] = []
    private var _lastGoalCommandText: String?
    private var _compactCommandCount = 0
    private var _lastSendTextAttachments: [[String: Any]] = []
    // MARK: tasks-index membership / CAS 重试替身状态（v1.15 验收）
    /// switchModelConfig 收到次数（stale-once-then-accepted：第 1 次回 stale，第 ≥2 次
    /// accepted——验证客户端 stale 原样重发一次即命中，用户报障「胶囊切换都不行」）
    private var _switchModelConfigCallCount = 0
    private var _pinnedTasksRequestCount = 0
    private var _archivedTasksRequestCount = 0
    // MARK: 重置卡替身状态（test13 验收——重置卡为扣费接口，替身只在本地内存
    // 记账，真机/真实桌面零接触）
    /// usage-stats 频道重置三步 RPC 到达记录（按到达顺序：request/use/mark）
    private var _resetCardRPCCalls: [String] = []
    /// useCodingPlanReset 携带的 resetType（「确认后必发、取消必不发」断言源）
    private var _resetCardUseTypes: [String] = []
    // MARK: bots 域替身状态（G-001~G-006 验收：桌面 botsService 只读四方法 + 放行写）
    private var _botsRPCCalls: [String] = []
    private var _bindCodeRequests = 0
    private var _lastBindCode: String?
    private var _lastBindCodeTTLms = 30_000
    private var _lastSaveBot: (botId: String, droppedBinding: Bool, hasCredentialValue: Bool)?
    private var _deleteBotIds: [String] = []
    private var _removeSecretIds: [String] = []
    private var _resetStateIds: [String] = []
    /// 绑定码 TTL 可由测试调短（过期态刷新断言，避免等 30s）
    var stubBindCodeTTLms = 30_000
    /// bots 频道收到的命令名（按到达顺序；出口 ≥4 与写命令到达断言）
    var botsRPCCalls: [String] { lock.lock(); defer { lock.unlock() }; return _botsRPCCalls }
    var bindCodeRequests: Int { lock.lock(); defer { lock.unlock() }; return _bindCodeRequests }
    var lastBindCode: String? { lock.lock(); defer { lock.unlock() }; return _lastBindCode }
    var lastSaveBot: (botId: String, droppedBinding: Bool, hasCredentialValue: Bool)? {
        lock.lock(); defer { lock.unlock() }; return _lastSaveBot
    }
    var deleteBotIds: [String] { lock.lock(); defer { lock.unlock() }; return _deleteBotIds }
    var removeSecretIds: [String] { lock.lock(); defer { lock.unlock() }; return _removeSecretIds }
    var resetStateIds: [String] { lock.lock(); defer { lock.unlock() }; return _resetStateIds }
    /// bots 内存配置（saveBot/deleteBot/removeBotSecret 就地生效——listBots 回执随之变化，
    /// 验收「解绑后 getBotStates 对应用户消失 / 删除后 listBots 不再返回」的数据源）
    private var botConfigs: [[String: Any]] = []
    private var botWorkspaceStates: [[String: Any]] = []

    // MARK: 完备性补验桩状态（G-008 workflowRuns / G-011 能力读面 / G-017 任务组）
    /// 会话级 workflowRuns 状态（G-008 通路 B：conversation 订阅即下发=冷快照恢复语义；
    /// fireWorkflowRunsStateForTest 供运行中整键翻转断言）
    private var _workflowRunsState: [String: [[String: Any]]] = [:]
    /// 能力读面收到的 RPC 方法名（按到达顺序；「真实清单来自替身」源断言）
    private var _capabilityReads: [String] = []
    /// 任务组写命令记录（command + 参数字典；G-017 移动端→桌面同步写断言）
    private var _taskGroupWrites: [(command: String, args: [String: StubRPC])] = []

    private let queue = DispatchQueue(label: "e2e.login.stub.server")
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    /// 强持有活跃 channel（channel 仅被回调弱引用，需此处保活）
    private var channels: [ConnectionChannel] = []

    /// 测试钩子：设置会话 workflowRuns（订阅即重放 = 冷快照恢复语义）
    func setWorkflowRunsState(sessionId: String, runs: [[String: Any]]) {
        lock.lock()
        _workflowRunsState[sessionId] = runs
        lock.unlock()
    }

    /// 测试钩子：向全部活跃 channel 重放 workflowRuns state.updated（整键翻转断言）
    func fireWorkflowRunsStateForTest(sessionId: String, runs: [[String: Any]], delay: TimeInterval = 0.3) {
        lock.lock()
        let snapshot = channels
        lock.unlock()
        let patch: [String: Any] = ["workflowRuns": ["revision": Int(Date().timeIntervalSince1970 * 1000),
                                                     "runs": runs]]
        for channel in snapshot {
            fireConversationStateDelta(sessionId: sessionId, patch: patch,
                                       channel: channel, delay: delay)
        }
    }

    /// 能力读面方法名（真实清单源断言）与任务组写记录
    var capabilityReads: [String] { lock.lock(); defer { lock.unlock() }; return _capabilityReads }
    var taskGroupWrites: [(command: String, args: [String: StubRPC])] {
        lock.lock(); defer { lock.unlock() }; return _taskGroupWrites
    }


    /// 会话行数据（sessionId → 行数组；快照会话预置历史行，createSession/sendText 时增补）
    private var sessionRows: [String: [[String: Any]]] = [:]
    private var nextRowId: [String: Int] = [:]
    /// 会话挂起交互（sessionId → pendingInteractions 数组；conversation 订阅后经
    /// state.updated 下发，resolveInteraction 应答后清空）——审批卡数据源
    private var sessionPendingInteractions: [String: [[String: Any]]] = [:]

    init(pairingToken: String = "e2e-pair-token-4242",
         oauthCode: String = "stub-auth-code-9f31") {
        self.pairingToken = pairingToken
        self.oauthCode = oauthCode
        // 快照中的两个会话预置历史行：连接态「历史来自替身」断言的数据源（mock 演示不经过替身）。
        // sess-e2e-1 预置 6 行：无游标拉尾部 3 行（4..6，含 toolCall 供只读聊天断言），
        // beforeRowId=4 向上翻页取 1..3（loadOlder 拼接去重断言）。
        sessionRows = [
            "sess-e2e-1": [
                ["rowId": 1, "kind": "userInput", "text": "更早的问题：把网关重试参数梳理一下"],
                ["rowId": 2, "kind": "assistantText", "text": "替身助手：更早页 · 重试参数已梳理", "state": "complete"],
                ["rowId": 3, "kind": "toolCall", "toolCallId": "tool-e2e-0", "toolName": "Read",
                 "inputText": "gateway.ts", "status": "success",
                 "output": ["text": "read 120 lines"]],
                ["rowId": 4, "kind": "userInput", "text": "帮我把登录超时问题定位一下"],
                ["rowId": 5, "kind": "assistantText", "text": "替身助手：历史行链路正常", "state": "complete"],
                ["rowId": 6, "kind": "toolCall", "toolCallId": "tool-e2e-1", "toolName": "Bash",
                 "inputText": "grep -rn timeout server/", "status": "success",
                 "output": ["text": "1 match: gateway.ts:42"]],
            ],
            "sess-e2e-2": [
                ["rowId": 1, "kind": "userInput", "text": "跑一遍基线检查"],
                ["rowId": 2, "kind": "assistantText", "text": "替身助手：基线检查完成。", "state": "complete"],
            ],
            // task-e2e-1 同名会话行：任务详情「模型轨迹」分段（07-seg）的 reasoning+toolCall
            // 投影数据源（RemoteTaskStore.trajectoryLines ← conversation 行投影）
            "task-e2e-1": [
                ["rowId": 1, "kind": "userInput", "text": "把登录超时问题修掉"],
                ["rowId": 2, "kind": "assistantText", "text": "替身助手：轨迹投影链路检查中", "state": "complete"],
                ["rowId": 3, "kind": "toolCall", "toolCallId": "tool-traj-0", "toolName": "Bash",
                 "inputText": "swift test --filter SessionStoreTests", "status": "success",
                 "output": ["text": "46 passed"]],
            ],
            // sess-e2e-3：六类行全 kind（首窗尾部 3 行 = toolCall/subagent/artifact，
            // loadOlder 取 1..3 = userInput/assistantText/reasoning）
            "sess-e2e-3": [
                ["rowId": 1, "kind": "userInput", "text": "生成一份六类行渲染基线"],
                ["rowId": 2, "kind": "assistantText", "text": "替身助手：六类行渲染基线已就绪", "state": "complete"],
                ["rowId": 3, "kind": "reasoning", "text": "先核对投影映射再生成产物", "state": "complete"],
                ["rowId": 4, "kind": "toolCall", "toolCallId": "tool-six-0", "toolName": "Bash",
                 "inputText": "swift build", "status": "success",
                 "output": ["text": "Build complete"]],
                ["rowId": 5, "kind": "subagent", "subagentType": "reviewer", "summaryText": "六类行渲染复查通过"],
                ["rowId": 6, "kind": "artifact", "displayName": "六类行基线.md", "artifactType": "file"],
            ],
            // sess-e2e-plan：流程面板（项 3）——行级 plan 行注入（items 三步：
            // 1 完成 / 1 进行 / 1 待办 → 头部计数 1/3，非全完成默认展开，头部可点折叠/展开）
            "sess-e2e-plan": [
                ["rowId": 1, "kind": "userInput", "text": "把登录超时修复拆成计划执行"],
                ["rowId": 2, "kind": "plan", "items": [
                    ["id": "plan-e2e-1", "content": "梳理登录超时复现路径", "status": "completed"],
                    ["id": "plan-e2e-2", "content": "修补会话重连竞态", "status": "in_progress"],
                    ["id": "plan-e2e-3", "content": "补回归测试并归档", "status": "pending"],
                ]],
                ["rowId": 3, "kind": "assistantText", "text": "替身助手：计划已生成，按步推进", "state": "complete"],
            ],
            // sess-e2e-think：思考折叠（项 4）——首窗即含 complete 态 reasoning 行
            //（ThinkingBlockView 默认折叠：仅「已深度思考」头部摘要，点开展开正文）
            "sess-e2e-think": [
                ["rowId": 1, "kind": "userInput", "text": "解释一下会话重连的退避策略"],
                ["rowId": 2, "kind": "reasoning", "text": "推理行：先核对退避基数 500ms 与上限 10s，再对照重连计数上限 6 次",
                 "state": "complete"],
                ["rowId": 3, "kind": "assistantText", "text": "替身助手：退避为 min(10s, 500ms·2^n)，最多 6 次", "state": "complete"],
            ],
        ]
        nextRowId = ["sess-e2e-1": 7, "sess-e2e-2": 3, "task-e2e-1": 4, "sess-e2e-3": 7,
                     "sess-e2e-plan": 4, "sess-e2e-think": 4]
        // sess-e2e-1 预置一条 permission 挂起交互（pendingInteractionSummary.permissionCount=1
        // 的具体对象）：conversation 订阅后经 state.updated 下发 → ChatView 审批卡渲染，
        // resolveInteraction 应答后清空
        sessionPendingInteractions = [
            "sess-e2e-1": [[
                "id": "int-e2e-perm-1",
                "kind": "permission",
                "title": "Bash",
                "command": "rm -rf /Users/e2e/zcode-workspace/build",
                "path": "/Users/e2e/zcode-workspace",
                "impact": "删除构建产物目录 build/（约 40MB，可重新生成）",
            ]],
            // G-017：计划审批（plan_approval）挂起交互——计划文本经 renderContext.plan 下发，
            // 客户端 PlanApprovalCard 结构化渲染 + 放行/驳回（resolveInteraction 桌面代执行）
            "sess-e2e-plan": [[
                "id": "int-e2e-plan-1",
                "kind": "plan_approval",
                "title": "登录超时修复计划",
                "renderContext": [
                    "kind": "plan_approval",
                    "plan": "第一步：梳理登录超时复现路径\n第二步：修补会话重连竞态\n第三步：补回归测试并归档",
                ],
            ]],
        ]
        // bots 域替身配置（对齐桌面 BotConfig 形态：一个已绑定+有凭据，一个停用+未绑定）
        botConfigs = [
            ["id": "bot-telegram", "name": "E2E 通知机器人", "provider": "telegram", "enabled": true,
             "providerUserId": "user-tg-1001", "displayName": "E2E 管理员", "credentialRef": "keychain:bot-tg-1"],
            ["id": "bot-feishu", "name": "E2E 飞书机器人", "provider": "feishu", "enabled": false,
             "credentialRef": "keychain:bot-fs-1"],
        ]
        botWorkspaceStates = [
            ["id": "st-ctx-1", "botId": "bot-telegram",
             "workspacePath": "/Users/e2e/zcode-workspace", "mode": "chat", "activeTaskId": "task-e2e-1"],
        ]
    }

    private static let wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private static let iso8601: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    enum StubError: Error, CustomStringConvertible {
        case startFailed(String)
        var description: String {
            switch self {
            case .startFailed(let detail): return detail
            }
        }
    }

    /// RPC 参数体转字典（bots 域 botId/bot/credentialValue 提取用）
    fileprivate func rpcArgDict(_ rpc: StubRPC) -> [String: StubRPC] {
        rpc.objectValue ?? [:]
    }

    /// 启动并阻塞等待端口就绪（超时抛错）。返回绑定端口。
    @discardableResult
    func start() throws -> UInt16 {
        let semaphore = DispatchSemaphore(value: 0)
        var startError: Error?
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: .any)
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if let port = listener.port?.rawValue {
                    self?.setPort(port)
                }
                semaphore.signal()
            case .failed(let error):
                startError = error
                semaphore.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        self.listener = listener
        listener.start(queue: queue)

        guard semaphore.wait(timeout: .now() + 5) == .success, startError == nil else {
            listener.cancel()
            throw StubError.startFailed(startError.map { "监听失败：\($0)" } ?? "监听启动超时（5s）")
        }
        return port
    }

    /// 关闭全部连接与监听（tearDown 调用）。
    func stop() {
        listener?.cancel()
        lock.lock()
        let all = channels
        channels.removeAll()
        lock.unlock()
        for channel in all {
            channel.shutdown()
        }
    }

    private func setPort(_ value: UInt16) {
        lock.lock()
        port = value
        lock.unlock()
    }

    // MARK: - 连接接入（stub 队列）

    private func accept(_ connection: NWConnection) {
        let channel = ConnectionChannel(connection: connection, server: self)
        lock.lock()
        channels.append(channel)
        lock.unlock()
        channel.start(queue: queue)
    }

    fileprivate func removeChannel(_ channel: ConnectionChannel) {
        lock.lock()
        channels.removeAll { $0 === channel }
        lock.unlock()
    }

    // MARK: - HTTP 路由（stub 队列）

    fileprivate func handleHTTP(_ request: RecordedRequest, respond: @escaping (Data) -> Void) {
        let path = request.path
        if path == "/api/oauth/authorize" || path == "/authorize" {
            handleAuthorize(request, respond: respond)
        } else if path == "/api/v1/oauth/token" && request.method == "POST" {
            handleTokenExchange(respond: respond)
        } else if path == "/api/server-info" {
            handleServerInfo(request, respond: respond)
        } else if path == "/api/v1/relay/devices" {
            handleRelayDevices(request, respond: respond)
        } else {
            respond(httpResponse(status: "404 Not Found",
                                 headers: ["Content-Type": "application/json"],
                                 body: Data(#"{"error":"stub: no route"}"#.utf8)))
        }
    }

    /// 设备清单/中继链接 API（项 1 替身扩展面）：返回账号下已配对的桌面设备及其
    /// `/remote/v4` 中继配对链接。绑定假设如实标注：账号级设备列表云端 API 未上线，
    /// App 侧当前不消费本接口（设备列表取 ServerRegistry 中继服务器 + 剪贴板链接）；
    /// 本面为链接形态契约与后续接入预留，用例可直连探针断言（同 server-info 鉴权口径）。
    private func handleRelayDevices(_ request: RecordedRequest, respond: @escaping (Data) -> Void) {
        guard acceptPairingCredential(request) else {
            respond(httpResponse(status: "401 Unauthorized",
                                 headers: ["Content-Type": "application/json"],
                                 body: toJSON(["error": "token mismatch"])))
            return
        }
        lock.lock()
        _relayDeviceListRequests += 1
        lock.unlock()
        let payload: [String: Any] = [
            "devices": [[
                "deviceId": "stub-relay-mac-1",
                "name": "E2E-Relay-Mac",
                "kind": "paired_mac",
                "online": false,
                "relayLink": stubRelayLink,
            ]],
        ]
        respond(httpResponse(status: "200 OK",
                             headers: ["Content-Type": "application/json"],
                             body: toJSON(payload)))
    }

    /// 假授权页：立即 302 到 zcode://oauth/callback?code=…&state=…（holdAuthorizePage 时返回驻留 HTML）
    private func handleAuthorize(_ request: RecordedRequest, respond: @escaping (Data) -> Void) {
        if holdAuthorizePage {
            let html = """
            <!doctype html><html><head><meta charset="utf-8"><title>替身授权页</title></head>
            <body><h1>替身授权页 · Z.ai 账号授权</h1><p>stub authorize page · 等待用户确认</p></body></html>
            """
            respond(httpResponse(status: "200 OK",
                                 headers: ["Content-Type": "text/html; charset=utf-8"],
                                 body: Data(html.utf8)))
            return
        }
        let state = tamperState ? ((request.query["state"] ?? "") + "-tampered") : (request.query["state"] ?? "")
        // 镜像真实 OAuth：回跳目标取授权请求里的 redirect_uri（服务端按 client 注册校验；
        // App 默认指向 zcode.z.ai/cn/share/callback，测试经启动参数覆盖为替身地址）
        var components = URLComponents(string: request.query["redirect_uri"] ?? "") ?? {
            var c = URLComponents()
            c.scheme = "zcode"
            c.host = "oauth"
            c.path = "/callback"
            return c
        }()
        var items = (components.queryItems ?? []).filter { $0.name != "code" && $0.name != "state" }
        items.append(URLQueryItem(name: "code", value: oauthCode))
        if !state.isEmpty {
            items.append(URLQueryItem(name: "state", value: state))
        }
        components.queryItems = items
        let location = components.string ?? "zcode://oauth/callback?code=\(oauthCode)&state=\(state)"
        respond(httpResponse(status: "302 Found",
                             headers: ["Location": location],
                             body: Data()))
    }

    /// 令牌交换：POST /api/v1/oauth/token（按 OAuth 机制的三种响应分支）
    private func handleTokenExchange(respond: @escaping (Data) -> Void) {
        switch exchangeMode {
        case .success:
            let payload: [String: Any] = [
                "code": 0,
                "data": [
                    "token": "stub.zcode.jwt.\(UUID().uuidString.prefix(8))",
                    "zai": ["access_token": "stub-zai-access-e2e-4242", "token_type": "Bearer"],
                    "expires_in": 3600,
                    "user": [
                        "id": "user-e2e-1",
                        "username": "e2e_user",
                        "displayName": "替身用户",
                    ],
                ],
            ]
            respond(httpResponse(status: "200 OK",
                                 headers: ["Content-Type": "application/json"],
                                 body: toJSON(payload)))
        case .businessError(let code, let message):
            respond(httpResponse(status: "200 OK",
                                 headers: ["Content-Type": "application/json"],
                                 body: toJSON(["code": code, "msg": message])))
        case .http401:
            respond(httpResponse(status: "401 Unauthorized",
                                 headers: ["Content-Type": "application/json"],
                                 body: toJSON(["code": 401, "msg": "stub unauthorized"])))
        }
    }

    /// 配对探测：GET /api/server-info（令牌经 query `token=` 或 `Authorization: Bearer …` 校验）
    private func handleServerInfo(_ request: RecordedRequest, respond: @escaping (Data) -> Void) {
        guard acceptPairingCredential(request) else {
            respond(httpResponse(status: "401 Unauthorized",
                                 headers: ["Content-Type": "application/json"],
                                 body: toJSON(["error": "token mismatch"])))
            return
        }
        let payload: [String: Any] = [
            "serverId": "stub-desktop-e2e",
            "name": "E2E Stub Desktop",
            "version": "1.4.2-e2e",
            "protocolVersion": 1,
            "authRequired": true,
            // workspaces 两项（项 2 项目层断言数据源）：[0] 既有主工作区（连接装配与
            // topic 键，不得移动位次），[1] 实验项目供项目选择页多候选断言
            "workspaces": [
                [
                    "path": "/Users/e2e/zcode-workspace",
                    "label": "e2e 主工作区",
                    "workspaceIdentity": "ws-e2e-1",
                ],
                [
                    "path": "/Users/e2e/zcode-workspace-lab",
                    "label": "e2e 实验项目",
                    "workspaceIdentity": "ws-e2e-2",
                ],
            ],
            "capabilities": ["conversationV4": true, "gitDiff": true],
        ]
        respond(httpResponse(status: "200 OK",
                             headers: ["Content-Type": "application/json"],
                             body: toJSON(payload)))
    }

    /// 配对凭据校验：query token= 或 Authorization: Bearer 头二者其一命中即可
    private func acceptPairingCredential(_ request: RecordedRequest) -> Bool {
        let presented = request.query["token"] ?? bearerToken(of: request)
        guard presented == pairingToken else {
            lock.lock()
            _pairingAuthFailures += 1
            lock.unlock()
            return false
        }
        lock.lock()
        _lastAcceptedPairingToken = presented
        lock.unlock()
        return true
    }

    private func bearerToken(of request: RecordedRequest) -> String? {
        guard let authorization = request.headers["authorization"],
              authorization.lowercased().hasPrefix("bearer ") else { return nil }
        return String(authorization.dropFirst("bearer ".count)).trimmingCharacters(in: .whitespaces)
    }

    // MARK: - WebSocket / RPC（stub 队列）

    fileprivate func handleWSUpgrade(_ request: RecordedRequest, channel: ConnectionChannel) {
        guard acceptPairingCredential(request),
              let key = request.headers["sec-websocket-key"] else {
            channel.sendRaw(httpResponse(status: "401 Unauthorized",
                                         headers: ["Content-Type": "application/json"],
                                         body: toJSON(["error": "token mismatch"])), closeAfter: true)
            return
        }
        let accept = Self.sha1Base64(key + Self.wsGUID)
        channel.sendRaw(httpResponse(status: "101 Switching Protocols",
                                     headers: ["Upgrade": "websocket",
                                               "Connection": "Upgrade",
                                               "Sec-WebSocket-Accept": accept],
                                     body: Data()), closeAfter: false)
        lock.lock()
        _websocketUpgrades += 1
        lock.unlock()
        channel.beginWebSocket()
        // 连接建立即推 Initialize（channelServer.ts:30-35 口径；客户端只认类型 200）
        channel.sendWSFrame(rpcFrame(header: [.int(200), .int(0)], body: .undefined))
    }

    /// RPC promise 调用分派（header [100, id, channel, command] + body）
    fileprivate func handleRPCCall(_ header: [StubRPC], _ body: StubRPC, channel: ConnectionChannel) {
        guard header.count >= 4, case .int(let id) = header[1], case .string(let command) = header[3] else { return }
        let channelName = (header.count > 2) ? (header[2].stringValue ?? "") : ""
        lock.lock()
        _rpcCallLog.append((channelName, command))
        lock.unlock()
        // 边界拦截计数（v3 纠偏口径，集合与 ReadOnlyGate 直写黑名单同步）：
        // 手机直写/配置写面（file 非读白名单、git 写 8 项、terminal 4 项、zcode-task
        // set*/restart、zcode-agent 直写族）→ blockedWriteCommandCount。
        // sendText/resolveInteraction/stop/队列等桌面代执行命令为边界内合法面，不计数。
        let blockedTask: Set<String> = [
            "setMode", "setConfigOption", "setModel", "setAutomationSessionConfig",
            "restartWorkspaceProcess",
        ]
        let blockedGit: Set<String> = [
            "stagePaths", "commit", "unstagePaths", "discardPaths", "push",
            "switchBranch", "createBranchAndSwitch", "generateCommitMessage",
        ]
        let blockedAgent: Set<String> = [
            "setModel", "setThoughtLevel", "setMode", "respondSessionRuntimePreferences",
            "grantWorkspaceHookTrust", "listMcpServerStatuses", "generateWorkspaceText",
            "testModelConnectivity", "createAutomation", "updateAutomation", "deleteAutomation",
            "setAutomationEnabled", "restartAutomation", "runAutomationNow", "deleteAutomationRun",
            // P3-11：installPlugin/uninstallPlugin 已过 gate 放行（桌面代执行），同步移出
            "updatePlugin", "setPluginEnabled",
            "writeWorkspaceFile", "saveFile", "writeFile", "applyEdits",
        ]
        let blockedTerminal: Set<String> = ["create", "write", "resize", "dispose"]
        let fileReadCommands: Set<String> = [
            "readdir", "readTextFile", "stat", "searchWorkspaceFiles", "readBinaryPreview",
        ]
        let isBlockedWrite =
            (channelName == "zcode-task" && blockedTask.contains(command))
            || (channelName == "git" && blockedGit.contains(command))
            || (channelName == "zcode-agent" && blockedAgent.contains(command))
            || (channelName == "terminal" && blockedTerminal.contains(command))
            || (channelName == "file" && !fileReadCommands.contains(command))
        if isBlockedWrite {
            lock.lock()
            _blockedWriteCommandCount += 1
            lock.unlock()
        }
        switch command {
        case "helloConversationV4":
            let hello: [String: Any] = [
                "protocolVersion": 3, // V4_WIRE_PROTOCOL_VERSION
                "connectionId": "conn-stub-" + UUID().uuidString.prefix(8),
                "clientMode": "web-remote-replayable",
                "deliveryProfile": "web-remote-replayable",
                "serverTime": Self.iso8601.string(from: Date()),
                "capabilities": [String: Bool](),
                "auth": ["userId": "user-e2e-1"],
            ]
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(hello)))
        case "initializeConversationV4", "unsubscribeSessionsIndexV4", "unsubscribeConversationV4",
             "unsubscribeWorkspaceConfigV4":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "subscribeSessionsIndexV4":
            let topic = body.objectValue?["topic"]?.stringValue ?? "sessions-index/unknown"
            lock.lock()
            _subscribedTopics.append(topic)
            lock.unlock()
            // 回执 subscriptionId：客户端保存后用于 dropped → resyncSessionsIndexV4
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["ok": true, "subscriptionId": "sub-sess-\(UUID().uuidString.prefix(6))"])))
            // 快照事件延迟下发：给客户端留出注册帧处理器的窗口（响应先于事件，二发幂等）
            fireSessionsIndex(topic: topic, channel: channel, delays: [0.25, 0.9])
        case "subscribeConversationV4":
            // 兜底对账驱动开关：置位时拒绝订阅（promiseError）→ 客户端走 readSession 对账
            if failConversationSubscribe {
                lock.lock()
                _conversationSubscribeRejections += 1
                lock.unlock()
                channel.sendWSFrame(rpcFrame(header: [.int(202), .int(id)],
                                             body: StubRPC.object([
                                                "name": "ErrorObj",
                                                "message": "stub: conversation subscribe denied",
                                             ])))
                return
            }
            let topic = body.objectValue?["topic"]?.stringValue ?? "conversation/unknown"
            lock.lock()
            _subscribedTopics.append(topic)
            // workspace 寻址记录（订阅归属断言面，见 subscribeTargets 注）
            _subscribeTargets.append((
                topic: topic,
                workspacePath: body.objectValue?["workspacePath"]?.stringValue ?? "",
                workspaceIdentity: body.objectValue?["workspaceIdentity"]?.stringValue))
            let pendingForSession = sessionPendingInteractions[sessionId(ofTopic: topic)] ?? []
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["ok": true, "subscriptionId": "sub-conv-\(UUID().uuidString.prefix(6))"])))
            // 订阅即下发挂起交互（state.updated → ChatView 审批卡数据源）
            if !pendingForSession.isEmpty {
                fireConversationStateDelta(sessionId: sessionId(ofTopic: topic),
                                           patch: ["pendingInteractions": pendingForSession],
                                           channel: channel, delay: 0.4)
            }
            // 订阅即下发 workflowRuns 状态（G-008 标准③冷快照恢复语义：重连/冷启后
            // 运行中 run 不消失——状态随订阅重放）
            let wfSession = sessionId(ofTopic: topic)
            lock.lock()
            let workflowRuns = _workflowRunsState[wfSession]
            lock.unlock()
            if let workflowRuns {
                fireConversationStateDelta(
                    sessionId: wfSession,
                    patch: ["workflowRuns": ["revision": Int(Date().timeIntervalSince1970 * 1000),
                                             "runs": workflowRuns]],
                    channel: channel, delay: 0.45)
            }
        case "subscribeWorkspaceConfigV4":
            let topic = body.objectValue?["topic"]?.stringValue ?? "workspace-config/unknown"
            lock.lock()
            _subscribedTopics.append(topic)
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["ok": true, "subscriptionId": "sub-wsconfig-\(UUID().uuidString.prefix(6))"])))
        case "resyncConversationV4", "resyncSessionsIndexV4", "resyncWorkspaceConfigV4":
            let subscriptionId = body.objectValue?["subscriptionId"]?.stringValue ?? "unknown"
            lock.lock()
            _resyncCalls.append("\(command)·\(subscriptionId)")
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["ok": true, "subscriptionId": subscriptionId])))
            // resync 后重发快照（sessions-index 侧续流恢复断言）
            if command == "resyncSessionsIndexV4" {
                fireSessionsIndex(topic: body.objectValue?["topic"]?.stringValue ?? "sessions-index/unknown",
                                  channel: channel, delays: [0.2])
            }
        case "conversationRowsRangeV4":
            let sessionId = body.objectValue?["sessionId"]?.stringValue ?? ""
            let beforeRowId = body.objectValue?["beforeRowId"]?.intValue
            lock.lock()
            _rowsRangeRequests.append((sessionId, beforeRowId))
            let allRows = sessionRows[sessionId] ?? []
            lock.unlock()
            // 向上分页：beforeRowId 取更早一窗（页大小 3），无游标取尾部 3 行
            let pageSize = 3
            let page: [[String: Any]]
            let hasMore: Bool
            if let beforeRowId {
                let earlier = allRows.filter { ($0["rowId"] as? Int ?? 0) < beforeRowId }
                page = Array(earlier.suffix(pageSize))
                hasMore = earlier.count > page.count
            } else {
                page = Array(allRows.suffix(pageSize))
                hasMore = allRows.count > page.count
            }
            let replyBody: [String: Any] = ["rows": page, "hasMore": hasMore]
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(replyBody)))
        case "readSession" where channelName == "zcode-agent":
            // 订阅失败兜底对账（runtimePolicy=existing-only，只读恢复不拉起 Agent）：
            // 回执 pendingInteractionSummary=2/1（与快照投影的 1/0 可辨，证明值来自 readSession
            // 而非快照）；客户端把它写入 pendingInteractions 展示态。
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "sessionId": body.objectValue?["sessionId"]?.stringValue ?? "",
                "phase": "running",
                "pendingInteractionSummary": ["permissionCount": 2, "userInputCount": 1],
            ])))
        case "getChanges" where channelName == "git":
            // git 读面①：按 sourceId 返回（unstaged 两文件 / staged 单文件），时序由 rpcCallLog 断言
            lock.lock()
            _gitReadCommandCount += 1
            lock.unlock()
            let sourceId = body.objectValue?["sourceId"]?.stringValue ?? "unstaged"
            let changes: [[String: Any]] = sourceId == "staged" ? [Self.stubStagedChange] : Self.stubDiffChanges
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: .array(changes.map { StubRPC.object($0) })))
        case "getDiff" where channelName == "git":
            // git 读面②：单文件 unified patch（DiffReviewView 展开态数据源）
            lock.lock()
            _gitReadCommandCount += 1
            lock.unlock()
            let path = body.objectValue?["path"]?.stringValue ?? ""
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["patch": Self.stubPatch(path: path)])))
        case "refresh" where channelName == "git":
            // git.refresh：Diff 新鲜度前置（失败降级；此处恒成功并计数供时序断言）
            lock.lock()
            _gitRefreshCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "listTaskList" where channelName == "zcode-task":
            // zcode-task 读面：任务时间线快照（RemoteTaskStore.refresh 期望 {items:[…]}）
            lock.lock()
            _taskReadCommandCount += 1
            lock.unlock()
            let items = Self.stubTasks.map { StubRPC.object($0) }
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["items": .array(items)])))
        case "getTaskConfigOptions" where channelName == "zcode-task":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(["options": Self.stubConfigOptions])))
        case "getTaskModelSelection" where channelName == "zcode-task":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(Self.stubModelSelection)))
        case "getTaskTokenUsage" where channelName == "zcode-task":
            var usage = Self.stubTokenUsage
            usage["sessionId"] = body.objectValue?["taskId"]?.stringValue ?? ""
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(usage)))
        case "setTaskPinned" where channelName == "zcode-task":
            let taskId = body.objectValue?["taskId"]?.stringValue ?? ""
            let pinned = body.objectValue?["pinned"]?.intValue == 1
            lock.lock()
            _recordedPinned[taskId] = pinned
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["taskId": taskId, "pinned": pinned])))
            // 回推 session.upserted（pinned 投影）：验证 sessions-index 是否反映 zcode-task 写
            fireSessionUpserted(sessionId: taskId, pinned: pinned, channel: channel)
        case "setTaskUnread" where channelName == "zcode-task":
            lock.lock()
            _setTaskUnreadCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "archiveTask" where channelName == "zcode-task":
            lock.lock()
            _archiveTaskCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "promoteDeferredDraftSession" where channelName == "zcode-session":
            lock.lock()
            _promoteDeferredDraftCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "renameTask" where channelName == "zcode-task":
            // P1-5：会话重命名双写到达断言（taskId+title+workspacePath）
            let taskId = body.objectValue?["taskId"]?.stringValue ?? ""
            let title = body.objectValue?["title"]?.stringValue ?? ""
            lock.lock()
            _renameTaskCount += 1
            _lastRenameTitle = title
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["taskId": taskId, "title": title])))
        case "unarchiveTask" where channelName == "zcode-task":
            // P1-6：取消归档（此前误发 archiveTask 无法取消）
            lock.lock()
            _unarchiveTaskCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "listArchivedTasks" where channelName == "zcode-task":
            // P1-6：已归档清单（固定一条归档样例）；v1.15 起同口径计数
            // （membership join 验收断言用——归档分区 listArchivedTasks 拉取）
            lock.lock()
            _listArchivedTasksCount += 1
            _archivedTasksRequestCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "items": .array([StubRPC.object([
                    "taskId": "sess-e2e-arch-1",
                    "workspacePath": "/Users/e2e/zcode-workspace",
                    "title": "替身会话 · 已归档样例",
                    "phase": "archived",
                    "lastActivityAt": Self.iso8601.string(from: Date().addingTimeInterval(-86_400)),
                    "lastAssistantPreview": "归档前的最后输出。",
                ])]),
            ])))
        case "readdir" where channelName == "file":
            // 文件树：两级目录（readDirectory 递归 readdir 子路径）
            let path = body.objectValue?["path"]?.stringValue ?? ""
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: .array(Self.readdirEntries(for: path).map { StubRPC.object($0) })))
        case "stat" where channelName == "file":
            // 文件元信息：{path, type, size?}（预览页大小 + 超大文件截断守卫）
            let path = body.objectValue?["path"]?.stringValue ?? ""
            let isDirectory = Self.stubTreePaths.contains { $0.path == path && $0.type == "directory" }
            var payload: [String: Any] = ["path": path, "type": isDirectory ? "directory" : "file"]
            if !isDirectory {
                payload["size"] = Self.stubLargeFileSize
                payload["mtimeMs"] = 1_760_000_000_000
            }
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(payload)))
        case "readTextFile" where channelName == "file":
            // 有界读：offset/length 切片 + totalBytes（截断判定）；FileTextSlice 回执
            let offset = body.objectValue?["offset"]?.intValue ?? 0
            let bytes = Array(Self.stubLargeFileText.utf8)
            let startIdx = min(max(0, offset), bytes.count)
            let endIdx: Int
            if let length = body.objectValue?["length"]?.intValue {
                endIdx = min(startIdx + max(0, length), bytes.count)
            } else {
                endIdx = bytes.count
            }
            let content = String(decoding: bytes[startIdx..<endIdx], as: UTF8.self)
            let reply: [String: Any] = [
                "path": body.objectValue?["path"]?.stringValue ?? "",
                "content": content,
                "offset": startIdx,
                "bytesRead": content.utf8.count,
                "totalBytes": bytes.count,
            ]
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(reply)))
        case "searchWorkspaceFiles" where channelName == "file":
            // 服务端搜索：固定候选按 query 包含过滤（大小写不敏感）
            let query = (body.objectValue?["query"]?.stringValue ?? "").lowercased()
            let hits = Self.stubSearchCandidates.filter {
                query.isEmpty || ($0["name"] as? String ?? "").lowercased().contains(query)
            }
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: .array(hits.map { StubRPC.object($0) })))
        case "watch" where channelName == "file-watcher":
            let path = body.objectValue?["path"]?.stringValue ?? ""
            let recursive = body.objectValue?["recursive"]?.intValue == 1
            lock.lock()
            _fileWatcherWatchCount += 1
            _lastWatchRequest = (path, recursive)
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["id": "watch-e2e-1"])))
        case "unwatch" where channelName == "file-watcher":
            lock.lock()
            _fileWatcherUnwatchCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "disposeAll" where channelName == "file-watcher":
            lock.lock()
            _fileWatcherDisposeAllCount += 1
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "conversationFileChangesV4" where channelName == "zcode-agent":
            // 会话维度文件变更（hunk 结构 patches；DiffReviewView「本次会话」分段数据源）
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)],
                                         body: StubRPC.object(Self.stubSessionFileChanges)))
        case "getView" where channelName == "model-selection":
            // model-selection.getView：providers/models + preferredSelection（chips 只读数据源）
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(Self.stubModelSelectionView)))
        case "getProviders" where channelName == "oauth":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["providers": ["zai", "bigmodel"]])))
        case "getActiveProvider" where channelName == "oauth":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["providerId": "zai"])))
        case "restoreCachedSessionState" where channelName == "oauth":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "status": "authenticated",
                "userInfo": ["id": "user-e2e-1", "username": "e2e_user", "displayName": "替身用户"],
            ])))
        case "getCodingPlanUsageSnapshot" where channelName == "usage-stats":
            let nextReset = Date().addingTimeInterval(5 * 86_400).timeIntervalSince1970 * 1000
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "range": "30d",
                "generatedAt": Int(Date().timeIntervalSince1970 * 1000),
                "quota": ["level": "coding-plan", "limits": [[
                    "type": "credits", "unit": "积分", "number": 500, "usage": 340,
                    "percentage": 68.0, "nextResetTime": Int(nextReset),
                ]]],
            ])))
        case "getCodingPlanResetStatus" where channelName == "usage-stats":
            let expireAt = Date().addingTimeInterval(4 * 3_600).timeIntervalSince1970 * 1000
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "availableFiveHourResets": [["expireAt": Int(expireAt)]],
                "availableWeekResets": [[:]],
                "latestFiveHourResetHistory": NSNull(),
                "latestWeekResetHistory": NSNull(),
                "hasUnreadHistory": false,
            ])))
        // 重置卡用卡三步（test13：重置卡为扣费接口——替身本地记账，不触真实额度；
        // 「取消必不发」由 useCodingPlanReset 计数断言）
        case "requestCodingPlanResetOpportunity" where channelName == "usage-stats":
            lock.lock()
            _resetCardRPCCalls.append("requestCodingPlanResetOpportunity")
            lock.unlock()
            // 探测回执成功空对象（客户端探测失败不阻断用卡，仅记日志）
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([:])))
        case "useCodingPlanReset" where channelName == "usage-stats":
            lock.lock()
            _resetCardRPCCalls.append("useCodingPlanReset")
            let resetType = body.objectValue?["resetType"]?.stringValue ?? ""
            _resetCardUseTypes.append(resetType)
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([:])))
        case "markCodingPlanResetHistoryRead" where channelName == "usage-stats":
            lock.lock()
            _resetCardRPCCalls.append("markCodingPlanResetHistoryRead")
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([:])))
        case "listAutomations" where channelName == "zcode-agent":
            // G-016：自动化列表（对齐桌面 listAutomations 投影字段 automationId/title/cronExpr/…）
            let now = Date()
            let nextRun = Int(now.addingTimeInterval(6 * 3_600).timeIntervalSince1970 * 1000)
            let lastRun = Int(now.addingTimeInterval(-2 * 3_600).timeIntervalSince1970 * 1000)
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "automations": [
                    ["automationId": "auto-e2e-1", "title": "E2E 夜间回归", "cronExpr": "0 2 * * *",
                     "enabled": true, "nextRunAt": nextRun, "lastRunAt": lastRun,
                     "runCount": 12, "lastRunFailed": false],
                    ["automationId": "auto-e2e-2", "title": "E2E 周报整理", "cronExpr": "0 9 * * 1",
                     "enabled": false, "runCount": 3, "lastRunFailed": true],
                ],
            ])))
        case "listAutomationRuns" where channelName == "zcode-agent":
            let started = Int(Date().addingTimeInterval(-2 * 3_600).timeIntervalSince1970 * 1000)
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "runs": [
                    ["runId": "run-e2e-1", "startedAt": started, "status": "success", "summary": "回归 46 用例全通过"],
                    ["runId": "run-e2e-2", "startedAt": started - 86_400_000, "status": "failed",
                     "lastError": "E2E_RUN_ERR_TIMEOUT"],
                ],
            ])))
        case "listBots" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let payload: [String: Any] = ["bots": botConfigs]
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(payload)))
        case "getStatus" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let enabledCount = botConfigs.filter { ($0["enabled"] as? Bool) == true }.count
            let runtime: [[String: Any]] = botConfigs.compactMap { config in
                guard let botId = config["id"] as? String else { return nil }
                let enabled = (config["enabled"] as? Bool) ?? false
                let bound = (config["providerUserId"] as? String)?.isEmpty == false
                return ["botId": botId,
                        "status": !enabled ? "disabled" : (bound ? "connected" : "idle"),
                        "message": enabled ? "长连接保持中" : "已停用 · 桌面端不再投递"]
            }
            let payload: [String: Any] = [
                "botsCount": botConfigs.count,
                "enabledBotsCount": enabledCount,
                "contextsCount": botWorkspaceStates.count,
                "botRuntime": runtime,
            ]
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(payload)))
        case "getBotStates" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let payload: [String: Any] = ["states": botWorkspaceStates]
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(payload)))
        case "createBindCode" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            _bindCodeRequests += 1
            let code = String(format: "BIND-%04d", 4200 + _bindCodeRequests)
            _lastBindCode = code
            let ttl = stubBindCodeTTLms
            _lastBindCodeTTLms = ttl
            let payload: [String: Any] = [
                "code": code,
                "expiresAt": Int(Date().addingTimeInterval(Double(ttl) / 1000).timeIntervalSince1970 * 1000),
                "ttlMs": ttl,
            ]
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(payload)))
        case "testBot" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let botId = self.rpcArgDict(body)["botId"]?.stringValue ?? ""
            // 对齐桌面 testBot 语义：好凭据连通、坏凭据固定错误码（与桌面 zh-CN 提示同源）
            let payload: [String: Any] = botId == "bot-telegram"
                ? ["ok": true, "message": "getMe ok · @e2e_notify_bot", "provider": "telegram"]
                : ["ok": false, "message": "E2E_BOT_ERR_CREDENTIAL · 凭据校验被拒绝", "provider": "feishu"]
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(payload)))
        case "saveBot" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let args = self.rpcArgDict(body)
            var botDict: [String: Any] = [:]
            if case .object(let obj)? = args["bot"] {
                botDict = obj.reduce(into: [:]) { acc, pair in
                    // StubRPC 无 bool/double 形态（wire 层 Bool→int 0/1），统一落 int/string
                    switch pair.value {
                    case .string(let s): acc[pair.key] = s
                    case .int(let i): acc[pair.key] = i
                    default: break
                    }
                }
            }
            let botId = botDict["id"] as? String ?? ""
            if let index = botConfigs.firstIndex(where: { ($0["id"] as? String) == botId }), !botDict.isEmpty {
                var updated = botConfigs[index]
                for (key, value) in botDict where key != "id" {
                    if value is NSNull { updated.removeValue(forKey: key) } else { updated[key] = value }
                }
                botConfigs[index] = updated
            }
            _lastSaveBot = (
                botId: botId,
                droppedBinding: botDict["providerUserId"] == nil,
                hasCredentialValue: args["credentialValue"] != nil
            )
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "deleteBot" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let deleteId = self.rpcArgDict(body)["botId"]?.stringValue ?? ""
            _deleteBotIds.append(deleteId)
            botConfigs.removeAll { ($0["id"] as? String) == deleteId }
            botWorkspaceStates.removeAll { ($0["botId"] as? String) == deleteId }
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "removeBotSecret" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let secretId = self.rpcArgDict(body)["botId"]?.stringValue ?? ""
            _removeSecretIds.append(secretId)
            // 桌面同构：移除密钥回未配置凭据态，并同步清理绑定
            if let index = botConfigs.firstIndex(where: { ($0["id"] as? String) == secretId }) {
                botConfigs[index].removeValue(forKey: "credentialRef")
                botConfigs[index].removeValue(forKey: "providerUserId")
                botConfigs[index].removeValue(forKey: "displayName")
            }
            botWorkspaceStates.removeAll { ($0["botId"] as? String) == secretId }
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        case "resetBotState" where channelName == "bots":
            lock.lock()
            _botsRPCCalls.append(command)
            let resetId = self.rpcArgDict(body)["botId"]?.stringValue ?? ""
            _resetStateIds.append(resetId)
            botWorkspaceStates.removeAll { ($0["botId"] as? String) == resetId }
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        // MARK: tasks-index membership（v1.15：置顶/归档组织态权威源——sessions-index 行
        // 无 pinned/archived 字段【实证·上游仓 sessionSummarySchema】，客户端 join 本数据）
        case "listPinnedTasks" where channelName == "zcode-task":
            lock.lock(); _pinnedTasksRequestCount += 1; lock.unlock()
            // 置顶预置：sess-e2e-think（列表末位会话——join 生效后应跃居「置顶」分区首行）
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "items": [[
                    "taskId": "sess-e2e-think",
                    "title": "替身会话 · 思考折叠投影",
                    "workspacePath": "/Users/e2e/zcode-workspace",
                    "lastActivityAt": Int(Date().timeIntervalSince1970 * 1000) - 300_000,
                ]],
            ])))
        case "listArchivedTasks" where channelName == "zcode-task":
            // 归档清单（既有处理器见上——此分支不可达，保留防重复注册）
            break
        // switchModelConfig 实经 sendConversationCommandV4 信封（store.sendCommand 统一
        // 出口，方法名恒为 sendConversationCommandV4——裸方法名分支永不匹配，test12
        // 门禁实证计数恒 0）；stale-once 逻辑在 handleConversationCommand 内
        case "sendConversationCommandV4":
            handleConversationCommand(body, channel: channel) { result in
                channel.sendWSFrame(self.rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(result)))
            }
        // MARK: 附件事务四方法（A-4 链路验收：web 客户端同形严格校验）
        case "attachmentBeginV4" where channelName == "zcode-agent":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: self.handleAttachmentBegin(body)))
        case "attachmentChunkV4" where channelName == "zcode-agent":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: self.handleAttachmentChunk(body)))
        case "attachmentCommitV4" where channelName == "zcode-agent":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: self.handleAttachmentCommit(body)))
        case "attachmentAbortV4" where channelName == "zcode-agent":
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: self.handleAttachmentAbort(body)))
        // MARK: 能力读面（G-011/G-022/G-024/G-025）：真实替身清单（回执形状对齐
        // P2ExtrasViews 宽容解析的取数键），供「连接态真实清单渲染 + 零写入口」断言
        case "listProjectMemories":
            lock.lock(); _capabilityReads.append(command); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "workspaces": [["id": "mem-e2e-1", "label": "zcode_mobile 记忆库",
                                "files": [["path": "MEMORY.md"], ["path": "decisions.md"], ["path": "stack.md"]],
                                "updatedAt": Int(Date().timeIntervalSince1970 * 1000)]],
            ])))
        case "getSkillReferenceCatalog":
            lock.lock(); _capabilityReads.append(command); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "skills": [["name": "e2e-skill-a", "description": "端到端验收技能 · stub", "enabled": true]],
            ])))
        case "listMcpServerStatuses":
            lock.lock(); _capabilityReads.append(command); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "servers": [["name": "e2e-mcp-server", "status": "connected", "command": "npx e2e-mcp"]],
            ])))
        case "listPlugins":
            lock.lock(); _capabilityReads.append(command); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "plugins": [["name": "e2e-plugin", "version": "1.2.3", "enabled": false,
                             "description": "端到端验收插件 · stub"]],
            ])))
        case "listSavedWorkflows":
            lock.lock(); _capabilityReads.append(command); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "workflows": [["id": "wf-e2e-1", "name": "登录链路工作流",
                               "description": "已保存工作流 · stub"]],
            ])))
        case "listSavedWorkflowRuns":
            lock.lock(); _capabilityReads.append(command); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "runs": [["runId": "run-saved-e2e-1", "workflowName": "登录链路工作流",
                          "startedAt": Int(Date().timeIntervalSince1970 * 1000) - 600_000,
                          "status": "completed"]],
            ])))
        case "list" where channelName == "off-peak":
            lock.lock(); _capabilityReads.append("off-peak.list"); lock.unlock()
            // offPeak 解析取顶层 JSON 数组（P2ExtrasViews:1040 arrayValue）→ StubRPC.array
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.array([
                .object(["offPeakTaskId": .string("offpeak-e2e-1"),
                         "title": .string("错峰回归任务 · stub"),
                         "createdAt": .int(Int(Date().timeIntervalSince1970 * 1000)),
                         "status": .string("queued")]),
            ])))
        case "list" where channelName == "feedback":
            lock.lock(); _capabilityReads.append("feedback.list"); lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "items": [["ticketId": "ticket-e2e-1", "title": "E2E 工单 · stub",
                           "updatedAt": Int(Date().timeIntervalSince1970 * 1000), "status": "processing"]],
            ])))
        // MARK: 任务组写（G-017）：移动端→桌面同步写命令记录（回执 ok）
        case "createTaskGroup" where channelName == "zcode-task":
            lock.lock()
            _taskGroupWrites.append((command: "createTaskGroup", args: self.rpcArgDict(body)))
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "result": ["groupId": "group-e2e-1"]])))
        case "listGroupedTaskViewStructure" where channelName == "zcode-task":
            // B-2 web 实证回执形状 {groups, members, topLevelOrders}（空结构 = 桌面无分组态；
            // 移入分组全链先拉结构作全量视图写基线，缺此应答流程会在 apply 前如实失败）
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object([
                "groups": [] as [Any],
                "members": [] as [Any],
                "topLevelOrders": [] as [Any],
            ])))
        case "applyGroupedTaskViewOrder" where channelName == "zcode-task":
            lock.lock()
            _taskGroupWrites.append((command: "applyGroupedTaskViewOrder", args: self.rpcArgDict(body)))
            lock.unlock()
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        default:
            // 未知命令统一快速应答，避免客户端悬挂
            channel.sendWSFrame(rpcFrame(header: [.int(201), .int(id)], body: StubRPC.object(["ok": true])))
        }
    }

    // MARK: 附件事务替身实现（web 客户端同形严格校验）

    /// 键集校验：必须键全在 + 不许多余键（workspaceIdentity 可选）。connectionId
    /// 显式列为禁止键——web 客户端参数无此键（attachmentUploadTransaction.ts，
    /// connectionId 由桌面 facade 注入），移动端多带即与 web 不对齐（2026-10-07
    /// 「附件不能上传」回归根因，留绊线防复发）。
    private func validateAttachmentKeys(
        _ dict: [String: StubRPC], required: [String], method: String) -> String? {
        for key in required where dict[key] == nil {
            return "\(method): 缺键 \(key)"
        }
        if dict["connectionId"] != nil {
            return "\(method): 多余键 connectionId（web 客户端不发该键）"
        }
        for key in dict.keys where !required.contains(key) && key != "workspaceIdentity" {
            return "\(method): 多余键 \(key)"
        }
        return nil
    }

    private func handleAttachmentBegin(_ body: StubRPC) -> StubRPC {
        let dict = body.objectValue ?? [:]
        lock.lock(); _attachmentBeginCount += 1; lock.unlock()
        if let problem = validateAttachmentKeys(
            dict,
            required: ["workspacePath", "sessionId", "uploadId", "fileName", "mime",
                       "totalBytes", "totalChunks", "checksum"],
            method: "begin") {
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        let uploadId = dict["uploadId"]?.stringValue ?? ""
        let totalChunks = dict["totalChunks"]?.intValue ?? 0
        let checksum = dict["checksum"]?.stringValue ?? ""
        lock.lock()
        // checksum 命中已 commit 附件 → committed 短路回执（web Begin 幂等同款）
        if let ref = _attachmentCommitted[checksum] {
            lock.unlock()
            return StubRPC.object([
                "uploadId": uploadId, "state": "committed",
                "nextChunkIndex": totalChunks, "ref": ref])
        }
        _attachmentTxns[uploadId] = AttachTxn(
            nextChunkIndex: 0, totalChunks: totalChunks, checksum: checksum,
            fileName: dict["fileName"]?.stringValue ?? "",
            mime: dict["mime"]?.stringValue ?? "",
            totalBytes: dict["totalBytes"]?.intValue ?? 0)
        lock.unlock()
        return StubRPC.object(["uploadId": uploadId, "state": "staging", "nextChunkIndex": 0])
    }

    private func handleAttachmentChunk(_ body: StubRPC) -> StubRPC {
        let dict = body.objectValue ?? [:]
        lock.lock(); _attachmentChunkCount += 1; lock.unlock()
        if let problem = validateAttachmentKeys(
            dict,
            required: ["workspacePath", "sessionId", "uploadId", "chunkIndex", "dataBase64"],
            method: "chunk") {
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        let uploadId = dict["uploadId"]?.stringValue ?? ""
        let chunkIndex = dict["chunkIndex"]?.intValue ?? -1
        lock.lock()
        guard let txn = _attachmentTxns[uploadId] else {
            lock.unlock()
            let problem = "chunk: 未知事务 \(uploadId)"
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        guard chunkIndex == txn.nextChunkIndex else {
            lock.unlock()
            let problem = "chunk: 乱序（期待 \(txn.nextChunkIndex) 实际 \(chunkIndex)）"
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        _attachmentTxns[uploadId]?.nextChunkIndex = chunkIndex + 1
        lock.unlock()
        // base64 可解码校验（每块独立 padding 纪律）
        if let encoded = dict["dataBase64"]?.stringValue,
           Data(base64Encoded: encoded) == nil {
            lock.lock(); _attachmentShapeErrors.append("chunk: dataBase64 不可解码"); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params",
                                              "message": "chunk: dataBase64 不可解码"]])
        }
        return StubRPC.object(["uploadId": uploadId, "nextChunkIndex": chunkIndex + 1])
    }

    private func handleAttachmentCommit(_ body: StubRPC) -> StubRPC {
        let dict = body.objectValue ?? [:]
        lock.lock(); _attachmentCommitCount += 1; lock.unlock()
        if let problem = validateAttachmentKeys(
            dict, required: ["workspacePath", "sessionId", "uploadId"], method: "commit") {
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        let uploadId = dict["uploadId"]?.stringValue ?? ""
        lock.lock()
        guard let txn = _attachmentTxns.removeValue(forKey: uploadId) else {
            lock.unlock()
            let problem = "commit: 未知事务 \(uploadId)"
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        guard txn.nextChunkIndex >= txn.totalChunks else {
            _attachmentShapeErrors.append(
                "commit: 缺块（收 \(txn.nextChunkIndex)/\(txn.totalChunks)）")
            lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params",
                                              "message": "commit: 缺块"]])
        }
        let ref = "att-e2e-" + uploadId.suffix(8)
        _attachmentCommitted[txn.checksum] = ref
        lock.unlock()
        return StubRPC.object(["ref": ref])
    }

    private func handleAttachmentAbort(_ body: StubRPC) -> StubRPC {
        let dict = body.objectValue ?? [:]
        lock.lock(); _attachmentAbortCount += 1; lock.unlock()
        if let problem = validateAttachmentKeys(
            dict, required: ["workspacePath", "sessionId", "uploadId"], method: "abort") {
            lock.lock(); _attachmentShapeErrors.append(problem); lock.unlock()
            return StubRPC.object(["fault": ["name": "Invalid params", "message": problem]])
        }
        lock.lock(); _attachmentTxns.removeValue(forKey: dict["uploadId"]?.stringValue ?? ""); lock.unlock()
        return StubRPC.object(["ok": true])
    }

    /// sendConversationCommandV4：createSession 建行并回执 sessionId；sendText 追加用户行 +
    /// 推送替身回执增量帧；resolveInteraction 记录 interactionId/answer 并清 pendingInteractions；
    /// stop 计数并回推任务状态事件。
    /// 边界计数（v3 纠偏口径）：仅 applyFileRewind 属直写类计入 blockedWriteCommandCount；
    /// sendText/resolveInteraction/stop 为边界内合法面（桌面代执行），独立计数供闭环断言。
    private func handleConversationCommand(_ body: StubRPC, channel: ConnectionChannel, reply: @escaping ([String: Any]) -> Void) {
        // 信封形态解包（客户端 v4 纠偏后 sendConversationCommandV4 携
        // {envelope:{commandId,clientId,type,payload,issuedAt,sessionId?}, workspacePath,
        //  workspaceIdentity?}）——桌面按 envelope 内字段执行；stub 同构解包，否则
        // type 读空 → 无 case 匹配 → 永不回执，客户端在测试窗口内等不到平铺兜底
        // （门禁实测：createSessionWithFirstInputCount / sendTextCount 恒 0）
        let effective = body.objectValue?["envelope"]?.objectValue ?? body.objectValue
        let type = effective?["type"]?.stringValue ?? ""
        let sessionId = effective?["sessionId"]?.stringValue
        if type == "applyFileRewind" {
            lock.lock()
            _blockedWriteCommandCount += 1
            lock.unlock()
        }
        switch type {
        case "createSession":
            let firstInput = effective?["payload"]?.objectValue?["firstInput"]?.objectValue?["text"]?.stringValue
            // 项 2：项目层选择随 createSession 的 workspaceId 下发（NewConversationSheet
            // 项目胶囊 → directory 参数；未绑定时客户端回退连接装配的 workspace）
            let workspaceId = effective?["payload"]?.objectValue?["workspaceId"]?.stringValue
            // 会话前模型选择（模型/思考等级行接线断言）
            let modelSelection = effective?["payload"]?.objectValue?["firstInput"]?
                .objectValue?["modelSelection"]?.objectValue
            let newId = "sess-e2e-" + UUID().uuidString.prefix(6)
            lock.lock()
            _createSessionCount += 1
            if let workspaceId { _createSessionWorkspaceIds.append(workspaceId) }
            if firstInput != nil { _createSessionWithFirstInputCount += 1 }
            if let modelSelection { _lastCreateSessionModelSelection = modelSelection }
            if let firstInput {
                // 边界内形态（command）：携带首条输入 → 直接写 userInput+assistant 行（桌面开跑）
                sessionRows[newId] = [
                    ["rowId": 1, "kind": "userInput", "text": firstInput],
                    ["rowId": 2, "kind": "assistantText", "text": "替身助手：首条指令已收到，v4 链路正常", "state": "complete"],
                ]
            } else {
                // draft 形态（session）：不带 firstInput → 空会话，不进 sqlite、无行
                sessionRows[newId] = []
            }
            nextRowId[newId] = firstInput != nil ? 3 : 1
            lock.unlock()
            reply(["result": ["sessionId": newId]])
        case "sendText":
            guard let sessionId else {
                reply(["ok": false])
                return
            }
            let text = effective?["payload"]?.objectValue?["text"]?.stringValue ?? ""
            // attachments 元素键提取（{ref, fileName, mime, bytes} 严格断言数据源）
            let attachmentItems: [StubRPC]
            if case .array(let items) = effective?["payload"]?.objectValue?["attachments"] {
                attachmentItems = items
            } else {
                attachmentItems = []
            }
            let attachments: [[String: Any]] = attachmentItems.compactMap { item in
                guard let dict = item.objectValue else { return nil }
                var mapped: [String: Any] = [:]
                for (key, value) in dict {
                    if let s = value.stringValue { mapped[key] = s }
                    else if let i = value.intValue { mapped[key] = i }
                }
                return mapped.isEmpty ? nil : mapped
            }
            let userRow: [String: Any] = ["rowId": bumpRow(sessionId), "kind": "userInput", "text": text]
            let assistantRow: [String: Any] = [
                "rowId": bumpRow(sessionId), "kind": "assistantText",
                "text": "替身回执 · 已收到「\(text)」", "state": "complete",
            ]
            lock.lock()
            sessionRows[sessionId, default: []].append(contentsOf: [userRow, assistantRow])
            _sendTextCount += 1
            if !attachments.isEmpty { _lastSendTextAttachments = attachments }
            lock.unlock()
            reply(["result": ["accepted": true]])
            // conversation 增量帧：只下发替身回复（用户行客户端已本地回显，避免双气泡）
            fireConversationDelta(sessionId: sessionId, row: assistantRow, channel: channel, delay: 0.35)
        case "resolveInteraction":
            let payloadDict = effective?["payload"]?.objectValue ?? [:]
            var payloadJSON: [String: Any] = [:]
            for (key, value) in payloadDict {
                if let s = value.stringValue { payloadJSON[key] = s }
                else if let i = value.intValue { payloadJSON[key] = i == 1 }
                else if let obj = value.objectValue {
                    var nested: [String: Any] = [:]
                    for (k, v) in obj {
                        if let s = v.stringValue { nested[k] = s }
                        else if let i = v.intValue { nested[k] = i == 1 }
                    }
                    payloadJSON[key] = nested
                }
            }
            let interactionId = payloadJSON["interactionId"] as? String ?? ""
            lock.lock()
            _resolveInteractionCount += 1
            _lastResolveInteraction = (interactionId, payloadJSON)
            lock.unlock()
            reply(["result": ["ok": true]])
            // 应答生效：清空该会话 pendingInteractions 并回推 state.updated（审批卡/角标撤下）
            if let sessionId {
                lock.lock()
                sessionPendingInteractions.removeValue(forKey: sessionId)
                lock.unlock()
                fireConversationStateDelta(sessionId: sessionId, patch: ["pendingInteractions": []],
                                           channel: channel, delay: 0.2)
            }
        case "sendGoalCommand":
            let text = effective?["payload"]?.objectValue?["text"]?.stringValue ?? ""
            lock.lock(); _lastGoalCommandText = text; lock.unlock()
            reply(["result": ["ok": true]])
        case "compact":
            lock.lock(); _compactCommandCount += 1; lock.unlock()
            reply(["result": ["ok": true]])
        case "stop":
            lock.lock()
            _stopCount += 1
            lock.unlock()
            reply(["result": ["ok": true]])
            // 状态回流：任务翻转为非运行（卡片/状态条经 onDynamicTaskEvent 免刷新更新）
            queue.asyncAfter(deadline: .now() + 0.4) { [weak self, weak channel] in
                guard let self, let channel, channel.open else { return }
                self.fireTaskEvent(status: "completed")
            }
        case "switchModelConfig":
            // 胶囊切换 stale-once-then-accepted（真机 diag.wf.control.ui 实证形态：
            // 活跃会话首击 stale proto.staleRevision → 客户端应原样重发一次即命中；
            // 计数 ≥2 即 CAS 重试链在位——test12 门禁断言）
            lock.lock()
            _switchModelConfigCallCount += 1
            let nthCall = _switchModelConfigCallCount
            lock.unlock()
            if nthCall == 1 {
                reply([
                    "commandId": "stub-cmd-\(nthCall)",
                    "status": "stale",
                    "reasonCode": "proto.staleRevision",
                    "revisionAtDecision": 12119,
                ])
            } else {
                reply([
                    "commandId": "stub-cmd-\(nthCall)",
                    "status": "accepted",
                    "result": ["ok": true],
                ])
            }
        default:
            reply(["result": ["ok": true]])
        }
    }

    private func bumpRow(_ sessionId: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        let next = nextRowId[sessionId] ?? 1
        nextRowId[sessionId] = next + 1
        return next
    }

    // MARK: - 读面静态数据（diff / 任务列表：连接态只读展示闭环断言的数据源）

    /// git.getChanges 应答：工作区未暂存变更（RemoteFileStore 逐项取 path/repoRelativePath/added/removed）
    private static let stubDiffChanges: [[String: Any]] = [
        [
            "path": "/Users/e2e/zcode-workspace/SessionStore.swift",
            "repoRelativePath": "SessionStore.swift",
            "added": 24,
            "removed": 8,
        ],
        [
            "path": "/Users/e2e/zcode-workspace/Composer.swift",
            "repoRelativePath": "Composer.swift",
            "added": 6,
            "removed": 2,
        ],
    ]

    /// git.getDiff 应答：按 path 返回 unified patch（DiffReviewView.parsePatch 可解析出 hunk/add/del）
    private static func stubPatch(path: String) -> String {
        let body: String
        if path.hasSuffix("Composer.swift") {
            body = """
            @@ -5,4 +5,6 @@ struct ComposerBar {
                 var body: some View {
            -        toolsRow
            +        if viewModel.isReadOnly {
            +            readOnlyNotice
            +        } else {
            +            toolsRow
            +        }
                 }
            """
        } else {
            body = """
            @@ -18,7 +18,9 @@ final class SessionStore {
                 func sessions() -> [Session] {
            -        return fileStore.loadSync()
            +        guard let data = try await fileStore.load() else { return [] }
            +        return try decoder.decode([Session].self, from: data)
                 }
            """
        }
        return "diff --git a/\(path) b/\(path)\nindex 8f2a1c3..b74e091 100644\n--- a/\(path)\n+++ b/\(path)\n" + body
    }

    /// zcode-task.listTaskList 应答：任务时间线（RemoteTaskStore.mapTask 要求 taskId + workspacePath）
    private static let stubTasks: [[String: Any]] = [
        [
            "taskId": "task-e2e-1",
            "workspacePath": "/Users/e2e/zcode-workspace",
            "title": "替身任务 · 登录链路回归",
            "status": "running",
            "updatedAt": Int(Date().timeIntervalSince1970 * 1000),
            "changeSummary": ["fileCount": 2, "added": 30, "removed": 10],
        ],
        [
            "taskId": "task-e2e-2",
            "workspacePath": "/Users/e2e/zcode-workspace",
            "title": "替身任务 · 基线检查归档",
            "status": "completed",
            "updatedAt": Int(Date().timeIntervalSince1970 * 1000) - 7_200_000,
        ],
    ]

    /// git.getChanges(staged) 应答：已暂存变更（staged/unstaged 双维度分段断言）
    private static let stubStagedChange: [String: Any] = [
        "path": "/Users/e2e/zcode-workspace/StagedFile.swift",
        "repoRelativePath": "StagedFile.swift",
        "added": 3,
        "removed": 1,
    ]

    /// zcode-task.getTaskConfigOptions 应答（ZCodeConfigOption 形态）
    private static let stubConfigOptions: [[String: Any]] = [
        [
            "id": "model", "name": "模型", "type": "select", "currentValue": "GLM-5.3",
            "options": [["value": "GLM-5.3", "name": "GLM-5.3"], ["value": "GLM-5", "name": "GLM-5"]],
        ],
        ["id": "thought_level", "name": "思考档", "type": "select", "currentValue": "high"],
    ]

    /// zcode-task.getTaskModelSelection 应答（ModelSelection 形态）
    private static let stubModelSelection: [String: Any] = [
        "providerId": "zai",
        "modelId": "GLM-5.3",
        "options": ["reasoningLevel": "high"],
    ]

    /// zcode-task.getTaskTokenUsage 应答（ZCodeTaskTokenUsageResult 形态）
    private static let stubTokenUsage: [String: Any] = [
        "sessionId": "",
        "totalTokens": 123_456,
        "inputTokens": 100_000,
        "outputTokens": 23_456,
        "reasoningTokens": 5_000,
        "cacheCreationTokens": 0,
        "cacheReadTokens": 0,
        "modelRequestCount": 42,
        "modelErrorCount": 0,
        "inputBaselineBySource": [String: Int](),
    ]

    /// conversationFileChangesV4 应答（hunk 结构 patches；「本次会话」分段数据源）
    private static let stubSessionFileChanges: [String: Any] = [
        "files": 1,
        "additions": 2,
        "deletions": 1,
        "state": "active",
        "items": [[
            "path": "/Users/e2e/zcode-workspace/SessionStore.swift",
            "additions": 2,
            "deletions": 1,
            "writeCount": 1,
            "toolNames": ["Edit"],
            "patches": [[
                "oldStart": 18, "oldLines": 3, "newStart": 18, "newLines": 4,
                "lines": ["        let cache = self.cache", "-        return cache.loadSync()", "+        return try await cache.load()", "    }"],
            ]],
        ]],
    ]

    /// model-selection.getView 应答（providers/models + preferredSelection；chips 只读数据源）
    private static let stubModelSelectionView: [String: Any] = [
        "revision": 1,
        "providers": [[
            "providerId": "zai",
            "providerName": "Z.ai",
            "models": [[
                "modelId": "GLM-5.3",
                "label": "GLM-5.3",
                "modelThoughtLevels": ["off", "low", "medium", "high"],
            ]],
        ]],
        "preferredSelection": [
            "providerId": "zai",
            "modelId": "GLM-5.3",
            "options": ["reasoningLevel": "high"],
        ],
    ]

    /// file.searchWorkspaceFiles 固定候选（name/path/relativePath/type）
    private static let stubSearchCandidates: [[String: Any]] = [
        ["name": "SessionStore.swift", "path": "/Users/e2e/zcode-workspace/SessionStore.swift",
         "relativePath": "SessionStore.swift", "type": "file"],
        ["name": "Composer.swift", "path": "/Users/e2e/zcode-workspace/Composer.swift",
         "relativePath": "Composer.swift", "type": "file"],
        ["name": "Sources", "path": "/Users/e2e/zcode-workspace/Sources",
         "relativePath": "Sources", "type": "directory"],
        ["name": "README.md", "path": "/Users/e2e/zcode-workspace/README.md",
         "relativePath": "README.md", "type": "file"],
    ]

    /// file.readdir 树（两级）：workspace 根 + Sources 子目录
    private static let stubTreePaths: [(path: String, type: String, size: Int?)] = [
        (path: "/Users/e2e/zcode-workspace/SessionStore.swift", type: "file", size: 4_096),
        (path: "/Users/e2e/zcode-workspace/Composer.swift", type: "file", size: 2_048),
        (path: "/Users/e2e/zcode-workspace/README.md", type: "file", size: 1_024),
        (path: "/Users/e2e/zcode-workspace/Sources", type: "directory", size: nil),
        (path: "/Users/e2e/zcode-workspace/Sources/Core.swift", type: "file", size: 8_192),
    ]

    /// file.stat/readTextFile 的大文件口径（> 256KiB 首屏页 → 截断守卫 + 加载更多断言）
    private static let stubLargeFileSize = 300_000
    private static var stubLargeFileText: String {
        // ~300KB 确定性文本（UTF-8 每行 32 字节 × 9600 行）
        let line = String(repeating: "a", count: 28) + "\n"
        return String(repeating: line, count: 9_600)
    }

    /// readdir：按请求 path 返回直接子项（name/path/type[/size]）
    private static func readdirEntries(for path: String) -> [[String: Any]] {
        let prefix = path.hasSuffix("/") ? path : path + "/"
        return stubTreePaths.compactMap { item in
            guard item.path.hasPrefix(prefix) else { return nil }
            let rest = String(item.path.dropFirst(prefix.count))
            guard !rest.isEmpty else { return nil }
            let isDirectChild = !rest.contains("/")
            if isDirectChild {
                var entry: [String: Any] = [
                    "name": rest,
                    "path": item.path,
                    "relativePath": rest,
                    "type": item.type,
                ]
                if let size = item.size { entry["size"] = size }
                return entry
            }
            // 深层路径 → 归并为目录项（本替身树只有 Sources/Core.swift 一层深）
            let dirName = String(rest.split(separator: "/").first ?? "")
            return [
                "name": dirName,
                "path": prefix + dirName,
                "relativePath": dirName,
                "type": "directory",
            ]
        }
    }

    // MARK: v4 下行帧

    /// sessions-index 快照事件（TopicWireFrame complete 信封 + snapshot 逻辑帧，二发幂等）。
    /// pinned 投影：recordedPinned 有记录时带出（setTaskPinned 双写后重建 Store 置顶保持断言）。
    private func fireSessionsIndex(topic: String, channel: ConnectionChannel, delays: [TimeInterval]) {
        let pinned = recordedPinned
        // 会话行自带 workspacePath（要求 4：多项目分组替身——桌面侧栏 mtt_mobile /
        // zcode_mobile / poker_texas_air / matchclub 多项目并存，分组键取行内字段；
        // sess-e2e-think 不带该字段 → 数据无法判定归属 → 归「其它」组）
        var sessions: [[String: Any]] = [
            [
                "sessionId": "sess-e2e-1",
                "title": "替身会话 · 登录链路验收",
                "phase": "running",
                "lastActivityAt": Self.iso8601.string(from: Date()),
                "lastAssistantPreview": "替身端持续输出中…",
                "workspacePath": "/Users/e2e/mtt_mobile",
                // G-007 通路 A：行自带 workflowActivity（sessionWorkflowActivitySchema 有界形态）
                "workflowActivity": ["runs": [[
                    "runId": "run-e2e-1",
                    "name": "登录链路工作流",
                    "status": "running",
                    "phases": [
                        ["name": "准备", "status": "done"],
                        ["name": "执行", "status": "running"],
                        ["name": "校验", "status": "pending"],
                    ],
                    "currentPhase": "执行",
                    "agentsWorking": 1,
                ]]],
                "pendingInteractionSummary": ["permissionCount": 1, "userInputCount": 0],
            ],
            [
                "sessionId": "sess-e2e-2",
                "title": "替身会话 · 已完成基线 E2E",
                "phase": "completedSuccess",
                "lastActivityAt": Self.iso8601.string(from: Date().addingTimeInterval(-7200)),
                "lastAssistantPreview": "基线检查完成。",
                "workspacePath": "/Users/e2e/zcode_mobile",
                "pendingInteractionSummary": ["permissionCount": 0, "userInputCount": 0],
            ],
            // 六类行投影专用会话（消息流只读渲染断言：userInput/assistantText/reasoning/
            // toolCall/subagent/artifact 全 kind 覆盖；无挂起交互不产角标）
            [
                "sessionId": "sess-e2e-3",
                "title": "替身会话 · 六类行投影",
                "phase": "completedSuccess",
                "lastActivityAt": Self.iso8601.string(from: Date().addingTimeInterval(-3600)),
                "lastAssistantPreview": "产物已生成。",
                "workspacePath": "/Users/e2e/poker_texas_air",
                "pendingInteractionSummary": ["permissionCount": 0, "userInputCount": 0],
            ],
            // 流程面板投影专用会话（项 3：行级 plan 行 → 任务拆解卡折叠/展开断言；
            // 标题不含 "E2E"——避免与登录套件 test12 的「E2E」搜索过滤断言耦合）
            [
                "sessionId": "sess-e2e-plan",
                "title": "替身会话 · 流程面板投影",
                "phase": "completedSuccess",
                "lastActivityAt": Self.iso8601.string(from: Date().addingTimeInterval(-5_400)),
                "lastAssistantPreview": "计划已生成。",
                "workspacePath": "/Users/e2e/matchclub",
                "pendingInteractionSummary": ["permissionCount": 0, "userInputCount": 0],
            ],
            // 思考折叠投影专用会话（项 4：complete 态 reasoning 行 → 默认折叠 + 展开交互）
            [
                "sessionId": "sess-e2e-think",
                "title": "替身会话 · 思考折叠投影",
                "phase": "completedSuccess",
                "lastActivityAt": Self.iso8601.string(from: Date().addingTimeInterval(-9_000)),
                "lastAssistantPreview": "退避策略说明完成。",
                "pendingInteractionSummary": ["permissionCount": 0, "userInputCount": 0],
            ],
        ]
        // recordedPinned 命中的会话注入 pinned 投影（服务端投影 zcode-task 写的前置验证面）
        for index in sessions.indices {
            if let sessionId = sessions[index]["sessionId"] as? String,
               let isPinned = pinned[sessionId] {
                sessions[index]["pinned"] = isPinned
            }
        }
        let frame: [String: Any] = [
            "wireVersion": 1,
            "kind": "complete",
            "logicalFrameId": UUID().uuidString,
            "logicalFrameOrdinal": 0,
            "topic": topic,
            "subscriptionId": "sub-sess-e2e",
            "frame": [
                "topic": topic,
                "subscriptionId": "sub-sess-e2e",
                "fromSeq": 0,
                "toSeq": 1,
                "sentAt": Self.iso8601.string(from: Date()),
                "payload": ["kind": "snapshot", "snapshot": ["sessions": sessions]],
            ],
        ]
        fire(event: "onDynamicSessionsIndexFrame", payload: frame, channel: channel, delays: delays) {
            self.lock.lock()
            self._sessionsIndexEventFires += 1
            self.lock.unlock()
        }
    }

    /// conversation 增量事件（deltas 逻辑帧，row.appended）
    private func fireConversationDelta(sessionId: String, row: [String: Any], channel: ConnectionChannel, delay: TimeInterval) {
        let topic = "conversation/\(sessionId)"
        let frame: [String: Any] = [
            "wireVersion": 1,
            "kind": "complete",
            "logicalFrameId": UUID().uuidString,
            "logicalFrameOrdinal": 0,
            "topic": topic,
            "subscriptionId": "sub-conv-e2e",
            "frame": [
                "topic": topic,
                "subscriptionId": "sub-conv-e2e",
                "fromSeq": 0,
                "toSeq": 1,
                "sentAt": Self.iso8601.string(from: Date()),
                "payload": ["kind": "deltas", "deltas": [["op": "row.appended", "row": row]]],
            ],
        ]
        fire(event: "onDynamicConversationFrame", payload: frame, channel: channel, delays: [delay]) {
            self.lock.lock()
            self._conversationEventFires += 1
            self.lock.unlock()
        }
    }

    /// conversation 状态增量（deltas 逻辑帧，state.updated patch）：
    /// 挂起交互（pendingInteractions）下发 / 应答清空的通道
    private func fireConversationStateDelta(sessionId: String, patch: [String: Any],
                                            channel: ConnectionChannel, delay: TimeInterval) {
        let topic = "conversation/\(sessionId)"
        let frame: [String: Any] = [
            "wireVersion": 1,
            "kind": "complete",
            "logicalFrameId": UUID().uuidString,
            "logicalFrameOrdinal": 0,
            "topic": topic,
            "subscriptionId": "sub-conv-e2e",
            "frame": [
                "topic": topic,
                "subscriptionId": "sub-conv-e2e",
                "fromSeq": 1,
                "toSeq": 2,
                "sentAt": Self.iso8601.string(from: Date()),
                "payload": ["kind": "deltas", "deltas": [["op": "state.updated", "patch": patch]]],
            ],
        ]
        fire(event: "onDynamicConversationFrame", payload: frame, channel: channel, delays: [delay]) {
            self.lock.lock()
            self._conversationEventFires += 1
            self.lock.unlock()
        }
    }

    /// "conversation/<sessionId>" topic → sessionId
    private func sessionId(ofTopic topic: String) -> String {
        topic.hasPrefix("conversation/") ? String(topic.dropFirst("conversation/".count)) : topic
    }

    /// setTaskPinned 后回推 session.upserted（sessions-index delta，带 pinned 投影）：
    /// 验证「sessions-index 是否反映 zcode-task 写」的前置面。
    private func fireSessionUpserted(sessionId: String, pinned: Bool, channel: ConnectionChannel) {
        let topic = "sessions-index/\(workspacePathForTopics)"
        let session: [String: Any] = [
            "sessionId": sessionId,
            "title": "替身会话 · 登录链路验收",
            "phase": "running",
            "lastActivityAt": Self.iso8601.string(from: Date()),
            "lastAssistantPreview": "替身端持续输出中…",
            "pendingInteractionSummary": ["permissionCount": 1, "userInputCount": 0],
            "pinned": pinned,
        ]
        let frame: [String: Any] = [
            "wireVersion": 1,
            "kind": "complete",
            "logicalFrameId": UUID().uuidString,
            "logicalFrameOrdinal": 0,
            "topic": topic,
            "subscriptionId": "sub-sess-e2e",
            "frame": [
                "topic": topic,
                "subscriptionId": "sub-sess-e2e",
                "fromSeq": 2,
                "toSeq": 3,
                "sentAt": Self.iso8601.string(from: Date()),
                "payload": ["kind": "deltas", "deltas": [["op": "session.upserted", "session": session]]],
            ],
        ]
        fire(event: "onDynamicSessionsIndexFrame", payload: frame, channel: channel, delays: [0.15]) {}
    }

    /// 替身工作区路径（server-info workspaces[0].path，topic 键）
    private var workspacePathForTopics: String { "/Users/e2e/zcode-workspace" }

    /// 通用事件下发：仅在客户端已 eventListen 该事件时发送并计数
    private func fire(event: String, payload: [String: Any], channel: ConnectionChannel,
                      delays: [TimeInterval], onFire: @escaping () -> Void) {
        for delay in delays {
            queue.asyncAfter(deadline: .now() + delay) { [weak self, weak channel] in
                guard let self, let channel, channel.open else { return }
                guard let eventId = channel.eventId(for: event) else { return }
                channel.sendWSFrame(self.rpcFrame(header: [.int(204), .int(eventId)], body: StubRPC.object(payload)))
                onFire()
            }
        }
    }

    // MARK: - 帧编码

    /// RPC 帧载荷：serialize(header 数组) + serialize(body)，外套 13 字节 Regular 帧
    fileprivate func rpcFrame(header: [StubRPC], body: StubRPC) -> Data {
        let payload = StubRPC.serialize(.array(header)) + StubRPC.serialize(body)
        var frame = Data(capacity: 13 + payload.count)
        frame.append(1) // ProtocolMessageType.regular
        frame.appendUInt32BE(0) // id
        frame.appendUInt32BE(0) // ack
        frame.appendUInt32BE(UInt32(payload.count))
        frame.append(payload)
        return frame
    }

    // MARK: - HTTP 报文解析

    /// 从累积缓冲解析一条完整 HTTP 请求；body 未收齐返回 nil（等待更多字节）。
    /// totalLength 为该请求消耗的字节数（相对 buffer.startIndex）。
    fileprivate func parseHTTP(_ buffer: Data) -> (request: RecordedRequest, totalLength: Int)? {
        guard let headerRange = buffer.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let headerData = buffer.subdata(in: buffer.startIndex..<headerRange.lowerBound)
        guard let headerText = String(data: headerData, encoding: .utf8) else { return nil }
        var lines = headerText.components(separatedBy: "\r\n")
        guard !lines.isEmpty else { return nil }
        let requestLine = lines.removeFirst()
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0])
        let target = String(parts[1])

        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = String(line[..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }

        var path = target
        var query: [String: String] = [:]
        if let question = target.firstIndex(of: "?") {
            path = String(target[..<question])
            let queryString = String(target[target.index(after: question)...])
            for pair in queryString.split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                let key = String(kv[0]).removingPercentEncoding ?? String(kv[0])
                let value = kv.count > 1 ? (String(kv[1]).removingPercentEncoding ?? String(kv[1])) : ""
                query[key] = value
            }
        }

        let contentLength = Int(headers["content-length"] ?? "0") ?? 0
        let bodyStartOffset = buffer.distance(from: buffer.startIndex, to: headerRange.upperBound)
        guard buffer.count >= bodyStartOffset + contentLength else { return nil }
        let body: Data
        if contentLength > 0 {
            body = buffer.subdata(in: headerRange.upperBound..<buffer.index(headerRange.upperBound, offsetBy: contentLength))
        } else {
            body = Data()
        }
        let bodyText = String(data: body, encoding: .utf8) ?? ""
        return (RecordedRequest(method: method, path: path, query: query, headers: headers, body: bodyText),
                bodyStartOffset + contentLength)
    }

    fileprivate func handleParsedRequest(_ request: RecordedRequest, channel: ConnectionChannel) {
        lock.lock()
        _requests.append(request)
        lock.unlock()

        let isWSUpgrade = (request.path == "/ws")
            && (request.headers["upgrade"]?.lowercased() == "websocket")
        if isWSUpgrade {
            handleWSUpgrade(request, channel: channel)
        } else {
            handleHTTP(request) { [weak channel] response in
                channel?.sendRaw(response, closeAfter: true)
            }
        }
    }

    private func httpResponse(status: String, headers: [String: String], body: Data) -> Data {
        var merged = headers
        var text = "HTTP/1.1 \(status)\r\n"
        if !status.hasPrefix("101") {
            merged["Content-Length"] = String(body.count)
            if merged["Connection"] == nil { merged["Connection"] = "close" }
        }
        for (key, value) in merged.sorted(by: { $0.key < $1.key }) {
            text += "\(key): \(value)\r\n"
        }
        text += "\r\n"
        var out = Data(text.utf8)
        out.append(body)
        return out
    }

    // MARK: - 工具

    private func toJSON(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("null".utf8)
    }

    static func sha1Base64(_ input: String) -> String {
        let data = Data(input.utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
        _ = data.withUnsafeBytes { buffer in
            CC_SHA1(buffer.baseAddress, CC_LONG(data.count), &digest)
        }
        return Data(digest).base64EncodedString()
    }
}

private extension Data {
    mutating func appendUInt32BE(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }
}

// MARK: - 单条连接（HTTP 累积解析 ↔ WebSocket 帧流切换）

/// 每条 NWConnection 一个 channel：先按 HTTP 累积解析（分片到达安全），
/// 升级成功后切换为 WS 帧解析（客户端帧带掩码；服务器帧不带）。
final class ConnectionChannel {
    private let connection: NWConnection
    private unowned let server: E2ELoginStubServer
    private var buffer = Data()
    private var isWebSocket = false
    private var isOpen = true
    /// event 名 → 客户端 eventListen id（[102, id, channel, event]）
    private var eventIds: [String: Int] = [:]

    init(connection: NWConnection, server: E2ELoginStubServer) {
        self.connection = connection
        self.server = server
    }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            if case .failed = state { self.shutdown() }
            if case .cancelled = state { self.shutdown() }
        }
        connection.start(queue: queue)
        receiveLoop()
    }

    /// tearDown 关停：直接取消，不再走服务端 close 帧
    func shutdown() {
        guard isOpen else { return }
        isOpen = false
        connection.cancel()
    }

    func eventId(for name: String) -> Int? {
        eventIds[name]
    }

    func beginWebSocket() {
        isWebSocket = true
    }

    /// 是否已完成 WS 升级（dropAllWebSocketChannels 只掐断 WS 通道，不动 HTTP 通道）
    var isWebSocketUpgraded: Bool { isWebSocket }

    var open: Bool { isOpen }

    private func close() {
        guard isOpen else { return }
        isOpen = false
        connection.cancel()
        server.removeChannel(self)
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, isComplete, error in
            guard let self, self.isOpen else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.drain()
            }
            if isComplete || error != nil {
                self.close()
                return
            }
            self.receiveLoop()
        }
    }

    /// 解析缓冲：HTTP 阶段按完整请求切分；WS 阶段按帧边界切分
    private func drain() {
        if isWebSocket {
            while let frame = parseWSFrame() {
                handleWSFrame(frame)
            }
            return
        }
        while isOpen, !isWebSocket, let parsed = server.parseHTTP(buffer) {
            guard buffer.count >= parsed.totalLength else { break }
            buffer.removeSubrange(buffer.startIndex..<buffer.index(buffer.startIndex, offsetBy: parsed.totalLength))
            server.handleParsedRequest(parsed.request, channel: self)
            if isWebSocket {
                // 升级完成：同一批到达的字节继续按 WS 帧解析
                while let frame = parseWSFrame() {
                    handleWSFrame(frame)
                }
                break
            }
        }
    }

    // MARK: WS 帧解析（RFC 6455 服务端视角）

    private struct WSFrame {
        let opcode: UInt8
        let payload: Data
    }

    private func parseWSFrame() -> WSFrame? {
        guard buffer.count >= 2 else { return nil }
        let first = buffer[buffer.startIndex]
        let second = buffer[buffer.startIndex + 1]
        let opcode = first & 0x0F
        let masked = (second & 0x80) != 0
        var length = Int(second & 0x7F)
        var offset = 2
        if length == 126 {
            guard buffer.count >= offset + 2 else { return nil }
            length = (Int(buffer[buffer.startIndex + offset]) << 8) | Int(buffer[buffer.startIndex + offset + 1])
            offset += 2
        } else if length == 127 {
            guard buffer.count >= offset + 8 else { return nil }
            length = 0
            for index in 0..<8 {
                length = (length << 8) | Int(buffer[buffer.startIndex + offset + index])
            }
            offset += 8
        }
        var maskKey: [UInt8] = []
        if masked {
            guard buffer.count >= offset + 4 else { return nil }
            maskKey = (0..<4).map { buffer[buffer.startIndex + offset + $0] }
            offset += 4
        }
        guard buffer.count >= offset + length else { return nil }
        var payload = buffer.subdata(in: (buffer.startIndex + offset)..<(buffer.startIndex + offset + length))
        if masked {
            for index in 0..<payload.count {
                payload[index] ^= maskKey[index % 4]
            }
        }
        buffer.removeSubrange(buffer.startIndex..<buffer.index(buffer.startIndex, offsetBy: offset + length))
        return WSFrame(opcode: opcode, payload: payload)
    }

    private func handleWSFrame(_ frame: WSFrame) {
        switch frame.opcode {
        case 0x8: // close：回显空关闭帧后收尾
            sendRaw(Data([0x88, 0x00]), closeAfter: true)
        case 0x9: // ping → pong
            var pong = Data([0x8A, UInt8(frame.payload.count)])
            pong.append(frame.payload)
            sendRaw(pong, closeAfter: false)
        case 0x1, 0x2: // text / binary → RPC 帧
            handleRPCPayload(frame.payload)
        default:
            break
        }
    }

    /// RPC 帧解析：13 字节头（帧类型恒为 Regular=1）+ serialize([type, id, …]) + serialize(body)。
    /// RPC 消息类型取自反序列化后 header 数组首元素（100=promise / 102=eventListen）。
    private func handleRPCPayload(_ payload: Data) {
        guard payload.count >= 13 else { return }
        let frameType = payload[payload.startIndex]
        guard frameType == 1 else { return } // 仅处理 Regular 帧
        var offset = payload.index(payload.startIndex, offsetBy: 13)
        guard let header = StubRPC.deserialize(payload, &offset) else { return }
        let body: StubRPC = (offset < payload.endIndex) ? (StubRPC.deserialize(payload, &offset) ?? .undefined) : .undefined
        guard case .array(let items) = header, let messageType = items.first?.intValue else { return }

        switch messageType {
        case 100: // promise 请求
            server.handleRPCCall(items, body, channel: self)
        case 102: // eventListen：记录 id 供后续 eventFire
            if items.count >= 4, case .int(let id) = items[1], case .string(let event) = items[3] {
                eventIds[event] = id
            }
        default:
            break
        }
    }

    // MARK: 发送

    fileprivate func sendWSFrame(_ rpcFrame: Data) {
        sendWSFrameRaw(rpcFrame)
    }

    /// WS 二进制消息封装（服务器帧不带掩码）
    private func sendWSFrameRaw(_ payload: Data) {
        var message = Data()
        message.append(0x82) // FIN + binary
        if payload.count < 126 {
            message.append(UInt8(payload.count))
        } else if payload.count <= 0xFFFF {
            message.append(126)
            message.append(UInt8((payload.count >> 8) & 0xFF))
            message.append(UInt8(payload.count & 0xFF))
        } else {
            message.append(127)
            for shift in stride(from: 56, through: 0, by: -8) {
                message.append(UInt8((payload.count >> shift) & 0xFF))
            }
        }
        message.append(payload)
        sendRaw(message, closeAfter: false)
    }

    fileprivate func sendRaw(_ data: Data, closeAfter: Bool) {
        guard isOpen else { return }
        connection.send(content: data, completion: .contentProcessed { [weak self] _ in
            if closeAfter {
                self?.close()
            }
        })
    }
}

// MARK: - RPC 自定义序列化（对齐 Sources/Services/RPC/RPCSerialization.swift 的最小子集）

/// 仅覆盖替身所需类型：undefined(0) / string(1) / array(4) / object(5, JSON) / int(6, VQL)。
enum StubRPC {
    case undefined
    case int(Int)
    case string(String)
    case array([StubRPC])
    case object([String: StubRPC])

    var objectValue: [String: StubRPC]? {
        if case .object(let dict) = self { return dict }
        return nil
    }

    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    var intValue: Int? {
        if case .int(let value) = self { return value }
        return nil
    }

    /// 便捷构造：[String: Any] 字典（值为任意 JSON 兼容类型）
    static func object(_ dict: [String: Any]) -> StubRPC {
        var result: [String: StubRPC] = [:]
        for (key, value) in dict { result[key] = stubValue(value) }
        return .object(result)
    }

    // MARK: VQL（7 bit 分组，高位续传）

    private static func writeIntVQL(_ value: Int) -> Data {
        var v = UInt64(bitPattern: Int64(value))
        if v == 0 { return Data([0x00]) }
        var bytes: [UInt8] = []
        while v != 0 {
            var byte = UInt8(v & 0b0111_1111)
            v >>= 7
            if v != 0 { byte |= 0b1000_0000 }
            bytes.append(byte)
        }
        return Data(bytes)
    }

    private static func readIntVQL(_ data: Data, _ offset: inout Int) -> Int? {
        var value = 0
        var shift = 0
        while true {
            guard offset < data.count else { return nil }
            let byte = data[data.startIndex + offset]
            offset += 1
            value |= Int(byte & 0b0111_1111) << shift
            if byte & 0b1000_0000 == 0 { return value }
            shift += 7
            if shift > 63 { return nil }
        }
    }

    // MARK: serialize

    static func serialize(_ value: StubRPC) -> Data {
        var out = Data()
        append(value, to: &out)
        return out
    }

    private static func append(_ value: StubRPC, to out: inout Data) {
        switch value {
        case .undefined:
            out.append(0)
        case .int(let i):
            out.append(6)
            out.append(writeIntVQL(Int(UInt32(bitPattern: Int32(truncatingIfNeeded: i)))))
        case .string(let s):
            let utf8 = Data(s.utf8)
            out.append(1)
            out.append(writeIntVQL(utf8.count))
            out.append(utf8)
        case .array(let items):
            out.append(4)
            out.append(writeIntVQL(items.count))
            for item in items { append(item, to: &out) }
        case .object(let dict):
            var json: [String: Any] = [:]
            for (key, item) in dict { json[key] = jsonValue(of: item) }
            let data = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("null".utf8)
            out.append(5)
            out.append(writeIntVQL(data.count))
            out.append(data)
        }
    }

    private static func jsonValue(of value: StubRPC) -> Any {
        switch value {
        case .undefined: return NSNull()
        case .int(let i): return i
        case .string(let s): return s
        case .array(let items): return items.map(jsonValue(of:))
        case .object(let dict): return dict.mapValues(jsonValue(of:))
        }
    }

    // MARK: deserialize

    static func deserialize(_ data: Data, _ offset: inout Int) -> StubRPC? {
        guard offset < data.count else { return nil }
        let tag = data[data.startIndex + offset]
        offset += 1
        switch tag {
        case 0:
            return .undefined
        case 1:
            guard let length = readIntVQL(data, &offset),
                  offset + length <= data.count else { return nil }
            let slice = data.subdata(in: (data.startIndex + offset)..<(data.startIndex + offset + length))
            offset += length
            return .string(String(data: slice, encoding: .utf8) ?? "")
        case 5:
            guard let length = readIntVQL(data, &offset),
                  offset + length <= data.count else { return nil }
            let slice = data.subdata(in: (data.startIndex + offset)..<(data.startIndex + offset + length))
            offset += length
            guard let json = try? JSONSerialization.jsonObject(with: slice) else { return nil }
            return stubValue(json)
        case 4:
            guard let count = readIntVQL(data, &offset) else { return nil }
            var items: [StubRPC] = []
            items.reserveCapacity(min(count, 4096))
            for _ in 0..<count {
                guard let item = deserialize(data, &offset) else { return nil }
                items.append(item)
            }
            return .array(items)
        case 6:
            guard let value = readIntVQL(data, &offset) else { return nil }
            return .int(Int(Int32(bitPattern: UInt32(truncatingIfNeeded: value))))
        default:
            return nil // buffer/vsbuffer：替身协议面不消费
        }
    }

    private static func stubValue(_ any: Any) -> StubRPC {
        switch any {
        case is NSNull:
            return .undefined
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return .int(number.boolValue ? 1 : 0)
            }
            return .int(number.intValue)
        case let string as String:
            return .string(string)
        case let array as [Any]:
            return .array(array.map(stubValue))
        case let dict as [String: Any]:
            var result: [String: StubRPC] = [:]
            for (key, value) in dict { result[key] = stubValue(value) }
            return .object(result)
        default:
            return .undefined
        }
    }
}

// MARK: - 便捷查询（供用例断言）

extension E2ELoginStubServer {
    /// 是否记录过指定 method+path 的请求
    func hasRequest(method: String, path: String) -> Bool {
        requests.contains { $0.method == method && $0.path == path }
    }

    /// 最近一条匹配请求（无则 nil）
    func lastRequest(method: String, path: String) -> RecordedRequest? {
        requests.last { $0.method == method && $0.path == path }
    }

    /// 匹配请求计数
    func requestCount(method: String, path: String) -> Int {
        requests.filter { $0.method == method && $0.path == path }.count
    }
}

import Foundation

// MARK: - RPC 通道传输抽象（局域网 ChannelClient 与云中继 RelayChannelClient 共用门面）

/// ZCodeServerConnection 唯一关心的通道语义：Promise 调用 / 事件订阅 / 断开。
/// 只读边界拦截在 ZCodeServerConnection.call 出口，两实现均被覆盖。
protocol RPCChannelTransport: Actor {
    func call(_ channel: String, _ command: String, _ arg: RPCValue,
              timeout: TimeInterval) async throws -> RPCValue
    func listen(_ channel: String, _ event: String, _ arg: RPCValue,
                handler: @escaping @Sendable (RPCValue) -> Void) async -> EventSubscription
    func disconnect() async
}

// MARK: - 中继 RPC 通道（桥内标准 RPC；载荷无 13 字节 SocketProtocol 头）

/// 连接编排（逆向结论 handshake 帧序）：
/// WS open → auth_init → auth_challenge → auth_response → auth_ack(matched)
/// → bootstrap-request → workspace-list-request → workspace-bridge-open → workspace-bridge-ready
/// → 桥内 rpc-frame 承载标准 RPC，等待桌面下行 Initialize([200]) 即就绪。
/// 断线由 RelayTransport 指数退避重连+重走 auth；paired 恢复后重建桥（bridgeGeneration 递增
/// 并携带上次 recoveryId），pending RPC 全部失败由 Store 层 resync 自愈。
actor RelayChannelClient: RPCChannelTransport {

    enum State: CustomStringConvertible {
        case uninitialized
        case idle
        case closed
        var description: String {
            switch self {
            case .uninitialized: return "uninitialized"
            case .idle: return "idle"
            case .closed: return "closed"
            }
        }
    }

    /// connectRelay 成功摘要（ZCodeServerConnection 五步日志与伪 server-info 用）
    struct ConnectSummary {
        var desktopAppVersion: String?
        var bridgeKind: String?
        var workspacePath: String
        var workspaceKey: String?
        var initialTaskId: String?
        var recoveryId: String?
        var sessionCount: Int
        /// workspace-list-request 返回的全部工作区（2026-10-06 web /remote/v4 页面同源取证：
        /// 桌面对配对客户端回 workspace-list-response{result:{workspaces[], activeWorkspaceKey}}；
        /// 首位 = active（与桥一致），其余为桌面打开的其它工作区——多 workspace 枚举源）
        var workspaces: [RelayWorkspaceSummary] = []
    }

    /// workspace-list-response 的条目（宽容解析：键 workspaceKey|workspaceIdentity|path 逐级
    /// 取原始 key；身份/名/kind 可选——kind/remoteSessionId 为 canBridge 门控字段，C-15）
    struct RelayWorkspaceSummary: Equatable {
        var workspaceKey: String
        var path: String?
        var workspaceIdentity: String?
        var name: String?
        var kind: String?
        var remoteSessionId: String?

        /// C-15 canBridge 门控（web 同款判别，bundle 实证）：
        /// `kind!=='remote' || !!(workspaceIdentity && remoteSessionId)`
        /// ——本地工作区恒可桥；remote 工作区需 identity+remoteSessionId 双全（远端会话
        /// 在场才可开桥，否则 web 侧退 home-only 不开桥）。kind 缺席视为非 remote
        ///（web 对 undefined!==`remote` 同判真，旧桌面不携带 kind 时行为不变）。
        var canBridge: Bool {
            guard kind == "remote" else { return true }
            let hasIdentity = workspaceIdentity.map { !$0.isEmpty } ?? false
            let hasSession = remoteSessionId.map { !$0.isEmpty } ?? false
            return hasIdentity && hasSession
        }
    }

    private(set) var state: State = .uninitialized
    private var lastRequestId = 0
    private var pendingResponses: [Int: CheckedContinuation<RPCValue, Error>] = [:]
    private var initializeWaiters: [CheckedContinuation<Void, Error>] = []
    private var eventHandlers: [Int: @Sendable (RPCValue) -> Void] = [:]
    /// 活跃订阅参数表（桥重建后在新区重发，恢复 sessions-index/conversation 推流）
    private var activeEventListeners: [Int: (channel: String, event: String, arg: RPCValue)] = [:]
    private var onClosed: (@Sendable (Error?) -> Void)?

    private let transport: RelayTransport
    private let link: RelayLinkConfig
    private let appVersion: String
    /// workspace-list-request 的全部工作区（连接期采集，summary 携带给装配层）
    private var workspaceSummaries: [RelayWorkspaceSummary] = []

    // 桥状态
    private var bridgeGeneration = 0
    private var lastRecoveryId: String?
    private var bridgeIdentity: RelayFrameCodec.Identity?
    /// 桥重建互斥（degraded 快速重建 / paired 恢复重建两路汇入，防并发 openBridge
    /// 互踩单槽 bridgeOpenWaiter）
    private var bridgeRebuildInFlight = false
    private(set) var desktopAppVersion: String?
    private(set) var initialViewState: JSONValue?
    private(set) var taskListJSON: JSONValue?
    private(set) var activeWorkspaceKey: String?
    private(set) var activeTaskId: String?
    /// 首次连接成功的摘要（重连重建后仍可读）
    private(set) var summary: ConnectSummary?

    init(transport: RelayTransport, link: RelayLinkConfig, appVersion: String) {
        self.transport = transport
        self.link = link
        self.appVersion = appVersion
    }

    // MARK: 连接编排

    /// 全链路：transport 鉴权 → bootstrap → bridge-open → 等桌面 Initialize。
    /// 成功后 state=.idle，后续 call/listen 走桥内 rpc-frame。
    func connectRelay(timeout: TimeInterval,
                      onClosed: @escaping @Sendable (Error?) -> Void) async throws -> ConnectSummary {
        self.onClosed = onClosed
        await transport.setRPCMessageHandler { [weak self] bytes in
            Task { await self?.handleRPCPayload(bytes) }
        }
        await transport.setClosedHandler { [weak self] reason in
            Task { await self?.handleTerminalFailure(reason) }
        }
        await transport.setPairedRecoveryHandler { [weak self] in
            Task { await self?.transportDidPair() }
        }
        // 桥退化回调（C-13）：先于 transport.start 注册（帧 handler 先于订阅/建桥纪律，
        // 桌面宣告可能在任意时点到达）
        await transport.setBridgeDegradedHandler { [weak self] reason in
            Task { await self?.handleBridgeDegraded(reason: reason) }
        }

        // 1. WS + auth（auth_init/auth_challenge/auth_response/auth_ack）
        try await transport.start(timeout: timeout)

        // 2. bootstrap（desktopAppVersion / initialViewState / tasks；81KB 列表单帧直出）
        let bootstrap = try await requestApp("bootstrap-request", timeout: 15)
        let result = bootstrap["result"]
        desktopAppVersion = result?["desktopAppVersion"]?.stringValue
        initialViewState = result?["initialViewState"]
        taskListJSON = result?["tasks"]
        // 诊断：bootstrap.tasks 的跨工作区面（web 移动流任务首页同源数据；
        // 若 tasks 行自带多 workspacePath，即为多 workspace 枚举源，无需额外 RPC）
        let taskWs = Set((taskListJSON?.arrayValue ?? []).compactMap {
            $0.objectValue?["workspacePath"]?.stringValue
        })
        UserDefaults.standard.set(
            "tasks=\(taskCount) distinctWs=\(taskWs.count) [\(taskWs.sorted().joined(separator: " | ").prefix(500))]",
            forKey: "diag.bootstrap.tasks")
        if let version = desktopAppVersion {
            let versionDrift = link.desktopAppVersion.map { $0 != version } ?? false
            log(.ok, "bootstrap-response · \(version) · tasks=\(taskCount)"
                + (versionDrift ? " · ⚠ 桌面版本与配对链接不一致" : ""))
        }

        // 3. workspace-list（activeWorkspaceKey + 全部工作区——多 workspace 枚举源）
        var workspaceSummaries: [RelayWorkspaceSummary] = []
        let workspaceList = try? await requestApp("workspace-list-request", timeout: 10)
        if let listResult = workspaceList?["result"] {
            activeWorkspaceKey = listResult["activeWorkspaceKey"]?.stringValue
            activeTaskId = listResult["activeTaskId"]?.stringValue
            self.workspaceSummaries = Self.parseWorkspaceSummaries(listResult["workspaces"])
        }
        if activeWorkspaceKey == nil {
            activeWorkspaceKey = initialViewState?["activeWorkspaceKey"]?.stringValue
            activeTaskId = activeTaskId ?? initialViewState?["activeTaskId"]?.stringValue
        }

        // 4. 开桥 + 等桌面 Initialize
        try await openBridge(workspaceKey: activeWorkspaceKey, taskId: activeTaskId)
        return summary ?? ConnectSummary(
            desktopAppVersion: desktopAppVersion, bridgeKind: nil,
            workspacePath: activeWorkspaceKey ?? "", workspaceKey: activeWorkspaceKey,
            initialTaskId: activeTaskId, recoveryId: lastRecoveryId, sessionCount: taskCount,
            workspaces: workspaceSummaries)
    }

    /// workspace-list-response 条目宽容解析（web 同源：条目优先带 workspaceKey 原始键；
    /// 缺席时按 web tc(path,identity) 公开式补算 = identity 优先、path 兜底——identity
    /// 缺席的本地工作区 key 即 path，与切换面「未收录路径冒充 key」的反查兜底不同，
    /// 此处是桌面侧同款键构造，非臆造）
    nonisolated static func parseWorkspaceSummaries(_ value: JSONValue?) -> [RelayWorkspaceSummary] {
        (value?.arrayValue ?? []).compactMap { item in
            guard let d = item.objectValue else { return nil }
            let key = d["workspaceKey"]?.stringValue
                ?? d["workspaceIdentity"]?.stringValue
                ?? d["path"]?.stringValue ?? ""
            guard !key.isEmpty else { return nil }
            return RelayWorkspaceSummary(
                workspaceKey: key,
                path: d["path"]?.stringValue,
                workspaceIdentity: d["workspaceIdentity"]?.stringValue,
                name: d["name"]?.stringValue ?? d["title"]?.stringValue,
                kind: d["kind"]?.stringValue,
                remoteSessionId: d["remoteSessionId"]?.stringValue)
        }
    }

    /// workspace-bridge-open → workspace-bridge-ready → 桥身份绑定 → 等待 Initialize([200])
    private func openBridge(workspaceKey: String?, taskId: String?) async throws {
        guard let workspaceKey, !workspaceKey.isEmpty else {
            throw RPCError(message: "中继 bootstrap 未提供 activeWorkspaceKey", name: "RelayFailure")
        }
        bridgeGeneration += 1
        let bridgeSessionId = UUID().uuidString.hexString
        var payload: [String: JSONValue] = [
            "zcode_type": .string("workspace-bridge-open"),
            "bridgeSessionId": .string(bridgeSessionId),
            "bridgeGeneration": .int(bridgeGeneration),
            "workspaceKey": .string(workspaceKey),
        ]
        if let recoveryId = lastRecoveryId {
            payload["recoveryId"] = .string(recoveryId)
        }
        if let taskId, !taskId.isEmpty {
            payload["taskId"] = .string(taskId)
        }
        log(.working, "workspace-bridge-open · gen=\(bridgeGeneration) workspace=\(workspaceKey)")

        let ready = try await transport.requestAppPayload(
            payload, zcodeType: "workspace-bridge-open", timeout: 15,
            bridgeSessionId: bridgeSessionId)
        let bridge = ready["bridge"]
        guard let readySessionId = bridge?["bridgeSessionId"]?.stringValue else {
            throw RPCError(message: "workspace-bridge-ready 缺 bridge 字段", name: "RelayFailure")
        }
        lastRecoveryId = bridge?["recoveryId"]?.stringValue
        bridgeIdentity = RelayFrameCodec.Identity(
            bridgeSessionId: readySessionId,
            bridgeGeneration: bridge?["bridgeGeneration"]?.intValue ?? bridgeGeneration,
            recoveryId: lastRecoveryId)
        await transport.bindFrameChannel(identity: bridgeIdentity!)
        // 视图状态采用 ready 回包值（web P() 同构：s=bridge.workspaceKey、c=bridge.initialTaskId）
        activeWorkspaceKey = bridge?["workspaceKey"]?.stringValue ?? workspaceKey
        activeTaskId = bridge?["initialTaskId"]?.stringValue ?? taskId
        log(.ok, "workspace-bridge-ready · kind=\(bridge?["kind"]?.stringValue ?? "?")"
            + " · path=\(bridge?["workspacePath"]?.stringValue ?? "?")"
            + (lastRecoveryId.map { " · recoveryId=\($0.prefix(10))…" } ?? ""))

        summary = ConnectSummary(
            desktopAppVersion: desktopAppVersion,
            bridgeKind: bridge?["kind"]?.stringValue,
            workspacePath: bridge?["workspacePath"]?.stringValue ?? workspaceKey,
            workspaceKey: bridge?["workspaceKey"]?.stringValue ?? workspaceKey,
            initialTaskId: bridge?["initialTaskId"]?.stringValue ?? taskId,
            recoveryId: lastRecoveryId,
            sessionCount: taskCount,
            workspaces: workspaceSummaries)

        // C-11 场景①（bridge-open 成功）：上报 mobile-view-state-update——含首连/切换/
        // 重连重建全部开桥路径（web P() 内 M(bridge.workspaceKey, bridge.initialTaskId)；
        // 桌面以 mobileViewState 优先于 initialViewState 决定断线重连后的落点，bundle 实证）
        await sendMobileViewStateUpdate(taskId: activeTaskId)

        // 桥内桌面即推 Initialize（探针实测 04 01 06 c8 01 00 = serialize([200])+serialize(undefined)）
        try await waitForInitialize(timeout: 10)
        // 桥重建（断线恢复）后重发全部活跃 eventListen：新桥上恢复 v4 topic 推流
        resendActiveEventListeners()
    }

    private func resendActiveEventListeners() {
        guard !activeEventListeners.isEmpty else { return }
        for (id, listener) in activeEventListeners.sorted(by: { $0.key < $1.key }) {
            let header = RPCSerialization.serialize(.array([
                .int(RequestType.eventListen.rawValue), .int(id),
                .string(listener.channel), .string(listener.event),
            ]))
            let body = RPCSerialization.serialize(listener.arg)
            Task { [weak self] in
                try? await self?.transport.sendRPCMessage(header + body)
            }
        }
        log(.ok, "桥重建 · 重发 \(activeEventListeners.count) 路 eventListen 订阅")
    }

    private func waitForInitialize(timeout: TimeInterval) async throws {
        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            await self?.failInitializeWaiters(
                RPCError(message: "等待桥内 Initialize 超时", name: "TimeoutError"))
        }
        defer { timeoutTask.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            // 本闭包在 actor 上下文同步执行
            switch state {
            case .idle: continuation.resume()
            case .closed:
                continuation.resume(throwing: RPCError(message: "连接在 Initialize 前已关闭", name: "ConnectionClosed"))
            case .uninitialized: initializeWaiters.append(continuation)
            }
        }
        log(.ok, "桥内 Initialize 已到 · RPC 通道就绪")
    }

    private func failInitializeWaiters(_ error: Error) {
        let waiters = initializeWaiters
        initializeWaiters.removeAll()
        for waiter in waiters { waiter.resume(throwing: error) }
    }

    // MARK: 断线重连编排（transport paired 恢复后重建桥）

    private func handleTerminalFailure(_ reason: RelayCloseReason) {
        guard state != .closed else { return }
        state = .closed
        let error = RPCError(message: reason.codeText, name: "RelayClosed")
        failPending(error)
        failInitializeWaiters(error)
        eventHandlers.removeAll()
        onClosed?(error)
    }

    /// transport 重连成功（paired）后的桥重建：generation 递增 + recoveryId，
    /// 未确认帧不跨桥重放（replay 语义保守化：pending RPC 失败，Store 层 resync）。
    func rebuildBridgeAfterReconnect() async {
        guard state == .idle, !bridgeRebuildInFlight, let workspaceKey = activeWorkspaceKey else { return }
        bridgeRebuildInFlight = true
        defer { bridgeRebuildInFlight = false }
        log(.info, "重连恢复 · 重建 workspace 桥（gen=\(bridgeGeneration + 1)）")
        let errored = pendingResponses
        pendingResponses.removeAll()
        for (_, continuation) in errored {
            continuation.resume(throwing: RPCError(message: "中继重连，请求已中断", name: "Reconnecting"))
        }
        do {
            try await openBridge(workspaceKey: workspaceKey, taskId: activeTaskId)
        } catch {
            log(.error, "桥重建失败：\(error.localizedDescription)")
            handleTerminalFailure(.relayUnavailable("桥重建失败"))
        }
    }

    /// transport 每次 paired（含首次）时回调；首次由 connectRelay 编排，其后走重建
    func transportDidPair() async {
        // connectRelay 编排期间（state==uninitialized）不处理，避免与首连竞争
        guard state == .idle else { return }
        await rebuildBridgeAfterReconnect()
    }

    /// 桥退化快速重建（C-13；web T(reason) 的移动端同构）：桌面宣告 bridge-degraded /
    /// 无 requestId 的 workspace-bridge-error，或本地 45s 无 ack 判退化后，pending RPC
    /// 立即失败（死桥上等待只会逐条 30s 超时）→ 同 transport 重开桥（不重走 auth/pair，
    /// recoveryId 随开携回）。传输已失联时不动（等 paired 恢复链路接管重建）。
    private func handleBridgeDegraded(reason: String) async {
        guard state == .idle, !bridgeRebuildInFlight else { return }
        guard let workspaceKey = activeWorkspaceKey else { return }
        guard await transport.isPaired else {
            log(.info, "桥退化但传输未配对 · 等 paired 恢复后重建（\(reason)）")
            return
        }
        bridgeRebuildInFlight = true
        defer { bridgeRebuildInFlight = false }
        log(.error, "桥退化 · \(reason) · 快速重建（gen=\(bridgeGeneration + 1)）")
        let errored = pendingResponses
        pendingResponses.removeAll()
        for (_, continuation) in errored {
            continuation.resume(throwing: RPCError(
                message: "桥退化重建，请求已中断（\(reason)）", name: "Reconnecting"))
        }
        do {
            try await openBridge(workspaceKey: workspaceKey, taskId: activeTaskId)
            log(.ok, "退化桥已重建 · 活跃订阅已随开桥重发")
        } catch {
            if await transport.isPaired {
                log(.error, "退化桥重建失败：\(error.localizedDescription)")
                handleTerminalFailure(.relayUnavailable("退化桥重建失败"))
            } else {
                // 重建途中传输失联：不走终态，paired 恢复链路（transportDidPair）接管
                log(.info, "退化桥重建中断（传输失联）· 等 paired 恢复后重建")
            }
        }
    }

    // MARK: 工作区切换（P3-10；C-10/C-11/C-12 对齐 web：bridge-open + view-state，无 reconnect 前置）

    /// 目标工作区的 workspaceKey 解析（C-12 严格口径）：**一律取清单原始 workspaceKey**
    /// ——先按 workspaceIdentity（远端工作区判别键）命中，再按 path；清单未收录返回 nil。
    /// 不再以 path 冒充 key（未取证兜底会发非法 key 致切换必败；web 同构判别
    /// Ia({workspacePath,workspaceIdentity}) = identity 优先、path 兜底——但那是桌面侧
    /// 清单条目的键构造，移动端反查必须落在清单既有条目上，落空即如实报「不在清单」）。
    func resolvedWorkspaceKey(forPath path: String, identity: String? = nil) -> String? {
        if let identity, !identity.isEmpty,
           let match = workspaceSummaries.first(where: { $0.workspaceIdentity == identity }) {
            return match.workspaceKey
        }
        if let match = workspaceSummaries.first(where: { $0.path == path }) {
            return match.workspaceKey
        }
        return nil
    }

    /// mobile-view-state-update 发送（C-11；web M(e,n) 同构，bundle 实证）：
    /// `{zcode_type, viewState:{activeWorkspaceKey, activeTaskId?, updatedAt:毫秒},
    ///   deviceInfo:{platform,version:appVersion,name}}`
    /// 单向通知（web sendPayload 面：无 requestId、无响应等待）；activeTaskId 缺席时
    /// 整键省略（web `...n?{activeTaskId:n}:{}` 同款）。appVersion 以 **version** 键入帧。
    func sendMobileViewStateUpdate(taskId: String? = nil) async {
        guard let key = activeWorkspaceKey, !key.isEmpty else { return }
        var viewState: [String: JSONValue] = [
            "activeWorkspaceKey": .string(key),
            "updatedAt": .int(Int(Date().timeIntervalSince1970 * 1000)),
        ]
        if let taskId, !taskId.isEmpty {
            viewState["activeTaskId"] = .string(taskId)
        }
        await transport.sendAppNotification(
            [
                "zcode_type": .string("mobile-view-state-update"),
                "viewState": .object(viewState),
                "deviceInfo": .object(Self.mobileDeviceInfo(appVersion: appVersion)),
            ],
            zcodeType: "mobile-view-state-update")
    }

    /// web u4t({appVersion}) 的移动端裁剪：platform/version/name 必带，浏览器专属键
    /// （viewport/userAgent/timezone 等）iOS 无对应面不带。platform 值域未取证——web
    /// 实测恒 `web`/`mobile-browser`，移动端如实报 `ios`（单向通知，被弃亦无功能回退）
    nonisolated static func mobileDeviceInfo(appVersion: String) -> [String: JSONValue] {
        [
            "platform": .string("ios"),
            "version": .string(appVersion.isEmpty ? "1.0.0" : appVersion),
            "name": .string("ZCode Mobile"),
        ]
    }

    /// 视图状态上报（C-11 场景②「打开任务」与切换后清空任务两用；web
    /// updateMobileViewState(r, taskId) 由活动任务变化 effect 触发，M(e,n) 的
    /// n=undefined 同步清模块态）。本地 activeTaskId 随之刷新——退化/重连重建以它为
    /// 落点（web T() 捕获 s/c 同构）。打开任务调用面由视图与 Store 波次接线。
    func updateActiveTask(_ taskId: String?) async {
        activeTaskId = (taskId?.isEmpty == false) ? taskId : nil
        await sendMobileViewStateUpdate(taskId: activeTaskId)
    }

    /// 工作区切换的桥落地（复用既有 WS/auth，不重建配对）：在途 RPC 按重连同口径失败
    /// （未确认帧不跨桥重放）→ 三路 dynamic eventListen 的 workspacePath 参数改写到
    /// 新工作区 → openBridge 重开桥（generation 递增、新身份绑定、Initialize 等待、
    /// 活跃 eventListen 重发）。失败回滚原工作区桥（尽力）后原样上抛。
    func switchBridgeWorkspace(workspaceKey: String, workspacePath: String) async throws {
        guard state == .idle else {
            throw RPCError(message: "通道未就绪（state=\(state)）", name: "NotInitialized")
        }
        // 切换全程持重建互斥（含失败回滚）：degraded 快速重建/paired 恢复重建在切换
        // 在途时并发 openBridge 会互踩单槽 bridgeOpenWaiter
        bridgeRebuildInFlight = true
        defer { bridgeRebuildInFlight = false }
        let previousKey = activeWorkspaceKey
        let previousTaskId = activeTaskId
        let previousPath = activeEventListeners.values.lazy.compactMap {
            $0.arg.jsonValue?["workspacePath"]?.stringValue
        }.first
        // 未确认 RPC 不跨桥重放（rebuildBridgeAfterReconnect 同口径）
        let errored = pendingResponses
        pendingResponses.removeAll()
        for (_, continuation) in errored {
            continuation.resume(throwing: RPCError(message: "工作区切换，请求已中断", name: "Reconnecting"))
        }
        redirectListenerWorkspacePath(to: workspacePath)
        activeWorkspaceKey = workspaceKey
        activeTaskId = nil
        do {
            try await openBridge(workspaceKey: workspaceKey, taskId: nil)
        } catch {
            let switchError = error
            // 回滚（尽力）：恢复原工作区桥与事件参数，保持当前工作区可用态；回滚失败
            // 将其错误并进上抛信息（不静默吞——切换失败与回滚失败都要回到 UI）
            log(.error, "工作区桥切换失败，回滚原工作区：\(switchError.localizedDescription)")
            redirectListenerWorkspacePath(to: previousPath)
            activeWorkspaceKey = previousKey
            activeTaskId = previousTaskId
            if let previousKey {
                do {
                    try await openBridge(workspaceKey: previousKey, taskId: previousTaskId)
                } catch {
                    let combined = "\(switchError.localizedDescription)"
                        + "（回滚原工作区亦失败：\(error.localizedDescription)，可断开重连恢复）"
                    throw RPCError(
                        message: combined,
                        name: (switchError as? RPCError)?.name ?? "SwitchFailed")
                }
            }
            throw switchError
        }
    }

    /// dynamic eventListen 参数重定向（conversation / sessions-index / workspace-config
    /// 三路按 workspacePath 定向；无该键的监听不动）
    private func redirectListenerWorkspacePath(to path: String?) {
        guard let path else { return }
        for (id, listener) in activeEventListeners {
            guard var fields = listener.arg.jsonValue?.objectValue,
                  fields["workspacePath"] != nil else { continue }
            fields["workspacePath"] = .string(path)
            var updated = listener
            updated.arg = .json(.object(fields))
            activeEventListeners[id] = updated
        }
    }

    func disconnect() async {
        state = .closed
        bridgeIdentity = nil
        activeEventListeners.removeAll()
        let error = RPCError(message: "连接已关闭", name: "ConnectionClosed")
        failPending(error)
        failInitializeWaiters(error)
        eventHandlers.removeAll()
        await transport.close()
    }

    // MARK: RPC（Promise）

    /// 桥内标准 RPC：serialize([100,id,channel,command]) + serialize([arg]) 作为 rpc-frame 载荷，
    /// **无 13 字节 SocketProtocol 头**（framingDeltas ①）。
    /// **body 是「参数数组」**（桌面端 toService 代理把调用参数列表整体序列化，host 端
    /// fromService 按 `apply(o, p)` 展开）——实测对象直传会被解成 undefined（探针 disc_probe2：
    /// 数组包对象 [201] 成功 / 对象直传 [202] undefined），与局域网直连的裸 arg 形态不同。
    func call(_ channel: String, _ command: String, _ arg: RPCValue = .undefined,
              timeout: TimeInterval = 30) async throws -> RPCValue {
        guard state == .idle else {
            throw RPCError(message: "通道未就绪（state=\(state)）", name: "NotInitialized")
        }
        let id = lastRequestId
        lastRequestId += 1

        let header = RPCSerialization.serialize(.array([
            .int(RequestType.promise.rawValue), .int(id), .string(channel), .string(command),
        ]))
        let body = RPCSerialization.serialize(.array([arg]))

        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            await self?.resolveResponse(
                id: id,
                result: .failure(RPCError(message: "RPC 超时：\(channel).\(command)", name: "TimeoutError")))
        }
        defer { timeoutTask.cancel() }

        do {
            return try await withCheckedThrowingContinuation { continuation in
                // 本闭包在 actor 上下文同步执行；响应/超时/发送失败三路竞争经 resolveResponse 恰好 resume 一次
                pendingResponses[id] = continuation
                Task { [weak self] in
                    guard let self else { return }
                    do {
                        try await self.transport.sendRPCMessage(header + body)
                    } catch {
                        await self.resolveResponse(
                            id: id,
                            result: .failure(RPCError(message: error.localizedDescription, name: "SendFailed")))
                    }
                }
            }
        } catch {
            pendingResponses.removeValue(forKey: id)
            throw error
        }
    }

    // MARK: 事件（EventListen / EventDispose）

    /// eventListen：arg **对象原样**（web 端 dynamic event 走 `service.onDynamicXxx(arg)`，
    /// proxy 经 requestEvent 直传 arg，不数组化——与 promise 面的参数数组不同，
    /// 探针 probe9 实测数组化 eventListen 后桌面快照帧不到）。
    func listen(_ channel: String, _ event: String, _ arg: RPCValue = .undefined,
                handler: @escaping @Sendable (RPCValue) -> Void) -> EventSubscription {
        let id = lastRequestId
        lastRequestId += 1
        eventHandlers[id] = handler
        activeEventListeners[id] = (channel, event, arg)

        let header = RPCSerialization.serialize(.array([
            .int(RequestType.eventListen.rawValue), .int(id), .string(channel), .string(event),
        ]))
        let body = RPCSerialization.serialize(arg)
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.transport.sendRPCMessage(header + body)
            } catch {
                self.log(.error, "eventListen 发送失败：\(error.localizedDescription)")
            }
        }

        return EventSubscription { [weak self] in
            guard let self else { return }
            Task { await self.disposeEvent(id) }
        }
    }

    private func disposeEvent(_ id: Int) {
        guard eventHandlers.removeValue(forKey: id) != nil else { return }
        activeEventListeners.removeValue(forKey: id)
        guard state == .idle else { return }
        let header = RPCSerialization.serialize(.array([
            .int(RequestType.eventDispose.rawValue), .int(id),
        ]))
        let body = RPCSerialization.serialize(.array([.undefined]))
        Task { [weak self] in
            try? await self?.transport.sendRPCMessage(header + body)
        }
    }

    // MARK: 下行 RPC 载荷（无 13 字节头，直接 deserialize）

    private func handleRPCPayload(_ bytes: Data) {
        var offset = 0
        guard let headerValue = RPCSerialization.deserialize(bytes, &offset),
              let body = RPCSerialization.deserialize(bytes, &offset) else { return }
        guard case .array(let headerItems) = headerValue,
              let rawType = headerItems[safe: 0]?.intValue,
              let type = ResponseType(rawValue: rawType) else { return }

        switch type {
        case .initialize:
            state = .idle
            let waiters = initializeWaiters
            initializeWaiters.removeAll()
            for waiter in waiters { waiter.resume() }
        case .promiseSuccess:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            resolveResponse(id: id, result: .success(body))
        case .promiseError:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            resolveResponse(id: id, result: .failure(decodeError(body)))
        case .promiseErrorObj:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            resolveResponse(id: id, result: .failure(
                RPCError(message: "RPC 错误对象", name: "ErrorObj", detail: body.jsonValue)))
        case .eventFire:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            eventHandlers[id]?(body)
        }
    }

    private func resolveResponse(id: Int, result: Result<RPCValue, Error>) {
        guard let continuation = pendingResponses.removeValue(forKey: id) else { return }
        switch result {
        case .success(let value): continuation.resume(returning: value)
        case .failure(let error): continuation.resume(throwing: error)
        }
    }

    private func failPending(_ error: Error) {
        let responses = pendingResponses
        pendingResponses.removeAll()
        for (_, continuation) in responses {
            continuation.resume(throwing: error)
        }
    }

    private func decodeError(_ body: RPCValue) -> RPCError {
        guard let json = body.jsonValue, case .object(let dict) = json else {
            return RPCError(message: "未知 RPC 错误", name: "Error")
        }
        var error = RPCError(message: "未知 RPC 错误", name: "Error", detail: .object(dict))
        if case .string(let message)? = dict["message"] { error.message = message }
        if case .string(let name)? = dict["name"] { error.name = name }
        return error
    }

    // MARK: app 层请求便捷封装 + 日志

    private func requestApp(_ zcodeType: String, timeout: TimeInterval) async throws -> JSONValue {
        try await transport.requestAppPayload(
            ["zcode_type": .string(zcodeType)], zcodeType: zcodeType, timeout: timeout)
    }

    private var taskCount: Int {
        taskListJSON?.arrayValue?.count ?? 0
    }

    nonisolated private func log(_ kind: ConnectLogLine.Kind, _ text: String) {
        transport.onLog(kind, text)
    }
}

private extension String {
    /// UUID → 无连字符 hex（桌面端 bridgeSessionId 合法字符集 [A-Za-z0-9._~-]）
    var hexString: String {
        self.replacingOccurrences(of: "-", with: "").lowercased()
    }
}

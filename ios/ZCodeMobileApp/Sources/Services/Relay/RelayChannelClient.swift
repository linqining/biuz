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

    // 桥状态
    private var bridgeGeneration = 0
    private var lastRecoveryId: String?
    private var bridgeIdentity: RelayFrameCodec.Identity?
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

        // 1. WS + auth（auth_init/auth_challenge/auth_response/auth_ack）
        try await transport.start(timeout: timeout)

        // 2. bootstrap（desktopAppVersion / initialViewState / tasks；81KB 列表单帧直出）
        let bootstrap = try await requestApp("bootstrap-request", timeout: 15)
        let result = bootstrap["result"]
        desktopAppVersion = result?["desktopAppVersion"]?.stringValue
        initialViewState = result?["initialViewState"]
        taskListJSON = result?["tasks"]
        if let version = desktopAppVersion {
            let versionDrift = link.desktopAppVersion.map { $0 != version } ?? false
            log(.ok, "bootstrap-response · \(version) · tasks=\(taskCount)"
                + (versionDrift ? " · ⚠ 桌面版本与配对链接不一致" : ""))
        }

        // 3. workspace-list（activeWorkspaceKey）
        let workspaceList = try? await requestApp("workspace-list-request", timeout: 10)
        if let listResult = workspaceList?["result"] {
            activeWorkspaceKey = listResult["activeWorkspaceKey"]?.stringValue
            activeTaskId = listResult["activeTaskId"]?.stringValue
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
            initialTaskId: activeTaskId, recoveryId: lastRecoveryId, sessionCount: taskCount)
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
            sessionCount: taskCount)

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
        guard state == .idle, let workspaceKey = activeWorkspaceKey else { return }
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

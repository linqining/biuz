import Foundation

// MARK: - 中继传输（_Vn 中继传输类移植：WS JSON 文本帧 + auth 握手 + 心跳/重连 + rpc-frame 通道）
//
// 与局域网直连的三层差异（逆向结论 framingDeltas）：
// ① WS 层是 JSON 文本帧（auth_*/pair_status_*/data/error），不是二进制 SocketProtocol 帧；
// ② 应用载荷走 data 帧 payload（bootstrap/bridge-open 等 app 请求 + rpc-frame 分片可靠层）；
// ③ rpc-frame 载荷 = serialize(header)+serialize(body) 纯 RPCSerialization 字节（无 13 字节头）。
// 心跳 10s+jitter≤2s、ack 看门狗 30s、waiting 30s 超时、指数退避 min(10s, 500ms·2^n)。

actor RelayTransport {

    // MARK: 常量（bundle AN/jN/MN/NN + Mh）
    static let heartbeatIntervalSeconds: TimeInterval = 10
    static let heartbeatJitterMaxSeconds: TimeInterval = 2
    static let heartbeatAckTimeoutSeconds: TimeInterval = 30
    static let waitingTimeoutSeconds: TimeInterval = 30
    static let deviceOfflineGraceSeconds: TimeInterval = 15
    static let reconnectBaseMillis: UInt64 = 500
    static let reconnectMaxSeconds: TimeInterval = 10
    static let maxReconnectAttempts = 6 // 浏览器端无限退避；移动端后台功耗上限（终态上抛由 UI 重试）

    // MARK: rpc-frame 通道常量（Vzn/f9）
    static let saturationHighWaterMarkBytes = 1_000_000
    static let saturationLowWaterMarkBytes = 256_000
    static let replayBufferMaxBytes = 8 * 1024 * 1024
    static let replayGraceSeconds: TimeInterval = 45

    enum RelayState: Equatable, CustomStringConvertible {
        case idle
        case connecting        // WS open → auth 握手中
        case waiting           // auth_ack waiting（等桌面确认配对）
        case paired            // matched，可承载 app/rpc 帧
        case reconnecting
        case closed
        case failed(RelayCloseReason)

        var description: String {
            switch self {
            case .idle: return "idle"
            case .connecting: return "authenticating"
            case .waiting: return "waiting"
            case .paired: return "paired"
            case .reconnecting: return "reconnecting"
            case .closed: return "closed"
            case .failed(let reason): return "failed(\(reason.codeText))"
            }
        }
    }

    /// app 层（data 帧非 rpc）请求回执
    struct AppResponse {
        var zcodeType: String
        var payload: JSONValue
    }

    /// 桥信息（workspace-bridge-ready.bridge）
    struct BridgeInfo {
        var bridgeSessionId: String
        var bridgeGeneration: Int
        var recoveryId: String?
        var kind: String?
        var workspaceKey: String?
        var workspacePath: String?
        var initialTaskId: String?
    }

    private let config: RelayLinkConfig
    /// 连接日志钩子（L2 面板；token/凭据一律不落日志）
    nonisolated let onLog: @Sendable (ConnectLogLine.Kind, String) -> Void

    private(set) var state: RelayState = .idle
    private var socket: URLSessionWebSocketTask?
    private var urlSession: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 60
        return URLSession(configuration: configuration)
    }()

    // auth/pair 状态
    private var hadPairedSession = false
    /// socket 世代（每次建连 +1）：区分「同 socket 心跳 matched」与「重连 socket matched」，
    /// 对齐 web 端 lastPairedSocketGeneration 语义（onSendReady 仅 reconnected-socket 触发）
    private var socketGeneration = 0
    private var lastPairedSocketGeneration = 0
    private var terminalSid: String?
    private var lastPairStatusAckAt: Date?
    private var heartbeatTask: Task<Void, Never>?
    private var ackWatchdogTask: Task<Void, Never>?
    private var waitingTimeoutTask: Task<Void, Never>?
    private var deviceOfflineTask: Task<Void, Never>?

    // 重连
    private var reconnectAttempt = 0
    private var intentionallyClosed = false
    private var reconnectLoopTask: Task<Void, Never>?
    private var pendingAuthContinuations: [CheckedContinuation<Void, Error>] = []
    /// 首次 paired 到位（start 完成的信号）
    private var pairedWaiters: [CheckedContinuation<Void, Error>] = []

    // app 层请求-响应（requestId 键控）
    private var appRequestWaiters: [String: CheckedContinuation<JSONValue, Error>] = [:]
    /// bridge-ready 特殊：按 bridgeSessionId 匹配
    private var bridgeOpenWaiter: (bridgeSessionId: String, continuation: CheckedContinuation<JSONValue, Error>)?

    // rpc-frame 发送队列（Vzn 口径）
    private var frameIdentity: RelayFrameCodec.Identity?
    private var outboundBatches: [(messageSeq: Int, frames: [[String: JSONValue]], outerBytes: Int, queuedAt: Date, nextFrameIndex: Int)] = []
    private var nextPhysicalSeq = 1
    private var nextMessageSeq = 1
    private var unacknowledgedByteCount = 0
    private var saturated = false
    private var saturationWaiters: [CheckedContinuation<Void, Never>] = []
    private var replayDeadline: Date?
    private var frameDegraded = false
    private var assembler = RelayFrameAssembler()
    /// 重组完成的下行 RPC 载荷（serialize(header)+serialize(body)）
    private var rpcMessageHandler: (@Sendable (Data) -> Void)?
    /// 通道终态上抛（终态错误 / 重试耗尽）
    private var closedHandler: (@Sendable (RelayCloseReason) -> Void)?
    /// 工作区清单推送上抛（workspace-list-updated，P3-10）：无 requestId 的主动推送，
    /// 走 resolveAppRequest 永远无 waiter 会被当作「无匹配 requestId」丢弃——单独拆 case 消费
    private var workspaceListUpdatedHandler: (@Sendable (JSONValue) -> Void)?
    /// 桥退化上抛（bridge-degraded / 无 requestId 的 workspace-bridge-error / replay 宽限超时，
    /// C-13）：RelayChannelClient 据此快速重建桥（对齐 web markDegraded → T(reason) 编排）
    private var bridgeDegradedHandler: (@Sendable (String) -> Void)?

    init(config: RelayLinkConfig, onLog: @escaping @Sendable (ConnectLogLine.Kind, String) -> Void) {
        self.config = config
        self.onLog = onLog
    }

    // MARK: 生命周期

    /// 建连 + auth 握手至 paired（超时抛错）。
    func start(timeout: TimeInterval) async throws {
        intentionallyClosed = false
        state = .connecting
        try await openSocketAndAuthenticate(timeout: timeout)
    }

    /// 主动断开（dispose 语义：不再重连）
    func close() {
        intentionallyClosed = true
        cancelTimers()
        reconnectLoopTask?.cancel()
        reconnectLoopTask = nil
        failAppWaiters(RPCError(message: String(localized: "中继连接已关闭"), name: "ConnectionClosed"))
        failPairedWaiters(RPCError(message: String(localized: "中继连接已关闭"), name: "ConnectionClosed"))
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
        state = .closed
    }

    func setRPCMessageHandler(_ handler: @escaping @Sendable (Data) -> Void) {
        rpcMessageHandler = handler
    }

    func setClosedHandler(_ handler: @escaping @Sendable (RelayCloseReason) -> Void) {
        closedHandler = handler
    }

    /// 重连恢复回调（曾配对过再次 matched；对齐 web 端 onSendReady(reconnected-socket)）
    private var pairedRecoveryHandler: (@Sendable () -> Void)?
    func setPairedRecoveryHandler(_ handler: @escaping @Sendable () -> Void) {
        pairedRecoveryHandler = handler
    }

    /// 工作区清单推送回调（P3-10；连接层据此刷新切换器清单）
    func setWorkspaceListUpdatedHandler(_ handler: @escaping @Sendable (JSONValue) -> Void) {
        workspaceListUpdatedHandler = handler
    }

    /// 桥退化回调（C-13；RelayChannelClient 在 connectRelay 时接线，先于任何桥建立）
    func setBridgeDegradedHandler(_ handler: @escaping @Sendable (String) -> Void) {
        bridgeDegradedHandler = handler
    }

    var desktopTerminalSid: String? { terminalSid }

    /// 传输是否处于 paired（桥重建编排据以区分「传输失联等恢复」与「真失败走终态」）
    var isPaired: Bool { state == .paired }

    // MARK: WS + auth

    private func openSocketAndAuthenticate(timeout: TimeInterval) async throws {
        guard var components = URLComponents(string: config.wssURL) else {
            throw RPCError(message: String(localized: "中继地址无效：\(config.wssURL)"), name: "RelayConfig")
        }
        if components.scheme == nil {
            components.scheme = "wss"
        }
        guard let url = components.url else {
            throw RPCError(message: String(localized: "中继地址无效：\(config.wssURL)"), name: "RelayConfig")
        }
        socketGeneration += 1
        let task = urlSession.webSocketTask(with: url)
        socket = task
        task.resume()
        log(.working, "WS 升级 \(url.host ?? "")\(url.path)?mid=…")

        // WS open 即发 auth_init（_Vn connect() 的 open 监听；meta 照 bundle 原样：
        // {platform:'web', version:<app_version|'web'>, name:'mobile-browser'}）
        sendJSON([
            "type": .string("auth_init"),
            "role": .string("terminal"),
            "device_sid": .string(config.deviceSid),
            "meta": .object([
                "platform": .string("web"),
                "version": .string(config.desktopAppVersion ?? "web"),
                "name": .string("mobile-browser"),
            ]),
            "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000)),
        ])
        log(.working, "auth_init · role=terminal · sid=\(config.deviceSid.prefix(12))…")

        // 读循环先行：auth_challenge 依赖它
        receiveNext()

        // auth 握手完成（paired 或 waiting）等待；waiting 态在此放行
        //（30s waitingTimer 负责超时终态），bootstrap 请求等待 paired 由调用方决定
        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            await self?.failPairedWaiters(
                RPCError(message: String(localized: "中继鉴权超时（auth 握手无响应）"), name: "TimeoutError"))
        }
        defer { timeoutTask.cancel() }
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                // 本闭包在 actor 上下文同步执行
                switch state {
                case .paired, .waiting: continuation.resume()
                case .failed(let reason):
                    continuation.resume(throwing: RPCError(message: reason.codeText, name: "RelayFailure"))
                case .closed:
                    continuation.resume(throwing: RPCError(message: String(localized: "连接在鉴权前已关闭"), name: "ConnectionClosed"))
                default: pairedWaiters.append(continuation)
                }
            }
        } catch {
            socket?.cancel(with: .normalClosure, reason: nil)
            failPairedWaiters(error) // 清残留 waiter（超时/失败两路竞争收口）
            throw error
        }
    }

    private func failPairedWaiters(_ error: Error) {
        let waiters = pairedWaiters
        pairedWaiters.removeAll()
        for waiter in waiters { waiter.resume(throwing: error) }
    }

    private func resolvePairedWaiters() {
        let waiters = pairedWaiters
        pairedWaiters.removeAll()
        for waiter in waiters { waiter.resume() }
    }

    // MARK: 读循环（JSON 文本帧）

    private func receiveNext() {
        guard let socket else { return }
        socket.receive { [weak self] result in
            guard let self else { return }
            Task { await self.handleReceive(result) }
        }
    }

    private func handleReceive(_ result: Result<URLSessionWebSocketTask.Message, Error>) {
        switch result {
        case .failure(let error):
            handleTransportFault(error)
        case .success(let message):
            receiveNext()
            switch message {
            case .string(let text):
                handleText(text)
            case .data(let data):
                // 中继全程 JSON 文本帧；二进制帧不合预期，忽略并记日志
                log(.info, "忽略非预期二进制帧（\(data.count)B）")
            @unknown default:
                break
            }
        }
    }

    private func handleText(_ text: String) {
        guard text.utf8.count <= RelayFrameCodec.maxPhysicalFrameBytes else {
            log(.error, "下行信封超限（envelopeTooLarge）")
            return
        }
        guard let data = text.data(using: .utf8),
              let message = try? JSONDecoder().decode(JSONValue.self, from: data),
              let type = message["type"]?.stringValue else {
            log(.info, "忽略非 JSON 文本帧（\(text.utf8.count)B）")
            return
        }
        switch type {
        case "auth_challenge":
            handleAuthChallenge(message)
        case "auth_ack", "pair_status_ack":
            if let sid = message["terminal_sid"]?.stringValue, !sid.isEmpty {
                terminalSid = sid
            }
            applyPairStatus(message["pair_status"]?.stringValue ?? "")
        case "data":
            handleDataPayload(message["payload"])
        case "error":
            handleRelayError(
                code: message["code"]?.stringValue ?? "",
                messageText: message["message"]?.stringValue ?? "")
        default:
            log(.info, "忽略帧 type=\(type)")
        }
    }

    // MARK: auth 握手

    private func handleAuthChallenge(_ message: JSONValue) {
        guard let nonce = message["nonce"]?.stringValue else {
            enterTerminalFailure(.invalidMobileConnection)
            return
        }
        let proof = RelayAuth.proof(
            passHash: config.passHash, nonce: nonce, role: "terminal", deviceSid: config.deviceSid)
        log(.ok, "auth_challenge · nonce=\(nonce.prefix(8))… → auth_response")
        sendJSON([
            "type": .string("auth_response"),
            "device_sid": .string(config.deviceSid),
            "proof": .string(proof),
            "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000)),
        ])
    }

    /// applyPairStatus（_Vn）：auth_ack/pair_status_ack 同路由
    private func applyPairStatus(_ status: String) {
        lastPairStatusAckAt = Date()
        armAckWatchdog()
        switch status {
        case "waiting":
            if hadPairedSession {
                // 曾经配对过：桌面短暂离线，保持心跳等 matched（stale 恢复由 watchdog 收口）
                setState(.waiting)
                startHeartbeat()
                return
            }
            setState(.waiting)
            startHeartbeat()
            startWaitingTimer()
        case "matched":
            reconnectAttempt = 0
            let wasPairedBefore = hadPairedSession
            let isSameSocket = lastPairedSocketGeneration == socketGeneration
            hadPairedSession = true
            lastPairedSocketGeneration = socketGeneration
            cancelWaitingTimer()
            setState(.paired)
            startHeartbeat()
            log(.ok, "auth_ack · pair_status=matched\(terminalSid.map { " · terminal_sid=\($0)" } ?? "")")
            resolvePairedWaiters()
            // 重连恢复仅在「曾配对过 + 新 socket」触发；同 socket 心跳 matched 不重建桥
            if wasPairedBefore && !isSameSocket {
                pairedRecoveryHandler?()
            }
        default:
            log(.error, "未知 pair_status=\(status)")
        }
    }

    private func handleRelayError(code: String, messageText: String) {
        let reason = RelayCloseReason.from(errorCode: code, message: messageText)
        log(.error, "error 帧 · code=\(code) \(messageText)")
        switch reason {
        case .deviceOffline:
            // 15s 宽限后重连（recoverFromDeviceOffline）
            deviceOfflineTask?.cancel()
            deviceOfflineTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(Self.deviceOfflineGraceSeconds * 1_000_000_000))
                guard !Task.isCancelled else { return }
                await self?.scheduleReconnectNow()
            }
        // KICKED/AUTH_FAILED/WRONG_PARAM 与 4010/4012 类：终态（重连无法自愈）
        case .sessionConflict, .invalidMobileConnection, .sessionNotFound,
             .sessionExpired, .desktopDisconnected, .workspaceClosed:
            enterTerminalFailure(reason)
        case .relayUnavailable:
            // INTERNAL 等服务器错误：paired→waiting 等桌面，否则立即重连
            if state == .paired || (hadPairedSession && state == .waiting) {
                setState(.waiting)
                startHeartbeat()
            } else {
                scheduleReconnectNow()
            }
        }
    }

    // MARK: data 帧（app 载荷 + rpc-frame）

    private func handleDataPayload(_ payload: JSONValue?) {
        guard let payload, let zcodeType = payload["zcode_type"]?.stringValue else { return }
        switch zcodeType {
        case "rpc-frame", "rpc-frame-ack":
            handleRelayFramePayload(payload)
        case "bootstrap-response", "workspace-list-response", "platform-response",
             "workspace-reconnect-response":
            // 带 requestId 的响应：经 waiter 配对（workspace-reconnect-response 属
            // 请求-响应面，reconnect 请求经 requestAppPayload 发出即在此配对回来）
            resolveAppRequest(payload)
        case "app-error", "workspace-bridge-error":
            // web $2t.acceptPayload 首分支（bundle 实证）：带 requestId 的错误帧一律按
            // 失败 reject 对应 waiter（L9(reason,error) 形态）；无 requestId 的
            // workspace-bridge-error 按 bridgeSessionId 走桥退化（C-13/C-14）
            rejectErrorPayload(payload, zcodeType: zcodeType)
        case "bridge-degraded":
            // 桌面宣告桥退化（无 requestId 主动推送；web 下行分发器按 bridgeSessionId
            // 匹配当前桥后 markDegraded 快速重建，C-13）
            handleBridgeDegradedFrame(payload, message: Self.errorText(payload))
        case "workspace-list-updated":
            // 桌面端主动推送（无 requestId，永远无 waiter）：从 resolveAppRequest 组
            // 单独拆出上抛，否则落入「app 响应无匹配 requestId（忽略）」被丢弃（P3-10）
            workspaceListUpdatedHandler?(payload)
        case "workspace-bridge-ready":
            resolveBridgeOpen(payload)
        default:
            // 新增 app 层请求-响应族（P1-1 附件上传会话 attachmentPut 等）响应类型名
            // 未取证：凡携 requestId 且有在途 waiter 的 data 帧一律按 requestId 配对
            // 回填（宽容形态，与 resolveAppRequest 同口径）；无 waiter 维持未消费日志
            if let requestId = payload["requestId"]?.stringValue, appRequestWaiters[requestId] != nil {
                resolveAppRequest(payload)
            } else {
                log(.info, "data 帧 · zcode_type=\(zcodeType)（未消费）")
            }
        }
    }

    /// 错误帧的 reason+error 文本合成（web L9(reason,error) / K9(reason,error) 同口径：
    /// reason 为错误码、error 为人类可读串，两者皆可缺席）
    private static func errorText(_ payload: JSONValue) -> String {
        let reason = payload["reason"]?.stringValue ?? ""
        let error = payload["error"]?.stringValue ?? ""
        return [reason, error].filter { !$0.isEmpty }.joined(separator: ": ")
    }

    /// app-error / workspace-bridge-error 统一收口（C-14，web acceptPayload 首分支 + 下行
    /// 分发器合并）：
    /// ① 带 requestId 且有在途 waiter → 按失败 reject（不再被当成功响应 resume）；
    /// ② bridge-open 在途（bridgeSessionId 匹配面）→ 错误帧回带同桥 id 即失败，不等超时；
    /// ③ 无 requestId 的 workspace-bridge-error → bridgeSessionId 命中当前桥走退化（C-13）；
    /// ④ 其余（无 waiter 的 app-error）→ 仅记日志（web 上抛全局错误面 x(K9(reason,error))，
    ///    移动端无对应 UI 面）。
    private func rejectErrorPayload(_ payload: JSONValue, zcodeType: String) {
        let message = Self.errorText(payload)
        let error = RPCError(
            message: message.isEmpty ? String(localized: "\(zcodeType) 错误帧") : message,
            name: "RelayAppError", detail: payload)
        if let requestId = payload["requestId"]?.stringValue,
           let waiter = appRequestWaiters.removeValue(forKey: requestId) {
            log(.error, "\(zcodeType) · requestId=\(requestId.prefix(8))… · \(message)")
            waiter.resume(throwing: error)
            return
        }
        if let bridgeId = payload["bridgeSessionId"]?.stringValue,
           let waiter = bridgeOpenWaiter, waiter.bridgeSessionId == bridgeId {
            bridgeOpenWaiter = nil
            log(.error, "\(zcodeType) · bridge=\(bridgeId.prefix(8))… · \(message)")
            waiter.continuation.resume(throwing: error)
            return
        }
        if zcodeType == "workspace-bridge-error" {
            handleBridgeDegradedFrame(payload, message: message)
        } else {
            log(.error, "app-error（无在途 waiter）· \(message)")
        }
    }

    /// 桥退化统一入口（bridge-degraded 帧 / 无 requestId 的 workspace-bridge-error /
    /// replay 宽限超时）：bridgeSessionId 命中当前帧身份才生效（web 同款比对，异桥帧忽略）
    private func handleBridgeDegradedFrame(_ payload: JSONValue, message: String) {
        guard let identity = frameIdentity,
              payload["bridgeSessionId"]?.stringValue == identity.bridgeSessionId else {
            log(.info, "桥退化帧 bridgeSessionId 不匹配当前桥（忽略）")
            return
        }
        frameDegraded = true
        let reason = message.isEmpty ? String(localized: "reason 未携带") : message
        log(.error, "bridge-degraded · \(reason)")
        bridgeDegradedHandler?(reason)
    }

    private func resolveAppRequest(_ payload: JSONValue) {
        guard let requestId = payload["requestId"]?.stringValue,
              let waiter = appRequestWaiters.removeValue(forKey: requestId) else {
            log(.info, "app 响应无匹配 requestId（忽略）")
            return
        }
        waiter.resume(returning: payload)
    }

    private func resolveBridgeOpen(_ payload: JSONValue) {
        guard let waiter = bridgeOpenWaiter else { return }
        if let expected = payload["bridgeSessionId"]?.stringValue, expected != waiter.bridgeSessionId {
            return
        }
        bridgeOpenWaiter = nil
        waiter.continuation.resume(returning: payload)
    }

    private func failAppWaiters(_ error: Error) {
        let waiters = appRequestWaiters.values
        appRequestWaiters.removeAll()
        for waiter in waiters { waiter.resume(throwing: error) }
        if let bridgeWaiter = bridgeOpenWaiter {
            bridgeOpenWaiter = nil
            bridgeWaiter.continuation.resume(throwing: error)
        }
    }

    /// app 层请求（bootstrap-request / workspace-list-request 等），requestId 匹配响应。
    /// bridgeSessionId 非空时走 bridge-ready 匹配面（桌面回包未必回带 requestId，
    /// web 端口径按 bridgeSessionId 匹配；bridgeSessionId 必定回带——探针实测）。
    func requestAppPayload(_ payload: [String: JSONValue], zcodeType: String,
                           timeout: TimeInterval, bridgeSessionId: String? = nil) async throws -> JSONValue {
        guard state == .paired else {
            throw RPCError(message: String(localized: "中继未配对（state=\(state)）"), name: "NotPaired")
        }
        let requestId = payload["requestId"]?.stringValue ?? UUID().uuidString
        var request = payload
        request["requestId"] = .string(requestId)
        return try await withCheckedThrowingContinuation { continuation in
            if let bridgeSessionId {
                bridgeOpenWaiter = (bridgeSessionId, continuation)
            } else {
                appRequestWaiters[requestId] = continuation
            }
            sendJSON([
                "type": .string("data"),
                "payload": .object(request),
                "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000)),
            ])
            log(.working, "→ \(zcodeType) requestId=\(requestId.prefix(8))…")
            // 超时兜底
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                await self?.timeoutAppRequest(requestId: requestId, zcodeType: zcodeType,
                                              bridgeSessionId: bridgeSessionId)
            }
        }
    }

    private func timeoutAppRequest(requestId: String, zcodeType: String, bridgeSessionId: String?) {
        if bridgeSessionId != nil {
            guard let waiter = bridgeOpenWaiter else { return }
            bridgeOpenWaiter = nil
            log(.error, "\(zcodeType) 响应超时")
            waiter.continuation.resume(throwing: RPCError(message: String(localized: "\(zcodeType) 响应超时"), name: "TimeoutError"))
            return
        }
        guard let waiter = appRequestWaiters.removeValue(forKey: requestId) else { return }
        log(.error, "\(zcodeType) 响应超时")
        waiter.resume(throwing: RPCError(message: String(localized: "\(zcodeType) 响应超时"), name: "TimeoutError"))
    }

    /// app 层单向通知帧（web sendPayload 面，C-11）：mobile-view-state-update 无 requestId、
    /// 不注册 waiter、不等待响应——用 requestAppPayload 会凭空挂 30s 超时 waiter，故单列
    func sendAppNotification(_ payload: [String: JSONValue], zcodeType: String) {
        guard state == .paired else {
            log(.info, "\(zcodeType) 未发送（state=\(state)，非 paired）")
            return
        }
        sendJSON([
            "type": .string("data"),
            "payload": .object(payload),
            "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000)),
        ])
        log(.working, "→ \(zcodeType)（单向通知）")
    }

    // MARK: 心跳 / 看门狗

    private func startHeartbeat() {
        guard heartbeatTask == nil else { return }
        armAckWatchdog()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let current = await self.state
                guard current == .paired || current == .waiting else { return }
                await self.sendPairStatusQuery()
                // 桥本地退化监测（C-13 接线，原死代码）：45s 无 rpc-frame-ack 且尚有
                // 未确认批次 → 本地判退化（对齐 web 桥 Qi(timeout) 看门狗），上抛快速重建；
                // 桌面主动宣告走 bridge-degraded 帧，两路在 handleBridgeDegraded 汇合
                if await self.checkReplayDeadline() {
                    await self.notifyLocalBridgeDegraded()
                }
                let jitter = Double.random(in: 0...Self.heartbeatJitterMaxSeconds)
                try? await Task.sleep(nanoseconds: UInt64((Self.heartbeatIntervalSeconds + jitter) * 1_000_000_000))
            }
        }
    }

    /// checkReplayDeadline 判退化的上抛（帧身份在场才通知——未开桥时无退化语义）
    private func notifyLocalBridgeDegraded() {
        guard frameIdentity != nil else { return }
        let reason = String(localized: "rpc-frame 45s 无 ack（replay 宽限超时）")
        log(.error, reason)
        bridgeDegradedHandler?(reason)
    }

    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        ackWatchdogTask?.cancel()
        ackWatchdogTask = nil
    }

    private func armAckWatchdog() {
        ackWatchdogTask?.cancel()
        ackWatchdogTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.heartbeatAckTimeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.handleAckWatchdogTimeout()
        }
    }

    private func handleAckWatchdogTimeout() async {
        guard state == .paired || state == .waiting else { return }
        log(.error, "30s 无 pair_status_ack · 判定 stale → 重连")
        await reconnectAfterStale()
    }

    private func startWaitingTimer() {
        waitingTimeoutTask?.cancel()
        waitingTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.waitingTimeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.enterTerminalFailure(.invalidMobileConnection,
                                             logText: "waiting 30s 超时 · 桌面未确认配对")
        }
    }

    private func cancelWaitingTimer() {
        waitingTimeoutTask?.cancel()
        waitingTimeoutTask = nil
    }

    private func cancelTimers() {
        stopHeartbeat()
        cancelWaitingTimer()
        deviceOfflineTask?.cancel()
        deviceOfflineTask = nil
    }

    private func sendPairStatusQuery() {
        sendJSON([
            "type": .string("pair_status_query"),
            "device_sid": .string(config.deviceSid),
            "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000)),
        ])
    }

    // MARK: 重连（指数退避 min(10s, 500ms·2^n)；重走 auth_init）

    private func handleTransportFault(_ error: Error) {
        guard !intentionallyClosed, state != .closed else { return }
        socket = nil
        stopHeartbeat()
        failPairedWaiters(RPCError(message: String(localized: "传输中断：\(error.localizedDescription)"), name: "TransportError"))
        if hadPairedSession || reconnectAttempt > 0 {
            scheduleReconnect()
        } else {
            enterTerminalFailure(.relayUnavailable(error.localizedDescription))
        }
    }

    private func reconnectAfterStale() async {
        guard !intentionallyClosed else { return }
        socket?.cancel(with: .abnormalClosure, reason: nil)
        socket = nil
        stopHeartbeat()
        failPairedWaiters(RPCError(message: String(localized: "心跳超时重连"), name: "StaleConnection"))
        await reopenWithBackoff()
    }

    private func scheduleReconnect() {
        reconnectLoopTask?.cancel()
        reconnectLoopTask = Task { [weak self] in
            await self?.reopenWithBackoff()
        }
    }

    private func scheduleReconnectNow() {
        reconnectLoopTask?.cancel()
        reconnectLoopTask = Task { [weak self] in
            await self?.reopenWithBackoff(initialDelay: 0)
        }
    }

    private func reopenWithBackoff(initialDelay: TimeInterval? = nil) async {
        guard !intentionallyClosed else { return }
        setState(.reconnecting)
        var firstAttempt = true
        while !intentionallyClosed && reconnectAttempt < Self.maxReconnectAttempts {
            var delay = min(
                Self.reconnectMaxSeconds,
                Double(Self.reconnectBaseMillis * (1 << min(reconnectAttempt, 10)) / 1000))
            if firstAttempt, let initialDelay {
                delay = initialDelay
            }
            firstAttempt = false
            reconnectAttempt += 1
            log(.info, "第 \(reconnectAttempt) 次重连 · \(String(format: "%.1f", delay))s 后")
            do {
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            } catch { return }
            do {
                try await openSocketAndAuthenticate(timeout: 15)
                if state == .paired || state == .waiting {
                    return // 重连成功；桥重建由 RelayChannelClient.onPaired 编排
                }
            } catch {
                log(.info, "重连失败：\(error.localizedDescription)")
            }
        }
        if !intentionallyClosed {
            enterTerminalFailure(.relayUnavailable(String(localized: "重试 \(Self.maxReconnectAttempts) 次仍失败")))
        }
    }

    private func enterTerminalFailure(_ reason: RelayCloseReason, logText: String? = nil) {
        guard state != .closed, state != .failed(reason) else { return }
        cancelTimers()
        reconnectLoopTask?.cancel()
        reconnectLoopTask = nil
        failAppWaiters(RPCError(message: reason.codeText, name: "RelayFailure"))
        failPairedWaiters(RPCError(message: reason.codeText, name: "RelayFailure"))
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
        state = .failed(reason)
        log(.error, logText ?? "中继终态 · \(reason.codeText)")
        closedHandler?(reason)
    }

    private func setState(_ newState: RelayState) {
        guard state != newState else { return }
        state = newState
        if newState == .paired || newState == .waiting {
            cancelWaitingTimer()
        }
    }

    // MARK: 发送

    private func sendJSON(_ object: [String: JSONValue]) {
        guard let socket else { return }
        let json = JSONValue.object(object)
        guard let data = try? JSONEncoder().encode(json),
              let text = String(data: data, encoding: .utf8) else { return }
        socket.send(.string(text)) { [weak self] error in
            if let error {
                Task { await self?.handleTransportFault(error) }
            }
        }
    }

    // MARK: rpc-frame 通道（Vzn 口径：分片 + ack + 水位 + degraded）

    /// 开桥成功后绑定帧身份并重置发送队列
    func bindFrameChannel(identity: RelayFrameCodec.Identity) {
        frameIdentity = identity
        outboundBatches.removeAll()
        nextPhysicalSeq = 1
        nextMessageSeq = 1
        unacknowledgedByteCount = 0
        saturated = false
        frameDegraded = false
        replayDeadline = nil
        assembler.reset()
        resumeSaturationWaiters()
    }

    func unbindFrameChannel() {
        frameIdentity = nil
        outboundBatches.removeAll()
        unacknowledgedByteCount = 0
        saturated = false
        frameDegraded = false
        resumeSaturationWaiters()
    }

    var isFrameChannelDegraded: Bool { frameDegraded || frameIdentity == nil }

    /// 发送一条 RPC 载荷（serialize(header)+serialize(body)）；饱和时挂起等待水位回落。
    func sendRPCMessage(_ bytes: Data) async throws {
        guard let identity = frameIdentity, !frameDegraded, state == .paired else {
            throw RPCError(message: String(localized: "rpc-frame 通道不可用"), name: "ChannelUnavailable")
        }
        // 饱和水位：高 1MB 暂停，低 256KB 恢复
        while saturated {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                saturationWaiters.append(continuation)
            }
            guard frameIdentity != nil, !frameDegraded, state == .paired else {
                throw RPCError(message: String(localized: "rpc-frame 通道不可用"), name: "ChannelUnavailable")
            }
        }
        guard let frames = RelayFrameCodec.encodeMessage(
            bytes, identity: identity,
            firstPhysicalSeq: nextPhysicalSeq, messageSeq: nextMessageSeq) else {
            throw RPCError(message: String(localized: "RPC 载荷超限（maxMessageBytes=16MB）"), name: "MessageTooLarge")
        }
        // 外层信封字节精确测量（每帧 JSON 文本 UTF8 长度；常规路径仅 1 片）
        let outerBytes = frames.reduce(0) { frame, payload in
            let encoded = (try? JSONEncoder().encode(JSONValue.object(payload))) ?? Data()
            return frame + encoded.count
        }
        guard unacknowledgedByteCount + outerBytes <= Self.replayBufferMaxBytes else {
            // replayBufferExceeded：degraded → 整桥重建（由 RelayChannelClient 编排）
            frameDegraded = true
            log(.error, "replay 缓冲超限（>8MB）· 通道 degraded")
            throw RPCError(message: String(localized: "replay 缓冲超限"), name: "Degraded")
        }
        let messageSeq = nextMessageSeq
        outboundBatches.append((messageSeq, frames, outerBytes, Date(), 0))
        unacknowledgedByteCount += outerBytes
        nextMessageSeq += 1
        nextPhysicalSeq += frames.count
        if !saturated, unacknowledgedByteCount > Self.saturationHighWaterMarkBytes {
            saturated = true
            log(.info, "发送饱和（未 ack >1MB）· 暂停发送")
        }
        replayDeadline = Date().addingTimeInterval(Self.replayGraceSeconds)
        flushOutbound()
    }

    private func flushOutbound() {
        guard state == .paired, !frameDegraded else { return }
        for index in outboundBatches.indices {
            while outboundBatches[index].nextFrameIndex < outboundBatches[index].frames.count {
                let frame = outboundBatches[index].frames[outboundBatches[index].nextFrameIndex]
                sendJSON(["type": .string("data"), "payload": .object(frame),
                          "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000))])
                outboundBatches[index].nextFrameIndex += 1
            }
        }
    }

    private func handleRelayFramePayload(_ payload: JSONValue) {
        // 上行 ack（对我发送帧的确认）
        if let ackMessageSeq = RelayFrameCodec.decodeAck(payload) {
            processAck(ackMessageSeq)
            return
        }
        guard let identity = frameIdentity else { return }
        // 桥身份三元组校验（Lzn）
        if payload["bridgeSessionId"]?.stringValue != identity.bridgeSessionId
            || (payload["bridgeGeneration"]?.intValue).map({ $0 != identity.bridgeGeneration }) == true {
            log(.info, "忽略异桥 rpc-frame（identity 不匹配）")
            return
        }
        guard let fragment = RelayFrameCodec.decode(payload) else {
            log(.error, "rpc-frame 解析失败 · invalidPayload")
            return
        }
        switch assembler.accept(fragment) {
        case .incomplete(let ack):
            if let ack { sendAck(ackMessageSeq: ack) }
        case .duplicate(let ack):
            sendAck(ackMessageSeq: ack)
        case .fault(let reason):
            log(.error, "下行帧丢弃 · \(reason)")
            sendAck(ackMessageSeq: fragment.messageSeq) // 防桌面端无限重传
        case .completed(let bytes, let messageSeq):
            sendAck(ackMessageSeq: messageSeq)
            rpcMessageHandler?(bytes)
        }
    }

    private func sendAck(ackMessageSeq: Int) {
        guard let identity = frameIdentity else { return }
        sendJSON([
            "type": .string("data"),
            "payload": .object(RelayFrameCodec.makeAck(identity: identity, ackMessageSeq: ackMessageSeq)),
            "client_ts": .int(Int(Date().timeIntervalSince1970 * 1000)),
        ])
    }

    private func processAck(_ ackMessageSeq: Int) {
        var drained = false
        while let first = outboundBatches.first, first.messageSeq <= ackMessageSeq {
            unacknowledgedByteCount = max(0, unacknowledgedByteCount - first.outerBytes)
            outboundBatches.removeFirst()
            drained = true
        }
        if drained {
            replayDeadline = outboundBatches.isEmpty
                ? nil : Date().addingTimeInterval(Self.replayGraceSeconds)
            if saturated, unacknowledgedByteCount <= Self.saturationLowWaterMarkBytes {
                saturated = false
                log(.ok, "发送水位回落（<256KB）· 恢复发送")
                resumeSaturationWaiters()
            }
        }
    }

    private func resumeSaturationWaiters() {
        let waiters = saturationWaiters
        saturationWaiters.removeAll()
        for waiter in waiters { waiter.resume() }
    }

    /// degraded 监测：45s 无 ack / 重放宽限超时 → 抛给上层重建桥
    func checkReplayDeadline() -> Bool {
        guard !frameDegraded, let deadline = replayDeadline else { return false }
        if Date() > deadline, !outboundBatches.isEmpty {
            frameDegraded = true
            log(.error, "45s 未收到 rpc-frame-ack · 通道 degraded")
            return true
        }
        return false
    }

    private func log(_ kind: ConnectLogLine.Kind, _ text: String) {
        onLog(kind, text)
    }
}

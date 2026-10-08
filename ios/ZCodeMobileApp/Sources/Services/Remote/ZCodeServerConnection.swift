import Foundation

// MARK: - ServerRemoteInfo（packages/shared/src/server-remote.ts:5-38）

struct ServerWorkspaceInfo: Equatable {
    var path: String
    var label: String?
    var workspaceIdentity: String?
}

struct ServerRemoteInfo: Equatable {
    static let expectedProtocolVersion = 1 // SERVER_REMOTE_PROTOCOL_VERSION

    var serverId: String
    var name: String?
    var version: String
    var protocolVersion: Int
    var authRequired: Bool
    var workspaces: [ServerWorkspaceInfo]
    var capabilities: [String] // chips 展示（server-info.capabilities 键集，L5 双源口径之展示源）

    static func parse(_ json: JSONValue) -> ServerRemoteInfo? {
        guard let dict = json.objectValue,
              let serverId = dict["serverId"]?.stringValue,
              let version = dict["version"]?.stringValue else { return nil }
        let workspaces = (dict["workspaces"]?.arrayValue ?? []).compactMap { item -> ServerWorkspaceInfo? in
            guard let path = item.objectValue?["path"]?.stringValue else { return nil }
            return ServerWorkspaceInfo(
                path: path,
                label: item.objectValue?["label"]?.stringValue,
                workspaceIdentity: item.objectValue?["workspaceIdentity"]?.stringValue)
        }
        var capabilities: [String] = []
        if let caps = dict["capabilities"]?.objectValue {
                capabilities = caps.filter { $0.value.boolValue == true }.keys.sorted()
        }
        return ServerRemoteInfo(
            serverId: serverId,
            name: dict["name"]?.stringValue,
            version: version,
            protocolVersion: dict["protocolVersion"]?.intValue ?? 0,
            authRequired: dict["authRequired"]?.boolValue ?? false,
            workspaces: workspaces,
            capabilities: capabilities)
    }
}

// MARK: - 连接错误（L3 四态对照）

enum ConnectError: Error, Equatable {
    case http(status: Int, endpoint: String)   // 401 → 令牌不匹配
    case timeout(endpoint: String)             // 超时（含本地网络权限被拒的静默失败）
    case protocolVersion(actual: String)       // remote v1 / v4 wire v3 不符
    case emptyWorkspaces                       // workspaces 为空
    case handshakeFailed(String)               // v4 握手失败
    case transport(String)                     // WS 升级失败

    var headline: String {
        switch self {
        case .http(let status, _):
            return status == 401 ? String(localized: "无法验证访问令牌") : String(localized: "服务返回错误")
        case .timeout: return String(localized: "连接超时")
        case .protocolVersion: return String(localized: "协议版本不匹配")
        case .emptyWorkspaces: return String(localized: "工作区列表为空")
        case .handshakeFailed: return String(localized: "协议握手失败")
        case .transport: return String(localized: "无法建立 WebSocket")
        }
    }

    /// mono 错误码行（l3-err-code）
    var codeLine: String {
        switch self {
        case .http(let status, let endpoint): return "HTTP \(status) · \(endpoint)"
        case .timeout(let endpoint): return "TIMEOUT · \(endpoint)"
        case .protocolVersion(let actual): return String(localized: "PROTOCOL \(actual) · 期望 remote v1 · v4 v3")
        case .emptyWorkspaces: return "workspaces=[]"
        case .handshakeFailed(let detail): return "HANDSHAKE · \(detail)"
        case .transport(let detail): return "WS · \(detail)"
        }
    }
}

// MARK: - server-info HTTP 探测（GET /api/server-info?token=…）

enum ServerInfoClient {

    struct ProbeResult {
        var info: ServerRemoteInfo?
        var status: Int?
        var latencyMs: Int
        var error: ConnectError?
    }

    /// 连接测试（L4-B：1.5s 超时，仅探测不建 WS）；L2 第一步超时 3s 自动重试 1 次。
    static func probe(server: ServerConfig, timeout: TimeInterval) async -> ProbeResult {
        guard let url = URL(string: server.baseURL)?.appendingPathComponent("api/server-info") else {
            return ProbeResult(info: nil, status: nil, latencyMs: 0, error: .transport(String(localized: "无效地址")))
        }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        var items = components.queryItems ?? []
        if !server.token.isEmpty {
            items.append(URLQueryItem(name: "token", value: server.token))
        }
        components.queryItems = items
        guard let requestURL = components.url else {
            return ProbeResult(info: nil, status: nil, latencyMs: 0, error: .transport(String(localized: "无效地址")))
        }

        let started = Date()
        var request = URLRequest(url: requestURL)
        request.timeoutInterval = timeout
        request.httpMethod = "GET"

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            let latency = Int(Date().timeIntervalSince(started) * 1000)
            return ProbeResult(info: nil, status: nil, latencyMs: latency,
                               error: .timeout(endpoint: "GET /api/server-info"))
        }
        let latency = Int(Date().timeIntervalSince(started) * 1000)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            return ProbeResult(info: nil, status: status, latencyMs: latency,
                               error: .http(status: status, endpoint: "GET /api/server-info"))
        }
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: data),
              let info = ServerRemoteInfo.parse(json) else {
            return ProbeResult(info: nil, status: status, latencyMs: latency,
                               error: .transport(String(localized: "server-info 解析失败")))
        }
        guard info.protocolVersion == ServerRemoteInfo.expectedProtocolVersion else {
            return ProbeResult(info: info, status: status, latencyMs: latency,
                               error: .protocolVersion(actual: "remote v\(info.protocolVersion)"))
        }
        return ProbeResult(info: info, status: status, latencyMs: latency, error: nil)
    }
}

// MARK: - 连接五步进度（L2：发现 → 鉴权 → WS → v4 握手 → 工作区）

struct ConnectStepState: Equatable {
    enum Phase: Equatable {
        case pending
        case running
        case done
        case failed
    }
    var phase: Phase = .pending
    var meta: String = ""   // 步骤 meta 列（耗时 / 状态码 / 免鉴权标注）
}

struct ConnectProgress: Equatable {
    var discover = ConnectStepState()
    var auth = ConnectStepState()
    var websocket = ConnectStepState()
    var handshake = ConnectStepState()
    var workspace = ConnectStepState()

    /// 计数含进行中（2 完成 + 1 进行中 = 3/5）
    var completedCount: Int {
        [discover, auth, websocket, handshake, workspace].filter { $0.phase == .done }.count
    }
    var runningCount: Int {
        [discover, auth, websocket, handshake, workspace].filter { $0.phase == .running }.count
    }
    var fraction: Double {
        Double(completedCount + runningCount) / 5.0
    }
    var allSteps: [ConnectStepState] {
        [discover, auth, websocket, handshake, workspace]
    }
}

/// 连接日志行（connect.log / oauth.log 终端条；token 一律 *** 掩码）
struct ConnectLogLine: Equatable, Identifiable {
    enum Kind { case ok, working, info, error }
    var id = UUID()
    var kind: Kind
    var text: String
}

// MARK: - v4 握手模型（transport.ts:40-86）

struct V4HelloMessage {
    static let wireProtocolVersion = 3 // V4_WIRE_PROTOCOL_VERSION

    var protocolVersion: Int
    var connectionId: String
    var clientMode: String
    var deliveryProfile: String
    var serverTime: String?
    var capabilities: [String: Bool]
    var authUserId: String?

    static func parse(_ json: JSONValue) -> V4HelloMessage? {
        guard let dict = json.objectValue else { return nil }
        var capabilities: [String: Bool] = [:]
        if let caps = dict["capabilities"]?.objectValue {
            for (key, value) in caps {
                capabilities[key] = value.boolValue ?? false
            }
        }
        return V4HelloMessage(
            protocolVersion: dict["protocolVersion"]?.intValue ?? 0,
            connectionId: dict["connectionId"]?.stringValue ?? "",
            clientMode: dict["clientMode"]?.stringValue ?? "",
            deliveryProfile: dict["deliveryProfile"]?.stringValue ?? "",
            serverTime: dict["serverTime"]?.stringValue,
            capabilities: capabilities,
            authUserId: dict["auth"]?.objectValue?["userId"]?.stringValue)
    }
}

// MARK: - 服务器连接管理（五步 + channel RPC 门面 + 断线通知）

/// 与桌面 zcode-server 的单条连接：GET server-info → WS /ws?token → v4 握手 → workspaces[0]。
@MainActor
@Observable
final class ZCodeServerConnection {

    enum ConnectionState: Equatable {
        case idle
        case connecting(ConnectProgress)
        case connected(ServerRemoteInfo, workspace: ServerWorkspaceInfo)
        case failed(ConnectError)
        case disconnected(ConnectError) // 曾连接后断线（重连提示）
    }

    private(set) var state: ConnectionState = .idle
    private(set) var logs: [ConnectLogLine] = []

    /// 局域网直连（ChannelClient，13 字节头二进制帧）或云中继（RelayChannelClient，
    /// JSON 文本帧 + rpc-frame）——统一走 RPCChannelTransport 门面，只读拦截同源覆盖
    private var client: (any RPCChannelTransport)?
    /// 中继客户端强类型引用（bootstrap tasks 清单读取；局域网直连时为 nil）
    private var relayClient: RelayChannelClient?
    private var relayTransport: RelayTransport?
    private var serverConfig: ServerConfig?
    private(set) var serverInfo: ServerRemoteInfo?
    private(set) var workspace: ServerWorkspaceInfo?
    private var helloMessage: V4HelloMessage?
    private var frameSubscription: EventSubscription?
    private var frameAssemblers: [String: TopicWireFrameAssembler] = [:] // channel 名键控
    private var frameHandlers: [String: @Sendable (V4TopicFrame) -> Void] = [:]
    /// assembler 丢帧（dropped）回调：key 同 assemblerKey，订阅方据此触发 resync 自愈
    private var frameDropHandlers: [String: @Sendable () -> Void] = [:]
    /// workspace-config 订阅面（connection 持有：subscribe/unsubscribe/resync 均为 promise 面）
    private var workspaceConfigTopicPath: String?
    private var workspaceConfigSubscriptionId: String?
    /// Store 注册 workspace-config handler 前到达的帧（快照/增量整体替换语义，重放安全）
    private var workspaceConfigReplay: [V4TopicFrame] = []
    private var manuallyCancelled = false
    /// 连接握手注册的 clientId（命令信封 envelope.clientId 必须与之一致，
    /// 否则桌面端拒收 fault.command.clientMismatch）。init 后不变 → nonisolated let 跨 actor 读
    nonisolated private let clientId = "zcode-mobile-" + UUID().uuidString.prefix(8)

    nonisolated var registeredClientId: String { clientId }

    /// 只读边界拦截记录（最近 20 条；连接态 execution 命令在 RPC 出口被拒的证据）
    private(set) var blockedExecutionCalls: [String] = []

    /// 断线回落回调（连接成功后 WS/中继通道意外中断时触发）：AppSession 据此把
    /// mode 切为 .disconnected（RootView 黄色横幅「与桌面端的连接已断开 · 重连」）。
    /// 仅「曾进入 .connected 后的真实传输中断」触发；手动 disconnect() 经
    /// manuallyCancelled 守卫先行返回，不会误触发。
    var onConnectionDropped: (@MainActor (String) -> Void)?

    /// 工作区清单推送回调（workspace-list-updated，P3-10）：AppSession 据此刷新
    /// 任务聚合清单（conversationStore.setAllWorkspaces）；清单本体已先行回写
    /// serverInfo.workspaces（切换器菜单经 @Observable 联动）
    var onWorkspaceListUpdated: (@MainActor ([ServerWorkspaceInfo]) -> Void)?

    /// 工作区切换能力（P3-10）：切换 = workspace-bridge-open 重开 + mobile-view-state-update
    /// （C-10 对齐 web；workspace-reconnect-request 已收敛为断连重连专用面，不再前置到
    /// 切换流程），relay WS 原生 zcode_type 消息仅云中继连接具备；局域网直连诚实只读
    /// （UI 不出可点菜单）
    var supportsWorkspaceSwitching: Bool {
        relayClient != nil && isActive
    }

    let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"

    var isActive: Bool {
        if case .connected = state { return true }
        return false
    }

    // MARK: 五步连接（超时阈值 3s/5s，自动重试 1 次；L2 口径）

    func connect(to server: ServerConfig, preferredWorkspace: String? = nil) async -> Result<ServerRemoteInfo, ConnectError> {
        disconnect()
        manuallyCancelled = false
        serverConfig = server
        logs.removeAll()
        var progress = ConnectProgress()

        // 步骤 1：发现服务（GET /api/server-info，3s 超时，重试 1 次）
        progress.discover.phase = .running
        state = .connecting(progress)
        log(.working, "GET /api/server-info")
        var probe = await ServerInfoClient.probe(server: server, timeout: 3)
        if probe.error != nil {
            log(.info, "超时，自动重试 1 次（指数退避）")
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            probe = await ServerInfoClient.probe(server: server, timeout: 3)
        }
        if let error = probe.error {
            progress.discover.phase = .failed
            state = .connecting(progress)
            await finishFailure(error)
            return .failure(error)
        }
        progress.discover.phase = .done
        progress.discover.meta = "\(probe.status ?? 200) · \(probe.latencyMs)ms"
        log(.ok, "GET /api/server-info → \(probe.status ?? 200) · \(probe.latencyMs)ms")
        guard let info = probe.info else {
            await finishFailure(.transport(String(localized: "server-info 缺失")))
            return .failure(.transport(String(localized: "server-info 缺失")))
        }
        serverInfo = info
        if let name = info.name {
            log(.ok, "serverId=\(info.serverId) · \(name) · v\(info.version) · protocolVersion=1")
        } else {
            log(.ok, "serverId=\(info.serverId) · v\(info.version) · protocolVersion=1")
        }

        // 步骤 2：校验访问令牌（authRequired=false 时为「免鉴权」口径，不出现「校验通过」）
        progress.auth.phase = .running
        state = .connecting(progress)
        if info.authRequired {
            if server.token.isEmpty {
                progress.auth.phase = .failed
                state = .connecting(progress)
                let error = ConnectError.http(status: 401, endpoint: "/api/server-info")
                await finishFailure(error)
                return .failure(error)
            }
            progress.auth.phase = .done
            progress.auth.meta = "authRequired=true"
            log(.ok, "token 校验通过 · authRequired=true")
        } else {
            progress.auth.phase = .done
            progress.auth.meta = "免鉴权"
            log(.ok, "authRequired=false · --no-token（免鉴权）")
        }

        // 步骤 3：建立 WebSocket（ws://host:port/ws?token=…，5s 超时）
        progress.websocket.phase = .running
        state = .connecting(progress)
        let client = ChannelClient()
        self.client = client
        do {
            var components = URLComponents(string: "\(server.wsBaseURL)/ws")!
            if !server.token.isEmpty {
                components.queryItems = [URLQueryItem(name: "token", value: server.token)]
                log(.working, "WS 升级 \(server.wsBaseURL)/ws?token=***")
            } else {
                log(.working, "WS 升级 \(server.wsBaseURL)/ws（无 token 段）")
            }
            try await client.connect(url: components.url!, timeout: 5) { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if self.manuallyCancelled { return }
                    let connectError = ConnectError.transport(error.map { $0.localizedDescription } ?? String(localized: "连接中断"))
                    self.notifyDroppedIfConnected(connectError.headline)
                    self.state = .disconnected(connectError)
                    self.teardownTransport()
                }
            }
            progress.websocket.phase = .done
            progress.websocket.meta = "web-remote-replayable"
            log(.ok, "WS 已建立 · clientMode=web-remote-replayable")
        } catch let error as RPCError where error.name == "TimeoutError" {
            progress.websocket.phase = .failed
            state = .connecting(progress)
            let connectError = ConnectError.timeout(endpoint: "GET /ws")
            await finishFailure(connectError)
            return .failure(connectError)
        } catch {
            progress.websocket.phase = .failed
            state = .connecting(progress)
            let connectError = ConnectError.transport(error.localizedDescription)
            await finishFailure(connectError)
            return .failure(connectError)
        }

        // 步骤 4：v4 握手（hello → clientHello，clientKind=mobileApp；capabilities 单向规则：
        // 只回显 Host 宣告过的键）
        progress.handshake.phase = .running
        state = .connecting(progress)
        do {
            let helloValue = try await client.call("zcode-agent", "helloConversationV4", .undefined, timeout: 5)
            guard let hello = V4HelloMessage.parse(helloValue.jsonValue ?? .null) else {
                throw ConnectError.handshakeFailed(String(localized: "hello 解析失败"))
            }
            guard hello.protocolVersion == V4HelloMessage.wireProtocolVersion else {
                throw ConnectError.protocolVersion(actual: "v4 wire v\(hello.protocolVersion)")
            }
            guard hello.clientMode == "web-remote-replayable" else {
                throw ConnectError.handshakeFailed("clientMode=\(hello.clientMode)")
            }
            helloMessage = hello
            log(.ok, "helloConversationV4 · protocolVersion=3 · deliveryProfile=\(hello.deliveryProfile)")

            // clientHello capabilities 单向宣告规则（transport.ts:72-83 .strict()）：
            // 只回显 Host hello 已宣告的键。workflowRunDeltas 为 true 时回显——移动端
            // 已实现 workflowRun.updated/removed 专属增量消费（RCS applyDelta），
            // 不声明则服务端把增量折叠回整键 state.updated 且宽 run 裁到 256
            // （2026-10-08 回归审核轮 §11.2：此前恒不声明=增量消费死代码）
            let clientHello = RPCValue.jsonObject { builder in
                builder.set("kind", "clientHello")
                builder.set("protocolVersion", V4HelloMessage.wireProtocolVersion)
                builder.set("clientId", clientId)
                builder.set("clientKind", "mobileApp") // transport.ts:73 预留值
                builder.set("appVersion", appVersion)
                if hello.capabilities["workflowRunDeltas"] == true {
                    builder.set("capabilities", .object(["workflowRunDeltas": .bool(true)]))
                }
            }
            _ = try await client.call("zcode-agent", "initializeConversationV4", clientHello, timeout: 5)
            progress.handshake.phase = .done
            progress.handshake.meta = "clientKind=mobileApp"
            log(.ok, "initializeConversationV4 · 握手完成")
        } catch let error as ConnectError {
            progress.handshake.phase = .failed
            state = .connecting(progress)
            await finishFailure(error)
            return .failure(error)
        } catch {
            progress.handshake.phase = .failed
            state = .connecting(progress)
            let connectError = ConnectError.handshakeFailed(error.localizedDescription)
            await finishFailure(connectError)
            return .failure(connectError)
        }

        // 步骤 5：载入工作区（默认 workspaces[0]，或用户偏好）
        progress.workspace.phase = .running
        state = .connecting(progress)
        let preferred = preferredWorkspace.flatMap { path in
            info.workspaces.first { $0.path == path }
        }
        guard let workspace = preferred ?? info.workspaces.first else {
            progress.workspace.phase = .failed
            state = .connecting(progress)
            let error = ConnectError.emptyWorkspaces
            await finishFailure(error)
            return .failure(error)
        }
        progress.workspace.phase = .done
        progress.workspace.meta = workspace.label ?? workspace.path
        log(.ok, "workspaces[0] · \(workspace.path)")
        self.workspace = workspace
        // 订阅 workspace 级下行帧流（zcode-agent.onDynamicConversationFrame /
        // onDynamicSessionsIndexFrame）。必须在 self.workspace 选定**之后**执行
        // （2026-10-08 回归审核轮 §11.1#4：曾置于步骤 3 后，三路 eventListen 以
        // workspacePath="" 注册——上游 emitter 按 resolveWorkspaceKey 硬路由，
        // 空串键恒收不到帧，LAN 下 sessions-index/chips 实时帧全静默丢失；
        // 中继路径本就先设 workspace 不受影响；E2E 替身不按键路由故门禁掩盖）
        frameSubscription = await subscribeFrameStreams(client: client)
        state = .connected(info, workspace: workspace)
        return .success(info)
    }

    // MARK: 云中继连接（remote/v4：跳过局域网探测，WS + auth 握手 → bootstrap → workspace 桥）

    /// 中继五步映射（L2 面板口径）：发现=跳过标注；鉴权=auth 握手 matched；
    /// WS=transport paired；握手=bootstrap + bridge-open + 桥内 Initialize；工作区=bridge.workspacePath。
    /// 成功后复用既有 Remote Store 面（call/listen 经 RPCChannelTransport 门面，
    /// ReadOnlyGate 出口拦截对中继路径同等生效）。
    /// 中继 bootstrap-response 的跨工作区 tasks 清单（web「所有项目目录」同源；
    /// 非中继连接为 nil）
    var relayBootstrapTasks: JSONValue? {
        get async { await relayClient?.taskListJSON }
    }

    @discardableResult
    func connectRelay(to server: ServerConfig) async -> Result<ServerRemoteInfo, ConnectError> {
        disconnect()
        manuallyCancelled = false
        serverConfig = server
        logs.removeAll()
        var progress = ConnectProgress()
        guard let link = server.relay else {
            let error = ConnectError.transport(String(localized: "中继配置缺失"))
            await finishFailure(error)
            return .failure(error)
        }

        // 步骤 1：发现——中继模式无 /api/server-info HTTP 面，直连 WS
        progress.discover.phase = .done
        progress.discover.meta = "云端中继"
        log(.ok, "云端中继 · 跳过局域网探测 → \(link.endpointHost ?? "?")/ws")

        // 步骤 2-4：auth 握手 → bootstrap → workspace-bridge-open → 桥内 Initialize
        progress.auth.phase = .running
        state = .connecting(progress)
        let transport = RelayTransport(config: link) { [weak self] kind, text in
            Task { @MainActor [weak self] in self?.log(kind, text) }
        }
        // 工作区清单推送（P3-10）：transport 拆出的独立 case 在此接线（先于任何
        // 桥/订阅建立——推送可能在握手后任意时点到达）
        await transport.setWorkspaceListUpdatedHandler { [weak self] payload in
            Task { @MainActor [weak self] in
                self?.handleWorkspaceListUpdated(payload)
            }
        }
        let client = RelayChannelClient(transport: transport, link: link, appVersion: appVersion)
        // 桥(重)开即重握手（RelayChannelClient.setOnBridgeOpened 注）：首连/断线恢复/
        // degraded 重建/切换全部路径经 openBridge 汇入，握手在此单点接管——connectRelay
        // 里不再单独调 performRelayV4Handshake（openBridge 内已先行）
        await client.setOnBridgeOpened { [weak self] bridgeClient in
            await self?.performRelayV4Handshake(client: bridgeClient)
        }
        self.relayTransport = transport
        self.client = client
        self.relayClient = client
        do {
            let summary = try await client.connectRelay(timeout: 15) { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self, !self.manuallyCancelled else { return }
                    let connectError = ConnectError.transport(
                        error.map { $0.localizedDescription } ?? String(localized: "中继连接中断"))
                    self.notifyDroppedIfConnected(connectError.headline)
                    self.state = .disconnected(connectError)
                    self.teardownTransport()
                }
            }
            progress.auth.phase = .done
            progress.auth.meta = "auth matched"
            progress.websocket.phase = .done
            progress.websocket.meta = summary.desktopAppVersion.map { String(localized: "桌面 v\($0)") } ?? "paired"
            log(.ok, "WS + auth 握手完成 · pair_status=matched · \(summary.sessionCount) 个会话")

            progress.handshake.phase = .done
            progress.handshake.meta = "bridge kind=\(summary.bridgeKind ?? "local")"
            log(.ok, "bootstrap + workspace-bridge-open 完成 · 桥内 Initialize 已到")

            // 步骤 5：桥 workspacePath → ServerWorkspaceInfo（Store 装配与局域网同构）。
            // 多 workspace（2026-10-06 取证 workspace-list-response）：全部工作区入 info，
            // active（桥）保持首位——任务列表按全清单聚合，单工作区行为不变；
            // C-15 canBridge 门控（web 同款 `kind!=='remote' || (identity && remoteSessionId)`）：
            // 不可桥条目从切换菜单过滤（web「不可桥不开桥、退 home-only」的菜单面等价）
            progress.workspace.phase = .running
            state = .connecting(progress)
            let activeEntry = summary.workspaces.first { $0.workspaceKey == summary.workspaceKey }
                ?? summary.workspaces.first
            var relayWorkspaces: [ServerWorkspaceInfo] = summary.workspaces
                .filter { $0.canBridge }
                .map(Self.workspaceInfo(from:))
            if let activeEntry, !relayWorkspaces.contains(where: {
                $0.path == (activeEntry.path ?? activeEntry.workspaceKey)
            }) {
                relayWorkspaces.insert(
                    ServerWorkspaceInfo(
                        path: activeEntry.path ?? activeEntry.workspaceKey,
                        label: server.displayName,
                        workspaceIdentity: activeEntry.workspaceIdentity),
                    at: 0)
            }
            if relayWorkspaces.isEmpty {
                relayWorkspaces = [ServerWorkspaceInfo(
                    path: summary.workspacePath, label: server.displayName,
                    workspaceIdentity: nil)]
            }
            let workspace = relayWorkspaces[0]
            progress.workspace.phase = .done
            progress.workspace.meta = workspace.label ?? workspace.path
            log(.ok, "workspace-bridge · \(workspace.path) · 清单 \(relayWorkspaces.count) 个工作区")
            UserDefaults.standard.set(
                "count=\(relayWorkspaces.count) paths=[\(relayWorkspaces.map(\.path).joined(separator: " | ").prefix(400))]",
                forKey: "diag.ws.list")
            UserDefaults.standard.synchronize()
            let info = ServerRemoteInfo(
                serverId: "relay-\(link.endpointHost ?? "zcode")",
                name: server.displayName,
                version: summary.desktopAppVersion ?? "relay",
                protocolVersion: ServerRemoteInfo.expectedProtocolVersion,
                authRequired: false,
                workspaces: relayWorkspaces,
                capabilities: [])
            serverInfo = info
            self.workspace = workspace

            // 桥内 v4 握手已由 onBridgeOpened 钩子在 openBridge 内完成（首连同路径）
            frameSubscription = await subscribeFrameStreams(client: client)
            state = .connected(info, workspace: workspace)
            return .success(info)
        } catch let error as RPCError {
            markRunningStepFailed(&progress)
            state = .connecting(progress)
            let connectError = Self.mapRelayError(error)
            await finishFailure(connectError)
            return .failure(connectError)
        } catch {
            markRunningStepFailed(&progress)
            state = .connecting(progress)
            let connectError = ConnectError.transport(error.localizedDescription)
            await finishFailure(connectError)
            return .failure(connectError)
        }
    }

    /// 桥内 v4 握手（helloConversationV4 → initializeConversationV4，clientKind=mobileApp）。
    /// 每次桥(重)开经 onBridgeOpened 调入（RelayChannelClient.openBridge 注）。旧版本桌面
    /// 缺命令时降级放行（无握手闸，订阅不受影响）；新桌面瞬态失败（桥刚重建窗口内
    /// timeout/瞬断）退避 1.2s 重试一次——此前 catch 直降级曾让连接停留在「未握手」态：
    /// 全部 assertReady 类调用（subscribe*/readSession/rowsRange）持续
    /// fault.connection.handshakeRequired 直到整轮重连才自愈（真机 2026-10-07
    /// 18:33-18:34 风暴实证；AGENTS §5.8 中继瞬断退避同口径）。
    private func performRelayV4Handshake(client: any RPCChannelTransport) async {
        func attempt() async throws -> V4HelloMessage {
            let helloValue = try await client.call("zcode-agent", "helloConversationV4", .undefined, timeout: 5)
            guard let hello = V4HelloMessage.parse(helloValue.jsonValue ?? .null) else {
                throw ConnectError.handshakeFailed(String(localized: "hello 解析失败"))
            }
            guard hello.protocolVersion == V4HelloMessage.wireProtocolVersion else {
                throw ConnectError.handshakeFailed(String(localized: "中继桥 v4 wire v\(hello.protocolVersion)"))
            }
            return hello
        }
        do {
            var hello: V4HelloMessage?
            do {
                hello = try await attempt()
            } catch {
                // 瞬态失败退避重试一次（旧桌面「方法不存在」类错误重试同样无害——
                // 二次失败走下方整体降级）
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                hello = try await attempt()
            }
            guard let hello else { return }
            let clientHello = RPCValue.jsonObject { builder in
                builder.set("kind", "clientHello")
                builder.set("protocolVersion", V4HelloMessage.wireProtocolVersion)
                builder.set("clientId", clientId)
                builder.set("clientKind", "mobileApp")
                builder.set("appVersion", appVersion)
                // 单向宣告规则同 LAN 路：Host 宣告 workflowRunDeltas 才回显
                //（transport.ts:72-83 .strict()，capabilities 键集封闭）
                if hello.capabilities["workflowRunDeltas"] == true {
                    builder.set("capabilities", .object(["workflowRunDeltas": .bool(true)]))
                }
            }
            do {
                _ = try await client.call("zcode-agent", "initializeConversationV4", clientHello, timeout: 5)
            } catch {
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                _ = try await client.call("zcode-agent", "initializeConversationV4", clientHello, timeout: 5)
            }
            log(.ok, "桥内 v4 握手 · protocolVersion=3 · deliveryProfile=\(hello.deliveryProfile)")
        } catch {
            log(.info, "桥内 v4 握手不可用（降级继续）：\(error.localizedDescription)")
        }
    }

    private func markRunningStepFailed(_ progress: inout ConnectProgress) {
        if progress.discover.phase == .running { progress.discover.phase = .failed }
        if progress.auth.phase == .running { progress.auth.phase = .failed }
        if progress.websocket.phase == .running { progress.websocket.phase = .failed }
        if progress.handshake.phase == .running { progress.handshake.phase = .failed }
        if progress.workspace.phase == .running { progress.workspace.phase = .failed }
    }

    // MARK: 工作区切换（P3-10；C-10/C-11/C-12 对齐 web：bridge-open 重开 + mobile-view-state-update）

    /// 中继工作区切换（web 同构，C-10）：**不前置 workspace-reconnect-request**——web 的
    /// reconnect 仅用于「重连已断开的远程工作区」专用面（见 reconnectRelayWorkspace），
    /// 与切换无关。切换 = ①按清单解析原始 workspaceKey（C-12 严格口径，未收录即失败，
    /// 不再以 path 冒充 key）②桥重开到目标 key（三路 dynamic 事件与 workspace-config
    /// 订阅重定向，失败回滚）③补发 mobile-view-state-update（C-11 切换场景；bridge-open
    /// 成功场景已在 RelayChannelClient.openBridge 内发送）④workspace/清单 active 序更新。
    /// 局域网直连无此消息面。
    func switchRelayWorkspace(to target: ServerWorkspaceInfo) async -> Result<ServerWorkspaceInfo, ConnectError> {
        guard isActive, let relayClient, workspace != nil else {
            return .failure(.transport(String(localized: "工作区切换仅支持云中继连接")))
        }
        guard let current = workspace, current.path != target.path else {
            return .failure(.transport(String(localized: "目标已是当前工作区")))
        }
        // C-12：workspaceKey 一律取清单原始 key；未收录返回失败（非法 key 切换必败）
        guard let key = await relayClient.resolvedWorkspaceKey(
            forPath: target.path, identity: target.workspaceIdentity) else {
            log(.error, "目标工作区不在桌面清单（无原始 workspaceKey）· \(target.path)")
            return .failure(.transport(String(localized: "目标工作区不在桌面清单")))
        }
        log(.working, "workspace-bridge-open（切换）→ \(target.path)")
        // 桥重开 + 订阅重定向；先清 workspace-config 重放缓存（旧工作区帧不重放给新
        // Store——重放面在 setFrameHandler 按 topic 精确匹配双保险）
        workspaceConfigReplay.removeAll()
        do {
            try await relayClient.switchBridgeWorkspace(workspaceKey: key, workspacePath: target.path)
        } catch {
            // 错误文本含 reason（切换在途的 workspace-bridge-error 帧经 C-14 收口后以
            // reason:error 组合上抛，不再被误判成功）。RPCError 非 LocalizedError
            //（仅 CustomStringConvertible），localizedDescription 会丢真实 message——
            // 取 message 原文上抛，失败提示链（AppSession → 切换器 hint）才有可行动原因
            let detail = (error as? RPCError)?.message ?? error.localizedDescription
            log(.error, "工作区桥切换失败 · \(detail)")
            return .failure(.transport(detail))
        }
        await resubscribeWorkspaceConfig(path: target.path)
        updateWorkspace(to: target)
        // C-11 场景③（切换工作区）：显式上报无任务的视图状态并清空本地 activeTaskId
        // （web 任务首页切换路径 `switchWorkspace` 后 `updateMobileViewState(key)` 同款
        // ——M(e,n) 的 n=undefined 会同时清掉模块态 c，重连落点随之不带任务）
        await relayClient.updateActiveTask(nil)
        log(.ok, "工作区已切换 · \(target.path) · 清单 \(serverInfo?.workspaces.count ?? 0) 个")
        return .success(target)
    }

    /// 重连已断开的远程工作区（C-10 保留项；web 侧栏「重连已断开的远程工作区」按钮
    /// 专用面，与切换流程解耦）：3 键形态 `{zcode_type, requestId, workspaceKey}`
    /// （bundle 实证——不带 workspacePath/workspaceIdentity），响应按 requestId 配对；
    /// `reason`/`error` 字符串或 ok=false 判失败（C-14 reason 识别）。移动端当前无该
    /// UI 入口，保留协议面供断连工作区恢复场景接线。
    func reconnectRelayWorkspace(workspaceKey: String) async -> Result<JSONValue, ConnectError> {
        guard isActive, let relayTransport else {
            return .failure(.transport(String(localized: "工作区重连仅支持云中继连接")))
        }
        do {
            let response = try await relayTransport.requestAppPayload(
                [
                    "zcode_type": .string("workspace-reconnect-request"),
                    "workspaceKey": .string(workspaceKey),
                ],
                zcodeType: "workspace-reconnect-request", timeout: 8)
            // 响应面拒绝形态（在途错误帧走 app-error/workspace-bridge-error，已被
            // RelayTransport 按 requestId reject 到 catch 分支）
            if let reason = response["reason"]?.stringValue, !reason.isEmpty {
                log(.error, "workspace-reconnect 被拒 · \(reason)")
                return .failure(.handshakeFailed("workspace-reconnect: \(reason)"))
            }
            if let errorText = response["error"]?.stringValue, !errorText.isEmpty {
                log(.error, "workspace-reconnect 被拒 · \(errorText)")
                return .failure(.handshakeFailed("workspace-reconnect: \(errorText)"))
            }
            if response["ok"]?.boolValue == false {
                log(.error, "workspace-reconnect 被拒（ok=false）")
                return .failure(.handshakeFailed("workspace-reconnect rejected"))
            }
            log(.ok, "workspace-reconnect 完成 · \(workspaceKey)")
            return .success(response)
        } catch let error as RPCError {
            log(.error, "workspace-reconnect 失败 · \(error.localizedDescription)")
            return .failure(Self.mapRelayError(error))
        } catch {
            log(.error, "workspace-reconnect 失败 · \(error.localizedDescription)")
            return .failure(.transport(error.localizedDescription))
        }
    }

    /// C-11 场景②（打开任务）：向桌面上报当前查看的任务（web `updateMobileViewState`
    /// 由活动任务变化 effect 触发）。仅云中继有此帧面（局域网直连静默跳过）；单向通知
    /// 无响应等待，未 paired 仅记连接日志。调用面（会话/任务打开处）由视图与 Store 波次接线。
    func reportActiveTask(_ taskId: String?) async {
        guard let relayClient else { return }
        await relayClient.updateActiveTask(taskId)
    }

    /// 切换成功后的连接态工作区更新：workspace 字段直读本连接（FileTreeView.headerPath /
    /// DiffReviewView.loadGitSummary 等处消费），不同步则切换后头部仍显旧工作区；
    /// serverInfo.workspaces 同步重排（active 首位，与连接期同构）。
    private func updateWorkspace(to target: ServerWorkspaceInfo) {
        workspace = target
        if var list = serverInfo?.workspaces {
            list.removeAll { $0.path == target.path }
            list.insert(target, at: 0)
            serverInfo?.workspaces = list
        }
    }

    /// 工作区切换后的 workspace-config 重订（新 topic）：旧订阅退订（best-effort，
    /// 旧桥已弃无强一致要求）+ 新 topic 重订；新快照在 Store 注册 handler 前到达的
    /// 部分走既有 workspaceConfigReplay 缓存重放路径（先于订阅注册纪律的缓存面）
    private func resubscribeWorkspaceConfig(path: String) async {
        let previousTopic = workspaceConfigTopicPath
        let previousSubscriptionId = workspaceConfigSubscriptionId
        workspaceConfigTopicPath = nil
        workspaceConfigSubscriptionId = nil
        guard let client else { return }
        if let previousTopic, let previousSubscriptionId {
            var unsubscribe = JSONObjectBuilder()
            unsubscribe.set("topic", previousTopic)
            unsubscribe.set("workspacePath", workspacePath(fromConfigTopic: previousTopic))
            unsubscribe.set("subscriptionId", previousSubscriptionId)
            _ = try? await client.call(
                "zcode-agent", "unsubscribeWorkspaceConfigV4",
                .json(.object(unsubscribe.fields)), timeout: 3)
        }
        let configTopic = "workspace-config/\(path)"
        var builder = JSONObjectBuilder()
        builder.set("topic", configTopic)
        builder.set("workspacePath", path)
        builder.set("runtimePolicy", "existing-only")
        if let value = try? await client.call(
            "zcode-agent", "subscribeWorkspaceConfigV4",
            .json(.object(builder.fields)), timeout: 5) {
            workspaceConfigSubscriptionId = value.jsonValue?["subscriptionId"]?.stringValue
            workspaceConfigTopicPath = configTopic
        }
        log(.ok, workspaceConfigSubscriptionId != nil
            ? "subscribeWorkspaceConfigV4 · 新工作区订阅完成"
            : "subscribeWorkspaceConfigV4 · 新工作区订阅未获回执（chips 走缺省展示）")
    }

    /// workspace-list-updated 推送（桌面端工作区清单变化）：宽容解析清单与
    /// activeWorkspaceKey（result.workspaces | workspaces | 顶层数组多形态），active
    /// 首位回写 serverInfo（切换器菜单经 @Observable 联动刷新），再上抛 AppSession
    /// 刷新任务聚合清单。activeWorkspaceKey 与本地当前工作区不一致时仅刷新清单不跟随
    /// 切换（桌面侧动作不突袭打断移动端进行中的会话）。
    /// C-15：菜单（serverInfo.workspaces）按 canBridge 过滤（active 首位恒保留——桥已
    /// 在其上打开即为可桥事实）；完整清单仍上抛 AppSession（任务聚合不受门控影响，
    /// bootstrap.tasks 才是跨工作区任务主源）。
    private func handleWorkspaceListUpdated(_ payload: JSONValue) {
        let result = payload["result"] ?? payload
        var listValue = result["workspaces"] ?? payload["workspaces"]
        if listValue == nil, result.arrayValue != nil { listValue = result }
        if listValue == nil, payload.arrayValue != nil { listValue = payload }
        let summaries = RelayChannelClient.parseWorkspaceSummaries(listValue)
        guard !summaries.isEmpty else {
            log(.info, "workspace-list-updated · 清单为空或形状未识别（忽略）")
            return
        }
        let activeKey = result["activeWorkspaceKey"]?.stringValue
            ?? payload["activeWorkspaceKey"]?.stringValue
        var ordered = summaries
        if let activeKey,
           let index = ordered.firstIndex(where: {
               ($0.path ?? $0.workspaceKey) == activeKey || $0.workspaceIdentity == activeKey
           }),
           index > 0 {
            ordered.insert(ordered.remove(at: index), at: 0)
        }
        let infos = ordered.map(Self.workspaceInfo(from:))
        // C-15：菜单只留可桥条目 + active 首位
        serverInfo?.workspaces = ordered.enumerated()
            .filter { $0.offset == 0 || $0.element.canBridge }
            .map { Self.workspaceInfo(from: $0.element) }
        log(.ok, "workspace-list-updated · 清单 \(infos.count) 个工作区"
            + "（菜单 \(serverInfo?.workspaces.count ?? 0) 个可桥）"
            + (activeKey.map { " · activeKey=\($0)" } ?? ""))
        onWorkspaceListUpdated?(infos)
    }

    /// 清单条目 → 连接态工作区描述（菜单/Store 装配共用映射；path 缺席以 workspaceKey 兜底）
    private static func workspaceInfo(from entry: RelayChannelClient.RelayWorkspaceSummary) -> ServerWorkspaceInfo {
        ServerWorkspaceInfo(
            path: entry.path ?? entry.workspaceKey,
            label: entry.name,
            workspaceIdentity: entry.workspaceIdentity)
    }

    // A-4（2026-10-06 删除）：自造传输帧 `zcode_type="attachmentPut"` 已移除——web 出站
    // zcode_type 全集 9 种无此帧（attachmentPut 是 web 客户端高层函数名，非传输帧）；
    // 附件上传事务改为四条 channel RPC（attachmentBeginV4/ChunkV4/CommitV4/AbortV4，
    // 见 RemoteConversationStore 附件事务节）。否定性结论已录协议文档 §12。

    /// 中继错误 → L3 连接错误映射
    private static func mapRelayError(_ error: RPCError) -> ConnectError {
        switch error.name {
        case "TimeoutError":
            return .timeout(endpoint: String(localized: "中继 auth/bridge"))
        case "RelayFailure", "RelayClosed":
            return .handshakeFailed(error.message)
        default:
            return .transport(error.message)
        }
    }

    /// 取消连接（L2 右上 ✕ / 底部取消：立即中断不留半开连接）
    func cancelConnecting() {
        manuallyCancelled = true
        disconnect()
        state = .idle
    }

    func disconnect() {
        manuallyCancelled = true
        teardownTransport()
        client = nil
        relayTransport = nil
    }

    private func teardownTransport() {
        frameSubscription?.cancel()
        frameSubscription = nil
        frameAssemblers.removeAll()
        frameHandlers.removeAll()
        frameDropHandlers.removeAll()
        workspaceConfigReplay.removeAll()
        // 多路 conversation 帧面记账随之作废（残留会让重连后 ensureConversationFrameStream
        // 误判已挂、新连接上帧面缺失——真机重连场景的静默丢帧面）
        for (_, subscription) in conversationFrameStreams {
            subscription.cancel()
        }
        conversationFrameStreams.removeAll()
        let configTopic = workspaceConfigTopicPath
        let configSubscriptionId = workspaceConfigSubscriptionId
        workspaceConfigTopicPath = nil
        workspaceConfigSubscriptionId = nil
        Task { [client] in
            // 退订 workspace-config（promise 面）需在连接仍存活时发出，随后再断开传输
            if let client, let configTopic {
                var builder = JSONObjectBuilder()
                builder.set("topic", configTopic)
                builder.set("workspacePath", workspacePath(fromConfigTopic: configTopic))
                if let configSubscriptionId {
                    builder.set("subscriptionId", configSubscriptionId)
                }
                _ = try? await client.call(
                    "zcode-agent", "unsubscribeWorkspaceConfigV4",
                    .json(.object(builder.fields)), timeout: 3)
            }
            await client?.disconnect()
        }
    }

    /// "workspace-config/<workspacePath>" → workspacePath
    private func workspacePath(fromConfigTopic topic: String) -> String {
        guard topic.hasPrefix("workspace-config/") else { return topic }
        return String(topic.dropFirst("workspace-config/".count))
    }

    private func finishFailure(_ error: ConnectError) async {
        teardownTransport()
        client = nil
        state = .failed(error)
        log(.error, error.codeLine)
    }

    /// 连接日志写入（internal：Remote Store 的订阅诊断同源进 L2 面板）
    func log(_ kind: ConnectLogLine.Kind, _ text: String) {
        logs.append(ConnectLogLine(kind: kind, text: text))
        if logs.count > 40 { logs.removeFirst(logs.count - 40) }
        #if DEBUG
        // 连接日志同步进 unified log（文本已脱敏），供 simctl log stream 真机诊断
        NSLog("[relay-log][\(kind)] \(text)")
        #endif
    }

    // MARK: 帧流订阅（conversation / sessions-index / workspace-config 三路 dynamic 事件）

    /// conversation 帧流按 workspace 多路记账（P0-1 修复，2026-10-08 真机实据）：
    /// 上游桌面按 workspaceKey 分 emitter 推帧且 ownsFrame 双重硬匹配
    /// 【实证·上游仓 zcodeAgentService.ts:1568/2098 + zcodeAgentConnectionScope.ts:389-404】
    /// ——eventListen 挂 A 区而会话订阅在 B 区时帧必丢（真机 sess_e8677b05：订阅按归属
    /// mtt_mobile 寻址、eventListen 恒绑连接区 poker_protocol → 快照/增量全丢、revision
    /// 永不就绪；重连后两区对齐帧即恢复）。web 参考客户端按会话归属 workspace 逐路注册
    /// （workspaceConnectionRegistry），移动端对齐：每 workspacePath 一路 eventListen。
    private var conversationFrameStreams: [String: EventSubscription] = [:]

    /// 确保指定 workspace 的 conversation 帧流已注册（幂等）。会话订阅前调用——
    /// 订阅按归属 workspace 寻址（v1.17），帧面必须同 workspace（本方法）。
    /// identity 型工作区必须携 identity（emitter 键 = identity || path，§11.2）。
    func ensureConversationFrameStream(workspacePath: String, workspaceIdentity: String? = nil) async {
        guard let client, isActive, !workspacePath.isEmpty else { return }
        let streamKey = workspaceIdentity?.isEmpty == false ? workspaceIdentity! : workspacePath
        guard conversationFrameStreams[streamKey] == nil else { return }
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspacePath)
            if let workspaceIdentity, !workspaceIdentity.isEmpty {
                builder.set("workspaceIdentity", workspaceIdentity)
            }
        }
        // assemblerKey 按 workspace 分键：两路帧流各自重组（TopicWireFrameAssembler
        // 分片账本不跨流混用）；handler 仍按 frame.topic 精确匹配（routeFrame）
        let subscription = await client.listen(
            "zcode-agent", "onDynamicConversationFrame", arg
        ) { [weak self] payload in
            self?.routeFrame(payload, assemblerKey: "conversation/\(streamKey)", handlerKey: nil)
        }
        conversationFrameStreams[streamKey] = subscription
        log(.ok, "conversation 帧流已挂 · \(streamKey)")
    }

    /// workspace 级下行帧流：三个 dynamic 事件共用通知面，按 topic 前缀分流；
    /// 另注册 zcode-task.onError（固定事件）进连接日志供诊断。
    /// 局域网与中继共用（中继路径 eventListen 载荷经 rpc-frame 透传，eventFire 结构不变）。
    /// conversation 首路（连接 workspace）在此注册；其余 workspace 按
    /// ensureConversationFrameStream 逐路补挂（P0-1）。
    private func subscribeFrameStreams(client: any RPCChannelTransport) async -> EventSubscription {
        // dynamic 事件带参数：{workspacePath, workspaceIdentity?}（zcodeAgent.ts
        // WorkspaceTarget）。emitter 按 resolveWorkspaceKey = identity || path 键控
        // （zcodeAgentService.ts:1568-1596）——identity 型工作区必须携 identity，
        // 否则三路帧流挂 path 键收不到（2026-10-08 回归审核轮 §11.2）
        let arg = RPCValue.jsonObject { builder in
            builder.set("workspacePath", workspace?.path ?? "")
            if let identity = workspace?.workspaceIdentity, !identity.isEmpty {
                builder.set("workspaceIdentity", identity)
            }
        }
        let conversationSub = await client.listen("zcode-agent", "onDynamicConversationFrame", arg) { [weak self] payload in
            self?.routeFrame(payload, assemblerKey: "conversation", handlerKey: nil)
        }
        conversationFrameStreams[workspace?.path ?? ""] = conversationSub
        let sessionsSub = await client.listen("zcode-agent", "onDynamicSessionsIndexFrame", arg) { [weak self] payload in
            self?.routeFrame(payload, assemblerKey: "sessions", handlerKey: "sessions-index")
        }
        let configSub = await client.listen("zcode-agent", "onDynamicWorkspaceConfigFrame", arg) { [weak self] payload in
            self?.routeFrame(payload, assemblerKey: "workspace-config", handlerKey: "workspace-config")
        }
        // zcode-task.onError：固定事件（无参），错误文本进连接日志面板
        let taskErrorSub = await client.listen("zcode-task", "onError", .undefined) { [weak self] payload in
            let message = payload.jsonValue?["message"]?.stringValue
                ?? payload.stringValue
                ?? "未知错误"
            Task { @MainActor [weak self] in
                self?.log(.error, "zcode-task.onError · \(message)")
            }
        }
        // workspace-config 订阅（promise 面）：runtimePolicy=existing-only，
        // 被动观察者禁止为订阅拉起 Agent（zcodeAgent.ts:527-529 注释口径）。
        // 服务端为 additive 演进面，旧版本不支持时订阅静默失败（chips 回退演示值）。
        // topic 键 = identity || path（同 sessions-index 口径，§11.2）
        let configKey: String = {
            if let identity = workspace?.workspaceIdentity, !identity.isEmpty { return identity }
            return workspace?.path ?? ""
        }()
        let configTopic = "workspace-config/\(configKey)"
        workspaceConfigTopicPath = configTopic
        var builder = JSONObjectBuilder()
        builder.set("topic", configTopic)
        builder.set("workspacePath", workspace?.path ?? "")
        if let identity = workspace?.workspaceIdentity, !identity.isEmpty {
            builder.set("workspaceIdentity", identity)
        }
        builder.set("runtimePolicy", "existing-only")
        if let value = try? await client.call(
            "zcode-agent", "subscribeWorkspaceConfigV4",
            .json(.object(builder.fields)), timeout: 5) {
            workspaceConfigSubscriptionId = value.jsonValue?["subscriptionId"]?.stringValue
        }
        log(.ok, workspaceConfigSubscriptionId != nil
            ? "subscribeWorkspaceConfigV4 · workspace-config 订阅完成"
            : "subscribeWorkspaceConfigV4 · 服务端未提供（chips 走缺省展示）")
        // 丢帧自愈：workspace-config assembler dropped → resyncWorkspaceConfigV4
        // （订阅面归 connection，resync 亦在此闭环；Store 仅消费投影）
        setFrameDropHandler(key: "workspace-config") { [weak self] in
            guard let self else { return }
            Task { await self.resyncWorkspaceConfig() }
        }
        return EventSubscription {
            conversationSub.cancel()
            sessionsSub.cancel()
            configSub.cancel()
            taskErrorSub.cancel()
        }
    }

    /// assembler dropped → resyncWorkspaceConfigV4（subscriptionId + base；无水位传 null 全量）
    private func resyncWorkspaceConfig() {
        guard let subscriptionId = workspaceConfigSubscriptionId, client != nil, isActive else { return }
        var builder = JSONObjectBuilder()
        builder.set("subscriptionId", subscriptionId)
        builder.set("base", JSONValue.null)
        Task { [weak self] in
            guard let self else { return }
            _ = try? await self.call(
                "zcode-agent", "resyncWorkspaceConfigV4", .json(.object(builder.fields)), timeout: 5)
            self.log(.info, "resyncWorkspaceConfigV4 · 丢帧自愈已触发")
        }
    }

    /// 丢帧自愈 handler 查找：assemblerKey 精确命中；多路帧流的分键
    /// （"conversation/&lt;path&gt;"）回退到基键 "conversation"——Store 只注册基键，
    /// 若无回退则跨工作区会话（v1.23 补挂路的全部场景）丢帧自愈永不触发
    /// （2026-10-08 回归审核轮 §11.1#5）
    private func dropHandler(for assemblerKey: String) -> (@Sendable () -> Void)? {
        frameDropHandlers[assemblerKey]
            ?? (assemblerKey.hasPrefix("conversation/") ? frameDropHandlers["conversation"] : nil)
    }

    nonisolated private func routeFrame(_ payload: RPCValue, assemblerKey: String, handlerKey: String?) {
        guard let json = payload.jsonValue,
              let envelope = TopicWireFrame.parse(json) else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            var assembler = self.frameAssemblers[assemblerKey] ?? TopicWireFrameAssembler()
            guard let logical = assembler.accept(envelope) else {
                self.frameAssemblers[assemblerKey] = assembler
                // 丢帧自愈：assembler 置位 dropped → 通知订阅方 resync（断线/换代/坏分片）
                if assembler.consumeDroppedFlag() {
                    self.dropHandler(for: assemblerKey)?()
                }
                return
            }
            self.frameAssemblers[assemblerKey] = assembler
            if assembler.consumeDroppedFlag() {
                self.dropHandler(for: assemblerKey)?()
            }
            guard let frame = V4TopicFrame.parse(logical) else { return }
            // 会话索引帧：handlerKey 为事件别名（"sessions-index"），而订阅侧按完整 topic
            // （"sessions-index/<workspacePath>"）注册——两个键都尝试，否则快照帧被静默丢弃
            // （症状：连接成功但会话列表恒为空态；e2e 门禁第 1 轮 test06/test12 实证）。
            if let handlerKey {
                let handler = self.frameHandlers[handlerKey] ?? self.frameHandlers[frame.topic]
                if assemblerKey == "workspace-config" {
                    // Store 注册 handler 前到达的快照/增量先缓存（整体替换语义，重放安全）
                    self.workspaceConfigReplay.append(frame)
                    if self.workspaceConfigReplay.count > 32 {
                        self.workspaceConfigReplay.removeFirst(self.workspaceConfigReplay.count - 32)
                    }
                }
                handler?(frame)
            }
            // conversation 帧按 topic 前缀分流给 handlerKey="conversation/<id>"
            if handlerKey == nil, frame.topic.hasPrefix("conversation/"),
               let handler = self.frameHandlers[frame.topic] {
                handler(frame)
            }
        }
    }

    /// 注册 logical 帧处理器（topic：conversation/<sessionId> 或 sessions-index/<workspaceId>）。
    /// workspace-config topic 注册时重放缓存帧（连接即订阅、Store 晚注册不丢快照）。
    func setFrameHandler(topic: String, handler: @escaping @Sendable (V4TopicFrame) -> Void) {
        frameHandlers[topic] = handler
        if topic.hasPrefix("workspace-config/") {
            let replay = workspaceConfigReplay
            workspaceConfigReplay.removeAll()
            // 按 topic 精确匹配重放：工作区切换瞬间的旧工作区残帧不重放给新 Store
            //（缓存与重放本就单 workspace 同 topic，语义不变；P3-10 切换面加严）
            for frame in replay where frame.topic == topic {
                handler(frame)
            }
        }
    }

    func removeFrameHandler(topic: String) {
        frameHandlers.removeValue(forKey: topic)
    }

    /// 注册 assembler 丢帧回调（key：conversation / sessions / workspace-config）。
    /// 回调方（各 Store）保存订阅回执的 subscriptionId，据以发 resyncConversationV4 /
    /// resyncSessionsIndexV4 / resyncWorkspaceConfigV4 恢复续流。
    func setFrameDropHandler(key: String, handler: @escaping @Sendable () -> Void) {
        frameDropHandlers[key] = handler
    }

    func removeFrameDropHandler(key: String) {
        frameDropHandlers.removeValue(forKey: key)
    }

    // MARK: channel RPC 门面

    /// 纵深防御：所有 V4Wire/RPC 调用的唯一出口。连接态（真实服务器）下 directWrite
    /// 分类（文件直写/回滚/仓库写/宿主与配置写）在此直接拦截——即使 UI 层有遗漏入口
    /// 也不会触达服务端；消息/审批/停止/队列等桌面代执行命令放行。
    /// mock 演示不经过本连接，演示态交互不受影响。
    /// 握手自愈（2026-10-08 真机实据）：桥(重)开竞态下 assertReady 类调用可能仍撞
    /// fault.connection.handshakeRequired（sess_e8677b05 rowsRange 实证——v1.17 单点
    /// 握手未覆盖全部重建路径）——捕获后重做一次 v4 握手并原样重试一次，把「能发送、
    /// 收不到」的握手形态在调用出口兜住。
    func call(_ channel: String, _ command: String, _ arg: RPCValue = .undefined,
              timeout: TimeInterval = 30) async throws -> RPCValue {
        guard let client, isActive else {
            throw RPCError(message: String(localized: "未连接桌面端"), name: "NotConnected")
        }
        let verdict = ReadOnlyGate.inspect(channel: channel, command: command, arg: arg)
        if verdict.isBlocked {
            let reason = verdict.reason ?? String(localized: "直写类命令")
            blockedExecutionCalls.append(reason)
            if blockedExecutionCalls.count > 20 { blockedExecutionCalls.removeFirst(blockedExecutionCalls.count - 20) }
            log(.error, "边界拦截 · \(reason)")
            throw RPCError(message: String(localized: "移动端边界：\(reason) 属手机直写面，不接"), name: "ReadOnlyViolation")
        }
        do {
            return try await client.call(channel, command, arg, timeout: timeout)
        } catch let error as RPCError where error.isHandshakeRequired {
            // 中继桥面专属自愈：重握手后重试一次（重试仍失败如实上抛）
            guard let relayClient = relayClient else { throw error }
            log(.info, "handshakeRequired · 重做 v4 握手后重试 \(channel).\(command)")
            await performRelayV4Handshake(client: relayClient)
            return try await client.call(channel, command, arg, timeout: timeout)
        }
    }

    func listen(_ channel: String, _ event: String, _ arg: RPCValue = .undefined,
                handler: @escaping @Sendable (RPCValue) -> Void) async -> EventSubscription? {
        guard let client, isActive else { return nil }
        return await client.listen(channel, event, arg, handler: handler)
    }

    /// 曾连接后的真实传输中断 → 通知 AppSession 切 .disconnected（横幅）。
    /// 连接建立前的失败走 finishFailure/.connectFailed 分支，state 非 .connected，不触发。
    private func notifyDroppedIfConnected(_ detail: String) {
        guard case .connected = state else { return }
        onConnectionDropped?(detail)
    }
}

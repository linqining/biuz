package cn.biuz.android.connection

import cn.biuz.android.protocol.ChannelClient
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.JSONObjectBuilder
import cn.biuz.android.protocol.JsonParseException
import cn.biuz.android.protocol.ReadOnlyGate
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.TopicWireFrame
import cn.biuz.android.protocol.TopicWireFrameAssembler
import cn.biuz.android.protocol.V4TopicFrame
import cn.biuz.android.protocol.WebSocketTransportFactory
import cn.biuz.android.relay.RelayChannelClient
import cn.biuz.android.relay.RelayLogKind
import cn.biuz.android.relay.RelayTransport
import cn.biuz.android.store.ConversationFrameSurface
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// 直译自 ios/ZCodeMobileApp/Sources/Services/Remote/ZCodeServerConnection.swift（1194 行）。
// 上游：packages/shared/src/server-remote.ts + packages/shared/src/zcode-protocol-v4/transport.ts。
//
// 本轮落位面 = 局域网直连全链路（协议文档 §3.1/§3.2/§4/§5/§6）：五步连接
// （server-info → 鉴权 → WS → v4 握手 → workspaces[0]）+ `call` 唯一出口（ReadOnlyGate
// 接线）+ 帧面按 workspace 多路幂等挂载（v1.23）+ workspace-config 订阅/resync 自愈。
//
// 云中继面（2026-10-10 M6 随 :relay 落位接线）：connectRelay / switchRelayWorkspace /
// reconnectRelayWorkspace / reportActiveTask / handleWorkspaceListUpdated / mapRelayError /
// markRunningStepFailed（iOS ZCodeServerConnection.swift:468-872 直译）——桥(重)开握手
// 单点经 onBridgeOpened 钩子调入 performRelayV4Handshake（协议文档 §5.3 桥(重)开握手
// 纪律；A6 红线：每次桥(重)开都是全新握手态）。
//
// 并发模型适配（docs/Android开发交接.md §2.2）：
// - Swift @MainActor @Observable → StateFlow 状态投 + synchronized 账本（logs/blocked/
//   帧面路由账本，锁内无挂起、handler 一律锁外调用——ChannelClient.kt 同先例）；
// - iOS routeFrame 的 MainActor hop → 单消费者 Channel 串行派发（帧序保序比照
//   MainActor 串行化，A8/A9 水位依赖帧序）；
// - `String(localized:)` → 同串字面量（同 Models.kt 文件头约定）；
// - TimeInterval → Duration（30.seconds 缺省，与 RPCChannelTransport 接口同口径）；
// - Bundle.main.infoDictionary（appVersion）→ 构造参数（纯 JVM 模块无 bundle）。

// MARK: - ServerRemoteInfo（packages/shared/src/server-remote.ts:5-38）

data class ServerWorkspaceInfo(
    val path: String,
    val label: String?,
    val workspaceIdentity: String?,
)

data class ServerRemoteInfo(
    val serverId: String,
    val name: String?,
    val version: String,
    val protocolVersion: Int,
    val authRequired: Boolean,
    val workspaces: List<ServerWorkspaceInfo>,
    /** chips 展示（server-info.capabilities 键集，L5 双源口径之展示源） */
    val capabilities: List<String>,
) {
    companion object {
        /** SERVER_REMOTE_PROTOCOL_VERSION */
        const val expectedProtocolVersion = 1

        /** Swift `ServerRemoteInfo.parse`（ZCodeServerConnection.swift:22-45）。 */
        fun parse(json: JSONValue): ServerRemoteInfo? {
            val dict = json.objectValue ?: return null
            val serverId = dict["serverId"]?.stringValue ?: return null
            val version = dict["version"]?.stringValue ?: return null
            val workspaces = (dict["workspaces"]?.arrayValue ?: emptyList()).mapNotNull { item ->
                val itemDict = item.objectValue ?: return@mapNotNull null
                val path = itemDict["path"]?.stringValue ?: return@mapNotNull null
                ServerWorkspaceInfo(
                    path = path,
                    label = itemDict["label"]?.stringValue,
                    workspaceIdentity = itemDict["workspaceIdentity"]?.stringValue,
                )
            }
            var capabilities: List<String> = emptyList()
            dict["capabilities"]?.objectValue?.let { caps ->
                capabilities = caps.filterValues { it.boolValue == true }.keys.sorted()
            }
            return ServerRemoteInfo(
                serverId = serverId,
                name = dict["name"]?.stringValue,
                version = version,
                protocolVersion = dict["protocolVersion"]?.intValue ?: 0,
                authRequired = dict["authRequired"]?.boolValue ?: false,
                workspaces = workspaces,
                capabilities = capabilities,
            )
        }
    }
}

// MARK: - 连接错误（L3 四态对照）

/**
 * Swift `enum ConnectError: Error` → sealed class（associated value → data class，
 * 无载荷 case → data object；值相等语义与 Swift Equatable 同构）。
 * Kotlin catch 需要 Throwable → 继承 Exception（protocol/RPCError 同先例）。
 */
sealed class ConnectError : Exception() {
    /** 401 → 令牌不匹配 */
    data class Http(val status: Int, val endpoint: String) : ConnectError()

    /** 超时（含本地网络权限被拒的静默失败） */
    data class Timeout(val endpoint: String) : ConnectError()

    /** remote v1 / v4 wire v3 不符 */
    data class ProtocolVersion(val actual: String) : ConnectError()

    data object EmptyWorkspaces : ConnectError()

    /** v4 握手失败 */
    data class HandshakeFailed(val detail: String) : ConnectError()

    /** WS 升级失败 */
    data class Transport(val detail: String) : ConnectError()

    /** （iOS String(localized:) 键 = 同串，见文件头约定） */
    val headline: String
        get() = when (this) {
            is Http -> if (status == 401) "无法验证访问令牌" else "服务返回错误"
            is Timeout -> "连接超时"
            is ProtocolVersion -> "协议版本不匹配"
            is EmptyWorkspaces -> "工作区列表为空"
            is HandshakeFailed -> "协议握手失败"
            is Transport -> "无法建立 WebSocket"
        }

    /** mono 错误码行（l3-err-code） */
    val codeLine: String
        get() = when (this) {
            is Http -> "HTTP $status · $endpoint"
            is Timeout -> "TIMEOUT · $endpoint"
            is ProtocolVersion -> "PROTOCOL $actual · 期望 remote v1 · v4 v3"
            is EmptyWorkspaces -> "workspaces=[]"
            is HandshakeFailed -> "HANDSHAKE · $detail"
            is Transport -> "WS · $detail"
        }
}

// MARK: - server-info HTTP 探测（GET /api/server-info?token=…）

object ServerInfoClient {

    class ProbeResult(
        val info: ServerRemoteInfo?,
        val status: Int?,
        val latencyMs: Int,
        val error: ConnectError?,
    )

    /**
     * 连接测试（L4-B：1.5s 超时，仅探测不建 WS）；L2 第一步超时 3s 自动重试 1 次。
     * iOS 原文 URLSession.shared.data(for:) → JDK HttpURLConnection 承载（一次性
     * HTTP 探测，无线程池生命周期负担；WS 面走 OkHttp——任务指定口径）。
     */
    suspend fun probe(server: ServerConfig, timeoutMs: Long): ProbeResult {
        val started = System.currentTimeMillis()
        var urlText = server.baseURL + "/api/server-info"
        if (server.token.isNotEmpty()) {
            urlText += "?token=" + percentEncodeQueryValue(server.token)
        }
        val url = runCatching { URL(urlText) }.getOrNull()
            ?: return ProbeResult(info = null, status = null, latencyMs = 0, error = ConnectError.Transport("无效地址"))
        var connection: HttpURLConnection? = null
        return try {
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            val status = conn.responseCode
            val latency = (System.currentTimeMillis() - started).toInt()
            if (status !in 200..299) {
                return ProbeResult(info = null, status = status, latencyMs = latency,
                    error = ConnectError.Http(status = status, endpoint = "GET /api/server-info"))
            }
            val body = conn.inputStream.use { it.readBytes() }
            val json = try {
                JSONValue.parse(body)
            } catch (e: JsonParseException) {
                null
            }
            val info = json?.let { ServerRemoteInfo.parse(it) }
            if (info == null) {
                return ProbeResult(info = null, status = status, latencyMs = latency,
                    error = ConnectError.Transport("server-info 解析失败"))
            }
            if (info.protocolVersion != ServerRemoteInfo.expectedProtocolVersion) {
                return ProbeResult(info = info, status = status, latencyMs = latency,
                    error = ConnectError.ProtocolVersion(actual = "remote v${info.protocolVersion}"))
            }
            ProbeResult(info = info, status = status, latencyMs = latency, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            // 真·超时保持 Timeout 语义（P-017 失败文案映射「响应超时」依赖此形）
            val latency = (System.currentTimeMillis() - started).toInt()
            ProbeResult(info = null, status = null, latencyMs = latency,
                error = ConnectError.Timeout(endpoint = "GET /api/server-info"))
        } catch (e: Throwable) {
            // 非超时网络异常（NetworkOnMainThreadException/ConnectException/安全策略…）
            // 如实带原始类名上错误面——旧实现全吞成 Timeout 假象，分诊误导
            // （2026-11-21 模拟器实测：手动连接 NetworkOnMainThreadException 被误报
            // 「桌面端响应超时」，错误面上屏取证）（§5-12 同精神——禁静默吞真因）
            println("cn.biuz.android [relay-log][probe] GET /api/server-info failed: " +
                "${e.javaClass.name}: ${e.message}")
            val latency = (System.currentTimeMillis() - started).toInt()
            ProbeResult(info = null, status = null, latencyMs = latency,
                error = ConnectError.Transport(
                    "server-info probe: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

// MARK: - 连接五步进度（L2：发现 → 鉴权 → WS → v4 握手 → 工作区）

data class ConnectStepState(
    val phase: Phase = Phase.pending,
    /** 步骤 meta 列（耗时 / 状态码 / 免鉴权标注） */
    val meta: String = "",
) {
    enum class Phase { pending, running, done, failed }
}

data class ConnectProgress(
    val discover: ConnectStepState = ConnectStepState(),
    val auth: ConnectStepState = ConnectStepState(),
    val websocket: ConnectStepState = ConnectStepState(),
    val handshake: ConnectStepState = ConnectStepState(),
    val workspace: ConnectStepState = ConnectStepState(),
) {
    /** 计数含进行中（2 完成 + 1 进行中 = 3/5） */
    val completedCount: Int
        get() = allSteps.count { it.phase == ConnectStepState.Phase.done }

    val runningCount: Int
        get() = allSteps.count { it.phase == ConnectStepState.Phase.running }

    val fraction: Double
        get() = (completedCount + runningCount) / 5.0

    val allSteps: List<ConnectStepState>
        get() = listOf(discover, auth, websocket, handshake, workspace)
}

/** 连接日志行（connect.log / oauth.log 终端条；token 一律 *** 掩码） */
data class ConnectLogLine(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val text: String,
) {
    enum class Kind { ok, working, info, error }
}

/** Swift `Result<ServerRemoteInfo, ConnectError>` 对应物（ConnectURLParser.ParseResult 同先例：sealed 同构直译）。 */
sealed class ConnectResult {
    data class Success(val info: ServerRemoteInfo) : ConnectResult()
    data class Failure(val error: ConnectError) : ConnectResult()
}

/** Swift `Result<ServerWorkspaceInfo, ConnectError>` 对应物（switchRelayWorkspace 面）。 */
sealed class WorkspaceSwitchResult {
    data class Success(val workspace: ServerWorkspaceInfo) : WorkspaceSwitchResult()
    data class Failure(val error: ConnectError) : WorkspaceSwitchResult()
}

// MARK: - v4 握手模型（transport.ts:40-86）

data class V4HelloMessage(
    val protocolVersion: Int,
    val connectionId: String,
    val clientMode: String,
    val deliveryProfile: String,
    val serverTime: String?,
    val capabilities: Map<String, Boolean>,
    val authUserId: String?,
) {
    companion object {
        /** V4_WIRE_PROTOCOL_VERSION */
        const val wireProtocolVersion = 3

        /** Swift `V4HelloMessage.parse`（ZCodeServerConnection.swift:198-214）。 */
        fun parse(json: JSONValue): V4HelloMessage? {
            val dict = json.objectValue ?: return null
            val capabilities = LinkedHashMap<String, Boolean>()
            dict["capabilities"]?.objectValue?.let { caps ->
                for ((key, value) in caps) {
                    capabilities[key] = value.boolValue ?: false
                }
            }
            return V4HelloMessage(
                protocolVersion = dict["protocolVersion"]?.intValue ?: 0,
                connectionId = dict["connectionId"]?.stringValue ?: "",
                clientMode = dict["clientMode"]?.stringValue ?: "",
                deliveryProfile = dict["deliveryProfile"]?.stringValue ?: "",
                serverTime = dict["serverTime"]?.stringValue,
                capabilities = capabilities,
                authUserId = dict["auth"]?.objectValue?.get("userId")?.stringValue,
            )
        }
    }
}

// MARK: - 服务器连接管理（五步 + channel RPC 门面 + 断线通知）

/**
 * 与桌面 zcode-server 的单条连接：GET server-info → WS /ws?token → v4 握手 → workspaces[0]。
 * （iOS @MainActor @Observable → StateFlow 投影 + synchronized 账本，见文件头并发适配注。）
 *
 * 实现 [ConversationFrameSurface]（:store 帧面契约——RemoteConversationStore 装配注入用）：
 * 本类四件帧方法（:1223/:1408/:1424/:1433）与接口签名逐字一致，装配轮声明 implements
 * 即接线（RemoteConversationStore.kt 文件头预置口径；单例共享注册表语义——同一 workspace
 * 恒一路 eventListen、帧 handler 按 topic 精确匹配——由本类既有实现保证）。
 */
class ZCodeServerConnection(
    /** iOS Bundle.main CFBundleShortVersionString 的构造参数对应物（缺省同 iOS 兜底值）。 */
    val appVersion: String = DEFAULT_APP_VERSION,
    private val transportFactory: WebSocketTransportFactory = DEFAULT_TRANSPORT_FACTORY,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationFrameSurface {

    /**
     * Relay V2 JWT 提供者（装配层注入；V1 不需要）。
     * 每次中继（重）连都会调用它换一次性 token（iOS `RelayTransport.jwtProvider` 同口径）。
     */
    var relayJwtProvider: (suspend () -> String?)? = null

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data class Connecting(val progress: ConnectProgress) : ConnectionState()
        data class Connected(val info: ServerRemoteInfo, val workspace: ServerWorkspaceInfo) : ConnectionState()
        data class Failed(val error: ConnectError) : ConnectionState()

        /** 曾连接后断线（重连提示） */
        data class Disconnected(val error: ConnectError) : ConnectionState()
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** iOS @Observable state 的 Kotlin 投影（读 `.value`；UI 层 collect 承接刷新）。 */
    val state: StateFlow<ConnectionState>
        get() = _state

    val isActive: Boolean
        get() = _state.value is ConnectionState.Connected

    /**
     * 局域网直连（ChannelClient，13 字节头二进制帧）或云中继（RelayChannelClient，
     * JSON 文本帧 + rpc-frame）——统一走 RPCChannelTransport 门面，只读拦截同源覆盖
     * （iOS `private var client: (any RPCChannelTransport)?`，:237 同型加宽）。
     */
    @Volatile
    private var client: RPCChannelTransport? = null

    /**
     * 云中继客户端引用（iOS RelayChannelClient 强类型引用，:239；局域网直连恒 null。
     * connect 的 handshakeRequired 捕获自愈、supportsWorkspaceSwitching、切换/重连/
     * 活动任务上报均以它判向，与 iOS `relayClient != nil` 同口径）。
     */
    @Volatile
    private var relayClient: RelayChannelClient? = null

    /** 云中继传输（iOS :240；workspace-reconnect-request 的 app 层请求面用）。 */
    @Volatile
    private var relayTransport: RelayTransport? = null

    /**
     * 工作区清单推送（P3-10 workspace-list-updated）：handleWorkspaceListUpdated 解析后
     * 上抛 AppSession 刷新任务聚合清单（iOS :274；清单本体已由连接层回写 serverInfo
     * 联动切换器，AppSession 侧再回写 RemoteConversationStore.allWorkspaces——iOS
     * setAllWorkspaces 同源）。
     */
    var onWorkspaceListUpdated: ((List<ServerWorkspaceInfo>) -> Unit)? = null

    @Volatile
    var serverInfo: ServerRemoteInfo? = null
        private set

    @Volatile
    var workspace: ServerWorkspaceInfo? = null
        private set

    private var helloMessage: V4HelloMessage? = null

    /**
     * 3.17.2 输入预测能力位（hello.capabilities.inputPrediction === true 才发请求；
     * spec「能力探测」——false/缺席（旧 Host）不得发起；iOS
     * ZCodeServerConnection.inputPredictionAvailable :250-253 直译）。装配层在
     * assembleStores 时读取（握手完成后装配，hello 已定案）。
     */
    fun inputPredictionAvailable(): Boolean =
        helloMessage?.capabilities?.get("inputPrediction") == true

    @Volatile
    private var frameSubscription: EventSubscription? = null

    // 帧面路由账本（routingLock 临界区；锁内无挂起，handler 一律锁外调用）
    private val routingLock = Any()
    private val frameAssemblers = HashMap<String, TopicWireFrameAssembler>() // assemblerKey 键控
    private val frameHandlers = HashMap<String, (V4TopicFrame) -> Unit>()
    /** assembler 丢帧（dropped）回调：key 同 assemblerKey，订阅方据此触发 resync 自愈 */
    private val frameDropHandlers = HashMap<String, () -> Unit>()

    /** workspace-config 订阅面（connection 持有：subscribe/unsubscribe/resync 均为 promise 面） */
    private var workspaceConfigTopicPath: String? = null
    private var workspaceConfigSubscriptionId: String? = null

    /** Store 注册 workspace-config handler 前到达的帧（快照/增量整体替换语义，重放安全） */
    private val workspaceConfigReplay = ArrayList<V4TopicFrame>()

    /** conversation 帧流按 workspace 多路记账（streamKey → 订阅句柄；见 ensureConversationFrameStream） */
    private val conversationFrameStreams = LinkedHashMap<String, EventSubscription>()

    @Volatile
    private var manuallyCancelled = false

    /**
     * 连接握手注册的 clientId（命令信封 envelope.clientId 必须与之一致，
     * 否则桌面端拒收 fault.command.clientMismatch）。init 后不变（A2 单一来源）。
     */
    val registeredClientId: String = "zcode-mobile-" + UUID.randomUUID().toString().take(8)

    /** 只读边界拦截记录（最近 20 条；连接态 execution 命令在 RPC 出口被拒的证据） */
    private val blockedExecutionCalls = ArrayList<String>()

    /**
     * 断线回落回调（连接成功后 WS/中继通道意外中断时触发）：装配层据此把
     * mode 切为 disconnected（横幅「与桌面端的连接已断开 · 重连」）。
     * 仅「曾进入 .connected 后的真实传输中断」触发；手动 disconnect() 经
     * manuallyCancelled 守卫先行返回，不会误触发。
     */
    var onConnectionDropped: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private val logLock = Any()
    private val logs = ArrayList<ConnectLogLine>()

    /** iOS @Observable logs 的读面（最近 40 条快照副本）。 */
    fun logs(): List<ConnectLogLine> = synchronized(logLock) { logs.toList() }

    /** 只读边界拦截记录读面（最近 20 条快照副本）。 */
    fun blockedExecutionCallsSnapshot(): List<String> = synchronized(logLock) { blockedExecutionCalls.toList() }

    /** iOS `relayClient != nil && isActive`（P3-10）：云中继连接为 true（切换器 UI gating）。 */
    val supportsWorkspaceSwitching: Boolean
        get() = relayClient != null && isActive

    // MARK: 帧派发（iOS routeFrame 的 MainActor hop → 单消费者 Channel 串行派发，保帧序）

    private class FrameDispatchUnit(
        val envelope: TopicWireFrame.Envelope,
        val assemblerKey: String,
        val handlerKey: String?,
    )

    private val frameDispatch = Channel<FrameDispatchUnit>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (unit in frameDispatch) {
                dispatchFrame(unit)
            }
        }
    }

    // MARK: 五步连接（超时阈值 3s/5s，自动重试 1 次；L2 口径）

    // Kotlin 无 Swift 实参标签（`to server:`）→ 参数名 server，调用面 connect(server = …)
    suspend fun connect(server: ServerConfig, preferredWorkspace: String? = null): ConnectResult {
        disconnect()
        manuallyCancelled = false
        synchronized(logLock) { logs.clear() }
        var progress = ConnectProgress()

        // 步骤 1：发现服务（GET /api/server-info，3s 超时，重试 1 次）
        progress = progress.copy(discover = progress.discover.copy(phase = ConnectStepState.Phase.running))
        _state.value = ConnectionState.Connecting(progress)
        log(ConnectLogLine.Kind.working, "GET /api/server-info")
        var probe = ServerInfoClient.probe(server = server, timeoutMs = 3_000)
        if (probe.error != null) {
            log(ConnectLogLine.Kind.info, "超时，自动重试 1 次（指数退避）")
            delay(1_000)
            probe = ServerInfoClient.probe(server = server, timeoutMs = 3_000)
        }
        probe.error?.let { error ->
            progress = progress.copy(discover = progress.discover.copy(phase = ConnectStepState.Phase.failed))
            _state.value = ConnectionState.Connecting(progress)
            finishFailure(error)
            return ConnectResult.Failure(error)
        }
        progress = progress.copy(
            discover = progress.discover.copy(
                phase = ConnectStepState.Phase.done,
                meta = "${probe.status ?: 200} · ${probe.latencyMs}ms",
            ),
        )
        log(ConnectLogLine.Kind.ok, "GET /api/server-info → ${probe.status ?: 200} · ${probe.latencyMs}ms")
        val info = probe.info
        if (info == null) {
            val error = ConnectError.Transport("server-info 缺失")
            finishFailure(error)
            return ConnectResult.Failure(error)
        }
        serverInfo = info
        if (info.name != null) {
            log(ConnectLogLine.Kind.ok, "serverId=${info.serverId} · ${info.name} · v${info.version} · protocolVersion=1")
        } else {
            log(ConnectLogLine.Kind.ok, "serverId=${info.serverId} · v${info.version} · protocolVersion=1")
        }

        // 步骤 2：校验访问令牌（authRequired=false 时为「免鉴权」口径，不出现「校验通过」）
        progress = progress.copy(auth = progress.auth.copy(phase = ConnectStepState.Phase.running))
        _state.value = ConnectionState.Connecting(progress)
        if (info.authRequired) {
            if (server.token.isEmpty()) {
                progress = progress.copy(auth = progress.auth.copy(phase = ConnectStepState.Phase.failed))
                _state.value = ConnectionState.Connecting(progress)
                val error = ConnectError.Http(status = 401, endpoint = "/api/server-info")
                finishFailure(error)
                return ConnectResult.Failure(error)
            }
            progress = progress.copy(auth = progress.auth.copy(phase = ConnectStepState.Phase.done, meta = "authRequired=true"))
            log(ConnectLogLine.Kind.ok, "token 校验通过 · authRequired=true")
        } else {
            progress = progress.copy(auth = progress.auth.copy(phase = ConnectStepState.Phase.done, meta = "免鉴权"))
            log(ConnectLogLine.Kind.ok, "authRequired=false · --no-token（免鉴权）")
        }

        // 步骤 3：建立 WebSocket（ws://host:port/ws?token=…，5s 超时）
        progress = progress.copy(websocket = progress.websocket.copy(phase = ConnectStepState.Phase.running))
        _state.value = ConnectionState.Connecting(progress)
        val client = ChannelClient(transportFactory, backgroundDispatcher)
        this.client = client
        try {
            var wsUrl = "${server.wsBaseURL}/ws"
            if (server.token.isNotEmpty()) {
                wsUrl += "?token=" + percentEncodeQueryValue(server.token)
                log(ConnectLogLine.Kind.working, "WS 升级 ${server.wsBaseURL}/ws?token=***")
            } else {
                log(ConnectLogLine.Kind.working, "WS 升级 ${server.wsBaseURL}/ws（无 token 段）")
            }
            client.connect(url = wsUrl, timeout = 5.seconds) { error ->
                if (manuallyCancelled) return@connect
                val detail = error?.message ?: error?.toString() ?: "连接中断"
                val connectError = ConnectError.Transport(detail)
                notifyDroppedIfConnected(connectError.headline)
                _state.value = ConnectionState.Disconnected(connectError)
                teardownTransport()
            }
            progress = progress.copy(
                websocket = progress.websocket.copy(phase = ConnectStepState.Phase.done, meta = "web-remote-replayable"),
            )
            log(ConnectLogLine.Kind.ok, "WS 已建立 · clientMode=web-remote-replayable")
        } catch (e: RPCError) {
            progress = progress.copy(websocket = progress.websocket.copy(phase = ConnectStepState.Phase.failed))
            _state.value = ConnectionState.Connecting(progress)
            val connectError = if (e.name == "TimeoutError") {
                ConnectError.Timeout(endpoint = "GET /ws")
            } else {
                ConnectError.Transport(e.message)
            }
            finishFailure(connectError)
            return ConnectResult.Failure(connectError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            progress = progress.copy(websocket = progress.websocket.copy(phase = ConnectStepState.Phase.failed))
            _state.value = ConnectionState.Connecting(progress)
            val connectError = ConnectError.Transport(e.message ?: e.toString())
            finishFailure(connectError)
            return ConnectResult.Failure(connectError)
        }

        // 步骤 4：v4 握手（hello → clientHello，clientKind=mobileApp；capabilities 单向规则：
        // 只回显 Host 宣告过的键）
        progress = progress.copy(handshake = progress.handshake.copy(phase = ConnectStepState.Phase.running))
        _state.value = ConnectionState.Connecting(progress)
        try {
            val helloValue = client.call("zcode-agent", "helloConversationV4", RPCValue.Undefined, timeout = 5.seconds)
            val hello = V4HelloMessage.parse(helloValue.jsonValue ?: JSONValue.Null)
                ?: throw ConnectError.HandshakeFailed("hello 解析失败")
            if (hello.protocolVersion != V4HelloMessage.wireProtocolVersion) {
                throw ConnectError.ProtocolVersion(actual = "v4 wire v${hello.protocolVersion}")
            }
            if (hello.clientMode != "web-remote-replayable") {
                throw ConnectError.HandshakeFailed("clientMode=${hello.clientMode}")
            }
            helloMessage = hello
            log(ConnectLogLine.Kind.ok, "helloConversationV4 · protocolVersion=3 · deliveryProfile=${hello.deliveryProfile}")

            // clientHello capabilities 单向宣告规则（transport.ts:72-83 .strict()）：
            // 只回显 Host hello 已宣告的键。workflowRunDeltas 为 true 时回显——移动端
            // 已实现 workflowRun.updated/removed 专属增量消费（RCS applyDelta），
            // 不声明则服务端把增量折叠回整键 state.updated 且宽 run 裁到 256
            // （2026-10-08 回归审核轮 §11.2：此前恒不声明=增量消费死代码）
            val clientHello = RPCValue.jsonObject {
                set("kind", "clientHello")
                set("protocolVersion", V4HelloMessage.wireProtocolVersion)
                set("clientId", registeredClientId)
                set("clientKind", "mobileApp") // transport.ts:73 预留值
                set("appVersion", appVersion)
                if (hello.capabilities["workflowRunDeltas"] == true) {
                    set(
                        "capabilities",
                        JSONValue.Obj(mapOf("workflowRunDeltas" to JSONValue.Bool(true))),
                    )
                }
            }
            client.call("zcode-agent", "initializeConversationV4", clientHello, timeout = 5.seconds)
            progress = progress.copy(
                handshake = progress.handshake.copy(phase = ConnectStepState.Phase.done, meta = "clientKind=mobileApp"),
            )
            log(ConnectLogLine.Kind.ok, "initializeConversationV4 · 握手完成")
        } catch (e: ConnectError) {
            progress = progress.copy(handshake = progress.handshake.copy(phase = ConnectStepState.Phase.failed))
            _state.value = ConnectionState.Connecting(progress)
            finishFailure(e)
            return ConnectResult.Failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            progress = progress.copy(handshake = progress.handshake.copy(phase = ConnectStepState.Phase.failed))
            _state.value = ConnectionState.Connecting(progress)
            val connectError = ConnectError.HandshakeFailed(e.message ?: e.toString())
            finishFailure(connectError)
            return ConnectResult.Failure(connectError)
        }

        // 步骤 5：载入工作区（默认 workspaces[0]，或用户偏好）
        progress = progress.copy(workspace = progress.workspace.copy(phase = ConnectStepState.Phase.running))
        _state.value = ConnectionState.Connecting(progress)
        val preferred = preferredWorkspace?.let { path -> info.workspaces.firstOrNull { it.path == path } }
        val selected = preferred ?: info.workspaces.firstOrNull()
        if (selected == null) {
            progress = progress.copy(workspace = progress.workspace.copy(phase = ConnectStepState.Phase.failed))
            _state.value = ConnectionState.Connecting(progress)
            val error = ConnectError.EmptyWorkspaces
            finishFailure(error)
            return ConnectResult.Failure(error)
        }
        progress = progress.copy(
            workspace = progress.workspace.copy(phase = ConnectStepState.Phase.done, meta = selected.label ?: selected.path),
        )
        log(ConnectLogLine.Kind.ok, "workspaces[0] · ${selected.path}")
        workspace = selected
        // 订阅 workspace 级下行帧流（zcode-agent.onDynamicConversationFrame /
        // onDynamicSessionsIndexFrame）。必须在 workspace 选定**之后**执行
        // （2026-10-08 回归审核轮 §11.1#4：曾置于步骤 3 后，三路 eventListen 以
        // workspacePath="" 注册——上游 emitter 按 resolveWorkspaceKey 硬路由，
        // 空串键恒收不到帧，LAN 下 sessions-index/chips 实时帧全静默丢失；
        // 中继路径本就先设 workspace 不受影响；E2E 替身不按键路由故门禁掩盖）
        frameSubscription = subscribeFrameStreams(client)
        _state.value = ConnectionState.Connected(info, selected)
        return ConnectResult.Success(info)
    }

    // MARK: 桥内 v4 握手（云中继；M6 onBridgeOpened 钩子的单点接线面）

    /**
     * 桥内 v4 握手（helloConversationV4 → initializeConversationV4，clientKind=mobileApp）。
     * 每次桥(重)开经 onBridgeOpened 调入（iOS RelayChannelClient.openBridge 注）。旧版本桌面
     * 缺命令时降级放行（无握手闸，订阅不受影响）；新桌面瞬态失败（桥刚重建窗口内
     * timeout/瞬断）退避 1.2s 重试一次——此前 catch 直降级曾让连接停留在「未握手」态：
     * 全部 assertReady 类调用（subscribe*、readSession、rowsRange）持续
     * fault.connection.handshakeRequired 直到整轮重连才自愈（真机 2026-10-07
     * 18:33-18:34 风暴实证；AGENTS §5.8 中继瞬断退避同口径）。
     * 本轮先落握手单点本体（只依赖 RPCChannelTransport 门面），M6 中继连接经
     * onBridgeOpened 钩子调入。
     */
    private suspend fun performRelayV4Handshake(client: RPCChannelTransport) {
        suspend fun attempt(): V4HelloMessage {
            val helloValue = client.call("zcode-agent", "helloConversationV4", RPCValue.Undefined, timeout = 5.seconds)
            val hello = V4HelloMessage.parse(helloValue.jsonValue ?: JSONValue.Null)
                ?: throw ConnectError.HandshakeFailed("hello 解析失败")
            if (hello.protocolVersion != V4HelloMessage.wireProtocolVersion) {
                throw ConnectError.HandshakeFailed("中继桥 v4 wire v${hello.protocolVersion}")
            }
            return hello
        }
        try {
            var hello: V4HelloMessage? = null
            try {
                hello = attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 瞬态失败退避重试一次（旧桌面「方法不存在」类错误重试同样无害——
                // 二次失败走下方整体降级）
                delay(1_200)
                hello = attempt()
            }
            hello ?: return
            val clientHello = RPCValue.jsonObject {
                set("kind", "clientHello")
                set("protocolVersion", V4HelloMessage.wireProtocolVersion)
                set("clientId", registeredClientId)
                set("clientKind", "mobileApp")
                set("appVersion", appVersion)
                // 单向宣告规则同 LAN 路：Host 宣告 workflowRunDeltas 才回显
                //（transport.ts:72-83 .strict()，capabilities 键集封闭）
                if (hello.capabilities["workflowRunDeltas"] == true) {
                    set(
                        "capabilities",
                        JSONValue.Obj(mapOf("workflowRunDeltas" to JSONValue.Bool(true))),
                    )
                }
            }
            try {
                client.call("zcode-agent", "initializeConversationV4", clientHello, timeout = 5.seconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                delay(1_200)
                client.call("zcode-agent", "initializeConversationV4", clientHello, timeout = 5.seconds)
            }
            log(ConnectLogLine.Kind.ok, "桥内 v4 握手 · protocolVersion=3 · deliveryProfile=${hello.deliveryProfile}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(ConnectLogLine.Kind.info, "桥内 v4 握手不可用（降级继续）：${e.message ?: e.toString()}")
        }
    }

    // MARK: 云中继连接（remote/v4：跳过局域网探测，WS + auth 握手 → bootstrap → workspace 桥）
    // （iOS ZCodeServerConnection.swift:468-872 直译；机械适配逐处注明）

    /** RelayLogKind → ConnectLogLine.Kind（:relay 自带枚举的 :connection 逐值映射）。 */
    private fun RelayLogKind.toConnectKind(): ConnectLogLine.Kind =
        when (this) {
            RelayLogKind.ok -> ConnectLogLine.Kind.ok
            RelayLogKind.working -> ConnectLogLine.Kind.working
            RelayLogKind.info -> ConnectLogLine.Kind.info
            RelayLogKind.error -> ConnectLogLine.Kind.error
        }

    /**
     * 中继 bootstrap-response 的跨工作区 tasks 清单（web「所有项目目录」同源；
     * 非中继连接为 nil）（iOS :476-478）。
     */
    val relayBootstrapTasks: JSONValue?
        get() = relayClient?.taskListJSON

    /**
     * 中继五步映射（L2 面板口径）：发现=跳过标注；鉴权=auth 握手 matched；
     * WS=transport paired；握手=bootstrap + bridge-open + 桥内 Initialize；工作区=bridge.workspacePath。
     * 成功后复用既有 Remote Store 面（call/listen 经 RPCChannelTransport 门面，
     * ReadOnlyGate 出口拦截对中继路径同等生效）（iOS :481-605 直译）。
     */
    suspend fun connectRelay(server: ServerConfig): ConnectResult {
        disconnect()
        manuallyCancelled = false
        synchronized(logLock) { logs.clear() }
        var progress = ConnectProgress()
        val link = server.relay
        if (link == null) {
            val error = ConnectError.Transport("中继配置缺失")
            finishFailure(error)
            return ConnectResult.Failure(error)
        }

        // 步骤 1：发现——中继模式无 /api/server-info HTTP 面，直连 WS（:493-496）
        progress = progress.copy(discover = ConnectStepState(phase = ConnectStepState.Phase.done, meta = "云端中继"))
        log(ConnectLogLine.Kind.ok, "云端中继 · 跳过局域网探测 → ${link.endpointHost ?: "?"}/ws")

        // 步骤 2-4：auth 握手 → bootstrap → workspace-bridge-open → 桥内 Initialize（:498-511）
        progress = progress.copy(auth = ConnectStepState(phase = ConnectStepState.Phase.running))
        _state.value = ConnectionState.Connecting(progress)
        val transport = RelayTransport(
            config = link,
            onLog = { kind, text -> log(kind.toConnectKind(), text) },
            jwtProvider = relayJwtProvider,
            clientAppVersion = appVersion,
        )
        // 工作区清单推送（P3-10）：transport 拆出的独立 case 在此接线（先于任何
        // 桥/订阅建立——推送可能在握手后任意时点到达）
        transport.setWorkspaceListUpdatedHandler { payload ->
            scope.launch { handleWorkspaceListUpdated(payload) }
        }
        val client = RelayChannelClient(
            transport = transport,
            link = link,
            appVersion = appVersion,
            backgroundDispatcher = backgroundDispatcher,
        )
        // 桥(重)开即重握手（RelayChannelClient.setOnBridgeOpened 注）：首连/断线恢复/
        // degraded 重建/切换全部路径经 openBridge 汇入，握手在此单点接管——connectRelay
        // 里不再单独调 performRelayV4Handshake（openBridge 内已先行）
        client.setOnBridgeOpened { bridgeClient ->
            performRelayV4Handshake(bridgeClient)
        }
        relayTransport = transport
        this.client = client
        relayClient = client
        try {
            val summary = client.connectRelay(timeout = 15.seconds) { error ->
                if (manuallyCancelled) return@connectRelay
                val connectError = ConnectError.Transport(error?.message ?: "中继连接中断")
                notifyDroppedIfConnected(connectError.headline)
                _state.value = ConnectionState.Disconnected(connectError)
                teardownTransport()
            }
            progress = progress.copy(
                auth = ConnectStepState(phase = ConnectStepState.Phase.done, meta = "auth matched"),
                websocket = ConnectStepState(
                    phase = ConnectStepState.Phase.done,
                    meta = summary.desktopAppVersion?.let { "桌面 v$it" } ?: "paired",
                ),
            )
            log(ConnectLogLine.Kind.ok, "WS + auth 握手完成 · pair_status=matched · ${summary.sessionCount} 个会话")

            progress = progress.copy(
                handshake = ConnectStepState(phase = ConnectStepState.Phase.done, meta = "bridge kind=${summary.bridgeKind ?: "local"}"),
            )
            log(ConnectLogLine.Kind.ok, "bootstrap + workspace-bridge-open 完成 · 桥内 Initialize 已到")

            // 步骤 5：桥 workspacePath → ServerWorkspaceInfo（Store 装配与局域网同构）。
            // 多 workspace（2026-10-06 取证 workspace-list-response）：全部工作区入 info，
            // active（桥）保持首位——任务列表按全清单聚合，单工作区行为不变；
            // C-15 canBridge 门控（web 同款 `kind!=='remote' || (identity && remoteSessionId)`）：
            // 不可桥条目从切换菜单过滤（web「不可桥不开桥、退 home-only」的菜单面等价）（:542-572）
            progress = progress.copy(workspace = ConnectStepState(phase = ConnectStepState.Phase.running))
            _state.value = ConnectionState.Connecting(progress)
            val activeEntry = summary.workspaces.firstOrNull { it.workspaceKey == summary.workspaceKey }
                ?: summary.workspaces.firstOrNull()
            val relayWorkspaces = summary.workspaces
                .filter { it.canBridge }
                .map { workspaceInfoFrom(it) }
                .toMutableList()
            if (activeEntry != null &&
                !relayWorkspaces.any { it.path == (activeEntry.path ?: activeEntry.workspaceKey) }
            ) {
                relayWorkspaces.add(
                    0,
                    ServerWorkspaceInfo(
                        path = activeEntry.path ?: activeEntry.workspaceKey,
                        label = server.displayName,
                        workspaceIdentity = activeEntry.workspaceIdentity,
                    ),
                )
            }
            if (relayWorkspaces.isEmpty()) {
                relayWorkspaces.add(
                    ServerWorkspaceInfo(
                        path = summary.workspacePath,
                        label = server.displayName,
                        workspaceIdentity = null,
                    ),
                )
            }
            val bridgeWorkspace = relayWorkspaces[0]
            progress = progress.copy(
                workspace = ConnectStepState(phase = ConnectStepState.Phase.done, meta = bridgeWorkspace.label ?: bridgeWorkspace.path),
            )
            log(ConnectLogLine.Kind.ok, "workspace-bridge · ${bridgeWorkspace.path} · 清单 ${relayWorkspaces.size} 个工作区")
            // iOS diag.ws.list UserDefaults 直写（:573-576）→ 连接日志承载（文件尾注）
            log(
                ConnectLogLine.Kind.info,
                "diag.ws.list · count=${relayWorkspaces.size} paths=[${relayWorkspaces.joinToString(" | ") { it.path }.take(400)}]",
            )
            val info = ServerRemoteInfo(
                serverId = "relay-${link.endpointHost ?: "zcode"}",
                name = server.displayName,
                version = summary.desktopAppVersion ?: "relay",
                protocolVersion = ServerRemoteInfo.expectedProtocolVersion,
                authRequired = false,
                workspaces = relayWorkspaces,
                capabilities = emptyList(),
            )
            serverInfo = info
            workspace = bridgeWorkspace

            // 桥内 v4 握手已由 onBridgeOpened 钩子在 openBridge 内完成（首连同路径）
            frameSubscription = subscribeFrameStreams(client)
            _state.value = ConnectionState.Connected(info, bridgeWorkspace)
            return ConnectResult.Success(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RPCError) {
            progress = markRunningStepFailed(progress)
            _state.value = ConnectionState.Connecting(progress)
            val connectError = mapRelayError(e)
            finishFailure(connectError)
            return ConnectResult.Failure(connectError)
        } catch (e: Throwable) {
            progress = markRunningStepFailed(progress)
            _state.value = ConnectionState.Connecting(progress)
            val connectError = ConnectError.Transport(e.message ?: e.toString())
            finishFailure(connectError)
            return ConnectResult.Failure(connectError)
        }
    }

    /** 在途步骤标记失败（iOS :660-666；ConnectProgress 不可变 → 逐位 copy）。 */
    private fun markRunningStepFailed(progress: ConnectProgress): ConnectProgress {
        var p = progress
        if (p.discover.phase == ConnectStepState.Phase.running) {
            p = p.copy(discover = p.discover.copy(phase = ConnectStepState.Phase.failed))
        }
        if (p.auth.phase == ConnectStepState.Phase.running) {
            p = p.copy(auth = p.auth.copy(phase = ConnectStepState.Phase.failed))
        }
        if (p.websocket.phase == ConnectStepState.Phase.running) {
            p = p.copy(websocket = p.websocket.copy(phase = ConnectStepState.Phase.failed))
        }
        if (p.handshake.phase == ConnectStepState.Phase.running) {
            p = p.copy(handshake = p.handshake.copy(phase = ConnectStepState.Phase.failed))
        }
        if (p.workspace.phase == ConnectStepState.Phase.running) {
            p = p.copy(workspace = p.workspace.copy(phase = ConnectStepState.Phase.failed))
        }
        return p
    }

    // MARK: 工作区切换（P3-10；C-10/C-11/C-12 对齐 web：bridge-open 重开 + mobile-view-state-update）

    /**
     * 中继工作区切换（web 同构，C-10）：**不前置 workspace-reconnect-request**——web 的
     * reconnect 仅用于「重连已断开的远程工作区」专用面（见 reconnectRelayWorkspace），
     * 与切换无关。切换 = ①按清单解析原始 workspaceKey（C-12 严格口径，未收录即失败，
     * 不再以 path 冒充 key）②桥重开到目标 key（三路 dynamic 事件与 workspace-config
     * 订阅重定向，失败回滚）③补发 mobile-view-state-update（C-11 切换场景；bridge-open
     * 成功场景已在 RelayChannelClient.openBridge 内发送）④workspace/清单 active 序更新。
     * 局域网直连无此消息面（iOS :677-713 直译）。
     */
    suspend fun switchRelayWorkspace(target: ServerWorkspaceInfo): WorkspaceSwitchResult {
        val relay = relayClient
        if (!isActive || relay == null || workspace == null) {
            return WorkspaceSwitchResult.Failure(ConnectError.Transport("工作区切换仅支持云中继连接"))
        }
        val current = workspace
        if (current != null && current.path == target.path) {
            return WorkspaceSwitchResult.Failure(ConnectError.Transport("目标已是当前工作区"))
        }
        // C-12：workspaceKey 一律取清单原始 key；未收录返回失败（非法 key 切换必败）
        val key = relay.resolvedWorkspaceKey(forPath = target.path, identity = target.workspaceIdentity)
        if (key == null) {
            log(ConnectLogLine.Kind.error, "目标工作区不在桌面清单（无原始 workspaceKey）· ${target.path}")
            return WorkspaceSwitchResult.Failure(ConnectError.Transport("目标工作区不在桌面清单"))
        }
        log(ConnectLogLine.Kind.working, "workspace-bridge-open（切换）→ ${target.path}")
        // 桥重开 + 订阅重定向；先清 workspace-config 重放缓存（旧工作区帧不重放给新
        // Store——重放面在 setFrameHandler 按 topic 精确匹配双保险）
        synchronized(routingLock) { workspaceConfigReplay.clear() }
        try {
            relay.switchBridgeWorkspace(workspaceKey = key, workspacePath = target.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 错误文本含 reason（切换在途的 workspace-bridge-error 帧经 C-14 收口后以
            // reason:error 组合上抛，不再被误判成功）。RPCError.message 原文上抛（非
            // toString/localizedDescription），失败提示链（AppSession → 切换器 hint）才有
            // 可行动原因（iOS :697-703 注同源）
            val detail = (e as? RPCError)?.message ?: e.message ?: e.toString()
            log(ConnectLogLine.Kind.error, "工作区桥切换失败 · $detail")
            return WorkspaceSwitchResult.Failure(ConnectError.Transport(detail))
        }
        resubscribeWorkspaceConfig(path = target.path)
        updateWorkspace(target)
        // C-11 场景③（切换工作区）：显式上报无任务的视图状态并清空本地 activeTaskId
        // （web 任务首页切换路径 `switchWorkspace` 后 `updateMobileViewState(key)` 同款
        // ——M(e,n) 的 n=undefined 会同时清掉模块态 c，重连落点随之不带任务）（:707-710）
        relay.updateActiveTask(null)
        log(ConnectLogLine.Kind.ok, "工作区已切换 · ${target.path} · 清单 ${serverInfo?.workspaces?.size ?: 0} 个")
        return WorkspaceSwitchResult.Success(target)
    }

    /**
     * 重连已断开的远程工作区（C-10 保留项；web 侧栏「重连已断开的远程工作区」按钮
     * 专用面，与切换流程解耦）：3 键形态 `{zcode_type, requestId, workspaceKey}`
     * （bundle 实证——不带 workspacePath/workspaceIdentity），响应按 requestId 配对；
     * `reason`/`error` 字符串或 ok=false 判失败（C-14 reason 识别）。移动端当前无该
     * UI 入口，保留协议面供断连工作区恢复场景接线（iOS :720-754；Swift Result →
     * kotlin.Result——ConnectError 即 Throwable，Failure 载荷同型）。
     */
    suspend fun reconnectRelayWorkspace(workspaceKey: String): Result<JSONValue> {
        val transport = relayTransport
        if (!isActive || transport == null) {
            return Result.failure(ConnectError.Transport("工作区重连仅支持云中继连接"))
        }
        return try {
            val response = transport.requestAppPayload(
                linkedMapOf(
                    "zcode_type" to JSONValue.Str("workspace-reconnect-request"),
                    "workspaceKey" to JSONValue.Str(workspaceKey),
                ),
                zcodeType = "workspace-reconnect-request",
                timeout = 8.seconds,
            )
            // 响应面拒绝形态（在途错误帧走 app-error/workspace-bridge-error，已被
            // RelayTransport 按 requestId reject 到 catch 分支）
            val reason = response["reason"]?.stringValue
            if (!reason.isNullOrEmpty()) {
                log(ConnectLogLine.Kind.error, "workspace-reconnect 被拒 · $reason")
                return Result.failure(ConnectError.HandshakeFailed("workspace-reconnect: $reason"))
            }
            val errorText = response["error"]?.stringValue
            if (!errorText.isNullOrEmpty()) {
                log(ConnectLogLine.Kind.error, "workspace-reconnect 被拒 · $errorText")
                return Result.failure(ConnectError.HandshakeFailed("workspace-reconnect: $errorText"))
            }
            if (response["ok"]?.boolValue == false) {
                log(ConnectLogLine.Kind.error, "workspace-reconnect 被拒（ok=false）")
                return Result.failure(ConnectError.HandshakeFailed("workspace-reconnect rejected"))
            }
            log(ConnectLogLine.Kind.ok, "workspace-reconnect 完成 · $workspaceKey")
            Result.success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RPCError) {
            log(ConnectLogLine.Kind.error, "workspace-reconnect 失败 · ${e.message}")
            Result.failure(mapRelayError(e))
        } catch (e: Throwable) {
            log(ConnectLogLine.Kind.error, "workspace-reconnect 失败 · ${e.message ?: e.toString()}")
            Result.failure(ConnectError.Transport(e.message ?: e.toString()))
        }
    }

    /**
     * C-11 场景②（打开任务）：向桌面上报当前查看的任务（web `updateMobileViewState`
     * 由活动任务变化 effect 触发）。仅云中继有此帧面（局域网直连静默跳过）；单向通知
     * 无响应等待，未 paired 仅记连接日志。调用面（会话/任务打开处）由视图与 Store 波次接线
     * （iOS :759-762；AGENTS §6 接线遗留：触发点在 ChatViewModel/视图打开会话处）。
     */
    suspend fun reportActiveTask(taskId: String?) {
        relayClient?.updateActiveTask(taskId)
    }

    /** 切换成功后的连接态工作区更新（iOS :767-774）。 */
    private fun updateWorkspace(target: ServerWorkspaceInfo) {
        workspace = target
        serverInfo?.let { info ->
            val list = info.workspaces.filter { it.path != target.path }.toMutableList()
            list.add(0, target)
            serverInfo = info.copy(workspaces = list)
        }
    }

    /** 工作区切换后的 workspace-config 重订（新 topic；iOS :779-808 直译）。 */
    private suspend fun resubscribeWorkspaceConfig(path: String) {
        val previousTopic: String?
        val previousSubscriptionId: String?
        synchronized(routingLock) {
            previousTopic = workspaceConfigTopicPath
            previousSubscriptionId = workspaceConfigSubscriptionId
            workspaceConfigTopicPath = null
            workspaceConfigSubscriptionId = null
        }
        val current = client ?: return
        if (previousTopic != null && previousSubscriptionId != null) {
            val unsubscribe = JSONObjectBuilder()
            unsubscribe.set("topic", previousTopic)
            unsubscribe.set("workspacePath", workspacePathFromConfigTopic(previousTopic!!))
            unsubscribe.set("subscriptionId", previousSubscriptionId)
            runCatching {
                current.call(
                    "zcode-agent", "unsubscribeWorkspaceConfigV4",
                    RPCValue.json(JSONValue.Obj(unsubscribe.fields)), timeout = 3.seconds,
                )
            }
        }
        val configTopic = "workspace-config/$path"
        val builder = JSONObjectBuilder()
        builder.set("topic", configTopic)
        builder.set("workspacePath", path)
        builder.set("runtimePolicy", "existing-only")
        val value = runCatching {
            current.call(
                "zcode-agent", "subscribeWorkspaceConfigV4",
                RPCValue.json(JSONValue.Obj(builder.fields)), timeout = 5.seconds,
            )
        }.getOrNull()
        val subscriptionId = value?.jsonValue?.get("subscriptionId")?.stringValue
        synchronized(routingLock) {
            workspaceConfigSubscriptionId = subscriptionId
            workspaceConfigTopicPath = configTopic
        }
        log(
            ConnectLogLine.Kind.ok,
            if (subscriptionId != null) "subscribeWorkspaceConfigV4 · 新工作区订阅完成"
            else "subscribeWorkspaceConfigV4 · 新工作区订阅未获回执（chips 走缺省展示）",
        )
    }

    /**
     * workspace-list-updated 推送（桌面端工作区清单变化）：宽容解析清单与
     * activeWorkspaceKey（result.workspaces | workspaces | 顶层数组多形态），active
     * 首位回写 serverInfo（切换器菜单经 StateFlow/重读联动刷新），再上抛 AppSession
     * 刷新任务聚合清单。activeWorkspaceKey 与本地当前工作区不一致时仅刷新清单不跟随
     * 切换（桌面侧动作不突袭打断移动端进行中的会话）。
     * C-15：菜单（serverInfo.workspaces）按 canBridge 过滤（active 首位恒保留——桥已
     * 在其上打开即为可桥事实）；完整清单仍上抛 AppSession（任务聚合不受门控影响，
     * bootstrap.tasks 才是跨工作区任务主源）（iOS :818-847 直译）。
     */
    private fun handleWorkspaceListUpdated(payload: JSONValue) {
        val result = payload["result"] ?: payload
        var listValue = result["workspaces"] ?: payload["workspaces"]
        if (listValue == null && result.arrayValue != null) listValue = result
        if (listValue == null && payload.arrayValue != null) listValue = payload
        val summaries = RelayChannelClient.parseWorkspaceSummaries(listValue)
        if (summaries.isEmpty()) {
            log(ConnectLogLine.Kind.info, "workspace-list-updated · 清单为空或形状未识别（忽略）")
            return
        }
        val activeKey = result["activeWorkspaceKey"]?.stringValue
            ?: payload["activeWorkspaceKey"]?.stringValue
        val ordered = summaries.toMutableList()
        if (activeKey != null) {
            val index = ordered.indexOfFirst {
                (it.path ?: it.workspaceKey) == activeKey || it.workspaceIdentity == activeKey
            }
            if (index > 0) {
                ordered.add(0, ordered.removeAt(index))
            }
        }
        val infos = ordered.map { workspaceInfoFrom(it) }
        // C-15：菜单只留可桥条目 + active 首位
        serverInfo = serverInfo?.copy(
            workspaces = ordered.mapIndexedNotNull { index, entry ->
                if (index == 0 || entry.canBridge) workspaceInfoFrom(entry) else null
            },
        )
        log(
            ConnectLogLine.Kind.ok,
            "workspace-list-updated · 清单 ${infos.size} 个工作区" +
                "（菜单 ${serverInfo?.workspaces?.size ?: 0} 个可桥）" +
                (activeKey?.let { " · activeKey=$it" } ?: ""),
        )
        onWorkspaceListUpdated?.invoke(infos)
    }

    /** 清单条目 → 连接态工作区描述（菜单/Store 装配共用映射；path 缺席以 workspaceKey 兜底）（iOS :850-855）。 */
    private fun workspaceInfoFrom(entry: RelayChannelClient.RelayWorkspaceSummary): ServerWorkspaceInfo =
        ServerWorkspaceInfo(
            path = entry.path ?: entry.workspaceKey,
            label = entry.name,
            workspaceIdentity = entry.workspaceIdentity,
        )

    /** 中继错误 → L3 连接错误映射（iOS :863-872）。 */
    private fun mapRelayError(error: RPCError): ConnectError =
        when (error.name) {
            "TimeoutError" -> ConnectError.Timeout(endpoint = "中继 auth/bridge")
            "RelayFailure", "RelayClosed" -> ConnectError.HandshakeFailed(error.message)
            else -> ConnectError.Transport(error.message)
        }

    // MARK: 取消连接 / 断开（L2 右上 ✕ / 底部取消：立即中断不留半开连接）

    fun cancelConnecting() {
        manuallyCancelled = true
        disconnect()
        _state.value = ConnectionState.Idle
    }

    fun disconnect() {
        manuallyCancelled = true
        teardownTransport()
        client = null
        relayTransport = null
    }

    private fun teardownTransport() {
        frameSubscription?.cancel()
        frameSubscription = null
        val subscriptions: List<EventSubscription>
        var configTopic: String? = null
        var configSubscriptionId: String? = null
        synchronized(routingLock) {
            frameAssemblers.clear()
            frameHandlers.clear()
            frameDropHandlers.clear()
            workspaceConfigReplay.clear()
            // 多路 conversation 帧面记账随之作废（残留会让重连后 ensureConversationFrameStream
            // 误判已挂、新连接上帧面缺失——真机重连场景的静默丢帧面）
            subscriptions = conversationFrameStreams.values.toList()
            conversationFrameStreams.clear()
            configTopic = workspaceConfigTopicPath
            configSubscriptionId = workspaceConfigSubscriptionId
            workspaceConfigTopicPath = null
            workspaceConfigSubscriptionId = null
        }
        subscriptions.forEach { it.cancel() }
        val currentClient = client
        scope.launch {
            // 退订 workspace-config（promise 面）需在连接仍存活时发出，随后再断开传输
            if (currentClient != null && configTopic != null) {
                val builder = JSONObjectBuilder()
                builder.set("topic", configTopic)
                builder.set("workspacePath", workspacePathFromConfigTopic(configTopic!!))
                if (configSubscriptionId != null) {
                    builder.set("subscriptionId", configSubscriptionId)
                }
                runCatching {
                    currentClient.call(
                        "zcode-agent", "unsubscribeWorkspaceConfigV4",
                        RPCValue.json(JSONValue.Obj(builder.fields)), timeout = 3.seconds,
                    )
                }
            }
            currentClient?.disconnect()
        }
    }

    /** "workspace-config/<workspacePath>" → workspacePath */
    private fun workspacePathFromConfigTopic(topic: String): String {
        if (!topic.startsWith("workspace-config/")) return topic
        return topic.removePrefix("workspace-config/")
    }

    private fun finishFailure(error: ConnectError) {
        teardownTransport()
        client = null
        _state.value = ConnectionState.Failed(error)
        log(ConnectLogLine.Kind.error, error.codeLine)
    }

    /** 连接日志写入（internal：Remote Store 的订阅诊断同源进 L2 面板） */
    fun log(kind: ConnectLogLine.Kind, text: String) {
        synchronized(logLock) {
            logs.add(ConnectLogLine(kind = kind, text = text))
            while (logs.size > 40) {
                logs.removeAt(0)
            }
        }
        // iOS DEBUG unified log（文本已脱敏）→ stdout 同口径，供 logcat/测试诊断
        println("[relay-log][$kind] $text")
    }

    // MARK: 帧流订阅（conversation / sessions-index / workspace-config 三路 dynamic 事件）

    /**
     * 确保指定 workspace 的 conversation 帧流已注册（幂等）。会话订阅前调用——
     * 订阅按归属 workspace 寻址（v1.17），帧面必须同 workspace（本方法）。
     * identity 型工作区必须携 identity（emitter 键 = identity || path，§11.2）。
     */
    override suspend fun ensureConversationFrameStream(workspacePath: String, workspaceIdentity: String?) {
        val current = client
        if (current == null || !isActive || workspacePath.isEmpty()) return
        val streamKey = if (!workspaceIdentity.isNullOrEmpty()) workspaceIdentity else workspacePath
        synchronized(routingLock) {
            if (conversationFrameStreams.containsKey(streamKey)) return
        }
        val arg = RPCValue.jsonObject {
            set("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrEmpty()) {
                set("workspaceIdentity", workspaceIdentity)
            }
        }
        // assemblerKey 按 workspace 分键：两路帧流各自重组（TopicWireFrameAssembler
        // 分片账本不跨流混用）；handler 仍按 frame.topic 精确匹配（routeFrame）
        val subscription = current.listen(
            "zcode-agent", "onDynamicConversationFrame", arg,
            handler = { payload ->
                routeFrame(payload, assemblerKey = "conversation/$streamKey", handlerKey = null)
            },
            onError = { error ->
                scope.launch {
                    log(ConnectLogLine.Kind.error, "conversation 帧流 EventError · $streamKey · ${error.message}")
                    // 服务端已终结该订阅；清记账使下次会话订阅前 ensure 幂等重挂
                    // （iOS ZCodeServerConnection.swift:1012-1019 同构）
                    synchronized(routingLock) { conversationFrameStreams.remove(streamKey) }
                }
            },
        )
        var superseded: EventSubscription? = null
        synchronized(routingLock) {
            superseded = conversationFrameStreams.put(streamKey, subscription)
        }
        // 幂等兜底：并发双挂时保先到、退后到（iOS MainActor 串行天然免疫，Kotlin 补齐）
        superseded?.cancel()
        log(ConnectLogLine.Kind.ok, "conversation 帧流已挂 · $streamKey")
    }

    /**
     * workspace 级下行帧流：三个 dynamic 事件共用通知面，按 topic 前缀分流；
     * 另注册 zcode-task.onError（固定事件）进连接日志供诊断。
     * 局域网与中继共用（中继路径 eventListen 载荷经 rpc-frame 透传，eventFire 结构不变）。
     * conversation 首路（连接 workspace）在此注册；其余 workspace 按
     * ensureConversationFrameStream 逐路补挂（P0-1）。
     */
    private suspend fun subscribeFrameStreams(client: RPCChannelTransport): EventSubscription {
        val wsPath = workspace?.path ?: ""
        val identity = workspace?.workspaceIdentity
        // dynamic 事件带参数：{workspacePath, workspaceIdentity?}（zcodeAgent.ts
        // WorkspaceTarget）。emitter 按 resolveWorkspaceKey = identity || path 键控
        // （zcodeAgentService.ts:1568-1596）——identity 型工作区必须携 identity，
        // 否则三路帧流挂 path 键收不到（2026-10-08 回归审核轮 §11.2）
        val arg = RPCValue.jsonObject {
            set("workspacePath", wsPath)
            if (!identity.isNullOrEmpty()) {
                set("workspaceIdentity", identity)
            }
        }
        val conversationSub = client.listen(
            "zcode-agent", "onDynamicConversationFrame", arg,
            handler = { payload ->
                routeFrame(payload, assemblerKey = "conversation", handlerKey = null)
            },
            onError = { error ->
                scope.launch {
                    log(ConnectLogLine.Kind.error, "conversation 帧流 EventError · ${error.message}")
                    synchronized(routingLock) { conversationFrameStreams.remove(wsPath) }
                }
            },
        )
        synchronized(routingLock) { conversationFrameStreams[wsPath] = conversationSub }
        val sessionsSub = client.listen(
            "zcode-agent", "onDynamicSessionsIndexFrame", arg,
            handler = { payload ->
                routeFrame(payload, assemblerKey = "sessions", handlerKey = "sessions-index")
            },
            onError = { error ->
                scope.launch { log(ConnectLogLine.Kind.error, "sessions-index 帧流 EventError · ${error.message}") }
            },
        )
        val configSub = client.listen(
            "zcode-agent", "onDynamicWorkspaceConfigFrame", arg,
            handler = { payload ->
                routeFrame(payload, assemblerKey = "workspace-config", handlerKey = "workspace-config")
            },
            onError = { error ->
                scope.launch { log(ConnectLogLine.Kind.error, "workspace-config 帧流 EventError · ${error.message}") }
            },
        )
        // zcode-task.onError：固定事件（无参），错误文本进连接日志面板
        val taskErrorSub = client.listen(
            "zcode-task", "onError", RPCValue.Undefined,
            handler = { payload ->
                val message = payload.jsonValue?.get("message")?.stringValue
                    ?: payload.stringValue
                    ?: "未知错误"
                scope.launch { log(ConnectLogLine.Kind.error, "zcode-task.onError · $message") }
            },
        )
        // workspace-config 订阅（promise 面）：runtimePolicy=existing-only，
        // 被动观察者禁止为订阅拉起 Agent（zcodeAgent.ts:527-529 注释口径）。
        // 服务端为 additive 演进面，旧版本不支持时订阅静默失败（chips 回退演示值）。
        // topic 键 = identity || path（同 sessions-index 口径，§11.2）
        val configKey = if (!identity.isNullOrEmpty()) identity else wsPath
        val configTopic = "workspace-config/$configKey"
        synchronized(routingLock) { workspaceConfigTopicPath = configTopic }
        val builder = JSONObjectBuilder()
        builder.set("topic", configTopic)
        builder.set("workspacePath", wsPath)
        if (!identity.isNullOrEmpty()) {
            builder.set("workspaceIdentity", identity)
        }
        builder.set("runtimePolicy", "existing-only")
        val value = runCatching {
            client.call(
                "zcode-agent", "subscribeWorkspaceConfigV4",
                RPCValue.json(JSONValue.Obj(builder.fields)), timeout = 5.seconds,
            )
        }.getOrNull()
        val subscriptionId = value?.jsonValue?.get("subscriptionId")?.stringValue
        synchronized(routingLock) { workspaceConfigSubscriptionId = subscriptionId }
        log(
            ConnectLogLine.Kind.ok,
            if (subscriptionId != null) "subscribeWorkspaceConfigV4 · workspace-config 订阅完成"
            else "subscribeWorkspaceConfigV4 · 服务端未提供（chips 走缺省展示）",
        )
        // 丢帧自愈：workspace-config assembler dropped → resyncWorkspaceConfigV4
        // （订阅面归 connection，resync 亦在此闭环；Store 仅消费投影）
        setFrameDropHandler(key = "workspace-config") {
            scope.launch { resyncWorkspaceConfig() }
        }
        return EventSubscription {
            conversationSub.cancel()
            sessionsSub.cancel()
            configSub.cancel()
            taskErrorSub.cancel()
        }
    }

    /** assembler dropped → resyncWorkspaceConfigV4（subscriptionId + base；无水位传 null 全量） */
    private suspend fun resyncWorkspaceConfig() {
        val subscriptionId = synchronized(routingLock) { workspaceConfigSubscriptionId }
        if (subscriptionId == null || client == null || !isActive) return
        val builder = JSONObjectBuilder()
        builder.set("subscriptionId", subscriptionId)
        builder.set("base", JSONValue.Null)
        runCatching {
            client?.call(
                "zcode-agent", "resyncWorkspaceConfigV4",
                RPCValue.json(JSONValue.Obj(builder.fields)), timeout = 5.seconds,
            )
        }
        log(ConnectLogLine.Kind.info, "resyncWorkspaceConfigV4 · 丢帧自愈已触发")
    }

    /**
     * 丢帧自愈 handler 查找：assemblerKey 精确命中；多路帧流的分键
     * （"conversation/&lt;path&gt;"）回退到基键 "conversation"——Store 只注册基键，
     * 若无回退则跨工作区会话（v1.23 补挂路的全部场景）丢帧自愈永不触发
     * （2026-10-08 回归审核轮 §11.1#5）。须在 routingLock 临界区内调用。
     */
    private fun dropHandlerLocked(forAssemblerKey: String): (() -> Unit)? {
        return frameDropHandlers[forAssemblerKey]
            ?: (if (forAssemblerKey.startsWith("conversation/")) frameDropHandlers["conversation"] else null)
    }

    /** iOS routeFrame 的非隔离前半（信封解析）+ MainActor hop → 单消费者串行派发。 */
    private fun routeFrame(payload: RPCValue, assemblerKey: String, handlerKey: String?) {
        val json = payload.jsonValue ?: return
        val envelope = TopicWireFrame.parse(json) ?: return
        frameDispatch.trySend(FrameDispatchUnit(envelope, assemblerKey, handlerKey))
    }

    /** 重组 → 丢帧自愈 → 按键分流（iOS routeFrame Task 体；单消费者协程内执行，帧序保序）。 */
    private fun dispatchFrame(unit: FrameDispatchUnit) {
        val dropCallbacks = ArrayList<() -> Unit>()
        val invocations = ArrayList<Pair<(V4TopicFrame) -> Unit, V4TopicFrame>>()
        synchronized(routingLock) {
            val assembler = frameAssemblers.getOrPut(unit.assemblerKey) { TopicWireFrameAssembler() }
            val logical = assembler.accept(unit.envelope)
            if (logical == null) {
                // 丢帧自愈：assembler 置位 dropped → 通知订阅方 resync（断线/换代/坏分片）
                if (assembler.consumeDroppedFlag()) {
                    dropHandlerLocked(unit.assemblerKey)?.let { dropCallbacks.add(it) }
                }
                return
            }
            if (assembler.consumeDroppedFlag()) {
                dropHandlerLocked(unit.assemblerKey)?.let { dropCallbacks.add(it) }
            }
            val frame = V4TopicFrame.parse(logical) ?: return
            // 会话索引帧：handlerKey 为事件别名（"sessions-index"），而订阅侧按完整 topic
            // （"sessions-index/<workspacePath>"）注册——两个键都尝试，否则快照帧被静默丢弃
            // （症状：连接成功但会话列表恒为空态；e2e 门禁第 1 轮 test06/test12 实证）。
            if (unit.handlerKey != null) {
                val handler = frameHandlers[unit.handlerKey] ?: frameHandlers[frame.topic]
                if (unit.assemblerKey == "workspace-config") {
                    // Store 注册 handler 前到达的快照/增量先缓存（整体替换语义，重放安全）
                    workspaceConfigReplay.add(frame)
                    if (workspaceConfigReplay.size > 32) {
                        workspaceConfigReplay.removeAt(0)
                    }
                }
                handler?.let { invocations.add(it to frame) }
            }
            // conversation 帧按 topic 前缀分流给 handlerKey="conversation/<id>"
            if (unit.handlerKey == null && frame.topic.startsWith("conversation/")) {
                frameHandlers[frame.topic]?.let { invocations.add(it to frame) }
            }
        }
        dropCallbacks.forEach { it() }
        invocations.forEach { (handler, frame) -> handler(frame) }
    }

    /**
     * 注册 logical 帧处理器（topic：conversation/<sessionId> 或 sessions-index/<workspaceId>）。
     * workspace-config topic 注册时重放缓存帧（连接即订阅、Store 晚注册不丢快照）。
     */
    override fun setFrameHandler(topic: String, handler: (V4TopicFrame) -> Unit) {
        var replay: List<V4TopicFrame> = emptyList()
        synchronized(routingLock) {
            frameHandlers[topic] = handler
            if (topic.startsWith("workspace-config/")) {
                replay = workspaceConfigReplay.toList()
                workspaceConfigReplay.clear()
            }
        }
        // 按 topic 精确匹配重放：工作区切换瞬间的旧工作区残帧不重放给新 Store
        //（缓存与重放本就单 workspace 同 topic，语义不变；P3-10 切换面加严）
        if (topic.startsWith("workspace-config/")) {
            replay.filter { it.topic == topic }.forEach(handler)
        }
    }

    override fun removeFrameHandler(topic: String) {
        synchronized(routingLock) { frameHandlers.remove(topic) }
    }

    /**
     * 注册 assembler 丢帧回调（key：conversation / sessions / workspace-config）。
     * 回调方（各 Store）保存订阅回执的 subscriptionId，据以发 resyncConversationV4 /
     * resyncSessionsIndexV4 / resyncWorkspaceConfigV4 恢复续流。
     */
    override fun setFrameDropHandler(key: String, handler: () -> Unit) {
        synchronized(routingLock) { frameDropHandlers[key] = handler }
    }

    fun removeFrameDropHandler(key: String) {
        synchronized(routingLock) { frameDropHandlers.remove(key) }
    }

    // MARK: channel RPC 门面

    /**
     * 纵深防御：所有 V4Wire/RPC 调用的唯一出口。连接态（真实服务器）下 directWrite
     * 分类（文件直写/回滚/仓库写/宿主与配置写）在此直接拦截——即使 UI 层有遗漏入口
     * 也不会触达服务端；消息/审批/停止/队列等桌面代执行命令放行。
     * mock 演示不经过本连接，演示态交互不受影响。
     * 握手自愈（2026-10-08 真机实据）：桥(重)开竞态下 assertReady 类调用可能仍撞
     * fault.connection.handshakeRequired（sess_e8677b05 rowsRange 实证——v1.17 单点
     * 握手未覆盖全部重建路径）——捕获后重做一次 v4 握手并原样重试一次，把「能发送、
     * 收不到」的握手形态在调用出口兜住（中继桥面专属；局域网无桥如实上抛）。
     */
    suspend fun call(
        channel: String,
        command: String,
        arg: RPCValue = RPCValue.Undefined,
        timeout: Duration = 30.seconds,
    ): RPCValue {
        val current = client
        if (current == null || !isActive) {
            throw RPCError(message = "未连接桌面端", name = "NotConnected")
        }
        val verdict = ReadOnlyGate.inspect(channel = channel, command = command, arg = arg)
        if (verdict.isBlocked) {
            val reason = verdict.reason ?: "直写类命令"
            synchronized(logLock) {
                blockedExecutionCalls.add(reason)
                while (blockedExecutionCalls.size > 20) {
                    blockedExecutionCalls.removeAt(0)
                }
            }
            log(ConnectLogLine.Kind.error, "边界拦截 · $reason")
            throw RPCError(message = "移动端边界：$reason 属手机直写面，不接", name = "ReadOnlyViolation")
        }
        try {
            return current.call(channel, command, arg, timeout = timeout)
        } catch (e: RPCError) {
            if (!e.isHandshakeRequired) throw e
            // 中继桥面专属自愈：重握手后重试一次（重试仍失败如实上抛）
            val relay = relayClient ?: throw e
            log(ConnectLogLine.Kind.info, "handshakeRequired · 重做 v4 握手后重试 $channel.$command")
            performRelayV4Handshake(relay)
            return current.call(channel, command, arg, timeout = timeout)
        }
    }

    suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue = RPCValue.Undefined,
        handler: (RPCValue) -> Unit,
        onError: ((RPCError) -> Unit)? = null,
    ): EventSubscription? {
        val current = client
        if (current == null || !isActive) return null
        return current.listen(channel, event, arg, handler = handler, onError = onError)
    }

    /** 曾连接后的真实传输中断 → 通知装配层切 .disconnected（横幅）。
     * 连接建立前的失败走 finishFailure/.Failed 分支，state 非 .connected，不触发。 */
    private fun notifyDroppedIfConnected(detail: String) {
        if (_state.value is ConnectionState.Connected) {
            onConnectionDropped?.invoke(detail)
        }
    }

    companion object {
        /** iOS Bundle.main 缺省兜底值（ZCodeServerConnection.swift:284）。 */
        const val DEFAULT_APP_VERSION = "1.0.0"

        /** OkHttp WebSocket 工厂（iOS URLSession.webSocketTask(with:) 对应物；测试可注入）。 */
        private val DEFAULT_TRANSPORT_FACTORY = WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
            OkHttpWebSocketTransport(url, timeoutMs, maximumMessageSize)
        }
    }
}

// MARK: 未从 iOS 原文移植的面（随对应里程碑/波次挂账）：
//   connectRelay 期 diag.ws.list / diag.bootstrap.tasks 的 UserDefaults 直写（iOS :573-576 /
//   :166-168）→ Android 以连接日志行承载（无 UserDefaults 面，AppSession.diagListener
//   体系归属装配层）。

// 辅助：token 段重建（步骤 3 与 probe 同参口径；companion 私有实现的模块内复用出口）。
private fun percentEncodeQueryValue(value: String): String {
    val sb = StringBuilder()
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c in '0'.code..'9'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        ) {
            sb.append(c.toChar())
        } else {
            sb.append("%02X".format(c))
        }
    }
    return sb.toString()
}

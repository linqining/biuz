package cn.biuz.android.relay

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.relay.RelayAuth
import cn.biuz.android.protocol.relay.RelayCloseReason
import cn.biuz.android.protocol.relay.RelayFrameAssembler
import cn.biuz.android.protocol.relay.RelayFrameCodec
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// 直译自 ios/ZCodeMobileApp/Sources/Services/Relay/RelayTransport.swift（928 行）。
// 上游核对（:protocol relay/RelayFrameCodec.kt 文件头同结论）：中继 rpc-frame 协议层不在
// 上游开源仓（/tmp/zcode-upstream 全无命中——属云中继服务端），形状权威 = iOS 原文 +
// 协议接口文档 §3.3【移植 bundle 逆向】。
//
// 机械适配（逐处注明）：
// - Swift actor → Mutex 临界区（ChannelClient.kt 同先例）：复合 check-then-act 与集合
//   变异一律锁内；挂起点（await/回调 handler/complete 之外的 send 即发即回）一律在锁外。
//   后缀 `Locked` = 须持锁调用（锁内禁挂起：socket.send 为非挂起回调式）。
// - CheckedContinuation → CompletableDeferred；看门狗/超时 Task → scope.launch + delay。
// - URLSessionWebSocketTask → [RelayTextSocket]（OkHttp 文本帧，OkHttpTextWebSocketTransport）；
//   iOS `.abnormalClosure` 关闭码 → 可发送的 CLOSE_GOING_AWAY(1001)。
// - ConnectLogLine.Kind 归 :connection（依赖方向 connection → relay 不可反向引用）→
//   本模块自带 [RelayLogKind]，:connection 逐值映射（RelayTransport.swift:70 的钩子参数）。
// - `String(localized:)` → 同串字面量（同 Models.kt 文件头约定）。
// - JSONValue.Int = 32 位值域（M1 残留 deviations 摘要）：client_ts 等毫秒时间戳走
//   [jsonMillis]（整值 double——JsonCodec 对整值 double 写整型字面量，线缆形态与
//   Swift .int 逐字节一致）；seq/messageSeq/bridgeGeneration 等小值协议量仍走 Int。
// - 终态粘滞适配（handleTransportFault）：iOS guard 仅挡 .closed；OkHttp 读循环在
//   close() 后必以「channel 关闭」回调（URLSession cancel 后 pending receive 是否回调
//   不可证），若照抄会让终态（AUTH_FAILED/KICKED）被读循环关闭事件复活重连——增加
//   `state is Failed → return` 使终态粘滞（enterTerminalFailure 语义不变）。
// - iOS `struct AppResponse` / `struct BridgeInfo`（:52-66）在本文件与全仓无消费点，
//   死代码不移植；workspace-bridge-ready 的桥信息由 RelayChannelClient 直接按 JSONValue 键读。
// - actor 不可重入差异：iOS handler/回调都在 actor 内联重入；Kotlin 锁不可重入——
//   锁内只登记回调引用，一律锁外 invoke（completedBytes 随 withLock 返回值带出）。

// MARK: - 连接日志级别（iOS ConnectLogLine.Kind 的 :relay 本地承载，:connection 逐值映射）

enum class RelayLogKind { ok, working, info, error }

// MARK: - 中继传输（_Vn 中继传输类移植：WS JSON 文本帧 + auth 握手 + 心跳/重连 + rpc-frame 通道）
//
// 与局域网直连的三层差异（逆向结论 framingDeltas）：
// ① WS 层是 JSON 文本帧（auth_*/pair_status_*/data/error），不是二进制 SocketProtocol 帧；
// ② 应用载荷走 data 帧 payload（bootstrap/bridge-open 等 app 请求 + rpc-frame 分片可靠层）；
// ③ rpc-frame 载荷 = serialize(header)+serialize(body) 纯 RPCSerialization 字节（无 13 字节头）。
// 心跳 10s+jitter≤2s、ack 看门狗 30s、waiting 30s 超时、指数退避 min(10s, 500ms·2^n)。

class RelayTransport(
    private val config: cn.biuz.android.protocol.relay.RelayLinkConfig,
    /**
     * 连接日志钩子（L2 面板；token/凭据一律不落日志）（RelayTransport.swift:70）。
     * iOS `nonisolated let`（RelayChannelClient.log 直呼）→ Kotlin 同模块 internal。
     */
    internal val onLog: (RelayLogKind, String) -> Unit,
    private val socketFactory: RelayTextSocketFactory =
        RelayTextSocketFactory { url, timeoutMs -> OkHttpTextWebSocketTransport(url, timeoutMs) },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    // MARK: 常量（bundle AN/jN/MN/NN + Mh）（:14-21）
    val heartbeatIntervalSeconds: Double = 10.0
    val heartbeatJitterMaxSeconds: Double = 2.0
    val heartbeatAckTimeoutSeconds: Double = 30.0
    val waitingTimeoutSeconds: Double = 30.0
    val deviceOfflineGraceSeconds: Double = 15.0
    val reconnectBaseMillis: Long = 500
    val reconnectMaxSeconds: Double = 10.0
    val maxReconnectAttempts = 6 // 浏览器端无限退避；移动端后台功耗上限（终态上抛由 UI 重试）

    // MARK: rpc-frame 通道常量（Vzn/f9）（:24-27）
    val saturationHighWaterMarkBytes = 1_000_000
    val saturationLowWaterMarkBytes = 256_000
    val replayBufferMaxBytes = 8 * 1024 * 1024
    val replayGraceSeconds: Double = 45.0

    /** Swift `enum RelayState`（:29-49；associated value → data class，余 data object）。 */
    sealed class RelayState {
        data object Idle : RelayState() // idle

        /** WS open → auth 握手中 */
        data object Connecting : RelayState() // authenticating

        /** auth_ack waiting（等桌面确认配对） */
        data object Waiting : RelayState() // waiting

        /** matched，可承载 app/rpc 帧 */
        data object Paired : RelayState() // paired

        data object Reconnecting : RelayState() // reconnecting

        data object Closed : RelayState() // closed

        data class Failed(val reason: RelayCloseReason) : RelayState()

        override fun toString(): String =
            when (this) {
                Idle -> "idle"
                Connecting -> "authenticating"
                Waiting -> "waiting"
                Paired -> "paired"
                Reconnecting -> "reconnecting"
                Closed -> "closed"
                is Failed -> "failed(${reason.codeText})"
            }
    }

    @Volatile
    private var state: RelayState = RelayState.Idle

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    @Volatile
    private var socket: RelayTextSocket? = null
    private var receiveJob: Job? = null

    // auth/pair 状态
    @Volatile
    private var hadPairedSession = false

    /**
     * socket 世代（每次建连 +1）：区分「同 socket 心跳 matched」与「重连 socket matched」，
     * 对齐 web 端 lastPairedSocketGeneration 语义（onSendReady 仅 reconnected-socket 触发）（:83-86）。
     */
    @Volatile
    private var socketGeneration = 0

    @Volatile
    private var lastPairedSocketGeneration = 0

    @Volatile
    private var terminalSid: String? = null

    /** Swift lastPairStatusAckAt（:88）存而不读（watchdog 以 ack 看门狗定时器承载），语义位保留。 */
    @Volatile
    private var lastPairStatusAckAt: Long? = null

    private var heartbeatJob: Job? = null
    private var ackWatchdogJob: Job? = null
    private var waitingTimeoutJob: Job? = null
    private var deviceOfflineJob: Job? = null

    // 重连
    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var intentionallyClosed = false
    private var reconnectLoopJob: Job? = null
    private val pairedWaiters = ArrayList<CompletableDeferred<Unit>>()

    // app 层请求-响应（requestId 键控）
    private val appRequestWaiters = HashMap<String, CompletableDeferred<JSONValue>>()

    /** bridge-ready 特殊：按 bridgeSessionId 匹配（单槽）（:105）。 */
    private var bridgeOpenWaiter: BridgeOpenWaiter? = null

    private class BridgeOpenWaiter(
        val bridgeSessionId: String,
        val continuation: CompletableDeferred<JSONValue>,
    )

    // rpc-frame 发送队列（Vzn 口径）
    @Volatile
    private var frameIdentity: RelayFrameCodec.Identity? = null

    private class OutboundBatch(
        val messageSeq: Int,
        val frames: List<Map<String, JSONValue>>,
        val outerBytes: Int,
        val queuedAt: Long,
        var nextFrameIndex: Int,
    )

    private val outboundBatches = ArrayList<OutboundBatch>()
    private var nextPhysicalSeq = 1
    private var nextMessageSeq = 1
    private var unacknowledgedByteCount = 0

    @Volatile
    private var saturated = false
    private val saturationWaiters = ArrayList<CompletableDeferred<Unit>>()

    /** Swift replayDeadline: Date? → epoch 毫秒 / null（Models.kt 文件头约定）。 */
    @Volatile
    private var replayDeadline: Long? = null

    @Volatile
    private var frameDegraded = false
    private val assembler = RelayFrameAssembler()

    /// 重组完成的下行 RPC 载荷（serialize(header)+serialize(body)）回调（:119）
    @Volatile
    private var rpcMessageHandler: ((ByteArray) -> Unit)? = null

    /// 通道终态上抛（终态错误 / 重试耗尽）回调（:121）
    @Volatile
    private var closedHandler: ((RelayCloseReason) -> Unit)? = null

    /// 重连恢复回调（曾配对过再次 matched；对齐 web 端 onSendReady(reconnected-socket)）（:165）
    @Volatile
    private var pairedRecoveryHandler: (() -> Unit)? = null

    /// 工作区清单推送回调（P3-10；连接层据此刷新切换器清单）（:171）
    @Volatile
    private var workspaceListUpdatedHandler: ((JSONValue) -> Unit)? = null

    /// 桥退化上抛（bridge-degraded / 无 requestId 的 workspace-bridge-error / replay 宽限超时，
    /// C-13）：RelayChannelClient 据此快速重建桥（对齐 web markDegraded → T(reason) 编排）（:127）
    @Volatile
    private var bridgeDegradedHandler: ((String) -> Unit)? = null

    // MARK: 生命周期

    /** 建连 + auth 握手至 paired（超时抛错）（:137-141）。 */
    suspend fun start(timeout: Duration) {
        mutex.withLock {
            intentionallyClosed = false
            setStateLocked(RelayState.Connecting)
        }
        openSocketAndAuthenticate(timeout)
    }

    /** 主动断开（dispose 语义：不再重连）（:144-154；iOS actor 同步方法 → suspend）。 */
    suspend fun close() {
        mutex.withLock {
            intentionallyClosed = true
            cancelTimersLocked()
            reconnectLoopJob?.cancel()
            reconnectLoopJob = null
            failAppWaitersLocked(RPCError(message = "中继连接已关闭", name = "ConnectionClosed"))
            failPairedWaitersLocked(RPCError(message = "中继连接已关闭", name = "ConnectionClosed"))
            socket?.close(RelayTextSocket.CLOSE_NORMAL, null)
            socket = null
            state = RelayState.Closed
        }
    }

    fun setRPCMessageHandler(handler: ((ByteArray) -> Unit)?) {
        rpcMessageHandler = handler
    }

    fun setClosedHandler(handler: ((RelayCloseReason) -> Unit)?) {
        closedHandler = handler
    }

    fun setPairedRecoveryHandler(handler: (() -> Unit)?) {
        pairedRecoveryHandler = handler
    }

    /// 工作区清单推送回调（P3-10；连接层据此刷新切换器清单）（:171-173）
    fun setWorkspaceListUpdatedHandler(handler: ((JSONValue) -> Unit)?) {
        workspaceListUpdatedHandler = handler
    }

    /// 桥退化回调（C-13；RelayChannelClient 在 connectRelay 时接线，先于任何桥建立）（:176-178）
    fun setBridgeDegradedHandler(handler: ((String) -> Unit)?) {
        bridgeDegradedHandler = handler
    }

    /** iOS `var desktopTerminalSid: String? { terminalSid }`（:180；锁外快照读，@Volatile）。 */
    val desktopTerminalSid: String?
        get() = terminalSid

    /**
     * 传输是否处于 paired（桥重建编排据以区分「传输失联等恢复」与「真失败走终态」）（:183）。
     * iOS actor 属性读即挂起 → Kotlin suspend。
     */
    suspend fun isPaired(): Boolean = mutex.withLock { state is RelayState.Paired }

    // MARK: WS + auth

    private suspend fun openSocketAndAuthenticate(timeout: Duration) {
        // iOS URLComponents：scheme 缺省补 wss（:191-196）；解析不出 scheme/host 即 RelayConfig
        val urlText = if (config.wssURL.contains("://")) config.wssURL else "wss://" + config.wssURL
        val scheme = urlText.substringBefore("://", "")
        if (scheme.isEmpty() || scheme != urlText.substringBefore(':')) {
            throw RPCError(message = "中继地址无效：${config.wssURL}", name = "RelayConfig")
        }
        socketGeneration += 1
        log(RelayLogKind.working, "WS 升级 ${urlText.substringAfter("//").substringBefore('/')}…?mid=…")
        val socket = try {
            socketFactory.create(urlText, SOCKET_CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw RPCError(message = "中继地址无效：${config.wssURL}", name = "RelayConfig")
        } catch (e: Throwable) {
            throw RPCError(message = "中继 WS 建立失败：${e.message ?: e.toString()}", name = "TransportError")
        }
        mutex.withLock {
            this.socket = socket
            // WS open 即发 auth_init（_Vn connect() 的 open 监听；meta 照 bundle 原样：
            // {platform:'web', version:<app_version|'web'>, name:'mobile-browser'}）（:205-218）
            sendJSONLocked(
                linkedMapOf(
                    "type" to JSONValue.Str("auth_init"),
                    "role" to JSONValue.Str("terminal"),
                    "device_sid" to JSONValue.Str(config.deviceSid),
                    "meta" to JSONValue.Obj(
                        linkedMapOf(
                            "platform" to JSONValue.Str("web"),
                            "version" to JSONValue.Str(config.desktopAppVersion ?: "web"),
                            "name" to JSONValue.Str("mobile-browser"),
                        ),
                    ),
                    "client_ts" to jsonMillis(System.currentTimeMillis()),
                ),
            )
            log(RelayLogKind.working, "auth_init · role=terminal · sid=${config.deviceSid.take(12)}…")
            // 读循环先行：auth_challenge 依赖它（:220-221）
            startReceiveLoopLocked(socket)
        }

        // auth 握手完成（paired 或 waiting）等待；waiting 态在此放行
        //（30s waitingTimer 负责超时终态），bootstrap 请求等待 paired 由调用方决定（:223-247）
        val waiter = CompletableDeferred<Unit>()
        try {
            val alreadyReleased = mutex.withLock {
                when (val current = state) {
                    is RelayState.Paired, is RelayState.Waiting -> true
                    is RelayState.Failed -> throw RPCError(message = current.reason.codeText, name = "RelayFailure")
                    is RelayState.Closed -> throw RPCError(message = "连接在鉴权前已关闭", name = "ConnectionClosed")
                    else -> {
                        pairedWaiters.add(waiter)
                        false
                    }
                }
            }
            if (!alreadyReleased) {
                try {
                    withTimeout(timeout) { waiter.await() }
                } catch (e: TimeoutCancellationException) {
                    throw RPCError(message = "中继鉴权超时（auth 握手无响应）", name = "TimeoutError")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            mutex.withLock {
                socket?.close(RelayTextSocket.CLOSE_NORMAL, null)
                failPairedWaitersLocked(error) // 清残留 waiter（超时/失败两路竞争收口）
            }
            throw error
        }
    }

    private fun failPairedWaitersLocked(error: Throwable) {
        val waiters = ArrayList(pairedWaiters)
        pairedWaiters.clear()
        for (waiter in waiters) waiter.completeExceptionally(error)
    }

    private fun resolvePairedWaitersLocked() {
        val waiters = ArrayList(pairedWaiters)
        pairedWaiters.clear()
        for (waiter in waiters) waiter.complete(Unit)
    }

    // MARK: 读循环（JSON 文本帧）

    /** 须持锁调用（receiveNext 的循环常驻臂；:264-288）。 */
    private fun startReceiveLoopLocked(socket: RelayTextSocket) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            var fault: Throwable? = null
            try {
                for (text in socket.incoming) {
                    handleText(text)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                fault = t
            }
            // 通道关闭/读错误 → handleTransportFault（iOS receive 完成句柄 .failure 分支；
            // 二进制帧分支在 OkHttpTextWebSocketTransport 层丢弃）
            handleTransportFault(
                fault ?: RPCError(message = "连接已关闭", name = "ConnectionClosed"),
            )
        }
    }

    private suspend fun handleText(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > RelayFrameCodec.maxPhysicalFrameBytes) {
            log(RelayLogKind.error, "下行信封超限（envelopeTooLarge）")
            return
        }
        val message = try {
            JSONValue.parse(text)
        } catch (e: Exception) {
            log(RelayLogKind.info, "忽略非 JSON 文本帧（${text.toByteArray(Charsets.UTF_8).size}B）")
            return
        }
        val type = message["type"]?.stringValue ?: return
        when (type) {
            "auth_challenge" -> handleAuthChallenge(message)
            "auth_ack", "pair_status_ack" -> {
                message["terminal_sid"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { terminalSid = it }
                applyPairStatus(message["pair_status"]?.stringValue ?: "")
            }
            "data" -> handleDataPayload(message["payload"])
            "error" -> handleRelayError(
                code = message["code"]?.stringValue ?: "",
                messageText = message["message"]?.stringValue ?: "",
            )
            else -> log(RelayLogKind.info, "忽略帧 type=$type")
        }
    }

    // MARK: auth 握手

    private suspend fun handleAuthChallenge(message: JSONValue) {
        val nonce = message["nonce"]?.stringValue
        if (nonce == null) {
            enterTerminalFailure(RelayCloseReason.InvalidMobileConnection)
            return
        }
        val proof = RelayAuth.proof(
            passHash = config.passHash,
            nonce = nonce,
            role = "terminal",
            deviceSid = config.deviceSid,
        )
        log(RelayLogKind.ok, "auth_challenge · nonce=${nonce.take(8)}… → auth_response")
        mutex.withLock {
            sendJSONLocked(
                linkedMapOf(
                    "type" to JSONValue.Str("auth_response"),
                    "device_sid" to JSONValue.Str(config.deviceSid),
                    "proof" to JSONValue.Str(proof),
                    "client_ts" to jsonMillis(System.currentTimeMillis()),
                ),
            )
        }
    }

    /** applyPairStatus（_Vn）：auth_ack/pair_status_ack 同路由（:338-371）。 */
    private suspend fun applyPairStatus(status: String) {
        val postLock = ArrayList<() -> Unit>()
        mutex.withLock {
            lastPairStatusAckAt = System.currentTimeMillis()
            armAckWatchdogLocked()
            when (status) {
                "waiting" -> {
                    if (hadPairedSession) {
                        // 曾经配对过：桌面短暂离线，保持心跳等 matched（stale 恢复由 watchdog 收口）（:344-348）
                        setStateLocked(RelayState.Waiting)
                        startHeartbeatLocked()
                        return@withLock
                    }
                    setStateLocked(RelayState.Waiting)
                    startHeartbeatLocked()
                    startWaitingTimerLocked()
                }
                "matched" -> {
                    reconnectAttempt = 0
                    val wasPairedBefore = hadPairedSession
                    val isSameSocket = lastPairedSocketGeneration == socketGeneration
                    hadPairedSession = true
                    lastPairedSocketGeneration = socketGeneration
                    setStateLocked(RelayState.Paired)
                    startHeartbeatLocked()
                    log(
                        RelayLogKind.ok,
                        "auth_ack · pair_status=matched" + (terminalSid?.let { " · terminal_sid=$it" } ?: ""),
                    )
                    resolvePairedWaitersLocked()
                    // 重连恢复仅在「曾配对过 + 新 socket」触发；同 socket 心跳 matched 不重建桥（:364-367）
                    if (wasPairedBefore && !isSameSocket) {
                        val handler = pairedRecoveryHandler
                        if (handler != null) postLock.add(handler)
                    }
                }
                else -> log(RelayLogKind.error, "未知 pair_status=$status")
            }
        }
        postLock.forEach { it() }
    }

    private suspend fun handleRelayError(code: String, messageText: String) {
        val reason = RelayCloseReason.from(errorCode = code, message = messageText)
        log(RelayLogKind.error, "error 帧 · code=$code $messageText")
        when (reason) {
            is RelayCloseReason.DeviceOffline -> {
                // 15s 宽限后重连（recoverFromDeviceOffline）（:377-384）
                mutex.withLock {
                    deviceOfflineJob?.cancel()
                    deviceOfflineJob = scope.launch {
                        delay(deviceOfflineGraceSeconds.toLongMilliseconds())
                        scheduleReconnectNow()
                    }
                }
            }
            // KICKED/AUTH_FAILED/WRONG_PARAM 与 4010/4012 类：终态（重连无法自愈）
            is RelayCloseReason.SessionConflict,
            is RelayCloseReason.InvalidMobileConnection,
            is RelayCloseReason.SessionNotFound,
            is RelayCloseReason.SessionExpired,
            is RelayCloseReason.DesktopDisconnected,
            is RelayCloseReason.WorkspaceClosed,
            -> enterTerminalFailure(reason)
            is RelayCloseReason.RelayUnavailable -> {
                // INTERNAL 等服务器错误：paired→waiting 等桌面，否则立即重连（:389-396）
                val shouldWait = mutex.withLock {
                    (state is RelayState.Paired) || (hadPairedSession && state is RelayState.Waiting)
                }
                if (shouldWait) {
                    mutex.withLock {
                        setStateLocked(RelayState.Waiting)
                        startHeartbeatLocked()
                    }
                } else {
                    scheduleReconnectNow()
                }
            }
        }
    }

    // MARK: data 帧（app 载荷 + rpc-frame）

    private suspend fun handleDataPayload(payload: JSONValue?) {
        val zcodeType = payload?.get("zcode_type")?.stringValue ?: return
        when (zcodeType) {
            "rpc-frame", "rpc-frame-ack" -> handleRelayFramePayload(payload)
            "bootstrap-response", "workspace-list-response", "platform-response",
            "workspace-reconnect-response",
            -> {
                // 带 requestId 的响应：经 waiter 配对（workspace-reconnect-response 属
                // 请求-响应面，reconnect 请求经 requestAppPayload 发出即在此配对回来）（:407-411）
                resolveAppRequest(payload)
            }
            "app-error", "workspace-bridge-error" -> {
                // web $2t.acceptPayload 首分支（bundle 实证）：带 requestId 的错误帧一律按
                // 失败 reject 对应 waiter（L9(reason,error) 形态）；无 requestId 的
                // workspace-bridge-error 按 bridgeSessionId 走桥退化（C-13/C-14）（:412-416）
                rejectErrorPayload(payload, zcodeType)
            }
            "bridge-degraded" -> {
                // 桌面宣告桥退化（无 requestId 主动推送；web 下行分发器按 bridgeSessionId
                // 匹配当前桥后 markDegraded 快速重建，C-13）（:417-420）
                handleBridgeDegradedFrame(payload, message = errorText(payload))
            }
            "workspace-list-updated" -> {
                // 桌面端主动推送（无 requestId，永远无 waiter）：从 resolveAppRequest 组
                // 单独拆出上抛，否则落入「app 响应无匹配 requestId（忽略）」被丢弃（P3-10）（:421-424）
                workspaceListUpdatedHandler?.invoke(payload)
            }
            "workspace-bridge-ready" -> resolveBridgeOpen(payload)
            else -> {
                // 新增 app 层请求-响应族（P1-1 附件上传会话 attachmentPut 等）响应类型名
                // 未取证：凡携 requestId 且有在途 waiter 的 data 帧一律按 requestId 配对
                // 回填（宽容形态，与 resolveAppRequest 同口径）；无 waiter 维持未消费日志（:427-435）
                val requestId = payload["requestId"]?.stringValue
                val hasWaiter = requestId != null && mutex.withLock { appRequestWaiters.containsKey(requestId) }
                if (hasWaiter) {
                    resolveAppRequest(payload)
                } else {
                    log(RelayLogKind.info, "data 帧 · zcode_type=$zcodeType（未消费）")
                }
            }
        }
    }

    /** 错误帧的 reason+error 文本合成（web L9(reason,error) / K9(reason,error) 同口径）（:441-445）。 */
    private fun errorText(payload: JSONValue): String {
        val reason = payload["reason"]?.stringValue ?: ""
        val error = payload["error"]?.stringValue ?: ""
        return listOf(reason, error).filter { it.isNotEmpty() }.joinToString(separator = ": ")
    }

    /** app-error / workspace-bridge-error 统一收口（C-14，:454-477）。 */
    private suspend fun rejectErrorPayload(payload: JSONValue, zcodeType: String) {
        val message = errorText(payload)
        val error = RPCError(
            message = message.ifEmpty { "$zcodeType 错误帧" },
            name = "RelayAppError",
            detail = payload,
        )
        val postLock = ArrayList<() -> Unit>()
        mutex.withLock {
            val requestId = payload["requestId"]?.stringValue
            val waiter = requestId?.let { appRequestWaiters.remove(it) }
            if (waiter != null) {
                log(RelayLogKind.error, "$zcodeType · requestId=${requestId!!.take(8)}… · $message")
                postLock.add { waiter.completeExceptionally(error) }
                return@withLock
            }
            val bridgeId = payload["bridgeSessionId"]?.stringValue
            val openWaiter = bridgeOpenWaiter
            if (bridgeId != null && openWaiter != null && openWaiter.bridgeSessionId == bridgeId) {
                bridgeOpenWaiter = null
                log(RelayLogKind.error, "$zcodeType · bridge=${bridgeId.take(8)}… · $message")
                postLock.add { openWaiter.continuation.completeExceptionally(error) }
                return@withLock
            }
            if (zcodeType == "workspace-bridge-error") {
                handleBridgeDegradedFrameLocked(payload, message = message)
            } else {
                log(RelayLogKind.error, "app-error（无在途 waiter）· $message")
            }
        }
        postLock.forEach { it() }
    }

    /** 桥退化统一入口（bridge-degraded 帧 / 无 requestId 的 workspace-bridge-error / replay 宽限超时）（:481-491）。 */
    private suspend fun handleBridgeDegradedFrame(payload: JSONValue, message: String) {
        mutex.withLock { handleBridgeDegradedFrameLocked(payload, message) }
    }

    /** 须持锁调用；bridgeSessionId 命中当前帧身份才生效（web 同款比对，异桥帧忽略）。 */
    private fun handleBridgeDegradedFrameLocked(payload: JSONValue, message: String) {
        val identity = frameIdentity
        if (identity == null || payload["bridgeSessionId"]?.stringValue != identity.bridgeSessionId) {
            log(RelayLogKind.info, "桥退化帧 bridgeSessionId 不匹配当前桥（忽略）")
            return
        }
        frameDegraded = true
        val reason = message.ifEmpty { "reason 未携带" }
        log(RelayLogKind.error, "bridge-degraded · $reason")
        bridgeDegradedHandler?.invoke(reason)
    }

    private suspend fun resolveAppRequest(payload: JSONValue) {
        val deferred = mutex.withLock {
            val requestId = payload["requestId"]?.stringValue ?: return@withLock null
            appRequestWaiters.remove(requestId)
        }
        if (deferred == null) {
            log(RelayLogKind.info, "app 响应无匹配 requestId（忽略）")
            return
        }
        deferred.complete(payload)
    }

    private suspend fun resolveBridgeOpen(payload: JSONValue) {
        val waiter = mutex.withLock {
            val current = bridgeOpenWaiter ?: return@withLock null
            val expected = payload["bridgeSessionId"]?.stringValue
            if (expected != null && expected != current.bridgeSessionId) return@withLock null
            bridgeOpenWaiter = null
            current
        } ?: return
        waiter.continuation.complete(payload)
    }

    private fun failAppWaitersLocked(error: Throwable) {
        val waiters = ArrayList(appRequestWaiters.values)
        appRequestWaiters.clear()
        for (waiter in waiters) waiter.completeExceptionally(error)
        bridgeOpenWaiter?.let {
            bridgeOpenWaiter = null
            it.continuation.completeExceptionally(error)
        }
    }

    /**
     * app 层请求（bootstrap-request / workspace-list-request 等），requestId 匹配响应。
     * bridgeSessionId 非空时走 bridge-ready 匹配面（桌面回包未必回带 requestId，
     * web 端口径按 bridgeSessionId 匹配；bridgeSessionId 必定回带——探针实测）（:524-551）。
     */
    suspend fun requestAppPayload(
        payload: Map<String, JSONValue>,
        zcodeType: String,
        timeout: Duration,
        bridgeSessionId: String? = null,
    ): JSONValue {
        val stateSnapshot = mutex.withLock { state }
        if (stateSnapshot !is RelayState.Paired) {
            throw RPCError(message = "中继未配对（state=$stateSnapshot）", name = "NotPaired")
        }
        val requestId = payload["requestId"]?.stringValue ?: UUID.randomUUID().toString()
        val request = LinkedHashMap(payload)
        request["requestId"] = JSONValue.Str(requestId)
        val waiter = CompletableDeferred<JSONValue>()
        mutex.withLock {
            if (bridgeSessionId != null) {
                bridgeOpenWaiter = BridgeOpenWaiter(bridgeSessionId, waiter)
            } else {
                appRequestWaiters[requestId] = waiter
            }
            sendJSONLocked(
                linkedMapOf(
                    "type" to JSONValue.Str("data"),
                    "payload" to JSONValue.Obj(request),
                    "client_ts" to jsonMillis(System.currentTimeMillis()),
                ),
            )
            log(RelayLogKind.working, "→ $zcodeType requestId=${requestId.take(8)}…")
        }
        // 超时兜底（iOS Task sleep + timeoutAppRequest 同构；bridgeOpenWaiter 单槽不按 requestId 移除）
        val timeoutJob = scope.launch {
            delay(timeout)
            timeoutAppRequest(requestId = requestId, zcodeType = zcodeType, bridgeSessionId = bridgeSessionId)
        }
        try {
            return waiter.await()
        } finally {
            timeoutJob.cancel()
        }
    }

    private suspend fun timeoutAppRequest(requestId: String, zcodeType: String, bridgeSessionId: String?) {
        val deferred = mutex.withLock {
            if (bridgeSessionId != null) {
                val waiter = bridgeOpenWaiter
                bridgeOpenWaiter = null
                waiter?.continuation
            } else {
                appRequestWaiters.remove(requestId)
            }
        }
        if (deferred == null) return
        log(RelayLogKind.error, "$zcodeType 响应超时")
        deferred.completeExceptionally(RPCError(message = "$zcodeType 响应超时", name = "TimeoutError"))
    }

    /**
     * app 层单向通知帧（web sendPayload 面，C-11）：mobile-view-state-update 无 requestId、
     * 不注册 waiter、不等待响应——用 requestAppPayload 会凭空挂 30s 超时 waiter，故单列（:568-579）。
     */
    suspend fun sendAppNotification(payload: Map<String, JSONValue>, zcodeType: String) {
        mutex.withLock {
            if (state !is RelayState.Paired) {
                log(RelayLogKind.info, "$zcodeType 未发送（state=$state，非 paired）")
                return
            }
            sendJSONLocked(
                linkedMapOf(
                    "type" to JSONValue.Str("data"),
                    "payload" to JSONValue.Obj(LinkedHashMap(payload)),
                    "client_ts" to jsonMillis(System.currentTimeMillis()),
                ),
            )
            log(RelayLogKind.working, "→ $zcodeType（单向通知）")
        }
    }

    // MARK: 心跳 / 看门狗

    private fun startHeartbeatLocked() {
        if (heartbeatJob != null) return
        armAckWatchdogLocked()
        heartbeatJob = scope.launch {
            while (true) {
                val current = mutex.withLock { state }
                if (current !is RelayState.Paired && current !is RelayState.Waiting) return@launch
                sendPairStatusQuery()
                // 桥本地退化监测（C-13 接线，原死代码）：45s 无 rpc-frame-ack 且尚有
                // 未确认批次 → 本地判退化（对齐 web 桥 Qi(timeout) 看门狗），上抛快速重建；
                // 桌面主动宣告走 bridge-degraded 帧，两路在 handleBridgeDegraded 汇合（:592-597）
                if (checkReplayDeadline()) {
                    notifyLocalBridgeDegraded()
                }
                val jitter = Random.nextDouble(0.0, heartbeatJitterMaxSeconds)
                delay((heartbeatIntervalSeconds + jitter).seconds)
            }
        }
    }

    /** checkReplayDeadline 判退化的上抛（帧身份在场才通知——未开桥时无退化语义）（:605-610）。 */
    private fun notifyLocalBridgeDegraded() {
        if (frameIdentity == null) return
        val reason = "rpc-frame 45s 无 ack（replay 宽限超时）"
        log(RelayLogKind.error, reason)
        bridgeDegradedHandler?.invoke(reason)
    }

    private fun stopHeartbeatLocked() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        ackWatchdogJob?.cancel()
        ackWatchdogJob = null
    }

    private fun armAckWatchdogLocked() {
        ackWatchdogJob?.cancel()
        ackWatchdogJob = scope.launch {
            delay(heartbeatAckTimeoutSeconds.seconds)
            handleAckWatchdogTimeout()
        }
    }

    private suspend fun handleAckWatchdogTimeout() {
        val shouldReconnect = mutex.withLock { state is RelayState.Paired || state is RelayState.Waiting }
        if (!shouldReconnect) return
        log(RelayLogKind.error, "30s 无 pair_status_ack · 判定 stale → 重连")
        reconnectAfterStale()
    }

    private fun startWaitingTimerLocked() {
        waitingTimeoutJob?.cancel()
        waitingTimeoutJob = scope.launch {
            delay(waitingTimeoutSeconds.seconds)
            enterTerminalFailure(
                RelayCloseReason.InvalidMobileConnection,
                logText = "waiting 30s 超时 · 桌面未确认配对",
            )
        }
    }

    private fun cancelTimersLocked() {
        stopHeartbeatLocked()
        waitingTimeoutJob?.cancel()
        waitingTimeoutJob = null
        deviceOfflineJob?.cancel()
        deviceOfflineJob = null
    }

    private suspend fun sendPairStatusQuery() {
        mutex.withLock {
            sendJSONLocked(
                linkedMapOf(
                    "type" to JSONValue.Str("pair_status_query"),
                    "device_sid" to JSONValue.Str(config.deviceSid),
                    "client_ts" to jsonMillis(System.currentTimeMillis()),
                ),
            )
        }
    }

    // MARK: 重连（指数退避 min(10s, 500ms·2^n)；重走 auth_init）

    private suspend fun handleTransportFault(error: Throwable) {
        var scheduleReconnect: Boolean
        var terminal: RelayCloseReason? = null
        mutex.withLock {
            // 终态粘滞适配见文件头：OkHttp 读循环 close 后必回调，终态不得被复活
            if (intentionallyClosed || state is RelayState.Closed || state is RelayState.Failed) {
                scheduleReconnect = false
                return@withLock
            }
            socket = null
            stopHeartbeatLocked()
            failPairedWaitersLocked(
                RPCError(message = "传输中断：${error.message ?: error.toString()}", name = "TransportError"),
            )
            log(RelayLogKind.error, "传输故障 · ${error::class.simpleName}: ${error.message ?: error.toString()}")
            if (hadPairedSession || reconnectAttempt > 0) {
                scheduleReconnect = true
            } else {
                terminal = RelayCloseReason.RelayUnavailable(error.message ?: error.toString())
                scheduleReconnect = false
            }
        }
        if (scheduleReconnect) {
            scheduleReconnect()
        }
        terminal?.let { enterTerminalFailure(it) }
    }

    private suspend fun reconnectAfterStale() {
        if (intentionallyClosed) return
        mutex.withLock {
            socket?.close(RelayTextSocket.CLOSE_GOING_AWAY, null)
            socket = null
            stopHeartbeatLocked()
            failPairedWaitersLocked(RPCError(message = "心跳超时重连", name = "StaleConnection"))
        }
        reopenWithBackoff()
    }

    private suspend fun scheduleReconnect() {
        mutex.withLock {
            reconnectLoopJob?.cancel()
            reconnectLoopJob = scope.launch { reopenWithBackoff() }
        }
    }

    private suspend fun scheduleReconnectNow() {
        mutex.withLock {
            reconnectLoopJob?.cancel()
            reconnectLoopJob = scope.launch { reopenWithBackoff(initialDelayMs = 0) }
        }
    }

    private suspend fun reopenWithBackoff(initialDelayMs: Long? = null) {
        if (intentionallyClosed) return
        mutex.withLock { setStateLocked(RelayState.Reconnecting) }
        var firstAttempt = true
        while (!intentionallyClosed && reconnectAttempt < maxReconnectAttempts) {
            var delayMs = min(
                (reconnectMaxSeconds * 1000).toLong(),
                reconnectBaseMillis * (1L shl min(reconnectAttempt, 10)),
            )
            if (firstAttempt && initialDelayMs != null) {
                delayMs = initialDelayMs
            }
            firstAttempt = false
            reconnectAttempt += 1
            log(RelayLogKind.info, "第 $reconnectAttempt 次重连 · ${"%.1f".format(delayMs / 1000.0)}s 后")
            delay(delayMs)
            try {
                openSocketAndAuthenticate(15.seconds)
                val current = mutex.withLock { state }
                if (current is RelayState.Paired || current is RelayState.Waiting) {
                    return // 重连成功；桥重建由 RelayChannelClient.onPaired 编排（:719-721）
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(RelayLogKind.info, "重连失败：${e.message ?: e.toString()}")
            }
        }
        if (!intentionallyClosed) {
            enterTerminalFailure(RelayCloseReason.RelayUnavailable("重试 $maxReconnectAttempts 次仍失败"))
        }
    }

    private suspend fun enterTerminalFailure(reason: RelayCloseReason, logText: String? = null) {
        var closeHandler: ((RelayCloseReason) -> Unit)? = null
        mutex.withLock {
            if (state is RelayState.Closed || state == RelayState.Failed(reason)) return
            cancelTimersLocked()
            reconnectLoopJob?.cancel()
            reconnectLoopJob = null
            failAppWaitersLocked(RPCError(message = reason.codeText, name = "RelayFailure"))
            failPairedWaitersLocked(RPCError(message = reason.codeText, name = "RelayFailure"))
            socket?.close(RelayTextSocket.CLOSE_NORMAL, null)
            socket = null
            state = RelayState.Failed(reason)
            log(RelayLogKind.error, logText ?: "中继终态 · ${reason.codeText}")
            closeHandler = closedHandler
        }
        closeHandler?.invoke(reason)
    }

    private fun setStateLocked(newState: RelayState) {
        if (state == newState) return
        state = newState
    }

    // MARK: 发送

    /** 须持锁调用（sendJSON，:756-766；失败回调走 handleTransportFault）。 */
    private fun sendJSONLocked(objectValue: Map<String, JSONValue>) {
        val socket = socket ?: return
        val text = JSONValue.Obj(LinkedHashMap(objectValue)).data?.toString(Charsets.UTF_8) ?: return
        socket.send(text) { error ->
            scope.launch { handleTransportFault(error) }
        }
    }

    // MARK: rpc-frame 通道（Vzn 口径：分片 + ack + 水位 + degraded）

    /** 开桥成功后绑定帧身份并重置发送队列（:771-782）。 */
    suspend fun bindFrameChannel(identity: RelayFrameCodec.Identity) {
        mutex.withLock {
            frameIdentity = identity
            outboundBatches.clear()
            nextPhysicalSeq = 1
            nextMessageSeq = 1
            unacknowledgedByteCount = 0
            saturated = false
            frameDegraded = false
            replayDeadline = null
            assembler.reset()
            resumeSaturationWaitersLocked()
        }
    }

    suspend fun unbindFrameChannel() {
        mutex.withLock {
            frameIdentity = null
            outboundBatches.clear()
            unacknowledgedByteCount = 0
            saturated = false
            frameDegraded = false
            resumeSaturationWaitersLocked()
        }
    }

    /** iOS `var isFrameChannelDegraded: Bool { frameDegraded || frameIdentity == nil }`（:793）。 */
    val isFrameChannelDegraded: Boolean
        get() = frameDegraded || frameIdentity == null

    /** 发送一条 RPC 载荷（serialize(header)+serialize(body)）；饱和时挂起等待水位回落（:796-836）。 */
    suspend fun sendRPCMessage(bytes: ByteArray) {
        mutex.withLock {
            if (frameIdentity == null || frameDegraded || state !is RelayState.Paired) {
                throw RPCError(message = "rpc-frame 通道不可用", name = "ChannelUnavailable")
            }
        }
        // 饱和水位：高 1MB 暂停，低 256KB 恢复
        while (true) {
            val deferred: CompletableDeferred<Unit>? = mutex.withLock {
                if (saturated) {
                    val waiter = CompletableDeferred<Unit>()
                    saturationWaiters.add(waiter)
                    waiter
                } else {
                    null
                }
            }
            if (deferred == null) break
            deferred.await()
            val stillUsable = mutex.withLock {
                frameIdentity != null && !frameDegraded && state is RelayState.Paired
            }
            if (!stillUsable) {
                throw RPCError(message = "rpc-frame 通道不可用", name = "ChannelUnavailable")
            }
        }
        mutex.withLock {
            val identity = frameIdentity
            if (identity == null || frameDegraded || state !is RelayState.Paired) {
                throw RPCError(message = "rpc-frame 通道不可用", name = "ChannelUnavailable")
            }
            val frames = RelayFrameCodec.encodeMessage(
                bytes,
                identity = identity,
                firstPhysicalSeq = nextPhysicalSeq,
                messageSeq = nextMessageSeq,
            ) ?: throw RPCError(message = "RPC 载荷超限（maxMessageBytes=16MB）", name = "MessageTooLarge")
            // 外层信封字节精确测量（每帧 JSON 文本 UTF8 长度；常规路径仅 1 片）（:815-818）
            var outerBytes = 0
            for (frame in frames) {
                outerBytes += JSONValue.Obj(LinkedHashMap(frame)).data?.size ?: 0
            }
            if (unacknowledgedByteCount + outerBytes > replayBufferMaxBytes) {
                // replayBufferExceeded：degraded → 整桥重建（由 RelayChannelClient 编排）（:819-824）
                frameDegraded = true
                log(RelayLogKind.error, "replay 缓冲超限（>8MB）· 通道 degraded")
                throw RPCError(message = "replay 缓冲超限", name = "Degraded")
            }
            val messageSeq = nextMessageSeq
            outboundBatches.add(OutboundBatch(messageSeq, frames, outerBytes, System.currentTimeMillis(), 0))
            unacknowledgedByteCount += outerBytes
            nextMessageSeq += 1
            nextPhysicalSeq += frames.size
            if (!saturated && unacknowledgedByteCount > saturationHighWaterMarkBytes) {
                saturated = true
                log(RelayLogKind.info, "发送饱和（未 ack >1MB）· 暂停发送")
            }
            replayDeadline = System.currentTimeMillis() + replayGraceSeconds.toLongMilliseconds()
            flushOutboundLocked()
        }
    }

    /** 须持锁调用（:838-848）。 */
    private fun flushOutboundLocked() {
        if (state !is RelayState.Paired || frameDegraded) return
        for (batch in outboundBatches) {
            while (batch.nextFrameIndex < batch.frames.size) {
                val frame = batch.frames[batch.nextFrameIndex]
                sendJSONLocked(
                    linkedMapOf(
                        "type" to JSONValue.Str("data"),
                        "payload" to JSONValue.Obj(LinkedHashMap(frame)),
                        "client_ts" to jsonMillis(System.currentTimeMillis()),
                    ),
                )
                batch.nextFrameIndex += 1
            }
        }
    }

    private suspend fun handleRelayFramePayload(payload: JSONValue) {
        // 上行 ack（对我发送帧的确认）（:852-855）
        val ackMessageSeq = RelayFrameCodec.decodeAck(payload)
        if (ackMessageSeq != null) {
            processAck(ackMessageSeq)
            return
        }
        val identity = frameIdentity ?: run {
            // iOS Lzn 同口径静默丢弃的可见化（直译行为不变，仅日志）：bind 前窗口的
            // rpc-frame 无法对桥身份——真桌面 Initialize 在 ready 后一个 RTT 到达不触发；
            // 触发即「ready 已收而桥未绑定」时序证据（联调分诊口）
            log(RelayLogKind.info, "rpc-frame 早于桥身份绑定到达（丢弃）")
            return
        }
        // 桥身份三元组校验（Lzn）（:857-861）
        if (payload["bridgeSessionId"]?.stringValue != identity.bridgeSessionId ||
            payload["bridgeGeneration"]?.intValue?.let { it != identity.bridgeGeneration } == true
        ) {
            log(RelayLogKind.info, "忽略异桥 rpc-frame（identity 不匹配）")
            return
        }
        val fragment = RelayFrameCodec.decode(payload)
        if (fragment == null) {
            log(RelayLogKind.error, "rpc-frame 解析失败 · invalidPayload")
            return
        }
        // handler 锁外调用（回调可能重入：RelayChannelClient.handleRPCPayload → call 解析）；
        // 重组完成的字节随 withLock 返回值带出（iOS `case .completed(let bytes, …)` 枚举携带值的等价承载）
        var handler: ((ByteArray) -> Unit)? = null
        var completedBytes: ByteArray? = null
        mutex.withLock {
            when (val result = assembler.accept(fragment)) {
                is RelayFrameAssembler.AcceptResult.Incomplete ->
                    result.ackMessageSeq?.let { sendAckLocked(it) }
                is RelayFrameAssembler.AcceptResult.Duplicate -> sendAckLocked(result.ackMessageSeq)
                is RelayFrameAssembler.AcceptResult.Fault -> {
                    log(RelayLogKind.error, "下行帧丢弃 · ${result.reason}")
                    sendAckLocked(fragment.messageSeq) // 防桌面端无限重传（:874）
                }
                is RelayFrameAssembler.AcceptResult.Completed -> {
                    sendAckLocked(result.messageSeq)
                    completedBytes = result.data
                    handler = rpcMessageHandler
                }
            }
        }
        handler?.invoke(completedBytes ?: return)
    }

    private fun sendAckLocked(ackMessageSeq: Int) {
        val identity = frameIdentity ?: return
        sendJSONLocked(
            linkedMapOf(
                "type" to JSONValue.Str("data"),
                "payload" to JSONValue.Obj(LinkedHashMap(RelayFrameCodec.makeAck(identity, ackMessageSeq))),
                "client_ts" to jsonMillis(System.currentTimeMillis()),
            ),
        )
    }

    private suspend fun processAck(ackMessageSeq: Int) {
        mutex.withLock {
            var drained = false
            while (true) {
                val first = outboundBatches.firstOrNull() ?: break
                if (first.messageSeq > ackMessageSeq) break
                unacknowledgedByteCount = maxOf(0, unacknowledgedByteCount - first.outerBytes)
                outboundBatches.removeAt(0)
                drained = true
            }
            if (drained) {
                replayDeadline =
                    if (outboundBatches.isEmpty()) null
                    else System.currentTimeMillis() + replayGraceSeconds.toLongMilliseconds()
                if (saturated && unacknowledgedByteCount <= saturationLowWaterMarkBytes) {
                    saturated = false
                    log(RelayLogKind.ok, "发送水位回落（<256KB）· 恢复发送")
                    resumeSaturationWaitersLocked()
                }
            }
        }
    }

    private fun resumeSaturationWaitersLocked() {
        val waiters = ArrayList(saturationWaiters)
        saturationWaiters.clear()
        for (waiter in waiters) waiter.complete(Unit)
    }

    /** degraded 监测：45s 无 ack / 重放宽限超时 → 抛给上层重建桥（:915-923）。 */
    suspend fun checkReplayDeadline(): Boolean {
        return mutex.withLock {
            if (frameDegraded) return@withLock false
            val deadline = replayDeadline ?: return@withLock false
            if (System.currentTimeMillis() > deadline && outboundBatches.isNotEmpty()) {
                frameDegraded = true
                log(RelayLogKind.error, "45s 未收到 rpc-frame-ack · 通道 degraded")
                true
            } else {
                false
            }
        }
    }

    private fun log(kind: RelayLogKind, text: String) {
        onLog(kind, text)
    }

    companion object {
        /** iOS urlSession configuration.timeoutIntervalForRequest = 15（:76）。 */
        const val SOCKET_CONNECT_TIMEOUT_MS = 15_000L
    }

    /** Double 秒 → 毫秒（TimeInterval 承载；常量全为整值秒，无损）。 */
    private fun Double.toLongMilliseconds(): Long = (this * 1000.0).toLong()

    /** 毫秒时间戳 → JSON 数值（整值 double；线缆整型字面量与 Swift .int 一致，见文件头注）。 */
    private fun jsonMillis(ms: Long): JSONValue = JSONValue.Double(ms.toDouble())
}

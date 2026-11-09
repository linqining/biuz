package cn.biuz.android.relay

import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCSerialization
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.ReadCursor
import cn.biuz.android.protocol.RequestType
import cn.biuz.android.protocol.ResponseType
import cn.biuz.android.protocol.relay.RelayCloseReason
import cn.biuz.android.protocol.relay.RelayFrameCodec
import cn.biuz.android.protocol.relay.RelayLinkConfig
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// 直译自 ios/ZCodeMobileApp/Sources/Services/Relay/RelayChannelClient.swift（721 行）。
// 上游核对（RelayTransport.kt 文件头同结论）：中继桥协议层不在上游开源仓，形状权威 =
// iOS 原文 + 协议接口文档 §3.3【移植 bundle 逆向】；RPCChannelTransport 门面按 M1 预置
// 于 :protocol ChannelClient.kt（勿重复定义），本类直接实现。
//
// 机械适配（逐处注明）：
// - Swift actor → Mutex 临界区（ChannelClient.kt / RelayTransport.kt 同先例）；后缀
//   `Locked` = 须持锁调用；挂起点一律锁外；回调（onBridgeOpened/eventHandlers/onClosed）
//   锁内只登记引用、锁外 invoke。
// - CheckedContinuation → CompletableDeferred；超时看门狗 → withTimeout /
//   scope.launch + delay。
// - iOS 下行 RPC 载荷 `Task { await self?.handleRPCPayload(bytes) }`（无序 Task）→
//   单消费者 Channel 串行派发（比 iOS 更强的保序，方向安全——A8/A9 水位依赖帧序，
//   ZCodeServerConnection frameDispatch 同先例）。
// - `String(localized:)` → 同串字面量（同 Models.kt 文件头约定）；client_ts/updatedAt
//   毫秒时间戳走 JSONValue.Double 整值（线缆整型字面量与 Swift .int 一致，
//   RelayTransport.kt 文件头注同源）。
// - mobile-view-state-update 的 deviceInfo.platform：iOS 如实报 "ios"（值域未取证，
//   AGENTS §6 未决⑫），Android 如实报 "android"——两态均属未取证词表，viewState 不生效
//   首查此值（deviation 挂账）。
// - iOS `UUID().uuidString.hexString`（:716-721）→ UUID 去连字符小写（同函数）。

// MARK: - 中继 RPC 通道（桥内标准 RPC；载荷无 13 字节 SocketProtocol 头）

/**
 * 连接编排（逆向结论 handshake 帧序）：
 * WS open → auth_init → auth_challenge → auth_response → auth_ack(matched)
 * → bootstrap-request → workspace-list-request → workspace-bridge-open → workspace-bridge-ready
 * → 桥内 rpc-frame 承载标准 RPC，等待桌面下行 Initialize([200]) 即就绪。
 * 断线由 RelayTransport 指数退避重连+重走 auth；paired 恢复后重建桥（bridgeGeneration 递增
 * 并携带上次 recoveryId），pending RPC 全部失败由 Store 层 resync 自愈。
 */
class RelayChannelClient(
    private val transport: RelayTransport,
    private val link: RelayLinkConfig,
    private val appVersion: String,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RPCChannelTransport {

    enum class State {
        Uninitialized,
        Idle,
        Closed,
        ;

        /** Swift CustomStringConvertible 同口径（错误文案内插 "state=uninitialized" 等）。 */
        override fun toString(): String = name.lowercase()
    }

    /** connectRelay 成功摘要（ZCodeServerConnection 五步日志与伪 server-info 用）（:38-51）。 */
    data class ConnectSummary(
        val desktopAppVersion: String?,
        val bridgeKind: String?,
        val workspacePath: String,
        val workspaceKey: String?,
        val initialTaskId: String?,
        val recoveryId: String?,
        val sessionCount: Int,
        /**
         * workspace-list-request 返回的全部工作区（2026-10-06 web /remote/v4 页面同源取证：
         * 桌面对配对客户端回 workspace-list-response{result:{workspaces[], activeWorkspaceKey}}；
         * 首位 = active（与桥一致），其余为桌面打开的其它工作区——多 workspace 枚举源）
         */
        val workspaces: List<RelayWorkspaceSummary>,
    )

    /**
     * workspace-list-response 的条目（宽容解析：键 workspaceKey|workspaceIdentity|path 逐级
     * 取原始 key；身份/名/kind 可选——kind/remoteSessionId 为 canBridge 门控字段，C-15）（:53-74）。
     */
    data class RelayWorkspaceSummary(
        val workspaceKey: String,
        val path: String?,
        val workspaceIdentity: String?,
        val name: String?,
        val kind: String?,
        val remoteSessionId: String?,
    ) {
        /**
         * C-15 canBridge 门控（web 同款判别，bundle 实证）：
         * `kind!=='remote' || !!(workspaceIdentity && remoteSessionId)`
         * ——本地工作区恒可桥；remote 工作区需 identity+remoteSessionId 双全（远端会话
         * 在场才可开桥，否则 web 侧退 home-only 不开桥）。kind 缺席视为非 remote
         * （web 对 undefined!==`remote` 同判真，旧桌面不携带 kind 时行为不变）。
         */
        val canBridge: Boolean
            get() {
                if (kind != "remote") return true
                val hasIdentity = !workspaceIdentity.isNullOrEmpty()
                val hasSession = !remoteSessionId.isNullOrEmpty()
                return hasIdentity && hasSession
            }
    }

    @Volatile
    var state: State = State.Uninitialized
        private set

    private val mutex = Mutex()
    private var lastRequestId = 0
    private val pendingResponses = HashMap<Int, CompletableDeferred<RPCValue>>()
    private val initializeWaiters = ArrayList<CompletableDeferred<Unit>>()
    private val eventHandlers = HashMap<Int, (RPCValue) -> Unit>()

    /** 活跃订阅参数表（桥重建后在新区重发，恢复 sessions-index/conversation 推流）（:82）。 */
    private class ActiveListener(
        val channel: String,
        val event: String,
        var arg: RPCValue,
    )

    private val activeEventListeners = LinkedHashMap<Int, ActiveListener>()

    @Volatile
    private var onClosed: ((Throwable?) -> Unit)? = null

    /**
     * 桥(重)开完成钩子（ZCodeServerConnection 注入：桥内 v4 握手）。每次 openBridge
     * ——首连/断线恢复重建/degraded 快速重建/工作区切换——桌面侧都是全新 scoped facade，
     * 握手态从零开始【实证·上游仓 zcodeAgentConnectionScope：`handshakeComplete` 为
     * facade 实例态，仅 `initializeConversationV4(clientHello)` 置真；真机 2026-10-07
     * 18:33-18:34 桥重开后 subscribe*、readSession、rowsRange 全线
     * `fault.connection.handshakeRequired` 风暴即此因——桥重开必须重握手】。
     * 在 waitForInitialize 之后、resendActiveEventListeners 之前调用；参数传 self
     * （桥传输本体），注入方弱持有连接、不自造引用环（:84-92）。
     */
    @Volatile
    private var onBridgeOpened: (suspend (RPCChannelTransport) -> Unit)? = null

    /** 桥(重)开钩子注入（actor 属性隔离边界：外部经此设置）（:95-97）。 */
    fun setOnBridgeOpened(handler: (suspend (RPCChannelTransport) -> Unit)?) {
        onBridgeOpened = handler
    }

    @Volatile
    private var desktopAppVersion: String? = null

    @Volatile
    var initialViewState: JSONValue? = null
        private set

    @Volatile
    var taskListJSON: JSONValue? = null
        private set

    @Volatile
    var activeWorkspaceKey: String? = null
        private set

    @Volatile
    var activeTaskId: String? = null
        private set

    /** 首次连接成功的摘要（重连重建后仍可读）（:122）。 */
    @Volatile
    var summary: ConnectSummary? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    // 桥状态
    private var bridgeGeneration = 0
    private var lastRecoveryId: String? = null
    private var bridgeIdentity: RelayFrameCodec.Identity? = null

    /** 桥重建互斥（degraded 快速重建 / paired 恢复重建两路汇入，防并发 openBridge 互踩单槽 bridgeOpenWaiter）（:109-111）。 */
    @Volatile
    private var bridgeRebuildInFlight = false

    /**
     * 切换互斥（并发切换串行守卫）：bridgeRebuildInFlight 只挡重建两路汇入，挡不住
     * 并发 switchBridgeWorkspace 互踩单槽 bridgeOpenWaiter——切换事务未完成时第二笔
     * 直接拒绝（AppSession.switchWorkspace 同口径拦截并提示，纵深防御）（:112-115）。
     */
    @Volatile
    private var switchInFlight = false

    /** workspace-list-request 的全部工作区（连接期采集，summary 携带给装配层）（:102-103）。 */
    @Volatile
    private var workspaceSummaries: List<RelayWorkspaceSummary> = emptyList()

    // MARK: 连接编排

    /** 全链路：transport 鉴权 → bootstrap → bridge-open → 等桌面 Initialize（:134-195）。 */
    suspend fun connectRelay(
        timeout: Duration,
        onClosed: (Throwable?) -> Unit,
    ): ConnectSummary {
        this.onClosed = onClosed
        transport.setRPCMessageHandler { bytes ->
            rpcPayloadDispatch.trySend(bytes)
        }
        transport.setClosedHandler { reason ->
            scope.launch { handleTerminalFailure(reason) }
        }
        transport.setPairedRecoveryHandler {
            scope.launch { transportDidPair() }
        }
        // 桥退化回调（C-13）：先于 transport.start 注册（帧 handler 先于订阅/建桥纪律，
        // 桌面宣告可能在任意时点到达）（:146-150）
        transport.setBridgeDegradedHandler { reason ->
            scope.launch { handleBridgeDegraded(reason) }
        }

        // 1. WS + auth（auth_init/auth_challenge/auth_response/auth_ack）
        transport.start(timeout)

        // 2. bootstrap（desktopAppVersion / initialViewState / tasks；81KB 列表单帧直出）
        val bootstrap = requestApp("bootstrap-request", 15.seconds)
        val result = bootstrap["result"]
        mutex.withLock {
            desktopAppVersion = result?.get("desktopAppVersion")?.stringValue
            initialViewState = result?.get("initialViewState")
            taskListJSON = result?.get("tasks")
        }
        // 诊断：bootstrap.tasks 的跨工作区面（iOS 写 UserDefaults diag.bootstrap.tasks，
        // :162-168；Android 连接层无 UserDefaults 面 → 同内容入连接日志，AppSession 侧
        // 经 bootstrapTasksProvider 消费同源数据）
        val taskList = mutex.withLock { taskListJSON }
        val taskWs = LinkedHashSet<String>()
        for (row in taskList?.arrayValue ?: emptyList()) {
            row.objectValue?.get("workspacePath")?.stringValue?.let { taskWs.add(it) }
        }
        log(
            RelayLogKind.info,
            "bootstrap.tasks · tasks=$taskCount distinctWs=${taskWs.size} [${taskWs.sorted().joinToString(" | ").take(500)}]",
        )
        val versionSnapshot = mutex.withLock { desktopAppVersion }
        if (versionSnapshot != null) {
            val versionDrift = link.desktopAppVersion?.let { it != versionSnapshot } ?: false
            log(
                RelayLogKind.ok,
                "bootstrap-response · $versionSnapshot · tasks=$taskCount" +
                    if (versionDrift) " · ⚠ 桌面版本与配对链接不一致" else "",
            )
        }

        // 3. workspace-list（activeWorkspaceKey + 全部工作区——多 workspace 枚举源）（:175-186）
        val parsedSummaries: List<RelayWorkspaceSummary>
        val workspaceList = runCatching { requestApp("workspace-list-request", 10.seconds) }.getOrNull()
        mutex.withLock {
            if (workspaceList != null) {
                val listResult = workspaceList["result"]
                if (listResult != null) {
                    activeWorkspaceKey = listResult["activeWorkspaceKey"]?.stringValue
                    activeTaskId = listResult["activeTaskId"]?.stringValue
                    workspaceSummaries = parseWorkspaceSummaries(listResult["workspaces"])
                }
            }
            if (activeWorkspaceKey == null) {
                activeWorkspaceKey = initialViewState?.get("activeWorkspaceKey")?.stringValue
                activeTaskId = activeTaskId ?: initialViewState?.get("activeTaskId")?.stringValue
            }
            parsedSummaries = workspaceSummaries
        }

        // 4. 开桥 + 等桌面 Initialize（:188-189）
        val key = mutex.withLock { activeWorkspaceKey }
        val taskId = mutex.withLock { activeTaskId }
        openBridge(workspaceKey = key, taskId = taskId)
        return summary ?: ConnectSummary(
            desktopAppVersion = versionSnapshot,
            bridgeKind = null,
            workspacePath = key ?: "",
            workspaceKey = key,
            initialTaskId = taskId,
            recoveryId = lastRecoveryId,
            sessionCount = taskCount,
            workspaces = parsedSummaries,
        )
    }

    /** workspace-bridge-open → workspace-bridge-ready → 桥身份绑定 → 等待 Initialize([200])（:218-283）。 */
    private suspend fun openBridge(workspaceKey: String?, taskId: String?) {
        if (workspaceKey.isNullOrEmpty()) {
            throw RPCError(message = "中继 bootstrap 未提供 activeWorkspaceKey", name = "RelayFailure")
        }
        val generation: Int
        val bridgeSessionId: String
        val payload: LinkedHashMap<String, JSONValue>
        mutex.withLock {
            bridgeGeneration += 1
            generation = bridgeGeneration
            bridgeSessionId = uuidHexString()
            payload = linkedMapOf(
                "zcode_type" to JSONValue.Str("workspace-bridge-open"),
                "bridgeSessionId" to JSONValue.Str(bridgeSessionId),
                "bridgeGeneration" to JSONValue.Int(generation),
                "workspaceKey" to JSONValue.Str(workspaceKey),
            )
            lastRecoveryId?.let { payload["recoveryId"] = JSONValue.Str(it) }
            if (!taskId.isNullOrEmpty()) {
                payload["taskId"] = JSONValue.Str(taskId)
            }
        }
        log(RelayLogKind.working, "workspace-bridge-open · gen=$generation workspace=$workspaceKey")

        val ready = transport.requestAppPayload(
            payload,
            zcodeType = "workspace-bridge-open",
            timeout = 15.seconds,
            bridgeSessionId = bridgeSessionId,
        )
        val bridge = ready["bridge"]
        val readySessionId = bridge?.get("bridgeSessionId")?.stringValue
            ?: throw RPCError(message = "workspace-bridge-ready 缺 bridge 字段", name = "RelayFailure")
        val recoveryId = bridge["recoveryId"]?.stringValue
        val identity = RelayFrameCodec.Identity(
            bridgeSessionId = readySessionId,
            bridgeGeneration = bridge["bridgeGeneration"]?.intValue ?: generation,
            recoveryId = recoveryId,
        )
        mutex.withLock {
            lastRecoveryId = recoveryId
            bridgeIdentity = identity
        }
        transport.bindFrameChannel(identity)
        // 视图状态采用 ready 回包值（web P() 同构：s=bridge.workspaceKey、c=bridge.initialTaskId）（:252-254）
        mutex.withLock {
            activeWorkspaceKey = bridge["workspaceKey"]?.stringValue ?: workspaceKey
            activeTaskId = bridge["initialTaskId"]?.stringValue ?: taskId
            summary = ConnectSummary(
                desktopAppVersion = desktopAppVersion,
                bridgeKind = bridge["kind"]?.stringValue,
                workspacePath = bridge["workspacePath"]?.stringValue ?: workspaceKey,
                workspaceKey = bridge["workspaceKey"]?.stringValue ?: workspaceKey,
                initialTaskId = bridge["initialTaskId"]?.stringValue ?: taskId,
                recoveryId = recoveryId,
                sessionCount = taskCount,
                workspaces = workspaceSummaries,
            )
        }
        log(
            RelayLogKind.ok,
            "workspace-bridge-ready · kind=${bridge["kind"]?.stringValue ?: "?"}" +
                " · path=${bridge["workspacePath"]?.stringValue ?: "?"}" +
                (recoveryId?.let { " · recoveryId=${it.take(10)}…" } ?: ""),
        )

        // C-11 场景①（bridge-open 成功）：上报 mobile-view-state-update——含首连/切换/
        // 重连重建全部开桥路径（web P() 内 M(bridge.workspaceKey, bridge.initialTaskId)；
        // 桌面以 mobileViewState 优先于 initialViewState 决定断线重连后的落点，bundle 实证）（:269-272）
        sendMobileViewStateUpdate(taskId = mutex.withLock { activeTaskId })

        // 桥内桌面即推 Initialize（探针实测 04 01 06 c8 01 00 = serialize([200])+serialize(undefined)）
        waitForInitialize(10.seconds)
        // 新桥 = 新 facade：先重做 v4 握手（hello → clientHello）再恢复订阅——
        // 顺序颠倒则握手前的 assertReady 类调用全部 handshakeRequired（onBridgeOpened 注）（:276-280）
        onBridgeOpened?.invoke(this)
        // 桥重建（断线恢复）后重发全部活跃 eventListen：新桥上恢复 v4 topic 推流（:281-282）
        resendActiveEventListeners()
    }

    private suspend fun resendActiveEventListeners() {
        val listeners: List<Pair<Int, ActiveListener>> = mutex.withLock {
            activeEventListeners.entries.sortedBy { it.key }.map { it.key to it.value }
        }
        if (listeners.isEmpty()) return
        for ((id, listener) in listeners) {
            val header = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.eventListen.rawValue),
                        RPCValue.Int(id),
                        RPCValue.Str(listener.channel),
                        RPCValue.Str(listener.event),
                    ),
                ),
            )
            val body = RPCSerialization.serialize(listener.arg)
            scope.launch {
                try {
                    transport.sendRPCMessage(header + body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // iOS `try?`（:294）
                }
            }
        }
        log(RelayLogKind.ok, "桥重建 · 重发 ${listeners.size} 路 eventListen 订阅")
    }

    private suspend fun waitForInitialize(timeout: Duration) {
        val waiter = CompletableDeferred<Unit>()
        val alreadyIdle = mutex.withLock {
            when (state) {
                State.Idle -> true
                State.Closed -> throw RPCError(message = "连接在 Initialize 前已关闭", name = "ConnectionClosed")
                State.Uninitialized -> {
                    initializeWaiters.add(waiter)
                    false
                }
            }
        }
        if (!alreadyIdle) {
            try {
                withTimeout(timeout) { waiter.await() }
            } catch (e: TimeoutCancellationException) {
                mutex.withLock { initializeWaiters.remove(waiter) }
                throw RPCError(message = "等待桥内 Initialize 超时", name = "TimeoutError")
            }
        }
        log(RelayLogKind.ok, "桥内 Initialize 已到 · RPC 通道就绪")
    }

    private fun failInitializeWaitersLocked(error: Throwable) {
        val waiters = ArrayList(initializeWaiters)
        initializeWaiters.clear()
        for (waiter in waiters) waiter.completeExceptionally(error)
    }

    // MARK: 断线重连编排（transport paired 恢复后重建桥）

    private suspend fun handleTerminalFailure(reason: RelayCloseReason) {
        var closedCallback: ((Throwable?) -> Unit)? = null
        mutex.withLock {
            if (state == State.Closed) return
            state = State.Closed
            val error = RPCError(message = reason.codeText, name = "RelayClosed")
            failPendingLocked(error)
            failInitializeWaitersLocked(error)
            eventHandlers.clear()
            closedCallback = onClosed
        }
        closedCallback?.invoke(null)
    }

    /**
     * transport 重连成功（paired）后的桥重建：generation 递增 + recoveryId，
     * 未确认帧不跨桥重放（replay 语义保守化：pending RPC 失败，Store 层 resync）（:339-355）。
     */
    suspend fun rebuildBridgeAfterReconnect() {
        val workspaceKey: String
        mutex.withLock {
            if (state != State.Idle || bridgeRebuildInFlight) return
            workspaceKey = activeWorkspaceKey ?: return
            bridgeRebuildInFlight = true
        }
        try {
            log(RelayLogKind.info, "重连恢复 · 重建 workspace 桥（gen=${bridgeGeneration + 1}）")
            failPendingNow(
                RPCError(message = "中继重连，请求已中断", name = "Reconnecting"),
            )
            openBridge(workspaceKey = workspaceKey, taskId = mutex.withLock { activeTaskId })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(RelayLogKind.error, "桥重建失败：${e.message ?: e.toString()}")
            handleTerminalFailure(RelayCloseReason.RelayUnavailable("桥重建失败"))
        } finally {
            mutex.withLock { bridgeRebuildInFlight = false }
        }
    }

    /** transport 每次 paired（含首次）时回调；首次由 connectRelay 编排，其后走重建（:358-362）。 */
    suspend fun transportDidPair() {
        // connectRelay 编排期间（state==uninitialized）不处理，避免与首连竞争
        if (mutex.withLock { state } != State.Idle) return
        rebuildBridgeAfterReconnect()
    }

    /**
     * 桥退化快速重建（C-13；web T(reason) 的移动端同构）：桌面宣告 bridge-degraded /
     * 无 requestId 的 workspace-bridge-error，或本地 45s 无 ack 判退化后，pending RPC
     * 立即失败（死桥上等待只会逐条 30s 超时）→ 同 transport 重开桥（不重走 auth/pair，
     * recoveryId 随开携回）。传输已失联时不动（等 paired 恢复链路接管重建）（:368-396）。
     */
    private suspend fun handleBridgeDegraded(reason: String) {
        val workspaceKey: String
        mutex.withLock {
            if (state != State.Idle || bridgeRebuildInFlight) return
            workspaceKey = activeWorkspaceKey ?: return
        }
        if (!transport.isPaired()) {
            log(RelayLogKind.info, "桥退化但传输未配对 · 等 paired 恢复后重建（$reason）")
            return
        }
        mutex.withLock { bridgeRebuildInFlight = true }
        try {
            log(RelayLogKind.error, "桥退化 · $reason · 快速重建（gen=${bridgeGeneration + 1}）")
            failPendingNow(
                RPCError(message = "桥退化重建，请求已中断（$reason）", name = "Reconnecting"),
            )
            openBridge(workspaceKey = workspaceKey, taskId = mutex.withLock { activeTaskId })
            log(RelayLogKind.ok, "退化桥已重建 · 活跃订阅已随开桥重发")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (transport.isPaired()) {
                log(RelayLogKind.error, "退化桥重建失败：${e.message ?: e.toString()}")
                handleTerminalFailure(RelayCloseReason.RelayUnavailable("退化桥重建失败"))
            } else {
                // 重建途中传输失联：不走终态，paired 恢复链路（transportDidPair）接管（:392-394）
                log(RelayLogKind.info, "退化桥重建中断（传输失联）· 等 paired 恢复后重建")
            }
        } finally {
            mutex.withLock { bridgeRebuildInFlight = false }
        }
    }

    // MARK: 工作区切换（P3-10；C-10/C-11/C-12 对齐 web：bridge-open + view-state，无 reconnect 前置）

    /**
     * 目标工作区的 workspaceKey 解析（C-12 严格口径）：**一律取清单原始 workspaceKey**
     * ——先按 workspaceIdentity（远端工作区判别键）命中，再按 path。清单未收录时按
     * web 同款公式合成键 `tc(path,identity)=identity.trim()||path`（【移植·bundle 逆向】
     * Ia/vM 同构：桌面以同一公式派生自身工作区的 workspaceKey，bridge-open 接受该键；
     * ⑤切换器并入 bootstrap.tasks 派生条目后，清单外工作区是常规路径而非非法 key——
     * 旧口径「未收录即失败」只覆盖 workspace-list-response 单源时代）。切换失败仍由
     * bridge-open 错误如实回传（AppSession.switchWorkspace hint）（:407-418）。
     */
    fun resolvedWorkspaceKey(forPath: String, identity: String? = null): String? {
        val summaries = workspaceSummaries
        if (!identity.isNullOrEmpty()) {
            summaries.firstOrNull { it.workspaceIdentity == identity }?.let { return it.workspaceKey }
        }
        summaries.firstOrNull { it.path == forPath }?.let { return it.workspaceKey }
        val trimmedIdentity = identity?.trim() ?: ""
        if (trimmedIdentity.isNotEmpty()) return trimmedIdentity
        return if (forPath.isEmpty()) null else forPath
    }

    /**
     * mobile-view-state-update 发送（C-11；web M(e,n) 同构，bundle 实证）：
     * `{zcode_type, viewState:{activeWorkspaceKey, activeTaskId?, updatedAt:毫秒},
     *   deviceInfo:{platform,version:appVersion,name}}`
     * 单向通知（web sendPayload 面：无 requestId、无响应等待）；activeTaskId 缺席时
     * 整键省略（web `...n?{activeTaskId:n}:{}` 同款）。appVersion 以 **version** 键入帧（:420-441）。
     */
    suspend fun sendMobileViewStateUpdate(taskId: String? = null) {
        val key = mutex.withLock { activeWorkspaceKey }
        if (key.isNullOrEmpty()) return
        val viewState = LinkedHashMap<String, JSONValue>()
        viewState["activeWorkspaceKey"] = JSONValue.Str(key)
        viewState["updatedAt"] = JSONValue.Double(System.currentTimeMillis().toDouble())
        if (!taskId.isNullOrEmpty()) {
            viewState["activeTaskId"] = JSONValue.Str(taskId)
        }
        transport.sendAppNotification(
            linkedMapOf(
                "zcode_type" to JSONValue.Str("mobile-view-state-update"),
                "viewState" to JSONValue.Obj(viewState),
                "deviceInfo" to JSONValue.Obj(mobileDeviceInfo(appVersion = appVersion)),
            ),
            zcodeType = "mobile-view-state-update",
        )
    }

    /**
     * web u4t({appVersion}) 的移动端裁剪：platform/version/name 必带，浏览器专属键
     * （viewport/userAgent/timezone 等）iOS 无对应面不带。platform 值域未取证——web
     * 实测恒 `web`/`mobile-browser`，iOS 移动端如实报 `ios`（单向通知，被弃亦无功能回退），
     * Android 同口径如实报 `android`（AGENTS §6 未决⑫：viewState 不生效首查此值）（:443-452）。
     */
    fun mobileDeviceInfo(appVersion: String): Map<String, JSONValue> = linkedMapOf(
        "platform" to JSONValue.Str("android"),
        "version" to JSONValue.Str(appVersion.ifEmpty { "1.0.0" }),
        "name" to JSONValue.Str("ZCode Mobile"),
    )

    /**
     * 视图状态上报（C-11 场景②「打开任务」与切换后清空任务两用；web
     * updateMobileViewState(r, taskId) 由活动任务变化 effect 触发，M(e,n) 的
     * n=undefined 同步清模块态）。本地 activeTaskId 随之刷新——退化/重连重建以它为
     * 落点（web T() 捕获 s/c 同构）。打开任务调用面由视图与 Store 波次接线（:454-461）。
     */
    suspend fun updateActiveTask(taskId: String?) {
        mutex.withLock { activeTaskId = if (!taskId.isNullOrEmpty()) taskId else null }
        sendMobileViewStateUpdate(taskId = mutex.withLock { activeTaskId })
    }

    /**
     * 工作区切换的桥落地（复用既有 WS/auth，不重建配对）：在途 RPC 按重连同口径失败
     * （未确认帧不跨桥重放）→ 三路 dynamic eventListen 的 workspacePath 参数改写到
     * 新工作区 → openBridge 重开桥（generation 递增、新身份绑定、Initialize 等待、
     * 活跃 eventListen 重发）。失败回滚原工作区桥（尽力）后原样上抛（:463-522）。
     */
    suspend fun switchBridgeWorkspace(workspaceKey: String, workspacePath: String) {
        if (mutex.withLock { state } != State.Idle) {
            throw RPCError(message = "通道未就绪（state=${mutex.withLock { state }}）", name = "NotInitialized")
        }
        // 并发切换串行守卫：上一笔切换事务未完成时新请求立即拒绝（UI 层 isSwitching
        // 禁用之外的纵深防御；bridgeRebuildInFlight 只挡重建两路，挡不住并发 switch
        // 互踩单槽 bridgeOpenWaiter）（:471-476）
        mutex.withLock {
            if (switchInFlight) {
                throw RPCError(message = "已有工作区切换在进行中", name = "SwitchInProgress")
            }
        }
        // 切换全程持重建互斥（含失败回滚）：degraded 快速重建/paired 恢复重建在切换
        // 在途时并发 openBridge 会互踩单槽 bridgeOpenWaiter（:477-484）
        val previousKey: String?
        val previousTaskId: String?
        val previousPath: String?
        mutex.withLock {
            bridgeRebuildInFlight = true
            switchInFlight = true
            previousKey = activeWorkspaceKey
            previousTaskId = activeTaskId
            previousPath = activeEventListeners.values
                .mapNotNull { (it.arg.jsonValue as? JSONValue.Obj)?.fields?.get("workspacePath")?.stringValue }
                .firstOrNull()
        }
        try {
            // 未确认 RPC 不跨桥重放（rebuildBridgeAfterReconnect 同口径）（:490-495）
            failPendingNow(
                RPCError(message = "工作区切换，请求已中断", name = "Reconnecting"),
            )
            redirectListenerWorkspacePath(to = workspacePath)
            mutex.withLock {
                activeWorkspaceKey = workspaceKey
                activeTaskId = null
            }
            try {
                openBridge(workspaceKey = workspaceKey, taskId = null)
            } catch (switchError: Throwable) {
                if (switchError is CancellationException) throw switchError
                // 回滚（尽力）：恢复原工作区桥与事件参数，保持当前工作区可用态；回滚失败
                // 将其错误并进上抛信息（不静默吞——切换失败与回滚失败都要回到 UI）（:501-520）
                log(RelayLogKind.error, "工作区桥切换失败，回滚原工作区：${switchError.message ?: switchError.toString()}")
                redirectListenerWorkspacePath(to = previousPath)
                mutex.withLock {
                    activeWorkspaceKey = previousKey
                    activeTaskId = previousTaskId
                }
                if (previousKey != null) {
                    try {
                        openBridge(workspaceKey = previousKey, taskId = previousTaskId)
                    } catch (rollbackError: Throwable) {
                        if (rollbackError is CancellationException) throw rollbackError
                        val combined = (switchError.message ?: switchError.toString()) +
                            "（回滚原工作区亦失败：${rollbackError.message ?: rollbackError.toString()}，可断开重连恢复）"
                        throw RPCError(
                            message = combined,
                            name = (switchError as? RPCError)?.name ?: "SwitchFailed",
                        )
                    }
                }
                throw switchError
            }
        } finally {
            mutex.withLock {
                bridgeRebuildInFlight = false
                switchInFlight = false
            }
        }
    }

    /** dynamic eventListen 参数重定向（conversation / sessions-index / workspace-config 三路按 workspacePath 定向；无该键的监听不动）（:526-536）。 */
    private suspend fun redirectListenerWorkspacePath(to: String?) {
        if (to == null) return
        mutex.withLock {
            for ((id, listener) in activeEventListeners) {
                val fields = (listener.arg.jsonValue as? JSONValue.Obj)?.fields ?: continue
                if (!fields.containsKey("workspacePath")) continue
                val updated = LinkedHashMap(fields)
                updated["workspacePath"] = JSONValue.Str(to)
                listener.arg = RPCValue.json(JSONValue.Obj(updated))
            }
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            state = State.Closed
            bridgeIdentity = null
            activeEventListeners.clear()
            val error = RPCError(message = "连接已关闭", name = "ConnectionClosed")
            failPendingLocked(error)
            failInitializeWaitersLocked(error)
            eventHandlers.clear()
        }
        transport.close()
    }

    // MARK: RPC（Promise）

    /**
     * 桥内标准 RPC：serialize([100,id,channel,command]) + serialize([arg]) 作为 rpc-frame 载荷，
     * **无 13 字节 SocketProtocol 头**（framingDeltas ①）。
     * **body 是「参数数组」**（桌面端 toService 代理把调用参数列表整体序列化，host 端
     * fromService 按 `apply(o, p)` 展开）——实测对象直传会被解成 undefined（探针 disc_probe2：
     * 数组包对象 [201] 成功 / 对象直传 [202] undefined），与局域网直连的裸 arg 形态不同（:551-556）。
     */
    override suspend fun call(
        channel: String,
        command: String,
        arg: RPCValue,
        timeout: Duration,
    ): RPCValue {
        // arg 约定（2026-11-10 实证）：Arg=Arr = 完整位置参数数组（web toService
        // `channel.call(name, args)` 同形）原样作 body——多参服务
        // （project.deleteProject(id, hostId) 等）靠它表达；否则视为单个位置参数
        // 包一层成 [arg]（既有全部调用形态不变——仓内零 Arr 形态调用点，零回归面）。
        // 反面实证：deleteProject 传 Obj(JSON数组) 被再包一层 → 桌面 spread 后
        // id=整个数组 → SQLite "cannot be bound to parameter 3"。
        val body = RPCSerialization.serialize(
            if (arg is RPCValue.Arr) arg else RPCValue.Arr(listOf(arg)),
        )
        val deferred = CompletableDeferred<RPCValue>()
        val id: Int
        val frame: ByteArray
        mutex.withLock {
            if (state != State.Idle) {
                throw RPCError(message = "通道未就绪（state=$state）", name = "NotInitialized")
            }
            id = lastRequestId
            lastRequestId += 1
            // header 含 id → 与注册同锁内构建（iOS actor 内同步构建同口径）
            frame = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.promise.rawValue),
                        RPCValue.Int(id),
                        RPCValue.Str(channel),
                        RPCValue.Str(command),
                    ),
                ),
            ) + body
            pendingResponses[id] = deferred
        }
        return try {
            try {
                transport.sendRPCMessage(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                resolveResponse(id = id, result = Result.failure(e))
            }
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            mutex.withLock { pendingResponses.remove(id) }
            throw RPCError(message = "RPC 超时：$channel.$command", name = "TimeoutError")
        } catch (e: CancellationException) {
            mutex.withLock { pendingResponses.remove(id) }
            throw e
        } catch (e: Throwable) {
            // iOS：发送失败/响应失败路径已 resume，此处移除残留并原样上抛（:592-595）
            mutex.withLock { pendingResponses.remove(id) }
            throw e
        }
    }

    // MARK: 事件（EventListen / EventDispose）

    /**
     * eventListen：arg **对象原样**（web 端 dynamic event 走 `service.onDynamicXxx(arg)`，
     * proxy 经 requestEvent 直传 arg，不数组化——与 promise 面的参数数组不同，
     * 探针 probe9 实测数组化 eventListen 后桌面快照帧不到）（:598-627）。
     */
    override suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue,
        handler: (RPCValue) -> Unit,
    ): EventSubscription {
        val header: ByteArray
        val body: ByteArray
        val id: Int
        mutex.withLock {
            id = lastRequestId
            lastRequestId += 1
            eventHandlers[id] = handler
            activeEventListeners[id] = ActiveListener(channel, event, arg)
            header = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.eventListen.rawValue),
                        RPCValue.Int(id),
                        RPCValue.Str(channel),
                        RPCValue.Str(event),
                    ),
                ),
            )
            body = RPCSerialization.serialize(arg)
        }
        scope.launch {
            try {
                transport.sendRPCMessage(header + body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(RelayLogKind.error, "eventListen 发送失败：${e.message ?: e.toString()}")
            }
        }
        return EventSubscription {
            scope.launch { disposeEvent(id) }
        }
    }

    private suspend fun disposeEvent(id: Int) {
        val frame: ByteArray
        mutex.withLock {
            if (eventHandlers.remove(id) == null) return
            activeEventListeners.remove(id)
            if (state != State.Idle) return
            val header = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.eventDispose.rawValue),
                        RPCValue.Int(id),
                    ),
                ),
            )
            val body = RPCSerialization.serialize(RPCValue.Arr(listOf(RPCValue.Undefined)))
            frame = header + body
        }
        // 锁外发送（sendRPCMessage 可在饱和水位上长挂——不持本类锁）
        try {
            transport.sendRPCMessage(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // iOS `try?`（:638）
        }
    }

    // MARK: 下行 RPC 载荷（无 13 字节头，直接 deserialize）

    /** iOS Task 派发 → 单消费者 Channel 串行派发（文件头注：比 iOS 无序 Task 更强保序）。 */
    private val rpcPayloadDispatch = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (bytes in rpcPayloadDispatch) {
                handleRPCPayload(bytes)
            }
        }
    }

    private suspend fun handleRPCPayload(bytes: ByteArray) {
        val cursor = ReadCursor(0)
        val headerValue = RPCSerialization.deserialize(bytes, cursor) ?: return
        val body = RPCSerialization.deserialize(bytes, cursor) ?: return
        val headerItems = (headerValue as? RPCValue.Arr)?.items ?: return
        val rawType = headerItems.getOrNull(0)?.intValue ?: return
        val type = ResponseType.fromRawValue(rawType) ?: return

        when (type) {
            ResponseType.initialize -> {
                val waiters = ArrayList<CompletableDeferred<Unit>>()
                mutex.withLock {
                    state = State.Idle
                    waiters.addAll(initializeWaiters)
                    initializeWaiters.clear()
                }
                for (waiter in waiters) waiter.complete(Unit)
            }
            ResponseType.promiseSuccess -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                resolveResponse(id = id, result = Result.success(body))
            }
            ResponseType.promiseError -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                resolveResponse(id = id, result = Result.failure(decodeError(body)))
            }
            ResponseType.promiseErrorObj -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                resolveResponse(
                    id = id,
                    result = Result.failure(
                        RPCError(message = "RPC 错误对象", name = "ErrorObj", detail = body.jsonValue),
                    ),
                )
            }
            ResponseType.eventFire -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                val handler = mutex.withLock { eventHandlers[id] }
                handler?.invoke(body)
            }
        }
    }

    /** 恰好 resume 一次（响应 / 超时 / 发送失败三路竞争收口）（:674-680）。 */
    private suspend fun resolveResponse(id: Int, result: Result<RPCValue>) {
        val deferred = mutex.withLock { pendingResponses.remove(id) } ?: return
        result.fold(
            onSuccess = { deferred.complete(it) },
            onFailure = { deferred.completeExceptionally(it) },
        )
    }

    /** 须持锁调用（failPending，:682-688）。 */
    private fun failPendingLocked(error: Throwable) {
        val responses = ArrayList(pendingResponses.values)
        pendingResponses.clear()
        for (deferred in responses) deferred.completeExceptionally(error)
    }

    /** 锁外入口：短暂持锁清账本并 complete（rebuild/degraded/switch 三路的失败上抛）。 */
    private suspend fun failPendingNow(error: Throwable) {
        mutex.withLock { failPendingLocked(error) }
    }

    private fun decodeError(body: RPCValue): RPCError {
        val dict = (body.jsonValue as? JSONValue.Obj)?.fields
            ?: return RPCError(message = "未知 RPC 错误", name = "Error")
        // Swift 先写 message 后写 name（两键可同帧在场），逐字口径（:690-698）
        var message = "未知 RPC 错误"
        var name = "Error"
        dict["message"]?.stringValue?.let { message = it }
        dict["name"]?.stringValue?.let { name = it }
        return RPCError(message = message, name = name, detail = JSONValue.Obj(dict))
    }

    // MARK: app 层请求便捷封装 + 日志

    private suspend fun requestApp(zcodeType: String, timeout: Duration): JSONValue {
        return transport.requestAppPayload(
            linkedMapOf("zcode_type" to JSONValue.Str(zcodeType)),
            zcodeType = zcodeType,
            timeout = timeout,
        )
    }

    private val taskCount: Int
        get() = taskListJSON?.arrayValue?.size ?: 0

    private fun log(kind: RelayLogKind, text: String) {
        transport.onLog(kind, text)
    }

    /** iOS private extension String.hexString（:716-721）内联承载。 */
    private fun uuidHexString(): String = UUID.randomUUID().toString().replace("-", "").lowercase()

    companion object {
        /**
         * workspace-list-response 条目宽容解析（web 同源：条目优先带 workspaceKey 原始键；
         * 缺席时按 web tc(path,identity) 公开式补算 = identity 优先、path 兜底——identity
         * 缺席的本地工作区 key 即 path，与切换面「未收录路径冒充 key」的反查兜底不同，
         * 此处是桌面侧同款键构造，非臆造）（iOS `nonisolated static func`，:197-216 →
         * companion，ZCodeServerConnection.handleWorkspaceListUpdated 亦调用）。
         */
        fun parseWorkspaceSummaries(value: JSONValue?): List<RelayWorkspaceSummary> {
            return (value?.arrayValue ?: emptyList()).mapNotNull { item ->
                val d = item.objectValue ?: return@mapNotNull null
                val key = d["workspaceKey"]?.stringValue
                    ?: d["workspaceIdentity"]?.stringValue
                    ?: d["path"]?.stringValue ?: ""
                if (key.isEmpty()) return@mapNotNull null
                RelayWorkspaceSummary(
                    workspaceKey = key,
                    path = d["path"]?.stringValue,
                    workspaceIdentity = d["workspaceIdentity"]?.stringValue,
                    name = d["name"]?.stringValue ?: d["title"]?.stringValue,
                    kind = d["kind"]?.stringValue,
                    remoteSessionId = d["remoteSessionId"]?.stringValue,
                )
            }
        }
    }
}

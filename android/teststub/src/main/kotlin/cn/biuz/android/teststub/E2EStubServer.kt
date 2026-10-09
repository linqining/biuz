package cn.biuz.android.teststub

import cn.biuz.android.protocol.CommandSchemas
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.PField
import cn.biuz.android.protocol.PObjectShape
import cn.biuz.android.protocol.PSchema
import cn.biuz.android.protocol.PValidator
import cn.biuz.android.protocol.RPCFrame
import cn.biuz.android.protocol.RPCSerialization
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.ReadCursor
import cn.biuz.android.protocol.RequestType
import cn.biuz.android.protocol.ResponseType
import cn.biuz.android.protocol.relay.RelayAuth
import cn.biuz.android.protocol.relay.RelayFrameAssembler
import cn.biuz.android.protocol.relay.RelayFrameCodec
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.http.ContentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ktor（CIO）本地替身服务器骨架——E2ELoginStubServer 的 Kotlin 同构（本轮骨架面）：
 * ① 握手 hello→clientHello（能力键只回显 host 宣告过的，A5）；
 * ② 可注水的会话/会话索引快照与 delta 帧推送（键级整体替换语义，A10；seq 每 topic 单调、快照重置）；
 * ③ sendConversationCommandV4 信封 strict schema 对拍后回 ACK（六态词表可注水，A13）；
 *    快照帧可先于订阅回执到达（A8，供 AckActivationBarrier 屏障测试）。
 *
 * 复用 :protocol 的 JSONValue/RPCSerialization/RPCFrame/PValidator/CommandSchemas
 * ——替身与被测客户端「构造与校验同源」，入站信封不过 strict 校验即回 rejected
 * （比上游服务端 strip 口径更严，方向安全——与 CommandSchemas.kt 构造纪律同理由）。
 *
 * iOS 原文：ios/Tests/E2ELoginStubServer.swift（3118 行；骨架轮只平移骨架，全量
 * 业务处理器——bots/任务组/重置卡等——经 [methodHandlers] 扩展点按里程碑补；
 * 附件四方法已于 2026-10-10 附件链路轮内置：strict 对拍 + 上传事务账本，
 * 见「MARK: 附件四方法」）。
 *
 * 与 iOS 替身的形态偏差（【上游裁决 2026-10-09 /tmp/zcode-upstream】，推帧贴上游真实形态）：
 * - serverTime 发 epoch 毫秒数（timestampSchema = z.number()，core.ts:19；真实发射
 *   zcodeAgentService.ts:4908 Date.now()）——iOS 替身发 ISO 字符串（:1071）；
 * - topic 帧 wireVersion=3（V4_WIRE_PROTOCOL_VERSION，core.ts:7；wire-codec.ts:122/:189
 *   恒写该值）——iOS 替身恒发 1（:2411 等）；
 * - accepted ACK 恒带 revisionAtDecision（commandAckSchema 必填 number，
 *   command.ts:439）——iOS 替身 accepted 分支缺该键（:1980-1984）；
 * - deliveryProfile 发 "replayable"（helloMessageSchema 枚举 + superRefine
 *   web-remote-replayable→replayable，transport.ts:44-51）——iOS 替身发
 *   "web-remote-replayable"（:1070）不在上游枚举内。
 */
class E2EStubServer(
    val host: String = "127.0.0.1",
    /** 替身工作区路径（server-info workspaces[0].path / topic 键；iOS 原文 :2587 同值）。 */
    val workspacePath: String = "/Users/e2e/zcode-workspace",
) {

    // MARK: 记账类型（用例断言面）

    class RecordedCall(val order: Int, val channel: String, val command: String, val atMillis: Long)

    class RecordedClientHello(
        val order: Int,
        val raw: JSONValue,
        val errors: List<String>,
        /** A5 单向宣告违规（host 未宣告却宣告的能力键）。 */
        val capabilityViolations: List<String>,
        val accepted: Boolean,
        val clientId: String?,
    )

    /** 一次 sendConversationCommandV4 的对拍记账（strict 校验结果 + 信封逐字段 + 回执状态）。 */
    class RecordedCommandEnvelope(
        val order: Int,
        val raw: JSONValue,
        val envelope: JSONValue?,
        val commandId: String?,
        val clientId: String?,
        /** null = 握手未完成即发命令；true/false = 与握手 clientId 逐字比对结果（A2）。 */
        val clientIdMatchesHandshake: Boolean?,
        val type: String,
        /** 信封 sessionId（键恒在场 A1；createSession 为 Null）。 */
        val sessionId: JSONValue?,
        val issuedAt: JSONValue?,
        val baseRevision: JSONValue?,
        val baseLogEpoch: JSONValue?,
        val workspacePath: String?,
        val workspaceIdentity: String?,
        /** strict 对拍错误（恒空 = 入站信封通过 strict 校验——冒烟门禁断言点）。 */
        val schemaErrors: List<String>,
        val schemaWarnings: List<String>,
        /** CAS/row-target 词表缺水位（上游 parseCommandEnvelope 第三步镜像）。 */
        val casGateError: String?,
        var ackStatus: String? = null,
        /** 该 type 的第几次调用（含被拒；StaleOnceThenAccept 等注水器同源计数）。 */
        var callNth: Int = 0,
    )

    class RecordedFrame(
        val order: Int,
        val eventName: String,
        val topic: String,
        val subscriptionId: String,
        val payloadKind: String,
        val fromSeq: Int?,
        val toSeq: Int?,
    )

    class RecordedSubscribe(
        val topic: String,
        val workspacePath: String?,
        val workspaceIdentity: String?,
    )

    /**
     * 一次附件四方法（attachmentBegin/Chunk/Commit/AbortV4）的对拍记账
     * （2026-10-10 附件链路轮）。strict 校验错误在 [schemaErrors]；业务级拒因
     * （未知 uploadId/块错位/块未收齐/方法级故障注水）在 [businessError]；
     * 受理回执体在 [reply]（fault 时保持 null）。
     */
    class RecordedAttachmentCall(
        val order: Int,
        val method: String,
        val raw: JSONValue,
        var schemaErrors: List<String> = emptyList(),
        var businessError: String? = null,
        var reply: JSONValue? = null,
    )

    /** 发送序账（帧与 promise 回执混排，供 A8「快照先于回执」断言）。 */
    data class WireLogEntry(val order: Int, val kind: String, val detail: String, val atMillis: Long)

    /** dispatch 的 promise 面产物：回执体 + 可选「回执后」动作（resync 快照在回执后发）。 */
    private class PromiseReply(val body: JSONValue, val afterReply: (suspend () -> Unit)? = null)

    /** promiseError 载体（客户端解码为 RPCError{message,name:"ErrorObj"}）。 */
    private class StubFault(override val message: String) : Exception(message)

    // MARK: 状态

    @Volatile
    var port: Int = 0
        private set

    private var engine: EmbeddedServer<*, *>? = null

    private val lock = Any()
    private var orderCounter = 0

    // 连接会话
    private val connections = CopyOnWriteArrayList<SessionContext>()

    private class SessionContext(val ws: WebSocketServerSession) {
        val mutex = Any()
        /** event 名 → 客户端 eventListen id（[102, id, channel, event]；帧经 [204, id] 下发）。 */
        val eventIds = HashMap<String, Int>()
        /** 本连接已订阅的 conversation topic（resync 全量重发用）。 */
        val conversationTopics = LinkedHashSet<String>()
        @Volatile var clientId: String? = null
        @Volatile var open = true

        /**
         * 帧出口（sendRpcFrame/sendEventFrame 统一走此）：局域网直连 = 13 字节头二进制帧
         * 直发（缺省）；rpc-frame 中继桥 = 桥内编码器（serialize 载荷 → rpc-frame 分片 →
         * JSON 文本帧）。A6 全新握手态建模（见 relayBridgeOpen）即靠「每个桥一个新
         * SessionContext + 各自 sink」承载。
         */
        var frameSink: (suspend (ByteArray) -> Unit) = { ws.send(Frame.Binary(fin = true, data = it)) }

        /**
         * A6 assertReady 门（上游 zcodeAgentConnectionScope `handshakeComplete` facade 实例态
         * 镜像）：中继桥 ctx 恒 true——clientId 未注册（hello→clientHello 未完成）时
         * assertReady 族命令（subscribe*、readSession、rowsRange）回
         * fault.connection.handshakeRequired；sendConversationCommandV4 不过闸（上游同）。
         * 局域网直连 ctx = false（无桥语义，行为不变）。
         */
        var assertReadyGate: Boolean = false

        /**
         * 中继桥 ctx 标记：帧构建走「serialize(header)+serialize(body) 裸载荷」（framingDeltas
         * ③——rpc-frame 载荷无 13 字节 SocketProtocol 头，iOS 探针 04 01 06 c8 01 00 实证）；
         * 局域网 ctx = false（RPCFrame.write 13 字节头二进制帧）。2026-10-10 中继联调实证：
         * 桥内帧误带 13 字节头时客户端 deserialize 读到 tag=1 按畸形 Str 解析 → 静默丢帧
         * （Initialize 永不到达），客户端 ack 正常（crc 只对字节自洽）——症状即「桥开成功
         * 而桥内 Initialize 超时」。
         */
        var relayBridge: Boolean = false
    }

    // MARK: 注水面（用例内直接赋值；全部线程安全）

    /**
     * hello 回执的 capabilities（host 能力集；客户端 capabilities 只许在此集内宣告，A5）。
     * 默认集镜像桌面实发（zcodeAgentService.ts:4909-4918）。
     */
    val serverCapabilities = LinkedHashMap<String, JSONValue>()

    /** A8 开关：true（默认）= 订阅即同步发快照帧、后发订阅回执；false = 回执后延迟 50ms 补发（屏障对照）。 */
    @Volatile
    var snapshotBeforeSubscribeAck: Boolean = true

    /** iOS 同名开关对齐（E2ELoginStubServer.swift:1091）：订阅拒绝 → 客户端走 readSession 对账兜底链。 */
    @Volatile
    var failConversationSubscribe: Boolean = false

    /** envelope.type → ACK 行为注水（缺省 [defaultBehaviorFor]）。 */
    private val ackBehaviors = HashMap<String, StubAckBehavior>()

    /** 会话快照整体注水（键级整体替换，A10；缺省 [StubV4Frames.defaultConversationSnapshot]）。 */
    private val conversationSnapshotOverrides = HashMap<String, JSONValue>()

    /** sessions-index 快照整体注水（缺省双会话骨架）。 */
    @Volatile
    private var sessionsIndexSnapshotOverride: JSONValue? = null

    /**
     * bootstrap.tasks 注水（跨工作区全量任务索引；行形状 zcodeTaskMetaSchema 子集，
     * 夹具见 [StubIndexFixtures]，含跨工作区幽灵行对）。经 bootstrap-request 回执
     * `result.tasks` 下发（iOS RelayChannelClient.swift:156-159 同源：
     * bootstrap["result"] → result.tasks）。
     */
    @Volatile
    private var bootstrapTasksOverride: JSONValue? = null

    /** 注水 bootstrap.tasks（空清单恢复缺省）。 */
    fun setBootstrapTasks(tasks: List<JSONValue>) {
        synchronized(lock) { bootstrapTasksOverride = JSONValue.Arr(tasks.toList()) }
    }

    // MARK: 双桌面代 CAS 语义（【实证·上游仓】command-inbox.ts:308-363 decide() 镜像）

    /**
     * CAS 裁决代际（两代桌面并存口径，AGENTS §4.5）：
     * - [CasGeneration.LEGACY_3_14_4]（缺省，2026-10-05 真机实证桌面）：CAS 15 命令缺
     *   baseRevision 或 row-target 5 命令缺 baseLogEpoch → rejected + message
     *   "CAS commands require baseRevision and baseLogEpoch"（不判 stale——旧代
     *   revision 账本面未取证）；
     * - [CasGeneration.UPSTREAM_MAIN]（上游 main）：权威 revision/logEpoch 账本裁决——
     *   缺 baseRevision → rejected `proto.missingBaseRevision`（command-inbox.ts:326-333）；
     *   row-target baseLogEpoch ≠ 权威 → **stale** `proto.staleLogEpoch`（:340-351）；
     *   baseRevision ≠ 权威 → **stale** `proto.staleRevision` + revisionAtDecision=权威
     *   revision（:352-363）——stale 回执自带最新 revisionAtDecision，客户端 sendCommand
     *   回填后原样重发一次即命中（sendCASWithRetry 三段自愈的活体靶场）。
     */
    enum class CasGeneration { LEGACY_3_14_4, UPSTREAM_MAIN }

    @Volatile
    var casGeneration: CasGeneration = CasGeneration.LEGACY_3_14_4

    /** 会话权威 revision 账本（UPSTREAM_MAIN 代裁决源；缺省 [StubV4Frames.DEFAULT_REVISION]）。 */
    private val revisionLedger = HashMap<String, Int>()

    /** 会话权威 logEpoch 账本（缺省 [StubV4Frames.DEFAULT_LOG_EPOCH]；快照/裁决同源）。 */
    private val logEpochLedger = HashMap<String, String>()

    /** 推进会话权威 revision（模拟队列准入/排空在两条命令间推进 revision——stale-once 真实成因）。 */
    fun bumpRevision(sessionId: String, by: Int = 1) {
        synchronized(lock) {
            revisionLedger[sessionId] = (revisionLedger[sessionId] ?: StubV4Frames.DEFAULT_REVISION) + by
        }
    }

    /** 会话权威 revision（账本缺省基线）。 */
    fun authoritativeRevision(sessionId: String?): Int =
        if (sessionId == null) 0 else synchronized(lock) { revisionLedger[sessionId] ?: StubV4Frames.DEFAULT_REVISION }

    /** 会话权威 logEpoch（账本缺省基线）。 */
    fun authoritativeLogEpoch(sessionId: String): String =
        synchronized(lock) { logEpochLedger[sessionId] ?: StubV4Frames.DEFAULT_LOG_EPOCH }

    /** 当前注水的 bootstrap.tasks（测试断言面；未注水 = 空清单）。 */
    fun bootstrapTasks(): List<JSONValue> =
        (synchronized(lock) { bootstrapTasksOverride })?.arrayValue ?: emptyList()

    /**
     * 未内置方法扩展点：method 名 → 回执体。内置 v4 面之外的处理器按里程碑注册
     * （iOS 替身的 3000 行 switch 即此处按需生长），注册后仍记账 rpcCalls。
     */
    val methodHandlers = LinkedHashMap<String, (JSONValue?) -> JSONValue>()

    /** 方法级 promise 故障注水：method 名 → fault message（handshakeRequired 捕获面等用例）。 */
    private val faultInjectedMethods = HashMap<String, String>()

    /** server-info HTTP 探测记账（连接层五步第一步的断言面）。 */
    private var serverInfoProbeCount = 0

    /** eventListen 帧记账（帧面挂载/幂等断言面；[102, id, channel, event] 逐次追加）。 */
    private val eventListenRecord = ArrayList<String>()

    // MARK: 内部记账

    private val rpcCalls = ArrayList<RecordedCall>()
    private val commandEnvelopes = ArrayList<RecordedCommandEnvelope>()
    private val framesSent = ArrayList<RecordedFrame>()
    private val wireLog = ArrayList<WireLogEntry>()
    private val handshakes = ArrayList<RecordedClientHello>()
    private val subscribeTargetsRecord = ArrayList<RecordedSubscribe>()
    private val subscribedTopicsRecord = ArrayList<String>()
    private val acksSentRecord = ArrayList<JSONValue>()
    private val sessionRows = HashMap<String, ArrayList<JSONValue>>()
    private val nextRowIds = HashMap<String, Int>()

    /** 附件四方法对拍记账（用例断言面；2026-10-10 附件链路轮）。 */
    private val attachmentCallsRecord = ArrayList<RecordedAttachmentCall>()

    /**
     * 上传事务账本（uploadId → staging 态）：begin 登记、chunk 记已收块（严格顺序）、
     * commit 收齐后移册并落 checksum 表、abort 移册（幂等）。续传语义镜像桌面：
     * 同 uploadId 重发 Begin 回报 staging.nextChunkIndex = 已收块数。
     */
    private val uploadStagings = HashMap<String, StubUploadStaging>()

    /** 已 commit 附件的 checksum:totalBytes → ref（Begin committed 短路的桌面去重语义）。 */
    private val committedRefsByChecksum = HashMap<String, String>()

    /** 全量入站计数（含 strict/CAS 拒收；用例断言「客户端发了多少次」）。 */
    private val commandCallCounts = HashMap<String, Int>()

    /** 到达行为层（对拍通过）的计数；StaleOnceThenAccept 的「首击」以它为准。 */
    private val behaviorCallCounts = HashMap<String, Int>()
    private val seqLedger = StubSeqLedger()
    private var blockedWriteCommandCount = 0
    private var conversationSubscribeRejections = 0

    @Volatile
    private var lastSendTextPayload: JSONValue? = null

    init {
        // 桌面 hello 实发能力集（zcodeAgentService.ts:4909-4918；compression 枚举 none|permessage-deflate）
        serverCapabilities["nativeDialogs"] = JSONValue.Bool(true)
        serverCapabilities["localTerminal"] = JSONValue.Bool(true)
        serverCapabilities["binaryFrames"] = JSONValue.Bool(false)
        serverCapabilities["compression"] = JSONValue.Str("none")
        serverCapabilities["workspaceHookReview"] = JSONValue.Bool(true)
        serverCapabilities["independentPlanState"] = JSONValue.Bool(true)
        serverCapabilities["workflowRunDeltas"] = JSONValue.Bool(true)
    }

    // MARK: 生命周期

    /**
     * 启动并返回端口（ServerSocket(0) 先探测空闲端口再绑定——冒烟测试免端口冲突；
     * iOS 原文 start() 的 NWPort 随机绑定同思路，E2ELoginStubServer.swift:749-798）。
     */
    @Synchronized
    fun start(): Int {
        check(engine == null) { "E2EStubServer 已启动" }
        val probed = ServerSocket(0, 1, InetAddress.getByName(host)).use { socket -> socket.localPort }
        val started = embeddedServer(CIO, host = host, port = probed) { configure() }
        started.start(wait = false)
        engine = started
        port = probed
        return probed
    }

    @Synchronized
    fun stop() {
        engine?.stop(200, 1000)
        engine = null
        relayEngine?.stop(200, 1000)
        relayEngine = null
    }

    private fun Application.configure() {
        install(WebSockets)
        routing {
            // 局域网直连服务发现（GET /api/server-info?token=…，协议文档 §3.1）：
            // serverId/version/protocolVersion=1/authRequired/workspaces[0]=替身工作区；
            // token 替身不校验（authRequired=false，桌面 --no-token 同口径）
            get("/api/server-info") {
                synchronized(lock) { serverInfoProbeCount += 1 }
                val body = serverInfoBody()
                call.respondText(
                    body.data?.toString(Charsets.UTF_8) ?: "{}",
                    ContentType.Application.Json,
                )
            }
            // 局域网直连 WS 升级路径（GET /ws?token=…；token 替身不校验，桌面 --no-token 同口径）
            webSocket("/ws") {
                serveSession(this)
            }
        }
    }

    /** server-info 回执体（ServerRemoteInfo 形状，协议文档 §3.1；capabilities = host 能力集）。 */
    private fun serverInfoBody(): JSONValue {
        val capabilities = synchronized(lock) { LinkedHashMap(serverCapabilities) }
        return stubObj(
            "serverId" to JSONValue.Str("stub-server-e2e"),
            "name" to JSONValue.Str("E2E 替身桌面"),
            "version" to JSONValue.Str("3.14.4-stub"),
            "protocolVersion" to JSONValue.Int(1),
            "authRequired" to JSONValue.Bool(false),
            "workspaces" to JSONValue.Arr(
                listOf(
                    stubObj(
                        "path" to JSONValue.Str(workspacePath),
                        "label" to JSONValue.Str("替身工作区"),
                    ),
                ),
            ),
            "capabilities" to JSONValue.Obj(capabilities),
        )
    }

    // MARK: 连接会话（channel 层）

    private suspend fun serveSession(wsSession: WebSocketServerSession) {
        val session = SessionContext(wsSession)
        connections.add(session)
        try {
            // 连接建立即推 Initialize（channelServer.ts:29-32；客户端 connect 等它才算就绪，
            // ChannelClient.kt handleFrame initialize 分支只认类型 200）
            sendRpcFrame(
                session,
                responseType = ResponseType.initialize.rawValue,
                id = 0,
                body = RPCValue.Undefined,
                wireKind = WIRE_INITIALIZE,
                wireDetail = "initialize",
            )
            for (frame in wsSession.incoming) {
                if (frame is Frame.Binary) serveFrame(session, frame.data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 客户端断开/读写异常：静默收尾（服务端测试替身无重连面）
        } finally {
            session.open = false
            connections.remove(session)
        }
    }

    /** 单条入站协议帧：13 字节头解帧 → header/body 反序列化 → 按 [RequestType] 分派。 */
    private suspend fun serveFrame(session: SessionContext, data: ByteArray) {
        val payload = RPCFrame.read(data) ?: return
        val cursor = ReadCursor(0)
        val headerValue = RPCSerialization.deserialize(payload, cursor) ?: return
        val body = RPCSerialization.deserialize(payload, cursor) ?: return
        val header = (headerValue as? RPCValue.Arr)?.items ?: return
        when (header.getOrNull(0)?.intValue) {
            RequestType.promise.rawValue -> {
                val id = header.getOrNull(1)?.intValue ?: return
                val channelName = header.getOrNull(2)?.stringValue ?: ""
                val command = header.getOrNull(3)?.stringValue ?: ""
                synchronized(lock) {
                    rpcCalls.add(RecordedCall(nextOrderLocked(), channelName, command, System.currentTimeMillis()))
                }
                dispatchPromise(session, id, command, body)
            }
            RequestType.eventListen.rawValue -> {
                val id = header.getOrNull(1)?.intValue ?: return
                val eventName = header.getOrNull(3)?.stringValue ?: return
                synchronized(session.mutex) { session.eventIds[eventName] = id }
                synchronized(lock) { eventListenRecord.add(eventName) }
            }
            RequestType.eventDispose.rawValue -> {
                val id = header.getOrNull(1)?.intValue ?: return
                synchronized(session.mutex) { session.eventIds.entries.removeAll { it.value == id } }
            }
        }
    }

    private suspend fun dispatchPromise(session: SessionContext, id: Int, command: String, arg: RPCValue) {
        val args = arg.jsonValue
        try {
            // 方法级故障注水前置到 try 内分派头（2026-10-10 中继轮）：原在 else 分支仅及
            // 未内置方法，内建命令（helloConversationV4 等）注水静默无效——瞬态失败退避
            // 用例需要内建命令可注水。必须在 try 内抛（StubFault → promiseError 回执），
            // 置于 try 外会让调用方挂到超时（DirectConnectE2ETest 实证回归）。
            val injected = synchronized(lock) { faultInjectedMethods[command] }
            if (injected != null) throw StubFault(injected)
            // A6 assertReady 门（上游 assertReady 镜像，仅中继桥 ctx 生效）：hello→clientHello
            // 未完成的全新 facade 上，assertReady 族调用必拒——「桥重开必须重握手」的替身级
            // 判定面（客户端 onBridgeOpened 钩子漏重握手时本门必炸）；同样依赖 try 内转
            // promiseError 回执
            if (session.assertReadyGate &&
                synchronized(session.mutex) { session.clientId } == null &&
                ASSERT_READY_COMMAND_PREFIXES.any { command.startsWith(it) }
            ) {
                throw StubFault("fault.connection.handshakeRequired")
            }
            val reply: PromiseReply = when (command) {
                "helloConversationV4" -> PromiseReply(helloBody())
                "initializeConversationV4" -> PromiseReply(handleClientHello(session, args))
                "subscribeConversationV4" -> handleSubscribeConversation(session, args)
                "subscribeSessionsIndexV4" -> handleSubscribeSessionsIndex(session, args)
                "subscribeWorkspaceConfigV4" -> handleSubscribeWorkspaceConfig(session, args)
                "resyncConversationV4" -> handleResyncConversation(session, args)
                "resyncSessionsIndexV4" -> handleResyncSessionsIndex(session, args)
                "unsubscribeConversationV4",
                "unsubscribeSessionsIndexV4",
                "unsubscribeWorkspaceConfigV4",
                -> PromiseReply(stubObj("ok" to JSONValue.Bool(true)))
                // bootstrap-request（中继 app 层请求的局域网替身对等面）：回执
                // {result:{version, desktopAppVersion, tasks}}——tasks 为 [setBootstrapTasks]
                // 注水的跨工作区清单（iOS RelayChannelClient.swift:155-159 消费同源）
                "bootstrap-request" -> PromiseReply(bootstrapResponseBody())
                "sendConversationCommandV4" -> PromiseReply(handleConversationCommand(session, args))
                // 附件四方法（A-4/A11，2026-10-10 附件链路轮）：strict 对拍 + 上传事务账本
                "attachmentBeginV4" -> PromiseReply(handleAttachmentBegin(session, args))
                "attachmentChunkV4" -> PromiseReply(handleAttachmentChunk(session, args))
                "attachmentCommitV4" -> PromiseReply(handleAttachmentCommit(session, args))
                "attachmentAbortV4" -> PromiseReply(handleAttachmentAbort(session, args))
                else -> {
                    val injected = synchronized(lock) { faultInjectedMethods[command] }
                    if (injected != null) throw StubFault(injected)
                    val custom = synchronized(lock) { methodHandlers[command] }
                        ?: throw StubFault("stub.methodNotImplemented:$command")
                    PromiseReply(custom(args))
                }
            }
            sendRpcFrame(session, ResponseType.promiseSuccess.rawValue, id, RPCValue.json(reply.body), WIRE_PROMISE_SUCCESS, command)
            reply.afterReply?.invoke()
        } catch (fault: StubFault) {
            sendRpcFrame(
                session,
                ResponseType.promiseError.rawValue,
                id,
                RPCValue.json(stubObj("message" to JSONValue.Str(fault.message), "name" to JSONValue.Str("ErrorObj"))),
                WIRE_PROMISE_ERROR,
                command,
            )
        } catch (e: Throwable) {
            sendRpcFrame(
                session,
                ResponseType.promiseError.rawValue,
                id,
                RPCValue.json(stubObj("message" to JSONValue.Str("stub: internal error: ${e.message}"), "name" to JSONValue.Str("ErrorObj"))),
                WIRE_PROMISE_ERROR,
                command,
            )
        }
    }

    // MARK: 握手（A5）

    /**
     * hello 回执（helloMessageSchema 镜像，transport.ts:32-58 + 真实发射
     * zcodeAgentService.ts:4902-4921）。capabilities 即 host 能力集宣告，
     * clientHello 的 capabilities 只能在此集内取键（A5 单向宣告）。
     */
    private fun helloBody(): JSONValue {
        val capabilities = synchronized(lock) { LinkedHashMap(serverCapabilities) }
        return stubObj(
            "kind" to JSONValue.Str("hello"),
            "protocolVersion" to JSONValue.Int(STUB_V4_WIRE_VERSION),
            "connectionId" to JSONValue.Str("conn-stub-" + UUID.randomUUID().toString().take(8)),
            "clientMode" to JSONValue.Str("web-remote-replayable"),
            // superRefine：clientMode=web-remote-replayable ⇒ deliveryProfile=replayable（transport.ts:44-51）
            "deliveryProfile" to JSONValue.Str("replayable"),
            // timestampSchema = z.number()（core.ts:19）——epoch 毫秒数，非 ISO 串
            "serverTime" to stubNum(System.currentTimeMillis()),
            "capabilities" to JSONValue.Obj(capabilities),
            "auth" to stubObj("userId" to JSONValue.Str("user-e2e-1")),
        )
    }

    /**
     * clientHello 校验（clientHelloSchema 镜像，transport.ts:68-90 `.strict()`）：
     * kind/protocolVersion=3/clientId/clientKind 词表/appVersion/capabilities 两键闭集。
     * A5 单向宣告：host hello 见到 workflowRunDeltas===true 之前，客户端携带该键 = 整条拒收。
     * 通过后登记 clientId（信封 clientId 逐字比对的权威源，A2）。
     */
    private fun handleClientHello(session: SessionContext, args: JSONValue?): JSONValue {
        val errors = ArrayList<String>()
        val capabilityViolations = ArrayList<String>()
        var accepted = false
        var clientId: String? = null
        if (args == null || args.objectValue == null) {
            errors.add("clientHello: 期望 object 参数")
        } else {
            val check = PValidator.validate(args, schema = clientHelloSchema(), path = "clientHello")
            errors += check.errors
            if (check.isValid) {
                clientId = args["clientId"]?.stringValue
                val hostDeclaredDeltas = synchronized(lock) { serverCapabilities["workflowRunDeltas"]?.boolValue == true }
                val claimedDeltas = args["capabilities"]?.objectValue?.get("workflowRunDeltas")?.boolValue == true
                if (claimedDeltas && !hostDeclaredDeltas) {
                    capabilityViolations.add(
                        "capabilities.workflowRunDeltas: host hello 未宣告 workflowRunDeltas=true，客户端不得宣告（A5 单向宣告，transport.ts:79-86）",
                    )
                }
                if (capabilityViolations.isEmpty()) {
                    accepted = true
                    session.clientId = clientId
                }
            }
        }
        synchronized(lock) {
            handshakes.add(
                RecordedClientHello(nextOrderLocked(), args ?: JSONValue.Null, errors.toList(), capabilityViolations.toList(), accepted, clientId),
            )
        }
        if (!accepted) {
            throw StubFault("stub.clientHelloRejected: " + (errors + capabilityViolations).joinToString("; "))
        }
        return stubObj("ok" to JSONValue.Bool(true)) // iOS 替身同形 {ok:true}（:1076-1078）
    }

    /** clientHelloSchema 的 PSchema 镜像（strict；词表逐字 transport.ts:68-90）。 */
    private fun clientHelloSchema(): PSchema {
        return PSchema.Obj(
            PObjectShape(
                fields = mapOf(
                    "kind" to PField.required(PSchema.enumeration("clientHello")),
                    "protocolVersion" to PField.required(PSchema.Integer(min = STUB_V4_WIRE_VERSION, max = STUB_V4_WIRE_VERSION)),
                    "clientId" to PField.required(PSchema.nonEmptyString),
                    "clientKind" to PField.optional(PSchema.enumeration("desktop", "web", "mobileRemote", "mobileApp")),
                    "appVersion" to PField.required(PSchema.string),
                    "capabilities" to PField.optional(
                        PSchema.Obj(
                            PObjectShape(
                                fields = mapOf(
                                    "workspaceHookReviewUi" to PField.optional(PSchema.Bool),
                                    "workflowRunDeltas" to PField.optional(PSchema.Bool),
                                ),
                                strict = true,
                                deny = emptySet(),
                            ),
                        ),
                    ),
                ),
                strict = true,
                deny = emptySet(),
            ),
        )
    }

    // MARK: 订阅（A8）

    private suspend fun handleSubscribeConversation(session: SessionContext, args: JSONValue?): PromiseReply {
        val topic = args?.get("topic")?.stringValue ?: throw StubFault("stub: subscribeConversationV4 缺 topic 参数")
        if (failConversationSubscribe) {
            synchronized(lock) { conversationSubscribeRejections += 1 }
            throw StubFault("stub: conversation subscribe denied")
        }
        recordSubscribe(topic, args)
        synchronized(session.mutex) { session.conversationTopics.add(topic) }
        val ackBody = stubObj(
            "ok" to JSONValue.Bool(true),
            "subscriptionId" to JSONValue.Str(SUB_ID_CONVERSATION),
            "mode" to JSONValue.Str("snapshot"),
            "logEpoch" to JSONValue.Str(StubV4Frames.DEFAULT_LOG_EPOCH),
        )
        if (snapshotBeforeSubscribeAck) {
            // A8：订阅即发快照——同协程在订阅回执帧**之前**同步发送（wire 序即到端序，
            // 复现中继/桌面「快照帧先于回执到达」竞态：客户端帧 handler 晚注册即静默丢
            // revision/logEpoch 回填）
            sendConversationSnapshot(session, topic)
            return PromiseReply(body = ackBody)
        }
        // 屏障对照组：回执先发、快照延迟补发
        return PromiseReply(body = ackBody, afterReply = {
            delay(SUBSCRIBE_ACK_THEN_SNAPSHOT_DELAY_MS)
            if (session.open) sendConversationSnapshot(session, topic)
        })
    }

    private suspend fun handleSubscribeSessionsIndex(session: SessionContext, args: JSONValue?): PromiseReply {
        val topic = args?.get("topic")?.stringValue ?: throw StubFault("stub: subscribeSessionsIndexV4 缺 topic 参数")
        recordSubscribe(topic, args)
        val ackBody = stubObj(
            "ok" to JSONValue.Bool(true),
            "subscriptionId" to JSONValue.Str(SUB_ID_SESSIONS_INDEX),
            "mode" to JSONValue.Str("snapshot"),
            "logEpoch" to JSONValue.Str(SESSIONS_LOG_EPOCH),
        )
        if (snapshotBeforeSubscribeAck) {
            // A8 同上：快照帧先于订阅回执
            sendSessionsIndexSnapshot(session, topic)
            return PromiseReply(body = ackBody)
        }
        return PromiseReply(body = ackBody, afterReply = {
            delay(SUBSCRIBE_ACK_THEN_SNAPSHOT_DELAY_MS)
            if (session.open) sendSessionsIndexSnapshot(session, topic)
        })
    }

    /**
     * workspace-config 订阅：仅回执（桌面 v3.14.4 对手机不推本 topic，协议文档 §6.5
     * 实测口径）；config 快照注水为后续里程碑扩展点（methodHandlers/帧构造器已备）。
     */
    private fun handleSubscribeWorkspaceConfig(session: SessionContext, args: JSONValue?): PromiseReply {
        val topic = args?.get("topic")?.stringValue ?: throw StubFault("stub: subscribeWorkspaceConfigV4 缺 topic 参数")
        recordSubscribe(topic, args)
        return PromiseReply(
            body = stubObj(
                "ok" to JSONValue.Bool(true),
                "subscriptionId" to JSONValue.Str(SUB_ID_WORKSPACE_CONFIG),
                "mode" to JSONValue.Str("snapshot"),
            ),
        )
    }

    /** resync：回执后全量快照重发（base:null = 无水位重发快照，幂等；§6.4）。 */
    private fun handleResyncConversation(session: SessionContext, @Suppress("UNUSED_PARAMETER") args: JSONValue?): PromiseReply {
        val topics = synchronized(session.mutex) { session.conversationTopics.toList() }
        return PromiseReply(body = stubObj("ok" to JSONValue.Bool(true)), afterReply = {
            for (topic in topics) {
                if (session.open) sendConversationSnapshot(session, topic)
            }
        })
    }

    private fun handleResyncSessionsIndex(session: SessionContext, @Suppress("UNUSED_PARAMETER") args: JSONValue?): PromiseReply {
        // resync 参数只携 subscriptionId/base，不携 topic——sessions-index 每连接单 topic（按连接 workspace）
        val topic = SESSIONS_INDEX_TOPIC_PREFIX + workspacePath
        return PromiseReply(body = stubObj("ok" to JSONValue.Bool(true)), afterReply = {
            if (session.open) sendSessionsIndexSnapshot(session, topic)
        })
    }

    /**
     * bootstrap-request 回执体（iOS RelayChannelClient.swift:156-159 消费形态）：
     * `{result:{version, desktopAppVersion, tasks}}`——tasks = [setBootstrapTasks]
     * 注水的跨工作区任务索引（缺省空清单；web「所有项目目录」同源数据面）。
     */
    private fun bootstrapResponseBody(): JSONValue {
        val tasks = synchronized(lock) { bootstrapTasksOverride } ?: JSONValue.Arr(emptyList())
        return stubObj(
            "result" to stubObj(
                "version" to JSONValue.Int(1),
                "desktopAppVersion" to JSONValue.Str(STUB_DESKTOP_APP_VERSION),
                "initialViewState" to stubObj("kind" to JSONValue.Str("stub")),
                "tasks" to tasks,
            ),
        )
    }

    private fun recordSubscribe(topic: String, args: JSONValue?) {
        synchronized(lock) {
            subscribedTopicsRecord.add(topic)
            subscribeTargetsRecord.add(
                RecordedSubscribe(
                    topic = topic,
                    workspacePath = args?.get("workspacePath")?.stringValue,
                    workspaceIdentity = args?.get("workspaceIdentity")?.stringValue,
                ),
            )
        }
    }

    // MARK: sendConversationCommandV4（A13 + strict 对拍）

    /**
     * 信封处理三步（上游 parseCommandEnvelope 镜像，command.ts:340-367）：
     * ① 信封 strict 对拍（CommandSchemas.envelopeShape，A4 参数层 strict——嵌套信封
     *    缺失/未知键/CAS 词表缺水位/可信字段即拒）；② payload 按 type 对拍（已移植词表）；
     * ③ 行为注水回 ACK。对拍失败回 rejected+proto.invalidPayload（拒因原文断言数据源）。
     */
    private suspend fun handleConversationCommand(session: SessionContext, args: JSONValue?): JSONValue {
        val outer = args?.objectValue
        val envelopeValue = outer?.get("envelope")
        val envelope = envelopeValue?.objectValue
        val type = envelope?.get("type")?.stringValue ?: ""
        if (type == "applyFileRewind") {
            // 移动端唯一拦截的 v4 type（ReadOnlyGate 直写黑名单对拍计数，iOS 原文 :1824-1828）
            synchronized(lock) { blockedWriteCommandCount += 1 }
        }

        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var casGateError: String? = null
        var clientIdMatchesHandshake: Boolean? = null
        // UPSTREAM_MAIN 代 CAS 裁决产物（非空 = 信封校验通过后直接回该 ACK，stale/rejected）
        var casVerdict: JSONValue? = null

        if (envelopeValue == null) {
            errors.add("envelope: 缺失嵌套信封（必须 {envelope:{…}, workspacePath} 嵌套形态，协议文档 §7.1）")
        } else {
            val envelopeCheck = PValidator.validate(
                envelopeValue,
                schema = PSchema.Obj(CommandSchemas.envelopeShape()),
                path = "envelope",
            )
            errors += envelopeCheck.errors
            warnings += envelopeCheck.warnings
            envelope?.get("payload")?.let { payload ->
                CommandSchemas.payloadSchema(type)?.let { schema ->
                    val payloadCheck = PValidator.validate(payload, schema = schema, path = "payload")
                    errors += payloadCheck.errors
                    warnings += payloadCheck.warnings
                }
            }
            val clientId = envelope?.get("clientId")?.stringValue
            clientIdMatchesHandshake = clientId != null && session.clientId != null && clientId == session.clientId
            if (session.clientId != null && clientIdMatchesHandshake == false) {
                // A2：clientId 与握手注册值逐字相等，违反即上游 fault.command.clientMismatch
                errors.add("envelope.clientId: 与握手注册 clientId 不一致（A2，上游 fault.command.clientMismatch）")
            }
            // CAS 门（双代语义）：
            // - parse 层（command.ts:340-367 parseCommandEnvelope 镜像，两代同文）：CAS 15
            //   命令缺 baseRevision 或 row-target 5 命令缺 baseLogEpoch → rejected +
            //   message "CAS commands require baseRevision and baseLogEpoch"；
            // - decide 层（UPSTREAM_MAIN 代，command-inbox.ts:323-363 镜像）：信封键全后
            //   账本裁决——row-target baseLogEpoch ≠ 权威 → **stale** proto.staleLogEpoch；
            //   baseRevision ≠ 权威 → **stale** proto.staleRevision + revisionAtDecision=
            //   权威 revision（客户端 sendCommand 回填后原样重发一次即命中——sendCASWithRetry
            //   三段自愈的活体靶场）。
            val missingBaseRevision =
                CommandSchemas.commandsRequiringBaseRevision.contains(type) &&
                    envelope?.containsKey("baseRevision") != true
            val missingBaseLogEpoch =
                CommandSchemas.rowTargetingCommands.contains(type) &&
                    envelope?.get("baseLogEpoch")?.stringValue == null
            if (missingBaseRevision || missingBaseLogEpoch) {
                casGateError = "CAS commands require baseRevision and baseLogEpoch"
            }
            val baseRevisionValue = envelope?.get("baseRevision")?.let { value ->
                when (value) {
                    is JSONValue.Int -> value.value.toLong()
                    is JSONValue.Double ->
                        if (value.value == kotlin.math.floor(value.value)) value.value.toLong() else null
                    else -> null
                }
            }
            if (casGateError == null &&
                synchronized(lock) { casGeneration } == CasGeneration.UPSTREAM_MAIN &&
                CommandSchemas.commandsRequiringBaseRevision.contains(type)
            ) {
                val casSessionId = envelope?.get("sessionId")?.stringValue
                val casCommandId = envelope?.get("commandId")?.stringValue ?: ""
                if (!casSessionId.isNullOrEmpty()) {
                    val revision = authoritativeRevision(casSessionId)
                    if (baseRevisionValue == null) {
                        // decide() :326-333 防御分支（正常不可达——parse 层已拒键缺/值畸形信封）
                        casVerdict = casAckLocked(
                            casCommandId, "rejected", "proto.missingBaseRevision",
                            message = null, revisionAtDecision = revision,
                        )
                    } else if (CommandSchemas.rowTargetingCommands.contains(type) &&
                        envelope?.get("baseLogEpoch")?.stringValue != authoritativeLogEpoch(casSessionId)
                    ) {
                        // decide() :340-351：row-target epoch 不符 → stale proto.staleLogEpoch
                        casVerdict = casAckLocked(
                            casCommandId, "stale", "proto.staleLogEpoch",
                            message = null, revisionAtDecision = revision,
                        )
                    } else if (baseRevisionValue != revision.toLong()) {
                        // decide() :352-363：revision 不符 → stale proto.staleRevision +
                        // revisionAtDecision=权威 revision（stale-once 自愈的正确 revision 回执）
                        casVerdict = casAckLocked(
                            casCommandId, "stale", "proto.staleRevision",
                            message = null, revisionAtDecision = revision,
                        )
                    }
                }
            }
        }

        val commandId = envelope?.get("commandId")?.stringValue ?: ""
        val sessionIdValue = envelope?.get("sessionId")
        val sessionId = sessionIdValue?.stringValue
        val record = RecordedCommandEnvelope(
            order = nextOrder(),
            raw = args ?: JSONValue.Null,
            envelope = envelopeValue,
            commandId = commandId.ifEmpty { null },
            clientId = envelope?.get("clientId")?.stringValue,
            clientIdMatchesHandshake = clientIdMatchesHandshake,
            type = type,
            sessionId = sessionIdValue,
            issuedAt = envelope?.get("issuedAt"),
            baseRevision = envelope?.get("baseRevision"),
            baseLogEpoch = envelope?.get("baseLogEpoch"),
            workspacePath = outer?.get("workspacePath")?.stringValue,
            workspaceIdentity = outer?.get("workspaceIdentity")?.stringValue,
            schemaErrors = errors.toList(),
            schemaWarnings = warnings.toList(),
            casGateError = casGateError,
        )
        synchronized(lock) {
            commandEnvelopes.add(record)
            // 记账口径：被拒命令同样计数（iOS 替身 `_switchModelConfigCallCount` 同语义——
            // 「客户端发了多少次」包含被拒的第一次）
            val nth = (commandCallCounts[type] ?: 0) + 1
            commandCallCounts[type] = nth
            record.callNth = nth
        }

        // strict 对拍 / CAS 门失败 → rejected（不空耗注定被拒的写；拒因原文上屏断言源）
        if (errors.isNotEmpty() || casGateError != null) {
            record.ackStatus = "rejected"
            return stubObj(
                "commandId" to nullableStr(commandId),
                "status" to JSONValue.Str("rejected"),
                "reasonCode" to JSONValue.Str("proto.invalidPayload"),
                "message" to JSONValue.Str((errors + listOfNotNull(casGateError)).joinToString("; ")),
                "revisionAtDecision" to JSONValue.Int(revisionAtDecisionFor(sessionId)),
            )
        }
        // decide 层裁决（UPSTREAM_MAIN 代 stale/rejected；A13 六态 + revisionAtDecision=权威）
        val verdict = casVerdict
        if (verdict != null) {
            record.ackStatus = StubAck.statusOf(verdict)
            synchronized(lock) { acksSentRecord.add(verdict) }
            return verdict
        }

        val nthCall = synchronized(lock) {
            val nth = (behaviorCallCounts[type] ?: 0) + 1
            behaviorCallCounts[type] = nth
            nth
        }
        var behavior = synchronized(lock) { ackBehaviors[type] } ?: defaultBehaviorFor(type)

        // createSession：回执携规范 sessionId（§7.2 实证回执形态
        // {status:accepted, result:{type:"createSession", sessionId:"sess_*", input:{inputId, delivery}}}）；
        // 带 firstInput = 建会话并开跑首条 turn（写 userInput+assistant 行），不带 = draft 空会话
        if (type == "createSession" && behavior is StubAckBehavior.Accept && behavior.result == null) {
            val newSessionId = "sess-e2e-" + UUID.randomUUID().toString().take(6)
            val firstInputText = envelope?.get("payload")?.objectValue
                ?.get("firstInput")?.objectValue?.get("text")?.stringValue
            synchronized(lock) {
                if (firstInputText != null) {
                    appendTurnRowsLocked(newSessionId, firstInputText, sourceCommandId = commandId)
                }
            }
            behavior = StubAckBehavior.Accept(
                result = stubObj(
                    "type" to JSONValue.Str("createSession"),
                    "sessionId" to JSONValue.Str(newSessionId),
                    "input" to stubObj(
                        "inputId" to JSONValue.Str("input-" + UUID.randomUUID().toString().take(8)),
                        "delivery" to JSONValue.Str("startNow"),
                    ),
                ),
            )
        }

        val body = StubAck.body(
            commandId = commandId,
            behavior = behavior,
            nthCall = nthCall,
            revisionAtDecision = revisionAtDecisionFor(sessionId),
        )
        record.ackStatus = StubAck.statusOf(body)
        synchronized(lock) { acksSentRecord.add(body) }

        // 权威成功态才落副作用（与客户端「权威成功才落盘」口径对齐）
        if (StubAck.statusOf(body) == "accepted") {
            applyAcceptedSideEffects(envelope ?: emptyMap(), commandId)
        }
        return body
    }

    /** 默认 ACK 行为：switchModelConfig = 真机实证 stale-once-then-accept（活跃会话单发必撞 stale）；其余 accepted。 */
    private fun defaultBehaviorFor(type: String): StubAckBehavior {
        return when (type) {
            "switchModelConfig" -> StubAckBehavior.StaleOnceThenAccept
            else -> StubAckBehavior.Accept()
        }
    }

    /** accepted 命令的会话副作用：sendText 写用户行（携 sourceCommandId）+ 助手行并推增量帧。 */
    private fun applyAcceptedSideEffects(envelope: Map<String, JSONValue>, commandId: String) {
        val type = envelope["type"]?.stringValue ?: return
        if (type != "sendText") return
        val sessionId = envelope["sessionId"]?.stringValue ?: return
        val text = envelope["payload"]?.objectValue?.get("text")?.stringValue ?: ""
        val userRow: JSONValue
        val assistantRow: JSONValue
        synchronized(lock) {
            val turnId = "turn-" + UUID.randomUUID().toString().take(8)
            val userRowId = nextRowIdLocked(sessionId)
            val assistantRowId = nextRowIdLocked(sessionId)
            userRow = StubV4Frames.userRow(
                rowId = userRowId,
                turnId = turnId,
                text = text,
                sourceCommandId = commandId,
                createdAtSeq = userRowId,
            )
            assistantRow = StubV4Frames.assistantRow(
                rowId = assistantRowId,
                turnId = turnId,
                text = "替身回执 · 已收到「$text」",
                createdAtSeq = assistantRowId,
            )
            sessionRows.getOrPut(sessionId) { ArrayList() }.add(userRow)
            sessionRows.getValue(sessionId).add(assistantRow)
            lastSendTextPayload = envelope["payload"] ?: JSONValue.Null
        }
        pushConversationRows(sessionId, listOf(userRow, assistantRow))
    }

    /** createSession 带 firstInput 时同形写两行（桌面开跑语义）。 */
    private fun appendTurnRowsLocked(sessionId: String, firstInputText: String, sourceCommandId: String) {
        val turnId = "turn-" + UUID.randomUUID().toString().take(8)
        val userRowId = nextRowIdLocked(sessionId)
        val assistantRowId = nextRowIdLocked(sessionId)
        val rows = sessionRows.getOrPut(sessionId) { ArrayList() }
        rows.add(
            StubV4Frames.userRow(
                rowId = userRowId,
                turnId = turnId,
                text = firstInputText,
                sourceCommandId = sourceCommandId,
                createdAtSeq = userRowId,
            ),
        )
        rows.add(
            StubV4Frames.assistantRow(
                rowId = assistantRowId,
                turnId = turnId,
                text = "替身助手：首条指令已收到，v4 链路正常",
                createdAtSeq = assistantRowId,
            ),
        )
    }

    private fun nextRowIdLocked(sessionId: String): Int {
        val next = (nextRowIds[sessionId] ?: 0) + 1
        nextRowIds[sessionId] = next
        return next
    }

    /** 会话 revision（账本缺省基线 [StubV4Frames.DEFAULT_REVISION]；UPSTREAM_MAIN 裁决/回填同源）。 */
    private fun revisionAtDecisionFor(sessionId: String?): Int = authoritativeRevision(sessionId)

    /** decide 层 CAS 裁决回执构造（commandAckSchema 键集：commandId/status/reasonCode/revisionAtDecision）。 */
    private fun casAckLocked(
        commandId: String,
        status: String,
        reasonCode: String,
        message: String?,
        revisionAtDecision: Int,
    ): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["commandId"] = nullableStr(commandId) ?: JSONValue.Str("")
        fields["status"] = JSONValue.Str(status)
        fields["reasonCode"] = JSONValue.Str(reasonCode)
        if (message != null) fields["message"] = JSONValue.Str(message)
        fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
        return JSONValue.Obj(fields)
    }

    // MARK: 附件四方法（A-4/A11 strict 对拍，2026-10-10 附件链路轮）
    //
    // 上游权威（【实证·上游仓】）：
    // - wire 键面 transport.ts:846-952——Begin 8 键（uploadId/sessionId/fileName/mime/
    //   totalBytes/totalChunks/checksum + host 注入 connectionId）/ Chunk 5 键 /
    //   Commit·Abort 3 键，全 .strict()；workspace 信封键（workspacePath/workspaceIdentity?）
    //   由 scope 层 getClient(params) 消费（zcodeAgentService.ts:3037），服务端实际校验
    //   含信封（2026-10-06 真机实证：缺 workspacePath 被 Invalid params 拒）——故替身
    //   对四方法一律要求 workspacePath 在场。
    // - zero-byte superRefine（transport.ts:859-868 原文 message）：totalBytes==0 与
    //   totalChunks==0 必须同真同假，异态即拒。
    // - chunk superRefine（transport.ts:910-926）：base64 非法（长度非 4 倍数/非法字符/
    //   '=' 位置错）即 "invalid base64"；解码后 > attachmentChunkMaxBytes(512KiB) 即超限。
    //   两件由 :protocol StrictBase64 镜像承担（构造与校验同源）。
    // - connectionId 出现在客户端参数 = 违反「服务端注入」纪律（withTrustedConnection
    //   先删后写真值，zcodeAgentConnectionScope.ts:91-110；真机多发即 Invalid params，
    //   2026-10-06 甲-2/A11）——不在 shape 键集，strict 即拒（替身比上游 facade 的
    //   strip 口径更严，方向安全，与文件头纪律一致）。
    // 拒收形态：strict/业务门失败 → StubFault（promiseError，客户端解码为 RPCError.message
    // 原文）；受理回执体按上游 result schema：Begin state 判别联合 / Chunk {uploadId,
    // nextChunkIndex} / Commit {ref} / Abort {}（transport.ts:885-952）。

    /** 上传事务 staging 态（账本值；received 严格顺序块 → 解码字节数）。 */
    private class StubUploadStaging(
        val sessionId: String,
        val fileName: String,
        val mime: String,
        val totalBytes: Int,
        val totalChunks: Int,
        val checksum: String,
    ) {
        /** chunkIndex → 解码字节数（严格顺序：下一块恒 = received.size）。 */
        val received = java.util.TreeMap<Int, Int>()
    }

    private fun attachmentRecord(method: String, args: JSONValue?): RecordedAttachmentCall {
        val call = RecordedAttachmentCall(order = nextOrder(), method = method, raw = args ?: JSONValue.Null)
        synchronized(lock) { attachmentCallsRecord.add(call) }
        return call
    }

    /** 对拍失败收口：StubFault（拒因原文回客户端，promiseError 形态）。 */
    private fun attachmentFault(message: String): Nothing {
        throw StubFault(message)
    }

    /** 附件四方法公共门：方法级故障注水（与 dispatch else 分支同面）→ strict schema 对拍。 */
    private fun attachmentGate(
        method: String,
        call: RecordedAttachmentCall,
        shape: PObjectShape,
    ): Map<String, JSONValue> {
        val injected = synchronized(lock) { faultInjectedMethods[method] }
        if (injected != null) {
            call.businessError = injected
            attachmentFault(injected)
        }
        val errors = ArrayList<String>()
        val check = PValidator.validate(call.raw, schema = PSchema.Obj(shape), path = method)
        errors += check.errors
        // workspacePath 恒在场（2026-10-06 真机实证：缺 workspacePath 被桌面 Invalid params 拒；
        // shape 为宽容白名单键、此处按桌面行为收严）
        if (call.raw.objectValue?.get("workspacePath")?.stringValue == null) {
            errors.add("$method.workspacePath: 缺失必填键（桌面实校验含会话域信封）")
        }
        if (errors.isNotEmpty()) {
            call.schemaErrors = errors.toList()
            attachmentFault("Invalid params: " + errors.joinToString("; "))
        }
        return call.raw.objectValue!!
    }

    /** 开启（或幂等续接）上传事务。同 uploadId 重发按已收块数回报续传起点（web PCe 同款）。 */
    private fun handleAttachmentBegin(
        session: SessionContext,
        @Suppress("UNUSED_PARAMETER") args: JSONValue?,
    ): JSONValue {
        val call = attachmentRecord("attachmentBeginV4", args)
        val dict = attachmentGate("attachmentBeginV4", call, CommandSchemas.attachmentBeginParamsShape())
        // zero-byte superRefine（transport.ts:859-868 上游原文 message）：
        // totalBytes==0 与 totalChunks==0 必须同真同假，异态即拒
        val totalBytesZ = dict["totalBytes"]?.intValue
        val totalChunksZ = dict["totalChunks"]?.intValue
        if (totalBytesZ != null && totalChunksZ != null && (totalBytesZ == 0) != (totalChunksZ == 0)) {
            call.schemaErrors = listOf("totalChunks: zero-byte upload must declare zero chunks")
            attachmentFault("Invalid params: totalChunks: zero-byte upload must declare zero chunks")
        }
        val uploadId = dict["uploadId"]?.stringValue ?: ""
        val totalBytes = dict["totalBytes"]?.intValue ?: 0
        val totalChunks = dict["totalChunks"]?.intValue ?: 0
        val checksum = dict["checksum"]?.stringValue ?: ""

        // committed 短路：checksum+totalBytes 命中已上传附件 → 免分块直取 ref（桌面去重语义）
        val committedRef = synchronized(lock) { committedRefsByChecksum["$checksum:$totalBytes"] }
        if (committedRef != null) {
            val reply = stubObj(
                "uploadId" to JSONValue.Str(uploadId),
                "state" to JSONValue.Str("committed"),
                "nextChunkIndex" to JSONValue.Int(totalChunks),
                "ref" to JSONValue.Str(committedRef),
            )
            call.reply = reply
            return reply
        }
        synchronized(lock) {
            val existing = uploadStagings[uploadId]
            if (existing == null || existing.checksum != checksum || existing.totalBytes != totalBytes) {
                // 新事务 / 同 id 异文件（客户端重开）：登记 staging（已收块归零）
                uploadStagings[uploadId] = StubUploadStaging(
                    sessionId = dict["sessionId"]?.stringValue ?: "",
                    fileName = dict["fileName"]?.stringValue ?: "",
                    mime = dict["mime"]?.stringValue ?: "",
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    checksum = checksum,
                )
            }
        }
        val received = synchronized(lock) { uploadStagings[uploadId]?.received?.size ?: 0 }
        val reply = stubObj(
            "uploadId" to JSONValue.Str(uploadId),
            "state" to JSONValue.Str("staging"),
            "nextChunkIndex" to JSONValue.Int(received),
        )
        call.reply = reply
        return reply
    }

    /** 分块下发：严格顺序（下一块恒 = 已收块数）；坏 padding/超 512KiB 已被 strict 门拒。 */
    private fun handleAttachmentChunk(
        session: SessionContext,
        @Suppress("UNUSED_PARAMETER") args: JSONValue?,
    ): JSONValue {
        val call = attachmentRecord("attachmentChunkV4", args)
        val dict = attachmentGate("attachmentChunkV4", call, CommandSchemas.attachmentChunkParamsShape())
        val uploadId = dict["uploadId"]?.stringValue ?: ""
        val chunkIndex = dict["chunkIndex"]?.intValue ?: -1
        val staging = synchronized(lock) { uploadStagings[uploadId] }
        if (staging == null) {
            call.businessError = "stub: 未知 uploadId（Begin 未登记）"
            attachmentFault("fault.attachment.uploadNotFound: stub: 未知 uploadId（Begin 未登记）")
        }
        val decodedBytes = PValidator.strictBase64ByteLength(dict["dataBase64"]?.stringValue ?: "")
        synchronized(lock) {
            if (chunkIndex != staging.received.size) {
                call.businessError =
                    "stub: chunkIndex 错位（期望 ${staging.received.size} 实收 $chunkIndex）"
                attachmentFault(call.businessError!!)
            }
            staging.received[chunkIndex] = decodedBytes ?: 0
        }
        val reply = stubObj(
            "uploadId" to JSONValue.Str(uploadId),
            "nextChunkIndex" to JSONValue.Int(synchronized(lock) { staging.received.size }),
        )
        call.reply = reply
        return reply
    }

    /** 事务收口：全块收齐 → 发 ref 并落 checksum 表（后续同文件 Begin 走 committed 短路）。 */
    private fun handleAttachmentCommit(
        session: SessionContext,
        @Suppress("UNUSED_PARAMETER") args: JSONValue?,
    ): JSONValue {
        val call = attachmentRecord("attachmentCommitV4", args)
        val dict = attachmentGate("attachmentCommitV4", call, CommandSchemas.attachmentFinishParamsShape())
        val uploadId = dict["uploadId"]?.stringValue ?: ""
        val staging = synchronized(lock) { uploadStagings[uploadId] }
        if (staging == null) {
            call.businessError = "stub: 未知 uploadId（Begin 未登记）"
            attachmentFault("fault.attachment.uploadNotFound: stub: 未知 uploadId（Begin 未登记）")
        }
        synchronized(lock) {
            if (staging.received.size != staging.totalChunks) {
                call.businessError =
                    "stub: 提交时块未收齐（${staging.received.size}/${staging.totalChunks}）"
                attachmentFault(call.businessError!!)
            }
        }
        val ref = "att-stub-" + UUID.randomUUID().toString().take(8)
        synchronized(lock) {
            committedRefsByChecksum["${staging.checksum}:${staging.totalBytes}"] = ref
            uploadStagings.remove(uploadId)
        }
        val reply = stubObj("ref" to JSONValue.Str(ref))
        call.reply = reply
        return reply
    }

    /** 失败路径中止：移册即收口（幂等——未知 uploadId 同样成功，回 strict 空对象）。 */
    private fun handleAttachmentAbort(
        session: SessionContext,
        @Suppress("UNUSED_PARAMETER") args: JSONValue?,
    ): JSONValue {
        val call = attachmentRecord("attachmentAbortV4", args)
        val dict = attachmentGate("attachmentAbortV4", call, CommandSchemas.attachmentFinishParamsShape())
        synchronized(lock) { uploadStagings.remove(dict["uploadId"]?.stringValue ?: "") }
        val reply = stubObj()
        call.reply = reply
        return reply
    }

    // MARK: 下行帧发射

    /** conversation 行增量（每行一帧 row.appended；seq 每 topic 单调；延迟错开保序）。 */
    fun pushConversationRows(sessionId: String, rows: List<JSONValue>, baseDelayMs: Long = ROW_DELTA_DELAY_MS) {
        val topic = "conversation/$sessionId"
        rows.forEachIndexed { index, row ->
            val delayMs = baseDelayMs * (index + 1)
            for (conn in connections.toList()) {
                val hasListener = synchronized(conn.mutex) { conn.open && conn.eventIds.containsKey(EVENT_CONVERSATION_FRAME) }
                if (!hasListener) continue
                val connRef = conn
                val rowRef = row
                connRef.ws.launch {
                    delay(delayMs)
                    if (!connRef.open) return@launch
                    sendConversationRowDelta(connRef, topic, rowRef)
                }
            }
        }
    }

    /** state.updated 增量（patch 键级整体替换，A10；挂起交互下发/撤下通道）。 */
    fun pushConversationStatePatch(sessionId: String, patch: Map<String, JSONValue>, delayMs: Long = ROW_DELTA_DELAY_MS) {
        val topic = "conversation/$sessionId"
        for (conn in connections.toList()) {
            val hasListener = synchronized(conn.mutex) { conn.open && conn.eventIds.containsKey(EVENT_CONVERSATION_FRAME) }
            if (!hasListener) continue
            val connRef = conn
            connRef.ws.launch {
                delay(delayMs)
                if (!connRef.open) return@launch
                val (from, to) = seqLedger.next(topic)
                val logical = StubV4Frames.deltasFrame(topic, SUB_ID_CONVERSATION, from, to, listOf(StubV4Frames.stateUpdated(patch)))
                val envelope = StubV4Frames.topicEnvelope(topic, SUB_ID_CONVERSATION, "online", logical)
                sendEventFrame(connRef, EVENT_CONVERSATION_FRAME, topic, "deltas", SUB_ID_CONVERSATION, from, to, envelope)
            }
        }
    }

    /**
     * 挂起交互下发/撤下便捷注水（挂起交互面 E2E 数据源）：patch 键级整替
     * `pendingInteractions`——[interaction] null = 撤下（空数组整替）。
     * 交互形状按上游真实形态注水（v1.31③：ExitPlanMode 挂起交互 kind="userInput"、
     * plan_approval 在 payload.schema.interaction、计划正文在 payload.input.plan——
     * 美化成顶层 kind=plan_approval 会掩盖客户端 schema 归位缺陷）。
     */
    fun pushPendingInteraction(sessionId: String, interaction: JSONValue?, delayMs: Long = ROW_DELTA_DELAY_MS) {
        val items = if (interaction != null) listOf(interaction) else emptyList()
        pushConversationStatePatch(
            sessionId = sessionId,
            patch = mapOf("pendingInteractions" to JSONValue.Arr(items)),
            delayMs = delayMs,
        )
    }

    private suspend fun sendConversationRowDelta(session: SessionContext, topic: String, row: JSONValue) {
        val (from, to) = seqLedger.next(topic)
        val logical = StubV4Frames.deltasFrame(topic, SUB_ID_CONVERSATION, from, to, listOf(StubV4Frames.rowAppended(row)))
        val envelope = StubV4Frames.topicEnvelope(topic, SUB_ID_CONVERSATION, "online", logical)
        sendEventFrame(session, EVENT_CONVERSATION_FRAME, topic, "deltas", SUB_ID_CONVERSATION, from, to, envelope)
    }

    /** conversation 快照帧（水位重置语义：seq 归零，后续增量 0→1 起步；iOS 原文 :2451-2491）。 */
    private suspend fun sendConversationSnapshot(session: SessionContext, topic: String) {
        val sessionId = topic.removePrefix(CONVERSATION_TOPIC_PREFIX)
        val snapshot: JSONValue
        synchronized(lock) {
            seqLedger.reset(topic)
            val override = conversationSnapshotOverrides[sessionId]
            snapshot = override ?: StubV4Frames.defaultConversationSnapshot(
                sessionId = sessionId,
                rows = sessionRows[sessionId]?.toList() ?: emptyList(),
                // 快照 revision 与 CAS 账本同源（bumpRevision 后 resync 即携新权威值）
                revision = revisionLedger[sessionId] ?: StubV4Frames.DEFAULT_REVISION,
            )
        }
        val logical = StubV4Frames.snapshotFrame(topic, SUB_ID_CONVERSATION, snapshot)
        val envelope = StubV4Frames.topicEnvelope(topic, SUB_ID_CONVERSATION, "initial", logical)
        sendEventFrame(session, EVENT_CONVERSATION_FRAME, topic, "snapshot", SUB_ID_CONVERSATION, 0, 0, envelope)
    }

    /** sessions-index 快照帧（fromSeq=0/toSeq=1；payload {logEpoch, sessions:[…]}，§6.5）。 */
    private suspend fun sendSessionsIndexSnapshot(session: SessionContext, topic: String) {
        val override = synchronized(lock) { sessionsIndexSnapshotOverride }
        val snapshot = override ?: defaultSessionsIndexSnapshot()
        val logical = StubV4Frames.snapshotFrame(topic, SUB_ID_SESSIONS_INDEX, snapshot)
        val envelope = StubV4Frames.topicEnvelope(topic, SUB_ID_SESSIONS_INDEX, "initial", logical)
        sendEventFrame(session, EVENT_SESSIONS_INDEX_FRAME, topic, "snapshot", SUB_ID_SESSIONS_INDEX, 0, 1, envelope)
    }

    /** sessions-index 缺省快照：双会话骨架（sessionRow 形状 §6.5：phase 词表 + workspacePath 分组键）。 */
    private fun defaultSessionsIndexSnapshot(): JSONValue {
        val now = System.currentTimeMillis()
        return stubObj(
            "logEpoch" to JSONValue.Str(SESSIONS_LOG_EPOCH),
            "sessions" to JSONValue.Arr(
                listOf(
                    stubObj(
                        "sessionId" to JSONValue.Str("sess-e2e-1"),
                        "title" to JSONValue.Str("替身会话 · 冒烟验收"),
                        "phase" to JSONValue.Str("running"),
                        "lastActivityAt" to stubNum(now),
                        "lastAssistantPreview" to JSONValue.Str("替身端持续输出中…"),
                        "workspacePath" to JSONValue.Str(workspacePath),
                    ),
                    stubObj(
                        "sessionId" to JSONValue.Str("sess-e2e-2"),
                        "title" to JSONValue.Str("替身会话 · 已完成基线"),
                        "phase" to JSONValue.Str("completedSuccess"),
                        "lastActivityAt" to stubNum(now - 7_200_000L),
                        "lastAssistantPreview" to JSONValue.Str("基线检查完成。"),
                        "workspacePath" to JSONValue.Str(workspacePath),
                    ),
                ),
            ),
        )
    }

    /** 事件帧下发：仅在客户端已 eventListen 该事件时发送并记账（iOS fire() 同口径，:2590-2600）。 */
    private suspend fun sendEventFrame(
        session: SessionContext,
        eventName: String,
        topic: String,
        payloadKind: String,
        subscriptionId: String,
        fromSeq: Int?,
        toSeq: Int?,
        envelope: JSONValue,
    ) {
        val eventId = synchronized(session.mutex) {
            if (!session.open) return
            session.eventIds[eventName]
        } ?: return
        val header = RPCSerialization.serialize(
            RPCValue.Arr(listOf(RPCValue.Int(ResponseType.eventFire.rawValue), RPCValue.Int(eventId))),
        )
        val bodyBytes = RPCSerialization.serialize(RPCValue.json(envelope))
        val frameBytes = wireFrame(session, header + bodyBytes)
        synchronized(lock) {
            val order = nextOrderLocked()
            wireLog.add(WireLogEntry(order, WIRE_EVENT_FRAME, "$eventName|$topic|$payloadKind", System.currentTimeMillis()))
            framesSent.add(RecordedFrame(order, eventName, topic, subscriptionId, payloadKind, fromSeq, toSeq))
        }
        session.frameSink(frameBytes)
    }

    private suspend fun sendRpcFrame(
        session: SessionContext,
        responseType: Int,
        id: Int,
        body: RPCValue,
        wireKind: String,
        wireDetail: String,
    ) {
        val header = RPCSerialization.serialize(RPCValue.Arr(listOf(RPCValue.Int(responseType), RPCValue.Int(id))))
        val bodyBytes = RPCSerialization.serialize(body)
        val frameBytes = wireFrame(session, header + bodyBytes)
        synchronized(lock) {
            wireLog.add(WireLogEntry(nextOrderLocked(), wireKind, wireDetail, System.currentTimeMillis()))
        }
        session.frameSink(frameBytes)
    }

    /** 线缆帧形态：中继桥 = 裸 serialize 载荷；局域网 = 13 字节头二进制帧（RelaySessionContext.relayBridge 注）。 */
    private fun wireFrame(session: SessionContext, payload: ByteArray): ByteArray =
        if (session.relayBridge) payload else RPCFrame.write(payload)

    // MARK: rpc-frame 中继模式（同进程第二端口；协议接口文档 §3.3）
    //
    // 与局域网直连的三层差异（RelayTransport.kt 文件头 framingDeltas 同源）：
    // ① WS 层是 JSON 文本帧（auth_*/pair_status_*/data/error）；
    // ② app 载荷走 data 帧 payload（bootstrap/bridge-open 请求-响应）；
    // ③ rpc-frame 载荷 = serialize(header)+serialize(body)（无 13 字节头）。
    //
    // A6 全新握手态建模：每次 workspace-bridge-open 都换新 SessionContext（clientId/
    // eventIds/conversationTopics 全空 + assertReadyGate=true）——等价上游「每次桥(重)开
    // 都是全新 scoped facade、handshakeComplete 实例态」；客户端不在 onBridgeOpened 里
    // 重做 hello→clientHello，重开后的 assertReady 族调用即在本替身撞
    // fault.connection.handshakeRequired。

    /** 中继模式 WS 端口（startRelay 后非零）。 */
    @Volatile
    var relayPort: Int = 0
        private set

    private var relayEngine: EmbeddedServer<*, *>? = null

    // auth 凭据注水（配对链接形态；proof 按 RelayAuth 同参复算——错凭据即 AUTH_FAILED 终态）
    @Volatile
    var relayDeviceSid: String = "d-stub-relay-terminal"

    @Volatile
    var relayPassHash: String = "stub-relay-pass-hash"

    /** 终态注水：auth_response 恒回 error 帧 AUTH_FAILED（客户端终态粘滞用例面）。 */
    @Volatile
    var relayFailAuth: Boolean = false

    /** 一次性注水：下一次 workspace-bridge-open 回 workspace-bridge-error（bridgeSessionId 匹配面拒开）。 */
    @Volatile
    private var relayRejectNextBridgeOpen: String? = null

    private val relayTerminals = CopyOnWriteArrayList<RelayTerminal>()

    private class RelayTerminal(val ws: WebSocketServerSession) {
        @Volatile var authed = false
        var nonce: String = ""
    }

    /**
     * 一次桥开好的上下文（A6：每桥一个全新 SessionContext）。下行 rpc-frame 计数器
     * ** transport 级**（[relayDownPhysicalSeq]/[relayDownMessageSeq]）不随桥重开归零——
     * iOS 客户端的 RelayFrameAssembler.reset() 不清 highestCompletedMessageSeq（原文同口径，
     * :protocol relay/RelayFrameCodec.kt 同注），即「对端发送计数跨桥单调」的桌面侧形态；
     * 上行（客户端 → 替身）按桥全新 assembler（客户端 bindFrameChannel 归零发送计数）。
     */
    private class RelayBridge(
        val sessionId: String,
        val generation: Int,
        val recoveryId: String?,
        val ctx: SessionContext,
    ) {
        val assembler = RelayFrameAssembler()
    }

    /** 桌面下行 rpc-frame 计数（transport 级，跨桥单调；见 RelayBridge 注）。 */
    private var relayDownPhysicalSeq = 1
    private var relayDownMessageSeq = 1

    @Volatile
    private var currentRelayBridge: RelayBridge? = null

    // 中继面记账
    private var relayBridgeOpenCountN = 0

    /** 桥内 rpc 字节派发记账（桥 gen + 派发动作；联调/用例断言面）。 */
    private val relayBridgeRpcLogN = ArrayList<String>()
    private val relayBridgeGenerationsN = ArrayList<Int>()
    private val relayBridgeRecoveryIdsN = ArrayList<String?>()
    private var relayAuthCountN = 0
    private var relayDownlinkAckCountN = 0
    private val relayViewStateUpdatesN = ArrayList<JSONValue>()

    /** 启动中继模式监听（同进程第二端口；可先于/后于 [start] 调用）。 */
    @Synchronized
    fun startRelay(): Int {
        check(relayEngine == null) { "E2EStubServer 中继模式已启动" }
        val probed = ServerSocket(0, 1, InetAddress.getByName(host)).use { socket -> socket.localPort }
        val started = embeddedServer(CIO, host = host, port = probed) { configureRelay() }
        started.start(wait = false)
        relayEngine = started
        relayPort = probed
        return probed
    }

    @Synchronized
    fun stopRelay() {
        relayEngine?.stop(200, 1000)
        relayEngine = null
        relayPort = 0
    }

    private fun Application.configureRelay() {
        install(WebSockets)
        routing {
            // 云中继 WS 升级路径（wss://<host>/ws?mid=… 的替身对等面；mid 替身不校验）
            webSocket("/ws") {
                serveRelayTerminal(this)
            }
        }
    }

    private suspend fun serveRelayTerminal(ws: WebSocketServerSession) {
        val terminal = RelayTerminal(ws)
        relayTerminals.add(terminal)
        try {
            for (frame in ws.incoming) {
                // 中继全程 JSON 文本帧；二进制帧不合预期，忽略（客户端读循环同口径）
                if (frame is Frame.Text) serveRelayText(terminal, frame.readText())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 客户端断开/读写异常：静默收尾
        } finally {
            terminal.authed = false
            relayTerminals.remove(terminal)
        }
    }

    private suspend fun serveRelayText(terminal: RelayTerminal, text: String) {
        val message = try {
            JSONValue.parse(text)
        } catch (e: Exception) {
            return
        }
        when (message["type"]?.stringValue) {
            "auth_init" -> {
                // 24 位随机 nonce（iOS 实测形态 lFr2Po5uGyuNti2Xuw7LfcrN 同长度）
                val nonce = UUID.randomUUID().toString().replace("-", "").take(24)
                terminal.nonce = nonce
                sendRelayJson(
                    terminal,
                    stubObj("type" to JSONValue.Str("auth_challenge"), "nonce" to JSONValue.Str(nonce)),
                )
            }
            "auth_response" -> {
                if (relayFailAuth) {
                    sendRelayError(terminal, "AUTH_FAILED", "stub: auth 注水失败")
                    return
                }
                val sid = message["device_sid"]?.stringValue ?: ""
                val proof = message["proof"]?.stringValue ?: ""
                val expected = RelayAuth.proof(
                    passHash = relayPassHash,
                    nonce = terminal.nonce,
                    role = "terminal",
                    deviceSid = sid,
                )
                if (sid != relayDeviceSid || proof != expected) {
                    sendRelayError(terminal, "AUTH_FAILED", "stub: sid/proof 不匹配")
                    return
                }
                terminal.authed = true
                synchronized(lock) { relayAuthCountN += 1 }
                sendRelayJson(
                    terminal,
                    stubObj(
                        "type" to JSONValue.Str("auth_ack"),
                        "pair_status" to JSONValue.Str("matched"),
                        "terminal_sid" to JSONValue.Str(sid),
                    ),
                )
            }
            "pair_status_query" -> sendRelayJson(
                terminal,
                stubObj(
                    "type" to JSONValue.Str("pair_status_ack"),
                    "pair_status" to JSONValue.Str("matched"),
                    "terminal_sid" to JSONValue.Str(relayDeviceSid),
                ),
            )
            "data" -> serveRelayData(terminal, message["payload"])
            else -> {}
        }
    }

    private suspend fun serveRelayData(terminal: RelayTerminal, payload: JSONValue?) {
        val zcodeType = payload?.get("zcode_type")?.stringValue ?: return
        when (zcodeType) {
            "rpc-frame" -> serveRelayRpcFrame(terminal, payload)
            "rpc-frame-ack" -> synchronized(lock) { relayDownlinkAckCountN += 1 }
            "bootstrap-request" -> {
                // iOS RelayChannelClient.swift:156-159 消费形态：{requestId, result:{…}}
                val body = bootstrapResponseBody()
                sendRelayData(
                    terminal,
                    stubObj(
                        "zcode_type" to JSONValue.Str("bootstrap-response"),
                        "requestId" to payload["requestId"],
                        "result" to (body["result"] ?: JSONValue.Null),
                    ),
                )
            }
            "workspace-list-request" -> sendRelayData(
                terminal,
                stubObj(
                    "zcode_type" to JSONValue.Str("workspace-list-response"),
                    "requestId" to payload["requestId"],
                    "result" to stubObj(
                        "activeWorkspaceKey" to JSONValue.Str(workspacePath),
                        // 桌面当前无活动任务（web 形态：键可缺席/为 null）
                        "workspaces" to JSONValue.Arr(
                            listOf(
                                stubObj(
                                    "workspaceKey" to JSONValue.Str(workspacePath),
                                    "path" to JSONValue.Str(workspacePath),
                                    "name" to JSONValue.Str("替身工作区"),
                                    "kind" to JSONValue.Str("local"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            "workspace-bridge-open" -> handleRelayBridgeOpen(terminal, payload)
            "mobile-view-state-update" -> synchronized(lock) { relayViewStateUpdatesN.add(payload) }
            else -> {}
        }
    }

    /** workspace-bridge-open → 换新桥 ctx（A6 全新握手态）→ workspace-bridge-ready → 桥内 Initialize。 */
    private suspend fun handleRelayBridgeOpen(terminal: RelayTerminal, payload: JSONValue) {
        val requestedKey = payload["workspaceKey"]?.stringValue ?: ""
        val generation = payload["bridgeGeneration"]?.intValue ?: 1
        val recoveryId = payload["recoveryId"]?.stringValue
        val bridgeSessionId = payload["bridgeSessionId"]?.stringValue
            ?: UUID.randomUUID().toString().replace("-", "").lowercase()

        // 一次性拒开注水：workspace-bridge-error 携 bridgeSessionId → 客户端按
        // bridgeOpenWaiter 匹配面失败（RelayTransport.rejectErrorPayload 情形②）
        val rejectReason = synchronized(lock) {
            val reason = relayRejectNextBridgeOpen
            relayRejectNextBridgeOpen = null
            reason
        }
        if (rejectReason != null) {
            sendRelayData(
                terminal,
                stubObj(
                    "zcode_type" to JSONValue.Str("workspace-bridge-error"),
                    "requestId" to payload["requestId"],
                    "bridgeSessionId" to JSONValue.Str(bridgeSessionId),
                    "reason" to JSONValue.Str("stub.bridgeRejected"),
                    "error" to JSONValue.Str(rejectReason),
                ),
            )
            return
        }

        // A6：旧桥 ctx 作废（open=false + 摘出 connections，增量推送不再投递）
        val old = synchronized(lock) { currentRelayBridge }
        if (old != null) {
            old.ctx.open = false
            connections.remove(old.ctx)
        }
        val ctx = SessionContext(terminal.ws)
        ctx.assertReadyGate = true
        ctx.relayBridge = true
        // recoveryId 桌面侧铸造（重开恢复凭据；客户端下次 bridge-open 携回——客户端侧
        // 断言「重建开桥携回首桥 recoveryId」的数据源）
        val mintedRecoveryId = "recovery-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val bridge = RelayBridge(bridgeSessionId, generation, mintedRecoveryId, ctx)
        ctx.frameSink = { bytes -> relayBridgeSend(bridge, bytes) }
        synchronized(lock) {
            currentRelayBridge = bridge
            relayBridgeOpenCountN += 1
            relayBridgeGenerationsN.add(generation)
            relayBridgeRecoveryIdsN.add(mintedRecoveryId)
            relayBridgeRpcLogN.add("bridge=$generation << bridge-open recoveryId=${recoveryId ?: "null"}")
            connections.add(ctx)
        }

        // workspace-bridge-ready（bridge 键面 = iOS RelayChannelClient.openBridge 消费面）
        sendRelayData(
            terminal,
            stubObj(
                "zcode_type" to JSONValue.Str("workspace-bridge-ready"),
                "requestId" to payload["requestId"],
                "bridge" to stubObj(
                    "bridgeSessionId" to JSONValue.Str(bridgeSessionId),
                    "bridgeGeneration" to JSONValue.Int(generation),
                    "recoveryId" to JSONValue.Str(mintedRecoveryId),
                    "kind" to JSONValue.Str("local"),
                    "workspacePath" to JSONValue.Str(requestedKey),
                    "workspaceKey" to JSONValue.Str(requestedKey),
                    "initialTaskId" to JSONValue.Null,
                ),
            ),
        )
        // 桌面行为：桥开即推 Initialize（channelServer.ts:29-32 同构；客户端 waitForInitialize 等它）。
        // 时序对齐真桌面：Initialize 在 bridge-ready 之后一个 RTT 内到达，客户端先完成
        // bindFrameChannel 再收帧——iOS Lzn 门（frameIdentity 未绑定静默丢弃）对该竞态同样
        // 成立，真桌面时序不触发；替身若同协程紧贴 ready 推送会稳定踩中丢弃窗（150ms ≈
        // RTT 形态），非放宽客户端校验。会话域 ws.launch 推送（勿用裸 CoroutineScope）。
        val bridgeForInit = bridge
        terminal.ws.launch {
            delay(RELAY_INITIALIZE_PUSH_DELAY_MS)
            if (!bridgeForInit.ctx.open) return@launch
            try {
                sendRpcFrame(
                    bridgeForInit.ctx,
                    responseType = ResponseType.initialize.rawValue,
                    id = 0,
                    body = RPCValue.Undefined,
                    wireKind = WIRE_INITIALIZE,
                    wireDetail = "initialize(bridge)",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 桥已替换/连接已断：静默（替身无重连面）
            }
        }
    }

    /** 桥内下行发送：serialize 载荷 → rpc-frame 编码（分片自适应）→ data JSON 文本帧。 */
    private suspend fun relayBridgeSend(bridge: RelayBridge, bytes: ByteArray) {
        val identity = RelayFrameCodec.Identity(
            bridgeSessionId = bridge.sessionId,
            bridgeGeneration = bridge.generation,
            recoveryId = bridge.recoveryId,
        )
        val firstPhysicalSeq: Int
        val messageSeq: Int
        val frames: List<Map<String, JSONValue>>
        synchronized(lock) {
            frames = RelayFrameCodec.encodeMessage(
                bytes,
                identity = identity,
                firstPhysicalSeq = relayDownPhysicalSeq,
                messageSeq = relayDownMessageSeq,
            ) ?: return
            firstPhysicalSeq = relayDownPhysicalSeq
            messageSeq = relayDownMessageSeq
            relayDownMessageSeq += 1
            relayDownPhysicalSeq += frames.size
            relayBridgeRpcLogN.add("bridge=${bridge.generation} >> ${frames.size}片 ${bytes.size}B open=${bridge.ctx.open}")
        }
        for (frame in frames) {
            if (!bridge.ctx.open) return
            sendRelayData(bridge, JSONValue.Obj(LinkedHashMap(frame)))
        }
    }

    /** data 帧下发（桥级）：{type:"data", payload, client_ts}；经桥 ctx 绑定的终端 WS 送出。 */
    private suspend fun sendRelayData(bridge: RelayBridge, payload: JSONValue) {
        val terminal = relayTerminals.firstOrNull { it.ws == bridge.ctx.ws }
        if (terminal == null) {
            println("[relay-stub] sendRelayData(bridge) 无匹配终端 terminals=${relayTerminals.size} zcode=${payload["zcode_type"]?.stringValue}")
            return
        }
        sendRelayData(terminal, payload)
    }

    private suspend fun sendRelayData(terminal: RelayTerminal, payload: JSONValue) {
        sendRelayJson(
            terminal,
            stubObj(
                "type" to JSONValue.Str("data"),
                "payload" to payload,
                "client_ts" to stubNum(System.currentTimeMillis()),
            ),
        )
    }

    private suspend fun sendRelayJson(terminal: RelayTerminal, payload: JSONValue) {
        terminal.ws.send(Frame.Text(payload.data?.toString(Charsets.UTF_8) ?: "{}"))
    }

    private suspend fun sendRelayError(terminal: RelayTerminal, code: String, messageText: String) {
        sendRelayJson(
            terminal,
            stubObj(
                "type" to JSONValue.Str("error"),
                "code" to JSONValue.Str(code),
                "message" to JSONValue.Str(messageText),
            ),
        )
    }

    /** 客户端上行 rpc-frame：ack 记账 / 身份三元组校验 / 分片重组 / 回 ack / 桥内派发。 */
    private suspend fun serveRelayRpcFrame(terminal: RelayTerminal, payload: JSONValue) {
        // 上行 ack（客户端对我下行帧的确认）记账
        if (RelayFrameCodec.decodeAck(payload) != null) {
            synchronized(lock) { relayDownlinkAckCountN += 1 }
            return
        }
        val bridge = synchronized(lock) { currentRelayBridge } ?: return
        // 桥身份三元组校验（客户端 handleRelayFramePayload Lzn 同款；异桥帧忽略）
        if (payload["bridgeSessionId"]?.stringValue != bridge.sessionId ||
            payload["bridgeGeneration"]?.intValue?.let { it != bridge.generation } == true
        ) {
            return
        }
        val fragment = RelayFrameCodec.decode(payload) ?: return
        when (val result = bridge.assembler.accept(fragment)) {
            is RelayFrameAssembler.AcceptResult.Incomplete ->
                result.ackMessageSeq?.let { relayBridgeAck(bridge, it) }
            is RelayFrameAssembler.AcceptResult.Duplicate ->
                relayBridgeAck(bridge, result.ackMessageSeq)
            is RelayFrameAssembler.AcceptResult.Fault ->
                relayBridgeAck(bridge, fragment.messageSeq) // 防客户端无限重传
            is RelayFrameAssembler.AcceptResult.Completed -> {
                relayBridgeAck(bridge, result.messageSeq)
                serveRelayRpcBytes(bridge, result.data)
            }
        }
    }

    /** 上行 rpc-frame-ack（客户端 RelayTransport.sendAck/makeAck 同键面）。 */
    private suspend fun relayBridgeAck(bridge: RelayBridge, ackMessageSeq: Int) {
        sendRelayData(
            bridge,
            RelayFrameCodec.makeAck(
                identity = RelayFrameCodec.Identity(
                    bridgeSessionId = bridge.sessionId,
                    bridgeGeneration = bridge.generation,
                    recoveryId = bridge.recoveryId,
                ),
                ackMessageSeq = ackMessageSeq,
            ).let { JSONValue.Obj(LinkedHashMap(it)) },
        )
    }

    /** 桥内 RPC 字节派发（无 13 字节头，直接 deserialize；serveFrame 的桥对等面）。 */
    private suspend fun serveRelayRpcBytes(bridge: RelayBridge, data: ByteArray) {
        val cursor = ReadCursor(0)
        val headerValue = RPCSerialization.deserialize(data, cursor)
        if (headerValue == null) {
            synchronized(lock) { relayBridgeRpcLogN.add("bridge=${bridge.generation} << deserialize-header=null") }
            return
        }
        val body = RPCSerialization.deserialize(data, cursor)
        if (body == null) {
            synchronized(lock) { relayBridgeRpcLogN.add("bridge=${bridge.generation} << deserialize-body=null") }
            return
        }
        val header = (headerValue as? RPCValue.Arr)?.items
        if (header == null) {
            synchronized(lock) { relayBridgeRpcLogN.add("bridge=${bridge.generation} << header-not-arr") }
            return
        }
        when (header.getOrNull(0)?.intValue) {
            RequestType.promise.rawValue -> {
                val id = header.getOrNull(1)?.intValue ?: return
                val channelName = header.getOrNull(2)?.stringValue ?: ""
                val command = header.getOrNull(3)?.stringValue ?: ""
                synchronized(lock) {
                    rpcCalls.add(RecordedCall(nextOrderLocked(), channelName, command, System.currentTimeMillis()))
                }
                // 桥内 promise body = 参数数组（toService 把调用参数列表整体序列化，host 端
                // fromService 按 apply(o, p) 展开——RelayChannelClient.call 直译注；与局域网
                // 裸 arg 形态不同，桥内派发先展开首元素）
                synchronized(lock) {
                    relayBridgeRpcLogN.add("bridge=${bridge.generation} << promise id=$id $channelName.$command")
                }
                val arg = (body as? RPCValue.Arr)?.items?.getOrNull(0) ?: body
                dispatchPromise(bridge.ctx, id, command, arg)
            }
            RequestType.eventListen.rawValue -> {
                val id = header.getOrNull(1)?.intValue ?: return
                val eventName = header.getOrNull(3)?.stringValue ?: return
                synchronized(bridge.ctx.mutex) { bridge.ctx.eventIds[eventName] = id }
                synchronized(lock) { eventListenRecord.add(eventName) }
            }
            RequestType.eventDispose.rawValue -> {
                val id = header.getOrNull(1)?.intValue ?: return
                synchronized(bridge.ctx.mutex) { bridge.ctx.eventIds.entries.removeAll { it.value == id } }
            }
        }
    }

    /** 服务端主动断开全部中继 WS（客户端走传输故障 → 指数退避重连 → paired 恢复重建桥）。 */
    fun dropRelayConnections() {
        for (terminal in relayTerminals.toList()) {
            terminal.authed = false
            // cancel 会话协程（非挂起、立即生效）：连接硬断 → 客户端 OkHttp onClosed/onFailure
            // → 读循环退出 → 传输故障链路。曾试 ws.close（挂起，需会话域 launch）与裸
            // CoroutineScope launch——CIO 会话上不生效（本轮联调实证），勿改回。
            runCatching { terminal.ws.cancel(CancellationException("stub drop")) }
        }
    }

    /** 一次性注水：下一次 workspace-bridge-open 回 workspace-bridge-error。 */
    fun injectRelayBridgeOpenReject(message: String) {
        synchronized(lock) { relayRejectNextBridgeOpen = message }
    }

    /**
     * 主动宣告当前桥退化（bridge-degraded 帧；无 requestId 的桌面主动推送形态，
     * C-13——客户端 handleBridgeDegradedFrame → 快速重建桥用例面）。
     */
    fun pushRelayBridgeDegraded(reason: String) {
        val bridge = synchronized(lock) { currentRelayBridge } ?: return
        val ws = bridge.ctx.ws
        ws.launch {
            sendRelayData(
                bridge,
                stubObj(
                    "zcode_type" to JSONValue.Str("bridge-degraded"),
                    "bridgeSessionId" to JSONValue.Str(bridge.sessionId),
                    "reason" to JSONValue.Str(reason),
                ),
            )
        }
    }

    // MARK: 中继面记账访问器（用例断言面）

    fun relayBridgeOpenCount(): Int = synchronized(lock) { relayBridgeOpenCountN }

    /** 历次 workspace-bridge-open 的 bridgeGeneration 序（重开递增断言面）。 */
    fun relayBridgeGenerations(): List<Int> = synchronized(lock) { relayBridgeGenerationsN.toList() }

    /** 桥内 rpc 字节派发记账副本（命令名逐条；「客户端发了没/替身收了没」分诊面）。 */
    fun relayBridgeRpcLog(): List<String> = synchronized(lock) { relayBridgeRpcLogN.toList() }

    /** 历次桥的桌面铸造 recoveryId（客户端重开携回断言面；与请求携带值对账用）。 */
    fun relayBridgeRecoveryIds(): List<String?> = synchronized(lock) { relayBridgeRecoveryIdsN.toList() }


    fun relayAuthCount(): Int = synchronized(lock) { relayAuthCountN }

    /** 客户端对我下行 rpc-frame 的 ack 计数（分片可靠层双向工作断言面）。 */
    fun relayDownlinkAckCount(): Int = synchronized(lock) { relayDownlinkAckCountN }

    fun relayViewStateCount(): Int = synchronized(lock) { relayViewStateUpdatesN.size }

    fun relayViewStatePayloads(): List<JSONValue> = synchronized(lock) { relayViewStateUpdatesN.toList() }

    /** 当前桥 sessionId（null = 无桥）。 */
    fun currentRelayBridgeSessionId(): String? = synchronized(lock) { currentRelayBridge?.sessionId }

    // MARK: 记账访问器（用例断言面；返回快照副本）

    fun rpcCallLog(): List<RecordedCall> = synchronized(lock) { rpcCalls.toList() }

    fun rpcCallCount(command: String): Int = synchronized(lock) { rpcCalls.count { it.command == command } }

    /** 全部 sendConversationCommandV4 入站信封记账（strict 对拍结果在 schemaErrors）。 */
    fun envelopes(): List<RecordedCommandEnvelope> = synchronized(lock) { commandEnvelopes.toList() }

    fun acksSent(): List<JSONValue> = synchronized(lock) { acksSentRecord.toList() }

    fun framesSent(): List<RecordedFrame> = synchronized(lock) { framesSent.toList() }

    /** 发送序账（帧与回执混排；A8 断言入口）。 */
    fun wireLog(): List<WireLogEntry> = synchronized(lock) { wireLog.toList() }

    fun handshakes(): List<RecordedClientHello> = synchronized(lock) { handshakes.toList() }

    fun subscribedTopics(): List<String> = synchronized(lock) { subscribedTopicsRecord.toList() }

    fun subscribeTargets(): List<RecordedSubscribe> = synchronized(lock) { subscribeTargetsRecord.toList() }

    fun rowsOf(sessionId: String): List<JSONValue> = synchronized(lock) { sessionRows[sessionId]?.toList() ?: emptyList() }

    fun commandCallCount(type: String): Int = synchronized(lock) { commandCallCounts[type] ?: 0 }

    fun blockedWriteCount(): Int = synchronized(lock) { blockedWriteCommandCount }

    fun conversationSubscribeRejectionCount(): Int = synchronized(lock) { conversationSubscribeRejections }

    fun lastSendTextPayload(): JSONValue? = synchronized(lock) { lastSendTextPayload }

    fun hasEventListener(eventName: String): Boolean = connections.any { synchronized(it.mutex) { it.open && it.eventIds.containsKey(eventName) } }

    /** server-info HTTP 探测次数（连接层五步第一步记账）。 */
    fun serverInfoProbeCount(): Int = synchronized(lock) { serverInfoProbeCount }

    /** eventListen 帧记账副本（帧面挂载/幂等断言面）。 */
    fun eventListenLog(): List<String> = synchronized(lock) { eventListenRecord.toList() }

    /** 附件四方法对拍记账副本（strict 结果在 schemaErrors、业务拒因在 businessError）。 */
    fun attachmentCalls(): List<RecordedAttachmentCall> =
        synchronized(lock) { attachmentCallsRecord.toList() }

    fun attachmentCallCount(method: String): Int =
        synchronized(lock) { attachmentCallsRecord.count { it.method == method } }

    /** 指定 uploadId 的已收块数（续传断言面；未知 id = -1）。 */
    fun attachmentReceivedChunkCount(uploadId: String): Int =
        synchronized(lock) { uploadStagings[uploadId]?.received?.size ?: -1 }

    /** 指定 uploadId 各已收块的解码字节数（分块切分断言面；未知 id = 空表）。 */
    fun attachmentReceivedChunkBytes(uploadId: String): List<Int> =
        synchronized(lock) { uploadStagings[uploadId]?.received?.values?.toList() ?: emptyList() }

    // MARK: 注水器（扩展点）

    /** 会话快照整体注水（键级整体替换，A10；后续里程碑灌完整 A 区形态）。 */
    fun setConversationSnapshot(sessionId: String, snapshot: JSONValue) {
        synchronized(lock) { conversationSnapshotOverrides[sessionId] = snapshot }
    }

    fun setSessionsIndexSnapshot(snapshot: JSONValue) {
        synchronized(lock) { sessionsIndexSnapshotOverride = snapshot }
    }

    fun setAckBehavior(type: String, behavior: StubAckBehavior) {
        synchronized(lock) { ackBehaviors[type] = behavior }
    }

    /**
     * 方法级 promise 故障注水：指定 method 的 promise 调用回
     * promiseError{message=[message], name:"ErrorObj"}（客户端解码为 RPCError.message
     * 原文——连接层 fault.connection.handshakeRequired 捕获分支的用例数据源）。
     */
    fun injectMethodFault(method: String, message: String) {
        synchronized(lock) { faultInjectedMethods[method] = message }
    }

    fun clearMethodFault(method: String) {
        synchronized(lock) { faultInjectedMethods.remove(method) }
    }

    // MARK: 工具

    private fun nextOrder(): Int = synchronized(lock) { nextOrderLocked() }

    private fun nextOrderLocked(): Int {
        orderCounter += 1
        return orderCounter
    }

    private fun nullableStr(value: String?): JSONValue? = value?.takeIf { it.isNotEmpty() }?.let { JSONValue.Str(it) }

    companion object {
        /**
         * A6 assertReady 族命令前缀词表（AGENTS §7 A6：subscribe*、readSession、rowsRange
         * 过握手闸；sendConversationCommandV4 不过闸——上游同）。
         */
        val ASSERT_READY_COMMAND_PREFIXES = listOf("subscribe", "readSession", "rowsRange")

        const val CONVERSATION_TOPIC_PREFIX = "conversation/"
        const val SESSIONS_INDEX_TOPIC_PREFIX = "sessions-index/"

        const val EVENT_CONVERSATION_FRAME = "onDynamicConversationFrame"
        const val EVENT_SESSIONS_INDEX_FRAME = "onDynamicSessionsIndexFrame"
        const val EVENT_WORKSPACE_CONFIG_FRAME = "onDynamicWorkspaceConfigFrame"

        /** 订阅回执与帧发射器共用固定 subscriptionId（v1.31 修复口径：两值错配 = 增量全被客户端过滤）。 */
        const val SUB_ID_CONVERSATION = "sub-conv-e2e"
        const val SUB_ID_SESSIONS_INDEX = "sub-sess-e2e"
        const val SUB_ID_WORKSPACE_CONFIG = "sub-wcfg-e2e"

        const val SESSIONS_LOG_EPOCH = "epoch-sess-1"

        /** bootstrap-request 回执 desktopAppVersion（配对链接版本对账断言面）。 */
        const val STUB_DESKTOP_APP_VERSION = "3.14.4-stub"

        /** 行增量发射延迟基值（错开保序；iOS 替身 0.3/0.35s 的测试提速版）。 */
        const val ROW_DELTA_DELAY_MS = 30L

        /** snapshotBeforeSubscribeAck=false 时回执到快照的补发延迟。 */
        const val SUBSCRIBE_ACK_THEN_SNAPSHOT_DELAY_MS = 50L

        /** 桥内 Initialize 推送延迟（bridge-ready 后一个 RTT 形态；见 handleRelayBridgeOpen 注）。 */
        const val RELAY_INITIALIZE_PUSH_DELAY_MS = 150L

        // wireLog kind 词表（用例断言用）
        const val WIRE_INITIALIZE = "initialize"
        const val WIRE_PROMISE_SUCCESS = "promiseSuccess"
        const val WIRE_PROMISE_ERROR = "promiseError"
        const val WIRE_EVENT_FRAME = "eventFrame"
    }
}

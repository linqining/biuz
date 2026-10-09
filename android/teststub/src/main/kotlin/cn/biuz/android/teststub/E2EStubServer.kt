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
import io.ktor.websocket.Frame
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
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
 * iOS 原文：ios/Tests/E2ELoginStubServer.swift（3118 行；本轮只平移骨架，全量
 * 业务处理器——附件四方法/bots/任务组/重置卡等——经 [methodHandlers] 扩展点按里程碑补）。
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
        val frameBytes = RPCFrame.write(header + bodyBytes)
        synchronized(lock) {
            val order = nextOrderLocked()
            wireLog.add(WireLogEntry(order, WIRE_EVENT_FRAME, "$eventName|$topic|$payloadKind", System.currentTimeMillis()))
            framesSent.add(RecordedFrame(order, eventName, topic, subscriptionId, payloadKind, fromSeq, toSeq))
        }
        session.ws.send(Frame.Binary(fin = true, data = frameBytes))
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
        val frameBytes = RPCFrame.write(header + bodyBytes)
        synchronized(lock) {
            wireLog.add(WireLogEntry(nextOrderLocked(), wireKind, wireDetail, System.currentTimeMillis()))
        }
        session.ws.send(Frame.Binary(fin = true, data = frameBytes))
    }

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

        // wireLog kind 词表（用例断言用）
        const val WIRE_INITIALIZE = "initialize"
        const val WIRE_PROMISE_SUCCESS = "promiseSuccess"
        const val WIRE_PROMISE_ERROR = "promiseError"
        const val WIRE_EVENT_FRAME = "eventFrame"
    }
}

package cn.biuz.android.teststub

// 双桌面代 CAS 语义冒烟（E2EStubServer.casGeneration 注水面；AGENTS §4.5 两代并存口径）：
// - UPSTREAM_MAIN 代（command-inbox.ts:308-363 decide() 镜像）：revision 账本裁决——
//   baseRevision 落后 → stale proto.staleRevision + revisionAtDecision=权威 revision
//   （「首拒 stale 回正确 revision」——客户端 sendCommand 回填后原样重发一次即命中，
//   sendCASWithRetry 三段自愈的活体靶场）；row-target baseLogEpoch 不符 → stale
//   proto.staleLogEpoch；bumpRevision 后 resync 快照携新权威 revision；
// - LEGACY 代（缺省，2026-10-05 真机实证桌面）：缺 baseRevision/row-target 缺
//   baseLogEpoch → rejected + message "CAS commands require baseRevision and baseLogEpoch"
//   （StubServerSmokeTest 用例 3 已覆盖缺键负路，此处补「键全但 revision 不符在 LEGACY
//   代不判 stale」的代际差异面）。
//
// 协议事实来源：【实证·上游仓】/tmp/zcode-upstream
//   packages/shared/src/zcode-protocol-v4/command.ts:296-320（CAS/row-target 词表）
//   apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/command-inbox.ts:323-363（decide()）。

import cn.biuz.android.protocol.ChannelClient
import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.WebSocketTransportFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class StubCasGenerationTest {

    // MARK: UPSTREAM_MAIN 代：stale 回执自带权威 revision（stale-once 自愈正确回执）

    @Test
    fun upstreamGeneration_staleCarriesAuthoritativeRevision_thenResendHits() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            server.casGeneration = E2EStubServer.CasGeneration.UPSTREAM_MAIN
            val clientId = "zcode-mobile-casgen-1"
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client.call("zcode-agent", "helloConversationV4")
            client.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId))

            // 快照缺省权威 revision = StubV4Frames.DEFAULT_REVISION（客户端水位同源）
            val authoritative = server.authoritativeRevision("sess-e2e-1")
            assertEquals(StubV4Frames.DEFAULT_REVISION, authoritative)

            // 队列准入推进权威 revision（bump 后客户端陈旧 base 落后 = 真实 stale 成因）
            server.bumpRevision("sess-e2e-1")
            val newer = server.authoritativeRevision("sess-e2e-1")

            // 陈旧 baseRevision 发 CAS 命令 → stale proto.staleRevision + revisionAtDecision=新权威
            val staleAck = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(clientId, "sendQueuedNow", stubObj("queueItemId" to JSONValue.Str("q1")), baseRevision = authoritative),
                ).jsonValue!!,
            )
            assertEquals("stale", staleAck.status)
            assertEquals("proto.staleRevision", staleAck.reasonCode)
            assertEquals(newer, staleAck.revisionAtDecision, "stale 回执必须携带权威 revision（客户端回填后重发即命中）")

            // 客户端以回填 revision 原样重发（同 commandId）→ accepted
            val retryAck = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(clientId, "sendQueuedNow", stubObj("queueItemId" to JSONValue.Str("q1")), baseRevision = newer),
                ).jsonValue!!,
            )
            assertEquals("accepted", retryAck.status, "回填权威 revision 后重发即命中（三段自愈第二段）")
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: UPSTREAM_MAIN 代：row-target baseLogEpoch 不符 → stale proto.staleLogEpoch

    @Test
    fun upstreamGeneration_rowTargetEpochMismatch_staleLogEpoch() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            server.casGeneration = E2EStubServer.CasGeneration.UPSTREAM_MAIN
            val clientId = "zcode-mobile-casgen-2"
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client.call("zcode-agent", "helloConversationV4")
            client.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId))

            val ack = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(
                        clientId, "retryTurn",
                        stubObj("target" to stubObj("rowId" to JSONValue.Int(1), "entityId" to JSONValue.Str("ent-1"))),
                        baseRevision = server.authoritativeRevision("sess-e2e-1"),
                        baseLogEpoch = "epoch-from-dead-cli",
                    ),
                ).jsonValue!!,
            )
            assertEquals("stale", ack.status, "row-target 5 命令 epoch 不符 = stale（decide() :340-351）")
            assertEquals("proto.staleLogEpoch", ack.reasonCode)
            assertEquals(server.authoritativeRevision("sess-e2e-1"), ack.revisionAtDecision)

            // 正确 epoch（缺省基线）+ 权威 revision → accepted
            val okAck = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(
                        clientId, "retryTurn",
                        stubObj("target" to stubObj("rowId" to JSONValue.Int(1), "entityId" to JSONValue.Str("ent-1"))),
                        baseRevision = server.authoritativeRevision("sess-e2e-1"),
                        baseLogEpoch = StubV4Frames.DEFAULT_LOG_EPOCH,
                    ),
                ).jsonValue!!,
            )
            assertEquals("accepted", okAck.status)
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: bumpRevision 后 resync 快照携新权威 revision（客户端水位/回填同源）

    @Test
    fun upstreamGeneration_snapshotAfterBumpCarriesNewRevision() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            server.casGeneration = E2EStubServer.CasGeneration.UPSTREAM_MAIN
            val clientId = "zcode-mobile-casgen-3"
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client.call("zcode-agent", "helloConversationV4")
            client.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId))

            // 订阅（快照先至）拿基线 revision；帧监听先装（§5-3：服务端仅向已 eventListen
            // 的连接发帧——不装则 resync 快照静默不发）
            val received = java.util.concurrent.ConcurrentLinkedQueue<cn.biuz.android.protocol.V4TopicFrame>()
            val convSub = client.listen(
                "zcode-agent",
                E2EStubServer.EVENT_CONVERSATION_FRAME,
                RPCValue.json(stubObj("workspacePath" to JSONValue.Str(server.workspacePath))),
            ) { payload ->
                val env = payload.jsonValue?.let { cn.biuz.android.protocol.TopicWireFrame.parse(it) }
                val logical = env?.completeFrame?.let { cn.biuz.android.protocol.V4TopicFrame.parse(it) }
                if (logical != null) received.add(logical)
            }
            awaitUntil("服务端登记 conversation 帧监听") {
                server.hasEventListener(E2EStubServer.EVENT_CONVERSATION_FRAME)
            }
            val subAck = client.call(
                "zcode-agent",
                "subscribeConversationV4",
                RPCValue.json(
                    stubObj(
                        "topic" to JSONValue.Str("conversation/sess-e2e-1"),
                        "workspacePath" to JSONValue.Str(server.workspacePath),
                    ),
                ),
            ).jsonValue!!
            assertEquals(E2EStubServer.SUB_ID_CONVERSATION, subAck["subscriptionId"]?.stringValue)

            server.bumpRevision("sess-e2e-1")
            // resync（base null）→ 快照重发携新权威 revision
            client.call(
                "zcode-agent",
                "resyncConversationV4",
                RPCValue.json(
                    stubObj(
                        "subscriptionId" to JSONValue.Str(E2EStubServer.SUB_ID_CONVERSATION),
                        "base" to JSONValue.Null,
                        "workspacePath" to JSONValue.Str(server.workspacePath),
                    ),
                ),
            )
            awaitUntil("resync 快照重发") {
                received.count { it.snapshot != null } >= 2
            }
            // resync 快照携 bump 后的权威 revision（快照 revision 与 CAS 账本同源）
            val resyncSnapshot = received.filter { it.snapshot != null }.last().snapshot!!
            assertEquals(
                StubV4Frames.DEFAULT_REVISION + 1,
                resyncSnapshot["revision"]?.intValue,
                "bumpRevision 后快照 revision = 权威账本值（客户端水位/回填同源）",
            )
            // 权威 revision 已入账本（快照 revision 与裁决同源——账本断言）
            assertEquals(
                StubV4Frames.DEFAULT_REVISION + 1,
                server.authoritativeRevision("sess-e2e-1"),
            )
            // assertTrue 收尾（assertNotNull 返回泛型 T 会把 runBlocking 推成非 void 签名，
            // JUnit5 对非 void @Test 方法静默跳过——本用例曾因此不被执行）
            assertTrue(server.authoritativeLogEpoch("sess-e2e-1").isNotEmpty())
            convSub.cancel()
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: LEGACY 代：键全但 revision 不符不判 stale（旧代无账本裁决面；行为注水承担 stale-once）

    @Test
    fun legacyGeneration_staleOnlyViaBehaviorInjection_notFromLedger() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            // 缺省 LEGACY：revision 账本不参与裁决
            val clientId = "zcode-mobile-casgen-4"
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            client.call("zcode-agent", "helloConversationV4")
            client.call("zcode-agent", "initializeConversationV4", clientHelloJson(clientId))

            server.bumpRevision("sess-e2e-1")
            val ack = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(
                        clientId, "setAutoDrain", stubObj("autoDrain" to JSONValue.Bool(false)),
                        baseRevision = StubV4Frames.DEFAULT_REVISION,
                    ),
                ).jsonValue!!,
            )
            assertEquals(
                "accepted", ack.status,
                "LEGACY 代无账本裁决：键全信封不因 revision 落后被拒（stale-once 由行为注水承担，" +
                    "2026-10-05 实证桌面只查双键在场）",
            )

            // 缺 baseRevision 仍 parse 层同文拒收（两代一致）
            val missing = CommandAck(
                client.call(
                    "zcode-agent",
                    "sendConversationCommandV4",
                    envelope(clientId, "setAutoDrain", stubObj("autoDrain" to JSONValue.Bool(false))),
                ).jsonValue!!,
            )
            assertEquals("rejected", missing.status)
            assertTrue(missing.message?.contains("CAS commands require baseRevision") == true)
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: 夹具

    private fun newClient(): ChannelClient =
        ChannelClient(WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
            JavaNetWebSocketTransport(url, timeoutMs, maximumMessageSize)
        })

    private fun clientHelloJson(clientId: String): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["kind"] = JSONValue.Str("clientHello")
        fields["protocolVersion"] = JSONValue.Int(3)
        fields["clientId"] = JSONValue.Str(clientId)
        fields["clientKind"] = JSONValue.Str("mobileApp")
        fields["appVersion"] = JSONValue.Str("0.1.0-casgen")
        fields["capabilities"] = JSONValue.Obj(mapOf<String, JSONValue>())
        return RPCValue.json(JSONValue.Obj(fields))
    }

    private fun envelope(
        clientId: String,
        type: String,
        payload: JSONValue,
        baseRevision: Number? = null,
        baseLogEpoch: String? = StubV4Frames.DEFAULT_LOG_EPOCH,
    ): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["commandId"] = JSONValue.Str("cmd-casgen-$type-$baseRevision")
        fields["clientId"] = JSONValue.Str(clientId)
        fields["type"] = JSONValue.Str(type)
        fields["payload"] = payload
        fields["issuedAt"] = stubNum(System.currentTimeMillis())
        fields["sessionId"] = JSONValue.Str("sess-e2e-1")
        if (baseRevision != null) {
            val longValue = baseRevision.toLong()
            fields["baseRevision"] =
                if (longValue in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) {
                    JSONValue.Int(longValue.toInt())
                } else {
                    JSONValue.Double(longValue.toDouble())
                }
        }
        if (baseLogEpoch != null) fields["baseLogEpoch"] = JSONValue.Str(baseLogEpoch)
        return RPCValue.json(
            stubObj(
                "envelope" to JSONValue.Obj(fields),
                "workspacePath" to JSONValue.Str("/Users/e2e/zcode-workspace"),
            ),
        )
    }

    private fun awaitUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
            Thread.sleep(20)
        }
    }
}

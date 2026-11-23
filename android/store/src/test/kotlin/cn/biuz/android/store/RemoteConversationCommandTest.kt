package cn.biuz.android.store

// RemoteConversationStore 写命令族单测（v4 命令扩容批；对应 iOS :3848-4238/:4453-4501 +
// ChatViewModel v1.33 护栏 + 组织态 :2596-2739）：
// - sendCASWithRetry 三段自愈：stale 原样重发（同 commandId，A12；revisionAtDecision 回填）
//   → 再 stale 清缓存 + resync 取权威 revision 后末次重发；revision 未就绪本地拒发不上线缆；
// - switchModelConfig：空/空白 provider 本地拦截（client.providerUnavailable，禁空串裸发）；
//   switchModelForRowKey 复合键「套餐|模型」解析（modelProvidersByPlan 权威）、getView 失败
//   以会话级 state.modelSelection.providerId 兜底（v1.36①）、解析不出本地拦截、下发 model 恒纯名；
// - switchCollaborationMode ACK 三态（v1.33）：权威成功乐观落投影 + 3s 护栏防旧投影回闪、
//   失败态 chip 不动 + 常驻错误行、未知态不落盘交投影定案；
// - setFollowupMode 词表门（queue|guide；now 为本机档不下发）；
// - resolveInteraction 三族形状门：answer 恒对象（甲-3），词表外/空值本地拒发；
// - 队列五件 payload 形状 + state.queue 投影（queueInfo）；
// - row-target 族 entityId 缺失合成拒收（retryTurn/setAssistantFeedback/editUserQuery）；
// - 组织态 setPinned/setArchived/renameConversation：本地 override 即时反馈 + 归属 workspace
//   寻址 + 失败完整回滚 + fault 原文上屏（§5-12）；
// - createSession firstInput.modelSelection providerId/modelId trim + 空选本地拦截。
// Mock 仅测试装配（红线见 AGENTS §5-13）。

import cn.biuz.android.protocol.CommandAck
import cn.biuz.android.protocol.Conversation
import cn.biuz.android.protocol.ExecutorKind
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private fun ack(
    status: String,
    reasonCode: String? = null,
    message: String? = null,
    revisionAtDecision: Long? = null,
): JSONValue = testObj(
    "status" to JSONValue.Str(status),
    "reasonCode" to reasonCode?.let { JSONValue.Str(it) },
    "message" to message?.let { JSONValue.Str(it) },
    "revisionAtDecision" to revisionAtDecision?.let { testNum(it) },
)

/** 会话快照注水（revision/logEpoch 水位 + 顶层 state 键）。 */
private fun snapshotJson(
    revision: Long,
    epoch: String = "epoch-1",
    state: Map<String, JSONValue>? = null,
): JSONValue = testObj(
    "logEpoch" to JSONValue.Str(epoch),
    "revision" to testNum(revision),
    "rows" to JSONValue.Arr(emptyList()),
    "state" to state?.let { JSONValue.Obj(it) },
)

class RemoteConversationCommandTest {

    // MARK: sendCASWithRetry 三段自愈

    @Test
    fun casRetry_threeStageHealing_sameCommandIdAndAuthoritativeRevision() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        // 快照落水位：revision=7 / epoch-1
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = snapshotJson(revision = 7),
            ),
        )
        advanceUntilIdle()
        assertEquals(7L, f.store.conversationBaseWatermark("sess-1").first)
        // 注水：stale(9) → stale(9) → accepted（第三段 resync 回推 revision=9 快照）
        f.transport.sendReplyScript.addLast(
            ack("stale", reasonCode = "proto.staleRevision", revisionAtDecision = 9),
        )
        f.transport.sendReplyScript.addLast(
            ack("stale", reasonCode = "proto.staleRevision", revisionAtDecision = 9),
        )
        f.transport.sendReplyScript.addLast(ack("accepted"))
        f.transport.resyncSnapshotReplayer = { sessionId ->
            f.frameSurface.emit(
                "conversation/$sessionId",
                frame(
                    topic = "conversation/$sessionId", subscriptionId = "sub-c-1",
                    fromSeq = 4, toSeq = 5,
                    snapshot = snapshotJson(revision = 9),
                ),
            )
        }
        val result = f.store.sendQueuedNow("sess-1", "q1")
        advanceUntilIdle()
        assertEquals("accepted", result?.get("status")?.stringValue)
        // 三段共 3 发；commandId 全程不变（A12 幂等）；baseRevision 7→9→9
        assertEquals(3, f.transport.sendCallCount)
        val envelopes = f.transport.sendArgs.map { it["envelope"]!!.objectValue!! }
        val commandIds = envelopes.map { it["commandId"]?.stringValue }
        assertEquals(1, commandIds.toSet().size, "三段重发同 commandId（A12）：$commandIds")
        assertEquals(listOf(7L, 9L, 9L), envelopes.map { it["baseRevision"].longOf() })
        assertEquals(listOf("epoch-1", "epoch-1", "epoch-1"), envelopes.map { it["baseLogEpoch"]?.stringValue })
        // 第三段发生过 resync（清缓存后取权威 revision）
        assertTrue(f.transport.resyncArgs.isNotEmpty(), "第三段必须 resync 取权威 revision")
        // 队列命令 payload 形状
        assertEquals("q1", envelopes.first()["payload"]!!.objectValue!!["queueItemId"]?.stringValue)
    }

    @Test
    fun casRetry_staleOnceResolvedOnSecondSend_noResync() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = snapshotJson(revision = 7),
            ),
        )
        advanceUntilIdle()
        f.transport.sendReplyScript.addLast(ack("stale", reasonCode = "proto.staleRevision", revisionAtDecision = 9))
        f.transport.sendReplyScript.addLast(ack("accepted"))
        val result = f.store.setAutoDrain("sess-1", enabled = true)
        advanceUntilIdle()
        assertEquals("accepted", result?.get("status")?.stringValue)
        assertEquals(2, f.transport.sendCallCount, "首段 stale 回执带权威 revision → 原样重发一次即命中")
        assertTrue(f.transport.resyncArgs.isEmpty(), "第二段命中不触发第三段 resync")
        val envelopes = f.transport.sendArgs.map { it["envelope"]!!.objectValue!! }
        assertEquals(1, envelopes.map { it["commandId"]?.stringValue }.toSet().size)
        assertEquals(listOf(7L, 9L), envelopes.map { it["baseRevision"].longOf() })
        assertEquals(true, envelopes.last()["payload"]!!.objectValue!!["autoDrain"]?.boolValue)
    }

    @Test
    fun casRetry_revisionMissingLocallyRejected_neverOnWire() = runTest {
        val f = Fixture(this)
        // 不订阅（无快照无水位）→ CAS base 不可得 → 构造期本地拒发
        val result = f.store.sendQueuedNow("sess-1", "q1")
        advanceUntilIdle()
        val commandAck = CommandAck(result)
        assertEquals("rejected", commandAck.status)
        assertEquals("client.casRevisionUnavailable", commandAck.reasonCode)
        assertEquals(0, f.transport.sendCallCount, "注定被拒的写不上线缆")
        assertTrue(
            commandAck.failureText!!.contains("本地拦截") &&
                commandAck.failureText!!.contains("casRevisionUnavailable"),
            "本地拒发文案区分「本地拦截」与「桌面端拒绝」：${commandAck.failureText}",
        )
    }

    // MARK: switchModelConfig / switchModelForRowKey（v1.27/36 纪律）

    @Test
    fun switchModelConfig_blankProviderLocallyRejected() = runTest {
        val f = Fixture(this)
        val result = f.store.switchModelConfig("sess-1", provider = "   ", model = "GLM-X", thought = "high")
        val commandAck = CommandAck(result)
        assertEquals("rejected", commandAck.status)
        assertEquals("client.providerUnavailable", commandAck.reasonCode)
        assertTrue(commandAck.failureText!!.contains("本地拦截"))
        assertEquals(0, f.transport.sendCallCount, "空串 provider 禁裸发")
    }

    @Test
    fun switchModelForRowKey_compositeKeyResolvesProviderAndSendsPureModelName() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        // 快照落水位（CAS base 数据源）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3, snapshot = snapshotJson(revision = 5),
            ),
        )
        advanceUntilIdle()
        // getView 注水：同名模型跨双套餐 + per-model 思考档词表 + preferredSelection
        f.transport.getViewReply = testObj(
            "providers" to JSONValue.Arr(
                listOf(
                    testObj(
                        "providerId" to JSONValue.Str("acct:individual-coding-plan"),
                        "models" to JSONValue.Arr(
                            listOf(
                                testObj(
                                    "modelId" to JSONValue.Str("GLM-X"),
                                    "label" to JSONValue.Str("GLM-X"),
                                    "config" to testObj(
                                        "optionSpecs" to testObj(
                                            "reasoningLevel" to testObj(
                                                "values" to JSONValue.Arr(
                                                    listOf(JSONValue.Str("low"), JSONValue.Str("high")),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    testObj(
                        "providerId" to JSONValue.Str("acct:start-plan"),
                        "models" to JSONValue.Arr(
                            listOf(testObj("modelId" to JSONValue.Str("GLM-X"), "label" to JSONValue.Str("GLM-X"))),
                        ),
                    ),
                ),
            ),
            "preferredSelection" to testObj(
                "providerId" to JSONValue.Str("acct:start-plan"),
                "modelId" to JSONValue.Str("GLM-X"),
                "options" to testObj("reasoningLevel" to JSONValue.Str("high")),
            ),
        )
        // 复合行键：个人套餐行必须发出个人套餐 providerId（裸名表后到覆盖先到的 v1.27 教训）
        val result = f.store.switchModelForRowKey("sess-1", rowKey = "个人套餐|GLM-X")
        advanceUntilIdle()
        assertEquals("accepted", result?.get("status")?.stringValue)
        val envelope = f.transport.sendArgs.single()["envelope"]!!.objectValue!!
        assertEquals("switchModelConfig", envelope["type"]?.stringValue)
        val payload = envelope["payload"]!!.objectValue!!
        assertEquals("acct:individual-coding-plan", payload["provider"]?.stringValue, "复合表权威解析 providerId")
        assertEquals("GLM-X", payload["model"]?.stringValue, "下发 model 恒纯名（套餐前缀只用于本地解析）")
        assertEquals("high", payload["thought"]?.stringValue, "thought 沿用 activeThoughtLevel")
        // CAS 词表命令信封双字段在场（base 取会话水位）
        assertEquals(5L, envelope["baseRevision"].longOf(), "CAS base 取会话水位")
        assertEquals("epoch-1", envelope["baseLogEpoch"]?.stringValue)
        // per-model 词表（getView 权威源）
        assertEquals(listOf("low", "high"), f.store.thoughtLevels("GLM-X"))
    }

    @Test
    fun switchModelForRowKey_getViewFailureFallsBackToSessionModelSelectionProvider() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        // getView 恒失败（v1.36①：getView 失败/空表 → 会话级 state.modelSelection.providerId 兜底）
        f.transport.getViewError = RPCError(message = "fault.connection.handshakeRequired", name = "Error")
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = snapshotJson(
                    revision = 5,
                    state = mapOf(
                        "modelSelection" to testObj(
                            "providerId" to JSONValue.Str("acct:individual-coding-plan"),
                            "modelId" to JSONValue.Str("GLM-X"),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        // 裸名同模型切换 → provider 兜底命中
        val result = f.store.switchModelForRowKey("sess-1", rowKey = "GLM-X")
        advanceUntilIdle()
        assertEquals("accepted", result?.get("status")?.stringValue)
        val payload = f.transport.sendArgs.single()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
        assertEquals("acct:individual-coding-plan", payload["provider"]?.stringValue, "state.modelSelection.providerId 兜底")
        assertEquals("GLM-X", payload["model"]?.stringValue)
        assertEquals(5L, f.transport.sendArgs.single()["envelope"]!!.objectValue!!["baseRevision"].longOf(), "CAS base 取会话水位")
    }

    @Test
    fun switchModelForRowKey_unresolvableProviderLocallyRejected() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.transport.getViewError = RPCError(message = "timeout", name = "TimeoutError")
        // 无 state.modelSelection 投影 → 解析不出 provider → 本地拦截
        val result = f.store.switchModelForRowKey("sess-1", rowKey = "GLM-Z")
        advanceUntilIdle()
        val commandAck = CommandAck(result)
        assertEquals("rejected", commandAck.status)
        assertEquals("client.providerUnavailable", commandAck.reasonCode)
        assertEquals(0, f.transport.sendCallCount, "provider 解析不出禁裸发")
    }

    // MARK: switchCollaborationMode ACK 三态 + 3s 护栏（v1.33）

    @Test
    fun switchCollaborationMode_ackSemantics_andProjectionGuard() = runTest {
        var clock = 1_000_000L
        val f = Fixture(this, nowMs = { clock })
        f.store.messages("sess-1")
        advanceUntilIdle()
        // 桌面投影初值 build
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = snapshotJson(revision = 5, state = mapOf("config" to testObj("mode" to JSONValue.Str("build")))),
            ),
        )
        advanceUntilIdle()
        assertEquals("build", f.store.sessionCollaborationMode("sess-1"))

        // ① 权威成功：乐观落投影（不等增量回流）
        f.transport.sendReplyScript.addLast(ack("accepted"))
        val acceptedAck = f.store.switchCollaborationMode("sess-1", "plan")
        advanceUntilIdle()
        assertEquals("accepted", acceptedAck?.get("status")?.stringValue)
        assertEquals("plan", f.store.sessionCollaborationMode("sess-1"), "权威成功即乐观落 chip")
        assertNull(f.store.collaborationModeFailureText("sess-1"))
        // ② 护栏窗内旧投影不回闪（ack 与 config.mode 增量间隙）
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 4, toSeq = 4,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj("config" to testObj("mode" to JSONValue.Str("build"))),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("plan", f.store.sessionCollaborationMode("sess-1"), "3s 护栏窗内旧投影不回闪")
        // ③ 窗口过后投影覆盖（桌面真态说话）
        clock += 4_000
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 5, toSeq = 5,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj("config" to testObj("mode" to JSONValue.Str("build"))),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("build", f.store.sessionCollaborationMode("sess-1"), "护栏窗后投影覆盖")

        // ④ 失败态：chip 不动 + 常驻错误行（拒因原文）
        f.transport.sendReplyScript.addLast(ack("rejected", reasonCode = "proto.invalidPayload", message = "bad mode"))
        val rejectedAck = f.store.switchCollaborationMode("sess-1", "plan")
        advanceUntilIdle()
        assertEquals("rejected", rejectedAck?.get("status")?.stringValue)
        assertEquals("build", f.store.sessionCollaborationMode("sess-1"), "失败态 chip 不动")
        assertTrue(
            f.store.collaborationModeFailureText("sess-1")!!.contains("proto.invalidPayload"),
            "失败行携 reasonCode 原文",
        )

        // ⑤ 未知态（status 缺席）：不落盘，投影首帧定案并清失败行
        f.transport.sendReplyScript.addLast(testObj("result" to testObj("ok" to JSONValue.Bool(true))))
        val unknownAck = f.store.switchCollaborationMode("sess-1", "plan")
        advanceUntilIdle()
        assertNull(unknownAck?.get("status")?.stringValue)
        assertEquals("build", f.store.sessionCollaborationMode("sess-1"), "未知态不落盘")
        assertTrue(f.store.collaborationModeFailureText("sess-1")!!.contains("未知"))
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 6, toSeq = 6,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj("config" to testObj("mode" to JSONValue.Str("plan"))),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("plan", f.store.sessionCollaborationMode("sess-1"), "投影首帧定案")
        assertNull(f.store.collaborationModeFailureText("sess-1"), "定案清失败行")
    }

    @Test
    fun setFollowupMode_vocabularyGate_nowNeverOnWire() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3, snapshot = snapshotJson(revision = 2),
            ),
        )
        advanceUntilIdle()
        // now 档：本地拒发（web 枚举仅 queue|guide；立即语义 = 逐消息 requestedDelivery:startNow）
        val nowAck = f.store.setFollowupMode("sess-1", "now")
        assertEquals("rejected", CommandAck(nowAck).status)
        assertEquals("client.invalidFollowupMode", CommandAck(nowAck).reasonCode)
        assertEquals(0, f.transport.sendCallCount)
        // queue/guide 照常 CAS 下发
        f.store.setFollowupMode("sess-1", "queue")
        advanceUntilIdle()
        val envelope = f.transport.sendArgs.single()["envelope"]!!.objectValue!!
        assertEquals("setFollowupMode", envelope["type"]?.stringValue)
        assertEquals("queue", envelope["payload"]!!.objectValue!!["mode"]?.stringValue)
        assertNotNull(envelope["baseRevision"])
        assertEquals("epoch-1", envelope["baseLogEpoch"]?.stringValue)
    }

    // MARK: resolveInteraction 三族形状门（甲-3：answer 恒对象，禁平铺裸串）

    @Test
    fun resolveInteraction_threeFamilyShapes_alwaysObjectAnswer() = runTest {
        val f = Fixture(this)
        // 权限族 {optionId}
        f.store.resolveInteraction("sess-1", "i-1", RemoteConversationStore.InteractionAnswer.Permission("allowOnce"))
        advanceUntilIdle()
        run {
            val payload = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
            assertEquals("i-1", payload["interactionId"]?.stringValue)
            val answer = payload["answer"]
            assertTrue(answer is JSONValue.Obj, "answer 恒对象（裸串被 zod expected object 拒）")
            assertEquals("allowOnce", answer.objectValue!!["optionId"]?.stringValue)
            assertNull(payload["approved"], "approved/scope 是 wire 不存在的键（A-3）")
        }
        // 计划族 {action}
        f.store.resolveInteraction("sess-1", "i-2", RemoteConversationStore.InteractionAnswer.Plan("accept"))
        advanceUntilIdle()
        run {
            val answer = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!["answer"]!!
            assertEquals("accept", answer.objectValue!!["action"]?.stringValue)
        }
        f.store.resolveInteraction("sess-1", "i-2", RemoteConversationStore.InteractionAnswer.Plan("cancel"))
        advanceUntilIdle()
        assertEquals("cancel", f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!["answer"]!!.objectValue!!["action"]?.stringValue)
        // 计划族词表外：本地拒发
        val badPlan = f.store.resolveInteraction("sess-1", "i-2", RemoteConversationStore.InteractionAnswer.Plan("rewrite"))
        assertEquals("client.invalidInteractionAnswer", CommandAck(badPlan).reasonCode)
        // 提问族 {freeText}（trim）
        f.store.resolveInteraction("sess-1", "i-3", RemoteConversationStore.InteractionAnswer.Question("  好的  "))
        advanceUntilIdle()
        run {
            val answer = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!["answer"]!!
            assertEquals("好的", answer.objectValue!!["freeText"]?.stringValue)
        }
        // 空白 freeText：本地拒发
        val blank = f.store.resolveInteraction("sess-1", "i-3", RemoteConversationStore.InteractionAnswer.Question("   "))
        assertEquals("client.invalidInteractionAnswer", CommandAck(blank).reasonCode)
        // 权限族空 optionId：本地拒发
        val noOption = f.store.resolveInteraction("sess-1", "i-1", RemoteConversationStore.InteractionAnswer.Permission(" "))
        assertEquals("client.invalidInteractionAnswer", CommandAck(noOption).reasonCode)
        // 本地拒发路径不上线缆：send 全部来自四条合法应答
        assertEquals(4, f.transport.sendCallCount)
    }

    // MARK: 队列五件 payload 形状 + state.queue 投影

    @Test
    fun queueCommands_payloadsAndCas_plusQueueInfoProjection() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3,
                snapshot = snapshotJson(
                    revision = 8,
                    state = mapOf(
                        "queue" to testObj(
                            "items" to JSONValue.Arr(
                                listOf(
                                    testObj(
                                        "queueItemId" to JSONValue.Str("q1"),
                                        "text" to JSONValue.Str("第一条"),
                                        "delivery" to testObj("admitted" to JSONValue.Str("queue")),
                                    ),
                                    testObj("itemId" to JSONValue.Str("q2"), "inputText" to JSONValue.Str("引导条")),
                                ),
                            ),
                            "autoDrain" to JSONValue.Bool(false),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        // queueInfo 投影（宽容键组 queueItemId|itemId|id + text|inputText|newText）
        val queue = f.store.queueInfo("sess-1")
        assertNotNull(queue)
        assertEquals(2, queue.items.size)
        assertEquals("q1", queue.items[0].id)
        assertEquals("第一条", queue.items[0].text)
        assertEquals("queue", queue.items[0].admitted)
        assertEquals("引导条", queue.items[1].text)
        assertFalse(queue.autoDrain)

        // 队列五件 payload 形状 + CAS 双字段
        f.store.sendQueuedNow("sess-1", "q1")
        f.store.editQueueItem("sess-1", "q1", "改写文本")
        f.store.deleteQueueItem("sess-1", "q2")
        f.store.reorderQueueItem("sess-1", "q2", beforeQueueItemId = null)
        f.store.setAutoDrain("sess-1", enabled = true)
        advanceUntilIdle()
        val types = f.transport.sendArgs.map { it["envelope"]!!.objectValue!!["type"]?.stringValue }
        assertEquals(
            listOf("sendQueuedNow", "editQueueItem", "deleteQueueItem", "reorderQueueItem", "setAutoDrain"),
            types,
        )
        val payloads = f.transport.sendArgs.map { it["envelope"]!!.objectValue!!["payload"]!!.objectValue!! }
        assertEquals("q1", payloads[0]["queueItemId"]?.stringValue)
        assertEquals("q1", payloads[1]["queueItemId"]?.stringValue)
        assertEquals("改写文本", payloads[1]["newText"]?.stringValue)
        assertEquals("q2", payloads[2]["queueItemId"]?.stringValue)
        assertEquals("q2", payloads[3]["queueItemId"]?.stringValue)
        assertTrue(payloads[3].containsKey("beforeQueueItemId") && payloads[3]["beforeQueueItemId"] is JSONValue.Null)
        assertEquals(true, payloads[4]["autoDrain"]?.boolValue)
        // 全部信封携 CAS 双字段（队列五件全 CAS 词表）
        for (envelope in f.transport.sendArgs.map { it["envelope"]!!.objectValue!! }) {
            assertEquals(8L, envelope["baseRevision"].longOf(), "队列命令必携 baseRevision（${envelope["type"]?.stringValue}）")
            assertEquals("epoch-1", envelope["baseLogEpoch"]?.stringValue)
        }
    }

    // MARK: goal 暂停/继续 + row-target 族 entityId 门

    @Test
    fun setGoalPaused_pauseResumeCassenvelopes_andRowTargetEntityIdGate() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 3, snapshot = snapshotJson(revision = 4),
            ),
        )
        advanceUntilIdle()
        // pauseGoal / resumeGoal（CAS）
        f.store.setGoalPaused(paused = true, conversationId = "sess-1")
        f.store.setGoalPaused(paused = false, conversationId = "sess-1")
        advanceUntilIdle()
        val goalTypes = f.transport.sendArgs.map { it["envelope"]!!.objectValue!!["type"]?.stringValue }
        assertEquals(listOf("pauseGoal", "resumeGoal"), goalTypes)
        assertTrue(f.transport.sendArgs.all { it["envelope"]!!.objectValue!!["baseRevision"] != null })

        // retryTurn：entityId 缺失 → 合成拒收不上线缆
        val noCursor = f.store.retryTurn("sess-1", rowId = 3, entityId = null)
        assertEquals("client.rowCursorUnavailable", CommandAck(noCursor).reasonCode)
        // entityId 在场 → target {rowId, entityId}
        f.store.retryTurn("sess-1", rowId = 3, entityId = " ent-3 ")
        advanceUntilIdle()
        run {
            val payload = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
            assertEquals("ent-3", payload["target"]!!.objectValue!!["entityId"]?.stringValue, "游标 trim 后下发")
            assertEquals(3, payload["target"]!!.objectValue!!["rowId"]?.intValue)
        }
        // setAssistantFeedback：like|dislike|null 三态 + entityId 门
        val noFeedbackCursor = f.store.setAssistantFeedback("sess-1", rowId = 5, entityId = null, value = true)
        assertEquals("client.rowCursorUnavailable", CommandAck(noFeedbackCursor).reasonCode)
        f.store.setAssistantFeedback("sess-1", rowId = 5, entityId = "ent-5", value = true)
        f.store.setAssistantFeedback("sess-1", rowId = 5, entityId = "ent-5", value = false)
        f.store.setAssistantFeedback("sess-1", rowId = 5, entityId = "ent-5", value = null)
        advanceUntilIdle()
        val feedbacks = f.transport.sendArgs.takeLast(3).map {
            it["envelope"]!!.objectValue!!["payload"]!!.objectValue!!["feedback"]
        }
        assertEquals("like", feedbacks[0]?.stringValue)
        assertEquals("dislike", feedbacks[1]?.stringValue)
        assertTrue(feedbacks[2] is JSONValue.Null, "取消反馈 = 置 null（值恒在键位）")
        // editUserQuery：newText + workspaceMode preserve
        f.store.editUserQuery("sess-1", rowId = 7, entityId = "ent-7", newText = "改写后")
        advanceUntilIdle()
        run {
            val payload = f.transport.sendArgs.last()["envelope"]!!.objectValue!!["payload"]!!.objectValue!!
            assertEquals("改写后", payload["newText"]?.stringValue)
            assertEquals("preserve", payload["workspaceMode"]?.stringValue)
        }
    }

    // MARK: 组织态写（override 即时反馈 + 归属寻址 + 失败回滚 + fault 原文）

    @Test
    fun organizationState_writesAddressedByOwnedWorkspace_andRollbackOnFailure() = runTest {
        val f = Fixture(this, withIndex = true)
        val index = f.index!!
        // 归属他区行注水（跨区寻址断言面）
        index.upsertLocalSession(
            SessionSummary(
                sessionId = "sess-x",
                title = "他区会话",
                phase = "running",
                lastActivityAt = 1_000L,
                workspacePath = "/other/ws",
            ),
        )
        // ① 置顶成功：本地 override 即变 + setTaskPinned 按归属 workspace 寻址 + membership 维护
        f.store.setPinned(pinned = true, conversationId = "sess-x")
        advanceUntilIdle()
        val pinArgs = f.transport.taskWrites.last().second
        assertEquals("sess-x", pinArgs["taskId"]?.stringValue)
        assertEquals("/other/ws", pinArgs["workspacePath"]?.stringValue, "跨区行按归属 workspace 寻址")
        assertEquals(true, pinArgs["pinned"]?.boolValue)
        assertTrue(index.pinnedTaskIds.contains("sess-x"), "membership 集写成功即维护")
        assertEquals("", f.store.lastTaskWriteFailureText())

        // ② 置顶写失败：override 回滚 + membership 不变 + fault 原文上屏
        f.transport.nextTaskWriteError = RPCError(message = "fault.task.notFound", name = "Error")
        f.store.setPinned(pinned = false, conversationId = "sess-x")
        advanceUntilIdle()
        assertTrue(index.pinnedTaskIds.contains("sess-x"), "写失败 membership 回滚")
        assertTrue(index.pinnedOverrides["sess-x"] == true, "本地 override 回滚到写前值")
        assertTrue(f.store.lastTaskWriteFailureText().contains("fault.task.notFound"), "拒因原文上屏：${f.store.lastTaskWriteFailureText()}")

        // ③ 归档：archiveTask + 归属寻址 + membership 维护；失败完整回滚 + 原文
        f.store.setArchived(archived = true, conversationId = "sess-x")
        advanceUntilIdle()
        run {
            val (command, args) = f.transport.taskWrites.last()
            assertEquals("archiveTask", command)
            assertEquals("/other/ws", args["workspacePath"]?.stringValue)
        }
        assertTrue(index.archivedTaskIds.contains("sess-x"))
        f.transport.nextTaskWriteError = RPCError(message = "fault.task.archived", name = "Error")
        f.store.setArchived(archived = false, conversationId = "sess-x")
        advanceUntilIdle()
        run {
            val (command, _) = f.transport.taskWrites.last()
            assertEquals("unarchiveTask", command, "取消归档走 unarchiveTask（P1-6 修复口径）")
        }
        assertTrue(index.archivedTaskIds.contains("sess-x"), "取消归档失败 membership 完整回滚")
        assertTrue(f.store.lastArchiveFailureText().contains("fault.task.archived"), "归档失败原文：${f.store.lastArchiveFailureText()}")

        // ④ 改名：renameTask + title override；失败回滚 + 原文
        f.store.renameConversation(" 新名字 ", conversationId = "sess-x")
        advanceUntilIdle()
        run {
            val (command, args) = f.transport.taskWrites.last()
            assertEquals("renameTask", command)
            assertEquals("新名字", args["title"]?.stringValue)
        }
        assertEquals("新名字", index.titleOverrides["sess-x"])
        f.transport.nextTaskWriteError = RPCError(message = "fault.task.readonly", name = "Error")
        f.store.renameConversation("另一个名字", conversationId = "sess-x")
        advanceUntilIdle()
        assertEquals("新名字", index.titleOverrides["sess-x"], "改名失败 override 回滚")
        assertTrue(f.store.lastTaskWriteFailureText().contains("fault.task.readonly"))
    }

    // MARK: createSession firstInput.modelSelection（providerId/modelId trim + 空选拦截）

    @Test
    fun createSession_modelSelectionTrimmed_emptySelectionLocallyRejected() = runTest {
        val f = Fixture(this)
        // createSession 成功回执（result.sessionId 规范 id）
        f.transport.sendReply = testObj(
            "status" to JSONValue.Str("accepted"),
            "result" to testObj("sessionId" to JSONValue.Str("sess-new-1")),
        )
        // trim：provider/model/档位两侧空白剥除后下发
        val conversation = f.store.createConversation(
            title = "首条指令",
            directory = "",
            executor = ExecutorKind.pairedMac,
            modelSelection = NewSessionModelSelection(
                providerId = " acct:individual-coding-plan ",
                modelId = " GLM-X ",
                reasoningLevel = " high ",
            ),
        )
        advanceUntilIdle()
        assertTrue(conversation.id.isNotEmpty())
        val envelope = f.transport.sendArgs.single()["envelope"]!!.objectValue!!
        assertEquals("createSession", envelope["type"]?.stringValue)
        assertNull(envelope["sessionId"]?.stringValue, "A1：createSession 信封 sessionId=null 键恒在场")
        assertTrue(envelope.containsKey("sessionId"))
        val selection = envelope["payload"]!!.objectValue!!["firstInput"]!!.objectValue!!["modelSelection"]!!.objectValue!!
        assertEquals("acct:individual-coding-plan", selection["providerId"]?.stringValue)
        assertEquals("GLM-X", selection["modelId"]?.stringValue)
        assertEquals("high", selection["options"]!!.objectValue!!["reasoningLevel"]?.stringValue)

        // 空选：本地拦截如实失败（不虚构成功、不上线缆）
        val before = f.transport.sendCallCount
        val failed = f.store.createConversation(
            title = "第二条",
            directory = "",
            executor = ExecutorKind.pairedMac,
            modelSelection = NewSessionModelSelection(providerId = "  ", modelId = "GLM-X", reasoningLevel = ""),
        )
        advanceUntilIdle()
        assertEquals("", failed.id, "空 provider 创建如实失败（空 id）")
        assertTrue(f.store.lastCreateFailureText().contains("模型选择不完整"))
        assertEquals(before, f.transport.sendCallCount, "空选拦截不上线缆")
    }
}

/** Long 宽容读取（JSONValue.Int 32 位值域装不下越界毫秒/revision——整值 double 路径同款）。 */
private fun JSONValue?.longOf(): Long? = when (this) {
    null -> null
    is JSONValue.Int -> value.toLong()
    is JSONValue.Double -> if (value == kotlin.math.floor(value)) value.toLong() else null
    else -> null
}

package cn.biuz.android.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 直译自 ios/UnitTests/CommandFactoryTests.swift（13 用例，2026-10-09 平移）。
// 断言语义与 iOS 原文一一对应（含 iOS 注释中的 A1/A3/A12/A-1/P1③ 验收编号）；
// 只断言结构信号（reasonCode / syntheticAck.status / warnings 命中），不断言文案语言。
//
// 平移口径（与被测实现头注同源）：
// - Swift `Result<Built, Rejection>` → Kotlin stdlib `Result<Built>`（failure 通道 =
//   [ConversationCommandFactory.Rejection] 异常）：`getOrNil()` → `getOrNull()`；
//   `guard case .failure(let r)` → `exceptionOrNull() as? Rejection` + assertNotNull。
// - Swift 命名元组 `(revision:logEpoch:)` → [CasRevision]。
// - issuedAt = 1_760_000_000_000（毫秒）恒越 Kotlin JSONValue.Int 的 32 位值域，按
//   已声明口径落 .double（RPCSerialization.kt 头注 / 帧层偏差；上游 zod number 两形态
//   同收）——iOS `envelope["issuedAt"]?.intValue == 1_760_000_000_000` 平移为
//   doubleValue 断言（同一验收语义：信封 issuedAt == 该毫秒数）；Kotlin intValue 对
//   该值会做 32 位截断，不可用。baseRevision=42 在值域内，仍落 .int，断言不变。
// - 13 用例全部为同步纯函数调用（build 无挂起点），无 async 用例，故未用
//   kotlinx-coroutines-test 的 runTest（依赖已由 build.gradle.kts 预置，本文件未动）。
class CommandFactoryTests {

    // MARK: 构造输入辅助（Swift makeRequest 的逐一对应）

    private fun obj(vararg pairs: Pair<String, JSONValue>): JSONValue =
        JSONValue.Obj(linkedMapOf(*pairs))

    private fun makeRequest(
        type: String,
        sessionId: String? = "sess_1",
        payload: JSONValue = obj(),
        casRevision: CasRevision? = null,
        commandId: String = "cmd-fixed-id",
    ): ConversationCommandRequest =
        ConversationCommandRequest(
            type = type,
            sessionId = sessionId,
            payload = payload,
            casRevision = casRevision,
            workspacePath = "/tmp/ws",
            workspaceIdentity = null,
            commandId = commandId,
        )

    private fun build(request: ConversationCommandRequest): Result<ConversationCommandFactory.Built> =
        ConversationCommandFactory.build(
            request, clientId = "zcode-mobile", issuedAt = 1_760_000_000_000L)

    /** 失败分支取本地合成拒绝（`guard case .failure(let r)` 对应物）；非 Rejection 即断言失败。 */
    private fun rejectionOf(result: Result<ConversationCommandFactory.Built>): ConversationCommandFactory.Rejection =
        assertNotNull(
            result.exceptionOrNull() as? ConversationCommandFactory.Rejection,
            "构造失败应返回 Rejection",
        )

    /** 信封便捷读取（`result.getOrNil()?.outer.objectValue?["envelope"]?.objectValue` 对应物）。 */
    private fun envelopeOf(built: ConversationCommandFactory.Built): Map<String, JSONValue> =
        assertNotNull(built.outer["envelope"]?.objectValue)

    // MARK: 嵌套信封形态（§7.1 实证）

    @Test
    fun testBuildsNestedOuterWithWorkspaceEnvelope() {
        val built = assertNotNull(
            build(
                makeRequest(type = "sendText", payload = obj("text" to JSONValue.Str("hi"))),
            ).getOrNull(),
        )
        val outer = assertNotNull(built.outer.objectValue)
        assertEquals(JSONValue.Str("/tmp/ws"), outer["workspacePath"])
        assertNull(outer["workspaceIdentity"]) // identity 缺席不携键
        val envelope = envelopeOf(built)
        assertEquals(JSONValue.Str("sendText"), envelope["type"])
        assertEquals(JSONValue.Str("sess_1"), envelope["sessionId"])
        assertEquals(JSONValue.Str("zcode-mobile"), envelope["clientId"])
        assertEquals(JSONValue.Str("cmd-fixed-id"), envelope["commandId"])
        assertEquals(JSONValue.Str("hi"), envelope["payload"]?.objectValue?.get("text"))
        // iOS 原文：envelope["issuedAt"]?.intValue == 1_760_000_000_000（见文件头平移口径）
        val issuedAt = assertNotNull(envelope["issuedAt"])
        assertEquals(1_760_000_000_000.0, issuedAt.doubleValue)
        assertNull(envelope["baseRevision"]) // 非 CAS 不携
    }

    @Test
    fun testCreateSessionCarriesNullSessionId() {
        // A1：sessionId 键恒在场，createSession 传 null
        val built = assertNotNull(
            build(
                makeRequest(
                    type = "createSession",
                    sessionId = null,
                    payload = obj("workspaceId" to JSONValue.Str("/tmp/ws")),
                ),
            ).getOrNull(),
        )
        assertEquals(JSONValue.Null, envelopeOf(built)["sessionId"])
    }

    // MARK: CAS 门（构造时即拒）

    @Test
    fun testCASCommandMissingRevisionRejectedLocally() {
        val rejection = rejectionOf(
            build(
                makeRequest(
                    type = "switchModelConfig",
                    payload = obj(
                        "provider" to JSONValue.Str("p"),
                        "model" to JSONValue.Str("m"),
                        "thought" to JSONValue.Str(""),
                    ),
                ),
            ),
        )
        assertEquals("client.casRevisionUnavailable", rejection.reasonCode)
        assertEquals(JSONValue.Str("rejected"), rejection.syntheticAck["status"])
    }

    @Test
    fun testCASCommandWithRevisionBuilds() {
        val built = assertNotNull(
            build(
                makeRequest(
                    type = "switchModelConfig",
                    payload = obj(
                        "provider" to JSONValue.Str("p"),
                        "model" to JSONValue.Str("m"),
                        "thought" to JSONValue.Str(""),
                    ),
                    casRevision = CasRevision(revision = 42, logEpoch = "epoch-1"),
                ),
            ).getOrNull(),
        )
        val envelope = envelopeOf(built)
        assertEquals(JSONValue.Int(42), envelope["baseRevision"])
        assertEquals(JSONValue.Str("epoch-1"), envelope["baseLogEpoch"])
    }

    @Test
    fun testEveryCASVocabularyMemberIsGated() {
        // 词表内全部命令缺水位必拒（pauseGoal/retryTurn 冷启动首击教训 P1③ 的结构化防线）
        val target = obj(
            "rowId" to JSONValue.Int(1),
            "entityId" to JSONValue.Str("e1"),
        )
        val validPayloads: Map<String, JSONValue> = mapOf(
            "applyFileRewind" to obj("target" to target),
            "forkAssistant" to obj("target" to target),
            "editUserQuery" to obj("target" to target, "newText" to JSONValue.Str("n")),
            "retryTurn" to obj("target" to target),
            "setAssistantFeedback" to obj("target" to target, "feedback" to JSONValue.Null),
            "sendQueuedNow" to obj("queueItemId" to JSONValue.Str("q")),
            "editQueueItem" to obj(
                "queueItemId" to JSONValue.Str("q"), "newText" to JSONValue.Str("n")),
            "reorderQueueItem" to obj(
                "queueItemId" to JSONValue.Str("q"), "beforeQueueItemId" to JSONValue.Null),
            "deleteQueueItem" to obj("queueItemId" to JSONValue.Str("q")),
            "setAutoDrain" to obj("autoDrain" to JSONValue.Bool(true)),
            "switchModelConfig" to obj(
                "provider" to JSONValue.Str("p"),
                "model" to JSONValue.Str("m"),
                "thought" to JSONValue.Str(""),
            ),
            "switchCollaborationMode" to obj("mode" to JSONValue.Str("build")),
            "setFollowupMode" to obj("mode" to JSONValue.Str("queue")),
            "pauseGoal" to obj(),
            "resumeGoal" to obj(),
        )
        assertEquals(CommandSchemas.commandsRequiringBaseRevision, validPayloads.keys)
        for (type in CommandSchemas.commandsRequiringBaseRevision.sorted()) {
            val rejection = rejectionOf(
                build(makeRequest(type = type, payload = validPayloads.getValue(type))),
            )
            assertEquals("client.casRevisionUnavailable", rejection.reasonCode, type)
        }
    }

    // MARK: payload 规范化（strip 语义 + 可信字段黑名单）

    @Test
    fun testUnknownPayloadKeyStrippedAndWarned() {
        val built = assertNotNull(
            build(
                makeRequest(
                    type = "sendText",
                    payload = obj("text" to JSONValue.Str("hi"), "staleKey" to JSONValue.Bool(true)),
                ),
            ).getOrNull(),
        )
        val payload = assertNotNull(
            built.outer["envelope"]?.get("payload")?.objectValue,
        )
        assertNull(payload["staleKey"]) // 剥离（服务端同语义）
        assertEquals(JSONValue.Str("hi"), payload["text"])
        assertTrue(built.warnings.any { it.contains("staleKey") })
    }

    @Test
    fun testTrustedFieldRejected() {
        // connectionId 由桌面 facade 注入，客户端携带即违规（v1.14 真机被拒先例）
        val rejection = rejectionOf(
            build(
                makeRequest(
                    type = "sendText",
                    payload = obj(
                        "text" to JSONValue.Str("hi"),
                        "connectionId" to JSONValue.Str("forged"),
                    ),
                ),
            ),
        )
        assertEquals("client.schemaViolation", rejection.reasonCode)
    }

    @Test
    fun testWrongEnumRejected() {
        val target = obj(
            "rowId" to JSONValue.Int(1),
            "entityId" to JSONValue.Str("e1"),
        )
        val rejection = rejectionOf(
            build(
                makeRequest(
                    type = "setAssistantFeedback",
                    payload = obj("target" to target, "feedback" to JSONValue.Str("positive")),
                ),
            ),
        )
        // positive/negative 词表应被拒（A-1 先例）
        assertEquals("client.schemaViolation", rejection.reasonCode)
        assertTrue(rejection.message.contains("feedback"))

        val good = build(
            makeRequest(
                type = "setAssistantFeedback",
                payload = obj("target" to target, "feedback" to JSONValue.Null),
                casRevision = CasRevision(revision = 1, logEpoch = "e"),
            ),
        )
        // iOS 原文 `_ = try? good.get()`：验收语义 = feedback=null 合法、构建成功
        // （原文 try? 静默吞败，此处以显式断言固化同一语义）
        assertTrue(good.isSuccess, "feedback=null 应构建成功")
    }

    @Test
    fun testRequiredPayloadFieldMissingRejected() {
        val rejection = rejectionOf(
            build(
                makeRequest(
                    type = "reorderQueueItem",
                    payload = obj("queueItemId" to JSONValue.Str("q1")), // 缺 beforeQueueItemId
                    casRevision = CasRevision(revision = 1, logEpoch = "e"),
                ),
            ),
        )
        assertTrue(rejection.message.contains("beforeQueueItemId"))
    }

    @Test
    fun testReorderQueueItemNullTailAllowed() {
        val built = assertNotNull(
            build(
                makeRequest(
                    type = "reorderQueueItem",
                    payload = obj(
                        "queueItemId" to JSONValue.Str("q1"),
                        "beforeQueueItemId" to JSONValue.Null, // null=移到队尾
                    ),
                    casRevision = CasRevision(revision = 1, logEpoch = "e"),
                ),
            ).getOrNull(),
        )
        val payload = assertNotNull(
            built.outer["envelope"]?.get("payload")?.objectValue,
        )
        assertEquals(JSONValue.Null, payload["beforeQueueItemId"])
    }

    // MARK: 幂等与未移植面

    @Test
    fun testCommandIdEchoedForRetryInvariance() {
        // A12：重试必须复用同一 commandId——工厂原样透传，不自行生成
        val built = assertNotNull(
            build(
                makeRequest(
                    type = "stop",
                    payload = obj(),
                    commandId = "retry-same-id",
                ),
            ).getOrNull(),
        )
        assertEquals(
            JSONValue.Str("retry-same-id"),
            built.outer["envelope"]?.get("commandId"),
        )
    }

    @Test
    fun testUnportedTypeBuildsWithWarning() {
        val built = assertNotNull(
            build(
                makeRequest(
                    type = "sendGoalCommand",
                    payload = obj("text" to JSONValue.Str("g")),
                ),
            ).getOrNull(),
        )
        assertEquals(
            JSONValue.Str("sendGoalCommand"),
            built.outer["envelope"]?.get("type"),
        )
        assertTrue(built.warnings.any { it.contains("未移植") })
    }

    @Test
    fun testSwitchModelConfigEmptyThoughtAllowed() {
        // 上游无 min：thought 空串合法（移动端无档位时发 ""）
        assertNotNull(
            build(
                makeRequest(
                    type = "switchModelConfig",
                    payload = obj(
                        "provider" to JSONValue.Str("p"),
                        "model" to JSONValue.Str("m"),
                        "thought" to JSONValue.Str(""),
                    ),
                    casRevision = CasRevision(revision = 1, logEpoch = "e"),
                ),
            ).getOrNull(),
        )
    }
}

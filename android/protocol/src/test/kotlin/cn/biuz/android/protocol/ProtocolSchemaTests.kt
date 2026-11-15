package cn.biuz.android.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 直译自 ios/UnitTests/ProtocolSchemaTests.swift（18 用例 → 本文件 17 个，见文末
// 「未平移用例」注记）。被测面：
// - ProtocolSchema.kt：PValidator / PSchema / PObjectShape / PField（zod 语义引擎）
// - CommandSchemas.kt：权威词表 / rowTarget / 附件参数 schema / CommandAck（ACK 读面）
// - ChannelClient.kt：RPCError（handshakeRequired 判定）
//
// 断言纪律（与 iOS 原文一一对应）：
// - 注释中的 A-xx/上游 file:line 验收编号原样保留；
// - 断言结构信号（reasonCode/词表/路径前缀），不锁自然语言文案（错误文案双语稳健
//   口径，AGENTS §5-18③）；
// - 本文件全部用例与 iOS 原文同为同步语义（无 async），故不引 kotlinx-coroutines-test。
//
// 机械映射：XCTest → kotlin.test（JUnit5 平台，BootstrapTest 同款接线）；
// `Set.isSubset(of:)` → `Set.containsAll`（Kotlin stdlib 无同名 API，语义相同）；
// `String.count`（grapheme）→ `String.length`（UTF-16；截断用例输入为 ASCII，等价，
// 口径与 ProtocolSchema.kt 文件头一致）。

class ProtocolSchemaTests {

    // MARK: 引擎语义（zod 映射）

    private val shape = PObjectShape(
        fields = mapOf(
            "a" to PField.required(PSchema.nonEmptyString),
            "b" to PField.optional(PSchema.Integer(min = 0, max = 10)),
        ),
    )

    @Test
    fun testStripRemovesUnknownKeyWithWarning() {
        // zod 默认 strip：未知键剥离后放行，但本地显性告警（上游此路静默）
        val result = PValidator.validate(
            JSONValue.Obj(
                mapOf(
                    "a" to JSONValue.Str("x"),
                    "junk" to JSONValue.Int(1),
                ),
            ),
            schema = PSchema.Obj(shape),
        )
        assertTrue(result.isValid)
        assertEquals(listOf("a"), result.value?.objectValue?.keys?.sorted())
        assertTrue(result.warnings.any { it.contains("junk") })
    }

    @Test
    fun testStrictRejectsUnknownKey() {
        val strict = PObjectShape(
            fields = mapOf("a" to PField.required(PSchema.string)),
            strict = true,
            deny = emptySet(),
        )
        val result = PValidator.validate(
            JSONValue.Obj(
                mapOf("a" to JSONValue.Str("x"), "junk" to JSONValue.Int(1)),
            ),
            schema = PSchema.Obj(strict),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("junk") })
    }

    @Test
    fun testRequiredKeyMissingIsError() {
        val result = PValidator.validate(JSONValue.Obj(emptyMap()), schema = PSchema.Obj(shape))
        assertFalse(result.isValid)
        // 双语稳健：断言路径前缀（zh「a: 缺失必填键」/ en「a: missing required key」
        // 同构）——错误文案已本地化，en 态下中文关键词不在场，不锁自然语言措辞
        assertTrue(result.errors.any { it.contains("a:") })
    }

    @Test
    fun testNullableRequiredKeyMustBePresentButMayBeNull() {
        // 信封 sessionId 语义（A1）：键缺席=错；null=对
        val nullableShape = PObjectShape(
            fields = mapOf("sessionId" to PField.requiredNullable(PSchema.nonEmptyString)),
        )
        assertFalse(
            PValidator.validate(JSONValue.Obj(emptyMap()), schema = PSchema.Obj(nullableShape)).isValid,
        )
        assertTrue(
            PValidator.validate(
                JSONValue.Obj(mapOf("sessionId" to JSONValue.Null)),
                schema = PSchema.Obj(nullableShape),
            ).isValid,
        )
        assertTrue(
            PValidator.validate(
                JSONValue.Obj(mapOf("sessionId" to JSONValue.Str("sess_1"))),
                schema = PSchema.Obj(nullableShape),
            ).isValid,
        )
    }

    @Test
    fun testEnumRejectsWrongValue() {
        // A-1 教训回归：setAssistantFeedback 值域 like|dislike，positive/negative 必拒
        val feedback = PObjectShape(
            fields = mapOf(
                "feedback" to PField.requiredNullable(PSchema.enumeration("like", "dislike")),
            ),
        )
        assertFalse(
            PValidator.validate(
                JSONValue.Obj(mapOf("feedback" to JSONValue.Str("positive"))),
                schema = PSchema.Obj(feedback),
            ).isValid,
        )
        assertTrue(
            PValidator.validate(
                JSONValue.Obj(mapOf("feedback" to JSONValue.Str("like"))),
                schema = PSchema.Obj(feedback),
            ).isValid,
        )
        assertTrue(
            PValidator.validate(
                JSONValue.Obj(mapOf("feedback" to JSONValue.Null)),
                schema = PSchema.Obj(feedback),
            ).isValid,
        )
    }

    @Test
    fun testDenyKeyIsHardErrorEvenInStripMode() {
        // 服务端注入字段（connectionId 等）：携带即违规（A11/v1.14 先例）
        val result = PValidator.validate(
            JSONValue.Obj(
                mapOf("a" to JSONValue.Str("x"), "connectionId" to JSONValue.Str("forged")),
            ),
            schema = PSchema.Obj(shape),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("connectionId") })
    }

    @Test
    fun testStrictBase64PaddingAndSizeLimit() {
        assertEquals(1, PValidator.strictBase64ByteLength("QQ==")) // "A"
        assertEquals(2, PValidator.strictBase64ByteLength("QUI=")) // "AB"
        assertEquals(3, PValidator.strictBase64ByteLength("QUJD")) // "ABC"，无 padding
        assertNull(PValidator.strictBase64ByteLength("QQ=")) // 长度非 4 倍数
        assertNull(PValidator.strictBase64ByteLength("QQ==Q")) // '=' 出现在尾部之外
        assertNull(PValidator.strictBase64ByteLength("====")) // padding >2
        assertNull(PValidator.strictBase64ByteLength("QUJ!")) // 非字母表

        val chunk = PSchema.StrictBase64(maxBytes = 512 * 1024)
        assertTrue(PValidator.validate(JSONValue.Str("QUJD"), schema = chunk).isValid)
        assertFalse(
            PValidator.validate(JSONValue.Str("QUJD"), schema = PSchema.StrictBase64(maxBytes = 2)).isValid,
        )
    }

    @Test
    fun testArrayElementAndMaxItems() {
        val schema = PSchema.Arr(element = PSchema.nonEmptyString, maxItems = 1)
        assertTrue(
            PValidator.validate(JSONValue.Arr(listOf(JSONValue.Str("x"))), schema = schema).isValid,
        )
        assertFalse(
            PValidator.validate(
                JSONValue.Arr(listOf(JSONValue.Str("x"), JSONValue.Str("y"))),
                schema = schema,
            ).isValid,
        )
        assertFalse(
            PValidator.validate(JSONValue.Arr(listOf(JSONValue.Str(""))), schema = schema).isValid,
        )
    }

    // MARK: 权威词表（上游 command.ts:296-320 逐字）

    @Test
    fun testCASVocabularyMatchesUpstream() {
        val expectedCAS: Set<String> = setOf(
            "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
            "setAssistantFeedback", "sendQueuedNow", "reorderQueueItem",
            "deleteQueueItem", "setAutoDrain", "switchModelConfig",
            "switchCollaborationMode", "setFollowupMode", "pauseGoal", "resumeGoal",
        )
        assertEquals(expectedCAS, CommandSchemas.commandsRequiringBaseRevision)
        assertEquals(14, CommandSchemas.commandsRequiringBaseRevision.size)
        val expectedRowTargeting: Set<String> = setOf(
            "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
            "setAssistantFeedback",
        )
        assertEquals(expectedRowTargeting, CommandSchemas.rowTargetingCommands)
        assertTrue(
            CommandSchemas.commandsRequiringBaseRevision.containsAll(CommandSchemas.rowTargetingCommands),
        )
    }

    @Test
    fun testRowTargetIsStrict() {
        // core.ts:10-15 conversationRowTargetSchema .strict()
        val result = PValidator.validate(
            JSONValue.Obj(
                mapOf(
                    "rowId" to JSONValue.Int(3),
                    "entityId" to JSONValue.Str("e1"),
                    "extra" to JSONValue.Bool(true),
                ),
            ),
            schema = CommandSchemas.rowTarget(),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("extra") })
        assertTrue(
            PValidator.validate(
                JSONValue.Obj(
                    mapOf("rowId" to JSONValue.Int(3), "entityId" to JSONValue.Str("e1")),
                ),
                schema = CommandSchemas.rowTarget(),
            ).isValid,
        )
    }

    @Test
    fun testAttachmentBeginParamsEnforceLimitsAndRegex() {
        fun beginDict(): MutableMap<String, JSONValue> = linkedMapOf(
            "uploadId" to JSONValue.Str("u.1_-x:y"),
            "sessionId" to JSONValue.Str("sess_1"),
            "fileName" to JSONValue.Str("a.png"),
            "mime" to JSONValue.Str("image/png"),
            "totalBytes" to JSONValue.Int(1024),
            "totalChunks" to JSONValue.Int(1),
            "checksum" to JSONValue.Str("sha256:" + "a".repeat(64)),
        )
        val schema = PSchema.Obj(CommandSchemas.attachmentBeginParamsShape())
        assertTrue(PValidator.validate(JSONValue.Obj(beginDict()), schema = schema).isValid)

        val over = beginDict()
        over["totalBytes"] = JSONValue.Int(CommandSchemas.attachmentMaxBytes + 1)
        assertFalse(PValidator.validate(JSONValue.Obj(over), schema = schema).isValid)

        val badChecksum = beginDict()
        badChecksum["checksum"] = JSONValue.Str("md5:xyz")
        assertFalse(PValidator.validate(JSONValue.Obj(badChecksum), schema = schema).isValid)

        val forged = beginDict()
        forged["connectionId"] = JSONValue.Str("forged")
        assertFalse(PValidator.validate(JSONValue.Obj(forged), schema = schema).isValid)
    }

    // MARK: ACK 解析（command.ts:430-443 + 中继 ack 包裹宽容）

    @Test
    fun testAckParsingTopLevelAndWrapped() {
        val top = CommandAck(
            JSONValue.Obj(
                mapOf(
                    "status" to JSONValue.Str("accepted"),
                    "revisionAtDecision" to JSONValue.Int(5),
                ),
            ),
        )
        assertEquals("accepted", top.status)
        assertFalse(top.isFailure)

        val wrapped = CommandAck(
            JSONValue.Obj(
                mapOf(
                    "ack" to JSONValue.Obj(
                        mapOf(
                            "status" to JSONValue.Str("rejected"),
                            "reasonCode" to JSONValue.Str("proto.invalidPayload"),
                            "message" to
                                JSONValue.Str("""[{"code":"invalid_enum_value","message":"Invalid enum value"}]"""),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(wrapped.isFailure)
        assertEquals("proto.invalidPayload", wrapped.reasonCode)
        // zod issue 数组形态：取首条 message 字段
        assertTrue(wrapped.failureText?.contains("Invalid enum value") == true)
    }

    @Test
    fun testAckFailureVocabulary() {
        for (status in listOf("rejected", "stale", "failed")) {
            assertTrue(
                CommandAck(JSONValue.Obj(mapOf("status" to JSONValue.Str(status)))).isFailure,
                status,
            )
        }
        for (status in listOf("accepted", "duplicate", "noop")) {
            assertFalse(
                CommandAck(JSONValue.Obj(mapOf("status" to JSONValue.Str(status)))).isFailure,
                status,
            )
        }
        // 宽容：status 缺席（旧桌面）与非词表遗留值（applied/ok 旧通道回执）按成功
        assertFalse(CommandAck(JSONValue.Obj(mapOf("commandId" to JSONValue.Str("c")))).isFailure)
        assertFalse(CommandAck(JSONValue.Obj(mapOf("status" to JSONValue.Str("applied")))).isFailure)
    }

    /**
     * 权威成功判定（2026-10-09「计划模式丢失」修复）：落本机持久化/chip 态只认
     * 六态词表内非失败态；status 缺席/词表外 = 未知态，读面宽容但不得落盘
     */
    @Test
    fun testAckAuthoritativeSuccess() {
        for (status in listOf("accepted", "duplicate", "noop")) {
            val ack = CommandAck(JSONValue.Obj(mapOf("status" to JSONValue.Str(status))))
            assertTrue(ack.isAuthoritativeSuccess, status)
            assertFalse(ack.isFailure, status)
        }
        // 未知态：读面宽容（isFailure=false）但非权威成功
        val absent = CommandAck(JSONValue.Obj(mapOf("commandId" to JSONValue.Str("c"))))
        assertFalse(absent.isFailure, "宽容成功读面不变")
        assertFalse(absent.isAuthoritativeSuccess, "status 缺席不得落盘")
        val legacy = CommandAck(JSONValue.Obj(mapOf("status" to JSONValue.Str("applied"))))
        assertFalse(legacy.isFailure)
        assertFalse(legacy.isAuthoritativeSuccess, "词表外遗留值不得落盘")
        for (status in listOf("rejected", "stale", "failed")) {
            val ack = CommandAck(JSONValue.Obj(mapOf("status" to JSONValue.Str(status))))
            assertTrue(ack.isFailure, status)
            assertFalse(ack.isAuthoritativeSuccess, status)
        }
    }

    @Test
    fun testFirstReadableMessagePlainVsZod() {
        assertEquals("boom", CommandAck.firstReadableMessage("boom"))
        assertEquals(
            "预期 string",
            CommandAck.firstReadableMessage("""code=x,"message": "预期 string"更多"""),
        )
        assertNull(CommandAck.firstReadableMessage(null))
        assertNull(CommandAck.firstReadableMessage(""))
        // 超长原文截断
        assertEquals(160, CommandAck.firstReadableMessage("a".repeat(300))?.length)
    }

    @Test
    fun testClientPrefixedRejectionReadsAsLocalBlock() {
        // client.* 前缀 = 本地拒发（命令未发出）——failureText 必须区分「本地拦截」
        // 与「桌面端拒绝」两支（2026-10-08 用户反馈：本地拒发误导排查方向）。
        // 双语稳健：断言结构信号（reasonCode 原文在场 / 异支词表缺席），不锁自然
        // 语言措辞——failureText 已本地化，en 态下中文措辞不在场
        val local = CommandAck(
            JSONValue.Obj(
                mapOf(
                    "status" to JSONValue.Str("rejected"),
                    "reasonCode" to JSONValue.Str("client.casRevisionUnavailable"),
                    "message" to JSONValue.Str("会话状态 revision 未就绪（快照未到达），命令未发出"),
                ),
            ),
        )
        assertTrue(local.isFailure)
        val localText = local.failureText ?: ""
        assertTrue(localText.contains("client.casRevisionUnavailable"), localText)
        assertTrue(localText.contains("revision"), localText)
        assertFalse(localText.contains("proto.invalidPayload"), localText)

        // 服务端拒因走另一支（reasonCode 同样原样在场）
        val remote = CommandAck(
            JSONValue.Obj(
                mapOf(
                    "status" to JSONValue.Str("rejected"),
                    "reasonCode" to JSONValue.Str("proto.invalidPayload"),
                    "message" to JSONValue.Str("Invalid input"),
                ),
            ),
        )
        val remoteText = remote.failureText ?: ""
        assertTrue(remoteText.contains("proto.invalidPayload"), remoteText)
        assertFalse(remoteText.contains("client.casRevisionUnavailable"), remoteText)
    }

    @Test
    fun testHandshakeRequiredDetection() {
        // 真机取证形态：name="Error"、message="fault.connection.handshakeRequired"
        val err = RPCError(message = "fault.connection.handshakeRequired", name = "Error")
        assertTrue(err.isHandshakeRequired)
        assertFalse(RPCError(message = "RPC 超时", name = "TimeoutError").isHandshakeRequired)
    }

    // ------------------------------------------------------------------
    // 未平移用例（iOS 原文第 18 例 testParseInteractionUpstreamExitPlanModeIsPlanApproval）：
    // 被测 API `RemoteConversationStore.parseInteraction`（iOS
    // RemoteConversationStore.swift:2904，Store 层挂起交互投影解析：schema.interaction
    // == "plan_approval" 归位 kind + payload.input.plan 计划正文）在 android/protocol
    // 模块无 Kotlin 对应物（store 模块仍为占位；全仓 grep "parseInteraction" 仅 iOS 命中）。
    // 按「生产代码缺 API 不绕过断言」纪律不平移、不造替身（测试内复刻解析逻辑 = 测
    // 自己的拷贝而非生产代码）。待 Store 层移植 parseInteraction 后按 iOS 原文补平移：
    // - ExitPlanMode 形态（kind="userInput"、payload.schema.interaction="plan_approval"、
    //   payload.input.plan 有值）断言 kind=="plan_approval" / isPlanApproval /
    //   planText=="1. do x\n2. do y" / title==prompt（数据形状 iOS 原文 :262-279）；
    // - AskUserQuestion 形态（同为 userInput、schema 无 interaction 键）断言
    //   kind=="userInput" 且 !isPlanApproval（iOS 原文 :281-294）。
    // ------------------------------------------------------------------
}

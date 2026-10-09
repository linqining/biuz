package cn.biuz.android.protocol

// 直译自 ios/ZCodeMobileApp/Sources/Services/RPC/CommandSchemas.swift（414 行）。
//
// v4 协议 schema 单一定义源（上游 zcode-protocol-v4 的 Swift 移植 → Kotlin 直译）。
//
// 【实证·上游仓 /tmp/zcode-upstream 2026-10-07 克隆件】字段定义逐字移植自
// packages/shared/src/zcode-protocol-v4/{command,core,transport,attachment-ref,
// model-selection,workflow-run-settings-command}.ts，行号为克隆件指位（重克隆可能
// 漂移，以符号检索为准）。上游无人类可读协议文档（schema 自注「未冻结，定型以
// 黄金测试为准」core.ts:1），源码即唯一权威——本文件是移动端的权威镜像，
// 发送构造、回执解析、单测三方共用（构造与校验同源）。
//
// 未移植 payload 的 type（sendGoalCommand/startSavedWorkflow/deleteSession/
// workspaceHook 三族/resumeWorkflowRun 等）：payloadSchema 返回 null，工厂只做
// 信封层校验（宽容不阻断——上游 payload 为 strip 语义，未知键本就被剥离；接入
// UI 前按 AGENTS §5-11 取证后补条目）。
//
// 机械映射：Swift `payloadSchema(for type:)` 参数标签 `for` 为 Kotlin 关键字 →
// 参数名 `type`；`String(localized:)` → 同串字面量（见 Models.kt 文件头约定）。

object CommandSchemas {

    // MARK: 权威词表

    /**
     * CAS 类命令全集（缺 baseRevision 构造即拒）【实证·上游仓 command.ts:296-320
     * COMMANDS_REQUIRING_BASE_REVISION，逐字】
     */
    val commandsRequiringBaseRevision: Set<String> = setOf(
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback", "sendQueuedNow", "editQueueItem", "reorderQueueItem",
        "deleteQueueItem", "setAutoDrain", "switchModelConfig",
        "switchCollaborationMode", "setFollowupMode", "pauseGoal", "resumeGoal",
    )

    /**
     * row-target 类（另要求 baseLogEpoch；为本表的子集）
     * 【command.ts:314-320 ROW_TARGETING_COMMANDS，2026-10-09 克隆件核实；iOS 原文注 :309-320】
     */
    val rowTargetingCommands: Set<String> = setOf(
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback",
    )

    /** ACK 六态词表【command.ts:430-443】 */
    val ackStatuses: Set<String> = setOf(
        "accepted", "rejected", "stale", "duplicate", "noop", "failed",
    )

    /**
     * 服务端注入的可信字段（客户端构造携带即违规）
     * 【zcodeAgentConnectionScope.ts:94-105 withTrustedConnection】
     */
    val trustedFieldDenyList: Set<String> = setOf(
        "connectionId", "clientMode", "workflowRunDeltas",
    )

    /** 附件常量【core.ts:84-96 PROTOCOL_V4_LIMITS】 */
    const val attachmentMaxBytes = 20 * 1024 * 1024 // 20 MiB
    const val attachmentChunkMaxBytes = 512 * 1024 // 512 KiB
    const val attachmentUploadMaxChunks = 64

    /**
     * 信封 type 已知词表（commandTypeSchema 子集=已移植面）。未列入的 type 不硬拒
     * （词表不完整是常态——上游全集大于移植面），仅告警提示核对。
     */
    val knownTypes: Set<String> = setOf(
        "sendText", "stop", "createSession", "switchModelConfig",
        "switchCollaborationMode", "setFollowupMode", "setAssistantFeedback",
        "editUserQuery", "resolveInteraction", "pauseGoal", "resumeGoal", "compact",
        "snoozeInteractionAutoResolution", "cancelBackgroundWork",
        "amendWorkflowRunSettings", "sendQueuedNow", "editQueueItem",
        "reorderQueueItem", "deleteQueueItem", "setAutoDrain", "retryTurn",
        "forkAssistant",
        // 未移植 payload 但已在发送面的 type（信封层放行）
        "sendGoalCommand", "startSavedWorkflow", "deleteSession",
        "requestWorkspaceHookReview", "respondWorkspaceHookReview",
        "revokeWorkspaceHookTrust", "toggleWorkspaceHookReviewItem",
        "resumeWorkflowRun", "applyFileRewind",
    )

    // MARK: 信封 schema（command.ts:323-336 commandEnvelopeSchema）
    //
    // 上游信封为 strip；本表取 strict=构造纪律（我们只发自己构造的信封，出现未知
    // 键=构造 bug，构造期即爆——官方 commandFactory.ts:51-70「CAS/row-target 缺字段
    // 构造时即抛」同思路，比服务端口径更严，方向安全）。

    fun envelopeShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "commandId" to PField.required(PSchema.nonEmptyString), // uuid，重试不变（A12）
                "clientId" to PField.required(PSchema.nonEmptyString), // 与握手逐字相等（A2）
                "sessionId" to PField.requiredNullable(PSchema.nonEmptyString), // 键恒在场（A1）
                "type" to PField.required(PSchema.nonEmptyString),
                "payload" to PField.required(PSchema.AnyJson),
                "issuedAt" to PField.required(PSchema.Num), // Unix 毫秒
                "baseRevision" to PField.optional(PSchema.Num), // CAS 双字段
                "baseLogEpoch" to PField.optional(PSchema.nonEmptyString),
            ),
            strict = true,
            deny = emptySet(),
        )
    }

    // MARK: payload schema（command.ts:44-247，除标注外均默认 strip）

    /** Swift 参数标签 `for` 为 Kotlin 关键字 → 参数名 `type`（调用形态 `payloadSchema("sendText")`）。 */
    fun payloadSchema(type: String): PSchema? {
        return when (type) {
            "sendText" -> PSchema.Obj(sendTextShape())
            "stop" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "expectedForegroundExecutionId" to PField.optional(PSchema.nonEmptyString),
                        ),
                    ),
                )
            "createSession" -> PSchema.Obj(createSessionShape())
            "switchModelConfig" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "provider" to PField.required(PSchema.string), // 上游无 min（thought 可空串同构）
                            "model" to PField.required(PSchema.string),
                            "thought" to PField.required(PSchema.string),
                        ),
                    ),
                )
            "switchCollaborationMode" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "mode" to PField.required(PSchema.enumeration("build", "edit", "plan", "yolo")),
                        ),
                    ),
                )
            "setFollowupMode" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "mode" to PField.required(PSchema.enumeration("queue", "guide")),
                        ),
                    ),
                )
            "setAssistantFeedback" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "target" to PField.required(rowTarget()),
                            // A-1 教训：值域 like|dislike（positive/negative 全拒）
                            "feedback" to PField.requiredNullable(PSchema.enumeration("like", "dislike")),
                        ),
                    ),
                )
            "editUserQuery" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "target" to PField.required(rowTarget()),
                            "newText" to PField.required(PSchema.string),
                            "attachments" to attachmentsField(),
                            "workspaceMode" to PField.optional(PSchema.enumeration("preserve", "rewind")),
                        ),
                    ),
                )
            "resolveInteraction" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "interactionId" to PField.required(PSchema.nonEmptyString),
                            "answer" to PField.required(PSchema.Obj(answerShape())),
                        ),
                    ),
                )
            "pauseGoal", "resumeGoal", "compact" -> PSchema.Obj(PObjectShape.empty())
            "snoozeInteractionAutoResolution" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "interactionId" to PField.required(PSchema.nonEmptyString),
                        ),
                    ),
                )
            "cancelBackgroundWork" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "workId" to PField.required(PSchema.nonEmptyString),
                        ),
                    ),
                )
            "amendWorkflowRunSettings" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "workId" to PField.required(PSchema.string),
                            // "providerId/modelId[$level]"，null=回会话模型
                            "subagentModel" to PField.optionalNullable(PSchema.nonEmptyString),
                            "maxConcurrency" to PField.optionalNullable(PSchema.Integer(min = 1, max = null)),
                        ),
                    ),
                )
            "sendQueuedNow", "deleteQueueItem" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "queueItemId" to PField.required(PSchema.string),
                        ),
                    ),
                )
            "editQueueItem" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "queueItemId" to PField.required(PSchema.string),
                            "newText" to PField.required(PSchema.string),
                        ),
                    ),
                )
            "reorderQueueItem" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "queueItemId" to PField.required(PSchema.string),
                            "beforeQueueItemId" to PField.requiredNullable(PSchema.nonEmptyString), // null=移到队尾
                        ),
                    ),
                )
            "setAutoDrain" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "autoDrain" to PField.required(PSchema.Bool),
                        ),
                    ),
                )
            "retryTurn", "forkAssistant" ->
                PSchema.Obj(
                    PObjectShape(
                        fields = mapOf(
                            "target" to PField.required(rowTarget()),
                        ),
                    ),
                )
            else -> null // 未移植：信封层校验 + 构造告警（strip 语义不阻断）
        }
    }

    private fun attachmentsField(): PField {
        return PField.optional(PSchema.Arr(element = PSchema.Obj(attachmentRefShape()), maxItems = null))
    }

    /** 行游标【core.ts:10-15 conversationRowTargetSchema，.strict()】（Swift :183-190） */
    fun rowTarget(): PSchema {
        return PSchema.Obj(
            PObjectShape(
                fields = mapOf(
                    "rowId" to PField.required(PSchema.Integer(min = 0, max = null)),
                    "entityId" to PField.required(PSchema.nonEmptyString), // trim min(1)
                ),
                strict = true,
                deny = emptySet(),
            ),
        )
    }

    /** 附件引用【attachment-ref.ts:4-12，.strict()】（Swift :193-203） */
    fun attachmentRefShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "ref" to PField.required(PSchema.string),
                "fileName" to PField.required(PSchema.string),
                "mime" to PField.required(PSchema.string),
                "bytes" to PField.required(PSchema.integer),
                "previewRef" to PField.optional(PSchema.string),
            ),
            strict = true,
            deny = emptySet(),
        )
    }

    /**
     * modelSelection（sendText.modelSelection / firstInput.modelSelection 同形）
     * 【model-selection.ts:4-15，.strict()】（Swift :207-217）
     */
    fun modelSelectionShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "providerId" to PField.required(PSchema.nonEmptyString), // trim min(1)
                "modelId" to PField.required(PSchema.nonEmptyString),
                "options" to PField.optional(
                    PSchema.Obj(
                        PObjectShape(
                            fields = mapOf(
                                "reasoningLevel" to PField.optional(PSchema.nonEmptyString),
                            ),
                            strict = true,
                            deny = emptySet(),
                        ),
                    ),
                ),
            ),
            strict = true,
            deny = emptySet(),
        )
    }

    private fun sendTextShape(): PObjectShape {
        // 上游另有 browserAmbientContext/context_refs/automationId/offPeak*/botDeliveryTarget/
        // toolDisallowlist/modelExecution 及三条 superRefine 互斥（command.ts:81-134）——
        // 移动端发送面不含这些键，未移植；接入时按上游补。
        return PObjectShape(
            fields = mapOf(
                "text" to PField.required(PSchema.string),
                "attachments" to attachmentsField(),
                "requestedDelivery" to PField.optional(PSchema.enumeration("startNow", "queue", "guide")),
                "modelSelection" to PField.optional(PSchema.Obj(modelSelectionShape())),
                "mode" to PField.optional(PSchema.enumeration("build", "edit", "plan", "yolo")),
                "planEnabled" to PField.optional(PSchema.Bool),
                "heldQueueDisposition" to PField.optional(
                    PSchema.enumeration("clearQueueAndSend", "keepQueueAndSend"),
                ),
                "expectedHeldQueueItemIds" to PField.optional(PSchema.Arr(element = PSchema.nonEmptyString, maxItems = null)),
            ),
        )
    }

    private fun createSessionShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "workspaceId" to PField.required(PSchema.nonEmptyString),
                "firstInput" to PField.optional(
                    PSchema.Obj(
                        PObjectShape(
                            fields = mapOf(
                                "text" to PField.required(PSchema.string),
                                "attachments" to attachmentsField(),
                                "modelSelection" to PField.optional(PSchema.Obj(modelSelectionShape())),
                                "mode" to PField.optional(PSchema.enumeration("build", "edit", "plan", "yolo")),
                                "planEnabled" to PField.optional(PSchema.Bool),
                            ),
                        ),
                    ),
                ),
                "config" to PField.optional(
                    PSchema.Obj(
                        PObjectShape(
                            fields = mapOf(
                                "modelSelection" to PField.optional(PSchema.Obj(modelSelectionShape())),
                                "provider" to PField.optional(PSchema.string),
                                "model" to PField.optional(PSchema.string),
                                "thought" to PField.optional(PSchema.string),
                                "followupMode" to PField.optional(PSchema.enumeration("queue", "guide")),
                                "mode" to PField.optional(PSchema.string),
                                "planEnabled" to PField.optional(PSchema.Bool),
                            ),
                        ),
                    ),
                ),
                "offPeakToolEnabled" to PField.optional(PSchema.Bool),
                "dynamicWorkflowEnabled" to PField.optional(PSchema.Bool),
            ),
        )
    }

    /**
     * resolveInteraction.answer【command.ts:176-188；answer 键全 optional，
     * 无 superRefine 三选一强制——调用方语义保证】（Swift :262-269）
     */
    private fun answerShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "optionId" to PField.optional(PSchema.string),
                "freeText" to PField.optional(PSchema.string),
                "action" to PField.optional(PSchema.enumeration("accept", "decline", "cancel")),
                "content" to PField.optional(PSchema.AnyJson),
            ),
        )
    }

    // MARK: 附件四方法参数（transport.ts:832-952，全 .strict()；供发送前校验）
    //
    // 注：参数另含会话域 workspace 信封（workspacePath/workspaceIdentity?）与
    // sessionId——信封键不在上游 strict 键集内但服务端实际接受（实机实证
    // 2026-10-06「缺 workspacePath 被 Invalid params 拒」），故本校验对信封键
    // 白名单放行、其余未知键仍拒。

    fun attachmentBeginParamsShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "uploadId" to PField.required(PSchema.PatternedStr(PPattern.uploadId)),
                "sessionId" to PField.required(PSchema.nonEmptyString),
                "fileName" to PField.required(PSchema.PatternedStr(PPattern.fileName)),
                "mime" to PField.required(PSchema.PatternedStr(PPattern.mime)),
                "totalBytes" to PField.required(PSchema.Integer(min = 0, max = attachmentMaxBytes)),
                "totalChunks" to PField.required(PSchema.Integer(min = 0, max = attachmentUploadMaxChunks)),
                "checksum" to PField.required(PSchema.PatternedStr(PPattern.sha256Checksum)),
                // 会话域信封键（服务端接受；identity 缺席不携键）
                "workspacePath" to PField.optional(PSchema.nonEmptyString),
                "workspaceIdentity" to PField.optional(PSchema.nonEmptyString),
            ),
            strict = true,
            deny = emptySet(),
        )
    }

    fun attachmentChunkParamsShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "uploadId" to PField.required(PSchema.PatternedStr(PPattern.uploadId)),
                "sessionId" to PField.required(PSchema.nonEmptyString),
                "chunkIndex" to PField.required(PSchema.Integer(min = 0, max = null)),
                "dataBase64" to PField.required(PSchema.StrictBase64(maxBytes = attachmentChunkMaxBytes)),
                "workspacePath" to PField.optional(PSchema.nonEmptyString),
                "workspaceIdentity" to PField.optional(PSchema.nonEmptyString),
            ),
            strict = true,
            deny = emptySet(),
        )
    }

    fun attachmentFinishParamsShape(): PObjectShape {
        return PObjectShape(
            fields = mapOf(
                "uploadId" to PField.required(PSchema.PatternedStr(PPattern.uploadId)),
                "sessionId" to PField.required(PSchema.nonEmptyString),
                "workspacePath" to PField.optional(PSchema.nonEmptyString),
                "workspaceIdentity" to PField.optional(PSchema.nonEmptyString),
            ),
            strict = true,
            deny = emptySet(),
        )
    }
}

// MARK: - ACK 解析（command.ts:430-443 commandAckSchema + 中继 ack 包裹宽容形态）

/**
 * v4 命令回执的统一读面。status 词表六态为准（accepted/rejected/stale/duplicate/
 * noop/failed）；非 accepted/duplicate/noop 必带 reasonCode【command.ts:437-443】。
 * 中继桥回执为 `{ack:{…}}` 包裹、局域网直连为顶层——两形态宽容解析（§4.3-4：
 * 宽容解析 ≠ 协议事实）。旧桌面缺 status 键按成功处理（不破坏既有可用路径）。
 *
 * （Swift :326-414）Swift `struct: Equatable` 的合成等值含 private dict，但 dict
 * 由 raw 确定性导出——Kotlin data class 等值仅取主构造属性 raw，行为等价。
 */
data class CommandAck(val raw: JSONValue?) {

    /**
     * 顶层 ?? ack 包裹双形态宽容合并（顶层 {"ack":{…}} 整体是对象，简单 `?:`
     * 会短路永取不到包裹内的 status）。（Swift init :331-349）
     *
     * 【上游裁决（2026-10-09 直译前核实 /tmp/zcode-upstream 克隆件）】上游**仅定义
     * 顶层扁平形态**（command.ts:430-443 commandAckSchema：commandId/status 六态/
     * reasonCode?/message?/revisionAtDecision/result?，无任何包裹键）；ack 包裹在上游
     * 只属于**订阅回执**（transport.ts:444-495 的 v4ConversationSubscribeResultSchema 等
     * `.strict()` 的 `{ack:…}`、controller.ts:269/:287 同族——其 result.ack 是
     * subscribeAck 不是 commandAck），命令回执无包裹定义，web 侧全部扁平直读
     * （pendingCommandRegistry.ts 的 `ack.status`/`ack.reasonCode`、
     * agentConversationTransport.ts:350 `sendCommand(): Promise<CommandAck>`）。
     * 故顶层/包裹两形态互斥、混合形态上游不存在且优先级无定义。
     *
     * ⚠ iOS 原文注释与代码自相矛盾（禁止照抄其注释）：原文注释
     * （CommandSchemas.swift:333-335）称「顶层键优先，缺席键回退包裹层」，但原文
     * 代码（:344-348）`var merged = wrapped` 起步、`merged[key] == nil` 才从顶层补入
     * ——实际行为是**包裹层打底、顶层补缺**。本直译按裁决保留双形态宽容，混合
     * 合并与 iOS 代码行为一致（包裹层打底、顶层补缺）；**混合优先级为实现自定，
     * 非协议事实**。
     */
    private val dict: Map<String, JSONValue>? = run {
        val top = raw?.objectValue ?: return@run null
        val wrapped = top["ack"]?.objectValue ?: return@run top
        val merged = LinkedHashMap(wrapped)
        for ((key, value) in top) {
            if (key != "ack" && !merged.containsKey(key)) {
                merged[key] = value
            }
        }
        merged
    }

    val status: String?
        get() = dict?.get("status")?.stringValue

    val reasonCode: String?
        get() = dict?.get("reasonCode")?.stringValue

    val message: String?
        get() = dict?.get("message")?.stringValue

    val revisionAtDecision: Int?
        get() = dict?.get("revisionAtDecision")?.intValue

    val result: JSONValue?
        get() = dict?.get("result")

    val statusKnown: Boolean
        get() {
            val s = status ?: return false
            return CommandSchemas.ackStatuses.contains(s)
        }

    /**
     * 失败判定：六态词表内非 accepted/duplicate/noop = 失败；status 缺席（旧桌面）
     * 或词表外遗留值（applied/ok 等旧通道回执）按成功（宽容，与既有行为一致）。
     */
    val isFailure: Boolean
        get() {
            val s = status
            if (s == null || !statusKnown) return false
            return !(s == "accepted" || s == "duplicate" || s == "noop")
        }

    /**
     * 权威成功：六态词表内的非失败态（accepted/duplicate/noop）。status 缺席或
     * 词表外 = 「未知态」——桌面可能已受理也可能没有。读面可继续宽容，但**落本机
     * 持久化/chip 态只认权威成功**：一次误判落盘即与桌面真态脱钩且无投影时永不
     * 自愈（2026-10-09「计划模式丢失」根因之一）
     */
    val isAuthoritativeSuccess: Boolean
        get() {
            if (status == null || !statusKnown) return false
            return !isFailure
        }

    /**
     * 失败文案（null=成功/宽容成功）。reasonCode（或 status）+ 首条可读 message——
     * message 可能是 zod issue 数组 JSON（取首个 "message": "…" 字段）或普通
     * fault 原文，两者都取到可读文本（v1.21 前两套口径不一致的统一收口）。
     * reasonCode 前缀 `client.*` = 本地拒发（命令未发出，如 CAS revision 未就绪/
     * schema 违规）——文案区分「本地拦截」与「桌面端拒绝」（2026-10-08 用户反馈：
     * 本地拒发曾显示「桌面端拒绝」误导排查方向）。
     */
    val failureText: String?
        get() {
            if (!isFailure) return null
            val readable = firstReadableMessage(message)
            val rc = reasonCode
            if (rc != null && rc.startsWith("client.")) {
                if (readable != null) {
                    return "本地拦截（$rc）：$readable"
                }
                return "本地拦截（$rc），命令未发出"
            }
            var detail = reasonCode ?: status ?: "?"
            if (readable != null) {
                detail += "：" + readable
            }
            return "桌面端拒绝（$detail）"
        }

    companion object {
        /**
         * message 可读化：zod issue 数组 JSON 取首条 message 字段（带/不带空格两种
         * 序列化形态都兼容——JSON.stringify 无空格，手工构造常带空格）；普通文本直接前缀。
         * （Swift :402-413）
         */
        fun firstReadableMessage(raw: String?): String? {
            if (raw.isNullOrEmpty()) return null
            for (probe in arrayOf("\"message\": \"", "\"message\":\"")) {
                val idx = raw.indexOf(probe)
                if (idx >= 0) {
                    val tail = raw.substring(idx + probe.length)
                    // Swift `end > tail.startIndex` = 首个引号不在 tail 第 0 位
                    val end = tail.indexOf('"')
                    if (end > 0) {
                        return tail.substring(0, end)
                    }
                }
            }
            return raw.take(160)
        }
    }
}

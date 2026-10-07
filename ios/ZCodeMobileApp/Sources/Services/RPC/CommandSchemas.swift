import Foundation

// MARK: - v4 协议 schema 单一定义源（上游 zcode-protocol-v4 的 Swift 移植）
//
// 【实证·上游仓 /tmp/zcode-upstream 2026-10-07 克隆件】字段定义逐字移植自
// packages/shared/src/zcode-protocol-v4/{command,core,transport,attachment-ref,
// model-selection,workflow-run-settings-command}.ts，行号为克隆件指位（重克隆可能
// 漂移，以符号检索为准）。上游无人类可读协议文档（schema 自注「未冻结，定型以
// 黄金测试为准」core.ts:1），源码即唯一权威——本文件是移动端的权威镜像，
// 发送构造、回执解析、单测三方共用（构造与校验同源）。
//
// 未移植 payload 的 type（sendGoalCommand/startSavedWorkflow/deleteSession/
// workspaceHook 三族/resumeWorkflowRun 等）：payloadSchema 返回 nil，工厂只做
// 信封层校验（宽容不阻断——上游 payload 为 strip 语义，未知键本就被剥离；接入
// UI 前按 §5-11 取证后补条目）。

enum CommandSchemas {

    // MARK: 权威词表

    /// CAS 类命令全集（缺 baseRevision 构造即拒）【实证·上游仓 command.ts:296-320
    /// COMMANDS_REQUIRING_BASE_REVISION，逐字】
    static let commandsRequiringBaseRevision: Set<String> = [
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback", "sendQueuedNow", "editQueueItem", "reorderQueueItem",
        "deleteQueueItem", "setAutoDrain", "switchModelConfig",
        "switchCollaborationMode", "setFollowupMode", "pauseGoal", "resumeGoal",
    ]

    /// row-target 类（另要求 baseLogEpoch；为本表的子集）【command.ts:309-320】
    static let rowTargetingCommands: Set<String> = [
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback",
    ]

    /// ACK 六态词表【command.ts:430-443】
    static let ackStatuses: Set<String> = [
        "accepted", "rejected", "stale", "duplicate", "noop", "failed",
    ]

    /// 服务端注入的可信字段（客户端构造携带即违规）
    /// 【zcodeAgentConnectionScope.ts:94-105 withTrustedConnection】
    static let trustedFieldDenyList: Set<String> = [
        "connectionId", "clientMode", "workflowRunDeltas",
    ]

    /// 附件常量【core.ts:84-96 PROTOCOL_V4_LIMITS】
    static let attachmentMaxBytes = 20 * 1024 * 1024      // 20 MiB
    static let attachmentChunkMaxBytes = 512 * 1024       // 512 KiB
    static let attachmentUploadMaxChunks = 64

    /// 信封 type 已知词表（commandTypeSchema 子集=已移植面）。未列入的 type 不硬拒
    /// （词表不完整是常态——上游全集大于移植面），仅告警提示核对。
    static let knownTypes: Set<String> = [
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
    ]

    // MARK: 信封 schema（command.ts:323-336 commandEnvelopeSchema）
    //
    // 上游信封为 strip；本表取 strict=构造纪律（我们只发自己构造的信封，出现未知
    // 键=构造 bug，构造期即爆——官方 commandFactory.ts:51-70「CAS/row-target 缺字段
    // 构造时即抛」同思路，比服务端口径更严，方向安全）。

    static func envelopeShape() -> PObjectShape {
        PObjectShape(
            fields: [
                "commandId": .required(.nonEmptyString),   // uuid，重试不变（A12）
                "clientId": .required(.nonEmptyString),    // 与握手逐字相等（A2）
                "sessionId": .requiredNullable(.nonEmptyString), // 键恒在场（A1）
                "type": .required(.nonEmptyString),
                "payload": .required(.anyJSON),
                "issuedAt": .required(.number),            // Unix 毫秒
                "baseRevision": .optional(.number),        // CAS 双字段
                "baseLogEpoch": .optional(.nonEmptyString),
            ],
            strict: true,
            deny: [])
    }

    // MARK: payload schema（command.ts:44-247，除标注外均默认 strip）

    static func payloadSchema(for type: String) -> PSchema? {
        switch type {
        case "sendText":
            return .object(sendTextShape())
        case "stop":
            return .object(PObjectShape(fields: [
                "expectedForegroundExecutionId": .optional(.nonEmptyString),
            ]))
        case "createSession":
            return .object(createSessionShape())
        case "switchModelConfig":
            return .object(PObjectShape(fields: [
                "provider": .required(.string),   // 上游无 min（thought 可空串同构）
                "model": .required(.string),
                "thought": .required(.string),
            ]))
        case "switchCollaborationMode":
            return .object(PObjectShape(fields: [
                "mode": .required(.enumeration("build", "edit", "plan", "yolo")),
            ]))
        case "setFollowupMode":
            return .object(PObjectShape(fields: [
                "mode": .required(.enumeration("queue", "guide")),
            ]))
        case "setAssistantFeedback":
            return .object(PObjectShape(fields: [
                "target": .required(rowTarget()),
                // A-1 教训：值域 like|dislike（positive/negative 全拒）
                "feedback": .requiredNullable(.enumeration("like", "dislike")),
            ]))
        case "editUserQuery":
            return .object(PObjectShape(fields: [
                "target": .required(rowTarget()),
                "newText": .required(.string),
                "attachments": attachmentsField(),
                "workspaceMode": .optional(.enumeration("preserve", "rewind")),
            ]))
        case "resolveInteraction":
            return .object(PObjectShape(fields: [
                "interactionId": .required(.nonEmptyString),
                "answer": .required(.object(answerShape())),
            ]))
        case "pauseGoal", "resumeGoal", "compact":
            return .object(PObjectShape.empty())
        case "snoozeInteractionAutoResolution":
            return .object(PObjectShape(fields: [
                "interactionId": .required(.nonEmptyString),
            ]))
        case "cancelBackgroundWork":
            return .object(PObjectShape(fields: [
                "workId": .required(.nonEmptyString),
            ]))
        case "amendWorkflowRunSettings":
            return .object(PObjectShape(fields: [
                "workId": .required(.string),
                "subagentModel": .optionalNullable(.nonEmptyString), // "providerId/modelId[$level]"，null=回会话模型
                "maxConcurrency": .optionalNullable(.integer(min: 1, max: nil)),
            ]))
        case "sendQueuedNow", "deleteQueueItem":
            return .object(PObjectShape(fields: [
                "queueItemId": .required(.string),
            ]))
        case "editQueueItem":
            return .object(PObjectShape(fields: [
                "queueItemId": .required(.string),
                "newText": .required(.string),
            ]))
        case "reorderQueueItem":
            return .object(PObjectShape(fields: [
                "queueItemId": .required(.string),
                "beforeQueueItemId": .requiredNullable(.nonEmptyString), // null=移到队尾
            ]))
        case "setAutoDrain":
            return .object(PObjectShape(fields: [
                "autoDrain": .required(.boolean),
            ]))
        case "retryTurn", "forkAssistant":
            return .object(PObjectShape(fields: [
                "target": .required(rowTarget()),
            ]))
        default:
            return nil // 未移植：信封层校验 + 构造告警（strip 语义不阻断）
        }
    }

    private static func attachmentsField() -> PField {
        .optional(.array(.object(attachmentRefShape()), maxItems: nil))
    }

    /// 行游标【core.ts:10-15 conversationRowTargetSchema，.strict()】
    static func rowTarget() -> PSchema {
        .object(PObjectShape(
            fields: [
                "rowId": .required(.integer(min: 0, max: nil)),
                "entityId": .required(.nonEmptyString),   // trim min(1)
            ],
            strict: true, deny: []))
    }

    /// 附件引用【attachment-ref.ts:4-12，.strict()】
    static func attachmentRefShape() -> PObjectShape {
        PObjectShape(
            fields: [
                "ref": .required(.string),
                "fileName": .required(.string),
                "mime": .required(.string),
                "bytes": .required(.integer),
                "previewRef": .optional(.string),
            ],
            strict: true, deny: [])
    }

    /// modelSelection（sendText.modelSelection / firstInput.modelSelection 同形）
    /// 【model-selection.ts:4-15，.strict()】
    static func modelSelectionShape() -> PObjectShape {
        PObjectShape(
            fields: [
                "providerId": .required(.nonEmptyString),  // trim min(1)
                "modelId": .required(.nonEmptyString),
                "options": .optional(.object(PObjectShape(
                    fields: ["reasoningLevel": .optional(.nonEmptyString)],
                    strict: true, deny: []))),
            ],
            strict: true, deny: [])
    }

    private static func sendTextShape() -> PObjectShape {
        PObjectShape(fields: [
            "text": .required(.string),
            "attachments": attachmentsField(),
            "requestedDelivery": .optional(.enumeration("startNow", "queue", "guide")),
            "modelSelection": .optional(.object(modelSelectionShape())),
            "mode": .optional(.enumeration("build", "edit", "plan", "yolo")),
            "planEnabled": .optional(.boolean),
            "heldQueueDisposition": .optional(
                .enumeration("clearQueueAndSend", "keepQueueAndSend")),
            "expectedHeldQueueItemIds": .optional(.array(.nonEmptyString, maxItems: nil)),
        ])
        // 上游另有 browserAmbientContext/context_refs/automationId/offPeak*/botDeliveryTarget/
        // toolDisallowlist/modelExecution 及三条 superRefine 互斥（command.ts:81-134）——
        // 移动端发送面不含这些键，未移植；接入时按上游补。
    }

    private static func createSessionShape() -> PObjectShape {
        PObjectShape(fields: [
            "workspaceId": .required(.nonEmptyString),
            "firstInput": .optional(.object(PObjectShape(fields: [
                "text": .required(.string),
                "attachments": attachmentsField(),
                "modelSelection": .optional(.object(modelSelectionShape())),
                "mode": .optional(.enumeration("build", "edit", "plan", "yolo")),
                "planEnabled": .optional(.boolean),
            ]))),
            "config": .optional(.object(PObjectShape(fields: [
                "modelSelection": .optional(.object(modelSelectionShape())),
                "provider": .optional(.string),
                "model": .optional(.string),
                "thought": .optional(.string),
                "followupMode": .optional(.enumeration("queue", "guide")),
                "mode": .optional(.string),
                "planEnabled": .optional(.boolean),
            ]))),
            "offPeakToolEnabled": .optional(.boolean),
            "dynamicWorkflowEnabled": .optional(.boolean),
        ])
    }

    /// resolveInteraction.answer【command.ts:176-188；answer 键全 optional，
    /// 无 superRefine 三选一强制——调用方语义保证】
    private static func answerShape() -> PObjectShape {
        PObjectShape(fields: [
            "optionId": .optional(.string),
            "freeText": .optional(.string),
            "action": .optional(.enumeration("accept", "decline", "cancel")),
            "content": .optional(.anyJSON),
        ])
    }

    // MARK: 附件四方法参数（transport.ts:832-952，全 .strict()；供发送前校验）
    //
    // 注：参数另含会话域 workspace 信封（workspacePath/workspaceIdentity?）与
    // sessionId——信封键不在上游 strict 键集内但服务端实际接受（实机实证
    // 2026-10-06「缺 workspacePath 被 Invalid params 拒」），故本校验对信封键
    // 白名单放行、其余未知键仍拒。

    static func attachmentBeginParamsShape() -> PObjectShape {
        PObjectShape(
            fields: [
                "uploadId": .required(.patternedString(.uploadId)),
                "sessionId": .required(.nonEmptyString),
                "fileName": .required(.patternedString(.fileName)),
                "mime": .required(.patternedString(.mime)),
                "totalBytes": .required(.integer(min: 0, max: attachmentMaxBytes)),
                "totalChunks": .required(.integer(min: 0, max: attachmentUploadMaxChunks)),
                "checksum": .required(.patternedString(.sha256Checksum)),
                // 会话域信封键（服务端接受；identity 缺席不携键）
                "workspacePath": .optional(.nonEmptyString),
                "workspaceIdentity": .optional(.nonEmptyString),
            ],
            strict: true, deny: [])
    }

    static func attachmentChunkParamsShape() -> PObjectShape {
        PObjectShape(
            fields: [
                "uploadId": .required(.patternedString(.uploadId)),
                "sessionId": .required(.nonEmptyString),
                "chunkIndex": .required(.integer(min: 0, max: nil)),
                "dataBase64": .required(.strictBase64(maxBytes: attachmentChunkMaxBytes)),
                "workspacePath": .optional(.nonEmptyString),
                "workspaceIdentity": .optional(.nonEmptyString),
            ],
            strict: true, deny: [])
    }

    static func attachmentFinishParamsShape() -> PObjectShape {
        PObjectShape(
            fields: [
                "uploadId": .required(.patternedString(.uploadId)),
                "sessionId": .required(.nonEmptyString),
                "workspacePath": .optional(.nonEmptyString),
                "workspaceIdentity": .optional(.nonEmptyString),
            ],
            strict: true, deny: [])
    }
}

// MARK: - ACK 解析（command.ts:430-443 commandAckSchema + 中继 ack 包裹宽容形态）

/// v4 命令回执的统一读面。status 词表六态为准（accepted/rejected/stale/duplicate/
/// noop/failed）；非 accepted/duplicate/noop 必带 reasonCode【command.ts:437-443】。
/// 中继桥回执为 `{ack:{…}}` 包裹、局域网直连为顶层——两形态宽容解析（§4.3-4：
/// 宽容解析 ≠ 协议事实）。旧桌面缺 status 键按成功处理（不破坏既有可用路径）。
struct CommandAck: Equatable {
    let raw: JSONValue?

    private let dict: [String: JSONValue]?

    init(_ raw: JSONValue?) {
        self.raw = raw
        // 顶层 ?? ack 包裹双形态：顶层（局域网直连）与 ack 包裹（中继桥）字段级
        // 合并——顶层键优先，缺席键回退包裹层（顶层 {"ack":{…}} 整体是对象，
        // 简单 `??` 会短路永取不到包裹内的 status）
        guard let top = raw?.objectValue else {
            self.dict = nil
            return
        }
        guard let wrapped = top["ack"]?.objectValue else {
            self.dict = top
            return
        }
        var merged = wrapped
        for (key, value) in top where key != "ack" && merged[key] == nil {
            merged[key] = value
        }
        self.dict = merged
    }

    var status: String? { dict?["status"]?.stringValue }
    var reasonCode: String? { dict?["reasonCode"]?.stringValue }
    var message: String? { dict?["message"]?.stringValue }
    var revisionAtDecision: Int? { dict?["revisionAtDecision"]?.intValue }
    var result: JSONValue? { dict?["result"] }

    var statusKnown: Bool {
        guard let status else { return false }
        return CommandSchemas.ackStatuses.contains(status)
    }

    /// 失败判定：六态词表内非 accepted/duplicate/noop = 失败；status 缺席（旧桌面）
    /// 或词表外遗留值（applied/ok 等旧通道回执）按成功（宽容，与既有行为一致）。
    var isFailure: Bool {
        guard let status, statusKnown else { return false }
        return !(status == "accepted" || status == "duplicate" || status == "noop")
    }

    /// 失败文案（nil=成功/宽容成功）。reasonCode（或 status）+ 首条可读 message——
    /// message 可能是 zod issue 数组 JSON（取首个 "message": "…" 字段）或普通
    /// fault 原文，两者都取到可读文本（v1.21 前两套口径不一致的统一收口）。
    /// reasonCode 前缀 `client.*` = 本地拒发（命令未发出，如 CAS revision 未就绪/
    /// schema 违规）——文案区分「本地拦截」与「桌面端拒绝」（2026-10-08 用户反馈：
    /// 本地拒发曾显示「桌面端拒绝」误导排查方向）。
    var failureText: String? {
        guard isFailure else { return nil }
        let readable = Self.firstReadableMessage(message)
        if let reasonCode, reasonCode.hasPrefix("client.") {
            if let readable {
                return String(localized: "本地拦截（\(reasonCode)）：\(readable)")
            }
            return String(localized: "本地拦截（\(reasonCode)），命令未发出")
        }
        var detail = reasonCode ?? status ?? "?"
        if let readable {
            detail += "：" + readable
        }
        return String(localized: "桌面端拒绝（\(detail)）")
    }

    /// message 可读化：zod issue 数组 JSON 取首条 message 字段（带/不带空格两种
    /// 序列化形态都兼容——JSON.stringify 无空格，手工构造常带空格）；普通文本直接前缀。
    static func firstReadableMessage(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        for probe in ["\"message\": \"", "\"message\":\""] {
            if let range = raw.range(of: probe) {
                let tail = raw[range.upperBound...]
                if let end = tail.firstIndex(of: "\""), end > tail.startIndex {
                    return String(tail[..<end])
                }
            }
        }
        return String(raw.prefix(160))
    }
}

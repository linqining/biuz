import Foundation

// MARK: - v4 会话命令单点构造器（官方 commandFactory 的 Swift 镜像）
//
// 【实证·上游仓 ui/src/v4/commandFactory.ts:51-70】官方客户端业务代码从不手拼
// 信封——CAS/row-target 缺字段在**构造时**即抛，clientId 持久化单一来源。本类型
// 是移动端唯一合法的信封构造点：RemoteConversationStore.sendCommand 委托此处，
// 任何绕开本工厂构造 sendConversationCommandV4 载荷的代码都违反信封纪律
// （AGENTS §5-2；反面教材：RemoteTaskStore.stop 自造信封）。
//
// 构造即校验（构造与校验同源）：产物再经 CommandSchemas.envelopeShape 校验——
// builder 不可能构造出 schema 拒收的东西，形状错误在发送前、本地即爆。
//
// 失败语义：本工厂不 throw 到调用方上层，而是返回本地合成拒收回执
// （status=rejected + reasonCode=client.*），走调用方既有失败呈现链
// （假成功禁令 §5-12；先例：client.casRevisionUnavailable，v1.21）。

/// 一次 v4 会话命令的构造输入
struct ConversationCommandRequest {
    let type: String
    /// 目标会话；nil 仅限 createSession（信封 sessionId=null，键恒在场 A1）
    let sessionId: String?
    let payload: JSONValue
    /// CAS 水位（baseRevision + baseLogEpoch 双字段，A3）。非 CAS 命令传 nil；
    /// CAS 词表内命令传 nil = 构造失败（client.casRevisionUnavailable）
    let casRevision: (revision: Int, logEpoch: String)?
    /// 会话归属 workspace（A7：写面按会话归属寻址，非当前连接 workspace）
    let workspacePath: String
    let workspaceIdentity: String?
    /// 幂等键：重试必须复用同一 commandId（A12，上游 duplicate 回放语义）
    let commandId: String
}

enum ConversationCommandFactory {

    /// 本地构造失败 → 合成拒收回执（不发出注定被拒/违规的写）
    struct Rejection: Error {
        let reasonCode: String
        let message: String

        /// 合成拒收回执（与 v1.21 client.casRevisionUnavailable 同形态）
        var syntheticAck: JSONValue {
            .object([
                "status": .string("rejected"),
                "reasonCode": .string(reasonCode),
                "message": .string(message),
            ])
        }
    }

    struct Built {
        /// sendConversationCommandV4 完整参数：{envelope:{…}, workspacePath,
        /// workspaceIdentity?}（嵌套形态 + 扁平 workspace 信封，§7.1 实证）
        let outer: JSONValue
        let warnings: [String]
    }

    static func build(
        _ request: ConversationCommandRequest,
        clientId: String,
        issuedAt: Int
    ) -> Result<Built, Rejection> {
        // ① payload 校验/规范化（strip 语义 + 可信字段黑名单）
        var warnings: [String] = []
        let normalizedPayload: JSONValue
        if let schema = CommandSchemas.payloadSchema(for: request.type) {
            let validation = PValidator.validate(request.payload, schema: schema, path: "payload")
            guard validation.isValid, let value = validation.value else {
                return .failure(Rejection(
                    reasonCode: "client.schemaViolation",
                    message: "payload 校验失败：" + validation.errors.prefix(3).joined(separator: "; ")))
            }
            normalizedPayload = value
            warnings += validation.warnings
        } else {
            normalizedPayload = request.payload
            if request.payload.objectValue?.keys.isEmpty == false {
                warnings.append("payload schema 未移植（type=\(request.type)），仅信封层校验")
            }
        }

        // ② 信封构造（键恒在场：sessionId createSession=null、其余=目标 id，A1）
        var envelope: [String: JSONValue] = [
            "commandId": .string(request.commandId),
            "clientId": .string(clientId),
            "type": .string(request.type),
            "payload": normalizedPayload,
            "issuedAt": .int(issuedAt),
        ]
        envelope["sessionId"] = request.sessionId.map { .string($0) } ?? .null
        if let cas = request.casRevision {
            envelope["baseRevision"] = .int(cas.revision)
            envelope["baseLogEpoch"] = .string(cas.logEpoch)
        }

        // ③ 构造即校验：信封经同一份 schema 复核（builder 不可能产出 schema 拒收物）
        let envelopeCheck = PValidator.validate(
            .object(envelope), schema: .object(CommandSchemas.envelopeShape()), path: "envelope")
        guard envelopeCheck.isValid else {
            return .failure(Rejection(
                reasonCode: "client.schemaViolation",
                message: "信封校验失败：" + envelopeCheck.errors.prefix(3).joined(separator: "; ")))
        }
        warnings += envelopeCheck.warnings

        // ④ CAS 门（官方 commandFactory「构造时即抛」+ 上游 parseCommandEnvelope
        // command.ts:340-367 第三步的本地前移）：词表内命令缺水位 = 构造失败，
        // 不空耗注定被拒的写（也不静默发出无 revision 的 CAS 命令）
        let isCAS = CommandSchemas.commandsRequiringBaseRevision.contains(request.type)
        if isCAS, request.casRevision == nil {
            return .failure(Rejection(
                reasonCode: "client.casRevisionUnavailable",
                message: String(localized: "会话状态 revision 未就绪（快照未到达），命令未发出")))
        }

        if !CommandSchemas.knownTypes.contains(request.type) {
            warnings.append("type=\(request.type) 不在已知词表，请核对拼写并补 schema 条目")
        }

        // ⑤ workspace 信封（扁平形态；identity 缺席不携键——web scope 转换器同构）
        var outer: [String: JSONValue] = ["envelope": .object(envelope)]
        outer["workspacePath"] = .string(request.workspacePath)
        if let identity = request.workspaceIdentity, !identity.isEmpty {
            outer["workspaceIdentity"] = .string(identity)
        }
        return .success(Built(outer: .object(outer), warnings: warnings))
    }
}

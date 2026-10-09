package cn.biuz.android.protocol

// 直译自 ios/ZCodeMobileApp/Sources/Services/RPC/ConversationCommandFactory.swift（128 行）。
//
// v4 会话命令单点构造器（官方 commandFactory 的 Swift 镜像）。
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
// 失败语义：本工厂不向调用方上层抛异常，而是返回本地合成拒收回执
// （status=rejected + reasonCode=client.*），走调用方既有失败呈现链
// （假成功禁令 §5-12；先例：client.casRevisionUnavailable，v1.21）。
//
// 机械映射约定（全文件统一，与 Models.kt 同口径）：
// - Swift 命名元组 `(revision: Int, logEpoch: String)?` → Kotlin data class
//   [CasRevision]（Kotlin 无命名元组）；
// - Swift `Int`（64 位）标量 issuedAt/revision → Kotlin Long；落 JSONValue 经
//   [intJson]（Int32 值域内 .int、越界 .double 整值字面量——JSONValue.fromAny 的
//   Long 分支口径，线缆 JSON 字面量形态不变）；
// - Swift `Result<Built, Rejection>` → Kotlin `Result<Built>`（stdlib Result 的
//   failure 通道为 Throwable，Rejection 继承 Exception；与 ChannelClient.kt 的
//   RPCError 异常先例一致）；
// - `String(localized:)` → 同串字面量（见 Models.kt 文件头约定）。

/** Swift `(revision: Int, logEpoch: String)` 命名元组的 Kotlin 对应物（Kotlin 无命名元组）。 */
data class CasRevision(
    /** Swift Int（64 位）→ Long；落 JSONValue 经 intJson（Int32 越界落 .double）。 */
    val revision: Long,
    val logEpoch: String,
)

/**
 * 一次 v4 会话命令的构造输入（Swift :19-32）。
 */
data class ConversationCommandRequest(
    val type: String,
    /** 目标会话；null 仅限 createSession（信封 sessionId=null，键恒在场 A1） */
    val sessionId: String?,
    val payload: JSONValue,
    /** CAS 水位（baseRevision + baseLogEpoch 双字段，A3）。非 CAS 命令传 null；
     * CAS 词表内命令传 null = 构造失败（client.casRevisionUnavailable） */
    val casRevision: CasRevision?,
    /** 会话归属 workspace（A7：写面按会话归属寻址，非当前连接 workspace） */
    val workspacePath: String,
    val workspaceIdentity: String?,
    /** 幂等键：重试必须复用同一 commandId（A12，上游 duplicate 回放语义） */
    val commandId: String,
)

object ConversationCommandFactory {

    /**
     * 本地构造失败 → 合成拒收回执（不发出注定被拒/违规的写）。（Swift :37-49）
     * Swift `struct Rejection: Error` → Kotlin Exception 子类（stdlib Result 的
     * failure 通道；message 为原文，RPCSerialization/ChannelClient 同先例）。
     */
    class Rejection(val reasonCode: String, override val message: String) : Exception(message) {
        /** 合成拒收回执（与 v1.21 client.casRevisionUnavailable 同形态） */
        val syntheticAck: JSONValue
            get() = JSONValue.Obj(
                mapOf(
                    "status" to JSONValue.Str("rejected"),
                    "reasonCode" to JSONValue.Str(reasonCode),
                    "message" to JSONValue.Str(message),
                ),
            )
    }

    /**
     * 构造产物（Swift :51-56）。
     * [outer] = sendConversationCommandV4 完整参数：{envelope:{…}, workspacePath,
     * workspaceIdentity?}（嵌套形态 + 扁平 workspace 信封，§7.1 实证）
     */
    data class Built(val outer: JSONValue, val warnings: List<String>)

    /**
     * Swift `.int(issuedAt)`（64 位 Int）对应物：Int32 值域内落 .int、越界落 .double
     * （整值 double 序列化为整型字面量，线缆 JSON 形态不变——JSONValue.fromAny 的
     * Long 分支口径，见 RPCSerialization.kt KDoc）。issuedAt 毫秒时间戳恒越 Int32，
     * 由本口径落 .double；服务端 zod number 校验两形态同收。
     */
    private fun intJson(value: Long): JSONValue = JSONValue.fromAny(value) ?: JSONValue.Null

    fun build(
        request: ConversationCommandRequest,
        clientId: String,
        issuedAt: Long,
    ): Result<Built> {
        // ① payload 校验/规范化（strip 语义 + 可信字段黑名单）
        val warnings = ArrayList<String>()
        val normalizedPayload: JSONValue
        val schema = CommandSchemas.payloadSchema(request.type)
        if (schema != null) {
            val validation = PValidator.validate(request.payload, schema = schema, path = "payload")
            if (!validation.isValid || validation.value == null) {
                return Result.failure(
                    Rejection(
                        reasonCode = "client.schemaViolation",
                        message = "payload 校验失败：" + validation.errors.take(3).joinToString("; "),
                    ),
                )
            }
            normalizedPayload = validation.value
            warnings += validation.warnings
        } else {
            normalizedPayload = request.payload
            if (request.payload.objectValue?.keys?.isNotEmpty() == true) {
                warnings.add("payload schema 未移植（type=${request.type}），仅信封层校验")
            }
        }

        // ② 信封构造（键恒在场：sessionId createSession=null、其余=目标 id，A1）
        // LinkedHashMap 保插入序（Swift Foundation 字典序未定义，键集一致）
        val envelope = LinkedHashMap<String, JSONValue>()
        envelope["commandId"] = JSONValue.Str(request.commandId)
        envelope["clientId"] = JSONValue.Str(clientId)
        envelope["type"] = JSONValue.Str(request.type)
        envelope["payload"] = normalizedPayload
        envelope["issuedAt"] = intJson(issuedAt)
        envelope["sessionId"] = request.sessionId?.let { JSONValue.Str(it) } ?: JSONValue.Null
        request.casRevision?.let { cas ->
            envelope["baseRevision"] = intJson(cas.revision)
            envelope["baseLogEpoch"] = JSONValue.Str(cas.logEpoch)
        }

        // ③ 构造即校验：信封经同一份 schema 复核（builder 不可能产出 schema 拒收物）
        val envelopeCheck = PValidator.validate(
            JSONValue.Obj(envelope),
            schema = PSchema.Obj(CommandSchemas.envelopeShape()),
            path = "envelope",
        )
        if (!envelopeCheck.isValid) {
            return Result.failure(
                Rejection(
                    reasonCode = "client.schemaViolation",
                    message = "信封校验失败：" + envelopeCheck.errors.take(3).joinToString("; "),
                ),
            )
        }
        warnings += envelopeCheck.warnings

        // ④ CAS 门（官方 commandFactory「构造时即抛」+ 上游 parseCommandEnvelope
        // command.ts:340-367 第三步的本地前移）：词表内命令缺水位 = 构造失败，
        // 不空耗注定被拒的写（也不静默发出无 revision 的 CAS 命令）
        val isCAS = CommandSchemas.commandsRequiringBaseRevision.contains(request.type)
        if (isCAS && request.casRevision == null) {
            return Result.failure(
                Rejection(
                    reasonCode = "client.casRevisionUnavailable",
                    message = "会话状态 revision 未就绪（快照未到达），命令未发出",
                ),
            )
        }

        if (!CommandSchemas.knownTypes.contains(request.type)) {
            warnings.add("type=${request.type} 不在已知词表，请核对拼写并补 schema 条目")
        }

        // ⑤ workspace 信封（扁平形态；identity 缺席不携键——web scope 转换器同构）
        val outer = LinkedHashMap<String, JSONValue>()
        outer["envelope"] = JSONValue.Obj(envelope)
        outer["workspacePath"] = JSONValue.Str(request.workspacePath)
        if (!request.workspaceIdentity.isNullOrEmpty()) {
            outer["workspaceIdentity"] = JSONValue.Str(request.workspaceIdentity)
        }
        return Result.success(Built(outer = JSONValue.Obj(outer), warnings = warnings))
    }
}

package cn.biuz.android.teststub

import cn.biuz.android.protocol.CommandSchemas
import cn.biuz.android.protocol.JSONValue

// sendConversationCommandV4 回执伪造面（A13 六态词表可注水）。
// iOS 原文：E2ELoginStubServer.handleConversationCommand 的各 reply 分支
// （E2ELoginStubServer.swift:1815-2029）。
//
// 【上游裁决 2026-10-09 /tmp/zcode-upstream】commandAckSchema
// （packages/shared/src/zcode-protocol-v4/command.ts:430-444）：
//   { memoryEnabled?, ttftExcluded?:"capacity", commandId, status: 六态枚举,
//     reasonCode?, message?, revisionAtDecision: number（**必填**）, result? }
// iOS 替身的 accepted 分支不带 revisionAtDecision（E2ELoginStubServer.swift:1980-1984）
// ——与上游必填形状不符，本实现恒携带（对拍更贴上游真实形态）。

/**
 * ACK 行为注水：按 envelope.type 注入，验证客户端对六态词表各态的处理
 * （stale 重试链 / rejected 拒因上屏 / noop·duplicate 权威成功 / failed 语义拒绝）。
 * iOS 原文的 stale-once（switchModelConfig，:1956-1985）与 collabMode 三注水
 * （staleOnce/statusAbsent/reject，:1986-2025）归纳为本密封类。
 */
sealed class StubAckBehavior {
    /** 默认：accepted + result.ok。result 可注水（createSession 的 {type,sessionId,input} 同路）。 */
    data class Accept(val result: JSONValue? = null) : StubAckBehavior()

    /** rejected + reasonCode/message（拒因原文上屏断言数据源）。 */
    data class Reject(val reasonCode: String, val message: String) : StubAckBehavior()

    /** stale + proto.staleRevision（CAS 落后，revisionAtDecision 携最新 revision）。 */
    data class Stale(val revisionAtDecision: Int) : StubAckBehavior()

    /** 首击 stale、次击起 accepted（真机 diag.wf.control.ui 实证形态：活跃会话单发必撞 stale，客户端应原样重发一次）。 */
    object StaleOnceThenAccept : StubAckBehavior()

    /** duplicate（重放缓存结果；客户端权威成功词表）。 */
    object Duplicate : StubAckBehavior()

    /** noop（同值切换等；上游 main 回 config.unchanged 同族权威成功）。 */
    object Noop : StubAckBehavior()

    /** failed（语义拒绝，例：backgroundWorkCancelRejected.not_found）。 */
    data class Failed(val reasonCode: String, val message: String) : StubAckBehavior()

    /** 旧桌面形态：回执无 status 键（客户端宽容判定按成功，但权威成功词表外——不得落盘）。 */
    object StatusAbsent : StubAckBehavior()
}

/**
 * ACK 构造（上游 commandAckSchema 键集与顺序：commandId → status → reasonCode? →
 * message? → revisionAtDecision → result?；memoryEnabled/ttftExcluded 不伪造）。
 */
object StubAck {

    /**
     * 按行为产出回执体。[nthCall] 为该 type 的第几次调用（StaleOnceThenAccept 用）；
     * [revisionAtDecision] 为 accepted 语义的会话 revision 回填（A13：恒在场）。
     */
    fun body(
        commandId: String,
        behavior: StubAckBehavior,
        nthCall: Int,
        revisionAtDecision: Int,
    ): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["commandId"] = JSONValue.Str(commandId)
        when (behavior) {
            is StubAckBehavior.Accept -> {
                fields["status"] = JSONValue.Str("accepted")
                fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
                fields["result"] = behavior.result ?: stubObj("ok" to JSONValue.Bool(true))
            }
            is StubAckBehavior.Reject -> {
                fields["status"] = JSONValue.Str("rejected")
                fields["reasonCode"] = JSONValue.Str(behavior.reasonCode)
                fields["message"] = JSONValue.Str(behavior.message)
                fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
            }
            is StubAckBehavior.Stale -> {
                fields["status"] = JSONValue.Str("stale")
                fields["reasonCode"] = JSONValue.Str("proto.staleRevision")
                fields["revisionAtDecision"] = JSONValue.Int(behavior.revisionAtDecision)
            }
            StubAckBehavior.StaleOnceThenAccept -> {
                if (nthCall <= 1) {
                    fields["status"] = JSONValue.Str("stale")
                    fields["reasonCode"] = JSONValue.Str("proto.staleRevision")
                    fields["revisionAtDecision"] = JSONValue.Int(DEFAULT_STALE_REVISION_AT_DECISION)
                } else {
                    fields["status"] = JSONValue.Str("accepted")
                    fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
                    fields["result"] = stubObj("ok" to JSONValue.Bool(true))
                }
            }
            StubAckBehavior.Duplicate -> {
                fields["status"] = JSONValue.Str("duplicate")
                fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
            }
            StubAckBehavior.Noop -> {
                fields["status"] = JSONValue.Str("noop")
                fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
            }
            is StubAckBehavior.Failed -> {
                fields["status"] = JSONValue.Str("failed")
                fields["reasonCode"] = JSONValue.Str(behavior.reasonCode)
                fields["message"] = JSONValue.Str(behavior.message)
                fields["revisionAtDecision"] = JSONValue.Int(revisionAtDecision)
            }
            StubAckBehavior.StatusAbsent -> {
                // 旧桌面宽容形态：无 status 键（CommandAck 宽容判定按成功、词表外不落盘）
                fields["result"] = stubObj("ok" to JSONValue.Bool(true))
            }
        }
        return JSONValue.Obj(fields)
    }

    /** 回执状态读取（记账用；status 缺席返回 null）。 */
    fun statusOf(ack: JSONValue): String? = ack["status"]?.stringValue

    /** 上游六态词表（command.ts:437-443）；供冒烟测试断言（复用 protocol 权威词表）。 */
    val statusVocabulary: Set<String> get() = CommandSchemas.ackStatuses

    /** 真机 diag.wf.control.ui 实证 stale 回执的 revisionAtDecision（2026-10-07，AGENTS §6）。 */
    const val DEFAULT_STALE_REVISION_AT_DECISION = 12119
}

package cn.biuz.android.teststub

import cn.biuz.android.protocol.JSONValue

// v4 下行帧构造面（TopicWireFrame complete 信封 + snapshot/deltas 逻辑帧）。
// iOS 原文：E2ELoginStubServer 的 fireSessionsIndex / sendConversationSnapshot /
// fireConversationDelta / fireConversationStateDelta（E2ELoginStubServer.swift:2302-2547）。
//
// 【上游裁决 2026-10-09 /tmp/zcode-upstream】推帧形态三处对齐上游、纠正 iOS 替身偏差：
// ① wireVersion = V4_WIRE_PROTOCOL_VERSION = 3（core.ts:7 常量；真实编码器
//    wire-codec.ts:122/:189 恒写该值）——iOS 替身恒发 wireVersion:1
//    （E2ELoginStubServer.swift:2411 等）不在上游 schema（wire.ts:26 z.literal(3)）内；
// ② deliveryKind ∈ {"initial","online","recovery"}（transport.ts:21-22 枚举；快照发
//    "initial"、增量发 "online"）——iOS 替身缺该键；
// ③ conversation snapshot 至少携 protocolVersion:1 + sessionId + logEpoch + seq +
//    revision（snapshot.ts:470-499 冻结契约的骨架子集；完整 A 区 control/availability/
//    inputRouting/meta/config/usage/queue… 以 setConversationSnapshot 整体注水承接，
//    客户端宽容解析骨架子集无碍）。行形状贴上游 rows.ts：userInput 行必带 origin
//    （rows.ts:114-122）与 sourceCommandId（rows.ts:132）；行基座 rowId/turnId/
//    createdAt/createdAtSeq（rows.ts:12-19 rowBaseFields）。

/** 信封/逻辑帧共用的 wire 版本（V4_WIRE_PROTOCOL_VERSION，core.ts:7）。 */
const val STUB_V4_WIRE_VERSION = 3

/**
 * 便捷 JSON 对象构造：null 值键整体省略（与 protocol 的 JSONObjectBuilder.set 同口径；
 * 独立命名避免与 protocol 概念重名）。
 */
fun stubObj(vararg pairs: Pair<String, JSONValue?>): JSONValue {
    val fields = LinkedHashMap<String, JSONValue>()
    for ((key, value) in pairs) {
        if (value != null) fields[key] = value
    }
    return JSONValue.Obj(fields)
}

/** Long 值落 JSONValue（issuedAt/serverTime 等 epoch 毫秒：越 Int32 落整值 double，线缆为整型字面量）。 */
fun stubNum(value: Long): JSONValue = JSONValue.fromAny(value) ?: JSONValue.Null

/**
 * 每 topic 帧水位台账（seq 单调递增；快照重置归零）。
 * iOS 原文 _conversationSeqs/nextConversationSeq（E2ELoginStubServer.swift:2437-2446）：
 * 客户端防回放过滤器按「toSeq ≤ 水位 = 迟到帧」丢弃，替身必须单调推进；
 * 快照 = 冷启/重同步语义，重置后增量从 0→1 起步。
 */
class StubSeqLedger {
    private val seqs = LinkedHashMap<String, Int>()

    /** 取下一区间（from=当前值, to=当前值+1）。 */
    @Synchronized
    fun next(topic: String): Pair<Int, Int> {
        val from = seqs[topic] ?: 0
        val to = from + 1
        seqs[topic] = to
        return from to to
    }

    /** 快照水位重置（该 topic 增量从 0→1 重新起步）。 */
    @Synchronized
    fun reset(topic: String) {
        seqs[topic] = 0
    }
}

/** v4 下行帧构造（纯函数集）。 */
object StubV4Frames {

    // MARK: TopicWireFrame complete 信封

    /**
     * complete 物理信封（wire.ts:24-36）：wireVersion=3、deliveryKind 按投递档、
     * logicalFrameId 随机、ordinal 0。帧的 subscriptionId 必须与订阅回执一致
     * （客户端按「帧 subId == 回执 subId」过滤旧订阅迟到帧，v1.31 修复口径）。
     */
    fun topicEnvelope(
        topic: String,
        subscriptionId: String,
        deliveryKind: String,
        logicalFrame: JSONValue,
    ): JSONValue = stubObj(
        "wireVersion" to JSONValue.Int(STUB_V4_WIRE_VERSION),
        "kind" to JSONValue.Str("complete"),
        "deliveryKind" to JSONValue.Str(deliveryKind),
        "logicalFrameId" to JSONValue.Str("lf-" + java.util.UUID.randomUUID().toString()),
        "logicalFrameOrdinal" to JSONValue.Int(0),
        "topic" to JSONValue.Str(topic),
        "subscriptionId" to JSONValue.Str(subscriptionId),
        "frame" to logicalFrame,
    )

    // MARK: 逻辑帧

    /** snapshot 逻辑帧（fromSeq=toSeq=水位重置点 0；payload.kind=snapshot）。 */
    fun snapshotFrame(
        topic: String,
        subscriptionId: String,
        snapshot: JSONValue,
    ): JSONValue = stubObj(
        "topic" to JSONValue.Str(topic),
        "subscriptionId" to JSONValue.Str(subscriptionId),
        "fromSeq" to JSONValue.Int(0),
        "toSeq" to JSONValue.Int(0),
        "sentAt" to JSONValue.Str(java.time.Instant.now().toString()),
        "payload" to stubObj(
            "kind" to JSONValue.Str("snapshot"),
            "snapshot" to snapshot,
        ),
    )

    /** deltas 逻辑帧（payload.kind=deltas；[op] 为封闭集合，delta.ts:96-108）。 */
    fun deltasFrame(
        topic: String,
        subscriptionId: String,
        fromSeq: Int,
        toSeq: Int,
        deltas: List<JSONValue>,
    ): JSONValue = stubObj(
        "topic" to JSONValue.Str(topic),
        "subscriptionId" to JSONValue.Str(subscriptionId),
        "fromSeq" to JSONValue.Int(fromSeq),
        "toSeq" to JSONValue.Int(toSeq),
        "sentAt" to JSONValue.Str(java.time.Instant.now().toString()),
        "payload" to stubObj(
            "kind" to JSONValue.Str("deltas"),
            "deltas" to JSONValue.Arr(deltas),
        ),
    )

    /** row.appended 增量 op（delta.ts:96）。 */
    fun rowAppended(row: JSONValue): JSONValue = stubObj(
        "op" to JSONValue.Str("row.appended"),
        "row" to row,
    )

    /** state.updated 增量 op（delta.ts:108；patch 键级整体替换，A10）。 */
    fun stateUpdated(patch: Map<String, JSONValue>): JSONValue = stubObj(
        "op" to JSONValue.Str("state.updated"),
        "patch" to JSONValue.Obj(LinkedHashMap(patch)),
    )

    // MARK: 行构造（rows.ts 形状）

    /**
     * userInput 行（rows.ts:100-150）：origin=realUser + sourceCommandId=信封 commandId
     * ——上游真实形态（真实桌面用户行/助手行都推；用户行携 sourceCommandId 供客户端
     * 精确消费乐观回显，rows.ts:141）。iOS 替身曾只推助手行掩盖回显缺陷（test26 教训）。
     */
    fun userRow(
        rowId: Int,
        turnId: String,
        text: String,
        sourceCommandId: String,
        createdAtSeq: Int,
    ): JSONValue = stubObj(
        "rowId" to JSONValue.Int(rowId),
        "turnId" to JSONValue.Str(turnId),
        "createdAt" to stubNum(System.currentTimeMillis()),
        "createdAtSeq" to JSONValue.Int(createdAtSeq),
        "kind" to JSONValue.Str("userInput"),
        "text" to JSONValue.Str(text),
        "origin" to JSONValue.Str("realUser"),
        "sourceCommandId" to JSONValue.Str(sourceCommandId),
    )

    /** assistantText 行（rows.ts:155-167：state 五态词表此处 complete）。 */
    fun assistantRow(
        rowId: Int,
        turnId: String,
        text: String,
        createdAtSeq: Int,
    ): JSONValue = stubObj(
        "rowId" to JSONValue.Int(rowId),
        "turnId" to JSONValue.Str(turnId),
        "createdAt" to stubNum(System.currentTimeMillis()),
        "createdAtSeq" to JSONValue.Int(createdAtSeq),
        "kind" to JSONValue.Str("assistantText"),
        "text" to JSONValue.Str(text),
        "state" to JSONValue.Str("complete"),
    )

    // MARK: 骨架快照

    /**
     * conversation 快照骨架（snapshot.ts:470-499 的骨架子集 + rows/state）：revision/
     * logEpoch/seq 必填（上游 conversationSnapshotSchema；替身形态对齐——快照帧先于
     * 订阅回执到达时客户端的 revision/logEpoch 无从回填缺陷即由此暴露）。
     * 整体注水走 [E2EStubServer.setConversationSnapshot]（键级整体替换，A10）。
     */
    fun defaultConversationSnapshot(
        sessionId: String,
        rows: List<JSONValue>,
        revision: Int,
    ): JSONValue = stubObj(
        "protocolVersion" to JSONValue.Int(1),
        "sessionId" to JSONValue.Str(sessionId),
        "logEpoch" to JSONValue.Str(DEFAULT_LOG_EPOCH),
        "seq" to JSONValue.Int(0),
        "revision" to JSONValue.Int(revision),
        "rows" to JSONValue.Arr(rows),
        "state" to stubObj(
            "revision" to JSONValue.Int(revision),
            "pendingInteractions" to JSONValue.Arr(emptyList()),
        ),
    )

    /** 默认水位 epoch（快照/增量一致性：seq 单调于同一 epoch 内）。 */
    const val DEFAULT_LOG_EPOCH = "epoch-conv-1"

    /** 默认会话 revision（iOS 替身 42 同值，E2ELoginStubServer.swift:2460）。 */
    const val DEFAULT_REVISION = 42
}

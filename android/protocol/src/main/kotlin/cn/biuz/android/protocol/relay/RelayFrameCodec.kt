package cn.biuz.android.protocol.relay

import cn.biuz.android.protocol.JSONValue
import java.io.ByteArrayOutputStream
import java.util.Base64

// 直译自 ios/ZCodeMobileApp/Sources/Services/Relay/RelayFrameCodec.swift（246 行）。
// 包结构按 iOS Services/Relay 落 relay 子包（与 relay/RelayLink.kt 同包）。
//
// 权威性核实（2026-10-09 上游克隆件）：中继 rpc-frame 协议层不在上游开源仓
// （rg "ackMessageSeq|remote/v4|auth_init" 于 /tmp/zcode-upstream 全无命中——该层属
// 云中继服务端，不在开源桌面/web 包内），形状权威 = iOS 原文 + 协议接口文档 §3.3
// 【移植 bundle 逆向】。可核对的两项与上游逐字一致：
// - crc32 反射 0xEDB88320 hex8（上游 wire-binary.ts:15 crc32WireBytes 同参）；
// - 1MiB 物理帧上限 / 16MB 逻辑消息上限（上游 core.ts:65-66 PROTOCOL_V4_LIMITS
//   maxFrameBytes / logicalFrameAssemblyMaxBytes）。
//
// 机械适配（逐处注明）：
// - Foundation Data → ByteArray；subdata → copyOfRange；base64（标准带 padding，
//   协议接口文档 §3.3「dataBase64 带 padding 标准_base64」）→ java.util.Base64；
//   Foundation `Data(base64Encoded:)` 可失败构造 → Base64.getDecoder + 捕
//   IllegalArgumentException 返回 null（同 V4Wire.kt 口径）；
// - Swift `enum RelayFrameCodec`（caseless）→ object；`[[String: JSONValue]]` →
//   List<Map<String, JSONValue>>（LinkedHashMap，键序 = Swift 字面量书写序；schema
//   为 strict 键集校验、与键序无关）；
// - `struct RelayFrameAssembler`（mutating）→ class（持有方单实例持有，值语义不外泄，
//   同 V4Wire.kt TopicWireFrameAssembler 文件头口径）；Date → Long（epoch 毫秒，
//   Models.kt 文件头约定）；TimeInterval(秒) → 毫秒比较；
// - JSONValue.Int 案构造复用 RPCSerialization.kt（32 位值域口径见其 KDoc；seq/
//   messageSeq 等协议值远在值域内）。
// 上游 bundle 指位注释（src-D3H6NV7w.js …）逐字保留。

// MARK: - rpc-frame 编解码（src-D3H6NV7w.js Yv/ny/ry schema + hy/gy 分片 移植）

/**
 * 中继桥 rpc 帧层纯函数。schema（.strict，不得多带字段）：
 * rpc-frame      = {zcode_type, bridgeSessionId, bridgeGeneration?, recoveryId?, seq, messageSeq,
 *                   fragmentIndex, fragmentCount, messageBytes, checksum{algorithm,value}, dataBase64}
 * rpc-frame-ack  = {zcode_type, bridgeSessionId, bridgeGeneration?, recoveryId?, ackMessageSeq}
 * - seq          = 物理帧序号（每片 +1，firstPhysicalSeq 起）
 * - messageSeq   = 逻辑消息序号（每条 RPC 消息 +1）
 * - checksum     = crc32 反射 0xEDB88320 hex8（与 V4Wire.crc32 同参；探针两侧校验一致）
 * - dataBase64   = 标准 base64（带 padding；ty() 要求规范可重编码）（RelayFrameCodec.swift:1-30）
 */
object RelayFrameCodec {

    /**
     * 常量（bundle Yv @243672：maxPhysicalFrameBytes=Ju.maxFrameBytes=1MB）
     * 2026-10-08 回归审核轮 §11.2：1e6 → 1048576 对齐上游 core.ts maxFrameBytes
     * （1e6 与 1MiB 存在 48576 字节窗口差，落在窗口内的下行信封曾被静默丢弃）
     * （:15-18）。
     */
    const val maxPhysicalFrameBytes: Int = 1_048_576 // 整条 data JSON 上限

    /**
     * 逻辑消息上限 16MB（RelayFrameCodec.swift:19；上游 core.ts:66
     * logicalFrameAssemblyMaxBytes 同值）。局域网侧 ChannelClient.MAX_MESSAGE_BYTES
     * 内联的同值常量（iOS 引用本值，ChannelClient.swift:158）——本文件落位后连接层
     * 可改引至此。
     */
    const val maxMessageBytes: Int = 16 * 1024 * 1024

    const val maxFragments: Int = 64

    /** Swift TimeInterval（秒）（:21）；Kotlin 侧毫秒换算见 RelayFrameAssembler。 */
    const val assemblyTimeoutSeconds: Double = 30.0

    /** Swift `struct Identity: Equatable`（:23-27）。 */
    data class Identity(
        val bridgeSessionId: String,
        val bridgeGeneration: Int?,
        val recoveryId: String?,
    )

    // MARK: 发送侧：逻辑消息 → 物理帧数组

    /**
     * 把一条完整 RPC 载荷（serialize(header)+serialize(body)，无 13 字节头）编码为 rpc-frame 字典数组。
     * 分片大小二分自适应（hy）：先按 1 片试算，超 maxPhysicalFrameBytes 则二分缩小片长（≤maxFragments 片）
     * （:31-81）。
     */
    fun encodeMessage(
        bytes: ByteArray,
        identity: Identity,
        firstPhysicalSeq: Int,
        messageSeq: Int,
    ): List<Map<String, JSONValue>>? {
        if (bytes.isEmpty() || bytes.size > maxMessageBytes || firstPhysicalSeq < 1 || messageSeq < 1) {
            return null
        }
        val checksum: JSONValue = JSONValue.Obj(
            linkedMapOf(
                "algorithm" to JSONValue.Str("crc32"),
                "value" to JSONValue.Str(crc32Hex(bytes)),
            )
        )
        val messageBytes = bytes.size

        // 片长二分（hy 口径）：约束是「最大单片信封 ≤ maxPhysicalFrameBytes」+「片数 ≤ maxFragments」，
        // 两约束交集非空（16MB/64 片 → 每片 250KB → 信封 333KB < 1MB）
        var pieceBytes = bytes.size
        while (fragmentFrameCount(messageBytes = messageBytes, pieceBytes = pieceBytes) > maxFragments ||
            envelopeBytes(perPieceBytes = pieceBytes) > maxPhysicalFrameBytes
        ) {
            pieceBytes = maxOf(1, pieceBytes / 2)
            if (pieceBytes == 1) break
        }

        val frames = ArrayList<Map<String, JSONValue>>()
        val count = fragmentFrameCount(messageBytes = messageBytes, pieceBytes = pieceBytes)
        var physicalSeq = firstPhysicalSeq
        var offset = 0
        for (index in 0 until count) {
            val end = minOf(offset + pieceBytes, messageBytes)
            val chunk = bytes.copyOfRange(offset, end)
            offset = end
            val frame = LinkedHashMap<String, JSONValue>()
            frame["zcode_type"] = JSONValue.Str("rpc-frame")
            frame["bridgeSessionId"] = JSONValue.Str(identity.bridgeSessionId)
            frame["seq"] = JSONValue.Int(physicalSeq)
            frame["messageSeq"] = JSONValue.Int(messageSeq)
            frame["fragmentIndex"] = JSONValue.Int(index)
            frame["fragmentCount"] = JSONValue.Int(count)
            frame["messageBytes"] = JSONValue.Int(messageBytes)
            frame["checksum"] = checksum
            frame["dataBase64"] = JSONValue.Str(Base64.getEncoder().encodeToString(chunk))
            identity.bridgeGeneration?.let { frame["bridgeGeneration"] = JSONValue.Int(it) }
            identity.recoveryId?.let { frame["recoveryId"] = JSONValue.Str(it) }
            frames.add(frame)
            physicalSeq += 1
        }
        return frames
    }

    /** 单片信封字节估算（hy 的 py(i) 口径：该片 base64 4/3 膨胀 + JSON 键架固定开销）（:83-88）。 */
    fun envelopeBytes(perPieceBytes: Int): Int {
        val base64Len = 4 * ((perPieceBytes + 2) / 3)
        val frameOverhead = 220 // zcode_type/seq/messageSeq/fragment*/checksum/identity/引号标点
        return base64Len + frameOverhead
    }

    /** pieceBytes <= 0 时返回 Int.max（Swift `.max` 同口径）（:90-93）。 */
    fun fragmentFrameCount(messageBytes: Int, pieceBytes: Int): Int {
        if (pieceBytes <= 0) return Int.MAX_VALUE
        return (messageBytes + pieceBytes - 1) / pieceBytes
    }

    // MARK: 接收侧

    /** 解析下行帧：rpc-frame → (messageSeq, fragmentIndex, fragmentCount, messageBytes, crc, data)（:97-108）。 */
    class InboundFragment(
        val bridgeSessionId: String,
        val bridgeGeneration: Int?,
        val recoveryId: String?,
        val messageSeq: Int,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val messageBytes: Int,
        val crcValue: String,
        val data: ByteArray,
    )

    /** 全字段守卫任一不满足 → null（Swift `guard let … else { return nil }` 链同序）（:110-133）。 */
    fun decode(payload: JSONValue): InboundFragment? {
        val dict = payload.objectValue ?: return null
        if (dict["zcode_type"]?.stringValue != "rpc-frame") return null
        val bridgeSessionId = dict["bridgeSessionId"]?.stringValue ?: return null
        val messageSeq = dict["messageSeq"]?.intValue?.takeIf { it >= 1 } ?: return null
        val fragmentIndex = dict["fragmentIndex"]?.intValue?.takeIf { it >= 0 } ?: return null
        val fragmentCount = dict["fragmentCount"]?.intValue?.takeIf { it >= 1 } ?: return null
        if (fragmentIndex >= fragmentCount || fragmentCount > maxFragments) return null
        val messageBytes = dict["messageBytes"]?.intValue?.takeIf { it >= 1 } ?: return null
        if (messageBytes > maxMessageBytes) return null
        val crcValue = dict["checksum"]?.objectValue?.get("value")?.stringValue ?: return null
        val base64 = dict["dataBase64"]?.stringValue ?: return null
        val data = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return InboundFragment(
            bridgeSessionId = bridgeSessionId,
            bridgeGeneration = dict["bridgeGeneration"]?.intValue,
            recoveryId = dict["recoveryId"]?.stringValue,
            messageSeq = messageSeq,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            messageBytes = messageBytes,
            crcValue = crcValue,
            data = data)
    }

    /** rpc-frame-ack（对端对我上行帧的确认）（:135-141）。 */
    fun decodeAck(payload: JSONValue): Int? {
        val dict = payload.objectValue ?: return null
        if (dict["zcode_type"]?.stringValue != "rpc-frame-ack") return null
        return dict["ackMessageSeq"]?.intValue?.takeIf { it >= 1 }
    }

    /** 上行 ack 回执（对桌面下行帧的确认；processAck 语义：ackMessageSeq=收到的 messageSeq）（:143-157）。 */
    fun makeAck(identity: Identity, ackMessageSeq: Int): Map<String, JSONValue> {
        val frame = LinkedHashMap<String, JSONValue>()
        frame["zcode_type"] = JSONValue.Str("rpc-frame-ack")
        frame["bridgeSessionId"] = JSONValue.Str(identity.bridgeSessionId)
        frame["ackMessageSeq"] = JSONValue.Int(ackMessageSeq)
        identity.bridgeGeneration?.let { frame["bridgeGeneration"] = JSONValue.Int(it) }
        identity.recoveryId?.let { frame["recoveryId"] = JSONValue.Str(it) }
        return frame
    }

    // MARK: CRC32（反射 0xEDB88320，hex8；与 V4Wire.crc32 同参）

    /**
     * Swift 每次调用内建表（:161-175）；表为算法纯函数 → Kotlin 预计算一次
     * （输出逐字节相同）。与 V4Wire.kt TopicWireFrameAssembler.crc32 同参同值
     * （两份实现并存系 iOS 原文即各自持有，直译保留）。
     */
    fun crc32Hex(data: ByteArray): String {
        var crc = 0xFFFF_FFFF.toInt()
        for (byte in data) {
            crc = CRC_TABLE[(crc xor (byte.toInt() and 0xFF)) and 0xFF] xor (crc ushr 8)
        }
        crc = crc xor 0xFFFF_FFFF.toInt()
        return "%08x".format(crc)
    }

    private val CRC_TABLE: IntArray = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if ((c and 1) == 1) (0xEDB8_8320.toInt() xor (c ushr 1)) else (c ushr 1)
        }
        c
    }
}

// MARK: - 下行分片重组器（ole/_n 逻辑帧组装器移植：messageSeq 键控）

/**
 * 重组下行 rpc-frame 分片；重复帧（对端重传）幂等并仍返回应 ack 的 messageSeq
 * （RelayFrameAssembler.swift:178-246，struct mutating → class）。
 */
class RelayFrameAssembler {

    private class Reassembly {
        val fragments = LinkedHashMap<Int, ByteArray>()
        var received = 0
        /** Swift `Date()` → epoch 毫秒（Models.kt 文件头约定）。 */
        val startedAt: Long = System.currentTimeMillis()
    }

    private val pending: HashMap<Int, Reassembly> = HashMap() // messageSeq 键控
    private var highestCompletedMessageSeq = 0

    /** Swift `enum AcceptResult`（带关联值 → sealed class）（:191-200）。 */
    sealed class AcceptResult {
        /** 分片未齐（或重复的历史帧无需处理） */
        data class Incomplete(val ackMessageSeq: Int?) : AcceptResult()

        /** 重复帧：已重组完成过，仅需回 ack */
        data class Duplicate(val ackMessageSeq: Int) : AcceptResult()

        /** 重组完成（crc 校验通过） */
        data class Completed(val data: ByteArray, val messageSeq: Int) : AcceptResult()

        /** 校验失败等不可恢复 */
        data class Fault(val reason: String) : AcceptResult()
    }

    /** Swift 默认参数 `now: Date = Date()` → epoch 毫秒。 */
    fun accept(fragment: RelayFrameCodec.InboundFragment, now: Long = System.currentTimeMillis()): AcceptResult {
        // 历史帧（早已完成且被 ack）→ duplicate 语义回 ack 即可（:204-206）
        if (fragment.messageSeq <= highestCompletedMessageSeq) {
            return AcceptResult.Duplicate(ackMessageSeq = fragment.messageSeq)
        }
        if (fragment.fragmentIndex >= fragment.fragmentCount) {
            return AcceptResult.Fault("fragment-index-out-of-range")
        }
        var item = pending[fragment.messageSeq] ?: Reassembly()
        // 超时（assemblyTimeoutMs=30s）：弃置重来（:211-215）
        if (now - item.startedAt > RelayFrameCodec.assemblyTimeoutSeconds.toLongMilliseconds()) {
            pending.remove(fragment.messageSeq)
            item = Reassembly()
        }
        if (item.fragments[fragment.fragmentIndex] == null) {
            // 单片 crc 逐片校验不可行（协议校验整条消息），先缓存（:216-219）
            item.fragments[fragment.fragmentIndex] = fragment.data
            item.received += 1
        }
        pending[fragment.messageSeq] = item

        if (item.received != fragment.fragmentCount) {
            return AcceptResult.Incomplete(ackMessageSeq = fragment.messageSeq)
        }
        pending.remove(fragment.messageSeq)
        // Swift reserveCapacity(messageBytes) 为容量预配，无语义，Kotlin 省略（V4Wire 同口径）
        val logical = ByteArrayOutputStream()
        for (i in 0 until fragment.fragmentCount) {
            val chunk = item.fragments[i] ?: return AcceptResult.Fault("missing-fragment-$i")
            logical.write(chunk)
        }
        val bytes = logical.toByteArray()
        if (bytes.size != fragment.messageBytes) {
            return AcceptResult.Fault("message-bytes-mismatch")
        }
        if (RelayFrameCodec.crc32Hex(bytes) != fragment.crcValue.lowercase()) {
            return AcceptResult.Fault("crc32-mismatch")
        }
        highestCompletedMessageSeq = maxOf(highestCompletedMessageSeq, fragment.messageSeq)
        return AcceptResult.Completed(bytes, messageSeq = fragment.messageSeq)
    }

    /** 重置在途装配（Swift 原文不清 highestCompletedMessageSeq，同口径保留）（:243-245）。 */
    fun reset() {
        pending.clear()
    }

    /** TimeInterval（秒）→ 毫秒（assemblyTimeoutSeconds=30.0 → 30_000ms，整值无损）。 */
    private fun Double.toLongMilliseconds(): Long = (this * 1000.0).toLong()
}

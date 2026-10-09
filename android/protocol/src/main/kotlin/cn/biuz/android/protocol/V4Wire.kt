package cn.biuz.android.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64

// 直译自 ios/ZCodeMobileApp/Sources/Services/RPC/V4Wire.swift（225 行）。
// 上游：packages/shared/src/zcode-protocol-v4/wire.ts / wire-assembler.ts。
// 逻辑与 Swift 原文逐行对应；唯一机械适配：Swift struct 值语义的 Assembler → Kotlin class
// 引用语义（持有方单实例持有时等价），Date → System.currentTimeMillis()。
// 注：Swift 的 JSONDecoder 由 RPCSerialization.kt 的 JsonCodec（JSONValue.parse）承接。

// MARK: - TopicWireFrame + 分片重组（packages/shared/src/zcode-protocol-v4/wire.ts / wire-assembler.ts 移植）

/** 物理帧信封：complete 直携 logical 帧；fragment 需经 assembler 重组（wire.ts:16-58）。 */
object TopicWireFrame {

    data class Envelope(
        var wireVersion: Int,
        var deliveryKind: String,
        var logicalFrameId: String,
        var logicalFrameOrdinal: Int,
        var topic: String,
        var subscriptionId: String,
        var completeFrame: JSONValue?,
        // fragment 专用
        var fragmentIndex: Int?,
        var fragmentCount: Int?,
        var logicalBytes: Int?,
        var checksum: String?,
        var dataBase64: String?,
    )

    /** 解析下行 dynamic 事件载荷；失败返回 null（service 边界宽容）（:24-49）。 */
    fun parse(json: JSONValue): Envelope? {
        val dict = json.objectValue ?: return null
        val wireVersion = dict["wireVersion"]?.intValue ?: return null
        val kind = dict["kind"]?.stringValue ?: return null
        val envelope = Envelope(
            wireVersion = wireVersion,
            deliveryKind = dict["deliveryKind"]?.stringValue ?: "",
            logicalFrameId = dict["logicalFrameId"]?.stringValue ?: "",
            logicalFrameOrdinal = dict["logicalFrameOrdinal"]?.intValue ?: 0,
            topic = dict["topic"]?.stringValue ?: "",
            subscriptionId = dict["subscriptionId"]?.stringValue ?: "",
            completeFrame = null,
            fragmentIndex = null, fragmentCount = null,
            logicalBytes = null, checksum = null, dataBase64 = null)
        when (kind) {
            "complete" -> envelope.completeFrame = dict["frame"]
            "fragment" -> {
                envelope.fragmentIndex = dict["fragmentIndex"]?.intValue
                envelope.fragmentCount = dict["fragmentCount"]?.intValue
                envelope.logicalBytes = dict["logicalBytes"]?.intValue
                envelope.checksum = dict["checksum"]?.objectValue?.get("value")?.stringValue
                envelope.dataBase64 = dict["dataBase64"]?.stringValue
            }
            else -> return null
        }
        return envelope
    }
}

/**
 * fragment → complete 重组器（logicalFrameId 键控；上限 1024 分片/16MB，core.ts:71-77）（:57-189）。
 *
 * dropped 标记（v4 断流自愈）：校验失败/解码失败/超限丢弃整条逻辑帧时置位，
 * 由订阅方（ZCodeServerConnection.routeFrame）取走并触发 resync——这是「静默断流」
 * （丢帧后永远等不到下一帧）唯一的客户端可观测信号。等待更多分片的正常返回不置位。
 */
class TopicWireFrameAssembler {

    private class Pending {
        val fragments = LinkedHashMap<Int, ByteArray>()
        var count = 0
        val firstSeen: Long = System.currentTimeMillis()
    }

    private val pending = HashMap<String, Pending>()

    /** 自上次取走以来是否丢弃过整条逻辑帧（routeFrame 消费后触发 resync）。 */
    var droppedSinceLastConsume: Boolean = false
        private set

    /** 喂入一个物理帧；complete 直接过，fragment 凑齐后输出 logical 帧 JSON（:78-143）。 */
    fun accept(envelope: TopicWireFrame.Envelope): JSONValue? {
        envelope.completeFrame?.let { return it }
        expireStaleAssemblies(now = System.currentTimeMillis())
        val index = envelope.fragmentIndex
            ?: return droppedAndNull() // 信封残缺：整条逻辑帧不可恢复
        val count = envelope.fragmentCount ?: return droppedAndNull()
        val base64 = envelope.dataBase64 ?: return droppedAndNull()
        val data = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return droppedAndNull()
        }
        if (index >= count) return droppedAndNull()
        val item = pending[envelope.logicalFrameId] ?: run {
            val fresh = Pending()
            // 并发装配预算：超限丢最旧（上游 hardBound 超限产 typed fault 同效果）
            if (pending.size >= MAX_CONCURRENT_ASSEMBLIES) {
                val oldest = pending.entries.minByOrNull { it.value.firstSeen }?.key
                if (oldest != null) {
                    pending.remove(oldest)
                    droppedSinceLastConsume = true
                }
            }
            pending[envelope.logicalFrameId] = fresh
            fresh
        }

        if (item.fragments[index] == null) {
            item.fragments[index] = data
            item.count += 1
        }
        if (item.count != count) {
            if (item.fragments.size > MAX_FRAGMENTS) {
                pending.remove(envelope.logicalFrameId)
                droppedSinceLastConsume = true // 超分片上限：弃置并标记
            }
            return null
        }
        pending.remove(envelope.logicalFrameId)
        // Swift reserveCapacity(logicalBytes ?? 0) 为容量预配，无语义，Kotlin 省略
        val logical = ByteArrayOutputStream()
        for (i in 0 until count) {
            val chunk = item.fragments[i] ?: return droppedAndNull()
            logical.write(chunk)
        }
        val bytes = logical.toByteArray()
        if (bytes.size > MAX_LOGICAL_BYTES) {
            return droppedAndNull()
        }
        // 可选校验 crc32（checksum 存在时；失配丢弃整条逻辑帧）
        val expected = envelope.checksum?.lowercase()
        if (!expected.isNullOrEmpty()) {
            if (crc32(bytes) != expected) {
                return droppedAndNull()
            }
        }
        val decoded = try {
            JSONValue.parse(bytes)
        } catch (e: JsonParseException) {
            return droppedAndNull() // 逻辑帧解码失败：下游永远收不到该帧
        }
        return decoded
    }

    /**
     * 主题过滤后丢弃（订阅更替时清理积压）。主动丢弃同样置位：订阅方随后的
     * resync 一次幂等无害，而漏标会在换代场景留下静默断流（:147-150）。
     */
    fun dropAll() {
        if (pending.isNotEmpty()) droppedSinceLastConsume = true
        pending.clear()
    }

    /**
     * 超时驱逐：分片停留超 30s 的半装配条目（上游 expire 同语义——丢分片的自愈
     * 信号，不是错误）。每次 fragment 喂入前顺带扫描（低频路径，条目 ≤32）（:154-163）。
     */
    private fun expireStaleAssemblies(now: Long) {
        val stale = pending.filterValues {
            now - it.firstSeen > ASSEMBLY_TIMEOUT_MS
        }.keys
        if (stale.isEmpty()) return
        for (key in stale) {
            pending.remove(key)
        }
        droppedSinceLastConsume = true
    }

    /** 取走 dropped 标记（读后清零；每次 routeFrame 轮询调用）（:166-170）。 */
    fun consumeDroppedFlag(): Boolean {
        val value = droppedSinceLastConsume
        droppedSinceLastConsume = false
        return value
    }

    /** 信封残缺/校验失败统一出口：置 dropped 并返回 null。 */
    private fun droppedAndNull(): JSONValue? {
        droppedSinceLastConsume = true
        return null
    }

    companion object {
        private const val MAX_FRAGMENTS = 1024
        private const val MAX_LOGICAL_BYTES = 16 * 1024 * 1024

        /**
         * 半装配驱逐（§11.3 对齐上游 wire-assembler.ts:472-481 expire + core.ts:68-69）：
         * ① 30s 装配超时——任一分片丢失后 Pending 永驻（单条最大近 16MB），长会话
         * 内存只增不减；过期驱逐并置 dropped（下游 resync 自愈在）。
         * ② 并发装配上限 32——超限丢最旧（上游 maxConcurrentAssemblies=32）（:71-72）。
         */
        private const val ASSEMBLY_TIMEOUT_MS = 30_000L
        private const val MAX_CONCURRENT_ASSEMBLIES = 32

        /** IEEE 802.3 多项式表（与 zod schema algorithm:"crc32" 对应；纯函数，预计算）。 */
        private val CRC_TABLE: IntArray = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if ((c and 1) == 1) (0xEDB8_8320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            c
        }

        /** CRC32（IEEE 802.3 多项式，与 zod schema algorithm:"crc32" 对应）（:174-188）。 */
        fun crc32(data: ByteArray): String {
            var crc = 0xFFFF_FFFF.toInt()
            for (byte in data) {
                crc = CRC_TABLE[(crc xor (byte.toInt() and 0xFF)) and 0xFF] xor (crc ushr 8)
            }
            crc = crc xor 0xFFFF_FFFF.toInt()
            return "%08x".format(crc)
        }
    }
}

// MARK: - 逻辑帧（transport.ts:162-186 createTopicFrameSchema）

/**
 * 重组后的逻辑帧：payload = snapshot | deltas（:194-225）。
 */
data class V4TopicFrame(
    var topic: String,
    var subscriptionId: String,
    var fromSeq: Int,
    var toSeq: Int,
    var sentAt: String?,
    var snapshot: JSONValue?,
    var deltas: List<JSONValue>,
) {
    companion object {
        fun parse(json: JSONValue): V4TopicFrame? {
            val dict = json.objectValue ?: return null
            val topic = dict["topic"]?.stringValue ?: return null
            val frame = V4TopicFrame(
                topic = topic,
                subscriptionId = dict["subscriptionId"]?.stringValue ?: "",
                fromSeq = dict["fromSeq"]?.intValue ?: 0,
                toSeq = dict["toSeq"]?.intValue ?: 0,
                sentAt = dict["sentAt"]?.stringValue,
                snapshot = null,
                deltas = emptyList())
            val payload = dict["payload"]?.objectValue
            if (payload != null) {
                when (payload["kind"]?.stringValue) {
                    "snapshot" -> frame.snapshot = payload["snapshot"]
                    "deltas" -> frame.deltas = payload["deltas"]?.arrayValue ?: emptyList()
                    else -> {}
                }
            }
            return frame
        }
    }
}

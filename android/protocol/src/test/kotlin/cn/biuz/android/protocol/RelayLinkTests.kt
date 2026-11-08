package cn.biuz.android.protocol

import cn.biuz.android.protocol.relay.RelayAuth
import cn.biuz.android.protocol.relay.RelayFrameAssembler
import cn.biuz.android.protocol.relay.RelayFrameCodec
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 云中继接入单测：链接解析 / 鉴权 proof / rpc-frame 编解码与分片 / 配置向后兼容。
 * 关键向量全部来自主会话实地探针（/tmp/relay-js/ws_probe.py、ws_bridge_probe.py 实测）。
 *
 * 直译自 ios/UnitTests/RelayLinkTests.swift（18 用例 → 18 个 @Test；其中 2 例
 * ——relayLinkArgument / ServerConfig 旧 JSON 解码——的连接层类型已随 :connection
 * 落位，2026-10-09 按 iOS 原文断言迁至 :connection 测试源集
 * RelayLinkConnectionLayerTests（:protocol 测试不可反向依赖 :connection——工程依赖环），
 * 原 @Disabled 占位同步删除）。
 * 机械适配（逐处注明）：
 * - XCTest 断言 → kotlin.test；`guard case`/`if case` 模式 → `is` 判型 + 属性读取；
 * - Foundation Data → ByteArray（contentEquals 逐字节比较）；JSONEncoder → JSONValue.data；
 * - Date → Long（epoch 毫秒，Models.kt 文件头约定）；
 * - iOS 测试私有扩展 encodeMessage(forcedPieceBytes:) → 本文件顶层私有
 *   [encodeMessageForcedPieceBytes]（fragmentFrameCount/crc32Hex 复用生产 API，不重造）。
 */
class RelayLinkTests {

    // MARK: 配对链接解析（ConnectURLParser.parseRelayLink）

    /** 真实配对链接形态（交接材料 §1，mid/name/app_version 语义保留） */
    private val sampleLink = "https://zcode.z.ai/remote/v4?sid=d_5BHs7zpSQADkcFSNhfud5v" +
        "&hash=juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4%3D&t=1791033721496" +
        "&mid=4499673e-d0f8-49ef-ada8-623c18767c24&name=MacBook-Pro-8.local&app_version=3.14.4"

    @Test
    fun testParseRelayLinkFromPairingURL() {
        val link = ConnectURLParser.parseRelayLink(sampleLink)
        assertNotNull(link)
        assertEquals("wss://zcode.z.ai/ws?mid=4499673e-d0f8-49ef-ada8-623c18767c24", link.wssURL)
        assertEquals("MacBook-Pro-8.local", link.machineName)
        assertEquals("d_5BHs7zpSQADkcFSNhfud5v", link.deviceSid)
        // hash：%3D 解码为 =（保留 base64 原字符串，不做 base64 解码）
        assertEquals("juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4=", link.passHash)
        assertEquals("3.14.4", link.desktopAppVersion)
    }

    @Test
    fun testParseRelayLinkWithoutMid() {
        val link = ConnectURLParser.parseRelayLink("https://zcode.z.ai/remote/v4?sid=s1&hash=h1")
        assertNotNull(link)
        assertEquals("wss://zcode.z.ai/ws", link.wssURL)
    }

    @Test
    fun testParseRelayLinkRejectsNonRelayInputs() {
        assertNull(ConnectURLParser.parseRelayLink("http://zcode.z.ai/remote/v4?sid=s&hash=h")) // 非 https
        assertNull(ConnectURLParser.parseRelayLink("https://zcode.z.ai/other?sid=s&hash=h")) // 非 /remote/
        // 缺 hash 不再是「非法」：3.16.2 起无 hash 的链接 = Relay V2（JWT 换一次性 token）
        val v2 = ConnectURLParser.parseRelayLink("https://zcode.z.ai/remote/v4?sid=s")
        assertEquals(true, v2?.isV2, "缺 hash 应判 V2 而非拒绝")
        assertNull(ConnectURLParser.parseRelayLink("https://zcode.z.ai/remote/v4?hash=h")) // 缺 sid
        assertNull(ConnectURLParser.parseRelayLink(""))
    }

    @Test
    fun testDirectTokenLinkNotMisreadAsRelay() {
        val link = ConnectURLParser.extractLink("http://192.168.1.24:3030/?token=abc")
        val parsed = link as? ConnectURLParser.ParsedLink.Direct
        if (parsed == null) {
            fail("局域网链接应识别为 direct")
        }
        assertEquals("192.168.1.24", parsed.parsed.host)
        assertEquals("abc", parsed.parsed.token)
    }

    @Test
    fun testExtractLinkPrefersRelay() {
        val link = ConnectURLParser.extractLink("看看这个 $sampleLink")
        val relay = link as? ConnectURLParser.ParsedLink.Relay
        if (relay == null) {
            fail("配对链接应识别为 relay")
        }
        assertEquals("d_5BHs7zpSQADkcFSNhfud5v", relay.link.deviceSid)
    }

    // MARK: 鉴权 proof（HMAC-SHA256 + base64url 无填充）

    /** 探针实测向量：ws_probe.py 以该 proof 走通 auth_ack{pair_status:'matched'} */
    @Test
    fun testProofMatchesProbeVector() {
        val proof = RelayAuth.proof(
            passHash = "juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4=",
            nonce = "lFr2Po5uGyuNti2Xuw7LfcrN",
            role = "terminal",
            deviceSid = "d_5BHs7zpSQADkcFSNhfud5v",
        )
        assertEquals("TVEonHQISlbmNucXTEYrnfL0D3v00eO075w66jFq8Es", proof)
        assertFalse(proof.contains('='), "base64url 无填充")
        assertFalse(proof.contains('+') || proof.contains('/'), "base64url 字母表")
    }

    // MARK: rpc-frame 编解码（schema strict 键集 + 分片）

    private val identity = RelayFrameCodec.Identity(
        bridgeSessionId = "abc123", bridgeGeneration = 2, recoveryId = null)

    @Test
    fun testEncodeSingleFragmentFrameFieldSet() {
        val bytes = byteArrayOf(0x04, 0x01, 0x06, 0xc8.toByte(), 0x01, 0x00) // 探针实测下行 Initialize 形态
        val frames = RelayFrameCodec.encodeMessage(bytes, identity = identity,
            firstPhysicalSeq = 1, messageSeq = 7) ?: return fail("编码失败")
        assertEquals(1, frames.size)
        val frame = frames[0]
        // strict schema 键集：不得多带字段（bundle ny.superRefine + .strict）
        assertEquals(setOf(
            "zcode_type", "bridgeSessionId", "bridgeGeneration", "seq", "messageSeq",
            "fragmentIndex", "fragmentCount", "messageBytes", "checksum", "dataBase64",
        ), frame.keys)
        assertEquals("rpc-frame", frame["zcode_type"]?.stringValue)
        assertEquals(1, frame["seq"]?.intValue)
        assertEquals(7, frame["messageSeq"]?.intValue)
        assertEquals(0, frame["fragmentIndex"]?.intValue)
        assertEquals(1, frame["fragmentCount"]?.intValue)
        assertEquals(bytes.size, frame["messageBytes"]?.intValue)
        assertEquals(Base64.getEncoder().encodeToString(bytes), frame["dataBase64"]?.stringValue)
        assertEquals("crc32", frame["checksum"]?.objectValue?.get("algorithm")?.stringValue)
        assertEquals(
            RelayFrameCodec.crc32Hex(bytes),
            frame["checksum"]?.objectValue?.get("value")?.stringValue)
    }

    @Test
    fun testEncodeOmitsOptionalIdentityFields() {
        val bare = RelayFrameCodec.Identity(
            bridgeSessionId = "abc", bridgeGeneration = null, recoveryId = null)
        val frames = RelayFrameCodec.encodeMessage(byteArrayOf(0x00), identity = bare,
            firstPhysicalSeq = 1, messageSeq = 1)
        assertNull(frames?.first()?.get("bridgeGeneration"))
        assertNull(frames?.first()?.get("recoveryId"))
    }

    @Test
    fun testPhysicalSeqIncrementsAcrossFragments() {
        // 8KB 消息 + 强制小片：seq 逐片递增，messageSeq 恒定
        val bytes = ByteArray(8192) { (it % 251).toByte() }
        val frames = encodeMessageForcedPieceBytes(bytes, identity = identity,
            firstPhysicalSeq = 5, messageSeq = 3, forcedPieceBytes = 1024)
        assertEquals(8, frames?.size)
        assertEquals(listOf(5, 6, 7, 8, 9, 10, 11, 12), frames!!.map { it["seq"]?.intValue ?: -1 })
        assertTrue(frames.all { it["messageSeq"]?.intValue == 3 })
        assertEquals(0, frames.first()["fragmentIndex"]?.intValue)
        assertEquals(7, frames.last()["fragmentIndex"]?.intValue)
    }

    @Test
    fun testFragmentCountCappedAtMaxFragments() {
        val bytes = ByteArray(2_000_000) { 0x41.toByte() }
        val frames = RelayFrameCodec.encodeMessage(bytes, identity = identity,
            firstPhysicalSeq = 1, messageSeq = 1) ?: return fail("编码失败")
        assertTrue(frames.size <= RelayFrameCodec.maxFragments)
        // 二分确保每片整帧 ≤ maxPhysicalFrameBytes
        val maxEnvelope = frames.maxOfOrNull { frame ->
            JSONValue.Obj(frame).data?.size ?: Int.MAX_VALUE
        } ?: Int.MAX_VALUE
        assertTrue(maxEnvelope <= RelayFrameCodec.maxPhysicalFrameBytes)
    }

    @Test
    fun testDecodeRoundTripAndAck() {
        val bytes = "hello-bridge".toByteArray(Charsets.UTF_8)
        val frame = RelayFrameCodec.encodeMessage(bytes, identity = identity,
            firstPhysicalSeq = 1, messageSeq = 4)!!.first()
        val fragment = RelayFrameCodec.decode(JSONValue.Obj(frame))
        assertEquals(4, fragment?.messageSeq)
        assertEquals(1, fragment?.fragmentCount)
        assertTrue(fragment?.data?.contentEquals(bytes) == true)
        assertEquals("abc123", fragment?.bridgeSessionId)

        // 非 rpc-frame / 缺必需字段（fragmentIndex）→ null
        assertNull(RelayFrameCodec.decode(JSONValue.Obj(mapOf("zcode_type" to JSONValue.Str("rpc-frame-ack")))))
        assertNull(
            RelayFrameCodec.decode(
                JSONValue.Obj(
                    linkedMapOf(
                        "zcode_type" to JSONValue.Str("rpc-frame"),
                        "bridgeSessionId" to JSONValue.Str("x"),
                        "messageSeq" to JSONValue.Int(1),
                        "fragmentCount" to JSONValue.Int(1),
                        "messageBytes" to JSONValue.Int(1),
                        "checksum" to JSONValue.Obj(
                            linkedMapOf(
                                "algorithm" to JSONValue.Str("crc32"),
                                "value" to JSONValue.Str("00000000"),
                            )
                        ),
                        "dataBase64" to JSONValue.Str(Base64.getEncoder().encodeToString(byteArrayOf(0x00))),
                    )
                )
            )
        )

        val ack = RelayFrameCodec.makeAck(identity = identity, ackMessageSeq = 9)
        // identity 无 recoveryId → optional 字段省略（strict schema 可选键不出现）
        assertEquals(setOf("zcode_type", "bridgeSessionId", "bridgeGeneration", "ackMessageSeq"), ack.keys)
        assertEquals(9, RelayFrameCodec.decodeAck(JSONValue.Obj(ack)))
        // 带 recoveryId 的身份 → ack 帧携带（Lzn 三元组匹配）
        val ackWithRecovery = RelayFrameCodec.makeAck(
            identity = RelayFrameCodec.Identity(
                bridgeSessionId = "abc123", bridgeGeneration = 2, recoveryId = "rec1"),
            ackMessageSeq = 9)
        assertEquals("rec1", ackWithRecovery["recoveryId"]?.stringValue)
    }

    // MARK: 下行分片重组（重复帧幂等 / crc 校验 / 乱序凑齐）

    @Test
    fun testAssemblerOutOfOrderFragmentsAndDuplicate() {
        val assembler = RelayFrameAssembler()
        val bytes = ByteArray(2048) { (it % 253).toByte() }
        val frames = encodeMessageForcedPieceBytes(bytes, identity = identity,
            firstPhysicalSeq = 1, messageSeq = 2, forcedPieceBytes = 1024)
        assertEquals(2, frames?.size)
        val fragments = frames!!.mapNotNull { RelayFrameCodec.decode(JSONValue.Obj(it)) }

        // 先到 index=1
        val incomplete = assembler.accept(fragments[1]) as? RelayFrameAssembler.AcceptResult.Incomplete
        if (incomplete == null) {
            fail("分片未齐应返回 incomplete")
        }
        assertEquals(2, incomplete.ackMessageSeq, "未凑齐也需回 ack（防桌面端重传）")
        // 再到 index=0 → 凑齐 + crc 校验通过
        val completed = assembler.accept(fragments[0]) as? RelayFrameAssembler.AcceptResult.Completed
        if (completed == null) {
            fail("凑齐应返回 completed")
        }
        assertTrue(completed.data.contentEquals(bytes))
        assertEquals(2, completed.messageSeq)
        // 桌面端重传（未收到 ack）→ duplicate 幂等且仍回 ack
        val duplicate = assembler.accept(fragments[1]) as? RelayFrameAssembler.AcceptResult.Duplicate
        if (duplicate == null) {
            fail("重复帧应返回 duplicate")
        }
        assertEquals(2, duplicate.ackMessageSeq)
    }

    @Test
    fun testAssemblerRejectsCrcMismatch() {
        val assembler = RelayFrameAssembler()
        // Kotlin InboundFragment 字段为 val（iOS var fragment.crcValue 原地改写 → 重造实例，语义同构）
        var fragment = RelayFrameCodec.InboundFragment(
            bridgeSessionId = "abc123", bridgeGeneration = null, recoveryId = null,
            messageSeq = 1, fragmentIndex = 0, fragmentCount = 1,
            messageBytes = 3, crcValue = "deadbeef", data = byteArrayOf(1, 2, 3))
        assertTrue(assembler.accept(fragment) is RelayFrameAssembler.AcceptResult.Fault,
            "crc 失配应返回 fault") // crc 失配 → fault
        fragment = RelayFrameCodec.InboundFragment(
            bridgeSessionId = "abc123", bridgeGeneration = null, recoveryId = null,
            messageSeq = 1, fragmentIndex = 0, fragmentCount = 1,
            messageBytes = 3,
            crcValue = RelayFrameCodec.crc32Hex(byteArrayOf(1, 2, 3)),
            data = byteArrayOf(1, 2, 3))
        val completed = assembler.accept(fragment) as? RelayFrameAssembler.AcceptResult.Completed
        if (completed == null) {
            fail("crc 匹配应完成")
        }
        assertTrue(completed.data.contentEquals(byteArrayOf(1, 2, 3)))
    }

    // MARK: crc32 与 V4Wire 同参 + 标准向量

    @Test
    fun testCrc32MatchesV4WireAndStandardVector() {
        val vector = "123456789".toByteArray(Charsets.UTF_8)
        assertEquals("cbf43926", RelayFrameCodec.crc32Hex(vector))
        assertEquals(RelayFrameCodec.crc32Hex(vector), TopicWireFrameAssembler.crc32(vector))
    }

    // MARK: sessions-index 快照时间双形态（真实桌面毫秒时间戳 / 替身 ISO8601）

    @Test
    fun testSessionSummaryParsesMillisLastActivityAt() {
        // 中继快照实测形态：lastActivityAt=1791040344222（毫秒）
        // iOS 原文 .int(1_791_040_344_222)（Swift Int 64 位）；Kotlin JSONValue.Int 为 32 位
        // 值域（RPCSerialization.kt 已声明偏差），毫秒经 fromAny(Long) 越界落 .double 承载，
        // 读取走 doubleValue（<2^53 精确无损）。
        val json = JSONValue.Obj(
            linkedMapOf(
                "sessionId" to JSONValue.Str("sess_demo"),
                "title" to JSONValue.Str("中继会话"),
                "phase" to JSONValue.Str("completedSuccess"),
                "lastActivityAt" to JSONValue.fromAny(1_791_040_344_222L)!!,
            )
        )
        val summary = makeSessionSummary(json)
        assertNotNull(summary)
        // iOS 原文：XCTAssertEqual(Int(date.timeIntervalSince1970 * 1000), 1_791_040_344_222)
        assertEquals(1_791_040_344_222L, summary)
    }

    @Test
    fun testSessionSummaryParsesISOLastActivityAt() {
        val json = JSONValue.Obj(
            linkedMapOf(
                "sessionId" to JSONValue.Str("sess_demo"),
                "lastActivityAt" to JSONValue.Str("2026-10-03T15:12:24.222Z"),
            )
        )
        assertNotNull(makeSessionSummary(json))
    }

    /**
     * iOS 原文 makeSessionSummary 的 Kotlin 对应物（镜像语义最小校验）：
     * SessionSummary.parse 是 private 扩展；经 store 的公共投影验证不可行
     * （iOS actor 私有 / Kotlin store 模块未移植），这里用镜像语义的最小校验：
     * 字段进入 sessions 的路径由集成烟测覆盖，单测锚定 JSONValue 的毫秒/ISO 读取行为。
     * 返回值 = SessionSummary.lastActivityAt 的 epoch 毫秒（Date → Long，Models.kt 约定）。
     *
     * 毫秒读取走 doubleValue 而非 iOS 的 intValue：JSONValue.Int 为 32 位值域（帧层已声明
     * 偏差），毫秒级时间戳经 .double 承载；intValue 对该 integral double 会返回 32 位截断值
     * 而非 null（宽容读取口径）——未来 SessionSummary.parse 移植时毫秒必须走 doubleValue。
     */
    private fun makeSessionSummary(json: JSONValue): Long? {
        val dict = json.objectValue ?: return null
        dict["sessionId"]?.stringValue ?: return null // iOS：guard let sessionId（构造 SessionSummary 必需）
        val ms = dict["lastActivityAt"]?.doubleValue?.toLong()
        val iso = dict["lastActivityAt"]?.stringValue
        var dateMillis: Long? = null
        if (ms != null && ms > 0) {
            dateMillis = ms
        } else if (iso != null) {
            // iOS 两级 ISO8601DateFormatter（带小数秒 → 回退默认）；java.time.Instant.parse
            // 单调用覆盖同语义（ISO_INSTANT 支持可选小数秒，解析失败抛 → null 口径）
            dateMillis = try {
                Instant.parse(iso).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
        return dateMillis ?: return null
        // SessionSummary(sessionId:sessionId, title:"t", phase:"completedSuccess",
        //                lastActivityAt:date, lastAssistantPreview:nil)
        // ——类型未移植（store 模块缺位），构造断言不可平移；毫秒/ISO 落位语义已由上方锚定。
    }

    // MARK: -ZCodeRelayLink 启动参数 + ServerConfig JSON 编解码
    // 两例已随 :connection 落位迁往 connection 模块 RelayLinkConnectionLayerTests
    // （2026-10-09 回补，原文断言恢复；迁移理由见本文件头注记）。
}

// MARK: - 测试辅助：强制片长（走生产二分之外的确定性分片路径）

/**
 * iOS 测试文件私有扩展 `RelayFrameCodec.encodeMessage(_:identity:firstPhysicalSeq:messageSeq:
 * forcedPieceBytes:)` 的直译（RelayLinkTests.swift:310-347）：帧构造与生产 encodeMessage
 * 逐字段同形（checksum/dataBase64 复用生产 API），仅片长改测试指定值，不走生产二分。
 */
private fun encodeMessageForcedPieceBytes(
    bytes: ByteArray,
    identity: RelayFrameCodec.Identity,
    firstPhysicalSeq: Int,
    messageSeq: Int,
    forcedPieceBytes: Int,
): List<Map<String, JSONValue>>? {
    if (forcedPieceBytes <= 0) return null
    val count = RelayFrameCodec.fragmentFrameCount(
        messageBytes = bytes.size, pieceBytes = forcedPieceBytes)
    if (count > RelayFrameCodec.maxFragments) return null
    val checksum: JSONValue = JSONValue.Obj(
        linkedMapOf(
            "algorithm" to JSONValue.Str("crc32"),
            "value" to JSONValue.Str(RelayFrameCodec.crc32Hex(bytes)),
        )
    )
    val frames = ArrayList<Map<String, JSONValue>>()
    var offset = 0
    for (index in 0 until count) {
        val end = minOf(offset + forcedPieceBytes, bytes.size)
        val chunk = bytes.copyOfRange(offset, end)
        offset = end
        val frame = linkedMapOf<String, JSONValue>(
            "zcode_type" to JSONValue.Str("rpc-frame"),
            "bridgeSessionId" to JSONValue.Str(identity.bridgeSessionId),
            "seq" to JSONValue.Int(firstPhysicalSeq + index),
            "messageSeq" to JSONValue.Int(messageSeq),
            "fragmentIndex" to JSONValue.Int(index),
            "fragmentCount" to JSONValue.Int(count),
            "messageBytes" to JSONValue.Int(bytes.size),
            "checksum" to checksum,
            "dataBase64" to JSONValue.Str(Base64.getEncoder().encodeToString(chunk)),
        )
        identity.bridgeGeneration?.let { frame["bridgeGeneration"] = JSONValue.Int(it) }
        identity.recoveryId?.let { frame["recoveryId"] = JSONValue.Str(it) }
        frames.add(frame)
    }
    return frames
}

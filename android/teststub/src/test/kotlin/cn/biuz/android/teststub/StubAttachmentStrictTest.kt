package cn.biuz.android.teststub

// 附件四方法替身对拍测试（2026-10-10 附件链路轮；真实本地回环 socket）：
// ChannelClient ↔ Ktor E2EStubServer，strict 键面对拍（含零字节 superRefine/超限/
// 坏 padding/connectionId 拒收——A-4/A11 红线的服务端面）+ 上传事务账本行为面
// （staging 续传起点 / committed 短路 / 块错位 / 块未收齐 / abort 幂等收口）。
// 拒收经 promiseError 回客户端，断言 RPCError.message 原文（替身 StubFault 直通）。

import cn.biuz.android.protocol.ChannelClient
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.WebSocketTransportFactory
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class StubAttachmentStrictTest {

    // MARK: 主路径：事务三步 + 续传 + committed 短路（真实回环 wire）

    @Test
    fun attachmentTransaction_happyResumeAndCommittedShortCircuit() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            handshake(client)

            // ① Begin：workspace 信封 + 事务五键，strict 全过 → staging{nextChunkIndex:0}
            val beginAck = callOk(
                client,
                "attachmentBeginV4",
                beginJson(uploadId = "upload-tx-1", totalBytes = 30, totalChunks = 3, checksum = checksum64("a")),
            )
            assertEquals("staging", beginAck["state"]?.stringValue)
            assertEquals(0, beginAck["nextChunkIndex"]?.intValue)
            assertEquals("upload-tx-1", beginAck["uploadId"]?.stringValue)

            // ② Chunk ×2：chunkIndex 块序号（非字节 offset），回执 nextChunkIndex 恒 = index+1
            val chunk0 = callOk(client, "attachmentChunkV4", chunkJson("upload-tx-1", 0, bytes = 10))
            assertEquals(1, chunk0["nextChunkIndex"]?.intValue)
            val chunk1 = callOk(client, "attachmentChunkV4", chunkJson("upload-tx-1", 1, bytes = 10))
            assertEquals(2, chunk1["nextChunkIndex"]?.intValue)
            assertEquals(listOf(10, 10), server.attachmentReceivedChunkBytes("upload-tx-1"), "已收块解码字节数记账")

            // ③ 同 uploadId 重发 Begin（重试场景）→ 回报已收块数 = 续传起点（web PCe 同款）
            val resumeAck = callOk(
                client,
                "attachmentBeginV4",
                beginJson(uploadId = "upload-tx-1", totalBytes = 30, totalChunks = 3, checksum = checksum64("a")),
            )
            assertEquals(2, resumeAck["nextChunkIndex"]?.intValue, "staging.nextChunkIndex = 已收块数（续传起点）")

            // ④ 续传补齐 + Commit → ref
            callOk(client, "attachmentChunkV4", chunkJson("upload-tx-1", 2, bytes = 10))
            val commitAck = callOk(client, "attachmentCommitV4", finishJson("upload-tx-1"))
            val ref = commitAck["ref"]?.stringValue
            assertNotNull(ref)
            assertTrue(ref.startsWith("att-stub-"), "回执 ref（sendText attachments 携带值）：$ref")

            // ⑤ committed 短路：同 checksum+totalBytes 的新事务 Begin → 直接拿 ref（桌面去重语义）
            val dedupAck = callOk(
                client,
                "attachmentBeginV4",
                beginJson(uploadId = "upload-tx-2", totalBytes = 30, totalChunks = 3, checksum = checksum64("a")),
            )
            assertEquals("committed", dedupAck["state"]?.stringValue, "checksum 命中已上传附件 → 免分块直取引用")
            assertEquals(ref, dedupAck["ref"]?.stringValue)

            // ⑥ Abort 幂等收口：upload-tx-2 只走了 committed 短路（无 staging 在册）——
            // abort 幂等成功（strict 空对象回执）；其后再 commit = 事务不存在
            val abortAck = callOk(client, "attachmentAbortV4", finishJson("upload-tx-2"))
            assertEquals(0, abortAck.objectValue?.size, "Abort 回执 strict 空对象（v4AttachmentAbortResultSchema）")
            val closedCommit = runCatching { client.call("zcode-agent", "attachmentCommitV4", finishJson("upload-tx-2")) }
                .exceptionOrNull()
            assertTrue(
                (closedCommit as? RPCError)?.message?.contains("未知 uploadId") == true,
                "abort 后事务已关：${closedCommit?.message}",
            )

            // 记账面：全部受理调用 strict 零错、无业务拒因
            val accepted = server.attachmentCalls().filter { it.businessError == null && it.reply != null }
            assertTrue(accepted.isNotEmpty())
            assertTrue(accepted.all { it.schemaErrors.isEmpty() }, "受理调用 strict 对拍恒零错：${accepted.map { it.schemaErrors }}")
            assertEquals(1, server.attachmentCallCount("attachmentAbortV4"))
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: strict 对拍拒收（零字节 / 超限 / 坏 padding / connectionId / 缺信封）

    @Test
    fun attachmentStrictGate_zeroByteOversizeBadPaddingAndTrustedField() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            handshake(client)

            // ① 零字节 superRefine（transport.ts:859-868 上游原文 message）
            val zeroOne = faultOf(client, "attachmentBeginV4", beginJson("upload-z1", 0, 1))
            assertTrue(
                zeroOne.message.contains("zero-byte upload must declare zero chunks"),
                "零字节异态拒收：${zeroOne.message}",
            )
            val nonZeroZero = faultOf(client, "attachmentBeginV4", beginJson("upload-z2", 100, 0))
            assertTrue(
                nonZeroZero.message.contains("zero-byte upload must declare zero chunks"),
                "有字节零块声明同拒：${nonZeroZero.message}",
            )

            // ② 超限：totalBytes > 20MB（attachmentMaxBytes，core.ts:84）、totalChunks > 64
            val oversize = faultOf(
                client,
                "attachmentBeginV4",
                beginJson("upload-big", 20 * 1024 * 1024 + 1, 64),
            )
            assertTrue(oversize.message.contains("> max(20971520)"), "20MB 上限：${oversize.message}")
            val overChunks = faultOf(client, "attachmentBeginV4", beginJson("upload-many", 20 * 1024 * 1024, 65))
            assertTrue(overChunks.message.contains("> max(64)"), "64 块上限：${overChunks.message}")

            // ③ 坏 padding：长度非 4 倍数 / '=' 中置（transport.ts:910-917 "invalid base64"）
            val badLen = faultOf(client, "attachmentChunkV4", chunkRawJson("upload-tx-1", 0, "QUJ"))
            assertTrue(badLen.message.contains("invalid base64"), "长度非 4 倍数：${badLen.message}")
            val midPad = faultOf(client, "attachmentChunkV4", chunkRawJson("upload-tx-1", 0, "AB=C"))
            assertTrue(midPad.message.contains("invalid base64"), "'=' 中置：${midPad.message}")

            // ④ 单块解码超 512KiB（attachmentChunkMaxBytes，core.ts:85）
            val overChunk = faultOf(
                client,
                "attachmentChunkV4",
                chunkRawJson(
                    "upload-tx-1",
                    0,
                    Base64.getEncoder().encodeToString(ByteArray(512 * 1024 + 1)),
                ),
            )
            assertTrue(overChunk.message.contains("> max(524288)"), "512KiB 解码上限：${overChunk.message}")

            // ⑤ connectionId 出现在客户端参数 = 违反「服务端注入」纪律（甲-2/A11）——strict 未知键拒
            val forged = faultOf(
                client,
                "attachmentBeginV4",
                beginJson("upload-forged", 10, 1, extra = mapOf("connectionId" to JSONValue.Str("conn-forged"))),
            )
            assertTrue(
                forged.message.contains("connectionId") && forged.message.contains("未知键"),
                "多发 connectionId 必拒（服务端注入字段）：${forged.message}",
            )

            // ⑥ 缺 workspacePath（2026-10-06 真机实证：桌面实校验含会话域信封）
            val noEnvelope = faultOf(
                client,
                "attachmentBeginV4",
                beginJson("upload-noenv", 10, 1, workspacePath = null),
            )
            assertTrue(
                noEnvelope.message.contains("workspacePath") && noEnvelope.message.contains("缺失必填键"),
                "缺 workspacePath 拒收：${noEnvelope.message}",
            )

            // 记账面：全部拒收落在 schemaErrors / businessError，受理回执为 null
            //（零字节×2 + 超限×2 + 坏 padding×2 + 块解码超限 + connectionId + 缺信封 = 9）
            val rejected = server.attachmentCalls().filter { it.reply == null }
            assertEquals(9, rejected.size)
            assertTrue(rejected.all { it.schemaErrors.isNotEmpty() || it.businessError != null })
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: 业务门（块错位 / 块未收齐 / 未知事务）

    @Test
    fun attachmentBusinessGate_misorderedAndIncomplete() = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        var client: ChannelClient? = null
        try {
            client = newClient()
            client.connect("ws://127.0.0.1:$port/ws", 10.seconds) {}
            handshake(client)

            callOk(client, "attachmentBeginV4", beginJson("upload-b1", 30, 3, checksum = checksum64("b")))
            // 块错位（跳块）：下一块恒 = 已收块数
            val misordered = runCatching { client.call("zcode-agent", "attachmentChunkV4", chunkJson("upload-b1", 1, bytes = 10)) }
                .exceptionOrNull()
            assertTrue((misordered as? RPCError)?.message?.contains("chunkIndex 错位") == true, "${misordered?.message}")
            // 补齐中途 Commit：块未收齐
            val incomplete = runCatching { client.call("zcode-agent", "attachmentCommitV4", finishJson("upload-b1")) }
                .exceptionOrNull()
            assertTrue((incomplete as? RPCError)?.message?.contains("块未收齐") == true, "${incomplete?.message}")
            // 未知 uploadId 的 chunk
            val unknownChunk = runCatching { client.call("zcode-agent", "attachmentChunkV4", chunkJson("upload-ghost", 0, bytes = 10)) }
                .exceptionOrNull()
            assertTrue((unknownChunk as? RPCError)?.message?.contains("未知 uploadId") == true, "${unknownChunk?.message}")
            // 正确顺序补齐（错位重试从 0 起）后 Commit 命中
            callOk(client, "attachmentChunkV4", chunkJson("upload-b1", 0, bytes = 10))
            callOk(client, "attachmentChunkV4", chunkJson("upload-b1", 1, bytes = 10))
            assertEquals(listOf(10, 10), server.attachmentReceivedChunkBytes("upload-b1"), "commit 前账本已收 2 块")
            callOk(client, "attachmentChunkV4", chunkJson("upload-b1", 2, bytes = 10))
            val commit = callOk(client, "attachmentCommitV4", finishJson("upload-b1"))
            assertNotNull(commit["ref"]?.stringValue)
            assertEquals(-1, server.attachmentReceivedChunkCount("upload-b1"), "commit 后账本移册（未知 id = -1）")
        } finally {
            client?.disconnect()
            server.stop()
        }
    }

    // MARK: 夹具

    private fun newClient(): ChannelClient =
        ChannelClient(WebSocketTransportFactory { url, timeoutMs, maximumMessageSize ->
            JavaNetWebSocketTransport(url, timeoutMs, maximumMessageSize)
        })

    private suspend fun handshake(client: ChannelClient) {
        client.call("zcode-agent", "helloConversationV4")
        client.call("zcode-agent", "initializeConversationV4", clientHelloJson("zcode-mobile-attach-1"))
    }

    private fun clientHelloJson(clientId: String): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["kind"] = JSONValue.Str("clientHello")
        fields["protocolVersion"] = JSONValue.Int(3)
        fields["clientId"] = JSONValue.Str(clientId)
        fields["clientKind"] = JSONValue.Str("mobileApp")
        fields["appVersion"] = JSONValue.Str("0.1.0-attach")
        fields["capabilities"] = JSONValue.Obj(mapOf<String, JSONValue>())
        return RPCValue.json(JSONValue.Obj(fields))
    }

    private fun beginJson(
        uploadId: String,
        totalBytes: Int,
        totalChunks: Int,
        checksum: String = checksum64(),
        workspacePath: String? = WORKSPACE,
        extra: Map<String, JSONValue> = emptyMap(),
    ): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        if (workspacePath != null) fields["workspacePath"] = JSONValue.Str(workspacePath)
        fields["sessionId"] = JSONValue.Str("sess-e2e-1")
        fields["uploadId"] = JSONValue.Str(uploadId)
        fields["fileName"] = JSONValue.Str("a.bin")
        fields["mime"] = JSONValue.Str("application/octet-stream")
        fields["totalBytes"] = JSONValue.Int(totalBytes)
        fields["totalChunks"] = JSONValue.Int(totalChunks)
        fields["checksum"] = JSONValue.Str(checksum)
        fields.putAll(extra)
        return RPCValue.json(JSONValue.Obj(fields))
    }

    private fun chunkJson(uploadId: String, chunkIndex: Int, bytes: Int): RPCValue =
        chunkRawJson(uploadId, chunkIndex, Base64.getEncoder().encodeToString(ByteArray(bytes)))

    private fun chunkRawJson(uploadId: String, chunkIndex: Int, dataBase64: String): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(WORKSPACE)
        fields["sessionId"] = JSONValue.Str("sess-e2e-1")
        fields["uploadId"] = JSONValue.Str(uploadId)
        fields["chunkIndex"] = JSONValue.Int(chunkIndex)
        fields["dataBase64"] = JSONValue.Str(dataBase64)
        return RPCValue.json(JSONValue.Obj(fields))
    }

    private fun finishJson(uploadId: String): RPCValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(WORKSPACE)
        fields["sessionId"] = JSONValue.Str("sess-e2e-1")
        fields["uploadId"] = JSONValue.Str(uploadId)
        return RPCValue.json(JSONValue.Obj(fields))
    }

    private suspend fun callOk(client: ChannelClient, method: String, arg: RPCValue): JSONValue =
        client.call("zcode-agent", method, arg).jsonValue
            ?: throw AssertionError("$method 回执缺 JSON body")

    /** 期望拒收的调用：断言 promiseError 形态并返回 RPCError（拒因原文断言面）。 */
    private suspend fun faultOf(client: ChannelClient, method: String, arg: RPCValue): RPCError {
        val error = runCatching { client.call("zcode-agent", method, arg) }.exceptionOrNull()
        return error as? RPCError
            ?: throw AssertionError("$method 期望 promiseError，实际 ${error?.javaClass?.simpleName}: $error")
    }

    private fun checksum64(seed: String = "c"): String = "sha256:" + seed.repeat(64)

    private companion object {
        const val WORKSPACE = "/Users/e2e/zcode-workspace"
    }
}

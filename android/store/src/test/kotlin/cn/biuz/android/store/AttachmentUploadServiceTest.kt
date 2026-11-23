package cn.biuz.android.store

// 附件链路单测（2026-10-10 附件链路移植轮；对应 iOS 门禁面）：
// - AttachmentUploadService：add 三道超限闸（空数据/20MB/8 条）、事务三步
//   begin(sha256+totalChunks)→chunk(chunkIndex 非 offset)→commit、每块独立 base64
//   自带 padding（A11）、Begin committed 短路 / staging 续传起点、进度错位即中止、
//   Begin 失败不发 Abort（web u=false 同款）、Abort 未送达如实上屏（戊-3）；
// - RemoteConversationStore 附件四方法：strict 本地拒发不空耗线缆、wire 键面无
//   connectionId（甲-2/A11：服务端注入，多发即 Invalid params）、回执解析三形态、
//   1.2s 首败退避重试一次（Abort 例外不重试）。
// Mock 仅测试装配（红线见 AGENTS §5-13）：替身传输复用 RemoteConversationStoreTest 的 Fixture。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCError
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private fun checksum64(hex: String = "0") = "sha256:" + hex.repeat(64)

class AttachmentUploadServiceTest {

    // MARK: add 三道闸 + wire 形状辅助（iOS :98-116/:296-322）

    @Test
    fun add_guards_emptyOversizeAndMaxItems() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))

        assertFalse(service.add("a.bin", "application/octet-stream", ByteArray(0)))
        assertTrue(service.hint!!.contains("内容为空"), "空数据不发起事务：${service.hint}")

        assertFalse(
            service.add("big.png", "image/png", ByteArray(AttachmentUploadService.MAX_BYTES + 1)),
        )
        assertTrue(service.hint!!.contains("20MB"), "超 20MB 本地拒（core.ts:84 attachmentMaxBytes）：${service.hint}")

        repeat(AttachmentUploadService.MAX_ITEMS) {
            assertTrue(service.add("f$it.bin", "application/octet-stream", ByteArray(1)))
        }
        assertFalse(service.add("9th.bin", "application/octet-stream", ByteArray(1)))
        assertTrue(service.hint!!.contains("最多同时携带 8"), "条数上限（staged 64MB 预算防击穿）：${service.hint}")
        assertEquals(AttachmentUploadService.MAX_ITEMS, service.totalCount)
    }

    @Test
    fun wireHelpers_uploadIdChunkMathAndSha256() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        assertTrue(service.makeUploadId().startsWith("upload-"), "upload-<uuid>（web MCe 同构）")
        assertEquals(1, service.totalChunksFor(1))
        assertEquals(1, service.totalChunksFor(AttachmentUploadService.CHUNK_SIZE))
        assertEquals(2, service.totalChunksFor(AttachmentUploadService.CHUNK_SIZE + 1))
        assertEquals(
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            service.sha256Checksum("hello".toByteArray()),
            "sha256:+64hex（web jCe 同构；测试内自证向量）",
        )
    }

    // MARK: 事务主路径（iOS :192-283）

    @Test
    fun transaction_happyPath_multiChunk_perChunkPadding() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        val size = AttachmentUploadService.CHUNK_SIZE + 5
        val data = ByteArray(size) { (it % 251).toByte() }
        assertTrue(service.add("report.pdf", "application/pdf", data))
        val item = service.itemsSnapshot().single()
        assertTrue(service.blocksSend, "有未 committed 项 = sendButton 禁用面")

        assertTrue(service.ensureAllCommitted(), "全部 committed 才放行发送")

        // Begin 键面：workspace 信封 + sessionId + uploadId + 事务五键，绝无 connectionId
        val begin = f.transport.attachmentArgs.first { it.first == "attachmentBeginV4" }.third
        assertEquals("sess-1", begin["sessionId"]?.stringValue)
        assertEquals("/conn/ws", begin["workspacePath"]?.stringValue, "会话域 workspace 信封")
        assertEquals("conn-ident", begin["workspaceIdentity"]?.stringValue)
        assertFalse(begin.containsKey("connectionId"), "甲-2/A11：connectionId 服务端注入，客户端多发即 Invalid params")
        assertEquals("report.pdf", begin["fileName"]?.stringValue)
        assertEquals("application/pdf", begin["mime"]?.stringValue)
        assertEquals(size, begin["totalBytes"]?.intValue)
        assertEquals(2, begin["totalChunks"]?.intValue)
        val beginChecksum = begin["checksum"]?.stringValue ?: ""
        assertTrue(beginChecksum.startsWith("sha256:") && beginChecksum.length == "sha256:".length + 64, "checksum = sha256:+64hex（对全量数据摘要，与 chunk 切分无关）")
        assertTrue(begin["uploadId"]?.stringValue?.startsWith("upload-") == true)

        // Chunk：chunkIndex 为块序号非字节 offset；每块独立编码自带 padding（A11）
        val chunks = f.transport.attachmentArgs.filter { it.first == "attachmentChunkV4" }
        assertEquals(2, chunks.size)
        val firstB64 = chunks[0].third["dataBase64"]?.stringValue ?: ""
        val secondB64 = chunks[1].third["dataBase64"]?.stringValue ?: ""
        assertEquals(0, chunks[0].third["chunkIndex"]?.intValue)
        assertEquals(1, chunks[1].third["chunkIndex"]?.intValue)
        assertFalse(firstB64.contains('='), "384KiB 可被 3 整除——整块无 padding（上游 attachmentUploadTransaction.ts:15 注释）")
        assertTrue(secondB64.contains('='), "末块独立编码自带 padding（A11：分块各自带 padding）")
        assertTrue(firstB64.length % 4 == 0 && secondB64.length % 4 == 0, "两块各自 4 对齐（padding 自足，非整串拼接后补）")
        val firstBytes = Base64.getDecoder().decode(firstB64)
        val secondBytes = Base64.getDecoder().decode(secondB64)
        assertEquals(AttachmentUploadService.CHUNK_SIZE, firstBytes.size)
        assertEquals(5, secondBytes.size)
        for (i in firstBytes.indices) assertEquals(data[i], firstBytes[i], "块 0 字节错位 @$i")
        for (i in secondBytes.indices) assertEquals(data[AttachmentUploadService.CHUNK_SIZE + i], secondBytes[i], "块 1 字节错位 @$i")
        // 回执进度判定 = nextChunkIndex 恒等于 chunkIndex+1（缺省自然回执已满足）

        // Commit：ref 落 item；sendText 附件形状 {ref, fileName, mime, bytes}（B-1，甲-4 禁改名）
        val commit = f.transport.attachmentArgs.first { it.first == "attachmentCommitV4" }.third
        assertEquals(begin["uploadId"]?.stringValue, commit["uploadId"]?.stringValue, "事务三步同 uploadId")
        assertEquals("att-fake-1", item.ref)
        assertEquals(PendingAttachment.State.committed, item.state)
        assertEquals(size, item.uploadedBytes, "uploadedBytes 截断到 totalBytes")
        assertFalse(service.blocksSend)
        val outgoing = service.committedAttachments().single()
        assertEquals("att-fake-1", outgoing.ref)
        assertEquals("report.pdf", outgoing.fileName)
        assertEquals("application/pdf", outgoing.mime)
        assertEquals(size, outgoing.bytes)

        // 发送成功移除（clearCommitted 配对面）
        service.clearCommitted(listOf(outgoing))
        assertTrue(service.isEmpty)
    }

    @Test
    fun transaction_beginCommitted_shortCircuitsWithoutChunks() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        val data = ByteArray(128) { 3 }
        service.add("dup.bin", "application/octet-stream", data)
        val item = service.itemsSnapshot().single()
        f.transport.enqueueAttachment(
            "attachmentBeginV4",
            testObj(
                "state" to JSONValue.Str("committed"),
                "ref" to JSONValue.Str("att-dup"),
                "nextChunkIndex" to JSONValue.Int(1),
            ),
        )
        assertTrue(service.ensureAllCommitted())
        assertEquals("att-dup", item.ref, "checksum 命中桌面已有附件 → 免分块直取引用")
        assertEquals(data.size, item.uploadedBytes)
        assertEquals(0, f.transport.attachmentCallCount("attachmentChunkV4"), "committed 短路零分块")
        assertEquals(0, f.transport.attachmentCallCount("attachmentCommitV4"))
    }

    @Test
    fun transaction_beginStaging_nextChunkIndexIsResumePoint() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        val size = AttachmentUploadService.CHUNK_SIZE + 5
        service.add("resume.bin", "application/octet-stream", ByteArray(size))
        val item = service.itemsSnapshot().single()
        f.transport.enqueueAttachment(
            "attachmentBeginV4",
            testObj("state" to JSONValue.Str("staging"), "nextChunkIndex" to JSONValue.Int(1)),
        )
        assertTrue(service.ensureAllCommitted())
        val chunkIndexes = f.transport.attachmentArgs
            .filter { it.first == "attachmentChunkV4" }
            .map { it.third["chunkIndex"]?.intValue }
        assertEquals(listOf(1), chunkIndexes, "staging.nextChunkIndex 为权威续传起点（web PCe 同款）")
        assertEquals(size, item.uploadedBytes)
    }

    // MARK: 失败路径（iOS :204-208/:277-294）

    @Test
    fun transaction_chunkMisreport_invalidServerProgress_abortSent() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        service.add("x.bin", "application/octet-stream", ByteArray(64))
        val item = service.itemsSnapshot().single()
        // 进度错位必须连续注两条（首败 1.2s 退避重试一次，第二次仍错位才落失败）
        f.transport.enqueueAttachment("attachmentChunkV4", testObj("nextChunkIndex" to JSONValue.Int(5)))
        f.transport.enqueueAttachment("attachmentChunkV4", testObj("nextChunkIndex" to JSONValue.Int(5)))
        assertFalse(service.ensureAllCommitted())
        assertEquals(PendingAttachment.State.failed, item.state)
        assertTrue(
            item.errorText!!.contains("fault.attachment.invalidServerProgress"),
            "进度错位=事务状态不可信：${item.errorText}",
        )
        assertEquals(1, f.transport.attachmentCallCount("attachmentAbortV4"), "Begin 已成功的事务失败 → Abort 尽力收口")
        assertNull(item.uploadId, "Abort 送达 = 事务已关（重试重开新事务）")
    }

    @Test
    fun transaction_beginFails_noAbortSent() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        service.add("y.bin", "application/octet-stream", ByteArray(8))
        val item = service.itemsSnapshot().single()
        f.transport.enqueueAttachment(
            "attachmentBeginV4",
            RPCError(message = "桌面繁忙", name = "TimeoutError"),
            RPCError(message = "桌面繁忙", name = "TimeoutError"),
        )
        assertFalse(service.ensureAllCommitted())
        assertTrue(
            item.errorText!!.contains("附件上传开启失败 · TimeoutError: 桌面繁忙"),
            "Begin 拒因原文直出失败行：${item.errorText}",
        )
        assertEquals(2, f.transport.attachmentCallCount("attachmentBeginV4"), "首败 1.2s 退避重试一次（AGENTS §5-9）")
        assertEquals(0, f.transport.attachmentCallCount("attachmentAbortV4"), "Begin 未成功无事务可中止（web u=false 同款）")
    }

    @Test
    fun transaction_commitFails_abortThenRetryOpensNewTransaction() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        service.add("z.bin", "application/octet-stream", ByteArray(32))
        val item = service.itemsSnapshot().single()
        f.transport.enqueueAttachment(
            "attachmentCommitV4",
            RPCError(message = "提交超时", name = "TimeoutError"),
            RPCError(message = "提交超时", name = "TimeoutError"),
        )
        assertFalse(service.ensureAllCommitted())
        assertTrue(item.errorText!!.contains("附件提交未送达 · TimeoutError: 提交超时"), "${item.errorText}")
        assertEquals(1, f.transport.attachmentCallCount("attachmentAbortV4"))
        assertNull(item.uploadId, "Abort 缺省送达 → uploadId 置 nil")

        val firstUploadId = f.transport.attachmentArgs
            .first { it.first == "attachmentBeginV4" }.third["uploadId"]?.stringValue
        service.retry(item.id)
        runCurrent()
        assertEquals(PendingAttachment.State.committed, item.state, "重试走缺省自然回执 → committed")
        val secondUploadId = f.transport.attachmentArgs
            .last { it.first == "attachmentBeginV4" }.third["uploadId"]?.stringValue
        assertNotNull(firstUploadId)
        assertNotNull(secondUploadId)
        assertTrue(firstUploadId != secondUploadId, "Abort 已送达的事务重试重开新事务（uploadId 变更）")
    }

    @Test
    fun transaction_abortUndelivered_hintSurfacedVerbatim_wu3() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        service.add("busy.bin", "application/octet-stream", ByteArray(16))
        val item = service.itemsSnapshot().single()
        f.transport.enqueueAttachment(
            "attachmentChunkV4",
            RPCError(message = "附件分块超时", name = "TimeoutError"),
            RPCError(message = "附件分块超时", name = "TimeoutError"),
        )
        f.transport.enqueueAttachment(
            "attachmentAbortV4",
            RPCError(message = "桌面繁忙 30s 一次性超时", name = "TimeoutError"),
        )
        assertFalse(service.ensureAllCommitted())
        assertTrue(
            item.errorText!!.contains("附件分块未送达 · TimeoutError: 附件分块超时"),
            "原始错误不被 Abort 结果掩盖（web warn-and-continue 同款）：${item.errorText}",
        )
        assertTrue(
            service.hint!!.contains("附件上传中止指令未送达 · TimeoutError: 桌面繁忙 30s 一次性超时"),
            "Abort 超时如实上屏（戊-3：单管道负载现象不吞不美化）：${service.hint}",
        )
        assertNotNull(item.uploadId, "Abort 未送达 = 保留续传（断线重连场景）")
        assertEquals(1, f.transport.attachmentCallCount("attachmentAbortV4"), "Abort 不重试（iOS 同口径）")
    }

    @Test
    fun transaction_chunkFirstFailure_backoffRetryHits() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        service.add("retry.bin", "application/octet-stream", ByteArray(16))
        val item = service.itemsSnapshot().single()
        // 仅注首败；第二次调用走缺省自然回执
        f.transport.enqueueAttachment(
            "attachmentChunkV4",
            RPCError(message = "中继瞬断", name = "TimeoutError"),
        )
        assertTrue(service.ensureAllCommitted(), "中继瞬断首败 1.2s 退避重试一次即命中")
        assertEquals(2, f.transport.attachmentCallCount("attachmentChunkV4"))
        assertEquals(PendingAttachment.State.committed, item.state)
    }

    // MARK: 条目管理（iOS :118-149/:176-190）

    @Test
    fun remove_pendingItemWithTransaction_aborts_committedSkipped() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        // ① 失败且 Abort 未送达（uploadId 保留）→ remove 尽力 Abort 收口
        service.add("r1.bin", "application/octet-stream", ByteArray(16))
        val failed = service.itemsSnapshot().single()
        f.transport.enqueueAttachment(
            "attachmentChunkV4",
            RPCError(message = "分块超时", name = "TimeoutError"),
            RPCError(message = "分块超时", name = "TimeoutError"),
        )
        f.transport.enqueueAttachment(
            "attachmentAbortV4",
            RPCError(message = "中止未送达", name = "TimeoutError"),
        )
        assertFalse(service.ensureAllCommitted())
        assertEquals(1, f.transport.attachmentCallCount("attachmentAbortV4"))
        assertNotNull(failed.uploadId)
        service.remove(failed.id)
        runCurrent()
        assertEquals(2, f.transport.attachmentCallCount("attachmentAbortV4"), "remove 对未 committed 且有事务的条目尽力 Abort")
        assertEquals(0, service.totalCount, "条目已移除")
        // ② committed 条目 remove：零 Abort（引用已交付 sendText）
        service.add("r2.bin", "application/octet-stream", ByteArray(16))
        val committed = service.itemsSnapshot().single()
        assertTrue(service.ensureAllCommitted())
        val before = f.transport.attachmentCallCount("attachmentAbortV4")
        service.remove(committed.id)
        runCurrent()
        assertEquals(before, f.transport.attachmentCallCount("attachmentAbortV4"), "committed 条目移除不发 Abort")
    }

    @Test
    fun clearCommitted_keepsUnsentRefs() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        service.add("c1.bin", "application/octet-stream", ByteArray(1))
        service.add("c2.bin", "application/octet-stream", ByteArray(1))
        assertTrue(service.ensureAllCommitted())
        assertEquals(listOf("att-fake-1", "att-fake-2"), service.committedAttachments().map { it.ref })
        service.clearCommitted(listOf(service.committedAttachments().first()))
        assertEquals(1, service.totalCount, "只移除已随消息下发的 ref（失败保留语义 §11.1#6）")
    }

    @Test
    fun hint_autoClearsAfter4s() = runTest {
        val f = Fixture(this)
        val service = AttachmentUploadService(f.store, "sess-1", StandardTestDispatcher(testScheduler))
        assertFalse(service.add("", "application/octet-stream", ByteArray(0)))
        assertNotNull(service.hint)
        advanceTimeBy(4_100)
        assertNull(service.hint, "提示短暂展示后自动清除（iOS :145 4s 同值）")
    }

    // MARK: store 四方法（strict 本地拒发 / wire 键面 / 回执解析 / 重试口径）

    @Test
    fun storeAttachment_paramViolations_rejectLocallyWithoutWire() = runTest {
        val f = Fixture(this)
        // fileName 带 LF（transport.ts:850-853 正则 [^\0\r\n]+）
        val badName = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-ok", fileName = "bad\nname",
            mime = "application/pdf", totalBytes = 10, totalChunks = 1, checksum = checksum64(),
        )
        assertTrue(badName.isFailure)
        assertTrue(badName.exceptionOrNull()!!.message!!.contains("参数校验失败"))
        assertEquals(0, f.transport.attachmentCallCount("attachmentBeginV4"), "本地构造期即爆，不空耗线缆")
        // uploadId 形状（transport.ts:840-843 ^[A-Za-z0-9][A-Za-z0-9._:-]*$）
        val badUploadId = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "bad id!", fileName = "a.bin",
            mime = "application/pdf", totalBytes = 10, totalChunks = 1, checksum = checksum64(),
        )
        assertTrue(badUploadId.isFailure)
        assertEquals(0, f.transport.attachmentCallCount("attachmentBeginV4"))
        // chunk 解码 > 512KiB（transport.ts:918-925 superRefine 的本地镜像）
        val big = ByteArray(CommandSchemasAttachmentChunkLimit + 1)
        val oversize = f.store.attachmentChunkV4(
            "sess-1", "upload-ok", 0, Base64.getEncoder().encodeToString(big),
        )
        assertTrue(oversize.isFailure)
        assertTrue(oversize.exceptionOrNull()!!.message!!.contains("max(524288)"), "512KiB 解码上限（A11）")
        assertEquals(0, f.transport.attachmentCallCount("attachmentChunkV4"))
    }

    @Test
    fun storeAttachment_wireShape_workspaceEnvelopeAndNoConnectionId() = runTest {
        val f = Fixture(this)
        f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-1", fileName = "a.bin",
            mime = "application/octet-stream", totalBytes = 4, totalChunks = 1,
            checksum = checksum64(),
        )
        val beginArgs = f.transport.attachmentArgs.single().third
        assertEquals(
            setOf(
                "workspacePath", "workspaceIdentity", "sessionId", "uploadId",
                "fileName", "mime", "totalBytes", "totalChunks", "checksum",
            ),
            beginArgs.keys,
            "Begin 键面 = web 客户端逐键对齐（attachmentUploadTransaction.ts common + 事务五键）",
        )
        assertEquals("/conn/ws", beginArgs["workspacePath"]?.stringValue)
        assertFalse(beginArgs.containsKey("connectionId"), "甲-2/A11：绝不发 connectionId")
        f.store.attachmentChunkV4("sess-1", "upload-1", 0, "QUJD")
        assertEquals(
            setOf("workspacePath", "workspaceIdentity", "sessionId", "uploadId", "chunkIndex", "dataBase64"),
            f.transport.attachmentArgs.last().third.keys,
        )
        f.store.attachmentCommitV4("sess-1", "upload-1")
        assertEquals(
            setOf("workspacePath", "workspaceIdentity", "sessionId", "uploadId"),
            f.transport.attachmentArgs.last().third.keys,
            "Commit = common 三键 + workspace 信封（transport.ts:937-947）",
        )
        f.store.attachmentAbortV4("sess-1", "upload-1")
        assertEquals(
            setOf("workspacePath", "workspaceIdentity", "sessionId", "uploadId"),
            f.transport.attachmentArgs.last().third.keys,
        )
        // diag 取证面（iOS diag.attach.last 同名键）
        assertTrue(
            f.diag["diag.attach.last"]?.startsWith("commit ") == true,
            "diag.attach.last 在案（begin/commit 各写一次，末次 = commit；abort 不写）",
        )
    }

    @Test
    fun storeAttachment_receiptParsing_threeShapes() = runTest {
        val f = Fixture(this)
        // committed 短路形态
        f.transport.enqueueAttachment(
            "attachmentBeginV4",
            testObj("state" to JSONValue.Str("committed"), "ref" to JSONValue.Str("att-dup")),
        )
        val committed = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-1", fileName = "a.bin",
            mime = "application/octet-stream", totalBytes = 4, totalChunks = 1,
            checksum = checksum64(),
        )
        assertEquals("att-dup", (committed.getOrNull() as? AttachmentBeginOutcome.Committed)?.ref)
        // staging 形态
        f.transport.enqueueAttachment(
            "attachmentBeginV4",
            testObj("state" to JSONValue.Str("staging"), "nextChunkIndex" to JSONValue.Int(2)),
        )
        val staging = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-2", fileName = "a.bin",
            mime = "application/octet-stream", totalBytes = 4, totalChunks = 1,
            checksum = checksum64(),
        )
        assertEquals(2, (staging.getOrNull() as? AttachmentBeginOutcome.Staging)?.nextChunkIndex)
        // 形状不识别如实报错
        f.transport.enqueueAttachment("attachmentBeginV4", testObj("unexpected" to JSONValue.Bool(true)))
        val unknown = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-3", fileName = "a.bin",
            mime = "application/octet-stream", totalBytes = 4, totalChunks = 1,
            checksum = checksum64(),
        )
        assertTrue(unknown.exceptionOrNull()!!.message!!.contains("回执 state 未识别"))
        // committed 缺 ref
        f.transport.enqueueAttachment("attachmentBeginV4", testObj("state" to JSONValue.Str("committed")))
        val noRef = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-4", fileName = "a.bin",
            mime = "application/octet-stream", totalBytes = 4, totalChunks = 1,
            checksum = checksum64(),
        )
        assertTrue(noRef.exceptionOrNull()!!.message!!.contains("committed 回执缺 ref"))
        // chunk 缺 nextChunkIndex
        f.transport.enqueueAttachment("attachmentChunkV4", testObj())
        val noNext = f.store.attachmentChunkV4("sess-1", "upload-1", 0, "QUJD")
        assertTrue(noNext.exceptionOrNull()!!.message!!.contains("回执缺 nextChunkIndex"))
        // commit 缺 ref
        f.transport.enqueueAttachment("attachmentCommitV4", testObj())
        val noCommitRef = f.store.attachmentCommitV4("sess-1", "upload-1")
        assertTrue(noCommitRef.exceptionOrNull()!!.message!!.contains("回执缺 ref"))
    }

    @Test
    fun storeAttachment_firstFailureRetriesOnce_abortNeverRetries() = runTest {
        val f = Fixture(this)
        f.transport.enqueueAttachment(
            "attachmentBeginV4",
            RPCError(message = "瞬断", name = "TimeoutError"),
        )
        val begin = f.store.attachmentBeginV4(
            sessionId = "sess-1", uploadId = "upload-1", fileName = "a.bin",
            mime = "application/octet-stream", totalBytes = 4, totalChunks = 1,
            checksum = checksum64(),
        )
        assertTrue(begin.isSuccess, "首败后走缺省自然回执（重试命中）")
        assertEquals(2, f.transport.attachmentCallCount("attachmentBeginV4"), "1.2s 退避重试一次（AGENTS §5-9）")
        f.transport.enqueueAttachment(
            "attachmentAbortV4",
            RPCError(message = "瞬断", name = "TimeoutError"),
        )
        val abort = f.store.attachmentAbortV4("sess-1", "upload-1")
        assertTrue(abort.isFailure)
        assertEquals(1, f.transport.attachmentCallCount("attachmentAbortV4"), "Abort 尽力收口不重试（iOS 同口径）")
    }

    /** 512KiB 常量镜像（CommandSchemas.attachmentChunkMaxBytes——跨包 internal 不可见，测试内复刻）。 */
    private companion object {
        const val CommandSchemasAttachmentChunkLimit = 512 * 1024
    }
}

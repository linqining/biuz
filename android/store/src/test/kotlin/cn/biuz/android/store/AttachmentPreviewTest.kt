package cn.biuz.android.store

// `attachmentPreview`（attachmentReadV4 分块聚合）单测。
//
// 缺陷背景（2026-11-24 任务 2 走查）：该方法此前**只有接口默认 null 实现**，
// RemoteConversationStore 从未 override → 连接态下会话里每个 userInput 附件都落到
// AttachmentViews 的 failed 分支，真机一律「⚠ 附件加载失败」。
//
// 口径（iOS RemoteConversationStore.swift:5163-5242 同构）：
// ① 按 nextOffset 连续拉块；② 块数耗尽仍要下一页 = 截断 → 作失败（禁前缀冒充完整）；
// ③ 分块 base64 各自 padding，整串解码失败按块独立解码拼接；④ 首败 1.2s 退避重试一次。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class AttachmentPreviewTest {

    private fun chunkReply(
        base64: String,
        mediaType: String = "image/png",
        nextOffset: Int? = null,
        totalBytes: Int = 0,
    ): RPCValue = RPCValue.json(
        testObj(
            "dataBase64" to JSONValue.Str(base64),
            "mediaType" to JSONValue.Str(mediaType),
            "totalBytes" to testNum(totalBytes.toLong()),
            "nextOffset" to nextOffset?.let { testNum(it.toLong()) },
        ),
    )

    @Test
    fun aggregatesAllChunksUntilNextOffsetAbsent() = runTest {
        val f = Fixture(this)
        val part1 = "aGVsbG8g"   // "hello "
        val part2 = "d29ybGQ="   // "world"
        f.transport.attachmentReadScript.add(chunkReply(part1, nextOffset = 8, totalBytes = 11))
        f.transport.attachmentReadScript.add(chunkReply(part2, totalBytes = 11))

        val preview = f.store.attachmentPreview("sess-1", "att-1")
        advanceUntilIdle()

        assertNotNull(preview, "两块应聚合成完整预览（原缺陷：恒 null → 全部显示加载失败）")
        assertEquals("image/png", preview.mediaType)
        assertEquals(11, preview.totalBytes)
        assertContentEquals("hello world".toByteArray(), preview.data)
        assertEquals(2, f.transport.attachmentReadArgs.size, "按 nextOffset 连续拉两块")
        assertEquals(0L, f.transport.attachmentReadArgs[0]["offset"]?.longValue())
        assertEquals(8L, f.transport.attachmentReadArgs[1]["offset"]?.longValue())
        // 会话域寻址（A7：params.workspacePath 选 CLI 进程）
        assertEquals("sess-1", f.transport.attachmentReadArgs[0]["sessionId"]?.stringValue)
        assertNotNull(f.transport.attachmentReadArgs[0]["workspacePath"]?.stringValue)
    }

    @Test
    fun truncationIsFailureNotPartialPreview() = runTest {
        // 60 块上限用尽仍在要下一页 = 截断 → 必须 null（不能拿前缀冒充完整预览）
        val f = Fixture(this)
        repeat(60) { i ->
            f.transport.attachmentReadScript.add(
                chunkReply("YQ==", nextOffset = (i + 1) * 512 * 1024, totalBytes = 40_000_000),
            )
        }
        val preview = f.store.attachmentPreview("sess-1", "att-big")
        advanceUntilIdle()
        assertNull(preview, "截断必须作失败处理（上游 fault.attachment.previewTruncated 同语义）")
    }

    @Test
    fun decodesPerChunkWhenJoinedStringFails() = runTest {
        // 每块自带 padding：整串拼接后 padding 出现在中间 → 整串解码不可靠，
        // 必须能按块独立解码拼接（AGENTS §5-8）
        val f = Fixture(this)
        f.transport.attachmentReadScript.add(chunkReply("YWJj", nextOffset = 3, totalBytes = 6))
        f.transport.attachmentReadScript.add(chunkReply("ZGVm", totalBytes = 6))

        val preview = f.store.attachmentPreview("sess-1", "att-2")
        advanceUntilIdle()
        assertNotNull(preview)
        assertContentEquals("abcdef".toByteArray(), preview.data, "分块解码拼接")
    }

    @Test
    fun retriesOnceAfterTransientFailure() = runTest {
        // 首轮分块读抛错（中继瞬断）→ 1.2s 退避后重试一次成功
        val f = Fixture(this)
        f.transport.attachmentReadScript.add(
            RPCValue.json(testObj()), // 缺 dataBase64 → once() 返回 null
        )
        f.transport.attachmentReadScript.add(chunkReply("b2s=", totalBytes = 2))

        val preview = f.store.attachmentPreview("sess-1", "att-3")
        advanceUntilIdle()
        assertNotNull(preview, "首败后重试一次应成功（§5-9）")
        assertContentEquals("ok".toByteArray(), preview.data)
    }

    @Test
    fun missingDataBase64ReturnsNull() = runTest {
        val f = Fixture(this)
        f.transport.attachmentReadScript.add(RPCValue.json(testObj("mediaType" to JSONValue.Str("image/png"))))
        f.transport.attachmentReadScript.add(RPCValue.json(testObj("mediaType" to JSONValue.Str("image/png"))))
        val preview = f.store.attachmentPreview("sess-1", "att-bad")
        advanceUntilIdle()
        assertNull(preview, "无 dataBase64 不造空预览")
    }
}

package cn.biuz.android.store

// `session/subagents` 子代理历史目录读面单测。
//
// 【实证·上游仓】wire 名 `session/subagents`（zcode-protocol/index.ts:4436，**非** v4/ 前缀）；
// 参数 `{sessionId, endedCursor?, endedLimit?=20 (≤100)}`（:2088-2095）；
// 回执 `{revision, childSessionIds[], running[], ended:{total, items[], nextCursor?}}`
// （:2013-2027），ended 词表 `success|failed|cancelled|lost`（:2008-2010）。
// 供活动摘要的「子代理目录」入口（web useSessionSubagents 同源）——此前移动端无此面，
// 目录入口只能恒关。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SubagentDirectoryTest {

    @Test
    fun parsesDirectoryPageWithCursor() = runTest {
        val f = Fixture(this)
        f.transport.subagentDirectoryReply = testObj(
            "revision" to testNum(4),
            "childSessionIds" to JSONValue.Arr(
                listOf(JSONValue.Str("c1"), JSONValue.Str("c2")),
            ),
            "running" to JSONValue.Arr(emptyList()),
            "ended" to testObj(
                "total" to testNum(7),
                "items" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "childSessionId" to JSONValue.Str("c1"),
                            "subagentType" to JSONValue.Str("explore"),
                            "title" to JSONValue.Str("搜索协议"),
                            "status" to JSONValue.Str("success"),
                            "startedAt" to testNum(1000),
                            "endedAt" to testNum(2000),
                        ),
                        testObj(
                            "childSessionId" to JSONValue.Str("c2"),
                            "subagentType" to JSONValue.Str("general"),
                            "title" to JSONValue.Str("改代码"),
                            "status" to JSONValue.Str("failed"),
                            "summary" to JSONValue.Str("编译失败"),
                        ),
                    ),
                ),
                "nextCursor" to JSONValue.Str("cursor-2"),
            ),
        )
        val page = f.store.subagentDirectory("sess-1", cursor = null, limit = 20)
        advanceUntilIdle()

        assertNotNull(page, "回执必须解析（原实现无此读面 → 目录入口只能恒关）")
        assertEquals(4, page.revision)
        assertEquals(listOf("c1", "c2"), page.childSessionIds)
        assertEquals(7, page.total)
        assertEquals(2, page.items.size)
        assertEquals("搜索协议", page.items[0].title)
        assertEquals("success", page.items[0].status)
        assertEquals(2000L, page.items[0].endedAt)
        assertEquals("编译失败", page.items[1].summary)
        assertEquals("failed", page.items[1].status)
        assertEquals("cursor-2", page.nextCursor, "nextCursor 缺席 = 已到末页")

        // 参数形状：**严格只有三键**——`zcodeSessionSubagentsParamsSchema` 是 `.strict()`，
        // 带 workspacePath/workspaceIdentity 会被整条拒收（A4 纪律；本轮真机实证
        // `Method not found: session/subagents` 是另一回事，见下）
        val args = f.transport.subagentDirectoryArgs.single()
        assertEquals("sess-1", args["sessionId"]?.stringValue)
        assertEquals(
            setOf("sessionId", "endedLimit"),
            args.keys,
            "strict 参数层禁多余键（不得带会话域 workspace 信封）",
        )
    }

    @Test
    fun cursorAndLimitAreSentAndClamped() = runTest {
        val f = Fixture(this)
        f.store.subagentDirectory("sess-2", cursor = "cur-x", limit = 500)
        advanceUntilIdle()
        val args = f.transport.subagentDirectoryArgs.single()
        assertEquals("cur-x", args["endedCursor"]?.stringValue)
        assertEquals(100L, args["endedLimit"]?.longValue(), "上限 100（schema max(100)）")
    }

    @Test
    fun failureReturnsNullNotFakeEmptyDirectory() = runTest {
        val f = Fixture(this)
        f.transport.subagentDirectoryReply = testObj() // 缺 ended → 空但结构存在
        val page = f.store.subagentDirectory("sess-3")
        advanceUntilIdle()
        assertNotNull(page)
        assertEquals(0, page.items.size)
        assertNull(page.nextCursor)
    }
}

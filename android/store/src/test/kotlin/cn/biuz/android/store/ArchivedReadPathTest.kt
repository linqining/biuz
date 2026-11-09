package cn.biuz.android.store

// 019 归档管理读面（2026-11-09）：
// - 主列表 `toConversations()` 恒不含归档行（includeArchived 缺省 false）；
// - 归档读面 `toConversations(includeArchived = true)` 保留归档行，供归档页 filter 用。
// 背景：接口 `archivedConversations()` 此前只有默认空实现（Remote store 未覆盖）+
// 主投影 filter 掉归档行 → 归档页恒空（真机实测）。本测试锁住两口径互不串。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ArchivedReadPathTest {

    private val ws = "/Users/mac/zcode_mobile"

    private fun row(sessionId: String): JSONValue {
        val row = LinkedHashMap<String, JSONValue>()
        row["sessionId"] = JSONValue.Str(sessionId)
        row["title"] = JSONValue.Str("会话 $sessionId")
        row["lastActivityAt"] = JSONValue.Str("2026-11-09T10:00:00.000Z")
        row["workspacePath"] = JSONValue.Str(ws)
        return JSONValue.Obj(row)
    }

    @Test
    fun archivedRowsExcludedFromMainListIncludedWhenAsked() = runBlocking {
        val table = SessionMergeTable()
        table.mergeTaskIndexRows(listOf(row("sess-live"), row("sess-archived")))

        val mainList = table.toConversations(archivedIds = setOf("sess-archived"))
        assertEquals(listOf("sess-live"), mainList.map { it.id }, "主列表必须排除归档行")

        val archivedList = table.toConversations(
            archivedIds = setOf("sess-archived"),
            includeArchived = true,
        ).filter { it.isArchived }
        assertEquals(listOf("sess-archived"), archivedList.map { it.id }, "归档读面必须能取到归档行")
        assertTrue(archivedList.single().isArchived)
    }

    @Test
    fun localUnarchiveOverrideRemovesRowFromArchivedList() = runBlocking {
        val table = SessionMergeTable()
        table.mergeTaskIndexRows(listOf(row("sess-a")))
        // 取消归档 = override false 覆盖 membership true（三级合成 override 优先）
        val archived = table.toConversations(
            archivedOverrides = mapOf("sess-a" to false),
            archivedIds = setOf("sess-a"),
            includeArchived = true,
        ).filter { it.isArchived }
        assertTrue(archived.isEmpty(), "本地取消归档后该行必须离开归档读面")
    }
}

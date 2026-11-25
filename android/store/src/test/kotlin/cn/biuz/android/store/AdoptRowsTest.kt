package cn.biuz.android.store

import cn.biuz.android.protocol.JSONValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * G52 重连缓存采纳单测（iOS adoptCaches 同口径，2026-11-25）：更新者赢——
 * updatedAt（lastActivityAt）新者覆盖、平手保旧值；新表独有行不动。
 */
class AdoptRowsTest {

    private fun summary(sessionId: String, activityAt: Int?): SessionSummary =
        SessionSummaryParser.parse(
            JSONValue.Obj(
                mapOf(
                    "sessionId" to JSONValue.Str(sessionId),
                    "title" to JSONValue.Str("t-$sessionId"),
                    "phase" to JSONValue.Str("completedSuccess"),
                    "lastActivityAt" to (if (activityAt == null) JSONValue.Null else JSONValue.Int(activityAt)),
                ),
            ),
        )!!

    @Test
    fun newerWinsAndTiesKeepExisting() = runTest {
        val table = SessionMergeTable()
        table.adoptRows(
            mapOf(
                "a" to summary("a", 200), // 新表同 id 更旧 → 旧表 300 保留
                "b" to summary("b", 400), // 新表同 id 更新 → 覆盖
                "c" to summary("c", 100), // 新表独有 → 种入
            ),
        )
        val seed = mapOf("a" to summary("a", 300), "b" to summary("b", 100))
        table.adoptRows(seed)
        val snap = table.snapshot()
        assertEquals(300, snap.getValue("a").lastActivityAt)
        assertEquals(400, snap.getValue("b").lastActivityAt)
        assertEquals(100, snap.getValue("c").lastActivityAt)
    }
}

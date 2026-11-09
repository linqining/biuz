package cn.biuz.android.connection

// 自动化读面解析单测（屏 023；iOS AutomationStore.parseAutomation 同口径）：
// - 条目必带 automationId（缺席丢弃）；title 缺席回退 id；
// - 时间双形态（毫秒整数 / 秒级浮点 >1e9）；enabled 缺席 false；
// - 运行历史 runs[] | 顶层数组，runId 缺席按序号兜底，summary|lastError 宽容。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteAutomationsTest {

    @Test
    fun parsesAutomationRecordFields() {
        val record = parseAutomation(
            JSONValue.Obj(
                mapOf(
                    "automationId" to JSONValue.Str("auto-1"),
                    "title" to JSONValue.Str("每日报表"),
                    "cronExpr" to JSONValue.Str("0 9 * * *"),
                    "enabled" to JSONValue.Bool(true),
                    // 毫秒时间戳在 wire 上是 Double（Int32 值域外整数落 .double）
                    "nextRunAt" to JSONValue.Double(1_731_200_000_000.0),
                    "lastRunAt" to JSONValue.Int(1_731_190_000), // 秒级整数形态
                    "runCount" to JSONValue.Int(12),
                    "lastRunFailed" to JSONValue.Bool(false),
                ),
            ),
        )!!
        assertEquals("auto-1", record.id)
        assertEquals("每日报表", record.title)
        assertEquals("0 9 * * *", record.scheduleText)
        assertTrue(record.enabled)
        assertEquals(1_731_200_000_000L, record.nextRunAtMs)
        assertEquals(12, record.runCount)
        assertEquals(false, record.lastRunFailed)
    }

    @Test
    fun fallsBackTitleToIdAndDefaults() {
        val record = parseAutomation(JSONValue.Obj(mapOf("automationId" to JSONValue.Str("auto-2"))))!!
        assertEquals("auto-2", record.title)
        assertEquals("--", record.scheduleText)
        assertEquals(false, record.enabled)
        assertEquals(0, record.runCount)
        assertNull(record.lastRunAtMs)
        assertNull(record.lastRunFailed)
    }

    @Test
    fun parsesSecondBasedTimestamps() {
        val record = parseAutomation(
            JSONValue.Obj(
                mapOf(
                    "automationId" to JSONValue.Str("auto-3"),
                    "lastRunAt" to JSONValue.Double(1_731_190_000.0),
                ),
            ),
        )!!
        assertEquals(1_731_190_000_000L, record.lastRunAtMs)
    }

    @Test
    fun dropsEntriesWithoutAutomationId() {
        assertNull(parseAutomation(JSONValue.Obj(mapOf("title" to JSONValue.Str("x")))))
        assertNull(parseAutomation(JSONValue.Str("not-object")))
    }

    @Test
    fun parsesRunsArrayAndTopLevelFallback() {
        val wrapped = parseAutomationRuns(
            JSONValue.Obj(
                mapOf(
                    "runs" to JSONValue.Arr(
                        listOf(
                            JSONValue.Obj(
                                mapOf(
                                    "runId" to JSONValue.Str("r1"),
                                    "startedAt" to JSONValue.Double(1_731_190_000_000.0),
                                    "status" to JSONValue.Str("success"),
                                    "summary" to JSONValue.Str("ok"),
                                ),
                            ),
                            JSONValue.Obj(
                                mapOf(
                                    "status" to JSONValue.Str("failed"),
                                    "lastError" to JSONValue.Str("boom"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, wrapped.size)
        assertEquals("r1", wrapped[0].id)
        assertEquals("success", wrapped[0].status)
        assertEquals("ok", wrapped[0].summary)
        // runId 缺席按序号兜底；summary 缺席读 lastError
        assertEquals("run-1", wrapped[1].id)
        assertEquals("failed", wrapped[1].status)
        assertEquals("boom", wrapped[1].summary)

        val topLevel = parseAutomationRuns(
            JSONValue.Arr(listOf(JSONValue.Obj(mapOf("status" to JSONValue.Str("running"))))),
        )
        assertEquals(1, topLevel.size)
        assertEquals("running", topLevel[0].status)
    }

    @Test
    fun emptyRunsForNullAndGarbage() {
        assertTrue(parseAutomationRuns(null).isEmpty())
        assertTrue(parseAutomationRuns(JSONValue.Null).isEmpty())
        assertTrue(parseAutomationRuns(JSONValue.Str("nope")).isEmpty())
    }
}

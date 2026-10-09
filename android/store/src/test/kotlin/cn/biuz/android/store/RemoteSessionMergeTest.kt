package cn.biuz.android.store

import cn.biuz.android.protocol.JSONValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 场景一：bootstrap.tasks / listTaskList 跨工作区全量索引 merge——
 * 权威行纠正（交接 §4-乙-6①）+ workspaceId 禁作分组键（乙-6②）+
 * 时间字段多形态多键回退、解析失败不渲染日期（乙-9）。
 */
class RemoteSessionMergeTest {

    private val wsGhost = "/Users/mac/mtt_mobile"
    private val wsReal = "/Users/mac/zcode_mobile"
    private val wsOther = "/Users/mac/poker_protocol"

    @Test
    fun bootstrapMerge_authoritativeCorrection_ghostAttributionAndWorkspaceIdRedLine() = runBlocking {
        val table = SessionMergeTable()
        // 既有行：归属被幽灵行占据（sessions-index/缓存先到，带 lastActivityAt）
        table.applySessionsSnapshot(
            testObj(
                "logEpoch" to JSONValue.Str("epoch-1"),
                "sessions" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "sessionId" to JSONValue.Str("sess-ghost"),
                            "title" to JSONValue.Str("旧标题"),
                            "phase" to JSONValue.Str("running"),
                            "lastActivityAt" to testNum(1_791_040_344_222L), // > Int32.MAX：毫秒主形态
                            "workspacePath" to JSONValue.Str(wsGhost),
                        ),
                    ),
                ),
            ),
        )

        // bootstrap.tasks：同会话双区行（幽灵行样本，乙-6/戊-1）+ 无归属行 + workspaceId-only 行
        val stats = table.mergeBootstrapTasks(
            listOf(
                // 真实区行（后到）：必须纠正幽灵归属——「只补缺不纠正」会让错误归属永久冻结
                testObj(
                    "taskId" to JSONValue.Str("sess-ghost"),
                    "title" to JSONValue.Str("真标题"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "workspaceIdentity" to JSONValue.Str("ident-real"),
                    "status" to JSONValue.Str("running"),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
                // workspaceId-only 行（乙-6②红线）：行 workspacePath 缺失 → 禁止用 workspaceId
                // 纠正/供给归属（iOS parse 的 workspaceId 兜底分支不复刻）；不携 status
                testObj(
                    "taskId" to JSONValue.Str("sess-ghost"),
                    "workspaceId" to JSONValue.Str(wsOther),
                ),
                // 无任何归属键的行：归属 null → 「其它」组
                testObj(
                    "taskId" to JSONValue.Str("sess-nows"),
                    "title" to JSONValue.Str("无归属行"),
                    "workspaceId" to JSONValue.Str(wsOther),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
            ),
        )
        assertEquals(3, stats.rows)
        assertEquals(1, stats.merged, "仅 sess-nows 新插入；两行 sess-ghost 走纠正路径")
        assertEquals(2, stats.totalSessions, "sess-ghost + sess-nows")
        assertEquals(1, stats.distinctWorkspaces, "仅真实区行携带 workspacePath（workspaceId-only 行不计入）")

        val sessions = table.snapshot()
        val ghost = sessions["sess-ghost"]
        assertNotNull(ghost)
        assertEquals(wsReal, ghost.workspacePath, "权威行纠正：真实区行后到也必须赢（乙-6①）")
        assertEquals("running", ghost.taskStatus, "task-index 是 status 权威源，行携带即回填")
        assertEquals("旧标题", ghost.title, "bootstrap merge 不做标题回填（iOS :402-432 同口径）")
        assertEquals(1_791_040_344_222L, ghost.lastActivityAt, "既有 lastActivityAt 不被 task-index 行回退")
        assertNull(ghost.pinned, "未投影组织态保持 null")

        val nows = sessions["sess-nows"]
        assertNotNull(nows)
        assertNull(nows.workspacePath, "workspaceId-only 行：归属必须保持 null（禁 workspaceId 当分组键）")

        val conversations = table.toConversations()
        val ghostConversation = conversations.single { it.id == "sess-ghost" }
        assertEquals(wsReal, ghostConversation.directory)
        assertEquals(1_791_040_344_222L, ghostConversation.updatedAt)
        val nowsConversation = conversations.single { it.id == "sess-nows" }
        assertEquals("", nowsConversation.directory, "归属未知的行目录为空 → UI 归「其它」组")
    }

    @Test
    fun taskIndexMerge_fieldLevelBackfill_titleOverrideAndTimeGuards() = runBlocking {
        val table = SessionMergeTable()
        // 先种占位标题行（createConversation 本地种行形态；lastActivityAt 在场）
        table.applySessionsSnapshot(
            testObj(
                "sessions" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "sessionId" to JSONValue.Str("sess-new"),
                            "title" to JSONValue.Str("新会话"),
                            "phase" to JSONValue.Str("running"),
                            "lastActivityAt" to testNum(1_767_000_000_000L),
                            "workspacePath" to JSONValue.Str(wsReal),
                        ),
                    ),
                ),
            ),
        )
        // task-index 行：桌面自动命名到达（mergeGlobalTaskIndex 行段——字段级回填）
        val (merged, backfilled) = table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-new"),
                    "title" to JSONValue.Str("桌面自动命名"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "status" to JSONValue.Str("completed"),
                    "updatedAt" to testNum(1_766_000_000_000L), // 旧于 sessions-index 实时值
                ),
            ),
        )
        assertEquals(0, merged)
        assertEquals(1, backfilled)
        val row = table.snapshot().getValue("sess-new")
        assertEquals("桌面自动命名", row.title, "占位标题被 task-index 权威回填（v1.27 报障根修）")
        assertEquals("completed", row.taskStatus)
        assertEquals(1_767_000_000_000L, row.lastActivityAt, "活跃时间仅缺值时补：sessions-index 实时值不回退 task-index（防排序抖动）")

        // 本地改名 override 让位：override 在场时服务端标题不覆盖投影（显示层同让位）
        val (merged2, backfilled2) = table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-new"),
                    "title" to JSONValue.Str("服务端又改了一次"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
            ),
            titleOverriddenIds = setOf("sess-new"),
        )
        assertEquals(0, merged2)
        assertEquals(0, backfilled2, "本地改名 override 在场：标题回填跳过（不剥 override）")
        assertEquals("桌面自动命名", table.snapshot().getValue("sess-new").title)
        val projected = table.toConversations(titleOverrides = mapOf("sess-new" to "本地改名"))
        assertEquals("本地改名", projected.single().title)
    }

    @Test
    fun timeParsing_multiKeyFallback_isoAndFailureMeansNoDate() = runBlocking {
        // 多键回退：lastActivityAt 缺失 → updatedAt → createdAt（bootstrap.tasks 行无 lastActivityAt——
        // 上游 zcodeTaskMetaSchema validation.ts:1205 实证；缺回退曾渲染「2025年前」）
        val isoMs = Instant.parse("2026-01-15T08:30:00.000Z").toEpochMilli()
        val parsed = SessionSummaryParser.parse(
            testObj(
                "sessionId" to JSONValue.Str("sess-iso"),
                "title" to JSONValue.Str("ISO 行"),
                "phase" to JSONValue.Str("completedSuccess"),
                "updatedAt" to JSONValue.Str("2026-01-15T08:30:00.000Z"), // ISO 字符串形态（替身双形态）
                "createdAt" to testNum(1_000_000_000_000L),
            ),
        )
        assertNotNull(parsed)
        assertEquals(isoMs, parsed.lastActivityAt, "updatedAt ISO 字符串形态解析（带小数秒）")

        val createdAtFallback = SessionSummaryParser.parse(
            testObj(
                "sessionId" to JSONValue.Str("sess-iso2"),
                "createdAt" to JSONValue.Str("2026-01-15T16:30:00+08:00"), // 非 UTC 偏移形态
            ),
        )
        assertNotNull(createdAtFallback)
        assertEquals(isoMs, createdAtFallback.lastActivityAt, "createdAt 键回退 + 偏移量 ISO 解析")

        // 解析失败 → null（不渲染日期）：全部时间键不可解析
        val bad = SessionSummaryParser.parse(
            testObj(
                "sessionId" to JSONValue.Str("sess-bad"),
                "updatedAt" to JSONValue.Str("not-a-date"),
            ),
        )
        assertNotNull(bad)
        assertNull(bad.lastActivityAt)
        val badTable = SessionMergeTable()
        badTable.mergeBootstrapTasks(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-bad"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "updatedAt" to JSONValue.Str("not-a-date"),
                ),
            ),
        )
        val projected = badTable.toConversations().single()
        assertEquals(Long.MIN_VALUE, projected.updatedAt, "解析失败 → distantPast 对应物，排序沉底、UI 不渲染日期")

        // 小数/零毫秒不采（longValue 整值 double 口径）
        assertNull(SessionSummaryParser.parseLastActivityAt(mapOf("lastActivityAt" to JSONValue.Double(1.5))))
        assertNull(SessionSummaryParser.parseLastActivityAt(mapOf("lastActivityAt" to testNum(0L))))
        assertEquals(
            1_791_040_344_222L,
            SessionSummaryParser.parseLastActivityAt(mapOf("lastActivityAt" to testNum(1_791_040_344_222L))),
        )
    }
}

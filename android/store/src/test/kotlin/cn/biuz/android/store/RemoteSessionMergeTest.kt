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
    fun bootstrapMerge_fillIfNil_workspaceIdRedLineAndStatusBackfill() = runBlocking {
        val table = SessionMergeTable()
        // 既有行：归属已定（早期来源种行；带 lastActivityAt）
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

        // bootstrap.tasks：同会话双区行（幽灵行样本，乙-6/戊-1）+ 无归属行 + workspaceId-only 行。
        // 2026-11-23 修订：bootstrap 是随快照无限重放的最陈旧源（3.15.1+ 桌面已无此字段），
        // workspacePath 只补缺不覆盖——「权威行后到必须赢」由 mergeTaskIndexRows 承担（见下测）。
        val stats = table.mergeBootstrapTasks(
            listOf(
                // 真实区行（后到）：**不得**覆盖既有归属（陈旧源防翻面；真实迁移伴随更新的
                // updatedAt，由 task-index 行覆盖）
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
        assertEquals(1, stats.merged, "仅 sess-nows 新插入；两行 sess-ghost 走补缺路径")
        assertEquals(2, stats.totalSessions, "sess-ghost + sess-nows")
        assertEquals(1, stats.distinctWorkspaces, "仅真实区行携带 workspacePath（workspaceId-only 行不计入）")

        val sessions = table.snapshot()
        val ghost = sessions["sess-ghost"]
        assertNotNull(ghost)
        assertEquals(wsGhost, ghost.workspacePath, "bootstrap 只补缺：既有归属不被陈旧重放源覆盖（2026-11-23 修订）")
        assertEquals("running", ghost.taskStatus, "task-index 是 status 权威源，行携带即回填")
        assertEquals("旧标题", ghost.title, "bootstrap merge 不做标题回填（iOS :402-432 同口径）")
        assertEquals(1_791_040_344_222L, ghost.lastActivityAt, "既有 lastActivityAt 不被 task-index 行回退")
        assertNull(ghost.pinned, "未投影组织态保持 null")

        val nows = sessions["sess-nows"]
        assertNotNull(nows)
        assertNull(nows.workspacePath, "workspaceId-only 行：归属必须保持 null（禁 workspaceId 当分组键）")

        val conversations = table.toConversations()
        val ghostConversation = conversations.single { it.id == "sess-ghost" }
        assertEquals(wsGhost, ghostConversation.directory)
        assertEquals(1_791_040_344_222L, ghostConversation.updatedAt)
        val nowsConversation = conversations.single { it.id == "sess-nows" }
        assertEquals("", nowsConversation.directory, "归属未知的行目录为空 → UI 归「其它」组")

        // 权威行纠正（乙-6①）转移到 task-index 通道：更新的 updatedAt 必须赢
        table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-ghost"),
                    "title" to JSONValue.Str("真标题"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "status" to JSONValue.Str("completed"),
                    "updatedAt" to testNum(1_791_050_000_000L), // 严格新于既有 1_791_040_344_222
                ),
            ),
        )
        assertEquals(wsReal, table.snapshot().getValue("sess-ghost").workspacePath, "乙-6①：task-index 行携带更新时间戳时纠正既有归属")
    }

    @Test
    fun taskIndexMerge_batchDedupeAndGhostFlipFlopGuard() = runBlocking {
        val table = SessionMergeTable()
        // 真机「项目只有一个命名空间」回归（2026-11-23）：批内同任务双行（host-scope 把
        // 幽灵行一并带回）——平手保首个，先合并的归属区行压过尾部当前区幽灵行
        val (merged, _) = table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-dup"),
                    "title" to JSONValue.Str("真区行"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
                testObj(
                    "taskId" to JSONValue.Str("sess-dup"),
                    "title" to JSONValue.Str("幽灵区行"),
                    "workspacePath" to JSONValue.Str(wsGhost),
                    "updatedAt" to testNum(1_767_000_000_000L), // 平手
                ),
            ),
        )
        assertEquals(1, merged, "批内同任务两行去重为一行")
        assertEquals(wsReal, table.snapshot().getValue("sess-dup").workspacePath, "平手保首个：幽灵行（批尾）不坐实错误归属")

        // 批内 updatedAt 新者赢
        table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-dup2"),
                    "title" to JSONValue.Str("旧行"),
                    "workspacePath" to JSONValue.Str(wsGhost),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
                testObj(
                    "taskId" to JSONValue.Str("sess-dup2"),
                    "title" to JSONValue.Str("新行"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "updatedAt" to testNum(1_768_000_000_000L), // 严格新
                ),
            ),
        )
        assertEquals(wsReal, table.snapshot().getValue("sess-dup2").workspacePath, "批内 updatedAt 新者赢")

        // 跨批翻面守卫：既有归属在场、后到批为同 updatedAt 的幽灵行 → 保现状（防 120s
        // 节流回填把已定归属反复翻回幽灵路径；fork/移动等真实迁移必然带更新时间戳）
        table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-dup"),
                    "title" to JSONValue.Str("幽灵区行"),
                    "workspacePath" to JSONValue.Str(wsGhost),
                    "updatedAt" to testNum(1_767_000_000_000L), // 与既有平手
                ),
            ),
        )
        assertEquals(wsReal, table.snapshot().getValue("sess-dup").workspacePath, "平手后到批不得翻面")

        // 跨批真实迁移：更新的 updatedAt 照常覆盖（乙-6① 不受阻）
        table.mergeTaskIndexRows(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-dup"),
                    "title" to JSONValue.Str("真区行"),
                    "workspacePath" to JSONValue.Str(wsGhost),
                    "updatedAt" to testNum(1_769_000_000_000L), // 严格新：模拟会话迁移后新活动
                ),
            ),
        )
        assertEquals(wsGhost, table.snapshot().getValue("sess-dup").workspacePath, "严格新 updatedAt 的行照常覆盖（真实迁移）")
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
    fun snapshotPreservesBootstrapAttribution_andReappliesRetainedRows() = runBlocking {
        val table = SessionMergeTable()
        // bootstrap 先到：两行带真实归属
        table.mergeBootstrapTasks(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-a"),
                    "title" to JSONValue.Str("A"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
                testObj(
                    "taskId" to JSONValue.Str("sess-b"),
                    "title" to JSONValue.Str("B"),
                    "workspacePath" to JSONValue.Str(wsOther),
                    "updatedAt" to testNum(1_766_000_000_000L),
                ),
            ),
        )
        // sessions-index 快照整表替换（真实行形态：只有 workspaceId，无 workspacePath）
        table.applySessionsSnapshot(
            testObj(
                "sessions" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "sessionId" to JSONValue.Str("sess-a"),
                            "title" to JSONValue.Str("A2"),
                            "phase" to JSONValue.Str("running"),
                            "workspaceId" to JSONValue.Str("w-1"),
                            "lastActivityAt" to testNum(1_768_000_000_000L),
                        ),
                        testObj(
                            "sessionId" to JSONValue.Str("sess-c"),
                            "title" to JSONValue.Str("C"),
                            "workspaceId" to JSONValue.Str("w-2"),
                        ),
                    ),
                ),
            ),
        )
        val sessions = table.snapshot()
        assertEquals(
            wsReal,
            sessions.getValue("sess-a").workspacePath,
            "快照整表替换后归属保全：workspacePath 缺失沿用既有归属（列表分组根因修复）",
        )
        assertEquals("A2", sessions.getValue("sess-a").title, "快照标题为权威，不被 bootstrap 留存行覆盖")
        assertNull(
            sessions.getValue("sess-c").workspacePath,
            "bootstrap 未覆盖的新行：workspaceId 仍不得当分组键（乙-6②）",
        )
        assertEquals(
            wsOther,
            sessions.getValue("sess-b").workspacePath,
            "bootstrap 留存行在快照后重放（跨工作区行不因快照缺席丢失）",
        )
    }

    @Test
    fun deltaUpsert_preservesAttribution_andBackfillsNewRowFromRetainedBootstrap() = runBlocking {
        val table = SessionMergeTable()
        table.mergeBootstrapTasks(
            listOf(
                testObj(
                    "taskId" to JSONValue.Str("sess-x"),
                    "title" to JSONValue.Str("X"),
                    "workspacePath" to JSONValue.Str(wsReal),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
                testObj(
                    "taskId" to JSONValue.Str("sess-y"),
                    "title" to JSONValue.Str("Y"),
                    "workspacePath" to JSONValue.Str(wsOther),
                    "updatedAt" to testNum(1_767_000_000_000L),
                ),
            ),
        )
        // 增量更新既有行（无 workspacePath）
        table.applySessionDelta(
            testObj(
                "op" to JSONValue.Str("session.upserted"),
                "session" to testObj(
                    "sessionId" to JSONValue.Str("sess-x"),
                    "title" to JSONValue.Str("X2"),
                    "phase" to JSONValue.Str("running"),
                ),
            ),
        )
        assertEquals(
            wsReal,
            table.snapshot().getValue("sess-x").workspacePath,
            "增量整行替换后归属保全",
        )
        // 增量新行：bootstrap 留存行里已有归属 → 重放补齐
        table.applySessionDelta(
            testObj(
                "op" to JSONValue.Str("session.upserted"),
                "session" to testObj(
                    "sessionId" to JSONValue.Str("sess-y"),
                    "title" to JSONValue.Str("Y2"),
                    "phase" to JSONValue.Str("completedSuccess"),
                ),
            ),
        )
        assertEquals(
            wsOther,
            table.snapshot().getValue("sess-y").workspacePath,
            "新行缺归属 → 用 bootstrap 留存行补齐（不覆盖快照标题）",
        )
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

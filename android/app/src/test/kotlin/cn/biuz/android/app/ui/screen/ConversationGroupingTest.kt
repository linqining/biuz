package cn.biuz.android.app.ui.screen

import cn.biuz.android.connection.ConnectError
import cn.biuz.android.protocol.Conversation
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 会话列表分组 + 行时间标注 + L3 失败对照（M2 界面轮纯函数单测）。 */
class ConversationGroupingTest {

    private val todayStart = 1_700_000_000_000L
    private val yesterdayStart = todayStart - 24 * 60 * 60 * 1_000L

    private fun row(
        id: String,
        updatedAt: Long,
        pinned: Boolean = false,
    ) = Conversation(
        id = id,
        title = "会话-$id",
        summary = "",
        directory = "",
        updatedAt = updatedAt,
        isPinned = pinned,
    )

    @Test
    fun groupsPinnedFirstThenTodayYesterdayEarlier() {
        val rows = listOf(
            row("newer", todayStart + 100),
            row("older", todayStart + 50),
            row("pinned-early", todayStart - 10 * 24 * 60 * 60 * 1_000L, pinned = true),
            row("yesterday", yesterdayStart + 60),
            row("last-week", yesterdayStart - 1),
        )
        val sections = groupConversationsByRecency(rows, todayStart, yesterdayStart)
        assertEquals(listOf("已置顶", "今天", "昨天", "更早"), sections.map { it.label })
        // 置顶组含过期行（置顶优先于时间分组）
        assertEquals(listOf("pinned-early"), sections[0].rows.map { it.id })
        assertEquals(listOf("newer", "older"), sections[1].rows.map { it.id })
        assertEquals(listOf("yesterday"), sections[2].rows.map { it.id })
        assertEquals(listOf("last-week"), sections[3].rows.map { it.id })
    }

    @Test
    fun emptySectionsAreOmitted() {
        val sections = groupConversationsByRecency(
            listOf(row("today-only", todayStart + 1)),
            todayStart,
            yesterdayStart,
        )
        assertEquals(listOf("今天"), sections.map { it.label })
    }

    @Test
    fun unparseableTimeSinksToEarlierWithoutLabel() {
        // Store 投影对解析失败行沉底 updatedAt=Long.MIN_VALUE（乙-9）
        val rows = listOf(row("broken", Long.MIN_VALUE))
        val sections = groupConversationsByRecency(rows, todayStart, yesterdayStart)
        assertEquals(listOf("更早"), sections.map { it.label })
        assertEquals("", conversationRowTimeLabel(Long.MIN_VALUE, todayStart, yesterdayStart, java.text.DateFormat.getTimeInstance()))
        assertEquals("", conversationRowTimeLabel(0, todayStart, yesterdayStart, java.text.DateFormat.getTimeInstance()))
    }

    @Test
    fun rowTimeLabelBuckets() {
        val withinToday = todayStart + 3 * 60 * 60 * 1_000L
        assertTrue(conversationRowTimeLabel(withinToday, todayStart, yesterdayStart, java.text.SimpleDateFormat("HH:mm", Locale.CHINA)).isNotEmpty())
        assertEquals("昨天", conversationRowTimeLabel(yesterdayStart + 1, todayStart, yesterdayStart, java.text.SimpleDateFormat("HH:mm", Locale.CHINA)))
        assertTrue(
            conversationRowTimeLabel(yesterdayStart - 1, todayStart, yesterdayStart, java.text.SimpleDateFormat("HH:mm", Locale.CHINA))
                .endsWith("日"),
        )
    }

    @Test
    fun projectSectionsPlacePinnedFirstThenProjectsThenUngrouped() {
        val rows = listOf(
            row("pinned-1", todayStart - 10 * 24 * 60 * 60 * 1_000L, pinned = true),
            Conversation("a-old", "A 旧", "", "/work/alpha", todayStart - 3 * 24 * 60 * 60 * 1_000L),
            Conversation("a-new", "A 新", "", "/work/alpha", todayStart - 60_000L),
            Conversation("b-1", "B 一", "", "/work/beta", todayStart - 24 * 60 * 60 * 1_000L),
            Conversation("free-1", "无项目", "", "", todayStart - 30_000L),
        )
        val sections = projectSections(rows, "已置顶", "任务")
        assertEquals(listOf("已置顶", "alpha", "beta", "任务"), sections.map { it.title })
        assertEquals(listOf("pinned-1"), sections[0].rows.map { it.id })
        assertEquals(listOf("a-new", "a-old"), sections[1].rows.map { it.id })
        assertEquals(listOf("free-1"), sections[3].rows.map { it.id })
    }

    @Test
    fun timelineSectionsDoNotBucketByDate() {
        val rows = listOf(
            row("early", todayStart - 10 * 24 * 60 * 60 * 1_000L),
            row("late", todayStart + 100),
        )
        val sections = timelineSections(rows, "已置顶", "最近更新")
        assertEquals(listOf("最近更新"), sections.map { it.title })
        assertEquals(listOf("late", "early"), sections[0].rows.map { it.id })
    }

    @Test
    fun unreadSectionsKeepPinnedAndFilterUnread() {
        val rows = listOf(
            row("pinned-1", todayStart, pinned = true),
            Conversation("unread-1", "未读", "", "", todayStart, unreadCount = 2),
            Conversation("read-1", "已读", "", "", todayStart),
        )
        val sections = unreadSections(rows, "已置顶", "未读", "暂无未读会话")
        assertEquals(listOf("已置顶", "未读"), sections.map { it.title })
        assertEquals(listOf("unread-1"), sections[1].rows.map { it.id })

        val empty = unreadSections(listOf(Conversation("read", "已读", "", "", todayStart)), "已置顶", "未读", "暂无未读会话")
        assertEquals(listOf("暂无未读会话"), empty.map { it.title })
        assertTrue(empty[0].rows.isEmpty())
    }

    @Test
    fun failureGuidanceCoversL3ErrorFamilies() {
        // 双语稳健口径（§5-18③ 同纪律）：断言提示种类枚举（ConnectFailureHint），不再断言
        // zh 文案词面——en 资源态下文案词面断言必翻车。
        // 401 = 令牌不匹配（重扫对照）；超时必含局域网检查项（本地网络静默失败不可区分的兜底）
        val http401 = connectFailureHints(ConnectError.Http(401, "GET /api/server-info"))
        assertTrue(http401.first() == ConnectFailureHint.TokenMismatch, "401 首条=令牌不匹配种类：$http401")
        assertTrue(http401.getOrNull(1) is ConnectFailureHint.RejectedEndpoint, "401 附被拒端点行")

        val http500 = connectFailureHints(ConnectError.Http(500, "GET /api/server-info"))
        assertTrue(http500.single() is ConnectFailureHint.Http, "非 401 HTTP = 状态行")

        val timeout = connectFailureHints(ConnectError.Timeout("GET /api/server-info"))
        assertTrue(ConnectFailureHint.CheckLan in timeout, "超时必含局域网检查项种类")
        assertTrue(ConnectFailureHint.CheckHostFlag in timeout)
        assertTrue(ConnectFailureHint.IsolationVpn in timeout)

        val protocol = connectFailureHints(ConnectError.ProtocolVersion("remote v2"))
        assertTrue(protocol.single() == ConnectFailureHint.ProtocolVersion)

        val emptyWs = connectFailureHints(ConnectError.EmptyWorkspaces)
        assertTrue(emptyWs.single() == ConnectFailureHint.EmptyWorkspace)

        val handshake = connectFailureHints(ConnectError.HandshakeFailed("reject detail"))
        assertTrue(handshake.single() == ConnectFailureHint.Handshake)

        val transport = connectFailureHints(ConnectError.Transport("conn refused"))
        assertTrue(transport.single() == ConnectFailureHint.Transport)
    }
}

/**
 * 两级看板（web project-sidebar Projects/Tasks 同构）：置顶 → 项目节点 → 任务；
 * 节点键 = 归属目录全路径；行序 = 运行中置顶 → 活跃时间降序。
 */
class ProjectBoardTest {

    private fun row(
        id: String,
        directory: String,
        updatedAt: Long,
        pinned: Boolean = false,
        running: Boolean = false,
        unread: Int = 0,
    ) = Conversation(
        id = id,
        title = "会话-$id",
        summary = "",
        directory = directory,
        updatedAt = updatedAt,
        isPinned = pinned,
        isRunning = running,
        unreadCount = unread,
    )

    @Test
    fun boardSplitsPinnedProjectsAndTasks() {
        val rows = listOf(
            row("p1-a", "/Users/mac/projects/alpha", 100),
            row("p1-b", "/Users/mac/projects/alpha", 200),
            row("p2-a", "/Users/mac/projects/beta", 150),
            row("task-a", "", 300),
            row("pin-a", "/Users/mac/projects/beta", 50, pinned = true),
        )
        val board = projectBoard(rows)
        assertEquals(listOf("pin-a"), board.pinned.map { it.id })
        assertEquals(
            listOf("/Users/mac/projects/alpha", "/Users/mac/projects/beta"),
            board.projects.map { it.key },
            "节点键 = 目录全路径（去重键）；组间按组内最新降序（alpha max 200 > beta max 150）",
        )
        assertEquals(listOf("alpha", "beta"), board.projects.map { it.title }, "标题 = 目录末段")
        assertEquals(listOf("p1-b", "p1-a"), board.projects[0].rows.map { it.id }, "组内按活跃时间降序")
        assertEquals(listOf("task-a"), board.tasks.map { it.id }, "无归属行落任务区（置顶行不计入）")
    }

    @Test
    fun runningRowsFloatToTopWithinGroups() {
        val rows = listOf(
            row("idle-new", "/Users/mac/projects/alpha", 900),
            row("running-old", "/Users/mac/projects/alpha", 100, running = true),
        )
        val board = projectBoard(rows)
        assertEquals(
            listOf("running-old", "idle-new"),
            board.projects.single().rows.map { it.id },
            "运行中置顶（web compareTaskListItemsWithRunningFirst 子集）",
        )
        assertEquals(1, board.projects.single().runningCount)
    }

    @Test
    fun unreadCountAggregatesForNodeBadge() {
        val rows = listOf(
            row("a", "/Users/mac/projects/alpha", 100, unread = 2),
            row("b", "/Users/mac/projects/alpha", 200, unread = 1),
            row("c", "/Users/mac/projects/alpha", 300),
        )
        val group = projectBoard(rows).projects.single()
        assertEquals(2, group.unreadCount, "未读汇总 = 组内有未读行数")
    }
}

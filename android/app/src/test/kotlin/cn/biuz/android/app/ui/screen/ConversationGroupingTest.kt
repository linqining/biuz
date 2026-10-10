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
        assertEquals(listOf("置顶", "今天", "昨天", "更早"), sections.map { it.label })
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

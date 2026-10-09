package cn.biuz.android.app.ui.screen

import cn.biuz.android.protocol.Conversation
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表分组（design-spec 屏 04：置顶 → 今天 → 昨天 → 更早；组内倒序由 Store 投影保证——
 * RemoteSessionMerge.toConversations 已按 isPinned desc + updatedAt desc 排序并滤归档行）。
 * 纯函数（边界毫秒注入）：JVM 单测回灌分组正确性。
 */
internal data class ConversationSection(val label: String, val rows: List<Conversation>)

internal const val SECTION_PINNED = "置顶"
internal const val SECTION_TODAY = "今天"
internal const val SECTION_YESTERDAY = "昨天"
internal const val SECTION_EARLIER = "更早"

internal fun groupConversationsByRecency(
    rows: List<Conversation>,
    todayStartMs: Long,
    yesterdayStartMs: Long,
): List<ConversationSection> {
    val pinned = rows.filter { it.isPinned }
    val rest = rows.filter { !it.isPinned }
    val sections = ArrayList<ConversationSection>(4)
    if (pinned.isNotEmpty()) sections.add(ConversationSection(SECTION_PINNED, pinned))
    val today = rest.filter { it.updatedAt >= todayStartMs }
    if (today.isNotEmpty()) sections.add(ConversationSection(SECTION_TODAY, today))
    val yesterday = rest.filter { it.updatedAt >= yesterdayStartMs && it.updatedAt < todayStartMs }
    if (yesterday.isNotEmpty()) sections.add(ConversationSection(SECTION_YESTERDAY, yesterday))
    val earlier = rest.filter { it.updatedAt < yesterdayStartMs }
    if (earlier.isNotEmpty()) sections.add(ConversationSection(SECTION_EARLIER, earlier))
    return sections
}

/**
 * 行时间标注：今天 → HH:mm；昨天 → 「昨天」；更早 → M月d日；无时间（解析失败沉底
 * updatedAt=Long.MIN_VALUE）→ 空串不渲染（乙-9 口径）。
 */
internal fun conversationRowTimeLabel(
    updatedAtMs: Long,
    todayStartMs: Long,
    yesterdayStartMs: Long,
    timeFormat: DateFormat,
): String = when {
    updatedAtMs <= 0 -> ""
    updatedAtMs >= todayStartMs -> timeFormat.format(Date(updatedAtMs))
    updatedAtMs >= yesterdayStartMs -> SECTION_YESTERDAY
    else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(updatedAtMs))
}

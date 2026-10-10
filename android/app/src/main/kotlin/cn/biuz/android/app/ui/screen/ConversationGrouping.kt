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
 *
 * 分组标题双语对账（设置里程碑）：[SectionKind] 是渲染面权威（SessionsScreen 经
 * stringResource 取组标题），[ConversationSection.label] 保留为 zh 参考词面（单测
 * 断言与回退兜底）——禁在 UI 层按 label 字符串匹配。
 */
internal enum class SectionKind {
    PINNED,
    TODAY,
    YESTERDAY,
    EARLIER,
}

internal data class ConversationSection(
    val kind: SectionKind,
    val rows: List<Conversation>,
) {
    /** zh 参考词面（单测断言/回退兜底；UI 渲染走 kind → 资源）。 */
    val label: String
        get() = when (kind) {
            SectionKind.PINNED -> SECTION_PINNED
            SectionKind.TODAY -> SECTION_TODAY
            SectionKind.YESTERDAY -> SECTION_YESTERDAY
            SectionKind.EARLIER -> SECTION_EARLIER
        }
}

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
    if (pinned.isNotEmpty()) sections.add(ConversationSection(SectionKind.PINNED, pinned))
    val today = rest.filter { it.updatedAt >= todayStartMs }
    if (today.isNotEmpty()) sections.add(ConversationSection(SectionKind.TODAY, today))
    val yesterday = rest.filter { it.updatedAt >= yesterdayStartMs && it.updatedAt < todayStartMs }
    if (yesterday.isNotEmpty()) sections.add(ConversationSection(SectionKind.YESTERDAY, yesterday))
    val earlier = rest.filter { it.updatedAt < yesterdayStartMs }
    if (earlier.isNotEmpty()) sections.add(ConversationSection(SectionKind.EARLIER, earlier))
    return sections
}

/**
 * 行时间标注：今天 → HH:mm；昨天 → 「昨天」（[yesterdayLabel] 注入，UI 传资源文案）；
 * 更早 → M月d日（[earlierPattern] 注入，en "MMM d"）；无时间（解析失败沉底
 * updatedAt=Long.MIN_VALUE）→ 空串不渲染（乙-9 口径）。
 */
internal fun conversationRowTimeLabel(
    updatedAtMs: Long,
    todayStartMs: Long,
    yesterdayStartMs: Long,
    timeFormat: DateFormat,
    yesterdayLabel: String = SECTION_YESTERDAY,
    earlierPattern: String = "M月d日",
): String = when {
    updatedAtMs <= 0 -> ""
    updatedAtMs >= todayStartMs -> timeFormat.format(Date(updatedAtMs))
    updatedAtMs >= yesterdayStartMs -> yesterdayLabel
    else -> SimpleDateFormat(earlierPattern, Locale.getDefault()).format(Date(updatedAtMs))
}

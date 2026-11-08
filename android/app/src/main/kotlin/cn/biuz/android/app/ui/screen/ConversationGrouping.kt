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

internal const val SECTION_PINNED = "已置顶"
internal const val SECTION_TODAY = "今天"
internal const val SECTION_YESTERDAY = "昨天"
internal const val SECTION_EARLIER = "更早"

/**
 * 列表视图四档 —— 对齐 web `ProjectSidebarListHeader.tsx:87-102`
 * （按项目 / 按分组 / 最近更新 / 按未读）。
 */
internal enum class ListViewMode { PROJECTS, GROUPED, TIMELINE, UNREAD }

/** 渲染用分区（标题 + 行；标题由调用方给已本地化文案）。 */
internal data class ListSection(val title: String, val rows: List<Conversation>)

/**
 * 按项目分组：置顶恒在最前 → 项目组（组间按组内最新降序）→ 未归属「任务」组。
 * web 口径：`project-sidebar/index.tsx:252-318`（pinned 先渲染；projects 视图按项目分，
 * 无项目的任务落 conversation-section「任务」）。
 */
internal fun projectSections(
    rows: List<Conversation>,
    pinnedTitle: String,
    ungroupedTitle: String,
): List<ListSection> {
    val pinned = rows.filter { it.isPinned }.sortedByDescending { it.updatedAt }
    val rest = rows.filter { !it.isPinned }
    val out = ArrayList<ListSection>()
    if (pinned.isNotEmpty()) out.add(ListSection(pinnedTitle, pinned))
    rest.filter { !it.projectName.isNullOrBlank() }
        .groupBy { it.projectName!! }
        .entries
        .sortedByDescending { entry -> entry.value.maxOf { it.updatedAt } }
        .forEach { entry ->
            out.add(ListSection(entry.key, entry.value.sortedByDescending { it.updatedAt }))
        }
    val ungrouped = rest.filter { it.projectName.isNullOrBlank() }.sortedByDescending { it.updatedAt }
    if (ungrouped.isNotEmpty()) out.add(ListSection(ungroupedTitle, ungrouped))
    return out
}

/** 最近更新：单列倒序，不做日期分桶（web `ProjectTaskRows kind=timeline`）。 */
internal fun timelineSections(rows: List<Conversation>, pinnedTitle: String, title: String): List<ListSection> {
    val pinned = rows.filter { it.isPinned }.sortedByDescending { it.updatedAt }
    val rest = rows.filter { !it.isPinned }.sortedByDescending { it.updatedAt }
    val out = ArrayList<ListSection>()
    if (pinned.isNotEmpty()) out.add(ListSection(pinnedTitle, pinned))
    if (rest.isNotEmpty()) out.add(ListSection(title, rest))
    return out
}

/** 按未读：只保留未读（置顶仍独立成区）。 */
internal fun unreadSections(
    rows: List<Conversation>,
    pinnedTitle: String,
    title: String,
    emptyTitle: String,
): List<ListSection> {
    val pinned = rows.filter { it.isPinned }.sortedByDescending { it.updatedAt }
    val unread = rows.filter { !it.isPinned && it.unreadCount > 0 }.sortedByDescending { it.updatedAt }
    val out = ArrayList<ListSection>()
    if (pinned.isNotEmpty()) out.add(ListSection(pinnedTitle, pinned))
    out.add(ListSection(if (unread.isEmpty()) emptyTitle else title, unread))
    return out
}

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

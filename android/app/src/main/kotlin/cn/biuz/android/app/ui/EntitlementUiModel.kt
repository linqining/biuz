package cn.biuz.android.app.ui

import cn.biuz.android.connection.CodingPlanEntitlementInfo
import cn.biuz.android.connection.CodingPlanQuotaWindow
import cn.biuz.android.connection.CodingPlanResetCards
import cn.biuz.android.connection.CodingPlanUsageInfo

/**
 * 权益页（用量统计）展示投影——纯逻辑，零 Android 依赖（JVM 单测锁定形状契约）。
 * 对位 iOS UsageStatsView（P2ExtrasViews.swift）三分支：codingPlanCard / entitlementSection /
 * resetCard。字符串装配归 UI 层（stringResource），本面只做形状判定与截取。
 *
 * 数据面契约（B-5 否定性结论，RemoteUsageStatsStore.KDoc 同源）：
 * **禁找不存在的 entitlements[]**——条目唯一来源 = subscription.details[]；
 * 档位 = quota.level（回落首条 productName，web IAt 同构）；
 * 顶层 remaining 仅在场判定（单位语义未取证，原样透出不加解释）。
 */
object EntitlementUiModel {

    /** 权益行截取上限（iOS entitlementSection prefix(8) 同数）。 */
    const val ENTITLEMENT_ROWS_LIMIT = 8

    /** 剩余额度低水位（进度条转橙阈值，iOS quotaWindowRow remaining < 0.15 同数）。 */
    const val LOW_REMAINING = 0.15

    /** 权益子块渲染形态（iOS entitlementSection 三分支 @ViewBuilder 直译）。 */
    data class EntitlementSection(
        /** true = 未订阅空态单行（unavailableReason == "no_plan"，web sidebar.usage.plan.noPlan 同位）。 */
        val noPlan: Boolean,
        /** 档位头（quota.level；缺席回落首条 productName——web IAt 同构）。 */
        val tier: String?,
        /** 顶层 remaining 原样透出（在场判定；null/空 = 不渲染「剩 X」）。 */
        val remaining: String?,
        val rows: List<EntitlementRow>,
        /** > 0 = 追加「在桌面端查看全部」尾行（rows 截取后的剩余条数）。 */
        val moreCount: Int,
        /** true = 投影为 null（读取失败/未取到）→「权益信息不可用」诚实行。 */
        val unavailable: Boolean,
    ) {
        /** 主块可见 = 有行可渲染（noPlan/unavailable 走各自单行，其余空回执整块不渲染）。 */
        val hasRows: Boolean get() = rows.isNotEmpty()
    }

    data class EntitlementRow(
        val name: String,
        val value: String?,
        val detail: String?,
    )

    fun entitlementSection(info: CodingPlanEntitlementInfo?): EntitlementSection {
        if (info == null) {
            return EntitlementSection(
                noPlan = false, tier = null, remaining = null,
                rows = emptyList(), moreCount = 0, unavailable = true,
            )
        }
        val rows = info.entitlements.map { EntitlementRow(it.name, it.value, it.detail) }
        return EntitlementSection(
            noPlan = info.noPlan,
            tier = info.tier?.takeIf { it.isNotEmpty() },
            remaining = info.remainingText?.takeIf { it.isNotEmpty() },
            rows = rows.take(ENTITLEMENT_ROWS_LIMIT),
            moreCount = (rows.size - ENTITLEMENT_ROWS_LIMIT).coerceAtLeast(0),
            unavailable = false,
        )
    }

    /**
     * 主额度窗（legacy 字段/用户卡口径）：5 小时窗优先、缺席取首窗（iOS
     * SettingsView usageDetailText 主窗取数同构——5 小时窗为主窗口）。
     */
    fun primaryWindow(usage: CodingPlanUsageInfo): CodingPlanQuotaWindow? {
        if (usage.windows.isEmpty()) return null
        return usage.windows.firstOrNull { it.label == PRIMARY_WINDOW_LABEL } ?: usage.windows.first()
    }

    /** 窗口行剩余小数（0–1；无百分比数据 = null → 进度条与「剩 N%」不渲染）。 */
    fun windowRemainingFraction(window: CodingPlanQuotaWindow): Double? = window.percentRemaining

    /** 进度条低水位转橙判定（iOS ThinProgressBar tint 分支同数）。 */
    fun isLowRemaining(remainingFraction: Double): Boolean = remainingFraction < LOW_REMAINING

    /** 重置机会卡可见（两组卡任一有存量；iOS resetOpportunity 门以 getCodingPlanResetStatus 组数为准）。 */
    fun hasResetOpportunity(cards: CodingPlanResetCards?): Boolean =
        (cards?.fiveHourCount ?: 0) > 0 || (cards?.weekCount ?: 0) > 0

    /** 重置卡摘要可见（额度卡内两行：任一 > 0）。 */
    fun hasResetCardsSummary(cards: CodingPlanResetCards?): Boolean = hasResetOpportunity(cards)

    /** 主窗口标签（RemoteUsageStatsStore.WINDOW_LABEL_5H 同词——投影面零依赖改内联常量）。 */
    const val PRIMARY_WINDOW_LABEL = "5 小时"
}

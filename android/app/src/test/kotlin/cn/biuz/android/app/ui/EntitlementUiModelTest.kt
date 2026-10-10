package cn.biuz.android.app.ui

import cn.biuz.android.connection.CodingPlanEntitlement
import cn.biuz.android.connection.CodingPlanEntitlementInfo
import cn.biuz.android.connection.CodingPlanQuotaWindow
import cn.biuz.android.connection.CodingPlanResetCards
import cn.biuz.android.connection.CodingPlanUsageInfo
import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 权益页展示投影单测（M2 设置里程碑）：锁定 B-5 形状契约（条目唯一来源 =
 * subscription.details[]、档位 = quota.level 回落首条、顶层 remaining 原样透出）
 * 与三分支形态（noPlan / rows / unavailable）——UI 字符串装配不在此测（资源面）。
 */
class EntitlementUiModelTest {

    private fun info(
        tier: String? = null,
        names: List<String> = emptyList(),
        noPlan: Boolean = false,
        remaining: String? = null,
    ) = CodingPlanEntitlementInfo(
        tier = tier,
        entitlements = names.map { CodingPlanEntitlement(name = it, detail = "2026 年 12 月 1 日 00:00 前有效") },
        noPlan = noPlan,
        remainingText = remaining,
        mcpQuotaAggregate = JSONValue.Null,
    )

    // MARK: 三分支形态

    @Test
    fun nullInfo_marksUnavailable() {
        val section = EntitlementUiModel.entitlementSection(null)
        assertTrue(section.unavailable, "投影 null = 读取失败/未取到（诚实行）")
        assertFalse(section.noPlan)
        assertFalse(section.hasRows)
        assertEquals(0, section.moreCount)
    }

    @Test
    fun noPlan_showsEmptyStateRow() {
        val section = EntitlementUiModel.entitlementSection(info(noPlan = true))
        assertTrue(section.noPlan, "unavailableReason=no_plan = 未订阅空态单行")
        assertFalse(section.hasRows, "noPlan 不渲染权益行")
        assertFalse(section.unavailable)
    }

    @Test
    fun emptyReceipt_rendersNothing() {
        // 成功但无在期订阅条目：子块整体不渲染（noPlan=false、无行、非 unavailable）
        val section = EntitlementUiModel.entitlementSection(info(names = emptyList()))
        assertFalse(section.noPlan)
        assertFalse(section.hasRows)
        assertFalse(section.unavailable)
    }

    // MARK: 档位 + remaining

    @Test
    fun tier_passesThroughParseLayerValue() {
        // 档位回落首条 productName（quota.level 缺席时）是 :connection 解析层职责
        // （AppSession.fetchCodingPlanEntitlements: tier = quota.level ?: 首条 name，
        // web IAt 同构）——本投影只透传 info.tier，不二次推导。
        assertEquals("max", EntitlementUiModel.entitlementSection(info(tier = "max", names = listOf("GLM-5.3"))).tier)
        assertNull(EntitlementUiModel.entitlementSection(info(tier = null)).tier)
        // 空串档位 = 不渲染档位头（iOS `!tier.isEmpty` 同口径；非空空白串原样透传不加 trim）
        assertNull(EntitlementUiModel.entitlementSection(info(tier = "")).tier)
        assertEquals("  ", EntitlementUiModel.entitlementSection(info(tier = "  ")).tier)
    }

    @Test
    fun remaining_passesThroughVerbatim_andBlankMeansAbsent() {
        assertEquals("340/500", EntitlementUiModel.entitlementSection(info(remaining = "340/500")).remaining)
        assertNull(EntitlementUiModel.entitlementSection(info(remaining = "")).remaining, "空串 = 不渲染「剩 X」")
        assertNull(EntitlementUiModel.entitlementSection(info()).remaining)
    }

    // MARK: 行截取

    @Test
    fun rows_cappedAtEight_withMoreCount() {
        val names = (1..10).map { "权益-$it" }
        val section = EntitlementUiModel.entitlementSection(info(names = names))
        assertEquals(8, section.rows.size, "行截取上限 8（iOS prefix(8) 同数）")
        assertEquals(2, section.moreCount, "剩余条数 = 追加「在桌面端查看全部」尾行")
        assertEquals("权益-1", section.rows.first().name)
        assertEquals("权益-8", section.rows.last().name)
    }

    // MARK: 主窗选择 + 低水位 + 重置机会

    private fun window(
        label: String,
        used: Int?,
        limit: Int?,
    ) = CodingPlanQuotaWindow(
        level = "TOKENS_LIMIT",
        label = label,
        used = used,
        limit = limit,
        unit = if (used != null) "条" else null,
    )

    @Test
    fun primaryWindow_prefersFiveHourRow() {
        val usage = CodingPlanUsageInfo(
            windows = listOf(window("每周", 10, 100), window("5 小时", 30, 100)),
        )
        assertEquals("5 小时", EntitlementUiModel.primaryWindow(usage)?.label, "5 小时窗为主窗口")
        // 缺席取首窗
        val weeklyOnly = CodingPlanUsageInfo(windows = listOf(window("每周", 10, 100)))
        assertEquals("每周", EntitlementUiModel.primaryWindow(weeklyOnly)?.label)
        // 无窗 = null（legacy 兜底分支由 UI 呈现）
        assertNull(EntitlementUiModel.primaryWindow(CodingPlanUsageInfo()))
    }

    @Test
    fun lowRemaining_thresholdMatchesIOS() {
        assertTrue(EntitlementUiModel.isLowRemaining(0.14), "<0.15 转橙（iOS 同数）")
        assertFalse(EntitlementUiModel.isLowRemaining(0.15))
    }

    @Test
    fun resetOpportunity_anyGroupWithCardsShows() {
        assertTrue(
            EntitlementUiModel.hasResetOpportunity(CodingPlanResetCards(fiveHourCount = 1)),
            "仅 5h 卡在场即呈现机会卡（周卡漏显示教训同修）",
        )
        assertTrue(
            EntitlementUiModel.hasResetOpportunity(CodingPlanResetCards(weekCount = 2)),
            "仅周卡在场即呈现机会卡",
        )
        assertFalse(EntitlementUiModel.hasResetOpportunity(CodingPlanResetCards()))
        assertFalse(EntitlementUiModel.hasResetOpportunity(null))
    }

    @Test
    fun windowRemainingFraction_usesDerivedPercentRemaining() {
        // percentUsed（0–100 已用百分数，上游裁决口径）→ 剩余 0–1
        val pct = CodingPlanQuotaWindow(level = "T", label = "5 小时", percentUsed = 70.0)
        assertEquals(0.30, EntitlementUiModel.windowRemainingFraction(pct)!!, 1e-9)
        // v1 条数窗回退 used/limit
        val count = CodingPlanQuotaWindow(level = "T", label = "5 小时", used = 340, limit = 500)
        assertEquals(0.32, EntitlementUiModel.windowRemainingFraction(count)!!, 1e-9)
        assertNull(EntitlementUiModel.windowRemainingFraction(CodingPlanQuotaWindow(level = "T", label = "5 小时")))
    }
}

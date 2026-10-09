package cn.biuz.android.store

// 权益/额度解析单测（M7；协议文档 §9.10 + 交接甲类表 9 + 上游裁决）：
// - 额度窗口 type+unit+number 三元组匹配（与数组顺序无关；type 大小写不敏感）：
//   5 小时=TOKENS_LIMIT/3/5、每周=TOKENS_LIMIT/6、工具调用=TIME_LIMIT/5/1、MCP=mcpQuota.aggregate；
// - percentage 口径（上游裁决）：**0–100 已用百分数** → 剩余 = clamp(100 − pct)
//   【实证·上游仓 codingPlanQuotaPresentation.ts:66-74 / usage-quota.ts:71】；
//   v1 条数套餐 percentage 缺席 → remaining/number×100（分母 number，web vGt 原式）；
// - getEntitlementSnapshot 回执解析：**禁找不存在的 entitlements[]**（B-5/甲-9）——
//   档位 = quota.level、条目 = subscription.details[]（authenticated=false 不读、
//   expireTime 过期过滤、productName 缺席回落 productId）、顶层 remaining 在场透出；
// - getCodingPlanResetStatus 两组卡 + 最早过期 + 使用历史（5h/周分组不合并——用户裁决）。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageEntitlementParseTest {

    // MARK: 额度窗口三元组匹配

    private fun limit(type: String, unit: Int, number: Int? = null, extra: List<Pair<String, JSONValue?>> = emptyList()): JSONValue =
        testObj(
            "type" to JSONValue.Str(type),
            "unit" to JSONValue.Int(unit),
            "number" to (number?.let { JSONValue.Int(it) }),
            *extra.toTypedArray(),
        )

    @Test
    fun quotaWindows_tripleMatch_orderIndependent() {
        // 数组序打乱（周在前、5h 在中、工具调用在尾）——匹配与顺序无关
        val quota = testObj(
            "level" to JSONValue.Str("max"),
            "limits" to JSONValue.Arr(
                listOf(
                    limit("TOKENS_LIMIT", 6, extra = listOf("percentage" to JSONValue.Int(40), "nextResetTime" to testNum(1_767_000_000_000L))),
                    limit("TIME_LIMIT", 5, 1, extra = listOf("percentage" to JSONValue.Int(10))),
                    limit("TOKENS_LIMIT", 3, 5, extra = listOf("percentage" to JSONValue.Int(80), "nextResetTime" to testNum(1_767_014_400_000L))),
                    limit("TOKENS_LIMIT", 3, 12, extra = listOf("percentage" to JSONValue.Int(1))), // 干扰项：unit 相同 number 不同
                ),
            ),
        )
        val windows = RemoteUsageStatsStore.parseQuotaWindows(quota)
        assertEquals(listOf("5 小时", "每周", "工具调用"), windows.map { it.label }, "三元组匹配 + 规范展示序")
        // 5 小时窗：percentage 80（已用百分数）→ percentUsed 0.8 / percentRemaining 0.2
        val fiveHour = windows[0]
        assertEquals(0.8, fiveHour.percentUsed!!, 1e-9)
        assertEquals(0.2, fiveHour.percentRemaining!!, 1e-9)
        // nextResetTime（自动重置时刻）格式化串在场（时区相关不断言字面）
        assertNotNull(windows[0].resetsAtText)
        // 工具调用窗：percentage 10 → 剩 90%
        assertEquals(0.9, windows[2].percentRemaining!!, 1e-9)
        // level 原词（卡片角标）
        assertEquals("max", quota.objectValue?.get("level")?.stringValue)
    }

    @Test
    fun quotaWindows_percentageClampedToRemainingRange() {
        val quota = testObj(
            "limits" to JSONValue.Arr(
                listOf(limit("TOKENS_LIMIT", 3, 5, extra = listOf("percentage" to JSONValue.Int(115)))),
            ),
        )
        val window = RemoteUsageStatsStore.parseQuotaWindows(quota).single()
        // 上游 getQuotaRemainingPercentage = clamp(100 − pct, 0, 100) → 超耗按 0% 剩余
        assertEquals(0.0, window.percentRemaining!!, 1e-9)
        assertEquals(1.0, window.percentUsed!!, 1e-9)
    }

    @Test
    fun quotaWindows_v1CountWindow_fallbackRemainingOverNumber() {
        // v1 条数套餐：无 percentage；currentValue 已用 / usage 总量 / remaining 剩余。
        // 三元组仍按 TIME_LIMIT/unit:5/number:1 匹配（协议文档 §9.10 表）；缺 percentage 时
        // remaining/number×100 兜底（分母 number——web vGt 原式 / 上游 bucketRemainingRatio
        // 【实证·上游仓 startPlanQuotaBuckets.ts:34-38】remaining 与 number 同桶同单位）。
        val quota = testObj(
            "limits" to JSONValue.Arr(
                listOf(
                    limit(
                        "TIME_LIMIT", 5, 1,
                        extra = listOf(
                            "currentValue" to JSONValue.Int(20),
                            "usage" to JSONValue.Int(50),
                            "remaining" to JSONValue.Double(0.6),
                            "nextResetTime" to testNum(1_767_000_000_000L),
                        ),
                    ),
                ),
            ),
        )
        val window = RemoteUsageStatsStore.parseQuotaWindows(quota).single()
        assertEquals("工具调用", window.label)
        assertEquals(20, window.used, "v1 条数窗：currentValue 已用")
        assertEquals(50, window.limit, "v1 条数窗：usage 总量")
        assertEquals("条", window.unit)
        // remaining/number×100 = 60% 剩余（0.6/1×100）；percentRemaining = 0.6
        assertEquals(0.6, window.percentRemaining!!, 1e-9)
    }

    @Test
    fun quotaWindows_unknownEntries_genericRowsCappedAtThree() {
        val quota = testObj(
            "limits" to JSONValue.Arr(
                listOf(
                    limit("MYSTERY_A", 9, extra = listOf("percentage" to JSONValue.Int(1))),
                    limit("MYSTERY_B", 9, extra = listOf("percentage" to JSONValue.Int(2))),
                    limit("MYSTERY_C", 9, extra = listOf("percentage" to JSONValue.Int(3))),
                    limit("MYSTERY_D", 9, extra = listOf("percentage" to JSONValue.Int(4))),
                ),
            ),
        )
        val windows = RemoteUsageStatsStore.parseQuotaWindows(quota)
        assertEquals(3, windows.size, "未识别条目 generic 行 ≤3（web slice(0,3)）")
        assertEquals(listOf("MYSTERY_A", "MYSTERY_B", "MYSTERY_C"), windows.map { it.label })
    }

    @Test
    fun quotaWindows_typeCaseInsensitive() {
        val quota = testObj(
            "limits" to JSONValue.Arr(
                listOf(limit("tokens_limit", 6, extra = listOf("percentage" to JSONValue.Int(50)))),
            ),
        )
        val windows = RemoteUsageStatsStore.parseQuotaWindows(quota)
        assertEquals(listOf("每周"), windows.map { it.label }, "type 大小写不敏感")
    }

    @Test
    fun remainingPercent_nullWhenNoData() {
        assertNull(RemoteUsageStatsStore.remainingPercent(null))
        assertNull(RemoteUsageStatsStore.remainingPercent(testObj("number" to JSONValue.Int(0)).objectValue), "分母 0 不除")
    }

    // MARK: getEntitlementSnapshot 回执（禁找不存在的 entitlements[]）

    @Test
    fun entitlementSnapshot_tierFromQuotaLevel_detailsFromSubscription() {
        val json = testObj(
            "provider" to JSONValue.Str("zai"),
            "authenticated" to JSONValue.Bool(true),
            "quota" to testObj("level" to JSONValue.Str("max")),
            "subscription" to testObj(
                "details" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "productName" to JSONValue.Str("GLM Coding Pro"),
                            "productId" to JSONValue.Str("plan-001"),
                            "expireTime" to JSONValue.Str("2099-01-01T00:00:00Z"),
                        ),
                        testObj("productId" to JSONValue.Str("plan-002")), // productName 缺席回落 productId
                    ),
                ),
            ),
            "remaining" to JSONValue.Str("1,234,567"),
            "mcpQuota" to testObj("aggregate" to testObj("percentage" to JSONValue.Int(25))),
            // 陷阱注水：顶层 entitlements[] 列表键在 wire 层不存在（B-5）——解析绝不消费
            "entitlements" to JSONValue.Arr(listOf(testObj("name" to JSONValue.Str("不该出现的条目")))),
        )
        val info = RemoteUsageStatsStore.parseEntitlementSnapshot(json)
        assertEquals("max", info.tier, "档位 = quota.level（web IAt 同构）")
        assertEquals(2, info.entitlements.size)
        assertEquals("GLM Coding Pro", info.entitlements[0].name)
        assertNotNull(info.entitlements[0].detail, "在期条目带「前有效」展示串")
        assertTrue(info.entitlements[0].detail!!.contains("前有效"))
        assertEquals("plan-002", info.entitlements[1].name, "productName 缺席回落 productId")
        assertEquals("1,234,567", info.remainingText, "顶层 remaining 仅在场判定、原样透出")
        assertNotNull(info.mcpQuotaAggregate, "mcpQuota.aggregate 原始形态透出（usage 窗口合并阶段消费）")
    }

    @Test
    fun entitlementSnapshot_authenticatedFalse_noDetailsRead() {
        val json = testObj(
            "authenticated" to JSONValue.Bool(false),
            "quota" to testObj("level" to JSONValue.Str("free")),
            "subscription" to testObj(
                "details" to JSONValue.Arr(
                    listOf(testObj("productName" to JSONValue.Str("不该被读到的订阅"))),
                ),
            ),
        )
        val info = RemoteUsageStatsStore.parseEntitlementSnapshot(json)
        assertEquals(0, info.entitlements.size, "authenticated===false 无订阅可言（web 同构过滤，消费函数见 iOS 注）")
        assertEquals("free", info.tier, "档位仍可取 quota.level")
    }

    @Test
    fun entitlementSnapshot_expiredEntriesFiltered_andNoPlanFlag() {
        val json = testObj(
            "unavailableReason" to JSONValue.Str("no_plan"),
            "subscription" to testObj(
                "details" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "productName" to JSONValue.Str("已过期套餐"),
                            "expireTime" to JSONValue.Str("2001-01-01T00:00:00Z"),
                        ),
                        testObj(
                            "productName" to JSONValue.Str("在期套餐"),
                            "expireTime" to testNum(4_102_444_800_000L), // 2100-01-01，毫秒数字形态宽容
                        ),
                    ),
                ),
            ),
        )
        val info = RemoteUsageStatsStore.parseEntitlementSnapshot(json)
        assertTrue(info.noPlan, "unavailableReason=='no_plan' = 未订阅空态")
        assertEquals(listOf("在期套餐"), info.entitlements.map { it.name }, "过期条目按 expireTime 过滤（ISO/毫秒双形态）")
        // 档位回落首个条目名（quota.level 缺席）
        assertEquals("在期套餐", info.tier)
    }

    // MARK: getCodingPlanResetStatus（两组卡分组不合并）

    @Test
    fun resetStatus_groupsCountAndEarliestAndHistory() {
        val dict = testObj(
            "availableFiveHourResets" to JSONValue.Arr(
                listOf(
                    testObj("expireAt" to testNum(1_767_050_000_000L)),
                    testObj("expireAt" to testNum(1_767_010_000_000L)),
                ),
            ),
            "availableWeekResets" to JSONValue.Arr(
                listOf(testObj("expireAt" to testNum(1_767_300_000_000L))),
            ),
            "latestFiveHourResetHistory" to testObj("usedAt" to testNum(1_766_900_000_000L)),
            "latestWeekResetHistory" to testObj("usedAt" to testNum(1_766_500_000_000L)),
        ).objectValue!!
        val cards = RemoteUsageStatsStore.parseResetStatus(dict)
        assertEquals(2, cards.fiveHourCount)
        assertEquals(1, cards.weekCount)
        // 5h 卡只重置 5h 窗、周卡只重置周窗——过期时间按组分开展示（用户裁决：不能合并取最早）
        assertNotNull(cards.fiveHourEarliestText)
        assertNotNull(cards.weekEarliestText)
        assertTrue(cards.fiveHourEarliestText != cards.weekEarliestText)
        assertNotNull(cards.lastFiveHourUsedText)
        assertNotNull(cards.lastWeekUsedText)
    }
}

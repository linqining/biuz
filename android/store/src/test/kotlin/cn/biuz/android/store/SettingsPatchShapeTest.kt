package cn.biuz.android.store

// 设置写面/插件卸载/套餐重置/权益入参 形状单测（M7；交接甲类表 6/7/8/9 红线锁定）：
// - 表 7：update(**{设置名: 新值}** 单键补丁对象)——{key,value} 是 wire 层不存在的键（B-7）；
//   写命令不做时间性自动重试（防双写）；
// - 表 8：uninstallPlugin {workspacePath(必填), workspaceIdentity?, pluginName, marketplace,
//   scope:"user"}——marketplace 缺失走 getPluginsOverview 兜底，仍缺不下发（B-8）；
// - 表 6：重置卡三步**平铺**（B-6）+ 扣费接口 uiConfirmed 闸门（未确认零 RPC）；
// - 表 9：getEntitlementSnapshot 六键入参；回执解析禁找不存在的 entitlements[]（B-5）；
// - settingService/setting 双候选 + resolved 频道粘滞（读写同频道）。
// Mock 仅测试装配（红线 §0-4）。

import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPatchShapeTest {

    private val ws = "/Users/mac/zcode_mobile"

    /** 全频道调用账（channel, command, argsMap）——wire 形状断言面。 */
    private class RecordingTransport(
        private val replies: (channel: String, command: String, args: Map<String, JSONValue>) -> JSONValue?,
        private val errors: (channel: String, command: String) -> Boolean = { _, _ -> false },
    ) : RPCChannelTransport {
        val calls = ArrayList<Triple<String, String, Map<String, JSONValue>>>()

        override suspend fun call(
            channel: String,
            command: String,
            arg: RPCValue,
            timeout: kotlin.time.Duration,
        ): RPCValue {
            val args = arg.jsonValue?.objectValue ?: emptyMap()
            calls.add(Triple(channel, command, args))
            if (errors(channel, command)) throw RPCError(message = "rejected: $channel.$command", name = "ErrorObj")
            return RPCValue.json(replies(channel, command, args) ?: testObj("ok" to JSONValue.Bool(true)))
        }

        override suspend fun listen(
            channel: String,
            event: String,
            arg: RPCValue,
            handler: (RPCValue) -> Unit,
        ): EventSubscription = EventSubscription { }

        override suspend fun disconnect() {}
    }

    private fun store(transport: RPCChannelTransport): RemoteUsageStatsStore =
        RemoteUsageStatsStore(transport, StoreWorkspaceInfo(ws, workspaceIdentity = "ws-ident"))

    // MARK: 表 7 —— 设置写面 {设置名: 新值} 单键补丁

    @Test
    fun settingsUpdate_singleKeyPatchShape_onResolvedChannel() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(
            replies = { channel, command, _ ->
                if (channel == "settingService" && command == "get") {
                    testObj(
                        "settings" to testObj(
                            "terminalFontFamily" to JSONValue.Str("JetBrains Mono"),
                            "taskAutoArchiveEnabled" to JSONValue.Bool(true),
                        ),
                    )
                } else {
                    null
                }
            },
        )
        val s = store(transport)
        // 先 get（命中 settingService → resolved 粘滞）
        val entries = s.desktopSettings()
        assertEquals(2, entries.size)
        assertEquals("terminalFontFamily", entries[0].key)
        assertEquals("JetBrains Mono", entries[0].value.stringValue)
        // update：单键补丁对象 + 同频道粘滞
        assertNull(s.updateSetting("terminalFontFamily", JSONValue.Str("SF Mono")))
        val update = transport.calls.single { it.second == "update" }
        assertEquals("settingService", update.first, "update 走 get 命中的频道（读写不分家）")
        assertEquals(1, update.third.size, "单键补丁对象：唯一键 = 设置名（{key,value} 形态 wire 层不存在——B-7）")
        assertEquals("SF Mono", update.third["terminalFontFamily"]?.stringValue)
    }

    @Test
    fun settingsUpdate_failedBothChannels_noTimeRetry() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(
            replies = { _, _, _ -> null },
            errors = { _, command -> command == "update" },
        )
        val s = store(transport)
        val error = s.updateSetting("taskAutoArchiveEnabled", JSONValue.Bool(false))
        // 写命令不做时间性自动重试：双候选各一发 = 2 次（allowRetry=false——防双写）
        val updateCalls = transport.calls.count { it.second == "update" }
        assertEquals(2, updateCalls, "update 失败不得自动重试（写面防双写）")
        assertNotNull(error)
        assertTrue(error.contains("修改失败"), "失败文案可上屏（§5-12）：$error")
    }

    @Test
    fun settingsGet_dualChannelCandidates_settingFallback() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(
            replies = { channel, command, _ ->
                if (channel == "setting" && command == "get") {
                    testObj("values" to testObj("locale" to JSONValue.Str("zh-CN")))
                } else if (command == "get" && channel == "settingService") {
                    throw RPCError(message = "no such channel", name = "ErrorObj")
                } else {
                    null
                }
            },
        )
        val s = store(transport)
        val entries = s.desktopSettings()
        assertEquals(1, entries.size)
        assertEquals("locale", entries[0].key)
        // 次候选命中 → resolved 粘滞到 setting
        assertEquals("setting", s.resolvedSettingChannel)
        assertNull(s.updateSetting("locale", JSONValue.Str("en-US")))
        assertEquals("setting", transport.calls.single { it.second == "update" }.first)
    }

    @Test
    fun settingEntries_scalarOnly_objectAndArrayValuesSkipped() {
        val entries = RemoteUsageStatsStore.parseSettingEntries(
            testObj(
                "shortcutBindings" to JSONValue.Arr(emptyList()), // 数组值跳过
                "providerFamilyConnectionSelections" to testObj("x" to JSONValue.Str("y")), // 对象值跳过
                "httpProxy" to JSONValue.Str("http://127.0.0.1:7890"),
                "desktopZoomLevel" to JSONValue.Int(2),
            ),
        )
        assertEquals(listOf("httpProxy", "desktopZoomLevel"), entries.map { it.key }, "只收标量（对象/数组不臆造渲染）")
    }

    // MARK: 表 6 —— 套餐重置（平铺四键 + 扣费二次确认闸门）

    @Test
    fun resetCard_notConfirmed_zeroRpcAndPrompt() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(replies = { _, _, _ -> null })
        val s = store(transport)
        val result = s.useCodingPlanResetCard(CodingPlanResetType.fiveHour, uiConfirmed = false)
        assertNotNull(result)
        assertTrue(result.contains("确认"), "未确认调用提示走确认弹层：$result")
        assertTrue(transport.calls.isEmpty(), "扣费接口红线：未经确认弹层零 RPC 下发")
    }

    @Test
    fun resetCard_confirmed_threeFlatSteps_onUsageStatsChannel() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(replies = { _, _, _ -> null })
        val s = store(transport)
        assertNull(s.useCodingPlanResetCard(CodingPlanResetType.week, uiConfirmed = true))
        // 三步全在 usage-stats 频道，序 = opportunity → use → mark
        assertEquals(
            listOf(
                "requestCodingPlanResetOpportunity",
                "useCodingPlanReset",
                "markCodingPlanResetHistoryRead",
            ),
            transport.calls.map { it.second },
        )
        assertTrue(transport.calls.all { it.first == "usage-stats" })
        // 平铺四键（B-6：嵌套 scope 形态推翻）
        val use = transport.calls[1].third
        assertEquals(4, use.size)
        assertEquals(RemoteUsageStatsStore.CODING_PLAN_PROVIDER_ID, use["preferredProviderId"]?.stringValue)
        assertEquals("zhipu-account", use["accountAccess"]?.objectValue?.get("type")?.stringValue)
        assertEquals("zai", use["accountAccess"]?.objectValue?.get("family")?.stringValue)
        assertEquals("individual-coding-plan", use["accountAccess"]?.objectValue?.get("planKind")?.stringValue)
        assertNotNull(use["idempotencyKey"]?.stringValue, "idempotencyKey 必带（平铺）")
        assertEquals("WEEK", use["resetType"]?.stringValue, "resetType ∈ FIVE_HOUR|WEEK（平铺键）")
    }

    @Test
    fun resetCard_useStepFails_errorSurfaces() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(
            replies = { _, _, _ -> null },
            errors = { _, command -> command == "useCodingPlanReset" },
        )
        val s = store(transport)
        val result = s.useCodingPlanResetCard(CodingPlanResetType.fiveHour, uiConfirmed = true)
        assertNotNull(result)
        assertTrue(result.contains("领取失败"), "步骤②失败如实回传（写面禁止静默）：$result")
    }

    // MARK: 表 8 —— 插件卸载五键 + marketplace 兜底

    @Test
    fun uninstallPlugin_fiveKeyPayload_withHintMarketplace() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(replies = { _, _, _ -> null })
        val s = store(transport)
        assertNull(s.uninstallPlugin("zcode-diag", marketplaceHint = "official"))
        val call = transport.calls.single()
        assertEquals("zcode-agent" to "uninstallPlugin", call.first to call.second)
        val args = call.third
        assertEquals(
            setOf("workspacePath", "workspaceIdentity", "pluginName", "marketplace", "scope"),
            args.keys,
            "五键形状（B-8：原 {name} 形态 wire 层不存在）",
        )
        assertEquals(ws, args["workspacePath"]?.stringValue, "workspacePath 必填（缺失桌面报「请先打开一个工作区」）")
        assertEquals("ws-ident", args["workspaceIdentity"]?.stringValue)
        assertEquals("zcode-diag", args["pluginName"]?.stringValue)
        assertEquals("official", args["marketplace"]?.stringValue)
        assertEquals("user", args["scope"]?.stringValue, "scope 恒 'user'")
    }

    @Test
    fun uninstallPlugin_marketplaceFallsBackToPluginsOverview() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(
            replies = { _, command, _ ->
                if (command == "getPluginsOverview") {
                    testObj(
                        "availablePlugins" to JSONValue.Arr(
                            listOf(
                                testObj(
                                    "name" to JSONValue.Str("zcode-missing-mkt"),
                                    "marketplace" to JSONValue.Str("community"),
                                ),
                            ),
                        ),
                    )
                } else {
                    null
                }
            },
        )
        val s = store(transport)
        assertNull(s.uninstallPlugin("zcode-missing-mkt", marketplaceHint = null))
        // 兜底序：先 overview，后 uninstall
        val commands = transport.calls.map { it.second }
        assertEquals(listOf("getPluginsOverview", "uninstallPlugin"), commands)
        assertEquals("community", transport.calls[1].third["marketplace"]?.stringValue)
    }

    @Test
    fun uninstallPlugin_marketplaceUnresolvable_refusesToSend() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(replies = { _, _, _ -> testObj("availablePlugins" to JSONValue.Arr(emptyList())) })
        val s = store(transport)
        val result = s.uninstallPlugin("zcode-nowhere", marketplaceHint = null)
        assertNotNull(result)
        assertTrue(result.contains("无法确定"), "宁可不卸载也不盲发必败键：$result")
        assertTrue(
            transport.calls.none { it.second == "uninstallPlugin" },
            "marketplace 解析不出 → 零卸载下发",
        )
    }

    // MARK: 表 9 —— getEntitlementSnapshot 六键入参 + 无列表键回执解析

    @Test
    fun entitlementFetch_sixKeyParams_exactly() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport(replies = { _, _, _ -> testObj("provider" to JSONValue.Str("zai")) })
        val s = store(transport)
        s.fetchCodingPlanEntitlements()
        val call = transport.calls.single()
        assertEquals("usage-stats" to "getEntitlementSnapshot", call.first to call.second)
        val args = call.third
        assertEquals(
            setOf(
                "includeSubscription",
                "preferredProviderId",
                "accountAccess",
                "allowDisabledPreferredProvider",
                "requirePreferredProvider",
                "allowEnvApiKey",
            ),
            args.keys,
            "web 六键全量（B-5 定形）",
        )
        assertEquals(JSONValue.Bool(true), args["includeSubscription"])
        assertEquals(JSONValue.Bool(true), args["allowDisabledPreferredProvider"])
        assertEquals(JSONValue.Bool(true), args["requirePreferredProvider"])
        assertEquals(JSONValue.Bool(false), args["allowEnvApiKey"])
    }
}

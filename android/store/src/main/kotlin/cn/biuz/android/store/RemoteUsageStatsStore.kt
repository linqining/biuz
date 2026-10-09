package cn.biuz.android.store

// 直译自 ios 两处服务面：
// - Services/Remote/AppSession.swift 的 usage-stats 读面（fetchCodingPlanUsage :740 起 /
//   fetchCodingPlanEntitlements :872 起 / getCodingPlanResetStatus 解析 :858 起 /
//   useCodingPlanResetCard :986 起 / usageStatsCall :708 / codingPlan 常量 :723-736）；
// - Features/Settings/P2ExtrasViews.swift 的桌面设置读/写（settingCall :2313 起 /
//   performUpdate :2410 起 / parseEntries 宽容解析）与插件卸载（performUninstall :1302 /
//   lookupPluginMarketplace :1346）。
//
// iOS 侧这些逻辑散在 AppSession（@Observable 装配壳）与 SwiftUI 视图内；Android :store
// 是纯 JVM 服务面，故收敛为本类（读面 + 写面 + 纯解析函数）；UI 呈现（确认弹层/行渲染/
// 通知提示）归 :app。协议形状权威：协议文档 §9.10（usage-stats）/§9.14（setting/
// settingService）/§9.2（uninstallPlugin/getPluginsOverview）+ 交接文档甲类表 6/7/8/9。
//
// 甲类红线对照（docs/Android开发交接.md §4-甲，逐条落点）：
// - 表 6（套餐重置）：**平铺** {preferredProviderId, accountAccess, idempotencyKey, resetType}
//   ——嵌套 scope 形态 wire 层不存在（B-6）；**扣费接口，[useCodingPlanResetCard] 硬要求
//   uiConfirmed=true 才下发**（iOS 在 UI confirmationDialog 把关；:store 加参数位闸门，
//   未确认调用直接拒绝且零 RPC——双保险）。
// - 表 7（设置写面）：update(**{设置名: 新值}** 单键补丁对象)——{key,value} 是 wire 层
//   不存在的键（B-7）；不做时间性自动重试，防双写。
// - 表 8（插件卸载）：{workspacePath(必填), workspaceIdentity?, pluginName, marketplace,
//   scope:"user"}——{name} 形态必败（B-8）；marketplace 缺失时 getPluginsOverview 兜底，
//   仍缺则**不下发**（宁可不卸载也不盲发必败键）。
// - 表 9（权益页）：**禁找不存在的 entitlements[]**——回执无任何列表键（B-5），条目唯一
//   来源 = subscription.details[]，档位 = quota.level，顶层 remaining 仅在场判定。
//   额度窗口按 type+unit+number 三元组匹配；percentage 语义 = **0–100 已用百分数**
//   （上游裁决，见 parseQuotaWindows KDoc——任务书「0–1 已用小数」为 iOS v1.6 已推翻的
//   旧口径，按上游 file:line 裁决执行）。

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCValue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 通知类额度窗口（iOS CodingPlanQuotaWindow；percentRemaining = 0–1 剩余小数）。 */
data class CodingPlanQuotaWindow(
    /** 窗口 type 原词（TOKENS_LIMIT/TIME_LIMIT；generic 行 = 服务端 type 或「额度」） */
    val level: String,
    val label: String,
    /** v1 条数窗：currentValue 已用 / usage 总量；v2/v3 百分比窗两者皆 null */
    val used: Int?,
    val limit: Int?,
    /** v1 条数窗单位「条」；百分比窗 null */
    val unit: String?,
    /** 0–1 已用小数（UI 进度条直接消费；null = 无百分比数据） */
    val percentUsed: Double?,
    /** 自动重置时间展示串（nextResetTime 毫秒；M 月 d 日 HH:mm） */
    val resetsAtText: String?,
) {
    /** 剩余小数（0–1；null = 无百分比数据）。iOS struct 同名派生 percentRemaining=1-pct/100。 */
    val percentRemaining: Double?
        get() = percentUsed?.let { 1.0 - it }
}

/** 额度页主投影（iOS CodingPlanUsageInfo 直译；resetCards 合并承载）。 */
data class CodingPlanUsageInfo(
    /** 全部额度窗口（5 小时/每周/工具调用；未识别条目 generic ≤3 行） */
    val windows: List<CodingPlanQuotaWindow> = emptyList(),
    /** 套餐档位（quota.level："max" 等；卡片角标） */
    val unitText: String? = null,
    /** 主窗口 legacy 字段（5 小时窗优先；SettingsView 用户卡） */
    val used: Int? = null,
    val limit: Int? = null,
    /** 0–1 剩余小数（主窗口） */
    val percentRemaining: Double? = null,
    val resetsAtText: String? = null,
    val resetCards: CodingPlanResetCards = CodingPlanResetCards(),
)

/** 重置卡全量投影（getCodingPlanResetStatus；5h/周两组可用卡 + 最早过期 + 最近使用历史）。 */
data class CodingPlanResetCards(
    val fiveHourCount: Int = 0,
    val weekCount: Int = 0,
    /** 5h 卡只重置 5h 窗、周卡只重置周窗，语义不同——过期时间按组分开展示（用户裁决：不能合并取最早） */
    val fiveHourEarliestText: String? = null,
    val weekEarliestText: String? = null,
    val lastFiveHourUsedText: String? = null,
    val lastWeekUsedText: String? = null,
)

/** 套餐权益条目（subscription.details[] 投影；name 缺席回落 productId）。 */
data class CodingPlanEntitlement(
    val name: String,
    val value: String? = null,
    /** 「M 月 d 日 HH:mm 前有效」（expireTime 可解析时） */
    val detail: String? = null,
) {
    val id: String get() = name
}

/**
 * getEntitlementSnapshot 投影。**回执无任何列表键**（原 entitlements/benefits/features/
 * items 解析任何路径落空——B-5 否定性结论），条目唯一来源 = subscription.details[]。
 */
data class CodingPlanEntitlementInfo(
    /** 档位 = quota.level（缺席回落 details[0].productName） */
    val tier: String?,
    val entitlements: List<CodingPlanEntitlement> = emptyList(),
    /** unavailableReason 原词（no_plan/not_configured/not_authenticated/…） */
    val unavailableReason: String? = null,
    /** unavailableReason=="no_plan" = 未订阅空态（UI 不渲染子块） */
    val noPlan: Boolean = false,
    /** 顶层 remaining（web 仅做在场判定；单位语义未取证，原样透出不加解释） */
    val remainingText: String? = null,
    /** mcpQuota.aggregate 原始形态（usage 窗口合并阶段消费） */
    val mcpQuotaAggregate: JSONValue? = null,
)

/** 桌面设置行（settingService.get 宽容解析产物；只收标量，对象/数组/空值跳过不臆造）。 */
data class DesktopSettingEntry(
    val key: String,
    val value: JSONValue,
    /** readonly 行（web 的 UI 语义：不可编辑——键未取证，宽容 readOnly/readonly 样键） */
    val readonly: Boolean,
) {
    val id: String get() = key
}

/** 套餐重置窗口词表（web resetType ∈ FIVE_HOUR|WEEK；AppSession.swift:982-985）。 */
enum class CodingPlanResetType(val wire: String) {
    fiveHour("FIVE_HOUR"),
    week("WEEK"),
}

/**
 * usage-stats / setting(SettingService) / zcode-agent 插件族 服务面（连接态真实实现）。
 * 全部走 transport（:connection 装配的 ZCodeServerConnection 唯一出口——ReadOnlyGate
 * 在出口拦截，usage-stats/setting 读面与 setting.update/uninstallPlugin 写面均 gate 放行）。
 */
class RemoteUsageStatsStore(
    private val transport: RPCChannelTransport,
    private val workspace: StoreWorkspaceInfo,
) {

    /** 诊断钩子（iOS UserDefaults diag.usage.* 承载；非 null = diag 模式在开）。 */
    @Volatile
    var diagListener: ((key: String, value: String) -> Unit)? = null

    /** get 首个命中的频道（update 同频道优先，避免读写分家；未命中前走候选序）。
     * iOS resolvedChannel @State 的对应物。 */
    @Volatile
    var resolvedSettingChannel: String? = null

    // MARK: usage-stats 读面

    /**
     * usage-stats 读面统一入口：桌面端对并发快照请求拒绝（"Request in progress,
     * please wait"——连接刷新与用量页竞态实测），首败 1.2s 退避重试一次
     * （同 loadOlder 中继瞬断口径）；重试仍败则原错误上抛（调用方取证）。
     */
    private suspend fun usageStatsCall(command: String, args: Map<String, JSONValue>): JSONValue {
        try {
            return transport.call("usage-stats", command, RPCValue.json(JSONValue.Obj(args))).jsonValue
                ?: JSONValue.Null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(RETRY_BACKOFF_MS)
            return transport.call("usage-stats", command, RPCValue.json(JSONValue.Obj(args))).jsonValue
                ?: JSONValue.Null
        }
    }

    /**
     * 套餐额度快照（getCodingPlanUsageSnapshot）。
     * ⚠ preferredProviderId 必须是注册表完整 id——传 "zai" 短 id 桌面端落到 bigmodel
     * API-key 面报 no_bigmodel_api_key（§9.10 实证先例）。
     */
    suspend fun fetchCodingPlanUsage(timeZoneId: String = ZoneId.systemDefault().id): CodingPlanUsageInfo? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["range"] = JSONValue.Str("30d")
        fields["preferredProviderId"] = JSONValue.Str(CODING_PLAN_PROVIDER_ID)
        fields["accountAccess"] = CODING_PLAN_ACCOUNT_ACCESS
        fields["timeZone"] = JSONValue.Str(timeZoneId)
        val dict: Map<String, JSONValue> = try {
            usageStatsCall("getCodingPlanUsageSnapshot", fields).objectValue ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败不再静默（「桌面端未返回 Coding Plan 用量」的取证口）
            diagListener?.invoke("diag.usage.snapshot.error", "err=${e.toString().take(500)}")
            return null
        }
        var info = CodingPlanUsageInfo()
        val quotaJson = dict["quota"]
        if (quotaJson != null) {
            // 窗口映射（2026-10-06 桌面 web bundle 精确取证）——纯解析函数单测锁定
            val windows = parseQuotaWindows(quotaJson)
            info = info.copy(windows = windows)
            // 套餐档位（quota.level："max" 等）记入 unitText 供卡片角标
            info = info.copy(unitText = quotaJson.objectValue?.get("level")?.stringValue)
            // 主窗口（legacy 字段兼容 SettingsView 用户卡）：5 小时窗优先
            val primary = windows.firstOrNull { it.label == WINDOW_LABEL_5H } ?: windows.firstOrNull()
            if (primary != null) {
                info = info.copy(
                    used = primary.used,
                    limit = primary.limit,
                    percentRemaining = primary.percentRemaining,
                    resetsAtText = primary.resetsAtText,
                )
            }
        }
        // 重置卡全量（5h/周两组可用卡 + 最早过期 + 最近使用历史）
        val resetFields = LinkedHashMap<String, JSONValue>()
        resetFields["preferredProviderId"] = JSONValue.Str(CODING_PLAN_PROVIDER_ID)
        resetFields["accountAccess"] = CODING_PLAN_ACCOUNT_ACCESS
        try {
            val resetDict = usageStatsCall("getCodingPlanResetStatus", resetFields).objectValue
            if (resetDict != null) {
                info = info.copy(resetCards = parseResetStatus(resetDict))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 重置状态读失败不拖垮额度主投影（UI 各区独立呈现）
        }
        return if (info.windows.isNotEmpty() || info.unitText != null || info.used != null ||
            info.limit != null || info.percentRemaining != null
        ) {
            info
        } else {
            null
        }
    }

    /**
     * 使用重置卡（桌面代执行三步，web reset-cards 同构；用户要求仅接线不自动触发）。
     * 参数口径【实证·bundle 逆向 2026-10-06，审查报告 B-6——原嵌套 scope 形态推翻】：
     * web 端「scope」只是前端内存变量名/去重键，服务端三步全部**平铺**：
     * ① requestCodingPlanResetOpportunity {preferredProviderId, accountAccess, idempotencyKey}
     * ② useCodingPlanReset {preferredProviderId, accountAccess, idempotencyKey, resetType}
     * ③ markCodingPlanResetHistoryRead {preferredProviderId, accountAccess}
     *
     * **红线（甲-6）**：扣费/消耗写面必须 destructive 二次确认——[uiConfirmed]=false 时
     * 直接返回提示且**零 RPC**（iOS 在 UI confirmationDialog 把关；:store 参数位闸门双保险，
     * 弹层须写明重置哪个窗口/剩余卡数/核销不可撤销）。
     * 步骤②失败如实回传 UI（null = 成功）；①③失败仅记日志不阻断（web fire-and-forget 同构）。
     */
    suspend fun useCodingPlanResetCard(type: CodingPlanResetType, uiConfirmed: Boolean): String? {
        if (!uiConfirmed) {
            // 红线闸门：未经确认弹层的调用不下发扣费命令
            return "重置卡使用需先经确认弹层（核销不可撤销）"
        }
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        // ① 幂等探测领取机会（web 手动路径同构：探测失败不阻断用卡，仅记日志——
        // 用卡资格以先前的 getCodingPlanResetStatus 读数为准，UI 按钮本就以它 gating）
        val requestFields = linkedMapOf(
            "preferredProviderId" to JSONValue.Str(CODING_PLAN_PROVIDER_ID),
            "accountAccess" to CODING_PLAN_ACCOUNT_ACCESS,
            "idempotencyKey" to JSONValue.Str(idempotencyKey),
        )
        try {
            usageStatsCall("requestCodingPlanResetOpportunity", requestFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagListener?.invoke(
                "diag.usage.reset",
                "requestCodingPlanResetOpportunity 失败（不阻断用卡）: ${e.message}",
            )
        }
        // ② 使用重置卡（平铺四键；失败如实回传 UI）
        val useFields = linkedMapOf(
            "preferredProviderId" to JSONValue.Str(CODING_PLAN_PROVIDER_ID),
            "accountAccess" to CODING_PLAN_ACCOUNT_ACCESS,
            "idempotencyKey" to JSONValue.Str(idempotencyKey),
            "resetType" to JSONValue.Str(type.wire),
        )
        try {
            usageStatsCall("useCodingPlanReset", useFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "领取失败 · ${e.message ?: e.toString()}"
        }
        // ③ 清历史未读角标（web fire-and-forget 同构：失败仅记日志，不影响用卡结果）
        val markFields = linkedMapOf(
            "preferredProviderId" to JSONValue.Str(CODING_PLAN_PROVIDER_ID),
            "accountAccess" to CODING_PLAN_ACCOUNT_ACCESS,
        )
        try {
            usageStatsCall("markCodingPlanResetHistoryRead", markFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagListener?.invoke(
                "diag.usage.reset",
                "markCodingPlanResetHistoryRead 失败（不影响用卡结果）: ${e.message}",
            )
        }
        return null
    }

    /**
     * 套餐权益快照（getEntitlementSnapshot；P3-9 额度页）。
     * 入参与回执均按 web 口径【实证·bundle 逆向 2026-10-06，审查报告 B-5】：
     * 入参六键；回执 {provider, authenticated?, unavailableReason?, quota:{level,limits},
     * subscription:{details:[{productName,productId,expireTime?}]}, remaining?}。
     * 调用失败 → null；成功但无在期订阅条目 → 空 entitlements（UI 不渲染子块）。
     * 诊断：diag.usage.entitlement 一次性原始回执（diagListener 接线才写；§11 登记，验收后清理）。
     */
    suspend fun fetchCodingPlanEntitlements(): CodingPlanEntitlementInfo? {
        val fields = linkedMapOf(
            "includeSubscription" to JSONValue.Bool(true),
            "preferredProviderId" to JSONValue.Str(CODING_PLAN_PROVIDER_ID),
            "accountAccess" to CODING_PLAN_ACCOUNT_ACCESS,
            "allowDisabledPreferredProvider" to JSONValue.Bool(true),
            "requirePreferredProvider" to JSONValue.Bool(true),
            "allowEnvApiKey" to JSONValue.Bool(false),
        )
        val json: JSONValue = try {
            usageStatsCall("getEntitlementSnapshot", fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagListener?.invoke("diag.usage.entitlement", "err=${e.toString().take(500)}")
            // 调用失败 → null 投影（UI「权益信息不可用 · 下拉刷新重试」；iOS 返回 nil 同口径）
            return null
        }
        if (json !is JSONValue.Null) {
            diagListener?.invoke("diag.usage.entitlement", json.toString().take(2000))
        }
        return parseEntitlementSnapshot(json)
    }

    // MARK: 桌面设置（settingService/setting 双候选；§9.14）

    /**
     * 桌面设置读面：settingService/setting 双候选依次尝试（真实频道名未取证）。
     * 已解析频道优先（get 命中后 update 走同频道）；全候选失败 → 1.2s 退避整轮
     * 重试一次（中继瞬断同口径），[allowRetry]=false 供写命令关闭防双写。
     */
    suspend fun settingCall(command: String, fields: Map<String, JSONValue>, allowRetry: Boolean = true): JSONValue {
        suspend fun runOnce(): JSONValue {
            var lastError: Throwable? = null
            val candidates = ArrayList(SETTING_CHANNELS)
            resolvedSettingChannel?.let { resolved ->
                candidates.remove(resolved)
                candidates.add(0, resolved)
            }
            for (channel in candidates) {
                try {
                    val value = transport.call(channel, command, RPCValue.json(JSONValue.Obj(fields))).jsonValue
                        ?: JSONValue.Null
                    resolvedSettingChannel = channel
                    return value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("桌面设置频道不可用")
        }
        return try {
            runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!allowRetry) throw e
            delay(RETRY_BACKOFF_MS)
            runOnce()
        }
    }

    /** 设置清单读（settingCall("get", {}) + parseEntries 宽容解析）。失败原样上抛。 */
    suspend fun desktopSettings(): List<DesktopSettingEntry> {
        val result = settingCall("get", emptyMap())
        return parseSettingEntries(result)
    }

    /**
     * 设置写面（web 实证 2026-10-06）：update(**{设置名: 新值}** 单键补丁对象——
     * terminalFontFamily/taskAutoArchiveEnabled 等 15 处调用点同构；原 {key,value}
     * 形态的键在 wire 层不存在，写面全坏（审查报告 B-7/甲-7））。
     * 写命令不做时间性自动重试，防双写（allowRetry=false）。
     * 返回 null = 成功；非 null = 可上屏错误文案（写面禁止静默）。
     */
    suspend fun updateSetting(key: String, newValue: JSONValue): String? {
        // 单键补丁对象：唯一键 = 设置名，值 = 新值
        val patch = linkedMapOf(key to newValue)
        return try {
            settingCall("update", patch, allowRetry = false)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败：行值回弹（本地未改，读自 entries）+ 错误提示
            "修改失败 · ${e.toString().take(160)}"
        }
    }

    // MARK: 插件卸载（zcode-agent.uninstallPlugin；§9.2 / 甲-8）

    /**
     * P3-11 卸载（桌面代执行；zcode-agent.uninstallPlugin 已过 gate）。
     * 参数【实证·bundle 逆向 2026-10-06，审查报告 B-8——原 {name: 行标题} 推翻】：
     * `{workspacePath(必填，缺失桌面报「请先打开一个工作区」), workspaceIdentity?,
     * pluginName, marketplace, scope:'user'}`。
     * [marketplaceHint]：listPlugins 条目自带 marketplace；条目缺 marketplace 时先读
     * getPluginsOverview 兜底（web 清单即 listPlugins+overview 双读合并），仍缺则
     * **如实提示不下发**（宁可不卸载也不盲发必败键）。返回 null=成功、非 null=错误文案。
     */
    suspend fun uninstallPlugin(pluginName: String, marketplaceHint: String?): String? {
        if (workspace.path.isEmpty()) {
            return "卸载失败 · 请先在桌面端打开一个工作区"
        }
        val marketplace = marketplaceHint?.takeIf { it.isNotEmpty() }
            ?: lookupPluginMarketplace(pluginName)
        if (marketplace == null || marketplace.isEmpty()) {
            return "卸载失败 · 无法确定「$pluginName」的插件市场来源" +
                "（listPlugins/getPluginsOverview 均未返回 marketplace）"
        }
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        workspace.workspaceIdentity?.takeIf { it.isNotEmpty() }?.let {
            fields["workspaceIdentity"] = JSONValue.Str(it)
        }
        fields["pluginName"] = JSONValue.Str(pluginName)
        fields["marketplace"] = JSONValue.Str(marketplace)
        fields["scope"] = JSONValue.Str("user")
        return try {
            transport.call("zcode-agent", "uninstallPlugin", RPCValue.json(JSONValue.Obj(fields)))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "卸载失败 · ${e.toString().take(160)}"
        }
    }

    /**
     * marketplace 兜底：getPluginsOverview({workspacePath, workspaceIdentity?}) 的
     * availablePlugins[]/plugins[] 按 name|pluginName|id 匹配插件行取其 marketplace
     * （回执形态未逐项取证，键组宽容；两数组均无匹配或无 marketplace 键 → null 如实上报）
     */
    suspend fun lookupPluginMarketplace(pluginName: String): String? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        workspace.workspaceIdentity?.takeIf { it.isNotEmpty() }?.let {
            fields["workspaceIdentity"] = JSONValue.Str(it)
        }
        return try {
            val json = transport.call("zcode-agent", "getPluginsOverview", RPCValue.json(JSONValue.Obj(fields))).jsonValue
            val candidates = (json?.get("availablePlugins")?.arrayValue ?: emptyList()) +
                (json?.get("plugins")?.arrayValue ?: emptyList())
            candidates.firstNotNullOfOrNull { item ->
                val d = item.objectValue ?: return@firstNotNullOfOrNull null
                val name = d["name"]?.stringValue
                    ?: d["pluginName"]?.stringValue
                    ?: d["id"]?.stringValue
                if (name == pluginName) d["marketplace"]?.stringValue?.takeIf { it.isNotEmpty() } else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // MARK: 纯解析函数（companion；单测锁定面）

    companion object {
        /** 幂等读级首败退避（中继瞬断纪律）：1.2s */
        private const val RETRY_BACKOFF_MS: Long = 1_200L

        /** usage-stats 个人 Coding Plan 统一参数（web bundle 取证 2026-10-06）。
         * preferredProviderId 必须用注册表完整 id（短 id 匹配不到任何 provider——§9.10）。 */
        const val CODING_PLAN_PROVIDER_ID = "account:zai-individual-coding-plan"

        /**
         * web 端 accountAccess 统一形状【实证·bundle 逆向】：所有 usage-stats 调用点恒传
         * `{type:'zhipu-account', family, planKind}`（换算函数 ET：access.accountType→family、
         * access.mode→planKind；family 由 provider id 推导——account:zai-* → 'zai'）。
         * 此前发注册表 access 形态 {accountType,mode,entitled}——当前桌面两种都收，按 web
         * 对齐防未来收紧落错面（审查报告 L-2）。
         */
        val CODING_PLAN_ACCOUNT_ACCESS: JSONValue = JSONValue.Obj(
            mapOf(
                "type" to JSONValue.Str("zhipu-account"),
                "family" to JSONValue.Str("zai"),
                "planKind" to JSONValue.Str("individual-coding-plan"),
            ),
        )

        /** 频道候选序（真实频道名未取证；与 ReadOnlyGate setting/settingService 词表同源） */
        val SETTING_CHANNELS = listOf("settingService", "setting")

        /** 读回执可能的包裹键（宽容形态；整对象兜底见 parseSettingEntries） */
        private val SETTING_WRAPPER_KEYS = listOf("settings", "values", "data", "config", "items", "entries")

        const val WINDOW_LABEL_5H = "5 小时"
        private const val WINDOW_LABEL_WEEK = "每周"
        private const val WINDOW_LABEL_TOOL = "工具调用"
        private const val WINDOW_LABEL_GENERIC = "额度"

        /** 重置时间/有效期展示串（iOS shortFormatter "M 月 d 日 HH:mm" 同构）。 */
        private val SHORT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")

        private fun formatTime(epochMs: Long): String = try {
            SHORT_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
        } catch (e: Exception) {
            ""
        }

        /**
         * quota.limits → 额度窗口。窗口映射（2026-10-06 桌面 web bundle 精确取证，
         * app.asar 内 U5/JH 选择器；协议文档 §9.10 同表）：按 **type+unit+number 三元组**
         * 匹配（与数组顺序无关；type 大小写不敏感）：
         *   5 小时   = TOKENS_LIMIT, unit:3, number:5（v2/v3 百分比窗）
         *   每周     = TOKENS_LIMIT, unit:6
         *   工具调用 = TIME_LIMIT,   unit:5, number:1（v1 条数窗：usage 总量/currentValue
     *            已用/remaining 剩余）
         *   ZCode MCP = quota.mcpQuota.aggregate（独立字段，不在此读——§11.2 usage 窗口
         *   合并阶段消费，此前从 usage 快照读恒 nil 致该行永不渲染）
         *
         * **percentage 口径（上游裁决 2026-10-10 /tmp/zcode-upstream）**：percentage 为
         * **0–100 已用百分数**——`getQuotaRemainingPercentage = clamp(100 − pct)`
         * （【实证·上游仓】packages/ui/src/lib/codingPlanQuotaPresentation.ts:66-74、
         * packages/shared/src/usage-quota.ts:71「已使用占比，展示端负责反转成剩余」）。
         * 任务书「0–1 已用小数」为 iOS v1.6 已推翻的旧口径（iOS AppSession.swift 注释：
         * 「旧注释…是错误口径勿再引用」），按上游裁决执行。v1 条数套餐 percentage 缺席时
         * `remaining/number×100` 兜底（分母是 number，web vGt 原式——v1.11 更正）。
         * 未识别条目回退 generic 行（≤3，label「额度」）。
         */
        fun parseQuotaWindows(quota: JSONValue?): List<CodingPlanQuotaWindow> {
            val limits = quota?.objectValue?.get("limits")?.arrayValue ?: return emptyList()
            val windows = ArrayList<CodingPlanQuotaWindow>()
            findLimit(limits, "TOKENS_LIMIT", unit = 3, number = 5)?.let {
                makeWindow(WINDOW_LABEL_5H, it)?.let { w -> windows.add(w) }
            }
            findLimit(limits, "TOKENS_LIMIT", unit = 6)?.let {
                makeWindow(WINDOW_LABEL_WEEK, it)?.let { w -> windows.add(w) }
            }
            findLimit(limits, "TIME_LIMIT", unit = 5, number = 1)?.let {
                makeWindow(WINDOW_LABEL_TOOL, it)?.let { w -> windows.add(w) }
            }

            // 未识别条目回退 generic 行（web：slice(0,3)，label「额度」）
            if (windows.isEmpty()) {
                for (limit in limits.take(3)) {
                    val type = limit.objectValue?.get("type")?.stringValue ?: ""
                    makeWindow(type.ifEmpty { WINDOW_LABEL_GENERIC }, limit)?.let { w -> windows.add(w) }
                }
            }
            return windows
        }

        /**
         * 三元组选择器（iOS findLimit 同构）：type 大小写不敏感、unit/number 精确匹配
         * （number=null = 不约束）。Long 路径读数（JSONValue.Int 32 位值域）。
         */
        private fun findLimit(limits: List<JSONValue>, type: String, unit: Int, number: Int? = null): JSONValue? {
            for (limit in limits) {
                val d = limit.objectValue ?: continue
                val limitType = d["type"]?.stringValue ?: continue
                if (!limitType.equals(type, ignoreCase = true)) continue
                if (d["unit"].longValue()?.toInt() != unit) continue
                if (number != null && d["number"].longValue()?.toInt() != number) continue
                return limit
            }
            return null
        }

        /**
         * 剩余百分比（web vGt 同构）：percentage 为 **0–100 已用百分数** → 剩余 = 100 − pct；
         * v1 条数套餐 percentage 缺席时 remaining/number×100 兜底（分母 number，web
         * vGt 原式——旧 remaining/usage×100 是拍脑袋口径，已校正）。返回 0..100。
         */
        fun remainingPercent(d: Map<String, JSONValue>?): Double? {
            if (d == null) return null
            d["percentage"]?.doubleValue?.let { pct ->
                return (100.0 - pct).coerceIn(0.0, 100.0)
            }
            val remaining = d["remaining"]?.doubleValue
            val number = d["number"]?.doubleValue
            if (remaining != null && number != null && number > 0) {
                return (remaining / number * 100.0).coerceIn(0.0, 100.0)
            }
            return null
        }

        private fun makeWindow(label: String, limit: JSONValue): CodingPlanQuotaWindow? {
            val d = limit.objectValue ?: return null
            val used = d["currentValue"].longValue()?.toInt()
            var window = CodingPlanQuotaWindow(
                level = d["type"]?.stringValue ?: "window",
                label = label,
                used = used,
                limit = d["usage"].longValue()?.toInt(),
                unit = if (used != null) "条" else null,
                percentUsed = null,
                resetsAtText = null,
            )
            val remaining = remainingPercent(d)
            if (remaining != null) {
                // struct: percentRemaining = 1 − percentUsed/100（0–1 小数）
                window = window.copy(percentUsed = (100.0 - remaining) / 100.0)
            }
            d["nextResetTime"].longValue()?.takeIf { it > 0 }?.let {
                window = window.copy(resetsAtText = formatTime(it))
            }
            return window
        }

        /**
         * 重置卡全量解析：5h/周两组可用卡 + 最早过期 + 最近使用历史。
         * 注意：usage.resetsAtText 保留额度窗口自身 nextResetTime（自动重置时间）；
         * 重置卡的 expireAt 是「卡过期时间」，语义不同——不能覆写（曾致用户卡显示
         * 卡过期日当额度重置日，数据对不上）。
         */
        fun parseResetStatus(dict: Map<String, JSONValue>): CodingPlanResetCards {
            val fiveHour = dict["availableFiveHourResets"]?.arrayValue ?: emptyList()
            val week = dict["availableWeekResets"]?.arrayValue ?: emptyList()
            fun earliestText(group: List<JSONValue>): String? = group
                .mapNotNull { it.objectValue?.get("expireAt").longValue() }
                .filter { it > 0 }
                .minOrNull()
                ?.let(::formatTime)
            var cards = CodingPlanResetCards(
                fiveHourCount = fiveHour.size,
                weekCount = week.size,
                fiveHourEarliestText = earliestText(fiveHour),
                weekEarliestText = earliestText(week),
            )
            dict["latestFiveHourResetHistory"]?.objectValue?.get("usedAt").longValue()?.takeIf { it > 0 }?.let {
                cards = cards.copy(lastFiveHourUsedText = formatTime(it))
            }
            dict["latestWeekResetHistory"]?.objectValue?.get("usedAt").longValue()?.takeIf { it > 0 }?.let {
                cards = cards.copy(lastWeekUsedText = formatTime(it))
            }
            return cards
        }

        /**
         * getEntitlementSnapshot 回执解析。**禁找不存在的 entitlements[]**（B-5/甲-9）：
         * 条目 = subscription.details[]（authenticated 显式 false 时无订阅可言——web $Fe
         * 同构过滤；键缺席视为已认证）；过期条目按 expireTime 过滤（ISO 字符串/毫秒数字
         * 宽容）；名称取 productName，缺席回落 productId；档位 = quota.level，回落首个
         * 条目名（web IAt 同构）；顶层 remaining 仅在场判定（单位语义未取证原样透出）；
         * mcpQuota.aggregate 原始形态透出（§11.2 usage 窗口合并阶段消费）。
         */
        fun parseEntitlementSnapshot(json: JSONValue?): CodingPlanEntitlementInfo {
            val dict = json?.objectValue
            var info = CodingPlanEntitlementInfo(tier = null, entitlements = emptyList())
            info = info.copy(unavailableReason = dict?.get("unavailableReason")?.stringValue)
            info = info.copy(noPlan = info.unavailableReason == "no_plan")
            val authenticated = dict?.get("authenticated")?.boolValue ?: true
            val details = if (authenticated) {
                dict?.get("subscription")?.objectValue?.get("details")?.arrayValue ?: emptyList()
            } else {
                emptyList()
            }
            val entitlements = details.mapNotNull { item ->
                val d = item.objectValue ?: return@mapNotNull null
                val name = d["productName"]?.stringValue ?: d["productId"]?.stringValue
                if (name.isNullOrEmpty()) return@mapNotNull null
                if (entitlementEntryExpired(d["expireTime"])) return@mapNotNull null
                val detail = entitlementEntryDate(d["expireTime"])?.let { "${formatTime(it)} 前有效" }
                CodingPlanEntitlement(name = name, value = null, detail = detail)
            }
            // 档位（web IAt 同构）：quota.level 优先，回落首个条目名
            info = info.copy(
                tier = dict?.get("quota")?.objectValue?.get("level")?.stringValue
                    ?: entitlements.firstOrNull()?.name,
                entitlements = entitlements,
            )
            // 顶层 remaining（web 仅做在场判定；单位语义未取证，原样透出不加解释）
            dict?.get("remaining")?.takeIf { it !is JSONValue.Null }?.let { remaining ->
                info = info.copy(
                    remainingText = remaining.stringValue
                        ?: remaining.longValue()?.toString()
                        ?: remaining.doubleValue?.let { String.format("%g", it) },
                )
            }
            // mcpQuota 原始形态透出（§11.2：usage 窗口合并阶段消费）
            dict?.get("mcpQuota")?.objectValue?.get("aggregate")?.let {
                info = info.copy(mcpQuotaAggregate = it)
            }
            return info
        }

        /** subscription.details[].expireTime → epoch 毫秒（web Date.parse 同构 = ISO 字符串；
         * 数字形态宽容按毫秒时间戳）。返回 null = 无法解析（不过滤、不展示）。 */
        fun entitlementEntryDate(value: JSONValue?): Long? {
            if (value == null) return null
            value.longValue()?.takeIf { it > 0 }?.let { return it }
            value.stringValue?.let { text ->
                try {
                    return Instant.parse(text).toEpochMilli()
                } catch (e: Exception) {
                    // fallthrough
                }
                try {
                    return OffsetDateTime.parse(text).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    // fallthrough
                }
            }
            return null
        }

        fun entitlementEntryExpired(value: JSONValue?): Boolean {
            val date = entitlementEntryDate(value) ?: return false
            return date <= System.currentTimeMillis()
        }

        /**
         * 设置回执三形态宽容解析（全部【宽容·未取证】；P2ExtrasViews.parseEntries 直译）：
         * A 顶层数组 [{key|name|id, value…}]；B 包裹对象 {settings|values|data|config|
         * items|entries: object 或 array}；C 整对象视为 {key: value} 映射。
         * 只收 bool/string/int/double 标量（对象/数组/空值不臆造渲染，直接跳过）。
         */
        fun parseSettingEntries(json: JSONValue?): List<DesktopSettingEntry> {
            val raw = ArrayList<Triple<String, JSONValue, Boolean>>() // (key, value, readonly)
            fun collectScalar(key: String, value: JSONValue): Boolean {
                val readonly = when (value) {
                    is JSONValue.Bool, is JSONValue.Int, is JSONValue.Double, is JSONValue.Str -> false
                    else -> return false
                }
                raw.add(Triple(key, value, readonly))
                return true
            }
            val array = json?.arrayValue
            if (array != null) {
                // A：顶层数组条目 {key|name|id, value…}
                for (item in array) {
                    val d = item.objectValue ?: continue
                    val key = d["key"]?.stringValue ?: d["name"]?.stringValue ?: d["id"]?.stringValue
                        ?: continue
                    val value = d["value"] ?: continue
                    collectScalar(key, value)
                }
            } else {
                val dict = json?.objectValue
                if (dict != null) {
                    var wrapped = false
                    for (wrapper in SETTING_WRAPPER_KEYS) {
                        val inner = dict[wrapper] ?: continue
                        val innerArray = inner.arrayValue
                        if (innerArray != null) {
                            for (item in innerArray) {
                                val d = item.objectValue ?: continue
                                val key = d["key"]?.stringValue
                                    ?: d["name"]?.stringValue
                                    ?: d["id"]?.stringValue
                                    ?: continue
                                val value = d["value"] ?: continue
                                collectScalar(key, value)
                            }
                            wrapped = true
                        } else {
                            val innerDict = inner.objectValue
                            if (innerDict != null) {
                                for ((k, v) in innerDict) collectScalar(k, v)
                                wrapped = true
                            }
                        }
                        if (wrapped) break
                    }
                    if (!wrapped) {
                        // C：整对象视为 {key: value} 映射
                        for ((k, v) in dict) collectScalar(k, v)
                    }
                }
            }
            return raw.map { (key, value, readonly) -> DesktopSettingEntry(key, value, readonly) }
        }
    }
}

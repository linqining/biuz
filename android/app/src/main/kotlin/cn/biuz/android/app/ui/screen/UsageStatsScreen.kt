package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.EntitlementUiModel
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.AppSession.Companion.CodingPlanResetType
import cn.biuz.android.connection.CodingPlanQuotaWindow
import cn.biuz.android.connection.CodingPlanResetCards
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 屏 12·用量统计 / 权益页（iOS UsageStatsView 同位）：
 * - **Coding Plan 额度卡**：全部窗口分行（5 小时/每周/工具调用/MCP 配额；进度条 +
 *   用量/上限 + 重置时间），档位角标 = **quota.level**，重置卡摘要（5h/周分列）；
 * - **套餐权益子块**：条目唯一来源 = **subscription.details[]**（禁找不存在的
 *   entitlements[]——B-5 否定性结论），档位头回落首条 productName，顶层
 *   **remaining** 仅在场判定原样透出；noPlan 空态 / 读取失败诚实行 / 空回执不渲染
 *   三分支（[EntitlementUiModel] 纯投影，JVM 单测锁定形状契约）；
 * - **重置机会卡**：5 小时/周卡分档使用按钮（无卡置灰）→ **二次确认弹层**（扣费/
 *   消耗接口红线：写明重置哪个窗口、剩余卡数、核销不可撤销）→ 确认后才发
 *   AppSession.useCodingPlanResetCard 三步桌面代执行（§5-15：确认回调只用呈现期
 *   捕获的 type，禁回读宿主待定态）；
 * - 数据加载（iOS reload 同构）：主卡先拉（refreshCodingPlanUsage 快路径，到达即
 *   渲染），权益/枚举链 refreshDesktopReadonlyInfoInBackground 后台补全；
 *   未连接空态如实（无演示数据红线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(
    session: AppSession,
    onBack: () -> Unit,
    onGoConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by session.mode.collectAsStateWithLifecycle()
    val usage by session.codingPlanUsage.collectAsStateWithLifecycle()
    val entitlements by session.codingPlanEntitlements.collectAsStateWithLifecycle()
    val isConnected = mode is AppSession.Mode.Connected

    var refreshing by remember { mutableStateOf(false) }
    /** 首拉后仍无用量投影（桌面端未提供/读取失败——iOS errorText 同语义位）。 */
    var usageMissing by remember { mutableStateOf(false) }

    // 数据加载（iOS UsageStatsView.reload 同构：主卡先行，其余链后台补全）
    suspend fun reload() {
        refreshing = true
        usageMissing = false
        if (session.isConnected) {
            session.refreshCodingPlanUsage()
            usageMissing = session.codingPlanUsage.value == null
        }
        refreshing = false
        session.refreshDesktopReadonlyInfoInBackground()
    }

    LaunchedEffect(isConnected) {
        if (isConnected) reload()
    }

    // 重置卡使用流程（扣费接口：确认弹层把关后才下发）
    var pendingResetType by remember { mutableStateOf<CodingPlanResetType?>(null) }
    var claiming by remember { mutableStateOf(false) }
    var claimNotice by remember { mutableStateOf<String?>(null) }

    fun performResetUse(type: CodingPlanResetType) {
        scope.launch {
            claiming = true
            claimNotice = null
            val message = session.useCodingPlanResetCard(type)
            claiming = false
            claimNotice = message ?: context.getString(R.string.usage_claim_success)
            if (message == null) reload()
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.usage_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            !isConnected -> UsageNotConnected(onGoConnect = onGoConnect)

            refreshing && usage == null && entitlements == null -> UsageLoading()

            // 读面失败三态：失败空态 + 重载 CTA（usage 在场时走 last-good 列表共存）
            !refreshing && usageMissing && usage == null -> UsageEmptyError(
                onReload = { scope.launch { reload() } },
            )

            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { reload() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BiuZSpacing.lg),
                ) {
                    Spacer(Modifier.height(BiuZSpacing.lg))
                    claimNotice?.let { notice ->
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (notice == context.getString(R.string.usage_claim_success)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = BiuZSpacing.sm)
                                .testTag("12-usage-claim-notice"),
                        )
                    }
                    CodingPlanCard(
                        usage = usage,
                        entitlements = entitlements,
                    )
                    // 重置机会卡（任一组有卡才呈现；分档使用按钮）
                    if (EntitlementUiModel.hasResetOpportunity(usage?.resetCards)) {
                        Spacer(Modifier.height(BiuZSpacing.md))
                        ResetOpportunityCard(
                            cards = usage?.resetCards,
                            claiming = claiming,
                            onClaim = { pendingResetType = it },
                        )
                    }
                    Spacer(Modifier.height(BiuZSpacing.xl))
                }
            }
        }
    }

    // 重置卡二次确认弹层（扣费/消耗接口红线；§5-15：确认回调只用呈现期捕获的 type）
    pendingResetType?.let { type ->
        val fiveHourCount = usage?.resetCards?.fiveHourCount ?: 0
        val weekCount = usage?.resetCards?.weekCount ?: 0
        val windowName = when (type) {
            CodingPlanResetType.FIVE_HOUR -> stringResource(R.string.usage_window_5h)
            CodingPlanResetType.WEEK -> stringResource(R.string.usage_window_week)
        }
        AlertDialog(
            onDismissRequest = { pendingResetType = null },
            title = { Text(stringResource(R.string.usage_reset_confirm_title)) },
            text = {
                Text(stringResource(R.string.usage_reset_confirm_msg, windowName, fiveHourCount, weekCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingResetType = null
                        performResetUse(type)
                    },
                    modifier = Modifier.testTag("12-usage-confirm-use"),
                ) {
                    Text(
                        stringResource(R.string.usage_act_confirm_use),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetType = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// MARK: - 未连接空态 / 加载 / 失败空态

@Composable
private fun UsageNotConnected(onGoConnect: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(BiuZSpacing.xl),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.state_not_connected),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = stringResource(R.string.usage_not_connected_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = BiuZText3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.lg))
        Button(
            onClick = onGoConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("12-usage-act-connect"),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.action_connect_desktop))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun UsageLoading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .testTag("12-usage-loading"),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(BiuZSpacing.sm))
        Text(
            text = stringResource(R.string.usage_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = BiuZText3,
        )
    }
}

@Composable
private fun UsageEmptyError(onReload: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(BiuZSpacing.xl),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.usage_no_quota),
            style = MaterialTheme.typography.bodyMedium,
            color = BiuZText3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.lg))
        OutlinedButton(
            onClick = onReload,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("12-usage-act-reload"),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.usage_act_reload))
        }
        Spacer(Modifier.weight(1f))
    }
}

// MARK: - Coding Plan 额度卡（窗口行 + 档位角标 + 重置卡摘要 + 权益子块）

@Composable
private fun CodingPlanCard(
    usage: cn.biuz.android.connection.CodingPlanUsageInfo?,
    entitlements: cn.biuz.android.connection.CodingPlanEntitlementInfo?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.large)
            .padding(BiuZSpacing.md)
            .testTag("12-usage-plan-card"),
    ) {
        Text(
            text = stringResource(R.string.usage_plan_card_title),
            style = MaterialTheme.typography.titleSmall,
            color = BiuZText3,
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        when {
            usage != null && usage.windows.isNotEmpty() -> {
                usage.windows.forEach { window ->
                    QuotaWindowRow(window)
                }
                // 套餐档位角标（quota.level："max" 等）
                usage.unitText?.takeIf { it.isNotEmpty() }?.let { level ->
                    Spacer(Modifier.height(BiuZSpacing.xs))
                    Text(
                        text = stringResource(R.string.usage_plan_level_fmt, level),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = BiuZText3,
                        modifier = Modifier.testTag("12-usage-plan-level"),
                    )
                }
                // 重置卡摘要（可使用明细见机会卡；5h/周卡过期时间分开展示——用户裁决
                // 「两类卡作用窗口不同，不合并取最早」）
                if (EntitlementUiModel.hasResetCardsSummary(usage.resetCards)) {
                    Spacer(Modifier.height(BiuZSpacing.xs))
                    ResetCardsSummary(cards = usage.resetCards)
                }
            }

            usage != null -> {
                // 旧形态兜底（无 limits 数组的版本）
                usage.percentRemaining?.let { remaining ->
                    QuotaProgressBar(remainingFraction = remaining)
                    Spacer(Modifier.height(BiuZSpacing.xs))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val used = usage.used
                    val limit = usage.limit
                    if (used != null && limit != null) {
                        Text(
                            text = stringResource(R.string.usage_used_limit_fmt, used, limit, usage.unitText ?: ""),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = BiuZText2,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    usage.resetsAtText?.let { resets ->
                        Text(
                            text = stringResource(R.string.usage_resets_fmt, resets),
                            style = MaterialTheme.typography.labelSmall,
                            color = BiuZText3,
                        )
                    }
                }
            }

            else -> Text(
                text = stringResource(R.string.usage_no_quota),
                style = MaterialTheme.typography.bodyMedium,
                color = BiuZText3,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.sm))
        // 当前套餐权益子块（quota.level + subscription.details + remaining；三态）
        EntitlementSectionBlock(entitlements)
    }
}

/** 单个额度窗口行（label + 重置时间 + 进度条 + 用量/上限 + 剩 N%；12-usage-window-*）。 */
@Composable
private fun QuotaWindowRow(window: CodingPlanQuotaWindow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiuZSpacing.xs)
            .testTag("12-usage-window-${window.level}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = windowLabel(window),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            window.resetsAtText?.let { resets ->
                Text(
                    text = stringResource(R.string.usage_resets_fmt, resets),
                    style = MaterialTheme.typography.labelSmall,
                    color = BiuZText3,
                )
            }
        }
        val remaining = EntitlementUiModel.windowRemainingFraction(window)
        if (remaining != null) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            QuotaProgressBar(remainingFraction = remaining)
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val used = window.used
            val limit = window.limit
            when {
                used != null && limit != null -> Text(
                    text = stringResource(R.string.usage_used_limit_fmt, used, limit, window.unit ?: ""),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = BiuZText2,
                )

                used != null -> Text(
                    text = stringResource(R.string.usage_used_only_fmt, used, window.unit ?: ""),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = BiuZText2,
                )
            }
            Spacer(Modifier.weight(1f))
            if (remaining != null) {
                val low = EntitlementUiModel.isLowRemaining(remaining)
                Text(
                    text = stringResource(R.string.usage_remain_fmt, (remaining * 100).roundToInt()),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (low) BiuZOrange else BiuZText3,
                )
            }
        }
    }
}

@Composable
private fun QuotaProgressBar(remainingFraction: Double) {
    LinearProgressIndicator(
        progress = { remainingFraction.toFloat().coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp),
        color = if (EntitlementUiModel.isLowRemaining(remainingFraction)) {
            BiuZOrange
        } else {
            MaterialTheme.colorScheme.primary
        },
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * 窗口行展示标签：已知投影常量映射资源（双语对账面），未知 type 原样透出
 * （宽容解析 ≠ 协议事实；:store/:connection 窗口标签常量为数据面，UI 不回改）。
 * 注：MCP 窗口数据面标签含品牌字样，呈现层按红线以「MCP 配额」资源替换。
 */
@Composable
private fun windowLabel(window: CodingPlanQuotaWindow): String = when (window.label) {
    "5 小时" -> stringResource(R.string.usage_window_5h)
    "每周" -> stringResource(R.string.usage_window_week)
    "工具调用" -> stringResource(R.string.usage_window_tool)
    "ZCode MCP" -> stringResource(R.string.usage_window_mcp)
    "额度" -> stringResource(R.string.usage_window_generic)
    else -> window.label
}

/** 重置卡摘要（额度卡内两行；过期时间分组展示）。 */
@Composable
private fun ResetCardsSummary(cards: CodingPlanResetCards?) {
    val fiveHourCount = cards?.fiveHourCount ?: 0
    val weekCount = cards?.weekCount ?: 0
    Column(modifier = Modifier.testTag("12-usage-reset-cards-summary")) {
        if (fiveHourCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🎫",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(BiuZSpacing.sm))
                Text(
                    text = stringResource(R.string.usage_reset_card_5h_fmt, fiveHourCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = BiuZText2,
                )
                Spacer(Modifier.weight(1f))
                cards?.fiveHourEarliestText?.takeIf { fiveHourCount > 0 }?.let { earliest ->
                    Text(
                        text = stringResource(R.string.usage_card_valid_until_fmt, earliest),
                        style = MaterialTheme.typography.labelSmall,
                        color = BiuZText3,
                    )
                }
            }
        }
        if (weekCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🎫",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(BiuZSpacing.sm))
                Text(
                    text = stringResource(R.string.usage_reset_card_week_fmt, weekCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = BiuZText2,
                )
                Spacer(Modifier.weight(1f))
                cards?.weekEarliestText?.takeIf { weekCount > 0 }?.let { earliest ->
                    Text(
                        text = stringResource(R.string.usage_card_valid_until_fmt, earliest),
                        style = MaterialTheme.typography.labelSmall,
                        color = BiuZText3,
                    )
                }
            }
        }
    }
}

// MARK: - 套餐权益子块（quota.level + subscription.details + remaining 三态）

@Composable
private fun EntitlementSectionBlock(info: cn.biuz.android.connection.CodingPlanEntitlementInfo?) {
    val section = EntitlementUiModel.entitlementSection(info)
    when {
        section.noPlan -> {
            // web 空态判定（sidebar.usage.plan.noPlan 同位）：未订阅如实呈现
            Text(
                text = "ⓘ " + stringResource(R.string.usage_entitle_no_plan),
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BiuZSpacing.xs)
                    .testTag("12-usage-entitlement-no-plan"),
            )
        }

        section.hasRows -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, BiuZRadius.medium)
                    .padding(BiuZSpacing.sm)
                    .testTag("12-usage-entitlement-section"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = section.tier?.let { tier ->
                            stringResource(R.string.usage_entitle_header_fmt, tier)
                        } ?: stringResource(R.string.usage_entitle_header),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = BiuZText3,
                        modifier = Modifier.testTag("12-usage-entitlement-header"),
                    )
                    Spacer(Modifier.weight(1f))
                    // 顶层 remaining（web 仅在场判定；单位语义未取证，原样透出不加解释）
                    section.remaining?.let { remaining ->
                        Text(
                            text = stringResource(R.string.usage_entitle_remaining_fmt, remaining),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = BiuZText3,
                        )
                    }
                }
                Spacer(Modifier.height(BiuZSpacing.xs))
                section.rows.forEach { row ->
                    EntitlementRowView(row)
                }
                if (section.moreCount > 0) {
                    Spacer(Modifier.height(BiuZSpacing.xs))
                    Text(
                        text = stringResource(R.string.usage_entitle_more),
                        style = MaterialTheme.typography.labelSmall,
                        color = BiuZText3,
                        modifier = Modifier.testTag("12-usage-entitlement-more"),
                    )
                }
            }
        }

        section.unavailable -> {
            // 读取失败/未取到：单行诚实提示（空回执整体不渲染，不到这里）
            Text(
                text = "ⓘ " + stringResource(R.string.usage_entitle_unavailable),
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BiuZSpacing.xs)
                    .testTag("12-usage-entitlement-unavailable"),
            )
        }
    }
}

/** 权益行：✓ + 名称（+有效期描述）+「已含」角标（iOS entitlementRow 同构）。 */
@Composable
private fun EntitlementRowView(row: EntitlementUiModel.EntitlementRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiuZSpacing.xs)
            .testTag("12-usage-entitlement-${row.name}"),
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(BiuZSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText2,
                maxLines = 1,
            )
            row.detail?.takeIf { it.isNotEmpty() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = BiuZText3,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.size(BiuZSpacing.sm))
        Text(
            text = stringResource(R.string.usage_entitle_included),
            style = MaterialTheme.typography.labelSmall,
            color = BiuZText3,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(999.dp))
                .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs),
        )
    }
}

// MARK: - 重置机会卡（分档使用按钮 + 二次确认入口）

@Composable
private fun ResetOpportunityCard(
    cards: cn.biuz.android.connection.CodingPlanResetCards?,
    claiming: Boolean,
    onClaim: (CodingPlanResetType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.medium)
            .padding(BiuZSpacing.md)
            .testTag("12-usage-reset-card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "⏱", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(BiuZSpacing.sm))
            Text(
                text = stringResource(R.string.usage_reset_opp_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        val fiveHourCount = cards?.fiveHourCount ?: 0
        val weekCount = cards?.weekCount ?: 0
        // 过期时间分组展示（5h 卡只重置 5h 窗、周卡只重置周窗——用户裁决不合并取最早）
        val expireText = cards?.fiveHourEarliestText?.takeIf { fiveHourCount > 0 }
            ?: cards?.weekEarliestText?.takeIf { weekCount > 0 }
        expireText?.let { earliest ->
            Text(
                text = stringResource(R.string.usage_reset_opp_expire_fmt, earliest),
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = stringResource(R.string.usage_reset_opp_available_fmt, fiveHourCount, weekCount),
            style = MaterialTheme.typography.labelMedium,
            color = BiuZText2,
        )
        cards?.lastFiveHourUsedText?.let { used ->
            Text(
                text = stringResource(R.string.usage_reset_opp_last_5h_fmt, used),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
            )
        }
        cards?.lastWeekUsedText?.let { used ->
            Text(
                text = stringResource(R.string.usage_reset_opp_last_week_fmt, used),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.sm))
        // 分档使用（.contain 容器对应物：外层 testTag 整体锚 + 两按钮各自锚）
        Row(
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("12-usage-act-claim"),
        ) {
            ClaimButton(
                title = if (claiming) {
                    stringResource(R.string.usage_claiming)
                } else {
                    stringResource(R.string.usage_act_claim_5h)
                },
                enabled = !claiming && fiveHourCount > 0,
                testTagId = "12-usage-act-claim-5h",
                onClick = { onClaim(CodingPlanResetType.FIVE_HOUR) },
                modifier = Modifier.weight(1f),
            )
            ClaimButton(
                title = if (claiming) {
                    stringResource(R.string.usage_claiming)
                } else {
                    stringResource(R.string.usage_act_claim_week)
                },
                enabled = !claiming && weekCount > 0,
                testTagId = "12-usage-act-claim-week",
                onClick = { onClaim(CodingPlanResetType.WEEK) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ClaimButton(
    title: String,
    enabled: Boolean,
    testTagId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = BiuZRadius.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = BiuZText3,
        ),
        modifier = modifier
            .height(44.dp)
            .testTag(testTagId),
    ) {
        Text(title, maxLines = 1)
    }
}

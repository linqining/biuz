package cn.biuz.android.app.ui.screen

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzEmptyState
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.AutomationRecord
import cn.biuz.android.connection.AutomationRunRecord
import cn.biuz.android.connection.AutomationsError
import cn.biuz.android.connection.loadAutomationRuns
import cn.biuz.android.connection.loadAutomations
import kotlinx.coroutines.launch

/**
 * 屏 023 · 自动化（定时任务）—— iOS `RemoteExtrasViews.swift:337` AutomationsView 的
 * Android 对位（G-016）。
 *
 * 数据面：`zcode-agent.listAutomations` / `listAutomationRuns`（只读；创建/启停/删除
 * 写面不接）。四态：未连接（引导 U-7）/ 加载 / 空（错误变体带重试）/ 列表；
 * 卡片点按展开运行历史（首次展开按需拉取，上限 10 条——iOS runs.prefix(10) 同口径）。
 */
@Composable
fun AutomationsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<AutomationRecord>>(emptyList()) }
    var notConnected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var runHistory by remember { mutableStateOf<Map<String, List<AutomationRunRecord>>>(emptyMap()) }

    val mode by session.mode.collectAsStateWithLifecycle()
    val connected = mode is AppSession.Mode.Connected

    suspend fun reload() {
        loading = true
        failure = null
        notConnected = false
        try {
            records = session.loadAutomations()
        } catch (e: AutomationsError.NotConnected) {
            notConnected = true
            records = emptyList()
        } catch (e: AutomationsError.Failed) {
            failure = e.message
        } catch (e: Throwable) {
            failure = e.message ?: e::class.simpleName ?: ""
        } finally {
            loading = false
        }
    }

    fun expand(record: AutomationRecord) {
        expandedId = if (expandedId == record.id) null else record.id
        if (expandedId != null && runHistory[record.id] == null) {
            scope.launch {
                try {
                    val runs = session.loadAutomationRuns(record.id)
                    runHistory = runHistory + (record.id to runs)
                } catch (e: AutomationsError.NotConnected) {
                    // 展开态拉历史失败静默保留「暂无运行记录」占位（读面宽容；主清单仍在）
                } catch (e: Throwable) {
                    // 同上：历史失败不上屏错误卡（iOS `try?` 同口径——运行历史是附属读面）
                }
            }
        }
    }

    LaunchedEffect(connected) { if (connected) reload() }

    Column(modifier = Modifier.fillMaxSize().background(BiuZBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp),
            )
            Text(
                text = stringResource(R.string.automation_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.desktop_not_connected),
                hint = stringResource(R.string.automation_not_connected_hint),
                modifier = Modifier.testTag("14-not-connected"),
            )

            loading && records.isEmpty() -> BiuzLoadingState(
                text = stringResource(R.string.automation_loading),
                tag = "14-loading",
            )

            records.isEmpty() -> BiuzEmptyState(
                title = failure ?: stringResource(R.string.automation_none),
                hint = if (failure == null) {
                    stringResource(R.string.automation_none_hint)
                } else {
                    stringResource(R.string.automation_error_hint)
                },
                modifier = Modifier.testTag("14-empty"),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = BiuZSpacing.md, end = BiuZSpacing.md,
                    top = BiuZSpacing.sm, bottom = BiuZSpacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.automation_summary, records.size),
                        fontSize = 11.sp,
                        color = BiuZText3,
                        modifier = Modifier.padding(horizontal = 2.dp).testTag("14-summary"),
                    )
                }
                items(records, key = { it.id }) { record ->
                    AutomationCardView(
                        record = record,
                        expanded = expandedId == record.id,
                        runs = runHistory[record.id].orEmpty(),
                        onClick = { expand(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationCardView(
    record: AutomationRecord,
    expanded: Boolean,
    runs: List<AutomationRunRecord>,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BiuZRadius.medium)
                .background(BiuZBgCard)
                .border(1.dp, BiuZBorder, BiuZRadius.medium)
                .clickable { onClick() }
                .padding(BiuZSpacing.md)
                .heightIn(min = 56.dp)
                .testTag("14-row-${record.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⏱",
                fontSize = 14.sp,
                color = if (record.enabled) BiuZAccent else BiuZText3,
                modifier = Modifier.padding(end = BiuZSpacing.sm),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = record.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BiuZText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = record.scheduleText,
                    fontSize = 11.sp,
                    color = BiuZText3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            record.lastRunAtMs?.let { ms ->
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    fontSize = 11.sp,
                    color = BiuZText3,
                    modifier = Modifier.padding(end = BiuZSpacing.sm),
                )
            }
            Text(
                text = stringResource(
                    if (record.enabled) R.string.automation_enabled else R.string.automation_disabled,
                ),
                fontSize = 10.sp,
                color = if (record.enabled) BiuZAccent else BiuZText3,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (record.enabled) BiuZBgInput else BiuZBg)
                    .border(1.dp, BiuZBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(
                text = if (expanded) "⌄" else "›",
                fontSize = 13.sp,
                color = BiuZText3,
                modifier = Modifier.padding(start = BiuZSpacing.xs),
            )
        }
        if (expanded) {
            RunsCardView(record = record, runs = runs)
        }
    }
}

@Composable
private fun RunsCardView(record: AutomationRecord, runs: List<AutomationRunRecord>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgInput)
            .padding(BiuZSpacing.sm)
            .testTag("14-runs-${record.id}"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.automation_runs_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText3,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.automation_runs_count, record.runCount),
                fontSize = 11.sp,
                color = BiuZText3,
            )
        }
        if (runs.isEmpty()) {
            Text(
                text = stringResource(R.string.automation_runs_empty),
                fontSize = 11.sp,
                color = BiuZText3,
            )
        } else {
            runs.take(10).forEach { run ->
                RunRowView(run = run)
            }
        }
    }
}

@Composable
private fun RunRowView(run: AutomationRunRecord) {
    // 状态词 → 文案与颜色（success/failed/running；未知词原样 + 中性色，不吞信息）
    val (label, tint) = when (run.status) {
        "success" -> stringResource(R.string.automation_status_success) to BiuZAccent
        "failed" -> stringResource(R.string.automation_status_failed) to BiuZRed
        "running" -> stringResource(R.string.automation_status_running) to BiuZBlue
        else -> run.status to BiuZText3
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(horizontal = BiuZSpacing.sm)
            .testTag("14-run-${run.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(tint)
                .size(6.dp),
        )
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
        run.startedAtMs?.let { ms ->
            Text(
                text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(ms)),
                fontSize = 11.sp,
                color = BiuZText3,
            )
        }
        Spacer(Modifier.weight(1f))
        run.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
            Text(
                text = summary,
                fontSize = 11.sp,
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}

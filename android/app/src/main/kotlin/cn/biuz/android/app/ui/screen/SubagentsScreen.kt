package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.SubagentRow
import cn.biuz.android.connection.SubagentsError
import cn.biuz.android.connection.SubagentsList
import cn.biuz.android.connection.loadSubagents
import cn.biuz.android.connection.setSubagentEnabled
import kotlinx.coroutines.launch

/**
 * 屏 026 · 子智能体——只读清单（web SubagentsSection 的移动对位）。
 *
 * 数据面：`subagents.list` {workspacePath, workspaceIdentity?, provider:"glm",
 * mode:"allRuntimeScopes"}【实证·上游仓 subagents.ts:15-41 + SubagentsSection.tsx:1404-1409】。
 * 分组 = web 口径：已安装（user scope）/ 内置子智能体 / 插件 profile；
 * 行展示 name · description 摘要 · 工具数（未配置 = 全部工具）· 启用徽标。
 * 只读：createAgent/updateAgent/deleteAgent/setEnabled/set*ModelOverride 写面
 * gate 拦截（第一波不接，登记 backlog）。
 */
@Composable
fun SubagentsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var list by remember { mutableStateOf<SubagentsList?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 写面波次②：启停开关（仅 user scope 可编辑 agent——web supportsEnabledToggle 同口径）
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var actionNoticeIsError by remember { mutableStateOf(false) }
    var busyAgentId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        failure = null
        notConnected = false
        try {
            val workspace = session.storeSurface.value.workspace
            list = session.loadSubagents(workspace?.path, workspace?.workspaceIdentity)
        } catch (e: SubagentsError.NotConnected) {
            notConnected = true
            list = null
        } catch (e: SubagentsError.Failed) {
            failure = e.message
        } catch (e: Throwable) {
            failure = e.message ?: e::class.simpleName ?: ""
        } finally {
            loading = false
        }
    }

    val mode by session.mode.collectAsStateWithLifecycle()
    val connected = mode is AppSession.Mode.Connected
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
                text = stringResource(R.string.subagents_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.desktop_not_connected),
                hint = stringResource(R.string.subagents_not_connected_hint),
                modifier = Modifier.testTag("26-not-connected"),
            )

            loading && list == null -> BiuzLoadingState(
                text = stringResource(R.string.subagents_loading),
                tag = "26-loading",
            )

            failure != null && list == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.md),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.desktop_load_failed) + " · " + failure,
                    onRetry = { scope.launch { reload() } },
                    tag = "26-load-error",
                )
            }

            (list?.agents?.isEmpty() ?: true) -> BiuzEmptyState(
                title = stringResource(R.string.subagents_none),
                hint = stringResource(R.string.subagents_none_hint),
                modifier = Modifier.testTag("26-empty"),
            )

            else -> {
                val agents = list?.agents.orEmpty()
                if (actionNotice != null) {
                    BiuzInlineNotice(
                        message = actionNotice.orEmpty(),
                        kind = if (actionNoticeIsError) cn.biuz.android.app.ui.components.BiuzNoticeKind.Error
                        else cn.biuz.android.app.ui.components.BiuzNoticeKind.Info,
                        modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
                        tag = "26-action-notice",
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = BiuZSpacing.md, end = BiuZSpacing.md,
                        top = BiuZSpacing.sm, bottom = BiuZSpacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    // 分组（web 口径）：已安装（user）/ 内置 / 插件 profile
                    val userAgents = agents.filter { it.source == "user" }
                    val builtIn = agents.filter { it.source == "built-in" }
                    val pluginAgents = agents.filter { it.source == "plugin" }
                    listOf(
                        Triple("user", userAgents, R.string.subagents_group_user),
                        Triple("built-in", builtIn, R.string.subagents_group_builtIn),
                        Triple("plugin", pluginAgents, R.string.subagents_group_plugin),
                    ).forEach { (key, rows, labelRes) ->
                        if (rows.isEmpty()) return@forEach
                        item(key = "group-$key") {
                            Text(
                                text = stringResource(labelRes, rows.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BiuZText3,
                                modifier = Modifier.padding(top = BiuZSpacing.xs),
                            )
                        }
                        items(rows, key = { "$key-${it.id}" }) { agent ->
                            SubagentRowView(
                                agent = agent,
                                busy = busyAgentId == agent.id,
                                onToggle = { newValue ->
                                    if (busyAgentId != null) return@SubagentRowView
                                    busyAgentId = agent.id
                                    scope.launch {
                                        val error = session.setSubagentEnabled(agent.id, newValue)
                                        busyAgentId = null
                                        actionNotice = error ?: "已${if (newValue) "启用" else "停用"} ${agent.name}"
                                        actionNoticeIsError = error != null
                                        if (error != null) reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentRowView(
    agent: SubagentRow,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("26-row-${agent.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = agent.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 启停开关（仅 user scope；web supportsEnabledToggle 同口径——
            // built-in/workspace/plugin 行不给开关）。开关不即时落本地：失败走 reload 回弹。
            if (agent.source == "user") {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                        strokeWidth = 2.dp,
                        color = BiuZAccent,
                    )
                } else {
                    androidx.compose.material3.Switch(
                        checked = agent.enabled,
                        onCheckedChange = { onToggle(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = BiuZAccent),
                        modifier = Modifier.padding(start = 8.dp).testTag("26-toggle-${agent.id}"),
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        if (agent.enabled) R.string.automation_enabled else R.string.automation_disabled,
                    ),
                    fontSize = 10.sp,
                    color = if (agent.enabled) BiuZAccent else BiuZText3,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (agent.description.isNotEmpty()) {
            Text(
                text = agent.description,
                fontSize = 12.sp,
                color = BiuZText3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (agent.toolsAll) {
                stringResource(R.string.subagents_tools_all)
            } else {
                stringResource(R.string.subagents_tools_count, agent.toolsCount)
            },
            fontSize = 10.sp,
            color = BiuZText3,
        )
    }
}

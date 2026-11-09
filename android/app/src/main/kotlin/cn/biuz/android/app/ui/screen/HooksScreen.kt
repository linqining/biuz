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
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.HookGroup
import cn.biuz.android.connection.HookRow
import cn.biuz.android.connection.HooksError
import cn.biuz.android.connection.HooksSnapshot
import cn.biuz.android.connection.loadHooks
import kotlinx.coroutines.launch

/**
 * 屏 024 · 钩子——iOS/Android 双端新增的只读清单（web HooksSection 的移动对位）。
 *
 * 数据面：`hooks.loadHooks` {workspacePath, workspaceIdentity?, scope:"all"}
 * 【实证·上游仓 hooks.ts:10-21】。分组 = web HooksList 三组口径：已安装（zcode
 * 可编辑）/ 插件 / 旧版；行展示 event · matcher · 运行方式 · 命令摘要；
 * trust 徽标按 requiresWorkspaceHookTrust（workspaceHook 在场且非 trusted_persistent）。
 * 只读：saveHooks/grantWorkspaceHookTrust 写面 gate 拦截（第一波不接，登记 backlog）。
 */
@Composable
fun HooksScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<HooksSnapshot?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        failure = null
        notConnected = false
        try {
            val workspace = session.storeSurface.value.workspace
            snapshot = session.loadHooks(workspace?.path, workspace?.workspaceIdentity)
        } catch (e: HooksError.NotConnected) {
            notConnected = true
            snapshot = null
        } catch (e: HooksError.Failed) {
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
                text = stringResource(R.string.hooks_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.desktop_not_connected),
                hint = stringResource(R.string.hooks_not_connected_hint),
                modifier = Modifier.testTag("24-not-connected"),
            )

            loading && snapshot == null -> BiuzLoadingState(
                text = stringResource(R.string.hooks_loading),
                tag = "24-loading",
            )

            failure != null && snapshot == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.md),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.desktop_load_failed) + " · " + failure,
                    onRetry = { scope.launch { reload() } },
                    tag = "24-load-error",
                )
            }

            (snapshot?.hooks?.isEmpty() ?: true) -> BiuzEmptyState(
                title = stringResource(R.string.hooks_none),
                hint = stringResource(R.string.hooks_none_hint),
                modifier = Modifier.testTag("24-empty"),
            )

            else -> {
                val hooks = snapshot?.hooks.orEmpty()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = BiuZSpacing.md, end = BiuZSpacing.md,
                        top = BiuZSpacing.sm, bottom = BiuZSpacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    item {
                        Text(
                            text = stringResource(
                                if (snapshot?.hooksEnabled == true) {
                                    R.string.hooks_summary_enabled
                                } else {
                                    R.string.hooks_summary_disabled
                                },
                                hooks.size,
                            ),
                            fontSize = 11.sp,
                            color = BiuZText3,
                            modifier = Modifier.padding(horizontal = 2.dp).testTag("24-summary"),
                        )
                    }
                    listOf(
                        HookGroup.installed to R.string.hooks_group_installed,
                        HookGroup.plugin to R.string.hooks_group_plugin,
                        HookGroup.legacy to R.string.hooks_group_legacy,
                    ).forEach { (group, labelRes) ->
                        val groupHooks = hooks.filter { it.group == group }
                        if (groupHooks.isEmpty()) return@forEach
                        item(key = "group-$group") {
                            Text(
                                text = stringResource(labelRes, groupHooks.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BiuZText3,
                                modifier = Modifier.padding(top = BiuZSpacing.xs),
                            )
                        }
                        items(groupHooks, key = { "${group.name}-${it.id}" }) { hook ->
                            HookRowView(hook)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HookRowView(hook: HookRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("24-row-${hook.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hook.event,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            hook.matcher?.takeIf { it.isNotEmpty() }?.let { matcher ->
                Text(
                    text = " · $matcher",
                    fontSize = 11.sp,
                    color = BiuZText3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(
                    if (hook.enabled) R.string.automation_enabled else R.string.automation_disabled,
                ),
                fontSize = 10.sp,
                color = if (hook.enabled) BiuZAccent else BiuZText3,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = hook.command.ifEmpty { stringResource(R.string.hooks_no_command) },
            fontSize = 11.sp,
            color = BiuZText3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    if (hook.type == "process") R.string.hooks_type_process else R.string.hooks_type_command,
                ),
                fontSize = 10.sp,
                color = BiuZText3,
            )
            if (hook.needsTrust) {
                Text(
                    text = stringResource(R.string.hooks_needs_trust),
                    fontSize = 10.sp,
                    color = BiuZOrange,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

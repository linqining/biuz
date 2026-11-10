package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.deleteProject
import cn.biuz.android.connection.deleteProjectData
import cn.biuz.android.connection.measureProjectData
import cn.biuz.android.connection.ProjectRow
import cn.biuz.android.connection.ProjectsError
import cn.biuz.android.connection.ProjectsTree
import cn.biuz.android.connection.loadProjectTree
import cn.biuz.android.connection.restoreProject
import kotlinx.coroutines.launch

/**
 * 屏 018 · 项目管理——只读清单第一波（web ProjectManagement 的移动对位）。
 *
 * 数据面：`project.readTree` {includeDeleted:true, includeDeletedTasks:true}
 * 【实证·上游仓 project-tree-reader.ts:19-25 + repository-contract.ts:35-41】。
 * 上区 = 活跃项目（名称 · 根路径 · 任务计数 · 置顶/目录缺失徽标）；
 * 下区 = 已移除的项目（deleted=true，web「已移除的项目」区同源）；
 * 页首 intro 摘自 web projectManagement.intro 原文。
 * 写面（deleteProject/restoreProject/measureProjectData/deleteProjectData）
 * 第一波不接（登记 backlog）；已移除区的「恢复」随写面波次接线。
 */
@Composable
fun ProjectsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var tree by remember { mutableStateOf<ProjectsTree?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 写面（P-009 写面波次①）：移除/恢复——返回 null=成功、非 null=错误文案（写面禁静默）
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var actionNoticeIsError by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectRow?>(null) }
    var pendingPurge by remember { mutableStateOf<ProjectRow?>(null) }
    // 写面波次④：同时删除数据（先 measure 显示大小——null=服务不可用隐藏选项，
    // web :98/:120-123 同口径不给 0 值假象）
    var deleteDataChecked by remember { mutableStateOf(false) }
    var measuredBytes by remember { mutableStateOf<Long?>(null) }

    suspend fun reload() {
        loading = true
        failure = null
        notConnected = false
        try {
            tree = session.loadProjectTree()
        } catch (e: ProjectsError.NotConnected) {
            notConnected = true
            tree = null
        } catch (e: ProjectsError.Failed) {
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
                text = stringResource(R.string.projects_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.desktop_not_connected),
                hint = stringResource(R.string.projects_not_connected_hint),
                modifier = Modifier.testTag("18-not-connected"),
            )

            loading && tree == null -> BiuzLoadingState(
                text = stringResource(R.string.projects_loading),
                tag = "18-loading",
            )

            failure != null && tree == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.md),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.desktop_load_failed) + " · " + failure,
                    onRetry = { scope.launch { reload() } },
                    tag = "18-load-error",
                )
            }

            tree != null -> {
                val projects = tree?.projects.orEmpty()
                val active = projects.filter { !it.deleted }
                val removed = projects.filter { it.deleted }
                val counts = tree?.taskCountByProject ?: emptyMap()
                if (actionNotice != null) {
                    BiuzInlineNotice(
                        message = actionNotice.orEmpty(),
                        kind = if (actionNoticeIsError) cn.biuz.android.app.ui.components.BiuzNoticeKind.Error
                        else cn.biuz.android.app.ui.components.BiuzNoticeKind.Info,
                        modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
                        tag = "18-action-notice",
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
                    item {
                        Text(
                            text = stringResource(R.string.projects_intro),
                            fontSize = 11.sp,
                            color = BiuZText3,
                            modifier = Modifier.padding(horizontal = 2.dp).testTag("18-intro"),
                        )
                    }
                    if (active.isEmpty() && removed.isEmpty()) {
                        item {
                            BiuzEmptyState(
                                title = stringResource(R.string.projects_none),
                                hint = stringResource(R.string.projects_none_hint),
                                modifier = Modifier.testTag("18-empty"),
                            )
                        }
                    }
                    items(active, key = { it.id }) { project ->
                        ProjectRowView(
                            project = project,
                            taskCount = counts[project.id] ?: 0,
                            onRemove = { pendingDelete = project },
                        )
                    }
                    if (removed.isNotEmpty()) {
                        item(key = "removed-header") {
                            Text(
                                text = stringResource(R.string.projects_removed_title, removed.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BiuZText3,
                                modifier = Modifier.padding(top = BiuZSpacing.xs),
                            )
                        }
                        items(removed, key = { "removed-${it.id}" }) { project ->
                            RemovedProjectRowView(
                                project = project,
                                onRestore = {
                                    scope.launch {
                                        val error = session.restoreProject(project.id, project.hostId)
                                        actionNotice = error ?: "已恢复 ${project.name}"
                                        actionNoticeIsError = error != null
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 移除确认（软删可撤销——web ProjectRemovalDialog 的「删数据」选项未接：v1 只做
    // 侧栏移除，measureProjectData/deleteProjectData 登记后续波次）
    pendingDelete?.let { project ->
        val removeTitle = stringResource(R.string.projects_remove_title, project.name)
        val removeMessage = stringResource(R.string.projects_remove_message)
        val removeConfirmText = stringResource(R.string.projects_remove_confirm)
        val cancelText = stringResource(R.string.desktop_act_cancel)
        val removedTemplate = "已移除 " + project.name
        // 写面波次④：移除弹层呈现时测量数据大小（hostId=local 才可删数据——
        // web useProjectManagement.ts:43-44 数据候选口径）
        LaunchedEffect(project.id) {
            deleteDataChecked = false
            measuredBytes = null
            if (project.hostId == "local") {
                measuredBytes = session.measureProjectData(listOf(project.id), project.hostId)?.totalBytes
            }
        }
        val canDeleteData = measuredBytes != null
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(removeTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    Text(removeMessage)
                    if (canDeleteData) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(BiuZRadius.small)
                                .clickable { deleteDataChecked = !deleteDataChecked }
                                .padding(vertical = 4.dp)
                                .testTag("18-check-delete-data"),
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = deleteDataChecked,
                                onCheckedChange = { deleteDataChecked = it },
                            )
                            Text(
                                text = stringResource(
                                    R.string.projects_delete_data_option,
                                    formatBytes(measuredBytes ?: 0),
                                ),
                                fontSize = 13.sp,
                                color = BiuZText2,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = project
                    val withData = deleteDataChecked
                    pendingDelete = null
                    scope.launch {
                        val error = session.deleteProject(target.id, target.hostId)
                        if (error == null && withData) {
                            // 二段破坏性确认（「删除 N」不可恢复——web ProjectRemovalDialog
                            // 的 .data.word 确认口径）
                            pendingPurge = target
                        } else {
                            actionNotice = error ?: removedTemplate
                            actionNoticeIsError = error != null
                        }
                        reload()
                    }
                }) { Text(removeConfirmText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(cancelText)
                }
            },
        )
    }

    // 彻底删除数据二段确认（破坏性、不可恢复——web confirm.data.word「删除」
    // 强确认口径；running/referenced 拒绝三态由 deleteProjectData 返回文案上屏）
    pendingPurge?.let { project ->
        val bytes = measuredBytes ?: 0
        val purgeTitle = stringResource(R.string.projects_purge_title, project.name)
        val purgeMessage = stringResource(R.string.projects_purge_message)
        val purgeConfirmText = stringResource(R.string.projects_purge_confirm, formatBytes(bytes))
        val cancelText2 = stringResource(R.string.desktop_act_cancel)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text(purgeTitle) },
            text = { Text(purgeMessage) },
            confirmButton = {
                TextButton(onClick = {
                    val target = project
                    pendingPurge = null
                    scope.launch {
                        val error = session.deleteProjectData(target.id, target.hostId)
                        actionNotice = error ?: "已删除 ${target.name} 及其数据"
                        actionNoticeIsError = error != null
                        reload()
                    }
                }) { Text(purgeConfirmText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) {
                    Text(cancelText2)
                }
            },
        )
    }
}

/** 字节人类可读（web 同款；files-model sizeText 三端口径）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRowView(project: ProjectRow, taskCount: Int, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .combinedClickable(onClick = {}, onLongClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("18-row-${project.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = project.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (project.pinned) {
                Text(
                    text = stringResource(R.string.projects_pinned),
                    fontSize = 10.sp,
                    color = BiuZAccent,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (project.primaryRootStatus == "missing") {
                Text(
                    text = stringResource(R.string.projects_missing),
                    fontSize = 10.sp,
                    color = BiuZOrange,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (project.roots.isNotEmpty()) {
            Text(
                text = project.roots.joinToString(" · "),
                fontSize = 10.5.sp,
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.projects_task_count, taskCount),
            fontSize = 10.sp,
            color = BiuZText3,
        )
    }
}

@Composable
private fun RemovedProjectRowView(project: ProjectRow, onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("18-removed-${project.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = project.name,
            fontSize = 13.sp,
            color = BiuZText3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.projects_removed_badge),
            fontSize = 10.sp,
            color = BiuZText3,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = stringResource(R.string.projects_restore),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = BiuZAccent,
            modifier = Modifier
                .clip(BiuZRadius.small)
                .clickable { onRestore() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("18-act-restore-${project.id}"),
        )
    }
}

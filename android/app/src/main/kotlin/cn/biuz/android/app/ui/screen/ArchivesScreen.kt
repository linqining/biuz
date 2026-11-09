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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.Conversation
import kotlinx.coroutines.launch

/**
 * 019 · 归档管理（`/remote/v4#settings/archives`）—— 文案对齐 web
 * `packages/ui/src/i18n/locales/zh-CN.ts:10-40`（`archives.*`）。
 *
 * 数据面：`archivedConversations()`（连接态 listArchivedTasks 只读拉取）；
 * 写面：`setArchived(false)` 取消归档、`deleteTask` 删除——失败原文上屏，不做本地假成功。
 * web 的「全部删除/全部取消归档」在移动端降为逐条 + 顶部两个批量项（确认弹层同款措辞）。
 */
@Composable
fun ArchivesScreen(
    session: AppSession,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val store = surface.conversation
    // 未连接态不得显示「暂无归档会话」（会读成「桌面没有归档」——如实区分，§5-12）
    val connected = mode is AppSession.Mode.Connected || mode is AppSession.Mode.Connecting
    // 已连接但会话索引一行都没有 = v4 握手未完成（与 P-023 看门狗同判据）：
    // 此时「暂无归档会话」是误导，改走「暂未返回会话列表 · 点按重试」
    var indexEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(connected, surface.epoch) {
        indexEmpty = connected && runCatching {
            surface.sessionsIndex?.currentConversations()?.isEmpty() ?: true
        }.getOrDefault(false)
    }

    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteAllConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        val result = runCatching { store.archivedConversations() }
        loading = false
        result.onSuccess {
            items = it
            loadError = null
        }.onFailure {
            loadError = it.message ?: it::class.simpleName ?: ""
        }
    }

    LaunchedEffect(store) { reload() }

    val filtered = remember(items, query) {
        val key = query.trim()
        if (key.isEmpty()) items
        else items.filter { it.title.contains(key, ignoreCase = true) }
    }

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
                text = stringResource(R.string.archives_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.archives_unarchive_all),
                    fontSize = 12.sp,
                    color = BiuZText3,
                    modifier = Modifier
                        .clip(BiuZRadius.small)
                        .clickable {
                            scope.launch {
                                actionError = null
                                var failed = 0
                                var lastFailure = ""
                                items.forEach { item ->
                                    runCatching { store.setArchived(false, item.id) }
                                    val failure = store.lastArchiveFailureText()
                                    if (failure.isNotEmpty()) {
                                        failed += 1
                                        lastFailure = failure
                                    }
                                }
                                if (failed > 0) actionError = "取消归档失败 $failed 项 · $lastFailure"
                                reload()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("19-unarchive-all"),
                )
                Text(
                    text = stringResource(R.string.archives_delete_all),
                    fontSize = 12.sp,
                    color = BiuZRed,
                    modifier = Modifier
                        .clip(BiuZRadius.small)
                        .clickable { deleteAllConfirm = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("19-delete-all"),
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(color = BiuZBorder)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.archives_search), fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                .testTag("19-search"),
        )

        actionError?.let {
            BiuzInlineNotice(message = it, modifier = Modifier.padding(horizontal = BiuZSpacing.md))
        }

        when {
            loading && items.isEmpty() -> BiuzLoadingState(text = stringResource(R.string.archives_loading))

            loadError != null && items.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = BiuZSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.archives_load_failed) + " · " + loadError,
                    onRetry = { scope.launch { reload() } },
                    tag = "19-load-error",
                )
            }

            filtered.isEmpty() -> BiuzEmptyState(
                title = when {
                    !connected -> stringResource(R.string.archives_not_connected)
                    indexEmpty && query.isBlank() -> stringResource(R.string.archives_index_pending)
                    query.isNotBlank() -> stringResource(R.string.archives_no_results)
                    else -> stringResource(R.string.archives_empty)
                },
                hint = when {
                    !connected -> stringResource(R.string.archives_not_connected_hint)
                    indexEmpty && query.isBlank() -> stringResource(R.string.archives_index_pending_hint)
                    else -> stringResource(R.string.archives_empty_hint)
                },
                action = if (indexEmpty) {
                    {
                        TextButton(onClick = { scope.launch { reload() } }) {
                            Text(stringResource(R.string.archives_retry))
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.testTag("19-empty"),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = BiuZSpacing.md,
                    end = BiuZSpacing.md,
                    bottom = BiuZSpacing.xl,
                ),
            ) {
                items(filtered, key = { it.id }) { item ->
                    ArchivedRow(
                        item = item,
                        busy = busyId == item.id,
                        onOpen = { onOpenConversation(item.id) },
                        onUnarchive = {
                            scope.launch {
                                busyId = item.id
                                actionError = null
                                runCatching { store.setArchived(false, item.id) }
                                val failure = store.lastArchiveFailureText()
                                busyId = null
                                if (failure.isNotEmpty()) actionError = failure else reload()
                            }
                        },
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        }
    }

    val deleteFailedText = stringResource(R.string.archives_delete_failed)
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.archives_delete_title, 1)) },
            text = { Text(stringResource(R.string.archives_delete_description)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        busyId = target.id
                        actionError = null
                        val ok = runCatching { store.deleteTask(target.id) }.getOrDefault(false)
                        busyId = null
                        if (!ok) actionError = deleteFailedText
                        else reload()
                    }
                }) { Text(stringResource(R.string.archives_delete), color = BiuZRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (deleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { deleteAllConfirm = false },
            title = { Text(stringResource(R.string.archives_delete_title, filtered.size)) },
            text = { Text(stringResource(R.string.archives_delete_description)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteAllConfirm = false
                    scope.launch {
                        actionError = null
                        val snapshot = filtered
                        var failed = 0
                        snapshot.forEach { item ->
                            val ok = runCatching { store.deleteTask(item.id) }.getOrDefault(false)
                            if (!ok) failed += 1
                        }
                        if (failed > 0) actionError = "删除失败 $failed 项"
                        reload()
                    }
                }) { Text(stringResource(R.string.archives_delete), color = BiuZRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ArchivedRow(
    item: Conversation,
    busy: Boolean,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = item.title,
            fontSize = 14.sp,
            color = BiuZText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = BiuZText2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md)) {
            Text(
                text = stringResource(R.string.archives_unarchive),
                fontSize = 12.sp,
                color = if (busy) BiuZText3 else BiuZText2,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable(enabled = !busy, onClick = onUnarchive)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("19-unarchive-${item.id}"),
            )
            Text(
                text = stringResource(R.string.archives_delete),
                fontSize = 12.sp,
                color = if (busy) BiuZText3 else BiuZRed,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable(enabled = !busy, onClick = onDelete)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("19-delete-${item.id}"),
            )
            if (busy) {
                Text(text = stringResource(R.string.archives_working), fontSize = 12.sp, color = BiuZText3)
            }
        }
    }
}

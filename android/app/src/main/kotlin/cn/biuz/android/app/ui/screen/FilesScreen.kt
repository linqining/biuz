package cn.biuz.android.app.ui.screen

// 文件页（屏 08 Diff 审查根 + 屏 09 目录树/预览；iOS DiffReviewView.swift + FileTreeView.swift
// 直译的对位）。职责边界（本任务清单）：
// - Diff 只读渲染（hunk/add/del/ctx 四类着色；只读——文件内容桌面代管，移动端零写入面）；
// - 逐文件批准 = git.stagePaths / 拒绝 = git.unstagePaths（可逆；破坏性 discardPaths 不接，
//   AGENTS §6 git 写族裁决）；全部批准 = 未决文件一次性 stagePaths 批量（一次 RPC）；
// - **破坏性入口 HIDDEN 非删除**（H3 标记待取证）：检查点入口/恢复（git-checkpoint 三方法
//   web bundle 0 命中 + 协议文档零实证条目——iOS FileTreeView :20-22/:168/:294-317 同款
//   HIDDEN 注释保留占位，真机取证立条后随入口一并恢复）。
//
// 读面三态纪律（§5-12）：加载/错误/空三态可区分；空结果 + Store 失败信号 = 读取失败
// 保留旧列表（last-good 与失败横幅共存），列表任何时刻不得无提示变空。写面失败如实
// 上屏（服务端 reason 原文），点按清除、下次动作亦自动清除。
//
// 数据源 = [AppSession.storeSurface]（file/task/conversation 三 Store 面 + workspace 路径）；
// store 换源以 epoch 为 key 重拉（iOS .task(id: ObjectIdentifier(store)) 同语义）。

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.chat.byteText
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZAdd
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgCode
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBlue
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZDel
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.DiffFile
import cn.biuz.android.protocol.DiffLine
import cn.biuz.android.protocol.DiffLineKind
import cn.biuz.android.protocol.FileNode
import cn.biuz.android.store.FileStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Diff 审查数据源分段（named gap 补全：staged 维度 + 会话维度；演示态不显示分段）。 */
enum class DiffSource(val id: String) {
    WorkspaceUnstaged("unstaged"),
    WorkspaceStaged("staged"),
    Session("session"),
    ;

    val labelRes: Int
        get() = when (this) {
            WorkspaceUnstaged -> R.string.files_tab_unstaged
            WorkspaceStaged -> R.string.files_tab_staged
            Session -> R.string.files_tab_session
        }
}

/**
 * 屏 08 · Diff 审查（文件页根）：分段数据源 + 统计行 + 文件卡列表 + 底部「全部批准」。
 * 演示态（EmptyFileStore）不渲染分段、行为退化为单维度空读。
 */
@Composable
fun FilesScreen(
    session: AppSession,
    onBack: () -> Unit,
    onOpenTree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val fileStore = surface.file
    val conversationStore = surface.conversation
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(DiffSource.WorkspaceUnstaged) }
    var files by remember { mutableStateOf<List<DiffFile>>(emptyList()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var isLoading by remember { mutableStateOf(true) }
    var deciding by remember { mutableStateOf(setOf<String>()) }
    var approvingAll by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    // 换源/手动刷新令牌（全部批准成功后 bump 重拉列表——iOS diffReloadToken 同语义）
    var reloadTick by remember(surface.epoch) { mutableStateOf(0) }

    // 「本次会话」分段的会话 id（最近活动会话承载；conversations() 已按 updatedAt 排序——
    // iOS DiffViewModel :60-62/:104 直译。惰性解析：load 触发于 store 切换瞬间会话列表
    // 可能尚未加载，一次性解析会永久落空）
    suspend fun sessionIdForSessionSource(): String? =
        conversationStore.conversations().firstOrNull()?.id

    // 拉取 + 三态应用（空结果 + Store 失败信号 = 瞬时读取失败：replacesExisting 决定
    // 保留旧列表或转错误态；iOS DiffViewModel.applyFetch :84-97 直译）
    suspend fun applyFetch(target: FileStore, src: DiffSource, replacesExisting: Boolean) {
        val fetched: List<DiffFile> = when (src) {
            DiffSource.WorkspaceUnstaged -> target.diffFiles()
            DiffSource.WorkspaceStaged ->
                if (target.isRemote) target.diffFiles("staged") else emptyList()
            DiffSource.Session ->
                if (target.isRemote) {
                    val sessionId = sessionIdForSessionSource()
                    if (sessionId == null) emptyList() else target.sessionDiffFiles(sessionId)
                } else {
                    emptyList()
                }
        }
        hasMore = when (src) {
            DiffSource.WorkspaceUnstaged -> target.hasMoreChanges("unstaged")
            DiffSource.WorkspaceStaged -> target.hasMoreChanges("staged")
            DiffSource.Session -> false
        }
        val remoteFailure = (target as? cn.biuz.android.store.RemoteFileStore)?.loadFailure(src.id)
        if (fetched.isEmpty() && remoteFailure != null) {
            loadError = remoteFailure
            if (replacesExisting) {
                files = emptyList()
                expanded = emptySet()
            }
            return
        }
        loadError = null
        files = fetched
    }

    LaunchedEffect(surface.epoch) {
        isLoading = true
        applyFetch(fileStore, source, replacesExisting = false)
        isLoading = false
    }
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) applyFetch(fileStore, source, replacesExisting = false)
    }

    val addedTotal = files.sumOf { it.added }
    val removedTotal = files.sumOf { it.removed }
    val pending = files.filter { !it.isApproved && !it.isRejected }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BiuZBg)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 标题 + 目录树入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                fontSize = 18.sp,
                color = BiuZText,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(BiuZSpacing.sm)
                    .testTag("08-act-back"),
            )
            Text(
                text = stringResource(R.string.files_title),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = BiuZText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "▤",
                fontSize = 16.sp,
                color = BiuZText,
                modifier = Modifier
                    .clickable(onClick = onOpenTree)
                    .padding(BiuZSpacing.sm)
                    .testTag("08-act-browse"),
            )
        }
        when {
            isLoading -> Text(
                text = stringResource(R.string.files_loading),
                style = TextStyle(fontSize = 13.sp),
                color = BiuZText3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp)
                    .testTag("08-loading-center"),
                textAlign = TextAlign.Center,
            )

            files.isEmpty() && loadError != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BiuZSpacing.xl)
                    .testTag("08-load-error-state"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.files_load_failed_title),
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZText,
                )
                Text(
                    text = loadError ?: "",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    color = BiuZText3,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.files_action_retry),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZAccent,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                isLoading = true
                                applyFetch(fileStore, source, replacesExisting = false)
                                isLoading = false
                            }
                        }
                        .padding(BiuZSpacing.sm)
                        .testTag("08-act-retry-load"),
                )
            }

            files.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BiuZSpacing.xl)
                    .testTag("08-empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(text = "✓", fontSize = 22.sp, color = BiuZAccent)
                Text(
                    text = stringResource(
                        if (source == DiffSource.Session) {
                            R.string.files_empty_session
                        } else {
                            R.string.files_empty_clean
                        },
                    ),
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZText,
                )
                Text(
                    text = stringResource(
                        if (source == DiffSource.Session) {
                            R.string.files_empty_session_detail
                        } else {
                            R.string.files_empty_clean_detail
                        },
                    ),
                    style = TextStyle(fontSize = 12.sp),
                    color = BiuZText3,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.files_browse_tree),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZAccent,
                    modifier = Modifier
                        .clickable(onClick = onOpenTree)
                        .padding(BiuZSpacing.sm)
                        .testTag("08-act-browse-empty"),
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = BiuZSpacing.lg,
                    vertical = BiuZSpacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
            ) {
                if (fileStore.isRemote) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                            DiffSource.entries.forEach { candidate ->
                                Text(
                                    text = stringResource(candidate.labelRes),
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = if (candidate == source) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = if (candidate == source) BiuZText else BiuZText3,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (candidate == source) BiuZBgInput else BiuZBgCard)
                                        .border(
                                            1.dp,
                                            if (candidate == source) BiuZBorderStrong else BiuZBorder,
                                            RoundedCornerShape(999.dp),
                                        )
                                        .clickable {
                                            scope.launch {
                                                source = candidate
                                                expanded = emptySet()
                                                actionError = null
                                                applyFetch(fileStore, candidate, replacesExisting = true)
                                            }
                                        }
                                        .padding(horizontal = BiuZSpacing.md, vertical = 6.dp)
                                        .testTag("08-seg-source-${candidate.id}"),
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "+$addedTotal",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = BiuZAdd,
                        )
                        Text(
                            text = "-$removedTotal",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = BiuZDel,
                        )
                        Text(
                            text = stringResource(R.string.files_n_files, files.size),
                            style = TextStyle(fontSize = 11.5.sp),
                            color = BiuZText3,
                        )
                        if (pending.isNotEmpty()) {
                            Text(
                                text = "${pending.size}",
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = BiuZOrange,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(BiuZOrange.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // 读面失败但旧列表仍在展示（last-good 兜底）：横幅如实声明数据可能过期
                loadError?.let { failure ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.small)
                                .background(BiuZBgCard)
                                .border(1.dp, BiuZOrange.copy(alpha = 0.4f), BiuZRadius.small)
                                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                                .testTag("08-load-error-banner"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                        ) {
                            Text(text = "⚠", fontSize = 12.sp, color = BiuZOrange)
                            Text(
                                text = failure,
                                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = BiuZOrange,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.files_action_retry),
                                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = BiuZAccent,
                                modifier = Modifier
                                    .clickable {
                                        scope.launch { applyFetch(fileStore, source, replacesExisting = false) }
                                    }
                                    .padding(BiuZSpacing.xs),
                            )
                        }
                    }
                }
                // 写面失败如实上屏（服务端 reason；点按清除，下次动作亦自动清除）
                actionError?.let { error ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.small)
                                .background(BiuZBgCard)
                                .border(1.dp, BiuZDel.copy(alpha = 0.5f), BiuZRadius.small)
                                .clickable { actionError = null }
                                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                                .testTag("08-action-error"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                        ) {
                            Text(text = "⚠", fontSize = 12.sp, color = BiuZDel)
                            Text(
                                text = error,
                                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = BiuZDel,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (hasMore) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.small)
                                .background(BiuZBgInput)
                                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                                .testTag("08-more-notice"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                        ) {
                            Text(text = "⋯", fontSize = 12.sp, color = BiuZText3)
                            Text(
                                text = stringResource(R.string.files_more_notice),
                                style = TextStyle(fontSize = 11.5.sp),
                                color = BiuZText3,
                            )
                        }
                    }
                }
                items(files, key = { it.id }) { file ->
                    DiffFileCard(
                        file = file,
                        expanded = file.id in expanded,
                        deciding = file.id in deciding,
                        anyDeciding = deciding.isNotEmpty(),
                        onToggle = {
                            expanded = if (file.id in expanded) expanded - file.id else expanded + file.id
                        },
                        onApprove = {
                            scope.launch {
                                deciding = deciding + file.id
                                // 批准 → stagePaths（web 同参 stagePath 原始路径）；null = 成功
                                actionError = fileStore.setFileDecision(
                                    path = file.stagePath ?: file.path,
                                    approved = true,
                                )
                                deciding = deciding - file.id
                                if (actionError == null) {
                                    applyFetch(fileStore, source, replacesExisting = false)
                                }
                            }
                        },
                        onReject = {
                            scope.launch {
                                deciding = deciding + file.id
                                // 拒绝 → unstagePaths（仅退索引，安全可逆；改动不动）
                                actionError = fileStore.setFileDecision(
                                    path = file.stagePath ?: file.path,
                                    approved = false,
                                )
                                deciding = deciding - file.id
                                if (actionError == null) {
                                    applyFetch(fileStore, source, replacesExisting = false)
                                }
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        // 底部动作栏「全部批准」（未决文件在场才渲染；一次 RPC 批量 stagePaths）
        if (!isLoading && pending.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BiuZBgCard)
                    .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .background(if (approvingAll) BiuZBgInput else BiuZAccent)
                        .clickable(enabled = !approvingAll) {
                            scope.launch {
                                approvingAll = true
                                actionError = fileStore.approveAll()
                                approvingAll = false
                                if (actionError == null) reloadTick++
                            }
                        }
                        .padding(vertical = 12.dp)
                        .testTag("08-act-approve-all"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (approvingAll) {
                            stringResource(R.string.files_approve_all_processing)
                        } else {
                            stringResource(R.string.files_approve_all, pending.size)
                        },
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = if (approvingAll) BiuZText3 else BiuZOnAccent,
                    )
                }
            }
        }
    }
}

// MARK: - 文件卡（展开态：头 + unified diff + 批准/拒绝行；iOS DiffFileCardView :438-582 直译）

@Composable
private fun DiffFileCard(
    file: DiffFile,
    expanded: Boolean,
    deciding: Boolean,
    anyDeciding: Boolean,
    onToggle: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(
                1.dp,
                when {
                    file.isApproved -> BiuZAccent.copy(alpha = 0.4f)
                    file.isRejected -> BiuZDel.copy(alpha = 0.5f)
                    else -> BiuZBorder
                },
                BiuZRadius.medium,
            ),
    ) {
        // 文件头：图标 + mono 路径 + ±统计 + 折叠箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(
                    start = BiuZSpacing.md,
                    end = BiuZSpacing.sm,
                    top = BiuZSpacing.sm,
                    bottom = BiuZSpacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            Text(text = "☰", fontSize = 12.sp, color = BiuZBlue)
            Text(
                text = file.path,
                style = TextStyle(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "+${file.added}",
                style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                color = BiuZAdd,
            )
            Text(
                text = "-${file.removed}",
                style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                color = BiuZDel,
            )
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 12.sp,
                color = BiuZText3,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(6.dp)
                    .testTag("08-filecard-toggle-${file.id}"),
            )
        }
        if (expanded) {
            DiffLineList(lines = file.lines)
            when {
                file.isApproved -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    Text(text = "✓", fontSize = 13.sp, color = BiuZAccent)
                    Text(
                        text = stringResource(R.string.files_card_approved),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZAccent,
                    )
                }

                file.isRejected -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    Text(text = "↩", fontSize = 13.sp, color = BiuZDel)
                    Text(
                        text = stringResource(R.string.files_card_rejected),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZDel,
                    )
                }

                else -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BiuZSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(BiuZRadius.small)
                            .border(1.dp, BiuZDel.copy(alpha = 0.5f), BiuZRadius.small)
                            .clickable(enabled = !anyDeciding, onClick = onReject)
                            .padding(vertical = 12.dp)
                            .testTag("08-filecard-reject"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (deciding) R.string.files_card_processing else R.string.files_card_reject,
                            ),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = BiuZDel,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(BiuZRadius.small)
                            .background(if (anyDeciding) BiuZBgInput else BiuZAccent)
                            .clickable(enabled = !anyDeciding, onClick = onApprove)
                            .padding(vertical = 12.dp)
                            .testTag("08-filecard-approve"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (deciding) R.string.files_card_processing else R.string.files_card_approve,
                            ),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = if (anyDeciding) BiuZText3 else BiuZOnAccent,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - diff 行列表（spec 5.4：正文 12px mono、双行号、横滚禁折行）

@Composable
fun DiffLineList(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BiuZBgCode),
    ) {
        // 同类行内序号（iOS IndexedDiffLine :596-603 同构：kind 分组计数器）
        val counters = HashMap<DiffLineKind, Int>()
        lines.forEach { line ->
            val next = (counters[line.kind] ?: 0) + 1
            counters[line.kind] = next
            val (fg, bg) = when (line.kind) {
                DiffLineKind.hunk -> BiuZBlue to BiuZBlue.copy(alpha = 0.10f)
                DiffLineKind.add -> BiuZAdd to BiuZAdd.copy(alpha = 0.10f)
                DiffLineKind.del -> BiuZDel to BiuZDel.copy(alpha = 0.10f)
                DiffLineKind.ctx -> BiuZText2 to androidx.compose.ui.graphics.Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = BiuZSpacing.sm)
                    .heightIn(min = if (line.kind == DiffLineKind.hunk) 24.dp else 21.dp)
                    .testTag("08-diffrow-${line.kind.name}-$next"),
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(
                    text = line.oldNumber?.toString() ?: "",
                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                    modifier = Modifier.width(32.dp),
                )
                Text(
                    text = line.newNumber?.toString() ?: "",
                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                    modifier = Modifier.width(32.dp),
                )
                Text(
                    text = line.text,
                    style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

// MARK: - 屏 09 · 目录树（文件夹展开/收起、文件行 mono、点击进预览；搜索服务端有界候选）

/**
 * 工作区文件树。演示态（!isRemote）保持本地过滤；连接态搜索走 file.searchWorkspaceFiles
 * 防抖 250ms。读面三态：last-good 树 + 失败横幅共存；首载失败错误空态。
 */
@Composable
fun FileTreeScreen(
    session: AppSession,
    onBack: () -> Unit,
    onOpenPreview: (FileNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val fileStore = surface.file
    val scope = rememberCoroutineScope()

    var tree by remember(surface.epoch) { mutableStateOf<List<FileNode>>(emptyList()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var isLoading by remember(surface.epoch) { mutableStateOf(true) }
    var searchResults by remember { mutableStateOf<List<FileNode>?>(null) }
    var loadError by remember(surface.epoch) { mutableStateOf<String?>(null) }

    // 拉取文件树并对账失败信号。Store 端 last-good 兜底：重拉失败返回旧树（不清空），
    // 视图旧行保留，失败横幅如实声明「暂显示上次结果」（iOS loadTree :31-39 直译）。
    suspend fun loadTree() {
        val fresh = fileStore.fileTree()
        tree = fresh
        loadError = (fileStore as? cn.biuz.android.store.RemoteFileStore)?.treeLoadFailure()
    }

    LaunchedEffect(surface.epoch) {
        if (tree.isEmpty()) isLoading = true
        loadTree()
        isLoading = false
        // 文件树活性（file-watcher.onDynamicChange）：变更即重拉；演示态流立即结束
        launch {
            fileStore.observeFileTreeChanges().collect { loadTree() }
        }
    }
    // 连接态防抖走服务端搜索；演示态 searchResults 恒 nil 保持本地过滤（iOS :197-220 直译）
    LaunchedEffect(query, fileStore.isRemote) {
        if (!fileStore.isRemote) {
            searchResults = null
            return@LaunchedEffect
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchResults = null
            return@LaunchedEffect
        }
        delay(250)
        val results = fileStore.searchFiles(trimmed, limit = 50)
        searchResults = results
        // 搜索命中文件自动展开其父目录，保证返回树视图时上下文完整
        expanded = expanded + results.filter { !it.isDirectory }.map { it.path.substringBeforeLast('/') }
    }

    // 命中行：搜索回执优先（连接态），否则树内过滤（演示态 + 未命中时回退）（iOS :50-67）
    val visibleRows = remember(tree, expanded, query, searchResults) {
        searchResults ?: run {
            val rows = ArrayList<FileNode>()
            fun walk(nodes: List<FileNode>) {
                for (node in nodes) {
                    if (query.isEmpty() || node.name.contains(query, ignoreCase = true)) rows.add(node)
                    if (node.isDirectory && node.id in expanded) walk(node.children ?: emptyList())
                }
            }
            walk(tree)
            rows
        }
    }
    val headerPath = surface.workspace?.path

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BiuZBg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                fontSize = 18.sp,
                color = BiuZText,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(BiuZSpacing.sm),
            )
            Text(
                text = stringResource(R.string.tree_title),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = BiuZText,
            )
        }
        if (isLoading) {
            Text(
                text = stringResource(R.string.tree_loading),
                style = TextStyle(fontSize = 13.sp),
                color = BiuZText3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp)
                    .testTag("09-loading-center"),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = BiuZSpacing.lg,
                    vertical = BiuZSpacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
            ) {
                item {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = TextStyle(fontSize = 13.5.sp, color = BiuZText),
                        singleLine = true,
                        decorationBox = { inner ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(BiuZRadius.medium)
                                    .background(BiuZBgInput)
                                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                                    .padding(horizontal = BiuZSpacing.md, vertical = 10.dp)
                                    .testTag("09-search"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = "⌕", fontSize = 13.sp, color = BiuZText3)
                                Spacer(Modifier.width(BiuZSpacing.sm))
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.tree_search_hint),
                                        style = TextStyle(fontSize = 13.5.sp),
                                        color = BiuZText3,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 读面失败横幅（旧树仍展示时声明数据可能过期；可重试）
                loadError?.let { failure ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.small)
                                .background(BiuZBgCard)
                                .border(1.dp, BiuZOrange.copy(alpha = 0.4f), BiuZRadius.small)
                                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                                .testTag("09-load-error-banner"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                        ) {
                            Text(text = "⚠", fontSize = 12.sp, color = BiuZOrange)
                            Text(
                                text = failure,
                                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = BiuZOrange,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.files_action_retry),
                                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = BiuZAccent,
                                modifier = Modifier
                                    .clickable { scope.launch { loadTree() } }
                                    .padding(BiuZSpacing.xs)
                                    .testTag("09-act-retry-tree"),
                            )
                        }
                    }
                }
                if (tree.isEmpty() && loadError == null) {
                    // 真空态（工作区无文件/目录为空）：与加载态、失败态三者可区分
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = BiuZSpacing.xl)
                                .testTag("09-empty-tree"),
                            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "▤", fontSize = 13.sp, color = BiuZText3)
                            Text(
                                text = stringResource(R.string.tree_empty),
                                style = TextStyle(fontSize = 12.5.sp),
                                color = BiuZText3,
                            )
                        }
                    }
                }
                item {
                    // G-031：连接态显示真实 workspace 路径，演示态保留演示路径
                    Text(
                        text = stringResource(
                            R.string.tree_workspace,
                            headerPath ?: "~/work/zcode",
                        ),
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZText3,
                        modifier = Modifier.padding(top = BiuZSpacing.xs),
                    )
                }
                items(visibleRows, key = { it.id }) { node ->
                    TreeRow(
                        node = node,
                        expanded = node.id in expanded,
                        showRelativePath = searchResults != null,
                        onClick = {
                            if (node.isDirectory) {
                                expanded = if (node.id in expanded) expanded - node.id else expanded + node.id
                            } else {
                                onOpenPreview(node)
                            }
                        },
                    )
                }
                // HIDDEN(对齐修复 H3)：一站式提交入口与检查点入口在此按 iOS FileTreeView
                // commitSection/:294-317 同位挂载——本任务清单未含提交 sheet（iOS P3-8
                // generateCommitMessage/commit 族），检查点三方法 web bundle 0 命中且协议
                // 文档零实证条目（审查报告 §五），恢复条件 = 真机取证 + 协议文档立条。
                // 破坏性入口（discardPaths/push/restoreBetweenCheckpoints）一律不接 UI。
            }
        }
    }
}

@Composable
private fun TreeRow(
    node: FileNode,
    expanded: Boolean,
    showRelativePath: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.small)
            .background(BiuZBgCard)
            .clickable(onClick = onClick)
            .padding(horizontal = BiuZSpacing.md, vertical = 8.dp)
            .testTag("09-row-${if (node.isDirectory) "dir" else "file"}-${node.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = if (node.isDirectory) "▤" else "≡",
            fontSize = 13.sp,
            color = if (node.isDirectory) BiuZBlue else BiuZText3,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = if (node.isDirectory) {
                    TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else {
                    TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                },
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // G-039：搜索命中态显示相对路径，补回层级上下文
            if (showRelativePath && !node.isDirectory && node.path.length > node.name.length) {
                Text(
                    text = node.path,
                    style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (node.isDirectory) {
            Text(text = if (expanded) "▾" else "▸", fontSize = 11.sp, color = BiuZText3)
        } else {
            node.size?.let {
                Text(
                    text = byteText(it.toInt()),
                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                )
            }
        }
    }
}

// MARK: - 屏 09 · 产物预览（Push L3）：文件名 + 大小、分段 预览/源码 + 有界分页读

/**
 * 文件预览。文本走 contentPage 有界读（首屏 + 截断「加载更多」按 offset 追加）；
 * 图片按扩展名分发 binaryPreview 只读预览；失败/演示态降级为说明文案（不虚构预览）。
 * 【平台偏差】iOS PDFKit/PDF 只读预览未移植（Android v1 如实提示桌面端查看）；
 * iOS TinyMarkdown 富渲染未移植（markdown 正文原样呈现）。
 */
@Composable
fun FilePreviewScreen(
    session: AppSession,
    path: String,
    name: String,
    size: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val fileStore = surface.file
    val scope = rememberCoroutineScope()

    var segment by remember(path) { mutableStateOf(0) } // 0=预览 1=源码
    var content by remember(path) { mutableStateOf("") }
    var isLoading by remember(path) { mutableStateOf(true) }
    var loadedBytes by remember(path) { mutableStateOf(0) }
    var totalBytes by remember(path) { mutableStateOf(0) }
    var isTruncated by remember(path) { mutableStateOf(false) }
    var isLoadingMore by remember(path) { mutableStateOf(false) }
    var readFailed by remember(path) { mutableStateOf(false) }
    var readFailureMessage by remember(path) { mutableStateOf<String?>(null) }
    var binaryData by remember(path) { mutableStateOf<ByteArray?>(null) }
    var binaryFailed by remember(path) { mutableStateOf(false) }

    val ext = name.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "bmp")
    val isMarkdown = ext in setOf("md", "markdown")

    // 首屏装载（文本/二进制分派）；文本空内容 + Store 端失败信号 = 读取失败态，
    // 不渲染空白内容区冒充空文件（iOS loadContent :559-583 直译）
    suspend fun loadContent() {
        if (isImage) {
            binaryData = fileStore.binaryPreview(path, maxBytes = 2_000_000)
            binaryFailed = binaryData == null
            isLoading = false
            return
        }
        val page = fileStore.contentPage(path, offset = 0, length = Int.MAX_VALUE)
        content = page.content
        totalBytes = page.totalBytes
        loadedBytes = page.content.toByteArray(Charsets.UTF_8).size
        isTruncated = page.isTruncated
        readFailed = false
        readFailureMessage = null
        if (page.content.isEmpty()) {
            val failure = (fileStore as? cn.biuz.android.store.RemoteFileStore)?.textReadFailureMessage()
            if (failure != null) {
                readFailed = true
                readFailureMessage = failure
            }
        }
        isLoading = false
    }

    LaunchedEffect(path) { loadContent() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BiuZBg)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 文件名 + 大小
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                fontSize = 18.sp,
                color = BiuZText,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(BiuZSpacing.sm),
            )
            Column {
                Text(
                    text = name,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = size?.let { byteText(it.toInt()) }
                        ?: (totalBytes.takeIf { it > 0 }?.let { byteText(it) } ?: "—"),
                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                )
            }
        }
        // 文本文件两段切换（预览/源码）；二进制不分段（iOS ZSegmentedPicker 对位）
        if (!isImage) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                listOf(
                    stringResource(R.string.preview_seg_preview),
                    stringResource(R.string.preview_seg_source),
                ).forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = if (segment == index) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (segment == index) BiuZText else BiuZText3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (segment == index) BiuZBgInput else BiuZBgCard)
                            .border(
                                1.dp,
                                if (segment == index) BiuZBorderStrong else BiuZBorder,
                                RoundedCornerShape(999.dp),
                            )
                            .clickable { segment = index }
                            .padding(horizontal = BiuZSpacing.md, vertical = 6.dp)
                            .testTag("09-seg-$index"),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(BiuZSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
        ) {
            when {
                isLoading -> Text(
                    text = stringResource(R.string.preview_loading_file),
                    style = TextStyle(fontSize = 13.sp),
                    color = BiuZText3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp)
                        .testTag("09-loading-file"),
                    textAlign = TextAlign.Center,
                )

                isImage -> {
                    val data = binaryData
                    if (data != null) {
                        val bitmap = remember(data) {
                            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(BiuZRadius.medium)
                                    .testTag("09-binary-image"),
                            )
                        } else {
                            BinaryFallbackText(failed = true)
                        }
                        Text(
                            text = stringResource(R.string.preview_image_note, byteText(data.size)),
                            style = TextStyle(fontSize = 11.sp),
                            color = BiuZText3,
                        )
                    } else {
                        BinaryFallbackText(failed = binaryFailed)
                    }
                }

                readFailed -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .background(BiuZBgCard)
                        .padding(BiuZSpacing.md)
                        .testTag("09-read-failed"),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.preview_read_failed),
                        style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZText,
                    )
                    Text(
                        text = readFailureMessage
                            ?: stringResource(R.string.preview_read_failed_detail),
                        style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                        color = BiuZText3,
                    )
                    Text(
                        text = stringResource(R.string.files_action_retry),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = BiuZAccent,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    isLoading = true
                                    loadContent()
                                }
                            }
                            .padding(BiuZSpacing.xs)
                            .testTag("09-act-retry-content"),
                    )
                }

                segment == 0 -> Text(
                    // 预览段（markdown 正文原样呈现——TinyMarkdown 富渲染未移植，见文件头偏差注）
                    text = content,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = BiuZText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .background(BiuZBgCard)
                        .padding(BiuZSpacing.md),
                )

                else -> {
                    // 源码视图：等宽 + 行号（横滚禁折行；iOS sourceView :715-741 直译）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BiuZRadius.medium)
                            .background(BiuZBgCode)
                            .padding(BiuZSpacing.md)
                            .testTag("09-source"),
                    ) {
                        content.split("\n").forEachIndexed { index, line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md)) {
                                Text(
                                    text = "${index + 1}",
                                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                                    color = BiuZText3,
                                    modifier = Modifier.width(32.dp),
                                )
                                Text(
                                    text = line.ifEmpty { " " },
                                    style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    color = BiuZText2,
                                    maxLines = 1,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
            // 「已截断 · 加载更多」：追加调用 offset=已读字节数（iOS loadMoreButton :663-693 直译）
            if (!isLoading && !isImage && !readFailed && isTruncated) {
                Text(
                    text = if (isLoadingMore) {
                        stringResource(R.string.preview_loading_more)
                    } else {
                        stringResource(R.string.preview_load_more, loadedBytes, totalBytes)
                    },
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = BiuZAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.small)
                        .background(BiuZBgInput)
                        .clickable(enabled = !isLoadingMore) {
                            scope.launch {
                                isLoadingMore = true
                                val page = fileStore.contentPage(path, offset = loadedBytes, length = Int.MAX_VALUE)
                                content += page.content
                                loadedBytes += page.content.toByteArray(Charsets.UTF_8).size
                                totalBytes = maxOf(totalBytes, page.totalBytes)
                                isTruncated = loadedBytes < totalBytes
                                isLoadingMore = false
                            }
                        }
                        .padding(vertical = 12.dp)
                        .testTag("09-act-load-more"),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BinaryFallbackText(failed: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .padding(BiuZSpacing.md)
            .testTag("09-binary-fallback"),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(text = "🖼", fontSize = 18.sp, color = BiuZText3)
        Text(
            text = stringResource(
                if (failed) R.string.preview_binary_unsupported else R.string.preview_loading_file,
            ),
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            color = BiuZText2,
        )
    }
}

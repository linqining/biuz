package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import cn.biuz.android.connection.AppSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * G38 命令中心统一搜索（web CommandCenterDialog.tsx:754-1084 移动端同构，
 * 2026-11-25 与 iOS CommandCenterSheet 同构）：上游四 scope（all/commands">"/
 * conversations":" / files"@" 前缀 + tabs，:167-182）移动端三 scope（全部/会话/文件
 * ——命令 scope 由 composer 斜杠菜单承载）。检索面：会话/任务 = zcode-task
 * listTaskList `search`（store.searchSessions/searchTasks【实证·wire 已固化协议文档
 * §9.3】），文件 = store.searchFiles；历史 = SharedPreferences
 * `zcode-command-center-search-history:<wsKey>` limit 20（web
 * commandCenterSearchHistory.ts:9-10 同词面同上限）。400ms 防抖。
 */
private const val CMD_HISTORY_PREFIX = "zcode-command-center-search-history:"
private const val CMD_HISTORY_LIMIT = 20

private fun cmdHistoryKey(workspacePath: String?): String =
    CMD_HISTORY_PREFIX + (workspacePath ?: "none")

private fun cmdHistoryRead(context: Context, workspacePath: String?): List<Pair<String, String>> {
    val prefs = context.getSharedPreferences("zcode.command.center.v1", Context.MODE_PRIVATE)
    val raw = prefs.getString(cmdHistoryKey(workspacePath), null) ?: return emptyList()
    return raw.split("\n").mapNotNull { line ->
        val parts = line.split("|", limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank()) parts[0] to parts[1] else null
    }
}

private fun cmdHistoryRecord(context: Context, workspacePath: String?, scope: String, query: String) {
    val prefs = context.getSharedPreferences("zcode.command.center.v1", Context.MODE_PRIVATE)
    val key = cmdHistoryKey(workspacePath)
    val existing = cmdHistoryRead(context, workspacePath).filter { it.first != scope || it.second != query }
    val updated = (listOf(scope to query) + existing).take(CMD_HISTORY_LIMIT)
    prefs.edit().putString(key, updated.joinToString("\n") { "${it.first}|${it.second}" }).apply()
}

@Composable
fun CommandCenterScreen(
    session: AppSession,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wsPath = surface.workspace?.path

    var query by remember { mutableStateOf("") }
    var activeScope by remember { mutableStateOf("all") } // all | conversations | files
    var searching by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(listOf<cn.biuz.android.protocol.Conversation>()) }
    var files by remember { mutableStateOf(listOf<cn.biuz.android.protocol.FileNode>()) }
    var history by remember { mutableStateOf(cmdHistoryRead(context, wsPath)) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(query, activeScope, surface.epoch) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            sessions = emptyList()
            files = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400) // web 防抖同款窗
        val conversationStore = surface.conversation
        val fileStore = surface.file
        val wantSessions = activeScope == "all" || activeScope == "conversations"
        val wantFiles = activeScope == "all" || activeScope == "files"
        val s = if (wantSessions) {
            runCatching { conversationStore.searchSessions(trimmed) }.getOrDefault(emptyList())
        } else emptyList()
        val f = if (wantFiles) {
            runCatching { fileStore.searchFiles(trimmed, limit = 20) }.getOrDefault(emptyList())
        } else emptyList()
        sessions = s
        files = f
        searching = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BiuZBg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = BiuZSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(horizontal = BiuZSpacing.md)
                    .testTag("38-act-back"),
            )
            Text(
                text = "统一搜索",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("统一搜索", fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md)
                .testTag("38-search"),
        )

        // scope chips（全部/会话/文件）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            listOf("all" to "全部", "conversations" to "会话", "files" to "文件").forEach { (id, label) ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (activeScope == id) BiuZAccent else BiuZText2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (activeScope == id) BiuZAccent.copy(alpha = BiuZAlpha.faint) else BiuZBgInput)
                        .clickable { activeScope = id }
                        .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs)
                        .testTag("38-scope-$id"),
                )
            }
        }

        // 历史 chips（空 query 时呈现；web :368 scope 前缀同位）
        if (query.isEmpty() && history.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiuZSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
            ) {
                history.take(6).forEach { (scopeId, historyQuery) ->
                    Text(
                        text = (if (scopeId == "files") "@" else ":") + historyQuery,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = BiuZText2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(BiuZBgInput)
                            .clickable {
                                query = historyQuery
                                if (scopeId != "all") activeScope = scopeId
                            }
                            .padding(horizontal = BiuZSpacing.xs, vertical = BiuZSpacing.xxs)
                            .testTag("38-history"),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
        ) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                // 空态不加伪段
            } else if (searching) {
                item(key = "38-searching") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).testTag("38-searching"),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                if (activeScope == "all" || activeScope == "conversations") {
                    item(key = "hdr-sessions") {
                        Text("会话", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BiuZText3)
                    }
                    if (sessions.isEmpty()) {
                        item(key = "no-sessions") {
                            Text("无匹配会话", fontSize = 12.sp, color = BiuZText3)
                        }
                    } else {
                        items(sessions.size, key = { "s-${sessions[it].id}" }) { index ->
                            val hit = sessions[index]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(BiuZRadius.small)
                                    .background(BiuZBgCard)
                                    .clickable {
                                        cmdHistoryRecord(context, wsPath, activeScope, trimmed)
                                        onOpenChat(hit.id)
                                    }
                                    .padding(BiuZSpacing.sm)
                                    .testTag("38-hit-session-${hit.id}"),
                            ) {
                                Text(
                                    text = hit.title,
                                    fontSize = 13.sp,
                                    color = BiuZText,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                hit.projectName?.let {
                                    Text(text = it, fontSize = 10.sp, color = BiuZText3, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (activeScope == "all" || activeScope == "files") {
                    item(key = "hdr-files") {
                        Text("文件", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BiuZText3)
                    }
                    if (files.isEmpty()) {
                        item(key = "no-files") {
                            Text("无匹配文件", fontSize = 12.sp, color = BiuZText3)
                        }
                    } else {
                        items(files.size, key = { "f-${files[it].id}" }) { index ->
                            val hit = files[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(BiuZRadius.small)
                                    .background(BiuZBgCard)
                                    .clickable {
                                        cmdHistoryRecord(context, wsPath, activeScope, trimmed)
                                    }
                                    .padding(BiuZSpacing.sm)
                                    .testTag("38-hit-file-${hit.id}"),
                                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                            ) {
                                Text(text = "📄", fontSize = 12.sp)
                                Column {
                                    Text(
                                        text = hit.name,
                                        fontSize = 13.sp,
                                        color = BiuZText,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = hit.path,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = BiuZText3,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

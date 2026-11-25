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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.FileNode
import kotlinx.coroutines.delay

/**
 * G15 @ 文件提及候选面板（web promptInputTriggers @ 触发的触屏对位，2026-11-25 与
 * iOS ReferenceFilePickerSheet 同构）：符号自动触发需键盘游标监听——触屏以显式入口
 * + 候选面板承载同一语义，点选把 `@"path" ` 插回 composer 草稿（宿主 updateDraft）。
 * 检索 = 连接态 file.searchWorkspaceFiles 服务端（400ms 防抖，CommandCenter 同窗）；
 * 空结果态如实。
 */
@Composable
fun MentionPickerScreen(
    session: AppSession,
    onBack: () -> Unit,
    onPick: (FileNode) -> Unit,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val fileStore = surface.file

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query, surface.epoch) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        results = runCatching { fileStore.searchFiles(trimmed, limit = 50) }
            .getOrDefault(emptyList())
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
                    .testTag("15-act-back"),
            )
            Text(
                text = "引用文件",
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
            placeholder = { Text("搜索文件名或路径", fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md)
                .testTag("15-search"),
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
        ) {
            if (searching) {
                item(key = "15-searching") {
                    Text(
                        text = "检索中…",
                        fontSize = 12.sp,
                        color = BiuZText3,
                        modifier = Modifier
                            .padding(vertical = BiuZSpacing.offsetLg)
                            .testTag("15-searching"),
                    )
                }
            } else if (results.isEmpty()) {
                item(key = "15-empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = BiuZSpacing.offsetLg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "没有匹配的文件",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BiuZText,
                        )
                        Spacer(Modifier.height(BiuZSpacing.xs))
                        Text(
                            text = "换个关键词试试（匹配文件名或路径）",
                            fontSize = 11.sp,
                            color = BiuZText3,
                        )
                    }
                }
            } else {
                items(results.size, key = { "15-row-${results[it].id}" }) { index ->
                    val node = results[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BiuZRadius.small)
                            .background(BiuZBgCard)
                            .clickable { onPick(node) }
                            .padding(BiuZSpacing.sm)
                            .testTag("15-row-$index"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                    ) {
                        Text(
                            text = if (node.isDirectory) "📁" else "📄",
                            fontSize = 13.sp,
                        )
                        Column {
                            Text(
                                text = node.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = BiuZText,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                text = node.path,
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

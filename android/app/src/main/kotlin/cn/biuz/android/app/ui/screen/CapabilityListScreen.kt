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
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzEmptyState
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.CapabilityError
import cn.biuz.android.connection.CapabilityKind
import cn.biuz.android.connection.CapabilityRow
import cn.biuz.android.connection.loadCapability
import kotlinx.coroutines.launch

/**
 * 设置页能力清单（记忆 / 技能 / MCP / 插件）—— iOS `RemoteCapabilityListPage` 的 Android 对位。
 *
 * 四态：未连接（引导，不是失败 U-7）/ 加载中 / 列表 / 读取失败 + 重试。
 * 只读：启停、卸载、安装等写面一律不接（移动端越界即失守）。
 */
@Composable
fun CapabilityListScreen(
    session: AppSession,
    kind: CapabilityKind,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(kind) { mutableStateOf(true) }
    var rows by remember(kind) { mutableStateOf<List<CapabilityRow>>(emptyList()) }
    var notConnected by remember(kind) { mutableStateOf(false) }
    var failure by remember(kind) { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        failure = null
        notConnected = false
        try {
            rows = session.loadCapability(kind)
        } catch (e: CapabilityError.NotConnected) {
            notConnected = true
            rows = emptyList()
        } catch (e: CapabilityError.Failed) {
            failure = e.message
        } catch (e: Throwable) {
            failure = e.message ?: e::class.simpleName ?: ""
        } finally {
            loading = false
        }
    }

    LaunchedEffect(kind, session.storeSurface.value.epoch) { reload() }

    val title = when (kind) {
        CapabilityKind.memory -> stringResource(R.string.cap_memory_title)
        CapabilityKind.skills -> stringResource(R.string.cap_skills_title)
        CapabilityKind.mcp -> stringResource(R.string.cap_mcp_title)
        CapabilityKind.plugins -> stringResource(R.string.cap_plugins_title)
    }
    val tagPrefix = when (kind) {
        CapabilityKind.memory -> "20"
        CapabilityKind.skills -> "21"
        CapabilityKind.mcp -> "22"
        CapabilityKind.plugins -> "23"
    }

    Column(modifier = Modifier.fillMaxSize().background(BiuZBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = BiuZSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 26.sp,
                color = BiuZText,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onBack() }
                    .padding(horizontal = BiuZSpacing.md),
            )
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
        }
        androidx.compose.material3.HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.cap_not_connected),
                hint = stringResource(R.string.cap_not_connected_hint),
                modifier = Modifier.testTag("$tagPrefix-not-connected"),
            )

            loading && rows.isEmpty() -> BiuzLoadingState(text = stringResource(R.string.cap_loading))

            failure != null -> Column(
                modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.cap_load_failed) + " · " + failure,
                    onRetry = { scope.launch { reload() } },
                    tag = "$tagPrefix-load-error",
                )
            }

            rows.isEmpty() -> BiuzEmptyState(
                title = when (kind) {
                    CapabilityKind.memory -> stringResource(R.string.cap_memory_empty)
                    CapabilityKind.skills -> stringResource(R.string.cap_skills_empty)
                    CapabilityKind.mcp -> stringResource(R.string.cap_mcp_empty)
                    CapabilityKind.plugins -> stringResource(R.string.cap_plugins_empty)
                },
                hint = when (kind) {
                    CapabilityKind.memory -> stringResource(R.string.cap_memory_empty_hint)
                    CapabilityKind.skills -> stringResource(R.string.cap_empty_hint)
                    CapabilityKind.mcp -> stringResource(R.string.cap_mcp_empty_hint)
                    CapabilityKind.plugins -> stringResource(R.string.cap_plugins_empty_hint)
                },
                modifier = Modifier.testTag("$tagPrefix-empty"),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = BiuZSpacing.md,
                    end = BiuZSpacing.md,
                    top = BiuZSpacing.sm,
                    bottom = BiuZSpacing.xl,
                ),
            ) {
                items(rows, key = { it.id }) { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BiuZRadius.medium)
                            .background(BiuZBgCard)
                            .border(1.dp, BiuZBorder, BiuZRadius.medium)
                            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.md)
                            .testTag("$tagPrefix-row-${row.id}"),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.title,
                                fontSize = 14.sp,
                                color = BiuZText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            row.badge?.let { badge ->
                                Text(
                                    text = badge,
                                    fontSize = 11.sp,
                                    color = BiuZText3,
                                    modifier = Modifier.padding(start = BiuZSpacing.sm),
                                )
                            }
                        }
                        Text(
                            text = row.subtitle,
                            fontSize = 12.sp,
                            color = BiuZText2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

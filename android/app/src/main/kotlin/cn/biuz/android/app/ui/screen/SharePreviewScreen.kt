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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.SharePreview
import cn.biuz.android.connection.SharePreviewError
import kotlinx.coroutines.launch

/**
 * 009-分享落地（只读预览）。
 *
 * web 权威源：`packages/web/src/share/ConversationShareLandingPage.tsx`（四态：loading / login_required /
 * ready / error）+ iOS 已落实现 `RemoteExtrasViews.swift:34-186`。
 *
 * 移动端差异：移动远控不需要网页那套「去 ZCode 继续 / 下载客户端」，落点就是只读预览本身；
 * 未登录时公开分享照常可看，私有的由服务端 401/403 拒（如实上屏，不预先拦）。
 */
@Composable
fun SharePreviewScreen(
    session: AppSession,
    shareCode: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SharePreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true
        errorText = null
        scope.launch {
            try {
                preview = session.loadSharePreview(shareCode)
            } catch (err: SharePreviewError) {
                errorText = err.message
            } catch (err: Exception) {
                errorText = err.message ?: "分享预览获取失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(shareCode) { load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.share_title),
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "✕",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable(onClick = onClose)
                    .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                    .testTag("09-close"),
            )
        }

        when {
            loading && preview == null -> BiuzLoadingState(
                text = stringResource(R.string.share_loading),
                tag = "09-loading",
                modifier = Modifier.padding(top = BiuZSpacing.offsetXl),
            )

            errorText != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(BiuZSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "🔗", fontSize = 24.sp)
                Spacer(Modifier.height(BiuZSpacing.md))
                Text(
                    text = errorText ?: "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("09-error"),
                )
                Spacer(Modifier.height(BiuZSpacing.lg))
                Text(
                    text = stringResource(R.string.share_retry),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(BiuZRadius.medium)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { load() }
                        .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.sm)
                        .testTag("09-retry"),
                )
            }

            else -> {
                val data = preview
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = BiuZSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    item {
                        Column {
                            Text(
                                text = data?.meta?.title ?: stringResource(R.string.share_untitled),
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("09-title"),
                            )
                            val owner = data?.meta?.ownerName
                            if (!owner.isNullOrEmpty()) {
                                Text(
                                    text = owner,
                                    fontSize = 12.sp,
                                    color = BiuZText3,
                                )
                            }
                        }
                    }
                    items(data?.rows?.size ?: 0) { index ->
                        val row = data!!.rows[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (row.isUser) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface,
                                    BiuZRadius.medium,
                                )
                                .padding(BiuZSpacing.md),
                            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                        ) {
                            Text(
                                text = if (row.isUser) "你" else "Agent",
                                fontSize = 11.sp,
                                color = BiuZText3,
                                modifier = Modifier.size(width = 34.dp, height = 16.dp),
                            )
                            Text(
                                text = row.text,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if ((data?.unsupportedRowCount ?: 0) > 0) {
                        item {
                            Text(
                                text = stringResource(R.string.share_unsupported, data!!.unsupportedRowCount),
                                fontSize = 11.sp,
                                color = BiuZText3,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(BiuZSpacing.xl)) }
                }
            }
        }
    }
}

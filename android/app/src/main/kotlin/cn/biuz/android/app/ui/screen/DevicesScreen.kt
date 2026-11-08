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
import androidx.compose.foundation.shape.CircleShape
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
import cn.biuz.android.app.ui.components.BiuzEmptyState
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.RelayDeviceSummary
import kotlinx.coroutines.launch

/**
 * 003-设备列表（对齐 web `webRemoteControlDeviceListPage.tsx:19-347`）。
 *
 * 数据源：`AppSession.loadRelayDevices()`（Relay V2 `GET /api/v1/remote/device`，Bearer JWT）。
 * 未登录/接口失败如实呈现空态或错误行（不造设备、不折叠失败）；点设备 → 连接（V2 一次性 token）。
 */
@Composable
fun DevicesScreen(
    session: AppSession,
    onBack: () -> Unit,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<RelayDeviceSummary>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var connectingSid by remember { mutableStateOf<String?>(null) }
    var showSwitchAccount by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        errorText = null
        scope.launch {
            try {
                val result = session.loadRelayDevices()
                devices = result
                if (result == null) {
                    errorText = session.let {
                        if (!it.isLoggedIn) "登录 Z.ai 账号后可自动发现已配对电脑"
                        else "无法加载设备，请检查网络后刷新。"
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(session.oauthSessionVersion.value) { refresh() }

    val online = (devices ?: emptyList()).filter { it.online }
    val offline = (devices ?: emptyList()).filterNot { it.online }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 标题行：返回 + 「选择电脑」+ 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable(onClick = onBack)
                    .padding(horizontal = BiuZSpacing.md)
                    .testTag("03-back"),
            )
            Text(
                text = stringResource(R.string.devices_title),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "↻",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable(enabled = !loading) { refresh() }
                    .padding(horizontal = BiuZSpacing.md)
                    .testTag("03-refresh"),
            )
        }

        // 账号行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiuZSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            val account = session.oauthUserInfo
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = account?.displayName?.take(1) ?: "·",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = account?.let { "${it.displayName} · Z.ai" }
                    ?: stringResource(R.string.devices_not_logged_in),
                fontSize = 12.sp,
                color = BiuZText3,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account != null) {
                Text(
                    text = stringResource(R.string.switch_account_action),
                    fontSize = 12.sp,
                    color = BiuZText3,
                    modifier = Modifier
                        .clip(BiuZRadius.small)
                        .clickable { showSwitchAccount = true }
                        .padding(horizontal = BiuZSpacing.sm, vertical = 2.dp)
                        .testTag("07-switch-account"),
                )
            }
        }

        errorText?.let { message ->
            Box(modifier = Modifier.padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.md)) {
                BiuzInlineNotice(
                    message = message,
                    tag = "03-error",
                    onRetry = { refresh() },
                )
            }
        }

        when {
            loading && devices == null -> BiuzLoadingState(
                text = stringResource(R.string.devices_loading),
                tag = "03-loading",
                modifier = Modifier.padding(top = 64.dp),
            )

            devices != null && devices!!.isEmpty() -> BiuzEmptyState(
                title = stringResource(R.string.devices_empty_title),
                hint = stringResource(R.string.devices_empty_desc),
                tag = "03-empty",
                modifier = Modifier.padding(top = 64.dp),
            )

            else -> LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = BiuZSpacing.lg)) {
                section("online", online) { device ->
                    DeviceRow(
                        device = device,
                        connecting = connectingSid == device.deviceSid,
                        onClick = {
                            connectingSid = device.deviceSid
                            scope.launch {
                                val outcome = session.connectRelayDevice(device)
                                connectingSid = null
                                if (outcome is AppSession.AutoConnectOutcome.Connected) onConnected()
                                else errorText = "连接 ${device.displayName} 失败"
                            }
                        },
                    )
                }
                section("offline", offline) { device ->
                    DeviceRow(device = device, connecting = false, onClick = null)
                }
                item { Spacer(Modifier.height(BiuZSpacing.xl)) }
            }
        }
        if (showSwitchAccount) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSwitchAccount = false },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showSwitchAccount = false
                            session.logout()
                            onBack()
                        },
                        modifier = Modifier.testTag("07-switch-confirm"),
                    ) {
                        androidx.compose.material3.Text(stringResource(R.string.switch_account_confirm))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showSwitchAccount = false }) {
                        androidx.compose.material3.Text(stringResource(R.string.switch_account_cancel))
                    }
                },
                title = { androidx.compose.material3.Text(stringResource(R.string.switch_account_title)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.switch_account_desc)) },
            )
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.section(
    key: String,
    devices: List<RelayDeviceSummary>,
    crossinline row: @Composable (RelayDeviceSummary) -> Unit,
) {
    if (devices.isEmpty()) return
    item(key = "header-$key") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BiuZSpacing.lg, bottom = BiuZSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (key == "online") "在线" else "离线",
                fontSize = 12.sp,
                color = BiuZText3,
            )
            Spacer(Modifier.size(BiuZSpacing.sm))
            Text(text = "${devices.size}", fontSize = 12.sp, color = BiuZText3)
        }
    }
    items(devices.size, key = { "${key}-${devices[it].deviceSid}" }) { index ->
        row(devices[index])
    }
}

@Composable
private fun DeviceRow(
    device: RelayDeviceSummary,
    connecting: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = BiuZSpacing.md)
            .testTag("03-device-${device.deviceSid}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, BiuZRadius.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "💻", fontSize = 16.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(device.platform ?: "")
                    val version = device.version?.trim()
                    when {
                        device.online && !version.isNullOrEmpty() -> append(" · ZCode $version")
                        !device.online -> append(" · 离线")
                    }
                }.trim(' ', '·'),
                fontSize = 12.sp,
                color = BiuZText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (connecting) Text(text = "…", fontSize = 14.sp, color = BiuZText3)
        else if (onClick != null) Text(text = "›", fontSize = 16.sp, color = BiuZText3)
    }
}

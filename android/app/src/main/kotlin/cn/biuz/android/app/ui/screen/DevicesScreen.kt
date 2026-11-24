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
import androidx.compose.ui.text.font.FontWeight
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
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.RelayDeviceCatalog
import cn.biuz.android.connection.RelayDeviceGroup
import cn.biuz.android.connection.RelayDeviceSummary
import kotlinx.coroutines.launch

/**
 * 003-设备列表（对齐 web `webRemoteControlDeviceListPage.tsx:19-347`）。
 *
 * 数据源：`AppSession.loadRelayDevices()`（Relay V2 `GET /api/v1/remote/device`，Bearer JWT）。
 * 未登录/接口失败如实呈现空态或错误行（不造设备、不折叠失败）；点设备 → 连接（V2 一次性 token）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    // 006 多实例：同 MID ≥2 在线实例的组点击弹选择 sheet（web
    // webRemoteControlConnectionSheet 同构——「这台电脑上有 N 个 ZCode 在线」）
    var instanceSheetGroup by remember { mutableStateOf<RelayDeviceGroup?>(null) }
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
                        .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xxs)
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
                modifier = Modifier.padding(top = BiuZSpacing.offsetXl),
            )

            devices != null && devices!!.isEmpty() -> BiuzEmptyState(
                title = stringResource(R.string.devices_empty_title),
                hint = stringResource(R.string.devices_empty_desc),
                tag = "03-empty",
                modifier = Modifier.padding(top = BiuZSpacing.offsetXl),
            )

            else -> LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = BiuZSpacing.lg)) {
                section("online", online) { device ->
                    // 006：同 MID ≥2 在线实例 → 点击弹实例选择 sheet（不直连）；
                    // 单实例保持直连。组按 deviceMid 聚合（web 分组同参）。
                    val group = RelayDeviceCatalog.groupDevicesByMid(online)
                        .firstOrNull { it.deviceMid == device.deviceMid }
                    val multiInstance = group != null &&
                        RelayDeviceCatalog.needsInstanceSheet(group)
                    DeviceRow(
                        device = device,
                        connecting = connectingSid == device.deviceSid,
                        onClick = {
                            if (multiInstance && group != null) {
                                instanceSheetGroup = group
                            } else {
                            connectingSid = device.deviceSid
                            scope.launch {
                                val outcome = session.connectRelayDevice(device)
                                connectingSid = null
                                if (outcome is AppSession.AutoConnectOutcome.Connected) onConnected()
                                else errorText = "连接 ${device.displayName} 失败"
                            }
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
        // 006 多实例选择 sheet（web webRemoteControlConnectionSheet 同构：
        // 「这台电脑上有 N 个 ZCode 在线，选择要连接的一个」）
        instanceSheetGroup?.let { group ->
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { instanceSheetGroup = null },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.lg)) {
                    Text(
                        text = stringResource(R.string.devices_sheet_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BiuZText,
                        modifier = Modifier.testTag("006-sheet-title"),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.devices_sheet_desc, group.onlineConnections.size,
                        ),
                        fontSize = 13.sp,
                        color = BiuZText3,
                        modifier = Modifier.testTag("006-sheet-desc"),
                    )
                    Spacer(Modifier.height(BiuZSpacing.md))
                    group.onlineConnections.forEach { instance ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BiuZRadius.medium)
                                .clickable {
                                    instanceSheetGroup = null
                                    connectingSid = instance.deviceSid
                                    scope.launch {
                                        val outcome = session.connectRelayDevice(instance)
                                        connectingSid = null
                                        if (outcome is AppSession.AutoConnectOutcome.Connected) onConnected()
                                        else errorText = "连接失败"
                                    }
                                }
                                .padding(vertical = BiuZSpacing.md)
                                .testTag("006-sheet-row-${instance.deviceSid}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🖥", fontSize = 18.sp, modifier = Modifier.padding(end = BiuZSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.devices_sheet_instance),
                                    fontSize = 14.sp,
                                    color = BiuZText,
                                )
                                Text(
                                    text = "SID …" + instance.deviceSid.takeLast(6),
                                    fontSize = 12.sp,
                                    color = BiuZText3,
                                )
                            }
                            Text("›", fontSize = 16.sp, color = BiuZText3)
                        }
                    }
                    TextButton(
                        onClick = { instanceSheetGroup = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.devices_sheet_cancel))
                    }
                }
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

package cn.biuz.android.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.ConnectError
import cn.biuz.android.connection.ConnectLogLine
import cn.biuz.android.connection.ConnectProgress
import cn.biuz.android.connection.ConnectStepState
import cn.biuz.android.connection.ServerConfig
import cn.biuz.android.connection.ServerInfoClient
import cn.biuz.android.connection.ZCodeServerConnection
import cn.biuz.android.protocol.ConnectURLParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 连接页（L1 同位 + 交接 §5-17 开发者模式裁决）：默认只留扫码主路径——
 * 剪贴板横幅（检测到连接链接即出现，一键填充直发）+ 最近连接卡（回连优先，
 * 进入页后台探测 server-info 1.5s 的中性探测点）+ 品牌区 + 扫码 CTA。
 * 手动输入地址 / 桌面端指引 / 令牌说明收进开发者模式（有效口径 =
 * [AppSettingsStore.effectiveDeveloperMode]：设置页品牌行连点 7 次的持久化开关
 * || -ZCodeDevMode 启动参数——M2 壳期的 BuildConfig.DEBUG 等效与连接页品牌行
 * 临时手势已撤，隐藏开关唯一落位在设置页品牌行，iOS 同位）。
 *
 * 失败态如实呈现（工程纪律 §5-12）：服务端拒因原文上屏（headline + mono codeLine），
 * 按 L3 四态对照给排查行（[connectFailureHints] 种类 → 资源文案），禁笼统「连接失败」。
 */
@Composable
fun ConnectScreen(
    session: AppSession,
    settings: AppSettingsStore,
    onOpenScan: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenLogin: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val mode by session.mode.collectAsStateWithLifecycle()
    val connectionState by session.connection.state.collectAsStateWithLifecycle()
    val developerModePersisted by settings.developerMode.collectAsStateWithLifecycle()
    // 手动连接族 gating 唯一判定源（设置页品牌行 7 连击翻转持久化，|| 启动参数）
    val developerMode = developerModePersisted || settings.developerModeStartupArgument
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // MARK: 剪贴板横幅（每次回前台复查；剪贴板读取失败静默 = 横幅不出现，降级口径）
    var clipboardRaw by remember { mutableStateOf<String?>(null) }
    var clipboardLink by remember { mutableStateOf<ConnectURLParser.ParsedLink?>(null) }

    fun refreshClipboardLink() {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val text = runCatching { manager?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        val link = text?.let { ConnectURLParser.extractLink(it) }
        clipboardRaw = text?.takeIf { link != null }
        clipboardLink = link
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshClipboardLink()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // MARK: 手动表单 / 帮助（开发者模式）与内联错误
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    var addressText by rememberSaveable { mutableStateOf("") }
    var tokenText by rememberSaveable { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }

    // MARK: 最近连接卡探测点（进入页后台探测 server-info，1.5s 超时；设计稿 §9.5）
    var probe by remember { mutableStateOf<ServerInfoClient.ProbeResult?>(null) }
    val savedServer = session.savedServer
    LaunchedEffect(savedServer?.id) {
        probe = session.probeSavedServer()
    }

    // MARK: 删除服务器二次确认（L3 删除入口；iOS :1123-1128 直译）
    var confirmDeleteFor by remember { mutableStateOf<ServerConfig?>(null) }
    confirmDeleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = { Text(stringResource(R.string.connect_delete_title)) },
            text = { Text(stringResource(R.string.connect_delete_msg_fmt, target.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFor = null
                    session.deleteServer(target)
                }) {
                    Text(
                        stringResource(R.string.connect_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(BiuZSpacing.xl))

            // MARK: 剪贴板横幅（banner-clip：检测到连接链接即出现，一键填充直发连接）
            if (clipboardLink != null && clipboardRaw != null &&
                mode !is AppSession.Mode.Connecting && mode !is AppSession.Mode.Connected
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, BiuZRadius.medium)
                        .padding(BiuZSpacing.md)
                        .testTag("l1-banner-clip"),
                ) {
                    Text(
                        text = stringResource(R.string.connect_clip_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(BiuZSpacing.xs))
                    Text(
                        text = when (val link = clipboardLink) {
                            is ConnectURLParser.ParsedLink.Relay -> link.relaySummary
                            is ConnectURLParser.ParsedLink.Direct ->
                                "${link.directHost}:${link.directPort}"
                            null -> ""
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = BiuZText3,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Button(
                        onClick = {
                            val raw = clipboardRaw ?: return@Button
                            scope.launch {
                                manualError = ConnectFlow.connectFromText(
                                    context,
                                    session,
                                    raw,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = BiuZRadius.medium,
                    ) {
                        Text(stringResource(R.string.connect_clip_fill))
                    }
                }
                Spacer(Modifier.height(BiuZSpacing.xl))
            }

            // MARK: 品牌区（商标红线：works with ZCode 描述性表述；开发者模式切换
            // 手势唯一落位在设置页品牌行——连接页不放，保持默认简洁态）
            Text(
                text = "BiuZ",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("l1-brand"),
            )
            Spacer(Modifier.height(BiuZSpacing.sm))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.app_works_with),
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText3,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xl))

            // MARK: 最近连接卡（回连优先；savedServer 为空 = 首启天然缺席）
            if (savedServer != null) {
                RecentServerCard(
                    server = savedServer,
                    probe = probe,
                    onConnect = { scope.launch { session.connect(savedServer) } },
                )
                Spacer(Modifier.height(BiuZSpacing.lg))
            }

            // MARK: 扫码主路径（l1-btn-scan，48dp 主钮）
            Button(
                onClick = onOpenScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("l1-btn-scan"),
                shape = BiuZRadius.medium,
            ) {
                Text(stringResource(R.string.connect_btn_scan))
            }
            Spacer(Modifier.height(BiuZSpacing.xl))

            // MARK: 登录入口（Relay V2 账户层：登录后可自动发现并连接已配对电脑）
            Text(
                text = stringResource(R.string.login_entry),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.medium)
                    .clickable(onClick = onOpenLogin)
                    .padding(vertical = BiuZSpacing.md)
                    .testTag("01-entry-login"),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.lg))

            // 已登录（Keystore 有账户凭据）→ 直接进「选择电脑」：登录过的用户不必每次重走授权
            if (session.isLoggedIn) {
                Text(
                    text = stringResource(R.string.devices_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BiuZRadius.medium)
                        .clickable(onClick = onOpenDevices)
                        .padding(vertical = BiuZSpacing.md)
                        .testTag("03-entry-devices"),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(BiuZSpacing.md))
            }
            when (val m = mode) {
                is AppSession.Mode.Connecting -> {
                    ConnectingSteps(
                        server = m.server,
                        progress = (connectionState as? ZCodeServerConnection.ConnectionState.Connecting)?.progress,
                        lastLog = session.connectLogs.lastOrNull(),
                        onCancel = { session.cancelConnecting() },
                    )
                }

                is AppSession.Mode.Connected -> {
                    Text(
                        text = stringResource(R.string.connect_connected_fmt, m.server.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Button(
                        onClick = onOpenSessions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = BiuZRadius.medium,
                    ) {
                        Text(stringResource(R.string.connect_enter_sessions))
                    }
                }

                is AppSession.Mode.ConnectFailed -> {
                    ConnectFailedCard(
                        server = m.server,
                        error = m.error,
                        onRetry = { scope.launch { session.connect(m.server) } },
                        onRescan = onOpenScan,
                        onDelete = { confirmDeleteFor = m.server },
                        developerMode = developerMode,
                    )
                }

                is AppSession.Mode.Disconnected -> {
                    // 橙横幅口径：断线保留断线前的数据（Store 快照未 teardown），可随时重连
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, BiuZRadius.medium)
                            .padding(BiuZSpacing.md),
                    ) {
                        Text(
                            text = stringResource(R.string.connect_disconnected_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        m.detail.takeIf { it.isNotBlank() }?.let { detail ->
                            Spacer(Modifier.height(BiuZSpacing.xs))
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = BiuZText3,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        Button(
                            onClick = { scope.launch { session.connect(m.server) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = BiuZRadius.medium,
                        ) {
                            Text(stringResource(R.string.connect_reconnect))
                        }
                    }
                }

                AppSession.Mode.Demo -> Unit // 未配对引导页（最近卡/横幅已覆盖回连路径）
            }

            // MARK: 开发者模式（手动输入地址 / 桌面端指引 / 令牌说明；§5-17 收拢）
            if (developerMode) {
                Spacer(Modifier.height(BiuZSpacing.lg))
                OutlinedButton(
                    onClick = { manualExpanded = !manualExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("l1-btn-manual"),
                    shape = BiuZRadius.medium,
                ) {
                    Text(
                        stringResource(
                            if (manualExpanded) R.string.connect_manual_hide else R.string.connect_manual_show,
                        ),
                    )
                }
                if (manualExpanded) {
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.medium)
                            .padding(BiuZSpacing.md),
                    ) {
                        OutlinedTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("l1-field-address"),
                            label = { Text(stringResource(R.string.connect_address_label)) },
                            placeholder = { Text(stringResource(R.string.connect_address_hint)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        OutlinedTextField(
                            value = tokenText,
                            onValueChange = { tokenText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("l1-field-token"),
                            label = { Text(stringResource(R.string.connect_token_label)) },
                            supportingText = {
                                Text(
                                    stringResource(R.string.connect_token_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BiuZText3,
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        manualError?.let { error ->
                            Spacer(Modifier.height(BiuZSpacing.xs))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        Button(
                            onClick = {
                                scope.launch {
                                    manualError = ConnectFlow.connectFromText(
                                        context,
                                        session,
                                        addressText,
                                        tokenText,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = BiuZRadius.medium,
                            enabled = addressText.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.connect_btn_connect))
                        }
                    }
                }

                Spacer(Modifier.height(BiuZSpacing.sm))
                Text(
                    text = stringResource(R.string.connect_guide_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag("l1-link-guide")
                        .clickableNoRipple { helpExpanded = !helpExpanded }
                        .padding(vertical = BiuZSpacing.sm),
                )
                if (helpExpanded) {
                    Text(
                        text = stringResource(R.string.connect_guide_body),
                        style = MaterialTheme.typography.labelMedium,
                        color = BiuZText3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(BiuZSpacing.xl))
            Text(
                text = stringResource(R.string.app_foot_boundary),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xl))
        }
    }
}

/** 去涟漪点击（链接行同款；iOS onTapGesture 对位）。 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    ),
)

// MARK: - 最近连接卡（名称 + mono 地址 + 中性探测点 + 连接钮）

@Composable
private fun RecentServerCard(
    server: ServerConfig,
    probe: ServerInfoClient.ProbeResult?,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.medium)
            .padding(BiuZSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 中性探测点（在线状态与任务状态胶囊解耦；spec §5.6）：绿=可达 / 灰=不可达 / 半透明=未探测
            ProbeDot(
                reachable = if (probe == null) null else probe.error == null && probe.info != null,
            )
            Spacer(Modifier.size(BiuZSpacing.sm))
            Text(
                text = server.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            server.lastConnectedAt?.let { last ->
                val lastSeenPattern = stringResource(R.string.connect_last_seen_pattern)
                val format = remember(lastSeenPattern) {
                    SimpleDateFormat(lastSeenPattern, Locale.getDefault())
                }
                Text(
                    text = stringResource(
                        R.string.connect_recent_last_seen_fmt,
                        format.format(Date(last)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = BiuZText3,
                )
            }
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = server.displayAddress,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = BiuZText3,
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.connect_btn_connect))
        }
    }
}

// MARK: - L2 连接中（五步进度：发现 → 鉴权/免鉴权 → WS 通道 → v4 握手 → 工作区）

@Composable
private fun ConnectingSteps(
    server: ServerConfig,
    progress: ConnectProgress?,
    lastLog: ConnectLogLine?,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, BiuZRadius.medium)
            .padding(BiuZSpacing.md),
    ) {
        Text(
            text = stringResource(R.string.connect_connecting_fmt, server.displayName),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = server.displayAddress,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = BiuZText3,
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        val steps = progress?.allSteps.orEmpty()
        val stepTitles = listOf(
            stringResource(R.string.connect_step_1),
            stringResource(R.string.connect_step_2),
            stringResource(R.string.connect_step_3),
            stringResource(R.string.connect_step_4),
            stringResource(R.string.connect_step_5),
        )
        steps.forEachIndexed { index, step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BiuZSpacing.xs),
            ) {
                when (step.phase) {
                    ConnectStepState.Phase.done -> Text(
                        "✓",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ConnectStepState.Phase.running -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ConnectStepState.Phase.failed -> Text(
                        "✕",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ConnectStepState.Phase.pending -> Text("○", color = BiuZText3)
                }
                Spacer(Modifier.size(BiuZSpacing.sm))
                Text(
                    text = "${index + 1}. ${stepTitles.getOrElse(index) { "" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (step.meta.isNotBlank()) {
                    Spacer(Modifier.size(BiuZSpacing.sm))
                    Text(
                        text = step.meta,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = BiuZText3,
                        maxLines = 1,
                    )
                }
            }
        }
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.connect_progress_fmt, progress?.completedCount ?: 0, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText2,
            )
        }
        lastLog?.let { line ->
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = line.text,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = BiuZText3,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(BiuZSpacing.sm))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

// MARK: - L3 失败对照（种类枚举 → 资源文案；单测断言种类词面，双语稳健口径）

/** L3 对照行提示种类（l3-row-401/timeout/protocol/empty-ws；超时必含局域网检查项）。 */
internal sealed interface ConnectFailureHint {
    data object TokenMismatch : ConnectFailureHint
    data class RejectedEndpoint(val endpoint: String) : ConnectFailureHint
    data class Http(val status: Int) : ConnectFailureHint
    data object CheckLan : ConnectFailureHint
    data object CheckHostFlag : ConnectFailureHint
    data object IsolationVpn : ConnectFailureHint
    data object ProtocolVersion : ConnectFailureHint
    data object EmptyWorkspace : ConnectFailureHint
    data object Handshake : ConnectFailureHint
    data object Transport : ConnectFailureHint
}

internal fun connectFailureHints(error: ConnectError): List<ConnectFailureHint> = when (error) {
    is ConnectError.Http -> if (error.status == 401) {
        listOf(
            ConnectFailureHint.TokenMismatch,
            if (error.endpoint.isNotBlank()) ConnectFailureHint.RejectedEndpoint(error.endpoint) else null,
        ).filterNotNull()
    } else {
        listOf(ConnectFailureHint.Http(error.status))
    }
    is ConnectError.Timeout -> listOf(
        ConnectFailureHint.CheckLan,
        ConnectFailureHint.CheckHostFlag,
        ConnectFailureHint.IsolationVpn,
    )
    is ConnectError.ProtocolVersion -> listOf(ConnectFailureHint.ProtocolVersion)
    ConnectError.EmptyWorkspaces -> listOf(ConnectFailureHint.EmptyWorkspace)
    is ConnectError.HandshakeFailed -> listOf(ConnectFailureHint.Handshake)
    is ConnectError.Transport -> listOf(ConnectFailureHint.Transport)
}

@Composable
internal fun connectFailureHintText(hint: ConnectFailureHint): String = when (hint) {
    ConnectFailureHint.TokenMismatch -> stringResource(R.string.connect_fail_token)
    is ConnectFailureHint.RejectedEndpoint -> stringResource(R.string.connect_fail_endpoint_fmt, hint.endpoint)
    is ConnectFailureHint.Http -> stringResource(R.string.connect_fail_http_fmt, hint.status)
    ConnectFailureHint.CheckLan -> stringResource(R.string.connect_fail_lan)
    ConnectFailureHint.CheckHostFlag -> stringResource(R.string.connect_fail_host)
    ConnectFailureHint.IsolationVpn -> stringResource(R.string.connect_fail_isolation)
    ConnectFailureHint.ProtocolVersion -> stringResource(R.string.connect_fail_protocol)
    ConnectFailureHint.EmptyWorkspace -> stringResource(R.string.connect_fail_empty_ws)
    ConnectFailureHint.Handshake -> stringResource(R.string.connect_fail_handshake)
    ConnectFailureHint.Transport -> stringResource(R.string.connect_fail_transport)
}

@Composable
private fun ConnectFailedCard(
    server: ServerConfig,
    error: ConnectError,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
    onDelete: () -> Unit,
    developerMode: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f), BiuZRadius.medium)
            .padding(BiuZSpacing.md)
            .testTag("l3-card-failed"),
    ) {
        // P-017：web 12 种全量文案（badge/title/description/nextSteps）
        val failureCopy = failureCopyForConnectError(error)
        // badge 徽标行
        Text(
            text = failureCopy.badge,
            style = MaterialTheme.typography.labelSmall,
            color = when (failureCopy.tone) {
                "danger" -> MaterialTheme.colorScheme.error
                "warning" -> BiuZOrange
                else -> BiuZText3
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        // 红横幅口径：headline + mono 错误码行（服务端拒因原文上屏，禁笼统「连接失败」）
        Text(
            text = failureCopy.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = failureCopy.description,
            style = MaterialTheme.typography.bodyMedium,
            color = BiuZText2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        Text(
            text = error.codeLine,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = BiuZText3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        // nextSteps
        failureCopy.nextSteps.forEach { step ->
            Text(
                text = "• $step",
                style = MaterialTheme.typography.bodySmall,
                color = BiuZText2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(BiuZSpacing.sm))
        connectFailureHints(error).forEach { hint ->
            Text(
                text = "· ${connectFailureHintText(hint)}",
                style = MaterialTheme.typography.labelMedium,
                color = BiuZText2,
                modifier = Modifier.padding(vertical = BiuZSpacing.xs / 2),
            )
        }
        Spacer(Modifier.height(BiuZSpacing.sm))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("l3-btn-retry"),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.connect_retry))
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("l3-btn-rescan"),
                shape = BiuZRadius.medium,
            ) {
                Text(stringResource(R.string.connect_rescan), maxLines = 1)
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("l3-btn-delete"),
                shape = BiuZRadius.medium,
            ) {
                Text(
                    stringResource(R.string.connect_delete_record),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
        if (developerMode) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.connect_dev_target_fmt, server.displayAddress),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

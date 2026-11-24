package cn.biuz.android.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.ui.components.SealBrandMark
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgElevated
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZRedDim
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.BiuZText
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
 * 连接页（登录主页）。
 *
 * **2026-11-24 交互重构（用户裁决「交互很糟糕…信息很乱」）**：页面只有品牌区 + 一张状态卡 +
 * 两个按钮——账号位「登录 Z.ai 账号」（无论在不在电脑身边都可用，排首位；已登录同位换
 * 「选择电脑」）与连接位「重连〈电脑名〉」/「扫码连接」/「扫码重连」。记住过电脑后不再并排
 * 扫码（功能重复）；失败卡收敛为一行结论 + 一句话（[ConnectRecovery] 决定次按钮与主次）。
 *
 * 扫码走向与官方 web 同构（上游 webRemoteControlEntry.ts:104-105）：桌面二维码只含中继
 * 参数（V2 无 hash），未登录时官方页面先出登录页、登录后自动连上——本页不另造「扫码免登录」
 * 路径；扫到链接即存入注册表，登录后 autoConnectAfterLogin 按已保存的电脑自动接管。
 *
 * 开发者模式（§5-17）：手动输入 / 桌面端指引 / 令牌说明 / 删除连接记录 / 失败卡技术原文
 * （codeLine + [connectFailureHints] 排查行）收在 [AppSettingsStore.effectiveDeveloperMode]
 * 之下（设置页品牌行连点 7 次的持久化开关 || -ZCodeDevMode 启动参数，iOS 同位）。
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

    // MARK: 最近连接卡探测点（进入页后台探测 server-info，1.5s 超时）
    var probe by remember { mutableStateOf<ServerInfoClient.ProbeResult?>(null) }
    val savedServer = session.savedServer
    LaunchedEffect(savedServer?.id) {
        probe = session.probeSavedServer()
    }

    // MARK: 删除连接记录二次确认（入口在开发者区；iOS :1123-1128 直译）
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

    // 账户态随登录/退出即时重组：isLoggedIn 是计算属性（无独立 StateFlow），以会话版本号为
    // 观察源——本页账号位按钮的文案与去向都取决于它。
    val oauthVersion by session.oauthSessionVersion.collectAsStateWithLifecycle()
    val loggedIn = remember(oauthVersion) { session.isLoggedIn }
    // 失败态恢复动作（决定次按钮的文案/去向与两钮主次；null = 非失败态）
    val recovery = (mode as? AppSession.Mode.ConnectFailed)
        ?.let { recoveryForConnectFailure(failureReasonForConnectError(it.error)) }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 品牌页边距 24dp
                .padding(horizontal = BiuZSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(BiuZSpacing.xxl))

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

            // MARK: 品牌区（84dp 主印 + 名称 + 标语）。主印「批」是品牌位的
            // 唯一图形，名称取印色朱磦（Color.kt：印色 = 品牌/盖章/审批位专用）；works with
            // ZCode 为商标红线的描述性表述，保留。开发者模式切换手势唯一落位在设置页品牌行。
            SealBrandMark(modifier = Modifier.testTag("l1-brand-seal"))
            Spacer(Modifier.height(BiuZSpacing.md))
            Text(
                text = stringResource(R.string.app_wordmark),
                style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 0.44.sp),
                color = BiuZSeal,
                modifier = Modifier.testTag("l1-brand"),
            )
            Spacer(Modifier.height(BiuZSpacing.xs))
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
            Spacer(Modifier.height(BiuZSpacing.xxl))

            // MARK: 状态区（2026-11-24 交互重构）：任一时刻只渲染一张卡——记住的电脑 /
            // 连接中 / 失败 / 断线 / 已连接，互不堆叠。
            when (val m = mode) {
                is AppSession.Mode.Connecting -> {
                    ConnectingSteps(
                        server = m.server,
                        progress = (connectionState as? ZCodeServerConnection.ConnectionState.Connecting)?.progress,
                        lastLog = session.connectLogs.lastOrNull(),
                        onCancel = { session.cancelConnecting() },
                        developerMode = developerMode,
                    )
                }

                is AppSession.Mode.Connected -> {
                    Text(
                        text = stringResource(R.string.connect_connected_fmt, m.server.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(BiuZSpacing.md))
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
                    ConnectFailedCard(server = m.server, error = m.error, developerMode = developerMode)
                    Spacer(Modifier.height(BiuZSpacing.lg))
                }

                is AppSession.Mode.Disconnected -> {
                    // 断线保留断线前的数据（Store 快照未 teardown）；重连动作在下方动作区
                    DisconnectedBanner(detail = m.detail)
                    Spacer(Modifier.height(BiuZSpacing.lg))
                }

                AppSession.Mode.Demo -> if (savedServer != null) {
                    RememberedServerCard(server = savedServer, probe = probe)
                    Spacer(Modifier.height(BiuZSpacing.lg))
                }
            }

            // MARK: 动作区：只有两个按钮（用户裁决 2026-11-24）
            // APP-DIFF[D018] business: web `/remote/v4` 无独立连接主页（无 JWT 直接进登录视图，
            // webRemoteControlEntry.ts:104-105），失败态为徽标 + 说明 + 错误行 + 多恢复按钮；
            // 此处按用户裁决 2026-11-24 收敛为「账号登录 + 重连/扫码」两个按钮与一句话失败卡
            // ① 账号位：「登录 Z.ai 账号」——无论在不在电脑身边都可用，排首位；已登录则同一位置
            //    换成「选择电脑」（设备目录，换电脑走这里，不再靠扫码）。
            // ② 连接位：记住过电脑 =「重连〈电脑名〉」（不再并排扫码——功能重复）；链接失效 =
            //    「扫码重连」；没记住过电脑 =「扫码连接」。
            // 主次（唯一主 CTA 随错误类型切换）：重连/重新扫码能解的错误与断线态，
            // 连接钮为主；其余（含需要登录）账号钮为主。主钮恒在上。
            if (mode !is AppSession.Mode.Connecting && mode !is AppSession.Mode.Connected) {
                val accountLabel = stringResource(if (loggedIn) R.string.devices_title else R.string.login_entry)
                val accountTag = if (loggedIn) "03-entry-devices" else "01-entry-login"
                val onAccount = if (loggedIn) onOpenDevices else onOpenLogin
                val target = savedServer
                val rescan = target != null && recovery == ConnectRecovery.RESCAN
                val connectLabel = when {
                    target == null -> stringResource(R.string.connect_btn_scan)
                    rescan -> stringResource(R.string.connect_rescan_reconnect)
                    else -> stringResource(R.string.connect_reconnect_fmt, target.displayName)
                }
                val connectTag = when {
                    target == null -> "l1-btn-scan"
                    rescan -> "l1-btn-rescan"
                    else -> "l1-btn-reconnect"
                }
                val onConnect: () -> Unit = {
                    if (target == null || rescan) {
                        onOpenScan()
                    } else {
                        scope.launch { session.connect(target) }
                    }
                }
                val accountPrimary = recovery == null || recovery == ConnectRecovery.LOGIN
                if (accountPrimary) {
                    ConnectActionButton(accountLabel, primary = true, tag = accountTag, onClick = onAccount)
                    Spacer(Modifier.height(BiuZSpacing.md))
                    ConnectActionButton(connectLabel, primary = false, tag = connectTag, onClick = onConnect)
                } else {
                    ConnectActionButton(connectLabel, primary = true, tag = connectTag, onClick = onConnect)
                    Spacer(Modifier.height(BiuZSpacing.md))
                    ConnectActionButton(accountLabel, primary = false, tag = accountTag, onClick = onAccount)
                }
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

                // 删除这台电脑的连接记录：2026-11-24 从失败卡次级按钮收进开发者模式——
                // 普通用户换电脑走「登录 → 选择电脑」，维护动作不再暴露；二次确认保留。
                if (savedServer != null) {
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Text(
                        text = stringResource(R.string.connect_delete_record),
                        style = MaterialTheme.typography.labelMedium,
                        color = BiuZText3,
                        modifier = Modifier
                            .clip(BiuZRadius.small)
                            .clickable { confirmDeleteFor = savedServer }
                            .padding(vertical = BiuZSpacing.sm)
                            .testTag("l3-btn-delete"),
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

// MARK: - 记住的电脑卡 + 断线横幅
// 2026-11-24 交互重构：卡内不再放「连接」钮——页面动作区只有两个按钮，重连是其中之一。

/** 名称 + mono 地址 + 中性探测点 + 上次连接时间（信息位）。 */
@Composable
private fun RememberedServerCard(server: ServerConfig, probe: ServerInfoClient.ProbeResult?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BiuZBgCard, BiuZRadius.medium)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .padding(BiuZSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 中性探测点（在线状态与任务状态胶囊解耦）：绿=可达 / 灰=不可达 / 半透明=未探测
            ProbeDot(
                reachable = if (probe == null) null else probe.error == null && probe.info != null,
            )
            Spacer(Modifier.size(BiuZSpacing.sm))
            Text(
                text = server.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    }
}

/** 断线横幅（弱底 + 一句状态；重连动作在页面动作区，横幅内不放按钮）。 */
@Composable
private fun DisconnectedBanner(detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BiuZBgElevated, BiuZRadius.medium)
            .padding(BiuZSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.connect_disconnected_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        detail.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = BiuZText3,
                textAlign = TextAlign.Center,
            )
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
    developerMode: Boolean = false,
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
                if (developerMode && step.meta.isNotBlank()) {
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
        // 技术原文（connect.log 行）只进开发者模式——普通用户此前在连接卡里直接看到
        // 「relay-auth-required · 缺少登录凭据（JWT）」这类实现词（2026-11-24 真机目检发现）。
        if (developerMode) {
            lastLog?.let { line ->
                Spacer(Modifier.height(BiuZSpacing.xs))
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = BiuZText3,
                    maxLines = 2,
                )
            }
        } else if (steps.isEmpty()) {
            // 中继链路没有五步进度：行内 spinner + 一行说明（行内 16px
            // spinner，不插占位卡）
            Spacer(Modifier.height(BiuZSpacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(BiuZSpacing.sm))
                Text(
                    text = stringResource(R.string.connect_connecting_fmt, server.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = BiuZText2,
                )
            }
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
    data object RelayAuth : ConnectFailureHint
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
    ConnectError.RelayAuthRequired -> listOf(ConnectFailureHint.RelayAuth)
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
    ConnectFailureHint.RelayAuth -> stringResource(R.string.connect_fail_relay_auth)
}

/**
 * 动作区按钮（48dp / 圆角 12 / 15.5sp·600）。主钮 = accent 实底
 * （深色档纸底墨字）；描边钮 = 透明底 + `--border-strong` 描边（`.btn-ghost`）——
 * 显式指定印系令牌，不取 Material 缺省 outline。
 */
@Composable
private fun ConnectActionButton(label: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    val text: @Composable RowScope.() -> Unit = {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag(tag),
            shape = BiuZRadius.medium,
            content = text,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag(tag),
            shape = BiuZRadius.medium,
            border = BorderStroke(1.dp, BiuZBorderStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BiuZText),
            content = text,
        )
    }
}

/**
 * 连接失败卡（2026-11-24 交互重构）：44dp 红图标位 +
 * 一行结论 + 一句话解释 + 目标电脑。不再渲染徽标 / mono 错误码 / 排查清单 / 次级按钮——
 * 恢复动作由页面动作区的两个按钮承担（主 CTA 随 [ConnectRecovery] 切换）。
 * 配色走印系令牌：卡底 `bgCard` + 红描边（`red` 55%），不用 Material `errorContainer`
 * 叠透明度（旧实现的暗红大色块即配色跑偏来源）。
 * 技术原文（codeLine）与排查行仅开发者模式可见——§5-12「拒因不丢」对开发者仍成立。
 */
@Composable
private fun ConnectFailedCard(server: ServerConfig, error: ConnectError, developerMode: Boolean) {
    val reason = failureReasonForConnectError(error)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BiuZBgCard, BiuZRadius.medium)
            .border(1.dp, BiuZRed.copy(alpha = BiuZAlpha.half), BiuZRadius.medium)
            .padding(14.dp)
            .testTag("l3-card-failed"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(BiuZRedDim, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = BiuZRed,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(BiuZSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(connectFailureTitleRes(reason)),
                    style = MaterialTheme.typography.titleMedium,
                    color = BiuZText,
                )
                Spacer(Modifier.height(BiuZSpacing.xs))
                Text(
                    text = stringResource(connectFailureBodyRes(reason)),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = BiuZText2,
                )
            }
        }
        Spacer(Modifier.height(BiuZSpacing.md))
        Text(
            text = "${server.displayName} · ${server.displayAddress}",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = BiuZText3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (developerMode) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = error.codeLine,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = BiuZText3,
            )
            connectFailureHints(error).forEach { hint ->
                Text(
                    text = "· ${connectFailureHintText(hint)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = BiuZText3,
                )
            }
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.connect_dev_target_fmt, server.displayAddress),
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
            )
        }
    }
}

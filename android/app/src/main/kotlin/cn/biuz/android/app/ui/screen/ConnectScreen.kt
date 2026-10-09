package cn.biuz.android.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * 手动输入地址 / 桌面端指引 / 令牌说明收进开发者模式（BuildConfig.DEBUG 调试开关等效
 * + 品牌行连点 7 次切换手势预留，iOS 落位为设置页品牌行、Android 设置页随后续里程碑接线）。
 *
 * 失败态如实呈现（工程纪律 §5-12）：服务端拒因原文上屏（headline + mono codeLine），
 * 按 L3 四态对照给排查行，禁笼统「连接失败」。
 */
@Composable
fun ConnectScreen(
    session: AppSession,
    onOpenScan: () -> Unit,
    onOpenSessions: () -> Unit,
    developerMode: Boolean,
    onToggleDeveloperModeOverride: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by session.mode.collectAsStateWithLifecycle()
    val connectionState by session.connection.state.collectAsStateWithLifecycle()
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

    // MARK: 品牌行连点 7 次（开发者模式手势预留；§5-17 iOS 设置页品牌行同款交互）
    var brandTaps by remember { mutableStateOf(0) }

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
            title = { Text("删除此服务器记录？") },
            text = { Text("将清除已保存的「${target.displayName}」地址与访问令牌。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFor = null
                    session.deleteServer(target)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFor = null }) { Text("取消") }
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
                        .padding(BiuZSpacing.md),
                ) {
                    Text(
                        text = "检测到连接链接",
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
                                manualError = ConnectFlow.connectFromText(session, raw)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = BiuZRadius.medium,
                    ) {
                        Text("一键填充")
                    }
                }
                Spacer(Modifier.height(BiuZSpacing.xl))
            }

            // MARK: 品牌区（连点 7 次切开发者模式手势预留；§5-17）
            Text(
                text = "BiuZ",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    brandTaps += 1
                    if (brandTaps >= 7) {
                        brandTaps = 0
                        onToggleDeveloperModeOverride()
                    }
                },
            )
            Spacer(Modifier.height(BiuZSpacing.sm))
            Text(
                text = "移动遥控台 · 把任务 biu 地发给你的 coding agent",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                // 商标裁决（branding/naming.md）：仅以描述性「works with ZCode」表达社区版身份
                text = "A mobile remote console that works with ZCode",
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
                    .height(48.dp),
                shape = BiuZRadius.medium,
            ) {
                Text("扫码连接桌面端")
            }
            Spacer(Modifier.height(BiuZSpacing.xl))

            // MARK: 连接生命周期五态呈现（L2 五步 / L3 失败对照 / 断线橙横幅）
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
                        text = "已连接 · ${m.server.displayName}",
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
                        Text("进入会话列表")
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
                            text = "与桌面端的连接已断开 · 显示断线前的数据",
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
                            Text("重新连接")
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
                        .height(44.dp),
                    shape = BiuZRadius.medium,
                ) {
                    Text(if (manualExpanded) "收起手动连接" else "手动输入地址连接")
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
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("地址 · host:port 或完整链接") },
                            placeholder = { Text("192.168.1.24:3030") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        OutlinedTextField(
                            value = tokenText,
                            onValueChange = { tokenText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("访问令牌（可选）") },
                            supportingText = {
                                Text(
                                    "安全输入 · 可选：--no-token 部署可留空；完整链接里的 token 自动拆解",
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
                                    manualError = ConnectFlow.connectFromText(session, addressText, tokenText)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = BiuZRadius.medium,
                            enabled = addressText.isNotBlank(),
                        ) {
                            Text("连接")
                        }
                    }
                }

                Spacer(Modifier.height(BiuZSpacing.sm))
                Text(
                    text = "如何在桌面端开启？ · $ zcode --web --host 0.0.0.0",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { helpExpanded = !helpExpanded }
                        .padding(vertical = BiuZSpacing.sm),
                )
                if (helpExpanded) {
                    Text(
                        text = "① 桌面机执行 zcode --web --host 0.0.0.0 [--port 3030]，终端打印带令牌链接（http://<LAN-IP>:<port>/?token=…）\n" +
                            "② 手机与桌面端须同一局域网；Android 无「本地网络」权限弹窗，若超时先查热点/VPN 隔离\n" +
                            "③ 令牌随链接下发、仅保存在本机应用私有存储；--no-token 部署（authRequired=false）令牌可留空",
                        style = MaterialTheme.typography.labelMedium,
                        color = BiuZText3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(BiuZSpacing.xl))
            Text(
                text = "凭据仅保存在本机 · 连接仅面向可信局域网 · 只读边界由桌面端强制",
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xl))
        }
    }
}

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
            val dotColor = when {
                probe == null -> BiuZText3.copy(alpha = 0.4f)
                probe.error == null && probe.info != null -> MaterialTheme.colorScheme.primary
                else -> BiuZText3
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.size(BiuZSpacing.sm))
            Text(
                text = server.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            server.lastConnectedAt?.let { last ->
                Text(
                    text = "上次连接 " + SimpleDateFormat("M 月 d 日 HH:mm", Locale.CHINA).format(Date(last)),
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
            Text("连接")
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
            text = "正在连接 ${server.displayName}",
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
        val stepTitles = listOf("发现桌面端", "鉴权", "WS 通道", "v4 握手", "工作区")
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
                text = "连接中 · ${progress?.completedCount ?: 0}/${steps.size}",
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
            Text("取消")
        }
    }
}

// MARK: - L3 失败页（服务端拒因原文 + 四态对照 + 主 CTA 不死路）

/** L3 对照行（l3-row-401/timeout/protocol/empty-ws；超时必含局域网检查项）。 */
internal fun connectFailureGuidance(error: ConnectError): List<String> = when (error) {
    is ConnectError.Http -> if (error.status == 401) {
        listOf(
            "令牌不匹配 · 重新扫码更新令牌",
            if (error.endpoint.isNotBlank()) "被拒端点：${error.endpoint}" else "",
        )
    } else {
        listOf("服务返回 HTTP ${error.status} · 确认桌面端服务正常")
    }
    is ConnectError.Timeout -> listOf(
        "确认手机与桌面端在同一局域网",
        "确认桌面端以 --host 0.0.0.0 启动（非 loopback）",
        "热点隔离 / VPN 也会静默超时",
    )
    is ConnectError.ProtocolVersion -> listOf("移动端与桌面端协议版本不一致 · 升级其中一端")
    ConnectError.EmptyWorkspaces -> listOf("桌面端启动未携带 --workspace · 检查启动参数")
    is ConnectError.HandshakeFailed -> listOf("v4 握手被拒 · 拒因见上方错误码行")
    is ConnectError.Transport -> listOf("无法建立 WebSocket · 确认端口与防火墙")
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
            .padding(BiuZSpacing.md),
    ) {
        // 红横幅口径：headline + mono 错误码行（服务端拒因原文上屏，禁笼统「连接失败」）
        Text(
            text = "连接失败 · ${error.headline}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(BiuZSpacing.xs))
        Text(
            text = error.codeLine,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = BiuZText3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        connectFailureGuidance(error).filter { it.isNotBlank() }.forEach { line ->
            Text(
                text = "· $line",
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
                .height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            Text("重试连接")
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = BiuZRadius.medium,
            ) {
                Text("重新扫码", maxLines = 1)
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = BiuZRadius.medium,
            ) {
                Text("删除记录", color = MaterialTheme.colorScheme.error, maxLines = 1)
            }
        }
        if (developerMode) {
            Spacer(Modifier.height(BiuZSpacing.xs))
            Text(
                text = "目标 ${server.displayAddress}（开发者模式可在连接页手动改址/改令牌）",
                style = MaterialTheme.typography.labelSmall,
                color = BiuZText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

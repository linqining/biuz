package cn.biuz.android.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.JSONValue
import kotlinx.coroutines.launch

/** G41 页内装载三态。 */
private sealed interface BehaviorPhase {
    data object Loading : BehaviorPhase
    data class Failed(val message: String) : BehaviorPhase
    data object Loaded : BehaviorPhase
}

/**
 * G41 行为设置页（web AgentBehaviorSectionContent 移动端同构，2026-11-25 与 iOS
 * BehaviorSettingsPage 同策展面）：桌面行为设置五键原生分组——读写走 setting 频道
 * （RemoteConversationStore.behaviorSettings/setBehaviorSetting；键权威【实证·上游仓
 * settingsPageHelpers.tsx:826-992】）。写直接下发（web toggle 变更即发；busy 期
 * 禁改 + 失败错误行；写永不重试防双写）。
 */
@Composable
fun BehaviorSettingsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val surface by session.storeSurface.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val store = surface.conversation
    val scope = rememberCoroutineScope()
    val connected = mode is AppSession.Mode.Connected

    var phase by remember { mutableStateOf<BehaviorPhase>(BehaviorPhase.Loading) }
    var interactionBehavior by remember { mutableStateOf("queue") }
    var askAutoResolution by remember { mutableStateOf(true) }
    var modelIoRetention by remember { mutableStateOf(false) }
    var showTodos by remember { mutableStateOf(false) }
    var showReasoning by remember { mutableStateOf(true) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    suspend fun applySettings(settings: Map<String, JSONValue>) {
        interactionBehavior = settings["zcodeInteractionBehavior"]?.stringValue ?: "queue"
        askAutoResolution = settings["askUserQuestionAutoResolutionEnabled"]?.boolValue ?: true
        modelIoRetention = settings["modelIoFullRetentionEnabled"]?.boolValue ?: false
        showTodos = settings["messageStreamShowTodos"]?.boolValue ?: false
        showReasoning = settings["messageStreamShowReasoning"]?.boolValue ?: true
    }

    LaunchedEffect(connected, surface.epoch) {
        if (!connected) {
            phase = BehaviorPhase.Failed("连接桌面端后可读写")
            return@LaunchedEffect
        }
        phase = BehaviorPhase.Loading
        val settings = runCatching { store.behaviorSettings() }.getOrNull()
        if (settings == null) {
            phase = BehaviorPhase.Failed("设置读取失败（连接中断或桌面端不支持）")
        } else {
            applySettings(settings)
            phase = BehaviorPhase.Loaded
            errorText = null
        }
    }

    fun write(key: String, value: JSONValue, onApplied: () -> Unit) {
        if (busyKey != null) return
        busyKey = key
        errorText = null
        scope.launch {
            val failure = runCatching { store.setBehaviorSetting(key, value) }
                .getOrNull().let { it as String? }
            busyKey = null
            if (failure == null) {
                onApplied()
            } else {
                errorText = "写入失败：$failure"
            }
        }
    }

    SettingsPageScaffold(title = "行为设置", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.lg),
        ) {
            Spacer(Modifier.height(BiuZSpacing.sm))
            when (val current = phase) {
                is BehaviorPhase.Loading -> Text(
                    text = "读取中…",
                    fontSize = 13.sp,
                    color = BiuZText3,
                    modifier = Modifier
                        .padding(vertical = BiuZSpacing.offsetLg)
                        .testTag("12-behavior-loading"),
                )
                is BehaviorPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    Text(
                        text = current.message,
                        fontSize = 13.sp,
                        color = BiuZRed,
                        modifier = Modifier
                            .padding(vertical = BiuZSpacing.lg)
                            .testTag("12-behavior-failed"),
                    )
                    Text(
                        text = "重试",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BiuZAccent,
                        modifier = Modifier
                            .clip(BiuZRadius.small)
                            .clickable {
                                scope.launch {
                                    phase = BehaviorPhase.Loading
                                    val settings = runCatching { store.behaviorSettings() }.getOrNull()
                                    if (settings == null) {
                                        phase = BehaviorPhase.Failed("设置读取失败（连接中断或桌面端不支持）")
                                    } else {
                                        applySettings(settings)
                                        phase = BehaviorPhase.Loaded
                                    }
                                }
                            }
                            .padding(BiuZSpacing.sm)
                            .testTag("12-behavior-retry"),
                    )
                }
                BehaviorPhase.Loaded -> {
                    // 与设置主页同一设计语言：分组标题 + 纸底卡 + 48/56dp 行（原为
                    // 自绘 SectionCard + 12sp 胶囊 chips——2026-11-25 用户报「设计风格不符」）
                    SettingsGroup(title = "交互") {
                        Text(
                            text = "审批响应方式",
                            style = MaterialTheme.typography.labelMedium,
                            color = BiuZText3,
                            modifier = Modifier.padding(
                                start = BiuZSpacing.md,
                                top = BiuZSpacing.sm,
                                bottom = BiuZSpacing.xxs,
                            ),
                        )
                        SettingsCheckRow(
                            title = "排队等待确认",
                            selected = interactionBehavior == "queue",
                            testTagId = "12-behavior-interaction-queue",
                            onClick = {
                                write("zcodeInteractionBehavior", JSONValue.Str("queue")) {
                                    interactionBehavior = "queue"
                                }
                            },
                        )
                        HorizontalDivider(color = BiuZBorder)
                        SettingsCheckRow(
                            title = "逐条引导确认",
                            selected = interactionBehavior == "guide",
                            testTagId = "12-behavior-interaction-guide",
                            onClick = {
                                write("zcodeInteractionBehavior", JSONValue.Str("guide")) {
                                    interactionBehavior = "guide"
                                }
                            },
                        )
                        HorizontalDivider(color = BiuZBorder)
                        SettingsToggleRow(
                            title = "提问自动继续",
                            subtitle = "Agent 提问后按默认答案自动继续",
                            isOn = askAutoResolution,
                            onToggle = { newValue ->
                                write("askUserQuestionAutoResolutionEnabled", JSONValue.Bool(newValue)) {
                                    askAutoResolution = newValue
                                }
                            },
                            testTagId = "12-behavior-ask-auto",
                            enabled = busyKey == null,
                        )
                    }
                    SettingsGroup(title = "消息流") {
                        SettingsToggleRow(
                            title = "显示思考过程",
                            subtitle = null,
                            isOn = showReasoning,
                            onToggle = { newValue ->
                                write("messageStreamShowReasoning", JSONValue.Bool(newValue)) {
                                    showReasoning = newValue
                                }
                            },
                            testTagId = "12-behavior-reasoning",
                            enabled = busyKey == null,
                        )
                        HorizontalDivider(color = BiuZBorder)
                        SettingsToggleRow(
                            title = "显示待办列表",
                            subtitle = null,
                            isOn = showTodos,
                            onToggle = { newValue ->
                                write("messageStreamShowTodos", JSONValue.Bool(newValue)) {
                                    showTodos = newValue
                                }
                            },
                            testTagId = "12-behavior-todos",
                            enabled = busyKey == null,
                        )
                    }
                    SettingsGroup(title = "数据") {
                        SettingsToggleRow(
                            title = "完整保留模型输入输出",
                            subtitle = "关闭时只保留近期轮次，节省磁盘",
                            isOn = modelIoRetention,
                            onToggle = { newValue ->
                                write("modelIoFullRetentionEnabled", JSONValue.Bool(newValue)) {
                                    modelIoRetention = newValue
                                }
                            },
                            testTagId = "12-behavior-modelio",
                            enabled = busyKey == null,
                        )
                    }
                    errorText?.let {
                        Text(text = it, fontSize = 12.sp, color = BiuZRed)
                    }
                    Text(
                        text = "这些设置写入桌面端并即时生效。",
                        fontSize = 11.sp,
                        color = BiuZText3,
                    )
                    Spacer(Modifier.height(BiuZSpacing.xl))
                }
            }
        }
    }
}

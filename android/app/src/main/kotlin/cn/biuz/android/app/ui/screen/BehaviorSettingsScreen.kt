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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.TextStyle
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
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
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
                    .testTag("12-behavior-back"),
            )
            Text(
                text = "行为设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiuZSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
        ) {
            when (val current = phase) {
                is BehaviorPhase.Loading -> Text(
                    text = "读取中…",
                    fontSize = 13.sp,
                    color = BiuZText3,
                    modifier = Modifier
                        .padding(vertical = BiuZSpacing.offsetLg)
                        .testTag("12-behavior-loading"),
                )
                is BehaviorPhase.Failed -> Column {
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
                    SectionCard(title = "交互") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "审批响应方式",
                                fontSize = 13.sp,
                                color = BiuZText,
                                modifier = Modifier.weight(1f),
                            )
                            enumChip("排队", interactionBehavior == "queue", busyKey == null, "12-behavior-interaction-queue") {
                                write("zcodeInteractionBehavior", JSONValue.Str("queue")) {
                                    interactionBehavior = "queue"
                                }
                            }
                            Spacer(Modifier.size(BiuZSpacing.xs))
                            enumChip("引导", interactionBehavior == "guide", busyKey == null, "12-behavior-interaction-guide") {
                                write("zcodeInteractionBehavior", JSONValue.Str("guide")) {
                                    interactionBehavior = "guide"
                                }
                            }
                        }
                        SwitchRow(
                            title = "提问自动继续",
                            checked = askAutoResolution,
                            enabled = busyKey == null,
                            testTag = "12-behavior-ask-auto",
                        ) { newValue ->
                            write("askUserQuestionAutoResolutionEnabled", JSONValue.Bool(newValue)) {
                                askAutoResolution = newValue
                            }
                        }
                    }
                    SectionCard(title = "消息流") {
                        SwitchRow(
                            title = "显示思考过程",
                            checked = showReasoning,
                            enabled = busyKey == null,
                            testTag = "12-behavior-reasoning",
                        ) { newValue ->
                            write("messageStreamShowReasoning", JSONValue.Bool(newValue)) {
                                showReasoning = newValue
                            }
                        }
                        SwitchRow(
                            title = "显示待办列表",
                            checked = showTodos,
                            enabled = busyKey == null,
                            testTag = "12-behavior-todos",
                        ) { newValue ->
                            write("messageStreamShowTodos", JSONValue.Bool(newValue)) {
                                showTodos = newValue
                            }
                        }
                    }
                    SectionCard(title = "数据") {
                        SwitchRow(
                            title = "完整保留模型输入输出",
                            checked = modelIoRetention,
                            enabled = busyKey == null,
                            testTag = "12-behavior-modelio",
                        ) { newValue ->
                            write("modelIoFullRetentionEnabled", JSONValue.Bool(newValue)) {
                                modelIoRetention = newValue
                            }
                        }
                    }
                    errorText?.let {
                        Text(text = it, fontSize = 12.sp, color = BiuZRed)
                    }
                    Text(
                        text = "这些设置写入桌面端并即时生效。",
                        fontSize = 11.sp,
                        color = BiuZText3,
                    )
                }
            }
        }
    }
}

/** G41 分节卡（bgCard 圆角 + 标题行）。 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .padding(BiuZSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = BiuZText3,
        )
        content()
    }
}

/** G41 枚举 chip（排队/引导两档单选）。 */
@Composable
private fun enumChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = if (selected) BiuZAccent else BiuZText2,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) BiuZAccent.copy(alpha = BiuZAlpha.faint) else BiuZBgInput)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs)
            .testTag(testTag),
    )
}

/** G41 开关行（13sp 标题 + Switch；busy 期禁改）。 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = BiuZText,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        )
    }
}

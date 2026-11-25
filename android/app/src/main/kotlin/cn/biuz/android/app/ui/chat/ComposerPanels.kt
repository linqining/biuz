package cn.biuz.android.app.ui.chat

// composer 共享选行/队列条（模型面板 + 新建会话 sheet 两个宿主同构语言；iOS
// ComposerOptionSheet 模型分节 :417-473 / QueueBarView :1326-1495 直译）。
//
// 三条裁决落点：
// - **复合键勾选跟随点选**（iOS :437-452 注释逐字语义）：选中勾跟随用户点选行
//   （pickedRowKey）；未点选过才按桌面当前绑定预勾（activeProviderId 消歧同名跨套餐）
//   ——此前勾恒按「provider == 桌面绑定」比对，点同名他套餐行勾纹丝不动，观感「选不中」；
// - 行键携「套餐|模型」复合键（同名模型跨套餐按组解析 providerId——裸名表后到覆盖先到，
//   点个人套餐行曾带出体验套餐 providerId，2026-10-08 报障）；
// - 队列条操作失败走常驻错误行（任务口径「失败 3s 气泡一律改常驻」；iOS 3s feedback
//   不复制）。
//
// 纯展示层：回调面全部由宿主注入（零 VM/Store 依赖），JVM 单测不涉及。

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnAccent
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZRed
import cn.biuz.android.app.ui.theme.BiuZRedDim
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.pressableScale
import cn.biuz.android.store.ConversationQueueInfo
import cn.biuz.android.store.ModelPlanGroup
import cn.biuz.android.store.ModelSelectionInfo

/** 队列条限高（条目多时内部滚动；与审批卡限高同族——键盘在场防顶出屏）。 */
private val QUEUE_BAR_MAX_HEIGHT = 200.dp

/**
 * 模型选行组（getView planGroups 分节 + 复合键勾选；[onPickModel] 收「套餐|模型」
 * 复合键或裸名——分组缺席时裸名直出行键）。
 *
 * 勾选条件（iOS NewConversationSheet :441-452 逐字直译）：
 * - 用户点选过（pickedRowKey != null）：勾跟随点选行；
 * - 未点选过：按桌面当前绑定预勾（model == activeModel 且 provider 复合表与
 *   activeProviderId 一致——同名跨套餐不误勾他组）。
 */
@Composable
fun ModelOptionSection(
    info: ModelSelectionInfo,
    pickedRowKey: String?,
    onPickModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 批4（2026-11-25 与 iOS ModelPickerSheet 同构）：搜索 + 最近使用（cap 3）+
    // 分组（套餐原序 + 「其他」组——models 里不在任一套餐组的模型整组归入其他，
    // web composerPlanGroups.ts:74-120 同语义；此前非套餐 provider（ccgithub 等）
    // 整组消失，行键「其他|模型」经 providerForRowKey 复合表/裸名表兜底解析）
    var query by remember { mutableStateOf("") }
    val recents = remember(info) { ModelRecentsStore.load() }
    val sections = remember(info) { buildModelSections(info) }
    val trimmed = query.trim()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.composer_model_search), fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("05-model-search"),
        )
        if (trimmed.isEmpty() && recents.isNotEmpty()) {
            Text(
                text = stringResource(R.string.composer_model_recents),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText3,
                modifier = Modifier.padding(top = BiuZSpacing.xs),
            )
            recents.forEach { rowKey ->
                val label = info.modelLabelForRowKey(rowKey)
                OptionRow(
                    title = label,
                    checked = pickedRowKey == rowKey ||
                        (pickedRowKey == null && label == info.activeModel),
                    onClick = { onPickModel(rowKey) },
                    testTag = "05-model-row-recent|$rowKey",
                )
            }
        }
        sections.forEach { group ->
            val matched = group.models.filter {
                trimmed.isEmpty() || it.contains(trimmed, ignoreCase = true)
            }
            if (matched.isEmpty()) return@forEach
            if (group.plan.isNotEmpty()) {
                Text(
                    text = group.plan,
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = BiuZText3,
                    modifier = Modifier.padding(top = BiuZSpacing.xs),
                )
            }
            matched.forEach { model ->
                // 空组（无分组退化）= 裸名行键（iOS :425-432 同构）
                val rowKey = if (group.plan.isEmpty()) model else "${group.plan}|$model"
                val checked = if (pickedRowKey != null) {
                    pickedRowKey == rowKey
                } else {
                    val providerMatches = !info.activeProviderId.isNullOrEmpty() &&
                        info.modelProvidersByPlan[rowKey] == info.activeProviderId
                    model == info.activeModel &&
                        (info.activeProviderId.isNullOrEmpty() || providerMatches)
                }
                OptionRow(
                    title = model,
                    checked = checked,
                    onClick = { onPickModel(rowKey) },
                    testTag = if (group.plan.isEmpty()) "05-model-row-$model" else "05-model-row-${group.plan}|$model",
                )
            }
        }
        if (trimmed.isNotEmpty() && sections.all { group ->
                group.models.none { it.contains(trimmed, ignoreCase = true) }
            }) {
            Text(
                text = stringResource(R.string.composer_model_no_match),
                style = TextStyle(fontSize = 12.sp),
                color = BiuZText3,
            )
        }
    }
}

/**
 * 批4 分组装配（纯函数，可测；iOS groupedSectionModel 同构）：套餐组原序 +
 * 「其他」组（models 里不在任一套餐组的裸名整组归入——组名「其他」，行键
 * 「其他|模型」；claim 判定 = model ∈ 任一 planGroups[].models）。
 */
internal fun buildModelSections(info: ModelSelectionInfo): List<ModelPlanGroup> {
    if (info.planGroups.isEmpty()) {
        // 无分组（getView 未带 planGroups）：退化单组直出（iOS :425-432 同构）
        return info.models.map { ModelPlanGroup(plan = "", models = listOf(it)) }
    }
    val claimed = info.planGroups.flatMap { it.models }.toSet()
    val others = info.models.filter { it !in claimed }
    val list = ArrayList<ModelPlanGroup>(info.planGroups)
    if (others.isNotEmpty()) {
        list.add(ModelPlanGroup(plan = "其他", models = others))
    }
    return list
}

/** 单套餐分节（节标题 = 套餐名；行键 =「套餐|模型」复合键）。 */
@Composable
private fun ModelPlanRows(
    info: ModelSelectionInfo,
    group: ModelPlanGroup,
    pickedRowKey: String?,
    onPickModel: (String) -> Unit,
) {
    Text(
        text = group.plan,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = BiuZText3,
        modifier = Modifier.padding(top = BiuZSpacing.xs),
    )
    group.models.forEach { model ->
        val rowKey = "${group.plan}|$model"
        val checked = if (pickedRowKey != null) {
            pickedRowKey == rowKey
        } else {
            val providerMatches = !info.activeProviderId.isNullOrEmpty() &&
                info.modelProvidersByPlan[rowKey] == info.activeProviderId
            model == info.activeModel &&
                (info.activeProviderId.isNullOrEmpty() || providerMatches)
        }
        OptionRow(title = model, checked = checked, onClick = { onPickModel(rowKey) })
    }
}

/** 思考档选行组（词表按当前模型查询，缺席退化静态梯——宿主拼好传入）。 */
@Composable
fun ThoughtOptionSection(
    levels: List<String>,
    pickedLevel: String?,
    activeLevel: String?,
    onPickThought: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs)) {
        levels.forEach { level ->
            OptionRow(
                title = level,
                checked = level == pickedLevel || (pickedLevel == null && level == activeLevel),
                onClick = { onPickThought(level) },
            )
        }
    }
}

/** 面板行（44pt 热区；选中 = 勾标 + accent 描边，iOS ComposerOptionItem 选中语言同构）。 */
@Composable
private fun OptionRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    // 面板选项行 = 报告 §4.4 第 3 类（行）：按压实，无触觉
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .pressableScale(interaction)
            .clip(BiuZRadius.medium)
            .background(BiuZBgInput)
            .border(
                1.dp,
                if (checked) BiuZAccent else BiuZBorderStrong,
                BiuZRadius.medium,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = BiuZText,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Text(text = "✓", color = BiuZAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 空清单「重新获取」行（面板不空白死路；iOS reload-model 项 :420-423 同位）。 */
@Composable
fun ModelRefetchRow(onRefetch: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(BiuZRadius.medium)
            .background(BiuZBgInput)
            .border(1.dp, BiuZAccent, BiuZRadius.medium)
            .clickable(onClick = onRefetch)
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↻",
            fontSize = 15.sp,
            color = BiuZAccent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(BiuZSpacing.sm))
        Text(
            text = stringResource(R.string.composer_model_refetch),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = BiuZAccent,
        )
    }
}

/**
 * 排队消息条（桌面 composer pending 队列同构：立即 / 编辑 / 删除 / 上移 / 自动排空；
 * iOS QueueBarView :1326-1495 直译，紧贴 composer 上方，无排队不渲染）。
 *
 * [failureText] 非空 = 最近一次队列操作失败/未知态（**常驻**错误行，成功操作即清；
 * 不复制 iOS 3s 自动清除——任务口径「失败 3s 气泡一律改常驻」）。
 */
@Composable
fun QueueBar(
    queue: ConversationQueueInfo,
    failureText: String?,
    onSendNow: (String) -> Unit,
    onEditSave: (String, String) -> Unit,
    onMoveUp: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleAutoDrain: (Boolean) -> Unit,
    /** G13 长按拖排序（桌面 hover-drag 触屏对位）：(draggedId, targetId)——拖起项移到落点行之前 */
    onReorder: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    // G13 拖拽态：拖起项 / 落点行 / 行窗口坐标（onGloballyPositioned 采集）
    var draggingItemId by remember { mutableStateOf<String?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    val rowBounds = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var dragStartCenterY by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BiuZSpacing.lg)
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        // 头行：计数 + 自动排空开关（05-queue-act-autodrain 同位）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "▤",
                fontSize = 12.sp,
                color = BiuZSeal,
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            Text(
                text = stringResource(R.string.queue_title, queue.items.size),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (queue.autoDrain) {
                    stringResource(R.string.queue_autodrain_on)
                } else {
                    stringResource(R.string.queue_autodrain_off)
                },
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = if (queue.autoDrain) BiuZAccent else BiuZText3,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (queue.autoDrain) BiuZAccent.copy(alpha = BiuZAlpha.faint) else BiuZBgInput)
                    .clickable { onToggleAutoDrain(!queue.autoDrain) }
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = QUEUE_BAR_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
        ) {
            queue.items.forEach { item ->
                // G13 长按拖：detectDragGesturesAfterLongPress 起拖（iOS onDrag 同触屏
                // 语义），行中心累计位移定位落点行（window 坐标系一致）；落点行描边
                // 朱磦反馈，松手 onReorder CAS——编辑态行不参与
                val rowModifier = if (editingItemId != null) Modifier else Modifier
                    .onGloballyPositioned { coords ->
                        rowBounds[item.id] = coords.boundsInWindow()
                    }
                    .pointerInput(item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingItemId = item.id
                                dropTargetId = null
                                dragOffsetY = 0f
                                dragStartCenterY =
                                    (rowBounds[item.id]?.center?.y ?: 0f)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetY += amount.y
                                val centerY = dragStartCenterY + dragOffsetY
                                dropTargetId = rowBounds.entries
                                    .filter { it.key != draggingItemId }
                                    .firstOrNull { (_, rect) -> centerY in rect.top..rect.bottom }
                                    ?.key
                            },
                            onDragEnd = {
                                val dragged = draggingItemId
                                val target = dropTargetId
                                if (dragged != null && target != null) {
                                    onReorder(dragged, target)
                                }
                                draggingItemId = null
                                dropTargetId = null
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                draggingItemId = null
                                dropTargetId = null
                                dragOffsetY = 0f
                            },
                        )
                    }
                QueueRow(
                    item = item,
                    canMoveUp = queue.items.firstOrNull()?.id != item.id,
                    rowModifier = rowModifier,
                    isDragging = draggingItemId == item.id,
                    isDropTarget = dropTargetId == item.id && draggingItemId != null,
                    editing = editingItemId == item.id,
                    editingText = editingText,
                    onEditingTextChange = { editingText = it },
                    onEditStart = {
                        editingText = item.text
                        editingItemId = item.id
                    },
                    onEditCancel = { editingItemId = null },
                    onEditSave = {
                        // 空文本禁发（iOS「内容不能为空」同口径的 UI 拦截面——按钮禁用）
                        if (editingText.isNotBlank()) {
                            onEditSave(item.id, editingText)
                            editingItemId = null
                        }
                    },
                    onSendNow = { onSendNow(item.id) },
                    onMoveUp = { onMoveUp(item.id) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }
        failureText?.let { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BiuZRadius.small)
                    .background(BiuZRedDim)
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "⚠", fontSize = 11.sp, color = BiuZRed)
                Spacer(Modifier.size(BiuZSpacing.xs))
                Text(
                    text = text,
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = BiuZRed,
                )
            }
        }
    }
}

/** 单条排队消息（立即 / 编辑 / 删除 / 上移；guide 模式条目标注）。 */
@Composable
private fun QueueRow(
    item: cn.biuz.android.store.RemoteQueueItem,
    canMoveUp: Boolean,
    editing: Boolean,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onEditStart: () -> Unit,
    onEditCancel: () -> Unit,
    onEditSave: () -> Unit,
    onSendNow: () -> Unit,
    onMoveUp: () -> Unit,
    onDelete: () -> Unit,
    /** G13 拖拽宿主 modifier（手势 + 坐标采集；宿主层组装，编辑态传空） */
    rowModifier: Modifier = Modifier,
    isDragging: Boolean = false,
    isDropTarget: Boolean = false,
) {
    if (editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = editingText,
                onValueChange = onEditingTextChange,
                textStyle = TextStyle(fontSize = 13.sp, color = BiuZText),
                modifier = Modifier
                    .weight(1f)
                    .clip(BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .border(1.dp, BiuZBorderStrong, BiuZRadius.medium)
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.tiny),
                maxLines = 4,
            )
            Spacer(Modifier.size(BiuZSpacing.sm))
            QueueActionText(
                label = stringResource(R.string.queue_act_save),
                color = BiuZOnAccent,
                container = BiuZAccent,
                enabled = editingText.isNotBlank(),
                onClick = onEditSave,
            )
            Spacer(Modifier.size(BiuZSpacing.xs))
            QueueActionText(
                label = stringResource(R.string.action_cancel),
                color = BiuZText2,
                container = BiuZBgInput,
                enabled = true,
                onClick = onEditCancel,
            )
        }
    } else {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = rowModifier
                .fillMaxWidth()
                .background(
                    when {
                        isDropTarget -> BiuZSeal.copy(alpha = BiuZAlpha.faint)
                        isDragging -> BiuZBgInput
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    },
                    BiuZRadius.small,
                )
                .border(
                    if (isDropTarget) 1.5.dp else 0.dp,
                    if (isDropTarget) BiuZSeal else androidx.compose.ui.graphics.Color.Transparent,
                    BiuZRadius.small,
                )
                .testTag("05-queue-row-${item.id}"),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.text,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    color = BiuZText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isGuide) {
                    Text(
                        text = stringResource(R.string.queue_guide_badge),
                        style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        color = BiuZSeal,
                    )
                }
            }
            Spacer(Modifier.width(BiuZSpacing.sm))
            QueueActionText(
                label = stringResource(R.string.queue_act_send_now),
                color = BiuZAccent,
                container = BiuZBgInput,
                enabled = true,
                onClick = onSendNow,
            )
            QueueActionText(
                label = stringResource(R.string.queue_act_edit),
                color = BiuZText2,
                container = BiuZBgInput,
                enabled = true,
                onClick = onEditStart,
            )
            if (canMoveUp) {
                QueueActionText(
                    label = stringResource(R.string.queue_act_move_up),
                    color = BiuZText2,
                    container = BiuZBgInput,
                    enabled = true,
                    onClick = onMoveUp,
                )
            }
            QueueActionText(
                label = stringResource(R.string.queue_act_delete),
                color = BiuZRed,
                container = BiuZBgInput,
                enabled = true,
                onClick = onDelete,
            )
        }
    }
}

/** 队列行小动作钮（文字胶囊；44dp 触达以 padding 撑足）。 */
@Composable
private fun QueueActionText(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    container: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(start = BiuZSpacing.xs)
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.tiny),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = if (enabled) color else BiuZText3,
        )
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzEmptyState
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.BiuzLoadingState
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.pressableScale
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.ModelSelectionOverride
import cn.biuz.android.connection.SubagentRow
import cn.biuz.android.connection.setBuiltInModelOverride
import cn.biuz.android.connection.setPluginAgentModelOverride
import cn.biuz.android.connection.SubagentsError
import cn.biuz.android.connection.SubagentsList
import cn.biuz.android.connection.createSubagent
import cn.biuz.android.connection.deleteSubagent
import cn.biuz.android.connection.loadSubagents
import cn.biuz.android.connection.setPluginAgentModelOverride
import cn.biuz.android.connection.setSubagentEnabled
import cn.biuz.android.connection.updateSubagent
import kotlinx.coroutines.launch

/**
 * 屏 026 · 子智能体——只读清单（web SubagentsSection 的移动对位）。
 *
 * 数据面：`subagents.list` {workspacePath, workspaceIdentity?, provider:"glm",
 * mode:"allRuntimeScopes"}【实证·上游仓 subagents.ts:15-41 + SubagentsSection.tsx:1404-1409】。
 * 分组 = web 口径：已安装（user scope）/ 内置子智能体 / 插件 profile；
 * 行展示 name · description 摘要 · 工具数（未配置 = 全部工具）· 启用徽标。
 * 只读：createAgent/updateAgent/deleteAgent/setEnabled/set*ModelOverride 写面
 * gate 拦截（第一波不接，登记 backlog）。
 */
@Composable
fun SubagentsScreen(
    session: AppSession,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var list by remember { mutableStateOf<SubagentsList?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 写面波次②：启停开关（仅 user scope 可编辑 agent——web supportsEnabledToggle 同口径）
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var actionNoticeIsError by remember { mutableStateOf(false) }
    var busyAgentId by remember { mutableStateOf<String?>(null) }
    // 写面波次③：编辑器（null=无；SubagentRow? 非 null=user 行编辑，"new" 标记新建）
    var editingAgent by remember { mutableStateOf<SubagentRow?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SubagentRow?>(null) }
    // 写面波次⑤：模型覆盖选择（built-in/plugin 行；null agent=关闭）
    var overrideTarget by remember { mutableStateOf<SubagentRow?>(null) }

    suspend fun reload() {
        loading = true
        failure = null
        notConnected = false
        try {
            val workspace = session.storeSurface.value.workspace
            list = session.loadSubagents(workspace?.path, workspace?.workspaceIdentity)
        } catch (e: SubagentsError.NotConnected) {
            notConnected = true
            list = null
        } catch (e: SubagentsError.Failed) {
            failure = e.message
        } catch (e: Throwable) {
            failure = e.message ?: e::class.simpleName ?: ""
        } finally {
            loading = false
        }
    }

    val mode by session.mode.collectAsStateWithLifecycle()
    val connected = mode is AppSession.Mode.Connected
    LaunchedEffect(connected) { if (connected) reload() }

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
                text = stringResource(R.string.subagents_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
            )
            Spacer(Modifier.weight(1f))
            // 写面波次③：新建子智能体（user scope；web addNew 同位）
            Text(
                text = "＋",
                fontSize = 20.sp,
                color = BiuZAccent,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable {
                        creatingNew = true
                        editingAgent = null
                    }
                    .padding(horizontal = BiuZSpacing.md)
                    .testTag("26-act-new"),
            )
        }
        HorizontalDivider(color = BiuZBorder)

        when {
            notConnected -> BiuzEmptyState(
                title = stringResource(R.string.desktop_not_connected),
                hint = stringResource(R.string.subagents_not_connected_hint),
                modifier = Modifier.testTag("26-not-connected"),
            )

            loading && list == null -> BiuzLoadingState(
                text = stringResource(R.string.subagents_loading),
                tag = "26-loading",
            )

            failure != null && list == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(BiuZSpacing.md),
            ) {
                BiuzInlineNotice(
                    message = stringResource(R.string.desktop_load_failed) + " · " + failure,
                    onRetry = { scope.launch { reload() } },
                    tag = "26-load-error",
                )
            }

            (list?.agents?.isEmpty() ?: true) -> BiuzEmptyState(
                title = stringResource(R.string.subagents_none),
                hint = stringResource(R.string.subagents_none_hint),
                modifier = Modifier.testTag("26-empty"),
            )

            else -> {
                val agents = list?.agents.orEmpty()
                if (actionNotice != null) {
                    BiuzInlineNotice(
                        message = actionNotice.orEmpty(),
                        kind = if (actionNoticeIsError) cn.biuz.android.app.ui.components.BiuzNoticeKind.Error
                        else cn.biuz.android.app.ui.components.BiuzNoticeKind.Info,
                        modifier = Modifier.padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.xs),
                        tag = "26-action-notice",
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = BiuZSpacing.md, end = BiuZSpacing.md,
                        top = BiuZSpacing.sm, bottom = BiuZSpacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
                ) {
                    // 分组（web 口径）：已安装（user）/ 内置 / 插件 profile
                    val userAgents = agents.filter { it.source == "user" }
                    val builtIn = agents.filter { it.source == "built-in" }
                    val pluginAgents = agents.filter { it.source == "plugin" }
                    listOf(
                        Triple("user", userAgents, R.string.subagents_group_user),
                        Triple("built-in", builtIn, R.string.subagents_group_builtIn),
                        Triple("plugin", pluginAgents, R.string.subagents_group_plugin),
                    ).forEach { (key, rows, labelRes) ->
                        if (rows.isEmpty()) return@forEach
                        item(key = "group-$key") {
                            Text(
                                text = stringResource(labelRes, rows.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BiuZText3,
                                modifier = Modifier.padding(top = BiuZSpacing.xs),
                            )
                        }
                        items(rows, key = { "$key-${it.id}" }) { agent ->
                            SubagentRowView(
                                agent = agent,
                                busy = busyAgentId == agent.id,
                                onToggle = { newValue ->
                                    if (busyAgentId != null) return@SubagentRowView
                                    busyAgentId = agent.id
                                    scope.launch {
                                        val error = session.setSubagentEnabled(agent.id, newValue)
                                        busyAgentId = null
                                        actionNotice = error ?: "已${if (newValue) "启用" else "停用"} ${agent.name}"
                                        actionNoticeIsError = error != null
                                        if (error != null) reload()
                                    }
                                },
                                onEdit = {
                                    if (agent.source == "user" && !agent.readOnly) {
                                        creatingNew = false
                                        editingAgent = agent
                                    }
                                },
                                onDelete = {
                                    if (agent.source == "user" && !agent.readOnly) {
                                        pendingDelete = agent
                                    }
                                },
                                onModelOverride = {
                                    if (agent.source == "built-in" || agent.source == "plugin") {
                                        overrideTarget = agent
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    // 写面波次⑤：模型覆盖弹层（web SubagentModelOverrideControl :608-805 同位）——
    // 列「继承默认」+ model-selection.getView 投影模型清单（模型/思考档二段），
    // built-in 发 agentName、plugin 发 agentId；modelSelection 缺省=继承默认
    overrideTarget?.let { target ->
        var info by remember(target.id) {
            mutableStateOf<cn.biuz.android.store.ModelSelectionInfo?>(null)
        }
        LaunchedEffect(target.id) {
            val store = session.storeSurface.value.conversation
            info = (store as? cn.biuz.android.store.RemoteConversationStore)?.modelSelectionView()
        }
        var pickedModel by remember(target.id) { mutableStateOf<String?>(null) }
        val isBuiltIn = target.source == "built-in"
        val agentKey = if (isBuiltIn) target.name else target.id
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { overrideTarget = null },
            title = { Text(stringResource(R.string.subagents_model_override_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
                ) {
                    Text(
                        text = agentKey ?: "",
                        fontSize = 12.sp,
                        color = BiuZText3,
                    )
                    // 继承默认（modelSelection 缺省）
                    Text(
                        text = stringResource(R.string.subagents_model_inherit),
                        fontSize = 14.sp,
                        color = BiuZText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BiuZRadius.small)
                            .clickable {
                                scope.launch {
                                    val error = if (isBuiltIn) {
                                        session.setBuiltInModelOverride(agentKey ?: "", null)
                                    } else {
                                        session.setPluginAgentModelOverride(target.id, null)
                                    }
                                    overrideTarget = null
                                    actionNotice = error ?: "已恢复继承默认"
                                    actionNoticeIsError = error != null
                                    reload()
                                }
                            }
                            .padding(vertical = BiuZSpacing.sm)
                            .testTag("26-model-inherit"),
                    )
                    // 模型清单（getView 投影；模型 → 该模型思考档二段）
                    val models = info?.models.orEmpty()
                    if (models.isEmpty()) {
                        Text(
                            text = stringResource(R.string.subagents_model_empty),
                            fontSize = 12.sp,
                            color = BiuZText3,
                        )
                    } else {
                        models.forEach { model ->
                            Text(
                                text = model,
                                fontSize = 14.sp,
                                color = BiuZText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(BiuZRadius.small)
                                    .clickable { pickedModel = model }
                                    .padding(vertical = BiuZSpacing.sm)
                                    .testTag("26-model-$model"),
                            )
                            if (pickedModel == model) {
                                val thoughts = info?.thoughtByModel?.get(model).orEmpty()
                                if (thoughts.isEmpty()) {
                                    // 该模型无思考档 → 只发 model（reasoningLevel 缺省）
                                    TextButton(
                                        onClick = {
                                            val providerId = info?.modelProviders?.get(model)
                                                ?: info?.activeProviderId ?: "glm"
                                            scope.launch {
                                                val override = ModelSelectionOverride(
                                                    providerId, model, null,
                                                )
                                                val error = if (isBuiltIn) {
                                                    session.setBuiltInModelOverride(agentKey ?: "", override)
                                                } else {
                                                    session.setPluginAgentModelOverride(target.id, override)
                                                }
                                                overrideTarget = null
                                                actionNotice = error ?: "已设置 $model"
                                                actionNoticeIsError = error != null
                                                reload()
                                            }
                                        },
                                        modifier = Modifier.testTag("26-thought-inherit-$model"),
                                    ) { Text(stringResource(R.string.subagents_thought_inherit)) }
                                }
                                thoughts.forEach { thought ->
                                    TextButton(
                                        onClick = {
                                            val providerId = info?.modelProviders?.get(model)
                                                ?: info?.activeProviderId ?: "glm"
                                            scope.launch {
                                                val override = ModelSelectionOverride(
                                                    providerId, model, thought,
                                                )
                                                val error = if (isBuiltIn) {
                                                    session.setBuiltInModelOverride(agentKey ?: "", override)
                                                } else {
                                                    session.setPluginAgentModelOverride(target.id, override)
                                                }
                                                overrideTarget = null
                                                actionNotice = error ?: "已设置 $model/$thought"
                                                actionNoticeIsError = error != null
                                                reload()
                                            }
                                        },
                                        modifier = Modifier
                                            .padding(start = BiuZSpacing.lg)
                                            .testTag("26-thought-$model-$thought"),
                                    ) { Text(thought) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { overrideTarget = null }) {
                    Text(stringResource(R.string.desktop_act_cancel))
                }
            },
        )
    }

    // 写面波次③：编辑器 sheet（呈现期捕获传参 §5-15；name/description/systemPrompt
    // 三键编辑 + raw preserve 族随 update 下发；web SubagentForm 同参同面）
    val editorSeed = editingAgent ?: if (creatingNew) SubagentRow(
        id = "__new__", name = "", description = "", scope = "user", source = "user",
        enabled = true, readOnly = false, tools = null, color = null, pluginName = null,
        path = null, systemPrompt = "",
    ) else null
    if (editorSeed != null) {
        var name by remember(editorSeed.id) { mutableStateOf(editorSeed.name) }
        var description by remember(editorSeed.id) { mutableStateOf(editorSeed.description) }
        var systemPrompt by remember(editorSeed.id) { mutableStateOf(editorSeed.systemPrompt ?: "") }
        var saving by remember(editorSeed.id) { mutableStateOf(false) }
        val workspace = session.storeSurface.value.workspace
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!saving) {
                    editingAgent = null
                    creatingNew = false
                }
            },
            title = {
                Text(
                    text = if (creatingNew) stringResource(R.string.subagents_new_title)
                    else stringResource(R.string.subagents_edit_title),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.subagents_field_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("26-field-name"),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.subagents_field_description)) },
                        modifier = Modifier.fillMaxWidth().testTag("26-field-description"),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(stringResource(R.string.subagents_field_prompt)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("26-field-prompt"),
                    )
                    if (saving) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().testTag("26-saving"),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !saving && name.trim().isNotEmpty() && systemPrompt.trim().isNotEmpty(),
                    onClick = {
                        val target = editingAgent
                        val newName = name.trim()
                        val newDescription = description.trim()
                        val newPrompt = systemPrompt.trim()
                        saving = true
                        scope.launch {
                            val error = if (creatingNew || target == null) {
                                session.createSubagent(newName, newDescription, newPrompt,
                                    workspace?.path, workspace?.workspaceIdentity)
                            } else {
                                session.updateSubagent(target, newName, newDescription, newPrompt,
                                    workspace?.path, workspace?.workspaceIdentity)
                            }
                            saving = false
                            editingAgent = null
                            creatingNew = false
                            actionNotice = error
                                ?: (if (creatingNew) "已新建 $newName" else "已保存 $newName")
                            actionNoticeIsError = error != null
                            reload()
                        }
                    },
                    modifier = Modifier.testTag("26-act-save"),
                ) { Text(stringResource(R.string.desktop_act_update)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    enabled = !saving,
                    onClick = { editingAgent = null; creatingNew = false },
                ) { Text(stringResource(R.string.desktop_act_cancel)) }
            },
        )
    }

    // 删除确认（saveHooks 同族：写失败原文常驻上屏）
    pendingDelete?.let { agent ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.subagents_delete_title, agent.name)) },
            text = { Text(stringResource(R.string.subagents_delete_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val target = agent
                    pendingDelete = null
                    scope.launch {
                        val error = session.deleteSubagent(target)
                        actionNotice = error ?: "已删除 ${target.name}"
                        actionNoticeIsError = error != null
                        reload()
                    }
                }) { Text(stringResource(R.string.projects_remove_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.desktop_act_cancel))
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SubagentRowView(
    agent: SubagentRow,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onModelOverride: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BiuZRadius.medium)
            .background(BiuZBgCard)
            .border(1.dp, BiuZBorder, BiuZRadius.medium)
            .combinedClickable(
                onClick = onEdit,
                onLongClick = onDelete,
            )
            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.md)
            .testTag("26-row-${agent.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = agent.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BiuZText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 启停开关（仅 user scope；web supportsEnabledToggle 同口径——
            // built-in/workspace/plugin 行不给开关）。开关不即时落本地：失败走 reload 回弹。
            if (agent.source == "user") {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(start = BiuZSpacing.sm).size(16.dp),
                        strokeWidth = 2.dp,
                        color = BiuZAccent,
                    )
                } else {
                    androidx.compose.material3.Switch(
                        checked = agent.enabled,
                        onCheckedChange = { onToggle(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = BiuZAccent),
                        modifier = Modifier.padding(start = BiuZSpacing.sm).testTag("26-toggle-${agent.id}"),
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        if (agent.enabled) R.string.automation_enabled else R.string.automation_disabled,
                    ),
                    fontSize = 10.sp,
                    color = if (agent.enabled) BiuZAccent else BiuZText3,
                    modifier = Modifier.padding(start = BiuZSpacing.sm),
                )
            }
        }
        if (agent.description.isNotEmpty()) {
            Text(
                text = agent.description,
                fontSize = 12.sp,
                color = BiuZText3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (agent.toolsAll) {
                stringResource(R.string.subagents_tools_all)
            } else {
                stringResource(R.string.subagents_tools_count, agent.toolsCount)
            },
            fontSize = 10.sp,
            color = BiuZText3,
        )
        // 写面波次⑤：built-in/plugin 行内模型覆盖入口（web
        // SubagentModelOverrideControl :608-805 同位；user 行走编辑器）
        if (agent.source == "built-in" || agent.source == "plugin") {
            // 这是**动作入口**，不是说明文字：原实现是裸墨色文本（无底色/无箭头/同正文
            // 字重），真机读作「卡片底部一行没写完的标签」。改为胶囊钮（与面板内
            // PanelCapsuleButton、works 行按钮同款），并给按压反馈。
            val interaction = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }
            Text(
                text = stringResource(R.string.subagents_model_override) + " ›",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = BiuZText2,
                modifier = Modifier
                    .pressableScale(interaction)
                    .clip(RoundedCornerShape(999.dp))
                    .background(BiuZBgInput)
                    .clickable(interactionSource = interaction, indication = null) { onModelOverride() }
                    .padding(horizontal = BiuZSpacing.sm, vertical = BiuZSpacing.xs)
                    .testTag("26-model-override-${agent.id}"),
            )
        }
    }
}

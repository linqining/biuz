package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlinx.coroutines.CancellationException

/**
 * 子智能体（subagents）只读读面（屏 026）——上游 `ISubagentsService` 的 Android 对位。
 * 协议事实【实证·上游仓】：
 * - 频道 `subagents`（packages/shared/src/channels.ts:177-178）；读命令 `list`
 *   （packages/services/src/subagents/subagents.ts:15-41 + SubagentsSection.tsx:1404-1409）：
 *   入参 `{workspacePath, workspaceIdentity?, provider: "glm", mode:
 *   "allRuntimeScopes"|"settingsUserOnly"}`（provider = ZCODE_AGENT_PROVIDER，
 *   shared/src/zcode-agent-policy.ts:5）；
 * - 回执 `AgentsListResult`（shared/src/subagents-types.ts:88-94）：`agents`、
 *   `userAgents`、`pluginAgents`、`capability{userScopeAvailable, userScopeReason?}`、
 *   `diagnostics?`；
 * - AgentSummary 条目（subagents-types.ts:49-75）：id/name/description/systemPrompt/
 *   color?/modelSelection?/tools?/skills?/permissionMode?/maxTurns?/background?/path/
 *   scope("built-in"|"workspace"|"user")/source("built-in"|"user"|"plugin")/enabled/
 *   readOnly?/projectPath?/pluginId?/pluginName?。
 *
 * 写面（createAgent/updateAgent/deleteAgent/setEnabled/set*ModelOverride）gate 拦截
 * （ReadOnlyGate），移动端第一波只读清单（web 分组：user=已安装 / built-in=内置 /
 * plugin=插件 profile）。
 */

/**
 * 一条子代理行；`raw` = 回执原条目（写面波次③：updateAgent config 按 raw 回传
 * 保留 web 表单 preserve 族字段——tools/disallowedTools/skills/permissionMode/
 * maxTurns/background/mcpServers 原样，编辑器只改 name/description/systemPrompt/
 * injectAgentsMd 四键）；`path`/`projectPath` 供 deleteAgent filePath 与
 * updateAgent oldFilePath。
 */
data class SubagentRow(
    val id: String,
    val name: String,
    val description: String,
    /** "built-in" | "workspace" | "user"（缺席 null） */
    val scope: String?,
    /** "built-in" | "user" | "plugin"（缺席 null） */
    val source: String?,
    val enabled: Boolean,
    val readOnly: Boolean,
    /** tools 白名单数组（null = 未配置 = 全部工具，web tools.all 口径） */
    val tools: List<String>?,
    val color: String?,
    val pluginName: String?,
    /** md 文件路径（built-in/plugin 无文件面，delete/update 的 filePath） */
    val path: String? = null,
    val projectPath: String? = null,
    val systemPrompt: String? = null,
    val injectAgentsMd: Boolean? = null,
    val raw: JSONValue? = null,
) {
    val toolsCount: Int get() = tools?.size ?: 0
    val toolsAll: Boolean get() = tools == null
}

/** 子代理清单读回执。 */
data class SubagentsList(
    val agents: List<SubagentRow>,
    /** user scope 管理能力（上游 resolveCapabilities；desktop_only 时移动端如实提示） */
    val userScopeAvailable: Boolean,
    val userScopeReason: String?,
)

/** 读面失败分类：未连接是引导不是失败（U-7），与读失败分开叙事。 */
sealed class SubagentsError(message: String) : Exception(message) {
    data object NotConnected : SubagentsError("未连接桌面端")
    class Failed(detail: String) : SubagentsError(detail)
}

/** ZCode Agent provider（shared/src/zcode-agent-policy.ts:5，web 页面固定值）。 */
const val ZCODE_AGENT_PROVIDER = "glm"

/**
 * 子代理清单读（mode="allRuntimeScopes" 全量：built-in + user + workspace + plugin，
 * web 列表默认形态；SubagentsSection.tsx:1404-1409 同参）。
 */
suspend fun AppSession.loadSubagents(
    workspacePath: String?,
    workspaceIdentity: String?,
): SubagentsList {
    if (!isConnected) throw SubagentsError.NotConnected
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    fields["provider"] = JSONValue.Str(ZCODE_AGENT_PROVIDER)
    fields["mode"] = JSONValue.Str("allRuntimeScopes")
    return try {
        val json = connection.call(
            "subagents", "list", RPCValue.json(JSONValue.Obj(fields)),
        ).jsonValue
        // web 投影口径（SubagentsSection.tsx:115-119 projectSettingsSubagents）：
        // agents 先滤掉 plugin 源再拼 pluginAgents（实测桌面 3.17.2 的 agents 数组
        // 已含插件代理，直接拼接会得到重复 id → LazyColumn key 冲突闪退）；
        // 再按 id 兜底去重（同 id 保留首现），重复行不上屏。
        val combined = (json?.get("agents")?.arrayValue ?: emptyList()).mapNotNull { parseSubagent(it) }
            .filter { it.source != "plugin" } +
            (json?.get("pluginAgents")?.arrayValue ?: emptyList()).mapNotNull { parseSubagent(it) }
        val seenIds = HashSet<String>()
        val agents = combined.filter { seenIds.add(it.id) }
        val capability = json?.get("capability")?.objectValue
        SubagentsList(
            agents = agents,
            userScopeAvailable = capability?.get("userScopeAvailable")?.boolValue ?: false,
            userScopeReason = capability?.get("userScopeReason")?.stringValue,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw SubagentsError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

/**
 * 子智能体启停（写面波次②，gate 已放行；web hooksStore 同族 subagentsStore
 * `setEnabled` 同参）：`subagents.setEnabled {agentId, enabled}`——服务端写
 * agents-state.json 的 disabledAgentIds（subagentsService.ts:678-692）。
 * web 仅 user scope 可编辑 agent 出开关（supportsEnabledToggle :152-154）。
 * 返回 null=成功；非 null=可上屏错误文案。
 */
suspend fun AppSession.setSubagentEnabled(agentId: String, enabled: Boolean): String? {
    if (!isConnected) return "未连接桌面端"
    val fields = LinkedHashMap<String, JSONValue>()
    fields["agentId"] = JSONValue.Str(agentId)
    fields["enabled"] = JSONValue.Bool(enabled)
    return try {
        connection.call("subagents", "setEnabled", RPCValue.json(JSONValue.Obj(fields)))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "操作失败 · ${e.toString().take(160)}"
    }
}

/**
 * 子智能体新建（写面波次③，gate 已放行；web SubagentsSection.tsx:1517-1525 同参）：
 * `subagents.createAgent {config, provider, scope, workspacePath?, workspaceIdentity?}`——
 * config 仅携编辑器四键 + raw preserve 族（web 表单 onSave 同构：initial 的
 * disallowedTools/skills/permissionMode/maxTurns/background/mcpServers 原样保留）。
 * 返回 null=成功；非 null=可上屏错误文案。
 */
suspend fun AppSession.createSubagent(
    name: String,
    description: String,
    systemPrompt: String,
    workspacePath: String?,
    workspaceIdentity: String?,
): String? {
    if (!isConnected) return "未连接桌面端"
    val fields = LinkedHashMap<String, JSONValue>()
    fields["config"] = JSONValue.Obj(subagentConfig(name, description, systemPrompt, null))
    fields["provider"] = JSONValue.Str(ZCODE_AGENT_PROVIDER)
    fields["scope"] = JSONValue.Str("user")
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    return try {
        connection.call("subagents", "createAgent", RPCValue.json(JSONValue.Obj(fields)))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "新建失败 · ${e.toString().take(160)}"
    }
}

/**
 * 子智能体更新（web SubagentsSection.tsx:1507-1516 同参）：
 * `subagents.updateAgent {agentId, config, oldFilePath, provider, scope,
 * workspacePath?, workspaceIdentity?}`——config = 编辑器四键 + raw preserve 族。
 * 返回 null=成功；非 null=可上屏错误文案。
 */
suspend fun AppSession.updateSubagent(
    agent: SubagentRow,
    name: String,
    description: String,
    systemPrompt: String,
    workspacePath: String?,
    workspaceIdentity: String?,
): String? {
    if (!isConnected) return "未连接桌面端"
    val fields = LinkedHashMap<String, JSONValue>()
    fields["agentId"] = JSONValue.Str(agent.id)
    fields["config"] = JSONValue.Obj(subagentConfig(name, description, systemPrompt, agent))
    if (!agent.path.isNullOrEmpty()) fields["oldFilePath"] = JSONValue.Str(agent.path)
    fields["provider"] = JSONValue.Str(ZCODE_AGENT_PROVIDER)
    fields["scope"] = if (agent.scope == "workspace") JSONValue.Str("workspace") else JSONValue.Str("user")
    val effectiveWorkspace = agent.projectPath ?: workspacePath
    if (!effectiveWorkspace.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(effectiveWorkspace)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    return try {
        connection.call("subagents", "updateAgent", RPCValue.json(JSONValue.Obj(fields)))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "保存失败 · ${e.toString().take(160)}"
    }
}

/**
 * 子智能体删除（web SubagentsSection.tsx:1565 同参）：
 * `subagents.deleteAgent {agentId, filePath}`——filePath = agent.path。
 * 返回 null=成功；非 null=可上屏错误文案。
 */
suspend fun AppSession.deleteSubagent(agent: SubagentRow): String? {
    if (!isConnected) return "未连接桌面端"
    val fields = LinkedHashMap<String, JSONValue>()
    fields["agentId"] = JSONValue.Str(agent.id)
    fields["filePath"] = JSONValue.Str(agent.path ?: "")
    return try {
        connection.call("subagents", "deleteAgent", RPCValue.json(JSONValue.Obj(fields)))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "删除失败 · ${e.toString().take(160)}"
    }
}

/**
 * config 装配（web onSave 表单同构）：编辑器四键 + agent.raw preserve 族
 * （disallowedTools/skills/permissionMode/maxTurns/background/mcpServers 原样）。
 * color 取 raw 保留（编辑器不含色板）；injectAgentsMd 仅编辑器显式设置时携带。
 */
private fun subagentConfig(
    name: String,
    description: String,
    systemPrompt: String,
    agent: SubagentRow?,
): LinkedHashMap<String, JSONValue> {
    val config = LinkedHashMap<String, JSONValue>()
    config["name"] = JSONValue.Str(name)
    config["description"] = JSONValue.Str(description)
    config["systemPrompt"] = JSONValue.Str(systemPrompt)
    val raw = agent?.raw
    for (key in listOf("color", "tools", "disallowedTools", "skills", "permissionMode",
        "maxTurns", "background", "mcpServers")) {
        val v = raw?.get(key) ?: continue
        config[key] = v
    }
    val inject = agent?.injectAgentsMd
    if (inject != null) config["injectAgentsMd"] = JSONValue.Bool(inject)
    return config
}

/** 子代理条目解析（纯函数；id/name 缺席丢弃）。 */
fun parseSubagent(json: JSONValue): SubagentRow? {
    val d = json.objectValue ?: return null
    val id = d["id"]?.stringValue ?: return null
    val name = d["name"]?.stringValue ?: return null
    return SubagentRow(
        id = id,
        name = name,
        description = d["description"]?.stringValue ?: "",
        scope = d["scope"]?.stringValue,
        source = d["source"]?.stringValue,
        enabled = d["enabled"]?.boolValue ?: true,
        readOnly = d["readOnly"]?.boolValue ?: false,
        tools = d["tools"]?.arrayValue?.mapNotNull { it.stringValue },
        color = d["color"]?.stringValue,
        pluginName = d["pluginName"]?.stringValue,
        path = d["path"]?.stringValue,
        projectPath = d["projectPath"]?.stringValue,
        systemPrompt = d["systemPrompt"]?.stringValue,
        injectAgentsMd = d["injectAgentsMd"]?.boolValue,
        raw = json,
    )
}

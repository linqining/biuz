package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「能力清单」只读读面（记忆 / 技能 / MCP / 插件）——
 * iOS `Features/Settings/P2ExtrasViews.swift:1595-1720`（RemoteCapabilityListPage.load）的 Android 对位。
 *
 * 证据（与 iOS 注释同源，均为【实证·上游仓 / bundle 逆向】）：
 * - memory：频道 `memory`（非 zcode-agent；channels.ts:184 `Memory="memory"`），
 *   `listProjectMemories` 无参；回执 `{workspaces|memories}[]`，条目 `{id,label,updatedAt,files[]}`；
 * - skills：`zcode-agent.getSkillReferenceCatalog`，入参 workspace 信封；回执宽容
 *   `skills[] | catalog.skills[] | entries[]`，条目 `{name|title, description|summary, enabled?}`；
 * - mcp：`zcode-agent.listMcpServerStatuses`，入参 `{workspacePath}`；回执
 *   `servers[] | mcpServers[]`，条目 `{name|server|id, status|state|connectionState, command|transport}`；
 * - plugins：`zcode-agent.listPlugins`，入参 workspace 信封；回执 `plugins[] | items[]`，
 *   条目 `{name|pluginName, marketplace?, description?}`。
 *
 * 写面（启停/卸载/安装）一律不接：移动端只读，越界即失守（ReadOnlyGate 口径）。
 */
enum class CapabilityKind { memory, skills, mcp, plugins }

/** 能力清单行（`badge` 为右缀状态词，nil = 不显示）。 */
data class CapabilityRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String? = null,
)

/** 读面失败分类：未连接与读失败要分开叙事（U-7：未连接是引导，不是失败）。 */
sealed class CapabilityError(message: String) : Exception(message) {
    data object NotConnected : CapabilityError("未连接桌面端")
    class Failed(detail: String) : CapabilityError(detail)
}

/** 一次能力读（供 UI 直接调用；失败抛 [CapabilityError]，由 UI 分类上屏）。 */
suspend fun AppSession.loadCapability(kind: CapabilityKind): List<CapabilityRow> {
    if (!isConnected) throw CapabilityError.NotConnected
    val workspace = storeSurface.value.workspace
    return try {
        when (kind) {
            CapabilityKind.memory -> readMemories()
            CapabilityKind.skills -> readSkills(workspace)
            CapabilityKind.mcp -> readMcp(workspace?.path)
            CapabilityKind.plugins -> readPlugins(workspace)
        }
    } catch (e: CapabilityError) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw CapabilityError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

private suspend fun AppSession.readMemories(): List<CapabilityRow> {
    val result = connection.call("memory", "listProjectMemories", RPCValue.Undefined)
    val json = result.jsonValue
    val items = json?.arrayValue
        ?: json?.get("workspaces")?.arrayValue
        ?: json?.get("memories")?.arrayValue
        ?: emptyList()
    return items.mapNotNull { item ->
        val d = item.objectValue ?: return@mapNotNull null
        val label = d["label"]?.stringValue ?: d["id"]?.stringValue ?: return@mapNotNull null
        val fileCount = d["files"]?.arrayValue?.size ?: 0
        val updated = d["updatedAt"]?.intValue?.toLong()?.let { ms ->
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ms))
        }
        CapabilityRow(
            id = label,
            title = label,
            subtitle = if (updated != null) {
                "$fileCount 个记忆文件 · 更新于 $updated"
            } else {
                "$fileCount 个记忆文件"
            },
        )
    }
}

private suspend fun AppSession.readSkills(workspace: ServerWorkspaceInfo?): List<CapabilityRow> {
    val result = connection.call(
        "zcode-agent",
        "getSkillReferenceCatalog",
        RPCValue.json(JSONValue.Obj(workspaceScopeFields(workspace))),
    )
    val json = result.jsonValue
    val items = json?.get("skills")?.arrayValue
        ?: json?.get("catalog")?.objectValue?.get("skills")?.arrayValue
        ?: json?.get("entries")?.arrayValue
        ?: json?.arrayValue
        ?: emptyList()
    return items.mapNotNull { item ->
        val d = item.objectValue ?: return@mapNotNull null
        val name = d["name"]?.stringValue ?: d["title"]?.stringValue ?: return@mapNotNull null
        CapabilityRow(
            id = name,
            title = name,
            subtitle = d["description"]?.stringValue
                ?: d["summary"]?.stringValue
                ?: "技能目录条目",
            badge = d["enabled"]?.boolValue?.let { if (it) "已启用" else "已停用" },
        )
    }
}

private suspend fun AppSession.readMcp(workspacePath: String?): List<CapabilityRow> {
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    val result = connection.call(
        "zcode-agent",
        "listMcpServerStatuses",
        RPCValue.json(JSONValue.Obj(fields)),
    )
    val json = result.jsonValue
    // 【实证 2026-11-10 真机回执】真实形状是 map 而非数组：
    //   { statuses: { "<serverId>": { status, transport, toolCount, updatedAt, protocolEra } } }
    // 键形态如 "plugin:documents:documents"（插件内建 MCP）。数组形态保留作宽容分支。
    json?.get("statuses")?.objectValue?.let { statuses ->
        return statuses.map { (rawId, value) ->
            val d = value.objectValue
            CapabilityRow(
                id = rawId,
                title = displayMcpName(rawId),
                subtitle = listOfNotNull(
                    d?.get("transport")?.stringValue,
                    d?.get("toolCount")?.intValue?.let { "$it 个工具" },
                ).joinToString(" · ").ifEmpty { "MCP 服务器" },
                badge = mcpStatusLabel(d?.get("status")?.stringValue),
            )
        }
    }
    val items = json?.get("servers")?.arrayValue
        ?: json?.get("mcpServers")?.arrayValue
        ?: json?.arrayValue
        ?: emptyList()
    return items.mapNotNull { item ->
        val d = item.objectValue ?: return@mapNotNull null
        val name = d["name"]?.stringValue
            ?: d["server"]?.stringValue
            ?: d["id"]?.stringValue
            ?: return@mapNotNull null
        val rawStatus = d["status"]?.stringValue
            ?: d["state"]?.stringValue
            ?: d["connectionState"]?.stringValue
        CapabilityRow(
            id = name,
            title = name,
            subtitle = d["command"]?.stringValue
                ?: d["transport"]?.stringValue
                ?: "MCP 服务器",
            badge = mcpStatusLabel(rawStatus),
        )
    }
}

/** 桌面回执的状态词 → 中文后缀（未知词原样透传，不吞信息）。 */
private fun mcpStatusLabel(raw: String?): String? = raw?.let {
    when (it.lowercase()) {
        "connected", "ready", "ok" -> "已连接"
        // 连接中档：真机 MCP 页曾把 `connecting` 原词漏到界面上（其它行都是「已连接」），
        // 宽容回退只该兜**未知**词，已知词必须本地化
        "connecting", "starting", "initializing" -> "连接中"
        "disconnected" -> "未连接"
        "disabled", "stopped" -> "已停用"
        "failed", "error" -> "异常"
        else -> it
    }
}

/** `plugin:documents:documents` → `documents`；普通 id 原样。 */
private fun displayMcpName(rawId: String): String {
    val segments = rawId.split(':').filter { it.isNotBlank() }
    return when {
        segments.isEmpty() -> rawId
        segments.first() == "plugin" && segments.size >= 3 -> segments.last()
        else -> rawId
    }
}

private suspend fun AppSession.readPlugins(workspace: ServerWorkspaceInfo?): List<CapabilityRow> {
    val result = connection.call(
        "zcode-agent",
        "listPlugins",
        RPCValue.json(JSONValue.Obj(workspaceScopeFields(workspace))),
    )
    val json = result.jsonValue
    val items = json?.get("plugins")?.arrayValue
        ?: json?.get("items")?.arrayValue
        ?: json?.arrayValue
        ?: emptyList()
    return items.mapNotNull { item ->
        val d = item.objectValue ?: return@mapNotNull null
        val name = d["name"]?.stringValue
            ?: d["pluginName"]?.stringValue
            ?: d["id"]?.stringValue
            ?: return@mapNotNull null
        CapabilityRow(
            id = name,
            title = name,
            subtitle = d["description"]?.stringValue
                ?: d["marketplace"]?.stringValue
                ?: "插件",
            badge = d["version"]?.stringValue,
        )
    }
}

/** workspace 信封（web 恒携 workspacePath，identity 在场才带；能力页无会话上下文）。 */
private fun workspaceScopeFields(workspace: ServerWorkspaceInfo?): Map<String, JSONValue> {
    val fields = LinkedHashMap<String, JSONValue>()
    val path = workspace?.path
    if (!path.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(path)
    val identity = workspace?.workspaceIdentity
    if (!identity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(identity)
    return fields
}

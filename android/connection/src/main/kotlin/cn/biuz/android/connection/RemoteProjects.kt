package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlinx.coroutines.CancellationException

/**
 * 项目管理（projects）只读读面（屏 018）——上游 `IProjectService` 的 Android 对位
 * （第一波：清单 + 已移除清单；移除/恢复/删数据写面后续波次）。
 * 协议事实【实证·上游仓，两页取证报告 2026-11-10】：
 * - 频道 `project`（packages/shared/src/channels.ts:123；手机 allowlist
 *   windowHostAttachmentRpcPolicy.ts:39）；读命令 `readTree`
 *   （project-tree-reader.ts:19-25）：入参 `{includeDeleted: true,
 *   includeDeletedTasks: true, hostId?}`，**无 zod**（服务端透传 project/index.ts:182-195）；
 * - 回执 `ProjectTree`（project-storage/repository-contract.ts:35-41）：`projects:
 *   VirtualProject[]`、`tasks: ProjectTask[]`、`deletedTasks?`；
 * - `VirtualProject`（:6-23）：id/hostId/name/nameOverridden/roots[{rootPath,position,
 *   isPrimary}]/position/pinned/deleted/createdAt/updatedAt/primaryRootStatus?
 *   ("available"|"missing"|"unavailable")/directoryMatchPath?/legacySingleRoot?;
 * - 写面口径（后续波次）：project.deleteProject(id, hostId) 软删 / restoreProject(id,
 *   hostId) 恢复 / measureProjectData·deleteProjectData 限定 hostId="local"（= 桌面
 *   Host 那台机器，CONTRACT.md:33）。
 */

/** 一条项目行（只读投影）。 */
data class ProjectRow(
    val id: String,
    val name: String,
    val hostId: String,
    val pinned: Boolean,
    val deleted: Boolean,
    val position: Int,
    /** 根路径列表（roots[].rootPath，按 position 序） */
    val roots: List<String>,
    /** "available" | "missing" | "unavailable"（缺席 null） */
    val primaryRootStatus: String?,
    val nameOverridden: Boolean,
) {
    val removed: Boolean get() = deleted
}

/** 项目树读回执（任务计数按 projectId 客户端聚合——web 同源口径）。 */
data class ProjectsTree(
    val projects: List<ProjectRow>,
    /** projectId → 任务数（tasks[] 聚合；projectId=null 的散任务不计入） */
    val taskCountByProject: Map<String, Int>,
)

/** 读面失败分类：未连接是引导不是失败（U-7），与读失败分开叙事。 */
sealed class ProjectsError(message: String) : Exception(message) {
    data object NotConnected : ProjectsError("未连接桌面端")
    class Failed(detail: String) : ProjectsError(detail)
}

/**
 * 移除项目（软删；web useProjectManagement.ts:222-225 同参 `deleteProject(id, hostId)`）
 * ——多参服务按位置数组透传：arg=RPCValue.Arr（中继传输对 Arr 原样发，见
 * RelayChannelClient 约定注记；直连 ChannelClient 无包装亦同形），幂等软删容器、
 * 保留任务归属与 roots（contract.ts:47-48）。返回 null=成功；非 null=可上屏错误文案。
 */
suspend fun AppSession.deleteProject(id: String, hostId: String): String? {
    if (!isConnected) return "未连接桌面端"
    return try {
        connection.call(
            "project", "deleteProject",
            RPCValue.Arr(listOf(RPCValue.Str(id), RPCValue.Str(hostId))),
        )
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "移除失败 · ${e.toString().take(160)}"
    }
}

/**
 * 恢复项目（撤销移除；web useProjectManagement.ts:341-344 同参
 * `restoreProject(id, hostId)`，回执 VirtualProject）。返回 null=成功。
 */
suspend fun AppSession.restoreProject(id: String, hostId: String): String? {
    if (!isConnected) return "未连接桌面端"
    return try {
        connection.call(
            "project", "restoreProject",
            RPCValue.Arr(listOf(RPCValue.Str(id), RPCValue.Str(hostId))),
        )
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "恢复失败 · ${e.toString().take(160)}"
    }
}

/**
 * 项目树读（includeDeleted=true 连已移除一起拿——web ProjectManagement 的
 * 「已移除的项目」区同源；hostId 缺省不传，服务端按当前 host 处理）。
 */
suspend fun AppSession.loadProjectTree(): ProjectsTree {
    if (!isConnected) throw ProjectsError.NotConnected
    val fields = LinkedHashMap<String, JSONValue>()
    fields["includeDeleted"] = JSONValue.Bool(true)
    fields["includeDeletedTasks"] = JSONValue.Bool(true)
    return try {
        val json = connection.call(
            "project", "readTree", RPCValue.json(JSONValue.Obj(fields)),
        ).jsonValue
        // id 兜底去重（同 id 保留首现；LazyColumn key 冲突闪退防御）
        val seenIds = HashSet<String>()
        val projects = (json?.get("projects")?.arrayValue ?: emptyList())
            .mapNotNull { parseProjectRow(it) }
            .filter { seenIds.add(it.id) }
        val counts = HashMap<String, Int>()
        for (task in json?.get("tasks")?.arrayValue ?: emptyList()) {
            val projectId = task.objectValue?.get("projectId")?.stringValue ?: continue
            counts[projectId] = (counts[projectId] ?: 0) + 1
        }
        ProjectsTree(projects = projects, taskCountByProject = counts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ProjectsError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

/** 项目条目解析（纯函数；id/name 缺席丢弃）。 */
fun parseProjectRow(json: JSONValue): ProjectRow? {
    val d = json.objectValue ?: return null
    val id = d["id"]?.stringValue ?: return null
    val name = d["name"]?.stringValue ?: return null
    val roots = (d["roots"]?.arrayValue ?: emptyList())
        .mapNotNull { root -> root.objectValue?.get("rootPath")?.stringValue }
    return ProjectRow(
        id = id,
        name = name,
        hostId = d["hostId"]?.stringValue ?: "",
        pinned = d["pinned"]?.boolValue ?: false,
        deleted = d["deleted"]?.boolValue ?: false,
        position = d["position"]?.intValue ?: 0,
        roots = roots,
        primaryRootStatus = d["primaryRootStatus"]?.stringValue,
        nameOverridden = d["nameOverridden"]?.boolValue ?: false,
    )
}

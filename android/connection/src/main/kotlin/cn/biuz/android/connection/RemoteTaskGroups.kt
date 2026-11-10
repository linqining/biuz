package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlinx.coroutines.CancellationException

/**
 * task-groups 读面（P-013）—— iOS/Android 共用 RPC 面（频道 `task-groups`
 * 【实证·上游仓 channels.ts:124】）；`read()` 无参 → TaskGroupStructure。
 *
 * 协议形状【实证·上游仓 task-groups/contract.ts:5-33】：
 * - TaskGroup {id, title, color, createdAt, updatedAt}
 * - members[] {groupId, workspaceKey, taskId, sortOrder}（taskId ↔ sessions 行 id）
 * - order[] {type: "group"|"task", key, sortOrder}（展示序）
 *
 * 只读：save/ungroup/move 写面不接（移动端越界即失守）。
 */

data class TaskGroupEntry(
    val id: String,
    val title: String,
    val color: String?,
)

data class TaskGroupMember(
    val groupId: String,
    val taskId: String,
)

data class TaskGroupStructure(
    val groups: List<TaskGroupEntry>,
    val members: List<TaskGroupMember>,
)

/** 解析 TaskGroupStructure（宽容：group/title/groupId/taskId 缺席条目丢弃）。 */
fun parseTaskGroupStructure(json: JSONValue?): TaskGroupStructure? {
    if (json == null || json is JSONValue.Null) return null
    val groupRows = json["groups"]?.arrayValue ?: return null
    val memberRows = json["members"]?.arrayValue ?: emptyList()
    val groups = groupRows.mapNotNull { g ->
        val d = g.objectValue ?: return@mapNotNull null
        val id = d["id"]?.stringValue ?: return@mapNotNull null
        TaskGroupEntry(
            id = id,
            title = d["title"]?.stringValue ?: id,
            color = d["color"]?.stringValue,
        )
    }
    val members = memberRows.mapNotNull { m ->
        val d = m.objectValue ?: return@mapNotNull null
        val groupId = d["groupId"]?.stringValue ?: return@mapNotNull null
        val taskId = d["taskId"]?.stringValue ?: return@mapNotNull null
        TaskGroupMember(groupId = groupId, taskId = taskId)
    }
    return TaskGroupStructure(groups = groups, members = members)
}

/** task-groups.read（workspace 信封）。失败原样上抛（UI 分类上屏）。 */
suspend fun AppSession.readTaskGroups(
    workspacePath: String?,
    workspaceIdentity: String?,
): TaskGroupStructure {
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    val result = connection.call(
        "task-groups", "read", RPCValue.json(JSONValue.Obj(fields)),
    )
    return parseTaskGroupStructure(result.jsonValue)
        ?: throw IllegalStateException("task-groups.read 回执解析失败")
}

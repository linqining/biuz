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

data class TaskGroupOrderEntry(
    val type: String,
    val key: String,
)

data class TaskGroupStructure(
    val groups: List<TaskGroupEntry>,
    val members: List<TaskGroupMember>,
    /** G36 组排序条目（task-groups.read order：{type:"group"|"task", key, sortOrder}） */
    val order: List<TaskGroupOrderEntry> = emptyList(),
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
    val order = (json["order"]?.arrayValue ?: emptyList()).mapNotNull { node ->
        val d = node.objectValue ?: return@mapNotNull null
        val type = d["type"]?.stringValue ?: return@mapNotNull null
        val key = d["key"]?.stringValue ?: return@mapNotNull null
        TaskGroupOrderEntry(type = type, key = key)
    }
    return TaskGroupStructure(groups = groups, members = members, order = order)
}

/** G36：order 数组中目标组的前一个组节点 key（首组 = null 无上移语义）。 */
fun TaskGroupStructure.groupPredecessorKey(of: String): String? {
    val groupKeys = order.filter { it.type == "group" }.map { it.key }
    val index = groupKeys.indexOf(of)
    return if (index > 0) groupKeys[index - 1] else null
}

/**
 * G36 组色/改名写面（task-groups.save；2026-11-25 与 iOS updateTaskGroupColor 同构）。
 * input {id?, title?, color?}——color 七档词表 gray/red/orange/yellow/green/blue/purple
 * 【实证·上游仓 task-groups/contract.ts:4】；失败原样上抛。
 */
/**
 * G36 组置顶排序（task-groups.move 组节点形态；2026-11-25 wire 取证【实证·上游仓
 * task-groups/index.ts:31-34 nodeSchema + repository.ts:189-202 moveInside】：
 * node {type:"group", groupId} strict 合法；组只能在顶层重排——groupId 恒 null、
 * sourceGroupId 恒 null（parent(null)===null）；**无 anchor 时 index=0 = 置顶**。
 * 失败原样上抛（stale-source/invalid-group 词表 UI 分类）。
 */
/**
 * G36 组排序「上移一位」（move 组节点 + anchor 形态【实证·上游仓 repository.ts
 * :199-202】：destination.findIndex(anchor) → splice(index+0,0,node) 即插到 anchor
 * 之前；anchor = read `order` 数组中目标组的前一个 {type:"group",key}】）。
 */
suspend fun AppSession.moveTaskGroupUpOne(
    workspacePath: String?,
    workspaceIdentity: String?,
    groupId: String,
    beforeGroupKey: String,
) {
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    fields["node"] = JSONValue.Obj(
        linkedMapOf(
            "type" to JSONValue.Str("group"),
            "groupId" to JSONValue.Str(groupId),
        ),
    )
    fields["sourceGroupId"] = JSONValue.Null
    fields["groupId"] = JSONValue.Null
    fields["anchor"] = JSONValue.Obj(
        linkedMapOf(
            "node" to JSONValue.Obj(
                linkedMapOf(
                    "type" to JSONValue.Str("group"),
                    "key" to JSONValue.Str(beforeGroupKey),
                ),
            ),
            "side" to JSONValue.Str("before"),
        ),
    )
    connection.call("task-groups", "move", RPCValue.json(JSONValue.Obj(fields)))
}

suspend fun AppSession.pinTaskGroupToTop(
    workspacePath: String?,
    workspaceIdentity: String?,
    groupId: String,
) {
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    fields["node"] = JSONValue.Obj(
        linkedMapOf(
            "type" to JSONValue.Str("group"),
            "groupId" to JSONValue.Str(groupId),
        ),
    )
    fields["sourceGroupId"] = JSONValue.Null
    fields["groupId"] = JSONValue.Null
    connection.call("task-groups", "move", RPCValue.json(JSONValue.Obj(fields)))
}

suspend fun AppSession.saveTaskGroup(
    workspacePath: String?,
    workspaceIdentity: String?,
    id: String,
    color: String? = null,
    title: String? = null,
) {
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    fields["id"] = JSONValue.Str(id)
    if (!color.isNullOrEmpty()) fields["color"] = JSONValue.Str(color)
    if (!title.isNullOrEmpty()) fields["title"] = JSONValue.Str(title)
    connection.call("task-groups", "save", RPCValue.json(JSONValue.Obj(fields)))
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

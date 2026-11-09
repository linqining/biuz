package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlinx.coroutines.CancellationException

/**
 * 钩子（hooks）只读读面（屏 024）——上游 `IHooksService` 的 Android 对位。
 * 协议事实【实证·上游仓】：
 * - 频道 `hooks`（packages/shared/src/channels.ts:181-182）；读命令 `loadHooks`
 *   （packages/services/src/hooks/hooks.ts:10-21）：入参 `{workspacePath,
 *   workspaceIdentity?, scope?: "all"|"user"}`，回执 `{hooks[], hooksEnabled,
 *   workspaceHookSnapshot?, trustStoreCorrupt?}`；
 * - Hook 条目（packages/shared/src/hooks.ts:33-50，TS 接口）：id/event/matcher?/type/
 *   command/args?/async?/shell?/statusMessage?/timeout?/enabled/editable?/configuredState?/
 *   workspaceHook?{trustState?}/custom?/location?{source,scope,…}；
 * - event 七值枚举（hooks.ts:4-11）SessionStart/UserPromptSubmit/PreToolUse/
 *   PermissionRequest/PostToolUse/PostToolUseFailure/Stop；
 * - trust 词表（zcode-protocol-v4/workspace-hook-review.ts:9-18）not_applicable/
 *   pending_trust/trusted_persistent/blocked_untrusted/blocked_policy/revoked/stale_digest。
 *
 * 写面（saveHooks 整表覆盖 + grantWorkspaceHookTrust）gate 拦截（ReadOnlyGate
 * channelDirectWriteCommands），移动端第一波只读；来源三分类 = web HooksList 分组口径
 * （已安装 = zcode 可编辑 / 插件 = plugin source / 旧版 = 其余）。
 */

/**
 * 一条钩子行；`raw` = 回执原条目（写面波次②：saveHooks 整表覆盖按 raw 回传，
 * 只翻转目标行 enabled——桌面侧只落 editable!==false 且 source==="zcode" 的条目，
 * 其余原样不动，故 raw 保真是安全整表保存的前提）。
 */
data class HookRow(
    val id: String,
    val event: String,
    val matcher: String?,
    val type: String,
    val command: String,
    val args: List<String>,
    val enabled: Boolean,
    val editable: Boolean,
    /** location.source：zcode | agents | claude（缺席 null） */
    val source: String?,
    /** location.scope：user | project（缺席 null） */
    val scope: String?,
    /** workspaceHook.trustState 原词（缺席 null = 非 workspace 钩子） */
    val trustState: String?,
    val raw: JSONValue? = null,
) {
    /** web requiresWorkspaceHookTrust 口径（WorkspaceHookTrustNotice.tsx:6-7）。 */
    val needsTrust: Boolean get() = trustState != null && trustState != "trusted_persistent"

    /** web HooksList 三分组口径：installed（zcode 可编辑）/ plugin / legacy。 */
    val group: HookGroup
        get() = when {
            source == "zcode" && editable -> HookGroup.installed
            source == "plugin" -> HookGroup.plugin
            else -> HookGroup.legacy
        }
}

enum class HookGroup { installed, plugin, legacy }

/** 钩子清单读回执（hooksEnabled = 总开关）。 */
data class HooksSnapshot(
    val hooks: List<HookRow>,
    val hooksEnabled: Boolean,
)

/** 读面失败分类：未连接是引导不是失败（U-7），与读失败分开叙事。 */
sealed class HooksError(message: String) : Exception(message) {
    data object NotConnected : HooksError("未连接桌面端")
    class Failed(detail: String) : HooksError(detail)
}

/** 钩子清单读（scope="all" 全源；web HooksSection 默认形态）。 */
suspend fun AppSession.loadHooks(
    workspacePath: String?,
    workspaceIdentity: String?,
): HooksSnapshot {
    if (!isConnected) throw HooksError.NotConnected
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    fields["scope"] = JSONValue.Str("all")
    return try {
        val json = connection.call(
            "hooks", "loadHooks", RPCValue.json(JSONValue.Obj(fields)),
        ).jsonValue
        // id 兜底去重（同 id 保留首现）：同钩子可能多 scope 重复出现，
        // 重复行会让 LazyColumn key 冲突闪退（子代理页实测教训）
        val seenIds = HashSet<String>()
        HooksSnapshot(
            hooks = parseHookRows(json?.get("hooks")).filter { seenIds.add(it.id) },
            hooksEnabled = json?.get("hooksEnabled")?.boolValue ?: false,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw HooksError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

/**
 * 钩子整表保存（写面波次②，gate 已放行；web hooksStore.persistHooks 同参）：
 * `hooks.saveHooks {workspacePath, workspaceIdentity?, hooks: Hook[]}`——hooks 按
 * 读面 raw 整条回传（仅翻转目标行 enabled/删除目标行）；服务端过滤 editable!==false
 * 且 location.source==="zcode" 的 user/project 钩子后原子写 config.json
 * （hooksService.ts:309-333），非可编辑条目不受影响。写命令不做时间性自动重试
 * （防双写）。返回 null=成功；非 null=可上屏错误文案。
 */
suspend fun AppSession.saveHooks(
    workspacePath: String?,
    workspaceIdentity: String?,
    hooks: List<HookRow>,
): String? {
    if (!isConnected) return "未连接桌面端"
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    if (!workspaceIdentity.isNullOrEmpty()) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
    fields["hooks"] = JSONValue.Arr(hooks.mapNotNull { it.raw })
    return try {
        connection.call("hooks", "saveHooks", RPCValue.json(JSONValue.Obj(fields)))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "保存失败 · ${e.toString().take(160)}"
    }
}

/** 钩子条目解析（纯函数；id/event/type/command 缺席的行丢弃）。 */
fun parseHookRows(json: JSONValue?): List<HookRow> {
    val items = json?.arrayValue ?: return emptyList()
    return items.mapNotNull { item ->
        val d = item.objectValue ?: return@mapNotNull null
        val id = d["id"]?.stringValue ?: return@mapNotNull null
        val event = d["event"]?.stringValue ?: return@mapNotNull null
        val type = d["type"]?.stringValue ?: "command"
        HookRow(
            id = id,
            event = event,
            matcher = d["matcher"]?.stringValue,
            type = type,
            command = d["command"]?.stringValue ?: "",
            args = d["args"]?.arrayValue?.mapNotNull { it.stringValue } ?: emptyList(),
            enabled = d["enabled"]?.boolValue ?: false,
            editable = d["editable"]?.boolValue ?: true,
            source = d["location"]?.objectValue?.get("source")?.stringValue,
            scope = d["location"]?.objectValue?.get("scope")?.stringValue,
            trustState = d["workspaceHook"]?.objectValue?.get("trustState")?.stringValue,
            raw = item,
        )
    }
}

package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlinx.coroutines.CancellationException

/**
 * 定时任务（自动化）只读读面（屏 023）—— iOS `RemoteExtrasViews.swift:229-335`
 * （AutomationStore，G-016）的 Android 对位。
 *
 * 协议事实（与 iOS 注释同源，【实证·上游仓 zcodeAgentService.ts:4252/4341，
 * toProtocolAutomation:946-965】）：
 * - `zcode-agent.listAutomations` 入参 `{workspacePath?}`；回执 `automations[] | 顶层数组`，
 *   条目 `{automationId,title,cronExpr,enabled,lifecycleStatus,nextRunAt,lastRunAt,
 *   runCount,recurring,maxRuns,scheduleRule}`；
 * - `zcode-agent.listAutomationRuns` 入参 `{automationId}`；回执 `runs[] | 顶层数组`，
 *   条目 `{runId,startedAt,status,summary|lastError}`。
 * 只读：创建/启停/删除等写面不接（移动端越界即失守）。
 */

/** 一条定时任务（nextRunAt/lastRunAt = epoch 毫秒；无值 null）。 */
data class AutomationRecord(
    val id: String,
    val title: String,
    val cronExpr: String?,
    val enabled: Boolean,
    val nextRunAtMs: Long?,
    val lastRunAtMs: Long?,
    val runCount: Int,
    val lastRunFailed: Boolean?,
) {
    /** cronExpr 缺席显示 --（iOS scheduleText 同口径）。 */
    val scheduleText: String get() = cronExpr ?: "--"
}

/** 一次运行记录（status ∈ success|failed|running…；未知词原样透传）。 */
data class AutomationRunRecord(
    val id: String,
    val startedAtMs: Long?,
    val status: String,
    val summary: String?,
)

/** 读面失败分类：未连接是引导不是失败（U-7），与读失败分开叙事。 */
sealed class AutomationsError(message: String) : Exception(message) {
    data object NotConnected : AutomationsError("未连接桌面端")
    class Failed(detail: String) : AutomationsError(detail)
}

/** 定时任务清单读（失败抛 [AutomationsError]，由 UI 分类上屏）。 */
suspend fun AppSession.loadAutomations(workspacePath: String? = null): List<AutomationRecord> {
    if (!isConnected) throw AutomationsError.NotConnected
    val fields = LinkedHashMap<String, JSONValue>()
    if (!workspacePath.isNullOrEmpty()) fields["workspacePath"] = JSONValue.Str(workspacePath)
    return try {
        val json = connection.call(
            "zcode-agent", "listAutomations", RPCValue.json(JSONValue.Obj(fields)),
        ).jsonValue
        val items = json?.get("automations")?.arrayValue ?: json?.arrayValue ?: emptyList()
        items.mapNotNull { parseAutomation(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw AutomationsError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

/** 单条自动化的运行历史读（展开卡片时按需拉取）。 */
suspend fun AppSession.loadAutomationRuns(automationId: String): List<AutomationRunRecord> {
    if (!isConnected) throw AutomationsError.NotConnected
    val fields = LinkedHashMap<String, JSONValue>()
    fields["automationId"] = JSONValue.Str(automationId)
    return try {
        val json = connection.call(
            "zcode-agent", "listAutomationRuns", RPCValue.json(JSONValue.Obj(fields)),
        ).jsonValue
        parseAutomationRuns(json)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw AutomationsError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

/**
 * 单条自动化解析（纯函数；时间双形态口径 = iOS date(_:)：整数按毫秒、
 * 大于 1e9 的秒级浮点按秒乘 1000）。
 */
fun parseAutomation(json: JSONValue): AutomationRecord? {
    val d = json.objectValue ?: return null
    val id = d["automationId"]?.stringValue ?: return null
    return AutomationRecord(
        id = id,
        title = d["title"]?.stringValue ?: id,
        cronExpr = d["cronExpr"]?.stringValue,
        enabled = d["enabled"]?.boolValue ?: false,
        nextRunAtMs = epochMillis(d["nextRunAt"]),
        lastRunAtMs = epochMillis(d["lastRunAt"]),
        runCount = d["runCount"]?.intValue ?: 0,
        lastRunFailed = d["lastRunFailed"]?.boolValue,
    )
}

/** 运行历史解析（纯函数）：`runs[] | 顶层数组`，id 缺席按序号兜底（iOS 同口径）。 */
fun parseAutomationRuns(json: JSONValue?): List<AutomationRunRecord> {
    val items = json?.get("runs")?.arrayValue ?: json?.arrayValue ?: emptyList()
    return items.mapIndexedNotNull { index, item ->
        val d = item.objectValue ?: return@mapIndexedNotNull null
        AutomationRunRecord(
            id = d["runId"]?.stringValue ?: "run-$index",
            startedAtMs = epochMillis(d["startedAt"]),
            status = d["status"]?.stringValue ?: "unknown",
            summary = d["summary"]?.stringValue ?: d["lastError"]?.stringValue,
        )
    }
}

/**
 * 时间双形态宽容读取（iOS RemoteExtrasViews.swift:310-314 date(_:) 口径的 Kotlin 落法）。
 * 注意 Kotlin Int 是 32 位：毫秒时间戳（~1.7e12）在线缆上恒为 Double（JSONValue.Int
 * 是 Int32 值域，越界整数 JSON 编解码落 .double），故按 doubleValue 统一判读——
 * >1e12 视为已毫秒、>1e9 视为秒乘 1000；不用 intValue（大 Double 会钳位成
 * Int.MAX_VALUE 造成假毫秒）。
 */
private fun epochMillis(value: JSONValue?): Long? {
    if (value == null || value is JSONValue.Null) return null
    val numeric = value.doubleValue ?: return null
    return when {
        numeric > 1_000_000_000_000.0 -> numeric.toLong()
        numeric > 1_000_000_000.0 -> (numeric * 1000).toLong()
        else -> null
    }
}

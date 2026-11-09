package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 桌面设置同步读/写面（屏 028）—— iOS `Features/Settings/P2ExtrasViews.swift:2287-2769`
 * （DesktopSettingsPage，P3-11C）的 Android 对位；与 :store 的 RemoteUsageStatsStore
 * settingCall 构成同面孪生（该件为自测承载，UI 数据源恒为本扩展，工程注释 AppSession.kt:30）。
 *
 * 协议事实（与 iOS 注释同源）：
 * - 频道【实证·上游仓 packages/shared/src/channels.ts:106 `Setting: "setting"`】——
 *   「settingService」只是 web main.tsx 的 TS 服务对象名，从不是 wire 频道
 *   （AGENTS.md §6 裁决记录；gate 对 setting.update 放行 ReadOnlyGate.kt:152）；
 * - 读 = `setting.get` 无参；回执宽容三形态（顶层数组 / 包裹对象 / 整对象映射，
 *   [parseDesktopSettingEntries]），只收 bool/int/double/string 可渲染标量，
 *   对象/数组/空值跳过不臆造渲染；get 全失败 1.2s 退避整轮重试一次（中继瞬断同口径）；
 * - 写【实证·bundle 逆向 2026-10-06，审查报告 B-7】= `setting.update({设置名: 新值})`
 *   单键补丁对象（原 {key,value} 形态的键在 wire 层不存在，写面全坏）；
 *   写命令不做时间性自动重试，防双写（[updateDesktopSetting] allowRetry=false）。
 */
data class DesktopSettingsRow(
    val key: String,
    val value: JSONValue,
    /** readonly 行（web UI 语义：不可编辑；键未取证，宽容 readOnly/readonly 样键） */
    val readonly: Boolean,
) {
    val id: String get() = key
}

/** 读面失败分类：未连接是引导不是失败（U-7），与读失败分开叙事。 */
sealed class DesktopSettingsError(message: String) : Exception(message) {
    data object NotConnected : DesktopSettingsError("未连接桌面端")
    class Failed(detail: String) : DesktopSettingsError(detail)
}

/** 桌面设置 wire 频道（channels.ts:106 实证，单一候选）。 */
private const val SETTING_CHANNEL = "setting"

/** 回执可能的包裹键（宽容形态 B；整对象兜底见 [parseDesktopSettingEntries]）。 */
private val SETTING_WRAPPER_KEYS = listOf("settings", "values", "data", "config", "items", "entries")

/** get 读面重试退避（中继瞬断口径；写面永不走此处）。 */
private const val READ_RETRY_BACKOFF_MS = 1_200L

/**
 * 一次桌面设置读（供 UI 直接调用；失败抛 [DesktopSettingsError]，由 UI 分类上屏）。
 * get 首败 1.2s 退避整轮重试一次。
 */
suspend fun AppSession.loadDesktopSettings(): List<DesktopSettingsRow> {
    if (!isConnected) throw DesktopSettingsError.NotConnected
    try {
        return parseDesktopSettingEntries(settingRpc("get", emptyMap()))
    } catch (e: DesktopSettingsError) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw DesktopSettingsError.Failed(e.message ?: e::class.simpleName ?: "读取失败")
    }
}

/**
 * 一次设置写（`setting.update` 单键补丁对象；不做时间性自动重试防双写）。
 * 返回 null = 成功；非 null = 可上屏错误文案（写面禁止静默吞错）。
 */
suspend fun AppSession.updateDesktopSetting(key: String, newValue: JSONValue): String? {
    if (!isConnected) return "未连接桌面端"
    // 单键补丁对象：唯一键 = 设置名，值 = 新值（B-7 红线）
    val patch = linkedMapOf(key to newValue)
    return try {
        settingRpc("update", patch, allowRetry = false)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "修改失败 · ${e.toString().take(160)}"
    }
}

/**
 * setting 频道调用（单一候选；get 首败 1.2s 退避整轮重试一次，
 * [allowRetry]=false 供写命令关闭防双写）。
 */
private suspend fun AppSession.settingRpc(
    command: String,
    fields: Map<String, JSONValue>,
    allowRetry: Boolean = true,
): JSONValue {
    suspend fun runOnce(): JSONValue =
        connection.call(SETTING_CHANNEL, command, RPCValue.json(JSONValue.Obj(fields))).jsonValue
            ?: JSONValue.Null
    return try {
        runOnce()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (!allowRetry) throw e
        delay(READ_RETRY_BACKOFF_MS)
        runOnce()
    }
}

/**
 * 回执宽容解析（纯函数；单测锁定面）——与 :store parseSettingEntries / iOS parseEntries
 * 三件同口径：
 * - A 顶层数组 [{key|name|id, value…}]；
 * - B 包裹对象 {settings|values|data|config|items|entries: object 或 array}；
 * - C 整对象视为 {key: value} 映射（B 各包裹键都缺席时）。
 * 只收 bool/int/double/string 标量（对象/数组/空值跳过不臆造渲染）；键序 = 回执插入序
 * （iOS 侧排序为 key 字母序——Android 保留回执序以省一次拷贝，页面按序直呈）。
 */
fun parseDesktopSettingEntries(json: JSONValue?): List<DesktopSettingsRow> {
    val raw = ArrayList<Triple<String, JSONValue, Boolean>>() // (key, value, readonly)
    fun collectScalar(key: String, value: JSONValue) {
        when (value) {
            is JSONValue.Bool, is JSONValue.Int, is JSONValue.Double, is JSONValue.Str ->
                raw.add(Triple(key, value, false))
            else -> return // 非标量跳过不臆造
        }
    }
    fun entryOf(d: Map<String, JSONValue>) {
        val key = d["key"]?.stringValue ?: d["name"]?.stringValue ?: d["id"]?.stringValue
            ?: return
        val value = d["value"] ?: return
        val readonly = d["readonly"]?.boolValue
            ?: d["readOnly"]?.boolValue
            ?: (d["editable"]?.boolValue == false)
        when (value) {
            is JSONValue.Bool, is JSONValue.Int, is JSONValue.Double, is JSONValue.Str ->
                raw.add(Triple(key, value, readonly))
            else -> return
        }
    }
    val array = json?.arrayValue
    if (array != null) {
        // 形态 A：顶层数组
        for (item in array) item.objectValue?.let(::entryOf)
    } else {
        val dict = json?.objectValue
        if (dict != null) {
            var handled = false
            for (wrapper in SETTING_WRAPPER_KEYS) {
                val inner = dict[wrapper] ?: continue
                val innerArray = inner.arrayValue
                if (innerArray != null) {
                    for (item in innerArray) item.objectValue?.let(::entryOf)
                    handled = true
                } else {
                    val innerDict = inner.objectValue
                    if (innerDict != null) {
                        for ((k, v) in innerDict) collectScalar(k, v)
                        handled = true
                    }
                }
                if (handled) break
            }
            // 形态 C：整对象兜底
            if (!handled) for ((k, v) in dict) collectScalar(k, v)
        }
    }
    return raw.map { (key, value, readonly) -> DesktopSettingsRow(key, value, readonly) }
}

package cn.biuz.android.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 批4 模型选择器「最近使用」（iOS ModelPickerSheet 同构，2026-11-25）：
 * 键 `composer.modelRecents.v1`（iOS UserDefaults 同词面），值 = rowKey JSON 数组，
 * cap 3（最新在前，去重）。rowKey =「套餐|模型」/「其他|模型」复合键——面板打开时
 * 按 rowKey 渲染行、点选即 switchModel（复合表解析失败回落裸名，StoreProtocols
 * providerForRowKey 兜底序）。
 */
object ModelRecentsStore {
    private const val PREFS_NAME = "zcode.model.recents.v1"
    private const val KEY = "composer.modelRecents.v1"
    private const val CAP = 3

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): List<String> {
        val p = prefs ?: return emptyList()
        val raw = p.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 记一次使用（去重置顶；cap 3）。 */
    fun record(rowKey: String) {
        val p = prefs ?: return
        if (rowKey.isBlank()) return
        val current = load().filter { it != rowKey }
        val updated = (listOf(rowKey) + current).take(CAP)
        val array = JSONArray()
        updated.forEach { array.put(it) }
        p.edit().putString(KEY, array.toString()).apply()
    }
}

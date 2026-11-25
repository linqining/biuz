package cn.biuz.android.app.ui.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * G18 prompt 历史（iOS ChatViewModel.promptHistory 同构，2026-11-25）：全局
 * SharedPreferences `zcode.prompt.history.v1`（JSON 数组，最新在尾，cap 50；
 * 连续重复不入账——web 去重口径）。单向循环 chip 消费（cycle 返回上一条，走到最早
 * 回空串重新循环——iOS cyclePromptHistory 同语义）。
 */
object PromptHistoryStore {
    private const val PREFS_NAME = "zcode.prompt.history.v1"
    private const val KEY = "history"
    private const val CAP = 50

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): List<String> {
        val p = prefs ?: return emptyList()
        val raw = p.getString(KEY, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotEmpty() }
    }

    /** 记录一次发送（连续重复不入账）。 */
    fun record(text: String) {
        val p = prefs ?: return
        if (text.isBlank()) return
        val history = load()
        if (history.lastOrNull() == text) return
        val updated = (history + text).takeLast(CAP)
        p.edit().putString(KEY, updated.joinToString("\n")).apply()
    }

    /** 单向循环：pointer 走到头回空串重新开始（null = 无历史）。 */
    fun cycle(pointerIndex: Int?): String? {
        val history = load()
        if (history.isEmpty()) return null
        return when (pointerIndex) {
            null -> history.last()
            0 -> ""
            else -> history[pointerIndex - 1]
        }
    }
}

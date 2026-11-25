package cn.biuz.android.app.ui.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * G50 会话草稿持久化（web composerDraftStore.ts 同构，2026-11-25 与 iOS 同步）：
 * 键 `zcode.composer.draft.v1.<sessionId>`，值 = 草稿原文；正文空白即删键
 * （composerDraftStore.ts:389-393 同口径）；附件不入草稿（上游裁决 :9-10）。
 * SharedPreferences 单例（BiuzApplication.onCreate 装配 context）。
 */
object ComposerDraftStore {
    private const val PREFS_NAME = "zcode.composer.drafts.v1"
    private fun key(conversationId: String) = "zcode.composer.draft.v1.$conversationId"

    private var prefs: SharedPreferences? = null

    /** E2E 自动化进程禁用（与 iOS -ZCodeE2EResetState 同词面契约——防用例间草稿串扰） */
    @Volatile
    var persistenceEnabled: Boolean = true

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(conversationId: String): String =
        prefs?.getString(key(conversationId), null) ?: ""

    fun persist(conversationId: String, text: String) {
        val p = prefs ?: return
        if (!persistenceEnabled) return
        if (text.isBlank()) {
            p.edit().remove(key(conversationId)).apply()
        } else {
            p.edit().putString(key(conversationId), text).apply()
        }
    }
}

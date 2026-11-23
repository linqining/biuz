package cn.biuz.android.app.ui.chat

// 新建会话 → 会话页的一次性交接（iOS `NewConversationHandoffBox`
// NewConversationSheet.swift:1113-1130 / ChatViewModel.swift:803-818 同构）。
//
// **缺陷背景（2026-11-24 任务 4 验收受阻根因）**：
// `performCreateConversation` 只在 `firstInput.text` 非空时携 `modelSelection`
// （RemoteConversationStore.kt:3415-3458），因为空 firstInput 会让桌面开一个空轮。
// 于是「新建会话 sheet 留空描述、只选模型」这条最常见的路径上，**模型选择被整段丢弃**，
// 会话回落桌面默认模型（实测选 ccgithub/deepseek-v4.1-flash → 会话显示 GLM-5.4）。
// iOS 早在 2026-10-07（AGENTS v1.21）就用交接箱修过同一问题：sheet 把选择暂存，
// 会话页 attach 时取出、随**首条 sendText.modelSelection** 下发（上游 sendText schema
// 实证通道），发送成功清除、失败保留重试。Android 此前从未移植。
//
// 本文件只承载暂存语义（进程内、按 sessionId 键控、取走即清）；不承载业务判断。

import cn.biuz.android.store.NewSessionModelSelection

/** 交接内容：会话前模型/思考选择 + 待补发草稿（附件链留待需要时扩展）。 */
internal data class NewSessionHandoff(
    val draftText: String = "",
    val modelSelection: NewSessionModelSelection? = null,
)

/** 进程内交接箱（按 sessionId 键控，take 即清——一次性）。 */
internal object NewSessionHandoffBox {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, NewSessionHandoff>()

    fun deposit(handoff: NewSessionHandoff, forSessionId: String) {
        if (forSessionId.isEmpty()) return
        pending[forSessionId] = handoff
    }

    /** 取走即清（幂等：重复调用返回 null）。 */
    fun take(forSessionId: String): NewSessionHandoff? {
        if (forSessionId.isEmpty()) return null
        return pending.remove(forSessionId)
    }

    /** 测试/降级用：清空。 */
    fun clear() = pending.clear()
}

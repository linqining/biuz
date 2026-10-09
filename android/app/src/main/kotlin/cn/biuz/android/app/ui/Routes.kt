package cn.biuz.android.app.ui

/**
 * 导航路由表（占位骨架，M2 起随连接/会话链路逐步实装）。
 * chat 路由携带 sessionId（桌面端规范 id，形如 sess_*）。
 */
object Routes {
    const val CONNECT = "connect"
    const val SCAN = "scan"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"

    fun chat(sessionId: String): String = "chat/$sessionId"
}

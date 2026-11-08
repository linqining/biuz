package cn.biuz.android.app.ui

/**
 * 导航路由表（M2 起随连接/会话链路逐步实装；设置里程碑 +settings 族四路由）。
 * chat 路由携带 sessionId（桌面端规范 id，形如 sess_*）。
 */
object Routes {
    const val CONNECT = "connect"
    const val SCAN = "scan"
    const val LOGIN = "login"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    const val LANGUAGE = "settings/language"
    const val ABOUT = "settings/about"
    const val USAGE = "settings/usage"
    // 文件页（Diff 审查根 + 目录树 + 文件预览；预览携 path/name/size 查询参数，
    // path 含斜杠经 Uri.encode 过路由）
    const val FILES = "files"
    const val FILES_TREE = "filesTree"
    const val FILES_PREVIEW = "filesPreview?path={path}&name={name}&size={size}"

    fun chat(sessionId: String): String = "chat/$sessionId"

    fun filesPreview(path: String, name: String, size: Long?): String =
        "filesPreview?path=${android.net.Uri.encode(path)}" +
            "&name=${android.net.Uri.encode(name)}" +
            "&size=${size ?: -1L}"
}

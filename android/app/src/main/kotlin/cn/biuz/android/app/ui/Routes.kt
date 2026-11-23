package cn.biuz.android.app.ui

/**
 * 导航路由表（M2 起随连接/会话链路逐步实装；设置里程碑 +settings 族四路由）。
 * chat 路由携带 sessionId（桌面端规范 id，形如 sess_*）。
 */
object Routes {
    const val CONNECT = "connect"
    const val SCAN = "scan"
    const val LOGIN = "login"
    const val DEVICES = "devices"
    const val SHARE = "share/{code}"
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

    /** 能力清单（记忆 / 技能 / MCP / 插件）：{kind} ∈ memory|skills|mcp|plugins */
    const val CAPABILITY = "capability/{kind}"

    fun capability(kind: String): String = "capability/$kind"

    /** 015 外观设置 */
    const val APPEARANCE = "appearance"

    /** 019 归档管理 */
    const val ARCHIVES = "archives"

    /** 028 桌面设置同步（setting.get 读 / setting.update 单键补丁写） */
    const val DESKTOP_SETTINGS = "settings/desktop"

    /** 023 自动化（定时任务 + 运行历史；zcode-agent.listAutomations 只读） */
    const val AUTOMATIONS = "settings/automations"

    /** 027 个性化（web LocalOnlyPersonalizationHint 同口径，零服务调用指引页） */
    const val PERSONALIZATION = "settings/personalization"

    /** 024 钩子（hooks.loadHooks 只读清单） */
    const val HOOKS = "settings/hooks"

    /** 026 子智能体（subagents.list 只读清单） */
    const val SUBAGENTS = "settings/subagents"

    /** 018 项目管理（project.readTree 只读清单第一波） */
    const val PROJECTS = "settings/projects"

    /** 执行输出（模型轨迹） */
    const val TASK_OUTPUT = "taskOutput/{sessionId}"

    fun taskOutput(sessionId: String): String = "taskOutput/$sessionId"

    fun share(code: String): String = "share/" + android.net.Uri.encode(code)

    fun filesPreview(path: String, name: String, size: Long?): String =
        "filesPreview?path=${android.net.Uri.encode(path)}" +
            "&name=${android.net.Uri.encode(name)}" +
            "&size=${size ?: -1L}"
}

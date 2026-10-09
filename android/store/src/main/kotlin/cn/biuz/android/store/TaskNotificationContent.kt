package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Services/Notifications/NotificationService.swift
// 的 TaskNotificationContent 纯函数段（:14-63）。任务状态/待审批本地通知（P1-7：
// 设置页「通知」假开关真实化）。
//
// 数据源：RemoteTaskStore.handleTaskEvent 的状态迁移（→done/failed/waiting 三类）。
// 口径对齐桌面 desktopNotifications：正文带上下文摘要（变更规模或待审批提示），
// (status,taskId,requestId) 去重键；内容构造为纯函数（无系统通知中心依赖，可单测）。
// 【挂账】通知投递与点击路由（Android NotificationManager/通道注册/权限）属 UI/装配层，
// 本批只落内容构建纯逻辑（G-016：时效性/去重键/路由键）；系统推送接入挂账。
// iOS 快捷动作仅「打开」单按钮（审批在应用内完成）——Android 对位同口径。

import cn.biuz.android.protocol.TaskStatus

/**
 * 通知内容纯函数构造结果（NotificationService.swift TaskNotificationContent :14-31）。
 *
 * - [dedupKey]："(status,taskId,requestId)" 去重键（调用方 3 秒窗口去重的账本键）；
 * - [identifier]：系统通知 id（同 id 系统级替换去重——Android notify(tag,id) 的 id 源）；
 * - [timeSensitive]：G-016 审批/提问类时效性通知（waiting；桌面
 *   desktopNotifications.ts:32-44 强提醒同口径）→ Android 通知 channel
 *   IMPORTANCE_HIGH / category 时间敏感的判定位；
 * - [requestID]：挂起交互 requestId（同 id 不重复提醒；null = 非交互类状态通知）。
 */
data class TaskNotificationContent(
    val dedupKey: String,
    val identifier: String,
    val title: String,
    val body: String,
    val taskID: String,
    val timeSensitive: Boolean,
    val requestID: String?,
) {
    companion object {

        /** 摘要截断上界（80 字符，省略号收尾）（:50） */
        const val SUMMARY_LIMIT = 80

        /**
         * 三类状态映射；其余状态（running）不产生通知（返回 null）。（:33-53）
         * - done     → 「任务完成」+ 变更规模摘要
         * - failed   → 「任务失败」+ 最后错误摘要
         * - waiting  → 「等待你的批准」+ 待审批提示
         * - summary 超过 80 字符截断（省略号收尾）
         */
        fun make(
            taskID: String,
            taskTitle: String,
            status: TaskStatus,
            changeSummary: String?,
            requestID: String? = null,
        ): TaskNotificationContent? {
            val titleText: String
            val bodyPrefix: String
            when (status) {
                TaskStatus.done -> {
                    titleText = "任务完成"
                    bodyPrefix = "$taskTitle 已完成"
                }
                TaskStatus.failed -> {
                    titleText = "任务失败"
                    bodyPrefix = "$taskTitle 执行失败"
                }
                TaskStatus.waiting -> {
                    titleText = "等待你的批准"
                    bodyPrefix = "$taskTitle 有操作待审批"
                }
                TaskStatus.running -> return null
            }
            var detail = changeSummary?.trim() ?: ""
            if (detail.length > SUMMARY_LIMIT) {
                detail = detail.take(SUMMARY_LIMIT) + "…"
            }
            val bodyText = if (detail.isEmpty()) bodyPrefix else "$bodyPrefix：$detail"
            val statusRaw = status.name // iOS rawValue 对应物（Kotlin enum name 即 wire 词）
            val requestKey = requestID ?: "-"
            return TaskNotificationContent(
                dedupKey = "($statusRaw,$taskID,$requestKey)",
                identifier = "task-$taskID-$statusRaw-${requestID ?: "na"}",
                title = titleText,
                body = bodyText,
                taskID = taskID,
                timeSensitive = status == TaskStatus.waiting,
                requestID = requestID,
            )
        }
    }
}

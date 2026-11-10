package cn.biuz.android.app.ui.screen

/**
 * P-017 连接失败 12 种文案（web `webRemoteControlScreenCopy.ts:16-141` zh-CN 全量
 * 直译；miniprogram `connection-screen-model.js` FAILURE 同源镜像）。
 *
 * ConnectError 类型映射：
 * - Http 401 → invalid-mobile-connection（校验失败）
 * - Timeout → desktop-bootstrap-timeout（响应超时）
 * - ProtocolVersion → unexpected-error（未知异常——协议不匹配属非预期）
 * - EmptyWorkspaces → workspace-closed（工作区关闭）
 * - HandshakeFailed → unexpected-error（未知异常）
 * - Transport → relay-unavailable（中转异常）
 * - 兜底 → unexpected-error
 */
data class ConnectFailureCopy(
    val reason: String,
    val tone: String,
    val badge: String,
    val title: String,
    val description: String,
    val detailLabel: String,
    val nextSteps: List<String>,
    val action: String,
)

private val FAILURE_COPY = mapOf(
    "device-not-owned" to ConnectFailureCopy(
        "device-not-owned", "warning", "账号不匹配", "该设备不属于当前账号",
        "请使用与电脑端相同的账号登录。", "失败原因",
        listOf("确认电脑端当前登录的账号。", "切换为相同账号后重新连接。"), "重新连接",
    ),
    "session-not-found" to ConnectFailureCopy(
        "session-not-found", "warning", "链接不可用", "访问链接已失效",
        "这次 Web 远程控制链接已经不存在，通常是桌面端重新生成了二维码。", "Relay 返回",
        listOf("回到桌面端重新打开 Web 远程控制。", "用手机扫描最新二维码。"), "重新加载",
    ),
    "session-expired" to ConnectFailureCopy(
        "session-expired", "warning", "会话结束", "本次远程控制已结束",
        "当前远程控制会话已经过期或被桌面端关闭。", "结束原因",
        listOf("在桌面端重新开启 Web 远程控制。", "用新的链接进入当前工作区。"), "重新加载",
    ),
    "session-conflict" to ConnectFailureCopy(
        "session-conflict", "warning", "设备接管", "已被其他设备接管",
        "另一台远程控制设备已经接入，同一时间只能保留一个手机控制端。", "Relay 返回",
        listOf("继续使用新接入的设备。", "如果要用本设备控制，请重新扫描桌面端二维码。"), "重新连接",
    ),
    "workspace-closed" to ConnectFailureCopy(
        "workspace-closed", "neutral", "工作区关闭", "当前工作区已关闭",
        "桌面端已经关闭了共享工作区，手机端无法继续访问这个 workspace。", "桌面端返回",
        listOf("在桌面端重新打开目标工作区。", "重新发起 Web 远程控制。"), "重新加载",
    ),
    "desktop-disconnected" to ConnectFailureCopy(
        "desktop-disconnected", "danger", "电脑端离线", "桌面端已离线",
        "电脑端已经断开连接，当前手机页面不能继续控制桌面工作区。", "Relay 返回",
        listOf("确认电脑端 ZCode 仍在运行并联网。", "在电脑端重新开启 Web 远程控制后再连接。"), "重新连接",
    ),
    "invalid-mobile-connection" to ConnectFailureCopy(
        "invalid-mobile-connection", "warning", "校验失败", "手机连接已失效",
        "当前页面的二维码参数无效，请扫描桌面端的最新二维码。", "失败原因",
        listOf("不要复用旧截图或旧链接。", "回到桌面端扫描最新二维码。"), "重新连接",
    ),
    "desktop-bootstrap-timeout" to ConnectFailureCopy(
        "desktop-bootstrap-timeout", "warning", "响应超时", "桌面端响应超时",
        "手机端已经连上 relay，但桌面端没有及时返回工作区数据。", "超时详情",
        listOf("确认桌面端没有休眠或卡在确认弹窗。", "保持电脑和手机网络可用后重试。"), "重试",
    ),
    "connection-recovery-timeout" to ConnectFailureCopy(
        "connection-recovery-timeout", "warning", "恢复超时", "连接恢复超时",
        "手机端连接没有及时恢复，当前页面暂时无法继续同步远程控制。", "恢复详情",
        listOf("保持手机网络可用后重试。", "如果仍无法恢复，再回到桌面端重新开启 Web 远程控制。"), "重试",
    ),
    "relay-unavailable" to ConnectFailureCopy(
        "relay-unavailable", "danger", "中转异常", "无法连接中转服务",
        "手机端无法稳定连接 Web 远程控制中转服务。", "连接详情",
        listOf("检查手机网络是否可访问外网。", "如果电脑端仍在线，可以稍后刷新重试。"), "重试",
    ),
    "unsupported-action" to ConnectFailureCopy(
        "unsupported-action", "neutral", "暂不支持", "当前动作暂不支持",
        "Web 远程控制只支持访问桌面端已经打开的工作区。", "限制说明",
        listOf("先在桌面端打开目标工作区。", "再从手机端选择这个工作区。"), "重新加载",
    ),
    "unexpected-error" to ConnectFailureCopy(
        "unexpected-error", "danger", "未知异常", "Web 远程控制失败",
        "打开远程控制页面时发生了未预期错误。", "错误详情",
        listOf("刷新页面再试一次。", "如果仍然失败，请回到桌面端重新生成二维码。"), "重试",
    ),
)

private const val DEFAULT_REASON = "unexpected-error"

/** ConnectError → web 12 种文案 reason 映射。 */
fun failureReasonForConnectError(error: cn.biuz.android.connection.ConnectError): String = when (error) {
    is cn.biuz.android.connection.ConnectError.Http ->
        if (error.status == 401) "invalid-mobile-connection" else DEFAULT_REASON
    is cn.biuz.android.connection.ConnectError.Timeout -> "desktop-bootstrap-timeout"
    is cn.biuz.android.connection.ConnectError.ProtocolVersion -> DEFAULT_REASON
    is cn.biuz.android.connection.ConnectError.EmptyWorkspaces -> "workspace-closed"
    is cn.biuz.android.connection.ConnectError.HandshakeFailed -> DEFAULT_REASON
    is cn.biuz.android.connection.ConnectError.Transport -> "relay-unavailable"
}

fun failureCopyFor(reason: String): ConnectFailureCopy =
    FAILURE_COPY[reason] ?: FAILURE_COPY[DEFAULT_REASON]!!

fun failureCopyForConnectError(error: cn.biuz.android.connection.ConnectError): ConnectFailureCopy =
    failureCopyFor(failureReasonForConnectError(error))

package cn.biuz.android.connection

/**
 * 桌面版本变化守卫（对齐 web `webRemoteControlVersion.ts:26-52`）。
 *
 * web 的判据是：**链接携带的 `app_version`** ≠ **bootstrap 回报的 desktopAppVersion** 时，
 * 弹出阻断式提示（「桌面版本已更新 / 任务操作已暂停，请重新加载以继续」），确认后带着新版本重连。
 *
 * 移动端等价：链接里的 `desktopAppVersion`（配对链接/设备目录）对比 `bootstrap-response` 的
 * 实际版本。任一为空则不判（旧桌面不带版本 / 手填链接）——宁可放过也不误拦。
 */
object VersionGuard {

    /** 返回需要提示的「桌面当前版本」；不需要提示返回 null。 */
    fun mismatch(linkVersion: String?, desktopVersion: String?): String? {
        val actual = desktopVersion?.trim().orEmpty()
        val declared = linkVersion?.trim().orEmpty()
        if (actual.isEmpty() || declared.isEmpty()) return null
        return if (declared != actual) actual else null
    }
}

package cn.biuz.android.connection

// iOS AppSession（ios/ZCodeMobileApp/Sources/Services/Remote/AppSession.swift）的静态面
// 先行落位。AppSession 是装配层（连接生命周期/Store 装配/演示开关），实例态随装配里程碑
// 并入；本文件只承载与连接模型同批验收的纯静态函数（M1 挂账回补的验收面）。

object AppSession {

    /**
     * `-ZCodeRelayLink <url>` 取值（相邻参数；纯函数不触 UI 态）
     * （AppSession.swift:387-392 逐字直译）。
     * 用法：`-ZCodeRelayLink <配对链接>` 解析中继配对链接并直接发起云中继连接
     * （无 UI 驱动的真机验证，模式同 -ZCodeOpenConnectFlow；链接失效保持演示态）。
     */
    fun relayLinkArgument(from: List<String>): String? {
        val index = from.indexOf("-ZCodeRelayLink")
        if (index < 0 || index + 1 >= from.size) return null
        val value = from[index + 1]
        return if (value.startsWith("-")) null else value
    }
}

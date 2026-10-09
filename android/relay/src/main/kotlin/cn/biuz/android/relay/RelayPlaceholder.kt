package cn.biuz.android.relay

/**
 * 占位类型：relay 模块编译锚点（中继传输，OkHttp WS）。
 * 实装内容：iOS Services/Relay 四件（RelayFrameCodec/RelayTransport/RelayChannelClient/
 * RelayLink），URLSession→OkHttp 替换（docs/Android开发交接.md §2.1/§8.1）。M6 里程碑。
 */
object RelayPlaceholder {
    const val MODULE: String = "relay"
}

package cn.biuz.android.protocol

/**
 * M1 占位类型：protocol 模块的编译锚点。
 *
 * 实装内容为 iOS 纯协议逻辑的 Kotlin 直译（docs/Android开发交接.md §2.1）：
 * V4Wire / RPCSerialization / ChannelClient / ProtocolSchema / CommandSchemas /
 * ConversationCommandFactory / RelayFrameCodec / ReadOnlyGate / Models 等。
 */
object Bootstrap {
    /** 模块标识，供占位单测与后续装配日志使用。 */
    const val MODULE: String = "protocol"
}

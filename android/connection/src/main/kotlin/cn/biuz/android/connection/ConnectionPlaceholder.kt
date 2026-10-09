package cn.biuz.android.connection

/**
 * 占位类型：connection 模块编译锚点。
 * 实装内容：Connection.call 唯一出口 + ReadOnlyGate + AckActivationBarrier + AppSession 装配
 * （docs/Android开发交接.md §8.1；红线见 §0）。架构核心必须与 iOS ZCodeServerConnection 同构。
 */
object ConnectionPlaceholder {
    const val MODULE: String = "connection"
}

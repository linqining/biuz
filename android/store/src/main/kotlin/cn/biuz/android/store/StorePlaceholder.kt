package cn.biuz.android.store

/**
 * 占位类型：store 模块编译锚点。
 * 实装内容：三大 Store（会话/文件/任务）+ 协议面 interface + 空实现（未连接态如实失败，
 * 不装配 Mock，docs/Android开发交接.md §8.1；红线 §0-4）。
 */
object StorePlaceholder {
    const val MODULE: String = "store"
}

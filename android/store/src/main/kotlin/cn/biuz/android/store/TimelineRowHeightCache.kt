package cn.biuz.android.store

/**
 * G9 行高缓存（上游 timelineRowHeightCache.ts 逐字语义移植，2026-11-25 与 iOS 同步）。
 * 虚拟化容器卸载/重挂丢测量——按稳定键（messageId）持久缓存保「行重挂不丢测高」。
 * LRU 4000 / 默认估高 72px / 插入序淘汰 / 会话切换 clear——上游 :13-58 同语义。
 */
class TimelineRowHeightCache {
    companion object {
        const val DEFAULT_ROW_HEIGHT_ESTIMATE_PX = 72
        const val MAX_ROW_HEIGHT_CACHE_ENTRIES = 4000
    }

    private val sizes = LinkedHashMap<String, Int>() // 迭代序 = 插入序（最新在尾）

    val size: Int get() = sizes.size

    /** 记录一次真实测量。重复写入刷新淘汰顺序（活跃行不被淘汰）。 */
    fun set(key: String, heightPx: Int) {
        if (heightPx <= 0) return
        sizes.remove(key)
        sizes[key] = heightPx
        while (sizes.size > MAX_ROW_HEIGHT_CACHE_ENTRIES) {
            val oldest = sizes.keys.firstOrNull() ?: break
            sizes.remove(oldest)
        }
    }

    fun get(key: String): Int? = sizes[key]

    /** estimateSize 入口：有测量用测量，无测量回落估计值。 */
    fun estimate(key: String?, fallbackPx: Int = DEFAULT_ROW_HEIGHT_ESTIMATE_PX): Int =
        key?.let { sizes[it] } ?: fallbackPx

    /** 会话切换时整体重置。 */
    fun clear() = sizes.clear()
}

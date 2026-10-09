package cn.biuz.android.protocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 直译自 ios/ZCodeMobileApp/Sources/Stores/Remote/AckActivationBarrier.swift（77 行）。
// 上游【实证·上游仓】：ui/src/v4/ackActivationBarrier.ts:34-64 +
// zcodeAgentConnectionScope.ts:415-478（注释逐条保留 iOS 原文指位）。
// 并发模型适配（docs/Android开发交接.md §2.2）：Swift actor → Mutex 临界区；
// 本类方法体内无挂起调用，临界区即纯内存操作。其余与 Swift 原文逐行对应。

// MARK: - ACK 激活屏障（官方 ackActivationBarrier 的 Swift 镜像）
//
// 【实证·上游仓 ui/src/v4/ackActivationBarrier.ts:34-64 +
// zcodeAgentConnectionScope.ts:415-478】上游明文承认「CLI notification 可能先于
// RPC subscribe response 抵达 host」，host 用 pending ownership staging 缓冲、
// ACK 后按原序释放；参考客户端同构做屏障，且「subscriptionId 必须先入 store 再
// activate 释放暂存帧」（conversationProjectionStore.ts:460-463）。
//
// 语义（订阅回执竞态的确定性收口）：
// - begin(key)：订阅发起前标记 in-flight；此后到达该 key 的帧进暂存区（不消费）
// - finish(key)：订阅成功后取出暂存帧**按原序**回放（消费方须已记录 subId）
// - abort(key)：订阅失败/放弃——暂存帧整体丢弃（未取得订阅的帧不可信）
// - 暂存上限 stagingLimit：超限整体作废并报 overflow（上游
//   initialFrameStagingOverflow 同语义——宁可全量 resync 重建，不消费无界积压）

/**
 * ACK 激活屏障（AckActivationBarrier.swift:17-77，Swift actor → Kotlin Mutex）。
 * 持有方：连接层（ZCodeServerConnection 对应物）单实例持有。
 */
class AckActivationBarrier {

    /** Swift `enum FinishOutcome`（:19-23，带关联值 → sealed class）。 */
    sealed class FinishOutcome {
        /** 原序回放（Swift 数组值语义 → Kotlin 快照拷贝，摘除后持有方独占）。 */
        data class Frames(val frames: List<V4TopicFrame>) : FinishOutcome()

        /** 暂存超限：消费方走全量 resync 重建。 */
        data object Overflow : FinishOutcome()

        /** 无暂存帧。 */
        data object Empty : FinishOutcome()
    }

    companion object {
        /**
         * 暂存上限（上游 host staging 量级；到达即 overflow——帧积压意味着回执异常
         * 迟到，此时增量连续性不可保证，全量快照重建是唯一安全口径）（:25-27）。
         *
         * 【上游核实 2026-10-09】上游 host 暂存上限实为 1024 帧/32MB
         * （zcodeAgentConnectionScope.ts:175-176 MAX_PENDING_OWNERSHIP_FRAMES/BYTES），
         * renderer 侧同为 1024（ackActivationBarrier.ts:13 MAX_STAGED_FRAMES）——iOS
         * 注释「上游 host staging 量级」的 256 是客户端保守取值，非上游原值；overflow
         * 语义（整批清空 + fault.subscription.initialFrameStagingOverflow 显式失败 →
         * 消费方 forceSnapshot 全量重建，scope.ts:454-458 +
         * conversationProjectionStore.ts:483-490）与上游逐字一致。本地缓冲量非线缆
         * 协议事实，直译保留 256。
         */
        const val stagingLimit = 256
    }

    private val inFlightKeys: MutableSet<String> = HashSet()
    private val staged: MutableMap<String, MutableList<V4TopicFrame>> = HashMap()
    private val overflowed: MutableSet<String> = HashSet()

    /** Swift actor 串行化的 Kotlin 等价物（锁内无挂起调用）。 */
    private val mutex = Mutex()

    /** 订阅发起前调用（必须先于 subscribe RPC）（:34-38）。 */
    suspend fun begin(key: String) {
        mutex.withLock {
            inFlightKeys.add(key)
            staged[key] = ArrayList()
            overflowed.remove(key)
        }
    }

    suspend fun isInFlight(key: String): Boolean = mutex.withLock { key in inFlightKeys }

    /**
     * 回执未到时暂存一帧；false = 暂存区已满（本帧起整体作废，finish 报 overflow）
     * （:46-56）。Swift `staged[key, default: []].append` → getOrPut。
     */
    suspend fun stage(key: String, frame: V4TopicFrame): Boolean {
        return mutex.withLock {
            if (key !in inFlightKeys) return@withLock false
            if (key in overflowed) return@withLock false
            val current = staged[key]
            if ((current?.size ?: 0) >= stagingLimit) {
                overflowed.add(key)
                staged.remove(key)
                return@withLock false
            }
            (current ?: ArrayList<V4TopicFrame>().also { staged[key] = it }).add(frame)
            true
        }
    }

    /**
     * 订阅成功：取出全部暂存帧（原序）并清除屏障态（:59-69）。
     * Swift defer 清理在 return 值算定后执行——同一临界区内「先算结果、后清理」等价。
     */
    suspend fun finish(key: String): FinishOutcome {
        return mutex.withLock {
            val outcome: FinishOutcome = when {
                key !in inFlightKeys -> FinishOutcome.Empty
                key in overflowed -> FinishOutcome.Overflow
                else -> {
                    val frames = staged[key]
                    if (frames.isNullOrEmpty()) FinishOutcome.Empty
                    else FinishOutcome.Frames(frames.toList()) // Swift 数组值语义：摘除即分离
                }
            }
            inFlightKeys.remove(key)
            staged.remove(key)
            overflowed.remove(key)
            outcome
        }
    }

    /** 订阅失败/放弃：丢弃暂存帧（:72-76）。 */
    suspend fun abort(key: String) {
        mutex.withLock {
            inFlightKeys.remove(key)
            staged.remove(key)
            overflowed.remove(key)
        }
    }
}

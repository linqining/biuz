package cn.biuz.android.protocol

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 直译自 ios/UnitTests/AckActivationBarrierTests.swift（6 用例，逐用例断言对应）。
// 被测：AckActivationBarrier.kt（Stores/Remote/AckActivationBarrier.swift 的 Kotlin
// 镜像，Swift actor → Mutex；上游【实证·上游仓】packages/ui/src/v4/ackActivationBarrier.ts +
// packages/services/src/zcode-agent/zcodeAgentConnectionScope.ts:175-176,452-453）。
// 断言纪律（AGENTS.md §5-18③）：断言结构信号（FinishOutcome sealed 子型 + toSeq 序列），
// 不断言文案语言；失败信息为 iOS 原文 XCTFail 文案的对译。

/**
 * ACK 激活屏障单测（订阅回执竞态：暂存→原序回放→丢弃/溢出）。
 * iOS async 用例 → runTest（kotlinx-coroutines-test，protocol/build.gradle.kts:22 预置）。
 */
class AckActivationBarrierTests {

    /** iOS `frame(seq:)`（原文 :8-12）逐字段对应。 */
    private fun frame(seq: Int): V4TopicFrame = V4TopicFrame(
        topic = "conversation/sess_1", subscriptionId = "", fromSeq = seq, toSeq = seq,
        sentAt = null, snapshot = null, deltas = emptyList())

    @Test
    fun testStagedFramesReplayInOrder() = runTest {
        val barrier = AckActivationBarrier()
        barrier.begin("k1")
        // 回执前到达三帧：暂存不消费
        barrier.stage("k1", frame(seq = 1))
        barrier.stage("k1", frame(seq = 2))
        barrier.stage("k1", frame(seq = 3))
        val frames = barrier.finish("k1") as? AckActivationBarrier.FinishOutcome.Frames
        assertTrue(frames != null, "应回放暂存帧")
        assertEquals(listOf(1, 2, 3), frames.frames.map { it.toSeq })
        // finish 后屏障清空：再 finish 为 empty
        val again = barrier.finish("k1")
        assertTrue(again is AckActivationBarrier.FinishOutcome.Empty, "finish 后应为 empty")
    }

    @Test
    fun testAbortDropsStagedFrames() = runTest {
        val barrier = AckActivationBarrier()
        barrier.begin("k1")
        barrier.stage("k1", frame(seq = 1))
        barrier.abort("k1") // 订阅失败：暂存帧整体丢弃
        val outcome = barrier.finish("k1")
        assertTrue(outcome is AckActivationBarrier.FinishOutcome.Empty, "abort 后不得回放")
    }

    @Test
    fun testOverflowDropsAllAndSignals() = runTest {
        val barrier = AckActivationBarrier()
        barrier.begin("k1")
        for (seq in 0 until AckActivationBarrier.stagingLimit) {
            barrier.stage("k1", frame(seq = seq))
        }
        // 超限一帧 → 整体作废
        val accepted = barrier.stage("k1", frame(seq = 999))
        assertFalse(accepted)
        val outcome = barrier.finish("k1")
        assertTrue(
            outcome is AckActivationBarrier.FinishOutcome.Overflow,
            "超限应报 overflow（消费方走全量 resync 重建）")
    }

    @Test
    fun testStageWithoutBeginIsIgnored() = runTest {
        val barrier = AckActivationBarrier()
        // 未 begin（不在订阅窗口）：不暂存（帧走直通路径，由调用方处理）
        val accepted = barrier.stage("k1", frame(seq = 1))
        assertFalse(accepted)
        val inFlight = barrier.isInFlight("k1")
        assertFalse(inFlight)
    }

    @Test
    fun testFinishWithoutBeginReturnsEmpty() = runTest {
        val barrier = AckActivationBarrier()
        val outcome = barrier.finish("k1")
        assertTrue(outcome is AckActivationBarrier.FinishOutcome.Empty, "未 begin 的 finish 应为 empty")
    }

    @Test
    fun testBeginIsReusableAcrossSubscribeRetries() = runTest {
        // 订阅失败（abort）后重订阅（begin）：状态干净复用
        val barrier = AckActivationBarrier()
        barrier.begin("k1")
        barrier.stage("k1", frame(seq = 7))
        barrier.abort("k1")
        barrier.begin("k1")
        val outcome = barrier.finish("k1")
        assertTrue(outcome is AckActivationBarrier.FinishOutcome.Empty, "重新 begin 后不应残留旧帧")
    }
}

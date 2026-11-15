package cn.biuz.android.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 中继重连退避策略三端同构对拍（Android 侧）。
 * 权威语义：web v3.18.1 `packages/shared/src/web-remote-relay/domain/retry.ts`
 * （commit c5904686ea83，WebRemoteControlV2RetryPolicy）；与小程序
 * `miniprogram/tests/relay-retry-policy.test.js`（12 项）、iOS
 * `RelayRetryPolicyTests` 逐向量对齐。
 * 确定性向量：全部通过注入 random 固定 0 / 0.5 / 1，不依赖真实随机数。
 *
 * 测试框架沿用 :relay 模块既有约定（build.gradle.kts 配 test-junit5 +
 * useJUnitPlatform），与 RelayV2Test 同形；断言用 kotlin.test。
 */
class RelayRetryPolicyTest {

    /** 固定返回值的 random 替身。 */
    private fun fixedRandom(value: Double): () -> Double = { value }

    // ---- 常量与默认参数对齐 web ----

    @Test
    fun constantsAndDefaultsMatchWeb() {
        assertEquals(1_500L, RELAY_RECONNECT_SPREAD_MS)
        assertEquals(1_000L, RELAY_RETRY_DEFAULT_INITIAL_MS)
        assertEquals(30_000L, RELAY_RETRY_DEFAULT_MAX_MS)
        assertEquals(0.2, RELAY_RETRY_DEFAULT_JITTER_RATIO)
        assertEquals(1_500L, RELAY_RETRY_DEFAULT_SPREAD_MS)
    }

    // ---- acknowledged 后首次重连：0..1500 打散窗口，random=0/0.5/1 → 0/750/1499 ----

    @Test
    fun acknowledgedFirstDelaySpreadsInWindow() {
        // 窗口实现 floor(random*1500)，整数上界 1499（random=1 为注入边界，由 clamp 守住）
        val p0 = RelayRetryPolicy(random = fixedRandom(0.0))
        p0.acknowledged()
        assertEquals(0L, p0.nextDelay())

        val pHalf = RelayRetryPolicy(random = fixedRandom(0.5))
        pHalf.acknowledged()
        assertEquals(750L, pHalf.nextDelay())

        val p1 = RelayRetryPolicy(random = fixedRandom(1.0))
        p1.acknowledged()
        assertEquals(1_499L, p1.nextDelay())
    }

    // ---- spread 只生效一次：首次打散后，第二次 nextDelay 回到指数曲线 ----

    @Test
    fun spreadConsumedOnceThenBackToExponential() {
        val p = RelayRetryPolicy(random = fixedRandom(0.5))
        p.acknowledged()
        assertEquals(750L, p.nextDelay(), "第一次：打散窗口随机")
        // 第二次起回到指数曲线 n=0，base=1000，random=0.5 → 乘数 0.8+0.5*0.4=1.0
        assertEquals(1_000L, p.nextDelay(), "第二次：指数曲线 n=0 base=1000")
    }

    // ---- spread 消费后的指数序列与 jitter（n=0/n=1，random=0 与 1）----

    @Test
    fun exponentialSequenceAfterSpread() {
        val p = RelayRetryPolicy(random = fixedRandom(0.0))
        p.acknowledged()
        assertEquals(0L, p.nextDelay(), "spread 一次（random=0）")
        assertEquals(800L, p.nextDelay(), "指数 n=0：1000*(1-0.2)=800")
        assertEquals(1_600L, p.nextDelay(), "指数 n=1：2000*(1-0.2)=1600")

        val q = RelayRetryPolicy(random = fixedRandom(1.0))
        q.acknowledged()
        assertEquals(1_499L, q.nextDelay(), "spread 一次（random=1 边界 clamp）")
        assertEquals(1_200L, q.nextDelay(), "指数 n=0：1000*(1+0.2)=1200")
        assertEquals(2_400L, q.nextDelay(), "指数 n=1：2000*(1+0.2)=2400")
    }

    // ---- reset：用户主动重连/回前台不打散，且指数计数归零 ----

    @Test
    fun resetClearsCounterAndSpread() {
        val p = RelayRetryPolicy(random = fixedRandom(0.0))
        // 未 acknowledged 直接 reset：不打散，n=0
        p.reset()
        assertEquals(800L, p.nextDelay(), "reset 后首调走指数曲线而非 spread")

        // 先累积两档指数（n=0、n=1），再 reset，计数应回到 n=0
        assertEquals(1_600L, p.nextDelay(), "reset 后第二次调用为 n=1")
        p.reset()
        assertEquals(800L, p.nextDelay(), "再次 reset 后从 n=0 重新开始")

        // acknowledged 后立刻 reset：spread 被清除，不打散
        val q = RelayRetryPolicy(random = fixedRandom(0.0))
        q.acknowledged()
        q.reset()
        assertEquals(800L, q.nextDelay(), "acknowledged 后 reset 应取消待生效 spread")
    }

    // ---- jitter ±20% 边界：同档 base 下 random=0/0.5/1 乘数 0.8/1.0/1.2 ----

    @Test
    fun jitterBounds() {
        val low = RelayRetryPolicy(random = fixedRandom(0.0))
        assertEquals(800L, low.nextDelay(), "下界 base*0.8")

        val mid = RelayRetryPolicy(random = fixedRandom(0.5))
        assertEquals(1_000L, mid.nextDelay(), "中值 base*1.0")

        val high = RelayRetryPolicy(random = fixedRandom(1.0))
        assertEquals(1_200L, high.nextDelay(), "上界 base*1.2")
    }

    // ---- 指数延迟封顶 maxMs=30000（random=1：第 6 次起恒为 30000）----

    @Test
    fun exponentialDelayCappedAtMaxMs() {
        val p = RelayRetryPolicy(random = fixedRandom(1.0))
        val delays = (0 until 20).map { p.nextDelay() }
        // 基数序列 1000/2000/4000/8000/16000，第 6 档 base=min(30000,32000)=30000
        assertEquals(listOf(1_200L, 2_400L, 4_800L, 9_600L, 19_200L), delays.take(5))
        assertEquals(30_000L, delays[5], "base 与 jitter 双层封顶后取 30000")
        assertTrue(delays.drop(5).all { it == 30_000L }, "后续恒为封顶值")
        assertTrue(delays.all { it <= 30_000L }, "任何延迟不得超过 maxMs")
    }

    // ---- base 先于 jitter 封顶：random=0 第 6 档为 30000*0.8=24000 ----

    @Test
    fun baseCappedBeforeJitter() {
        val p = RelayRetryPolicy(random = fixedRandom(0.0))
        var last = 0L
        repeat(6) { last = p.nextDelay() }
        assertEquals(24_000L, last, "base 已封到 30000，再乘下界乘数 0.8")
    }

    // ---- 未 acknowledged 的全新实例：首次 nextDelay 直接走指数曲线，不打散 ----

    @Test
    fun freshInstanceSkipsSpreadWithoutAcknowledged() {
        val p = RelayRetryPolicy(random = fixedRandom(0.5))
        assertEquals(1_000L, p.nextDelay(), "无 spread 待生效，n=0 base=1000")
    }

    // ---- acknowledged 语义：清指数计数，且 spread 仅 arm 一次（重复 acknowledged 不叠加）----

    @Test
    fun acknowledgedResetsCounterAndArmsSpreadOnce() {
        val p = RelayRetryPolicy(random = fixedRandom(0.5))
        p.nextDelay() // n=0 → 1000，failures→1
        p.nextDelay() // n=1 → 2000，failures→2
        p.acknowledged() // 计数清零并 arm spread
        p.acknowledged() // 重复调用不改变一次性语义
        assertEquals(750L, p.nextDelay(), "ack 后第一次仍是打散")
        assertEquals(1_000L, p.nextDelay(), "spread 只一次；计数从 n=0 重启而非沿用 n=2")
    }

    // ---- 自定义参数注入（initialMs/maxMs/jitterRatio/spreadMs）逐档生效 ----

    @Test
    fun customOptionsTakeEffect() {
        val p = RelayRetryPolicy(
            random = fixedRandom(1.0),
            initialMs = 100L,
            maxMs = 500L,
            jitterRatio = 0.5,
            spreadMs = 10L,
        )
        p.acknowledged()
        assertEquals(9L, p.nextDelay(), "自定义窗口 floor(1*10) 后 clamp 到 spreadMs-1=9")
        assertEquals(150L, p.nextDelay(), "指数 n=0：100*(1+0.5)=150")
        val capped = (0 until 8).map { p.nextDelay() }
        assertTrue(capped.all { it <= 500L }, "自定义 maxMs 封顶生效")
        assertEquals(500L, capped.last())
    }

    // ---- 默认 Random 冒烟：spread 结果为 0..1499 的整数，无需注入亦可工作 ----

    @Test
    fun defaultRandomSmoke() {
        val p = RelayRetryPolicy()
        p.acknowledged()
        val d = p.nextDelay()
        assertTrue(d in 0..1_499, "打散窗口整数域 0..1499，实际=$d")
    }
}

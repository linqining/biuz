import XCTest
@testable import ZCodeMobile

/// 中继重连退避策略三端同构对拍（iOS 侧）。
/// 权威语义：web v3.18.1 `packages/shared/src/web-remote-relay/domain/retry.ts`
/// （commit c5904686ea83，WebRemoteControlV2RetryPolicy）；与小程序
/// `miniprogram/tests/relay-retry-policy.test.js`（12 项）逐向量对齐。
/// 确定性向量：全部通过注入 random 固定 0 / 0.5 / 1，不依赖真实随机数。
final class RelayRetryPolicyTests: XCTestCase {

    /// 固定返回值的 random 替身。
    private func fixedRandom(_ value: Double) -> () -> Double {
        { value }
    }

    // MARK: 常量与默认参数对齐 web

    func testConstantsAndDefaultsMatchWeb() {
        XCTAssertEqual(RelayRetryPolicy.reconnectSpreadMs, 1_500)
        XCTAssertEqual(RelayRetryPolicy.defaultInitialMs, 1_000)
        XCTAssertEqual(RelayRetryPolicy.defaultMaxMs, 30_000)
        XCTAssertEqual(RelayRetryPolicy.defaultJitterRatio, 0.2, accuracy: 0.000_001)
        XCTAssertEqual(RelayRetryPolicy.defaultSpreadMs, 1_500)
    }

    // MARK: acknowledged 后首次重连：0..1500 打散窗口

    func testAcknowledgedFirstDelaySpreadsInWindow() {
        // 窗口实现 floor(random*1500)，整数上界 1499（random=1 为注入边界，由 clamp 守住）
        var p0 = RelayRetryPolicy(random: fixedRandom(0))
        p0.acknowledged()
        XCTAssertEqual(p0.nextDelay(), 0)

        var pHalf = RelayRetryPolicy(random: fixedRandom(0.5))
        pHalf.acknowledged()
        XCTAssertEqual(pHalf.nextDelay(), 750)

        var p1 = RelayRetryPolicy(random: fixedRandom(1))
        p1.acknowledged()
        XCTAssertEqual(p1.nextDelay(), 1_499)
    }

    // MARK: spread 只生效一次：首次打散后第二次回到指数曲线

    func testSpreadConsumedOnceThenBackToExponential() {
        var p = RelayRetryPolicy(random: fixedRandom(0.5))
        p.acknowledged()
        XCTAssertEqual(p.nextDelay(), 750, "第一次：打散窗口随机")
        // 第二次起回到指数曲线 n=0，base=1000，random=0.5 → 乘数 0.8+0.5*0.4=1.0
        XCTAssertEqual(p.nextDelay(), 1_000, "第二次：指数曲线 n=0 base=1000")
    }

    // MARK: spread 消费后的指数序列与 jitter（n=0/n=1，random=0 与 1）

    func testExponentialSequenceAfterSpread() {
        var p = RelayRetryPolicy(random: fixedRandom(0))
        p.acknowledged()
        XCTAssertEqual(p.nextDelay(), 0, "spread 一次（random=0）")
        XCTAssertEqual(p.nextDelay(), 800, "指数 n=0：1000*(1-0.2)=800")
        XCTAssertEqual(p.nextDelay(), 1_600, "指数 n=1：2000*(1-0.2)=1600")

        var q = RelayRetryPolicy(random: fixedRandom(1))
        q.acknowledged()
        XCTAssertEqual(q.nextDelay(), 1_499, "spread 一次（random=1 边界 clamp）")
        XCTAssertEqual(q.nextDelay(), 1_200, "指数 n=0：1000*(1+0.2)=1200")
        XCTAssertEqual(q.nextDelay(), 2_400, "指数 n=1：2000*(1+0.2)=2400")
    }

    // MARK: reset：用户主动重连/回前台不打散，且指数计数归零

    func testResetClearsCounterAndSpread() {
        var p = RelayRetryPolicy(random: fixedRandom(0))
        // 未 acknowledged 直接 reset：不打散，n=0
        p.reset()
        XCTAssertEqual(p.nextDelay(), 800, "reset 后首调走指数曲线而非 spread")

        // 先累积两档指数（n=0、n=1），再 reset，计数应回到 n=0
        XCTAssertEqual(p.nextDelay(), 1_600, "reset 后第二次调用为 n=1")
        p.reset()
        XCTAssertEqual(p.nextDelay(), 800, "再次 reset 后从 n=0 重新开始")

        // acknowledged 后立刻 reset：spread 被清除，不打散
        var q = RelayRetryPolicy(random: fixedRandom(0))
        q.acknowledged()
        q.reset()
        XCTAssertEqual(q.nextDelay(), 800, "acknowledged 后 reset 应取消待生效 spread")
    }

    // MARK: jitter ±20% 边界：同档 base 下 random=0/0.5/1 乘数 0.8/1.0/1.2

    func testJitterBounds() {
        var low = RelayRetryPolicy(random: fixedRandom(0))
        XCTAssertEqual(low.nextDelay(), 800, "下界 base*0.8")

        var mid = RelayRetryPolicy(random: fixedRandom(0.5))
        XCTAssertEqual(mid.nextDelay(), 1_000, "中值 base*1.0")

        var high = RelayRetryPolicy(random: fixedRandom(1))
        XCTAssertEqual(high.nextDelay(), 1_200, "上界 base*1.2")
    }

    // MARK: 指数延迟封顶 maxMs=30000（random=1：第 6 次起恒为 30000）

    func testExponentialDelayCappedAtMaxMs() {
        var p = RelayRetryPolicy(random: fixedRandom(1))
        var delays: [UInt64] = []
        for _ in 0..<20 {
            delays.append(p.nextDelay())
        }
        // 基数序列 1000/2000/4000/8000/16000，第 6 档 base=min(30000,32000)=30000
        XCTAssertEqual(Array(delays.prefix(5)), [1_200, 2_400, 4_800, 9_600, 19_200])
        XCTAssertEqual(delays[5], 30_000, "base 与 jitter 双层封顶后取 30000")
        XCTAssertTrue(delays.dropFirst(5).allSatisfy { $0 == 30_000 }, "后续恒为封顶值")
        XCTAssertTrue(delays.allSatisfy { $0 <= 30_000 }, "任何延迟不得超过 maxMs")
    }

    // MARK: base 先于 jitter 封顶：random=0 第 6 档为 30000*0.8=24000

    func testBaseCappedBeforeJitter() {
        var p = RelayRetryPolicy(random: fixedRandom(0))
        var last: UInt64 = 0
        for _ in 0..<6 {
            last = p.nextDelay()
        }
        XCTAssertEqual(last, 24_000, "base 已封到 30000，再乘下界乘数 0.8")
    }

    // MARK: 未 acknowledged 的全新实例：首次 nextDelay 直接走指数曲线，不打散

    func testFreshInstanceSkipsSpreadWithoutAcknowledged() {
        var p = RelayRetryPolicy(random: fixedRandom(0.5))
        XCTAssertEqual(p.nextDelay(), 1_000, "无 spread 待生效，n=0 base=1000")
    }

    // MARK: acknowledged 语义：清指数计数，且 spread 仅 arm 一次（重复 acknowledged 不叠加）

    func testAcknowledgedResetsCounterAndArmsSpreadOnce() {
        var p = RelayRetryPolicy(random: fixedRandom(0.5))
        _ = p.nextDelay() // n=0 → 1000，failures→1
        _ = p.nextDelay() // n=1 → 2000，failures→2
        p.acknowledged()  // 计数清零并 arm spread
        p.acknowledged()  // 重复调用不改变一次性语义
        XCTAssertEqual(p.nextDelay(), 750, "ack 后第一次仍是打散")
        XCTAssertEqual(p.nextDelay(), 1_000, "spread 只一次；计数从 n=0 重启而非沿用 n=2")
    }

    // MARK: 自定义参数注入（initialMs/maxMs/jitterRatio/spreadMs）逐档生效

    func testCustomOptionsTakeEffect() {
        var p = RelayRetryPolicy(
            random: fixedRandom(1),
            initialMs: 100,
            maxMs: 500,
            jitterRatio: 0.5,
            spreadMs: 10)
        p.acknowledged()
        XCTAssertEqual(p.nextDelay(), 9, "自定义窗口 floor(1*10) 后 clamp 到 spreadMs-1=9")
        XCTAssertEqual(p.nextDelay(), 150, "指数 n=0：100*(1+0.5)=150")
        var capped: [UInt64] = []
        for _ in 0..<8 {
            capped.append(p.nextDelay())
        }
        XCTAssertTrue(capped.allSatisfy { $0 <= 500 }, "自定义 maxMs 封顶生效")
        XCTAssertEqual(capped.last, 500)
    }

    // MARK: 默认随机数冒烟：spread 结果为 0..1499 的整数，无需注入亦可工作

    func testDefaultRandomSmoke() {
        var p = RelayRetryPolicy()
        p.acknowledged()
        let d = p.nextDelay()
        XCTAssertLessThanOrEqual(d, 1_499, "打散窗口整数域 0..1499，实际=\(d)")
    }
}

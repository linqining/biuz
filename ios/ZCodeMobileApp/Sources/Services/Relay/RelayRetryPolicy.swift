import Foundation

// MARK: - 中继重连退避策略（纯策略层）—— web v3.18.1
//
// 权威来源 commit：c5904686ea83
// `packages/shared/src/web-remote-relay/domain/retry.ts` 的 Swift 移植
// （WebRemoteControlV2RetryPolicy 逐语义对齐），与小程序
// `miniprogram/utils/relay-retry-policy.js`（已通过 12 项单测）三端同构。
//
// 语义对齐（web v3.18.1 新策略）：
// 1. 连接 acknowledged 后的「第一次」重连不走指数曲线，而在 0..spreadMs 打散窗口内
//    一次性随机（spreadNext，仅一次），避免多设备 thundering herd；
// 2. 用户主动 start / 回前台走 reset()：清退避计数与 spread，reset 后不打散；
// 3. 其后回到指数退避 initialMs=1000、maxMs=30000、jitterRatio=0.2（±20%）；
// 4. 常量 reconnectSpreadMs=1500，对应 web
//    WEB_REMOTE_CONTROL_V2_RECONNECT_SPREAD_MS。
//
// 仅策略层，本提交不接 RelayTransport 状态机（接线留待后续）；只依赖 Foundation，
// 可在 XCTest 中以注入 random 的方式确定性对拍。

/// 中继重连退避策略（值类型；每个 transport 实例独占一个，对齐 web 每实例独占语义）。
struct RelayRetryPolicy {

    /// 连接建立后第一次重连的打散窗口（ms），电脑与手机共用，
    /// 对齐 web `WEB_REMOTE_CONTROL_V2_RECONNECT_SPREAD_MS`。
    static let reconnectSpreadMs: UInt64 = 1_500

    /// 默认退避参数（对齐 web backoff：initialMs 1000 / maxMs 30000 / jitter 0.2 / spreadMs 1500）。
    static let defaultInitialMs: UInt64 = 1_000
    static let defaultMaxMs: UInt64 = 30_000
    static let defaultJitterRatio: Double = 0.2
    static let defaultSpreadMs: UInt64 = Self.reconnectSpreadMs

    /// 随机数注入，默认 `Double.random(in: 0..<1)`（真值域 [0,1)，对齐 Math.random）；
    /// 确定性单测可固定返回 0 / 0.5 / 1。
    private let random: () -> Double
    /// 指数退避首档基数（ms）。
    private let initialMs: UInt64
    /// 延迟硬封顶（ms），base 与 jitter 后各封一次。
    private let maxMs: UInt64
    /// jitter 比例，乘数区间 [1-r, 1+r]（±20%）。
    private let jitterRatio: Double
    /// acknowledged 后首次重连的打散窗口（ms）。
    private let spreadMs: UInt64

    /// 对齐 web 私有态：failures=指数失败计数，spreadNext=下一次重连是否走一次性打散。
    private var failures: Int = 0
    private var spreadNext: Bool = false

    /// - Parameters:
    ///   - random: 随机数注入闭包，默认 `Double.random(in: 0..<1)`；确定性单测可固定返回 0/0.5/1。
    ///   - initialMs: 指数退避首档基数（ms），默认 1000。
    ///   - maxMs: 延迟硬封顶（ms），base 与 jitter 后各封一次，默认 30000。
    ///   - jitterRatio: jitter 比例，乘数区间 [1-r, 1+r]，默认 0.2（±20%）。
    ///   - spreadMs: acknowledged 后首次重连的打散窗口（ms），默认 1500。
    init(random: @escaping () -> Double = { Double.random(in: 0..<1) },
         initialMs: UInt64 = Self.defaultInitialMs,
         maxMs: UInt64 = Self.defaultMaxMs,
         jitterRatio: Double = Self.defaultJitterRatio,
         spreadMs: UInt64 = Self.defaultSpreadMs) {
        self.random = random
        self.initialMs = initialMs
        self.maxMs = maxMs
        self.jitterRatio = jitterRatio
        self.spreadMs = spreadMs
    }

    /// 用户触发的连接（start、回前台）——对齐 web `reset()`：
    /// 清退避计数与 spread 标记，按原曲线尽快重连，不打散。
    mutating func reset() {
        failures = 0
        spreadNext = false
    }

    /// 连接已建立（auth_ack 后）——对齐 web `acknowledged()`：
    /// 清零指数计数，并 arm 一次性 spread：下一次 `nextDelay()` 在整个打散窗口内随机，
    /// 只打散这一次，失败后回到原指数曲线。
    mutating func acknowledged() {
        failures = 0
        spreadNext = true
    }

    /// 返回下一次重连延迟（ms）。每次调用只消费一次 `random()`。
    ///
    /// 分支一（spreadNext=true，仅 acknowledged 后的第一次）：
    ///   web 原文 `Math.floor(random() * spreadMs)`。Math.random() 真值域为 [0,1)，
    ///   floor 结果天然落在整数 0..spreadMs-1；注入式 random 在确定性单测里可能返回
    ///   恰好 1，故用 `min(spreadMs - 1, …)` 守住窗口上界——生产环境 random()<1
    ///   时该 clamp 永不触发，与 web 数值完全一致。
    ///
    /// 分支二（指数退避）：对齐 web
    ///   base   = min(maxMs, initialMs * 2^min(failures, 16))，failures 后置自增；
    ///   delay  = min(maxMs, round(base * (1 - jitter + random() * jitter * 2)))，
    ///   乘数区间 [1-jitterRatio, 1+jitterRatio]，即 ±20%，外层再封 maxMs。
    /// - Returns: 下一次重连延迟（毫秒）
    mutating func nextDelay() -> UInt64 {
        if spreadNext {
            spreadNext = false
            let raw = UInt64((random() * Double(spreadMs)).rounded(.down))
            let upperBound = spreadMs > 0 ? spreadMs - 1 : 0
            return min(upperBound, raw)
        }
        let exponent = min(failures, 16)
        failures += 1
        let base = min(Double(maxMs), Double(initialMs) * pow(2.0, Double(exponent)))
        let multiplier = 1.0 - jitterRatio + random() * jitterRatio * 2.0
        // 对齐 JS Math.round（非负数 half-up）；Swift rounded(.toNearestOrEven) 是银行家舍入，
        // 与 web 语义不同，故显式 floor(x + 0.5)。当前向量均不落在 .5 边界，保留以严格对齐。
        let rounded = (base * multiplier + 0.5).rounded(.down)
        return min(maxMs, UInt64(rounded))
    }
}

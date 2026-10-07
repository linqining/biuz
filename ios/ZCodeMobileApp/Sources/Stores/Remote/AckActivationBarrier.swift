import Foundation

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
actor AckActivationBarrier {

    enum FinishOutcome {
        case frames([V4TopicFrame])   // 原序回放
        case overflow                  // 暂存超限：消费方走全量 resync 重建
        case empty                     // 无暂存帧
    }

    /// 暂存上限（上游 host staging 量级；到达即 overflow——帧积压意味着回执异常
    /// 迟到，此时增量连续性不可保证，全量快照重建是唯一安全口径）
    nonisolated static let stagingLimit = 256

    private var inFlightKeys: Set<String> = []
    private var staged: [String: [V4TopicFrame]] = [:]
    private var overflowed: Set<String> = []

    /// 订阅发起前调用（必须先于 subscribe RPC）
    func begin(_ key: String) {
        inFlightKeys.insert(key)
        staged[key] = []
        overflowed.remove(key)
    }

    func isInFlight(_ key: String) -> Bool {
        inFlightKeys.contains(key)
    }

    /// 回执未到时暂存一帧；false = 暂存区已满（本帧起整体作废，finish 报 overflow）
    @discardableResult
    func stage(_ key: String, frame: V4TopicFrame) -> Bool {
        guard inFlightKeys.contains(key) else { return false }
        if overflowed.contains(key) { return false }
        guard (staged[key]?.count ?? 0) < Self.stagingLimit else {
            overflowed.insert(key)
            staged[key] = nil
            return false
        }
        staged[key, default: []].append(frame)
        return true
    }

    /// 订阅成功：取出全部暂存帧（原序）并清除屏障态
    func finish(_ key: String) -> FinishOutcome {
        defer {
            inFlightKeys.remove(key)
            staged[key] = nil
            overflowed.remove(key)
        }
        guard inFlightKeys.contains(key) else { return .empty }
        if overflowed.contains(key) { return .overflow }
        guard let frames = staged[key], !frames.isEmpty else { return .empty }
        return .frames(frames)
    }

    /// 订阅失败/放弃：丢弃暂存帧
    func abort(_ key: String) {
        inFlightKeys.remove(key)
        staged[key] = nil
        overflowed.remove(key)
    }
}

import XCTest
@testable import ZCodeMobile

// MARK: - AckActivationBarrier 单测（订阅回执竞态：暂存→原序回放→丢弃/溢出）

final class AckActivationBarrierTests: XCTestCase {

    private func frame(seq: Int) -> V4TopicFrame {
        V4TopicFrame(
            topic: "conversation/sess_1", subscriptionId: "", fromSeq: seq, toSeq: seq,
            sentAt: nil, snapshot: nil, deltas: [])
    }

    func testStagedFramesReplayInOrder() async {
        let barrier = AckActivationBarrier()
        await barrier.begin("k1")
        // 回执前到达三帧：暂存不消费
        await barrier.stage("k1", frame: frame(seq: 1))
        await barrier.stage("k1", frame: frame(seq: 2))
        await barrier.stage("k1", frame: frame(seq: 3))
        guard case .frames(let frames) = await barrier.finish("k1") else {
            return XCTFail("应回放暂存帧")
        }
        XCTAssertEqual(frames.map(\.toSeq), [1, 2, 3])
        // finish 后屏障清空：再 finish 为 empty
        let again = await barrier.finish("k1")
        guard case .empty = again else { return XCTFail("finish 后应为 empty") }
    }

    func testAbortDropsStagedFrames() async {
        let barrier = AckActivationBarrier()
        await barrier.begin("k1")
        await barrier.stage("k1", frame: frame(seq: 1))
        await barrier.abort("k1") // 订阅失败：暂存帧整体丢弃
        guard case .empty = await barrier.finish("k1") else {
            return XCTFail("abort 后不得回放")
        }
    }

    func testOverflowDropsAllAndSignals() async {
        let barrier = AckActivationBarrier()
        await barrier.begin("k1")
        for seq in 0..<AckActivationBarrier.stagingLimit {
            await barrier.stage("k1", frame: frame(seq: seq))
        }
        // 超限一帧 → 整体作废
        let accepted = await barrier.stage("k1", frame: frame(seq: 999))
        XCTAssertFalse(accepted)
        guard case .overflow = await barrier.finish("k1") else {
            return XCTFail("超限应报 overflow（消费方走全量 resync 重建）")
        }
    }

    func testStageWithoutBeginIsIgnored() async {
        let barrier = AckActivationBarrier()
        // 未 begin（不在订阅窗口）：不暂存（帧走直通路径，由调用方处理）
        let accepted = await barrier.stage("k1", frame: frame(seq: 1))
        XCTAssertFalse(accepted)
        let inFlight = await barrier.isInFlight("k1")
        XCTAssertFalse(inFlight)
    }

    func testFinishWithoutBeginReturnsEmpty() async {
        let barrier = AckActivationBarrier()
        guard case .empty = await barrier.finish("k1") else {
            return XCTFail("未 begin 的 finish 应为 empty")
        }
    }

    func testBeginIsReusableAcrossSubscribeRetries() async {
        // 订阅失败（abort）后重订阅（begin）：状态干净复用
        let barrier = AckActivationBarrier()
        await barrier.begin("k1")
        await barrier.stage("k1", frame: frame(seq: 7))
        await barrier.abort("k1")
        await barrier.begin("k1")
        guard case .empty = await barrier.finish("k1") else {
            return XCTFail("重新 begin 后不应残留旧帧")
        }
    }
}

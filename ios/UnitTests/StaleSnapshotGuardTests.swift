import XCTest
@testable import ZCodeMobile

// MARK: - 旧快照丢弃判定（2026-11-24 与 Android 同步）
//
// 用户报障「移动端落后桌面端，消息被覆盖，新的消息不见只有旧的」：会话快照帧此前没有
// 任何时序校验——迟到的 resync/重放快照会按旧内容覆盖 rows 并把水位写回旧值；水位回退后
// 新增量又被当重复帧丢掉 → 界面停在旧内容上再也不动。
// 判据只信**确凿的旧证据**，宁放勿误杀（误杀会丢恢复帧，比回退更糟）。

final class StaleSnapshotGuardTests: XCTestCase {

    private func drop(
        toSeq: Int,
        rev: Int? = nil,
        watermarkSeq: Int? = nil,
        epoch: String? = "e1",
        snapEpoch: String? = "e1",
        knownRev: Int? = nil
    ) -> Bool {
        RemoteConversationStore.shouldDropStaleSnapshot(
            frameToSeq: toSeq,
            frameRevision: rev,
            watermarkSeq: watermarkSeq,
            watermarkEpoch: epoch,
            snapshotEpoch: snapEpoch,
            knownRevision: knownRev)
    }

    func testSnapshotOlderThanWatermarkIsDropped() {
        XCTAssertTrue(drop(toSeq: 2, watermarkSeq: 7), "toSeq 落后水位 → 旧帧")
        XCTAssertTrue(drop(toSeq: 7, watermarkSeq: 7), "toSeq 相等 → 已应用过")
    }

    func testSnapshotAtOrAheadOfWatermarkIsApplied() {
        XCTAssertFalse(drop(toSeq: 8, watermarkSeq: 7), "水位之后 → 新帧")
        XCTAssertFalse(drop(toSeq: 1, watermarkSeq: nil), "首次快照（无水位）→ 应用")
    }

    func testEpochChangeAlwaysWins() {
        XCTAssertFalse(
            drop(toSeq: 1, watermarkSeq: 99, epoch: "e2", snapEpoch: "e1"),
            "纪元换代（重启/fork 重编号 rowId）→ 一律认新")
    }

    func testZeroSeqSentinelIsNotTreatedAsStale() {
        XCTAssertFalse(drop(toSeq: 0, watermarkSeq: 12), "toSeq=0 是部分桌面的快照哨兵 → 不按 seq 判")
    }

    func testLowerRevisionIsDropped() {
        XCTAssertTrue(drop(toSeq: 0, rev: 8, watermarkSeq: 12, knownRev: 10), "revision 更小 → 旧状态")
        XCTAssertFalse(drop(toSeq: 0, rev: 10, watermarkSeq: 12, knownRev: 10), "revision 相等 → 应用")
        XCTAssertFalse(drop(toSeq: 0, rev: 11, watermarkSeq: 12, knownRev: 10), "revision 更大 → 应用")
    }

    func testUnknownRevisionFallsBackToSeqRuleOnly() {
        XCTAssertFalse(drop(toSeq: 0, rev: nil, watermarkSeq: 12, knownRev: nil), "无 revision 证据 → 不误杀")
        XCTAssertTrue(drop(toSeq: 5, rev: nil, watermarkSeq: 12, knownRev: nil), "仍有 seq 证据 → 丢")
    }

    func testNewerSeqIsNeverDroppedByLowerRevision() {
        // 回归（2026-11-25）：快照顶层 revision 与 state.updated patch.revision 未必同源，
        // 曾把 revision 当独立判据 → 一个 toSeq 更新的合法快照被单独判死，连带丢掉
        // 它携带的 pendingInteractions（用户报「iOS 端审批没看到」）。seq 有值就只信 seq。
        XCTAssertFalse(
            drop(toSeq: 9, rev: 1, watermarkSeq: 8, knownRev: 99),
            "toSeq 已前进 → 不得因 revision 更小而丢")
        XCTAssertTrue(
            drop(toSeq: 0, rev: 1, watermarkSeq: 8, knownRev: 99),
            "哨兵帧才用 revision 兜底")
    }
}

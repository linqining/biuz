package cn.biuz.android.store

// 旧快照丢弃判定（2026-11-24 用户报障「移动端落后桌面端，消息被覆盖，新的消息不见只有旧的」）。
//
// 快照帧此前没有任何时序校验：迟到的 resync/重放快照会按旧内容合并甚至整表替换 rows，
// 并把水位写回旧值；水位回退后又会让新增量被当成重复帧丢弃 → 永远落后。
// 判据只信**确凿的旧证据**，宁放勿误杀（误杀的代价是丢恢复帧，比回退更糟）。

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleSnapshotGuardTest {

    private fun drop(
        toSeq: Int,
        rev: Long? = null,
        watermarkSeq: Int? = null,
        epoch: String? = "e1",
        snapEpoch: String? = "e1",
        knownRev: Long? = null,
    ) = shouldDropStaleSnapshot(
        frameToSeq = toSeq,
        frameRevision = rev,
        watermarkSeq = watermarkSeq,
        watermarkEpoch = epoch,
        snapshotEpoch = snapEpoch,
        knownRevision = knownRev,
    )

    @Test
    fun snapshotOlderThanWatermarkIsDropped() {
        assertTrue(drop(toSeq = 2, watermarkSeq = 7), "toSeq 落后水位 → 旧帧")
        assertTrue(drop(toSeq = 7, watermarkSeq = 7), "toSeq 相等 → 已应用过")
    }

    @Test
    fun snapshotAtOrAheadOfWatermarkIsApplied() {
        assertFalse(drop(toSeq = 8, watermarkSeq = 7), "水位之后 → 新帧")
        assertFalse(drop(toSeq = 1, watermarkSeq = null), "首次快照（无水位）→ 应用")
    }

    @Test
    fun epochChangeAlwaysWins() {
        // CLI 重启/fork 重新编号 rowId，seq 从头编排——按 seq 比会误杀恢复帧
        assertFalse(
            drop(toSeq = 1, watermarkSeq = 99, epoch = "e2", snapEpoch = "e1"),
            "纪元换代 → 一律认新",
        )
    }

    @Test
    fun zeroSeqSentinelIsNotTreatedAsStale() {
        // 部分桌面的快照固定 fromSeq=0/toSeq=0（哨兵），不能据 seq 判旧
        assertFalse(drop(toSeq = 0, watermarkSeq = 12), "toSeq=0 哨兵 → 不按 seq 判")
    }

    @Test
    fun lowerRevisionIsDropped() {
        assertTrue(
            drop(toSeq = 0, rev = 8, watermarkSeq = 12, knownRev = 10),
            "revision 严格更小 → 旧状态（哨兵 seq 下仍能识别）",
        )
        assertFalse(drop(toSeq = 0, rev = 10, watermarkSeq = 12, knownRev = 10), "revision 相等 → 应用")
        assertFalse(drop(toSeq = 0, rev = 11, watermarkSeq = 12, knownRev = 10), "revision 更大 → 应用")
    }

    @Test
    fun unknownRevisionFallsBackToSeqRuleOnly() {
        assertFalse(drop(toSeq = 0, rev = null, watermarkSeq = 12, knownRev = null), "无 revision 证据 → 不误杀")
        assertTrue(drop(toSeq = 5, rev = null, watermarkSeq = 12, knownRev = null), "仍有 seq 证据 → 丢")
    }

    @Test
    fun newerSeqIsNeverDroppedByLowerRevision() {
        // 回归（2026-11-25）：快照顶层 revision 与 state.updated patch.revision 未必同源，
        // 曾把 revision 当独立判据 → toSeq 更新的合法快照被单独判死，连带丢掉
        // pendingInteractions（用户报「iOS 端审批没看到」）。seq 有值就只信 seq。
        assertFalse(drop(toSeq = 9, rev = 1, watermarkSeq = 8, knownRev = 99), "toSeq 已前进 → 不丢")
        assertTrue(drop(toSeq = 0, rev = 1, watermarkSeq = 8, knownRev = 99), "哨兵帧才用 revision 兜底")
    }
}

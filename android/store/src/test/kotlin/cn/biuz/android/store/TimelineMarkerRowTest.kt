package cn.biuz.android.store

// v4 `timelineMarker` 行解析单测（rows.ts:441-449 行 schema + :355-424 payload union）。
//
// 背景：Android 的行 kind 分发此前**没有** timelineMarker 分支——marker 行被静默丢弃，
// 模型切换 / 上下文压缩 / 目标校验 / fork 派生提示在时间轴上全部不可见。
// 本测试锁定：四类可见 marker 的字段投影 + 未知类型「解析但不渲染」+ marker 子对象缺失不崩。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TimelineMarkerRowTest {

    private fun snapshotWith(vararg rows: JSONValue): JSONValue = testObj(
        "logEpoch" to JSONValue.Str("e1"),
        "revision" to testNum(1),
        "rows" to JSONValue.Arr(rows.toList()),
    )

    @Test
    fun compactMarkerProjectsStatusAndOrigin() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    row(
                        1, "timelineMarker",
                        "marker" to testObj(
                            "type" to JSONValue.Str("compact"),
                            "origin" to JSONValue.Str("auto"),
                            "status" to JSONValue.Str("success"),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val marker = f.store.messages("sess-1").single().timelineMarker
        assertNotNull(marker, "timelineMarker 行必须被解析（此前被静默丢弃）")
        assertEquals("compact", marker.type)
        assertEquals("auto", marker.origin)
        assertEquals("success", marker.status)
        assertTrue(marker.isVisible)
    }

    @Test
    fun modelChangeProjectsBothEndsAndSourceLessForm() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    row(
                        1, "timelineMarker",
                        "marker" to testObj(
                            "type" to JSONValue.Str("modelChange"),
                            "fromProvider" to JSONValue.Str("zai"),
                            "fromModel" to JSONValue.Str("GLM-5.3"),
                            "toProvider" to JSONValue.Str("zai"),
                            "toModel" to JSONValue.Str("GLM-5.4"),
                            "toThought" to JSONValue.Str("max"),
                        ),
                    ),
                    row(
                        2, "timelineMarker",
                        "marker" to testObj(
                            // source-less 形态：首次使用的模型事实（rows.ts:384-393 的
                            // z.never().optional() 保证不会只出现一半）
                            "type" to JSONValue.Str("modelChange"),
                            "toProvider" to JSONValue.Str("ccgithub"),
                            "toModel" to JSONValue.Str("deepseek-v4.1-flash"),
                            "toThought" to JSONValue.Str("max"),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val markers = f.store.messages("sess-1").mapNotNull { it.timelineMarker }
        assertEquals(2, markers.size)
        assertEquals("GLM-5.3", markers[0].fromModel)
        assertEquals("GLM-5.4", markers[0].toModel)
        assertNull(markers[1].fromModel, "source-less 形态 fromModel 缺席")
        assertEquals("deepseek-v4.1-flash", markers[1].toModel)
    }

    @Test
    fun goalVerifyUsesOutcomeAndIteration() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    row(
                        1, "timelineMarker",
                        "marker" to testObj(
                            "type" to JSONValue.Str("goalVerify"),
                            // goalVerify 用 outcome 而非 status（rows.ts:400-424）
                            "outcome" to JSONValue.Str("pass"),
                            "iteration" to testNum(3),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val marker = f.store.messages("sess-1").single().timelineMarker!!
        assertEquals("pass", marker.status, "outcome 归位到 status（两种词表共用一个字段）")
        assertEquals(3, marker.iteration)
    }

    @Test
    fun unknownMarkerParsedButNotVisible() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    row(
                        1, "timelineMarker",
                        // worktreeCreated 在 web 的 divider switch 里落 default: null（不渲染）
                        "marker" to testObj(
                            "type" to JSONValue.Str("worktreeCreated"),
                            "path" to JSONValue.Str("/tmp/wt"),
                            "sha" to JSONValue.Str("abc"),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val marker = f.store.messages("sess-1").single().timelineMarker
        assertNotNull(marker, "未知/无 UI 类型也解析（让计数不错位）")
        assertEquals("worktreeCreated", marker.type)
        assertFalse(marker.isVisible, "web default: null 同口径——不渲染")
    }

    @Test
    fun markerRowWithoutMarkerObjectIsDroppedNotCrashed() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = snapshotWith(
                    // 缺 marker 子对象 = 偏斜行：不合成空消息（避免渲染空气泡）
                    row(1, "timelineMarker"),
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(f.store.messages("sess-1").isEmpty(), "缺 marker 的行整行跳过，不产生空消息")
    }
}

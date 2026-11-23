package cn.biuz.android.store

// `state.modelSelection` 读面优先级（2026-11-24 真机取证）：
//
// 新建会话选 ccgithub/deepseek-v4.1-flash → 发送后 `switchModelConfig` 回 accepted，
// 投影里两个键**不一致**：
//   顶层 state.modelSelection   = GLM-5.4（旧值，滞后）
//   state.config.modelSelection = deepseek/deepseek-v4.1-flash（新值，已生效）
// 原实现优先读顶层 → chip 恒旧值。上游权威：`sessionConfigStateSchema.modelSelection`
// 注释「Session 接受并持久化的稀疏选择意图」（session-config.ts:5-7），且
// `switchModelConfig` 走 `record.app.setModel` → `onModelSelected` → config 投影
// （commands/handlers/model-config.ts:76-130）。顶层同名字段不是上游 schema 字段。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ModelSelectionProjectionPriorityTest {

    private fun statePatch(
        topLevel: JSONValue?,
        config: JSONValue?,
    ): JSONValue = testObj(
        "modelSelection" to topLevel,
        "config" to config,
    )

    @Test
    fun configModelSelectionWinsOverStaleTopLevel() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-1")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("e1"),
                    "rows" to JSONValue.Arr(emptyList()),
                ),
            ),
        )
        f.frameSurface.emit(
            "conversation/sess-1",
            frame(
                topic = "conversation/sess-1", subscriptionId = "sub-c-1",
                fromSeq = 2, toSeq = 2,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to statePatch(
                            topLevel = testObj(
                                "providerId" to JSONValue.Str("account:zai-individual-coding-plan"),
                                "modelId" to JSONValue.Str("GLM-5.4"),
                                "options" to testObj("reasoningLevel" to JSONValue.Str("max")),
                            ),
                            config = testObj(
                                "modelSelection" to testObj(
                                    "providerId" to JSONValue.Str("new-provider-5"),
                                    "modelId" to JSONValue.Str("deepseek/deepseek-v4.1-flash"),
                                    "options" to testObj("reasoningLevel" to JSONValue.Str("max")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val sel = f.store.sessionModelSelection("sess-1")
        assertEquals(
            "deepseek/deepseek-v4.1-flash",
            sel?.model,
            "config.modelSelection 是权威源（顶层同名字段滞后，原实现读它 → chip 恒旧值）",
        )
        assertEquals("new-provider-5", sel?.provider)
        assertEquals("max", sel?.thought)
    }

    @Test
    fun topLevelUsedOnlyWhenConfigAbsent() = runTest {
        // 旧桌面兼容：config 无 modelSelection 时回退顶层
        val f = Fixture(this)
        f.store.messages("sess-2")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-2",
            frame(
                topic = "conversation/sess-2", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("e1"),
                    "rows" to JSONValue.Arr(emptyList()),
                ),
            ),
        )
        // 旧桌面：只投影顶层，config 无 modelSelection
        f.frameSurface.emit(
            "conversation/sess-2",
            frame(
                topic = "conversation/sess-2", subscriptionId = "sub-c-1",
                fromSeq = 2, toSeq = 2,
                deltas = listOf(
                    testObj(
                        "op" to JSONValue.Str("state.updated"),
                        "patch" to testObj(
                            "modelSelection" to testObj(
                                "providerId" to JSONValue.Str("p1"),
                                "modelId" to JSONValue.Str("legacy-model"),
                            ),
                            "config" to testObj("provider" to JSONValue.Str("p1")),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("legacy-model", f.store.sessionModelSelection("sess-2")?.model)
    }

    @Test
    fun bothAbsentYieldsNull() = runTest {
        val f = Fixture(this)
        f.store.messages("sess-3")
        advanceUntilIdle()
        f.frameSurface.emit(
            "conversation/sess-3",
            frame(
                topic = "conversation/sess-3", subscriptionId = "sub-c-1",
                fromSeq = 1, toSeq = 1,
                snapshot = testObj(
                    "logEpoch" to JSONValue.Str("e1"),
                    "rows" to JSONValue.Arr(emptyList()),
                ),
            ),
        )
        advanceUntilIdle()
        assertNull(f.store.sessionModelSelection("sess-3"), "两处都缺席 → null（不合成空选择）")
    }
}

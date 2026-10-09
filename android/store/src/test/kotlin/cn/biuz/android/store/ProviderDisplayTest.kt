package cn.biuz.android.store

// 供应商连接状态展示门（2026-10-08 报障「供应商没翻译」+「对话框模型选不了」）。
// 平移自 ios/UnitTests/ProviderDisplayTests.swift（断言语义一一对应）。
//
// 落位说明（被测 API 所在模块 = :store）：
// - iOS 四用例中仅 testHasSelectableModelsDiscriminatesEmptySeed 在 Android 有被测 API：
//   ModelSelectionInfo.hasSelectableModels（StoreProtocols.kt:166，iOS StoreProtocols.swift:553
//   同构直译）与 ModelPlanGroup（StoreProtocols.kt:199 / iOS :581）。
// - 其余三用例（testProviderNameWinsOverBuiltinMapping / testBuiltinIdFallbackMapping /
//   testUnavailableReasonTranslation）的被测 API 在 Android **缺位**：iOS 原文是
//   ModelSettingsView 的 UI 层静态纯函数（SettingsView.swift:817 providerDisplayName /
//   :845 unavailableReasonText / :638 ProviderConnection），Android 全仓 rg
//   （providerDisplayName|unavailableReasonText|ProviderConnection）零命中；平移纪律禁改
//   生产代码，故按 M1 先例（android/README.md 门禁现状「缺 parseInteraction 1 例，
//   Store 层 API 缺位」挂账口径）如实挂账，不虚构替身实现。三用例断言语义存档如下，
//   待展示 API 随 settings UI 里程碑落位后逐字回补：
//   ① providerName 权威回退序：桌面回执 providerName（trim 判空与上游同语义
//      config-service.ts:204——空白串视同缺席）→ 内置六 id 中文映射（逐字全串匹配，
//      【实证·上游仓 shared/src/model-provider-types.ts:7-14 BUILTIN_MODEL_PROVIDER_IDS】）
//      → 未知 id 原样透出（不虚构翻译）：
//      account:zai-individual-coding-plan → "Z.ai 个人套餐"
//      account:zai-team-coding-plan       → "Z.ai 团队套餐"
//      account:zai-start-plan             → "Z.ai 体验套餐"
//      account:bigmodel-individual-coding-plan → "BigModel 个人套餐"
//      account:bigmodel-team-coding-plan       → "BigModel 团队套餐"
//      account:bigmodel-start-plan             → "BigModel 体验套餐"
//   ② 拒因中文化（【实证·上游仓 shared/src/account-provider-state.ts:7-11】四值词表）：
//      not-authenticated → "未认证"、not-connected → "未连接"、
//      credential-failed → "凭据校验失败"、not-entitled → "未订阅该套餐"；
//      null → null；未知拒因原样透出（宽容解析 ≠ 协议事实，§4.3）。
//   ③ 用例替身构造（iOS ProviderDisplayTests.swift:9-16）：
//      ProviderConnection(providerId, providerName?,
//      availability = reason == null ? "available" : "unavailable", unavailableReason = reason?)。
//
// 注意：①的内置映射是**逐字全串匹配**，与 RemoteConversationStore.parseModelSelectionView
// 的套餐分组推名（RemoteConversationStore.kt:2979-2981 contains 前缀族）是两个函数两种口径，
// 回补时不得互相替代。

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDisplayTest {

    /**
     * hasSelectableModels 区分「非 nil 但空」种子态（iOS ProviderDisplayTests.swift:62-70
     * 逐断言直译；Kotlin data class 不可变，Swift var 赋值以 copy 表达）。
     */
    @Test
    fun testHasSelectableModelsDiscriminatesEmptySeed() {
        var selection = ModelSelectionInfo()
        assertFalse(selection.hasSelectableModels, "空实例（overlay 种子态）= 无可选清单")
        selection = selection.copy(models = listOf("GLM-5.3"))
        assertTrue(selection.hasSelectableModels)
        selection = selection.copy(
            models = emptyList(),
            planGroups = listOf(
                ModelPlanGroup(plan = "个人套餐", models = listOf("GLM-5.3")),
            ),
        )
        assertTrue(selection.hasSelectableModels, "仅套餐分组在场也算可选")
    }
}

package cn.biuz.android.store

// `parseModelSelectionView` 的 provider 分组口径（web composerPlanGroups.ts:74-120 /
// modelSelectionGroups.ts:56-77 同构）。
//
// 缺陷背景（2026-11-24 用户报障「新建会话选不到 ccgithub 的 deepseek」）：
// 原实现 `planNameFor` 只认 individual/start/team-coding-plan 三词，其余返 null；
// 返 null 的 provider 其模型只进裸表 `models`，**不进任何 planGroups**——而 UI 在
// planGroups 非空时只渲染分组行，于是自定义 provider（ccgithub）的模型在面板里彻底消失。
// web 侧「非套餐」一律进 others 组照样渲染，标签取 providerName || providerId。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSelectionGroupingTest {

    private fun view(vararg providers: JSONValue): Map<String, JSONValue> = testObj(
        "providers" to JSONValue.Arr(providers.toList()),
    ).objectValue!!

    private fun provider(
        providerId: String,
        providerName: String?,
        vararg models: String,
    ): JSONValue = testObj(
        "providerId" to JSONValue.Str(providerId),
        "providerName" to providerName?.let { JSONValue.Str(it) },
        "models" to JSONValue.Arr(models.map { JSONValue.Str(it) }),
    )

    private val store = RemoteConversationStore(
        transport = FakeConversationTransport(java.util.concurrent.CopyOnWriteArrayList()),
        frameSurface = FakeConversationFrameSurface(java.util.concurrent.CopyOnWriteArrayList()),
        workspace = StoreWorkspaceInfo("/ws"),
        clientId = "c1",
    )

    @Test
    fun customProviderFallsIntoItsOwnGroupInsteadOfVanishing() {
        // ccgithub 是自定义 provider：不是任何套餐，但**必须**可见
        val info = store.parseModelSelectionView(
            view(
                provider("individual-coding-plan-abc", "个人套餐", "GLM-5.4", "GLM-5.3"),
                provider("ccgithub", "ccgithub", "deepseek/deepseek-v4.1-flash"),
            ),
        )
        val groups = info.planGroups.map { it.plan to it.models }
        assertTrue(
            groups.any { it.first == "ccgithub" && it.second == listOf("deepseek/deepseek-v4.1-flash") },
            "自定义 provider 必须有自己的一组（原缺陷：返 null → 不进 planGroups → 面板里消失）：$groups",
        )
        assertTrue(
            groups.any { it.first == "个人套餐" },
            "套餐分组不受影响：$groups",
        )
        // 裸名表仍含全部模型（旧读法兼容）
        assertTrue(info.models.contains("deepseek/deepseek-v4.1-flash"))
        // 复合键表按新分组名记 providerId（switchModelConfig 需要）
        assertEquals(
            "ccgithub",
            info.modelProvidersByPlan["ccgithub|deepseek/deepseek-v4.1-flash"],
        )
    }

    @Test
    fun customProviderWithoutNameFallsBackToProviderId() {
        // web modelSelectionGroups.ts:59 `provider.providerName?.trim() || provider.providerId`
        val info = store.parseModelSelectionView(
            view(provider("my-relay", null, "some-model")),
        )
        assertEquals(listOf("my-relay"), info.planGroups.map { it.plan })
        assertEquals(listOf("some-model"), info.planGroups.single().models)
    }

    @Test
    fun startPlanProviderKeepsItsOwnGroup() {
        // start-plan（体验套餐）在 web 侧同样进 others，不是 individual 套餐
        val info = store.parseModelSelectionView(
            view(
                provider("start-plan-xyz", "体验套餐", "GLM-5.3-Flash"),
                provider("individual-coding-plan-abc", "个人套餐", "GLM-5.4"),
            ),
        )
        val plans = info.planGroups.map { it.plan }
        assertEquals(listOf("体验套餐", "个人套餐"), plans, "两组都在：$plans")
    }
}

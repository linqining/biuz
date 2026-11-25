package cn.biuz.android.app.ui.chat

import cn.biuz.android.store.ModelPlanGroup
import cn.biuz.android.store.ModelSelectionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 批4 二级模型选择器分组装配单测（iOS groupedSectionModel 同构，2026-11-25）：
 * 套餐组原序 + 「其他」组（不在任一套餐组的裸名整组归入——ccgithub/DeepSeek 类
 * 自定义 provider 此前整组消失的根修面）；全空分组退化裸名直出。
 */
class ModelSectionsTest {

    @Test
    fun planGroupsKeepOrderAndOthersGroupAppended() {
        val info = ModelSelectionInfo(
            models = listOf("GLM-5.4", "deepseek-v4.1-flash", "GLM-5.3-Flash"),
            planGroups = listOf(
                ModelPlanGroup(plan = "个人套餐", models = listOf("GLM-5.4", "GLM-5.3-Flash")),
            ),
        )
        val sections = buildModelSections(info)
        assertEquals(2, sections.size)
        assertEquals("个人套餐", sections[0].plan)
        assertEquals(listOf("GLM-5.4", "GLM-5.3-Flash"), sections[0].models)
        // 「其他」组收编未认领裸名（deepseek）
        assertEquals("其他", sections[1].plan)
        assertEquals(listOf("deepseek-v4.1-flash"), sections[1].models)
    }

    @Test
    fun emptyPlanGroupsDegeneratesToBareRows() {
        val info = ModelSelectionInfo(models = listOf("GLM-5.4", "GLM-5.3"))
        val sections = buildModelSections(info)
        assertEquals(2, sections.size)
        assertTrue(sections.all { it.plan.isEmpty() })
        assertEquals(listOf("GLM-5.4", "GLM-5.3"), sections.map { it.models.single() })
    }

    @Test
    fun allClaimedModelsOmitOthersGroup() {
        val info = ModelSelectionInfo(
            models = listOf("GLM-5.4"),
            planGroups = listOf(ModelPlanGroup(plan = "个人套餐", models = listOf("GLM-5.4"))),
        )
        val sections = buildModelSections(info)
        assertEquals(1, sections.size)
        assertEquals("个人套餐", sections.single().plan)
    }
}

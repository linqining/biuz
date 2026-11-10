package cn.biuz.android.app.ui.screen

// P-013 按分组视图 taskGroupSections 单测（task-groups.read 结构驱动；
// web TaskGroupedView 同口径）：
// - taskId ↔ sessions 行 id 对位；组间/组内 updatedAt 降序；
// - 不在任何组的会话归 fallback 尾区；空组不产出 section。

import cn.biuz.android.connection.TaskGroupEntry
import cn.biuz.android.connection.TaskGroupMember
import cn.biuz.android.connection.TaskGroupStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskGroupSectionsTest {

    private fun conversation(id: String, updatedAt: Long) =
        cn.biuz.android.protocol.Conversation(
            id = id, title = "会话 $id", summary = "",
            directory = "", updatedAt = updatedAt,
        )

    @Test
    fun groupsTasksByMemberMapping() {
        val rows = listOf(
            conversation("t1", 100), conversation("t2", 200), conversation("t3", 300),
        )
        val structure = TaskGroupStructure(
            groups = listOf(
                TaskGroupEntry("g1", "前端", "blue"),
                TaskGroupEntry("g2", "后端", "green"),
            ),
            members = listOf(
                TaskGroupMember("g1", "t1"),
                TaskGroupMember("g1", "t3"),
                TaskGroupMember("g2", "t2"),
            ),
        )
        val sections = taskGroupSections(rows, structure.groups, structure.members, "未分组")
        assertEquals(2, sections.size)
        assertEquals("前端", sections[0].title)
        assertEquals(2, sections[0].rows.size)
        assertEquals("后端", sections[1].title)
        assertEquals(1, sections[1].rows.size)
    }

    @Test
    fun groupOrderFollowsGroupsArray() {
        val rows = listOf(conversation("t1", 100), conversation("t2", 200))
        val structure = TaskGroupStructure(
            groups = listOf(
                TaskGroupEntry("g2", "第二组", null),
                TaskGroupEntry("g1", "第一组", null),
            ),
            members = listOf(
                TaskGroupMember("g1", "t1"),
                TaskGroupMember("g2", "t2"),
            ),
        )
        val sections = taskGroupSections(rows, structure.groups, structure.members, "未分组")
        assertEquals(listOf("第二组", "第一组"), sections.map { it.title },
            "组间序 = groups[] 数组声明序（web TaskGroupedView 同口径）")
    }

    @Test
    fun ungroupedTasksFallToTailSection() {
        val rows = listOf(
            conversation("t1", 100), conversation("t2", 200), conversation("t-orphan", 300),
        )
        val structure = TaskGroupStructure(
            groups = listOf(TaskGroupEntry("g1", "前端", null)),
            members = listOf(TaskGroupMember("g1", "t1")),
        )
        val sections = taskGroupSections(rows, structure.groups, structure.members, "未分组")
        assertEquals(2, sections.size)
        assertEquals("前端", sections[0].title)
        assertEquals("未分组", sections[1].title)
        assertTrue(sections[1].rows.map { it.id }.contains("t-orphan"), "t-orphan 应在尾区")
        assertTrue(sections[1].rows.map { it.id }.contains("t2"), "t2 也应在尾区（未分配）")
    }

    @Test
    fun inGroupTasksExcludedFromFallback() {
        val rows = listOf(conversation("t1", 100), conversation("t2", 200))
        val structure = TaskGroupStructure(
            groups = listOf(TaskGroupEntry("g1", "全部", null)),
            members = listOf(TaskGroupMember("g1", "t1"), TaskGroupMember("g1", "t2")),
        )
        val sections = taskGroupSections(rows, structure.groups, structure.members, "未分组")
        // 全部任务都在组内 → 无尾区
        assertEquals(1, sections.size)
        assertTrue(sections[0].rows.size == 2)
    }

    @Test
    fun emptyGroupsProduceNoSection() {
        val rows = listOf(conversation("t1", 100))
        val structure = TaskGroupStructure(
            groups = listOf(TaskGroupEntry("g1", "空组", null)),
            members = emptyList(),
        )
        val sections = taskGroupSections(rows, structure.groups, structure.members, "未分组")
        // 空组不产 section，t1 归尾区
        assertEquals(1, sections.size)
        assertEquals("未分组", sections[0].title)
        assertEquals(1, sections[0].rows.size)
    }
}

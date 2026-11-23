package cn.biuz.android.app.ui.chat

import cn.biuz.android.protocol.ToolCall
import cn.biuz.android.protocol.ToolCallStatus
import cn.biuz.android.protocol.ToolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具卡展示模型（web toolIdentity/toolDisplay/renderers 子集，2026-11-23）。
 * 断言族判定、命令/路径/查询/URL 提取、参数剔除命令键、错误包裹提取与坏 JSON 宽容。
 */
class ToolDisplayModelTest {

    private fun call(
        toolName: String?,
        input: String? = null,
        status: ToolCallStatus = ToolCallStatus.done,
        output: String? = null,
        description: String? = null,
        title: String? = null,
        added: Int? = null,
        removed: Int? = null,
        kind: ToolKind = ToolKind.bash,
    ) = ToolCall(
        id = "t1",
        kind = kind,
        target = input.orEmpty(),
        status = status,
        output = output,
        addedLines = added,
        removedLines = removed,
        toolName = toolName,
        description = description,
        title = title,
    )

    @Test
    fun familiesMatchWebIdentitySubset() {
        assertEquals(ToolFamily.Shell, ToolDisplay.familyOf("Bash"))
        assertEquals(ToolFamily.Shell, ToolDisplay.familyOf("shell_execute"))
        assertEquals(ToolFamily.FileRead, ToolDisplay.familyOf("Read"))
        assertEquals(ToolFamily.FileRead, ToolDisplay.familyOf("Grep"))
        assertEquals(ToolFamily.FileWrite, ToolDisplay.familyOf("Edit"))
        assertEquals(ToolFamily.FileWrite, ToolDisplay.familyOf("MultiEdit"))
        assertEquals(ToolFamily.Search, ToolDisplay.familyOf("Search"))
        assertEquals(ToolFamily.WebFetch, ToolDisplay.familyOf("WebFetch"))
        assertEquals(ToolFamily.Mcp, ToolDisplay.familyOf("mcp__github__search"))
        assertEquals(ToolFamily.Agent, ToolDisplay.familyOf("Task"))
        assertEquals(ToolFamily.Todo, ToolDisplay.familyOf("TodoWrite"))
        assertEquals(ToolFamily.Workflow, ToolDisplay.familyOf("workflow_start"))
        assertEquals(ToolFamily.Generic, ToolDisplay.familyOf("UnknownTool"))
        assertEquals(ToolFamily.Generic, ToolDisplay.familyOf(null))
    }

    @Test
    fun shellCommandExtraction_variantsAndParameters() {
        val plain = ToolDisplay.build(
            call(
                toolName = "Bash",
                input = """{"command":"cd /tmp && ls","description":"列目录"}""",
                description = "列目录",
            ),
        )
        assertEquals(ToolFamily.Shell, plain.family)
        assertEquals("cd /tmp && ls", plain.command)
        assertEquals("cd /tmp && ls", plain.primary)
        assertEquals("列目录", plain.secondary)
        assertNull(plain.parameters, "命令键已单独展示 → 参数块剔除")

        // argv 数组 + bash -lc 解包
        val argv = ToolDisplay.build(
            call(toolName = "Bash", input = """{"command":["bash","-lc","git status --short"]}"""),
        )
        assertEquals("git status --short", argv.command)

        // 额外参数保留（非命令键）
        val extra = ToolDisplay.build(
            call(toolName = "Bash", input = """{"command":"ls","timeout":30}"""),
        )
        assertEquals("timeout = 30", extra.parameters)
    }

    @Test
    fun fileReadAndWrite_pathBasenameAndDiff() {
        val read = ToolDisplay.build(
            call(toolName = "Read", input = """{"file_path":"/Users/mac/proj/README.md"}"""),
        )
        assertEquals(ToolFamily.FileRead, read.family)
        assertEquals("README.md", read.primary)
        assertEquals("/Users/mac/proj/README.md", read.filePath)

        val write = ToolDisplay.build(
            call(
                toolName = "Edit",
                input = """{"filePath":"/Users/mac/proj/src/a.kt","old_string":"a","new_string":"b"}""",
                added = 3,
                removed = 1,
            ),
        )
        assertEquals(ToolFamily.FileWrite, write.family)
        assertEquals("a.kt", write.primary)
        assertEquals(3, write.addedLines)
        assertEquals(1, write.removedLines)
    }

    @Test
    fun searchFetchAndMcpPrimaryTexts() {
        val search = ToolDisplay.build(call(toolName = "Search", input = """{"query":"ToolDisplay"}"""))
        assertEquals("ToolDisplay", search.primary)

        val fetch = ToolDisplay.build(
            call(toolName = "WebFetch", input = """{"url":"https://example.com/doc"}"""),
        )
        assertEquals("https://example.com/doc", fetch.primary)

        val mcp = ToolDisplay.build(call(toolName = "mcp__github__search_repos", input = """{"query":"x"}"""))
        assertEquals("github · search_repos", mcp.primary)
        assertEquals("github · search_repos", ToolDisplay.mcpDisplayName("mcp__github__search_repos"))
        assertNull(ToolDisplay.mcpDisplayName(""))
    }

    @Test
    fun failedStatus_extractsToolUseErrorAndHidesOutput() {
        val failed = ToolDisplay.build(
            call(
                toolName = "Bash",
                input = """{"command":"false"}""",
                status = ToolCallStatus.failed,
                output = "before <tool_use_error>permission denied</tool_use_error> after",
            ),
        )
        assertEquals(ToolPhase.Failed, failed.phase)
        assertEquals("permission denied", failed.errorText)
        assertNull(failed.output, "失败态输出块让位错误块")

        // 非包裹错误：错误文本回落全文
        val plainError = ToolDisplay.build(
            call(
                toolName = "Bash",
                input = """{"command":"false"}""",
                status = ToolCallStatus.failed,
                output = "boom",
            ),
        )
        assertEquals("boom", plainError.errorText)
    }

    @Test
    fun badJsonTolerated_fallsBackToTitleOrToolName() {
        val model = ToolDisplay.build(
            call(toolName = "CustomTool", input = "not-json{", title = "自定义动作"),
        )
        assertEquals(ToolFamily.Generic, model.family)
        assertEquals("自定义动作", model.primary)
        assertNull(model.command)
        assertNull(model.parameters)

        val nameOnly = ToolDisplay.build(call(toolName = "CustomTool", input = ""))
        assertEquals("CustomTool", nameOnly.primary)
    }

    @Test
    fun phasesMapFromStatus() {
        assertEquals(ToolPhase.Running, ToolDisplay.phaseOf(ToolCallStatus.running))
        assertEquals(ToolPhase.Done, ToolDisplay.phaseOf(ToolCallStatus.done))
        assertEquals(ToolPhase.Failed, ToolDisplay.phaseOf(ToolCallStatus.failed))
        assertTrue(ToolDisplay.build(call("Bash", "{}", status = ToolCallStatus.running)).phase == ToolPhase.Running)
    }
}

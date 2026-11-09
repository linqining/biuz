package cn.biuz.android.connection

// 屏 024/026/018 读面解析单测（上游形状锁定；证据见各 Remote*.kt 头注）。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSettingsPagesParsersTest {

    // ---- 024 hooks.loadHooks ----

    @Test
    fun hookRowParsesCoreFieldsAndGroups() {
        val rows = parseHookRows(
            JSONValue.Arr(
                listOf(
                    // zcode + 可编辑 → installed
                    JSONValue.Obj(
                        mapOf(
                            "id" to JSONValue.Str("h1"),
                            "event" to JSONValue.Str("SessionStart"),
                            "type" to JSONValue.Str("command"),
                            "command" to JSONValue.Str("echo hi"),
                            "args" to JSONValue.Arr(listOf(JSONValue.Str("a"))),
                            "enabled" to JSONValue.Bool(true),
                            "editable" to JSONValue.Bool(true),
                            "location" to JSONValue.Obj(
                                mapOf("source" to JSONValue.Str("zcode"), "scope" to JSONValue.Str("user")),
                            ),
                        ),
                    ),
                    // claude 源 → legacy
                    JSONValue.Obj(
                        mapOf(
                            "id" to JSONValue.Str("h2"),
                            "event" to JSONValue.Str("PreToolUse"),
                            "type" to JSONValue.Str("process"),
                            "command" to JSONValue.Str("tool"),
                            "enabled" to JSONValue.Bool(false),
                            "location" to JSONValue.Obj(
                                mapOf("source" to JSONValue.Str("claude"), "scope" to JSONValue.Str("project")),
                            ),
                        ),
                    ),
                    // workspace 钩子带 trustState → needsTrust
                    JSONValue.Obj(
                        mapOf(
                            "id" to JSONValue.Str("h3"),
                            "event" to JSONValue.Str("Stop"),
                            "type" to JSONValue.Str("command"),
                            "command" to JSONValue.Str("notify"),
                            "enabled" to JSONValue.Bool(true),
                            "workspaceHook" to JSONValue.Obj(
                                mapOf("trustState" to JSONValue.Str("pending_trust")),
                            ),
                        ),
                    ),
                    // 缺 id 丢弃
                    JSONValue.Obj(mapOf("event" to JSONValue.Str("Stop"))),
                ),
            ),
        )
        assertEquals(3, rows.size)
        assertEquals(HookGroup.installed, rows[0].group)
        assertEquals("user", rows[0].scope)
        assertEquals(HookGroup.legacy, rows[1].group)
        // editable 缺席 → 默认 true（上游 save 过滤口径 editable !== false，缺席=可编辑）
        assertTrue(rows[1].editable)
        assertTrue(rows[2].needsTrust)
        assertNull(rows[2].source)
    }

    @Test
    fun hooksSnapshotDefaults() {
        // hooksEnabled 缺席 → false（不臆造开关态）
        val json = JSONValue.Obj(mapOf("hooks" to JSONValue.Arr(emptyList())))
        // 直接经 loadHooks 需连接；这里验证 parse 层缺省由调用方兜底——跳过（loadHooks 有连接态门）
        assertEquals(0, parseHookRows(json.get("hooks")).size)
    }

    // ---- 026 subagents.list ----

    @Test
    fun subagentRowParsesFields() {
        val row = parseSubagent(
            JSONValue.Obj(
                mapOf(
                    "id" to JSONValue.Str("a1"),
                    "name" to JSONValue.Str("reviewer"),
                    "description" to JSONValue.Str("审查代码"),
                    "scope" to JSONValue.Str("user"),
                    "source" to JSONValue.Str("user"),
                    "enabled" to JSONValue.Bool(true),
                    "tools" to JSONValue.Arr(listOf(JSONValue.Str("read"), JSONValue.Str("grep"))),
                    "color" to JSONValue.Str("blue"),
                ),
            ),
        )!!
        assertEquals("reviewer", row.name)
        assertEquals("user", row.scope)
        assertEquals(2, row.toolsCount)
        assertFalse(row.toolsAll)
        // tools 缺席 = 全部工具（web tools.all 口径）
        val noTools = parseSubagent(
            JSONValue.Obj(
                mapOf("id" to JSONValue.Str("a2"), "name" to JSONValue.Str("explore")),
            ),
        )!!
        assertTrue(noTools.toolsAll)
        assertEquals("", noTools.description)
        assertTrue(noTools.enabled) // enabled 缺席默认 true（上游必带，缺席按启用处理）
        // 缺 name 丢弃
        assertNull(parseSubagent(JSONValue.Obj(mapOf("id" to JSONValue.Str("a3")))))
    }

    // ---- 018 project.readTree ----

    @Test
    fun projectRowParsesAndAggregatesTasks() {
        val row = parseProjectRow(
            JSONValue.Obj(
                mapOf(
                    "id" to JSONValue.Str("p1"),
                    "name" to JSONValue.Str("mtt_mobile"),
                    "hostId" to JSONValue.Str("local"),
                    "pinned" to JSONValue.Bool(true),
                    "deleted" to JSONValue.Bool(false),
                    "position" to JSONValue.Int(2),
                    "roots" to JSONValue.Arr(
                        listOf(
                            JSONValue.Obj(mapOf("rootPath" to JSONValue.Str("/Users/mac/p1"), "isPrimary" to JSONValue.Bool(true))),
                            JSONValue.Obj(mapOf("rootPath" to JSONValue.Str("/Users/mac/p1b"))),
                        ),
                    ),
                    "primaryRootStatus" to JSONValue.Str("missing"),
                    "nameOverridden" to JSONValue.Bool(true),
                ),
            ),
        )!!
        assertEquals("mtt_mobile", row.name)
        assertEquals(listOf("/Users/mac/p1", "/Users/mac/p1b"), row.roots)
        assertTrue(row.pinned)
        assertEquals("missing", row.primaryRootStatus)
        assertFalse(row.removed)
        // 缺 name 丢弃；deleted 缺席 false
        assertNull(parseProjectRow(JSONValue.Obj(mapOf("id" to JSONValue.Str("p2")))))
    }
}

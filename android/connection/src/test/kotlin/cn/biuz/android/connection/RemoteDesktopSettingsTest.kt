package cn.biuz.android.connection

// 桌面设置回执宽容解析单测（屏 028；iOS parseEntries 三形态同口径）：
// - A 顶层数组 [{key|name|id, value…}]；B 包裹对象（object/array 两种内层）；
//   C 整对象兜底 {key: value}；
// - 只收 bool/int/double/string 标量（对象/数组/空值跳过不臆造渲染）；
// - readonly 宽容样键（readonly / readOnly / editable=false）。

import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteDesktopSettingsTest {

    @Test
    fun parsesTopLevelArrayEntries() {
        val json = JSONValue.Arr(
            listOf(
                JSONValue.Obj(mapOf("key" to JSONValue.Str("locale"), "value" to JSONValue.Str("zh-CN"))),
                JSONValue.Obj(mapOf("name" to JSONValue.Str("memoryEnabled"), "value" to JSONValue.Bool(true))),
                JSONValue.Obj(mapOf("id" to JSONValue.Str("desktopZoomLevel"), "value" to JSONValue.Double(1.5))),
            ),
        )
        val rows = parseDesktopSettingEntries(json)
        assertEquals(3, rows.size)
        assertEquals("locale", rows[0].key)
        assertEquals(JSONValue.Str("zh-CN"), rows[0].value)
        assertEquals("memoryEnabled", rows[1].key)
        assertEquals("desktopZoomLevel", rows[2].key)
        assertFalse(rows.all { it.readonly })
    }

    @Test
    fun parsesWrappedObjectAndArrayForms() {
        // B-1 包裹 object：{settings: {k: v}}
        val wrappedObject = parseDesktopSettingEntries(
            JSONValue.Obj(
                mapOf(
                    "settings" to JSONValue.Obj(
                        mapOf(
                            "httpProxy" to JSONValue.Str("http://127.0.0.1:7890"),
                            "taskAutoArchiveEnabled" to JSONValue.Bool(false),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, wrappedObject.size)

        // B-2 包裹 array：{items: [{key, value…}]}
        val wrappedArray = parseDesktopSettingEntries(
            JSONValue.Obj(
                mapOf(
                    "items" to JSONValue.Arr(
                        listOf(
                            JSONValue.Obj(
                                mapOf(
                                    "key" to JSONValue.Str("taskAutoArchiveOlderThanDays"),
                                    "value" to JSONValue.Int(30),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, wrappedArray.size)
        assertEquals("taskAutoArchiveOlderThanDays", wrappedArray[0].key)
        assertEquals(JSONValue.Int(30), wrappedArray[0].value)
    }

    @Test
    fun fallsBackToWholeObjectMapping() {
        // C：包裹键全缺席 → 整对象视为 {key: value}
        val rows = parseDesktopSettingEntries(
            JSONValue.Obj(
                mapOf(
                    "terminalFontFamily" to JSONValue.Str("JetBrains Mono"),
                    "keepAwakeWhileRunning" to JSONValue.Bool(true),
                    "notScalar" to JSONValue.Obj(mapOf("nested" to JSONValue.Str("skip"))),
                ),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals("terminalFontFamily", rows[0].key)
        assertEquals("keepAwakeWhileRunning", rows[1].key)
    }

    @Test
    fun skipsNonScalarValues() {
        val rows = parseDesktopSettingEntries(
            JSONValue.Obj(
                mapOf(
                    "aBool" to JSONValue.Bool(true),
                    "anInt" to JSONValue.Int(3),
                    "aDouble" to JSONValue.Double(0.5),
                    "aStr" to JSONValue.Str("x"),
                    "anArray" to JSONValue.Arr(emptyList()),
                    "anObject" to JSONValue.Obj(emptyMap()),
                    "aNull" to JSONValue.Null,
                ),
            ),
        )
        assertEquals(listOf("aBool", "anInt", "aDouble", "aStr"), rows.map { it.key })
    }

    @Test
    fun honorsReadonlyFlagVariants() {
        val rows = parseDesktopSettingEntries(
            JSONValue.Arr(
                listOf(
                    JSONValue.Obj(
                        mapOf(
                            "key" to JSONValue.Str("dataBaseDir"),
                            "value" to JSONValue.Str("/data"),
                            "readonly" to JSONValue.Bool(true),
                        ),
                    ),
                    JSONValue.Obj(
                        mapOf(
                            "key" to JSONValue.Str("shortcutBindings"),
                            "value" to JSONValue.Str("{}"),
                            "readOnly" to JSONValue.Bool(true),
                        ),
                    ),
                    JSONValue.Obj(
                        mapOf(
                            "key" to JSONValue.Str("desktopWindowSize"),
                            "value" to JSONValue.Str("1200x800"),
                            "editable" to JSONValue.Bool(false),
                        ),
                    ),
                    JSONValue.Obj(
                        mapOf("key" to JSONValue.Str("locale"), "value" to JSONValue.Str("zh-CN")),
                    ),
                ),
            ),
        )
        assertTrue(rows.first { it.key == "dataBaseDir" }.readonly)
        assertTrue(rows.first { it.key == "shortcutBindings" }.readonly)
        assertTrue(rows.first { it.key == "desktopWindowSize" }.readonly)
        assertFalse(rows.first { it.key == "locale" }.readonly)
    }

    @Test
    fun returnsEmptyForNullAndGarbage() {
        assertTrue(parseDesktopSettingEntries(null).isEmpty())
        assertTrue(parseDesktopSettingEntries(JSONValue.Null).isEmpty())
        assertTrue(parseDesktopSettingEntries(JSONValue.Str("nope")).isEmpty())
        // 数组条目缺 key 或缺 value 的行跳过
        val partial = parseDesktopSettingEntries(
            JSONValue.Arr(
                listOf(
                    JSONValue.Obj(mapOf("key" to JSONValue.Str("missingValue"))),
                    JSONValue.Obj(mapOf("value" to JSONValue.Str("missingKey"))),
                    JSONValue.Str("notAnObject"),
                ),
            ),
        )
        assertTrue(partial.isEmpty())
    }
}

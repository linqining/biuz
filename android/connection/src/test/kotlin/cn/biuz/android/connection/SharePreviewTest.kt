package cn.biuz.android.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 009 分享预览：回执解析宽容 + 深链 code 解析（iOS SharePreviewStore / web 分享页同口径）。 */
class SharePreviewTest {

    @Test
    fun parsesMetaAndRowsWithTolerantTextKeys() {
        val body = """
            {"share":{"title":"三端对比","owner":{"displayName":"旅行者0554"},"createdAt":1794000000000},
             "rows":[
               {"kind":"userInput","text":"请对比三端"},
               {"kind":"assistantText","summaryText":"结论如下…"},
               {"kind":"toolCall","inputText":"{\"cmd\":\"ls\"}"},
               {"kind":"weird"},
               "not-an-object"
             ]}
        """.trimIndent()
        val preview = SharePreviewClient.parse(body)!!
        assertEquals("三端对比", preview.meta.title)
        assertEquals("旅行者0554", preview.meta.ownerName)
        assertEquals(3, preview.rows.size, "text/inputText/summaryText 三键都要认")
        assertEquals(true, preview.rows[0].isUser)
        assertEquals(2, preview.unsupportedRowCount, "缺文本行与非对象行计入不支持，但不整份失败")
    }

    @Test
    fun nonObjectBodyReturnsNull() {
        assertNull(SharePreviewClient.parse("[]"))
        assertNull(SharePreviewClient.parse("not json"))
        assertNull(SharePreviewClient.parse(null))
    }

    @Test
    fun shareCodeFromLinks() {
        assertEquals("abc123", SharePreviewClient.shareCodeFrom("zcode://share/abc123"))
        assertEquals("abc123", SharePreviewClient.shareCodeFrom("https://zcode.z.ai/share/abc123"))
        assertEquals("abc123", SharePreviewClient.shareCodeFrom("https://zcode.z.ai/cn/share/abc123?x=1"))
        assertNull(SharePreviewClient.shareCodeFrom("https://zcode.z.ai/cn/share/callback?code=1&state=2"),
            "回调路径不是分享 code")
        assertNull(SharePreviewClient.shareCodeFrom("zcode://share"))
        assertNull(SharePreviewClient.shareCodeFrom(null))
    }
}

package cn.biuz.android.store

import kotlin.test.Test
import kotlin.test.assertEquals

/** 占位单测：验证 store 模块可编译、JUnit5 平台已接线。 */
class StorePlaceholderTest {
    @Test
    fun placeholderIsPresent() {
        assertEquals("store", StorePlaceholder.MODULE)
    }
}

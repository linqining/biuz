package cn.biuz.android.relay

import kotlin.test.Test
import kotlin.test.assertEquals

/** 占位单测：验证 relay 模块可编译、JUnit5 平台已接线。 */
class RelayPlaceholderTest {
    @Test
    fun placeholderIsPresent() {
        assertEquals("relay", RelayPlaceholder.MODULE)
    }
}

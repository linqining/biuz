package cn.biuz.android.teststub

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 占位单测：验证 teststub 模块可编译，且 JUnit5 平台 + kotlinx-coroutines-test 已接线
 * （替身服务器场景单测的前置基建）。
 */
class TestStubPlaceholderTest {
    @Test
    fun placeholderIsPresent() = runTest {
        assertEquals("teststub", TestStubPlaceholder.MODULE)
    }
}

package cn.biuz.android.protocol

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 占位单测：验证模块可编译，且 JUnit5 平台 + kotlinx-coroutines-test 已接线
 * （M1 平移 ProtocolSchemaTests/CommandFactoryTests 等协程单测的前置基建）。
 */
class BootstrapTest {
    @Test
    fun bootstrapPlaceholderIsPresent() = runTest {
        assertEquals("protocol", Bootstrap.MODULE)
    }
}

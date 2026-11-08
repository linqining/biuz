package cn.biuz.android.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 008 版本守卫纯逻辑（web webRemoteControlVersion.ts:26-52 同口径）。 */
class VersionGuardTest {

    @Test
    fun mismatchReturnsActualDesktopVersion() {
        assertEquals("3.17.2", VersionGuard.mismatch(linkVersion = "3.16.0", desktopVersion = "3.17.2"))
    }

    @Test
    fun sameVersionIsNotMismatch() {
        assertNull(VersionGuard.mismatch("3.17.2", "3.17.2"))
        assertNull(VersionGuard.mismatch(" 3.17.2 ", "3.17.2"), "应 trim 后比较")
    }

    @Test
    fun missingEitherSideNeverBlocks() {
        // 旧桌面不带版本 / 手填链接无版本 → 不拦（宁可放过不误拦）
        assertNull(VersionGuard.mismatch(null, "3.17.2"))
        assertNull(VersionGuard.mismatch("", "3.17.2"))
        assertNull(VersionGuard.mismatch("3.16.0", null))
        assertNull(VersionGuard.mismatch("3.16.0", "   "))
    }
}

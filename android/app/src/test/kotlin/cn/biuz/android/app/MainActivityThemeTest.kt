package cn.biuz.android.app

import cn.biuz.android.app.settings.AppAppearance
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 外观偏好 → 深浅档解析（2026-11-24 朱批默认浅色）：固定档不随系统、system 档随系统。
 * 纯函数直测（不实例化 Activity）；同时锁定「系统深色 + 默认浅色」这一真机回归场景。
 */
class MainActivityThemeTest {

    @Test
    fun fixedAppearanceIgnoresSystem() {
        assertFalse(MainActivity.resolveDarkTheme(AppAppearance.LIGHT, systemDark = true))
        assertTrue(MainActivity.resolveDarkTheme(AppAppearance.DARK, systemDark = false))
    }

    @Test
    fun systemAppearanceFollowsSystem() {
        assertFalse(MainActivity.resolveDarkTheme(AppAppearance.SYSTEM, systemDark = false))
        assertTrue(MainActivity.resolveDarkTheme(AppAppearance.SYSTEM, systemDark = true))
    }

    @Test
    fun defaultIsLightEvenWhenSystemIsDark() {
        // 未设置偏好时 AppSettingsStore 给 LIGHT：系统深色下 App 仍为浅色（状态栏图标须随之为深色）
        val stored = cn.biuz.android.app.settings.AppAppearance.fromStored(null)
        assertFalse(MainActivity.resolveDarkTheme(stored, systemDark = true))
    }
}

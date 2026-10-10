package cn.biuz.android.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置装配面单测（M2 设置里程碑）：开发者模式持久化 + -ZCodeDevMode 有效口径 +
 * 语言档位映射回灌（iOS AppSettingsModel.effectiveDeveloperMode / AppLanguagePreference
 * 单测对位；SharedPreferences 面以内存 fake 承接）。
 */
class AppSettingsStoreTest {

    private class FakePersistence : AppSettingsPersistence {
        var developerMode: Boolean = false
        var language: String? = null
        override fun loadDeveloperMode(): Boolean = developerMode
        override fun saveDeveloperMode(enabled: Boolean) {
            developerMode = enabled
        }
        override fun loadLanguage(): String? = language
        override fun saveLanguage(raw: String?) {
            language = raw
        }
    }

    // MARK: 开发者模式

    @Test
    fun developerMode_defaultsOff_andPersistsOnToggle() {
        val persistence = FakePersistence()
        val store = AppSettingsStore(persistence)
        assertFalse(store.developerMode.value, "默认简洁态：开发者模式缺省关（§5-17）")
        assertFalse(store.effectiveDeveloperMode)

        store.setDeveloperMode(true)
        assertTrue(store.developerMode.value)
        assertTrue(store.effectiveDeveloperMode)
        assertTrue(persistence.developerMode, "翻转落持久化（重启后仍在）")
    }

    @Test
    fun effectiveDeveloperMode_startupArgumentOverridesOff() {
        val store = AppSettingsStore(FakePersistence(), developerModeStartupArgument = true)
        assertFalse(store.developerMode.value, "持久化位仍关")
        assertTrue(store.effectiveDeveloperMode, "-ZCodeDevMode 在场即有效（iOS 同口径）")
    }

    @Test
    fun brandTapGate_sevenTapsTriggerOnceThenReset() {
        val gate = BrandTapGate()
        repeat(6) { index ->
            assertFalse(gate.tap(), "第 ${index + 1} 次不触发")
        }
        assertEquals(6, gate.count)
        assertTrue(gate.tap(), "第 7 次触发翻转")
        assertEquals(0, gate.count, "触发即清零（iOS handleBrandTap 同契约）")
        // 清零后重新计数
        assertFalse(gate.tap())
        assertEquals(1, gate.count)
    }

    @Test
    fun brandTapGate_noTimeoutWindow_betweenSessionsStillCounts() {
        // 无超时窗：逐次点击均计数（iOS SettingsView 品牌行注释原文口径）
        val gate = BrandTapGate()
        repeat(5) { gate.tap() }
        gate.reset()
        assertEquals(0, gate.count)
    }

    // MARK: 语言偏好

    @Test
    fun language_roundTripPersists() {
        val persistence = FakePersistence()
        val store = AppSettingsStore(persistence)
        assertEquals(AppLanguagePreference.SYSTEM, store.language.value, "缺省跟随系统")

        store.setLanguage(AppLanguagePreference.ENGLISH)
        assertEquals(AppLanguagePreference.ENGLISH, store.language.value)
        assertEquals("en", persistence.language)

        store.setLanguage(AppLanguagePreference.CHINESE)
        assertEquals("zh-Hans", persistence.language)

        // 回灌：存档词反解一致
        assertEquals(AppLanguagePreference.CHINESE, AppLanguagePreference.fromStored("zh-Hans"))
        assertEquals(AppLanguagePreference.ENGLISH, AppLanguagePreference.fromStored("en"))
        assertEquals(AppLanguagePreference.SYSTEM, AppLanguagePreference.fromStored("system"))
        assertEquals(AppLanguagePreference.SYSTEM, AppLanguagePreference.fromStored(null))
        assertEquals(AppLanguagePreference.SYSTEM, AppLanguagePreference.fromStored("fr"), "未知词宽容回 system")
    }

    @Test
    fun language_systemLocalePrefixMapping() {
        assertEquals(AppLanguagePreference.CHINESE, AppLanguagePreference.fromSystemLanguage("zh"))
        assertEquals(AppLanguagePreference.CHINESE, AppLanguagePreference.fromSystemLanguage("zh-Hans-CN"))
        assertEquals(AppLanguagePreference.ENGLISH, AppLanguagePreference.fromSystemLanguage("en-US"))
        assertEquals(AppLanguagePreference.SYSTEM, AppLanguagePreference.fromSystemLanguage("fr"))
        assertEquals(AppLanguagePreference.SYSTEM, AppLanguagePreference.fromSystemLanguage(null))
    }
}

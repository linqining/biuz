package cn.biuz.android.app.ui.screen

import cn.biuz.android.connection.OAuthProviderId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 登录平台切换（2026-11-24 用户裁决：把「先跳一页选平台」收进主按钮下拉箭头）。
 * 锁定默认档与两项映射——这是本次交互简化能被 E2E 之外的单测盯住的纯逻辑面
 * （Compose 层的菜单开合/选中在真机目检里验证）。
 */
class LoginProviderTest {

    @Test
    fun defaultProviderIsZai() {
        assertEquals(OAuthProviderId.ZAI, LoginProvider.entries.first().id)
        assertEquals(2, LoginProvider.entries.size, "界面只提供两个平台：全球 Z.ai / 中国 BigModel")
    }

    @Test
    fun providerMapsToDistinctOAuthProviders() {
        val ids = LoginProvider.entries.map { it.id }
        assertEquals(listOf(OAuthProviderId.ZAI, OAuthProviderId.BIGMODEL), ids)
        assertEquals(ids.size, ids.distinct().size, "两个平台不得映射到同一 provider")
    }
}

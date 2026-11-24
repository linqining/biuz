package cn.biuz.android.app.ui.theme

// 动效令牌 + 印系色令牌回归门（2026-11-24 UIUX 审查报告 A/B 批次）。
//
// 两条纪律不能靠肉眼守：
// 1) 落印五个时长必须与 brand/seal/motion-spec.html 逐字一致——改了就是偏离品牌；
// 2) 调色板不得再出现品牌四色之外的「标识色」——violet 曾以 #7C3AED/#C792FF 藏在
//    调色板和 Material3 tertiary 里（审查报告 P0-②），删掉后必须有测试挡住回归。

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionAndPaletteTest {

    @Test
    fun stampDurationsMatchMotionSpecVerbatim() {
        // brand/seal/motion-spec.html：落印 460ms · 停驻 700ms · 离场 180ms ·
        // 印泥外溢 520ms（延迟 180ms）
        assertEquals(460, BiuZMotion.stamp, "落印总时长")
        assertEquals(700, BiuZMotion.stampHold, "停驻")
        assertEquals(180, BiuZMotion.stampExit, "离场")
        assertEquals(520, BiuZMotion.ring, "印泥外溢")
        assertEquals(180L, BiuZMotion.ringDelay, "外溢延迟")
    }

    @Test
    fun pressDurationsAreShorterThanStamp() {
        // 按钮按压是从落印语言派生的**收窄**版：不做过冲、时长远短于落印，
        // 否则「过冲」这个签名会被每个按钮稀释掉（报告 §4.3）
        assertTrue(BiuZMotion.pressIn < BiuZMotion.stamp, "按下必须比落印短")
        assertTrue(BiuZMotion.pressOut < BiuZMotion.stamp, "回弹必须比落印短")
        assertTrue(BiuZMotion.pressIn < BiuZMotion.pressOut, "压下比回弹快（压实手感）")
        assertEquals(0.96f, BiuZMotion.pressedScale, "按压缩放 0.96（再小像按坏）")
    }

    @Test
    fun brandColorsAreExactlyTheFourSealTokens() {
        // 品牌四色（brand/seal/README.md:39）
        assertEquals(Color(0xFFD2402E), BiuZLightPalette.seal, "朱磦")
        assertEquals(Color(0xFF17181A), BiuZLightPalette.text, "墨")
        assertEquals(Color(0xFFF7F3EC), BiuZLightPalette.bg, "纸")
        // 钤灰没有独立令牌位（由 text2/text3 的墨/纸 alpha 承载），此处只锁前三色
    }

    @Test
    fun sealStaysVermilionInBothTiers() {
        // violet 字段已从调色板删除（编译期不可能再引用）；这里锁运行时值：
        // 两档的 seal 都必须是朱磦系，不能回退成紫。
        assertEquals(Color(0xFFD2402E), BiuZDarkPalette.seal)
        assertEquals(Color(0xFFB03020), BiuZDarkPalette.sealPress)
    }
}

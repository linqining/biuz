package cn.biuz.android.app.ui.theme

// 排版阶守卫（2026-11-24 UIUX 审查 C 批次）。
//
// 修复前的量化事实：全仓 504 处硬编码 `fontSize`、20 种字号，其中 6 个 x.5 半档共 108 处
// （9.5/10.5/11.5/12.5/13.5/14.5/15.5）——差 0.5sp 在 400+ ppi 屏肉眼不可辨，却多出 6 个样式。
//
// 本测试直接扫源码，任何**半档**回流即失败（含 `.5` 形式）。
// 为什么不在此断言「只有 8 档」：字形尺寸（emoji/箭头/图标字符）本就不属排版阶，
// 见 Type.kt 的 BiuZGlyph；用正则只挡半档是最稳且无误伤的口径。
//
// 目录约定：Gradle 单测的工作目录是模块目录（android/app），故源码根为 src/main/kotlin。
// 目录缺失时跳过（不误报）——CI/其它工作目录下调用的场景。

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypographyScaleGuardTest {

    private fun sourceRoot(): File? {
        val candidates = listOf(
            File("src/main/kotlin"),                 // android/app（常规）
            File("app/src/main/kotlin"),             // android/（仓库根启动）
            File("android/app/src/main/kotlin"),     // 仓库根
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    @Test
    fun noHalfStepFontSizes() {
        val root = sourceRoot()
        if (root == null) {
            println("TypographyScaleGuardTest: 源码目录不在预期位置，跳过（非失败）")
            return
        }
        // 半档：fontSize = X.5.sp（X 任意整数）
        val halfStep = Regex("""fontSize\s*=\s*\d+\.5\.sp""")
        val offenders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (halfStep.containsMatchIn(line)) {
                    offenders.add("${f.path}:${i + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "文本字号只允许整数档（见 Type.kt BiuZFont）；x.5 半档会制造无系统的样式：\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun fontTokenScaleIsAscendingAndSmall() {
        val steps = listOf(
            BiuZFont.mono, BiuZFont.micro, BiuZFont.caption,
            BiuZFont.bodyCompact, BiuZFont.body, BiuZFont.subtitle,
            BiuZFont.title, BiuZFont.display,
        )
        assertTrue(steps == steps.sorted(), "文本档位必须递增：$steps")
        assertTrue(steps.size <= 8, "文本档位不得超过 8 档（超过说明又在随手加字号）：$steps")
        assertTrue(steps.distinct().size == steps.size, "档位不得重复：$steps")
    }

    @Test
    fun noBareAlphaMagicNumbers() {
        val root = sourceRoot() ?: run {
            println("TypographyScaleGuardTest: 源码目录不在预期位置，跳过（非失败）")
            return
        }
        // 修饰色透明度只允许 5 档语义值（BiuZAlpha）；裸写 0.37f 之类即失败。
        // 例外：Color.Transparent / alpha = 1f / 0f 的完全透明/不透明不在此列。
        val bare = Regex("""copy\(\s*alpha\s*=\s*[0-9]+\.?[0-9]*f?\s*\)""")
        val offenders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (bare.containsMatchIn(line)) offenders.add("${f.path}:${i + 1}: ${line.trim()}")
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "修饰色透明度只允许 BiuZAlpha 的 5 档（faint/subtle/half/dense/nearSolid）：\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun noBarePaddingLiterals() {
        val root = sourceRoot() ?: run {
            println("TypographyScaleGuardTest: 源码目录不在预期位置，跳过（非失败）")
            return
        }
        // padding 只允许 BiuZSpacing 令牌（8 节奏档 + 2 布局偏移档）。
        // 例外：Modifier.padding 的 PaddingValues(...) 形式不在本正则内（另算）。
        val bare = Regex("""\.padding\([^()]*?[0-9]+\.dp[^()]*?\)""")
        val offenders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (bare.containsMatchIn(line)) offenders.add("${f.path}:${i + 1}: ${line.trim()}")
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "padding 只允许 BiuZSpacing 令牌（禁裸 dp 字面量）：\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun spacingScaleIsAscendingWithTwoOffsetTiers() {
        val rhythm = listOf(
            BiuZSpacing.xxs, BiuZSpacing.xs, BiuZSpacing.tiny, BiuZSpacing.sm,
            BiuZSpacing.md, BiuZSpacing.lg, BiuZSpacing.xl, BiuZSpacing.xxl,
        )
        assertTrue(rhythm == rhythm.sorted(), "节奏档必须递增：$rhythm")
        assertEquals(8, rhythm.distinct().size, "节奏档恒 8 档：$rhythm")
        // 布局偏移档独立于节奏档（保值，不参与 2 的幂收敛）
        assertTrue(BiuZSpacing.offsetLg > BiuZSpacing.xxl, "偏移档必须大于最大节奏档")
        assertTrue(BiuZSpacing.offsetXl > BiuZSpacing.offsetLg)
    }

    @Test
    fun alphaScaleHasFiveSemanticLevels() {
        val levels = listOf(
            BiuZAlpha.faint, BiuZAlpha.subtle, BiuZAlpha.half,
            BiuZAlpha.dense, BiuZAlpha.nearSolid,
        )
        assertTrue(levels == levels.sorted(), "alpha 档位必须递增：$levels")
        assertEquals(5, levels.distinct().size, "语义 alpha 恒 5 档：$levels")
    }

    @Test
    fun glyphScaleIsSeparateFromTextScale() {
        // 字形阶不受 8 档限制，但必须与文本阶分开命名空间（防止有人把 26sp 图标并进标题档）
        assertTrue(BiuZGlyph.lg > BiuZFont.display, "空态图标（26）应大于页面标题（22）")
        assertTrue(BiuZGlyph.lg !in listOf(BiuZFont.display, BiuZFont.title))
    }
}

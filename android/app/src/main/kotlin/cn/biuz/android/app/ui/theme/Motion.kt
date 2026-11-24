package cn.biuz.android.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

// 朱批动效令牌（唯一来源；数值对齐 brand/seal/motion-spec.html）
//
// 品牌动效只有一种缓动：cubic-bezier(.22,.68,.32,1)。落印的签名是「过冲后收束」
// （1.6→0.96→1.04→1.0）——**过冲只留给落印**，别处一律「压实」不做过冲，
// 否则签名会被稀释成到处都是的小抖动。
//
// 两套时长，语义不同：
// - 落印族（stamp/stampHold/stampExit/ring/ringDelay）＝ motion-spec 逐字值，
//   只服务决议面（SealStamp.kt）；
// - 按压族（pressIn/pressOut）＝ 从落印语言派生的按钮反馈，见
//   docs/Android-UIUX与交互审查报告-2026-11-24.md §4.3。
object BiuZMotion {
    /** 全品牌唯一缓动 cubic-bezier(.22,.68,.32,1) */
    val emphasized: Easing = CubicBezierEasing(0.22f, 0.68f, 0.32f, 1f)

    // ── 落印（motion-spec） ──
    const val stamp = 460
    const val stampHold = 700
    const val stampExit = 180
    const val ring = 520
    const val ringDelay = 180L

    // ── 按压（按钮/芯片/行） ──
    /** 按下：scale 1.00 → 0.96（压缩感＝压实，不是水波） */
    const val pressIn = 90
    /** 松开：0.96 → 1.00 */
    const val pressOut = 140

    /** 按压缩放目标值。0.96 是「压实」而非「缩小」——再小就像按坏。 */
    const val pressedScale = 0.96f
}

/**
 * 系统动画时长倍率为 0（开发者选项/无障碍「关闭动画」）→ 跳过一切位移类动画，
 * 只保留色阶变化（motion-spec「减弱动态：全部停用 · 终态直出」）。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrDefault(1f) == 0f
    }
}

/**
 * 按压实（按钮/芯片/行唯一入口）。
 *
 * 用法：`Modifier.pressableScale()`——内部自建 interactionSource，不接管 onClick；
 * 调用方仍用 `clickable(...)`（自带 ripple 关掉见 [pressableClick]）。
 *
 * 纪律（报告 §4.3）：
 * - **不动旋转**：−2.5° 是落印专用（brand/seal 用印规则），按钮永不旋转；
 * - **不做透明度闪烁**：默认 ripple 的扩散圆在纸质底上像污渍，这里改为底色阶跃 + 压缩；
 * - **减弱动态**下 scale 跳过。
 */
@Composable
fun Modifier.pressableScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = BiuZMotion.pressedScale,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val target = if (pressed && !reduced) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (pressed) BiuZMotion.pressIn else BiuZMotion.pressOut,
            easing = BiuZMotion.emphasized,
        ),
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 主 CTA 的按压实 + 轻击触觉（**只给主 CTA**：发送 / 批准 / 开始任务 / 登录）。
 *
 * 触觉为什么只给主 CTA：motion-spec 的落印才配触觉（那是「决议」分量）；
 * 列表行/芯片/图标钮按一下就震，会把触觉降成噪音。
 */
@Composable
fun Modifier.ctaPress(
    interactionSource: MutableInteractionSource,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val pressed by interactionSource.collectIsPressedAsState()
    // 按下帧触发一次（用 LaunchedEffect 而非 side effect：保证每个 press 边沿只响一次）
    LaunchedEffect(pressed) {
        if (pressed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    return this.pressableScale(interactionSource)
}

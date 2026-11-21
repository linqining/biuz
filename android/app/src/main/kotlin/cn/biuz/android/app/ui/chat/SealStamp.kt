package cn.biuz.android.app.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.biuz.android.app.ui.theme.BiuZBg
import cn.biuz.android.app.ui.theme.BiuZOnSeal
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.BiuZText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: - 朱批盖章动效（motion-spec 口径，brand/seal/motion-spec.html；与 iOS StampView.swift 同构）
//
// 落印 460ms：scale 1.6 → 0.96 → 1.04 → 1.0 · rotate −10° → −2.5° · alpha 0→0.94
// （CubicBezier(.22,.68,.32,1)）；印泥外溢圈 520ms 延迟 180ms（颜色随印：朱印朱圈、墨印墨圈）。
// 印面 Path = brand/seal/seal-mark.svg 逐字移植（右下残边是品牌签名，不得修复）；
// 印文「照办/不准」为图形不译；落印时机 = ACK 权威成功之后（失败不落印，U-5）。
// 系统动画时长关闭（ANIMATOR_DURATION_SCALE=0）时终态直出；落印场合恒 −2.5°，上限 ±8°。
// 变换与透明度只在 graphicsLayer 一层生效：静态路径直出终态，动画路径由 Animatable 独占。

/** 印面 100×100 设计坐标 Path（与 iOS SealFaceShape 逐字一致）。 */
private fun sealFacePath100(): Path = Path().apply {
    moveTo(14f, 0f)
    lineTo(86f, 0f)
    quadraticBezierTo(100f, 0f, 100f, 14f)
    lineTo(100f, 84f)
    lineTo(91f, 86f)
    lineTo(94f, 94f)
    lineTo(85f, 96f)
    lineTo(82f, 100f)
    lineTo(14f, 100f)
    quadraticBezierTo(0f, 100f, 0f, 86f)
    lineTo(0f, 14f)
    quadraticBezierTo(0f, 0f, 14f, 0f)
    close()
}

/** 一枚印：朱印「照办」（批准）/ 墨印「不准」（拒绝）；墨印在深色档翻转为纸面墨字
 *  （BiuZText/BiuZBg 动态取值），保证墨底上依然可读——印是「落」在当前界面上的。 */
@Composable
fun SealStamp(approved: Boolean, modifier: Modifier = Modifier, size: Dp = 116.dp) {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    val faceColor = if (approved) BiuZSeal else BiuZText
    val inkColor = if (approved) BiuZOnSeal else BiuZBg
    val ringColor = if (approved) BiuZSeal else BiuZText
    val chars = if (approved) listOf("照", "办") else listOf("不", "准")

    val scaleAnim = remember { Animatable(if (reduceMotion) 1f else 1.6f) }
    val rotationAnim = remember { Animatable(if (reduceMotion) -2.5f else -10f) }
    val alphaAnim = remember { Animatable(if (reduceMotion) 0.94f else 0f) }
    val ringScaleAnim = remember { Animatable(if (reduceMotion) 1.45f else 0.9f) }
    val ringAlphaAnim = remember { Animatable(if (reduceMotion) 0f else 0.7f) }
    val easing = remember { CubicBezierEasing(0.22f, 0.68f, 0.32f, 1f) }
    val ringOut = remember { CubicBezierEasing(0f, 0f, 0.58f, 1f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        suspend fun seg(a: Animatable<Float, *>, to: Float, ms: Int) =
            a.animateTo(to, tween(ms, easing = easing))
        coroutineScope {
            launch { seg(scaleAnim, 0.96f, 253); seg(scaleAnim, 1.04f, 100); seg(scaleAnim, 1f, 107) }
            launch { seg(rotationAnim, -2f, 253); seg(rotationAnim, -3.2f, 100); seg(rotationAnim, -2.5f, 107) }
            launch { seg(alphaAnim, 1f, 253); seg(alphaAnim, 0.97f, 100); seg(alphaAnim, 0.94f, 107) }
        }
        delay(180) // 印泥外溢：延迟 180ms
        coroutineScope {
            launch { ringScaleAnim.animateTo(1.45f, tween(520, easing = ringOut)) }
            launch { ringAlphaAnim.animateTo(0f, tween(520, easing = ringOut)) }
        }
    }

    Box(
        modifier = modifier
            .size(100.dp)
            .graphicsLayer {
                scaleX = size.value / 100f
                scaleY = size.value / 100f
            },
        contentAlignment = Alignment.Center,
    ) {
        // 印面 + 印文（落印变换单层承载）
        Box(
            Modifier.graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                rotationZ = rotationAnim.value
                alpha = alphaAnim.value
            }
        ) {
            Canvas(Modifier.size(100.dp)) {
                val s = this.size.minDimension / 100f
                scale(s, s, pivot = center) {
                    drawPath(sealFacePath100(), color = faceColor)
                }
            }
            Column(
                modifier = Modifier.size(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                chars.forEach { ch ->
                    Text(
                        text = ch,
                        style = TextStyle(
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Serif,
                        ),
                        color = inkColor,
                    )
                }
            }
        }
        // 印泥外溢圈
        Canvas(Modifier.size(100.dp)) {
            val s = this.size.minDimension / 100f
            scale(s, s, pivot = center) {
                drawCircle(
                    color = ringColor,
                    radius = 50f * (ringScaleAnim.value / 0.9f),
                    center = center,
                    style = Stroke(width = 2f),
                    alpha = ringAlphaAnim.value,
                )
            }
        }
    }
}

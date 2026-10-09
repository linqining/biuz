package cn.biuz.android.app.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/**
 * CameraX ImageAnalysis（YUV_420_888）Y 平面 → zxing 纯解码。
 *
 * 设计约束（工程纪律）：
 * - 全函数为纯计算（字节数组进出，零 Android 类依赖）——JVM 单测可回灌
 *   （zxing QRCodeWriter 编码 → 亮度数组 → 本解码，真链路自证）；
 * - 调用方（QrAnalyzer）在分析专用线程执行，主线程零参与；
 * - 只解 QR_CODE（目标格式 mono 提示：http(s)://host:port/?token=…）；
 * - 解码失败尝试一次 90° 旋转重试（桌面二维码相对传感器常处横向姿态；
 *   MultiFormatReader 的 TRY_HARDER 内部旋转依赖源 rotateSupported，Y 平面自转不依赖该口）。
 */
object QrFrameDecoder {

    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * ImageProxy.planes[0]（Y 平面）→ 行主序亮度数组。
     * 宽容 stride：rowStride ≥ width（行尾 padding 剔除）、pixelStride ∈ {1, 2}
     * （个别设备 Y 半采样布局）；buffer 只做绝对/顺序读，不改写调用方缓冲语义。
     */
    fun extractYLuma(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        val out = ByteArray(width * height)
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(out, 0, out.size)
            return out
        }
        for (row in 0 until height) {
            val base = row * rowStride
            if (pixelStride == 1) {
                buffer.position(base)
                buffer.get(out, row * width, width)
            } else {
                for (col in 0 until width) {
                    out[row * width + col] = buffer.get(base + col * pixelStride)
                }
            }
        }
        return out
    }

    /** 亮度数组 → QR 内容；两次姿态（0°/90°）未命中或非 QR 内容返回 null（不抛）。 */
    fun decodeYQr(luma: ByteArray, width: Int, height: Int): String? {
        var data = luma
        var w = width
        var h = height
        repeat(2) {
            try {
                val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
                return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
            } catch (e: NotFoundException) {
                // 旋转 90° 重试一次（尺寸随之对调）；旋转自身异常同样不外抛——
                // 本函数运行在分析线程，任何外抛 = 应用进程崩溃（2026-10-09 模拟器
                // 实测教训：非方形帧旋转越界曾炸进程）
                try {
                    data = rotate90Clockwise(data, w, h)
                    val t = w; w = h; h = t
                } catch (e: Exception) {
                    return null
                }
            } catch (e: ReaderException) {
                // 格式/校验类失败：非 QR 内容，直接放弃（终态不重试口径）
                return null
            } catch (e: Exception) {
                // 数组/参数类异常同口径静默放弃（分析帧是易丢数据，下一帧再来）
                return null
            }
        }
        return null
    }

    /**
     * 行主序亮度数组顺时针旋转 90°（w×h → h×w；纯函数，单测回灌旋转正确性）。
     * 坐标映射：新 (x2, y2) = 旧 (y2, h-1-x2)，即 out[y2*h + x2] = src[(h-1-x2)*w + y2]
     * （旧 y = h-1-x2 恒在 [0,h)、旧 x = y2 恒在 [0,w)，任意 w≠h 不越界——勿改回
     * 按 y2 求旧行号的写法，w>h 时 (h-1-y2) 为负越界，模拟器 640×480 帧实测崩溃）。
     */
    fun rotate90Clockwise(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y2 in 0 until w) {
            val outBase = y2 * h
            for (x2 in 0 until h) {
                out[outBase + x2] = src[(h - 1 - x2) * w + y2]
            }
        }
        return out
    }
}

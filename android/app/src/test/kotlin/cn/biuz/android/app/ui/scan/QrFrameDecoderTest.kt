package cn.biuz.android.app.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * QR 识别链单测（真链路回灌）：zxing QRCodeWriter 编码目标内容 → BitMatrix 渲染为
 * Y 平面亮度数组 → [QrFrameDecoder.decodeYQr] 解码。覆盖：直连链接、中继链接、
 * 90° 旋转姿态、stride 抽取、非 QR 噪声返回 null。
 */
class QrFrameDecoderTest {

    /** BitMatrix → 行主序亮度数组（黑=0，白=255）。 */
    private fun renderY(content: String, size: Int = 240): Pair<ByteArray, Int> {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val n = matrix.width
        val y = ByteArray(n * n)
        for (row in 0 until n) {
            for (col in 0 until n) {
                y[row * n + col] = if (matrix.get(col, row)) 0 else 0xFF.toByte()
            }
        }
        return y to n
    }

    @Test
    fun decodesDirectConnectLink() {
        val content = "http://192.168.1.24:3030/?token=abc123"
        val (y, n) = renderY(content)
        assertEquals(content, QrFrameDecoder.decodeYQr(y, n, n))
    }

    @Test
    fun decodesRelayPairingLink() {
        val content = "https://zcode.z.ai/remote/v4?sid=device-sid-1&hash=c2hh&mid=mid-1&name=lin-mac"
        val (y, n) = renderY(content)
        assertEquals(content, QrFrameDecoder.decodeYQr(y, n, n))
    }

    @Test
    fun decodesAfter90DegreeRotation() {
        val content = "http://10.0.0.2:3030/?token=tok-rot"
        val (y, n) = renderY(content)
        val rotated = QrFrameDecoder.rotate90Clockwise(y, n, n)
        // 解码器首姿态未命中 → 自转 90° 重试应命中（验证旋转方向与尺寸对调正确）
        assertEquals(content, QrFrameDecoder.decodeYQr(rotated, n, n))
    }

    @Test
    fun rotate90ClockwiseNonSquareMatchesCoordinateMap() {
        // 2×3（w×h）逐字母矩阵：a b / c d / e f —— 顺时针 90° = e c a / f d b。
        // 非方形是相机帧（如 640×480）常态：按 y2 求旧行号的旧写法在 w>h 时负越界
        //（2026-10-09 模拟器实测 ArrayIndexOutOfBoundsException 崩进程）。
        val src = "abcdef".map { it.code.toByte() }.toByteArray()
        val out = QrFrameDecoder.rotate90Clockwise(src, 2, 3)
        assertEquals("ecafdb", String(out))
        // 宽 > 高（相机横向姿态）同样安全
        val wide = "abcdef".map { it.code.toByte() }.toByteArray() // 3×2: a b c / d e f
        val outWide = QrFrameDecoder.rotate90Clockwise(wide, 3, 2)
        // CW of a b c / d e f = d a / e b / f c
        assertEquals("dae" + "bfc", String(outWide))
    }

    @Test
    fun decodesInsideWideNonSquareFrame() {
        // QR 置于更宽的亮度帧内（w≠h 首姿态解码；相机帧非方形常态）
        val content = "http://10.0.0.3:3030/?token=wide"
        val (qr, n) = renderY(content)
        val frameW = n + 64
        val frame = ByteArray(frameW * n) { 0xFF.toByte() }
        for (row in 0 until n) qr.copyInto(frame, row * frameW, row * n, row * n + n)
        assertEquals(content, QrFrameDecoder.decodeYQr(frame, frameW, n))
    }

    @Test
    fun extractYLumaHandlesRowPaddingAndPixelStride() {
        val content = "http://192.168.1.24:3030/?token=pad"
        val (src, n) = renderY(content)
        // 模拟 rowStride = width + 8（行尾 padding）+ pixelStride = 1
        val paddedWidth = n + 8
        val padded = ByteArray(paddedWidth * n) { 0xFF.toByte() }
        for (row in 0 until n) {
            src.copyInto(padded, row * paddedWidth, row * n, row * n + n)
        }
        val buffer = java.nio.ByteBuffer.wrap(padded)
        val extracted = QrFrameDecoder.extractYLuma(
            buffer = buffer,
            width = n,
            height = n,
            rowStride = paddedWidth,
            pixelStride = 1,
        )
        assertEquals(content, QrFrameDecoder.decodeYQr(extracted, n, n))

        // 模拟 pixelStride = 2（Y 半采样布局）：rowStride 为字节步长（含 pixelStride，
        // 真实相机缓冲同构）；亮度字节在每行内隔字节放置
        val stridedRowBytes = paddedWidth * 2
        val strided = ByteArray(stridedRowBytes * n) { 0xFF.toByte() }
        for (row in 0 until n) {
            for (col in 0 until n) {
                strided[row * stridedRowBytes + col * 2] = src[row * n + col]
            }
        }
        val extractedStrided = QrFrameDecoder.extractYLuma(
            buffer = java.nio.ByteBuffer.wrap(strided),
            width = n,
            height = n,
            rowStride = stridedRowBytes,
            pixelStride = 2,
        )
        assertEquals(content, QrFrameDecoder.decodeYQr(extractedStrided, n, n))
    }

    @Test
    fun noiseReturnsNullWithoutThrowing() {
        val noise = ByteArray(120 * 120) { (it * 37 % 256).toByte() }
        assertNull(QrFrameDecoder.decodeYQr(noise, 120, 120))
        // 全白（无定位图案）同样干净返回 null
        val blank = ByteArray(120 * 120) { 0xFF.toByte() }
        assertNotNull(blank)
        assertNull(QrFrameDecoder.decodeYQr(blank, 120, 120))
        // 非方形噪声：首姿态失败 → 90° 旋转重试路径（w≠h 下必须不越界、干净返 null）
        val noiseWide = ByteArray(160 * 120) { ((it * 37) % 256).toByte() }
        assertNull(QrFrameDecoder.decodeYQr(noiseWide, 160, 120))
    }
}

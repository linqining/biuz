package cn.biuz.android.app.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX ImageAnalysis.Analyzer：Y 平面抽取 → zxing 解码 → 回调。
 * 回调在分析专用线程（单线程 executor）到达——消费方如需触碰 Compose 态，
 * 必须自行 hop 主线程（ScanScreen 经 rememberCoroutineScope.launch 承接）。
 *
 * 节流：≥150ms 间隔取帧解码（分析帧 KEEP_ONLY_LATEST 本身背压，此层再省 CPU）；
 * 不在分析器内做「已交付」闩锁——二维码非法内容需继续扫（内联红字态），合法连接
 * 由消费方置 connectStarted 后闭路。
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val lastDecodeAt = AtomicLong(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val last = lastDecodeAt.get()
            if (now - last < THROTTLE_MS) return
            if (!lastDecodeAt.compareAndSet(last, now)) return

            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val luma = QrFrameDecoder.extractYLuma(
                buffer = plane.buffer,
                width = width,
                height = height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            val text = QrFrameDecoder.decodeYQr(luma, width, height) ?: return
            onDecoded(text)
        } catch (e: Throwable) {
            // 分析线程的任何异常都不许杀进程（单帧是易丢数据，下一帧再来）——
            // 2026-10-09 模拟器实测：解码内旋转越界曾以未捕获异常炸掉整个 App
        } finally {
            image.close() // 分析帧必须归还，否则 ImageAnalysis 停帧
        }
    }

    private companion object {
        const val THROTTLE_MS = 150L
    }
}

package cn.biuz.android.relay

import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 中继 WS 文本帧传输面（机械适配：iOS `URLSessionWebSocketTask` 的 Kotlin 对应物，
 * 仅文本帧口径——中继全程 JSON 文本帧，与局域网 [cn.biuz.android.protocol.WebSocketTransport]
 * 的二进制协议帧是两条不同链路，故独立成接口，勿混用）。
 *
 * 语义契约（对齐 :connection OkHttpWebSocketTransport 文件头注）：
 * - send 非挂起回调式：失败经 [send] 的 onFailure 恰好回调一次（Swift 完成句柄
 *   `(Error?) -> Void` 的 `if let error` 分支）；
 * - 读循环事件源：每条文本帧一个元素；对端关闭/连接失败 → [incoming] 关闭或以异常结束
 *   （RelayTransport 读循环由此退出走 handleTransportFault）。
 */
interface RelayTextSocket {
    fun send(text: String, onFailure: (Throwable) -> Unit)

    val incoming: ReceiveChannel<String>

    /** `cancel(with:reason:)` 对应物；code 用 RFC 语义（1000 normal / 1001 going-away）。 */
    fun close(code: Int, reason: String?)

    companion object {
        const val CLOSE_NORMAL = 1000 // .normalClosure
        const val CLOSE_GOING_AWAY = 1001 // .goingAway（iOS .abnormalClosure 的可发送等价码）
    }
}

/** 工厂（iOS `URLSession` 注入对应物；测试可用真实 OkHttp 对回环端点直连，无需替身）。 */
fun interface RelayTextSocketFactory {
    fun create(url: String, timeoutMs: Long): RelayTextSocket
}

/**
 * OkHttp WebSocket 承载 [RelayTextSocket]（任务指定口径：OkHttp WS + wss://zcode.z.ai/remote/v4，
 * 协议接口文档 §3.3）。每传输独立 client（close 即回收线程，非 daemon 线程不回收会让
 * 测试 JVM 挂起——OkHttpWebSocketTransport 同先例）。
 */
class OkHttpTextWebSocketTransport(
    url: String,
    timeoutMs: Long,
) : RelayTextSocket {

    private val closed = AtomicBoolean(false)

    /** 帧不丢弃口径优先（A8/A9 同 OkHttpWebSocketTransport 注）→ UNLIMITED。 */
    private val incomingChannel = Channel<String>(capacity = Channel.UNLIMITED)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        // WS 长连：读超时必须关（服务端推送无周期保证）
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val opened = CountDownLatch(1)
    private var openError: Throwable? = null

    private val webSocket: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incomingChannel.trySend(text)
            }

            // 中继全程 JSON 文本帧；二进制帧不合预期，忽略（iOS RelayTransport.handleReceive
            // `case .data` 分支同口径，仅记日志的差别由读循环统一承载）
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                openError = t
                opened.countDown()
                incomingChannel.close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incomingChannel.close()
            }
        },
    )

    init {
        // 工厂契约：create 返回即连接已建立（对应 Swift resume() 后等 open 回调）
        if (!opened.await(timeoutMs + 5_000, TimeUnit.MILLISECONDS)) {
            runCatching { webSocket.cancel() }
            releaseClient()
            throw SocketTimeoutException("WebSocket 握手超时：$url")
        }
        openError?.let { error ->
            releaseClient()
            throw IllegalStateException("WebSocket 建立失败：${error.message ?: error.toString()}", error)
        }
    }

    override val incoming: ReceiveChannel<String>
        get() = incomingChannel

    override fun send(text: String, onFailure: (Throwable) -> Unit) {
        if (closed.get()) {
            onFailure(IllegalStateException("transport closed"))
            return
        }
        val enqueued = runCatching { webSocket.send(text) }.getOrDefault(false)
        if (!enqueued) {
            onFailure(IllegalStateException("WebSocket 发送失败（已关闭或发送队列溢出）"))
        }
    }

    override fun close(code: Int, reason: String?) {
        if (!closed.compareAndSet(true, false)) return
        runCatching { webSocket.close(code, reason ?: "") }
        incomingChannel.close()
        releaseClient()
    }

    private fun releaseClient() {
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }
}

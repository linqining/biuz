package cn.biuz.android.connection

import cn.biuz.android.protocol.WebSocketTransport
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
import okio.ByteString.Companion.toByteString

/**
 * OkHttp WebSocket 承载 protocol 的 [WebSocketTransport]（任务指定口径：局域网直连
 * `GET /ws?token=…` 形态，协议文档 §3.2）。iOS 原文：URLSessionWebSocketTask
 * （protocol/ChannelClient.kt 传输抽象的注释契约）。
 *
 * 语义契约对齐 ChannelClient.kt 的 [WebSocketTransport] 接口注释：
 * - 一条 WS **二进制**消息 = 一个协议帧（onMessage(ByteString) → incoming 元素）；
 *   文本帧按 Swift `guard case .data` 口径静默忽略（onMessage(String) 不实现）；
 * - send 非挂起回调式：OkHttp send 返回 false（已关闭/发送队列溢出）即回调
 *   onFailure 恰好一次（Swift 完成句柄 `(Error?) -> Void` 的仅失败分支）；
 * - 连接级失败（onFailure）/对端正常关闭（onClosed）→ [incoming] 关闭或以异常结束，
 *   ChannelClient 读循环由此退出；
 * - 工厂契约：create（构造）阻塞至连接建立（对应 Swift `resume()` 后等回调），
 *   失败原样上抛 → ChannelClient.connect 传播 → 连接步骤 3 失败。
 */
class OkHttpWebSocketTransport(
    url: String,
    timeoutMs: Long,
    /**
     * OkHttp 对单条消息无内建上限（流式读取），此值仅作声明透传（= 协议逻辑帧上限
     * 16MB，ChannelClient.MAX_MESSAGE_BYTES 口径——LAN 直连不受中继分片保护，
     * 见该常量 KDoc）。
     */
    override val maximumMessageSize: Long,
) : WebSocketTransport {

    private val closed = AtomicBoolean(false)

    /**
     * 帧不丢弃口径优先（A8/A9：帧被丢 = 静默断流/水位断裂，比内存压力更危险）→
     * UNLIMITED，与 teststub 的 JavaNetWebSocketTransport 测试夹具同口径；
     * 背压由 ChannelClient 常驻读循环保证。
     */
    private val incomingChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    /** 每传输独立 client：close 即回收线程（OkHttp Dispatcher 执行器线程非 daemon，
     * 不关闭会让测试 JVM 挂起不退出）。 */
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

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incomingChannel.trySend(bytes.toByteArray())
            }

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
        // 工厂契约：create 返回即连接已建立（ChannelClient.connect 随后等 Initialize；
        // 与 JavaNetWebSocketTransport 夹具的 buildAsync().get(timeout) 同思路）
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

    override val incoming: ReceiveChannel<ByteArray>
        get() = incomingChannel

    override fun send(frame: ByteArray, onFailure: (Throwable) -> Unit) {
        if (closed.get()) {
            onFailure(IllegalStateException("transport closed"))
            return
        }
        val enqueued = runCatching {
            webSocket.send(frame.toByteString())
        }.getOrDefault(false)
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

    /** 每传输独立 client 的线程回收（OkHttp Dispatcher 执行器线程非 daemon，
     * 不关闭会让测试 JVM 挂起不退出）。 */
    private fun releaseClient() {
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }
}

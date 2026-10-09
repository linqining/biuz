package cn.biuz.android.teststub

import cn.biuz.android.protocol.WebSocketTransport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 测试夹具：JDK java.net.http.WebSocket 承载 protocol 的 [WebSocketTransport]
 * （真本地回环 socket，替身服务器的对端是真实 Ktor WS 端点）。
 *
 * 语义契约对齐 ChannelClient.kt 接口注释：
 * - 一条二进制消息 = 一个协议帧（onBinary → incoming 元素）；
 * - send 非挂起回调式；java.net.http.WebSocket 不允许并发 send —— 以 Mutex 串行化
 *   （每次 sendBinary future 完成后才发下一条）；
 * - close 后 [incoming] 关闭（读循环退出）。
 */
class JavaNetWebSocketTransport(
    url: String,
    timeoutMs: Long,
    override val maximumMessageSize: Long,
) : WebSocketTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incomingChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)

    private val ws: WebSocket

    init {
        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                incomingChannel.trySend(bytes)
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                incomingChannel.close()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                incomingChannel.close(error)
            }
        }
        ws = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .buildAsync(URI.create(url), listener)
            // 工厂契约：create 返回即连接已建立（ChannelClient.connect 随后等 Initialize）
            .get(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
    }

    override val incoming: ReceiveChannel<ByteArray>
        get() = incomingChannel

    override fun send(frame: ByteArray, onFailure: (Throwable) -> Unit) {
        if (closed.get()) {
            onFailure(IllegalStateException("transport closed"))
            return
        }
        scope.launch {
            sendMutex.withLock {
                try {
                    ws.sendBinary(ByteBuffer.wrap(frame), true).get(10, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    onFailure(t)
                }
            }
        }
    }

    override fun close(code: Int, reason: String?) {
        if (!closed.compareAndSet(true, false)) return
        runCatching { ws.sendClose(code, reason ?: "").get(2, TimeUnit.SECONDS) }
        incomingChannel.close()
        scope.cancel()
    }
}

package cn.biuz.android.protocol

import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// 直译自 ios/ZCodeMobileApp/Sources/Services/RPC/ChannelClient.swift（375 行）。
// 上游：packages/rpc/src/{protocol.ts, channelClient.ts, channels.shared.ts}。
// 并发模型适配（docs/Android开发交接.md §2.2）：
// - Swift actor 隔离 → Mutex 临界区（与 actor 同为串行化保证，挂起点一律在锁外）；
// - CheckedContinuation → CompletableDeferred；
// - 看门狗 Task → withTimeout（响应/超时两路竞争仍恰好 resolve 一次）；
// - URLSessionWebSocketTask → WebSocketTransport 接口（JVM stdlib 无 WS 客户端，
//   本模块禁引 OkHttp；实现由 connection/relay 模块以 OkHttp 承接，协议管线逻辑
//   全部在本类，与 Swift 逐行对应）。

// MARK: - 协议帧（packages/rpc/src/protocol.ts 移植，ChannelClient.swift:3-56）

/** 13 字节头：[type:1][id:4 BE][ack:4 BE][length:4 BE]（protocol.ts:183-230） */
enum class ProtocolMessageType(val rawValue: Int) {
    none(0),
    regular(1),
    control(2),
    ack(3),
    disconnect(5),
    replayRequest(6),
    pause(7),
    resume(8),
    keepAlive(9),
}

object RPCFrame {
    const val headerSize = 13

    /** 写一帧 Regular 消息（payload 为已序列化的 RPC 值）（:22-30）。 */
    fun write(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(headerSize + payload.size)
        out.write(ProtocolMessageType.regular.rawValue)
        out.writeUInt32BE(0) // id
        out.writeUInt32BE(0) // ack
        out.writeUInt32BE(payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    /** 解析一帧；返回 payload。非 Regular / 长度不符返回 null（Ack/KeepAlive 丢弃）（:33-40）。 */
    fun read(data: ByteArray): ByteArray? {
        if (data.size < headerSize) return null
        val type = data[0].toInt() and 0xFF
        if (type != ProtocolMessageType.regular.rawValue) return null
        val length = data.readUInt32BE(9)
        if (length != (data.size - headerSize).toLong()) return null
        return data.copyOfRange(headerSize, data.size)
    }
}

private fun ByteArray.readUInt32BE(offset: Int): Long {
    val b0 = (this[offset].toInt() and 0xFF).toLong()
    val b1 = (this[offset + 1].toInt() and 0xFF).toLong()
    val b2 = (this[offset + 2].toInt() and 0xFF).toLong()
    val b3 = (this[offset + 3].toInt() and 0xFF).toLong()
    return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
}

private fun ByteArrayOutputStream.writeUInt32BE(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

// MARK: - 请求/响应类型（channels.shared.ts:18-31，ChannelClient.swift:58-73）

enum class RequestType(val rawValue: Int) {
    promise(100),
    promiseCancel(101),
    eventListen(102),
    eventDispose(103),
}

enum class ResponseType(val rawValue: Int) {
    initialize(200),
    promiseSuccess(201),
    promiseError(202),
    promiseErrorObj(203),
    eventFire(204),
    eventError(205),
    ;

    companion object {
        /** Swift `ResponseType(rawValue:)` 对应物；未知 raw → null（handleFrame 静默丢帧）。 */
        fun fromRawValue(raw: Int): ResponseType? = entries.find { it.rawValue == raw }
    }
}

/**
 * RPC 层错误（PromiseError data.message/name 口径，channelClient.ts:96-117）（:76-89）。
 * `message` 恒为服务端/本地原文（写面上屏用它，勿经 toString 加前缀——协议文档 §5-12）。
 */
class RPCError(
    override val message: String,
    val name: String = "Error",
    val detail: JSONValue? = null,
) : Exception(message) {
    /** Swift CustomStringConvertible.description 同口径（"\(name): \(message)"）。 */
    override fun toString(): String = "$name: $message"

    /**
     * 桥重开竞态下 assertReady 类调用的确定性拒绝【实证·上游仓
     * zcodeAgentConnectionScope.assertReady】——真机 detail 形态 name="Error"、
     * message="fault.connection.handshakeRequired"（sess_e8677b05 rowsRange 取证）
     */
    val isHandshakeRequired: Boolean
        get() = message.contains("fault.connection.handshakeRequired")
}

// MARK: - 传输抽象（iOS URLSessionWebSocketTask 的 Kotlin 对应物）

/**
 * 一条 WS 连接的传输面（iOS `URLSessionWebSocketTask` 对应物）。
 * JVM stdlib 无 WebSocket 客户端且 protocol 模块禁引 OkHttp——实现由 connection/relay
 * 模块以 OkHttp 承接。语义契约（websocket.ts write/send 一一对应）：
 * - 一条 WS **二进制**消息 = 一个协议帧（无需处理粘包）；文本帧按 Swift
 *   `guard case .data` 口径静默忽略；
 * - [close] 之后 [incoming] 必须完成（关闭或以异常结束），读循环由此退出。
 */
interface WebSocketTransport {
    /** 单条消息上限（iOS 缺省 1MiB；本链路放宽到 16MB=协议逻辑帧上限，:155-158）。 */
    val maximumMessageSize: Long

    /**
     * 发送一条二进制消息；不抛异常，失败经 onFailure 恰好回调一次（Swift 完成句柄
     * `(Error?) -> Void` 的 `if let error` 分支；Kotlin 用非空参数承载「仅失败时调用」）。
     */
    fun send(frame: ByteArray, onFailure: (Throwable) -> Unit)

    /** 读循环事件源：每条二进制消息一个元素。实现须有界缓冲（回压上游即断开）。 */
    val incoming: ReceiveChannel<ByteArray>

    /** 关闭（`cancel(with:reason:)` 对应物；code 见 CLOSE_* 常量）。 */
    fun close(code: Int, reason: String?)

    companion object {
        const val CLOSE_NORMAL = 1000 // .normalClosure
        const val CLOSE_GOING_AWAY = 1001 // .goingAway
    }
}

/**
 * WebSocketTransport 工厂（iOS `URLSession` 注入对应物：urlSession.webSocketTask(with:)）。
 * 实现应在此完成连接建立并开始接收（对应 `URLRequest.timeoutInterval` + `resume()`）。
 */
fun interface WebSocketTransportFactory {
    fun create(url: String, timeoutMs: Long, maximumMessageSize: Long): WebSocketTransport
}

// MARK: - RPC 通道传输抽象（局域网 ChannelClient 与云中继 RelayChannelClient 共用门面）
// iOS 原文定义于 RelayChannelClient.swift:7-14；为使本类 conformance 可表达而随类落位，
// 后续 RelayChannelClient 移植直接实现本接口（勿重复定义）。

/**
 * ZCodeServerConnection 唯一关心的通道语义：Promise 调用 / 事件订阅 / 断开。
 * 只读边界拦截在 ZCodeServerConnection.call 出口，两实现均被覆盖。
 * 注：Swift 协议无默认参数；Kotlin 默认值只能声明于接口（timeout 默认 30s 来自实现体）。
 */
interface RPCChannelTransport {
    suspend fun call(channel: String, command: String, arg: RPCValue = RPCValue.Undefined, timeout: Duration = 30.seconds): RPCValue

    /**
     * EventListen 一次，服务端按原 id 持续 EventFire（serialization：[102, id, channel, event] + arg）。
     * [onError]：EventError(205) 订阅失败终态回调——服务端已终结订阅（channelClient.ts:176-185
     * 同构：handler 移除、不写入正常事件流、不保留旧请求、无需回 dispose）；
     * iOS ChannelClient.listen onError :271-279 双传输同构。
     */
    suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue = RPCValue.Undefined,
        handler: (RPCValue) -> Unit,
        onError: ((RPCError) -> Unit)? = null,
    ): EventSubscription

    suspend fun disconnect()
}

// MARK: - 事件订阅句柄（ChannelClient.swift:91-114）

/**
 * 事件订阅：释放（cancel）时自动发 EventDispose（channelClient.ts:163-190）。
 * 注：Swift `deinit { cancel() }` 在 Kotlin 无析构对应物——持有方（连接层生命周期）
 * 必须显式 cancel，否则仅漏发 dispose 帧、无协议正确性影响（handler 已不再被调用的
 * 前提是连接层同步持有/释放本句柄，与 iOS 持有纪律一致）。
 */
class EventSubscription(private val onCancel: () -> Unit) {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            onCancel()
        }
    }
}

// MARK: - ChannelClient（channelClient.ts 移植；URLSessionWebSocketTask 传输，ChannelClient.swift:116-375）

/**
 * 一条 WS 二进制消息 = 一个协议帧（websocket.ts write/send 一一对应，无需处理粘包）。
 * Swift actor → Kotlin：全部可变状态收敛进 [mutex] 临界区；挂起（等待响应/Initialize）
 * 一律在锁外经 CompletableDeferred 完成，锁内不含任何可能长挂的调用。
 */
class ChannelClient(
    private val transportFactory: WebSocketTransportFactory,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RPCChannelTransport {

    enum class State {
        Uninitialized,
        Idle,
        Closed,
        ;

        /** Swift CustomStringConvertible 同口径（错误文案内插 "state=uninitialized" 等）。 */
        override fun toString(): String = name.lowercase()
    }

    private var state: State = State.Uninitialized
    private var lastRequestId = 0
    private var socket: WebSocketTransport? = null
    private val pendingResponses = HashMap<Int, CompletableDeferred<RPCValue>>()
    private val initializeWaiters = ArrayList<CompletableDeferred<Unit>>()
    private val eventHandlers = HashMap<Int, (RPCValue) -> Unit>()

    /** EventError(205) 订阅失败终态回调（id 键控；iOS eventErrorHandlers :141 同构）。 */
    private val eventErrorHandlers = HashMap<Int, (RPCError) -> Unit>()
    private var onClosed: ((Throwable?) -> Unit)? = null

    /** Swift actor 串行化的 Kotlin 等价物；锁内禁挂起（send 为非挂起回调式）。 */
    private val mutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private var receiveJob: kotlinx.coroutines.Job? = null

    // MARK: 连接

    /**
     * 连接并等待服务端 Initialize（channelServer.ts:30-35 连接建立即推）（:150-186）。
     * 超时由 withTimeout 承接（Swift watchdog Task 同效）；失败路径 cancel(normalClosure)。
     * 注：connect 不在 RPCChannelTransport 协议面（Swift 同：仅 ChannelClient 自有）。
     */
    suspend fun connect(url: String, timeout: Duration, onClosed: (Throwable?) -> Unit) {
        mutex.withLock { this.onClosed = onClosed }
        val transport = transportFactory.create(
            url = url,
            timeoutMs = timeout.inWholeMilliseconds,
            maximumMessageSize = MAX_MESSAGE_BYTES,
        )
        mutex.withLock { socket = transport }
        startReceiveLoop(transport)

        // Initialize 看门狗（channelClient.ts whenInitialized + 超时）
        val waiter = CompletableDeferred<Unit>()
        val alreadyIdle = mutex.withLock {
            when (state) {
                State.Idle -> true
                State.Closed -> throw RPCError(message = "连接在 Initialize 前已关闭", name = "ConnectionClosed")
                State.Uninitialized -> {
                    initializeWaiters.add(waiter)
                    false
                }
            }
        }
        if (!alreadyIdle) {
            try {
                withTimeout(timeout) { waiter.await() }
            } catch (e: TimeoutCancellationException) {
                mutex.withLock { initializeWaiters.remove(waiter) }
                closeTransportOnConnectFailure()
                throw RPCError(message = "等待服务端 Initialize 超时", name = "TimeoutError")
            } catch (e: CancellationException) {
                // 外层作用域取消：原样上抛（Swift 结构化取消同口径）
                closeTransportOnConnectFailure()
                throw e
            } catch (e: Throwable) {
                // close/handleTransportError 经 waiter 传递的失败（Swift continuation.resume(throwing:) 同路）
                closeTransportOnConnectFailure()
                throw e
            }
        }
    }

    /** Swift `socket?.cancel(with: .normalClosure, reason: nil)`（:183）。 */
    private suspend fun closeTransportOnConnectFailure() {
        val transport = mutex.withLock { socket }
        transport?.close(WebSocketTransport.CLOSE_NORMAL, null)
    }

    override suspend fun disconnect() {
        val transport = mutex.withLock {
            val t = socket
            socket = null
            t
        }
        transport?.close(WebSocketTransport.CLOSE_GOING_AWAY, null)
        close()
    }

    private suspend fun close() {
        var closedCallback: ((Throwable?) -> Unit)? = null
        mutex.withLock {
            if (state == State.Closed) return
            state = State.Closed
            val closedError = RPCError(message = "连接已关闭", name = "ConnectionClosed")
            failPendingLocked(closedError)
            failInitializeWaitersLocked(closedError)
            eventHandlers.clear()
            eventErrorHandlers.clear()
            closedCallback = onClosed
        }
        // 回调在锁外调用（回调可能重入本类；Swift actor 可重入挂起，Kotlin Mutex 不可重入）
        closedCallback?.invoke(null)
    }

    private suspend fun handleTransportError(error: Throwable) {
        var errorCallback: ((Throwable?) -> Unit)? = null
        mutex.withLock {
            if (state == State.Closed) return
            state = State.Closed
            val rpcError = RPCError(
                message = "传输错误：${error.message ?: error.toString()}",
                name = "TransportError")
            failPendingLocked(rpcError)
            failInitializeWaitersLocked(rpcError)
            eventHandlers.clear()
            eventErrorHandlers.clear()
            errorCallback = onClosed
        }
        errorCallback?.invoke(error)
    }

    private fun failPendingLocked(error: RPCError) {
        val responses = ArrayList(pendingResponses.values)
        pendingResponses.clear()
        for (continuation in responses) {
            continuation.completeExceptionally(error)
        }
    }

    private fun failInitializeWaitersLocked(error: RPCError) {
        val waiters = ArrayList(initializeWaiters)
        initializeWaiters.clear()
        for (waiter in waiters) {
            waiter.completeExceptionally(error)
        }
    }

    // MARK: 请求（Promise）

    /**
     * Promise 调用：serialize([100, id, channel, command]) + serialize(arg)
     * （channelClient.ts:225-231）。超时由 withTimeout 兜底；超时/响应两路竞争
     * 经 resolveResponse 恰好 resolve 一次（:234-261）。
     */
    override suspend fun call(
        channel: String,
        command: String,
        arg: RPCValue,
        timeout: Duration,
    ): RPCValue {
        // body 序列化与 Swift 同序（帧构建在注册/发送前完成；纯计算，无锁需求）。
        // 潜伏口径（2026-11-10 登记）：arg 不包装直接发——zcode-agent V4 命令走
        // 自定义分发不受影响；ProxyChannel.fromService 类服务（project/hooks/
        // subagents）单参 Obj 也 OK（desktop spread 后 args=[obj]——fromService
        // 对非数组 arg 调 apply 会 TypeError，但既有 LAN 调用面全部走 zcode-agent
        // 自定义分发 + 单参 Obj 服务在真桌面实测可用）。多参位置服务 LAN 直连
        // 未用——LAN 取证轮确认 desktop fromService 对非数组 arg 的实际行为后修。
        val body = RPCSerialization.serialize(arg)

        val deferred = CompletableDeferred<RPCValue>()
        val id: Int
        mutex.withLock {
            if (state != State.Idle) {
                throw RPCError(message = "通道未就绪（state=$state）", name = "NotInitialized")
            }
            id = lastRequestId
            lastRequestId += 1
            val header = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.promise.rawValue),
                        RPCValue.Int(id),
                        RPCValue.Str(channel),
                        RPCValue.Str(command),
                    ),
                ),
            )
            pendingResponses[id] = deferred
            sendOnCurrentSocketLocked(RPCFrame.write(header + body))
        }
        return try {
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // Swift：resolveResponse(failure) 同效——晚到响应在 remove 后被静默丢弃
            mutex.withLock { pendingResponses.remove(id) }
            throw RPCError(message = "RPC 超时：$channel.$command", name = "TimeoutError")
        }
    }

    // MARK: 事件（EventListen / EventDispose）

    /**
     * EventListen 一次，服务端按原 id 持续 EventFire（serialization：[102, id, channel, event] + arg）
     * （:266-282）。
     */
    override suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue,
        handler: (RPCValue) -> Unit,
        onError: ((RPCError) -> Unit)?,
    ): EventSubscription {
        val body = RPCSerialization.serialize(arg)
        val id: Int
        mutex.withLock {
            id = lastRequestId
            lastRequestId += 1
            eventHandlers[id] = handler
            if (onError != null) eventErrorHandlers[id] = onError
            val header = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.eventListen.rawValue),
                        RPCValue.Int(id),
                        RPCValue.Str(channel),
                        RPCValue.Str(event),
                    ),
                ),
            )
            sendOnCurrentSocketLocked(RPCFrame.write(header + body))
        }
        // Swift：Task { await self.disposeEvent(id) }
        return EventSubscription {
            scope.launch { disposeEvent(id) }
        }
    }

    private suspend fun disposeEvent(id: Int) {
        mutex.withLock {
            eventErrorHandlers.remove(id)
            val removed = eventHandlers.remove(id) != null
            if (!removed || state != State.Idle) return
            val header = RPCSerialization.serialize(
                RPCValue.Arr(
                    listOf(
                        RPCValue.Int(RequestType.eventDispose.rawValue),
                        RPCValue.Int(id),
                    ),
                ),
            )
            val body = RPCSerialization.serialize(RPCValue.Undefined)
            sendOnCurrentSocketLocked(RPCFrame.write(header + body))
        }
    }

    // MARK: 内部：发送与接收

    /** 须在 [mutex] 临界区内调用（与 Swift actor 隔离同口径）。 */
    private fun sendOnCurrentSocketLocked(frame: ByteArray) {
        val transport = socket ?: return
        transport.send(frame) { error ->
            // Swift：if let error { Task { await self.handleTransportError(error) } }
            scope.launch { handleTransportError(error) }
        }
    }

    /** 读循环：每条 WS 二进制消息 = 一个协议帧（:305-324；循环常驻即 receiveNext 重入臂）。 */
    private fun startReceiveLoop(transport: WebSocketTransport) {
        receiveJob = scope.launch {
            try {
                for (frame in transport.incoming) {
                    handleFrame(frame)
                }
                // 通道正常完成（本端 close 已置 state）→ handleTransportError 静默
                handleTransportError(RPCError(message = "连接已关闭", name = "ConnectionClosed"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                handleTransportError(t)
            }
        }
    }

    private suspend fun handleFrame(data: ByteArray) {
        val payload = RPCFrame.read(data) ?: return
        val cursor = ReadCursor(0)
        val headerValue = RPCSerialization.deserialize(payload, cursor) ?: return
        val body = RPCSerialization.deserialize(payload, cursor) ?: return
        val headerItems = (headerValue as? RPCValue.Arr)?.items ?: return
        val rawType = headerItems.getOrNull(0)?.intValue ?: return
        val type = ResponseType.fromRawValue(rawType) ?: return

        when (type) {
            ResponseType.initialize -> {
                val waiters = ArrayList<CompletableDeferred<Unit>>()
                mutex.withLock {
                    state = State.Idle
                    waiters.addAll(initializeWaiters)
                    initializeWaiters.clear()
                }
                for (waiter in waiters) waiter.complete(Unit)
            }
            ResponseType.promiseSuccess -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                resolveResponse(id, Result.success(body))
            }
            ResponseType.promiseError -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                resolveResponse(id, Result.failure(decodeError(body)))
            }
            ResponseType.promiseErrorObj -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                resolveResponse(
                    id,
                    Result.failure(
                        RPCError(message = "RPC 错误对象", name = "ErrorObj", detail = body.jsonValue),
                    ),
                )
            }
            ResponseType.eventFire -> {
                val id = headerItems.getOrNull(1)?.intValue ?: return
                // Swift 在 actor 内同步调用 handler；Kotlin 锁内调用用户回调可能死锁
                // （handler 若重入本类）——锁外调用。查表与调用间的 dispose 竞态最多
                // 多投递一条事件，与 store 层幂等消费兼容。
                val handler = mutex.withLock { eventHandlers[id] }
                handler?.invoke(body)
            }
            ResponseType.eventError -> {
                // EventError(205)：订阅失败终态——移除 handler 并经 onError 上抛
                // （channelClient.ts:176-185 同构：不写入正常事件流、不保留旧请求；
                // 服务端已终结订阅，无需回 dispose。iOS ChannelClient.swift:364-368 同构）
                val id = headerItems.getOrNull(1)?.intValue ?: return
                val onError = mutex.withLock {
                    eventHandlers.remove(id)
                    eventErrorHandlers.remove(id)
                }
                onError?.invoke(decodeError(body))
            }
        }
    }

    /** 恰好 resume 一次（响应 / 超时两路竞争收口）（:358-364）。 */
    private suspend fun resolveResponse(id: Int, result: Result<RPCValue>) {
        val continuation = mutex.withLock { pendingResponses.remove(id) } ?: return
        result.fold(
            onSuccess = { continuation.complete(it) },
            onFailure = { continuation.completeExceptionally(it) },
        )
    }

    private fun decodeError(body: RPCValue): RPCError {
        val json = body.jsonValue
        val dict = (json as? JSONValue.Obj)?.fields
            ?: return RPCError(message = "未知 RPC 错误", name = "Error")
        // Swift 先写 message 后写 name（两键可同帧在场），逐字口径
        var message = "未知 RPC 错误"
        var name = "Error"
        dict["message"]?.stringValue?.let { message = it }
        dict["name"]?.stringValue?.let { name = it }
        return RPCError(message = message, name = name, detail = JSONValue.Obj(dict))
    }

    companion object {
        /**
         * 上限放宽到 16MB（=协议逻辑帧上限；2026-10-08 回归审核轮 §11.2）：
         * URLSessionWebSocketTask 缺省 1MiB，超限单条回执（长列表/大 rowsRange/大 diff）
         * 直接断连——上游 Channel 协议层无帧大小上限，LAN 直连不受中继分片保护（:155-158）。
         * iOS 引用 RelayFrameCodec.maxMessageBytes（RelayFrameCodec.swift:19）——该文件
         * 不在本批移植范围，先内联同值常量，RelayFrameCodec 移植后改引。
         */
        const val MAX_MESSAGE_BYTES: Long = 16L * 1024L * 1024L
    }
}

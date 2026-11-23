package cn.biuz.android.protocol

// EventError(205) 订阅失败终态（channelClient.ts:176-185 同构；iOS ChannelClient.swift
// :364-368/:271-294 双传输同构，2026-11-07 v1.39）：
// - eventError 帧 → 移除 handler + onError 上抛（不写入正常事件流）；
// - 终态不回 dispose（服务端已终结订阅）；
// - 后续同 id eventFire 不再投递（handler 已移除）；
// - eventFire 正常路径不受影响（正控）。
// Mock 仅测试装配（红线见 AGENTS §5-13）。

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 测试替身 WS 传输：帧发送账 + 注帧入口。 */
private class FakeWebSocketTransport : WebSocketTransport {
    override val maximumMessageSize: Long = 16L * 1024L * 1024L

    val sentFrames = CopyOnWriteArrayList<ByteArray>()
    private val incomingChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = incomingChannel

    override fun send(frame: ByteArray, onFailure: (Throwable) -> Unit) {
        sentFrames.add(frame)
    }

    override fun close(code: Int, reason: String?) {
        incomingChannel.close()
    }

    suspend fun deliver(frame: ByteArray) {
        incomingChannel.send(frame)
    }
}

class ChannelClientEventErrorTest {

    /** header + body 拼帧（handleFrame 读法：RPCFrame.read → header/body 两段 deserialize）。 */
    private fun frame(headerItems: List<RPCValue>, body: RPCValue): ByteArray =
        RPCFrame.write(RPCSerialization.serialize(RPCValue.Arr(headerItems)) + RPCSerialization.serialize(body))

    @Test
    fun eventErrorInvokesOnErrorRemovesHandlerAndSkipsDispose() = runTest {
        val ws = FakeWebSocketTransport()
        val client = ChannelClient(
            transportFactory = WebSocketTransportFactory { _, _, _ -> ws },
            backgroundDispatcher = StandardTestDispatcher(testScheduler),
        )
        // 先注 Initialize（header [200] + body 占位 Undefined；替身 Channel 无界缓冲，
        // receive loop 启动即消费——connect 的 Initialize 等待由此解锁）
        ws.deliver(frame(listOf(RPCValue.Int(200)), RPCValue.Undefined))
        client.connect("ws://test", 5.seconds, onClosed = {})
        runCurrent()

        var errorCaptured: RPCError? = null
        var firedPayload: RPCValue? = null
        client.listen(
            "zcode-agent", "onDynamicConversationFrame", RPCValue.Undefined,
            handler = { firedPayload = it },
            onError = { errorCaptured = it },
        )
        runCurrent()
        val framesAfterListen = ws.sentFrames.size
        assertEquals(1, framesAfterListen, "eventListen 已发出（[102, 0, channel, event]）")

        // EventError(205)：终态 → onError 上抛 + handler 移除；不回 dispose
        ws.deliver(
            frame(
                listOf(RPCValue.Int(205), RPCValue.Int(0)),
                RPCValue.jsonObject { set("message", "订阅失败原文"); set("name", "ErrorObj") },
            ),
        )
        runCurrent()
        assertEquals("订阅失败原文", errorCaptured?.message)
        assertEquals("ErrorObj", errorCaptured?.name)
        assertEquals(ws.sentFrames.size, framesAfterListen, "终态不回 dispose（服务端已终结订阅）")

        // 后续同 id eventFire 不再投递（handler 已移除）
        ws.deliver(
            frame(
                listOf(RPCValue.Int(204), RPCValue.Int(0)),
                RPCValue.jsonObject { set("late", true) },
            ),
        )
        runCurrent()
        assertFalse(firedPayload?.jsonValue?.get("late")?.boolValue ?: false)
    }

    @Test
    fun eventFireNormalPathUnaffected() = runTest {
        val ws = FakeWebSocketTransport()
        val client = ChannelClient(
            transportFactory = WebSocketTransportFactory { _, _, _ -> ws },
            backgroundDispatcher = StandardTestDispatcher(testScheduler),
        )
        // 先注 Initialize（header [200] + body 占位 Undefined；替身 Channel 无界缓冲，
        // receive loop 启动即消费——connect 的 Initialize 等待由此解锁）
        ws.deliver(frame(listOf(RPCValue.Int(200)), RPCValue.Undefined))
        client.connect("ws://test", 5.seconds, onClosed = {})
        runCurrent()

        var firedPayload: RPCValue? = null
        var errorCaptured: RPCError? = null
        client.listen(
            "zcode-agent", "onDynamicSessionsIndexFrame", RPCValue.Undefined,
            handler = { firedPayload = it },
            onError = { errorCaptured = it },
        )
        runCurrent()
        ws.deliver(
            frame(
                listOf(RPCValue.Int(204), RPCValue.Int(0)),
                RPCValue.jsonObject { set("ok", true) },
            ),
        )
        runCurrent()
        assertTrue(firedPayload?.jsonValue?.get("ok")?.boolValue ?: false, "正控：eventFire 正常投递")
        assertEquals(null, errorCaptured)
    }
}

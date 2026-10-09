package cn.biuz.android.store

import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** 测试专用替身传输（Mock 仅测试装配——红线 §0-4；正式路径不出现本类）。 */
internal class FakeRemoteTransport : RPCChannelTransport {

    /** (channel, command) 调用序账（兜底链顺序断言用）。 */
    val callLog = CopyOnWriteArrayList<Pair<String, String>>()

    /** subscribeSessionsIndexV4 的回执体（可注水成缺 subscriptionId 形态）。 */
    @Volatile
    var subscribeSessionsIndexReply: JSONValue = JSONValue.Obj(
        mapOf(
            "ok" to JSONValue.Bool(true),
            "ack" to JSONValue.Obj(
                mapOf(
                    "subscriptionId" to JSONValue.Str("sub-sess-test"),
                    "mode" to JSONValue.Str("snapshot"),
                    "logEpoch" to JSONValue.Str("epoch-test"),
                ),
            ),
        ),
    )

    /** 非空 = subscribeSessionsIndexV4 抛错（订阅失败兜底链注水）。 */
    @Volatile
    var subscribeError: Throwable? = null

    /** listSessions 回执行（兜底读注水）。 */
    @Volatile
    var listSessionsItems: List<JSONValue> = emptyList()

    /** true = listSessions 回 {sessions:[…]} 包裹形态；false = 顶层数组形态（宽容双形态）。 */
    @Volatile
    var listSessionsAsObject: Boolean = true

    /** listTaskList 回执 items（task-index 行注水）。 */
    @Volatile
    var listTaskListItems: List<JSONValue> = emptyList()

    /** sessions-index 帧槽（listen 登记；测试直接投递信封载荷）。 */
    @Volatile
    var sessionsFrameHandler: ((RPCValue) -> Unit)? = null

    override suspend fun call(channel: String, command: String, arg: RPCValue, timeout: Duration): RPCValue {
        callLog.add(channel to command)
        return when (command) {
            "subscribeSessionsIndexV4" -> {
                subscribeError?.let { throw it }
                RPCValue.json(subscribeSessionsIndexReply)
            }
            "listSessions" -> {
                val items = JSONValue.Arr(listSessionsItems)
                RPCValue.json(
                    if (listSessionsAsObject) {
                        JSONValue.Obj(mapOf("sessions" to items))
                    } else {
                        items
                    },
                )
            }
            "listTaskList" -> RPCValue.json(
                JSONValue.Obj(mapOf("items" to JSONValue.Arr(listTaskListItems))),
            )
            else -> throw RPCError(message = "fake: unhandled $command", name = "ErrorObj")
        }
    }

    override suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue,
        handler: (RPCValue) -> Unit,
    ): EventSubscription {
        if (event == RemoteSessionsIndexStore.EVENT_SESSIONS_INDEX_FRAME) {
            sessionsFrameHandler = handler
        }
        return EventSubscription { }
    }

    override suspend fun disconnect() {}
}

/** 测试 JSON 便捷构造（null 值键整体省略；与 teststub 的 stubObj 同口径的 store 侧复制）。 */
internal fun testObj(vararg pairs: Pair<String, JSONValue?>): JSONValue {
    val fields = LinkedHashMap<String, JSONValue>()
    for ((key, value) in pairs) {
        if (value != null) fields[key] = value
    }
    return JSONValue.Obj(fields)
}

/** Long 值落 JSONValue（毫秒时间戳：越 Int32 落整值 double，线缆为整型字面量）。 */
internal fun testNum(value: Long): JSONValue = JSONValue.fromAny(value) ?: JSONValue.Null

internal fun awaitUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
        Thread.sleep(20)
    }
}

/** 挂起版轮询（condition 内可调 suspend 读面，如 store.currentConversations()）。 */
internal suspend fun awaitUntilSuspend(
    description: String,
    timeoutMs: Long = 5_000,
    condition: suspend () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
        kotlinx.coroutines.delay(20)
    }
}

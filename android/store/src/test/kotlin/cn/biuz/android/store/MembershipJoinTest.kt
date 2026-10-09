package cn.biuz.android.store

// membership join 单测（M7；A15 + 交接 §5-组织态）：
// - membershipScopes：全工作区清单 ∪ bootstrap 派生 ∪ 当前工作区保底，路径去重，
//   每路径两问（listPinnedTasks + listArchivedTasks，pin 在前），identity 反查；
// - refreshTaskMembership：逐 scope **并发**拉取【实证·上游仓 taskListMembershipSets.ts】、
//   单区失败跳过（web 同构）、扁平合并写 membership 集；
// - 渲染三级合成（iOS :654-659）：本地 override ?? membership ?? 行字段。

import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MembershipJoinTest {

    private val ws = "/Users/mac/zcode_mobile"

    /** membership 专用替身：按 (method, workspacePath) 注水 id 集；失败键抛错（单问失败注水）。 */
    private class MembershipTransport(
        private val pinned: Map<String, List<String>>,
        private val archived: Map<String, List<String>>,
        /** 失败键集（"method:path"）——精确到单问，证明 pin/archive 两问互不拖垮。 */
        private val failingKeys: Set<String> = emptySet(),
    ) : RPCChannelTransport {

        /** (method → workspacePath) 调用记账（并发性断言辅助 + scope 覆盖面断言）。 */
        val calls = ArrayList<Pair<String, String>>()
        val args = ArrayList<Map<String, JSONValue>>()

        override suspend fun call(
            channel: String,
            command: String,
            arg: RPCValue,
            timeout: kotlin.time.Duration,
        ): RPCValue {
            val dict = arg.jsonValue?.objectValue ?: emptyMap()
            val path = dict["workspacePath"]?.stringValue ?: ""
            calls.add(command to path)
            args.add(dict)
            if ("$command:$path" in failingKeys) {
                throw RPCError(message = "scope down: $command $path", name = "ErrorObj")
            }
            val ids = when (command) {
                "listPinnedTasks" -> pinned[path] ?: emptyList()
                "listArchivedTasks" -> archived[path] ?: emptyList()
                else -> throw RPCError(message = "fake: unhandled $command", name = "ErrorObj")
            }
            return RPCValue.json(
                testObj("items" to JSONValue.Arr(ids.map { testObj("taskId" to JSONValue.Str(it)) })),
            )
        }

        override suspend fun listen(
            channel: String,
            event: String,
            arg: RPCValue,
            handler: (RPCValue) -> Unit,
        ): EventSubscription = EventSubscription { }

        override suspend fun disconnect() {}
    }

    // MARK: membershipScopes（scope 枚举）

    @Test
    fun membershipScopes_unionDedupes_andPairsPinArchivePerPath() {
        val all = listOf(
            StoreWorkspaceInfo("/ws/a", workspaceIdentity = "id-a"),
            StoreWorkspaceInfo("/ws/b", workspaceIdentity = "id-b"),
        )
        val bootstrap = listOf(
            StoreWorkspaceInfo("/ws/b", workspaceIdentity = "id-b"),
            StoreWorkspaceInfo("/ws/c", workspaceIdentity = null),
        )
        val current = StoreWorkspaceInfo("/ws/d", workspaceIdentity = "id-d")
        val scopes = RemoteConversationStore.membershipScopes(all, bootstrap, current)
        // 去重后 4 路径 × 2 问 = 8；pin 在前
        assertEquals(
            listOf(
                Triple("/ws/a", "id-a", true),
                Triple("/ws/a", "id-a", false),
                Triple("/ws/b", "id-b", true),
                Triple("/ws/b", "id-b", false),
                Triple("/ws/c", null, true),
                Triple("/ws/c", null, false),
                Triple("/ws/d", "id-d", true),
                Triple("/ws/d", "id-d", false),
            ),
            scopes,
            "清单去重 + 每路径两问（pin 在前）+ identity 反查",
        )
    }

    @Test
    fun membershipScopes_currentIdentityAppliedWhenAbsentFromLists() {
        // 当前工作区只在 current 清单（all/bootstrap 均无）→ identity 取 current
        val scopes = RemoteConversationStore.membershipScopes(
            all = emptyList(),
            bootstrap = emptyList(),
            current = StoreWorkspaceInfo("/ws/cur", workspaceIdentity = "cur-ident"),
        )
        assertEquals(
            listOf(
                Triple("/ws/cur", "cur-ident", true),
                Triple("/ws/cur", "cur-ident", false),
            ),
            scopes,
        )
    }

    // MARK: refreshTaskMembership（并发拉取 + 单区失败跳过）

    @Test
    fun refreshTaskMembership_concurrentFetch_flattensIds_singleScopeFailureSkipped() = runBlocking {
        val transport = MembershipTransport(
            pinned = mapOf(
                "/ws/a" to listOf("sess-pin-1", "sess-pin-2"),
                // /ws/b pinned 问失败（单问失败跳过——web 同构，不拖垮其余问）
            ),
            archived = mapOf(
                "/ws/a" to listOf("sess-arch-1"),
                "/ws/b" to listOf("sess-arch-2"),
            ),
            failingKeys = setOf("listPinnedTasks:/ws/b"),
        )
        val index = RemoteSessionsIndexStore(transport, StoreWorkspaceInfo("/ws/a"))
        val store = RemoteConversationStore(
            transport = transport,
            frameSurface = object : cn.biuz.android.store.ConversationFrameSurface {
                override suspend fun ensureConversationFrameStream(workspacePath: String, workspaceIdentity: String?) {}
                override fun setFrameHandler(topic: String, handler: (cn.biuz.android.protocol.V4TopicFrame) -> Unit) {}
                override fun removeFrameHandler(topic: String) {}
                override fun setFrameDropHandler(key: String, handler: () -> Unit) {}
            },
            workspace = StoreWorkspaceInfo("/ws/a", workspaceIdentity = "id-a"),
            sessionsIndex = index,
            clientId = "test-client-1",
        )
        store.allWorkspaces = listOf(
            StoreWorkspaceInfo("/ws/a", workspaceIdentity = "id-a"),
            StoreWorkspaceInfo("/ws/b", workspaceIdentity = "id-b"),
        )

        store.refreshTaskMembership()

        // 逐 scope 两问全覆盖（2 路径 × 2 方法）
        assertEquals(4, transport.calls.size)
        assertTrue(transport.calls.any { it == ("listPinnedTasks" to "/ws/a") })
        assertTrue(transport.calls.any { it == ("listPinnedTasks" to "/ws/b") })
        assertTrue(transport.calls.any { it == ("listArchivedTasks" to "/ws/a") })
        assertTrue(transport.calls.any { it == ("listArchivedTasks" to "/ws/b") })
        // membership 集扁平合并（失败区跳过不拖垮其余）
        assertEquals(setOf("sess-pin-1", "sess-pin-2"), index.pinnedTaskIds)
        assertEquals(setOf("sess-arch-1", "sess-arch-2"), index.archivedTaskIds)
    }

    @Test
    fun refreshTaskMembership_scopeArgs_carryWorkspaceEnvelope() = runBlocking {
        val transport = MembershipTransport(emptyMap(), emptyMap())
        val index = RemoteSessionsIndexStore(transport, StoreWorkspaceInfo("/ws/cur", workspaceIdentity = "cur-i"))
        val store = RemoteConversationStore(
            transport = transport,
            frameSurface = object : cn.biuz.android.store.ConversationFrameSurface {
                override suspend fun ensureConversationFrameStream(workspacePath: String, workspaceIdentity: String?) {}
                override fun setFrameHandler(topic: String, handler: (cn.biuz.android.protocol.V4TopicFrame) -> Unit) {}
                override fun removeFrameHandler(topic: String) {}
                override fun setFrameDropHandler(key: String, handler: () -> Unit) {}
            },
            workspace = StoreWorkspaceInfo("/ws/cur", workspaceIdentity = "cur-i"),
            sessionsIndex = index,
            clientId = "test-client-1",
        )
        store.refreshTaskMembership()
        // scope 参数 {workspacePath, workspaceIdentity?}（identity 在场才携——web v4 scope 转换器同构）
        assertEquals(2, transport.args.size)
        assertTrue(transport.args.all { it["workspacePath"]?.stringValue == "/ws/cur" })
        assertTrue(transport.args.all { it["workspaceIdentity"]?.stringValue == "cur-i" })
    }

    // MARK: 渲染三级合成（本地 override ?? membership ?? 行字段）

    @Test
    fun toConversations_threeLevelSynthesis_overrideMembershipSummary() = runBlocking {
        val table = SessionMergeTable()
        // 行字段：summary.pinned=false / summary.archived=false（宽容键在场的旧投影）
        table.applySessionsSnapshot(
            testObj(
                "sessions" to JSONValue.Arr(
                    listOf(
                        testObj(
                            "sessionId" to JSONValue.Str("sess-1"),
                            "title" to JSONValue.Str("三级合成"),
                            "phase" to JSONValue.Str("completedSuccess"),
                            "lastActivityAt" to testNum(1_767_000_000_000L),
                            "workspacePath" to JSONValue.Str(ws),
                            "pinned" to JSONValue.Bool(false),
                            "archived" to JSONValue.Bool(false),
                        ),
                    ),
                ),
            ),
        )
        val pinnedIds = setOf("sess-1")
        val archivedIds = emptySet<String>()

        // ① 无 override：membership 权威（行字段 pinned=false 让位 membership）
        var row = table.toConversations(pinnedIds = pinnedIds, archivedIds = archivedIds).single()
        assertTrue(row.isPinned, "membership join 是组织态权威源（A15：行无 pinned 字段 schema 冻结；宽容键不越权）")
        assertTrue(!row.isArchived)

        // ② 本地 override 最高级：置顶 override=false 压过 membership=true（操作即时反馈）
        row = table.toConversations(
            pinnedOverrides = mapOf("sess-1" to false),
            pinnedIds = pinnedIds,
            archivedIds = archivedIds,
        ).single()
        assertTrue(!row.isPinned, "本地 override ?? membership ?? summary")

        // ③ 全缺：行字段兜底（false）
        row = table.toConversations().single()
        assertTrue(!row.isPinned && !row.isArchived)
    }
}

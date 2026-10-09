package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.store.EmptyConversationStore
import cn.biuz.android.store.EmptyFileStore
import cn.biuz.android.store.EmptyTaskStore
import cn.biuz.android.store.RemoteTaskStore
import cn.biuz.android.teststub.E2EStubServer
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * AppSession 装配层验收（iOS AppSession.swift 直译面）：
 * ① 服务器注册表语义（iOS ServerRegistry upsert 队首插入/remove 清 selected/selectedServer）；
 * ② bootstrap.tasks → 工作区派生（workspaceIdentity trim/补身/去重保序）；
 * ③ 装配生命周期（iOS 装配策略三段直译）：未连接 = Empty 三件 → 连接成功 = 真实 Store
 *    装配 + epoch+1 + 注册表收口 → 断线保留最后快照（不 teardown，iOS 13-③）→
 *    连接失败 = ConnectFailed + teardown 回 Empty（epoch 续增，换源重绑）；
 * ④ 冷启动 bootstrap（已存服务器 → 自动重连；未配置 → demo）；
 * ⑤ 中继配置连接诚实失败（M6 前不伪装局域网建链）。
 */
class AppSessionAssemblyTest {

    // MARK: ① ServerRegistry 语义（iOS KeychainStore.swift:166-232 直译面）

    @Test
    fun serverRegistry_upsertInsertsAtHead_removeClearsSelected() {
        val persistence = InMemoryServerRegistryPersistence()
        val registry = ServerRegistry(persistence)
        val lan = ServerConfig(id = "lan", name = null, host = "192.168.1.24", port = 3030, useTLS = false, token = "t1")
        val other = ServerConfig(id = "other", name = null, host = "10.0.0.2", port = 3030, useTLS = false, token = "")

        assertEquals(emptyList(), registry.servers)
        assertNull(registry.selectedServer)

        registry.upsert(lan)
        registry.upsert(other)
        // 新服务器插入队首（最近连接优先，iOS `insert(config, at: 0)`）
        assertEquals(listOf(other, lan), registry.servers)

        // 已在册按 id 原位更新（不换位）
        registry.upsert(lan.copy(token = "t2"))
        assertEquals(listOf(other, lan.copy(token = "t2")), registry.servers)

        registry.selectedServerId = "other"
        assertEquals(other, registry.selectedServer)

        // remove 清选中态（iOS remove :196-204）
        registry.remove("other")
        assertEquals(listOf(lan.copy(token = "t2")), registry.servers)
        assertNull(registry.selectedServerId)

        // clear（E2E 复位面）
        registry.clear()
        assertEquals(emptyList(), registry.servers)
    }

    // MARK: ② bootstrap.tasks → 工作区派生（iOS deriveBootstrapWorkspaces :1264-1286 直译面）

    @Test
    fun deriveBootstrapWorkspaces_identityTrimBackfillAndOrder() {
        val tasks = JSONValue.parse(
            """
            [
              {"taskId":"t1","workspacePath":"/a/b","workspaceIdentity":" id-1 ","title":"x"},
              {"taskId":"t2","workspacePath":"/a/b","title":"y"},
              {"taskId":"t3","workspacePath":"/a/c","workspaceIdentity":"   "},
              {"taskId":"t4","title":"no path"},
              {"taskId":"t5","workspacePath":"","title":"empty path"},
              {"taskId":"t6","workspacePath":"/a/b","workspaceIdentity":" id-2 "}
            ]
            """.trimIndent(),
        )
        val workspaces = AppSession.deriveBootstrapWorkspaces(tasks)
        // 按 path 去重保序；无 path/空 path 行剔除
        assertEquals(listOf("/a/b", "/a/c"), workspaces.map { it.path })
        // label 取路径末段；identity trim 后生效，多行补首个非空身份
        assertEquals("id-1", workspaces[0].workspaceIdentity)
        assertEquals("b", workspaces[0].label)
        // 纯空白 identity 视为缺席（不回填）
        assertNull(workspaces[1].workspaceIdentity)
        assertEquals("c", workspaces[1].label)

        // 非 tasks JSON → 空清单（iOS guard let items else return []）
        assertEquals(emptyList(), AppSession.deriveBootstrapWorkspaces(JSONValue.Str("not-array")))
        assertEquals(emptyList(), AppSession.deriveBootstrapWorkspaces(null))
    }

    // MARK: ③+④ 装配生命周期 + 冷启动（对替身走真实五步连接）

    @Test
    fun assembly_bootstrapConnect_dropKeepsSnapshot_failureTeardowns(): Unit = runBlocking {
        val server = E2EStubServer()
        val port = server.start()
        val persistence = InMemoryServerRegistryPersistence()
        val session = AppSession(registryPersistence = persistence)
        val diags = ConcurrentHashMap<String, String>()
        session.diagListener = { key, value -> diags[key] = value }
        try {
            // 未连接态 = Empty 三件（红线：无 Mock；Empty 读空写如实失败）
            assertEquals(AppSession.Mode.Demo, session.mode.value)
            assertEquals(0, session.storeSurface.value.epoch)
            assertTrue(session.storeSurface.value.conversation is EmptyConversationStore)
            assertTrue(session.storeSurface.value.task is EmptyTaskStore)
            assertTrue(session.storeSurface.value.file is EmptyFileStore)
            assertNull(session.storeSurface.value.sessionsIndex)

            // 冷启动装配（iOS bootstrap 同序）：已存服务器 → 自动重连（先装配 Store 再切 mode）
            val config = ServerConfig(id = "e2e", name = null, host = "127.0.0.1", port = port, useTLS = false, token = "")
            persistence.saveServers(listOf(config))
            persistence.saveSelectedId(config.id)
            session.bootstrap()
            assertTrue(session.mode.value is AppSession.Mode.Connected, "冷启自动重连应成功，实际：${session.mode.value}")
            val surface1 = session.storeSurface.value
            assertEquals(1, surface1.epoch)
            assertTrue(surface1.task is RemoteTaskStore, "连接态任务 Store 应为真实实现")
            assertTrue(surface1.sessionsIndex != null, "连接态应装配 sessions-index 订阅面")
            assertEquals(server.workspacePath, surface1.workspace?.path)
            assertTrue(surface1.conversation !is EmptyConversationStore, "连接态会话列表直供订阅面")
            // 注册表收口（iOS connectToSaved 成功分支：lastConnectedAt/name 回填 + selected）
            assertEquals("e2e", persistence.loadSelectedId())
            assertNotNull(persistence.loadServers().firstOrNull { it.id == "e2e" }?.lastConnectedAt)
            // 诊断落点（iOS diag.assemble/diag.lastConnect 同名键）
            assertTrue(diags.containsKey("diag.assemble"), "装配诊断应落账：${diags.keys}")
            assertTrue(diags.containsKey("diag.lastConnect"))

            // 断线回落（iOS 13-③）：曾连接后通道中断 → mode=Disconnected，Store 快照保留（不 teardown）
            server.stop()
            awaitUntil("断线回落") { session.mode.value is AppSession.Mode.Disconnected }
            assertEquals(surface1, session.storeSurface.value, "断线保留最后快照（同 Store 实例同 epoch）")

            // 重连失败（桌面已停）→ ConnectFailed + teardown 回 Empty + epoch 续增（换源重绑）
            session.connect(ServerConfig(id = "e2e", name = null, host = "127.0.0.1", port = port, useTLS = false, token = ""))
            val failed = session.mode.value
            assertTrue(failed is AppSession.Mode.ConnectFailed, "桌面已停连接应失败，实际：$failed")
            assertTrue(session.storeSurface.value.conversation is EmptyConversationStore, "失败态回 Empty 三件")
            assertTrue(session.storeSurface.value.task is EmptyTaskStore)
            assertTrue(session.storeSurface.value.sessionsIndex == null)
            assertEquals(2, session.storeSurface.value.epoch, "teardown epoch +1 驱动 UI 换源重绑")
            assertTrue(diags["diag.lastConnect"]?.startsWith("failed:") == true)
        } finally {
            session.connection.disconnect()
            server.stop()
        }
    }

    // MARK: ⑤ 中继配置连接：诚实失败（M6 前不伪装建链；2s/4s 退避重试两轮后 ConnectFailed）

    @Test
    fun relayConfig_connectFailsHonestly_beforeRelayTransport(): Unit = runBlocking {
        val session = AppSession(registryPersistence = InMemoryServerRegistryPersistence())
        val link = cn.biuz.android.protocol.ConnectURLParser.parseRelayLink(
            "https://zcode.z.ai/remote/v4?sid=d_test&hash=abc%3D&t=1&mid=mid-1&name=MacBook",
        ) ?: return@runBlocking fail("配对链接应可解析")
        val config = ServerConfig(id = "relay", name = null, host = "zcode.z.ai", port = 443, useTLS = true, token = "", relay = link)
        val started = System.currentTimeMillis()
        session.connect(config)
        val elapsed = System.currentTimeMillis() - started
        val mode = session.mode.value
        assertTrue(mode is AppSession.Mode.ConnectFailed, "中继配置（M6 前）应如实失败，实际：$mode")
        val error = (mode as AppSession.Mode.ConnectFailed).error
        val detail = (error as? ConnectError.Transport)?.detail
        assertTrue(detail != null && detail.contains("M6"), "失败形态应为 Transport 携 M6 挂账文案，实际：$error")
        // iOS 中继瞬断退避（2s+4s）在此同样走满——不快失败也不伪装成功
        assertTrue(elapsed >= 6_000, "中继退避重试两轮应 ≥6s，实际 ${elapsed}ms")
        assertTrue(session.storeSurface.value.conversation is EmptyConversationStore, "失败态 = Empty 三件")
    }

    // MARK: 夹具

    private fun awaitUntil(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时：$description" }
            Thread.sleep(20)
        }
    }
}

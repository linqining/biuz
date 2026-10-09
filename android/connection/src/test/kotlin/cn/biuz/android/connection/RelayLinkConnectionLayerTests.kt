package cn.biuz.android.connection

import cn.biuz.android.protocol.ConnectURLParser
import cn.biuz.android.protocol.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * M1 挂账回补（2026-10-09，:connection 层落位）：ios/UnitTests/RelayLinkTests.swift 的
 * 两例连接层用例按 iOS 原文断言恢复。
 *
 * 落位说明：ServerConfig（连接层凭据模型）与 AppSession.relayLinkArgument（装配层静态面）
 * 归 :connection；而 :protocol 的测试源集不可反向依赖 :connection（Gradle 工程依赖环禁止
 * ——:connection 依赖 :protocol）。故两例自 android/protocol 的 RelayLinkTests.kt
 * 迁入本文件（原 @Disabled 占位已同步删除，见该文件头注记）。
 */
class RelayLinkConnectionLayerTests {

    /** 真实配对链接形态（交接材料 §1；与 :protocol RelayLinkTests 同向量） */
    private val sampleLink = "https://zcode.z.ai/remote/v4?sid=d_5BHs7zpSQADkcFSNhfud5v" +
        "&hash=juuBpaXioBE9akxEsuG8avtyRigPmmvUvyAb029Hso4%3D&t=1791033721496" +
        "&mid=4499673e-d0f8-49ef-ada8-623c18767c24&name=MacBook-Pro-8.local&app_version=3.14.4"

    // MARK: -ZCodeRelayLink 启动参数（iOS：AppSession.relayLinkArgument(from:)）

    @Test
    fun testRelayLinkArgumentParsing() {
        assertNull(AppSession.relayLinkArgument(emptyList()))
        assertNull(AppSession.relayLinkArgument(listOf("-ZCodeRelayLink")))
        // 相邻参数取值；缺参占位（以 - 开头）忽略
        assertNull(AppSession.relayLinkArgument(listOf("-ZCodeRelayLink", "-ZCodeOpenConnectFlow")))
        assertEquals(
            sampleLink,
            AppSession.relayLinkArgument(listOf("-ZCodeRelayLink", sampleLink, "-ZCodeOpenConnectFlow")),
        )
    }

    // MARK: ServerConfig JSON 编解码向后兼容（旧 Keychain 数据无 relay 字段）

    @Test
    fun testServerConfigDecodesLegacyJSONWithoutRelay() {
        // iOS 原文：JSONDecoder().decode(ServerConfig.self, legacy)
        val legacy = """
            {"id":"a","name":null,"host":"192.168.1.24","port":3030,"useTLS":false,
             "token":"","lastConnectedAt":null,"preferredWorkspacePath":null}
        """.trimIndent()
        val config = ServerConfig.fromJson(JSONValue.parse(legacy))
            ?: return fail("legacy JSON（无 relay 字段）应可解码")
        assertNull(config.relay)
        assertEquals("192.168.1.24:3030", config.displayAddress)

        // 中继配置往返：relayed.relay = parseRelayLink(sampleLink) → encode/decode 往返相等
        val relayed = config.copy(relay = ConnectURLParser.parseRelayLink(sampleLink))
        val encoded = relayed.toJson().data ?: return fail("编码失败")
        val roundTrip = ServerConfig.fromJson(JSONValue.parse(encoded)) ?: return fail("往返解码失败")
        assertEquals(relayed.relay, roundTrip.relay)
        assertEquals("MacBook-Pro-8.local", roundTrip.displayName)
        // Codable 往返全字段相等语义
        assertEquals(relayed, roundTrip)
    }
}

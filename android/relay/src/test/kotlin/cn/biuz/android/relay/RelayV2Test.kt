package cn.biuz.android.relay

import cn.biuz.android.protocol.ConnectURLParser
import cn.biuz.android.protocol.relay.RelayLinkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Relay V2 契约对拍（iOS `RelayTransport.swift:262-379` 同口径）。
 * 只覆盖纯构造 + 回执解析 + 心跳常量；状态机在 E2E 里验。
 */
class RelayV2Test {

    private fun v2Config(mid: String? = "m-1") = RelayLinkConfig(
        wssURL = "wss://zcode.z.ai/api/v1/remote/ws2",
        machineName = "MacBook-Pro",
        deviceSid = "sid-1",
        passHash = null,
        desktopAppVersion = "3.17.2",
        protocolVersion = 2,
        deviceMid = mid,
    )

    // ---- 链接识别 ----

    @Test
    fun linkWithoutHashIsParsedAsV2() {
        val link = ConnectURLParser.parseRelayLink(
            "https://zcode.z.ai/remote/v4?sid=sid-1&mid=m-1&name=MacBook-Pro&app_version=3.17.2",
        )
        assertEquals(true, link?.isV2, "无 hash 的链接应判 V2")
        assertEquals("wss://zcode.z.ai/api/v1/remote/ws2", link?.wssURL, "V2 端点是 ws2")
        assertEquals("m-1", link?.deviceMid)
        assertEquals("https://zcode.z.ai", link?.httpOrigin)
        assertNull(link?.passHash)
    }

    @Test
    fun linkWithHashStaysV1() {
        val link = ConnectURLParser.parseRelayLink(
            "https://zcode.z.ai/remote/v4?sid=sid-1&hash=abc&mid=m-1",
        )
        assertEquals(false, link?.isV2)
        assertEquals("wss://zcode.z.ai/ws?mid=m-1", link?.wssURL)
        assertEquals("abc", link?.passHash)
    }

    // ---- 建连 URL ----

    @Test
    fun socketUrlAppendsTokenAndMidAndDropsStaleOnes() {
        val url = RelayV2.buildSocketUrl(
            baseWssUrl = "wss://zcode.z.ai/api/v1/remote/ws2?mid=old&token=stale",
            token = "tok-1",
            mid = "m-1",
        )
        assertEquals("wss://zcode.z.ai/api/v1/remote/ws2?token=tok-1&mid=m-1", url)
    }

    @Test
    fun socketUrlWithoutMidOmitsMidParam() {
        val url = RelayV2.buildSocketUrl("wss://zcode.z.ai/api/v1/remote/ws2", "tok-1", null)
        assertEquals("wss://zcode.z.ai/api/v1/remote/ws2?token=tok-1", url)
    }

    // ---- auth_init ----

    @Test
    fun authInitCarriesNoRoleAndSecondPrecisionTimestamp() {
        val map = RelayV2.authInit(
            config = v2Config(),
            clientTsSeconds = 1_700_000_000L,
            platform = "android",
            appVersion = "1.0.0",
            clientName = "朱批",
        )
        assertEquals("auth_init", map["type"]?.stringValue)
        assertEquals("sid-1", map["device_sid"]?.stringValue)
        assertNull(map["role"], "V2 auth_init 不带 role")
        // client_ts 必须是秒（服务端拒绝毫秒）
        assertEquals(1_700_000_000, map["client_ts"]?.intValue)
        val meta = map["meta"]?.objectValue
        assertEquals("android", meta?.get("platform")?.stringValue)
        assertEquals("朱批", meta?.get("name")?.stringValue)
    }

    @Test
    fun heartbeatFollowsProtocolVersion() {
        assertEquals(Triple(5.0, 1.0, 15.0), RelayV2.heartbeatSeconds(isV2 = true))
        assertEquals(Triple(10.0, 2.0, 30.0), RelayV2.heartbeatSeconds(isV2 = false))
    }

    // ---- token ----

    @Test
    fun tokenResponseParsing() {
        assertEquals("tok-1", RelayV2.parseTokenResponse("""{"code":0,"data":{"token":"tok-1"}}"""))
        assertNull(RelayV2.parseTokenResponse("""{"code":401,"msg":"unauthorized"}"""))
        assertNull(RelayV2.parseTokenResponse("""{"code":0,"data":{"token":""}}"""))
        assertNull(RelayV2.parseTokenResponse("not json"))
    }

    @Test
    fun tokenFetchMapsHttpErrorsToTypedExceptions() {
        // 不触网：只验「明显不可达端点」不会静默返回成功
        assertFailsWith<RelayTokenException.RequestFailed> {
            RelayV2.fetchToken(httpOrigin = "http://127.0.0.1:1", jwt = "jwt")
        }
    }
}

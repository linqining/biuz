package cn.biuz.android.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OAuth 账户层纯逻辑对拍（iOS `ZaiOAuthProvider.swift` / web `zaiWebOAuthProvider.ts` 同口径）。
 * 不触网络：只验 state 编解码、令牌回执归一化、用户信息兼容形态、设备目录解析与落点顺序。
 */
class OAuthAccountTest {

    private val client = ZaiOAuthClient(OAuthConfig())

    // ---- state ----

    @Test
    fun stateRoundTripsNonceThroughBase64UrlJson() {
        val nonce = "d1230c46-725b-40f1-8a80-28cbd0d188f1"
        val state = OAuthStateCodec.buildState(nonce)
        // 服务端要求 base64url(JSON)：不含 padding，且能被解析回同一个 nonce
        assertTrue(!state.contains("="), "state 不应带 padding：$state")
        assertEquals(nonce, OAuthStateCodec.nonce(state))
    }

    @Test
    fun stateRejectsGarbageWithoutNonce() {
        assertNull(OAuthStateCodec.nonce("not-a-state"))
        assertNull(OAuthStateCodec.nonce(""))
    }

    // ---- 授权 URL ----

    @Test
    fun zaiAuthorizeUrlCarriesRequiredParams() {
        val url = client.buildAuthorizeUrl(OAuthProviderId.ZAI, "STATE")
        assertTrue(url.startsWith("https://chat.z.ai/api/oauth/authorize?"), url)
        assertTrue(url.contains("response_type=code"), url)
        assertTrue(url.contains("client_id=client_P8X5CMWmlaRO9gyO-KSqtg"), url)
        assertTrue(url.contains("state=STATE"), url)
        assertTrue(url.contains("redirect_uri="), url)
    }

    @Test
    fun bigModelAuthorizeUrlUsesRedirectParamName() {
        val url = client.buildAuthorizeUrl(OAuthProviderId.BIGMODEL, "STATE")
        assertTrue(url.startsWith("https://bigmodel.cn/login?"), url)
        assertTrue(url.contains("redirect="), "BigModel 参数名是 redirect 不是 redirect_uri：$url")
        assertTrue(url.contains("appId=zcode"), url)
    }

    // ---- 回调解析 ----

    @Test
    fun callbackParsesCodeAndAuthCodeAlias() {
        val a = client.parseCallbackParams("https://zcode.z.ai/cn/share/callback?code=abc&state=st")
        assertEquals("abc", a.code)
        assertEquals("st", a.state)

        val b = client.parseCallbackParams("https://zcode.z.ai/cn/share/callback?authCode=xyz&state=st")
        assertEquals("xyz", b.code, "authCode 兼容形态")
    }

    @Test
    fun callbackWithoutStateIsRejected() {
        assertFailsWith<OAuthException.StateMismatch> {
            client.parseCallbackParams("https://zcode.z.ai/cn/share/callback?code=abc")
        }
    }

    @Test
    fun remoteEntryCallbackIsRecognizedByParamShape() {
        // 远控入口的 redirect_uri 是 /remote/v4 本身——只按 /cn/share/callback 判会漏拦，
        // WebView 会把回调页跑完并跳到官方域，App 拿不到 code（实测踩坑）。
        assertTrue(
            client.isCallbackUrl(
                "https://zcode.z.ai/remote/v4?sid=d_SNZ&mid=4499&code=abc&state=eyJub25jZSI6IngifQ",
            ),
            "远控入口回调必须被识别",
        )
        assertTrue(client.isCallbackUrl("https://zcode.z.ai/remote/v4?error=access_denied&state=st"))
        assertTrue(
            !client.isCallbackUrl("https://zcode.z.ai/remote/v4?sid=d_SNZ&mid=4499"),
            "普通入口 URL 不是回调",
        )
        assertTrue(!client.isCallbackUrl("https://zcode.z.ai/remote/v4?code=abc"), "缺 state 不算回调")
    }

    @Test
    fun callbackUrlRecognition() {
        assertTrue(client.isCallbackUrl("https://zcode.z.ai/cn/share/callback?code=1&state=2"))
        assertTrue(client.isCallbackUrl("https://zcode.z.ai/share/callback?code=1&state=2"))
        assertTrue(!client.isCallbackUrl("https://zcode.z.ai/remote/v4"))
    }

    // ---- 令牌回执归一化 ----

    @Test
    fun tokenResponseNormalizesZaiShape() {
        val body = """
            {"code":0,"data":{"token":"jwt-1","expires_in":3600,
             "zai":{"access_token":"at-1"},
             "user":{"id":"u1","username":"traveler","displayName":"旅行者0554"}}}
        """.trimIndent()
        val creds = client.normalizeTokenResponse(body, OAuthProviderId.ZAI)
        assertEquals("jwt-1", creds.tokenSet.zcodeJwtToken)
        assertEquals("at-1", creds.tokenSet.accessToken)
        assertEquals("旅行者0554", creds.userInfo.displayName)
        assertTrue((creds.tokenSet.expiresAt ?: 0) > System.currentTimeMillis())
    }

    @Test
    fun tokenResponseNormalizesBigModelShape() {
        val body = """{"code":0,"data":{"token":"jwt-2","bigmodel":{"access_token":"at-2"}}}"""
        val creds = client.normalizeTokenResponse(body, OAuthProviderId.BIGMODEL)
        assertEquals("at-2", creds.tokenSet.accessToken)
        assertEquals("User", creds.userInfo.displayName, "缺 user 时回落占位身份")
    }

    @Test
    fun tokenResponseRejectsNonZeroCodeAndMissingFields() {
        assertFailsWith<OAuthException.ExchangeFailed> {
            client.normalizeTokenResponse("""{"code":1001,"msg":"invalid state"}""", OAuthProviderId.ZAI)
        }
        assertFailsWith<OAuthException.ExchangeFailed> {
            client.normalizeTokenResponse("""{"code":0,"data":{"zai":{"access_token":"x"}}}""", OAuthProviderId.ZAI)
        }
        assertFailsWith<OAuthException.ExchangeFailed> {
            client.normalizeTokenResponse("not json", OAuthProviderId.ZAI)
        }
    }

    @Test
    fun userInfoSupportsFallbackShapeAndAvatarNormalization() {
        val json = cn.biuz.android.protocol.JSONValue.parse(
            """{"user_id":"42","name":"","email":"a@b.c","avatar":"aGVsbG8gd29ybGQgYmFzZTY0"}""",
        )
        val info = client.toUserInfo(json)
        assertEquals("42", info?.id)
        assertEquals("a@b.c", info?.username, "name 空时回退 email")
        assertEquals(
            "data:image/png;base64,aGVsbG8gd29ybGQgYmFzZTY0",
            info?.avatarUrl,
            "裸 base64（≥16 字符）补 data URI",
        )
        // 短串不补（iOS 同口径 count >= 16）
        assertEquals("aGVsbG8=", client.normalizeAvatarUrl("aGVsbG8="))
    }

    // ---- 设备目录 ----

    @Test
    fun deviceCatalogParsesRowsAndDropsIncompleteOnes() {
        val body = """
            {"code":0,"data":[
              {"device_sid":"s1","device_mid":"m1","online":true,"created_at":1700000000,
               "meta":{"name":"MacBook-Pro","platform":"darwin","version":"3.17.2"}},
              {"device_sid":"","device_mid":"m2","online":true},
              {"device_mid":"m3"}
            ]}
        """.trimIndent()
        val devices = RelayDeviceCatalog.parseDevices(body)
        assertEquals(1, devices?.size, "缺 sid/mid 的行应丢弃：$devices")
        assertEquals("MacBook-Pro", devices!![0].name)
        assertEquals("3.17.2", devices[0].version)
        assertEquals(true, devices[0].online)
    }

    @Test
    fun deviceCatalogRejectsErrorPayload() {
        assertNull(RelayDeviceCatalog.parseDevices("""{"code":401,"msg":"unauthorized"}"""))
        assertNull(RelayDeviceCatalog.parseDevices(null))
    }

    @Test
    fun autoConnectPrefersSavedThenLastThenUniqueMidThenUniqueOnline() {
        val devices = listOf(
            RelayDeviceSummary("s1", "m1", online = true),
            RelayDeviceSummary("s2", "m2", online = true),
            RelayDeviceSummary("s3", "m3", online = false),
        )
        // saved SID 优先
        assertEquals("s1", RelayDeviceCatalog.selectAutoConnectDevice(devices, "s1", "s2", null)?.deviceSid)
        // last SID 次之
        assertEquals("s2", RelayDeviceCatalog.selectAutoConnectDevice(devices, null, "s2", null)?.deviceSid)
        // last MID 唯一在线
        assertEquals("s2", RelayDeviceCatalog.selectAutoConnectDevice(devices, null, null, "m2")?.deviceSid)
        // 多设备无偏好 → null（交 UI 选择）
        assertNull(RelayDeviceCatalog.selectAutoConnectDevice(devices, null, null, null))
        // 唯一在线 → 直接落点
        assertEquals(
            "s1",
            RelayDeviceCatalog.selectAutoConnectDevice(listOf(devices[0], devices[2]), null, null, null)?.deviceSid,
        )
    }

    @Test
    fun autoConnectIgnoresOfflineCandidates() {
        val devices = listOf(
            RelayDeviceSummary("s1", "m1", online = false),
            RelayDeviceSummary("s2", "m2", online = true),
        )
        // 离线设备不能作为落点；唯一在线设备直接胜出
        assertEquals(
            "s2",
            RelayDeviceCatalog.selectAutoConnectDevice(devices, "s1", null, null)?.deviceSid,
            "saved SID 离线时应跳过、落到唯一在线设备",
        )
        assertEquals("s2", RelayDeviceCatalog.selectAutoConnectDevice(devices, null, null, null)?.deviceSid)
        // 全部离线 → 无落点
        val allOffline = devices.map { it.copy(online = false) }
        assertNull(RelayDeviceCatalog.selectAutoConnectDevice(allOffline, "s1", "s2", "m2"))
    }

    // ---- 凭据存储 ----

    @Test
    fun credentialStorePersistsAndReloadsAndClears() {
        val backing = InMemoryPersistence()
        val store = OAuthCredentialStore(backing)
        val tokenSet = OAuthTokenSet("at", "jwt", expiresAt = System.currentTimeMillis() + 60_000)
        val user = OAuthUserInfo("u1", "traveler", "旅行者0554")
        store.save(tokenSet, user)

        val reloaded = OAuthCredentialStore(backing)
        assertEquals("jwt", reloaded.tokenSet?.zcodeJwtToken)
        assertEquals("旅行者0554", reloaded.userInfo?.displayName)
        assertEquals(false, reloaded.isExpired())

        reloaded.clear()
        assertNull(OAuthCredentialStore(backing).tokenSet, "clear 后重启应读不到凭据")
    }

    @Test
    fun expiredTokenIsReported() {
        val backing = InMemoryPersistence()
        val store = OAuthCredentialStore(backing)
        store.save(
            OAuthTokenSet("at", "jwt", expiresAt = System.currentTimeMillis() - 1),
            OAuthUserInfo("u", "u", "U"),
        )
        assertEquals(true, store.isExpired())
    }

    // ---- Relay V2 链接持久化 ----

    @Test
    fun v2RelayLinkSurvivesServerConfigRoundTrip() {
        val link = cn.biuz.android.protocol.relay.RelayLinkConfig(
            wssURL = "wss://zcode.z.ai/api/v1/remote/ws2",
            machineName = "MacBook-Pro",
            deviceSid = "sid-1",
            passHash = null,
            desktopAppVersion = "3.17.2",
            protocolVersion = 2,
            deviceMid = "mid-1",
        )
        val server = ServerConfig(
            id = "id-1",
            name = "Mac",
            host = "zcode.z.ai",
            port = 443,
            useTLS = true,
            token = "",
            relay = link,
        )
        val restored = ServerConfig.fromJson(server.toJson())
        assertEquals(true, restored?.relay?.isV2, "V2 判定必须随持久化往返保留")
        assertEquals("mid-1", restored?.relay?.deviceMid)
        assertNull(restored?.relay?.passHash, "V2 无 passHash")
        assertEquals("https://zcode.z.ai", restored?.relay?.httpOrigin)
    }

    private class InMemoryPersistence : OAuthCredentialPersistence {
        private var payload: String? = null
        override fun load(): String? = payload
        override fun save(payload: String): Boolean {
            this.payload = payload
            return true
        }
        override fun clear() {
            payload = null
        }
    }
}

package cn.biuz.android.protocol.relay

import cn.biuz.android.protocol.UrlComponents
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// 直译自 ios/ZCodeMobileApp/Sources/Services/Relay/RelayLink.swift（105 行）。
// 机械适配（逐处注明）：
// - CryptoKit HMAC<SHA256>/SymmetricKey → javax.crypto.Mac("HmacSHA256")（JDK 自带，
//   protocol 模块零第三方依赖口径不变）；
// - base64URLNoPadding：Swift 标准表转 URL 表去 padding → java.util.Base64.getUrlEncoder()
//   .withoutPadding()（产出逐字节相同）；
// - RelayLinkConfig 的 Codable 持久化面不在 protocol 模块实装（依赖骨架无序列化运行时，
//   同 Models.kt 文件头约定——Keychain/EncryptedSharedPreferences 归装配层）；
// - Swift enum 带 associated value → sealed class（relayUnavailable(String)），
//   无载荷 case → data object（既有 sealed 类口径，同 RPCSerialization.kt JSONValue）。
// 上游 bundle 指位注释（src-D3H6NV7w.js …）逐字保留。

// MARK: - 云中继链接配置（remote/v4 配对链接 → WS 端点 + 会话凭据）

/**
 * 配对链接解析产物（逆向结论⑦）：`https://<host>/remote/v4?sid=…&hash=…&mid=…`
 * - sid/hash 不进 WS URL：仅用于 auth_init/auth_response 帧（hash 保留为字符串作 HMAC 密钥，
 *   不做 base64 解码）；WS 端点 `wss://<host>/ws?mid=<mid>`（src-D3H6NV7w.js@179516 xh + Lh）。
 * （RelayLink.swift:9-24）
 */
data class RelayLinkConfig(
    /** wss 端点（V1：`/ws?mid=…`；V2：`/api/v1/remote/ws2`，token 在建连时追加） */
    val wssURL: String,
    /** 配对链接 name 参数（桌面机器名，UI 展示用） */
    val machineName: String?,
    /** 配对链接 sid 参数（桌面会话凭据，auth 帧 device_sid） */
    val deviceSid: String,
    /** V1 配对链接 hash（HMAC 密钥）；V2 无此字段（null） */
    val passHash: String? = null,
    /** 配对链接 app_version 参数（桌面版本，诊断对照 bootstrap-response.desktopAppVersion） */
    val desktopAppVersion: String? = null,
    /** 1/nil = V1（兼容 3.16.2 之前桌面）；2 = V2（3.16.2 起，JWT + 一次性 token） */
    val protocolVersion: Int? = null,
    /** V2 网关路由提示 mid（ws2?mid=…） */
    val deviceMid: String? = null,
) {
    /** URLComponents(string: wssURL)?.host 对应物（RelayLink.swift:21-23）。 */
    val endpointHost: String?
        get() = UrlComponents.parse(wssURL)?.host

    /**
     * V2 判定（iOS `RelayLinkConfig.isV2`：protocolVersion == 2）。
     * V1 走 auth_challenge/auth_response 的 HMAC 挑战；V2 走 JWT 换一次性 token + auth_init。
     */
    val isV2: Boolean
        get() = protocolVersion == 2

    /** V2 HTTP 面 origin（`/api/v1/remote/token` 挂在同一 host；iOS `httpOrigin` 同口径）。 */
    val httpOrigin: String?
        get() {
            val components = UrlComponents.parse(wssURL) ?: return null
            val host = components.host ?: return null
            return "https://$host"
        }
}

// MARK: - 关闭/失败原因（src-D3H6NV7w.js TN close code 表 + error.code 表）

/**
 * 中继连接终态/中间态原因（人类可读口径进 L2/L3 日志）（RelayLink.swift:29-83）。
 * Swift `enum RelayCloseReason: Equatable` → sealed class：无载荷 case = data object，
 * relayUnavailable(String) 带 detail。
 */
sealed class RelayCloseReason {
    data object SessionNotFound : RelayCloseReason() // WS 4004

    data object SessionConflict : RelayCloseReason() // WS 4009 / error KICKED

    data object DesktopDisconnected : RelayCloseReason() // WS 4010

    data object SessionExpired : RelayCloseReason() // WS 4011

    data object WorkspaceClosed : RelayCloseReason() // WS 4012

    data object InvalidMobileConnection : RelayCloseReason() // WS 4013 / error AUTH_FAILED|WRONG_PARAM / waiting 30s 超时

    data object DeviceOffline : RelayCloseReason() // error DEVICE_OFFLINE（15s 宽限重连）

    /** 其余（网络/服务器 INTERNAL 等） */
    data class RelayUnavailable(val detail: String) : RelayCloseReason()

    val isTerminal: Boolean
        get() = when (this) {
            is SessionNotFound, is SessionConflict, is SessionExpired, is InvalidMobileConnection -> true
            // 4010/4012/DEVICE_OFFLINE/网络类：桌面侧暂态，可重连恢复
            is DesktopDisconnected, is WorkspaceClosed, is DeviceOffline, is RelayUnavailable -> false
        }

    /** 错误码行（L3 codeLine 口径）（iOS String(localized:) 键 = 同串，见文件头约定）。 */
    val codeText: String
        get() = when (this) {
            is SessionNotFound -> "SESSION-NOT-FOUND · WS 4004"
            is SessionConflict -> "SESSION-CONFLICT · WS 4009 / KICKED"
            is DesktopDisconnected -> "DESKTOP-DISCONNECTED · WS 4010"
            is SessionExpired -> "SESSION-EXPIRED · WS 4011"
            is WorkspaceClosed -> "WORKSPACE-CLOSED · WS 4012"
            is InvalidMobileConnection -> "INVALID-MOBILE-CONNECTION · WS 4013 / AUTH_FAILED"
            is DeviceOffline -> "DEVICE-OFFLINE · 宽限重连"
            is RelayUnavailable -> "RELAY-UNAVAILABLE · $detail"
        }

    companion object {
        /** `from(closeCode:)` 对应物（RelayLink.swift:63-73）；未知 close code → null。 */
        fun from(closeCode: Int): RelayCloseReason? =
            when (closeCode) {
                4004 -> SessionNotFound
                4009 -> SessionConflict
                4010 -> DesktopDisconnected
                4011 -> SessionExpired
                4012 -> WorkspaceClosed
                4013 -> InvalidMobileConnection
                else -> null
            }

        /** `from(errorCode:message:)` 对应物（RelayLink.swift:75-82）。 */
        fun from(errorCode: String, message: String): RelayCloseReason =
            when (errorCode) {
                "KICKED" -> SessionConflict
                "DEVICE_OFFLINE" -> DeviceOffline
                "AUTH_FAILED", "WRONG_PARAM" -> InvalidMobileConnection
                else -> RelayUnavailable(if (message.isEmpty()) errorCode else "$errorCode $message")
            }
    }
}

// MARK: - 鉴权 proof（lVn @ src-D3H6NV7w.js:336279）

/** Swift caseless enum RelayAuth → object（RelayLink.swift:87-105）。 */
object RelayAuth {

    /**
     * proof = base64url 无填充(HMAC-SHA256(key=UTF8(passHash), msg=`${nonce}|terminal|${deviceSid}`))
     * 实测向量：nonce=lFr2Po5uGyuNti2Xuw7LfcrN / hash=juuBpa…o4= / sid=d_5BHs7zpSQADkcFSNhfud5v
     *       → TVEonHQISlbmNucXTEYrnfL0D3v00eO075w66jFq8Es（ws_probe.py 实测，auth_ack matched）
     */
    fun proof(passHash: String, nonce: String, role: String, deviceSid: String): String {
        val message = "$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8)
        val key = SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val digest = mac.doFinal(message)
        return base64URLNoPadding(digest)
    }

    fun base64URLNoPadding(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}

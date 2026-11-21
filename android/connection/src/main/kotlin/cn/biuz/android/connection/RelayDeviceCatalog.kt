package cn.biuz.android.connection

import cn.biuz.android.protocol.JSONValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Relay V2 账号设备目录（iOS `AppSession.swift:1293-1494` 同构）。
 *
 * `GET /api/v1/remote/device`（Bearer JWT）→ 设备列表；`selectAutoConnectDevice` 复刻官方
 * web `resolveAutoConnectSid` 的落点顺序：saved SID → last SID → last MID 唯一在线 → 唯一在线设备。
 * 目录不可用返回 null（**不**等同于「没有设备」，调用方要回退本地已配对中继）。
 */
/**
 * 006 多实例：同 MID 设备组（web WebRemoteControlDeviceGroup 同构，
 * web-remote-control/contract.ts:6-12）。onlineConnections 为可变列表仅供
 * [RelayDeviceCatalog.groupDevicesByMid] 聚合填充。
 */
data class RelayDeviceGroup(
    val deviceMid: String,
    val name: String? = null,
    var platform: String? = null,
    val onlineConnections: MutableList<RelayDeviceSummary> = mutableListOf(),
) {
    val displayName: String
        get() = name ?: deviceMid
}

data class RelayDeviceSummary(
    val deviceSid: String,
    val deviceMid: String,
    val online: Boolean,
    val createdAt: Int? = null,
    val name: String? = null,
    val platform: String? = null,
    val version: String? = null,
) {
    val displayName: String
        get() {
            val trimmed = name?.trim().orEmpty()
            return if (trimmed.isEmpty()) deviceMid else trimmed
        }
}

class RelayDeviceCatalog(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /** 拉取设备目录。任何失败（网络/非 2xx/code≠0/形状不符）都返回 null。 */
    fun fetch(tokenOrigin: String, jwt: String, timeoutSeconds: Long = 12): List<RelayDeviceSummary>? {
        if (jwt.isBlank()) return null
        val base = if (tokenOrigin.endsWith("/")) tokenOrigin.dropLast(1) else tokenOrigin
        val request = Request.Builder()
            .url("$base/api/v1/remote/device")
            .header("Authorization", "Bearer $jwt")
            .get()
            .build()
        return try {
            httpClient.newBuilder()
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    val body = response.body?.string()
                    println("[朱批] device catalog http=" + response.code + " len=" + (body?.length ?: 0))
                    if (response.code !in 200..299) {
                        println("[朱批] device catalog body=" + (body?.take(160) ?: ""))
                        return null
                    }
                    parseDevices(body)
                }
        } catch (err: Exception) {
            println("[朱批] device catalog failed: " + err::class.simpleName + ": " + err.message)
            null
        }
    }

    companion object {
        /**
         * 回执解析：`{ code: 0, data: [{ device_sid, device_mid, online, created_at, meta{name,platform,version} }] }`。
         * sid/mid 任一缺失的行直接丢弃（与 iOS compactMap 同口径）。
         */
        fun parseDevices(body: String?): List<RelayDeviceSummary>? {
            val json = body?.let { runCatching { JSONValue.parse(it) }.getOrNull() } ?: return null
            val code = json["code"]?.intValue
            if (code != null && code != 0) return null
            val rows = json["data"]?.arrayValue ?: return null
            return rows.mapNotNull { row ->
                val sid = row["device_sid"]?.stringValue
                val mid = row["device_mid"]?.stringValue
                if (sid.isNullOrEmpty() || mid.isNullOrEmpty()) return@mapNotNull null
                val meta = row["meta"]
                RelayDeviceSummary(
                    deviceSid = sid,
                    deviceMid = mid,
                    online = row["online"]?.boolValue ?: false,
                    createdAt = row["created_at"]?.intValue,
                    name = meta?.get("name")?.stringValue,
                    platform = meta?.get("platform")?.stringValue,
                    version = meta?.get("version")?.stringValue,
                )
            }
        }

        /**
         * 006 多实例：同 MID 设备组（web `groupWebRemoteControlDevices` 直译，
         * webRemoteControlDeviceGroups.ts:12-56）——MID 只负责设备展示，保留全部
         * 在线 SID（连接与业务状态仍按 SID 隔离）；历史离线注册不生成设备行。
         * 排序：online 优先 → 无名靠后 → name（collator 简化为字典序——组头仅
         * 展示用）→ mid → sid。
         */
        fun groupDevicesByMid(devices: List<RelayDeviceSummary>): List<RelayDeviceGroup> {
            val sorted = devices.sortedWith(
                compareBy(
                    { it.online.not() },
                    { it.name?.trim().isNullOrEmpty() },
                    { it.name?.trim().orEmpty() },
                    { it.deviceMid },
                    { it.deviceSid },
                ),
            )
            val groups = LinkedHashMap<String, RelayDeviceGroup>()
            for (record in sorted) {
                val group = groups.getOrPut(record.deviceMid) {
                    RelayDeviceGroup(
                        deviceMid = record.deviceMid,
                        name = record.name?.trim()?.takeIf { it.isNotEmpty() },
                        platform = record.platform?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
                if (group.platform == null) {
                    group.platform = record.platform?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (record.online) group.onlineConnections.add(record)
            }
            return groups.values.toList()
        }

        /** 同 MID 在线实例 ≥2 时该组需要 006 选择 sheet（web sheet 触发口径）。 */
        fun needsInstanceSheet(group: RelayDeviceGroup): Boolean =
            group.onlineConnections.size >= 2

        /**
         * 官方 `resolveAutoConnectSid` 的移动端等价：saved SID → last SID → last MID 唯一在线
         * → 唯一在线设备；多设备无偏好返回 null（交 UI 让用户选）。
         */
        fun selectAutoConnectDevice(
            devices: List<RelayDeviceSummary>,
            savedSid: String?,
            lastSid: String?,
            lastMid: String?,
        ): RelayDeviceSummary? {
            val online = devices.filter { it.online }
            savedSid?.let { sid -> online.firstOrNull { it.deviceSid == sid }?.let { return it } }
            lastSid?.let { sid -> online.firstOrNull { it.deviceSid == sid }?.let { return it } }
            if (lastMid != null) {
                val sameMid = online.filter { it.deviceMid == lastMid }
                if (sameMid.size == 1) return sameMid.first()
            }
            if (online.size == 1) return online.first()
            return null
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

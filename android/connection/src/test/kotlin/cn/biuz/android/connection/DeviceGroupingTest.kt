package cn.biuz.android.connection

// 006 多实例分组单测（web groupWebRemoteControlDevices 直译锁定面，
// webRemoteControlDeviceGroups.ts:12-56）：
// - MID 聚合 + 在线 SID 全保留（不去重成单一连接目标）；
// - 离线记录不生成设备行（不进 onlineConnections）；
// - 排序：online 优先 → 无名靠后 → name → mid → sid；
// - 组 platform 取第一条能给出平台的记录。

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

import kotlin.test.assertTrue

class DeviceGroupingTest {

    private fun device(
        sid: String, mid: String, online: Boolean,
        name: String? = null, platform: String? = null,
    ) = RelayDeviceSummary(
        deviceSid = sid, deviceMid = mid, online = online,
        name = name, platform = platform,
    )

    @Test
    fun groupsByMidAndKeepsAllOnlineSids() {
        val groups = RelayDeviceCatalog.groupDevicesByMid(
            listOf(
                device("sid-a", "mid-1", online = true, name = "Mac Pro"),
                device("sid-b", "mid-1", online = true, name = "Mac Pro"),
                device("sid-c", "mid-2", online = true, name = "mini"),
            ),
        )
        assertEquals(2, groups.size)
        val first = groups.first { it.deviceMid == "mid-1" }
        // 在线 SID 不能去重成一个随意选择的连接目标（web 注释原文）
        assertEquals(2, first.onlineConnections.size)
        assertEquals(listOf("sid-a", "sid-b"), first.onlineConnections.map { it.deviceSid })
    }

    @Test
    fun offlineRecordsDoNotGenerateConnections() {
        val groups = RelayDeviceCatalog.groupDevicesByMid(
            listOf(
                device("sid-on", "mid-1", online = true, name = "Mac"),
                device("sid-off", "mid-1", online = false, name = "Mac"),
                device("sid-only-off", "mid-9", online = false, name = "Old"),
            ),
        )
        // 离线-only MID 仍生成组（web 保留组）但无连接；在线组不含离线 SID
        val mid1 = groups.first { it.deviceMid == "mid-1" }
        assertEquals(listOf("sid-on"), mid1.onlineConnections.map { it.deviceSid })
        val mid9 = groups.first { it.deviceMid == "mid-9" }
        assertTrue(mid9.onlineConnections.isEmpty())
        assertFalse(RelayDeviceCatalog.needsInstanceSheet(mid9))
    }

    @Test
    fun sortsOnlineFirstThenNameThenMidThenSid() {
        val groups = RelayDeviceCatalog.groupDevicesByMid(
            listOf(
                device("s-off", "m-z", online = false, name = "Zeta"),
                device("s-c", "m-c", online = true, name = "Beta"),
                device("s-b", "m-b", online = true, name = "Alpha"),
                device("s-a", "m-a", online = true), // 无名靠后（同在线档内）
            ),
        )
        assertEquals(
            listOf("m-b", "m-c", "m-a", "m-z"),
            groups.map { it.deviceMid },
            "在线具名优先（Alpha→Beta），无名靠后，离线最后",
        )
    }

    @Test
    fun platformFallsBackToFirstRecordWithPlatform() {
        val groups = RelayDeviceCatalog.groupDevicesByMid(
            listOf(
                device("s1", "mid-1", online = true, name = "Mac"),
                device("s2", "mid-1", online = true, name = "Mac", platform = "darwin"),
            ),
        )
        assertEquals("darwin", groups.single().platform, "组 platform 取第一条能给出的记录")
    }

    @Test
    fun needsInstanceSheetOnlyForTwoOrMoreOnline() {
        val groups = RelayDeviceCatalog.groupDevicesByMid(
            listOf(
                device("s1", "mid-1", online = true, name = "Mac"),
                device("s2", "mid-1", online = true, name = "Mac"),
                device("s3", "mid-2", online = true, name = "mini"),
            ),
        )
        val mid1 = groups.first { it.deviceMid == "mid-1" }
        val mid2 = groups.first { it.deviceMid == "mid-2" }
        assertTrue(RelayDeviceCatalog.needsInstanceSheet(mid1), "同 MID 2 个在线 SID → sheet")
        assertFalse(RelayDeviceCatalog.needsInstanceSheet(mid2), "单实例不弹 sheet")
        assertEquals("mini", mid2.displayName)
        // 组 name = 组内第一条记录 displayName（web groups.get 建组即写）
        assertEquals("mini", mid2.name)
    }
}

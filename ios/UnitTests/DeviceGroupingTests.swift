import XCTest
@testable import ZCodeMobile

/// 006 多实例分组单测（web groupWebRemoteControlDevices 直译锁定面；Android
/// DeviceGroupingTest 镜像）。
final class DeviceGroupingTests: XCTestCase {

    private func device(
        _ sid: String, _ mid: String, online: Bool,
        name: String? = nil, platform: String? = nil
    ) -> AppSession.RelayDeviceSummary {
        AppSession.RelayDeviceSummary(
            deviceSid: sid, deviceMid: mid, online: online,
            createdAt: nil, name: name, platform: platform, version: nil)
    }

    func testGroupsByMidAndKeepsAllOnlineSids() {
        let groups = AppSession.groupDevicesByMid([
            device("sid-a", "mid-1", online: true, name: "Mac Pro"),
            device("sid-b", "mid-1", online: true, name: "Mac Pro"),
            device("sid-c", "mid-2", online: true, name: "mini"),
        ])
        XCTAssertEqual(groups.count, 2)
        let first = groups.first { $0.deviceMid == "mid-1" }!
        // 在线 SID 不能去重成一个随意选择的连接目标（web 注释原文）
        XCTAssertEqual(first.onlineConnections.map(\.deviceSid), ["sid-a", "sid-b"])
        XCTAssertTrue(first.needsInstanceSheet)
    }

    func testOfflineRecordsDoNotGenerateConnections() {
        let groups = AppSession.groupDevicesByMid([
            device("sid-on", "mid-1", online: true, name: "Mac"),
            device("sid-off", "mid-1", online: false, name: "Mac"),
            device("sid-off9", "mid-9", online: false, name: "Old"),
        ])
        let mid1 = groups.first { $0.deviceMid == "mid-1" }!
        XCTAssertEqual(mid1.onlineConnections.map(\.deviceSid), ["sid-on"])
        let mid9 = groups.first { $0.deviceMid == "mid-9" }!
        XCTAssertTrue(mid9.onlineConnections.isEmpty)
        XCTAssertFalse(mid9.needsInstanceSheet)
    }

    func testSortsOnlineFirstThenNameThenMidThenSid() {
        let groups = AppSession.groupDevicesByMid([
            device("s-off", "m-z", online: false, name: "Zeta"),
            device("s-c", "m-c", online: true, name: "Beta"),
            device("s-b", "m-b", online: true, name: "Alpha"),
            device("s-a", "m-a", online: true),
        ])
        XCTAssertEqual(
            groups.map(\.deviceMid), ["m-b", "m-c", "m-a", "m-z"],
            "在线具名优先（Alpha→Beta），无名靠后，离线最后")
    }

    func testNeedsInstanceSheetOnlyForTwoOrMoreOnline() {
        let groups = AppSession.groupDevicesByMid([
            device("s1", "mid-1", online: true, name: "Mac"),
            device("s2", "mid-1", online: true, name: "Mac"),
            device("s3", "mid-2", online: true, name: "mini"),
        ])
        let mid1 = groups.first { $0.deviceMid == "mid-1" }!
        let mid2 = groups.first { $0.deviceMid == "mid-2" }!
        XCTAssertTrue(mid1.needsInstanceSheet, "同 MID 2 个在线 SID → sheet")
        XCTAssertFalse(mid2.needsInstanceSheet, "单实例不弹 sheet")
        XCTAssertEqual(mid2.displayName, "mini")
    }
}

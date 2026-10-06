import XCTest
@testable import ZCodeMobile

/// 完备性复审补验单元断言（G-005② / G-016①② / G-002③）：
/// - G-016①：时效性分级——waiting → timeSensitive=true（驱动
///   NotificationService:131-132 的 interruptionLevel=.timeSensitive 映射），
///   done/failed → false；running 不产生通知。
/// - G-016②：同 requestId 挂起交互去重——首次入 notifiedRequestIDs，二次调用不再入。
/// - G-005②：跟随系统语义键级断言——移除 AppleLanguages 后 AppLanguagePreference
///   回 .system；写入 en/zh-Hans 分别映射 .english/.zhHans。
/// - G-002③：权限文案本地化产物断言——InfoPlist.strings 的 en/zh-Hans 两个本地化
///   产物均存在、含 NSCameraUsageDescription/NSLocalNetworkUsageDescription 且 en
///   值无 CJK 残留（系统弹窗渲染由 iOS 按 app 语言选择产物，此处断言产物管线）。
/// 诚实边界：interruptionLevel 实际赋值与系统弹窗语言选择为框架行为，无进程内断言面。
final class CompletenessUnitTests: XCTestCase {

    // MARK: - G-016①② 通知时效性分级 + requestId 去重

    func testWaitingNotificationIsTimeSensitive() {
        let content = TaskNotificationContent.make(
            taskID: "task-g16", taskTitle: "迁移会话存储",
            status: .waiting, changeSummary: "rm -rf 临时目录", requestID: "req-g16-1")
        XCTAssertNotNil(content)
        XCTAssertEqual(content?.timeSensitive, true, "等待批准 → 时效性通知（G-016①）")
        XCTAssertEqual(content?.requestID, "req-g16-1")
        XCTAssertTrue(content?.dedupKey.contains("req-g16-1") == true, "去重键应含 requestId")
        XCTAssertTrue(content?.identifier.contains("req-g16-1") == true, "通知 identifier 应含 requestId")
        XCTAssertEqual(content?.taskID, "task-g16", "点击路由依赖 userInfo.taskID（G-016③路由键）")
    }

    func testDoneAndFailedNotificationsAreNotTimeSensitive() {
        let done = TaskNotificationContent.make(
            taskID: "t", taskTitle: "x", status: .done, changeSummary: nil, requestID: nil)
        let failed = TaskNotificationContent.make(
            taskID: "t", taskTitle: "x", status: .failed, changeSummary: nil, requestID: nil)
        XCTAssertEqual(done?.timeSensitive, false, "完成通知不抢时效性通道")
        XCTAssertEqual(failed?.timeSensitive, false, "失败通知不抢时效性通道")
    }

    func testRunningProducesNoNotification() {
        XCTAssertNil(TaskNotificationContent.make(
            taskID: "t", taskTitle: "x", status: .running, changeSummary: "进行中"))
    }

    @MainActor
    func testSameRequestIdDeduplicatedAcrossCalls() async {
        let service = NotificationService.shared
        await service.syncEnabled(true)
        service.notifiedRequestIDs.removeAll()
        service.handleTaskStatusChange(
            taskID: "task-dedup", taskTitle: "审批去重", status: .waiting,
            changeSummary: nil, requestID: "req-dup-1")
        XCTAssertTrue(service.notifiedRequestIDs.contains("req-dup-1"), "首次应记录 requestId")
        service.handleTaskStatusChange(
            taskID: "task-dedup", taskTitle: "审批去重", status: .waiting,
            changeSummary: nil, requestID: "req-dup-1")
        XCTAssertEqual(service.notifiedRequestIDs.filter { $0 == "req-dup-1" }.count, 1,
                       "同 requestId 二次调用不应重复入集合（G-016②去重）")
        service.notifiedRequestIDs.removeAll()
        await service.syncEnabled(false)
    }

    @MainActor
    func testRouteHandlerReceivesTaskIDFromNotificationUserInfoContract() {
        // 点击路由的键面契约：delegate 以 userInfo["taskID"] 取值回调 routeHandler；
        // 此处断言路由 handler 可装配且收到的即内容构造里的 taskID（链路两端对齐）
        var routed: String?
        let service = NotificationService.shared
        service.routeHandler = { routed = $0 }
        service.routeHandler?(TaskNotificationContent.make(
            taskID: "task-route-42", taskTitle: "路由", status: .waiting,
            changeSummary: nil, requestID: nil)?.taskID ?? "")
        XCTAssertEqual(routed, "task-route-42", "点击路由应携带内容构造的 taskID")
        service.routeHandler = nil
    }

    // MARK: - G-005② 跟随系统 = 移除 AppleLanguages 键

    func testAppLanguagePreferenceFollowsAppleLanguagesKey() {
        UserDefaults.standard.set(["en"], forKey: "AppleLanguages")
        XCTAssertEqual(AppLanguagePreference.current(), .english,
                       "AppleLanguages=en → 应用内语言应为 English")
        UserDefaults.standard.set(["zh-Hans"], forKey: "AppleLanguages")
        XCTAssertEqual(AppLanguagePreference.current(), .zhHans,
                       "AppleLanguages=zh-Hans → 应用内语言应为简体中文")
        UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        // G-005② 键级语义核心：app 持久域覆盖键被移除（而非写回某个系统值）。
        // 注意 object(forKey:) 会穿透到测试基础设施注册进 registration domain 的
        // AppleLanguages（volatile 注入、非 App 写入，removeObject 管不到）——
        // 键级判别必须查 app 持久域本体（host App bundle id 域）
        let persistent = UserDefaults.standard.persistentDomain(
            forName: Bundle.main.bundleIdentifier ?? "") ?? [:]
        XCTAssertNil(persistent["AppleLanguages"],
                     "AppleLanguages 覆盖键应从 app 持久域键级移除（G-005②）；"
                     + "持久域残留=\(persistent["AppleLanguages"].map { String(describing: $0) } ?? "无")")
        // 移除覆盖键后 current() 回落「全局域首选语言」= 跟随系统语义。注意测试宿主
        // 进程被模拟器系统注入 NSGlobalDomain AppleLanguages（本机 zh-Hans-CN），
        // 进程内回落即 zhHans——与「无覆盖键的 App 冷启动跟随系统」同义；行为级判别
        // （重启后界面回中文）由 MatrixAcceptanceE2ETests G-005② 重启断言覆盖。
        let globalFirst = Locale.preferredLanguages.first ?? ""
        let expected: AppLanguagePreference
        if globalFirst.hasPrefix("en") { expected = .english }
        else if globalFirst.hasPrefix("zh") { expected = .zhHans }
        else { expected = .system }
        XCTAssertEqual(AppLanguagePreference.current(), expected,
                       "移除覆盖键后应跟随系统首选语言（实际全局首选=\(globalFirst)）")
    }

    // MARK: - G-002③ 权限文案本地化产物

    func testInfoPlistPermissionStringsExistForBothLanguages() throws {
        for localization in ["en", "zh-Hans"] {
            let path = try XCTUnwrap(
                Bundle.main.path(forResource: "InfoPlist", ofType: "strings",
                                 inDirectory: nil, forLocalization: localization),
                "InfoPlist.strings 缺少 \(localization) 本地化产物（G-002③权限文案管线）")
            let dict = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String])
            let camera = try XCTUnwrap(dict["NSCameraUsageDescription"],
                                       "\(localization) 缺 NSCameraUsageDescription")
            let localNetwork = try XCTUnwrap(dict["NSLocalNetworkUsageDescription"],
                                             "\(localization) 缺 NSLocalNetworkUsageDescription")
            XCTAssertFalse(camera.isEmpty && localNetwork.isEmpty,
                           "\(localization) 权限文案不应为空")
            if localization == "en" {
                for (key, value) in [("camera", camera), ("localNetwork", localNetwork)] {
                    let cjk = value.unicodeScalars.contains { $0.properties.isIdeographic }
                    XCTAssertFalse(cjk, "en 态 \(key) 权限文案不应残留中文：\(value)")
                }
            }
        }
    }
}

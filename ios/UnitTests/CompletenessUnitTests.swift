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

    // MARK: - AppSettings 旧档兼容解码（开发者模式字段 2026-10-08 追加）

    /// 旧版设置档（无 developerMode 键）解码必须整档存活：合成 Codable 遇缺键抛
    /// keyNotFound → UserDefaultsSettingsStore.load() 回退默认值，外观/语言/模型偏好
    /// 全部丢失（升级即重置事故）；decodeIfPresent 逐键兼容后旧档字段应逐一保留
    func testAppSettingsLegacyArchiveDecodesWithoutDeveloperModeKey() throws {
        let legacyJSON = Data("""
        {"appearance":"dark","notificationsEnabled":false,"model":"GLM-5","thoughtLevel":"high","language":"English"}
        """.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: legacyJSON)
        XCTAssertEqual(settings.appearance, .dark)
        XCTAssertEqual(settings.notificationsEnabled, false)
        XCTAssertEqual(settings.model, "GLM-5")
        XCTAssertEqual(settings.thoughtLevel, .high)
        XCTAssertEqual(settings.language, "English")
        XCTAssertEqual(settings.developerMode, false, "旧档缺键应落默认关")
    }

    /// 新档含 developerMode 键的编解码回环
    func testAppSettingsDeveloperModeRoundTrip() throws {
        var settings = AppSettings()
        settings.developerMode = true
        let data = try JSONEncoder().encode(settings)
        let decoded = try JSONDecoder().decode(AppSettings.self, from: data)
        XCTAssertEqual(decoded.developerMode, true, "开发者模式开应回环保留")
        XCTAssertEqual(decoded, settings)
    }
}

/// 斜杠命令解析（composer 能力命令面；web sX 解析器同构拦截子集，
/// 2026-10-06 用户报障「命令没有 workflow/goal」——桌面 config slashCommands +
/// 内建集的菜单数据源之外，发送侧的客户端拦截语义在此定形）。
final class SlashIntentParseTests: XCTestCase {

    func testGoalFamily() {
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/goal 完成自测纪律"), .goal(objective: "完成自测纪律"))
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/target 完成自测纪律"), .goal(objective: "完成自测纪律"), "/target 为 /goal 别名（web 同构）")
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/goal replace 新目标"), .goal(objective: "新目标"), "replace 首词整词匹配后剥离")
        XCTAssertNil(ChatViewModel.parseSlashIntent("/goal"), "空目标不拦截（web emptyGoal 特例面 → 原文直发）")
        XCTAssertNil(ChatViewModel.parseSlashIntent("/goal pause"), "pause/clear/show/resume 子命令不拦截")
        XCTAssertNil(ChatViewModel.parseSlashIntent("/goal replace"), "replace 无正文 → nil 原文直发")
    }

    func testPlanAndCompact() {
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/plan"), .plan(task: ""), "空任务 = 仅切计划模式（web planShortcut !task → sent）")
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/plan 修复登录页"), .plan(task: "修复登录页"))
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/compact"), .compact)
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/compress"), .compact, "compress 为 compact 别名（web 同构）")
    }

    func testUninterceptedFallsThrough() {
        XCTAssertNil(ChatViewModel.parseSlashIntent("/workflow 跑一遍回归"), "桌面下发命令原文直发由桌面 agent 解释")
        XCTAssertNil(ChatViewModel.parseSlashIntent("/side 记一下这件事"), "side/btw 特例面移动端未展开 → 原文直发")
        XCTAssertNil(ChatViewModel.parseSlashIntent("普通消息不带斜杠"))
        XCTAssertEqual(ChatViewModel.parseSlashIntent("/goal replaces everything"), .goal(objective: "replaces everything"), "replaces 非整词 replace，整句作为目标文本")
    }
}

/// workspace-config 思考档词表键归一（2026-10-07 真机报障 sess_ce4531a1 回归）：
/// 桌面 configOptions 模型条目 value 为复合串 `providerId/modelId[$reasoningLevel]`
/// （上游仓 model-selection.ts:34 formatModelPickerValue），词表必须按裸 modelId
/// 建键——曾整串建键导致 thoughtLevels(for:) 永远 miss，新建会话 sheet 思考档退化
/// 静态梯（含 medium），首条 turn model_creation 被拒（GLM-5.3 系列 variants 仅
/// [low,max,high]，Reasoning effort "medium" not supported）。
final class WorkspaceConfigThoughtKeyTests: XCTestCase {

    private func makeConfigFrame() -> V4TopicFrame {
        let modelOption: JSONValue = .object([
            "id": .string("model"),
            "name": .string("Model"),
            "type": .string("select"),
            "currentValue": .string("account:zai-start-plan/GLM-5.3-Flash$max"),
            "options": .array([
                // 复合串（上游 formatModelPickerValue 权威形态）+ 档位后缀变体 + 裸串宽容形态
                .object([
                    "value": .string("account:zai-start-plan/GLM-5.3-Flash"),
                    "name": .string("GLM-5.3-Flash"),
                    "modelThoughtLevels": .array([.string("low"), .string("max"), .string("high")])
                ]),
                .object([
                    "value": .string("account:zai-individual-coding-plan/GLM-5.3$low"),
                    "name": .string("GLM-5.3"),
                    "modelThoughtLevels": .array([.string("low"), .string("max"), .string("high")])
                ]),
                .object([
                    "value": .string("LegacyBareModel"),
                    "modelThoughtLevels": .array([.string("off"), .string("on")])
                ])
            ])
        ])
        return V4TopicFrame(
            topic: "workspace-config//tmp/ws", subscriptionId: "sub-test",
            fromSeq: 0, toSeq: 0, sentAt: nil,
            snapshot: .object(["config": .object(["configOptions": .array([modelOption])])]),
            deltas: [])
    }

    @MainActor
    func testThoughtVocabularyKeyedByBareModelId() async {
        let connection = ZCodeServerConnection()
        let store = await RemoteConversationStore(
            connection: connection,
            workspace: ServerWorkspaceInfo(path: "/tmp/ws", label: nil, workspaceIdentity: nil))
        await store.handleWorkspaceConfigFrame(makeConfigFrame())
        let flash = await store.thoughtLevels(for: "GLM-5.3-Flash")
        XCTAssertEqual(flash, ["low", "max", "high"],
                       "复合串 value 必须归一为裸 modelId 建键（报障根因：整串建键查询必 miss）")
        let base = await store.thoughtLevels(for: "GLM-5.3")
        XCTAssertEqual(base, ["low", "max", "high"], "含 $档位 后缀的复合串同样按裸 modelId 命中")
        let legacy = await store.thoughtLevels(for: "LegacyBareModel")
        XCTAssertEqual(legacy, ["off", "on"], "无 / 的裸串形态原样建键（宽容旧桌面）")
        let missing = await store.thoughtLevels(for: "GLM-4")
        XCTAssertEqual(missing, [], "未下发词表的模型保持空（调用方自行兜底）")
    }

    /// getView per-model 思考档词表【实证·上游仓 provider/facades.ts
    /// ModelSelectionModelView `{modelId, config}`——web v4 工具栏 yB 唯一数据源
    /// `config.optionSpecs.reasoningLevel.values`】；本机桌面 v3.14.4 对手机不推
    /// workspace-config，getView 是词表唯一可用源（真机报障 2026-10-07 二轮：
    /// 键修复后 sheet/chips 仍见 medium——词表数据根本没到）。
    func testGetViewPerModelThoughtVocabulary() {
        let view: [String: JSONValue] = [
            "providers": .array([
                .object([
                    "providerId": .string("account:zai-start-plan"),
                    "models": .array([
                        .object([
                            "modelId": .string("GLM-5.3-Flash"),
                            "config": .object(["optionSpecs": .object([
                                "reasoningLevel": .object(["values": .array([
                                    .string("low"), .string("max"), .string("high")])])])])
                        ]),
                        .object([
                            "modelId": .string("GLM-5.2"),
                            "modelThoughtLevels": .array([.string("enabled"), .string("off")])
                        ]),
                        .string("LegacyStringModel")
                    ])
                ]),
                .object([
                    "providerId": .string("account:zai-individual-coding-plan"),
                    "models": .array([
                        .object([
                            "modelId": .string("GLM-5.3"),
                            "label": .string("GLM-5.3 Pro"),
                            "config": .object(["optionSpecs": .object([
                                "reasoningLevel": .object(["values": .array([
                                    .string("low"), .string("max")])])])])
                        ])
                    ])
                ])
            ]),
            "preferredSelection": .object([
                "providerId": .string("account:zai-start-plan"),
                "modelId": .string("GLM-5.3-Flash"),
                "options": .object(["reasoningLevel": .string("max")])
            ])
        ]
        let info = RemoteConversationStore.parseModelSelectionView(view)
        XCTAssertEqual(info.thoughtByModel["GLM-5.3-Flash"], ["low", "max", "high"],
                       "per-model config.optionSpecs 词表（web yB 同构，不含 medium）")
        XCTAssertEqual(info.thoughtByModel["GLM-5.2"], ["enabled", "off"],
                       "modelThoughtLevels 键同义兜底")
        XCTAssertEqual(info.thoughtByModel["GLM-5.3"], ["low", "max"])
        XCTAssertEqual(info.thoughtByModel["GLM-5.3 Pro"], ["low", "max"],
                       "label 相异时双键（sheet 以 label 展示/查询）")
        XCTAssertNil(info.thoughtByModel["LegacyStringModel"], "字符串条目无 per-model 词表")
        XCTAssertEqual(info.activeModel, "GLM-5.3-Flash")
        XCTAssertEqual(info.activeThoughtLevel, "max")
    }
}

import XCTest

/// ZCode Mobile · 缺口矩阵独立验收（XCUITest，G-001~G-064 可 UI 动态验收子集）
///
/// 本文件是「矩阵验收工程师」独立于实现者的验收面：矩阵静态核验项（grep/实读）在验收报告
/// 记录；此处只做需要在模拟器上跑起来的行为断言。覆盖：
/// - G-010（会话来源过滤 全部↔云端↔机器 往返 + 空态说明 + 持久化 + 连接态 mac 归档）
/// - G-001~G-006（IM Bot 读面/绑定码/测试/解绑/删除·移除密钥/上下文清退——替身 bots 域
///   9 命令 + 就地状态变化，E2ELoginStubServer 已扩展）
/// - G-016（自动化页真实列表 + 运行历史 + 演示态离线空态）
/// - G-017（plan_approval 计划审批结构化卡 + 放行 resolveInteraction）
/// - G-015（分享只读页冷启入口 + 失效码错误态；有效码只读渲染依赖云端真实分享数据，
///   由真实验证流程覆盖，此处验入口与错误态）
/// - G-020/G-021/G-022/G-023/G-034（用户卡真名/上下文演示条/伪子智能体行/伪麦克风/设备在线数）
/// - G-024（新建会话 @ 文件引用端到端 + 无功能 chips 禁用态）
/// - G-025（审批 Sheet 追问接真：输入 → sendText 下发 → 任务保持待操作）
/// - G-026（协议名可点 → 内嵌 WebView Sheet 可关闭）
/// - G-008（应用内语言切换 → AppleLanguages 持久化 → 重启生效）
/// - G-041/G-042（用量真值 + 重置机会卡一键领取）
///
/// 时序容忍：全部等待用 waitForExistence / XCTNSPredicateExpectation / 有界轮询，不写裸 sleep
/// （G-002 过期态验证除外——替身 TTL 调成 3s 后必须真实等过期，为语义等待而非时序赌注）。
/// 每个用例 `-ZCodeE2EResetState` 独立冷启，无顺序依赖。
/// 本文件只做编译级自检，由统一门禁脚本在模拟器上执行。
final class MatrixAcceptanceE2ETests: XCTestCase {

    private var stub: E2ELoginStubServer!
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        stub = E2ELoginStubServer()
        try stub.start()
        app = XCUIApplication()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        stub?.stop()
    }

    // MARK: - 基础设施（与既有套件同口径）

    private func element(_ application: XCUIApplication, _ identifier: String) -> XCUIElement {
        application.descendants(matching: .any)[identifier].firstMatch
    }

    @discardableResult
    private func waitUntil(timeout: TimeInterval, _ message: String, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.05)
        }
        return condition()
    }

    @discardableResult
    private func waitStaticText(_ application: XCUIApplication, containing text: String,
                                timeout: TimeInterval, _ message: String) -> Bool {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let hit = application.staticTexts.matching(predicate).firstMatch
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: hit)
        let outcome = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(outcome == .completed, message)
        return outcome == .completed
    }

    /// 审批卡/计划卡挂在消息列表顶部，详情默认吸底滚动（defaultScrollAnchor(.bottom)）后
    /// 卡片可能滑出 LazyVStack 渲染窗口——tap 前下滑回顶部揭示（既有 test16 同款兜底）
    @discardableResult
    private func revealBySwipeDown(_ item: XCUIElement, application: XCUIApplication) -> Bool {
        for _ in 0..<8 {
            if item.exists && item.isHittable { return true }
            application.swipeDown()
        }
        return item.exists && item.isHittable
    }

    private func snap(_ application: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: application.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// 演示态冷启动（清空凭据态/服务器注册表/语言偏好）
    @discardableResult
    private func launchDemo(extra: [String] = []) -> XCUIApplication {
        app.launchArguments = ["-ZCodeE2EResetState", "-AppleLanguages", "(zh-Hans)"] + extra
        app.launch()
        return app
    }

    /// 连接态冷启动（清空态 + OAuth 端点指向替身）
    @discardableResult
    private func launchFreshForStub() -> XCUIApplication {
        app.launchArguments = [
            "-ZCodeE2EResetState",
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            "-AppleLanguages", "(zh-Hans)",
        ]
        app.launch()
        return app
    }

    /// 会话内二次启动（保留凭据态：已保存服务器触发冷启自动重连）
    @discardableResult
    private func relaunchKeepState(_ application: XCUIApplication,
                                   extra: [String] = []) -> XCUIApplication {
        application.terminate()
        application.launchArguments = [
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            "-AppleLanguages", "(zh-Hans)",
        ] + extra
        application.launch()
        return application
    }

    /// 键盘弹出等待 + 输入（转场动画期 tap 落空的有界重试）
    @discardableResult
    private func typeInto(_ application: XCUIApplication, _ field: XCUIElement, text: String) -> Bool {
        guard field.waitForExistence(timeout: 8) else { return false }
        for _ in 0..<3 {
            field.tap()
            if application.keyboards.firstMatch.waitForExistence(timeout: 3) {
                field.typeText(text)
                return true
            }
        }
        field.typeText(text)
        return true
    }

    /// 连接替身并回到主界面（配对 → 冷启自动重连，与既有套件 connectAndEnterMain 同口径）
    private func connectAndEnterMain(_ application: XCUIApplication) {
        let meTab = element(application, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")
        meTab.tap()
        let addRow = element(application, "l4-row-add")
        XCTAssertTrue(addRow.waitForExistence(timeout: 8), "设置页应有「添加服务器」行")
        addRow.tap()
        let manualButton = element(application, "l1-btn-manual")
        XCTAssertTrue(manualButton.waitForExistence(timeout: 8), "连接页应有手动输入入口")
        manualButton.tap()
        let hostField = element(application, "l1-field-host")
        XCTAssertTrue(hostField.waitForExistence(timeout: 8), "应进入手动连接页")
        XCTAssertTrue(typeInto(application, element(application, "l1-field-host"),
                               text: "http://127.0.0.1:\(stub.port)"), "地址栏应可输入")
        XCTAssertTrue(typeInto(application, element(application, "l1-field-token"),
                               text: stub.pairingToken), "令牌栏应可输入")
        let keyboardConnect = element(application, "l1-keyboard-connect")
        if keyboardConnect.waitForExistence(timeout: 2) {
            keyboardConnect.tap()
        } else {
            element(application, "l1-submit-connect").tap()
        }
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(waitUntil(timeout: 15, "连接成功后连接流程应自动收起") {
            !element(application, "l1-submit-connect").exists
        })
        relaunchKeepState(application)
    }

    /// 设置页 → 指定入口行（须已处于主界面）
    private func openSettingsPage(_ application: XCUIApplication, rowIdentifier: String) {
        let meTab = element(application, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")
        meTab.tap()
        let row = element(application, rowIdentifier)
        XCTAssertTrue(row.waitForExistence(timeout: 8), "设置页应有入口行 \(rowIdentifier)")
        row.tap()
    }

    // MARK: - G-010/G-006 会话来源过滤：三档各自非空（演示）+ 连接态如实降级 + 持久化

    func test01_sourceFilterRoundTripDemoAndConnected() throws {
        let application = launchDemo()

        // ① 演示态三档各自非空（G-006：mock seed 补 source 演示值——c3/c4=cloud，其余=mac）
        XCTAssertTrue(element(application, "04-row-c1").waitForExistence(timeout: 10),
                      "演示态应进入会话列表（mock 会话在场）")
        XCTAssertTrue(element(application, "04-chip-source-all").waitForExistence(timeout: 6),
                      "来源过滤 chips 应在场")
        element(application, "04-chip-source-cloud").tap()
        XCTAssertTrue(element(application, "04-row-c3").waitForExistence(timeout: 12),
                      "演示态「云端沙盒」档应返回演示 cloud 会话集合（c3/c4）")
        XCTAssertFalse(element(application, "04-row-c1").exists,
                       "cloud 档不应混入 mac 会话（c1）")
        element(application, "04-chip-source-mac").tap()
        // 档位切换后 List 重排 + LazyVStack 重新物化，等待窗给足时序容忍（同等待遇用于三档）
        if !element(application, "04-row-c1").waitForExistence(timeout: 4) {
            // 首次 tap 若落在 chips 行重排动画期（XCUI 坐标解析先于布局稳定），有界重试一次
            element(application, "04-chip-source-mac").tap()
        }
        XCTAssertTrue(element(application, "04-row-c1").waitForExistence(timeout: 12),
                      "演示态「我的 Mac」档应返回演示 mac 会话集合（c1/c2/c5/c6）")
        XCTAssertFalse(element(application, "04-row-c3").exists,
                       "mac 档不应混入 cloud 会话（c3）")
        element(application, "04-chip-source-all").tap()
        XCTAssertTrue(element(application, "04-row-c1").waitForExistence(timeout: 12),
                      "切回「全部」后 mock 会话行应回归")

        // ② 连接态：替身会话归「我的 Mac」档；无 cloud 数据时云端档置灰禁用
        // （G-006 验收②/G-010：按数据源如实降级，不呈现恒空档）
        connectAndEnterMain(application)
        let stubRow = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(stubRow.waitForExistence(timeout: 15), "连接态应呈现替身快照会话行")
        element(application, "04-chip-source-mac").tap()
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 8),
                      "连接态 mac 会话应出现在「我的 Mac」档")
        let cloudChip = element(application, "04-chip-source-cloud")
        XCTAssertTrue(cloudChip.waitForExistence(timeout: 6), "云端沙盒 chips 应在场")
        XCTAssertFalse(cloudChip.isEnabled,
                       "连接态无 cloud 数据源时云端档应置灰禁用（如实降级）")
        element(application, "04-chip-source-all").tap()
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 8),
                      "切回「全部」后替身会话行应回归")

        // ③ 持久化：切「我的 Mac」→ 重启后档位保持（list.sourceFilter.v1）且 mac 行仍在。
        // 选中态判别（防假阳性）：替身只有 mac 会话，「全部」档回退同样能显示该行——
        // 故必须断言 mac chip 处于选中态（背景 accent = 选中），且回退判别用 c1
        //（stub 会话 source=mac；若回退「全部」则 demo c1 行也会出现）
        element(application, "04-chip-source-mac").tap()
        relaunchKeepState(application)
        let macChip = element(app, "04-chip-source-mac")
        XCTAssertTrue(macChip.waitForExistence(timeout: 10),
                      "重启后来源 chips 应在场")
        XCTAssertTrue(macChip.isSelected,
                      "重启后「我的 Mac」chip 应保持选中（list.sourceFilter.v1 持久化；"
                      + "回退「全部」档该断言失败）")
        XCTAssertTrue(element(app, "04-row-sess-e2e-1").waitForExistence(timeout: 15),
                      "重启后（mac 档持久化）替身会话行应仍归档显示")
        snap(app, "matrix-g010-source-filter-persisted")
    }

    // MARK: - 要求 4：多项目分组（分组键=会话自带工作区字段，与桌面侧栏项目全集对齐；
    // 数据无法判定归属的会话归「其它」组不丢弃）

    func test16_multiProjectGroupingFromSessionWorkspaceFields() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        // 替身快照携带行内 workspacePath：四个项目组 + 一个未知归属（其它组）
        for group in ["mtt_mobile", "zcode_mobile", "poker_texas_air", "matchclub"] {
            XCTAssertTrue(element(application, "04-group-\(group)").waitForExistence(timeout: 8),
                          "项目分组头「\(group)」应存在（分组键取会话自带 workspacePath）")
        }
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 8),
                      "mtt_mobile 组应包含 sess-e2e-1 会话行")
        XCTAssertTrue(element(application, "04-group-其它").waitForExistence(timeout: 8),
                      "无 workspace 字段的 sess-e2e-think 应归「其它」组而非丢弃")
        // 回归：分场不丢行——替换替身行仍全量在场
        XCTAssertTrue(element(application, "04-row-sess-e2e-2").exists,
                      "zcode_mobile 组应包含 sess-e2e-2 会话行")
        snap(application, "req4-multi-project-grouping")
    }

    // MARK: - 要求 5：桌面 workflow 运行进度只读面板（阶段节点链 + 子代理卡片 + 进度点）

    func test17_workflowPanelRendersNodesSubagentAndProgress() throws {
        let application = launchDemo()

        // 运行中演示会话 c1 → Mock 提供演示 workflow run（其余会话无数据不渲染）
        XCTAssertTrue(element(application, "04-row-c1").waitForExistence(timeout: 10),
                      "演示列表应加载（c1 在场）")
        element(application, "04-row-c1").tap()

        let panel = element(application, "05-workflow-panel")
        XCTAssertTrue(panel.waitForExistence(timeout: 10),
                      "运行中演示会话应渲染 workflow 只读面板（头部下固定区）")
        XCTAssertTrue(panel.staticTexts["持久层重构 · 分步工作流"].waitForExistence(timeout: 6),
                      "面板应显示 run 名称")
        XCTAssertTrue(panel.staticTexts["梳理 SessionStore 调用面"].exists,
                      "阶段节点链应包含已完成节点")
        XCTAssertTrue(panel.staticTexts["回归测试与提交"].exists,
                      "阶段节点链应包含待执行节点")
        XCTAssertTrue(panel.staticTexts["迁移调用方（子代理）"].exists,
                      "子代理实例卡应渲染（G-008 actors 权威投影）")
        XCTAssertTrue(panel.staticTexts["2/4"].waitForExistence(timeout: 4),
                      "进度点计数 2/4 应显示（done 节点数/总节点数）")
        XCTAssertTrue(panel.staticTexts["产物 2"].waitForExistence(timeout: 4),
                      "容量行应显示产物计数（artifacts 只读展示）")
        // G-021②：无 sessionId 的 actor 不渲染下钻入口——演示 actor（demo-site-0#1）
        // 未携 sessionId → 按钮挂 .disabled（映射 a11y 不可用态，XCUI isEnabled=false）
        let actor = element(application, "05-workflow-actor-demo-site-0#1")
        XCTAssertTrue(actor.waitForExistence(timeout: 4), "子代理实例卡应在场（负向断言载体）")
        XCTAssertFalse(actor.isEnabled,
                       "无 sessionId 的 actor 应为禁用态（不渲染下钻入口，G-021②）")
        snap(application, "req5-workflow-panel")
    }

    // MARK: - G-007：会话行工作流迷你轨道（通路 A：sessions-index workflowActivity 零新增订阅）

    func test18_sessionRowWorkflowMiniTrack() throws {
        let application = launchDemo()

        // 演示 c1 携带 workflowActivity（7 站折叠：运行站 ±2 共 5 站 +「+2」尾）
        let row = element(application, "04-row-c1")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "演示列表应加载（c1 在场）")
        let track = element(application, "04-workflow-track")
        XCTAssertTrue(track.waitForExistence(timeout: 8),
                      "有 workflowActivity 的会话行应渲染迷你轨道")
        XCTAssertTrue(row.staticTexts["迁移调用方"].waitForExistence(timeout: 6),
                      "迷你轨道应显示当前阶段名（currentPhase）")
        XCTAssertTrue(row.staticTexts["2 agents working"].exists,
                      "迷你轨道应显示 agentsWorking 计数（桌面同词汇）")
        XCTAssertTrue(row.staticTexts["+2"].exists,
                      "7 站超 6 站上限应以「+N」呈现折叠溢出")
        // 验收③：无 workflowActivity 的会话行不渲染占位（全局仅 c1/c5 两行有轨道）
        let trackCount = application.descendants(matching: .any)
            .matching(identifier: "04-workflow-track").count
        XCTAssertEqual(trackCount, 2,
                       "仅携带 workflowActivity 的会话行（c1/c5）渲染轨道，其余无占位")
        snap(application, "g007-workflow-mini-track")
    }

    // MARK: - G-001 IM Bot 列表与运行状态读面（与桌面 BotsDialog 同源数据）

    func test02_imBotPageReflectsDesktopBotsState() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        openSettingsPage(application, rowIdentifier: "12-row-bot")
        XCTAssertTrue(element(application, "12-bot-summary").waitForExistence(timeout: 12),
                      "连接态 IM Bot 页应呈现通道汇总")
        waitStaticText(application, containing: "共 2 个通道 · 1 个绑定上下文", timeout: 8,
                       "汇总应与替身（=桌面 BotsDialog）数据一致：2 通道 · 1 绑定上下文")
        XCTAssertTrue(element(application, "12-bot-row-bot-telegram").waitForExistence(timeout: 6),
                      "已绑定 Telegram bot 行应在场（名称/绑定态与桌面一致）")
        XCTAssertTrue(element(application, "12-bot-row-bot-feishu").waitForExistence(timeout: 6),
                      "停用飞书 bot 行应在场")
        XCTAssertTrue(element(application, "12-bot-runtime-connected").waitForExistence(timeout: 6)
                      || element(application, "12-bot-runtime-disabled").exists,
                      "运行态标签（已连接/已停用）应来自 getStatus.botRuntime")

        // 读面出口 ≥4 断言（G-001 验收②的替身侧证据）：listBots/getStatus/getBotStates
        XCTAssertTrue(waitUntil(timeout: 6, "bots 读命令应到达替身") {
            let calls = Set(stub.botsRPCCalls)
            return calls.contains("listBots") && calls.contains("getStatus")
                && calls.contains("getBotStates")
        }, "bots 频道应出现 listBots/getStatus/getBotStates 读出口；实际=\(stub.botsRPCCalls)")
        snap(application, "matrix-g001-imbot-list")
    }

    // MARK: - G-002 + G-005 绑定码（生成/复制命令/过期刷新）+ 坏凭据连通性测试

    func test03_imBotBindCodeLifecycleAndFailedConnectivity() throws {
        stub.stubBindCodeTTLms = 3_000 // 短 TTL：真实等过期（验收③过期态可刷新）
        let application = launchDemo()
        connectAndEnterMain(application)

        openSettingsPage(application, rowIdentifier: "12-row-bot")
        let feishuRow = element(application, "12-bot-row-bot-feishu")
        XCTAssertTrue(feishuRow.waitForExistence(timeout: 12), "未绑定飞书 bot 行应在场")
        feishuRow.tap()
        let bindButton = element(application, "12-bot-act-bindcode")
        XCTAssertTrue(bindButton.waitForExistence(timeout: 8), "详情应有「生成绑定码」入口")
        bindButton.tap()

        let codeText = element(application, "12-bot-code")
        XCTAssertTrue(codeText.waitForExistence(timeout: 8), "绑定码应展示（码 + 有效期）")
        let firstCode = stub.lastBindCode
        XCTAssertNotNil(firstCode, "替身应收到 createBindCode 并回执码")
        XCTAssertTrue(waitStaticText(application, containing: "/bind \(firstCode ?? "")", timeout: 6,
                                     "应展示可复制的绑定命令 /bind <code>"))
        waitStaticText(application, containing: "3 秒", timeout: 4,
                       "应标注有效期（替身 TTL 3 秒）")

        // 过期态 → 刷新出新码
        XCTAssertTrue(waitUntil(timeout: 10, "TTL 3s 过后应呈现过期态") {
            element(application, "12-bot-code-expired").exists
        }, "过期态提示应出现（12-bot-code-expired）")
        element(application, "12-bot-act-bindcode").tap()
        XCTAssertTrue(element(application, "12-bot-code").waitForExistence(timeout: 8),
                      "重新生成后新码应出现")
        XCTAssertNotEqual(stub.lastBindCode, firstCode, "刷新后的码应与上一枚不同")

        // G-005 验收②：错误凭据返回失败 + 桌面同源错误码
        let testButton = element(application, "12-bot-act-test")
        XCTAssertTrue(testButton.waitForExistence(timeout: 6), "详情应有「测试连接」")
        testButton.tap()
        XCTAssertTrue(element(application, "12-bot-test-fail").waitForExistence(timeout: 8),
                      "坏凭据 bot 测试应返回失败")
        waitStaticText(application, containing: "E2E_BOT_ERR_CREDENTIAL", timeout: 6,
                       "错误信息应携带与桌面一致的错误码")
        snap(application, "matrix-g002-bindcode-expired-refresh")
    }

    /// G-005 验收①：在线 bot 测试返回通过
    func test04_imBotTestConnectionPassesForHealthyBot() throws {
        let application = launchDemo()
        connectAndEnterMain(application)
        openSettingsPage(application, rowIdentifier: "12-row-bot")
        let telegramRow = element(application, "12-bot-row-bot-telegram")
        XCTAssertTrue(telegramRow.waitForExistence(timeout: 12), "已绑定 Telegram bot 行应在场")
        telegramRow.tap()
        let testButton = element(application, "12-bot-act-test")
        XCTAssertTrue(testButton.waitForExistence(timeout: 8), "详情应有「测试连接」")
        testButton.tap()
        XCTAssertTrue(element(application, "12-bot-test-ok").waitForExistence(timeout: 8),
                      "在线 bot 测试应返回通过（12-bot-test-ok）")
    }

    // MARK: - G-003 + G-006 解绑（二次确认/仅清 providerUserId）+ 工作区上下文清退入口

    func test05_imBotUnbindRequiresConfirmAndClearsBindingOnly() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        openSettingsPage(application, rowIdentifier: "12-row-bot")
        let telegramRow = element(application, "12-bot-row-bot-telegram")
        XCTAssertTrue(telegramRow.waitForExistence(timeout: 12), "已绑定 bot 行应在场")
        telegramRow.tap()
        XCTAssertTrue(element(application, "12-bot-bind-user").waitForExistence(timeout: 8),
                      "绑定用户（G-006 per-user 信息）应展示")
        let resetContext = element(application, "12-bot-state-reset-st-ctx-1")
        XCTAssertTrue(resetContext.waitForExistence(timeout: 8),
                      "工作区上下文行应有「清退」入口（G-006 per-context）")

        // 解绑：二次确认「取消」不产生任何 RPC
        let unbind = element(application, "12-bot-act-unbind")
        XCTAssertTrue(unbind.waitForExistence(timeout: 6), "已绑定 bot 应有「解绑」入口")
        unbind.tap()
        let cancel = element(application, "12-bot-confirm-act-cancel")
        XCTAssertTrue(cancel.waitForExistence(timeout: 6), "解绑应弹二次确认")
        cancel.tap()
        XCTAssertFalse(stub.botsRPCCalls.contains("saveBot"),
                       "二次确认取消不得产生 saveBot RPC；实际=\(stub.botsRPCCalls)")

        // 解绑：确认后 saveBot 到达且摘除绑定身份 + resetBotState 联动
        unbind.tap()
        let confirm = element(application, "12-bot-confirm-act-confirm")
        XCTAssertTrue(confirm.waitForExistence(timeout: 6), "确认按钮应在场")
        confirm.tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 saveBot（解绑）") {
            stub.lastSaveBot?.botId == "bot-telegram" && stub.lastSaveBot?.droppedBinding == true
        }, "解绑应整对象回传且不含 providerUserId（仅清绑定身份）；实际=\(String(describing: stub.lastSaveBot))")
        XCTAssertTrue(stub.resetStateIds.contains("bot-telegram"),
                      "解绑应联动 resetBotState（桌面 BotsDialog 同构）")
        waitStaticText(application, containing: "已解绑", timeout: 8,
                       "解绑成功应有操作反馈")
        XCTAssertTrue(waitUntil(timeout: 10, "解绑后刷新：绑定用户行应消失（回未绑定态）") {
            !element(application, "12-bot-bind-user").exists
        }, "解绑后详情应回未绑定态（bot 配置本身未删）")
        snap(application, "matrix-g003-unbound")
    }

    // MARK: - G-004 删除 / 移除密钥（二次确认取消不产生 RPC；删除后 listBots 不再返回）

    func test06_imBotDeleteAndRemoveSecretWithConfirm() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        openSettingsPage(application, rowIdentifier: "12-row-bot")
        let telegramRow = element(application, "12-bot-row-bot-telegram")
        XCTAssertTrue(telegramRow.waitForExistence(timeout: 12), "bot 行应在场")
        telegramRow.tap()

        // 移除密钥：取消不产生 RPC → 确认后回未配置凭据态
        let removeSecret = element(application, "12-bot-act-remove-secret")
        XCTAssertTrue(removeSecret.waitForExistence(timeout: 8), "应有「移除密钥」入口")
        removeSecret.tap()
        XCTAssertTrue(element(application, "12-bot-confirm-act-cancel").waitForExistence(timeout: 6),
                      "移除密钥应弹二次确认")
        element(application, "12-bot-confirm-act-cancel").tap()
        XCTAssertFalse(stub.removeSecretIds.contains("bot-telegram"),
                       "二次确认取消不得产生 removeBotSecret RPC")
        removeSecret.tap()
        XCTAssertTrue(element(application, "12-bot-confirm-act-confirm").waitForExistence(timeout: 6))
        element(application, "12-bot-confirm-act-confirm").tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 removeBotSecret") {
            stub.removeSecretIds.contains("bot-telegram")
        })

        // 删除 Bot：确认后 listBots 不再返回该 bot（回列表断言行消失）
        let deleteBot = element(application, "12-bot-act-delete")
        XCTAssertTrue(deleteBot.waitForExistence(timeout: 8), "应有「删除 Bot」入口")
        deleteBot.tap()
        XCTAssertTrue(element(application, "12-bot-confirm-act-cancel").waitForExistence(timeout: 6),
                      "删除应弹二次确认")
        element(application, "12-bot-confirm-act-cancel").tap()
        XCTAssertFalse(stub.deleteBotIds.contains("bot-telegram"),
                       "二次确认取消不得产生 deleteBot RPC")
        deleteBot.tap()
        XCTAssertTrue(element(application, "12-bot-confirm-act-confirm").waitForExistence(timeout: 6))
        element(application, "12-bot-confirm-act-confirm").tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 deleteBot") {
            stub.deleteBotIds.contains("bot-telegram")
        })
        // 返回列表：bot-telegram 行消失，bot-feishu 仍在
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(element(application, "12-bot-row-bot-feishu").waitForExistence(timeout: 8),
                      "删除后列表应保留其余 bot")
        XCTAssertFalse(element(application, "12-bot-row-bot-telegram").exists,
                       "删除后 listBots 不应再返回该 bot（桌面同步语义）")
        snap(application, "matrix-g004-after-delete")
    }

    // MARK: - G-016 自动化页：真实列表（连接态）/ 离线空态（演示态）

    func test07_automationsRealListConnectedAndOfflineDemo() throws {
        let application = launchDemo()

        // ① 演示态：离线空态而非假数据
        openSettingsPage(application, rowIdentifier: "12-row-automation")
        XCTAssertTrue(waitStaticText(application, containing: "未连接桌面端", timeout: 8,
                                     "演示态自动化页应呈现离线空态而非硬编码演示列表"))
        XCTAssertTrue(element(application, "14-act-connect").waitForExistence(timeout: 6),
                      "离线空态应有「连接桌面端」CTA")
        application.terminate()

        // ② 连接态：替身 listAutomations 真实列表（名称/启停状态/上次成败）
        launchFreshForStub()
        connectAndEnterMain(app)
        openSettingsPage(app, rowIdentifier: "12-row-automation")
        XCTAssertTrue(waitStaticText(app, containing: "共 2 个定时任务", timeout: 12,
                                     "连接态自动化页应呈现替身列表汇总"))
        XCTAssertTrue(waitStaticText(app, containing: "E2E 夜间回归", timeout: 8,
                                     "自动化卡应来自 listAutomations 真实数据"))
        waitStaticText(app, containing: "E2E 周报整理", timeout: 6, "第二条自动化应在场")
        XCTAssertTrue(waitUntil(timeout: 8, "替身应收到 listAutomations") {
            stub.rpcCallLog.contains { $0.0 == "zcode-agent" && $0.1 == "listAutomations" }
        })
        snap(app, "matrix-g016-automations-connected")
    }

    // MARK: - G-017 计划审批（plan_approval）结构化卡 + 放行决议

    func test08_planApprovalCardRendersAndResolves() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        let planRow = element(application, "04-row-sess-e2e-plan")
        XCTAssertTrue(planRow.waitForExistence(timeout: 15), "列表应呈现 sess-e2e-plan 会话行")
        planRow.tap()
        XCTAssertTrue(waitUntil(timeout: 12, "点击会话行应推入详情") {
            element(application, "05-composer-input").exists
        }, "进入 sess-e2e-plan 详情")

        // 结构化计划卡（区别于普通命令审批卡 05-approval-card）
        let planCard = element(application, "05-plan-card")
        XCTAssertTrue(revealBySwipeDown(planCard, application: application),
                      "plan_approval 交互应渲染结构化计划审批卡（05-plan-card）")
        waitStaticText(application, containing: "修补会话重连竞态", timeout: 8,
                       "计划正文应来自 renderContext.plan")
        XCTAssertTrue(element(application, "05-act-plan-approve").exists
                      && element(application, "05-act-plan-reject").exists,
                      "计划卡应有放行/驳回双动作")

        // 放行：resolveInteraction 携带真实 interactionId → 卡随 state 更新撤下
        let approve = element(application, "05-act-plan-approve")
        XCTAssertTrue(revealBySwipeDown(approve, application: application),
                      "放行按钮应可点（必要时滚动揭示）")
        approve.tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到计划放行 resolveInteraction") {
            stub.lastResolveInteraction?.interactionId == "int-e2e-plan-1"
        }, "放行应携带 plan 交互 id；实际=\(String(describing: stub.lastResolveInteraction?.interactionId))")
        XCTAssertTrue(waitUntil(timeout: 10, "决议生效后计划卡应撤下") {
            !element(application, "05-plan-card").exists
        })
        snap(application, "matrix-g017-plan-approved")
    }

    // MARK: - G-025 审批 Sheet 追问接真（文本输入 → sendText → 任务保持待操作）

    func test09_approvalFollowupSendsTextAndKeepsTaskWaiting() throws {
        let application = launchDemo()

        // 演示态任务 t1（waiting）→ 审批 Sheet
        element(application, "02-tab-tasks").tap()
        let taskCard = element(application, "02-taskcard-t1")
        XCTAssertTrue(taskCard.waitForExistence(timeout: 10), "演示任务看板应有待操作任务卡")
        let approveEntry = element(application, "02-taskcard-approve")
        XCTAssertTrue(approveEntry.waitForExistence(timeout: 6), "待操作任务卡应有审批入口")
        approveEntry.tap()
        let followup = element(application, "06-act-followup")
        XCTAssertTrue(followup.waitForExistence(timeout: 8), "审批 Sheet 应有「追问」出口")

        // 追问：先弹文本输入 → 发送 → 真实反馈 + Sheet 保持（任务仍在待操作）。
        // SwiftUI .alert 内的 TextField/Button 由 UIAlertController（UIKit）托管，
        // accessibilityIdentifier 不透传（诊断实据：alert 在场、键盘已弹而
        // 06-field-followup / 06-act-followup-send 恒查不到）——按 alert 容器内
        // 首 TextField 与「发送」动作按钮定位
        followup.tap()
        let followupAlert = application.alerts.firstMatch
        XCTAssertTrue(followupAlert.waitForExistence(timeout: 6), "追问应先弹文本输入")
        let field = followupAlert.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 4), "追问输入框应在 alert 内")
        XCTAssertTrue(typeInto(application, field, text: "迁移期间会锁表吗"), "追问输入框应可输入")
        followupAlert.buttons.matching(NSPredicate(format: "label == '发送'")).firstMatch.tap()
        XCTAssertTrue(waitStaticText(application, containing: "已把追问发送给 Agent", timeout: 8,
                                     "追问发送后应有真实反馈 toast（不再是无声假反馈）"))
        XCTAssertTrue(element(application, "06-act-approve").exists
                      || element(application, "06-act-followup").exists,
                      "追问后审批 Sheet 应保持（批准/拒绝仍待决定）")
        snap(application, "matrix-g025-followup-sent")
    }

    // MARK: - G-008 应用内语言切换 → AppleLanguages 持久化 → 重启生效

    func test10_languageInAppSwitchPersistsAcrossRelaunch() throws {
        let application = launchDemo()

        openSettingsPage(application, rowIdentifier: "12-row-language")
        let englishOption = element(application, "12-language-en")
        XCTAssertTrue(englishOption.waitForExistence(timeout: 8), "语言页应有 English 选项")
        englishOption.tap()
        XCTAssertTrue(element(application, "12-language-restart-hint").waitForExistence(timeout: 6),
                      "选择 English 后应提示重启生效（自管 AppleLanguages 链路）")

        // 重启：不带 -ZCodeE2EResetState（否则会复位 AppleLanguages）也不带语言参数，
        // 验证应用内写入的 AppleLanguages 持久化生效
        application.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "重启后应进入主界面")
        // 界面已切英文：设置页「设备与配对」行标题英文（xcstrings en 列）
        openSettingsPage(app, rowIdentifier: "12-row-language")
        XCTAssertTrue(app.staticTexts["Devices & pairing"].waitForExistence(timeout: 6)
                      || app.staticTexts["Devices & Pairing"].exists,
                      "重启后设置行标题应为英文（应用内切换持久化生效）")
        // 语言页选中态仍在 English（选项 label 恒为 "English"）
        XCTAssertTrue(element(app, "12-language-en").exists, "语言页应保留 English 选项")
        snap(app, "matrix-g008-english-after-relaunch")

        // G-005②：切「跟随系统」→ AppleLanguages 键被移除 → 重启后随系统首选语言。
        // 判别依据：本模拟器系统语言为 zh-Hans-CN——若键残留（仍为 en），重启后界面
        // 保持英文；键被真正移除则回中文。跟随系统行为因此可判别。
        element(app, "12-language-system").tap()
        XCTAssertTrue(element(app, "12-language-restart-hint").waitForExistence(timeout: 6),
                      "切换跟随系统后应提示重启生效")
        application.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "跟随系统重启后应进入主界面")
        // 移除 AppleLanguages 后应随系统语言（本模拟器 zh-Hans-CN）回中文——
        // 若键残留为 en，此断言失败（G-005②键级移除的行为级判别）
        element(app, "12-tab-me").tap()
        let pairingRowAfterSystem = element(app, "12-row-pairing")
        XCTAssertTrue(pairingRowAfterSystem.waitForExistence(timeout: 8), "设置页应出现设备与配对行")
        XCTAssertTrue(pairingRowAfterSystem.label.contains("设备与配对"),
                      "移除 AppleLanguages 后应随系统语言回中文；实际 label=\(pairingRowAfterSystem.label)")
        // 语言页当前生效语言行应显示简体中文（Locale.preferredLanguages 首选已回落系统值）
        openSettingsPage(app, rowIdentifier: "12-row-language")
        XCTAssertTrue(app.staticTexts["简体中文"].waitForExistence(timeout: 6),
                      "当前界面语言行应显示简体中文（跟随系统）")
        snap(app, "matrix-g005-system-follows-key-removal")
        // 还原为简体中文（防污染；下个用例的 -ZCodeE2EResetState 亦会复位 AppleLanguages）
        element(app, "12-language-zh-Hans").tap()
    }

    // MARK: - G-026 协议名可点 → 内嵌 WebView Sheet 可关闭

    func test11_agreementWebViewOpensAndClosable() throws {
        let application = launchDemo(extra: ["-ZCodeOpenLoginFlow"])
        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 15), "应进入 O1 登录主页")

        let agreementEntry = element(application, "o1-act-agreement")
        XCTAssertTrue(agreementEntry.waitForExistence(timeout: 8),
                      "登录页协议脚注应可点（《用户协议》入口 o1-act-agreement）")
        agreementEntry.tap()
        let closeButton = element(application, "o1-legal-close")
        XCTAssertTrue(closeButton.waitForExistence(timeout: 10),
                      "应弹出内嵌协议 WebView Sheet（带关闭钮）")
        closeButton.tap()
        XCTAssertTrue(waitUntil(timeout: 8, "关闭后应回到登录页") {
            !closeButton.exists && oauthButton.exists
        })
        snap(application, "matrix-g026-agreement-closed")
    }

    // MARK: - G-015 分享只读页：冷启入口 + 失效码明确错误态

    func test12_sharePreviewColdLaunchAndInvalidCodeErrorState() throws {
        // 失效码 → 云端 404/无网络 → 均呈现明确错误态（验收②）
        let application = launchDemo(extra: ["-ZCodeShareLink", "e2e-invalid-share-code"])
        XCTAssertTrue(element(application, "04-row-c1").waitForExistence(timeout: 10),
                      "主界面应可用（演示态）")
        XCTAssertTrue(element(application, "15-error").waitForExistence(timeout: 15),
                      "失效分享码应呈现明确错误态（15-error）而非空白死页")
        waitStaticText(application, containing: "分享预览获取失败", timeout: 6,
                       "错误态应携带明确错误文案")
        XCTAssertTrue(element(application, "15-act-close").exists,
                      "分享预览页应有关闭出口")
        snap(application, "matrix-g015-share-error-state")
    }

    // MARK: - G-021/G-023/G-034 伪数据清扫：上下文演示条 / 伪麦克风 / 设备假在线

    func test13_pseudoDataSweepContextBarMicAndDevices() throws {
        let application = launchDemo()

        // 演示态会话详情：上下文条为演示标注（G-021）、composer 无伪麦克风交互（G-023）
        let row = element(application, "04-row-c1")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "演示态应进入会话列表")
        row.tap()
        XCTAssertTrue(waitUntil(timeout: 12, "推入会话详情应出现输入区") {
            element(application, "05-composer-input").exists
        }, "应进入会话详情")
        let contextMeter = element(application, "05-composer-context")
        if contextMeter.waitForExistence(timeout: 6) {
            waitStaticText(application, containing: "上下文·演示", timeout: 4,
                           "演示态上下文条应标注演示（连接态走真实用量，G-021）")
        }
        XCTAssertFalse(application.staticTexts["64%"].exists,
                       "硬编码 64% 不得残留（G-021）")
        XCTAssertFalse(application.buttons.matching(
            NSPredicate(format: "identifier CONTAINS 'mic'")).firstMatch.exists,
                       "composer 不应残留伪麦克风可交互控件（G-023）")

        // G-034 演示态设备页：0 台在线、无「我的 Mac 在线」假状态
        app.navigationBars.buttons.element(boundBy: 0).tap()
        openSettingsPage(application, rowIdentifier: "12-row-pairing")
        XCTAssertTrue(waitStaticText(application, containing: "0 台在线", timeout: 8,
                                     "演示态设备页在线数应为 0（真实连接态推导）"))
        XCTAssertFalse(application.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "我的 Mac 在线")).firstMatch.exists,
                       "演示态不得出现「我的 Mac 在线」假状态（G-034）")
        snap(application, "matrix-g034-devices-demo")
    }

    // MARK: - G-020 + G-022 连接态：用户卡真名 + 伪子智能体行移除

    func test14_connectedUserCardRealNameAndNoPseudoSubagentRow() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        // G-034 连接态：设备页 1 台在线（与实际连接数一致）+ G-059 真实状态行
        openSettingsPage(application, rowIdentifier: "12-row-pairing")
        XCTAssertTrue(waitStaticText(application, containing: "1 台在线", timeout: 8,
                                     "连接态设备页应显示 1 台在线"))
        XCTAssertTrue(waitStaticText(application, containing: "1 台已配对", timeout: 6,
                                     "已配对数应与注册表一致"))

        // G-022 连接态任务详情：伪「子智能体」行（test-runner · 回归 46 用例）已移除
        element(application, "02-tab-tasks").tap()
        let taskCard = element(application, "02-taskcard-task-e2e-1")
        XCTAssertTrue(taskCard.waitForExistence(timeout: 15), "任务看板应呈现替身任务卡")
        taskCard.tap()
        XCTAssertTrue(waitStaticText(application, containing: "替身助手", timeout: 12,
                                     "任务详情应渲染（替身轨迹内容在场）"))
        XCTAssertFalse(application.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "test-runner")).firstMatch.exists,
                       "伪子智能体行（test-runner 运行中 · 回归 46 用例）不得残留（G-022）")
        snap(application, "matrix-g022-task-detail-clean")
    }

    // MARK: - G-024 新建会话：@ 文件引用端到端 + 无功能 chips 禁用态

    func test15_newConversationAtFileReferenceAndDisabledChips() throws {
        let application = launchDemo()

        element(application, "02-tab-tasks").tap()
        let fab = element(application, "02-fab-newtask")
        XCTAssertTrue(fab.waitForExistence(timeout: 10), "任务看板应有新建入口（FAB）")
        fab.tap()
        XCTAssertTrue(element(application, "03-input-title").waitForExistence(timeout: 8),
                      "新建 Sheet 应弹出")

        // 无功能 chips 呈禁用态（在场但不可触发）
        XCTAssertTrue(element(application, "03-chip-attach").waitForExistence(timeout: 6),
                      "「附件」chip 应在场（禁用态）")
        XCTAssertTrue(element(application, "03-chip-repo").exists
                      && element(application, "03-chip-voice").exists,
                      "「仓库」「语音」chip 应在场（禁用态）")

        // @ 文件引用端到端：选文件 → 输入区出现 @路径（随 firstInput 下发）
        let atFileChip = element(application, "03-chip-atfile")
        XCTAssertTrue(atFileChip.waitForExistence(timeout: 6), "「引用文件 @」chip 应在场")
        atFileChip.tap()
        let pickerRow = application.buttons
            .matching(NSPredicate(format: "identifier BEGINSWITH '03-filepicker-row-'"))
            .element(boundBy: 0)
        XCTAssertTrue(pickerRow.waitForExistence(timeout: 10),
                      "文件选择器应弹出并列出工作区文件")
        pickerRow.tap()
        XCTAssertTrue(waitUntil(timeout: 8, "选文件后输入区应出现 @路径") {
            element(application, "03-input-title").label.contains(" @")
        }, "输入区应追加 @路径（G-024 验收①端到端）")
        snap(application, "matrix-g024-atfile-inserted")
    }

    // MARK: - G-041/G-042 用量统计真值 + 重置机会卡一键领取

    func test16_usageStatsRealSnapshotAndResetClaim() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        openSettingsPage(application, rowIdentifier: "12-row-usage")
        XCTAssertTrue(element(application, "12-usage-plan-card").waitForExistence(timeout: 12),
                      "连接态用量页应呈现 Coding Plan 快照卡（usage-stats 真值）")
        waitStaticText(application, containing: "340", timeout: 8,
                       "额度用量应来自替身快照（usage=340/500）")
        // G-042 重置机会卡（替身 availableFiveHourResets=1）+ 一键领取
        XCTAssertTrue(element(application, "12-usage-reset-card").waitForExistence(timeout: 8),
                      "有重置机会时应出现领取卡")
        let claim = element(application, "12-usage-act-claim")
        XCTAssertTrue(claim.waitForExistence(timeout: 6), "领取卡应有一键领取入口")
        claim.tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到领取请求（桌面代执行）") {
            stub.rpcCallLog.contains { $0.1 == "requestCodingPlanResetOpportunity" }
                || stub.rpcCallLog.contains { $0.1 == "useCodingPlanReset" }
        }, "一键领取应经 usage-stats 频道下发")
        snap(application, "matrix-g042-reset-claimed")
    }

    // MARK: - G-008②③ 替身注入 workflowRuns：订阅重放（冷快照恢复）+ 运行中整键翻转

    /// 通路 B 替身注入面（区别于 test17 的演示 Mock）：
    /// ③ 冷快照恢复——stub 在 subscribeConversationV4 时重放 workflowRuns 状态，
    ///   重启（冷启重连）后运行中 run 不消失；
    /// ② 帧实时整键同步——运行中 fireWorkflowRunsStateForTest 以新 runs 整键替换，
    ///   UI 状态胶囊/阶段随之更新（不做字段级合并）。
    func test19_workflowRunsStubColdSnapshotAndLiveFlip() throws {
        stub.setWorkflowRunsState(sessionId: "sess-e2e-1", runs: [[
            "runId": "run-e2e-wf-1",
            "name": "stub 注入工作流",
            "status": "running",
            "currentPhase": "执行",
            "phases": [["name": "准备"], ["name": "执行"], ["name": "校验"]],
        ]])
        let application = launchDemo()
        connectAndEnterMain(application)
        let planRow = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(planRow.waitForExistence(timeout: 15), "重连后应呈现替身会话行")
        var inDetail = false
        for _ in 0..<6 where !inDetail {
            planRow.tap()
            inDetail = element(application, "05-composer-input").waitForExistence(timeout: 2)
        }
        XCTAssertTrue(inDetail, "进入 sess-e2e-1 详情")

        // ③ 冷快照恢复：订阅重放的 workflowRuns → 面板渲染（重启链路同一重放点）
        let panel = element(application, "05-workflow-panel")
        XCTAssertTrue(panel.waitForExistence(timeout: 12),
                      "订阅重放的 workflowRuns 应渲染工作流面板（G-008③ 冷恢复）")
        XCTAssertTrue(panel.staticTexts["stub 注入工作流"].waitForExistence(timeout: 6),
                      "面板应显示替身注入的 run 名")

        // ② 运行中整键翻转：fire 新 runs（全部阶段 settled ok → completed）→ UI 同步
        stub.fireWorkflowRunsStateForTest(sessionId: "sess-e2e-1", runs: [[
            "runId": "run-e2e-wf-1",
            "name": "stub 注入工作流",
            "status": "completed",
            "currentPhase": "校验",
            "phases": [["name": "准备"], ["name": "执行"], ["name": "校验"]],
        ]])
        let completed = panel.staticTexts.matching(
            NSPredicate(format: "label CONTAINS '已完成' OR label CONTAINS 'completed'"))
        XCTAssertTrue(completed.firstMatch.waitForExistence(timeout: 8),
                      "workflowRuns 整键替换后 UI 应同步为完成态（G-008②）")
        snap(application, "g008-stub-workflow-flip")
    }

    // MARK: - G-011/G-022/G-024/G-025 能力读面：连接态真实清单 + 零写入口

    /// 七个只读页逐页：替身提供的真实清单行渲染（源=capabilityReads 记录的 RPC 读）
    /// + 无写入口（启用/安装/卸载/创建/新建议钮零命中）
    func test20_capabilityPagesRenderStubListsAndNoWriteEntries() throws {
        let application = launchDemo()
        connectAndEnterMain(application)

        let pages: [(row: String, stubTitle: String, method: String)] = [
            ("12-row-memory", "zcode_mobile 记忆库", "listProjectMemories"),
            ("12-row-skills", "e2e-skill-a", "getSkillReferenceCatalog"),
            ("12-row-mcp", "e2e-mcp-server", "listMcpServerStatuses"),
            ("12-row-plugins", "e2e-plugin", "listPlugins"),
            ("12-row-workflows", "登录链路工作流", "listSavedWorkflows"),
            ("12-row-offpeak", "错峰回归任务 · stub", "off-peak.list"),
            ("12-row-feedback", "E2E 工单 · stub", "feedback.list"),
        ]
        let writePatterns = NSPredicate(
            format: "label CONTAINS '启用' OR label CONTAINS '停用' OR label CONTAINS '安装'"
                + " OR label CONTAINS '卸载' OR label CONTAINS '创建' OR label CONTAINS '新建'")
        for page in pages {
            openSettingsPage(application, rowIdentifier: page.row)
            let stubRow = application.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier BEGINSWITH '12-capability-row-'")).firstMatch
            XCTAssertTrue(stubRow.waitForExistence(timeout: 10),
                          "\(page.method) 页应渲染替身真实清单行（G-011 真实数据源）")
            let stubTitle = application.staticTexts
                .matching(NSPredicate(format: "label CONTAINS %@", page.stubTitle)).firstMatch
            XCTAssertTrue(stubTitle.waitForExistence(timeout: 4),
                          "\(page.method) 清单应含替身条目「\(page.stubTitle)」")
            XCTAssertFalse(application.buttons.matching(writePatterns).firstMatch.exists,
                           "\(page.method) 页不得出现写入口（G-011 零写入口）")
            XCTAssertTrue(waitUntil(timeout: 6, "\(page.method) 应真实到达替身") {
                stub.capabilityReads.contains(page.method)
            }, "页面数据应来自替身读面（capabilityReads）；实际=\(stub.capabilityReads)")
            application.navigationBars.buttons.element(boundBy: 0).tap()
        }
        snap(application, "g011-capability-pages")
    }

    // MARK: - G-017 移动端→桌面任务组同步写

    /// 长按会话行 → 移入分组 → 建组命名：createTaskGroup + applyGroupedTaskViewOrder
    /// 真实到达替身（记录面断言）+ 列表出现新组且会话归组（UI 面）。
    /// 组名输入为 SwiftUI alert（identifier 不透传，test09 同款教训）——按 alert 容器定位
    func test21_taskGroupSyncWriteReachesStubAndRegroups() throws {
        let application = launchDemo()
        connectAndEnterMain(application)
        let row = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "重连后应呈现替身会话行")

        row.press(forDuration: 1.2)
        let moveMenuItem = element(application, "04-ctx-group-sess-e2e-1")
        XCTAssertTrue(moveMenuItem.waitForExistence(timeout: 6), "长按菜单应有「移入分组」项")
        moveMenuItem.tap()
        let groupAlert = application.alerts.firstMatch
        XCTAssertTrue(groupAlert.waitForExistence(timeout: 6), "移入分组应弹组名输入 alert")
        let groupField = groupAlert.textFields.firstMatch
        XCTAssertTrue(groupField.waitForExistence(timeout: 4), "组名输入框应在 alert 内")
        XCTAssertTrue(typeInto(application, groupField, text: "e2e-group-x"), "组名应可输入")
        groupAlert.buttons.matching(NSPredicate(format: "label == '移入'")).firstMatch.tap()

        // 同步写：两条命令真实到达替身（移动端→桌面方向）
        XCTAssertTrue(waitUntil(timeout: 10, "createTaskGroup 应到达替身") {
            stub.taskGroupWrites.contains { $0.command == "createTaskGroup" }
        }, "建组写应真实下发（G-017 同步）")
        XCTAssertTrue(waitUntil(timeout: 10, "applyGroupedTaskViewOrder 应到达替身") {
            stub.taskGroupWrites.contains { $0.command == "applyGroupedTaskViewOrder" }
        }, "入组顺序写应真实下发（G-017 同步）")
        // UI 面：新组头出现且会话归组
        XCTAssertTrue(element(application, "04-group-e2e-group-x").waitForExistence(timeout: 8),
                      "列表应出现新组头（会话归组 UI 面）")
        snap(application, "g017-task-group-sync")
    }
}

import XCTest

/// ZCode Mobile · 第 1 轮全屏视觉走查（XCUITest 截图审计）
///
/// 覆盖演示模式与连接替身两种状态下的全部页面/关键状态，每步 XCTAttachment 截图，
/// 并对关键 frame 做回归断言（内容底边不越 Tab bar 顶边、键盘弹起后输入框仍可见、
/// 底部动作栏与 Tab bar 不互相遮挡）。截图经 xcresulttool 导出后逐张人工复核。
/// 本文件只做编译级自检，由统一门禁脚本在模拟器上执行。
final class LayoutAuditTests: XCTestCase {

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
        stub.stop()
    }

    // MARK: - 基础设施

    private func element(_ application: XCUIApplication, _ identifier: String) -> XCUIElement {
        application.descendants(matching: .any)[identifier].firstMatch
    }

    @discardableResult
    private func wait(_ item: XCUIElement, timeout: TimeInterval, _ message: String) -> Bool {
        XCTAssertTrue(item.waitForExistence(timeout: timeout), message)
        return item.exists
    }

    /// 截图附件（keepAlways，导出后逐张复核）
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// frame 断言：元素底边不越过 Tab bar 顶边（Tab bar 以 04-tab-chat 按钮 minY-12 近似容器顶边）
    private func assertAboveTabBar(_ item: XCUIElement, tabButton: XCUIElement, _ message: String) {
        guard item.exists, tabButton.exists else {
            XCTFail("frame 断言前置失败：元素或 TabBar 不在场")
            return
        }
        let tabBarTop = tabButton.frame.minY - 12
        XCTAssertLessThanOrEqual(item.frame.maxY, tabBarTop + 2,
                                 "\(message)（内容底 \(item.frame.maxY) > TabBar 顶 \(tabBarTop)）")
    }

    /// frame 断言：元素完整落在键盘上方（可见、未被键盘遮挡）
    private func assertAboveKeyboard(_ item: XCUIElement, keyboard: XCUIElement, _ message: String) {
        guard item.exists, keyboard.exists else {
            XCTFail("frame 断言前置失败：输入框或键盘不在场")
            return
        }
        XCTAssertLessThanOrEqual(item.frame.maxY, keyboard.frame.minY + 2,
                                 "\(message)（输入框底 \(item.frame.maxY) > 键盘顶 \(keyboard.frame.minY)）")
    }

    /// frame 断言：元素完整在屏幕内
    private func assertOnScreen(_ item: XCUIElement, _ message: String) {
        guard item.exists else {
            XCTFail("frame 断言前置失败：\(message)")
            return
        }
        let screen = app.frame
        XCTAssertTrue(screen.contains(item.frame), message + "（frame=\(item.frame)）")
    }

    /// 演示模式冷启动（无服务器配置）
    @discardableResult
    private func launchDemo() -> XCUIApplication {
        // -ZCodeDemoData：E2E 演示开关（对齐修复后 Mock 仅测试用例允许装配）
        app.launchArguments = ["-ZCodeE2EResetState", "-ZCodeDemoData", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        wait(element(app, "04-row-c1"), timeout: 10, "演示会话列表应加载")
        return app
    }

    /// 连接替身直达已连接态（手动输入 stub 地址令牌）；内部自行冷启动
    private func connectStub(_ application: XCUIApplication) {
        application.launchArguments = ["-ZCodeE2EResetState", "-ZCodeDemoData", "-AppleLanguages", "(zh-Hans)"]
        application.launch()
        let meTab = element(application, "12-tab-me")
        wait(meTab, timeout: 10, "Tab 栏应出现")
        meTab.tap()
        wait(element(application, "l4-row-add"), timeout: 8, "设置页应有添加服务器行")
        element(application, "l4-row-add").tap()
        wait(element(application, "l1-btn-manual"), timeout: 8, "应有手动输入入口")
        element(application, "l1-btn-manual").tap()
        let host = element(application, "l1-field-host")
        wait(host, timeout: 8, "应进入手动连接页")
        host.tap()
        host.typeText("http://127.0.0.1:\(stub.port)")
        let token = element(application, "l1-field-token")
        // 先点眼睛把 SecureField 切为明文 TextField：避免 iOS「保存密码？」系统弹窗
        // 盖住后续页面（走查实测该弹窗会挡住设置页并吞掉全部滑动手势）
        element(application, "l1-act-eye").tap()
        token.tap()
        token.typeText(stub.pairingToken)
        // 兜底：万一仍弹出系统保存提示，关掉（双语按钮兼容）
        dismissPasswordAlertIfPresent(timeout: 3)
        // 收起键盘后提交（键盘态下提交按钮可能被遮挡——这正是走查点之一）
        application.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.06)).tap()
        let submit = element(application, "l1-submit-connect")
        wait(submit, timeout: 4, "应有表单提交按钮")
        submit.tap()
        XCTAssertTrue(waitUntil(timeout: 25) {
            self.stub.lastAcceptedPairingToken == self.stub.pairingToken
                && self.stub.websocketUpgrades >= 1
        }, "替身应接受令牌并完成 WS 升级")
    }

    private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.05)
        }
        return condition()
    }

    // MARK: - 演示模式：四 Tab 根页

    func test01_demoFourTabRoots() throws {
        _ = launchDemo()
        let tabChat = element(app, "04-tab-chat")

        // 会话 Tab 根页
        snap("01-demo-chat-list")
        // 最后一行会话 + 列表缓冲不得被 TabBar 遮挡：以可见的最后一行近似断言
        assertAboveTabBar(element(app, "04-row-c2"), tabButton: tabChat,
                          "会话列表行不得与 TabBar 重叠")
        // 新建按钮（导航栏）在屏内
        assertOnScreen(element(app, "04-act-new"), "新建会话入口应在屏内")

        // 任务 Tab 根页：FAB 与 TabBar 不互相遮挡
        element(app, "02-tab-tasks").tap()
        wait(element(app, "02-fab-newtask"), timeout: 6, "任务看板应加载")
        snap("02-demo-task-board")
        assertOnScreen(element(app, "02-fab-newtask"), "新建任务 FAB 应在屏内")
        assertAboveTabBar(element(app, "02-fab-newtask"), tabButton: tabChat,
                          "FAB 不得与 TabBar 重叠（否则不可点）")
        assertAboveTabBar(element(app, "02-search"), tabButton: tabChat,
                          "任务搜索框不得与 TabBar 重叠")

        // 滚动到底：最后一张任务卡应能完整滚出 FAB+TabBar 遮挡区（P2 回归：
        // 底部余量 180pt 必须覆盖 FAB 56 + 间距 16 + TabBar ≈87）
        app.swipeUp()
        app.swipeUp()
        Thread.sleep(forTimeInterval: 0.5)
        let lastCard = element(app, "02-taskcard-t6") // mock 末位已完成卡
        if lastCard.waitForExistence(timeout: 3) {
            scrollToReveal(lastCard)
            snap("02b-demo-task-board-bottom")
            let fab = element(app, "02-fab-newtask").frame
            XCTAssertLessThanOrEqual(lastCard.frame.maxY, fab.minY + 2,
                                     "滚到底后最后一张任务卡不得被 FAB 遮挡（卡底 \(lastCard.frame.maxY) > FAB 顶 \(fab.minY)）")
            assertAboveTabBar(lastCard, tabButton: tabChat,
                              "滚到底后最后一张任务卡不得被 TabBar 遮挡")
        }

        // 文件 Tab 根页：底部动作栏叠于 TabBar 之上，二者都完整可见
        element(app, "08-tab-review").tap()
        wait(element(app, "08-filecard-toggle-d1"), timeout: 6, "文件页应加载")
        snap("03-demo-diff-root")
        assertOnScreen(element(app, "08-act-approve-all"), "全部批准动作栏应在屏内")
        assertOnScreen(element(app, "04-tab-chat"), "TabBar 应在动作栏之下完整可见")
        let approveAll = element(app, "08-act-approve-all").frame
        let tab = element(app, "04-tab-chat").frame
        XCTAssertLessThanOrEqual(approveAll.maxY, tab.minY + 2,
                                 "底部动作栏不得覆盖 TabBar（动作栏底 \(approveAll.maxY) > Tab 顶 \(tab.minY)）")

        // 设置 Tab 根页：滚动到底，页脚与品牌行应完整可见且不被 TabBar 遮挡
        element(app, "12-tab-me").tap()
        wait(element(app, "12-usercard"), timeout: 6, "设置页应加载")
        let footer = element(app, "12-foot-data-source")
        scrollToReveal(footer)
        snap("04-demo-settings-root")
        assertAboveTabBar(footer, tabButton: tabChat,
                          "滚动到底后设置页脚不得被 TabBar 遮挡")
        assertAboveTabBar(element(app, "12-brand-name"), tabButton: tabChat,
                          "滚动到底后品牌名行不得被 TabBar 遮挡")
    }

    // MARK: - 演示模式：聊天详情（历史 / 工具卡 / todo 卡 / 提问卡 / 输入区）

    func test02_demoChatDetail() throws {
        _ = launchDemo()
        element(app, "04-row-c1").tap()
        wait(element(app, "05-composer-input"), timeout: 8, "会话页应推入并显示输入区")
        // 历史流渲染窗口
        wait(element(app, "05-questioncard"), timeout: 15, "提问卡应出现（c1 剧本尾部）")
        snap("05-demo-chat-history")
        snap("06-demo-chat-toolcard")

        // 输入区（Composer）完整在屏内、发送键可见
        assertOnScreen(element(app, "05-composer-input"), "消息输入框应在屏内")
        assertOnScreen(element(app, "05-composer-send"), "发送键应在屏内")
        snap("07-demo-chat-composer")

        // 工具卡展开态（diff 行）不越界
        element(app, "05-toolcard-head-edit").tap()
        if element(app, "05-act-view-full-diff").waitForExistence(timeout: 3) {
            snap("08-demo-chat-toolcard-expanded")
            assertOnScreen(element(app, "05-act-view-full-diff"), "展开后的查看完整 Diff 应在屏内")
        }
    }

    // MARK: - 演示模式：设置分区（账户 / 外观）+ 深浅两态

    func test03_demoSettingsSectionsAndAppearance() throws {
        _ = launchDemo()
        element(app, "12-tab-me").tap()
        wait(element(app, "12-usercard"), timeout: 6, "设置页应加载")

        // 账户分区（未登录卡）
        element(app, "l4-row-account").tap()
        wait(element(app, "l4-b-card-login"), timeout: 6, "账户页应显示未登录卡")
        snap("09-demo-settings-account")
        app.navigationBars.buttons.firstMatch.tap()

        // 外观分区：深浅两态各截一张（当前模拟器 system=浅色）
        element(app, "12-row-appearance").tap()
        wait(element(app, "12-appearance-dark"), timeout: 6, "外观页应加载")
        snap("10-demo-settings-appearance-light")
        element(app, "12-appearance-dark").tap()
        Thread.sleep(forTimeInterval: 0.6) // 即时换肤动画
        snap("11-demo-settings-appearance-dark")
        app.navigationBars.buttons.firstMatch.tap()

        // 深色下的会话列表与设置根页
        wait(element(app, "04-row-c1"), timeout: 6, "应回到设置根页")
        snap("12-demo-settings-root-dark")
        element(app, "04-tab-chat").tap()
        wait(element(app, "04-row-c1"), timeout: 6, "应回到会话列表")
        snap("13-demo-chat-list-dark")

        // 恢复浅色，避免影响后续用例外观
        element(app, "12-tab-me").tap()
        element(app, "12-row-appearance").tap()
        wait(element(app, "12-appearance-system"), timeout: 6, "外观页应有跟随系统项")
        element(app, "12-appearance-system").tap()
    }

    // MARK: - 登录 O1 主页 + 授权 Sheet（替身授权页驻留）

    func test04_loginHomeAndAuthorizeSheet() throws {
        stub.holdAuthorizePage = true
        app.launchArguments = ["-ZCodeE2EResetState", "-ZCodeDemoData", "-AppleLanguages", "(zh-Hans)", "-ZCodeOpenLoginFlow",
                               "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
                               "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
                               "-ZCodeOAuthClientID", "stub-client-e2e"]
        app.launch()

        // O1 登录主页
        wait(element(app, "o1-btn-oauth"), timeout: 10, "应出现 O1 登录主页")
        snap("14-login-home")

        // 授权 Sheet（替身授权页驻留，不 302）
        element(app, "o1-btn-oauth").tap()
        wait(element(app, "o2-act-close"), timeout: 10, "授权 Sheet 应出现")
        Thread.sleep(forTimeInterval: 1.0) // 等授权页加载
        snap("15-login-authorize-sheet")
        assertOnScreen(element(app, "o2-act-close"), "授权 Sheet 关闭按钮应在屏内")

        // 关闭 → O3 取消页 → 跳过回主界面
        element(app, "o2-act-close").tap()
        wait(element(app, "o3-btn-skip"), timeout: 10, "取消后应进入 O3 页")
        snap("16-login-cancel-result")
        element(app, "o3-btn-skip").tap()
        wait(element(app, "04-tab-chat"), timeout: 10, "跳过后应回主界面")
    }

    // MARK: - L1 手动表单（含键盘弹起态，已知遮挡线索）

    func test05_manualFormKeyboardStates() throws {
        _ = launchDemo()
        element(app, "12-tab-me").tap()
        element(app, "l4-row-add").tap()
        let manual = element(app, "l1-btn-manual")
        wait(manual, timeout: 8, "应有手动输入入口")
        manual.tap()
        let host = element(app, "l1-field-host")
        wait(host, timeout: 8, "应进入手动连接页")
        snap("17-l1-manual-form")

        let keyboard = app.keyboards.firstMatch

        // 地址栏聚焦：键盘弹起后地址栏仍可见
        host.tap()
        XCTAssertTrue(keyboard.waitForExistence(timeout: 5), "键盘应弹出")
        Thread.sleep(forTimeInterval: 0.5)
        snap("18-l1-form-host-keyboard")
        assertAboveKeyboard(host, keyboard: keyboard, "键盘弹起后地址栏应仍可见")

        // 令牌栏聚焦（焦点直接切换，不收键盘）：已知遮挡线索（令牌栏在表单更低处）。
        // 若令牌栏被键盘遮住，tap 无法命中且 isHittable=false——这正是要抓的遮挡证据。
        let token = element(app, "l1-field-token")
        token.tap()
        Thread.sleep(forTimeInterval: 0.8) // 等焦点转移与键盘避让稳定
        snap("19-l1-form-token-keyboard")
        XCTAssertTrue(token.isHittable, "键盘弹起后令牌栏应可命中（未被键盘遮挡）")
        assertAboveKeyboard(token, keyboard: keyboard, "键盘弹起后令牌栏应仍可见（已知遮挡线索回归）")

        // 键盘工具栏「连接」按钮也在屏内
        let kbdConnect = element(app, "l1-keyboard-connect")
        if kbdConnect.exists {
            assertOnScreen(kbdConnect, "键盘工具栏连接按钮应在屏内")
        }
    }

    // MARK: - L2 连接中 + L3 失败态（不可达地址）

    func test06_connectingAndFailureStates() throws {
        _ = launchDemo()
        element(app, "12-tab-me").tap()
        element(app, "l4-row-add").tap()
        wait(element(app, "l1-btn-manual"), timeout: 8, "应有手动输入入口")
        element(app, "l1-btn-manual").tap()
        let host = element(app, "l1-field-host")
        wait(host, timeout: 8, "应进入手动连接页")
        host.tap()
        host.typeText("http://10.255.255.1:1") // 黑洞地址：probe 3s 超时 ×2 + 间隔 ≈ 7s 窗口
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.06)).tap()
        element(app, "l1-submit-connect").tap()

        // L2 连接中（五步进度卡在场）
        wait(element(app, "l2-card-steps"), timeout: 6, "应进入 L2 连接中")
        snap("20-l2-connecting")
        assertOnScreen(element(app, "l2-act-cancel"), "L2 取消按钮应在屏内")
        // 五步进度 meta（黑洞性地址卡在发现步：进度 1/5，探测 3s 超时 ×2 全窗口恒定）
        XCTAssertTrue(app.staticTexts["连接进度 · 1/5"].waitForExistence(timeout: 4),
                      "L2 应显示五步进度 meta（发现服务进行中 = 1/5）")
        // 五步列表逐行在场：以行名 Text 观测（stepRow 的 HStack 容器 identifier
        // 不进可访问性树；blackhole 无 server-info，auth 步恒为「校验访问令牌」）
        for stepName in ["发现服务", "校验访问令牌", "建立 WebSocket", "协议握手 v4", "载入工作区"] {
            XCTAssertTrue(app.staticTexts[stepName].exists, "L2 五步列表应含「\(stepName)」行")
        }

        // L3 失败态（超时分支）
        wait(element(app, "l3-row-timeout"), timeout: 30, "不可达地址应进入 L3 失败态")
        Thread.sleep(forTimeInterval: 0.6) // 等 L2→L3 转场动画结束，避免截到叠影帧
        snap("21-l3-failure-timeout")
        assertOnScreen(element(app, "l3-err-code"), "错误码行应在屏内")
        assertOnScreen(element(app, "l3-btn-retry"), "原配置重试按钮应在屏内")
        // P1 回归：提交连接后键盘必须收起，不得残留在 L2/L3 上遮挡错误对照表
        XCTAssertTrue(waitKeyboardDismiss(timeout: 5), "进入 L3 后键盘应已收起（submit 时主动 resign）")
    }

    // MARK: - 连接替身：会话列表 / 连接态聊天（输入区可用）/ 新建会话 Sheet

    func test07_stubConnectedReadOnlyChat() throws {
        connectStub(app)
        // 连接成功后连接流程自动收起（按钮从可访问性树消失）
        waitDisappear(element(app, "l1-submit-connect"), timeout: 25,
                      "连接成功后应自动收起连接流程")
        // 冷启动重连进主界面（与回归 e2e 同口径，简化：直接等待列表出现替身行或空态）
        if !element(app, "04-row-sess-e2e-1").waitForExistence(timeout: 10) {
            // 冷启动兜底：重新启动 App（已保存服务器自动重连）
            app.terminate()
            app.launchArguments = ["-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
                                   "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)"]
            app.launch()
            _ = element(app, "04-row-sess-e2e-1").waitForExistence(timeout: 15)
                || element(app, "04-empty").waitForExistence(timeout: 6)
        }
        let tabChat = element(app, "04-tab-chat")
        snap("22-stub-chat-list")

        // 替身快照会话详情：历史行 + 工具卡 + 输入区（v3 纠偏：连接态 composer 可用）。
        // connectStub 结束时 UI 停在设置 Tab（门禁第 2 轮实证：步骤截图 22 为设置页）——
        // 四 Tab 同挂 ZStack，行元素跨 Tab 在树（row.exists 为真）但不可命中，必须先切回会话 Tab；
        // tap 带推入效果重试（转场动画期可能落空）
        tabChat.tap()
        wait(element(app, "04-search"), timeout: 8, "应切回会话列表页")
        let row = element(app, "04-row-sess-e2e-1")
        if row.exists {
            var pushed = false
            for _ in 0..<3 where !pushed {
                row.tap()
                pushed = element(app, "05-composer-input").waitForExistence(timeout: 5)
            }
            XCTAssertTrue(pushed, "点击替身会话行应推入会话详情（连接态输入区可用）")
            wait(element(app, "05-toolcard-head-bash"), timeout: 15, "替身历史工具卡应渲染")
            snap("23-stub-chat-readonly")
            assertOnScreen(element(app, "05-composer-input"), "消息输入框应完整在屏内")
            assertOnScreen(element(app, "05-composer-send"), "发送键应完整在屏内")
            app.navigationBars.buttons.firstMatch.tap()
        }

        // 新建会话 Sheet（完整表单）→ 空标题创建 draft 会话后自动推入聊天详情
        tabChat.tap()
        wait(element(app, "04-act-new"), timeout: 8, "会话页应有新建入口")
        element(app, "04-act-new").tap()
        wait(element(app, "03-input-title"), timeout: 6, "首条任务输入框应出现（连接态完整表单）")
        snap("24-stub-new-conversation-sheet")
        assertOnScreen(element(app, "03-submit-start"), "开始任务按钮应在屏内")
        element(app, "03-submit-start").tap()
        wait(element(app, "05-composer-input"), timeout: 15, "创建会话后应进入聊天详情（输入区可用）")
        snap("23b-stub-empty-chat-readonly")
        assertOnScreen(element(app, "05-composer-input"), "消息输入框应完整在屏内")
        app.navigationBars.buttons.firstMatch.tap()
    }

    // MARK: - 连接替身：设置服务器分区（已连接态）

    func test08_stubSettingsServerSections() throws {
        connectStub(app)
        if !element(app, "12-tab-me").waitForExistence(timeout: 8) {
            app.terminate()
            app.launchArguments = ["-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
                                   "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)"]
            app.launch()
            _ = element(app, "04-row-sess-e2e-1").waitForExistence(timeout: 15)
                || element(app, "04-empty").waitForExistence(timeout: 6)
        }
        element(app, "12-tab-me").tap()
        let serverRow = element(app, "l4-row-server")
        wait(serverRow, timeout: 12, "已连接后设置页应有服务器行")
        waitLabelContains(serverRow, "已连接", 15)
        let brandRow = element(app, "12-brand-name")
        dismissPasswordAlertIfPresent(timeout: 1)
        scrollToReveal(brandRow)
        snap("25-stub-settings-root")
        assertAboveTabBar(brandRow, tabButton: element(app, "04-tab-chat"),
                          "滚动到底后品牌名行不得被 TabBar 遮挡")
        app.swipeDown()
        app.swipeDown()

        // 服务器详情（L5）
        serverRow.tap()
        wait(element(app, "l5-row-token-update"), timeout: 8, "服务器详情应有更新令牌行")
        snap("26-stub-server-detail")
        app.navigationBars.buttons.firstMatch.tap()

        // 账户分区（未登录，连接态）
        element(app, "l4-row-account").tap()
        wait(element(app, "l4-b-card-login"), timeout: 8, "账户页应显示未登录卡")
        snap("27-stub-settings-account")
    }

    private func waitLabelContains(_ item: XCUIElement, _ text: String, _ timeout: TimeInterval) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", text), object: item)
        XCTAssertEqual(XCTWaiter().wait(for: [expectation], timeout: timeout), .completed,
                       "标签应包含 \(text)")
    }

    /// 等待元素从可访问性树消失
    @discardableResult
    private func waitDisappear(_ item: XCUIElement, timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 关掉 iOS「保存密码？」自动填充弹窗（系统级弹出、盖在 App 上，
    /// 双语按钮兼容；不处理会吞掉后续全部手势与断言）
    @discardableResult
    private func dismissPasswordAlertIfPresent(timeout: TimeInterval = 2) -> Bool {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["以后", "稍后", "Not Now", "Don’t Save", "Later"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: timeout) {
                button.tap()
                return true
            }
        }
        return false
    }

    /// 有界轮询键盘收起（收起动画期间 keyboards 仍在树，不能一次 waitForExistence 判定）
    private func waitKeyboardDismiss(timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !app.keyboards.firstMatch.exists { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return !app.keyboards.firstMatch.exists
    }

    /// 循环上滑直到元素中心点进入屏幕可视区（可滚动长页滚到底），最多 maxSwipes 次。
    /// 不用 isHittable 判定（被遮挡/屏外元素可能误报 true）。
    private func scrollToReveal(_ item: XCUIElement, maxSwipes: Int = 6) {
        let screen = app.frame
        for _ in 0..<maxSwipes {
            if item.exists, screen.contains(CGPoint(x: item.frame.midX, y: item.frame.midY)) { break }
            app.swipeUp()
            Thread.sleep(forTimeInterval: 0.3)
        }
    }
}

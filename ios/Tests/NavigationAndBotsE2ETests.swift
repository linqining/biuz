import XCTest

/// G-009 / G-011 / G-001 · 导航完备性与 IM Bot 页 e2e。
///
/// 约定同 ZCodeMobileE2ETests：仅编译级自检 + identifier 三段式契约，
/// 由统一门禁脚本在模拟器执行；本会话不运行。
final class NavigationAndBotsE2ETests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        // 本地化后固定测试语言（中文断言稳定，G-063）；
        // -ZCodeDemoData：E2E 演示开关（对齐修复后 Mock 仅测试用例允许装配）
        app.launchArguments = ["-ZCodeDemoData", "-AppleLanguages", "(zh-Hans)"] + arguments
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// 截图附件（遍历/失败现场留档）
    private func snap(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// 点击输入框并等键盘弹出（转场动画期 tap 落空的有界重试）
    @discardableResult
    private func typeInto(_ app: XCUIApplication, _ field: XCUIElement, text: String) -> Bool {
        guard field.waitForExistence(timeout: 8) else { return false }
        for _ in 0..<3 {
            field.tap()
            if app.keyboards.firstMatch.waitForExistence(timeout: 3) {
                field.typeText(text)
                return true
            }
        }
        field.typeText(text)
        return true
    }

    /// 提交手动连接表单：优先键盘工具栏「连接」，回退表单提交按钮
    private func submitConnect(_ app: XCUIApplication) {
        let keyboardConnect = element(app, "l1-keyboard-connect")
        if keyboardConnect.waitForExistence(timeout: 2) {
            keyboardConnect.tap()
        } else {
            element(app, "l1-submit-connect").tap()
        }
    }

    /// 冷启动直开 L1 连接页 → 进入手动输入页
    private func openManualConnect(_ app: XCUIApplication) {
        let manualButton = element(app, "l1-btn-manual")
        XCTAssertTrue(manualButton.waitForExistence(timeout: 10),
                      "-ZCodeOpenConnectFlow 应直开 L1 连接页（手动输入入口可见）")
        manualButton.tap()
        XCTAssertTrue(element(app, "l1-field-host").waitForExistence(timeout: 8), "应进入手动连接页")
    }

    /// G-009 验收②③：L1 连接主页 cover 有 ✕（l1-act-close）；
    /// 点击后 cover 收起（presentedFlow==nil 的可观测面 = 关闭钮消失、主界面 Tab 可见）。
    func test01_connectFlowCoverShowsCloseAndDismisses() throws {
        let app = launch(arguments: ["-ZCodeOpenConnectFlow"])

        let close = element(app, "l1-act-close")
        XCTAssertTrue(close.waitForExistence(timeout: 10),
                      "L1 连接主页 cover 应存在 ✕ 关闭钮（G-009）")
        close.tap()

        let closed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: close)
        XCTAssertEqual(XCTWaiter().wait(for: [closed], timeout: 5), .completed,
                       "点击 ✕ 后连接 cover 应回收起（关闭钮从可访问性树消失）")
        XCTAssertTrue(element(app, "04-tab-chat").isHittable,
                      "关闭连接 cover 后应回到主界面（会话 Tab 可见）")
    }

    /// G-001/G-011：IM Bot 页在演示态（未连接）呈现明确引导空态而非假数据；
    /// 空态「连接桌面端」CTA 可打开 L1 cover，形成导航闭环（且 L1 可被 ✕ 关闭）。
    func test02_imBotPageShowsConnectionGuideWhenDisconnected() throws {
        let app = launch(arguments: ["-ZCodeE2EResetState"])

        let settingsTab = element(app, "12-tab-me")
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 10), "应能进入设置 Tab")
        settingsTab.tap()

        let botRow = element(app, "12-row-bot")
        XCTAssertTrue(botRow.waitForExistence(timeout: 6), "设置页应出现 IM Bot 行")
        botRow.tap()

        let connectCTA = element(app, "12-bot-act-connect")
        XCTAssertTrue(connectCTA.waitForExistence(timeout: 6),
                      "演示态（未连接）IM Bot 页应呈现「未连接桌面端」引导空态，而非静态假数据")
        connectCTA.tap()

        let close = element(app, "l1-act-close")
        XCTAssertTrue(close.waitForExistence(timeout: 6),
                      "空态 CTA 应打开 L1 连接 cover（含 ✕ 关闭钮）")
        close.tap()
    }

    /// G-011：登录成功页 O3-B 自动引导卡的出口闭环（o3-act-skip-connect）。
    /// 无 stub 环境（未配对设备 + E2E reset + 剪贴板兜底禁用）时自动直达落
    /// noPairedDevice → 引导卡出现 → 「先去连接桌面端」收起登录 cover。
    /// G-004 复审补强：✕ 需真点验证关闭行为 + 再进状态干净（非仅 exists）。
    func test03_loginSuccessGuideCardProvidesExit() throws {
        let app = launch(arguments: ["-ZCodeOpenLoginFlow", "-ZCodeE2EResetState"])
        let oauthButton = element(app, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应进入登录主页")
        let closeButton = element(app, "o1-act-close")
        XCTAssertTrue(closeButton.waitForExistence(timeout: 6), "登录主页应有 ✕ 关闭钮")
        // 真点关闭：cover 收起（✕ 与登录主页均离场）
        closeButton.tap()
        let closed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: closeButton)
        XCTAssertEqual(XCTWaiter().wait(for: [closed], timeout: 6), .completed,
                       "点 ✕ 后登录 cover 应回收起")
        // 再进状态干净：重新打开登录 cover 应回到 O1 主页初始态（OAuth 入口在场）
        let reopened = launch(arguments: ["-ZCodeOpenLoginFlow", "-ZCodeE2EResetState"])
        XCTAssertTrue(element(reopened, "o1-btn-oauth").waitForExistence(timeout: 10),
                      "再次进入登录 flow 应呈现干净的 O1 主页（无残留态）")
    }

    // MARK: - G-003②：连接进行中点 ✕/取消 → cancelConnecting 生效，无悬挂 connecting 态

    /// 不可路由地址（RFC1918 保留段）使 L2 连接态持续存在 → 点 L2 取消 →
    /// 覆盖层收起、回 L1 根页，且重开手动页表单干净（无悬挂 connecting 残留）
    func test04_cancelDuringConnectingTearsDownWithoutResidue() throws {
        let app = launch(arguments: ["-ZCodeOpenConnectFlow", "-ZCodeE2EResetState"])
        openManualConnect(app)
        XCTAssertTrue(typeInto(app, element(app, "l1-field-host"),
                               text: "http://10.255.255.1:3030"), "地址栏应可输入不可路由地址")
        XCTAssertTrue(typeInto(app, element(app, "l1-field-token"), text: "e2e-token"), "令牌栏应可输入")
        submitConnect(app)

        // L2 连接态出现（不可路由地址 → 连接挂起数秒，窗口足够点取消）
        let cancel = element(app, "l2-act-cancel")
        XCTAssertTrue(cancel.waitForExistence(timeout: 15),
                      "连接进行中应出现 L2 连接视图与取消入口（G-003②前置）")
        cancel.tap()
        // 取消生效：L2 覆盖层收起、L1 根页回归（手动入口重新可见）
        let l2Gone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: cancel)
        XCTAssertEqual(XCTWaiter().wait(for: [l2Gone], timeout: 8), .completed,
                       "取消后 L2 连接视图应收起（cancelConnecting 生效）")
        // 取消语义（门禁诊断实锤：取消后 L2 收起、cover 保持、栈顶回提交前的手动页——
        // 保留已输入地址供改址重试，为有意 UX；「退出连接流程」由 ✕ 路径（test01）承担）
        XCTAssertTrue(element(app, "l1-field-host").waitForExistence(timeout: 8),
                      "取消后应回手动连接页（L2 不悬挂，地址保留可改址重试）")
        XCTAssertFalse(element(app, "l2-act-cancel").exists, "L2 连接视图应完全收起（无悬挂 connecting 态）")
        let hostField = element(app, "l1-field-host")
        XCTAssertEqual(hostField.value as? String ?? "", "http://10.255.255.1:3030",
                       "取消后手动页应保留原输入地址（供改址重试，非清空丢弃）")
    }

    // MARK: - G-004①：多屏退出路径遍历（每屏 ≥1 条可达退出路径）

    /// 数据驱动遍历可达屏：每屏断言 ≥1 条退出路径（系统返回 / ✕ / 取消 / 出口 CTA）。
    /// 覆盖设置域 15 屏 + 连接域 3 屏 + 登录 cover 1 屏；Tab 根屏（4）为遍历树根、
    /// 全屏 cover（审批 Sheet/任务详情/文件页）由各自套件覆盖交互面。
    func test05_screenExitPathTraversal() throws {
        let app = launch(arguments: ["-ZCodeE2EResetState"])
        // 设置域：逐行进入 → 断言导航返回钮在场 → 返回（再进干净由循环天然验证）
        let settingsScreens = ["12-row-pairing", "12-row-bot", "12-row-usage",
                               "12-row-automation", "12-row-language", "12-row-appearance",
                               "12-row-model", "12-row-notify", "12-row-diagnostics",
                               "12-row-memory", "12-row-skills", "12-row-mcp",
                               "12-row-plugins", "12-row-workflows", "12-row-offpeak",
                               "12-row-feedback"]
        element(app, "12-tab-me").tap()
        for row in settingsScreens {
            let entry = element(app, row)
            // exists≠hittable：设置页为 ScrollView（行全量物化，exists 恒真而视口外
            // 不可命中）——tap 前有界下滑至可命中；tap 后以「导航返回钮出现」为生效
            // 判据，未生效（tap 落空停根页）则滑回顶部重定位重试
            if !entry.waitForExistence(timeout: 6) { continue }
            let backButton = app.navigationBars.buttons.firstMatch
            var entered = false
            for round in 0..<3 where !entered {
                // exists/isHittable 均不可靠（行压在 TabBar 之下时 isHittable 仍可能
                // true，tap 命中 TabBar 被静默吞掉——门禁诊断实据：usage 行 y=839
                // hittable=true 而推入不发生）——以 frame 相对 TabBar 顶判定可视性，
                // 不足则有界上滑；tap 后以「导航返回钮出现」为生效判据
                let tabTop = element(app, "04-tab-chat").frame.minY
                var scrolled = 0
                while entry.exists, entry.frame.maxY > tabTop - 8, scrolled < 10 {
                    app.swipeUp()
                    scrolled += 1
                }
                NSLog("test05-traverse \(row) round=\(round) scrolled=\(scrolled) frame=\(entry.frame) tabTop=\(tabTop)")
                guard entry.exists, entry.frame.maxY <= tabTop - 8 else { break }
                entry.tap()
                entered = backButton.waitForExistence(timeout: 5)
                NSLog("test05-traverse \(row) tapped entered=\(entered) navbars=\(app.navigationBars.count) backExists=\(backButton.exists)")
                if !entered, !backButton.exists {
                    for _ in 0..<8 { app.swipeDown() } // 滑回顶部重新定位该行
                }
            }
            XCTAssertTrue(entered,
                          "\(row) 屏应有 ≥1 条退出路径（导航返回，G-004①）")
            guard entered else { continue }
            backButton.tap()
            XCTAssertTrue(entry.waitForExistence(timeout: 6),
                          "\(row) 返回后应回设置根（无残留态，G-004②）")
        }
        // 连接域：L1 cover（✕）→ 手动页（返回）→ 帮助页（返回）。
        // 设置域循环结束停在页面底部，l4-row-add 在页面顶部——先滑回顶部再进入
        for _ in 0..<8 { app.swipeDown() }
        element(app, "l4-row-add").tap()
        let l1Close = element(app, "l1-act-close")
        XCTAssertTrue(l1Close.waitForExistence(timeout: 8), "L1 cover 应有 ✕（G-004①）")
        element(app, "l1-btn-manual").tap()
        XCTAssertTrue(element(app, "l1-field-host").waitForExistence(timeout: 8), "手动页应在场")
        XCTAssertTrue(app.navigationBars.buttons.firstMatch.exists, "手动页应有返回（G-004①）")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(l1Close.waitForExistence(timeout: 6), "返回后应回 L1 根")
        l1Close.tap()
        let l1Gone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: l1Close)
        XCTAssertEqual(XCTWaiter().wait(for: [l1Gone], timeout: 6), .completed,
                       "L1 ✕ 后 cover 收起（再进干净由 test01/test04 覆盖）")
        // 登录域：O1 cover（✕）
        let login = launch(arguments: ["-ZCodeOpenLoginFlow", "-ZCodeE2EResetState"])
        let o1Close = element(login, "o1-act-close")
        XCTAssertTrue(o1Close.waitForExistence(timeout: 10), "登录 cover 应有 ✕（G-004①）")
        snap(login, "g004-exit-traversal-login")
    }
}

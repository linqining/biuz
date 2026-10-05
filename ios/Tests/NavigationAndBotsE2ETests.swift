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
        // 本地化后固定测试语言（中文断言稳定，G-063）
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)"] + arguments
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
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
    func test03_loginSuccessGuideCardProvidesExit() throws {
        let app = launch(arguments: ["-ZCodeOpenLoginFlow", "-ZCodeE2EResetState"])
        let oauthButton = element(app, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应进入登录主页")
        // 完整 OAuth 走 stub 环境（E2ELoginStubServer），此处仅断言主页可退出：
        // o1-act-close 存在即 G-011 登录 cover 出口完备的回归面
        XCTAssertTrue(element(app, "o1-act-close").exists, "登录主页应有 ✕ 关闭钮")
        _ = app // oauthButton 未点击（stub 凭据交换由门禁登录套件覆盖）
    }
}

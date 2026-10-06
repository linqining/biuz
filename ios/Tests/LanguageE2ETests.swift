import XCTest

/// 语言双态断言（P1 语言适配）：zh-Hans 固定态中文文案稳定（既有套件依赖）；
/// en 固定态关键屏文案为英文、无中文残留（抽样口径）。由统一门禁脚本执行，本会话不运行。
final class LanguageE2ETests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(language: String, arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        // -ZCodeDemoData：E2E 演示开关（对齐修复后 Mock 仅测试用例允许装配）
        app.launchArguments = ["-ZCodeDemoData", "-AppleLanguages", "(\(language))", "-AppleLocale", language == "en" ? "en_US" : "zh_CN"] + arguments
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// zh-Hans 态：会话列表/设置关键文案为中文（与既有套件断言语义一致）
    func test01_zhHansFixedShowsChinese() throws {
        let app = launch(language: "zh-Hans")
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10), "zh 态应进入会话列表")
        element(app, "12-tab-me").tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 6), "设置页用户卡应出现")
        // H10：用户卡兜底名「Zai 开发者」演示假名已移除——兜底改「未登录」（经 xcstrings 查表，
        // 仍可作为 zh 态本地化断言锚点）
        XCTAssertTrue(app.staticTexts["未登录"].waitForExistence(timeout: 4), "zh 态用户卡兜底名应为中文「未登录」")
    }

    /// en 态：Tab/设置/用户卡/连接页/登录页关键文案为英文，无中文残留。
    /// G-002 复审补强：收紧 OR 宽松断言为双独立断言；补连接页/登录页抽样；
    /// 权限弹窗文案的本地化产物断言在 CompletenessUnitTests（系统弹窗渲染为 iOS 行为）。
    func test02_englishFixedShowsEnglish() throws {
        let app = launch(language: "en")
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10), "en 态应进入会话列表")
        element(app, "12-tab-me").tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 6), "设置页用户卡应出现")
        // 关键断言①（收紧：两条独立断言，OR 不再放水）：兜底名英文在场 + 中文兜底名不在
        //（H10：「Zai 开发者/Zai Developer」演示假名已移除，兜底改「未登录/Signed out」）
        let fallbackName = app.staticTexts["Signed out"]
        let zhName = app.staticTexts["未登录"]
        XCTAssertTrue(fallbackName.waitForExistence(timeout: 4),
                      "en 态用户卡兜底名应为英文（Signed out）")
        XCTAssertFalse(zhName.exists,
                      "en 态不得残留中文兜底名「未登录」")
        // 关键断言②：设置行标题英文（zh 源键 en 列：设备与配对→Devices & pairing、语言→Language）
        XCTAssertTrue(app.staticTexts["Devices & pairing"].waitForExistence(timeout: 4),
                      "en 态设置页「设备与配对」行应显示英文标题")
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 4),
                      "en 态设置页「语言」行应显示英文标题")

        // G-002 复审补强：连接页 en 态抽样（L1 两入口 + 表单标签均查 en 列）
        let connectApp = launch(language: "en", arguments: ["-ZCodeOpenConnectFlow"])
        let scanButton = connectApp.buttons["Scan to connect desktop"]
        XCTAssertTrue(scanButton.waitForExistence(timeout: 10),
                      "en 态应进入 L1 连接页且扫描入口为英文（Scan to connect desktop）")
        let manualEntry = connectApp.buttons["Enter address manually"]
        XCTAssertTrue(manualEntry.waitForExistence(timeout: 4),
                      "en 态 L1 手动入口应为英文（Enter address manually）")
        manualEntry.tap()
        XCTAssertTrue(element(connectApp, "l1-field-host").waitForExistence(timeout: 8),
                      "en 态手动页应在场")
        XCTAssertTrue(connectApp.staticTexts["Server address"].waitForExistence(timeout: 6),
                      "en 态手动页「服务器地址」标签应为英文")
        XCTAssertFalse(connectApp.staticTexts["服务器地址"].exists,
                       "en 态手动页不得残留「服务器地址」中文标签")

        // G-002 复审补强：登录页 en 态抽样
        let loginApp = launch(language: "en", arguments: ["-ZCodeOpenLoginFlow", "-ZCodeE2EResetState"])
        XCTAssertTrue(loginApp.buttons["Sign in with Z.ai"].waitForExistence(timeout: 10),
                      "en 态登录页 OAuth 入口应为英文（Sign in with Z.ai）")
        XCTAssertFalse(loginApp.buttons["使用 Z.ai 账号登录"].exists,
                       "en 态登录页不得残留中文 OAuth 入口")
    }
}

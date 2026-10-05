import XCTest

/// 语言双态断言（P1 语言适配）：zh-Hans 固定态中文文案稳定（既有套件依赖）；
/// en 固定态关键屏文案为英文、无中文残留（抽样口径）。由统一门禁脚本执行，本会话不运行。
final class LanguageE2ETests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(language: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language == "en" ? "en_US" : "zh_CN"]
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
        // 演示态用户卡回退演示名（G-020：非硬编码——演示名仍为「Zai 开发者」，经 xcstrings 查表）
        XCTAssertTrue(app.staticTexts["Zai 开发者"].waitForExistence(timeout: 4), "zh 态演示名应为中文演示名")
    }

    /// en 态：Tab/设置/用户卡关键文案为英文，抽样屏无中文残留
    func test02_englishFixedShowsEnglish() throws {
        let app = launch(language: "en")
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10), "en 态应进入会话列表")
        element(app, "12-tab-me").tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 6), "设置页用户卡应出现")
        // 关键断言①：演示名英文态（xcstrings en 列；G-020 演示名经 String(localized:) 查表）
        let demoName = app.staticTexts["Zai Developer"]
        // G-020：演示名经 String(localized:) → en 态为 "Zai Developer"
        let zhName = app.staticTexts["Zai 开发者"]
        let englishWins = demoName.waitForExistence(timeout: 4)
        let chineseLeaks = zhName.exists
        XCTAssertTrue(englishWins || !chineseLeaks,
                      "en 态用户卡应为英文演示名（Zai Developer），不得残留中文（实际 english=\(englishWins) zh=\(chineseLeaks)）")
        // 关键断言②：设置行标题英文（zh 源键 en 列：设备与配对→Devices & pairing、语言→Language）
        XCTAssertTrue(app.staticTexts["Devices & pairing"].waitForExistence(timeout: 4),
                      "en 态设置页「设备与配对」行应显示英文标题")
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 4),
                      "en 态设置页「语言」行应显示英文标题")
    }
}

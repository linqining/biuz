import XCTest

/// ZCode Mobile · 云中继接入回归（XCUITest）
///
/// 覆盖 ConnectURLParser 对 `/remote/v4` 云中继配对链接在真实 UI 面的「识别 → 拦截 → 解析产物反射」：
/// - L1-K 手动连接页：粘贴 `/remote/` 配对链接应走中继连接分支（L2 → L3，而非局域网直连解析的
///   表单报错），成功提示（l1-parse-ok）携带链接 `name=` 参数提取的机器名；
/// - 拦截负例：非 `/remote/` 路径、以及路径为 `/remote/` 但带 `token=` 的链接，均不得进中继分支，
///   应按直连规则报「缺端口」（l1-field-host-err）且不出现 L2/L3；
/// - `-ZCodeRelayLink` 冷启动钩子（真机验证入口，docs/relay-handoff.md §3.4）：有效链接直接发起
///   中继连接（失败后以「桌面端连接失败」横幅收场（对齐修复后无演示回退语义），横幅仅在确曾发起连接时出现）；
///   缺 hash 的无效链接解析拒绝 → 直接演示态，无连接动作、无横幅。
///
/// 边界约定（如实声明）：**真实中继链路（zcode.z.ai 活会话）不做 XCUITest**——依赖外部桌面端
/// 配对会话的时效 sid/hash，由真实验证工程师以 `simctl launch -ZCodeRelayLink` + 截图证据验收；
/// 本文件用例全部指向 127.0.0.1 回环（解析器按逆向结论将 wss 端口固定为 443，本机即刻拒连），
/// 不触外部中继端点；既有替身（E2ELoginStubServer）不适用于中继 WS 面（wss 端点取自链接 host，
/// 无法指向本地替身端口），故解析结果以「单测风格 UI 断言」反射验证。
/// 等待一律 waitForExistence / label 谓词（容忍本机拒连的秒级失败与 auth 15s 超时的时序差），
/// 不写固定 sleep；每个用例以 `-ZCodeE2EResetState` 独立冷启动，无顺序依赖；
/// 本文件只做编译级自检，由统一门禁脚本在模拟器上执行。
final class RelayLinkE2ETests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 测试数据（形态对齐 docs/relay-handoff.md §1 真实配对链接，host 换为回环）

    /// 有效中继配对链接：https + /remote/ 路径 + sid/hash/mid/name/app_version（hash 含 URL 编码）
    private let relayLink = "https://127.0.0.1/remote/v4?sid=d_e2e_sid_0001"
        + "&hash=e2e%2BHash%2Fabc123%3D&t=1791033721496"
        + "&mid=e2e-mid-0001&name=E2E-Relay-Mac&app_version=3.14.4"
    /// 非 /remote/ 路径的 https 链接（不得识别为中继）
    private let nonRelayLink = "https://127.0.0.1/other/v4?sid=s1&hash=h1"
    /// 路径为 /remote/ 但带 token= 的链接（token= 形态归局域网直连解析，不得误入中继分支）
    private let tokenShapedRelayLink = "https://127.0.0.1/remote/v4?sid=s1&hash=h1&token=abc"

    // MARK: - 基础设施

    /// 冷启动（清空凭据态 + 服务器注册表；附加启动参数原样透传）
    @discardableResult
    private func launch(args: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)"] // 本地化后固定测试语言（中文断言稳定）
        // -ZCodeDemoData：E2E 演示开关；-ZCodeDevMode：开发者模式（手动连接入口用例依赖）
        app.launchArguments = ["-ZCodeE2EResetState", "-ZCodeDemoData", "-ZCodeDevMode"] + args
        app.launch()
        return app
    }

    private func element(_ application: XCUIApplication, _ identifier: String) -> XCUIElement {
        application.descendants(matching: .any)[identifier].firstMatch
    }

    /// 等待元素 accessibility label 包含指定文本（解析成功提示等动态文案）
    @discardableResult
    private func waitLabel(_ item: XCUIElement, contains text: String,
                           timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", text), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 点击输入框并等键盘弹出（转场动画期 tap 可能落空，未弹出则有界重试）
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

    /// 逐键退格清空地址栏（URL 键盘无全选菜单可依赖；80 位覆盖最长测试链接）。
    /// 上一段提交/报错后键盘可能已收起（typeText 需焦点在场）：先 tap 重新聚焦
    /// 并等键盘弹出（有界重试），再退格清空。
    private func clearHostField(_ application: XCUIApplication, _ field: XCUIElement) {
        for _ in 0..<3 {
            field.tap()
            if application.keyboards.firstMatch.waitForExistence(timeout: 3) {
                break
            }
        }
        field.typeText(String(repeating: "\u{8}", count: 80))
    }

    /// 提交手动连接表单：优先键盘工具栏「连接」（键盘弹出时表单按钮可能被遮挡），回退表单提交按钮
    private func submitManualConnect(_ application: XCUIApplication) {
        let keyboardConnect = element(application, "l1-keyboard-connect")
        if keyboardConnect.waitForExistence(timeout: 2) {
            keyboardConnect.tap()
        } else {
            element(application, "l1-submit-connect").tap()
        }
    }

    /// 冷启动直开 L1 连接页 → 进入手动输入页（-ZCodeOpenConnectFlow 钩子，AppSession.bootstrap）
    private func openManualConnect(_ application: XCUIApplication) {
        let manualButton = element(application, "l1-btn-manual")
        XCTAssertTrue(manualButton.waitForExistence(timeout: 10),
                      "-ZCodeOpenConnectFlow 应直开 L1 连接页（手动输入入口可见）")
        manualButton.tap()
        let hostField = element(application, "l1-field-host")
        XCTAssertTrue(hostField.waitForExistence(timeout: 8), "应进入手动连接页")
    }

    // MARK: - 用例 1：/remote/ 配对链接识别 + 解析产物 UI 反射（核心回归）

    /// 手动连接页粘贴 /remote/v4 配对链接：
    /// ① 走中继连接分支（L2 → L3 失败态；回环 443 本机拒连，至多 auth 15s 超时），而非
    ///    直连解析的表单报错（l1-field-host-err 不得出现）——证明「识别 + 拦截」生效；
    /// ② 解析成功提示携带 `name=` 参数提取的机器名「已识别云中继配对链接 · E2E-Relay-Mac」
    ///    ——证明解析产物（RelayLinkConfig.machineName）正确。
    /// 说明：L3 覆盖层挂于 ConnectFlowView.overlay（ConnectFlowView.swift:36-58），其下
    /// ManualConnectView 仍在可访问性树内（同套件先例：未选中 Tab 页仍可被查询），故
    /// l1-parse-ok 以存在性 + label 断言。
    func test01_manualFormRecognizesRemoteRelayLinkAndReflectsParseResult() throws {
        let app = launch(args: ["-ZCodeOpenConnectFlow"])
        openManualConnect(app)

        let hostField = element(app, "l1-field-host")
        XCTAssertTrue(typeInto(app, hostField, text: relayLink), "服务器地址栏应可输入中继配对链接")
        submitManualConnect(app)

        // ① 中继分支被拦截执行：进入 L3 失败态（回环拒连），而非表单地址报错
        XCTAssertTrue(element(app, "l3-btn-retry").waitForExistence(timeout: 30),
                      "/remote/ 配对链接应触发中继连接并进入 L3 失败态"
                      + "（127.0.0.1:443 回环拒连，至多 15s auth 超时）")
        XCTAssertFalse(element(app, "l1-field-host-err").exists,
                       "中继链接不得走局域网直连解析的地址报错分支（l1-field-host-err 不应出现）")

        // ② 解析产物反射：成功提示携带链接 name= 参数的机器名。
        // l1-parse-ok 的 identifier 挂在无背景 HStack 容器上（不进可访问性树、label 不聚合），
        // 以提示文本的全局 Text 观测（同 13-banner/12-usercard 口径）
        XCTAssertTrue(app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", "已识别云中继配对链接 · E2E-Relay-Mac"))
            .firstMatch.waitForExistence(timeout: 8),
                      "解析成功提示应携带配对链接 name= 参数提取的机器名")
    }

    // MARK: - 用例 2：拦截负例（非 /remote/ 路径、token= 形态不得误入中继分支）

    /// ① 非 /remote/ 路径（/other/v4）：不识别为中继 → 按直连规则报「缺少端口」（l1-field-host-err），
    ///    且不进入 L2/L3（若误入中继分支，L2 连接视图会立即出现并持续至失败）；
    /// ② 路径为 /remote/ 但带 token= 的链接：ConnectURLParser 归直连解析（token= 链接不进中继），
    ///    同样报缺端口、无连接动作。
    func test02_manualFormRejectsNonRemoteAndTokenShapedLinks() throws {
        let app = launch(args: ["-ZCodeOpenConnectFlow"])
        openManualConnect(app)

        let hostField = element(app, "l1-field-host")

        // ① 非 /remote/ 路径
        XCTAssertTrue(typeInto(app, hostField, text: nonRelayLink), "地址栏应可输入非中继链接")
        submitManualConnect(app)
        XCTAssertTrue(element(app, "l1-field-host-err").waitForExistence(timeout: 6),
                      "非 /remote/ 路径不应识别为中继，应按直连规则报地址错误（缺端口）")
        XCTAssertFalse(element(app, "l3-btn-retry").waitForExistence(timeout: 3),
                       "非 /remote/ 路径不得触发中继连接（不应出现 L3 失败态）")
        clearHostField(app, hostField)

        // ② /remote/ 路径但带 token=（token= 形态归直连）
        XCTAssertTrue(typeInto(app, hostField, text: tokenShapedRelayLink), "地址栏应可输入 token= 链接")
        submitManualConnect(app)
        XCTAssertTrue(element(app, "l1-field-host-err").waitForExistence(timeout: 6),
                      "带 token= 的链接即使路径为 /remote/ 也不应识别为中继")
        XCTAssertFalse(element(app, "l3-btn-retry").waitForExistence(timeout: 3),
                       "token= 链接不得触发中继连接（不应出现 L3 失败态）")
    }

    // MARK: - 用例 3：-ZCodeRelayLink 冷启动钩子（有效链接 → 发起中继连接 → 失败回退演示）

    /// 真机验证入口的回归（docs/relay-handoff.md §3.4：simctl launch -ZCodeRelayLink + 截图）：
    /// 钩子解析有效链接后直接发起中继连接（无 UI 驱动路径，AppSession.bootstrap:153-156）。
    /// 回环拒连失败后出现「桌面端连接失败」横幅（对齐修复后标题不再含「已回退演示数据」
    /// ——Mock 回退语义已消失；横幅仅在确曾发起连接时出现——若解析/拦截回归破坏，
    /// mode 恒为 demo，横幅永不出现）；-ZCodeDemoData 下失败后回退演示数据（会话行仍在）。
    func test03_relayLinkLaunchArgParsesAndAttemptsRelayConnect() throws {
        let app = launch(args: ["-ZCodeRelayLink", relayLink])

        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "钩子路径主界面应可用（演示列表渲染）")
        XCTAssertTrue(element(app, "13-banner-connection").waitForExistence(timeout: 30),
                      "-ZCodeRelayLink 应解析链接并发起中继连接，失败后出现连接失败横幅"
                      + "（至多 15s auth 超时）")
        XCTAssertTrue(app.staticTexts["桌面端连接失败"].waitForExistence(timeout: 6),
                      "横幅应表明桌面端连接失败")
        XCTAssertTrue(element(app, "04-row-c1").exists, "中继连接失败后应回退演示数据（会话行仍在）")
    }

    // MARK: - 用例 4：-ZCodeRelayLink 冷启动钩子（无效链接 → 解析拒绝 → 直接演示态）

    /// 缺 hash 的链接解析拒绝（ConnectURLParser.parseRelayLink 返回 nil）：connectRelayLink
    /// 直接回演示态（AppSession.swift:174-177），不发起任何连接——无失败横幅、无 L2/L3
    /// （拦截形态回归；演示态为确定性行为，负向等待窗口有界）。
    func test04_relayLinkLaunchArgInvalidLinkStaysDemo() throws {
        let app = launch(args: ["-ZCodeRelayLink", "https://127.0.0.1/remote/v4?sid=only-sid-no-hash"])

        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "解析拒绝的中继链接应直接进入演示模式（会话列表可见）")
        XCTAssertFalse(element(app, "13-banner-connection").waitForExistence(timeout: 5),
                       "解析拒绝的链接不应发起连接（不应出现连接失败横幅）")
        XCTAssertFalse(app.staticTexts["连接失败"].exists,
                       "解析拒绝的链接不应出现连接失败覆盖层")
    }
}

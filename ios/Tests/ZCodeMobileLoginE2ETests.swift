import XCTest

/// ZCode Mobile · 登录与 API 接入 e2e（XCUITest）
///
/// 覆盖两条登录路径与令牌鉴权链路：
/// - OAuth（Z.ai 账号）：应用内授权 Sheet 加载替身授权页 → 回调拦截 → POST /api/v1/oauth/token 交换 →
///   登录成功页 / 设置账户区；异常分支含 state 篡改拒绝、code≠0、HTTP 401、用户取消、退出登录；
/// - 桌面配对：手动输入服务器地址与令牌 → GET /api/server-info → WS /ws?token= → v4 握手 →
///   会话数据（server-info 名称 / createSession 回执 / 历史行 / sessions-index 快照）全部来自替身；
///   连接态只读边界：发送/应答入口禁用（只读提示替代）、新建会话为不带 firstInput 的空会话、
///   替身侧 execution 命令计数恒为 0；
///   令牌/地址错误进入 L3 失败态并可重试；未配置冷启动直接演示模式；设置页保存配置后重连；
///   交互闭环（流程 12）：每个页面/交互按「操作 → 替身收到命令/事件（计数器）→ UI 反映回执」
///   断言——列表加载（订阅+快照计数+行回执）、搜索过滤/置顶/归档（本地操作零 execution 命令
///   + UI 分组/过滤回执）、diff 只读展示（git 读面计数 + 文件卡回执）、任务列表（zcode-task
///   读面计数 + 任务卡回执）。
///   本期接入面闭环（流程 13-15）：活性推送/丢帧自愈/git.refresh 时序/置顶双写/向上分页游标、
///   chips/文件树（readdir+watch+搜索+截断）/diff 分段（staged+本次会话）/任务详情元数据/
///   用量卡与桌面端登录投影的替身命令级断言、已读清零（角标投影 → setTaskUnread 计数 → 徽章消失）、
///   订阅失败 readSession 兜底对账（替身拒绝订阅 → 只读恢复，execution 恒 0）。
///
/// 基础设施：`E2ELoginStubServer`（Network.framework NWListener，127.0.0.1 随机端口）在
/// setUp 启动、tearDown 关闭；base URL / 授权页 URL / stub 凭据经启动参数注入被测 App
/// （`-ZCodeOAuthZaiOrigin` / `-ZCodeOAuthTokenOrigin` / 表单令牌），每个用例以
/// `-ZCodeE2EResetState` 清空本机 Keychain 凭据态，保证可重复、无顺序依赖。
/// 等待一律用 waitForExistence / XCTNSPredicateExpectation / 有界轮询，不写固定 sleep；
/// 本文件只做编译级自检，由统一门禁脚本在模拟器上执行。
final class ZCodeMobileLoginE2ETests: XCTestCase {

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

    /// 等待元素 accessibility label 包含指定文本（行 value 等动态文本）
    @discardableResult
    private func waitLabel(_ item: XCUIElement, contains text: String,
                           timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", text), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 等待元素从可访问性树消失（覆盖层切换 / 动画）
    @discardableResult
    private func waitDisappear(_ item: XCUIElement, timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 等待出现 label 包含指定片段的静态文本（替身回执等动态文案）
    @discardableResult
    private func waitStaticText(containing fragment: String, in application: XCUIApplication,
                                timeout: TimeInterval, _ message: String) -> Bool {
        let matched = application.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
        XCTAssertTrue(matched.waitForExistence(timeout: timeout), message)
        return matched.exists
    }

    /// 有界轮询替身侧状态（请求记录 / 事件计数），50ms 步进，超时判失败。
    /// 条件闭包非逃逸（同步轮询），可直接引用用例属性。
    @discardableResult
    private func waitUntil(timeout: TimeInterval = 10, _ message: String,
                           _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(condition(), message)
        return condition()
    }

    private enum OpenFlow { case login }

    /// 关键界面截图附件（keepAlways；验收检查点经 xcresulttool 导出后逐张复核）
    private func snap(_ application: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: application.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// 冷启动（清空凭据态 + OAuth 端点指向替身；可选直开登录流程）。
    /// -ZCodeDemoData（E2E 演示开关，默认携带）：登录/连接流程用例收尾的「回主界面」
    /// 断言保持旧演示四 Tab 口径；test09（未配对根页）以 demoData:false 关闭
    @discardableResult
    private func launchFresh(openFlow: OpenFlow? = nil, demoData: Bool = true) -> XCUIApplication {
        var arguments = [
            "-ZCodeE2EResetState",
            // -ZCodeDevMode：开发者模式（l1-btn-manual 手动配对 / l3-btn-update-token 用例依赖）
            "-ZCodeDevMode",
        ] + (demoData ? ["-ZCodeDemoData"] : []) + [
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthClientID", "stub-client-e2e",
            // 回调改为替身的 web 回调路径（App 按注册 redirect_uri 拦截，镜像真实 zcode.z.ai 行为）
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            // 本地化后固定测试语言（中文文案断言稳定）
            "-AppleLanguages", "(zh-Hans)",
        ]
        if openFlow == .login {
            arguments.append("-ZCodeOpenLoginFlow")
        }
        app.launchArguments = arguments
        app.launch()
        return app
    }

    /// 会话内二次启动（保留凭据态：已保存服务器触发冷启动自动重连）
    @discardableResult
    private func relaunch(_ application: XCUIApplication) -> XCUIApplication {
        application.terminate()
        application.launchArguments = [
            "-ZCodeDevMode",
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            "-AppleLanguages", "(zh-Hans)",
        ]
        application.launch()
        return application
    }

    @discardableResult
    private func typeInto(_ field: XCUIElement, text: String) -> Bool {
        guard field.waitForExistence(timeout: 8) else { return false }
        // 键盘未弹出时 typeText 合成事件落空（软键盘偶发延迟/焦点未建立）：
        // tap 带键盘弹出等待，有界重试后再输入
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

    /// 清空既有内容再输入（SecureField 无全选菜单可依赖，逐键退格清空）。
    /// 先等字段可点击（页面推入动画结束后）再聚焦，否则 tap 得到 hit point {-1,-1} 落空，
    /// 退格/输入会与 tokenFocusOnly 的延迟聚焦产生焦点竞争。
    private func clearAndType(_ field: XCUIElement, text: String) {
        waitUntil(timeout: 8, "令牌栏应进入可点击状态") { field.isHittable }
        field.tap()
        field.typeText(String(repeating: "\u{8}", count: 48))
        field.typeText(text)
    }

    /// 设置页 → 添加服务器 → 手动输入页
    private func openManualConnect(_ application: XCUIApplication) {
        let meTab = element(application, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")
        meTab.tap()
        let addRow = element(application, "l4-row-add")
        XCTAssertTrue(addRow.waitForExistence(timeout: 8), "设置页应有「添加服务器」行")
        addRow.tap()
        let manualButton = element(application, "l1-btn-manual")
        XCTAssertTrue(manualButton.waitForExistence(timeout: 8), "连接页应有「手动输入地址连接」入口")
        manualButton.tap()
        let hostField = element(application, "l1-field-host")
        XCTAssertTrue(hostField.waitForExistence(timeout: 8), "应进入手动连接页")
    }

    private func fillManualConnect(_ application: XCUIApplication, host: String, token: String) {
        XCTAssertTrue(typeInto(element(application, "l1-field-host"), text: host), "服务器地址栏应可输入")
        XCTAssertTrue(typeInto(element(application, "l1-field-token"), text: token), "令牌栏应可输入")
    }

    /// 提交手动连接表单：令牌栏聚焦（键盘弹出）时表单按钮可能被键盘遮挡（hit point -1），
    /// 优先用键盘工具栏的「连接」（l1-keyboard-connect），不可用时回退表单提交按钮
    private func submitManualConnect(_ application: XCUIApplication) {
        let keyboardConnect = element(application, "l1-keyboard-connect")
        if keyboardConnect.waitForExistence(timeout: 2) {
            keyboardConnect.tap()
        } else {
            element(application, "l1-submit-connect").tap()
        }
    }

    /// 点击屏幕顶部导航标题处的空白收起键盘：
    /// 键盘若保持弹出会遮挡连接失败页的恢复动作区，导致后续 tap 命中落空
    private func dismissKeyboard(_ application: XCUIApplication) {
        application.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.06)).tap()
    }

    /// OAuth 全流程直达：O1 主页 → 授权 Sheet（替身 302 回调）→ 交换 → 成功页 → 回主界面
    private func performOAuthLogin(_ application: XCUIApplication) {
        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应出现 O1 登录主页")
        oauthButton.tap()
        let continueButton = element(application, "o3-btn-continue")
        XCTAssertTrue(continueButton.waitForExistence(timeout: 20), "替身授权回调后应完成交换进入登录成功页")
        continueButton.tap()
        XCTAssertTrue(element(application, "04-tab-chat").waitForExistence(timeout: 10), "「开始使用」后应回到主界面")
    }

    // MARK: - 流程 1：OAuth 全流程端到端（授权页 → 回调拦截 → 令牌交换 → 已登录 UI）

    func test01_oauthFullFlowAuthorizeExchangeAndShowsLoggedInUI() throws {
        let application = launchFresh(openFlow: .login)

        // O1 → O2-A → O2-B → O3：替身授权页立即 302，回调拦截与交换在回环上瞬间完成，
        // 「Sheet 中间态」存在时长不定，直接以成功页作为全流程完成信号
        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应出现 O1 登录主页")
        oauthButton.tap()
        let continueButton = element(application, "o3-btn-continue")
        XCTAssertTrue(continueButton.waitForExistence(timeout: 20),
                      "替身授权页 302 回调被拦截后应完成交换并进入登录成功页")
        XCTAssertTrue(element(application, "o3-card-user").waitForExistence(timeout: 6),
                      "成功页应展示用户卡（displayName / 头像占位）")
        XCTAssertTrue(element(application, "o3-card-user").label.contains("替身用户"),
                      "用户卡应展示替身 displayName「替身用户」")

        // 替身侧证据 ①：授权页请求带齐 OAuth 参数（client_id / redirect_uri / response_type / state）
        // redirect_uri 为注册的 web 回调（测试经启动参数指向替身地址）；首次 WKWebView 冷启动较慢给 20s
        XCTAssertTrue(waitUntil(timeout: 20, "替身应收到授权页请求") {
            guard let request = stub.lastRequest(method: "GET", path: "/api/oauth/authorize") else { return false }
            return request.query["response_type"] == "code"
                && request.query["redirect_uri"] == "http://127.0.0.1:\(stub.port)/cn/share/callback"
                && request.query["client_id"] == "stub-client-e2e"
                && request.query["state"]?.isEmpty == false
        })
        // 替身侧证据 ②：POST /api/v1/oauth/token，code 与 state 与授权请求一一对应
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到令牌交换请求") {
            stub.requestCount(method: "POST", path: "/api/v1/oauth/token") >= 1
        })
        let authorizeRequest = stub.lastRequest(method: "GET", path: "/api/oauth/authorize")
        let tokenRequest = stub.lastRequest(method: "POST", path: "/api/v1/oauth/token")
        let tokenBody = (tokenRequest?.body.data(using: .utf8))
            .flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
        XCTAssertEqual(tokenBody?["provider"] as? String, "zai", "交换应声明 provider=zai")
        XCTAssertEqual(tokenBody?["code"] as? String, stub.oauthCode, "交换应携带替身签发的授权码")
        XCTAssertEqual(tokenBody?["state"] as? String, authorizeRequest?.query["state"],
                       "交换 state 应与发起授权的 state 一致")

        // 「开始使用」回主界面，设置账户区展示已登录
        continueButton.tap()
        XCTAssertTrue(element(application, "04-tab-chat").waitForExistence(timeout: 10), "应回到主界面")
        element(application, "12-tab-me").tap()
        let accountRow = element(application, "l4-row-account")
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8), "设置页应有账户行")
        waitLabel(accountRow, contains: "已登录", timeout: 8, "账户行应显示已登录")
        accountRow.tap()
        XCTAssertTrue(element(application, "l4-b-act-logout").waitForExistence(timeout: 8),
                      "账户配置页应展示已登录卡（含退出登录动作）")
        waitStaticText(containing: "替身用户", in: application, timeout: 6,
                       "账户区应展示 OAuth displayName")
    }

    // MARK: - 流程 2：state 不匹配拒绝，重试（重置一次性 state）后恢复

    func test02_oauthStateMismatchRejectedThenRetryRecovers() throws {
        stub.tamperState = true
        let application = launchFresh(openFlow: .login)

        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应出现 O1 登录主页")
        oauthButton.tap()

        let retryButton = element(application, "o3-btn-retry")
        XCTAssertTrue(retryButton.waitForExistence(timeout: 20), "回调 state 被篡改应进入失败页")
        waitLabel(element(application, "o3-err-code"), contains: "state", timeout: 6,
                  "错误码应标明 state 校验失败")
        // 替身确实回发了篡改后的 state
        XCTAssertTrue(waitUntil(timeout: 10, "替身应记录一次授权页请求") {
            stub.hasRequest(method: "GET", path: "/api/oauth/authorize")
        })

        // 关闭篡改后原位重试：state 重置且一致 → 登录成功
        stub.tamperState = false
        retryButton.tap()
        XCTAssertTrue(element(application, "o3-btn-continue").waitForExistence(timeout: 20),
                      "重试（state 一致）应完成登录")
        XCTAssertEqual(stub.requestCount(method: "GET", path: "/api/oauth/authorize"), 2,
                       "重试应重新发起一次授权")
    }

    // MARK: - 流程 3：令牌交换失效（code≠0 / HTTP 401）

    func test03_oauthTokenExchangeBusinessErrorAndHTTP401() throws {
        stub.exchangeMode = .businessError(code: 4013, message: "stub · 授权码无效")
        let application = launchFresh(openFlow: .login)

        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应出现 O1 登录主页")
        oauthButton.tap()

        let retryButton = element(application, "o3-btn-retry")
        XCTAssertTrue(retryButton.waitForExistence(timeout: 20), "code≠0 应进入失败页")
        waitLabel(element(application, "o3-err-code"), contains: "stub · 授权码无效", timeout: 6,
                  "错误码应回显业务错误信息")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到第一次交换请求") {
            stub.requestCount(method: "POST", path: "/api/v1/oauth/token") == 1
        })

        // 切换 HTTP 401 分支后原位重试
        stub.exchangeMode = .http401
        retryButton.tap()
        waitLabel(element(application, "o3-err-code"), contains: "HTTP 401", timeout: 20,
                  "第二次交换应按 HTTP 401 失败")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到第二次交换请求") {
            stub.requestCount(method: "POST", path: "/api/v1/oauth/token") == 2
        })
        // 失效处理：账户层不应落任何凭据（设置账户行保持未登录）
        let skipButton = element(application, "o3-btn-skip")
        XCTAssertTrue(skipButton.waitForExistence(timeout: 4), "失败页应有跳过动作")
        skipButton.tap()
        XCTAssertTrue(element(application, "04-tab-chat").waitForExistence(timeout: 10))
        element(application, "12-tab-me").tap()
        waitLabel(element(application, "l4-row-account"), contains: "未登录", timeout: 8,
                  "交换失败后应保持未登录态")
    }

    // MARK: - 流程 4：用户关闭授权 Sheet 取消 → 回到未登录态

    func test04_oauthUserCancelReturnsToLoggedOutState() throws {
        // 授权页驻留（不立即 302），保证取消动作稳定落在 Sheet 上
        stub.holdAuthorizePage = true
        let application = launchFresh(openFlow: .login)

        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 10), "应出现 O1 登录主页")
        oauthButton.tap()

        let closeButton = element(application, "o2-act-close")
        XCTAssertTrue(closeButton.waitForExistence(timeout: 10),
                      "授权 Sheet 应出现（替身授权页驻留模式）")
        XCTAssertTrue(waitUntil(timeout: 10, "替身授权页应被 Sheet 加载") {
            stub.hasRequest(method: "GET", path: "/api/oauth/authorize")
        })
        closeButton.tap()

        let skipButton = element(application, "o3-btn-skip")
        XCTAssertTrue(skipButton.waitForExistence(timeout: 10), "关闭授权 Sheet 应进入取消/失败页")
        waitLabel(element(application, "o3-err-code"), contains: "未产生 code", timeout: 6,
                  "用户取消应标记为「未产生 code」的中性结果")
        skipButton.tap()

        XCTAssertTrue(element(application, "04-tab-chat").waitForExistence(timeout: 10), "跳过后应回到主界面")
        element(application, "12-tab-me").tap()
        waitLabel(element(application, "l4-row-account"), contains: "未登录", timeout: 8,
                  "取消后应保持未登录态")
    }

    // MARK: - 流程 5：退出登录（仅清账户层，回到未登录态）

    func test05_logoutClearsAccountLayerAndReturnsToLoggedOut() throws {
        let application = launchFresh(openFlow: .login)
        performOAuthLogin(application)

        element(application, "12-tab-me").tap()
        let accountRow = element(application, "l4-row-account")
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8), "设置页应有账户行")
        waitLabel(accountRow, contains: "已登录", timeout: 8, "登录后账户行应为已登录")
        accountRow.tap()

        let logoutButton = element(application, "l4-b-act-logout")
        XCTAssertTrue(logoutButton.waitForExistence(timeout: 8), "已登录卡应有退出登录动作")
        logoutButton.tap()
        let confirmButton = element(application, "l4-b-sheet-logout-act-confirm")
        XCTAssertTrue(confirmButton.waitForExistence(timeout: 8), "退出登录应有二次确认 Sheet")
        confirmButton.tap()

        XCTAssertTrue(element(application, "l4-b-card-login").waitForExistence(timeout: 8),
                      "退出后账户区应回到未登录卡")
        let backButton = application.navigationBars.buttons.firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 4), "账户配置页应有返回按钮")
        backButton.tap()
        waitLabel(element(application, "l4-row-account"), contains: "未登录", timeout: 8,
                  "退出登录后设置账户行应显示未登录")
    }

    // MARK: - 流程 6：配对登录成功（地址 + 令牌 → 会话数据来自替身）+ 边界纠偏新口径

    /// v3 纠偏口径：连接态新建会话带首条指令（createSession+firstInput 桌面开跑）、
    /// 输入区可输入可发送（sendText 真实到达替身）；替身侧直写/配置写类命令数恒为 0，
    /// 列表/历史/快照仍全部来自替身（只读能力保留）。
    func test06_pairingSuccessConnectsAndLoadsStubData() throws {
        let application = launchFresh()

        // 手动输入替身地址与令牌发起配对
        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: stub.pairingToken)
        submitManualConnect(application)

        // 连接成功：替身通过鉴权并完成 WS 升级，连接流程自动收起回到已连接主界面
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受正确令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(waitDisappear(element(application, "l1-submit-connect"), timeout: 15,
                                    "连接成功后应自动收起连接流程（进入已连接主界面）"))
        XCTAssertFalse(element(application, "13-banner-connection").exists,
                       "连接成功不应出现失败横幅")

        // 冷启动自动重连（不带 reset）：连接状态与替身 server-info 名称可见
        relaunch(application)
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 10)
            || element(application, "04-empty").waitForExistence(timeout: 6),
                      "启动后应进入会话列表（远端数据源）")
        element(application, "12-tab-me").tap()
        let serverRow = element(application, "l4-row-server")
        XCTAssertTrue(serverRow.waitForExistence(timeout: 8), "设置页应有服务器行")
        waitLabel(serverRow, contains: "E2E Stub Desktop", timeout: 10,
                  "服务器名应来自替身 server-info（E2E Stub Desktop）")
        waitLabel(serverRow, contains: "已连接", timeout: 15, "自动重连成功后应显示已连接")
        waitLabel(element(application, "12-foot-data-source"), contains: "指令经桌面端执行", timeout: 6,
                  "数据源页脚应为执行边界口径（对齐修复后恒定文案）")

        // —— 纠偏后：新建会话 Sheet 恢复完整表单，标题/首条指令可输入 ——
        element(application, "04-tab-chat").tap()
        let newButton = element(application, "04-act-new")
        XCTAssertTrue(newButton.waitForExistence(timeout: 8), "会话页应有新建入口")
        newButton.tap()
        let titleInput = element(application, "03-input-title")
        XCTAssertTrue(titleInput.waitForExistence(timeout: 6),
                      "连接态新建 Sheet 应提供首条任务输入框（createSession+firstInput 属桌面代执行合法面）")
        tapAndWaitKeyboard(titleInput, application: application)
        titleInput.typeText("替身回归：新建即开跑")

        // 提交：createSession+firstInput 一次下发，自动推入会话详情
        element(application, "03-submit-start").tap()
        XCTAssertTrue(waitUntil(timeout: 12, "替身应收到带 firstInput 的 createSession") {
            stub.createSessionWithFirstInputCount >= 1
        }, "新建会话应携带首条指令（firstInput）真实下发")

        // —— 纠偏后：会话详情输入区可用，发送真实到达替身 ——
        let composerInput = element(application, "05-composer-input")
        XCTAssertTrue(composerInput.waitForExistence(timeout: 15),
                      "createSession 回执后应推入会话页且输入区可用（不再有 05-composer-readonly 锁形降级）")
        tapAndWaitKeyboard(composerInput, application: application)
        composerInput.typeText("替身回归：发送链路")
        let sendCountBefore = stub.sendTextCount
        element(application, "05-composer-send").tap()

        // 替身侧证据：sendText 计数 ≥1（P0 纠偏核心）；替身回执流回显到消息列表
        XCTAssertTrue(waitUntil(timeout: 10, "连接态发送应真实到达替身（sendText 计数 >0）") {
            stub.sendTextCount >= sendCountBefore + 1
        }, "sendText 信封应到达替身；实测 sendTextCount=\(stub.sendTextCount)")
        XCTAssertTrue(waitStaticText(containing: "替身回执 · 已收到「替身回归：发送链路」", in: application,
                                     timeout: 15, "替身回执应经 conversation 增量帧回显到消息流"))
        XCTAssertEqual(composerInput.value as? String ?? "", "",
                       "发送后输入框草稿应清空")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "发送链路全程替身不应收到任何直写/配置写类命令（文件直写类保持拦截）")

        // 返回列表：打开替身快照会话 sess-e2e-1，历史行应来自替身（只读能力保留）
        let backButton = application.navigationBars.buttons.firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 4), "会话页应有返回按钮")
        backButton.tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应已收到 sessions-index 订阅并下发快照事件") {
            stub.sessionsIndexEventFires >= 1
                && stub.subscribedTopics.contains { $0.hasPrefix("sessions-index/") }
        })
        let stubRow = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(stubRow.waitForExistence(timeout: 10),
                      "返回后列表应呈现替身快照会话行（sessions-index 订阅）")
        // 行点击推入详情（返回转场动画期 tap 可能落空，带效果重试）
        XCTAssertTrue(tapUntil(stubRow, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "点击替身会话行应推入会话详情页（连接态输入区可用）")
        XCTAssertTrue(waitStaticText(containing: "替身助手：历史行链路正常", in: application, timeout: 15,
                                     "会话历史应来自替身 conversationRowsRangeV4（预置历史行）"))
        // 进入详情的两条 session 类伴生写/订阅：conversation 订阅 + 已读清零（setTaskUnread）
        XCTAssertTrue(waitUntil(timeout: 10, "进入详情应发起 conversation 订阅") {
            stub.subscribedTopics.contains("conversation/sess-e2e-1")
        })
        XCTAssertTrue(waitUntil(timeout: 10, "进入详情应触发已读清零写（setTaskUnread）") {
            stub.setTaskUnreadCount >= 1
        })
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "浏览详情（订阅+历史+已读清零）不应产生任何直写/配置写类命令")
    }

    // MARK: - 流程 7：令牌错误 → 401 失败态 → 更新令牌重试成功

    func test07_pairingWrongTokenShowsFailureThenTokenRetrySucceeds() throws {
        let application = launchFresh()

        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: "wrong-token-0000")
        submitManualConnect(application)

        let row401 = element(application, "l3-row-401")
        XCTAssertTrue(row401.waitForExistence(timeout: 25), "错误令牌应进入 L3 401 失败态")
        waitLabel(element(application, "l3-err-code"), contains: "HTTP 401", timeout: 6,
                  "错误码应标明 401 令牌不匹配")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应记录一次令牌校验失败") {
            stub.pairingAuthFailures >= 1
        })

        // 失败态内「手动更新令牌」→ 换正确令牌重试（以替身接受正确令牌为重试成功的可靠信号）
        dismissKeyboard(application) // 键盘未收会遮挡恢复动作区，tap 会命中落空
        element(application, "l3-btn-update-token").tap()
        let tokenField = element(application, "l1-field-token")
        XCTAssertTrue(tokenField.waitForExistence(timeout: 8), "应进入手动令牌更新页")
        clearAndType(tokenField, text: stub.pairingToken)
        submitManualConnect(application)

        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受更新后的令牌（重试连接成功）") {
            stub.lastAcceptedPairingToken == stub.pairingToken
        })
        // 诊断：失败时区分"重连带了错误令牌"（authFailures 继续增长）与"重连未发出"（计数不变）
        XCTAssertEqual(stub.lastAcceptedPairingToken, stub.pairingToken,
                       "替身侧诊断：authFailures=\(stub.pairingAuthFailures), lastAccepted=\(stub.lastAcceptedPairingToken ?? "nil")")
        XCTAssertFalse(element(application, "l3-btn-retry").waitForExistence(timeout: 1),
                       "重试成功后 L3 失败视图不应再出现")
    }

    // MARK: - 流程 8：地址错误 → 失败态，且可原配置重试

    func test08_pairingWrongAddressShowsFailureWithRetry() throws {
        let application = launchFresh()

        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:1", token: stub.pairingToken)
        submitManualConnect(application)

        let rowTimeout = element(application, "l3-row-timeout")
        XCTAssertTrue(rowTimeout.waitForExistence(timeout: 30), "不可达地址应进入 L3 超时失败态")
        waitLabel(element(application, "l3-err-code"), contains: "TIMEOUT", timeout: 6,
                  "错误码应标明连接超时")
        let retryButton = element(application, "l3-btn-retry")
        XCTAssertTrue(retryButton.waitForExistence(timeout: 4), "失败态应提供「原配置重试」")

        dismissKeyboard(application) // 键盘未收会遮挡恢复动作区，tap 会命中落空
        retryButton.tap()
        XCTAssertTrue(rowTimeout.waitForExistence(timeout: 30), "重试后（地址仍不可达）应回到失败态")
    }

    // MARK: - 流程 9：未配置冷启动进入连接引导页（对齐修复：未配对=引导为根，设计稿 §1.3；
    // 原「直接进入演示模式」行为随 Mock 移除废止——未连接 ≠ 演示）

    func test09_coldStartWithoutConfigShowsConnectGuideRoot() throws {
        // 不带 -ZCodeDemoData：正式用户路径（未配对 = 连接引导页为根）
        let application = launchFresh(demoData: false)

        // 连接引导页为根：扫码 / 手动入口在场，四 Tab 主界面不在场
        XCTAssertTrue(element(application, "l1-btn-scan").waitForExistence(timeout: 10),
                      "未配置冷启动应以连接引导页为根（扫码入口可见）")
        XCTAssertTrue(element(application, "l1-btn-manual").waitForExistence(timeout: 4),
                      "连接引导页应有手动输入入口")
        XCTAssertFalse(element(application, "13-banner-connection").exists,
                       "未配对态不应出现连接状态横幅")
        XCTAssertFalse(element(application, "04-tab-chat").exists,
                       "未配对态不应出现四 Tab 主界面（无假数据可展示）")
    }

    // MARK: - 流程 10：设置页保存服务器配置后重连，展示连接状态

    func test10_settingsSavedConfigReconnectShowsConnectionStatus() throws {
        let application = launchFresh()

        // 第一段：以错误令牌保存服务器（提交即写入 ServerRegistry），随后冷启动自动重连失败
        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: "wrong-token-aaaa")
        submitManualConnect(application)
        XCTAssertTrue(element(application, "l3-row-401").waitForExistence(timeout: 25),
                      "错误令牌应进入失败态")

        relaunch(application)
        // 对齐修复：重连失败不再回退演示数据——四 Tab 空态 + 失败横幅（设计稿 §1.6）
        XCTAssertTrue(element(application, "04-tab-chat").waitForExistence(timeout: 10),
                      "重连失败应保持四 Tab 主界面（空数据，不回退演示）")
        XCTAssertTrue(element(application, "13-banner-connection").waitForExistence(timeout: 20),
                      "保存的配置自动重连失败应以横幅展示")

        // 第二段：设置页「访问令牌 · 更新」保存正确令牌（保存后手动重连一次）
        element(application, "12-tab-me").tap()
        let tokenRow = element(application, "l4-row-token")
        XCTAssertTrue(tokenRow.waitForExistence(timeout: 8), "设置页应有访问令牌行")
        tokenRow.tap()
        let tokenField = element(application, "l1-field-token")
        XCTAssertTrue(tokenField.waitForExistence(timeout: 8), "应进入手动连接页（令牌更新）")
        clearAndType(tokenField, text: stub.pairingToken)
        submitManualConnect(application)
        XCTAssertTrue(waitUntil(timeout: 15, "替身应接受更新后的令牌") {
            stub.lastAcceptedPairingToken == stub.pairingToken
        })

        // 第三段：冷启动自动重连成功 → 设置页展示「已连接」且无失败横幅
        relaunch(application)
        // 连接成功后会话列表数据源切至远端 Store：呈现替身快照行或远端空态
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 10)
            || element(application, "04-empty").waitForExistence(timeout: 6),
                      "应进入会话列表（远端数据源）")
        element(application, "12-tab-me").tap()
        waitLabel(element(application, "l4-row-server"), contains: "已连接", timeout: 15,
                  "重连成功后服务器行应显示已连接")
        waitLabel(element(application, "12-foot-data-source"), contains: "指令经桌面端执行", timeout: 6,
                  "数据源页脚应为执行边界口径（对齐修复后恒定文案）")
        XCTAssertFalse(element(application, "13-banner-connection").exists,
                       "已连接状态不应出现连接失败横幅")
    }

    // MARK: - 流程 11：替身鉴权面（server-info / WS 升级校验令牌与 Authorization 头）

    /// 说明：当前 App 的配对实现以 `?token=` 携带凭据（server-info 查询参数与 WS 升级查询参数），
    /// Authorization: Bearer 形态由替身同等校验（「已登录会话列表走 Bearer 认证」的替身侧契约，
    /// 供后续客户端切换请求形态时复用）。本用例不启动被测 App，直接验证替身鉴权面。
    func test11_stubEnforcesCredentialsOnServerInfoAndWebSocketUpgrade() throws {
        let base = "http://127.0.0.1:\(stub.port)"

        // query token= 形态（App 当前实际形态）
        XCTAssertEqual(httpGetStatus("\(base)/api/server-info?token=\(stub.pairingToken)"), 200,
                       "正确令牌（query 形态）应通过配对探测")
        XCTAssertEqual(httpGetStatus("\(base)/api/server-info?token=bad-token"), 401,
                       "错误令牌（query 形态）应被替身拒绝")
        // Authorization: Bearer 形态
        XCTAssertEqual(httpGetStatus("\(base)/api/server-info", bearer: stub.pairingToken), 200,
                       "正确令牌（Authorization: Bearer 头）应通过校验")
        XCTAssertEqual(httpGetStatus("\(base)/api/server-info", bearer: "bad-token"), 401,
                       "错误令牌（Authorization: Bearer 头）应被拒绝")

        // WS 升级：正确令牌 → 101 并收到 Initialize RPC 帧；错误令牌 → 拒绝
        XCTAssertTrue(webSocketUpgradeReceivesInitialize(token: stub.pairingToken),
                      "正确令牌应完成 WS 升级并收到 Initialize 帧")
        XCTAssertTrue(webSocketUpgradeRejected(token: "bad-token"),
                      "错误令牌应被 WS 升级拒绝")
        XCTAssertTrue(stub.pairingAuthFailures >= 2, "替身应记录多次鉴权失败")
    }

    // MARK: - 流程 12：连接态交互闭环（操作 → 替身命令/事件计数 → UI 反映回执）

    /// 闭环口径（每步同时给出操作、替身侧计数证据、UI 回执三段断言）：
    /// ① 列表加载：sessions-index 订阅 + 快照事件计数 ≥1 → 替身快照行上屏；
    /// ② 搜索过滤：纯本地操作 → execution 计数不变 → 行即时过滤/恢复；
    /// ③ 置顶：本地覆盖 → execution 计数不变 →「置顶」分组头出现，取消后消失；
    /// ④ 归档：本地覆盖（v4 命令面缺口，连接态列表不过滤归档行——如实断言零命令）；
    /// ⑤ diff 只读展示：git.getChanges/getDiff 读面计数 ≥1 → 文件卡/统计/diff 行回执；
    /// ⑥ 任务列表：zcode-task.listTaskList 读面计数 ≥1 → 替身任务卡回执。
    /// 全程替身收到的 execution 类命令数恒为 0（只读边界不因任何 UI 操作被击穿）。
    func test12_connectedInteractionLoopListSearchPinArchiveDiffTasks() throws {
        let application = launchFresh()

        // 连接替身 → 冷启动自动重连进主界面（与流程 6 同口径）
        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: stub.pairingToken)
        submitManualConnect(application)
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(waitDisappear(element(application, "l1-submit-connect"), timeout: 15,
                                    "连接成功后连接流程应自动收起"))
        relaunch(application)

        // 闭环①：会话列表加载 = 订阅 + 快照事件计数 → 行回执
        // 冷启动重连（probe+WS+订阅）实测可超过 15s（流程 6 中「已连接」标签曾等满 15s），
        // 分四段等待：替身第二次 WS 升级（重连完成）→ 替身第二次 sessions-index 订阅
        // → 替身第二次快照下发 → UI 行回执；断在哪段即链路断点
        let stubRow1 = element(application, "04-row-sess-e2e-1")
        let stubRow2 = element(application, "04-row-sess-e2e-2")
        XCTAssertTrue(waitUntil(timeout: 30, "冷启动自动重连应完成（替身应收到第二次 WS 升级）") {
            stub.websocketUpgrades >= 2 || stubRow1.exists
        })
        XCTAssertTrue(waitUntil(timeout: 15, "重连后 App 应重新发起 sessions-index 订阅") {
            stub.subscribedTopics.filter { $0.hasPrefix("sessions-index/") }.count >= 2
                || stubRow1.exists
        })
        XCTAssertTrue(waitUntil(timeout: 15, "重连订阅后替身应再次下发快照事件") {
            stub.sessionsIndexEventFires >= 2 || stubRow1.exists
        })
        XCTAssertTrue(stubRow1.waitForExistence(timeout: 15),
                      "自动重连后应呈现替身快照会话 sess-e2e-1")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 sessions-index 订阅并下发快照事件") {
            stub.subscribedTopics.contains { $0.hasPrefix("sessions-index/") }
                && stub.sessionsIndexEventFires >= 1
        })
        XCTAssertTrue(stubRow2.waitForExistence(timeout: 6), "替身快照会话 sess-e2e-2 应同屏可见")

        // 闭环②：搜索过滤 = 纯本地（execution 计数不变）→ 行即时过滤与恢复
        let searchInput = element(application, "04-search-input")
        XCTAssertTrue(searchInput.waitForExistence(timeout: 6), "会话页应有搜索输入框")
        tapAndWaitKeyboard(searchInput, application: application)
        XCTAssertTrue(application.keyboards.firstMatch.exists, "搜索框聚焦后键盘应弹出")
        // 搜索词用 ASCII 锚点（sess-e2e-2 标题含 "E2E"）：typeText 中文合成事件连跑时
        // 存在丢字偶发，ASCII 输入可靠；过滤语义（title/summary 匹配）不变
        searchInput.typeText("E2E")
        XCTAssertTrue(waitDisappear(stubRow1, timeout: 6, "搜索「E2E」后 sess-e2e-1 应被过滤"))
        XCTAssertTrue(stubRow2.waitForExistence(timeout: 3), "标题含「E2E」的 sess-e2e-2 应保留")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "搜索过滤不应产生任何直写/配置写类命令")
        searchInput.typeText(String(repeating: "\u{8}", count: 8))
        // 合成事件丢字偶发（套件已知，门禁第 1 轮实证）：清空不彻底（value 残留字符）
        // 时补发退格，直至输入框为空再断言行恢复
        if (searchInput.value as? String ?? "").isEmpty == false {
            searchInput.tap()
            searchInput.typeText(String(repeating: "\u{8}", count: 12))
        }
        XCTAssertTrue(waitUntil(timeout: 8, "清空搜索词后 sess-e2e-1 应恢复显示") {
            (searchInput.value as? String ?? "").isEmpty && stubRow1.exists
        })

        // 闭环③：置顶 = 本地覆盖（execution 计数不变）→「置顶」分组头回执，取消后消失
        swipeRowAndTapAction(application, row: stubRow1, actionIdentifier: "04-rowact-pin-sess-e2e-1",
                             message: "左滑 sess-e2e-1 应出现置顶动作")
        XCTAssertTrue(application.staticTexts["置顶"].waitForExistence(timeout: 6),
                      "置顶后列表应出现「置顶」分组头")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "置顶不应产生任何直写/配置写类命令")
        swipeRowAndTapAction(application, row: stubRow1, actionIdentifier: "04-rowact-pin-sess-e2e-1",
                             message: "左滑置顶行应出现取消置顶动作")
        XCTAssertTrue(waitDisappear(application.staticTexts["置顶"], timeout: 6,
                                    "取消置顶后「置顶」分组头应消失"))

        // 闭环④：归档 = zcode-task.archiveTask 双写（session 类）→ 操作到达替身（计数器），
        // UI 回执：归档行移出主列表、进入「已归档」分区（P1-6 行为）
        swipeRowAndTapAction(application, row: stubRow2, actionIdentifier: "04-rowact-archive-sess-e2e-2",
                             message: "左滑 sess-e2e-2 应出现归档动作")
        XCTAssertTrue(waitUntil(timeout: 10, "归档应触发 archiveTask 双写（session 类）") {
            stub.archiveTaskCount >= 1
        })
        XCTAssertTrue(waitDisappear(element(application, "04-row-sess-e2e-2"), timeout: 6,
                                    "归档后 sess-e2e-2 应移出主列表"))
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "归档不应产生任何直写/配置写类命令")

        // 闭环⑤：diff 只读展示 = git 读面计数 → 文件卡/统计/diff 行回执
        element(application, "08-tab-review").tap()
        let sessionStoreToggle = element(application, "08-filecard-toggle-SessionStore.swift")
        XCTAssertTrue(sessionStoreToggle.waitForExistence(timeout: 15),
                      "文件页应呈现替身 git.getChanges 返回的变更文件卡（SessionStore.swift）")
        XCTAssertTrue(element(application, "08-filecard-toggle-Composer.swift").waitForExistence(timeout: 6),
                      "替身变更清单第二文件 Composer.swift 也应上屏")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 git 读面命令（getChanges/getDiff）") {
            stub.gitReadCommandCount >= 1
        })
        XCTAssertTrue(application.staticTexts["+30"].waitForExistence(timeout: 6),
                      "统计行应聚合替身变更（+24/+6 = +30）")
        XCTAssertTrue(application.staticTexts["-10"].waitForExistence(timeout: 3),
                      "统计行应聚合替身变更（-8/-2 = -10）")
        sessionStoreToggle.tap()
        XCTAssertTrue(element(application, "08-diffrow-hunk-1").waitForExistence(timeout: 6),
                      "展开后应看到替身 patch 的 hunk 行")
        XCTAssertTrue(element(application, "08-diffrow-add-1").exists,
                      "展开后应看到替身 patch 的新增行")
        XCTAssertTrue(element(application, "08-diffrow-del-1").exists,
                      "展开后应看到替身 patch 的删除行")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "浏览 diff 全程替身不应收到任何直写/配置写类命令")

        // 闭环⑥：任务列表 = zcode-task 读面计数 → 替身任务卡回执
        element(application, "02-tab-tasks").tap()
        let stubTask = element(application, "02-taskcard-task-e2e-1")
        XCTAssertTrue(stubTask.waitForExistence(timeout: 15),
                      "任务看板应呈现替身 listTaskList 返回的任务卡（task-e2e-1）")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 listTaskList 读面命令") {
            stub.taskReadCommandCount >= 1
        })
        XCTAssertTrue(application.staticTexts["替身任务 · 登录链路回归"].exists,
                      "任务卡标题应来自替身任务快照")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "浏览任务列表全程替身不应收到任何直写/配置写类命令")

        // 闭环⑦：设置项修改 = 纯本地（UserDefaults）→ 行值即时回执，零 execution 命令
        element(application, "12-tab-me").tap()
        let appearanceRow = element(application, "12-row-appearance")
        XCTAssertTrue(appearanceRow.waitForExistence(timeout: 6), "设置页应有外观行")
        appearanceRow.tap()
        let darkOption = element(application, "12-appearance-dark")
        XCTAssertTrue(darkOption.waitForExistence(timeout: 6), "外观页应有 Zai Dark 选项")
        darkOption.tap()
        let backButton = application.navigationBars.buttons.firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 4), "外观页应有返回按钮")
        backButton.tap()
        waitLabel(element(application, "12-row-appearance"), contains: "Zai Dark",
                  timeout: 6, "外观选择应即时反映到设置行值")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "用例全程（含所有 UI 操作）替身不应收到任何直写/配置写类命令")
    }

    /// 左滑会话行露出滑动动作并点击指定动作按钮（按钮 identifier 形如 04-rowact-pin-<id>）
    private func swipeRowAndTapAction(_ application: XCUIApplication, row: XCUIElement,
                                      actionIdentifier: String, message: String) {
        XCTAssertTrue(row.waitForExistence(timeout: 6), message + "（行应先在场）")
        row.swipeLeft()
        let action = element(application, actionIdentifier)
        XCTAssertTrue(action.waitForExistence(timeout: 4), message)
        action.tap()
    }

    /// 带效果验证的稳健点击：列表刷新/返回转场动画期间 tap 可能落空（门禁第 2 轮实证：
    /// 会话行 tap 未推入详情页、搜索框 tap 未建立键盘焦点），未出现预期效果则有界重试。
    /// verify 应为「点击产生的直接效果」的快速判定，不承担业务断言。
    @discardableResult
    private func tapUntil(_ target: XCUIElement, timeout: TimeInterval = 12,
                          _ verify: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            guard target.exists, target.isHittable else {
                Thread.sleep(forTimeInterval: 0.3)
                continue
            }
            target.tap()
            if verify() { return true }
            Thread.sleep(forTimeInterval: 0.6)
        }
        return verify()
    }

    /// 下滑回列表顶部直到元素回到可访问性树（LazyVStack 窗口外不渲染；
    /// 有界重试，用于揭示吸底滚动后滑出窗口的顶部内容）
    @discardableResult
    private func revealBySwipeDown(_ item: XCUIElement, application: XCUIApplication,
                                   maxSwipes: Int = 6) -> Bool {
        for _ in 0..<maxSwipes where !item.exists {
            application.swipeDown()
            _ = item.waitForExistence(timeout: 1)
        }
        return item.exists
    }

    /// 点击输入框并等待键盘弹出（无键盘不 typeText，避免合成事件落空）
    private func tapAndWaitKeyboard(_ field: XCUIElement, application: XCUIApplication,
                                    timeout: TimeInterval = 10) {
        _ = tapUntil(field, timeout: timeout) {
            application.keyboards.firstMatch.exists
        }
    }

    // MARK: 替身鉴权用 HTTP/WS 探针（测试进程直连替身）

    private func httpGetStatus(_ urlString: String, bearer: String? = nil) -> Int {
        guard let url = URL(string: urlString) else { return -1 }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        if let bearer {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        let semaphore = DispatchSemaphore(value: 0)
        var status = -1
        URLSession.shared.dataTask(with: request) { _, response, _ in
            status = (response as? HTTPURLResponse)?.statusCode ?? -1
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 8)
        return status
    }

    /// 正确令牌：升级成功 → 收到首条二进制 RPC 帧（Initialize，Regular 帧首字节 = 1）
    private func webSocketUpgradeReceivesInitialize(token: String) -> Bool {
        guard let url = URL(string: "ws://127.0.0.1:\(stub.port)/ws?token=\(token)") else { return false }
        let task = URLSession.shared.webSocketTask(with: url)
        task.resume()
        let semaphore = DispatchSemaphore(value: 0)
        var gotRegularFrame = false
        task.receive { result in
            if case .success(.data(let data)) = result, data.first == 1 {
                gotRegularFrame = true
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 6)
        task.cancel(with: .normalClosure, reason: nil)
        return gotRegularFrame
    }

    /// 错误令牌：升级被拒（401 + 连接关闭）→ receive 失败，或超时未收到任何帧
    private func webSocketUpgradeRejected(token: String) -> Bool {
        guard let url = URL(string: "ws://127.0.0.1:\(stub.port)/ws?token=\(token)") else { return true }
        let task = URLSession.shared.webSocketTask(with: url)
        task.resume()
        let semaphore = DispatchSemaphore(value: 0)
        var rejected = false
        task.receive { result in
            if case .failure = result { rejected = true }
            semaphore.signal()
        }
        if semaphore.wait(timeout: .now() + 6) == .timedOut {
            rejected = true // 未收到任何应用帧即视为未升级成功
        }
        task.cancel(with: .normalClosure, reason: nil)
        return rejected
    }

    // MARK: - 流程 13/14：本期新增接入面的连接态闭环（调研 plan「替身验证」落地）

    /// 连接替身并回到主界面（test12 同口径；relaunch 触发冷启动自动重连）
    @discardableResult
    private func connectAndEnterMain(_ application: XCUIApplication) -> XCUIApplication {
        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: stub.pairingToken)
        submitManualConnect(application)
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(waitDisappear(element(application, "l1-submit-connect"), timeout: 15,
                                    "连接成功后连接流程应自动收起"))
        relaunch(application)
        return application
    }

    /// 流程 13：活性与写持久化闭环。
    /// ① onDynamicTaskEvent 服务端推送 → 任务卡免刷新更新（observeTasks 活性 named gap 修复）；
    /// ② 坏 checksum 分片 → assembler dropped → resyncSessionsIndexV4 调用（静默断流自愈）；
    /// ③ git.refresh → git.getChanges 时序（Diff 新鲜度前置）；
    /// ④ 置顶双写：UI 左滑 → setTaskPinned 到达替身 → 快照回推 pinned 投影（execution 恒 0）；
    /// ⑤ 向上分页：loadOlder 拼接出更早消息（beforeRowId named gap 修复）。
    func test13_connectedLivePushResyncRefreshPinPersistence() throws {
        let application = connectAndEnterMain(launchFresh())

        // ① 任务推送活性：切到任务 Tab → 卡片加载在场（listTaskList 已回、cache 已填充）→
        // 替身 fire 状态变化 → 事件 upsert 免刷新更新。
        // 时序约束：fire 必须在卡片在场之后——事件先于 refresh 到达会被随后的
        // listTaskList 全量覆盖回初始 running 态（门禁第 1 轮实证的竞态）。
        // 须选中任务 Tab：未选中 Tab 的 LazyVStack 不渲染窗口外内容，且 swipeUp 须滚任务板。
        element(application, "02-tab-tasks").tap()
        let taskCard = element(application, "02-taskcard-task-e2e-1")
        XCTAssertTrue(taskCard.waitForExistence(timeout: 15), "任务看板应呈现替身任务卡")
        XCTAssertTrue(waitUntil(timeout: 15, "App 应订阅 onDynamicTaskEvent") {
            stub.hasEventListener("onDynamicTaskEvent")
        })
        stub.fireTaskEvent(status: "completed")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应向活跃连接发出任务事件帧") {
            stub.taskEventFireHits >= 1
        }, "诊断：channels=\(stub.hasEventListener("onDynamicTaskEvent")), fireHits=\(stub.taskEventFireHits)")
        // 卡片随分组重排（running 组 → done 组）可能移出 LazyVStack 渲染窗口：
        // 先等看板 pill 重渲染为已完成（组合谓词：label==状态 AND identifier==卡片 id），
        // 再点击卡片推入详情（pushTask 传入点击时的 task 值——先渲染后点击避免 stale 传参）
        let donePill = application.staticTexts.matching(
            NSPredicate(format: "label == '已完成' AND identifier == '02-taskcard-task-e2e-1'"))
        var pillShowsDone = donePill.firstMatch.exists
        let scrollDeadline = Date().addingTimeInterval(12)
        while !pillShowsDone, Date() < scrollDeadline {
            application.swipeUp()
            if donePill.firstMatch.waitForExistence(timeout: 1) {
                pillShowsDone = true
            }
        }
        XCTAssertTrue(pillShowsDone, "onDynamicTaskEvent 后看板卡片应免刷新转为已完成；fireHits=\(stub.taskEventFireHits)")
        XCTAssertTrue(tapUntil(taskCard, timeout: 10) {
            element(application, "07-status-bar").exists
        }, "点击任务卡应推入任务详情")
        // 状态观测面：statusBar 的状态 pill 是「identifier=07-status-bar、label=状态文案」的
        // 独立 StaticText（SwiftUI 容器 identifier 会传播给子文本；同名 id 有多个，
        // firstMatch 会命中标题 Text，必须用全局组合谓词精确定位）
        let detailDonePill = application.staticTexts.matching(
            NSPredicate(format: "label == '已完成' AND identifier == '07-status-bar'"))
        XCTAssertTrue(waitUntil(timeout: 10, "推送后任务详情状态栏应免刷新转为已完成") {
            detailDonePill.firstMatch.exists
        }, "推送后任务详情状态栏应免刷新转为已完成（07-status-bar 状态 pill）")
        // 返回任务板（任务详情处于 push 深层，TabBar 隐藏；后续段需切 Tab）
        app_navigationBack(application)
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "任务推送不应产生任何直写/配置写类命令")

        // ② 丢帧自愈：坏 checksum 分片 → dropped → resyncSessionsIndexV4 → 替身重发快照 → 列表恢复
        XCTAssertTrue(waitUntil(timeout: 15, "App 应订阅 sessions-index 帧事件") {
            stub.hasEventListener("onDynamicSessionsIndexFrame")
        })
        stub.fireBadChecksumFragment(topic: "sessions-index//Users/e2e/zcode-workspace")
        XCTAssertTrue(waitUntil(timeout: 10, "坏分片应触发 resyncSessionsIndexV4 自愈调用") {
            stub.resyncCalls.contains { $0.hasPrefix("resyncSessionsIndexV4") }
        })
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 10),
                      "resync 后替身重发快照，列表应呈现自愈恢复后的替身会话行（UI 回执）")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "resync 自愈不应产生任何直写/配置写类命令")

        // ③ git.refresh 前置时序：Diff 页加载后替身应先收到 refresh 再收到 getChanges
        element(application, "08-tab-review").tap()
        XCTAssertTrue(element(application, "08-filecard-toggle-SessionStore.swift").waitForExistence(timeout: 15),
                      "Diff 页应呈现替身变更文件卡")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 git.refresh 与 git.getChanges") {
            stub.gitRefreshCount >= 1
                && stub.rpcCallLog.contains { $0.1 == "refresh" && $0.0 == "git" }
                && stub.rpcCallLog.contains { $0.1 == "getChanges" && $0.0 == "git" }
        })
        let refreshIdx = stub.rpcCallLog.firstIndex { $0.0 == "git" && $0.1 == "refresh" }
        let changesIdx = stub.rpcCallLog.firstIndex { $0.0 == "git" && $0.1 == "getChanges" }
        if let refreshIdx, let changesIdx {
            XCTAssertLessThan(refreshIdx, changesIdx, "git.refresh 应先于 git.getChanges")
        } else {
            XCTFail("替身应同时记录 refresh 与 getChanges 调用")
        }

        // ④ 置顶双写：左滑置顶 → setTaskPinned 到达替身；回执经 session.upserted（pinned 投影）回推
        XCTAssertTrue(waitUntil(timeout: 15, "重连订阅后应出现替身快照会话行") {
            element(application, "04-row-sess-e2e-1").exists
        })
        element(application, "04-tab-chat").tap()
        let stubRow1 = element(application, "04-row-sess-e2e-1")
        swipeRowAndTapAction(application, row: stubRow1, actionIdentifier: "04-rowact-pin-sess-e2e-1",
                             message: "左滑 sess-e2e-1 应出现置顶动作")
        XCTAssertTrue(waitUntil(timeout: 10, "置顶应触发 setTaskPinned 写（双写持久化）") {
            stub.recordedPinned["sess-e2e-1"] == true
        })
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "置顶双写属元数据面，不计入直写/配置写")

        // ⑤ 向上分页：进入 sess-e2e-1 只读详情 → 「加载更早消息」→ 更早页拼接（不重复）
        tapUntil(stubRow1, timeout: 12) {
            element(application, "05-composer-input").exists
        }
        XCTAssertTrue(element(application, "05-composer-input").waitForExistence(timeout: 8),
                      "应进入会话详情（连接态输入区可用）")
        let loadOlder = element(application, "05-act-load-older")
        XCTAssertTrue(loadOlder.waitForExistence(timeout: 10),
                      "连接态只读详情应有向上分页入口（尾部 3 行 < 全量 6 行）")
        loadOlder.tap()
        XCTAssertTrue(waitStaticText(containing: "更早的问题：把网关重试参数梳理一下", in: application,
                                    timeout: 10, "loadOlder 应拼接出更早页消息"))
        XCTAssertTrue(waitStaticText(containing: "帮我把登录超时问题定位一下", in: application,
                                    timeout: 6, "首屏尾部消息应保留（拼接不覆盖）"))
        // 替身侧证据：分页请求应携带游标 beforeRowId=4（尾部窗最早行），首屏为无游标拉取
        XCTAssertTrue(waitUntil(timeout: 10, "loadOlder 应向替身发起带 beforeRowId=4 的 conversationRowsRangeV4") {
            stub.rowsRangeRequests.contains { $0.sessionId == "sess-e2e-1" && $0.beforeRowId == 4 }
                && stub.rowsRangeRequests.contains { $0.sessionId == "sess-e2e-1" && $0.beforeRowId == nil }
        })
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "全程不应产生任何直写/配置写类命令")
    }

    /// 流程 14：只读数据面闭环。
    /// ① Chat chips 绑 model-selection 真实数据（只读，不调 set*）；
    /// ② 文件树服务端搜索 + 大文件截断「加载更多」；
    /// ③ Diff 数据源分段（staged 维度 + 本次会话）；
    /// ④ 任务详情只读元数据（配置/模型/Token 用量）；
    /// ⑤ 设置页 Coding Plan 用量卡 + 服务器详情桌面端登录行。
    func test14_connectedReadonlyDataFacesChipsSearchTruncationSegments() throws {
        let application = connectAndEnterMain(launchFresh())
        XCTAssertTrue(waitUntil(timeout: 15, "重连订阅后应出现替身快照会话行") {
            element(application, "04-row-sess-e2e-1").exists
        })

        // ① model-selection.getView → chips 绑定模型（workspace-config 订阅 + 快照为 Store 投影面）
        XCTAssertTrue(waitUntil(timeout: 10, "连接后应完成 workspace-config 订阅") {
            stub.subscribedTopics.contains { $0.hasPrefix("workspace-config/") }
        })
        stub.fireWorkspaceConfigFrame(workspacePath: "/Users/e2e/zcode-workspace")
        element(application, "04-tab-chat").tap()
        tapUntil(element(application, "04-row-sess-e2e-1"), timeout: 12) {
            element(application, "05-composer-input").exists
        }
        XCTAssertTrue(element(application, "05-composer-input").waitForExistence(timeout: 8),
                      "应进入会话详情（连接态输入区可用）")
        let chips = element(application, "05-composer-remote-chips")
        // 失败消息自带替身侧证据：getView 是否到达替身（通道名）、最近 RPC 序列、订阅面
        let chipsAppeared = chips.waitForExistence(timeout: 10)
        XCTAssertTrue(chipsAppeared,
                      """
                      只读态应展示桌面端模型 chips（model-selection.getView）。
                      诊断：getView 到达替身的通道=\(stub.rpcCallLog.filter { $0.1 == "getView" }.map { $0.0 })；
                      rpcCallLog 尾部=\(stub.rpcCallLog.suffix(10))；topics=\(stub.subscribedTopics)
                      """)
        XCTAssertTrue(waitLabel(chips, contains: "GLM-5.3", timeout: 10, "chips 应显示替身绑定模型"))
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 model-selection.getView 读面命令") {
            stub.rpcCallLog.contains { $0.0 == "model-selection" && $0.1 == "getView" }
        })
        app_navigationBack(application)

        // ② 文件树：readdir 两级目录渲染 + file-watcher watch（树页打开即建立监听）
        // 详情返回转场期 TabBar 可能暂不可查询，tap 带效果重试（切 Tab 的直接效果 = 文件页元素出现）
        let filesTab = element(application, "08-tab-review")
        XCTAssertTrue(tapUntil(filesTab, timeout: 12) {
            element(application, "08-filecard-toggle-SessionStore.swift").exists
        }, "应切到文件 Tab（返回转场后 TabBar 应可查询）")
        XCTAssertTrue(element(application, "08-act-browse").waitForExistence(timeout: 8),
                      "文件页应有「浏览」入口")
        element(application, "08-act-browse").tap()
        let sourcesRow = element(application, "09-row-dir-/Users/e2e/zcode-workspace/Sources")
        XCTAssertTrue(sourcesRow.waitForExistence(timeout: 15),
                      "文件树应渲染替身 readdir 返回的 Sources 目录行（UI 回执）")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 file.readdir 读面命令") {
            stub.rpcCallLog.contains { $0.0 == "file" && $0.1 == "readdir" }
        })
        XCTAssertTrue(waitUntil(timeout: 10, "文件树打开应触发 file-watcher.watch") {
            stub.fileWatcherWatchCount >= 1
        })
        XCTAssertTrue(stub.lastWatchRequest?.path.hasPrefix("/Users/e2e/zcode-workspace") == true,
                      "watch 参数应指向替身工作区（实际：\(String(describing: stub.lastWatchRequest))）")
        // 服务端搜索（searchWorkspaceFiles）命中候选
        let searchField = element(application, "09-search")
        XCTAssertTrue(searchField.waitForExistence(timeout: 10), "文件树应有搜索框")
        tapAndWaitKeyboard(searchField, application: application)
        searchField.typeText("Composer")
        XCTAssertTrue(element(application, "09-row-file-/Users/e2e/zcode-workspace/Composer.swift")
            .waitForExistence(timeout: 10),
            "服务端搜索应返回替身候选 Composer.swift")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 file.searchWorkspaceFiles 读面命令") {
            stub.rpcCallLog.contains { $0.0 == "file" && $0.1 == "searchWorkspaceFiles" }
        })

        // ②b 大文件截断：stat(size=300KB) > 256KiB 首屏 → 「已截断 · 加载更多」（readTextFile 切片）
        let composerRow = element(application, "09-row-file-/Users/e2e/zcode-workspace/Composer.swift")
        if composerRow.exists {
            composerRow.tap()
            XCTAssertTrue(element(application, "09-act-load-more").waitForExistence(timeout: 10),
                          "300KB 大文件应显示截断守卫（首屏 256KiB）")
            XCTAssertTrue(element(application, "09-act-load-more").label.contains("已截断"),
                          "截断按钮应带「已截断」提示")
            XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 file.stat 与 file.readTextFile 读面命令") {
                stub.rpcCallLog.contains { $0.0 == "file" && $0.1 == "stat" }
                    && stub.rpcCallLog.contains { $0.0 == "file" && $0.1 == "readTextFile" }
            })
            app_navigationBack(application)
        }

        // ③ Diff 分段：默认未暂存（既有断言面）→ 已暂存分段出现替身 staged 文件卡。
        // 当前仍处文件树 push 深层（filePath 非空时 TabBar 隐藏），先返回文件 Tab 根再断言
        app_navigationBack(application)
        XCTAssertTrue(element(application, "08-seg-source-未暂存").waitForExistence(timeout: 10),
                      "连接态 Diff 页应显示数据源分段（未暂存/已暂存/本次会话）")
        XCTAssertTrue(element(application, "08-seg-source-未暂存").waitForExistence(timeout: 10),
                      "连接态 Diff 页应显示数据源分段（未暂存/已暂存/本次会话）")
        element(application, "08-seg-source-已暂存").tap()
        XCTAssertTrue(element(application, "08-filecard-toggle-StagedFile.swift").waitForExistence(timeout: 10),
                      "已暂存分段应呈现替身 staged 文件卡")
        element(application, "08-seg-source-本次会话").tap()
        XCTAssertTrue(element(application, "08-filecard-toggle-SessionStore.swift").waitForExistence(timeout: 10),
                      "本次会话分段应呈现 conversationFileChangesV4 回执文件卡；rpcTail=\(stub.rpcCallLog.suffix(6))")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 zcode-agent.conversationFileChangesV4 读面命令") {
            stub.rpcCallLog.contains { $0.0 == "zcode-agent" && $0.1 == "conversationFileChangesV4" }
        })

        // ④ 任务详情只读元数据：模型绑定 + 配置 + Token 用量行
        element(application, "02-tab-tasks").tap()
        let taskCard = element(application, "02-taskcard-task-e2e-1")
        XCTAssertTrue(taskCard.waitForExistence(timeout: 15), "任务看板应呈现替身任务卡")
        XCTAssertTrue(tapUntil(taskCard, timeout: 10) {
            element(application, "07-status-bar").exists
        }, "点击任务卡应推入任务详情")
        // 替身侧证据前置：三读命令先到替身，UI 行随后渲染（缺数据行不渲染避免死控件）
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到任务详情三读命令（配置/模型/用量）") {
            stub.rpcCallLog.contains { $0.0 == "zcode-task" && $0.1 == "getTaskConfigOptions" }
                && stub.rpcCallLog.contains { $0.0 == "zcode-task" && $0.1 == "getTaskModelSelection" }
                && stub.rpcCallLog.contains { $0.0 == "zcode-task" && $0.1 == "getTaskTokenUsage" }
        })
        // metaRow 的子文本继承容器 identifier（07-meta-readonly），以全局组合谓词观测
        let metaTexts = application.staticTexts.matching(
            NSPredicate(format: "identifier == '07-meta-readonly'"))
        XCTAssertTrue(
            metaTexts.matching(NSPredicate(format: "label BEGINSWITH 'GLM-5.3'"))
                .firstMatch.waitForExistence(timeout: 10),
            "任务详情应显示绑定模型行（替身 modelId · reasoningLevel）")
        XCTAssertTrue(
            metaTexts.matching(NSPredicate(format: "label CONTAINS '思考档=high'"))
                .firstMatch.exists,
            "配置行应显示替身 option 名/值")
        XCTAssertTrue(
            metaTexts.matching(NSPredicate(format: "label CONTAINS '输入 100000'"))
                .firstMatch.exists,
            "用量行应显示替身 inputTokens 回执值")

        // ④b「模型轨迹」分段：07-seg 两段切换 + 轨迹卡 reasoning+toolCall 投影
        // （stub task-e2e-1 同名会话行：assistantText → reasoning 标签、toolCall → BASH 标签）
        let trajectorySeg = element(application, "07-seg-模型轨迹")
        XCTAssertTrue(trajectorySeg.waitForExistence(timeout: 6),
                      "任务详情应有「后台 Bash / 模型轨迹」两段分段（07-seg-*）")
        trajectorySeg.tap()
        XCTAssertTrue(element(application, "07-toolcard-head-trajectory").waitForExistence(timeout: 6),
                      "切到模型轨迹段应渲染轨迹卡（TRAJECTORY 头）")
        XCTAssertFalse(element(application, "07-terminal").exists,
                       "轨迹段下后台 Bash 终端卡应收起（分段互斥）")
        XCTAssertTrue(waitStaticText(containing: "替身助手：轨迹投影链路检查中", in: application,
                                     timeout: 10, "轨迹卡应呈现 reasoning 投影行（会话 assistantText 投影）"))
        XCTAssertTrue(waitStaticText(containing: "swift test --filter SessionStoreTests", in: application,
                                     timeout: 6, "轨迹卡应呈现 toolCall 投影行（BASH 目标+输出）"))
        snap(application, "28-task-trajectory-segment")
        app_navigationBack(application)

        // ⑤ 设置页用量卡（真实 quota 投影：340/500 + resetStatus 重置时间）
        //    + 服务器详情桌面端登录行（oauth 三读投影）
        // 用量明细是卡内独立 Text（容器聚合 label 不含它），以文本内容全局观测
        element(application, "12-tab-me").tap()
        XCTAssertTrue(element(application, "12-usercard").waitForExistence(timeout: 10), "设置页应显示用户卡")
        let usageDetail = application.staticTexts.matching(
            NSPredicate(format: "label CONTAINS '340 / 500'"))
        XCTAssertTrue(usageDetail.firstMatch.waitForExistence(timeout: 15),
                      "连接态用量卡应显示替身 quota 投影（340/500 积分）")
        XCTAssertTrue(usageDetail.firstMatch.label.contains("重置"),
                      "用量卡应显示替身 getCodingPlanResetStatus 回执的重置时间投影")
        let serverRow = element(application, "l4-row-server")
        XCTAssertTrue(serverRow.waitForExistence(timeout: 8), "设置页应有服务器行")
        serverRow.tap()
        let infoCard = element(application, "l5-info-card")
        XCTAssertTrue(infoCard.waitForExistence(timeout: 10), "服务器详情应显示信息卡")
        // 桌面端登录行是卡内独立 Text（容器聚合 label 不含它），以文本内容全局观测
        let desktopLoginRow = application.staticTexts.matching(
            NSPredicate(format: "label CONTAINS '替身用户'"))
        XCTAssertTrue(desktopLoginRow.firstMatch.waitForExistence(timeout: 10),
                      "信息卡应显示桌面端登录投影（restoreCachedSessionState userInfo）")
        XCTAssertTrue(desktopLoginRow.firstMatch.label.contains("zai"),
                      "信息卡应显示当前 provider 投影（getActiveProvider=zai）")
        // 替身侧证据：oauth 三读 + usage-stats 两读命令均应到达替身
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 oauth 三只读命令") {
            stub.rpcCallLog.contains { $0.0 == "oauth" && $0.1 == "getProviders" }
                && stub.rpcCallLog.contains { $0.0 == "oauth" && $0.1 == "getActiveProvider" }
                && stub.rpcCallLog.contains { $0.0 == "oauth" && $0.1 == "restoreCachedSessionState" }
        })
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 usage-stats 两只读命令") {
            stub.rpcCallLog.contains { $0.0 == "usage-stats" && $0.1 == "getCodingPlanUsageSnapshot" }
                && stub.rpcCallLog.contains { $0.0 == "usage-stats" && $0.1 == "getCodingPlanResetStatus" }
        })
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "全部只读数据面浏览不应产生任何直写/配置写类命令")
    }

    /// 流程 15：写路径与兜底路径闭环。
    /// ① 已读清零：快照角标（pendingInteractionSummary.permissionCount=1 → 行内未读徽章「1」）
    ///    → 进入详情触发 markRead（本地清零 + zcode-task.setTaskUnread 到替身）→ 返回后徽章消失；
    /// ② 订阅失败兜底：替身拒绝 subscribeConversationV4（promiseError）→ 客户端走
    ///    zcode-agent.readSession（runtimePolicy=existing-only，只读对账不拉起 Agent）；
    /// ③ 全程替身收到的直写/配置写类命令数恒为 0（边界不被兜底路径绕过）。
    func test15_connectedUnreadClearLoopAndSubscribeFallbackReconciliation() throws {
        let application = connectAndEnterMain(launchFresh())
        let stubRow1 = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(stubRow1.waitForExistence(timeout: 15), "重连后应呈现替身快照会话行")

        // ① 未读徽章在场（快照 pendingInteractionSummary.permissionCount=1 的 UI 投影）
        let badge = stubRow1.staticTexts["1"]
        XCTAssertTrue(badge.waitForExistence(timeout: 10),
                      "替身快照会话行应显示未读徽章「1」（pendingInteractionSummary 投影）")

        // 进入详情：markRead 本地清零 + setTaskUnread 到替身
        XCTAssertTrue(tapUntil(stubRow1, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "点击会话行应推入详情（连接态输入区可用）")
        XCTAssertTrue(waitUntil(timeout: 10, "进入详情应触发 setTaskUnread 写（session 类）") {
            stub.setTaskUnreadCount >= 1
                && stub.rpcCallLog.contains { $0.0 == "zcode-task" && $0.1 == "setTaskUnread" }
        })
        app_navigationBack(application)
        // UI 回执：本地清零后列表行未读徽章消失
        XCTAssertTrue(waitDisappear(badge, timeout: 10, "已读清零后行内未读徽章应消失"))
        XCTAssertEqual(stub.blockedWriteCommandCount, 0, "已读清零全程不应产生任何直写/配置写类命令")

        // ② 订阅失败兜底：替身拒绝 conversation 订阅 → readSession 只读对账
        stub.failConversationSubscribe = true
        let stubRow2 = element(application, "04-row-sess-e2e-2")
        XCTAssertTrue(stubRow2.waitForExistence(timeout: 8), "列表应呈现替身快照会话 sess-e2e-2")
        XCTAssertTrue(tapUntil(stubRow2, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "订阅被拒时点击会话行仍应推入详情（兜底路径不阻塞浏览）")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应记录订阅拒绝并随后收到 readSession 对账") {
            stub.conversationSubscribeRejections >= 1
                && stub.rpcCallLog.contains { $0.0 == "zcode-agent" && $0.1 == "readSession" }
        })
        // 兜底路径下详情数据仍来自替身分页（预置历史行正常渲染，链路不中断）
        XCTAssertTrue(waitStaticText(containing: "替身助手：基线检查完成", in: application,
                                    timeout: 10, "兜底路径下会话历史应仍来自替身 conversationRowsRangeV4"))

        // ③ skip-boundary：含拒绝/兜底/清零写在内的全程，替身 execution 类命令数恒为 0
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "全程（含订阅失败兜底与已读清零写）替身不应收到任何直写/配置写类命令")
    }

    /// 流程 16（v3 纠偏批次核心闭环）：连接态桌面代执行命令全链路。
    /// ① 审批：会话行待审批角标 → 详情审批卡（命令/影响结构化排版）→ 批准 →
    ///    resolveInteraction 到达替身且携带真实 interactionId → state 更新后卡片撤下；
    /// ② 停止：运行中任务详情「停止任务」→ 二次确认 → v4 stop 到达替身 → 状态事件
    ///    回流后卡片翻转为非运行；
    /// ③ 会话管理：行长按菜单重命名（renameTask 到达）+ 已归档分区取消归档（unarchiveTask 到达）；
    /// ④ 边界底线：全程 blockedWriteCommandCount == 0（文件直写/配置写类不因命令面放开而松动）。
    func test16_connectedSendApproveStopRenameUnarchiveLoop() throws {
        let application = connectAndEnterMain(launchFresh())
        let stubRow1 = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(stubRow1.waitForExistence(timeout: 15), "重连后应呈现替身快照会话行")

        // ① 审批闭环：进入 sess-e2e-1（替身在 conversation 订阅后下发 permission 挂起交互）
        XCTAssertTrue(tapUntil(stubRow1, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "点击会话行应推入详情")
        let approvalCard = element(application, "05-approval-card")
        XCTAssertTrue(approvalCard.waitForExistence(timeout: 12),
                      "替身 state.pendingInteractions（permission）应驱动审批卡渲染")
        XCTAssertTrue(waitStaticText(containing: "rm -rf /Users/e2e/zcode-workspace/build", in: application,
                                     timeout: 8, "审批卡应展示命令结构化排版（mono 命令体）"))
        XCTAssertTrue(application.staticTexts.matching(
            NSPredicate(format: "label CONTAINS '删除构建产物目录'")).firstMatch.exists,
            "审批卡应展示影响摘要行")
        // 授权范围 chips 可切换（A-3 后两档：仅本次/始终允许；默认仅本次）。
        // 审批卡位于消息列表顶部，详情吸底滚动
        // （defaultScrollAnchor(.bottom)）后卡片可能滑出 LazyVStack 渲染窗口（元素出树，
        // 门禁第 1 轮实证 tap No matches）——tap 前先下滑回顶部揭示
        let choiceAlways = element(application, "05-choice-always")
        XCTAssertTrue(revealBySwipeDown(choiceAlways, application: application),
                      """
                      下滑揭示后审批卡授权范围 chips 应可操作（05-choice-*）。
                      诊断：card.exists=\(approvalCard.exists)；容器 identifier 覆盖子元素时
                      05-choice-* 不进树（应用侧已改为 accessibilityElement(children: .contain)）
                      """)
        choiceAlways.tap()
        element(application, "05-choice-once").tap()
        // 批准：resolveInteraction 真实下发且携带 interactionId
        element(application, "05-act-approve").tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 resolveInteraction 应答") {
            stub.resolveInteractionCount >= 1
        }, "批准应经 v4 resolveInteraction 下发")
        XCTAssertEqual(stub.lastResolveInteraction?.interactionId, "int-e2e-perm-1",
                       "应答应携带真实 interactionId；实际=\(String(describing: stub.lastResolveInteraction?.interactionId))")
        let approvedPayload = stub.lastResolveInteraction?.payload ?? [:]
        // A-3（web 实证 bundle）：answer 恒为对象按交互族分形——权限族 {optionId}。
        // 替身交互不带 options → 默认两档矩阵，仅本次 + 批准 = 规范 id "allowOnce"
        // （approved/scope 是 wire 不存在的旧形态，已随对齐修复移除）
        let answerOptionId = (approvedPayload["answer"] as? [String: Any])?["optionId"] as? String
        XCTAssertEqual(answerOptionId, "allowOnce",
                       "answer 应为 {optionId:\"allowOnce\"}；实际 payload=\(approvedPayload)")
        XCTAssertTrue(waitDisappear(approvalCard, timeout: 10, "应答生效后审批卡应随 state 更新撤下"))

        // ② 停止闭环：任务看板 → 运行中任务详情 → 停止任务（二次确认）→ stop 到达 + 状态回流
        app_navigationBack(application)
        element(application, "02-tab-tasks").tap()
        let taskCard = element(application, "02-taskcard-task-e2e-1")
        XCTAssertTrue(taskCard.waitForExistence(timeout: 15), "任务看板应呈现替身任务卡")
        XCTAssertTrue(tapUntil(taskCard, timeout: 10) {
            element(application, "07-status-bar").exists
        }, "点击任务卡应推入任务详情")
        let stopAction = element(application, "07-act-stop")
        XCTAssertTrue(stopAction.waitForExistence(timeout: 8),
                      "连接态任务详情应提供停止任务入口（07-act-stop-readonly 已移除）")
        stopAction.tap()
        // 确认弹窗：与动作按钮同名（「停止任务」），取最后一个匹配（弹窗按钮后入树）
        let stopButtons = application.buttons.matching(NSPredicate(format: "label == '停止任务'"))
        XCTAssertTrue(stopButtons.firstMatch.waitForExistence(timeout: 6), "停止应弹二次确认弹窗")
        stopButtons.element(boundBy: max(0, stopButtons.count - 1)).tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 v4 stop 命令") {
            stub.stopCount >= 1
        }, "停止任务应真实下发（stopCount=\(stub.stopCount)）")
        // 状态回流：替身 stop 分支回推 completed 事件 → 详情状态条翻转为已完成
        let donePill = application.staticTexts.matching(
            NSPredicate(format: "label == '已完成' AND identifier == '07-status-bar'"))
        XCTAssertTrue(donePill.firstMatch.waitForExistence(timeout: 12),
                      "stop 后替身状态事件应回流并翻转任务详情状态条（07-status-bar pill）")
        app_navigationBack(application)
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "审批/停止全程不应产生任何直写/配置写类命令（边界底线）")

        // ③ 会话管理：重命名（renameTask 到达 + renameTask 新标题断言）
        element(application, "04-tab-chat").tap()
        let row1 = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(row1.waitForExistence(timeout: 10), "应回到会话列表并呈现 sess-e2e-1")
        row1.press(forDuration: 1.2)
        let renameMenu = application.buttons["重命名"].firstMatch.exists
            ? application.buttons["重命名"].firstMatch
            : application.staticTexts["重命名"].firstMatch
        XCTAssertTrue(renameMenu.waitForExistence(timeout: 5),
                      "长按会话行应弹出含「重命名」的上下文菜单（04-ctx-rename）")
        renameMenu.tap()
        // 重命名输入框定位到 alert 内（四 Tab 同挂 ZStack，未选中 Tab 的 02-search-input
        // 仍在 textFields 查询结果里，firstMatch 会误命中——门禁实证）；alert 的 TextField
        // 不自动获焦，typeText 前先 tap 聚焦并等键盘弹出
        let renameField = application.alerts.firstMatch.textFields.firstMatch
        XCTAssertTrue(renameField.waitForExistence(timeout: 6), "重命名应弹输入框")
        tapAndWaitKeyboard(renameField, application: application)
        // 输入用 ASCII 锚点（套件先例：中文合成事件存在丢字偶发；丢字为空时
        // renameConversation 的空标题守卫会静默返回，renameTask 不下发）
        renameField.typeText("Renamed-E2E-16")
        let fieldValue = String(describing: renameField.value ?? "nil")
        // 先收键盘再点保存（键盘为系统层，若遮挡保存按钮命中点则 tap 落空）：
        // 点 alert 主体安全收起键盘（alert 为模态，不因外部 tap 关闭）
        application.alerts.firstMatch.tap()
        if application.keyboards.firstMatch.exists {
            dismissKeyboard(application)
        }
        application.alerts.firstMatch.buttons["保存"].firstMatch.tap()
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 renameTask 元数据写") {
            stub.renameTaskCount >= 1
        }, "诊断 renameTaskCount=\(stub.renameTaskCount) alert仍在树=\(application.alerts.firstMatch.exists) 输入后value=\(fieldValue)")
        XCTAssertTrue(stub.lastRenameTitle?.contains("Renamed-E2E-16") == true,
                      "renameTask 应携带新标题；实际=\(String(describing: stub.lastRenameTitle))")

        // ③b 已归档分区：入口行展开 → listArchivedTasks 拉取 + 归档样例行 + 取消归档（unarchiveTask 到达）
        let archivedEntry = element(application, "04-act-archived")
        XCTAssertTrue(archivedEntry.waitForExistence(timeout: 8),
                      "连接态会话列表应提供「已归档」入口（P1-6）")
        archivedEntry.tap()
        let archivedRow = element(application, "04-archivedrow-sess-e2e-arch-1")
        XCTAssertTrue(archivedRow.waitForExistence(timeout: 10),
                      "已归档分区应呈现替身 listArchivedTasks 归档样例行")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 listArchivedTasks 只读拉取") {
            stub.listArchivedTasksCount >= 1
        })
        // 滑块取消归档（swipe 落空时点击行主体等效：两者都触发 unarchiveTask）
        row1.swipeLeft()
        let unarchive = element(application, "04-archact-unarchive-sess-e2e-arch-1")
        if unarchive.waitForExistence(timeout: 4) {
            unarchive.tap()
        } else {
            archivedRow.tap()
        }
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 unarchiveTask（取消归档此前误发 archiveTask）") {
            stub.unarchiveTaskCount >= 1
        }, "取消归档应下发 unarchiveTask")
        // ④ 边界底线（全程收口）
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "全程（审批/停止/重命名/归档管理）不应产生任何直写/配置写类命令")
    }

    /// 流程 17：断线自愈与状态横幅（13-③）。
    /// 连接替身后替身侧强制掐断 WS 通道（模拟桌面端断网）→ AppSession 切 .disconnected →
    /// 黄色横幅「已断开 · 显示断线前的数据 · 重连」；点「重连」→ 完整重连（server-info → WS →
    /// v4 握手）→ 横幅消失、列表恢复替身数据。全程不产生直写/配置写类命令。
    func test17_droppedConnectionShowsBannerAndRetryReconnects() throws {
        let application = connectAndEnterMain(launchFresh())
        let stubRow1 = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(stubRow1.waitForExistence(timeout: 15), "重连后应呈现替身快照会话行")
        XCTAssertFalse(element(application, "13-banner-connection").exists,
                       "已连接态不应出现连接横幅")

        // 替身侧掐断全部 WS 通道（传输层 cancel，无 close 帧）
        stub.dropAllWebSocketChannels()

        // 黄色断线横幅出现：标题 + 「重连」动作按钮（动作热区在横幅内、可点）。
        // 横幅容器 label 不聚合子文本（同 12-usercard 口径），标题以全局 Text 观测：
        // 黄色断线态标题「已断开 · 显示断线前的数据」（U-9：断线保留最后快照，无演示
        // 回退语义）≠ 红色失败态「桌面端连接失败」，此断言同时验证断线路由
        // （AppSession .disconnected）而非连接失败
        let banner = element(application, "13-banner-connection")
        XCTAssertTrue(banner.waitForExistence(timeout: 15),
                      "WS 通道被掐断后应出现断线横幅（AppSession .disconnected 投影）")
        XCTAssertTrue(application.staticTexts["已断开 · 显示断线前的数据"].waitForExistence(timeout: 8),
                      "断线横幅应表明已断开并显示断线前数据（黄色断线态标题；若实际渲染红色失败态标题则断线路由错误）")
        let reconnectButton = application.buttons["重连"].firstMatch
        XCTAssertTrue(reconnectButton.waitForExistence(timeout: 6),
                      "断线横幅应提供「重连」动作")
        snap(application, "30-disconnected-banner")

        // 重连：替身应再次完成 WS 升级 → 横幅消失 → 列表恢复替身数据
        let upgradesBefore = stub.websocketUpgrades
        reconnectButton.tap()
        XCTAssertTrue(waitUntil(timeout: 25, "重连应重新完成 server-info 探测与 WS 升级") {
            stub.websocketUpgrades >= upgradesBefore + 1
        }, "重连应重新完成 WS 升级；实测 upgrades=\(stub.websocketUpgrades)")
        XCTAssertTrue(waitDisappear(banner, timeout: 20, "重连成功后断线横幅应消失"))
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 10),
                      "重连后列表应恢复替身数据（自愈完成）")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "断线/重连全程不应产生任何直写/配置写类命令")
    }

    /// 流程 18：消息流六类行只读渲染（stub sess-e2e-3 全 kind 预置行）。
    /// 首窗尾部 3 行 = toolCall 工具卡 / subagent / artifact；「加载更早消息」拼接出
    /// userInput / assistantText / reasoning（项 4 起为思考折叠块）——六类行映射全量断言 + 截图检查点。
    func test18_connectedMessageStreamRendersAllSixRowKinds() throws {
        let application = connectAndEnterMain(launchFresh())
        let stubRow3 = element(application, "04-row-sess-e2e-3")
        XCTAssertTrue(stubRow3.waitForExistence(timeout: 15),
                      "重连后应呈现替身快照会话 sess-e2e-3（六类行投影专用）")
        XCTAssertTrue(tapUntil(stubRow3, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "点击 sess-e2e-3 应推入详情")

        // 首窗（尾部 3 行）：toolCall 工具卡（类型/目标/状态）+ subagent + artifact
        XCTAssertTrue(element(application, "05-toolcard-head-bash").waitForExistence(timeout: 12),
                      "toolCall 行应渲染 bash 工具卡（类型着色头部）")
        XCTAssertTrue(waitStaticText(containing: "swift build", in: application, timeout: 8,
                                     "工具卡应显示目标（inputText 投影）"))
        XCTAssertTrue(waitStaticText(containing: "子智能体 · reviewer", in: application, timeout: 8,
                                     "subagent 行应渲染「🤖 子智能体 · 类型：摘要」"))
        XCTAssertTrue(waitStaticText(containing: "六类行基线.md", in: application, timeout: 8,
                                     "artifact 行应渲染产物名（📦 产物投影）"))

        // 向上拼接出更早一窗：userInput / assistantText / reasoning
        //（项 4 口径更新：reasoning 不再 💭 前缀平铺，渲染为思考折叠块——折叠态仅
        // 「已深度思考」头部摘要，与 FeatureCompletionE2ETests 思考折叠用例同构）
        let loadOlder = element(application, "05-act-load-older")
        XCTAssertTrue(loadOlder.waitForExistence(timeout: 8), "6 行 > 首窗 3 行，应出现向上分页入口")
        loadOlder.tap()
        XCTAssertTrue(waitStaticText(containing: "生成一份六类行渲染基线", in: application,
                                    timeout: 10, "userInput 行应渲染用户气泡文本"))
        XCTAssertTrue(waitStaticText(containing: "替身助手：六类行渲染基线已就绪", in: application,
                                     timeout: 8, "assistantText 行应渲染助手文本"))
        let thinkingHead = element(application, "05-thinking-head")
        XCTAssertTrue(thinkingHead.waitForExistence(timeout: 8),
                      "reasoning 行应渲染思考折叠块头部（项 4：💭 前缀已由折叠块取代）")
        waitLabel(thinkingHead, contains: "已深度思考", timeout: 6,
                  "complete 态 reasoning 行折叠摘要应显示「已深度思考」")
        snap(application, "31-session-six-row-kinds")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "浏览六类行消息流不应产生任何直写/配置写类命令")
    }

    /// 返回上一页（导航栏返回按钮；带存在性检查避免转场期落空）
    private func app_navigationBack(_ application: XCUIApplication) {
        let backButton = application.navigationBars.buttons.firstMatch
        if backButton.waitForExistence(timeout: 4) {
            backButton.tap()
        }
    }
}

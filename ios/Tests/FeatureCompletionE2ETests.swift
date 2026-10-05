import XCTest

/// ZCode Mobile · 四项补全验收 e2e（XCUITest）
///
/// 覆盖本轮四项功能补全的真实链路（既有套件口径延续：替身数据 + 计数器断言 + UI 回执三段闭环）：
/// - 项 1 登录直达会话：OAuth 成功后 O3 页自动运转「发现已配对设备 → 发起连接」状态机——
///   无已配对设备时给出可行动引导（不静默）；注册表已有中继设备时自动对其实发起中继连接
///   （替身 `GET /api/v1/relay/devices` 设备清单/链接契约面 + L1 粘贴链接写入注册表），
///   失败引导携带设备名；已连接真实链路时登录后「开始使用」落回替身真实会话列表。
///   如实标注：中继 WS 端点取自配对链接 host 且固定 wss:443（ConnectURLParser 口径）+
///   TLS，本地替身无法承接该面——「中继连通并进入真实会话列表」的端到端佐证见验收表
///   人工项（`simctl launch -ZCodeRelayLink <现行链接>` + 截图），自动化只覆盖「自动发起」
///   与失败/引导路径；
/// - 项 2 新建会话选项目：替身 server-info workspaces 两项 → 项目选择页呈现候选 →
///   选择后 createSession 携带对应 workspaceId（替身计数断言）；未指定时回退主工作区；
/// - 项 3 流程面板：替身会话行注入行级 plan 行 → 任务拆解卡默认展开、计数 1/3、
///   头部点击折叠/再展开（替身 rowsRange 请求证据）；
/// - 项 4 思考折叠：替身注入 complete 态 reasoning 行 → 默认折叠仅头部摘要、点击展开
///   正文、再点击收起；
/// - 项 5 Composer 执行目标选择器：弹出「云端沙盒 / 我的 Mac」两项（我的 Mac 来自注册表
///   中继设备）、切换选中态回显、跨会话持久化（per-conversation + 全局默认）、切换后发送
///   仍真实到达替身（sendText 计数；目标为 UI+持久化面，v4 下发通道不变——桌面代执行边界）。
///
/// 基础设施：`E2ELoginStubServer`（127.0.0.1 随机端口）setUp 启动 / tearDown 关闭；
/// 等待一律 waitForExistence / XCTNSPredicateExpectation / 有界轮询，不写固定 sleep；
/// 每个用例以 `-ZCodeE2EResetState` 独立冷启动，无顺序依赖；
/// 本文件只做编译级自检，由统一门禁脚本在模拟器上执行。
final class FeatureCompletionE2ETests: XCTestCase {

    /// 替身 server-info workspaces[0]（连接装配与 topic 键；不得移动位次）
    private let mainWorkspacePath = "/Users/e2e/zcode-workspace"
    /// 替身 server-info workspaces[1]（项目选择页第二候选）
    private let labWorkspacePath = "/Users/e2e/zcode-workspace-lab"

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

    /// 等待元素 accessibility label 包含指定文本（胶囊/行 value 等聚合动态文案）
    @discardableResult
    private func waitLabel(_ item: XCUIElement, contains text: String,
                           timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", text), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 等待元素从可访问性树消失（覆盖层切换 / 折叠动画）
    @discardableResult
    private func waitDisappear(_ item: XCUIElement, timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 等待出现 label 包含指定片段的静态文本（替身回执 / 引导卡标题等动态文案）
    @discardableResult
    private func waitStaticText(containing fragment: String, in application: XCUIApplication,
                                timeout: TimeInterval, _ message: String) -> Bool {
        let matched = application.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
        XCTAssertTrue(matched.waitForExistence(timeout: timeout), message)
        return matched.exists
    }

    /// 有界轮询替身侧状态（请求记录 / 命令计数），50ms 步进，超时判失败
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

    /// 短窗等待元素离场（tapUntil 的效果判定用：吸收 0.2s 折叠/收起动画窗口，
    /// 避免验证早于动画完成而误判失败 → 重试点击造成折叠/展开来回切换）
    private func waitGoneQuickly(_ item: XCUIElement, within: TimeInterval = 1.5) -> Bool {
        let deadline = Date().addingTimeInterval(within)
        while Date() < deadline {
            if !item.exists { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return !item.exists
    }

    /// 短窗等待元素出现（同上，展开/收起动画窗口）
    private func waitVisibleQuickly(_ item: XCUIElement, within: TimeInterval = 1.5) -> Bool {
        let deadline = Date().addingTimeInterval(within)
        while Date() < deadline {
            if item.exists { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return item.exists
    }

    /// 带效果验证的稳健点击：转场动画/菜单弹出期 tap 可能落空，未出现预期效果则有界重试。
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

    /// 长链接输入（URL 键盘 typeText 长串存在丢字偶发，套件已知家族）：
    /// 输入后回读校验，不一致则退格清空重输（有界重试）
    @discardableResult
    private func typeLink(_ field: XCUIElement, text: String) -> Bool {
        guard field.waitForExistence(timeout: 8) else { return false }
        for _ in 0..<3 {
            field.tap()
            _ = app.keyboards.firstMatch.waitForExistence(timeout: 2)
            field.typeText(text)
            if (field.value as? String ?? "").hasSuffix(text) { return true }
            field.typeText(String(repeating: "\u{8}", count: text.count + 8))
        }
        return (field.value as? String ?? "").hasSuffix(text)
    }

    /// 关键界面截图附件（keepAlways；验收检查点经 xcresulttool 导出后逐张复核）
    private func snap(_ application: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: application.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// 冷启动（清空凭据态 + 服务器注册表 + OAuth 端点指向替身；可选直开登录/连接流程）
    @discardableResult
    private func launchFresh(openFlow: String? = nil) -> XCUIApplication {
        var arguments = [
            "-ZCodeE2EResetState",
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthClientID", "stub-client-e2e",
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            // 本地化后固定测试语言（中文文案断言稳定）
            "-AppleLanguages", "(zh-Hans)",
        ]
        if let openFlow {
            arguments.append(openFlow)
        }
        app.launchArguments = arguments
        app.launch()
        return app
    }

    /// 会话内二次启动（保留凭据/注册表态：已保存服务器触发冷启动自动重连）
    @discardableResult
    private func relaunch(_ application: XCUIApplication, openFlow: String? = nil) -> XCUIApplication {
        application.terminate()
        var arguments = [
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthClientID", "stub-client-e2e",
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            "-AppleLanguages", "(zh-Hans)",
        ]
        if let openFlow {
            arguments.append(openFlow)
        }
        application.launchArguments = arguments
        application.launch()
        return application
    }

    @discardableResult
    private func typeInto(_ field: XCUIElement, text: String) -> Bool {
        guard field.waitForExistence(timeout: 8) else { return false }
        // 键盘未弹出时 typeText 合成事件落空：循环「tap → 等键盘（短窗）」，键盘一旦
        // 出现立即输入（缩短 tap 与 typeText 之间的窗口，避免表单回流/动画期焦点丢失；
        // 门禁第 1 轮实证 l1-field-token 在回流期帧异常收缩、tap 未建立焦点）
        for _ in 0..<4 {
            guard field.exists, field.isHittable else {
                Thread.sleep(forTimeInterval: 0.3)
                continue
            }
            field.tap()
            if app.keyboards.firstMatch.waitForExistence(timeout: 1.5) {
                field.typeText(text)
                return true
            }
        }
        // 软键盘不可用（硬件键盘连接模拟器）时 tap 后直接输入
        field.tap()
        Thread.sleep(forTimeInterval: 0.3)
        field.typeText(text)
        return true
    }

    /// 设置页 → 添加服务器 → 手动输入页
    private func openManualConnect(_ application: XCUIApplication) {
        let meTab = element(application, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")
        meTab.tap()
        let addRow = element(application, "l4-row-add")
        XCTAssertTrue(addRow.waitForExistence(timeout: 8), "设置页应有「添加服务器」行")
        addRow.tap()
        openManualPage(application)
    }

    /// 连接流程已开（-ZCodeOpenConnectFlow / 添加服务器行）时直达手动输入页
    /// （TabBar 位于 cover 之下，不可再点——RelayLinkE2ETests 同口径）
    private func openManualPage(_ application: XCUIApplication) {
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

    /// 提交手动连接表单：优先键盘工具栏「连接」（键盘弹出时表单按钮可能被遮挡），回退表单提交按钮
    private func submitManualConnect(_ application: XCUIApplication) {
        let keyboardConnect = element(application, "l1-keyboard-connect")
        if keyboardConnect.waitForExistence(timeout: 2) {
            keyboardConnect.tap()
        } else {
            element(application, "l1-submit-connect").tap()
        }
    }

    /// 点击输入框并等待键盘弹出（无键盘不 typeText，避免合成事件落空）
    private func tapAndWaitKeyboard(_ field: XCUIElement, application: XCUIApplication,
                                    timeout: TimeInterval = 10) {
        _ = tapUntil(field, timeout: timeout) {
            application.keyboards.firstMatch.exists
        }
    }

    /// 返回上一页（导航栏返回按钮；带存在性检查避免转场期落空）
    private func app_navigationBack(_ application: XCUIApplication) {
        let backButton = application.navigationBars.buttons.firstMatch
        if backButton.waitForExistence(timeout: 4) {
            backButton.tap()
        }
    }

    /// 打开项目选择页（连接态 projects 异步装载：sheet 刚呈现即点胶囊可能落在加载态/呈现
    /// 时序竞态，内容不随装载完成刷新——门禁实证；取消后重开一次即已装载完成）
    @discardableResult
    private func openProjectPicker(_ application: XCUIApplication, timeout: TimeInterval = 20) -> Bool {
        let pill = element(application, "03-pill-project")
        let noneRow = element(application, "03-picker-none")
        let cancel = element(application, "03-picker-cancel")
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            guard pill.exists, pill.isHittable else {
                Thread.sleep(forTimeInterval: 0.3)
                continue
            }
            pill.tap()
            if noneRow.waitForExistence(timeout: 2.5) { return true }
            // 选择页已呈现但停在加载态（03-picker-cancel 在头部、加载分支外常驻）→ 取消重开
            if cancel.exists {
                cancel.tap()
                Thread.sleep(forTimeInterval: 0.5)
            }
        }
        return noneRow.exists
    }

    /// 打开目标菜单并等待指定候选出现（菜单打开后 trigger 被遮挡不可命中，
    /// 不复用 tapUntil 的 hittable 前置；tap 一次后短轮询候选计数，未弹出则有界重开）
    @discardableResult
    private func openTargetMenu(_ trigger: XCUIElement, candidate: XCUIElementQuery,
                                timeout: TimeInterval = 12) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if candidate.count >= 1 { return true }
            if trigger.exists, trigger.isHittable {
                trigger.tap()
            }
            let presentDeadline = Date().addingTimeInterval(1.5)
            while Date() < presentDeadline, candidate.count == 0 {
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
        return candidate.count >= 1
    }

    /// LAN 直连替身并回到已连接主界面（登录套件 connectAndEnterMain 同口径：
    /// 手动配对 → 替身完成 WS 升级 → 连接流程自动收起 → 冷启动自动重连进主界面）
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

    /// OAuth 全流程（O1 → 替身授权 302 → 交换 → O3 成功页）
    private func performOAuthLogin(_ application: XCUIApplication) {
        let oauthButton = element(application, "o1-btn-oauth")
        XCTAssertTrue(oauthButton.waitForExistence(timeout: 15), "应出现 O1 登录主页")
        oauthButton.tap()
        XCTAssertTrue(element(application, "o3-btn-continue").waitForExistence(timeout: 20),
                      "替身授权回调后应完成交换进入登录成功页")
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到令牌交换请求") {
            stub.requestCount(method: "POST", path: "/api/v1/oauth/token") >= 1
        })
    }

    /// O3「开始使用」：登录 cover 收起（按钮出树即 cover 已关）。
    /// 直达引导卡可能把按钮挤出首屏：不可命中时先上滑揭示再点（滚动感知重试）
    private func tapStartUsing(_ application: XCUIApplication) {
        let button = element(application, "o3-btn-continue")
        XCTAssertTrue(button.waitForExistence(timeout: 8), "登录成功页应有「开始使用」按钮")
        let deadline = Date().addingTimeInterval(20)
        var dismissed = false
        while Date() < deadline, !dismissed {
            guard button.exists else { Thread.sleep(forTimeInterval: 0.3); continue }
            if button.isHittable {
                button.tap()
                dismissed = waitGoneQuickly(button, within: 1.5)
            } else {
                application.swipeUp()
                _ = button.waitForExistence(timeout: 1)
            }
        }
        XCTAssertTrue(dismissed || !button.exists, "点击「开始使用」应收起登录 cover")
        // 登录可能从推入的账户页发起：cover 收起后仍在推入页（TabBar 隐藏），返回一层
        if !element(application, "04-tab-chat").waitForExistence(timeout: 5) {
            app_navigationBack(application)
        }
        XCTAssertTrue(element(application, "04-tab-chat").waitForExistence(timeout: 10),
                      "「开始使用」后应回到主界面")
    }

    /// 测试进程直连替身的 GET 探针（同登录套件 test11 口径，带回执 JSON）
    private func httpGetJSON(_ urlString: String) -> (status: Int, body: [String: Any]?) {
        guard let url = URL(string: urlString) else { return (-1, nil) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 5
        let semaphore = DispatchSemaphore(value: 0)
        var status = -1
        var bodyDict: [String: Any]?
        URLSession.shared.dataTask(with: request) { data, response, _ in
            status = (response as? HTTPURLResponse)?.statusCode ?? -1
            if let data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                bodyDict = json
            }
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 8)
        return (status, bodyDict)
    }

    // MARK: - 项 1a：OAuth 成功后登录直达状态机自动运转（无已配对设备 → 可行动引导，不静默）

    func test01_loginDirectShowsActionableGuideWhenNoPairedDevice() throws {
        let application = launchFresh(openFlow: "-ZCodeOpenLoginFlow")

        performOAuthLogin(application)

        // O3 成功页展示替身用户卡（OAuth 链路真实完成）
        let userCard = element(application, "o3-card-user")
        XCTAssertTrue(userCard.waitForExistence(timeout: 6), "成功页应展示用户卡")
        waitLabel(userCard, contains: "替身用户", timeout: 6, "用户卡应展示替身 displayName")

        // 登录直达状态机已自动执行：无已配对设备（E2E reset 后注册表为空，剪贴板兜底禁用）
        // → 「暂未发现已配对的桌面设备」引导卡 + 两个可行动动作（粘贴链接 / 先去连接）
        XCTAssertTrue(application.staticTexts["暂未发现已配对的桌面设备"].waitForExistence(timeout: 12),
                      "OAuth 成功后应自动尝试发现设备，无设备时展示可行动引导（不静默）")
        XCTAssertTrue(element(application, "o3-act-paste-link").exists, "引导卡应有「粘贴链接连接」动作")
        XCTAssertTrue(element(application, "o3-act-skip-connect").exists, "引导卡应有「先去连接桌面端」动作")
        // 替身侧证据：OAuth 令牌交换真实发生（登录链路经替身）
        XCTAssertGreaterThanOrEqual(stub.requestCount(method: "POST", path: "/api/v1/oauth/token"), 1,
                                    "替身应收到 OAuth 令牌交换")
        snap(application, "40-login-direct-no-device-guide")

        // 「开始使用」后回到主界面（未连接 → 演示底座可用，不被登录流程卡死）
        tapStartUsing(application)
        XCTAssertTrue(element(application, "04-row-c1").waitForExistence(timeout: 10),
                      "无设备直达后主界面应可用（演示列表）")
    }

    // MARK: - 项 1b：注册表已有中继设备 → OAuth 成功后自动对其发起中继连接（失败引导带设备名）

    /// 链路：替身设备清单/链接 API 取回替身形态中继链接 → L1 粘贴提交（连接前置写：
    /// ServerRegistry.upsert relay 设备）→ 回环 443 中继连接失败 → 二次启动完成 OAuth →
    /// O3 自动对已配对设备发起中继连接 → 失败引导携带设备名（证明「自动发起中继连接」
    /// 动作真实执行；连通面为人工佐证项——wss:443 + TLS 本地替身无法承接）。
    func test02_loginAutoInitiatesRelayConnectToPairedDevice() throws {
        // ① 替身设备清单/中继链接契约面（直连探针，鉴权口径同 server-info）
        let denied = httpGetJSON("http://127.0.0.1:\(stub.port)/api/v1/relay/devices?token=wrong-token")
        XCTAssertEqual(denied.status, 401, "错误令牌应被设备清单 API 拒绝")
        let devices = httpGetJSON("http://127.0.0.1:\(stub.port)/api/v1/relay/devices?token=\(stub.pairingToken)")
        XCTAssertEqual(devices.status, 200, "正确令牌应取得设备清单")
        let deviceList = devices.body?["devices"] as? [[String: Any]]
        let relayLink = deviceList?.first?["relayLink"] as? String
        XCTAssertEqual(deviceList?.first?["name"] as? String, "E2E-Relay-Mac",
                       "设备清单应携带桌面机器名")
        XCTAssertTrue(relayLink?.contains("/remote/v4?sid=") == true
            && relayLink?.contains("hash=") == true
            && relayLink?.contains("name=E2E-Relay-Mac") == true,
            "设备清单应携带 /remote/v4 中继配对链接（sid/hash/name 形态）；实际：\(relayLink ?? "nil")")
        XCTAssertEqual(stub.relayDeviceListRequests, 1,
                       "设备清单 API 应记录成功请求（401 拒绝经鉴权面前置，不计数）")

        // ② Launch 1：L1 粘贴替身中继链接 → 解析识别 + 连接前置写入注册表 → 回环失败
        _ = launchFresh(openFlow: "-ZCodeOpenConnectFlow")
        openManualPage(app)
        XCTAssertTrue(typeLink(element(app, "l1-field-host"), text: stub.stubRelayLink),
                      "地址栏应可输入替身中继链接（回读校验一致）")
        submitManualConnect(app)
        // 中继分支被拦截执行：解析成功提示（携带链接 name= 机器名）+ L3 失败态（回环 443 拒连）
        XCTAssertTrue(app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", "已识别云中继配对链接 · E2E-Relay-Mac"))
            .firstMatch.waitForExistence(timeout: 10),
                      "解析成功提示应携带配对链接机器名")
        XCTAssertTrue(element(app, "l3-btn-retry").waitForExistence(timeout: 30),
                      "中继链接应真实发起中继连接并进入失败态（回环 443 拒连）")

        // ③ Launch 2（保留注册表）：OAuth 登录成功 → O3 自动对已配对设备发起中继连接
        relaunch(app, openFlow: "-ZCodeOpenLoginFlow")
        performOAuthLogin(app)
        // 自动连接失败引导携带设备名：仅当「发现了已配对中继设备并真实发起过连接」才会出现
        // （无设备时为「暂未发现已配对的桌面设备」引导，两者互斥可辨）；失败消息自带
        // O3 状态诊断（互斥引导在场性 + 连接中卡片在场性），便于门禁轮次定位
        let failedGuideShown = app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", "连接 E2E-Relay-Mac 失败"))
            .firstMatch.waitForExistence(timeout: 60)
        XCTAssertTrue(failedGuideShown,
                      """
                      OAuth 成功后应自动对已配对中继设备发起连接，失败引导应携带设备名
                      （回环 443 拒连 + App 内置 2s/4s 退避重试）。
                      诊断：无设备引导在场=\(app.staticTexts["暂未发现已配对的桌面设备"].exists)，
                      连接中卡片在场=\(element(app, "o3-card-autolink").exists)，
                      已识别提示在场=\(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已识别云中继配对链接")).firstMatch.exists)
                      """)
        XCTAssertTrue(element(app, "o3-act-paste-link").exists, "失败引导应保留「粘贴链接连接」兜底动作")
        snap(app, "41-login-direct-relay-failed-guide")

        // ④ 「开始使用」回主界面（连接失败回演示底座，界面可用不留半开连接）
        tapStartUsing(app)
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "直达失败后主界面应回演示底座可用")
    }

    // MARK: - 项 1c：OAuth 成功后「开始使用」落回真实（替身）会话列表（已连接链路不中断）

    func test03_loginLandsInRealStubSessionList() throws {
        let application = launchFresh()

        // 先建立真实链路（LAN 直连替身，连接态装配远端 Store）
        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: stub.pairingToken)
        submitManualConnect(application)
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 15),
                      "连接成功后列表应呈现替身会话行")

        // 设置页账户区发起 OAuth 登录（未登录卡 → 登录流程）
        element(application, "12-tab-me").tap()
        let accountRow = element(application, "l4-row-account")
        XCTAssertTrue(accountRow.waitForExistence(timeout: 8), "设置页应有账户行")
        accountRow.tap()
        let loginCard = element(application, "l4-b-card-login")
        XCTAssertTrue(loginCard.waitForExistence(timeout: 8), "账户页应有未登录卡")
        let loginButton = application.buttons["使用 Z.ai 账号登录"].firstMatch
        XCTAssertTrue(loginButton.waitForExistence(timeout: 6), "未登录卡应提供登录动作")
        loginButton.tap()

        // OAuth 全流程成功（替身授权 + 交换）
        performOAuthLogin(application)
        // 登录直达状态机运转：注册表无中继设备（LAN 配对不属中继设备）→ 引导卡，且
        // 不打断既有真实连接（无设备直达为纯 UI 面）
        XCTAssertTrue(application.staticTexts["暂未发现已配对的桌面设备"].waitForExistence(timeout: 12),
                      "无中继设备时登录直达应展示引导（既有连接不受影响）")
        snap(application, "42-login-direct-over-connected")

        // 「开始使用」→ 落回的是真实（替身）会话列表：数据源仍「已连接」、服务器行替身名、
        // 替身会话行在场（未回退演示数据）
        tapStartUsing(application)
        let footDataSource = element(application, "12-foot-data-source")
        XCTAssertTrue(footDataSource.waitForExistence(timeout: 8), "主界面应有数据源脚标")
        waitLabel(footDataSource, contains: "已连接", timeout: 6, "登录后数据源应保持实时数据")
        let serverRow = element(application, "l4-row-server")
        XCTAssertTrue(serverRow.waitForExistence(timeout: 8), "设置页应有服务器行")
        waitLabel(serverRow, contains: "E2E Stub Desktop", timeout: 10,
                  "服务器名应来自替身 server-info")
        element(application, "04-tab-chat").tap()
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 12),
                      "登录完成后应落在真实（替身）会话列表")
        waitDisappear(element(application, "04-row-c1"), timeout: 6,
                      "真实链路在场时不应回退演示数据（mock 置顶行应离场）")
        // 替身侧证据：登录直达全程未破坏 v4 会话链路（sessions-index 快照已下发）
        waitUntil(timeout: 6, "替身应已下发 sessions-index 快照") { stub.sessionsIndexEventFires >= 1 }
    }

    // MARK: - 项 2：新建会话选项目 → createSession 携带对应 workspace（替身计数断言）

    func test04_newConversationProjectPickerCarriesWorkspaceOnCreateSession() throws {
        let application = connectAndEnterMain(launchFresh())
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 15),
                      "重连后应呈现替身快照会话行")

        // ① 新建会话 Sheet → 项目选择页呈现替身 workspaces 两项（先清除上次选择，
        // 消除 UserDefaults 跨运行残留，保证标签检索唯一命中选择页行）
        element(application, "04-tab-chat").tap()
        let newButton = element(application, "04-act-new")
        XCTAssertTrue(newButton.waitForExistence(timeout: 8), "会话页应有新建入口")
        newButton.tap()
        XCTAssertTrue(element(application, "03-input-title").waitForExistence(timeout: 8),
                      "新建 Sheet 应弹出（连接态完整表单）")
        // 项目选择页：不指定（纯对话）行 + 项目候选（主工作区 / 实验项目，标签检索不受分组序影响）
        XCTAssertTrue(openProjectPicker(application), "点击项目胶囊应弹出项目选择页")
        element(application, "03-picker-none").tap()
        waitLabel(element(application, "03-pill-project"), contains: "未绑定", timeout: 6,
                  "清除后项目胶囊应显示未绑定")
        XCTAssertTrue(openProjectPicker(application), "再次点击项目胶囊应弹出项目选择页")
        snap(application, "43-project-picker")

        let mainRow = application.buttons
            .containing(NSPredicate(format: "label CONTAINS %@", "e2e 主工作区")).firstMatch
        XCTAssertTrue(mainRow.waitForExistence(timeout: 10),
                      "项目选择页应呈现替身 server-info workspaces[0]（e2e 主工作区）")
        let labRow = application.buttons
            .containing(NSPredicate(format: "label CONTAINS %@", "zcode-workspace-lab")).firstMatch
        XCTAssertTrue(labRow.waitForExistence(timeout: 10),
                      "项目选择页应呈现替身 workspaces[1]（e2e 实验项目 /Users/e2e/zcode-workspace-lab）")

        // ② 选择实验项目 → 胶囊与工作目录行回显
        XCTAssertTrue(tapUntil(labRow, timeout: 8) {
            !element(application, "03-picker-none").exists
        }, "点击项目行应回到新建 Sheet")
        waitLabel(element(application, "03-pill-project"), contains: "e2e 实验项目", timeout: 6,
                  "项目胶囊应回显所选项目名")
        waitLabel(element(application, "03-row-directory"), contains: labWorkspacePath, timeout: 6,
                  "工作目录行应回显所选项目路径")

        // ③ 带首条指令提交 → createSession 携带所选 workspaceId（替身计数断言）
        let titleInput = element(application, "03-input-title")
        tapAndWaitKeyboard(titleInput, application: application)
        titleInput.typeText("project-layer-e2e")
        element(application, "03-submit-start").tap()
        XCTAssertTrue(waitUntil(timeout: 12, "替身应收到携带实验项目 workspaceId 的 createSession") {
            stub.lastCreateSessionWorkspaceId == labWorkspacePath
        }, "createSession 应携带所选项目 workspaceId；实际序列=\(stub.createSessionWorkspaceIds)")
        XCTAssertGreaterThanOrEqual(stub.createSessionWithFirstInputCount, 1,
                                    "带标题提交应携带 firstInput（桌面开跑形态）")
        XCTAssertTrue(waitStaticText(containing: "替身助手：首条指令已收到", in: application,
                                     timeout: 15, "createSession 回执应推入会话详情"))
        app_navigationBack(application)

        // ④ 未指定项目提交（草稿形态）→ workspaceId 回退连接装配的主工作区
        XCTAssertTrue(tapUntil(element(application, "04-act-new"), timeout: 8) {
            element(application, "03-input-title").waitForExistence(timeout: 5)
        }, "再次新建应弹出 Sheet")
        XCTAssertTrue(openProjectPicker(application), "项目选择页应弹出")
        element(application, "03-picker-none").tap()
        waitLabel(element(application, "03-pill-project"), contains: "未绑定", timeout: 6,
                  "选择「不指定」后项目胶囊应显示未绑定")
        element(application, "03-submit-start").tap()
        XCTAssertTrue(waitUntil(timeout: 12, "未指定项目时 createSession 应回退主工作区 workspaceId") {
            stub.lastCreateSessionWorkspaceId == mainWorkspacePath
        }, "未绑定时 createSession 应回退连接装配的 workspace；实际序列=\(stub.createSessionWorkspaceIds)")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "项目选择与创建会话全程不应产生任何直写/配置写类命令")
    }

    // MARK: - 项 3：流程面板——行级 plan 行 → 任务拆解卡计数 / 折叠 / 展开

    func test05_planTodoPanelRendersCountCollapseExpand() throws {
        let application = connectAndEnterMain(launchFresh())
        let planRow = element(application, "04-row-sess-e2e-plan")
        XCTAssertTrue(planRow.waitForExistence(timeout: 15),
                      "重连后列表应呈现流程面板投影会话（sess-e2e-plan）")
        XCTAssertTrue(tapUntil(planRow, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "点击会话行应推入详情")

        // 替身侧证据：plan 行经 conversationRowsRangeV4 历史分页真实到达
        XCTAssertTrue(waitUntil(timeout: 10, "替身应收到 sess-e2e-plan 的历史行请求") {
            stub.rowsRangeRequests.contains { $0.sessionId == "sess-e2e-plan" }
        })

        // 任务拆解卡渲染：计数 1/3（1 完成 / 3 步），非全完成默认展开
        let card = element(application, "05-todocard")
        XCTAssertTrue(card.waitForExistence(timeout: 12), "plan 行应渲染任务拆解卡（流程面板）")
        XCTAssertTrue(application.staticTexts["1/3"].waitForExistence(timeout: 6),
                      "面板头部应显示完成计数 1/3")
        XCTAssertTrue(application.staticTexts["梳理登录超时复现路径"].exists,
                      "已完成步骤（划线）应上屏")
        XCTAssertTrue(application.staticTexts["修补会话重连竞态"].exists,
                      "进行中步骤（蓝框 spinner）应上屏")
        XCTAssertTrue(application.staticTexts["补回归测试并归档"].exists,
                      "待办步骤（空心圆）应上屏")
        snap(application, "44-plan-todo-panel-expanded")

        // 折叠：头部点击 → 步骤行离场（仅剩头部摘要；效果判定带动画观察窗）
        let head = element(application, "05-todocard-head")
        let row0 = element(application, "05-todocard-row-0")
        XCTAssertTrue(tapUntil(head, timeout: 8) { self.waitGoneQuickly(row0) },
                      "点击面板头部应折叠步骤列表")
        XCTAssertTrue(waitGoneQuickly(application.staticTexts["修补会话重连竞态"], within: 6),
                      "折叠后步骤标题应离场")

        // 再展开：头部点击 → 步骤行恢复
        XCTAssertTrue(tapUntil(head, timeout: 8) { self.waitVisibleQuickly(row0) },
                      "再次点击头部应展开步骤列表")
        XCTAssertTrue(application.staticTexts["修补会话重连竞态"].waitForExistence(timeout: 6),
                      "展开后进行中步骤标题应恢复")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "流程面板浏览与折叠交互不应产生任何直写/配置写类命令")
    }

    // MARK: - 项 4：思考折叠——complete 态 reasoning 行默认折叠 + 展开交互

    func test06_reasoningCollapsesByDefaultAndExpandsOnTap() throws {
        let application = connectAndEnterMain(launchFresh())
        let thinkRow = element(application, "04-row-sess-e2e-think")
        XCTAssertTrue(thinkRow.waitForExistence(timeout: 15),
                      "重连后列表应呈现思考折叠投影会话（sess-e2e-think）")
        XCTAssertTrue(tapUntil(thinkRow, timeout: 12) {
            element(application, "05-composer-input").exists
        }, "点击会话行应推入详情")

        // 默认折叠：仅「已深度思考」头部摘要（历史行首见即 complete，无流式计时）
        let head = element(application, "05-thinking-head")
        XCTAssertTrue(head.waitForExistence(timeout: 12),
                      "reasoning 行应渲染思考折叠块头部")
        waitLabel(head, contains: "已深度思考", timeout: 6,
                  "complete 态折叠摘要应显示「已深度思考」")
        XCTAssertFalse(element(application, "05-thinking-body").exists,
                       "默认折叠态不应渲染思考正文")
        XCTAssertTrue(waitUntil(timeout: 3, "正文应保持离场（折叠态稳定）") {
            !element(application, "05-thinking-body").exists
        })
        snap(application, "45-thinking-collapsed")

        // 展开：头部点击 → 正文上屏（替身 reasoning 行文本；效果判定带动画观察窗）
        let body = element(application, "05-thinking-body")
        XCTAssertTrue(tapUntil(head, timeout: 8) { self.waitVisibleQuickly(body) },
                      "点击折叠头部应展开思考正文")
        XCTAssertTrue(waitStaticText(containing: "退避基数 500ms", in: application,
                                     timeout: 8, "展开后应显示替身 reasoning 行正文"))

        // 再折叠：头部点击 → 正文离场
        XCTAssertTrue(tapUntil(head, timeout: 8) { self.waitGoneQuickly(body) },
                      "再次点击头部应收起思考正文")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "思考折叠交互不应产生任何直写/配置写类命令")
    }

    // MARK: - 项 5：Composer 执行目标选择器——两项候选 / 选中态持久化 / 切换后发送到达替身

    /// 「我的 Mac」候选项来自注册表中继设备（DeviceDirectory 口径）：先 LAN 连接替身，
    /// 再经 L1 粘贴替身中继链接失败（连接前置写注册表）→ 重启后自动重连 LAN（选中服务器），
    /// 目标菜单即含「云端沙盒 / E2E-Relay-Mac（我的 Mac）」两项。
    func test07_composerTargetSelectorItemsPersistedAndSendReachesStub() throws {
        // ① Launch 1（reset）：建立 LAN 真实链路（选中态落库）→ L1 粘贴中继链接失败（注册表获得 relay 设备）
        _ = launchFresh()
        openManualConnect(app)
        fillManualConnect(app, host: "http://127.0.0.1:\(stub.port)", token: stub.pairingToken)
        submitManualConnect(app)
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(waitDisappear(element(app, "l1-submit-connect"), timeout: 15,
                                    "LAN 连接成功后连接流程应自动收起（回到设置 Tab）"))
        XCTAssertTrue(element(app, "l4-row-add").waitForExistence(timeout: 10),
                      "连接流程收起后应回到主界面（设置 Tab）")
        openManualConnect(app)
        XCTAssertTrue(typeLink(element(app, "l1-field-host"), text: stub.stubRelayLink),
                      "地址栏应可输入替身中继链接（回读校验一致）")
        submitManualConnect(app)
        XCTAssertTrue(element(app, "l3-btn-retry").waitForExistence(timeout: 30),
                      "中继链接应真实发起连接并失败（回环 443 拒连；注册表已 upsert relay 设备）")

        // ② Launch 2：自动重连 LAN（选中服务器），进入替身会话详情
        relaunch(app)
        let row1 = element(app, "04-row-sess-e2e-1")
        XCTAssertTrue(row1.waitForExistence(timeout: 15), "重启后应自动重连并呈现替身会话行")
        XCTAssertTrue(tapUntil(row1, timeout: 12) {
            element(app, "05-composer-input").exists
        }, "点击会话行应推入详情")

        // ③ 目标选择器弹出两项：云端沙盒 + 我的 Mac（E2E-Relay-Mac）。
        // 菜单项以 label 精确匹配（菜单 identifier 不保证透出，不做存在性断言）；
        // 点击时刻胶囊标签尚未等于候选名（初始云端沙盒），firstMatch 即菜单项；
        // 「云端沙盒」命中数 ≥2（胶囊 + 菜单项）证明两项候选同屏
        let trigger = element(app, "05-act-target")
        XCTAssertTrue(trigger.waitForExistence(timeout: 8), "Composer 应常驻执行目标选择器")
        let macItems = app.buttons.matching(NSPredicate(format: "label == 'E2E-Relay-Mac'"))
        let cloudItems = app.buttons.matching(NSPredicate(format: "label == '云端沙盒'"))
        XCTAssertTrue(openTargetMenu(trigger, candidate: macItems),
                      "点击目标选择器应弹出设备清单菜单（含我的 Mac 候选）")
        XCTAssertGreaterThanOrEqual(cloudItems.count, 2,
                                    "菜单打开后应同时存在胶囊与「云端沙盒」菜单项两项命中")
        snap(app, "46-composer-target-menu")
        macItems.firstMatch.tap()
        waitLabel(trigger, contains: "E2E-Relay-Mac", timeout: 8,
                  "点击「我的 Mac」应选中（胶囊回显设备名，菜单收起）")

        // ④ 切换后发送仍真实到达替身（替身计数断言；目标为 UI+持久化面，
        //    v4 下发通道不变——客户端发命令、桌面代执行边界）
        let composerInput = element(app, "05-composer-input")
        tapAndWaitKeyboard(composerInput, application: app)
        composerInput.typeText("target-mac-send-e2e")
        let sendCountBefore = stub.sendTextCount
        element(app, "05-composer-send").tap()
        XCTAssertTrue(waitUntil(timeout: 10, "切换目标后发送应真实到达替身（sendText 计数 >0）") {
            stub.sendTextCount >= sendCountBefore + 1
        }, "sendText 应到达替身；实测 sendTextCount=\(stub.sendTextCount)")

        // ⑤ 选中态持久化：返回再进入同会话（per-conversation）与另一会话（全局默认回退）均回显 Mac
        app_navigationBack(app)
        XCTAssertTrue(tapUntil(row1, timeout: 12) {
            element(app, "05-composer-input").exists
        }, "再次进入 sess-e2e-1 应推入详情")
        waitLabel(element(app, "05-act-target"), contains: "E2E-Relay-Mac", timeout: 8,
                  "同会话再次进入应恢复所选目标（per-conversation 持久化）")
        app_navigationBack(app)
        let row2 = element(app, "04-row-sess-e2e-2")
        XCTAssertTrue(row2.waitForExistence(timeout: 10), "列表应呈现 sess-e2e-2")
        XCTAssertTrue(tapUntil(row2, timeout: 12) {
            element(app, "05-composer-input").exists
        }, "进入 sess-e2e-2 应推入详情")
        waitLabel(element(app, "05-act-target"), contains: "E2E-Relay-Mac", timeout: 8,
                  "新会话应回退全局默认目标（上次选择持久化）")

        // ⑥ 切回云端沙盒（菜单往返可重复）→ 发送链路依旧到达替身
        //（此刻胶囊为 E2E-Relay-Mac，「云端沙盒」firstMatch 即菜单项）
        let trigger2 = element(app, "05-act-target")
        XCTAssertTrue(openTargetMenu(trigger2, candidate: cloudItems),
                      "目标菜单应再次弹出（含云端沙盒候选）")
        cloudItems.firstMatch.tap()
        waitLabel(trigger2, contains: "云端沙盒", timeout: 8,
                  "点击「云端沙盒」应选中（胶囊回显，菜单收起）")
        tapAndWaitKeyboard(composerInput, application: app)
        composerInput.typeText("target-cloud-send-e2e")
        let sendCountBefore2 = stub.sendTextCount
        element(app, "05-composer-send").tap()
        XCTAssertTrue(waitUntil(timeout: 10, "切回云端目标后发送应依旧到达替身") {
            stub.sendTextCount >= sendCountBefore2 + 1
        })
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "目标切换与发送全程不应产生任何直写/配置写类命令")
    }
}

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
    /// 输入后回读校验，不一致则退格清空重输（有界重试）。
    /// 先退格清空：二次进入手动页会回填上次的 LAN 地址，直接追加会让字段变成
    /// 「127.0.0.1:51434https://…」——hasSuffix 校验被骗过而解析永远失败（门禁实测），
    /// 故清空后输入并以整串相等作回读校验。
    @discardableResult
    private func typeLink(_ field: XCUIElement, text: String) -> Bool {
        guard field.waitForExistence(timeout: 8) else { return false }
        for _ in 0..<3 {
            field.tap()
            _ = app.keyboards.firstMatch.waitForExistence(timeout: 2)
            field.typeText(String(repeating: "\u{8}", count: 120))
            field.typeText(text)
            if let value = field.value as? String, value == text { return true }
            field.typeText(String(repeating: "\u{8}", count: text.count + 8))
        }
        return (field.value as? String) == text
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
    private func launchFresh(openFlow: String? = nil, extraArguments: [String] = []) -> XCUIApplication {
        var arguments = [
            "-ZCodeE2EResetState",
            // E2E 演示开关（对齐修复后 Mock 仅测试用例允许装配；连接成功后换真实 Store）
            "-ZCodeDemoData",
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
        arguments.append(contentsOf: extraArguments)
        app.launchArguments = arguments
        app.launch()
        return app
    }

    /// 会话内二次启动（保留凭据/注册表态：已保存服务器触发冷启动自动重连）
    @discardableResult
    private func relaunch(_ application: XCUIApplication, openFlow: String? = nil,
                          extraArguments: [String] = []) -> XCUIApplication {
        application.terminate()
        var arguments = [
            "-ZCodeDemoData",
            "-ZCodeOAuthZaiOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthTokenOrigin", "http://127.0.0.1:\(stub.port)",
            "-ZCodeOAuthClientID", "stub-client-e2e",
            "-ZCodeOAuthRedirectURI", "http://127.0.0.1:\(stub.port)/cn/share/callback",
            "-AppleLanguages", "(zh-Hans)",
        ]
        if let openFlow {
            arguments.append(openFlow)
        }
        arguments.append(contentsOf: extraArguments)
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

    /// 滚动到目标行可点（列表长于视口时 tapUntil 的 isHittable 守卫会永不满足——
    /// test06 门禁实证：分组展开后 think 行沉到折叠线以下，12s 内零次合成 tap）。
    /// 上滑找行（列表向下滚动），命中即停；找不到返回 false 由调用方断言。
    @discardableResult
    /// 元素帧落在应用可见界内（带 40pt 边距）才算「在屏」：SwiftUI List 对已物化
    /// 但滚出视口的行会报 exists/isHittable=true 的陈旧帧——只查 isHittable 会
    /// 跳过滚动直接点空，列表全程不滚（test07 门禁视频实证）
    private func isOnScreen(_ el: XCUIElement, application: XCUIApplication) -> Bool {
        guard el.exists else { return false }
        let f = el.frame, b = application.frame
        return f.midX >= b.minX && f.midX <= b.maxX
            && f.midY >= b.minY + 40 && f.midY <= b.maxY - 40
    }

    /// 坐标拖拽滚动（起滑点参数化）：application.swipe* 从屏幕中心起滑，详情页
    /// 常驻审批卡铺在中下部会吞掉手势、列表纹丝不动（test05 门禁视频实证）——
    /// 起滑点固定在内容区上/下沿安全带，拖拽手势归属由初始触点决定
    private func dragScroll(_ application: XCUIApplication, fromDY: CGFloat, toDY: CGFloat) {
        let start = application.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: fromDY))
        let end = application.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: toDY))
        start.press(forDuration: 0.06, thenDragTo: end)
    }

    /// 滚动查找直到目标真正在屏且可命中：目标在视口下方向上拖、整体在上方向下拖；
    /// 不在 a11y 树时先上拖（多数场景目标在下方）。scrollElement 提供时对该元素
    /// 本身 swipe（详情页消息区：frame 被 safeAreaInset 缩短到常驻审批卡上方的
    /// 带内，元素中心起滑不会被卡吞手势——坐标拖拽被卡吞、列表纹丝不动的
    /// test05 门禁视频实证兜底）。
    private func scrollToHittable(_ target: XCUIElement, application: XCUIApplication,
                                  scrollElement: XCUIElement? = nil,
                                  maxSwipes: Int = 8) -> Bool {
        let deadline = Date().addingTimeInterval(20)
        for _ in 0..<maxSwipes where Date() < deadline {
            if isOnScreen(target, application: application), target.isHittable { return true }
            let upward: Bool
            if target.exists, target.frame.maxY < application.frame.minY + 80 {
                upward = false   // 目标整体在视口上方 → 向下拖揭示
            } else {
                upward = true    // 目标在下方（或不在树中）→ 向上拖揭示
            }
            if let scroll = scrollElement, scroll.exists {
                if upward { scroll.swipeUp(velocity: .fast) } else { scroll.swipeDown(velocity: .fast) }
            } else if upward {
                dragScroll(application, fromDY: 0.66, toDY: 0.26)
            } else {
                dragScroll(application, fromDY: 0.24, toDY: 0.64)
            }
            Thread.sleep(forTimeInterval: 0.4)
            // 诊断：滚动失败定位（目标帧应逐轮变化；不变化=手势未作用于滚动容器）
            if !isOnScreen(target, application: application) {
                let f = target.exists ? NSCoder.string(for: target.frame) : "n/a"
                let sf = (scrollElement?.exists ?? false)
                    ? NSCoder.string(for: scrollElement!.frame) : "nil"
                NSLog("scrollDiag exists=\(target.exists) frame=\(f) hittable=\(target.isHittable) up=\(upward) scrollEl=\(sf)")
            }
        }
        return isOnScreen(target, application: application) && target.isHittable
    }

    /// 列表行 → 会话详情（tapUntil 不胜任的导航场景：①tap 后详情 push 有过渡窗，
    /// 立即 verify 必假、下一轮行已出树只能干等超时——test07 门禁实证；②membership
    /// 重排会重置列表滚动吞掉 tap，行可能需要重滚）。先查是否已在详情，再滚动到
    /// 行、点击，1.2s 过渡窗确认 composer 出树；未进详情则有界重试。
    @discardableResult
    private func openConversation(_ row: XCUIElement, application: XCUIApplication,
                                  timeout: TimeInterval = 18) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element(application, "05-composer-input").exists { return true }
            scrollToHittable(row, application: application, maxSwipes: 4)
            if isOnScreen(row, application: application), row.isHittable {
                row.tap()
            }
            let settle = Date().addingTimeInterval(1.2)
            while Date() < settle {
                if element(application, "05-composer-input").exists { return true }
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
        return element(application, "05-composer-input").exists
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
        var labelsDump = ""
        while Date() < deadline {
            if candidate.count >= 1 { return true }
            if trigger.exists, trigger.isHittable {
                trigger.tap()
            }
            let presentDeadline = Date().addingTimeInterval(1.5)
            while Date() < presentDeadline, candidate.count == 0 {
                Thread.sleep(forTimeInterval: 0.1)
            }
            // 诊断：菜单打开/未开时都可转储当前按钮 label 集合（判定「菜单未弹出」
            // 还是「弹出但候选缺失/label 不符」）
            labelsDump = app.buttons.allElementsBoundByIndex.prefix(18)
                .map { "\($0.label)|\($0.identifier)" }.joined(separator: " ; ")
        }
        if candidate.count == 0 {
            NSLog("openTargetMenu 诊断 labels=\(labelsDump)")
        }
        return candidate.count >= 1
    }

    /// LAN 直连替身并回到已连接主界面（登录套件 connectAndEnterMain 同口径：
    /// 手动配对 → 替身完成 WS 升级 → 连接流程自动收起 → 冷启动自动重连进主界面）
    @discardableResult
    private func connectAndEnterMain(_ application: XCUIApplication,
                                     extraArguments: [String] = []) -> XCUIApplication {
        openManualConnect(application)
        fillManualConnect(application, host: "http://127.0.0.1:\(stub.port)", token: stub.pairingToken)
        submitManualConnect(application)
        XCTAssertTrue(waitUntil(timeout: 25, "替身应接受令牌并完成 WS 升级") {
            stub.lastAcceptedPairingToken == stub.pairingToken && stub.websocketUpgrades >= 1
        })
        XCTAssertTrue(waitDisappear(element(application, "l1-submit-connect"), timeout: 15,
                                    "连接成功后连接流程应自动收起"))
        relaunch(application, extraArguments: extraArguments)
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
        // 连接流程收起后停在打开它的设置 Tab（test07 断言「回到设置 Tab」为预期行为）；
        // 会话行在会话 Tab 的视图树内——先切到会话 Tab 再断言替身行
        element(application, "04-tab-chat").tap()
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

        // 「开始使用」→ 落回的是真实（替身）会话列表：页脚为执行边界口径、服务器行替身名、
        // 替身会话行在场（未停留演示数据）
        tapStartUsing(application)
        let footDataSource = element(application, "12-foot-data-source")
        XCTAssertTrue(footDataSource.waitForExistence(timeout: 8), "主界面应有数据源脚标")
        waitLabel(footDataSource, contains: "指令经桌面端执行", timeout: 6, "登录后数据源应保持真实连接口径")
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
        scrollToHittable(planRow, application: application)
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

        // 收起前先驳回计划：常驻审批卡把消息区压成极窄带（~15% 屏高），坐标拖拽/
        // 元素 swipe 全部揭不出头部（test05 五连失败视频实证）；驳回后消息区恢复
        // 全高、头部直接可见。顺带回归 resolveInteraction 链（决议 → 卡片撤下）
        let rejectButton = element(application, "05-act-plan-reject")
        XCTAssertTrue(rejectButton.waitForExistence(timeout: 8), "计划审批卡应有驳回入口")
        XCTAssertTrue(tapUntil(rejectButton, timeout: 8) {
            self.waitGoneQuickly(element(application, "05-approval-card"), within: 4)
        }, "驳回计划后审批卡应撤下")
        XCTAssertTrue(waitGoneQuickly(element(application, "05-act-plan-reject"), within: 4),
                      "驳回按钮应随卡撤下")

        // 折叠：头部点击 → 步骤行离场（仅剩头部摘要；效果判定带动画观察窗）
        let head = element(application, "05-todocard-head")
        let row0 = element(application, "05-todocard-row-0")
        XCTAssertTrue(scrollToHittable(head, application: application,
                                       scrollElement: element(application, "05-message-scroll")),
                      "任务拆解卡头部应可滚动到可见")
        XCTAssertTrue(tapUntil(head, timeout: 8) { self.waitGoneQuickly(row0) },
                      "点击面板头部应折叠步骤列表")
        XCTAssertTrue(waitGoneQuickly(application.staticTexts["修补会话重连竞态"], within: 6),
                      "折叠后步骤标题应离场")

        // 再展开：头部点击 → 步骤行恢复
        XCTAssertTrue(scrollToHittable(head, application: application,
                                       scrollElement: element(application, "05-message-scroll")),
                      "折叠后头部应仍可命中")
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
        // 行可能沉到视口折叠线以下（分组展开后列表长于视口——tapUntil 的 isHittable
        // 守卫会永不满足，test06 门禁实证），先滚动到可点
        scrollToHittable(thinkRow, application: application)
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
        XCTAssertTrue(openConversation(row1, application: app), "点击会话行应推入详情")

        // ③ 目标选择器弹出两项：云端沙盒 + 我的 Mac（E2E-Relay-Mac）。
        // 菜单项以 label 精确匹配（菜单 identifier 不保证透出，不做存在性断言）；
        // 点击时刻胶囊标签尚未等于候选名（初始云端沙盒），firstMatch 即菜单项；
        // 「云端沙盒」命中数 ≥2（胶囊 + 菜单项）证明两项候选同屏。
        // trigger 用 BEGINSWITH：SwiftUI Menu 历史上会把 label identifier 逐层拼接
        // （05-act-target-05-act-target-…），精确匹配可能命中惰性子元素
        let trigger = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH '05-act-target'")).firstMatch
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
        //    v4 下发通道不变——客户端发命令、桌面代执行边界）。
        //    菜单选中后的收起动画可能吞掉首次 send tap：有界重发（文本保持在
        //    输入框，无需重敲），以替身 sendText 计数增长为送达判据
        let composerInput = element(app, "05-composer-input")
        tapAndWaitKeyboard(composerInput, application: app)
        composerInput.typeText("target-mac-send-e2e")
        let sendCountBefore = stub.sendTextCount
        let sendButton = element(app, "05-composer-send")
        var delivered = false
        for _ in 0..<4 where !delivered {
            if sendButton.exists, sendButton.isHittable {
                sendButton.tap()
            }
            let deadline = Date().addingTimeInterval(2.5)
            while Date() < deadline {
                if stub.sendTextCount >= sendCountBefore + 1 { delivered = true; break }
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
        XCTAssertTrue(delivered, "切换目标后发送应真实到达替身（sendText 计数 >0）；实测 sendTextCount=\(stub.sendTextCount)")

        // ⑤ 选中态持久化：返回再进入同会话（per-conversation）与另一会话（全局默认回退）均回显 Mac。
        // back 后等 composer 离场再继续：转场未完时 openConversation 首查 composer
        // 会误判「已在详情」提前返回（test07 门禁视频实证：首次 back tap 落空时
        // 后续断言全在详情里空转）
        app_navigationBack(app)
        waitGoneQuickly(element(app, "05-composer-input"), within: 4)
        XCTAssertTrue(openConversation(row1, application: app),
                      "再次进入 sess-e2e-1 应推入详情")
        waitLabel(element(app, "05-act-target"), contains: "E2E-Relay-Mac", timeout: 8,
                  "同会话再次进入应恢复所选目标（per-conversation 持久化）")
        app_navigationBack(app)
        waitGoneQuickly(element(app, "05-composer-input"), within: 4)
        let row2 = element(app, "04-row-sess-e2e-2")
        XCTAssertTrue(row2.waitForExistence(timeout: 10), "列表应呈现 sess-e2e-2")
        XCTAssertTrue(openConversation(row2, application: app),
                      "进入 sess-e2e-2 应推入详情（membership 重排/滚动重置由 openConversation 吸收）")
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

    // MARK: - 项 8：新建会话模型/思考等级选择 → createSession firstInput.modelSelection

    /// 连接态新建 Sheet 的「模型」「思考等级」两行可点（getView 投影驱动），
    /// 选中的模型/思考档随 firstInput.modelSelection 下发（web U7e 形态：
    /// {providerId, modelId, options:{reasoningLevel}}）。
    func test08_newConversationModelSelectionCarriedInFirstInput() throws {
        let application = connectAndEnterMain(launchFresh())
        XCTAssertTrue(element(application, "04-row-sess-e2e-1").waitForExistence(timeout: 15),
                      "连接后应呈现替身会话列表")

        // ① 打开新建 Sheet → 模型/思考等级两行出现（restoreContext 拉替身 getView）
        element(application, "04-tab-chat").tap()
        let newButton = element(application, "04-act-new")
        XCTAssertTrue(newButton.waitForExistence(timeout: 8), "会话页应有新建入口")
        newButton.tap()
        XCTAssertTrue(element(application, "03-input-title").waitForExistence(timeout: 8),
                      "新建 Sheet 应弹出（连接态完整表单）")
        let modelRow = element(application, "03-row-model")
        XCTAssertTrue(modelRow.waitForExistence(timeout: 10),
                      "连接态应呈现可点模型行（替身 getView providers 投影）")
        let thoughtRow = element(application, "03-row-thought")
        XCTAssertTrue(thoughtRow.waitForExistence(timeout: 6),
                      "连接态应呈现可点思考等级行")
        waitLabel(modelRow, contains: "GLM-5.3", timeout: 8,
                  "模型行应回显替身 preferredSelection 的当前模型")

        // ② 打开思考等级菜单 → 选「medium」→ 行 label 回显（SwiftUI Menu + XCUI
        // 点选有 flaky 风险：一次 tap 可能不生效——以「行 label 变为所选档位」为
        // 成功判据，未中则重开菜单重选，有界轮询）
        let thoughtTrigger = element(application, "03-row-thought")
        let selected = waitUntil(timeout: 24, "思考等级行应回显所选档位 medium") {
            // 菜单未开或行未回显时重试一轮：开菜单 → 点 medium
            if !application.buttons["medium"].exists {
                thoughtTrigger.tap()
                _ = application.buttons["medium"].waitForExistence(timeout: 3)
            }
            application.buttons["medium"].tap()
            // 行 label 回显 selectedThought（思考等级行 value=选中档位）
            let rowLabel = thoughtTrigger.label
            return rowLabel.contains("medium")
        }
        XCTAssertTrue(selected, "点选 medium 后思考等级行应回显（菜单点选生效）")

        // ②b UI/UX 目检快照（本轮重设计交付）：模型自绘面板 / slash 建议菜单 /
        // 附件来源面板（新建 sheet 三态；快照入 xcresult 供验收 agent 复核）
        element(application, "03-row-model").tap()
        XCTAssertTrue(element(application, "05-composer-option-sheet").waitForExistence(timeout: 6),
                      "模型行应弹自绘面板（ComposerOptionSheet）")
        snap(application, "52-new-sheet-model-panel")
        element(application, "05-composer-option-cancel").tap()
        _ = waitGoneQuickly(element(application, "05-composer-option-sheet"), within: 4)

        let sheetInput = element(application, "03-input-title")
        tapAndWaitKeyboard(sheetInput, application: application)
        sheetInput.typeText("/")
        XCTAssertTrue(element(application, "03-slash-goal").waitForExistence(timeout: 6),
                      "输入 / 应呈现 slash 建议菜单（内建 goal 项在场）")
        snap(application, "53-new-sheet-slash-menu")
        sheetInput.typeText("\u{8}")
        XCTAssertTrue(waitGoneQuickly(element(application, "03-slash-goal"), within: 3),
                      "删掉 / 后建议菜单应收起")

        element(application, "03-chip-attach").tap()
        XCTAssertTrue(element(application, "05-attach-sheet").waitForExistence(timeout: 6),
                      "附件 chip 应弹附件来源面板（拍照/图库/文件三通道）")
        snap(application, "54-new-sheet-attach-source")
        element(application, "05-attach-sheet-cancel").tap()
        _ = waitGoneQuickly(element(application, "05-attach-sheet"), within: 4)

        // ③ 输入首条指令提交 → createSession.firstInput 携带 modelSelection
        let titleInput = element(application, "03-input-title")
        tapAndWaitKeyboard(titleInput, application: application)
        titleInput.typeText("model-selection-e2e")
        element(application, "03-submit-start").tap()
        XCTAssertTrue(waitUntil(timeout: 12, "替身应收到携带 modelSelection 的 createSession") {
            guard let selection = stub.lastCreateSessionModelSelection else { return false }
            // 元素为 StubRPC 枚举（lastCreateSessionModelSelection: [String: StubRPC]）：
            // 经 stringValue/objectValue 提取，不得 as? String（恒 nil）；
            // selection["options"] 先经 StubRPC.objectValue 解包再取档位
            let level = selection["options"]?.objectValue?["reasoningLevel"]?.stringValue
            return selection["providerId"]?.stringValue == "zai"
                && selection["modelId"]?.stringValue == "GLM-5.3"
                && level == "medium"
        }, "firstInput.modelSelection 应携带 {providerId, modelId, options:{reasoningLevel}}；"
            + "实际=\(String(describing: stub.lastCreateSessionModelSelection))")
        XCTAssertEqual(stub.blockedWriteCommandCount, 0,
                       "模型选择全程不应产生任何直写/配置写类命令")
    }

    /// LIVE 探针（不进门禁；环境变量 ZCODE_LIVE_PANEL=1 才执行）：真实中继链路
    /// 点按验证工作流面板三联症修复（用户 2026-10-06 报障：① 面板展开后不能收起
    /// ② 面板内容不能滚动 ③ 展开面板键盘不收起）。依赖模拟器已配对的真机桌面
    /// 在线（非替身——替身无 workflow 数据面），深链直开带 run 的会话并自动展开。
    /// 会话 id 为真机「二级页面」会话，若该会话被桌面侧删除则探针需换 id（探针
    /// 专用，不构成门禁债）。
    func testLive_workflowPanelCollapseScrollAndKeyboard() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["ZCODE_LIVE_PANEL"] == "1",
            "LIVE 探针：仅 ZCODE_LIVE_PANEL=1 时执行（依赖真实桌面在线，不进门禁）")
        let application = XCUIApplication()
        application.launchArguments = [
            "-ZCodeOpenConversationId", "sess_3ed6cee1-aa8d-4323-879a-2d8f95ba7bcb",
            "-ZCodePanelExpand", "workflow",
        ]
        application.launch()

        // ① 面板展开（真实链路连接+订阅+投影给足 45s）：收起头可见且可点
        let collapse = element(application, "05-panel-act-collapse-workflow")
        XCTAssertTrue(collapse.waitForExistence(timeout: 45), "工作流面板应展开且收起头在场")
        XCTAssertTrue(waitUntil(timeout: 10, "收起头应进入可点区域") { collapse.isHittable },
                      "收起头必须可见可点（上一版被 frame(maxHeight) 居中裁切挤出可视区）")
        let panelBody = element(application, "05-panel-workflow")
        XCTAssertTrue(panelBody.waitForExistence(timeout: 8), "面板体应在场")

        // ② 点收起头 → 面板收起（chips 行仍在，可再次展开）
        XCTAssertTrue(tapUntil(collapse) { !panelBody.exists },
                      "点面板自带收起头后面板应收起")
        XCTAssertTrue(element(application, "05-panel-chip-workflow").waitForExistence(timeout: 5),
                      "收起后 chips 行应在场")

        // ③ 键盘路径：聚焦输入框（键盘起）→ 点 chip 展开面板 → 键盘应即时收起
        //（FocusState 通道；原 UIApplication.sendAction 在 iOS 26 不可靠）
        let input = element(application, "05-composer-input")
        XCTAssertTrue(input.waitForExistence(timeout: 5), "composer 输入框应在场")
        input.tap()
        XCTAssertTrue(application.keyboards.firstMatch.waitForExistence(timeout: 6),
                      "点输入框应唤起键盘")
        let chip = element(application, "05-panel-chip-workflow")
        XCTAssertTrue(tapUntil(chip) { panelBody.exists }, "点 chip 应重新展开面板")
        XCTAssertTrue(
            waitUntil(timeout: 8, "展开面板后键盘应收起（FocusState 精确失焦）") {
                !application.keyboards.firstMatch.exists
            }, "面板展开必须收起键盘（用户报障 ③）")

        snap(application, "live-panel-expanded-keyboard-dismissed")
    }

    /// LIVE 探针（不进门禁；ZCODE_LIVE_PANEL=1 才执行）：斜杠命令菜单点按验证
    /// （用户 2026-10-06 澄清的「能力/goal/workflow 命令」——web 输入 "/" 触发的
    /// 能力命令面）。真实链路：菜单出现（内建 goal 项 + 桌面下发命令）→ 前缀过滤 →
    /// 选中插入 "/name "。不发 送——/goal 会真实改写桌面会话目标，探针零副作用。
    func testLive_composerSlashMenu() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["ZCODE_LIVE_PANEL"] == "1",
            "LIVE 探针：仅 ZCODE_LIVE_PANEL=1 时执行（依赖真实桌面在线，不进门禁）")
        let application = XCUIApplication()
        application.launchArguments = [
            "-ZCodeOpenConversationId", "sess_3ed6cee1-aa8d-4323-879a-2d8f95ba7bcb",
        ]
        application.launch()

        // ① 聚焦输入框并键入 "/" → 菜单出现，内建 /goal 行在场
        let input = element(application, "05-composer-input")
        XCTAssertTrue(input.waitForExistence(timeout: 45), "会话应载入且 composer 在场")
        input.tap()
        XCTAssertTrue(application.keyboards.firstMatch.waitForExistence(timeout: 6), "应唤起键盘")
        input.typeText("/")
        let menu = element(application, "05-slash-menu")
        XCTAssertTrue(menu.waitForExistence(timeout: 6), "键入 / 应触发斜杠命令菜单")
        let goalRow = element(application, "05-slash-row-goal")
        XCTAssertTrue(goalRow.waitForExistence(timeout: 5), "内建 /goal 项应在场")
        XCTAssertTrue(element(application, "05-slash-row-workflow").waitForExistence(timeout: 5),
                      "内建 /workflow 项应在场（桌面技能命令直发面）")

        // ② 前缀过滤：继续输入 "go" → 过滤后仍剩 goal 行（其余项被滤除）
        input.typeText("go")
        Thread.sleep(forTimeInterval: 0.6)
        XCTAssertTrue(goalRow.exists, "go 前缀下 /goal 行应保留")

        // ③ 选中 → 草稿回填 "/goal "（参数待补），菜单随之隐去（已含空格）
        goalRow.tap()
        let filled = waitUntil(timeout: 8, "选中后草稿应回填 /goal ") {
            (input.value as? String ?? "").hasPrefix("/goal")
        }
        XCTAssertTrue(filled, "选中命令应插入 /goal 前缀；实际=\(input.value ?? "nil")")
        XCTAssertTrue(waitGoneQuickly(element(application, "05-slash-menu"), within: 2),
                      "进入参数段（含空格）后菜单应隐去")

        // ④ 清稿收场（探针不留草稿）
        input.typeText(String(repeating: "\u{8}", count: 16))
        snap(application, "live-slash-menu-selected")
    }

    // MARK: - 门禁 09：斜杠命令全链（菜单 → 选中 → 客户端拦截转 sendGoalCommand）

    /// 用户报障 2026-10-07「刚修改的 slash command 不能用」的替身回归：连接态键入
    /// "/" → 菜单在场（内建 + 替身推送归一化去重）→ 选中回填 → 补参数发送 →
    /// 客户端拦截转 sendGoalCommand（非 sendText）→ 反馈行上屏。
    func test09_composerSlashMenuInterceptsGoalCommand() throws {
        let application = connectAndEnterMain(launchFresh())
        element(application, "04-tab-chat").tap()
        let row = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "替身会话行应在场")
        row.tap()

        // ① 键入 "/" → 菜单在场，内建 /goal 在场
        let input = element(application, "05-composer-input")
        XCTAssertTrue(input.waitForExistence(timeout: 15), "会话详情 composer 应在场")
        tapAndWaitKeyboard(input, application: application)
        input.typeText("/")
        XCTAssertTrue(element(application, "05-slash-menu").waitForExistence(timeout: 6),
                      "键入 / 应触发斜杠命令菜单")
        let goalRow = element(application, "05-slash-row-goal")
        XCTAssertTrue(goalRow.waitForExistence(timeout: 5), "内建 /goal 行应在场")
        // ② 替身推送 "/compact"（带前导斜杠，上游 CLI 原始形态）→ 归一化后与内建
        // 去重 → compact 恰一行（归一化缺失时渲染 //compact 且过滤永不命中）
        let compactRows = application.buttons
            .matching(identifier: "05-slash-row-compact").allElementsBoundByIndex
        XCTAssertEqual(compactRows.count, 1,
                       "推送名前导斜杠应归一化并与内建去重；实际 \(compactRows.count) 行")

        // ③ 选中 → 草稿回填 "/goal "（菜单随空格隐去）
        XCTAssertTrue(tapUntil(goalRow) {
            (input.value as? String ?? "").hasPrefix("/goal")
        }, "选中命令应回填 /goal 前缀；实际=\(input.value ?? "nil")")
        XCTAssertTrue(waitGoneQuickly(element(application, "05-slash-menu"), within: 3),
                      "进入参数段后菜单应隐去")

        // ④ 补参数发送 → 客户端拦截转 sendGoalCommand（替身收到 goal 文本而非 sendText）
        input.typeText("E2E goal probe")
        element(application, "05-composer-send").tap()
        XCTAssertTrue(waitUntil(timeout: 12, "替身应收到 sendGoalCommand") {
            stub.lastGoalCommandText == "E2E goal probe"
        }, "斜杠 /goal 应客户端拦截转 sendGoalCommand；实际=\(stub.lastGoalCommandText ?? "nil")")
        XCTAssertTrue(element(application, "05-slash-hint").waitForExistence(timeout: 6),
                      "下发结果反馈行应上屏")
        snap(application, "45-slash-goal-dispatched")
    }

    // MARK: - 门禁 10：附件上传事务全链（Begin/Chunk/Commit → sendText 携带 ref）

    /// 用户报障 2026-10-07「附件不能上传」的替身回归：替身按 web 客户端形状严格
    /// 校验附件四方法（多带 connectionId 即记形状错误——上游 facade 会剥掉客户端
    /// 伪造值，与 web 不对齐）。系统相册/文件选择器无法被 XCUITest 驱动，经
    /// -ZCodeAttachFixturePath 注入真实文件字节，上传事务与 sendText 携带均走
    /// 用户路径同款代码（无 mock/假成功）。
    func test10_attachmentUploadTransactionCarriedInSendText() throws {
        // 1.2MB（> 3×384KB → 4 块，覆盖多分块顺序上传）；扩展名 bin → octet-stream
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("e2e-attach-\(UUID().uuidString).bin")
        let payload = Data((0..<1_200_000).map { UInt8(($0 * 31) % 251) })
        try payload.write(to: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        let application = connectAndEnterMain(
            launchFresh(),
            extraArguments: ["-ZCodeAttachFixturePath", fixture.path])
        element(application, "04-tab-chat").tap()
        let row = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "替身会话行应在场")
        row.tap()

        // ① 待发附件条在场（fixture 注入 = composer 附件入口注入后的同态）
        XCTAssertTrue(element(application, "05-attach-strip").waitForExistence(timeout: 10),
                      "待发附件条应随 fixture 注入在场")

        // ② fixture「选定即上传」（用户三通道同款语义，不等发送）：替身应收满
        //    Begin → Chunk×4 → Commit 全链且形状零瑕疵
        XCTAssertTrue(waitUntil(timeout: 30, "替身应收满 4 块并 commit") {
            stub.attachmentCommitCount >= 1
                && stub.attachmentChunkCount >= 4
                && stub.attachmentBeginCount >= 1
        }, "上传事务应 Begin→Chunk×4→Commit 全链到达；实际 begin=\(stub.attachmentBeginCount) chunk=\(stub.attachmentChunkCount) commit=\(stub.attachmentCommitCount)")
        XCTAssertTrue(stub.attachmentShapeErrors.isEmpty,
                      "附件四方法载荷应通过 web 同形严格校验；实际=\(stub.attachmentShapeErrors)")

        // ③ committed 后发送闸放行 → sendText 携带 Commit 回执 ref
        let input = element(application, "05-composer-input")
        XCTAssertTrue(input.waitForExistence(timeout: 8), "composer 输入框应在场")
        tapAndWaitKeyboard(input, application: application)
        input.typeText("attach-e2e-message")
        element(application, "05-composer-send").tap()
        XCTAssertTrue(waitUntil(timeout: 12, "sendText 应携带已提交 ref 的附件数组") {
            let attachments = stub.lastSendTextAttachments
            return attachments.contains {
                ($0["ref"] as? String)?.hasPrefix("att-e2e-") == true
                    && ($0["bytes"] as? Int) == 1_200_000
            }
        }, "sendText attachments 元素应为 {ref, fileName, mime, bytes} 且 ref 来自 Commit 回执；实际=\(stub.lastSendTextAttachments)")
        snap(application, "46-attachment-committed")
    }

    // MARK: - 门禁 11：附件来源底部 sheet + 键盘点按收起（审批卡在场）

    /// 用户报障 2026-10-07 两联的替身回归：①「添加附件弹窗都弹到什么地方去了」
    /// （confirmationDialog 本 OS 渲染成锚定 popover → 自绘底部 sheet）；②「有审核
    /// 弹窗的时候键盘收不起来」（审批卡/消息区点按失焦通道）。
    func test11_attachmentSourceSheetAndTapToDismissKeyboard() throws {
        let application = connectAndEnterMain(launchFresh())
        element(application, "04-tab-chat").tap()
        let row = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "替身会话行应在场")
        row.tap()

        // ① 审批卡在场（sess-e2e-1 预置 permission 挂起交互）→ 聚焦输入框（键盘起）
        let approvalCard = element(application, "05-approval-card")
        XCTAssertTrue(approvalCard.waitForExistence(timeout: 12), "审批卡应常驻 composer 上方")
        let input = element(application, "05-composer-input")
        tapAndWaitKeyboard(input, application: application)
        XCTAssertTrue(application.keyboards.firstMatch.exists, "键盘应唤起")

        // ② 点审批卡空白区（标题静态文本，非按钮）→ 键盘应收起（容器点按失焦通道）
        let cmdText = element(application, "05-approval-cmd")
        XCTAssertTrue(cmdText.waitForExistence(timeout: 5), "审批卡命令行应在场")
        cmdText.tap()
        XCTAssertTrue(waitUntil(timeout: 8, "点审批卡空白区后键盘应收起") {
            !application.keyboards.firstMatch.exists
        }, "审批卡在场时点按卡空白区必须收起键盘（用户报障 ②）")

        // ③ 附件入口 → 底部 sheet 三选项（拍照/照片图库/文件）→ 取消收起
        let attachButton = element(application, "05-attach-button")
        XCTAssertTrue(attachButton.waitForExistence(timeout: 8), "composer 附件入口应在场")
        attachButton.tap()
        let sheet = element(application, "05-attach-sheet")
        XCTAssertTrue(sheet.waitForExistence(timeout: 8), "添加附件应弹出底部 sheet（非锚定 popover）")
        XCTAssertTrue(element(application, "05-attach-sheet-camera").waitForExistence(timeout: 5),
                      "拍照选项应在场")
        XCTAssertTrue(element(application, "05-attach-sheet-photos").exists, "照片图库选项应在场")
        XCTAssertTrue(element(application, "05-attach-sheet-files").exists, "文件选项应在场")
        snap(application, "47-attachment-source-sheet")
        element(application, "05-attach-sheet-cancel").tap()
        XCTAssertTrue(waitGoneQuickly(sheet, within: 4), "取消应收起附件 sheet")

        // ④ 再次唤起键盘 → 点审批卡空白区（命令行静态文本——卡片几何中心在横幅
        // 插入后恰落在「始终允许」快捷按钮上，中心 tap 会误触审批）→ 键盘应收起
        tapAndWaitKeyboard(input, application: application)
        XCTAssertTrue(application.keyboards.firstMatch.exists, "键盘应再次唤起")
        cmdText.tap()
        XCTAssertTrue(waitUntil(timeout: 8, "点审批卡后键盘应再次收起") {
            !application.keyboards.firstMatch.exists
        }, "点按失焦通道应可重复触发")
        snap(application, "48-keyboard-tap-dismissed")
    }

    // MARK: - 门禁 12：tasks-index membership join（置顶/归档）+ CAS stale 重试

    /// 用户报障 2026-10-07 三联的替身回归：①「桌面置顶 4 项移动端只见 1 项」——
    /// sessions-index 行无 pinned 字段【实证·上游仓】，置顶组织态须 listPinnedTasks
    /// join；②「归档的会话又丢了」——归档区 listArchivedTasks 行应呈现；③「composer
    /// 胶囊切换都不行」——switchModelConfig 首击 stale 须原样重发一次（替身
    /// stale-once-then-accepted 绊线，计数 ≥2 即重试链在位）。
    func test12_pinnedAndArchivedMembershipJoinsWithModelCASRetry() throws {
        let application = connectAndEnterMain(launchFresh())
        element(application, "04-tab-chat").tap()

        // ① membership join：替身 listPinnedTasks 预置 sess-e2e-think（列表末位）→
        //    应跃居「置顶」分区（行帧高于未置顶的 sess-e2e-1 行）
        XCTAssertTrue(waitUntil(timeout: 20, "替身应收到 listPinnedTasks membership 拉取") {
            stub.pinnedTasksRequestCount >= 1
        }, "会话列表装载应拉取 listPinnedTasks（置顶权威源 join）")
        let thinkRow = element(application, "04-row-sess-e2e-think")
        let plainRow = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(waitUntil(timeout: 15, "置顶 join 应把 think 行顶到列表最前") {
            guard thinkRow.exists, plainRow.exists,
                  thinkRow.isHittable, plainRow.isHittable else { return false }
            return thinkRow.frame.minY < plainRow.frame.minY
        }, "listPinnedTasks 预置会话应出现在置顶分区（帧序高于未置顶行）；" +
           "think=\(thinkRow.exists) plain=\(plainRow.exists)")

        // ② composer 模型/思考胶囊：switchModelConfig 首击 stale → 重发命中。
        //    （置于归档分区之前：归档展开/收起后的列表布局一致性不可靠，行导航
        //    偶发落空——test12 全量门禁 flake 实证；从列表顶部状态直接进详情无此依赖）
        let detailRow = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(openConversation(detailRow, application: application),
                      "点击会话行应推入详情")
        let thoughtChip = element(application, "05-chip-thought")
        XCTAssertTrue(thoughtChip.waitForExistence(timeout: 12), "思考胶囊应在场（连接态 chips）")
        // Menu 呈现为系统弹层：开弹以菜单项出现为证据。不能用 tapUntil——菜单开着时
        // 胶囊被遮挡不可命中/或仍可命中时再 tap 会把菜单关掉（开合互打，test12 门禁
        // 视频实证）；openTargetMenu tap 一次后短轮询候选、未弹有界重开
        let lowItems = application.buttons.matching(NSPredicate(format: "label == 'low'"))
        XCTAssertTrue(openTargetMenu(thoughtChip, candidate: lowItems),
                      "点思考胶囊应弹出菜单（low 档项在场）")
        lowItems.firstMatch.tap()
        // 首击 stale（替身固定回 stale 一次）→ 客户端应原样重发 → 计数 ≥2 即重试链在位
        XCTAssertTrue(waitUntil(timeout: 12, "stale 后应重发 switchModelConfig") {
            stub.switchModelConfigCallCount >= 2
        }, "switchModelConfig stale 应触发原样重发；实际调用=\(stub.switchModelConfigCallCount) 次")
        waitUntil(timeout: 5, "重试命中后不应残留拒绝提示") {
            !element(application, "05-composer-switch-hint").exists
        }
        snap(application, "50-model-cas-retry")

        // ③ 归档分区：返回列表 → 展开 → 替身 listArchivedTasks 预置行应在场（并发拉取）
        app_navigationBack(application)
        waitGoneQuickly(element(application, "05-composer-input"), within: 4)
        XCTAssertTrue(scrollToHittable(element(application, "04-act-archived"), application: application),
                      "归档入口行应可滚动到可见")
        element(application, "04-act-archived").tap()
        XCTAssertTrue(waitUntil(timeout: 20, "替身应收到 listArchivedTasks 拉取") {
            stub.archivedTasksRequestCount >= 1
        })
        XCTAssertTrue(element(application, "04-archivedrow-sess-e2e-arch-1").waitForExistence(timeout: 15),
                      "归档分区应呈现 listArchivedTasks 预置行（替身会话 · 已归档样例）")
        snap(application, "49-archived-membership")

        // 收起归档分区（展开/收起可逆回归；此后无行导航依赖）
        element(application, "04-act-archived").tap()
        XCTAssertTrue(waitGoneQuickly(element(application, "04-archivedrow-sess-e2e-arch-1"), within: 6),
                      "再次点击归档入口应收起归档分区")
    }

    // MARK: - 门禁 14：重置卡二次确认门（扣费接口：确认后必发、取消必不发）

    /// 用户报障 2026-10-07「改重置卡样式后什么都不能用了」回归门：自绘确认面板
    /// 改版曾把确认链路改断——收起 sheet 的 binding 置 nil 先于回调读态执行，
    /// performResetUse 永不触发（无测试覆盖致回归漏网，本轮补门）。三段锁死：
    /// ①替身预置 5h+周卡各 1 张 → 机会卡与两档按钮在场；②取消路径：面板可开可
    /// 关，useCodingPlanReset 零到达；③确认路径：点确认必发
    /// useCodingPlanReset(resetType=FIVE_HOUR) 且成功反馈行在场。
    /// 重置卡为扣费接口【用户叮嘱 2026-10-07】：本用例仅对本地替身发（127.0.0.1
    /// 内存记账），真机/真实桌面零接触；真机验证一律走取消路径。
    func test14_resetCardConfirmGate() throws {
        let application = connectAndEnterMain(launchFresh())
        element(application, "12-tab-me").tap()
        let usageRow = element(application, "12-row-usage")
        XCTAssertTrue(usageRow.waitForExistence(timeout: 10), "设置页应有用量统计入口")
        usageRow.tap()

        // ① 替身 getCodingPlanResetStatus 预置 5h+周各 1 张 → 重置机会卡在场
        let resetCard = element(application, "12-usage-reset-card")
        XCTAssertTrue(resetCard.waitForExistence(timeout: 15), "重置机会卡应在场（替身预置两档卡）")
        let claim5h = element(application, "12-usage-act-claim-5h")
        XCTAssertTrue(claim5h.waitForExistence(timeout: 8), "5 小时卡使用按钮应在场")

        // ② 取消路径：面板开 → 取消 → 收起，扣费调用零到达
        let sheet = element(application, "12-usage-reset-sheet")
        claim5h.tap()
        XCTAssertTrue(sheet.waitForExistence(timeout: 8), "使用重置卡应弹自绘确认面板")
        element(application, "12-usage-cancel").tap()
        XCTAssertTrue(waitGoneQuickly(sheet, within: 4), "取消应收起确认面板")
        waitUntil(timeout: 4, "取消后确认扣费请求不应到达") {
            !stub.resetCardRPCCalls.contains("useCodingPlanReset")
        }
        XCTAssertFalse(stub.resetCardRPCCalls.contains("useCodingPlanReset"),
                       "取消路径不得发出 useCodingPlanReset（实际到达：\(stub.resetCardRPCCalls)）")

        // ③ 确认路径：再开面板 → 确认 → 替身必收到 useCodingPlanReset(FIVE_HOUR)
        claim5h.tap()
        XCTAssertTrue(sheet.waitForExistence(timeout: 8), "确认面板应再次弹出")
        element(application, "12-usage-confirm-use").tap()
        XCTAssertTrue(waitUntil(timeout: 10, "确认后应发出 useCodingPlanReset") {
            stub.resetCardUseTypes.contains("FIVE_HOUR")
        }, "点确认必须下发 useCodingPlanReset(resetType=FIVE_HOUR)——回归根因即此步被吞")
        XCTAssertTrue(element(application, "12-usage-claim-notice").waitForExistence(timeout: 8),
                      "用卡成功应出现反馈行")
        snap(application, "51-reset-card-used")
    }

    /// test13 订阅 workspace 按会话归属寻址（真机报障 2026-10-07「手机端没有回复」回归）：
    /// 连接工作区 = /Users/e2e/zcode-workspace（identity ws-e2e-1），会话行 sess-e2e-1
    /// 自带 workspacePath = /Users/e2e/mtt_mobile（跨工作区行，bootstrap/sessions-index
    /// 常态）——订阅必须携带会话归属 workspace 而非连接 workspace【实证·上游仓
    /// zcodeAgentService.subscribeConversationV4：getReadOnlyClient(params) 按
    /// params.workspacePath 选 CLI 进程承接订阅；寻址错位 = 历史可读、运行中 turn 的
    /// 行增量永不抵达】。修复前此断言失败（恒发连接 workspace）。
    func test13_conversationSubscribeTargetsSessionWorkspace() throws {
        let application = connectAndEnterMain(launchFresh())
        element(application, "04-tab-chat").tap()
        let row = element(application, "04-row-sess-e2e-1")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "替身会话行应在场")
        XCTAssertTrue(openConversation(row, application: application), "点击会话行应推入详情")
        XCTAssertTrue(waitUntil(timeout: 15, "替身应收到该会话的 subscribeConversationV4") {
            stub.subscribeTargets.contains { $0.topic == "conversation/sess-e2e-1" }
        }, "订阅请求应到达替身（实际：\(stub.subscribeTargets)）")
        let target = stub.subscribeTargets.last { $0.topic == "conversation/sess-e2e-1" }
        XCTAssertEqual(target?.workspacePath, "/Users/e2e/mtt_mobile",
                       "订阅必须携带会话行自带的 workspacePath（跨工作区会话归属），" +
                       "而非连接工作区 /Users/e2e/zcode-workspace（实际：\(String(describing: target))）")
        XCTAssertNil(target?.workspaceIdentity,
                     "归属工作区无 identity 时不得携带连接工作区的 ws-e2e-1（identity 错配同样使桌面侧 workspaceKey 失配）")
        snap(application, "52-subscribe-workspace-target")
    }
}

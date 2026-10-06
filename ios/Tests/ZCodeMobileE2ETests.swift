import XCTest

/// ZCode Mobile · 核心用户流程 e2e（XCUITest 原生 UI 测试）。
///
/// 约定：
/// - 选择器一律基于 accessibilityIdentifier（与 App 内三段式 id 契约对齐，见 design-spec §5.10）；
/// - 只用 waitForExistence / XCTNSPredicateExpectation 等待，容忍流式动画与首屏加载，不写固定 sleep；
/// - 每个用例独立 launch，Mock 数据常驻内存、每次启动重建，设置走 UserDefaults —— 用例间无顺序依赖、可重复执行；
/// - 本文件只做编译级自检，由统一门禁脚本在模拟器上执行。
final class ZCodeMobileE2ETests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 基础设施

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        // -ZCodeDemoData：E2E 演示开关（对齐修复后 Mock 仅测试用例允许装配，
        // AppSession.isDemoDataEnabled；不携带则未连接态为连接引导页/空态）
        app.launchArguments += ["-ZCodeDemoData", "-AppleLanguages", "(zh-Hans)"] // 本地化后固定测试语言（中文断言稳定）
        app.launch()
        return app
    }

    /// 任意元素类型按 accessibilityIdentifier 查找（按钮/静态文本/容器统一）
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// 等待元素 accessibility label 包含指定文本（用于设置行 value 这类动态文本）
    @discardableResult
    private func waitLabel(_ item: XCUIElement, contains text: String,
                           timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", text), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 等待元素从可访问性树消失（用于折叠动画，替代固定 sleep）
    @discardableResult
    private func waitDisappear(_ item: XCUIElement, timeout: TimeInterval, _ message: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: item)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertTrue(result == .completed, message)
        return result == .completed
    }

    /// 滚动揭示视口外元素（项 5 项目分组上线后列表按项目分组渲染，未绑定项目的
    /// 会话归「任务」组、位于首屏之下——List/LazyVStack 惰性创建，视口外查不到。
    /// 有界下滑直到元素入树；已在场则原样返回）
    @discardableResult
    private func scrollReveal(_ app: XCUIApplication, _ identifier: String,
                              maxSwipes: Int = 6) -> Bool {
        let item = element(app, identifier)
        if item.exists { return true }
        for _ in 0..<maxSwipes where !item.exists {
            app.swipeUp()
        }
        return item.exists
    }

    // MARK: - 流程 1：启动进入会话列表，能看到 mock 会话数据

    func test01_launchShowsConversationListWithMockData() throws {
        let app = launch()

        // 默认选中「会话」Tab；列表有 350ms 模拟加载，直接等置顶行出现
        let pinnedRow = element(app, "04-row-c1")
        XCTAssertTrue(pinnedRow.waitForExistence(timeout: 10), "启动后应进入会话列表并出现置顶会话行（c1）")

        // 首屏断言（滚动前完成：LazyVStack 行滑出视口后即从树中移除）
        XCTAssertTrue(element(app, "04-act-new").exists, "会话页导航栏应有新建入口")
        XCTAssertTrue(element(app, "04-search").exists, "会话页应有搜索框")
        XCTAssertTrue(app.staticTexts["重构会话持久层"].exists, "应显示 mock 会话标题「重构会话持久层」")

        // 项目分组（项 5）上线后列表按项目分组渲染：未绑定项目的 c2/c5 归「任务」组、
        // 位于首屏之下（List 行惰性创建，视口外查不到）——滚动揭示后断言
        XCTAssertTrue(scrollReveal(app, "04-row-c2"), "未绑定项目的会话（c2）滚动后应显示（任务组）")
        XCTAssertTrue(app.staticTexts["修复登录超时问题"].exists,
                      "应显示 mock 会话标题「修复登录超时问题」（c2 揭示窗口内）")
        XCTAssertTrue(scrollReveal(app, "04-row-c5"), "运行中会话（c5）滚动后应显示（任务组）")
    }

    // MARK: - 流程 2：底部 Tab 在 会话/任务/文件/设置 之间切换

    func test02_bottomTabsSwitchBetweenFourRoots() throws {
        let app = launch()

        let chatTab = element(app, "04-tab-chat")
        XCTAssertTrue(chatTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")

        // 任务
        element(app, "02-tab-tasks").tap()
        XCTAssertTrue(element(app, "02-fab-newtask").waitForExistence(timeout: 6), "任务看板应有新建 FAB")
        XCTAssertTrue(element(app, "02-search").waitForExistence(timeout: 6), "任务看板加载完成应有搜索框")

        // 会话
        chatTab.tap()
        XCTAssertTrue(element(app, "04-act-new").waitForExistence(timeout: 6), "会话页应有新建入口")
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 6), "会话页应展示会话行")

        // 文件
        element(app, "08-tab-review").tap()
        XCTAssertTrue(element(app, "08-branch-switcher").waitForExistence(timeout: 6), "文件页应有分支胶囊")
        XCTAssertTrue(element(app, "08-filecard-toggle-d1").waitForExistence(timeout: 6), "文件页应列出 diff 文件卡 d1 的折叠开关")

        // 设置
        element(app, "12-tab-me").tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 6), "设置页应有用户卡")
        XCTAssertTrue(element(app, "12-row-appearance").exists, "设置页应有外观行")
        XCTAssertTrue(element(app, "12-row-model").exists, "设置页应有模型设置行")

        // 往返切回会话，验证切换可重复
        chatTab.tap()
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 6), "切回会话 Tab 应恢复列表")
    }

    // MARK: - 流程 3：新建会话 → 发送消息 → 出现模拟流式回复

    func test03_newConversationSendAndStreamReply() throws {
        let app = launch()

        let newButton = element(app, "04-act-new")
        XCTAssertTrue(newButton.waitForExistence(timeout: 10), "会话列表应加载完成并出现新建入口")
        newButton.tap()

        let titleInput = element(app, "03-input-title")
        XCTAssertTrue(titleInput.waitForExistence(timeout: 6), "新建会话 Sheet 应弹出")
        titleInput.tap()
        titleInput.typeText("E2E smoke conversation")

        let submit = element(app, "03-submit-start")
        XCTAssertTrue(submit.waitForExistence(timeout: 4), "新建会话应有「开始任务」按钮")
        submit.tap()

        let composer = element(app, "05-composer-input")
        XCTAssertTrue(composer.waitForExistence(timeout: 8), "开始任务后应推入会话页")
        composer.tap()
        composer.typeText("verify streaming reply")

        let send = element(app, "05-composer-send")
        XCTAssertTrue(send.waitForExistence(timeout: 4), "会话页应有发送键")
        send.tap()

        // 用户消息回显（此时正吸底，必然在渲染窗口内）
        XCTAssertTrue(app.staticTexts["verify streaming reply"].waitForExistence(timeout: 8),
                      "发送后用户消息应上屏")

        // 模拟流式回复（AsyncStream 逐字推送）：按剧本出现顺序逐个等待，
        // 每个元素在其刚出现、仍处于吸底渲染窗口内时完成断言，规避 LazyVStack 窗口外不可见的问题
        XCTAssertTrue(element(app, "05-toolcard-head-bash").waitForExistence(timeout: 20),
                      "流式回复应出现 bash 工具卡")
        XCTAssertTrue(element(app, "05-todocard").waitForExistence(timeout: 20),
                      "流式回复应出现任务拆解卡")
        XCTAssertTrue(element(app, "05-toolcard-head-edit").waitForExistence(timeout: 20),
                      "流式回复应出现 edit 工具卡")
        XCTAssertTrue(element(app, "05-questioncard").waitForExistence(timeout: 20),
                      "流式回复应以 Agent 提问卡收尾")

        // 快捷回复 chips 可点并发应答（QuestionCard → onReply → store.answerQuestion；
        // v3 纠偏后 MessageViews chips 连接态/演示态同构可点，演示走 Mock 路径行为不变）。
        // chip 文本取新会话剧本（MockConversationStore 剧本 quickReplies 三档）
        let chip = app.buttons["给我看完整 diff"]
        XCTAssertTrue(chip.waitForExistence(timeout: 8), "提问卡应提供快捷回复 chips（44px 热区）")
        chip.tap()
        XCTAssertTrue(app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", "收到，按「给我看完整 diff」处理"))
            .firstMatch.waitForExistence(timeout: 15),
                      "点击 chip 应发出应答并触发模拟回复（应答文本回执上屏）")

        // 返回列表，新会话应按时间线排入「任务」组（未绑定项目；项目分组上线后该组
        // 位于视口外，需滚动揭示——List 行惰性创建）
        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 4), "会话页应有系统返回按钮")
        back.tap()
        let newConversationRow = app.staticTexts["E2E smoke conversation"]
        if !newConversationRow.waitForExistence(timeout: 4) {
            for _ in 0..<6 where !newConversationRow.exists {
                app.swipeUp()
            }
        }
        XCTAssertTrue(newConversationRow.exists, "返回后列表滚动揭示应出现新建的会话（任务组）")
    }

    // MARK: - 流程 4：设置页修改一项配置并保存，重新进入仍在

    func test04_settingsAppearancePersistsAcrossRelaunch() throws {
        let app = launch()

        let meTab = element(app, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")
        meTab.tap()

        let appearanceRow = element(app, "12-row-appearance")
        XCTAssertTrue(appearanceRow.waitForExistence(timeout: 6), "设置页应有外观行")
        appearanceRow.tap()

        let darkOption = element(app, "12-appearance-dark")
        XCTAssertTrue(darkOption.waitForExistence(timeout: 6), "外观页应有 Zai Dark 选项")
        darkOption.tap() // 选择即保存（didSet → UserDefaults），并即时换肤

        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 4), "外观页应有返回按钮")
        back.tap()

        waitLabel(element(app, "12-row-appearance"), contains: "Zai Dark",
                  timeout: 6, "返回设置页后外观行的值应显示为 Zai Dark")

        // 冷启动回归，验证 UserDefaults 持久化
        app.terminate()
        app.launch()

        let meTabAgain = element(app, "12-tab-me")
        XCTAssertTrue(meTabAgain.waitForExistence(timeout: 10), "重启后 Tab 栏应出现")
        meTabAgain.tap()

        let appearanceRowAgain = element(app, "12-row-appearance")
        XCTAssertTrue(appearanceRowAgain.waitForExistence(timeout: 6), "重启后设置页应有外观行")
        waitLabel(appearanceRowAgain, contains: "Zai Dark",
                  timeout: 6, "重启后外观设置应保持 Zai Dark")
    }

    // MARK: - 流程 5：打开 diff 视图查看文件变更

    func test05_diffReviewShowsFileChanges() throws {
        let app = launch()

        let filesTab = element(app, "08-tab-review")
        XCTAssertTrue(filesTab.waitForExistence(timeout: 10), "底部 Tab 栏应出现")
        filesTab.tap()

        let fileCardToggle = element(app, "08-filecard-toggle-d1")
        XCTAssertTrue(fileCardToggle.waitForExistence(timeout: 8), "文件页应列出 diff 文件卡 d1")
        XCTAssertTrue(element(app, "08-branch-switcher").exists, "文件页应有分支胶囊")
        XCTAssertTrue(app.staticTexts["src/core/SessionStore.swift"].exists, "文件卡应显示变更文件路径")

        // 默认折叠，点开 d1 查看变更行
        fileCardToggle.tap()

        XCTAssertTrue(element(app, "08-diffrow-hunk-1").waitForExistence(timeout: 6), "展开后应看到 hunk 行")
        XCTAssertTrue(element(app, "08-diffrow-add-1").exists, "展开后应看到新增行")
        XCTAssertTrue(element(app, "08-diffrow-del-1").exists, "展开后应看到删除行")
        XCTAssertTrue(app.staticTexts["+24"].exists, "文件卡应显示新增行数统计")
        XCTAssertTrue(app.staticTexts["-8"].exists, "文件卡应显示删除行数统计")

        // 折叠 / 再展开往返，验证 diff 视图交互可重复
        fileCardToggle.tap()
        waitDisappear(element(app, "08-diffrow-add-1"), timeout: 5, "折叠后 diff 行应从可访问性树消失")
        fileCardToggle.tap()
        XCTAssertTrue(element(app, "08-diffrow-add-1").waitForExistence(timeout: 6), "再次展开应恢复 diff 行")

        // 底部动作栏（文件 Tab 根页专属，叠于 Tab 栏之上）
        XCTAssertTrue(element(app, "08-act-approve-all").waitForExistence(timeout: 4),
                      "文件页底部应有「全部批准」动作")

        // 文件只读边界 UI 走查：文件页可见操作不得含文件写/仓库写入口。
        // 「全部批准 / 批准此文件」为本地决策记录（服务端无逐文件批准接口），
        // 不在禁止面；RemoteFileStore 仅调用 file 读面 + git 读面（代码走查核对）。
        let visibleButtons = app.buttons
        let sampleCount = min(visibleButtons.count, 60)
        var checkedCount = 0
        for index in 0..<sampleCount {
            let item = visibleButtons.element(boundBy: index)
            guard item.exists, item.isHittable else { continue }
            checkedCount += 1
            // 「提交图谱」为 G-023 只读页入口（git.getCommitGraph 读面，DiffReviewView:282），
            // label 撞禁词「提交」——白名单放行；真实仓库写面的提交/暂存仍拦截
            if item.label.contains("提交图谱") { continue }
            for forbidden in ["保存", "写入", "回滚", "还原", "提交", "暂存", "放弃更改"] {
                XCTAssertFalse(item.label.contains(forbidden),
                               "文件页不应出现文件写/仓库写入口（命中「\(forbidden)」：\(item.label)）")
            }
        }
        XCTAssertGreaterThan(checkedCount, 0,
                             "文件页应存在可走查的可见操作（isHittable 命中 \(checkedCount) 个）")
    }

    // MARK: - 流程 6：品牌回归（品牌名 BiuZ + 主界面可见文案不含 ZCode）

    /// 本轮品牌变更回归（冷启动、演示模式）：
    /// ① 主界面（会话 Tab 根页 + TabBar）全部可见文案不含 "ZCode"；
    /// ② 设置页品牌名行（12-brand-name）以 BiuZ 开头（works-with 桌面端为描述性引用，不在主界面）；
    /// ③ 设置页数据源页脚自称为 "BiuZ for iOS · …"。
    /// App 图标资产（AppIcon-1024.png ← branding/logo biuz-icon）属构建产物层面，
    /// 由门禁在仓库 Assets 目录断言，不做 UI 断言。
    func test06_brandShowsBiuZAndMainScreenHasNoZCodeCopy() throws {
        let app = launch()

        // 主界面（会话 Tab 根页）加载完成
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "冷启动应进入主界面会话列表")
        XCTAssertTrue(element(app, "04-tab-chat").waitForExistence(timeout: 6), "TabBar 应出现")

        // ① 主界面可见文案不含 ZCode（遍历当前窗口内可命中的静态文本：标题/行/分组头/TabBar）。
        // 说明：RootView 四个 Tab 根页同挂 ZStack（RootView.swift:16-32），未选中 Tab 虽
        // accessibilityHidden 但仍出现在 XCUITest 查询树——以 isHittable 过滤出真正可见文案
        // （未选中 Tab allowsHitTesting(false)，其元素不可命中）。
        let visibleTexts = app.staticTexts
        let sampleCount = min(visibleTexts.count, 60)
        var checkedCount = 0
        for index in 0..<sampleCount {
            let item = visibleTexts.element(boundBy: index)
            guard item.exists, item.isHittable else { continue }
            checkedCount += 1
            XCTAssertFalse(item.label.contains("ZCode"),
                           "主界面可见文案不应出现 ZCode（命中：\(item.label)）")
        }
        XCTAssertGreaterThan(checkedCount, 0,
                             "主界面应存在可断言的可见文案（isHittable 命中 \(checkedCount) 条）")

        // ② 设置页品牌名行以 BiuZ 开头
        element(app, "12-tab-me").tap()
        let brandRow = element(app, "12-brand-name")
        XCTAssertTrue(scrollToReveal(brandRow, app: app),
                      "设置页应有品牌名行（12-brand-name，滚动到底可见）")
        XCTAssertTrue(brandRow.label.hasPrefix("BiuZ"),
                      "品牌名行应以 BiuZ 开头（实际：\(brandRow.label)）")

        // ③ 数据源页脚自称为 BiuZ for iOS（演示模式后缀 · 演示数据由本地 Mock 提供）
        waitLabel(element(app, "12-foot-data-source"), contains: "BiuZ for iOS",
                  timeout: 6, "设置页页脚应自称为 BiuZ for iOS")
    }

    /// 循环上滑直到元素进入屏幕可视区（设置页为 ScrollView，品牌行在首屏窗口外），最多 maxSwipes 次
    @discardableResult
    private func scrollToReveal(_ item: XCUIElement, app: XCUIApplication, maxSwipes: Int = 6) -> Bool {
        let screen = app.frame
        for _ in 0..<maxSwipes {
            if item.exists, screen.contains(CGPoint(x: item.frame.midX, y: item.frame.midY)) {
                return true
            }
            app.swipeUp()
        }
        return item.exists
    }

    /// 左滑会话行露出滑动动作并点击指定动作按钮（按钮 identifier 形如 04-rowact-pin-<id>）。
    /// 行可能位于分组序列末尾、LazyVStack 视口外未物化——先滚动揭示再左滑
    private func swipeRowAndTapAction(_ app: XCUIApplication, row: XCUIElement,
                                      actionIdentifier: String, message: String) {
        _ = scrollToReveal(row, app: app)
        XCTAssertTrue(row.waitForExistence(timeout: 6), message + "（行应先在场）")
        row.swipeLeft()
        let action = element(app, actionIdentifier)
        XCTAssertTrue(action.waitForExistence(timeout: 4), message)
        action.tap()
    }

    /// 点击输入框并等待键盘弹出（列表刷新/转场动画期 tap 可能落空，未弹出则有界重试）
    @discardableResult
    private func tapUntilKeyboard(_ field: XCUIElement, app: XCUIApplication,
                                  timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            guard field.exists, field.isHittable else {
                Thread.sleep(forTimeInterval: 0.3)
                continue
            }
            field.tap()
            if app.keyboards.firstMatch.exists { return true }
            Thread.sleep(forTimeInterval: 0.6)
        }
        return app.keyboards.firstMatch.exists
    }

    // MARK: - 流程 7：演示模式会话列表操作闭环（置顶分组 / 归档过滤 / 搜索过滤）

    /// 交互闭环的 UI 反射完整形态（MockConversationStore，常驻内存不经替身）：
    /// ① c1 默认置顶 →「置顶」分组头在场；取消置顶 → 分组头消失；
    /// ② 置顶 c3 → 分组头重现；再取消 → 消失（操作可重复）；
    /// ③ 归档 c2 → 行从列表消失（Mock 过滤归档行；连接态归档不过滤的 v4 缺口
    ///    由登录套件 test12 如实标注，替身侧零 execution 命令证据见 test12）；
    /// ④ 搜索过滤 → 不匹配行隐藏，清空恢复。
    func test07_demoConversationListOpsPinnedArchiveSearchLoop() throws {
        let app = launch()

        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "演示列表应加载（置顶行 c1 可见）")
        XCTAssertTrue(app.staticTexts["置顶"].waitForExistence(timeout: 6),
                      "c1 默认置顶，应存在「置顶」分组头")

        // ① 取消 c1 置顶 →「置顶」分组头消失
        swipeRowAndTapAction(app, row: element(app, "04-row-c1"),
                             actionIdentifier: "04-rowact-pin-c1",
                             message: "左滑置顶行 c1 应出现取消置顶动作")
        XCTAssertTrue(waitDisappear(app.staticTexts["置顶"], timeout: 6,
                                    "取消唯一置顶行后「置顶」分组头应消失"))

        // ② 置顶 c3 → 分组头重现；再取消 → 消失（置顶操作可重复）
        swipeRowAndTapAction(app, row: element(app, "04-row-c3"),
                             actionIdentifier: "04-rowact-pin-c3",
                             message: "左滑 c3 应出现置顶动作")
        XCTAssertTrue(app.staticTexts["置顶"].waitForExistence(timeout: 6),
                      "置顶 c3 后「置顶」分组头应重现")
        XCTAssertTrue(element(app, "04-row-c3").exists, "置顶后 c3 应在列表置顶分组内")
        swipeRowAndTapAction(app, row: element(app, "04-row-c3"),
                             actionIdentifier: "04-rowact-pin-c3",
                             message: "左滑置顶行 c3 应出现取消置顶动作")
        XCTAssertTrue(waitDisappear(app.staticTexts["置顶"], timeout: 6,
                                    "再次取消置顶后「置顶」分组头应消失"))

        // ③ 归档 c2 → 行消失
        swipeRowAndTapAction(app, row: element(app, "04-row-c2"),
                             actionIdentifier: "04-rowact-archive-c2",
                             message: "左滑 c2 应出现归档动作")
        XCTAssertTrue(waitDisappear(element(app, "04-row-c2"), timeout: 6,
                                    "归档后 c2 行应从列表消失"))

        // ④ 搜索过滤：「性能」仅保留 c5（首页性能调优），清空恢复
        let searchInput = element(app, "04-search-input")
        XCTAssertTrue(searchInput.waitForExistence(timeout: 6), "会话页应有搜索输入框")
        // 点击聚焦并等键盘弹出（列表刷新期 tap 可能落空，带效果重试）
        _ = tapUntilKeyboard(searchInput, app: app)
        XCTAssertTrue(app.keyboards.firstMatch.exists, "搜索框聚焦后键盘应弹出")
        searchInput.typeText("性能")
        XCTAssertTrue(waitDisappear(element(app, "04-row-c1"), timeout: 6,
                                    "搜索后不匹配行 c1 应隐藏"))
        XCTAssertTrue(element(app, "04-row-c5").waitForExistence(timeout: 3),
                      "标题含「性能」的 c5 应保留")
        searchInput.typeText(String(repeating: "\u{8}", count: 8))
        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 6),
                      "清空搜索词后列表应恢复")
    }

    // MARK: - 流程 8：列表分区头与行信息层级 + 演示态额度回退（贯穿约束抽查）

    /// ① 分区渲染：置顶 + 项目分组头（要求 4：分组键=会话自带工作区字段——mock c1/c6→zcode、
    /// c3→notes、c4→api、c5→zcode-mobile 四组；c2 未携带 workspace 字段 → 归「其它」组不丢弃；
    /// 日期分组已被项目分组取代）；② 行信息层级：标题 + 运行中胶囊（c5）+ 未读徽章（c2=2）；
    /// ③ H10：演示态额度假值（68% · 340/500）已永久移除——无数据不渲染进度条与百分比、
    ///    明细恒诚实文案；连接态替身投影由登录套件 test14 断言。
    func test08_listGroupHeadersRowHierarchyAndDemoQuota() throws {
        let app = launch()

        XCTAssertTrue(element(app, "04-row-c1").waitForExistence(timeout: 10),
                      "演示列表应加载（置顶行 c1 可见）")
        XCTAssertTrue(app.staticTexts["置顶"].waitForExistence(timeout: 6),
                      "c1 置顶应存在「置顶」分组头")
        // 要求 4：项目分组头（≥3 组断言：zcode/notes/api/zcode-mobile 四组）
        XCTAssertTrue(app.staticTexts["zcode"].waitForExistence(timeout: 6),
                      "项目分组头「zcode」应存在（c1/c6，分组键取会话自带工作区字段）")
        XCTAssertTrue(app.staticTexts["notes"].waitForExistence(timeout: 4),
                      "项目分组头「notes」应存在（c3）")
        XCTAssertTrue(app.staticTexts["api"].waitForExistence(timeout: 4),
                      "项目分组头「api」应存在（c4）")
        XCTAssertTrue(app.staticTexts["zcode-mobile"].waitForExistence(timeout: 4),
                      "项目分组头「zcode-mobile」应存在（c5）")
        // 「任务」组（未绑定项目会话，G-018 后组名与桌面侧栏对齐：原「其它」改「任务」）
        // 排在分组序列末尾，LazyVStack 视口外不物化——有界下滑揭示后再断言
        if !app.staticTexts["任务"].exists {
            for _ in 0..<4 where !app.staticTexts["任务"].exists {
                app.swipeUp()
            }
        }
        XCTAssertTrue(app.staticTexts["任务"].waitForExistence(timeout: 4),
                      "归属未知的 c2 应归「任务」组而非丢弃（组名与桌面侧栏同名语义）")

        // 行信息层级：c5 运行中胶囊 + c2 未读徽章计数
        let c5 = element(app, "04-row-c5")
        XCTAssertTrue(c5.waitForExistence(timeout: 6), "运行中会话行（c5）应显示")
        XCTAssertTrue(c5.staticTexts["运行中"].exists, "运行中会话行应带「运行中」胶囊")
        let c2 = element(app, "04-row-c2")
        // c2 排在任务组内 c5 之后，揭示组头时可能刚好停在视口下缘外——继续滚动揭示
        XCTAssertTrue(scrollReveal(app, "04-row-c2"), "未读会话行（c2）应显示（任务组滚动揭示）")
        XCTAssertTrue(c2.staticTexts["2"].waitForExistence(timeout: 4),
                      "未读徽章应显示计数 2（mock unreadCount=2）")

        // H10：演示态额度假值已移除——无真实数据时进度条与百分比整块不渲染，
        // 明细恒诚实文案「额度未获取 · 连接后自动刷新」（付费承诺性假信息不得呈现）。
        element(app, "12-tab-me").tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 8), "设置页应显示用户卡")
        XCTAssertFalse(app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", "68%")).firstMatch.exists,
                       "无额度数据时不得渲染演示百分比 68%")
        XCTAssertTrue(app.staticTexts["额度未获取 · 连接后自动刷新"].waitForExistence(timeout: 4),
                      "额度明细应为诚实文案「额度未获取 · 连接后自动刷新」")
    }
}

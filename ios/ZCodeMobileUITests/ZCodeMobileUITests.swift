import XCTest

/// ZCode iOS 冒烟 UI 用例（可跑通基线）。
/// 选择器全部基于 accessibilityIdentifier（三段式命名，对齐 design-spec.md 5.10）。
/// 由统一门禁脚本执行，不在本地跑。
final class ZCodeMobileUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        return app
    }

    /// 任意元素类型按 id 查找（按钮/静态文本/容器统一）
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// 关键界面截图附件（keepAlways；验收检查点导出后复核）
    private func snap(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    // MARK: - Tab 导航

    func testTabsSwitchAndRootsExist() throws {
        let app = launch()

        let tasksTab = element(app, "02-tab-tasks")
        let chatTab = element(app, "04-tab-chat")
        let reviewTab = element(app, "08-tab-review")
        let meTab = element(app, "12-tab-me")
        XCTAssertTrue(tasksTab.waitForExistence(timeout: 10), "任务 Tab 不存在")
        XCTAssertTrue(chatTab.exists, "对话 Tab 不存在")
        XCTAssertTrue(reviewTab.exists, "审查 Tab 不存在")
        XCTAssertTrue(meTab.exists, "我的 Tab 不存在")

        chatTab.tap()
        XCTAssertTrue(element(app, "04-search-input").waitForExistence(timeout: 6), "对话 Tab 根页未渲染")
        XCTAssertTrue(element(app, "04-act-new").exists, "会话页应有新建按钮")

        reviewTab.tap()
        XCTAssertTrue(element(app, "08-branch-switcher").waitForExistence(timeout: 6), "审查 Tab 应有分支胶囊")

        meTab.tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 6), "设置页应有用户卡")
        XCTAssertTrue(element(app, "12-row-appearance").exists, "设置页应有外观行")

        tasksTab.tap()
        XCTAssertTrue(element(app, "02-fab-newtask").waitForExistence(timeout: 6), "任务看板应有 FAB")
    }

    // MARK: - 看板 → 新建任务 Sheet

    func testDashboardNewTaskSheet() throws {
        let app = launch()
        let fab = element(app, "02-fab-newtask")
        XCTAssertTrue(fab.waitForExistence(timeout: 10), "FAB 不存在")
        fab.tap()

        let submit = element(app, "03-submit-start")
        XCTAssertTrue(submit.waitForExistence(timeout: 6), "新建任务 Sheet 应有开始按钮")
        let titleInput = element(app, "03-input-title")
        XCTAssertTrue(titleInput.exists, "新建任务应有输入区")
        titleInput.tap()
        titleInput.typeText("调研 Swift Concurrency 迁移方案")
        submit.tap()
    }

    // MARK: - 会话流（打开 → 发送 → 流式回复）

    func testChatFlowSendAndReceive() throws {
        let app = launch()
        XCTAssertTrue(element(app, "02-tab-tasks").waitForExistence(timeout: 10))

        let row = element(app, "04-row-c1")
        if row.waitForExistence(timeout: 6) {
            // 从看板置顶卡进入
            row.tap()
        } else {
            // 或从对话 Tab 会话行进入
            element(app, "04-tab-chat").tap()
            let chatRow = element(app, "04-row-c1")
            XCTAssertTrue(chatRow.waitForExistence(timeout: 6), "会话列表应有置顶会话行")
            chatRow.tap()
        }

        let input = element(app, "05-composer-input")
        XCTAssertTrue(input.waitForExistence(timeout: 8), "对话页应有输入框")
        input.tap()
        input.typeText("帮我检查一下最新的改动")

        let send = element(app, "05-composer-send")
        XCTAssertTrue(send.exists, "应有发送键")
        send.tap()
        // 流式回复进行中发送键仍可达
        XCTAssertTrue(send.waitForExistence(timeout: 4))
    }

    // MARK: - 审批（去审批 → 审批 Sheet）

    func testTaskBoardApprovalSheet() throws {
        let app = launch()
        let approveEntry = element(app, "02-taskcard-approve")
        XCTAssertTrue(approveEntry.waitForExistence(timeout: 10), "待操作任务卡应有去审批按钮")
        approveEntry.tap()

        // 打开待批准会话时审批 Sheet 自动直达（design-spec 屏 06）
        let approve = element(app, "06-act-approve")
        XCTAssertTrue(approve.waitForExistence(timeout: 8), "审批 Sheet 应有批准按钮")
        XCTAssertTrue(element(app, "06-act-reject").exists, "审批 Sheet 应有拒绝按钮")
        XCTAssertTrue(element(app, "06-choice-once").exists, "默认授权范围应为仅本次")

        element(app, "06-choice-always").tap()
        approve.tap()
    }

    // MARK: - 执行输出（会话 → 屏 07 终端）

    func testTerminalOutput() throws {
        let app = launch()
        XCTAssertTrue(element(app, "02-tab-tasks").waitForExistence(timeout: 10))

        let card = element(app, "02-taskcard-c2")
        XCTAssertTrue(card.waitForExistence(timeout: 6), "任务看板应有进行中任务")
        card.tap()

        let outputEntry = element(app, "05-btn-output")
        XCTAssertTrue(outputEntry.waitForExistence(timeout: 8), "会话页应有执行输出入口")
        outputEntry.tap()

        XCTAssertTrue(element(app, "07-terminal").waitForExistence(timeout: 6), "应显示终端卡")
        XCTAssertTrue(element(app, "07-act-copyall").exists, "应有复制全部")
        XCTAssertTrue(element(app, "07-act-stop").exists, "应有停止任务")
    }

    // MARK: - Diff 审查（展开 → 行着色结构 → 批准）

    func testDiffReviewRowsAndApprove() throws {
        let app = launch()
        let reviewTab = element(app, "08-tab-review")
        XCTAssertTrue(reviewTab.waitForExistence(timeout: 10))
        reviewTab.tap()

        let toggle = element(app, "08-filecard-toggle-d1")
        XCTAssertTrue(toggle.waitForExistence(timeout: 8), "应有 diff 文件卡")
        // 默认已展开 d1；确保展开态
        if !element(app, "08-diffrow-hunk-1").exists {
            toggle.tap()
        }

        XCTAssertTrue(element(app, "08-diffrow-add-1").waitForExistence(timeout: 5), "应有新增行")
        XCTAssertTrue(element(app, "08-diffrow-del-1").exists, "应有删除行")

        let approve = element(app, "08-filecard-approve")
        XCTAssertTrue(approve.waitForExistence(timeout: 4), "应有批准此文件")
        approve.tap()

        let approveAll = element(app, "08-act-approve-all")
        XCTAssertTrue(approveAll.waitForExistence(timeout: 4), "底部动作栏应有全部批准")
    }

    // MARK: - 产物预览（审查 → 在预览中打开 → 分段/文件树）

    func testFilePreviewAndDrawer() throws {
        let app = launch()
        let reviewTab = element(app, "08-tab-review")
        XCTAssertTrue(reviewTab.waitForExistence(timeout: 10))
        reviewTab.tap()

        let openPreview = element(app, "08-filecard-open-preview-d1")
        XCTAssertTrue(openPreview.waitForExistence(timeout: 8), "文件卡应有预览入口")
        openPreview.tap()

        XCTAssertTrue(element(app, "09-seg-preview").waitForExistence(timeout: 6), "预览页应有分段控件")
        XCTAssertTrue(element(app, "09-seg-source").exists, "应有源码段")

        // 源码段 = 行号源码视图（Markdown 走 TinyMarkdown 预览 / 代码走行号源码视图切换）
        element(app, "09-seg-source").tap()
        XCTAssertTrue(element(app, "09-source").waitForExistence(timeout: 6), "源码段应显示行号源码视图")
        snap(app, "40-preview-source-lineview")

        let browse = element(app, "09-drawer-filetree")
        XCTAssertTrue(browse.waitForExistence(timeout: 4), "应有浏览工作区文件入口")
        browse.tap()

        let fileRow = element(app, "09-row-file-DESIGN.md")
        XCTAssertTrue(fileRow.waitForExistence(timeout: 6), "文件树应列出 DESIGN.md")
    }

    // MARK: - 设置（UserDefaults 持久化）

    func testSettingsAppearancePersist() throws {
        let app = launch()
        let meTab = element(app, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10))
        meTab.tap()

        let appearanceRow = element(app, "12-row-appearance")
        XCTAssertTrue(appearanceRow.waitForExistence(timeout: 6), "应有外观行")
        appearanceRow.tap()

        let darkOption = element(app, "12-appearance-dark")
        XCTAssertTrue(darkOption.waitForExistence(timeout: 6), "应有 Zai Dark 选项")
        darkOption.tap()

        // 退出重进，验证 UserDefaults 回读
        app.terminate()
        app.launch()
        element(app, "12-tab-me").tap()
        element(app, "12-row-appearance").tap()
        XCTAssertTrue(element(app, "12-appearance-dark").waitForExistence(timeout: 6), "重启后外观应保持 Zai Dark")
    }

    // MARK: - 设置（通知开关持久化，P1-7 真实化后的开关回归）

    /// 「通知」开关（12-row-notify，设置页唯一 Toggle 行）驱动 UNUserNotificationCenter
    /// 授权与本地通知投递；本用例沿用 testSettingsAppearancePersist 模式断言开关值的
    /// UserDefaults 持久化。方向无关设计：读取当前值 → 翻转 → 冷启动回读翻转值 → 还原，
    /// 套件可重复执行（UserDefaults 在用例间不重置）。通知实际到达与点击路由为真机手动
    /// 验收项（模拟器 UNUserNotification 行为不完整），验收单如实标注。
    func testSettingsNotificationsTogglePersist() throws {
        let app = launch()
        let meTab = element(app, "12-tab-me")
        XCTAssertTrue(meTab.waitForExistence(timeout: 10))
        meTab.tap()

        let toggle = app.switches.firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 6), "设置页应有「通知」开关（12-row-notify）")
        let initialValue = (toggle.value as? String) == "1"

        // 翻转：开→关（或关→开，方向无关）
        toggle.tap()
        let flippedValue = toggle.value as? String
        XCTAssertEqual(flippedValue, initialValue ? "0" : "1", "点击应翻转通知开关")

        // 冷启动回读：UserDefaults 持久化
        app.terminate()
        app.launch()
        element(app, "12-tab-me").tap()
        let toggleAgain = app.switches.firstMatch
        XCTAssertTrue(toggleAgain.waitForExistence(timeout: 6), "重启后设置页应仍有通知开关")
        XCTAssertEqual(toggleAgain.value as? String, flippedValue, "重启后通知开关应保持翻转后的值")

        // 还原初始值（避免影响套件内后续用例的开关态）；还原为开可能触发系统授权弹窗，
        // 出现则点「允许」，防残留弹窗干扰
        toggleAgain.tap()
        XCTAssertEqual(toggleAgain.value as? String, initialValue ? "1" : "0", "还原点击应恢复初始值")
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["允许", "Allow", "好", "OK"] {
            if springboard.buttons[label].waitForExistence(timeout: 2) {
                springboard.buttons[label].tap()
                break
            }
        }
    }
}

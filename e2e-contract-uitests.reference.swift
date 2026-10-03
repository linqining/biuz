// ============================================================
// ZCode Mobile · E2E 契约测试（对照物，不参与工程编译）
// 来源：工作区早期会话产物，由门禁测试工程师收编其中的 id 约定。
// 已实现侧的对应 id 见 ios/ZCodeMobileUITests/ZCodeMobileUITests.swift 与各 View 的 accessibilityIdentifier。
// 本文件仅作 id 契约参考：04-row-c1、03-input-title、12-usercard、07-terminal、
// 08-diffrow-add-1、12-appearance-dark、06-act-approve、02-fab-newtask 等。
// ============================================================

/*
import XCTest

final class ZCodeE2EContractReference: XCTestCase {

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    func testTabsSwitchAndRootsExist() throws {
        let app = XCUIApplication()
        app.launch()
        let chatTab = element(app, "04-tab-chat")
        let tasksTab = element(app, "02-tab-tasks")
        let filesTab = element(app, "08-tab-review")
        let settingsTab = element(app, "12-tab-me")
        XCTAssertTrue(chatTab.waitForExistence(timeout: 8))
        tasksTab.tap()
        XCTAssertTrue(element(app, "02-fab-newtask").waitForExistence(timeout: 5))
        filesTab.tap()
        XCTAssertTrue(element(app, "08-branch-switcher").waitForExistence(timeout: 5) || element(app, "08-empty").exists)
        settingsTab.tap()
        XCTAssertTrue(element(app, "12-usercard").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "12-row-appearance").exists)
        chatTab.tap()
        XCTAssertTrue(element(app, "04-act-new").waitForExistence(timeout: 5))
    }

    func testChatFlowSendAndReceive() throws {
        let app = XCUIApplication()
        app.launch()
        let row = element(app, "04-row-c1")
        XCTAssertTrue(row.waitForExistence(timeout: 6))
        row.tap()
        let input = element(app, "05-composer-input")
        XCTAssertTrue(input.waitForExistence(timeout: 6))
        input.tap()
        input.typeText("帮我检查一下最新的改动")
        element(app, "05-composer-send").tap()
    }

    func testNewConversationSheet() throws {
        let app = XCUIApplication()
        app.launch()
        element(app, "04-act-new").tap()
        let submit = element(app, "03-submit-start")
        XCTAssertTrue(submit.waitForExistence(timeout: 5))
        let titleInput = element(app, "03-input-title")
        titleInput.tap()
        titleInput.typeText("调研 Swift Concurrency 迁移方案")
        submit.tap()
    }

    func testTaskBoardApprovalSheet() throws {
        let app = XCUIApplication()
        app.launch()
        let approveEntry = element(app, "02-taskcard-approve")
        XCTAssertTrue(approveEntry.waitForExistence(timeout: 6))
        approveEntry.tap()
        let approve = element(app, "06-act-approve")
        XCTAssertTrue(approve.waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "06-act-reject").exists)
        XCTAssertTrue(element(app, "06-choice-once").exists)
        element(app, "06-choice-always").tap()
        approve.tap()
    }

    func testTerminalOutput() throws {
        let app = XCUIApplication()
        app.launch()
        element(app, "02-tab-tasks").tap()
        let card = element(app, "02-taskcard-t2")
        XCTAssertTrue(card.waitForExistence(timeout: 6))
        card.tap()
        XCTAssertTrue(element(app, "07-terminal").waitForExistence(timeout: 6))
        XCTAssertTrue(element(app, "07-act-copyall").exists)
        XCTAssertTrue(element(app, "07-act-stop").exists)
    }

    func testDiffReviewRowsAndApprove() throws {
        let app = XCUIApplication()
        app.launch()
        element(app, "08-tab-review").tap()
        let cardHead = element(app, "08-filecard-toggle-d1")
        XCTAssertTrue(cardHead.waitForExistence(timeout: 6))
        cardHead.tap()
        XCTAssertTrue(element(app, "08-diffrow-add-1").waitForExistence(timeout: 5) || element(app, "08-diffrow-hunk-1").exists)
        XCTAssertTrue(element(app, "08-filecard-approve").waitForExistence(timeout: 4))
        element(app, "08-filecard-approve").tap()
        XCTAssertTrue(element(app, "08-act-approve-all").waitForExistence(timeout: 4))
    }

    func testFileTreeAndPreview() throws {
        let app = XCUIApplication()
        app.launch()
        element(app, "08-tab-review").tap()
        let browse = element(app, "08-act-browse")
        XCTAssertTrue(browse.waitForExistence(timeout: 6))
        browse.tap()
        let fileRow = element(app, "09-row-file-DESIGN.md")
        XCTAssertTrue(fileRow.waitForExistence(timeout: 6))
        fileRow.tap()
        XCTAssertTrue(element(app, "09-seg-preview").waitForExistence(timeout: 6) || element(app, "09-seg-source").exists)
    }

    func testSettingsAppearancePersist() throws {
        let app = XCUIApplication()
        app.launch()
        element(app, "12-tab-me").tap()
        let appearanceRow = element(app, "12-row-appearance")
        XCTAssertTrue(appearanceRow.waitForExistence(timeout: 6))
        appearanceRow.tap()
        let darkOption = element(app, "12-appearance-dark")
        XCTAssertTrue(darkOption.waitForExistence(timeout: 5))
        darkOption.tap()
        app.terminate()
        app.launch()
        element(app, "12-tab-me").tap()
        element(app, "12-row-appearance").tap()
        XCTAssertTrue(element(app, "12-appearance-dark").waitForExistence(timeout: 5))
    }
}
*/

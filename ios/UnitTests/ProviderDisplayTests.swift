import XCTest
@testable import ZCodeMobile

/// 供应商连接状态展示门（2026-10-08 报障「供应商没翻译」+「对话框模型选不了」）：
/// providerName 优先 → 内置 id 中文映射 → 原样 id；拒因四值词表中文化
/// 【实证·上游仓 shared/src/account-provider-state.ts:7-11】；ModelSelectionInfo
/// hasSelectableModels 是会话内模型面板守卫开门的判据（空清单 ≠ 无清单）。
final class ProviderDisplayTests: XCTestCase {
    private func connection(
        id: String, name: String? = nil, reason: String? = nil
    ) -> ModelSettingsView.ProviderConnection {
        ModelSettingsView.ProviderConnection(
            providerId: id, providerName: name,
            availability: reason == nil ? "available" : "unavailable",
            unavailableReason: reason)
    }

    func testProviderNameWinsOverBuiltinMapping() {
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(
                connection(id: "account:zai-individual-coding-plan", name: "Z.ai Account")),
            "Z.ai Account", "桌面回执 providerName 是权威展示名")
    }

    func testBuiltinIdFallbackMapping() {
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:zai-individual-coding-plan")),
            "Z.ai 个人套餐")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:zai-team-coding-plan")),
            "Z.ai 团队套餐")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:zai-start-plan")),
            "Z.ai 体验套餐")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:bigmodel-individual-coding-plan")),
            "BigModel 个人套餐")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:bigmodel-team-coding-plan")),
            "BigModel 团队套餐")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:bigmodel-start-plan")),
            "BigModel 体验套餐")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "new-provider")),
            "new-provider", "未知 id 原样透出（不虚构翻译）")
        XCTAssertEqual(
            ModelSettingsView.providerDisplayName(connection(id: "account:zai-team-coding-plan", name: "  ")),
            "Z.ai 团队套餐", "providerName 空白串视同缺席")
    }

    func testUnavailableReasonTranslation() {
        XCTAssertEqual(ModelSettingsView.unavailableReasonText("not-authenticated"), "未认证")
        XCTAssertEqual(ModelSettingsView.unavailableReasonText("not-connected"), "未连接")
        XCTAssertEqual(ModelSettingsView.unavailableReasonText("credential-failed"), "凭据校验失败")
        XCTAssertEqual(ModelSettingsView.unavailableReasonText("not-entitled"), "未订阅该套餐")
        XCTAssertNil(ModelSettingsView.unavailableReasonText(nil))
        XCTAssertEqual(ModelSettingsView.unavailableReasonText("future-reason"),
                       "future-reason", "未知拒因原样透出（宽容解析≠协议事实）")
    }

    func testHasSelectableModelsDiscriminatesEmptySeed() {
        var selection = ModelSelectionInfo()
        XCTAssertFalse(selection.hasSelectableModels, "空实例（overlay 种子态）= 无可选清单")
        selection.models = ["GLM-5.3"]
        XCTAssertTrue(selection.hasSelectableModels)
        selection.models = []
        selection.planGroups = [ModelPlanGroup(plan: "个人套餐", models: ["GLM-5.3"])]
        XCTAssertTrue(selection.hasSelectableModels, "仅套餐分组在场也算可选")
    }
}

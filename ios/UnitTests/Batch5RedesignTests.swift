import XCTest
@testable import ZCodeMobile

// MARK: - 批5 重设计单测（2026-11-25，design/design-spec.md §3.3/§3.4）
//
// 覆盖三块新逻辑：① ToolPlanAdapter（TodoWrite/TodoRead 工具行 → 待办步骤，
// 上游 tool-plan-adapter.ts 移植）② parseStateTodos 的 plan.items 权威源优先
// ③ ModelPickerRecents（模型选择器最近使用，cap 3）。

final class Batch5RedesignTests: XCTestCase {

    // MARK: ToolPlanAdapter.isTodoPlanToolName（上游 TODO_TOOL_NAME_PATTERN 逐字移植）

    func testTodoPlanToolNameMatchesTodoWriteReadAndUpdatePlan() {
        XCTAssertTrue(ToolPlanAdapter.isTodoPlanToolName("TodoWrite"))
        XCTAssertTrue(ToolPlanAdapter.isTodoPlanToolName("todo_write"))
        XCTAssertTrue(ToolPlanAdapter.isTodoPlanToolName("TodoRead"))
        XCTAssertTrue(ToolPlanAdapter.isTodoPlanToolName("update_plan"))
        XCTAssertTrue(ToolPlanAdapter.isTodoPlanToolName("mcp__todo-write"))
        XCTAssertTrue(ToolPlanAdapter.isTodoPlanToolName("update-plan"))
    }

    func testTodoPlanToolNameRejectsLooseTodoSubstrings() {
        // 上游正则的否定面：TodoList/TodoRite 不匹配（todo 后必须跟 read/write）
        XCTAssertFalse(ToolPlanAdapter.isTodoPlanToolName("TodoList"))
        XCTAssertFalse(ToolPlanAdapter.isTodoPlanToolName("todo_agenda"))
        XCTAssertFalse(ToolPlanAdapter.isTodoPlanToolName(nil))
        XCTAssertFalse(ToolPlanAdapter.isTodoPlanToolName(""))
        // Bash 等无关工具不得误判
        XCTAssertFalse(ToolPlanAdapter.isTodoPlanToolName("Bash"))
    }

    // MARK: ToolPlanAdapter.planSteps（input/output 双源 + 整组放弃口径）

    func testPlanStepsFromOutputTodosArray() {
        let output = #"{"todos":[{"id":"1","content":"定位卡顿","status":"completed"},{"id":"2","content":"改懒加载","status":"in_progress"},{"id":"3","content":"回归","status":"pending"}]}"#
        let steps = ToolPlanAdapter.planSteps(inputText: nil, outputText: output)
        XCTAssertEqual(steps?.count, 3)
        XCTAssertEqual(steps?[0].state, .done)
        XCTAssertEqual(steps?[1].state, .now)
        XCTAssertEqual(steps?[2].state, .todo)
    }

    func testPlanStepsFallbackToInputWhenOutputEmpty() {
        let input = #"{"plan":[{"content":"只写 input 的清单","status":"completed"}]}"#
        let steps = ToolPlanAdapter.planSteps(inputText: input, outputText: nil)
        XCTAssertEqual(steps?.count, 1)
        XCTAssertEqual(steps?.first?.title, "只写 input 的清单")
    }

    func testPlanStepsWholeGroupAbandonedOnUnparsableItem() {
        // 任一条目缺 content/status → 整组放弃（上游 steps.count == collection.length 口径），
        // 调用方回退原始工具卡
        let output = #"{"todos":[{"content":"好的","status":"completed"},{"title":"缺 status"}]}"#
        XCTAssertNil(ToolPlanAdapter.planSteps(inputText: nil, outputText: output))
    }

    func testPlanStepsRejectsNonTodoPayloadShapes() {
        // 非 JSON / 非清单键 / 空数组 → nil（不猜散键）
        XCTAssertNil(ToolPlanAdapter.planSteps(inputText: "not json", outputText: nil))
        XCTAssertNil(ToolPlanAdapter.planSteps(inputText: #"{"other":[1,2]}"#, outputText: nil))
        XCTAssertNil(ToolPlanAdapter.planSteps(inputText: #"{"todos":[]}"#, outputText: nil))
    }

    // MARK: parseStateTodos（state.plan.items 权威源优先，design-spec §3.4）

    func testParseStateTodosPrefersPlanItemsOverLegacyKeys() {
        let state: JSONValue = .object([
            "plan": .object(["items": .array([
                .object(["id": .string("p1"), "content": .string("权威清单"), "status": .string("inProgress")]),
            ])]),
            "todos": .array([
                .object(["content": .string("旧键清单"), "status": .string("pending")]),
            ]),
        ])
        let todos = RemoteConversationStore.parseStateTodos(state)
        XCTAssertEqual(todos?.count, 1)
        XCTAssertEqual(todos?.first?.title, "权威清单")
        // planStateSchema 的 inProgress 经小写归一应命中 .now（修复混写 case 永不命中）
        XCTAssertEqual(todos?.first?.state, .now)
    }

    func testParseStateTodosFallsBackToLegacyTodosKey() {
        let state: JSONValue = .object([
            "todos": .array([
                .object(["content": .string("旧形态待办"), "status": .string("completed")]),
            ]),
        ])
        let todos = RemoteConversationStore.parseStateTodos(state)
        XCTAssertEqual(todos?.first?.title, "旧形态待办")
        XCTAssertEqual(todos?.first?.state, .done)
    }

    func testParseStateTodosNilWhenPlanItemsUnparsable() {
        // plan.items 在场但整组不可解析 → 权威缺席不落旧键（防两源拼凑）
        let state: JSONValue = .object([
            "plan": .object(["items": .array([.string("非对象条目")])]),
            "todos": .array([
                .object(["content": .string("旧键"), "status": .string("pending")]),
            ]),
        ])
        XCTAssertNil(RemoteConversationStore.parseStateTodos(state))
    }

    // MARK: ModelPickerSheet 分组装配（2026-11-25 用户裁决：非套餐 provider **平铺**为同级组）

    func testModelPickerGroupsFlattenNonPlanProvidersByProvider() {
        // ccgithub/deepseek 形状：provider 不在 planGroups 词表（非套餐），
        // 其模型只出现在 models 裸名表——修复前面板整组消失（Android 同构缺陷）
        let selection = ModelSelectionInfo(
            models: ["GLM-5.3", "deepseek/deepseek-v4.1-flash"],
            activeModel: "GLM-5.3",
            thoughtLevels: [],
            activeThoughtLevel: "medium",
            planGroups: [
                ModelPlanGroup(plan: "个人套餐", models: ["GLM-5.3", "GLM-5.3-Flash"]),
            ],
            modelProviders: ["GLM-5.3": "zai-individual-coding-plan",
                             "deepseek/deepseek-v4.1-flash": "ccgithub"],
            modelProvidersByPlan: ["个人套餐|GLM-5.3": "zai-individual-coding-plan"],
            activeProviderId: "zai-individual-coding-plan")
        let groups = ModelPickerSheet.makeGroups(selection)
        // 用户裁决（2026-11-25）：「其他的应该平铺，和我的同级，而不是只有一个其他」
        // → 未归属套餐的模型按 providerId 自成一档，与套餐组同级
        XCTAssertEqual(groups.count, 2, "套餐组 + provider 组")
        XCTAssertEqual(groups.last?.plan, "ccgithub", "组名取 providerId（去掉 : 前缀 / -plan 尾）")
        XCTAssertEqual(groups.last?.models, ["deepseek/deepseek-v4.1-flash"])
    }

    func testProviderGroupNameStripsPrefixAndPlanSuffix() {
        XCTAssertEqual(ModelPickerSheet.providerGroupName("ccgithub"), "ccgithub")
        XCTAssertEqual(ModelPickerSheet.providerGroupName("zai:individual-coding-plan"), "individual-coding")
        XCTAssertEqual(ModelPickerSheet.providerGroupName(nil), "其他", "无 provider 才回「其他」")
        XCTAssertEqual(ModelPickerSheet.providerGroupName("   "), "其他")
    }

    func testModelPickerGroupsNoOthersWhenAllClaimed() {
        let selection = ModelSelectionInfo(
            models: ["GLM-5.3"],
            activeModel: "GLM-5.3",
            planGroups: [ModelPlanGroup(plan: "个人套餐", models: ["GLM-5.3"])])
        let groups = ModelPickerSheet.makeGroups(selection)
        XCTAssertEqual(groups.count, 1, "全部模型已被套餐覆盖 → 无其他组")
    }

    // MARK: ModelPickerRecents（最近使用，cap 3）

    func testModelPickerRecentsCapAndOrder() {
        let saved = UserDefaults.standard.stringArray(forKey: ModelPickerRecents.storeKey)
        defer {
            if let saved {
                UserDefaults.standard.set(saved, forKey: ModelPickerRecents.storeKey)
            } else {
                UserDefaults.standard.removeObject(forKey: ModelPickerRecents.storeKey)
            }
        }
        UserDefaults.standard.removeObject(forKey: ModelPickerRecents.storeKey)
        ModelPickerRecents.record("个人套餐|A")
        ModelPickerRecents.record("个人套餐|B")
        ModelPickerRecents.record("体验套餐|C")
        ModelPickerRecents.record("其他|D")
        ModelPickerRecents.record("个人套餐|A") // 重复置顶去重
        let list = ModelPickerRecents.load()
        XCTAssertEqual(list, ["个人套餐|A", "其他|D", "体验套餐|C"], "重复置顶去重 + 超出 cap 3 淘汰最旧")
    }
}

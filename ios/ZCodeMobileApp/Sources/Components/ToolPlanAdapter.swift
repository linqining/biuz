import Foundation

// MARK: - TodoWrite/TodoRead 工具行 → 待办步骤解析（2026-11-25 批5，design-spec §3.4）
//
// 上游 `packages/shared/src/tool-plan-adapter.ts` 的 Swift 移植（file:line 对应）：
// - 工具名判据：`/(?:^|[_\s-])(?:todo[_\s-]*(?:read|write)|update[_\s-]*plan)(?:$|[_\s-])/i`
//   （tool-plan-adapter.ts:3-4 TODO_TOOL_NAME_PATTERN）；
// - 步骤集合键：todos|plan|steps|items（:5 PLAN_COLLECTION_KEYS）；
// - 步骤标题键：content|step|title|text|activeForm（:33-37）；状态 pending|in_progress|
//   completed（:15-21 normalizePlanStatus，`-` 归一）；
// - 整组判定：任一条目不可解析即整组放弃（:82 `steps.length === collection.length` 同口径，
//   宁可回退原始工具卡也不渲染半截清单）。
// 行内子代理镜像过滤（:124-143 isMainAgentToolProjectionSource，subagent/parentToolCallId
// 排除）在行层未取证的键不移植——移动端 toolCall 行当前不携 _meta，宽收集即可；
// 恢复条件 = 行 schema 新增镜像标记时补负控。
enum ToolPlanAdapter {
    /// 工具名是否 todo 族（上游正则逐字移植；toolIdentity 层的宽松 contains("todo")
    /// 仅决定工具卡族图标，本判据决定是否解析清单）
    static func isTodoPlanToolName(_ name: String?) -> Bool {
        guard let name, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        // (?:^|[_\s-])(?:todo[_\s-]*(?:read|write)|update[_\s-]*plan)(?:$|[_\s-])
        let pattern = "(?:^|[_\\s-])(?:todo[_\\s-]*(?:read|write)|update[_\\s-]*plan)(?:$|[_\\s-])"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return false
        }
        let range = NSRange(name.trimmingCharacters(in: .whitespaces).startIndex..., in: name)
        return regex.firstMatch(in: name, options: [], range: range) != nil
    }

    /// 从 input/output 载荷提取待办步骤。input 为 toolCall 行 inputText（JSON 串或
    /// 已是对象文本）；output 为 output.text（TodoWrite 回显清单 JSON 串）。
    /// 两者都试（上游 extractPlanStepsFromToolOutput 优先 output、回退 input 同序）。
    static func planSteps(inputText: String?, outputText: String?) -> [TodoItem]? {
        if let steps = steps(fromText: outputText, idPrefix: "todo-out") {
            return steps
        }
        return steps(fromText: inputText, idPrefix: "todo-in")
    }

    private static func steps(fromText text: String?, idPrefix: String) -> [TodoItem]? {
        guard let text, !text.isEmpty else { return nil }
        // inputText 可能是完整 JSON 对象串；解析失败直接放弃（不猜散键）
        guard let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data),
              let object = root as? [String: Any] else {
            return nil
        }
        for key in ["todos", "plan", "steps", "items"] {
            guard let collection = object[key] as? [Any], !collection.isEmpty else { continue }
            var steps: [TodoItem] = []
            for (index, element) in collection.enumerated() {
                guard let item = parseStep(element, id: "\(idPrefix)-\(index)") else {
                    // 任一条目不可解析 → 整组放弃（上游同口径）
                    steps.removeAll()
                    break
                }
                steps.append(item)
            }
            if !steps.isEmpty {
                return steps
            }
            if steps.isEmpty && !collection.isEmpty {
                // 该键存在但整组失败：不再尝试其余键（上游同口径：单一集合键语义）
                return nil
            }
        }
        return nil
    }

    private static func parseStep(_ element: Any, id: String) -> TodoItem? {
        if let title = element as? String {
            let trimmed = title.trimmingCharacters(in: .whitespaces)
            // 纯字符串步骤：上游 parsePlanStep 首条默认 in_progress、其余 pending（:23-27）
            guard !trimmed.isEmpty else { return nil }
            return TodoItem(id: id, title: trimmed, state: id.hasSuffix("-0") ? .now : .todo)
        }
        guard let dict = element as? [String: Any] else { return nil }
        let title = (dict["content"] as? String)
            ?? (dict["step"] as? String)
            ?? (dict["title"] as? String)
            ?? (dict["text"] as? String)
            ?? (dict["activeForm"] as? String)
        guard let title, !title.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        guard let status = normalizeStatus(dict["status"]) else { return nil }
        let stepId = (dict["id"] as? String) ?? id
        return TodoItem(id: stepId, title: title, state: status)
    }

    /// pending|in_progress|completed 归一（`-`→`_` 后小写；朱批 failed 扩展沿用
    /// RemoteConversationStore.mapTodoStatus 词表）
    private static func normalizeStatus(_ value: Any?) -> TodoState? {
        guard let raw = value as? String else { return nil }
        let normalized = raw.replacingOccurrences(of: "-", with: "_").lowercased()
        switch normalized {
        case "pending": return .todo
        case "in_progress": return .now
        case "completed": return .done
        default: return nil
        }
    }
}

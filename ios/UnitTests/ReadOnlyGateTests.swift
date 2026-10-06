import XCTest
@testable import ZCodeMobile

/// 边界纯函数单测（v3 纠偏口径）：
/// 放行 = 客户端发命令由桌面执行（消息/审批/停止/队列/会话管理/任务元数据）；
/// 拦截 = 手机直写面（文件直写/回滚/仓库破坏性写/宿主 terminal/桌面配置与凭据写族）。
/// 双向断言：放行面不得误杀（纠偏核心回归），直写面保持拦截（防回归底线）。
final class ReadOnlyGateTests: XCTestCase {

    // MARK: ① 放行面（v3 纠偏核心：桌面代执行命令全部放行）

    func testConversationMessageFaceAllowed() {
        for type in ["sendText", "retryTurn", "editUserQuery", "compact", "sendGoalCommand",
                     "pauseGoal", "resumeGoal", "cancelBackgroundWork"] {
            let envelope = Self.envelope(type: type, firstInput: nil)
            let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: "sendConversationCommandV4", arg: envelope)
            XCTAssertFalse(verdict.isBlocked, "v4 \(type) 属桌面代执行消息/会话面，应放行")
            XCTAssertEqual(verdict.classification, .command, "v4 \(type) 分类应为 command")
        }
    }

    func testConversationApprovalStopQueueAllowed() {
        for type in ["resolveInteraction", "stop", "sendQueuedNow", "setAutoDrain",
                     "respondWorkspaceHookReview", "resumeWorkflowRun", "startSavedWorkflow",
                     "amendWorkflowRunSettings"] {
            let envelope = Self.envelope(type: type, firstInput: nil)
            let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: "sendConversationCommandV4", arg: envelope)
            XCTAssertFalse(verdict.isBlocked, "v4 \(type) 属审批/停止/队列面，应放行")
        }
    }

    func testConversationEnvelopeCreateSessionWithFirstInputAllowed() {
        let envelope = Self.envelope(type: "createSession", firstInput: .object(["text": .string("你好")]))
        let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: "sendConversationCommandV4", arg: envelope)
        XCTAssertFalse(verdict.isBlocked, "createSession+firstInput（首条指令随建会话下发）应放行")
        XCTAssertEqual(verdict.classification, .command)
    }

    func testConversationEnvelopeCreateSessionDraftAllowed() {
        let envelope = Self.envelope(type: "createSession", firstInput: nil)
        let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: "sendConversationCommandV4", arg: envelope)
        XCTAssertFalse(verdict.isBlocked, "createSession 无 firstInput（draft）应放行")
        XCTAssertEqual(verdict.classification, .command)
    }

    func testConversationFileRewindBlocked() {
        let envelope = Self.envelope(type: "applyFileRewind", firstInput: nil)
        let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: "sendConversationCommandV4", arg: envelope)
        XCTAssertTrue(verdict.isBlocked, "applyFileRewind（会话内文件回退直写）应拦截")
        XCTAssertEqual(verdict.classification, .directWrite)
    }

    func testTaskApprovalStopQueueFaceAllowed() {
        // 审批应答/停止/消息投递/队列三件全部放行（v1.4 黑名单纠偏反转）
        for command in ["respondPermission", "respondElicitation", "stopGeneration",
                        "compactSession", "goalSession", "sendPrompt", "deliverSessionMessage",
                        "enqueueTaskCommand", "promoteTaskCommand", "cancelTaskCommand"] {
            let verdict = ReadOnlyGate.inspect(channel: "zcode-task", command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "zcode-task.\(command) 属桌面代执行面，应放行")
        }
    }

    func testAgentLegacyMessageFaceAllowed() {
        // 旧协议消息/会话管理入口随消息面纠偏一并放行
        for command in ["sendPrompt", "compactSession", "goalSession", "closeSession"] {
            let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "zcode-agent.\(command) 属消息/会话管理面，应放行")
        }
    }

    // MARK: ② 直写面（防回归底线）

    func testGitWriteCommandsAllowedWebParity() {
        // 2026-10-06 边界修订（用户裁决「和 web bundle 保持一致」）：git 写族与 web
        // 端同等放行（桌面代执行——web 端这些命令全部经桌面 git 服务执行）。
        // file 频道默认拒绝与 file.* 直写拦截保持不变（真·文件直写仍不接）。
        for command in ["stagePaths", "commit", "unstagePaths", "discardPaths", "push",
                        "switchBranch", "createBranchAndSwitch", "generateCommitMessage"] {
            let verdict = ReadOnlyGate.inspect(channel: "git", command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "git.\(command) 应与 web 对齐放行（桌面代执行）")
        }
    }

    func testGitReadCommandsAllowed() {
        for command in ["refresh", "getChanges", "getDiff"] {
            let verdict = ReadOnlyGate.inspect(channel: "git", command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "git.\(command) 纯读应放行")
        }
    }

    func testFileChannelDenyByDefaultWithReadWhitelist() {
        // 读白名单放行（RemoteFileStore 实际调用面 + 计划中的二进制预览读）
        for command in ["readdir", "readTextFile", "stat", "searchWorkspaceFiles", "readBinaryPreview"] {
            let verdict = ReadOnlyGate.inspect(channel: "file", command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "file.\(command) 读白名单应放行")
        }
        // 默认拒绝：已知写与未知命令（编辑器保存类/目录操作/任意未列入命令）一律拦截
        for command in ["writeTextFile", "writeWorkspaceFile", "writeFile", "mkdir",
                        "createDirectory", "delete", "rename", "move", "copy",
                        "applyPatch", "truncate", "saveDocument", "totallyUnknownFileCommand"] {
            let verdict = ReadOnlyGate.inspect(channel: "file", command: command, arg: .undefined)
            XCTAssertTrue(verdict.isBlocked, "file.\(command) 不在读白名单，应默认拒绝")
            XCTAssertEqual(verdict.classification, .directWrite)
        }
    }

    func testTaskConfigWriteFaceBlocked() {
        for command in ["setMode", "setConfigOption", "setModel", "setAutomationSessionConfig",
                        "restartWorkspaceProcess"] {
            let verdict = ReadOnlyGate.inspect(channel: "zcode-task", command: command, arg: .undefined)
            XCTAssertTrue(verdict.isBlocked, "zcode-task.\(command) 桌面配置写应拦截")
        }
    }

    func testTaskMetadataWriteAllowed() {
        // 任务元数据写（tasks-index）与审批/停止面一致放行
        for command in ["setTaskPinned", "setTaskUnread", "archiveTask", "unarchiveTask",
                        "renameTask", "listArchivedTasks"] {
            let verdict = ReadOnlyGate.inspect(channel: "zcode-task", command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "zcode-task.\(command) 元数据写应放行")
        }
    }

    func testZcodeAgentDirectWriteCommandsBlocked() {
        let commands = [
            "setModel", "setThoughtLevel", "setMode",
            "writeWorkspaceFile", "saveFile", "writeFile", "applyEdits",
            "respondSessionRuntimePreferences", "grantWorkspaceHookTrust",
            "listMcpServerStatuses", "generateWorkspaceText", "testModelConnectivity",
            "installPlugin", "uninstallPlugin", "setPluginEnabled", "configurePlugin",
            "createAutomation", "deleteAutomation", "runAutomationNow",
        ]
        for command in commands {
            let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: command, arg: .undefined)
            XCTAssertTrue(verdict.isBlocked, "zcode-agent.\(command)（直写/配置写面）应拦截")
        }
    }

    func testChannelBlacklistBlocked() {
        let cases: [(String, String)] = [
            ("terminal", "create"), ("terminal", "write"), ("terminal", "dispose"),
            ("setting", "update"), ("setting", "updateDataBaseDir"),
            ("onboarding-record", "appendRecord"), ("onboarding-record", "clearRecords"),
            ("settings-sync", "importSelected"),
            ("git-checkpoint", "createCheckpoint"), ("git-checkpoint", "restoreBetweenCheckpoints"),
            ("git-checkpoint", "deleteCheckpoint"),
            ("credential", "save"), ("credential", "delete"),
            ("oauth", "startOAuth"), ("oauth", "handleCallback"), ("oauth", "refreshToken"),
            ("oauth", "logout"), ("oauth", "logoutAll"),
            ("conversation-share", "publish"), ("conversation-share", "importShare"),
            ("window-controller", "mutateTask"),
            ("feedback", "create"), ("feedback", "uploadAttachment"),
            ("provider-settings", "createPersonalProvider"), ("provider-settings", "testModelConnectivity"),
            ("provider-provisioning-target", "apply"),
            ("skills", "setEnabled"), ("hooks", "saveHooks"), ("commands", "writeCommandFile"),
            ("subagents", "deleteAgent"), ("mcp-sync", "saveMcpToUserDirectory"),
            ("plugin-sync", "importPluginsArchive"), ("plugins", "installPlugin"),
            ("plugin-management", "setPluginEnabled"),
            ("bots", "handleInboundMessage"), ("bots", "beginFeishuRegistration"),
            ("off-peak-task", "createTask"), ("coding-plan-subscription", "payStripe"),
        ]
        for (channel, command) in cases {
            let verdict = ReadOnlyGate.inspect(channel: channel, command: command, arg: .undefined)
            XCTAssertTrue(verdict.isBlocked, "\(channel).\(command)（频道直写黑名单）应拦截")
        }
    }

    // MARK: ②' IM Bot 合法远控写放行（G-002~G-005：桌面宿主侧配置/凭据写，非手机直写仓库）

    func testBotLifecycleCommandsAllowed() {
        let cases: [(String, String)] = [
            ("bots", "saveBot"), ("bots", "resetBotState"),
            ("bots", "deleteBot"), ("bots", "removeBotSecret"),
            ("bots", "testBot"), ("bots", "createBindCode"),
        ]
        for (channel, command) in cases {
            let verdict = ReadOnlyGate.inspect(channel: channel, command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "\(channel).\(command) 已放行为合法远控写（桌面代执行），不应拦截")
        }
    }

    // MARK: ②'' Coding Plan 重置机会领取放行（G-042：usage-stats 频道一键领取入口）
    // 实现口径（ReadOnlyGate channelDirectWriteCommands["usage-stats"] = []）：
    // requestCodingPlanResetOpportunity / useCodingPlanReset 为「移动端发命令、桌面代执行」
    // 的合法远控写（非手机直写仓库文件）；usage-stats 其余均为读面。放行必须保持——
    // 反向回归：一旦有人把这两件加回黑名单，一键领取入口即被边界误杀（G-042 验收失败）。

    func testCodingPlanResetClaimCommandsAllowed() {
        let cases: [(String, String)] = [
            ("usage-stats", "requestCodingPlanResetOpportunity"),
            ("usage-stats", "useCodingPlanReset"),
        ]
        for (channel, command) in cases {
            let verdict = ReadOnlyGate.inspect(channel: channel, command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "\(channel).\(command) G-042 放行（一键领取入口），不应拦截")
        }
    }

    // MARK: ③ 放行回归（既有只读接入面不误杀）

    func testNewReadonlyFacesAllowed() {
        let cases: [(String, String)] = [
            ("file-watcher", "watch"), ("file-watcher", "unwatch"), ("file-watcher", "disposeAll"),
            ("zcode-task", "listTaskList"), ("zcode-task", "getTaskConfigOptions"),
            ("zcode-task", "getTaskModelSelection"), ("zcode-task", "getTaskTokenUsage"),
            ("zcode-agent", "listSessions"), ("zcode-agent", "readSession"),
            ("zcode-agent", "subscribeSessionsIndexV4"), ("zcode-agent", "resyncSessionsIndexV4"),
            ("zcode-agent", "subscribeConversationV4"), ("zcode-agent", "resyncConversationV4"),
            ("zcode-agent", "subscribeWorkspaceConfigV4"), ("zcode-agent", "resyncWorkspaceConfigV4"),
            ("zcode-agent", "unsubscribeWorkspaceConfigV4"), ("zcode-agent", "conversationFileChangesV4"),
            ("zcode-agent", "conversationRowsRangeV4"), ("zcode-agent", "helloConversationV4"),
            ("zcode-session", "promoteDeferredDraftSession"),
            ("model-selection", "getView"),
            ("oauth", "getProviders"), ("oauth", "getActiveProvider"), ("oauth", "restoreCachedSessionState"),
            ("usage-stats", "getCodingPlanUsageSnapshot"), ("usage-stats", "getCodingPlanResetStatus"),
        ]
        for (channel, command) in cases {
            let verdict = ReadOnlyGate.inspect(channel: channel, command: command, arg: .undefined)
            XCTAssertFalse(verdict.isBlocked, "\(channel).\(command) 只读面不得误杀")
        }
    }

    // MARK: ④ 拦截记录（blockedExecutionCalls 记录存在性：容器 + 可记录 reason）

    /// 出口记录契约：ZCodeServerConnection.call 在 directWrite 判定时把 verdict.reason
    /// append 进 blockedExecutionCalls（RPC 唯一出口，ZCodeServerConnection.swift call()）。
    /// 本用例断言记录链路的两个环节：① 记录容器存在且新连接初始为空；② 全部拦截面
    /// （git 写 / file 默认拒绝 / 配置写 / 频道黑名单 / v4 信封直写类）的 verdict 均携带
    /// 非空 reason——即任何一次真实拦截都必然产生一条可诊断记录。
    @MainActor
    func testBlockedVerdictsCarryRecordableReasonAndConnectionLogStartsEmpty() throws {
        // ① 记录容器存在、初始为空（新连接无拦截记录）
        let connection = ZCodeServerConnection()
        XCTAssertTrue(connection.blockedExecutionCalls.isEmpty,
                      "新连接的拦截记录应为空（blockedExecutionCalls 容器存在，仅真实拦截时 append）")

        // ② 拦截面 verdict 全部携带可记录 reason（出口 append 的字符串源）
        let blockedFaces: [(String, String)] = [
            // git 写族已按 web 对齐放行（2026-10-06），拦截面只剩 file/zcode-agent/task 等
            ("file", "writeTextFile"), ("file", "applyPatch"), ("file", "unknownWriteCmd"),
            ("zcode-task", "setModel"), ("zcode-task", "setConfigOption"),
            ("zcode-agent", "writeWorkspaceFile"), ("zcode-agent", "saveFile"),
            ("terminal", "create"), ("credential", "save"), ("oauth", "logout"),
        ]
        for (channel, command) in blockedFaces {
            let verdict = ReadOnlyGate.inspect(channel: channel, command: command, arg: .undefined)
            XCTAssertTrue(verdict.isBlocked, "\(channel).\(command) 应拦截（前置）")
            let reason = try XCTUnwrap(verdict.reason,
                                       "\(channel).\(command) 拦截应携带 reason（blockedExecutionCalls 记录源）")
            XCTAssertFalse(reason.isEmpty, "\(channel).\(command) 的 reason 不应为空串")
        }

        // ③ v4 信封直写类（applyFileRewind）同样携带可记录 reason
        let envelope = Self.envelope(type: "applyFileRewind", firstInput: nil)
        let verdict = ReadOnlyGate.inspect(channel: "zcode-agent", command: "sendConversationCommandV4", arg: envelope)
        XCTAssertTrue(verdict.isBlocked, "applyFileRewind 应拦截（前置）")
        let reason = try XCTUnwrap(verdict.reason, "applyFileRewind 拦截应携带 reason")
        XCTAssertFalse(reason.isEmpty, "applyFileRewind 的 reason 不应为空串")
    }

    // MARK: 工具

    /// sendConversationCommandV4 信封构造（对齐 RemoteConversationStore.sendCommand）
    private static func envelope(type: String, firstInput: JSONValue?) -> RPCValue {
        var payloadFields: [String: JSONValue] = ["workspaceId": .string("/tmp/ws")]
        if let firstInput {
            payloadFields["firstInput"] = firstInput
        }
        return .json(.object([
            "commandId": .string(UUID().uuidString),
            "clientId": .string("zcode-mobile"),
            "sessionId": .null,
            "type": .string(type),
            "payload": .object(payloadFields),
            "issuedAt": .string("2026-01-01T00:00:00Z"),
        ]))
    }
}

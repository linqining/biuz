import Foundation

// MARK: - 移动端边界（产品边界 + API 层纵深防御）
//
// 边界口径（v3 纠偏，用户澄清）：客户端允许发送命令、由电脑/云端进程执行与修改；
// 手机端不得直接编辑电脑/云端中的文件（文件直写/回滚/编辑器保存类接口不接）。
// 因此分类语义为：
// - command   = 客户端发命令、桌面代执行：消息（sendText/retryTurn/editUserQuery）、
//   审批应答（resolveInteraction/respondWorkspaceHookReview/respondPermission/
//   respondElicitation）、停止与中断（stop/pauseGoal/resumeGoal/stopGeneration）、
//   队列管理（sendQueuedNow/setAutoDrain/enqueue/promote/cancelTaskCommand）、
//   会话/任务管理（compact/goal/sendPrompt/createSession+firstInput…）→ 放行；
// - session   = 会话元数据/账本写（置顶/归档/未读/renameTask/draft 转正）→ 放行；
// - readOnly  = 订阅/分页查询/附件读 → 放行；
// - directWrite = 手机直写面（文件直写/回滚/仓库破坏性写/宿主 terminal/桌面配置与
//   凭据写族）→ 一律拦截。与 v1.3/v1.4 的差别：v4 命令面只有 applyFileRewind
//   属文件直写（会话内文件回退），其余 v4 type 全部归 command 放行。
// file 频道采取「默认拒绝 + 读白名单」收口：仅放行 readdir/readTextFile/stat/
// searchWorkspaceFiles/readBinaryPreview（RemoteFileStore 实际调用面，本会话 grep
// 核对 RemoteFileStore.swift:123-390），任何未知/写命令（writeTextFile、mkdir、
// writeWorkspaceFile、编辑器保存类等）默认拒绝，保证「不直写文件」不依赖黑名单枚举。

/// 命令四分类
enum V4CommandClassification: Equatable {
    case readOnly    // 订阅/查询：无执行下游
    case session     // 会话元数据/账本写：不驱动 agent，放行
    case command     // 客户端发命令、桌面代执行（消息/审批/停止/队列/会话管理）：放行
    case directWrite // 手机直写面（文件直写/回滚/仓库写/宿主与配置写）：拦截
}

enum ReadOnlyGate {

    struct Verdict: Equatable {
        let classification: V4CommandClassification
        /// directWrite 时的人类可读拦截原因（其余为 nil）
        let reason: String?
        var isBlocked: Bool { classification == .directWrite }
    }

    /// sendConversationCommandV4 信封中唯一的直写类 type：applyFileRewind（会话内
    /// 文件回退，直接改写工作区文件历史）。其余 v4 type（sendText/stop/
    /// resolveInteraction/retryTurn/editUserQuery/队列/sendGoalCommand/compact/
    /// respondWorkspaceHookReview/cancelBackgroundWork/workflow 运行控制等）均属
    /// 「客户端发命令、桌面代执行」，放行。
    private static let directWriteConversationTypes: Set<String> = [
        "applyFileRewind",
    ]

    /// 双态 type：带 firstInput = command（首条指令随 createSession 下发、桌面立即
    /// 开跑，边界内允许）；不带 = session（空 draft 会话，不进 sqlite、不启动 turn）
    private static let firstInputDependentTypes: Set<String> = [
        "createSession", "createSelectionSideSession",
    ]

    /// zcode-task 频道的直写/配置写命令（黑名单口径，连接态出口必拦）：
    /// 会话配置与模型切换（set*）与桌面共享进程重启——属桌面配置写族，非本轮
    /// 开放对象（聊天页模型/思考档 chips 仍为只读展示，无调用入口）。
    /// 消息投递（sendPrompt/deliverSessionMessage）、审批应答（respondPermission/
    /// respondElicitation）、停止（stopGeneration）、会话管理（compactSession/
    /// goalSession）、队列三件（enqueueTaskCommand/promoteTaskCommand/
    /// cancelTaskCommand）全部放行（桌面代执行）。
    private static let directWriteTaskCommands: Set<String> = [
        "setMode", "setConfigOption", "setModel", "setAutomationSessionConfig",
        "restartWorkspaceProcess",
    ]

    /// git 频道的仓库写操作（getChanges/getDiff/refresh 为纯读，放行）：
    /// stagePaths/commit/unstagePaths/discardPaths/push/switchBranch/
    /// createBranchAndSwitch/generateCommitMessage——手机端不改仓库。
    private static let directWriteGitCommands: Set<String> = [
        "stagePaths", "commit",
        "unstagePaths", "discardPaths", "push",
        "switchBranch", "createBranchAndSwitch", "generateCommitMessage",
    ]

    /// file 频道读白名单（默认拒绝收口）：RemoteFileStore 实际调用面 + 计划中的
    /// 二进制预览读。白名单之外的任何 file 命令（含未知写命令 writeTextFile/
    /// writeWorkspaceFile/mkdir/delete/rename/applyPatch/编辑器保存类）一律拦截。
    private static let fileReadCommands: Set<String> = [
        "readdir", "readTextFile", "stat", "searchWorkspaceFiles", "readBinaryPreview",
    ]

    /// zcode-agent 频道直写命令黑名单（sendConversationCommandV4 之外的直发命令）。
    /// 旧协议消息/会话管理入口（sendPrompt/compactSession/goalSession/closeSession）
    /// 随消息面纠偏一并放行，不再列入；仍拦截：会话配置切换（set*）、编辑器保存类
    /// 文件直写（writeWorkspaceFile/saveFile/writeFile/applyEdits）、harness 配置写
    /// （respondSessionRuntimePreferences/grantWorkspaceHookTrust）、MCP 真实探测、
    /// 模型调用（generateWorkspaceText/testModelConnectivity）、插件写 12 族、
    /// workflow 文件写与自动化写族。
    private static let zcodeAgentDirectWriteCommands: Set<String> = [
        "setModel", "setThoughtLevel", "setMode",
        "writeWorkspaceFile", "saveFile", "writeFile", "applyEdits",
        "respondSessionRuntimePreferences", "grantWorkspaceHookTrust",
        "listMcpServerStatuses", "generateWorkspaceText", "testModelConnectivity",
        // 插件写族（改变 harness 插件工具面）
        "addPluginMarketplace", "removePluginMarketplace", "updatePluginMarketplace",
        "installPlugin", "cancelPluginOperation", "uninstallPlugin", "updatePlugin",
        "restoreBuiltinPlugin", "configurePlugin", "resetPluginConfig",
        "validatePlugin", "setPluginEnabled",
        // workflow 文件写（修改/删除/移动被引用的定义）
        "updateSavedWorkflowMeta", "deleteSavedWorkflow", "moveSavedWorkflow",
        // 自动化写族（布置/启停/立即派发未来自动执行）
        "createAutomation", "updateAutomation", "deleteAutomation",
        "setAutomationEnabled", "restartAutomation", "runAutomationNow",
        "deleteAutomationRun",
    ]

    /// 其余频道的直写命令黑名单（频道 → 命令集；未列入的频道走 default 放行）。
    /// 覆盖口径：宿主执行面（terminal）、桌面配置写（setting/onboarding-record/
    /// settings-sync）、仓库快照写（git-checkpoint）、凭据与 OAuth 写（credential/oauth
    /// 写族）、远端写（conversation-share/usage-stats 重置操作）、bot 凭据与配置写、
    /// harness 能力面配置写（skills/mcp-sync/plugin-sync/plugins/plugin-management/
    /// subagents/commands/hooks）、交易写（coding-plan-subscription/off-peak-task）、
    /// 窗口级 mutation（window-controller.mutateTask）。
    private static let channelDirectWriteCommands: [String: Set<String>] = [
        "terminal": ["create", "write", "resize", "dispose"],
        "setting": ["update", "updateDataBaseDir"],
        "onboarding-record": [
            "appendRecord", "dismissOnboarding", "claimAnonymousRecord",
            "syncSettingsFromRecord", "updateRecordPreferences", "clearRecords",
        ],
        "settings-sync": [
            "copyClaudeAgentsFileToZcodeAgentsFile", "importSelected",
            "markFirstRunPromptHandled",
        ],
        "git-checkpoint": ["createCheckpoint", "restoreBetweenCheckpoints", "deleteCheckpoint"],
        "credential": ["save", "delete"],
        "oauth": [
            "startOAuth", "startOAuthWithPolling", "handleCallback", "refreshToken",
            "logout", "logoutAll", "cancelPending",
        ],
        "conversation-share": ["publish", "importShare"],
        // G-042 放行：重置机会领取（request/use）为桌面代执行写，移动端一键领取入口；
        // markCodingPlanResetHistoryRead 本就不属直写。保留拦截面：无。
        "usage-stats": [],
        "coding-plan-subscription": [
            "createSign", "updateSign", "bindStripeCard", "unbindStripeCard", "payStripe",
            "createPaypalSetupToken", "subscribePaypal",
            "calculateEnterpriseOrder", "createEnterpriseOrder", "cancelEnterpriseOrder",
            "continueEnterpriseOrderPayment",
        ],
        "off-peak-task": [
            "createTask", "cancelTask", "pauseTask", "continueTask",
            "deleteTask", "deleteHistory", "updateTask",
        ],
        "window-controller": ["mutateTask"],
        "bots": [
            "beginFeishuRegistration", "pollFeishuRegistration",
            "beginWeixinRegistration", "pollWeixinRegistration",
            "saveConfig", "handleInboundMessage",
            "handleProviderCallback", "handleProviderCallbackResponse",
            // G-002~G-005 放行（P0 缺口矩阵）：saveBot（解绑=清 providerUserId，桌面
            // BotsDialog.tsx:1027-1035 同构）、resetBotState、deleteBot、removeBotSecret、
            // testBot、createBindCode——写的是桌面宿主侧 Bot 配置/凭据存储，非手机直写
            // 仓库文件，属「发命令由桌面执行」合法远控写。注册轮询与消息处理仍拦截。
        ],
        "skills": ["setEnabled", "copyToCommon", "removeFromCommon", "deleteSkill"],
        "skill-sync": ["importSkillsArchive"],
        "mcp-sync": ["listWorkspaceMcpServerStatuses", "saveMcpToUserDirectory", "importMcpServers"],
        "plugin-sync": ["importPluginsArchive", "importMarketplaceSourceArchive"],
        "plugins": [
            "addMarketplace", "removeMarketplace", "updateMarketplace",
            "installPlugin", "uninstallPlugin", "setPluginEnabled",
        ],
        "plugin-management": [
            "addPluginMarketplace", "removePluginMarketplace", "updatePluginMarketplace",
            "installPlugin", "cancelPluginOperation", "uninstallPlugin", "updatePlugin",
            "restoreBuiltinPlugin", "configurePlugin", "resetPluginConfig",
            "validatePlugin", "setPluginEnabled",
        ],
        "subagents": [
            "setEnabled", "setBuiltInModelOverride", "setPluginAgentModelOverride",
            "createAgent", "updateAgent", "deleteAgent",
        ],
        "commands": ["writeCommandFile", "updateCommandFile", "deleteCommandFile", "setCommandEnabled"],
        "hooks": ["saveHooks", "grantWorkspaceHookTrust"],
        "feedback": [
            "create", "cancelCreate", "comment", "uploadAttachment",
            "uploadAttachmentWithProgress", "cancelUpload", "uploadAttachmentData",
            "attachLogsFromExport", "prepareCompactLogArchive",
            "cleanupPreparedLogArchive", "revealLogArchive",
        ],
        "provider-settings": [
            "createPersonalProvider", "savePersonalProviderOverlay", "deletePersonalProvider",
            "reorderPersonalProviders", "reorderPersonalModels", "addPersonalModel",
            "renamePersonalModel", "deletePersonalModel", "savePersonalModelDraft",
            "setPersonalModelEnabled", "testModelConnectivity",
        ],
        "provider-provisioning-target": ["apply"],
    ]

    /// 对一次 channel RPC 出口做边界判定。
    /// - zcode-agent/zcode-task：消息/审批/停止/队列/会话管理放行（command），
    ///   配置与直写黑名单拦截（directWrite），其余默认只读；
    /// - git：读放行，仓库写拦截；
    /// - file：读白名单放行，其余（含未知）一律拦截（默认拒绝收口）；
    /// - 其余频道：黑名单拦截，默认放行（hello/订阅/查询链路不误杀）。
    static func inspect(channel: String, command: String, arg: RPCValue) -> Verdict {
        switch channel {
        case "zcode-agent":
            if command == "sendConversationCommandV4" {
                return inspectConversationCommand(arg)
            }
            if zcodeAgentDirectWriteCommands.contains(command) {
                return Verdict(
                    classification: .directWrite,
                    reason: "zcode-agent.\(command) 为直写/配置写面")
            }
            // hello/init、subscribe/unsubscribe/resync、rowsRange/plans/workflow 读族、
            // usage、commandsQuery、backgroundBashOutput、附件读族、eventListen 均 readonly；
            // 附件事务（attachmentBegin/Chunk/Commit/Abort）为存储写非手机直写，session 放行。
            return Verdict(classification: .readOnly, reason: nil)
        case "zcode-task":
            if directWriteTaskCommands.contains(command) {
                return Verdict(
                    classification: .directWrite,
                    reason: "zcode-task.\(command) 为桌面配置写面")
            }
            // 审批应答/停止/队列/消息投递/任务元数据写（setTaskPinned/archiveTask/
            // setTaskUnread/renameTask/unarchiveTask/listArchivedTasks）放行
            return Verdict(classification: .readOnly, reason: nil)
        case "git":
            if directWriteGitCommands.contains(command) {
                return Verdict(
                    classification: .directWrite,
                    reason: "git.\(command) 为工作区写操作")
            }
            return Verdict(classification: .readOnly, reason: nil)
        case "file":
            // 默认拒绝收口：仅读白名单放行，写/未知命令全部拦截
            if fileReadCommands.contains(command) {
                return Verdict(classification: .readOnly, reason: nil)
            }
            return Verdict(
                classification: .directWrite,
                reason: "file.\(command) 不在移动端读白名单（文件直写类不接）")
        default:
            if let blacklist = channelDirectWriteCommands[channel],
               blacklist.contains(command) {
                return Verdict(
                    classification: .directWrite,
                    reason: "\(channel).\(command) 为直写/配置写面")
            }
            // file-watcher、model-selection、zcode-session（promoteDeferredDraftSession
            // 属 task-index 元数据写）等移动端使用的只读/session 面放行。
            return Verdict(classification: .readOnly, reason: nil)
        }
    }

    /// sendConversationCommandV4 信封：applyFileRewind 拦截；带 firstInput 的
    /// createSession 与其余全部 type 归 command（客户端发命令、桌面代执行）放行。
    private static func inspectConversationCommand(_ arg: RPCValue) -> Verdict {
        let dict = arg.jsonValue?.objectValue ?? [:]
        let type = dict["type"]?.stringValue ?? ""
        if directWriteConversationTypes.contains(type) {
            return Verdict(
                classification: .directWrite,
                reason: "sendConversationCommandV4→\(type)（文件回退直写）")
        }
        if firstInputDependentTypes.contains(type),
           dict["payload"]?.objectValue?["firstInput"] != nil {
            return Verdict(
                classification: .command,
                reason: nil) // createSession+firstInput：首条指令随建会话下发，桌面开跑
        }
        // sendText/stop/resolveInteraction/队列/compact/goal 等全部 command 放行；
        // 无 firstInput 的 createSession（draft）保留 session 语义。
        return Verdict(classification: .command, reason: nil)
    }
}

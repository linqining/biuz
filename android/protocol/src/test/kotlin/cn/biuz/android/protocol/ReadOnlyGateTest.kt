package cn.biuz.android.protocol

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 直译自 ios/UnitTests/ReadOnlyGateTests.swift（22 用例，v3 纠偏口径）：
 * 放行 = 客户端发命令由桌面执行（消息/审批/停止/队列/会话管理/任务元数据）；
 * 拦截 = 手机直写面（文件直写/回滚/仓库破坏性写/宿主 terminal/桌面配置与凭据写族）。
 * 双向断言：放行面不得误杀（纠偏核心回归），直写面保持拦截（防回归底线）。
 *
 * 用例映射：22 用例一一对应同名方法；唯 iOS 第 19 用例
 * testBlockedVerdictsCarryRecordableReasonAndConnectionLogStartsEmpty 的环节①
 * （新连接 blockedExecutionCalls 初始为空）依赖 ZCodeServerConnection——该类属
 * connection 模块尚未移植（android/connection 现为占位），本测试类只平移其环节②③
 * （全部拦截面 verdict.reason 非空 + applyFileRewind reason 非空），约减如实记录于
 * 交付 deviations，未绕过任何可平移断言。
 *
 * 平移注记：
 * - iOS @MainActor 仅为主线程隔离（被测 inspect 是纯同步函数），Kotlin 无对应物，去修饰。
 * - 嵌套信封 issuedAt 原文为 .int(1_760_000_000_000)（64 位毫秒）；Kotlin JSONValue.Int
 *   为 32 位值域（RPCSerialization.kt 已声明口径：越界整数按 Codable 主路落 .double，
 *   RPCSerialization.swift:70-71），故落 JSONValue.Double 同值。该字段不参与 gate 判定
 *   （inspectConversationCommand 只读 type/payload），断言语义不变。
 * - 全部用例为同步断言，无 async 用例，不需要 kotlinx-coroutines-test runTest。
 */
class ReadOnlyGateTest {

    // MARK: ① 放行面（v3 纠偏核心：桌面代执行命令全部放行）

    @Test
    fun testConversationMessageFaceAllowed() {
        for (type in listOf(
            "sendText", "retryTurn", "editUserQuery", "compact", "sendGoalCommand",
            "pauseGoal", "resumeGoal", "cancelBackgroundWork",
        )) {
            val envelope = envelope(type = type, firstInput = null)
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-agent", command = "sendConversationCommandV4", arg = envelope)
            assertFalse(verdict.isBlocked, "v4 $type 属桌面代执行消息/会话面，应放行")
            assertEquals(
                V4CommandClassification.command, verdict.classification, "v4 $type 分类应为 command")
        }
    }

    @Test
    fun testConversationApprovalStopQueueAllowed() {
        for (type in listOf(
            "resolveInteraction", "stop", "sendQueuedNow", "setAutoDrain",
            "respondWorkspaceHookReview", "resumeWorkflowRun", "startSavedWorkflow",
            "amendWorkflowRunSettings",
        )) {
            val envelope = envelope(type = type, firstInput = null)
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-agent", command = "sendConversationCommandV4", arg = envelope)
            assertFalse(verdict.isBlocked, "v4 $type 属审批/停止/队列面，应放行")
        }
    }

    @Test
    fun testConversationEnvelopeCreateSessionWithFirstInputAllowed() {
        val envelope = envelope(
            type = "createSession",
            firstInput = JSONValue.Obj(mapOf("text" to JSONValue.Str("你好"))))
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = envelope)
        assertFalse(verdict.isBlocked, "createSession+firstInput（首条指令随建会话下发）应放行")
        assertEquals(V4CommandClassification.command, verdict.classification)
    }

    // 断言口径注记：iOS 原文此处断言 draft 分类为 .command（ReadOnlyGateTests.swift:43），
    // 与 iOS 实现代码一致（ReadOnlyGate.swift:294 末行 return .command；:293 注释却写
    // 「保留 session 语义」——注释与代码矛盾在 iOS 原文即存在）。上游佐证：firstInput
    // 缺省 → phase=draft 空会话不驱动 turn【实证·上游仓 command.ts:45】。本测试按 iOS
    // 测试原文断言 .command，Kotlin 实现（ReadOnlyGate.kt:334）行为逐字对应。
    @Test
    fun testConversationEnvelopeCreateSessionDraftAllowed() {
        val envelope = envelope(type = "createSession", firstInput = null)
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = envelope)
        assertFalse(verdict.isBlocked, "createSession 无 firstInput（draft）应放行")
        assertEquals(V4CommandClassification.command, verdict.classification)
    }

    @Test
    fun testConversationFileRewindBlocked() {
        val envelope = envelope(type = "applyFileRewind", firstInput = null)
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = envelope)
        assertTrue(verdict.isBlocked, "applyFileRewind（会话内文件回退直写）应拦截")
        assertEquals(V4CommandClassification.directWrite, verdict.classification)
    }

    @Test
    fun testTaskApprovalStopQueueFaceAllowed() {
        // 审批应答/停止/消息投递/队列三件全部放行（v1.4 黑名单纠偏反转）
        for (command in listOf(
            "respondPermission", "respondElicitation", "stopGeneration",
            "compactSession", "goalSession", "sendPrompt", "deliverSessionMessage",
            "enqueueTaskCommand", "promoteTaskCommand", "cancelTaskCommand",
        )) {
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-task", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "zcode-task.$command 属桌面代执行面，应放行")
        }
    }

    @Test
    fun testAgentLegacyMessageFaceAllowed() {
        // 旧协议消息/会话管理入口随消息面纠偏一并放行
        for (command in listOf("sendPrompt", "compactSession", "goalSession", "closeSession")) {
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-agent", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "zcode-agent.$command 属消息/会话管理面，应放行")
        }
    }

    // MARK: ② 直写面（防回归底线）

    @Test
    fun testGitWriteCommandsAllowedWebParity() {
        // 2026-10-06 边界修订（用户裁决「和 web bundle 保持一致」）：git 写族与 web
        // 端同等放行（桌面代执行——web 端这些命令全部经桌面 git 服务执行）。
        // file 频道默认拒绝与 file.* 直写拦截保持不变（真·文件直写仍不接）。
        for (command in listOf(
            "stagePaths", "commit", "unstagePaths", "discardPaths", "push",
            "switchBranch", "createBranchAndSwitch", "generateCommitMessage",
        )) {
            val verdict = ReadOnlyGate.inspect(
                channel = "git", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "git.$command 应与 web 对齐放行（桌面代执行）")
        }
    }

    @Test
    fun testGitReadCommandsAllowed() {
        for (command in listOf("refresh", "getChanges", "getDiff")) {
            val verdict = ReadOnlyGate.inspect(
                channel = "git", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "git.$command 纯读应放行")
        }
    }

    @Test
    fun testGitCheckpointCreateRestoreAllowed() {
        // P3-11 检查点（2026-10-06，设计稿 §11A 前置裁决）：创建/恢复与 web 对齐桌面
        // 代执行（gitCheckpointService 四方法词表，立项报告 §8.1:579，gitCheckpoint.ts:14-17）；
        // 移动端恢复入口硬要求 destructive 确认弹层。deleteCheckpoint 无 UI 入口维持拦截。
        for (command in listOf("diffCheckpoints", "createCheckpoint", "restoreBetweenCheckpoints")) {
            val verdict = ReadOnlyGate.inspect(
                channel = "git-checkpoint", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "git-checkpoint.$command 应放行（桌面代执行）")
        }
        val deleteVerdict = ReadOnlyGate.inspect(
            channel = "git-checkpoint", command = "deleteCheckpoint", arg = RPCValue.Undefined)
        assertTrue(deleteVerdict.isBlocked, "git-checkpoint.deleteCheckpoint 无 UI 入口应拦截")
    }

    @Test
    fun testFileChannelDenyByDefaultWithReadWhitelist() {
        // 读白名单放行（RemoteFileStore 实际调用面 + 计划中的二进制预览读）
        for (command in listOf("readdir", "readTextFile", "stat", "searchWorkspaceFiles", "readBinaryPreview")) {
            val verdict = ReadOnlyGate.inspect(
                channel = "file", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "file.$command 读白名单应放行")
        }
        // 默认拒绝：已知写与未知命令（编辑器保存类/目录操作/任意未列入命令）一律拦截
        for (command in listOf(
            "writeTextFile", "writeWorkspaceFile", "writeFile", "mkdir",
            "createDirectory", "delete", "rename", "move", "copy",
            "applyPatch", "truncate", "saveDocument", "totallyUnknownFileCommand",
        )) {
            val verdict = ReadOnlyGate.inspect(
                channel = "file", command = command, arg = RPCValue.Undefined)
            assertTrue(verdict.isBlocked, "file.$command 不在读白名单，应默认拒绝")
            assertEquals(V4CommandClassification.directWrite, verdict.classification)
        }
    }

    @Test
    fun testTaskConfigWriteFaceBlocked() {
        for (command in listOf(
            "setMode", "setConfigOption", "setModel", "setAutomationSessionConfig",
            "restartWorkspaceProcess",
        )) {
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-task", command = command, arg = RPCValue.Undefined)
            assertTrue(verdict.isBlocked, "zcode-task.$command 桌面配置写应拦截")
        }
    }

    @Test
    fun testTaskMetadataWriteAllowed() {
        // 任务元数据写（tasks-index）与审批/停止面一致放行
        for (command in listOf(
            "setTaskPinned", "setTaskUnread", "archiveTask", "unarchiveTask",
            "renameTask", "listArchivedTasks",
        )) {
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-task", command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "zcode-task.$command 元数据写应放行")
        }
    }

    @Test
    fun testZcodeAgentDirectWriteCommandsBlocked() {
        val commands = listOf(
            "setModel", "setThoughtLevel", "setMode",
            "writeWorkspaceFile", "saveFile", "writeFile", "applyEdits",
            "respondSessionRuntimePreferences", "grantWorkspaceHookTrust",
            "generateWorkspaceText", "testModelConnectivity",
            // P3-11 放行（2026-10-06）：installPlugin/uninstallPlugin 桌面代执行
            //（gate :95 注释裁决，卸载 UI 带确认）；setPluginEnabled 等其余插件写维持拦截
            "setPluginEnabled", "configurePlugin",
            "createAutomation", "deleteAutomation", "runAutomationNow",
        )
        for (command in commands) {
            val verdict = ReadOnlyGate.inspect(
                channel = "zcode-agent", command = command, arg = RPCValue.Undefined)
            assertTrue(verdict.isBlocked, "zcode-agent.$command（直写/配置写面）应拦截")
        }
        // 2026-10-08 回归审核轮 §11.1#3：listMcpServerStatuses 是列表查询
        //（上游 zcodeAgentService.ts:3820 无拦截），设置页 MCP 页真实调用面——
        // 曾列入「MCP 真实探测」属误分类，连接态 MCP 页恒失败，已移出黑名单
        val mcpList = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "listMcpServerStatuses", arg = RPCValue.Undefined)
        assertFalse(mcpList.isBlocked, "zcode-agent.listMcpServerStatuses（列表查询）应放行")
    }

    @Test
    fun testChannelBlacklistBlocked() {
        val cases: List<Pair<String, String>> = listOf(
            "terminal" to "create", "terminal" to "write", "terminal" to "dispose",
            // setting.update 放行（P3-11 桌面设置同步，gate :118 注释裁决）；
            // updateDataBaseDir（数据库目录迁移写）维持拦截
            "setting" to "updateDataBaseDir",
            "onboarding-record" to "appendRecord", "onboarding-record" to "clearRecords",
            "settings-sync" to "importSelected",
            // P3-11 放行（2026-10-06，设计稿 §11A 前置裁决）：createCheckpoint/
            // restoreBetweenCheckpoints 移入放行面（testGitCheckpointCreateRestoreAllowed）；
            // deleteCheckpoint 无 UI 入口维持拦截
            "git-checkpoint" to "deleteCheckpoint",
            "credential" to "save", "credential" to "delete",
            "oauth" to "startOAuth", "oauth" to "handleCallback", "oauth" to "refreshToken",
            "oauth" to "logout", "oauth" to "logoutAll",
            "conversation-share" to "publish", "conversation-share" to "importShare",
            "window-controller" to "mutateTask",
            "feedback" to "create", "feedback" to "uploadAttachment",
            "provider-settings" to "createPersonalProvider", "provider-settings" to "testModelConnectivity",
            "provider-provisioning-target" to "apply",
            "skills" to "setEnabled", "hooks" to "saveHooks", "commands" to "writeCommandFile",
            "subagents" to "deleteAgent", "mcp-sync" to "saveMcpToUserDirectory",
            "plugin-sync" to "importPluginsArchive", "plugins" to "installPlugin",
            "plugin-management" to "setPluginEnabled",
            "bots" to "handleInboundMessage", "bots" to "beginFeishuRegistration",
            "off-peak-task" to "createTask", "coding-plan-subscription" to "payStripe",
        )
        for ((channel, command) in cases) {
            val verdict = ReadOnlyGate.inspect(
                channel = channel, command = command, arg = RPCValue.Undefined)
            assertTrue(verdict.isBlocked, "$channel.$command（频道直写黑名单）应拦截")
        }
    }

    // MARK: ②' IM Bot 合法远控写放行（G-002~G-005：桌面宿主侧配置/凭据写，非手机直写仓库）

    @Test
    fun testBotLifecycleCommandsAllowed() {
        val cases: List<Pair<String, String>> = listOf(
            "bots" to "saveBot", "bots" to "resetBotState",
            "bots" to "deleteBot", "bots" to "removeBotSecret",
            "bots" to "testBot", "bots" to "createBindCode",
        )
        for ((channel, command) in cases) {
            val verdict = ReadOnlyGate.inspect(
                channel = channel, command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "$channel.$command 已放行为合法远控写（桌面代执行），不应拦截")
        }
    }

    // MARK: ②'' Coding Plan 重置机会领取放行（G-042：usage-stats 频道一键领取入口）
    // 实现口径（ReadOnlyGate channelDirectWriteCommands["usage-stats"] = []）：
    // requestCodingPlanResetOpportunity / useCodingPlanReset 为「移动端发命令、桌面代执行」
    // 的合法远控写（非手机直写仓库文件）；usage-stats 其余均为读面。放行必须保持——
    // 反向回归：一旦有人把这两件加回黑名单，一键领取入口即被边界误杀（G-042 验收失败）。

    @Test
    fun testCodingPlanResetClaimCommandsAllowed() {
        val cases: List<Pair<String, String>> = listOf(
            "usage-stats" to "requestCodingPlanResetOpportunity",
            "usage-stats" to "useCodingPlanReset",
        )
        for ((channel, command) in cases) {
            val verdict = ReadOnlyGate.inspect(
                channel = channel, command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "$channel.$command G-042 放行（一键领取入口），不应拦截")
        }
    }

    // MARK: ③ 放行回归（既有只读接入面不误杀）

    @Test
    fun testNewReadonlyFacesAllowed() {
        val cases: List<Pair<String, String>> = listOf(
            "file-watcher" to "watch", "file-watcher" to "unwatch", "file-watcher" to "disposeAll",
            "zcode-task" to "listTaskList", "zcode-task" to "getTaskConfigOptions",
            "zcode-task" to "getTaskModelSelection", "zcode-task" to "getTaskTokenUsage",
            "zcode-agent" to "listSessions", "zcode-agent" to "readSession",
            "zcode-agent" to "subscribeSessionsIndexV4", "zcode-agent" to "resyncSessionsIndexV4",
            "zcode-agent" to "subscribeConversationV4", "zcode-agent" to "resyncConversationV4",
            "zcode-agent" to "subscribeWorkspaceConfigV4", "zcode-agent" to "resyncWorkspaceConfigV4",
            "zcode-agent" to "unsubscribeWorkspaceConfigV4", "zcode-agent" to "conversationFileChangesV4",
            "zcode-agent" to "conversationRowsRangeV4", "zcode-agent" to "helloConversationV4",
            "zcode-session" to "promoteDeferredDraftSession",
            "model-selection" to "getView",
            "oauth" to "getProviders", "oauth" to "getActiveProvider", "oauth" to "restoreCachedSessionState",
            "usage-stats" to "getCodingPlanUsageSnapshot", "usage-stats" to "getCodingPlanResetStatus",
        )
        for ((channel, command) in cases) {
            val verdict = ReadOnlyGate.inspect(
                channel = channel, command = command, arg = RPCValue.Undefined)
            assertFalse(verdict.isBlocked, "$channel.$command 只读面不得误杀")
        }
    }

    // MARK: ④ 拦截记录（blockedExecutionCalls 记录存在性：容器 + 可记录 reason）

    /**
     * 出口记录契约：ZCodeServerConnection.call 在 directWrite 判定时把 verdict.reason
     * append 进 blockedExecutionCalls（RPC 唯一出口，ZCodeServerConnection.swift call()）。
     * iOS 原用例断言记录链路三个环节：① 记录容器存在且新连接初始为空；② 全部拦截面
     * （git 写 / file 默认拒绝 / 配置写 / 频道黑名单 / v4 信封直写类）的 verdict 均携带
     * 非空 reason；③ v4 信封直写类同样携带可记录 reason。
     * 本平移只覆盖 ②③——环节① 依赖 ZCodeServerConnection（connection 模块未移植，
     * android/connection 现为占位），缺生产 API 不可平移，如实约减（见类 KDoc）。
     */
    @Test
    fun testBlockedVerdictsCarryRecordableReason() {
        // ② 拦截面 verdict 全部携带可记录 reason（出口 append 的字符串源）
        val blockedFaces: List<Pair<String, String>> = listOf(
            // git 写族已按 web 对齐放行（2026-10-06），拦截面只剩 file/zcode-agent/task 等
            "file" to "writeTextFile", "file" to "applyPatch", "file" to "unknownWriteCmd",
            "zcode-task" to "setModel", "zcode-task" to "setConfigOption",
            "zcode-agent" to "writeWorkspaceFile", "zcode-agent" to "saveFile",
            "terminal" to "create", "credential" to "save", "oauth" to "logout",
        )
        for ((channel, command) in blockedFaces) {
            val verdict = ReadOnlyGate.inspect(
                channel = channel, command = command, arg = RPCValue.Undefined)
            assertTrue(verdict.isBlocked, "$channel.$command 应拦截（前置）")
            val reason = assertNotNull(
                verdict.reason, "$channel.$command 拦截应携带 reason（blockedExecutionCalls 记录源）")
            assertFalse(reason.isEmpty(), "$channel.$command 的 reason 不应为空串")
        }

        // ③ v4 信封直写类（applyFileRewind）同样携带可记录 reason
        val envelope = envelope(type = "applyFileRewind", firstInput = null)
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = envelope)
        assertTrue(verdict.isBlocked, "applyFileRewind 应拦截（前置）")
        val reason = assertNotNull(verdict.reason, "applyFileRewind 拦截应携带 reason")
        assertFalse(reason.isEmpty(), "applyFileRewind 的 reason 不应为空串")
    }

    // MARK: 嵌套信封形态（P1① 回归门，2026-10-07 审查轮）
    //
    // 发送面实际形态为嵌套 `{envelope:{type,payload,…}, workspacePath, …}`
    // （ConversationCommandFactory/RemoteConversationStore.sendCommand 构造）。
    // 修复前 gate 只读顶层 `type`，嵌套形态下恒空串——applyFileRewind 拦截成死代码，
    // 而本文件旧助手只造平铺形态，单测全绿掩盖失守。

    @Test
    fun testNestedEnvelopeApplyFileRewindBlocked() {
        val arg = nestedEnvelope(type = "applyFileRewind", firstInput = null)
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = arg)
        assertTrue(verdict.isBlocked, "嵌套信封 applyFileRewind 应拦截（修复前成死代码）")
        assertTrue(verdict.reason?.contains("applyFileRewind") == true)
    }

    @Test
    fun testNestedEnvelopeCreateSessionWithFirstInputIsCommand() {
        val arg = nestedEnvelope(
            type = "createSession",
            firstInput = JSONValue.Obj(mapOf("text" to JSONValue.Str("hi"))))
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = arg)
        assertEquals(V4CommandClassification.command, verdict.classification)
        assertFalse(verdict.isBlocked)
    }

    @Test
    fun testNestedEnvelopeSendTextIsCommand() {
        val arg = nestedEnvelope(
            type = "sendText",
            firstInput = JSONValue.Obj(mapOf("text" to JSONValue.Str("hi")))) // firstInput 与 sendText 无关
        val verdict = ReadOnlyGate.inspect(
            channel = "zcode-agent", command = "sendConversationCommandV4", arg = arg)
        assertEquals(V4CommandClassification.command, verdict.classification)
    }

    // MARK: 工具

    /** sendConversationCommandV4 信封构造（对齐 RemoteConversationStore.sendCommand）。 */
    private fun envelope(type: String, firstInput: JSONValue?): RPCValue {
        val payloadFields = linkedMapOf<String, JSONValue>("workspaceId" to JSONValue.Str("/tmp/ws"))
        if (firstInput != null) {
            payloadFields["firstInput"] = firstInput
        }
        return RPCValue.json(
            JSONValue.Obj(
                linkedMapOf(
                    "commandId" to JSONValue.Str(UUID.randomUUID().toString()),
                    "clientId" to JSONValue.Str("zcode-mobile"),
                    "sessionId" to JSONValue.Null,
                    "type" to JSONValue.Str(type),
                    "payload" to JSONValue.Obj(payloadFields),
                    "issuedAt" to JSONValue.Str("2026-01-01T00:00:00Z"),
                )
            )
        )
    }

    /**
     * 嵌套信封构造（对齐 ConversationCommandFactory 产物：{envelope:{…},
     * workspacePath, workspaceIdentity?}）。issuedAt 原文 .int(1_760_000_000_000)
     * 为 64 位毫秒，Kotlin JSONValue.Int 32 位值域装不下——按 RPCSerialization.kt
     * 已声明口径（越界整数落 .double，RPCSerialization.swift:70-71 Codable 主路）
     * 落 JSONValue.Double 同值；该字段不参与 gate 判定，断言语义不变。
     */
    private fun nestedEnvelope(type: String, firstInput: JSONValue?): RPCValue {
        val payloadFields = linkedMapOf<String, JSONValue>("workspaceId" to JSONValue.Str("/tmp/ws"))
        if (firstInput != null) {
            payloadFields["firstInput"] = firstInput
        }
        val envelope: JSONValue = JSONValue.Obj(
            linkedMapOf(
                "commandId" to JSONValue.Str(UUID.randomUUID().toString()),
                "clientId" to JSONValue.Str("zcode-mobile"),
                "sessionId" to JSONValue.Null,
                "type" to JSONValue.Str(type),
                "payload" to JSONValue.Obj(payloadFields),
                "issuedAt" to JSONValue.Double(1_760_000_000_000.0),
            )
        )
        return RPCValue.json(
            JSONValue.Obj(
                linkedMapOf(
                    "envelope" to envelope,
                    "workspacePath" to JSONValue.Str("/tmp/ws"),
                )
            )
        )
    }
}

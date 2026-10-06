# Web Bundle 接口对齐盘点报告

> 日期：2026-10-06 · 版本 **v2**（对照重扫更新；v1 见 §六历史说明）
> 数据源：① `/remote/v4` 页面 bundle（`/tmp/webshell2.js`，4.8MB，与移动端同一条配对通道——**接口形状唯一权威准绳**）；② `docs/web一致性审查报告.md`（33 条发现全量处置）；③ 本项目全源码重扫（`connection.call` / `sendCommand` / `listen` / 传输层消息）+ 本轮各波次实现者结果。
> 证据等级口径同协议文档 §1：web 形状【移植·bundle 逆向】；真机取证【实证】。**本轮全部 bundle 逆向形状零真机取证**（桌面端不在线；目检代理失败降级确定性实拍，截图未经判读）——下表「已接」指移动端已按 web 形状接线，非活体验证。

---

## 一、总览（v2 重扫口径）

| 维度 | Web 已用 | 移动端已接 | 差距（剩余） |
|---|---|---|---|
| v4 会话命令（envelope.type） | ~28 型 | **26 型**（含 hook 四命令；startSavedWorkflow 发送层在、UI 隐藏） | renameSession（探针裁决中）等 2-3 型 |
| RPC 频道方法 | ~80+ 对 | ~70 对在用（本轮 +12：附件四条/getPluginsOverview/installPlugin/provider-settings.getView/system.info/git.getIdentity/onDynamicWorkspaceEvent/getEntitlementSnapshot 定形…） | 集中在桌面本地写面（边界外） |
| 传输层消息（zcode_type） | ~10 种 | **8 种**（本轮补齐 mobile-view-state-update / bridge-degraded / app-error / workspace-bridge-error 收口；attachmentPut 证伪删除） | platform-request（web 桌面代理面，移动端无场景） |
| 附件 | 上传事务 + 分段读 | **上传事务（Begin/Chunk/Commit/Abort）+ 分段读** | 仅剩真机首击取证 |
| 形状偏差（审查报告 §二/§三） | — | **A-1~A-4、B-1~B-9、C-1~C-16、L-1/L-2 全部闭合**（§四口径清理同步） | 真机复核（§五） |

---

## 二、三列表：web 已用 / 移动端已接 / 差距

### A. v4 会话命令（sendConversationCommandV4）

| 命令 | Web 已用 | 移动端已接（本轮后） | 剩余差距 |
|---|---|---|---|
| sendText（含 requestedDelivery/attachments/modelSelection） | ✅ | ✅ attachments 元素键 {ref,fileName,mime,bytes} 本轮修正（B-1）；queue 档实证 | sendText 附件首击待真机 |
| createSession / deleteSession | ✅ | ✅（实证） | — |
| resolveInteraction | ✅ | ✅ 三族形态定形（A-3：{optionId}/{action}/{freeText}，顶层平铺删） | options 元素形状未取证（宽容） |
| 队列五件（sendQueuedNow/edit/delete/reorder/setAutoDrain） | ✅ | ✅（实证 + CAS 探针） | — |
| switchModelConfig / pauseGoal / resumeGoal / retryTurn / stop | ✅ | ✅（实证；stop payload 收敛 {}） | — |
| switchCollaborationMode | ✅ | ✅ 发送层 + chips UI | 值域 plan/build 未逐字取证；首击回执 |
| setFollowupMode | ✅ 枚举 queue/guide | ✅ **now 档不下发命令**（A-2：立即=逐消息 startNow） | 独立命令 vs 会话级偏好关系（探针） |
| setAssistantFeedback | ✅ like/dislike | ✅ 值域修正（A-1） | 服务端回读字段（现会话内存） |
| editUserQuery | ✅ | ✅ + workspaceMode:"preserve"（C-2） | attachments? 可选未接；首击回执 |
| compact / snoozeInteractionAutoResolution / sendGoalCommand | ✅ | ✅（sendGoalCommand 降普通信封——§四口径清理） | 桌面异步行为 |
| forkAssistant | ✅ v4 命令 {target}+CAS | ✅ 双面（v4 主 + channel 回退，B-9） | 消息级 fork UI（现列表级） |
| **respondWorkspaceHookReview / requestWorkspaceHookReview**（本轮新接） | ✅ | ✅ 全链（store→审批卡 kind 分派→恢复链） | 首击回执；reviewItems 元素键（宽容链） |
| toggleWorkspaceHookReviewItem / revokeWorkspaceHookTrust（本轮新接） | 仅 schema 无调用点 | 发送层 ✅；toggle 无 UI 入口（enabled 语义未知）、revoke 有 UI+destructive 确认 | union 第二臂；语义取证 |
| startSavedWorkflow | **0 命中，不在 web 枚举** | 发送层保留、**UI 已隐藏**（H2；恢复条件=真机探针 accepted） | 否定性结论（§五） |
| renameSession | 仅 schema（web 未用） | 未接（与 renameTask 疑似双入口，探针裁决） | — |
| applyFileRewind / createSelectionSideSession / discardSharedContext | schema 在/场景在 | **skip**（边界红线 / 桌面选区场景 / 破坏性未取证）——词表已登记（协议文档 §7.2） | 维持 |

### B. RPC 频道方法

| 频道.方法 | Web 已用 | 移动端已接（本轮后） | 剩余差距 |
|---|---|---|---|
| zcode-task.listTaskList / setTaskPinned / archiveTask / unarchiveTask / renameTask / setTaskUnread / listArchivedTasks | ✅ | ✅；**归档逐区合并 + 行归属寻址本轮修正**（⑤⑥） | 置顶/改名/未读仍发当前 workspace（跨工作区行可能落错区，协议文档 §12-6） |
| zcode-task.listGroupedTaskViewStructure / createTaskGroup / renameTaskGroup / updateTaskGroupColor / deleteTaskGroup / applyGroupedTaskViewOrder | ✅ | ✅ **形状本轮定形**（B-2/B-3/B-4/C-3/C-4：members 顶层、零参建组、title 键、全量视图写）+ 移入分组全链 | apply 省略顶层散任务节点的服务端解释；createTaskGroup 回执解包形态（宽容） |
| zcode-task.onDynamicTaskEvent | ✅ | ✅（既有） | — |
| **zcode-task.onDynamicWorkspaceEvent**（本轮新接） | ✅ | ✅ 只订当前活跃工作区（事件→全量 refresh） | reason 词表（diag.task.wsEvent 取证） |
| zcode-agent 订阅/读族（subscribe/resync/rowsRange/readSession/listSessions/hello 等） | ✅ | ✅（实证；rowsRange 60s 超时治理本轮） | — |
| zcode-agent 附件四条（Begin/Chunk/Commit/Abort）（本轮重写） | ✅ strict | ✅ **按 web 事务整链重写**（A-4/C-16：strict 键集、384KB、nextChunkIndex、sha256、Abort 口径） | connectionId 等价性 + 首击回执 + 上限真值（§五） |
| zcode-agent.listPlugins / getSkillReferenceCatalog | ✅ 带 workspace 维度 | ✅ 补 {workspacePath, workspaceIdentity?, configScope?}（C-8/C-9） | — |
| **zcode-agent.getPluginsOverview / installPlugin**（本轮新接/修正） | ✅ | ✅ 市场浏览→安装闭环（长按安装+确认+诊断解读）；uninstallPlugin 五键修正（B-8） | 单频道回执键完备性（双频道差异）；scope:'user' 旧桌面拒认可能 |
| zcode-agent.getPluginReferenceCatalog | ✅ | **不接**（插件页需求已由 overview 覆盖，按「overview 命中不足再接」判定） | — |
| git 读面（refresh/getChanges/getDiff/getRepositorySummary） | ✅ | ✅；getDiff sourceId+结构化回执本轮修正（C-7） | — |
| git.getCommitGraph / getBranchComparison / generateCommitMessage | ✅ / 仅 mock / ✅ | ✅ 形状定形（C-5/C-6：maxCount/skip/authoredAtMs/全参） | getBranchComparison 真实回执整条待探针（web 仅 mock） |
| git.stagePaths / unstagePaths / commit | ✅ | ✅；**paths=原始 path（R4）/ commit hash 成功判定**本轮修正（假提交修复） | nothing-to-commit 拒收形态；hash 键名真机验证 |
| git.getIdentity（本轮新接） | ✅ | ✅ 提交身份预检（警示不硬禁） | 回执字段名未取证 |
| usage-stats 族 | ✅ | ✅；getEntitlementSnapshot 六键+无列表键回执定形（B-5）、重置卡三连平铺（B-6）、remaining/number 分母（L-1）、accountAccess 形状（L-2） | remaining 单位语义；回执复核 |
| **provider-settings.getView**（本轮新接） | ✅ | ✅ 供应商连接状态区（SettingsView） | providers[].accountState 形态与 availability 词表 |
| **system.info**（本轮新接） | ✅ | ✅ 关于区宿主行 | — |
| setting/settingService.get/update | ✅ | ✅；update 载荷本轮修正为单键补丁（B-7） | 真实频道名（双候选未裁决） |
| git-checkpoint 三方法 | **0 命中** | 发送层保留、**UI 已隐藏**（H3；恢复条件=取证+立条） | 形状全靠猜——最不可靠一环 |
| off-peak.list / feedback.list | 未用 / **web 无 list** | off-peak 只读在；feedback.list 入口已隐藏（H1；恢复条件=探针回执成形） | 双重未取证 |
| oauth 读三件 | ✅ | ✅（实证） | — |
| model-selection.getView/onDidChange | ✅ | ✅（实证 + chips 会话级覆盖） | — |
| bots 族 | ✅ | ✅（实证） | — |
| file 读白名单 / file-watcher | ✅ | ✅（实证） | — |

### C. 传输层消息（relay WS zcode_type）

| 消息 | Web 已用 | 移动端已接（本轮后） | 剩余差距 |
|---|---|---|---|
| rpc-frame / rpc-frame-ack / auth / bootstrap / workspace-list-request/response / workspace-bridge-open/ready / workspace-list-updated | ✅ | ✅；workspace-list 条目 canBridge 门控本轮补齐（C-15） | bootstrap.result.workspaces 未消费（无缺口） |
| workspace-reconnect-request/response | ✅ 重连专用 3 键 | ✅ 独立协议面保留（**无 UI 入口**；切换流程已解耦，C-10） | 桥重开后是否要求重握手 |
| mobile-view-state-update | ✅ 三场景 | ✅ 三场景接线（C-11：bridge-open/切工作区已闭环；**打开任务触发点待接线**） | platform 值域（现报 "ios"） |
| bridge-degraded（+checkReplayDeadline 本地检测） | ✅ | ✅ 收口（C-13：置 degraded+立即失败 pending+重开桥） | — |
| app-error / workspace-bridge-error | ✅ requestId reject | ✅ 收口（C-14：reject waiter/桥退化分发/无 waiter 记日志） | — |
| ~~attachmentPut~~ | **证伪：不是传输帧**（web 高层函数名） | 自造帧已删（A-4） | — |
| platform-request（externalWebRemoteControlProxy） | ✅ web 桌面代理 | 不接（web 打开本地目录等桌面场景） | — |
| fileChanges / fileRewindPreview | ✅ / ✅ | fileChanges 已接（conversationFileChangesV4）；rewind 预览不接（applyFileRewind 红线同族） | — |

---

## 三、本轮已闭合项（对齐修复 + 降级 + 回归，全部映射审查报告编号）

- **必坏类（§二 A/B 组）**：A-1 setAssistantFeedback like/dislike ✅；A-2 setFollowupMode queue/guide ✅；A-3 resolveInteraction 三族 ✅；A-4 附件事务整链重写 ✅；B-1 attachments 键 ✅；B-2/B-3/B-4 任务分组三连 ✅；B-5 权益快照 ✅；B-6 重置卡平铺 ✅；B-7 设置写面载荷 ✅；B-8 卸载五键 ✅；B-9 forkAssistant 双面 ✅。
- **场景类（§三 C 组）**：C-1 行游标虚构删 ✅；C-2 workspaceMode ✅；C-3/C-4 组写族形状 ✅；C-5/C-6/C-7 git 三项 ✅；C-8/C-9 workspace 维度 ✅；C-10~C-15 传输层对齐 ✅；C-16 384KB ✅。
- **口径类（§四）**：stop payload 清理 ✅；sendGoalCommand 降普通信封 ✅；L-1 remaining/number ✅；L-2 accountAccess ✅；旧注释清理 ✅。
- **提审/降级（§六 U 组 + H 隐藏）**：U-1 演示模式收敛（Mock 仅 -ZCodeDemoData E2E 开关，正式路径 Empty*Store 空实现）✅；U-2/U-3 设置页假值移除 ✅；U-4 scope→optionId 透传 ✅；U-5 审批假成功→如实回传 ✅；U-6 发送失败草稿回填+错误行 ✅；U-7 未连接两相区分 ✅；U-8 模型设置下发 ✅；U-9 断线横幅声明 ✅；隐藏入口：H1 反馈工单、H2 startSavedWorkflow 启动、H3 检查点、H4 仓库/语音 chips（gating）、H5 composer 目标菜单（gating）、H6「桌面端继续」→「浏览文件」、H7 云端沙盒 chip。
- **回归修复（用户反馈①-⑧）**：①文件页确认链路（diffReloadToken 消费/写面报错/loading/stagePath 口径）✅；②文件 tab 点击后清空（三态+last-good）✅；③消息区空白透出+订阅失败错误态 ✅；④提交假成功 hash 判定 ✅；⑤切换器清单单源（workspaces ∪ bootstrap 派生）✅；⑥归档逐区合并 ✅；⑦历史拉取 60s 超时治理 ✅；⑧多 run 排序仲裁+「历史 run」分界 ✅。
- **补充接口（12 条）**：workspace hook 四命令、git.getIdentity、onDynamicWorkspaceEvent、getPluginsOverview、installPlugin、provider-settings.getView、system.info（+renameSession 登记未接、getPluginReferenceCatalog 判定不接）。

## 四、剩余缺口（按处置类别）

1. **待真机取证**（桌面在线后一轮探针全收）：全部【移植·bundle 逆向】形状首击回执（协议文档各条目「待真机取证清单」+ §12-7）；附件 connectionId 等价性与上限真值；getBranchComparison 真实回执；settingService 真实频道名；hook 审核 payload 元素键；getPluginsOverview 单频道键完备性；platform 值域；git commit hash 键名。
2. **待接线**：mobile-view-state-update 场景②（打开任务 → `connection.reportActiveTask(taskId)`，连接层 API 已备）；连接态提问卡 UI（QuestionInteractionCard，wire 已改 {freeText}，演示态路径不变）。
3. **待裁决/跟进**：跨工作区置顶/改名/未读寻址（协议文档 §12-6）；renameSession vs renameTask 双入口；apply 全量写省略散任务节点的服务端解释；E2E Matrix test21 第三断言（存量无生产者，门禁轮裁决）。
4. **隐藏入口待恢复**（恢复条件均为真机探针）：startSavedWorkflow（accepted）、检查点三方法（取证+立条）、反馈工单（回执成形）、云端沙盒/仓库语音/执行目标 chips（产品裁决）。

## 五、skip 清单（边界外/红线，维持现状——重扫登记）

`applyFileRewind`（ReadOnlyGate 唯一 v4 直写拦截，红线）；`createSelectionSideSession`（桌面编辑器选区）；`discardSharedContext`（语义未取证+破坏性嫌疑，词表占位）；`closeSession`/`readWorkspacePresentation`/`respondProviderRuntimeHeaders`（web 桌面副屏/凭据流）；`releaseWorkspacePreparation`/`restartWorkspaceProcess`（桌面生命周期/配置写，gate 拦）；`syncAppRuntimePreferences`（桌面多窗口偏好广播）/`onAgentRuntimeRestarted`/`onDynamicCuaPermissionObservation`（CUA 边界外）；transfer 频道 stage/adopt/cancel/cleanup（web 桌面本地文件通道，iOS 无宿主路径——附件已走 v4 事务不重复建链）；feedback 写族 + credential.save/delete + commands.writeCommandFile 族 + setting.updateDataBaseDir（桌面本地鉴权/配置写）；coding-plan-subscription.getEnterprisePricing（购买流）；plugin-management/plugins 双频道写读族 + remotePluginSyncService（与 zcode-agent 面重复/跨桌面同步场景）；file.ensureConversationWorkspace/resolvePath/createScratchWorkspace + system.listIntegratedTerminalShells + terminal.*（桌面生命周期/终端交互流）；platform-request（web 桌面代理）。

## 六、v1 → v2 差异说明

v1（2026-10-06 早间）基于 bundle 首轮粗扫 + upstream 清单，把「接入评估」作为主要输出；本轮一致性审查（`docs/web一致性审查报告.md`）证实其中多处「已支持」实为形状错位（resolveInteraction/attachments/分组族/设置写面/重置卡等 19 条必坏+场景偏差），v2 全部按审查报告编号闭合并重扫登记。v1 的「附件未接/多工作区切换器未接/分组未接」等结论已过时，以本版为准；v1 建议优先级（P1 附件/P2 工作流闭环/P3 git+权益+切换器）全部落地。

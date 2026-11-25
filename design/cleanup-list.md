# 清理清单（过期 / 无接口 / 被取代 / 杂项）

> 2026-11-25 · 对准**本轮重设计后**的代码树（三 tab / 待批收件箱 / 更改 sheet / 二级模型选择器 / keep-warm 已落地）。
> 处置动词：**删**（无引用死码，本轮或下轮直接删）/ **dev-gate**（保留功能、入口收开发者模式）/ **归档**（移动端无接口，实现保留入口隐藏，恢复条件=取证）/ **改**（修掉）。
> 每项带证据 file:line（本轮重设计后行号）与牵连测试。P0 = 先于一切。

## P0 疑似缺陷（先复现确认再修——本轮已按代码路径预修一处）

| # | 项 | 证据 | 处置 |
|---|---|---|---|
| C0-1 | 诊断导出把 `session.mode` 直接插值——Mode 关联值携 `ServerConfig`（含 token/passHash），default description 会原样打印令牌进「生成并分享」的文件；页面文案却承诺「凭据与令牌不会包含」 | 修复前 `P2ExtrasViews.swift:924`（本轮已改为脱敏摘要 `modeSummary`，只落模式名+电脑显示名）；页面承诺文案 :880 | **已修**（防御性）；真机生成一份导出文件复核无令牌字样后关闭 |
| C0-2 | **服务器配置明文镜像 UserDefaults**：`ServerRegistry.syncMirror` 把整份 `[ServerConfig]`（含 token/passHash）JSON 编码进 `server.configs.mirror.v1`，而连接页文案对用户承诺「仅存 Keychain」 | `KeychainStore.swift:268/329-333`（mirrorKey/syncMirror）；承诺文案 `ConnectFlowView.swift`、`ServerViews.swift:307` | **改**：镜像只存**脱敏投影**（id/name/host/port/useTLS/lastConnectedAt/preferredWorkspacePath，去掉 token 与 relay.passHash——镜像用途只是模拟器重装自愈，无需凭据）；或镜像整体移除改「重装后需重新扫码」。牵连 `mirroredServers()` 读面（:300-306）与 `clearMirror()` |
| C0-3 | OAuth `state` 明文上屏（调试面板展示完整 state 值，防 CSRF 值可被截屏） | `LoginFlowView.swift:748`、`OAuthResultView.swift:500`（审计实证） | **改**：掩码显示（前 6 后 4）；仅开发者模式面板 |
| C0-4 | Token 用量按字节格式化（ByteCountFormatter.memory 把 tokens 渲染成 KB） | 修复前 `TaskOutputView.swift:143` | **已修**（本轮改为千分位分组） |

## 一、本轮重设计直接取代（已被新实现覆盖，代码留存=死码）

| # | 项 | 证据 | 处置 | 牵连 |
|---|---|---|---|---|
| C1-1 | `ComposerOptionSheet` 的 `.model` 档（已迁 ModelPickerSheet）——枚举 case 已删，`modelSheetOptions()` 注释残留 | `ChatView.swift:2504-2506`（空注释位）、`ComposerOptionSheet.swift` | **删**残留注释 | 无 |
| C1-2 | `DiffReviewView` 全局文件 tab 根（struct 已删，仅存 DiffViewModel/DiffFileCardView/DiffLine*） | `DiffReviewView.swift`（本轮 spliced）；文件名与内容已不匹配 | **改**：文件更名 `DiffModels.swift`（含 DiffViewModel/DiffFileCardView/DiffLineListView/CommitGraphPage），注释对齐会话 sheet 宿主 | SessionChangesSheet 引用 DiffViewModel/DiffFileCardView；CommitGraphPage 被文件 sheet 用 |
| C1-3 | `ApprovalSheetView` 已删但注释仍引用（4 处「ApprovalSheetView 同构/先例」） | `ChatView.swift:932/1186/1219/1437`、`P2ExtrasViews.swift:2449`、`FileTreeView.swift:1240` | **改**：注释改指 `SessionChangesSheet` / 决议卡 | 无 |
| C1-4 | `TaskBoardView` 已删但 `RemoteTaskStore.groupedTaskViewStructure`/`DesktopTaskGrouping` 及注释仍指向已死的看板消费方 | `RemoteTaskStore.swift:422/509`；teststub 引用见下 | **改**：groupedTaskViewStructure 已无 UI 消费者——**删**方法+DesktopTaskGrouping（协议默认实现保留）；注释清理 | `RemoteTaskStoreTests`? 查引用后删 |
| C1-5 | TaskRoute/taskPath/pushTask（路由枚举已删）——TaskOutputView 改 sheet 后无路由引用 | AppRouter（本轮已删）| **已完成**（无残留） | — |
| C1-6 | 会话行 `.task(id: viewMode)`/GROUPED 分支里 `filtered.first(where:)` 的 O(n²) 小疵 | `ConversationListView.swift:538-542` | P3 **改**：按 id 建 dictionary | 无 |
| C1-7 | `SessionChangesSheet.reload` 里 `viewModel.isLoading` 双写（load 内部已管理） | `SessionChangesSheet.swift` reload() | P3 **改**：去外壳 isLoading | 无 |
| C1-8 | `AgreementWebViewSheet`/`AgreementPage`/`AgreementWebView`（约 150 行）自 2026-11-25 起无入口——O1 协议脚注随 Android 对齐撤除（结构保留） | `LoginFlowView.swift`（enum 处已注明无入口） | **归档（维持）**：恢复条件=设置页法务行接入；届时补回入口与 E2E | 原 Matrix test11 已改写为平台切换用例 |

## 二、无接口支撑（隐藏入口既裁决，恢复条件=取证；本轮复核入口必要性）

| # | 项 | 证据 | 处置 |
|---|---|---|---|
| C2-1 | H1 反馈工单：路由 case + destination 分支 + 替身 `feedback.list` 记账，入口行已注释 | `AppRouter.swift`（feedbackTickets case）、`SettingsView.swift:494-497`、`NavigationAndBotsE2ETests.swift:173-178` 仍列 12-row-feedback | **归档**（维持）；E2E 该行断言核对容错 |
| C2-2 | H3 检查点：`CheckpointSheet`（260 行）+ store 三方法，入口注释保留 | `FileTreeView.swift:1146-1408`（本轮行号前移） | **归档**（维持） |
| C2-3 | H2 startSavedWorkflow：`StartWorkflowSheet` 族（340 行）入口注释 | `P2ExtrasViews.swift:1886-2228` | **归档**（维持）；**下轮删**：工作流库页已无「启动」语义（AGENTS 登记的 UI 已隐藏），或恢复=探针 accepted |
| C2-4 | `session/subagents` 子代理历史目录：远程桥不暴露（否定性结论 §10.1c），Android 已能力门控；iOS 对位逻辑在 store | 协议文档 §10.1c；`RemoteConversationStore` 目录读面 | **归档**（维持，桌面暴露即自动恢复） |
| C2-5 | `usageStatsV4`/`conversationUsageV4`/`queryConversationCommandsV4`/`backgroundBashOutputV4` 等未接协议位 | 协议文档 §12 | 维持（缺口清单 G25/G27 实施时接） |

## 三、演示与探针（验收后清理欠账，§4.6）

| # | 项 | 证据 | 处置 |
|---|---|---|---|
| C3-1 | Mock 三件 847 行（MockConversationStore/MockTaskStore/MockFileStore）+ demo-only 分支 | `Stores/Mock*.swift`；**9 个 E2E 文件全部跑在 `-ZCodeDemoData`**（本轮实证 test01-24 launch args） | **前置工程**：先把 E2E 迁到替身服务器（E2ELoginStubServer 已有 LAN 全链路）才能删 Mock；单独立轮 |
| C3-2 | `diag.*` 写入点 ~55 处 + `runQueueCASProbeDiag/runStopProbeDiag/runCleanupProbeDiag`/`workflowApiDiagDump` | `RemoteConversationStore.swift` 多处、`ChatViewModel.swift:723-730` | **批量清理**：按协议文档 §11 逐键核销（多数已超「验收后」时点）；`diag.wf.mode` 门控的保留；无门控无条件写的（`diag.feedback.*`/`diag.attach.last`/`diag.convbuild.*`/`diag.pend.*`）改门控或删 |
| C3-3 | `-ZCodeDiag*` 探针启动参数族（QueueCASProbe/StopProbe/CleanupProbe/Workflow/LoadOlder/PanelControl/SwitchModelTo/SwitchThoughtTo） | `AppSession.swift:570-599`、`ChatViewModel.swift:769-774` | 随 C3-2；探针函数保留可复跑（AGENTS §6 记录在案），验收全过后删 |
| C3-4 | 新建会话 sheet 的 demo-only 分支（执行器/云端沙盒区、仓库/语音 chips） | `NewConversationSheet.swift:530-541/790-796`、`ChatView.swift` targetRow（isDemoDataEnabled 分支） | **下轮删**（连同 ExecutionTargetStore per-conversation 持久化）；test07 目标选择器用例同轮改写 |

## 四、死分支 / 令牌与文案 / 孤儿

| # | 项 | 证据 | 处置 |
|---|---|---|---|
| C4-1 | iOS `Theme.violet/codeKw` 紫族——本轮已删 violet/violetDim；`codeKw` 仍是紫（语法高亮，属内容色非标识色，web 同源） | `Theme.swift`（本轮） | codeKw **保留**（TinySyntax 内容色）；下一轮评估换暖棕 |
| C4-2 | 半档字号 19 种 → 8 档收敛（Android 已完成 8 档 + BiuZGlyph 字形阶） | iOS `T.font(size:)` 调用 ~460 处（审计 §10 计数） | **单独立轮**（机械替换+逐屏截图回归，参照 Android C/I 批）；本设计稿字阶已定 spec |
| C4-3 | 字面量 radius（9/10/12/13/18/24）与裸 alpha | 审计 §10；本轮未动 | 同上立轮 |
| C4-4 | 孤儿 UI 测试文件 `ZCodeMobileUITests.swift`（264 行，未进 target，断言引用不存在的 id） | `project.yml:67-68` path=Tests；文件在 ZCodeMobileUITests/ | **删** |
| C4-5 | `UnitTests/.zcode/workflow-drafts/`（非测试工件混入单测目录） | iOS 盘点 §9 | **移**出单测目录或删 |
| C4-6 | `ios/DerivedData-*` 13 个历史构建目录（biuz132/biuz139/catchup/cred/sweep/v134/v139/verify/sync…） | `ios/` 目录 | **删**（本地磁盘卫生，不入库——确认 .gitignore 覆盖） |
| C4-7 | `.capture/chrome-profile`（浏览器采证 profile，含登录态 cookie） | `.capture/` 目录 | 保留（采证基建）；确认不入库 |
| C4-8 | Android 残留：`biuz_bg`/`Theme.BiuZ` 命名、Task Output 紫 `#A78BFA`、`switch_account_desc` 网页措辞、web 时代英文混排（STR:26/530/539）、30 键 en 缺译、Hooks/Subagents/Projects KDoc「只读」与实态不符、`settings.gradle.kts:28` 旧 applicationId 注释、`AndroidManifest` 「应用名 BiuZ」注释 | Android 盘点 §9/§10 | **下阶段 Android 对齐批**统一清（清单已固化） |
| C4-9 | Android `closeConversation` 从未被调用 / `loadOlder` 无 UI / `workflowMirrorStore` 未接线 / `RemoteUsageStatsStore` 777 行仅测试引用 / 启动参数硬关 | Android 盘点 §8/§9 | Android 对齐批（keep-warm 批5同构移植时一并收） |
| C4-10 | E2E 里 `-ZCodeDemoData` 下仍断言存在的 demo-only 控件（仓库/语音 chips、执行目标行：`MatrixAcceptanceE2ETests:828-829`、`FeatureCompletionE2ETests:838-945`） | 本轮 test01-24 launch args 实证 | C3-1 轮随 Mock 迁移改写 |
| C4-11 | 疑似越界文案：设置页副标题含桌面 home 目录路径（`桌面宿主 macOS · /Users/…`）与原始错误 120 字符直拼 | `SettingsView.swift:343/346`（audit §7） | **改**：仅开发者模式显示路径；普通态「桌面端 macOS」 |
| C4-12 | 会话页加载失败原文直拼 `RPCError.name：message`（普通用户看到协议串） | `ChatView.swift:663` 族、`ChatViewModel.swift:216/284` | **改**：headline 人话 + 原文收开发者模式（与 L3 同口径）；U-5「原文上屏」对普通用户降级为「详情见诊断」 |

## 五、已完成（本轮落地，记录防止重复劳动）

- ✅ 任务 tab 看板 + FAB + ApprovalSheetView（R2 撤除，文件已删）
- ✅ 全局文件 tab 根 DiffReviewView struct + DiffActionBar（R2，会话「更改」sheet 承接）
- ✅ `NewConversationSheet` 模型/思考 ComposerOptionSheet 档（批4 二级选择器承接）
- ✅ violet/渐变/阴影令牌（批6 令牌清理，grep=0）
- ✅ Token 字节格式化、诊断导出 mode 插值（C0-1/C0-4）
- ✅ 「查看完整 Diff」死按钮（远端恒 nil 的 mock-only 入口）

## 六、下阶段批次引用

- Android 对齐批（design-spec §4 批 A-D）+ 本清单 C4-8/9。
- Mock→替身迁移轮：C3-1 + C4-10 + C1-4。
- diag 清理轮：C3-2/3（协议文档 §11 逐键核销）。
- 令牌收敛轮（iOS 字阶/间距/alpha 机械替换）：C4-2/3。
- 凭据防御轮：C0-2/3（真机复现 → 修 → 回归）。

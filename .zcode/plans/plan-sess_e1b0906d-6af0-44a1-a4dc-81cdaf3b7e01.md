两个报障的根因已用代码证据锁定，本 plan = 取证分诊 + 修复 + 回归测试门禁。工作树在途 v1.31 改动（等待确认标/转圈/双气泡基线）不动，本轮叠加。

## Bug① 新会话模型/思考强度「不能选」（3 处根因，全部修复）

1. **选中勾跟随桌面绑定而非用户点选**：`NewConversationSheet.swift:396` 勾条件 `model == selectedModel && providerMatches` 完全没用 `selectedModelRowKey`——同名跨套餐行（个人/体验都有 GLM-5.3）点选后勾纹丝不动，观感「选不中」。
   修：勾条件改为 `selectedModelRowKey == rowKey` 优先，未点选过时才按桌面绑定行预勾（保留 v1.27 消歧初值）。
2. **getView 数据面失败 = 静默死路**：真机桌面不回 `model-selection.getView` 时模型面板永不开（`NewConversationSheet.swift:926-947` 只落提示）；此时思考档能选，但提交时 `pendingModelSelection()` 因 provider 映射缺失**静默返 nil**（:986-995），createSession 不带 modelSelection 照发——「选了等于没选」。E2E 替身恒有 getView，所以历轮全绿、真机复现。
   修：提交门硬化——用户明确选过（selectedModelRowKey != nil）但解析不出完整选择时，禁止静默裸发：错误行明示「模型选择未能下发 · 桌面端模型列表未回执」，给出「用默认模型继续」显式出口；getView 失败补自动重拉一次。
3. **§5-15 同型 pick 闭包**：`:220-223` 先收面板再应用、「重新获取」dismiss 中 200ms 重开（:417-428）存在 present/dismiss 竞态。修：重开改为 dismiss 完成后 task 内重试呈现。

## Bug② 当前会话计划模式丢失（4 处，全部修复）

1. **上游取证先行**：clone `zai-org/ZCode` rg `collaborationMode` 的 state 投影/持久化语义——有读面则 chip 接桌面权威源（同 `state.modelSelection` 三路口径：load/observe/onDidChange 回流）；确认无读面则把「本机偏好」标注常驻化（现状仅首屏一次性 3s hint，`ChatView.swift:1747-1750` 代码自认无桌面读面）。证据以「上游仓 file:line」固化进协议文档。
2. **ack 未知态不再假成功**：`CommandSchemas.swift:362-367` 现把 status 缺席/词表外一律判成功——一次误判即把 plan 写入本机持久化（`ChatViewModel.swift:189-190`），与桌面真态永久脱钩。修：仅 accepted/duplicate/noop 落态；status 缺席/词表外 → chip 不动、不落盘，如实提示「桌面回执异常（status 缺席）· 以桌面实际模式为准」。
3. **失败呈现持久化**：switchCollaborationMode 失败从 3s 气泡（`ChatView.swift:2094-2103`）升级为常驻错误行（复用 sendText 的 `lastSendUndeliveredText` 模式），带 ack 原文（reasonCode+message）。
4. **/plan 文本分支不再 fire-and-forget**：`ChatViewModel.swift:1026/:1034` 现在切换失败照发消息（任务以旧模式进桌面）。修：切换失败即阻断发送 + 错误上屏，用户重试或改用 chip。

分诊取证（修完即自证）：`diag.wf.control.ui` 已恒写完整 ack（`ChatViewModel.swift:457-462`）；修复后错误行直接显示真实拒因，桌面在线轮用模拟器复现 chip→plan 取真实回执定案（accepted=桌面重置无回流 / 缺 status=误判落盘 / rejected=命令被拒）。

## 回归测试（本次交付重点）

**替身扩展**（`E2ELoginStubServer.swift`，仿 :1871-1892 stale-once 先例）：
- 新增 `switchCollaborationMode` case：调用计数 + `payload.mode` 记账 + stale-once 绊线 + 「status 缺席」形态注水开关（现落 default 恒 ok:true 零记账）。
- getView 多套餐形态注水（planGroups/modelProvidersByPlan/activeProviderId）——rowKey/providerMatches/同名跨套餐路径 E2E 从未走到过。

**新 E2E**（FeatureCompletionE2ETests）：
- **test17 协作模式全链路**：chip 选 plan → 替身收到 `switchCollaborationMode` 且 payload mode=plan；stale-once 重发 ≥2；status 缺席形态 → chip 不落盘 + 如实提示上屏；rejected 形态 → 常驻错误行显示原文。
- **test08 扩展**：多套餐替身下点选跨套餐同名行 → `createSession.firstInput.modelSelection.providerId` 正确、行值与勾跟随点选；`modelSelectionViewFails` 注毒 → 提交被拦 + 错误行可见（不再静默裸发）。
- 单测：CommandAck 未知态判定新用例（ProtocolSchemaTests）；DraftHandoffModelSelectionTests 补「provider 缺失可见反馈」同族。

**门禁跑法**（§5-16 纪律）：build 显式 grep `TEST BUILD SUCCEEDED`/`error:` 判定（禁 `| tail` 管道）；`test-without-building` 显式 `-derivedDataPath DerivedData`；模拟器 10501D0E-…；跑 test08/test12/test13/test14/test15/test16 + 新 test17，环境抖动单跑复判。v1.31 在途项一并过门，`diag.pend.*` 探针验收后清理。

## 文档回写

协议文档 §9 switchCollaborationMode 条目（ack 判定变化+回执形态）、§13 v1.32 变更记录、§11 新增 diag 键登记（如有）；AGENTS.md §6 两项报障收口；上游证据 file:line 固化。
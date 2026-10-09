package cn.biuz.android.app.ui.screen

// ======================================================================
// 【挂账·未平移】ios/UnitTests/PlanToolCallParsingTests.swift（10 例）→ 本文件
//
// 被测 API `PlanToolCallSupport`（iOS 渲染层
// ios/ZCodeMobileApp/Sources/Features/Conversations/MessageViews.swift:633-695，
// 调用点 :96——ExitPlanMode 工具行 target → 计划预览卡提取/标题/文件名尾段）
// 在 android 全仓无 Kotlin 对应物，按「生产代码缺 API 不绕过断言」纪律
// 不平移、不造替身（测试内复刻解析逻辑 = 测自己的拷贝而非生产代码；
// 先例：android/protocol/src/test/.../ProtocolSchemaTests.kt:388-401 挂账
// parseInteraction——该 API 已随 M3 store 落地并于
// RemoteConversationStore.kt:1622 补平移，本挂账同流程回收）。
//
// 缺位证据（2026-10-09 全仓 grep，0 命中）：PlanToolCallSupport / planTitle /
// planFileLabel / planFilePath / allowedPrompts / getPlanDirectoryTitle /
// getPathLeaf；M3 已交付面 = store 行映射（toolCallRowMessage →
// protocol Models.kt:21 ToolCall，只携 target 原文）+ :app ChatScreen 占位
// （73 行，无消息行渲染）——行渲染消费面（计划卡）未移植，故本测试无锚点。
//
// 生产代码落位建议：纯逻辑三函数随 :app 行渲染轮移植（iOS 归属渲染层；
// 若 store 行映射轮先行，也可落 store 并由 :app 消费——以行渲染轮裁决为准）。
//
// 上游权威【实证·上游仓 /tmp/zcode-upstream/packages/ui/src/lib/planToolCall.ts】：
//   :10  PlanToolCallContent { markdown?, planFilePath? }
//   :52  extractPlanToolCallContent（上游按 tool identity family 分流，提取链
//        input → JSON.parse(inputText) → output → raw.rawInput/rawOutput/raw.content）
//   :91  getPlanDirectoryTitle（首个 H1，否则首个非空行去行首装饰）
//   :101 getPlanFileLabel = getPathLeaf
// 移动端形状门收敛（iOS MessageViews.swift:634-641 注释 + 协议接口文档 §10.1/v1.35）：
// 移动端 ToolCall 行只留 inputText 作 target（无 toolName），收敛为载荷形状门——
// target 解析为 JSON 对象且 `plan` 键为非空串才成卡；上游 switch-mode 族内
// text/content 兜底键移动端**不认**（防 MCP {text:…} 载荷误判）。
//
// 逐用例补平移配方（数据/断言 1:1 对应 iOS 原文行号；断言的是协议形状，
// 文案无关）：
//   1. testOfficialExitPlanModeInput（:11-17）parse(
//      `{"allowedPrompts":[{"prompt":"run tests","tool":"Bash"}],"plan":"# 计划标题\n\n正文"}}`
//      去 `plan` 值 = "# 计划标题\n\n正文"；planFilePath == null
//   2. testPlanFilePathExtracted（:19-24）parse(`{"plan":"正文","planFilePath":"/Users/mac/proj/plan.md"}`)
//      → markdown=="正文" 且 planFilePath=="/Users/mac/proj/plan.md"
//   3. testEmptyPlanReturnsNil（:26-30）parse(`{"plan":""}`) 与 parse(`{"plan":"   "}`)
//      均 null（plan 空白串 = 无有效计划，官方 hasMarkdown 同口径）
//   4. testTextFieldDoesNotTriggerPlanCard（:32-37）parse(`{"text":"普通文本工具输入"}`)
//      与 parse(`{"content":"x"}`) 均 null（形状门：只认 `plan` 键，text/content
//      兜底为上游族内行为，移动端不认）
//   5. testNonJSONObjectReturnsNil（:39-45）parse 入参 "grep -rn timeout server/" /
//      `[{"text":"数组载荷"}]` / `{"plan": 42}`（plan 非串）/ "" 均 null
//   6. testMalformedJSONReturnsNil（:47-49）parse(`{"plan": 不是JSON}`) → null
//   7. testTitleFromFirstH1（:53-57）planTitle("前言\n\n# 真标题\n## 二级\n# 第二个H1")
//      == "真标题"（首个 H1，`#(?!#)` 排除 H2+；正则须多行锚点 anchorsMatchLines
//      等价物——Kotlin Regex 默认 ^$ 只匹配串首尾，需 RegexOption.MULTILINE，
//      iOS 单测实证过的坑）
//   8. testTitleFallbackFirstNonEmptyLineStripped（:59-63）planTitle 回退：
//      "### 三级标题开头\n正文"→"三级标题开头"、"> 引用开头\n正文"→"引用开头"、
//      "- 列表开头\n正文"→"列表开头"（去 #{1,6}/引用/列表符装饰）
//   9. testTitleEmptyReturnsNil（:65-67）planTitle("") → null
//   10. testPlanFileLabelLeaf（:71-75）planFileLabel："/Users/mac/proj/plan.md"→
//      "plan.md"、"C:\\repo\\plan.md"→"plan.md"（/ 与 \ 均为分隔符）、"plan.md"→"plan.md"
//
// 回收条件：:app（或 store）落地 PlanToolCallSupport 生产代码后，按上文配方
// 在本包补 10 例 JUnit5 @Test 并删除本挂账注释（同 parseInteraction 先例流程）。
// ======================================================================

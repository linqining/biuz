# web 有 / App 缺口清单（下阶段实施依据）

> 2026-11-25 · 权威源：上游 `ZCode/`（fec9759）+ `/tmp/zcode-upstream`（a4b44451 = v4.0.0，桌面实跑版本）。逐条带上游 file:line + RPC；状态列：**缺**（App 无此面）/ **弱**（有但显著简于 web）/ **差异**（承载不同，登记过 APP-DIFF 不补）。iOS 列 = 本轮重设计后现状；Android 列 = 2026-11-24 全量盘点现状（下阶段对齐批次输入）。
> 范围 = web **手机远控壳**（`WebRemoteControlMobileShell` + `SessionPane` + 侧栏 + 设置）；桌面专属面（终端 tab / 高速卡 / 浏览器 / 分栏）不在复刻面（bootstrap §3 已排除）。

## 1. 会话流渲染（ConversationRowView / ToolCallBlocks）

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G1 | 逐工具专用渲染器注册表（40+ 工具：Cua/Session/Read/Edit/Bash/Search/WebFetch/MCP/Agent/Todo/Skill/ExitPlanMode…） | `ToolCallBlocks/resolveRenderer.ts:55-171`；`lib/toolIdentity.ts:219` | — | **部分已修**（9 族：原 6 族 + **mcp/webfetch/search 三族专属判定与图标/动词**（toolIdentity.ts 同序——ToolKind 枚举扩三 case + mapToolKind 补判定 + 工具卡专属 icon/label + switch 三 case 并入命令块族）；ExitPlanMode 已有计划预览卡（v1.35）；**40+ 全量专用渲染器仍独立轮**） | **部分已修**（ToolDisplayModel 族分派 Mcp/WebFetch/Search 专属 primary/URL 提取原已有——升格确认；ExitPlanMode 计划卡同前） | P3（残面=全量 40+ 渲染器独立轮） |
| G2 | 「Worked for Ns」回合头 + 连续工具摘要组 + 文件 chip 折叠 | `ConversationTurnGroup.tsx:1212-1680`；`conversationAssistantWorkItems.ts:157` | — | **等效承载登记**（回合摘要行+进行中回合展开已承载信息面；统计分组折叠为 MessageViews 结构重排——独立轮登记） | **已补**（TurnGrouping 回合分组——已完成回合折叠「已工作·N」摘要、运行中保持展开、散行尾单元；web 同语义双端覆盖） | P3（iOS 折叠独立轮） |
| G3 | 消息内查找（find）+ 命中高亮 | `useConversationTimelineFind.ts:139` | — | **等效承载登记**（已加载范围本地检索+命中计数已有；命中高亮=Text AttributedString 拼接——MessageView 结构重排独立轮登记） | 缺（检索/高亮均缺——LazyColumn 无本地检索面；独立轮） | P3 |
| G4 | Gen UI `::visualize` 卡（复制图/微调/展开） | `gen-ui/components/GenUiCard.tsx:66-276` | — | 占位卡（请到桌面查看） | 占位 | P3 |
| G5 | 消息编辑重发（ComposerEditBar + reset 警示） | `composer-edit-mode/ComposerEditBar.tsx:28-106`；RPC `editUserQuery` | — | 有（EditResendSheet + 确认） | 有（2026-11-24 批次） | — |
| G6 | 待办默认在状态胶囊（**v4.0.0 纠偏：无焦点窗**——PlanStatusSection 全量渲染 + focusCurrent 定位首个 inProgress，:552-566） | `ConversationStatusPanel.tsx:552-566,1408-1412` | snapshot state.plan | **已补**（2026-11-25 缺口轮：头部摘要可点胶囊 + TodoListSheet 全量清单；纯文本摘要不升按钮） | 缺（头部无 todo 胶囊；PlanPanel 有 N/M） | iOS 已补 / Android P3 |
| G7 | 流内待办卡设置开关 `messageStreamShowTodos`（默认 false） | `SessionPane.tsx:2204`；`validationAppSettings.ts:571` | — | **已补**（AppSettings.showTodosInStream 默认 false + 设置行 + MessageView 过滤分支） | **已补**（AppSettingsStore.showTodosInStream + 设置行 + 渲染分支待接） | — |
| G8 | timelineMarker 分隔线（compact/modelChange/goalVerify/forkNotice 可点回父会话） | `ConversationShareReadonlyTimeline.tsx:264-410` | rows | 有（解析+渲染，Android 同构；forkNotice 跳转未接） | 有（同构） | — |
| G9 | 虚拟化长列表 + 行高缓存 4000 + 冻结史/活尾分段 | `timelineRowHeightCache.ts:13-58`（turnId 键 LRU 4000/默认估高 72/插入序淘汰/会话切换 clear）；`ConversationTimeline.tsx:448-455`（getConversationRowsPartition——React 派生成本优化） | — | **已补**（TimelineRowHeightCache 逐字语义移植（messageId 键）+ 行测高写入（GeometryReader 背景回调——流式高度增长持续刷新，上游 ResizeObserver 同语义；onAppear+onChange 双路）+ 会话切换 clear + **活尾跟随判定**（冻结史/尾轮分区移动端等效：用户滚离底部（back-to-bottom 在场）时新行到达不抢滚底，回底部恢复跟随——web user-scroll-intent 同语义；LazyVStack 本身已虚拟化） | **已补**（TimelineRowHeightCache.kt 同构 + onGloballyPositioned 行测高 + 活尾跟随判定（firstVisible/visibleItems 距底判定 + ExtendedFloatingActionButton 回底钮 overlay——此前恒滚底会打断史区阅读的真实痛点修复） | **等效承载登记**（小程序 chat 页 scroll-view 原生 bindscroll detail 提供 scrollHeight/scrollTop，scroll-into-view 锚定不依赖行高——行高缓存无消费面，平台 API 承载同能力；APP-DIFF 纪律） |
| G10 | 回合导航轨（≥2 轮时右缘 round-jump） | `ConversationTimeline.tsx:620,2000` | — | 缺 | 缺 | P3 |

## 2. Composer 与模型（ConversationComposer / V4ComposerToolbar）

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G11 | 模型菜单：套餐/连接二级 + 思考滑杆（stop dots + max 气泡）+ 徽标 + 视觉标记 | `ComposerModelMenu.tsx:156-379`；`ComposerProviderMenu.tsx:120-126`；`ModelConfigSelect.tsx:460-523` | `provider-settings.getView/refresh`、`model-selection.getView` | 本轮批4已二级化（分段替代滑杆）；徽标/视觉标记缺 | 缺二级（扁平；下阶段对齐批4） | iOS P3 / **Android P1** |
| G12 | 模型菜单「管理模型」页脚 → 设置模型供应商 | `V4ComposerToolbar.tsx:895` | — | 有（批4页脚） | 缺 | P3 |
| G13 | 队列：拖拽排序 / 编辑锁指示 / `/compact` 行标签 / 暂停横幅+恢复 / held 确认弹层 | `ConversationQueuePanel.tsx:238-479`；`command.ts:270-311` | sendQueuedNow/editQueueItem/deleteQueueItem/reorderQueueItem/setAutoDrain/lock 族 | **已补**（长按拖排序：onDrag + QueueDropDelegate 落点描边 → reorderQueueItem CAS） | **已补**（detectDragGesturesAfterLongPress + 行 window 坐标定位落点 + 朱磦描边 → reorderQueueItem；编辑态行不参与） | — |
| G14 | 斜杠命令目录（CLI catalog + /compact /goal /target /side /btw 本地命令） | `slashCommands.ts:41-76`；`slashCommandHelpers.ts:15-48` | workspace-config slashCommands | **等效承载登记**（本地预置菜单已承载 /goal /plan /compact 三核心命令的客户端拦截转命令；CLI 目录字表依赖 workspace-config 投影——桌面实测表空，投影到达后字表即自动补齐（消费面已预留）） | **等效承载登记**（同构——本地预置 + 投影预留） | P3 |
| G15 | @/$/#/# 提及（文件/插件/会话/技能/子代理/白板 candidate 面板） | `lib/promptInputTriggers.ts:38-47`（@ 触发正则 + 中文放宽）；`mentions/mentionTypes.ts` | file.searchWorkspaceFiles 等 | **部分已修**（composer 工具行 @ 钮 → ReferenceFilePickerSheet（G-024 组件复用）+ **服务端 searchFiles 250ms 防抖接线**（FileTreeView 同窗，onChange 版）；点选插 `@"path" ` 进草稿。$ 技能/# 会话/子代理/白板 candidate 面板缺——符号自动触发需键盘游标监听，触屏以显式按钮承载同语义） | **部分已修**（composer @ 钮 + MentionPickerScreen 全屏 Dialog（searchFiles 400ms 防抖 + 空结果态）；点选插 `@"path" ` 进 updateDraft。$/#/子代理/白板缺同上） | **部分已修**（chat composer @ 钮 + mention 浮层（connection.searchWorkspaceFiles 400ms 防抖 + 空态）点选插 `@"path" ` 进 draft+persistDraft；**# 会话子面板已补**（scope tabs @文件/#会话——# 命中插 `#标题 ` 引用；commandCenterSearch sessions 通道复用）；**$ 技能子面板已补**（skills 频道 list【实证·上游仓 skills/skills.ts:43-45 descriptor】三 tab + 本地词表过滤 + 点选插 `$名称 `；¥/￥ 归一同规）；**子代理 candidate 缺**（远程桥 Method not found 否定性结论）；**白板 candidate 缺**（小程序白板绘图面板未实现——数据源为 iOS/Android 白板面专属，独立工程登记）） | — | | — |
| G16 | 附件粘贴/拖放/白板提及；限额 8 件·20MB·64K 文本 | `useComposerAttachments.ts:257`；`chatAttachments.ts:37-44` | attachment 四方法 | **已补**（composer 粘贴钮：剪贴板图片→jpeg 待发/文件 URL→mime 读字节/纯文本→追加草稿） | **已补**（chat 页 📋 钮：ClipDescription image/* 经 contentResolver 读字节入待发；纯文本追加草稿；enqueueAttachment 同门） | — |
| G17 | 输入预测（mobile 专属协议） | `zcode-protocol-v4/transport.ts:359`；`input-prediction/CONTRACT.md` | `conversationInputPredictionV4` | 有 | 有 | — |
| G18 | prompt 历史 ↑/↓ | `LexicalChatInput.tsx:1341-1379` | — | **已补**（chip 单向循环原已有；0-13 波无回归） | **已补**（PromptHistoryStore（SharedPreferences `zcode.prompt.history.v1` cap 50/连续重复不入账）+ cyclePromptHistory 单向循环 + composer 🕘 chip（05-chip-history）+ performSend 入账） | — |
| G19 | 语音输入（dictation） | `ConversationComposer.tsx:759,2704` | voice-input 频道 | 缺（移动端不在 RPC 白名单——web 手机壳同样不可达，如实不做） | 缺（同） | 如实不做 |
| G20 | 停止任务时 Esc/空草稿判定 + retry（web 无 retry 按钮故意的） | `ConversationComposer.tsx:1460,2712`；`ConversationRowView.tsx:355` | stopTurn/retryTurn | 有（停止）；retry 无 UI（与 web 一致） | 有（同） | — |

## 3. 交互卡与队列（V4InteractionDialogs / PermissionDialog / ElicitationDialog）

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G21 | 权限卡：编号选项列表 + 全宽自定义行 + deny/refine 反馈文本框 + guarded/yolo 切换 | `PermissionDialog.tsx:631,680-703,1193-1232`；`snapshot.ts:277` | resolveInteraction | **已补**（freeText=true 反馈输入行；拒绝携带 answer.freeText） | **已补**（同构；permissionAnswerWithFeedback） | — |
| G22 | 提问卡：多题向导（上一题返回）+ 多选 checkbox + auto-resolution 倒计时可暂停 | `ElicitationDialog.tsx:652-958`（questionIndex :353 / drafts 不重置 :338,373 / hasPrevious :686）；`TaskInteractionBadge.tsx:33-59` | resolveInteraction / snoozeInteractionAutoResolution | **已补**（多题向导化：一次一题 + 第 N/M 进度 + 上一题（index>0，drafts 按 qid 保留）+ 跳过 cancel + 下一题/提交二合一；提交 submitQuestions 信封 content.answer_N+answers 汇总+单题 answer 捷径；倒计时+稍后处理前波已有） | **已补**（同构向导：freeTextEnabled 模型+解析（iOS :3358-3366 同构）+ onQuestionAnswer raw 通道 + questionAnswersAnswer 构造器 + 上一题/跳过/下一题-提交 + 单测 2 例） | P3 |
| G23 | 插件安装卡（跳过/安装并启用） | `PluginInstallCard.tsx:68-98`；`V4InteractionDialogs.tsx:357-367`（optionId=choice 字面量） | resolveInteraction | **已补**（PluginInstallInteractionCard 四文案分叉；此前未知 kind 落 EmptyView） | **已补**（同构分派 + 卡） | — |
| G24 | MCP 待审核指示（workspaceMcpAdmission pendingCount → composer 指示条） | `WorkspaceHookPendingBanner.tsx:215-316`；`snapshot.ts:531-536` | state.workspaceMcpAdmission | **已补**（横幅 + 去审核切设置 tab（纯导航）+ 忽略本地态） | **已补**（白名单已有键（11-24 轮）；横幅 + 同款两动作） | — |

## 4. 面板 / 工作流 / 子代理（SessionPane + SidePane）

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G25 | 工作流 run 详情页：血缘（amends/superseded）、用量、结果/停止原因、并发 chip 冷却、Configure/Resume/Save（**纠偏：事件日志/脚本原文节上游 2026-09-04 已撤**——SidePane.tsx:396-427 注释，journal RPC 保留但不做 UI） | `WorkflowRunSidePane.tsx:352-435`；`Sections.tsx:76-463`；`workflow-runs.ts:419-583`；`workflows/save`=zcode-agent `saveSavedWorkflowFromRun` | v4 command 族 + `findSavedWorkflowForRun`/`saveSavedWorkflowFromRun` | **已补**（血缘两行/终态区 resultPreview+error+stopReason 文案表/冷却 chip/保存表单（target_exists 覆盖确认）/再次运行；G25 字段解析 8 枚） | **已补**（同构四区 + WorkflowSaveFormDialog） | **已补**（chat 页 run 卡收口：血缘/终态/停止原因 + **阶段链**（phases[].{name,status} 结构化渲染，web phasesOf 同口径）+ **forRun 已存判定接入**（findSavedWorkflowForRun 逐 run 探测——savedByRun 翻面「保存/再次运行」，失败不挡保存上游同口径）+ 保存（target_exists 覆盖确认）/再次运行（startSavedWorkflow 信封）；**actors/冷却 chip/artifacts 面待工作流面板独立轮**——消费面独立登记非 G25 主线） |
| G26 | 工作流目录 / 子代理历史目录（ended 翻页） | `SubagentDirectorySidePane.tsx:161-204`；`WorkflowRunDirectorySidePane` | `session/subagents`（**远程桥不暴露**——协议文档 §10.1c 否定性结论，2026-11-24 真机 Method not found） | 能力门控隐藏（实现保留） | 同 | 如实不做（桌面桥暴露后自动恢复） |
| G27 | 后台 bash 输出页（有界输出查询） | `useBackgroundBashOutput.ts:41-59`（1s 轮询）；`background-bash-output.ts`（≤8192 快照式无 offset） | `v4/conversation/backgroundBashOutput`（iOS/Android wire = zcode-agent `backgroundBashOutputV4`） | **已补**（bash 族行「输出」钮 + sheet 1s 轮询 + truncated/outputPath） | **已补**（同构 sheet） | — |
| G28 | 活动摘要头像组（v4.0.0 三枚叠压 + 结果徽标） | `ConversationStatusPanel.tsx:1301-1399` | state.subagentProjection | **已补**（ChatView 头部 ≤3 枚叠图 + 徽标点；Android 同规则移植回 iOS） | 有 | — |
| G29 | 状态面板 auto/panel/mini 三档呈现切换 | `ConversationStatusPanel.tsx:2063-2157`（v4.0.0） | — | **等效承载登记**（auto 档=桌面宽度自适应面板；移动端单宽度无 auto 触发面——chips 行+面板展开两态已承载「入口→详情」信息层级；三档视觉切换独立轮登记） | **等效承载登记**（同——chips 行+面板展开） | P3 |
| G30 | 用量：上下文环 + cache 命中率 + plan 余量面板 + 配额横幅 | `contextUsage.tsx:261`；`CodingPlanUsageRemainingPanel.tsx:222`；`ConversationQuotaBanner.tsx:53` | `v4/usage/stats`、`coding-plan-subscription` | 弱（composer 上下文条 + 设置用量页；横幅缺——已裁决持久错误行+用量卡等效承载，PARITY 登记） | 弱（同） | — |
| G31 | 白板多板 tab（桌面驱动） | `AnimatedSidePanePanel.tsx:1402` | — | 单板/workspace（v1 裁决差异，PARITY P-010 已登记） | 单板（同） | — |
| G32 | 文件浏览器：仅变更 toggle / 行右键（加入聊天等）| `WorkspaceFileTree.tsx:710-737`；`model.ts:341-360` | file.* | **已补**（仅显示变更 chip：file 行 path ∈ git unstaged 变更集过滤） | **已补**（FileTreeScreen 顶栏「仅变更」chip：diffFiles() 变更集过滤 file 行） | — |

## 5. 列表 / 项目 / 分组（ProjectSidebar）

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G33 | 行内待批徽标（kind 区分 + auto-resolution 倒计时进度环 + 可暂停） | `TaskListItem.tsx:716-726`；`TaskInteractionBadge.tsx:21-58`；`zcode-task-types-core.ts:262-268`（轻量投影） | sessions-index `pendingInteraction` + `snoozeInteractionAutoResolution` | **已补**（行徽标 kind 区分 + TimelineView 倒计时环 + 点按暂停；静默期/snoozed 不画环） | **已补**（同构徽标 + Canvas 环 + snooze；主列表行接线，归档组行后续批） | — |
| G34 | 置顶 20 上限 + 显示更多/更少 | `ProjectTaskRows.tsx:112`（slice(0,20) + showMore/showLess） | — | **已补**（pinned cap 20 + 「显示更多置顶（N）/收起置顶」行） | **已补**（PINNED_PAGE_SIZE=20 + timelineSections pinnedLimit + LazyColumn 尾溢出行；PROJECTS 看板另计） | **已补**（PINNED_PAGE_SIZE=20 + pinnedSection(showAll)/pinnedOverflowCount + sessions 页溢出行 onTogglePinnedMore + wxml pinned-more） |
| G35 | 行 change-summary（+N/−N 摘要） | `TaskListItem.tsx:482-492` | task changeSummary | 有（taskImpact 行） | 有 | — |
| G36 | 分组视图组色 / 移动到顶部 / 新建组 | `TaskGroupedView.tsx:244-245`（updateColor → save({id,color})）；`contract.ts:4`（色七档 union） | `task-groups.save` | **已补**（组色双端 + **组置顶排序双端**：wire 取证【实证·上游仓 task-groups/index.ts:31-34 nodeSchema 判别联合含 {type:\"group\",groupId} strict + repository.ts:189-202 moveInside——组节点 groupId 恒 null（顶层重排）/sourceGroupId 恒 null/**无 anchor 时 index=0 = 置顶**】；iOS pinTaskGroupToTop store 方法 + 组头 Menu「置顶到顶部」项；Android RemoteTaskGroups.pinTaskGroupToTop + 组头 combinedClickable 长按置顶（点击换色保留）；失败词表 stale-source/invalid-group 如实上屏） | **已补**（同构） | **已补**（connection.readTaskGroups/saveTaskGroupColor/pinTaskGroupToTop 三 RPC + sessions GROUPED 视图组头色点（点击循环七档）/长按置顶（groupColors 映射 + _groupIds 定址；wx.showActionSheet 简化为点击循环） | — |
| G37 | 项目行：显示文件（逐根）/ 行内新建任务 / 拖拽排序（远控已禁） | `ProjectNodeContent.tsx:331-406` | project.moveTask | 缺（项目节点行仅折叠+运行/未读汇总） | 缺（同） | P3 |
| G38 | 命令中心（全/命令/会话/文件四 scope 搜索 + 历史） | `CommandCenterDialog.tsx:167-182`（scope 前缀 >/:/@）；`:463-492`（listTaskList kind:"all" scope:"host" searchQuery + 本地文件过滤）；`commandCenterSearchHistory.ts:9-10`（localStorage 键 + limit 20） | `zcode-task.listTaskList search` | **已补**（CommandCenterSheet：⋯菜单「统一搜索」入口 + 全部/会话/文件三 scope chips（命令 scope 由 composer 斜杠菜单承载，独立页不加）+ 400ms 防抖 store.searchSessions（listTaskList search）+ store.searchFiles + 历史 chips（UserDefaults 同词面键 limit 20）；会话命中推 chatPath、文件命中拷路径语义留树页检索） | **已补**（CommandCenterScreen：路由 + 列表工具区「统一搜索」行 + 三 scope chips + 400ms 防抖 + 历史 chips；会话命中导航 chat） | **已补**（pages/command-center 新页（app.json 注册）+ sessions footer 🔍 入口 + connection.commandCenterSearch（listTaskList search + file.searchWorkspaceFiles 双通道）+ 历史 chips（wx storage 同词面 limit 20）；会话命中 navigateTo chat、文件命中记录历史） |
| G39 | 跨工作区切换（switchWorkspace 桥重建） | `web/main.tsx:1603-1613` | bootstrap/bridge-open | 有（P3-10 工作区切换器） | **缺**（switchWorkspace 无 UI 调用——Android 盘点 §9） | **Android P1** |
| G40 | 行元数据：cron/off-peak 图标、worktree 标签、远端设备标签 | `TaskListItem.tsx:759-779`；`zcode-task-types.ts:56-64`（isCronTask=cronAutomationId‖automationId / isOffPeakTask=offPeakTaskId【实证·上游仓】） | — | **已补**（cron/off-peak 图标 + **worktree `<仓> · <桶>` 标签 + 远端设备名**（worktreeRoot/serverDisplayName 六层贯通双端；UUID 禁上屏——web taskSourceAttribution.ts:19-24 同规；段数不足两段 worktree 不显——worktreeLabel.ts 同规）） | **已补**（同构） | **已补**（mapTaskRows 读 cronAutomationId‖automationId/offPeakTaskId/worktreeRoot‖worktree_root/serverDisplayName 四族宽容读 + session-model 透传 + worktreeLabel 派生 + session-row 图标⏰/🌙与标签行渲染） |

## 6. 设置 / 账号 / 连接

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G41 | 行为设置：交互队列/引导、ask 自动决议、模型IO保留、动态工作流模式等 9 项 | `settingsPageHelpers.tsx:826-992` | setting 频道 | **已补**（BehaviorSettingsPage 策展五键原生分组——其余键值面 DesktopSettingsPage 等效承载） | **已补**（BehaviorSettingsScreen 同策展五键：store.behaviorSettings/setBehaviorSetting setting 频道双候选；设置页「行为设置」行 → BEHAVIOR_SETTINGS 路由；**盘点纠偏：Android 已有 DesktopSettingsScreen 路由**——「无桌面设置面」判定过时） | — |
| G42 | 外观设置：界面字号 `--ui-font-size` / 代码预览字号/换行/行号 | `settingsCodePreview.tsx:121-253`；`uiFontSize.ts:4-10`（**本地偏好非桌面 setting**——localStorage `zcode-ui-font-size-px`，12–20px 默认 14，只缩字号 token 不动图标/间距/圆角 :24 注释【实证·上游仓】） | — | **部分已修**（UiFontSize × T.font/T.mono + **代码预览三键**：CodePreviewSettings（web 同词面键 zcode-code-preview-settings；fontSizePx 12–20 默认 12/行号默认 on/换行默认 off【实证·上游仓 codePreviewSettings.ts:18-24】）+ Appearance 页 CodePreviewSettingsSection（Slider+两 Switch）+ FilePreviewView.sourceView 三键生效（wrap 模式折行放宽 minWidth）+ FilePreviewScreen 源码段顶三键行内控制（字号循环/行号/换行；触屏简化独立页成本高——行内即达意，登记差异）；**主题双键 shiki 移动端 TinySyntax 无主题面不投影**——登记差异 | **已补**（**前置 files 页预览内容接线完成**：connection.readFileText（file.readTextFile {path,offset,length} → FileTextSlice【实证·上游仓 Android readTextSlice :347-361 同构】256KB 有界首屏 + totalBytes 判截断）+ 源码视图行号渲染 + 三键行内控制（字号循环/行号/换行钮，zcode-code-preview-settings 同词面持久）——G42 三键小程序消费面成立） | — |
| G43 | 归档：自动归档（3/7/14/30 天）+ 全部删除（confirm）+ 分组头 | `settingsPageHelpers.tsx:69,73-136`（档位枚举）；`validationAppSettings.ts:555-557` | setting 频道两键 | **已补**（AutoArchiveSettingsPage + **全部删除**：deleteArchivedTasks 按 workspace 分组一次 RPC + confirmationDialog + 结果行） | **已补**（AutoArchive 区 + **全部删除**：zcode-task deleteArchivedTasks 批量 RPC 换掉逐行 deleteTask（旧循环对已归档行报 sessionNotFound 的假失败根修）+ 结果行） | — |
| G44 | 订阅计划升级入口（footer 升级卡 + 对话框） | `WorkspaceSidebarFooterUsageSummary.tsx:421-479` | coding-plan-subscription | 缺（合规：商店 listing 禁 Z.ai 联动暗示，升级收桌面端——**登记为刻意不做**，见差异） | 同 | 刻意不做 |
| G45 | 会话分享（publish/import + 只读时间线页） | `ConversationShareMenu.tsx`；`conversation-share-interaction/` | conversation-share（手机白名单只读 getCapabilities/getPreview） | 有（分享落地预览深链）；**发起分享缺** | 同 | P3 |
| G46 | 反馈工单（feedback.list） | 否定性结论：web feedbackService 无 list（协议文档 §12-8） | feedback.create/comment/upload | 隐藏入口 H1（实现保留） | 同 | 如实不做 |
| G47 | 桌面二维码不在位提示（web 扫码页死代码，App 有实扫） | `ui/webRemoteControlScanPage.tsx`（无调用点） | — | 有（反向：App 有 web 没有的实扫） | 有 | — |

## 7. 缓存 / 性能

| # | web 能力 | 上游证据 | RPC | iOS | Android | 优先级 |
|---|---|---|---|---|---|---|
| G48 | keep-warm 30s 会话租约（重开零 RPC） | `sessionDataLayer.ts:31,84-90,149-159` | — | 有（批5） | 有（keep-warm 租约已在；**G52 adoptCaches 亦已补**——同工作区重连迁移行/消息/state 投影与会话表，订阅簿记不迁移【iOS exportCaches/adoptCaches 同纪律】） | — |
| G49 | 滚动位置记忆 LRU 200（workspace::pane::session 键） | `chatSessionScrollMemory.ts:9-66,116-145` | — | **已补**（ChatScrollMemory LRU 200 + scrollPosition 双向绑定 + 首装载恢复；行 id 锚定近似） | **已补**（ChatScrollMemory + firstVisibleItemIndex 恢复） | — |
| G50 | 草稿持久化（localStorage per workspace/session） | `composerDraftStore.ts:9-10,41,389-393`（附件不入草稿、空白删键） | — | **已补**（zcode.composer.draft.v1.<id>；didSet 写/发送清空；E2E reset 禁用） | **已补**（ComposerDraftStore SharedPreferences + init/updateDraft/两清空点接线） | — |
| G51 | 待发命令账本（24h TTL + 重发横幅；**web 也从不自动重放**） | `pendingCommandRegistry.ts:37-38`；`PendingCommandRecoveryBanner.tsx:12` | queryConversationCommandsV4 | 弱（发送失败错误行+草稿回填，已裁决等效承载） | 缺（同） | — |
| G52 | 断线重连后行缓存保留 | `AppSession.swift` exportCaches/adoptCaches（iOS 已有） | — | 有 | **缺 adopt**（重连全丢重建） | **Android P1** |

## 8. App 有 / web 没有（反向盘点——登记为刻意新增或候选收敛）

| # | App 面 | 判定 |
|---|---|---|
| R1 | 待批 tab（跨会话审批收件箱 + **Android 底部导航壳三 tab**（会话/待批/设置——Scaffold+NavigationBar，tab 路由集合外隐藏底栏；testTag 14-tab-*）） | 本轮产品裁决新增（R2）——web 无此面；待批在 web 只是列表行徽标 |
| R2 | 模型选择器「最近使用」+ 搜索 | 批4 触屏可达性新增（web 明确无搜索/最近——`display.tsx:120` matches 函数零调用点） |
| R3 | 任务看板 tab（已撤）/ 全局文件 tab（已撤） | 早期设计遗留，本轮 R2 裁决移除 |
| R4 | IM Bot 管理 / 服务器详情 / 提交图谱 / 工作流库·错峰任务页 | web 手机壳无（automations/plugin-store 主视图被 `WorkspaceShellLayout.tsx:1484-1493` 强制回 chat）；App 作为设置内管理页保留——**进 cleanup-list 复核入口必要性** |
| R5 | 投递模式 now 档（不下发命令的本机偏好） | A-2 探针裁决产物；web followup 只有 queue/guide（`followupModeSettings.ts:4-9`）——保留，桌面无感 |
| R6 | 落印盖章动效 / 品牌系统 | 朱批品牌（motion-spec），与 web 无对位 |
| R7 | 通知本地推送（任务完成/待审批/失败） | P1-7 OS 通道；web 无——保留 |

## 9. 下阶段批次建议（按依赖与价值排序）

> **2026-11-25 缺口补齐轮（三波）回写——P1/P2 全清 + P3 小项已收**：G6/G7/G21/G23/G24/G25/G27/G28/G33/G49/G50/G16/G32/G13/G41/G43（含「全部删除」双端）/G34/G40 双端落地；Android P1 集群（批4/G52/G39/R1 收件箱）已清；Android 已镜像 iOS 全部面（G16/G32/G13/G41 第三波补齐）。
> 清单纠偏累计五处：G6 焦点窗 v4.0.0 不存在；G25 事件日志节上游已撤；G48 keep-warm Android 已在；**G41「Android 无桌面设置面」判定过时（DESKTOP_SETTINGS 路由已在）**；**Android「全部删除」旧循环逐行 deleteTask 对已归档行报 sessionNotFound（假失败）——批量 RPC 才是桌面正规入口**。
> 小程序已镜像：G21/G23/G33/G49/G50/G34/G38/G36/G15/G42/G40/G25/G18/**G22（第十六波：多题向导 qnow 预装饰 + 上一题/跳过/下一题-提交 + freeTextEnabled 解析 + questionAnswersAnswer 信封）**。

1. **P3 余量**：G44 订阅升级入口（刻意不做复议保留——唯一登记项）。G36 组色+置顶+**排序「上移一位」**（第八/十一波）/G9 行高缓存+活尾跟随（第七波）/G38 命令中心（第五波）/G15 提及面板含 $技能/#会话子面板（第六/十一波）/G42 界面字号+代码预览三键（第六波）已双端落地；小程序第四至八波镜像（第九/十/十一波）已落地。
2. **待真机取证**：全部新增 RPC 面首击回执（findSavedWorkflowForRun/saveSavedWorkflowFromRun/backgroundBashOutputV4/setting 频道 autoArchive+behavior 五键/**deleteArchivedTasks**/snooze）升格【实证】。
3. **刻意不做**（不再复议）：G19 语音、G26 两目录（桥不暴露）、G44 升级入口、G46 反馈工单、G51（等效承载已裁决）、G25 事件日志 UI（上游已撤）。

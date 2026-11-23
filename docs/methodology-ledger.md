# 方法论台账（methodology ledger）

> 用户指令（2026-11-20 确立，最高优先级）：
> **实现过程中发现任何导致偏差且属于方法论问题的，必须更新 skill 并持续调整；
> 这是解决根本问题/共性问题的路径。**
> 本台账是该指令的落地物：每一次方法论变更都必须在这里留一行，并且**必须带一个机械检查**
> （契约断言 / 门禁脚本 / 采证步骤），否则只是散文，会随时间失效。

## 协议（每次方法论变更五步）

1. **归因**：把偏差分成「内容缺陷（单个元素）」与「方法论缺陷（一类元素/重复失效模式）」。
   判据：同类问题是否可能再发生在别的元素/页面上？会 → 方法论。
2. **定位机制**：对照 `.agents/skills/web-to-app/references/source-to-contract.md` 的
   十类机制（M1–M10），确定是哪一类或哪几类。
3. **改 skill**：同轮更新对应技能文件（通常 `source-to-contract.md` + 相关 reference），
   写清规则、判据与反例；**同轮完成，不推迟到下一轮**。
4. **上机械检查**：同一轮加一个可执行检查。没有机械检查的变更记 `partial`，不算关闭。
   本仓落点固定为 `.agents/skills/web-to-app/scripts/methodology_checks.py` 的一个子命令；
   新增一类缺陷时**先加注册表数据，再加子命令**，不要只写散文。
5. **记账**：本文件追加 ML-N（症状/机制/skill 变更/机械检查/状态）。

**升级规则**：同一类缺陷在方法论更新后再次出现 → 说明更新不足，必须升级为
「能力/门禁」变更（补工具子命令、改契约结构、加工作流门禁），不是再写一条散文。

## 已沉淀的方法论变更

| 编号 | 症状（实证） | 机制 | skill 变更 | 机械检查 | 状态 |
|---|---|---|---|---|---|
| ML-1 | 2026-11-19 `c590468..fec9759` 同步中，`sync_state.py begin/done` 把 web 源仓本地 HEAD（`a9fe401`，滞后）记成基线，实际同步目标是 `fec9759`；下次无 `before` 的运行会重复同步旧区间 | M7 权威漂移（把"读到的 HEAD"当"权威目标"；读取 ≠ 权威） | `sync_state.py` begin/done 新增 `--commit` 显式钉住同步目标 + `reconcile` 向前自校正（仅允许向前，回退/无关需 `--force`）；SKILL.md / sync-workflow.md 用法；工作流 begin/done 均传 `--commit <after>` 并在 done 后核对落盘基线 | `methodology_checks.py baseline`（审计字段 `commit_source`/`source_head`/`head_lagging` + web 源仓可达性） | enforced（2026-11-20，PR #3） |
| ML-2 | 续跑识别曾用 `git log --grep ... --invert-grep` 判重：收尾提交也带 trailer，且多 `--grep` 下 invert 是 OR 语义，续跑被误判成「重复」提前退出 | M10 门自身未验证（判定门未做正负控） | 改为纯区间提交计数状态机（业务提交>0 / 仅 chore / 无提交三态）；手册 §3 明确「不要退回 grep 方案」 | 工作流 resumeMode 判定实测（三态全覆盖） | enforced |
| ML-3 | 工作流收尾提交白名单最初只有 `parity-sync-state.json`，漏了 `parity-diff-ledger.md`；`sync_state.py done` 同时写两文件 → 残留脏文件，下次触发被准入挡住 | M10（收尾机制自身缺口） | `WORKFLOW_TRACKED_WRITES` 双文件；纪律「新增工作流自有写入必须同步更新白名单」（手册 §5） | `methodology_checks.py state-ledger`（状态文件 ↔ 台账基线行镜像一致性）+ 工作流准入 tracked dirty 校验 | enforced |
| ML-4 | 工作流 bash 非登录 shell，PATH 无 node → 小程序门禁假失败（`node: command not found`）被误读成代码问题 | M10 | 门禁命令内多级 node 回退（PATH → `/usr/local/bin` → `/opt/homebrew/bin` → Floatboat 内置）+ `exit 127` 区分环境缺陷与代码缺陷（手册 §6） | 门禁输出「使用 node: …」行 + `mp.code===127` 判定；「不要把环境缺失误判成代码问题」 | enforced |
| ML-5 | 同一区间先由 doubao 处理判定「三端无代码落点、仅文档登记」；deepseek-flash 找到真实落点（iOS Keychain 无降级态、Android 无差别 `clear()`、小程序明文 JWT 落盘，P-031）——「没有发现」≠「不存在」 | M6 变体（自查指标替代语义核查） | 重要分析结论交叉复核纪律（手册 §8）+ 报告 agent 自检字段 | Phase 6 构建门禁（落点必须编译/测试过）+ 报告 `agent_self_checks` | **partial**（交叉复核本身暂无机检；同类再发时升级为双模型强制对跑门禁） |
| ML-6 | PR 落地通道逐次摸索：GitHub Connector PAT 建 PR 可、合并 403（Resource not accessible）；内置浏览器无登录态；`git merge --ff-only` 在本地 base 领先 origin 时以 "Already up to date" 通过，落地推送把漂移提交一起带上远程（2026-11-20 临时仓模拟实测） | M10（落地通道未预验证即投入使用） | 工作流 Phase 7.5 固化本地 `merge --no-ff` 落地通道 + base 与 origin/base 逐字节守卫 + 冲突自动 `git merge --abort` 回滚；手册 §8 通道矩阵 | 门禁 `pr_landing` 字段（merged / already merged / skipped / fail）+ 临时仓正/负控模拟（正常落地/幂等重跑/漂移拒绝/冲突恢复） | enforced（2026-11-20） |
| ML-8 | 文档口径的门禁启用命令 `gradle … -Dbiuz.stubLive=true`（StubLiveServerTest 头注/交接 §5 同口径）静默失效——gradle `-D` 落 daemon JVM，不透传 fork 的 test worker，JUnit `EnabledIfSystemProperty` 恒不命中 → 活体宿主从未启动、模拟器链路验证 silently skip（2026-11-21 P-032 轮实测：带 flag 跑 892ms「绿」退出，port 文件不存在） | M10 门自身未验证（门开关掉了、门没真开——与 ML-4 同族：环境/配置缺陷伪装成「通过」） | `android/app/build.gradle.kts` test 任务补 `System.getProperty("biuz.stubLive")` 显式转发；启用判据固化为「输出必须含 `STUB-LIVE port=<n>` 且 `/tmp/m2ui-stub-port` 落盘」 | 运行时观测（STUB-LIVE 行/port 文件存在性）；`methodology_checks.py` 尚无对应子命令 | **partial**（修复即门启用；子命令机检待同类再发时升级） |
| ML-7 | `methodology_checks.py` 首跑（2026-11-20）实证三类登记脱钩：① 台账 D001–D009 共 8 行「生效」但全仓代码 APP-DIFF 标记仅命中 D014–D017（两级缺一不可被打破）；② BACKLOG 状态列词表漂移——真实状态出现「已解除阻塞」「如实不做」「部分已修」等文件头未声明的衍生词（P-013/014/025/026/027/030/031）；③ P-027 状态裸「部分已修」未注记剩余部分 | M7 台账/登记脱钩（登记与代码实态分叉；状态机词表未正式化） | ① `source-to-contract.md` §5 新增 `diff-ledger` 双向对账；② backlog 状态词表正式化（待修/修复中/部分已修*/已修/已完成/已解除/如实不做，`*` 与「如实不做」必须括号注记剩余/堵点/复验条件），写入 SKILL.md 留痕规则；③ 处置工作项见下 | `methodology_checks.py diff-ledger`（双向对账 + kind 词表 + pending 挂账）+ `backlog`（编号唯一/词表/证据非空） | **enforced（2026-11-20 清零，PR 落地后复跑全 PASS；处置过程另发现 D001/D008 台账文案与代码实态不符，已按实修正而非照文打标）** |
| ML-9 | skill 早期内容是「一个 web→原生项目收敛弧线」的沉淀，机制表实例、出处段、路由表都带该项目专有信息（实例列基本上是单一项目的返工记录；机制只到 M10），作为**泛化 web→app 技能**在别的项目上复用会引入不相关上下文，且缺几条通用性强的维度（分类粒度、原生壳层、文本度量、动作语义、证据时效、同族单点） | M7 收敛对象（把"一个项目的教训"当"通用方法论"）+ 通用性缺口 | ① `source-to-contract.md` 机制表 M1–M10 → **M1–M16**，实例全部改为通用/不指名；出处段改为「多个项目长期收敛的实证方法论」；② 新增 4 篇 reference：`false-positive-triage.md`（假缺陷四型+假绿反向污染）、`class-matrix-and-native-surfaces.md`（类矩阵/原生壳层/真实字体度量/动作目的地/共享组件不变量）、`layout-claim-evidence.md`（target/expected/measured/tolerance 五项）、`runtime-guards.md`（双端运行时护栏）；③ `SKILL.md` 新增「四条硬口径」（假缺陷先分诊 / 改前写类矩阵 / 布局结论要 bounds / 证据时效且 cleared 不能自指），路由表扩到 ⑩–⑬；④ 强化 `ui-parity.md`（真实字体度量与字素簇截断、目标元素 bounds）与 `acceptance.md`（四维覆盖声明、构建身份硬门、原生壳层检查项）；⑤ 全仓清除项目专有残留 | 无新机检子命令，复用既有 5 子命令 + `selftest`（6/6 PASS）；文档类变更以「reference 链接完整性 + `py_compile` + 真实项目跑 5 子命令」自证 | enforced（2026-11-24，文本审计无项目专有残留、链接零缺失、工具 SELFTEST PASS 6/6） |

### ML-7 处置清单（复验条件）—— 2026-11-20 全部清零

- [x] D001/D002/D003/D005/D006/D007：语义成立，在真实实现处补 `APP-DIFF` 标记共 14 处（iOS 7 / Android 5 / 小程序 4，含 session-row 悬空引用「见 APP-DIFF」补编号 D006）
- [x] D008：语义**部分不成立**——全仓核对仅 iOS 有常驻搜索框（`04-search`），Android/小程序无任何搜索入口；台账改为 iOS-only 并如实标注功能缺口待裁决，未在缺失端伪造标记
- [x] D001：同理修正——小程序无侧抽屉形态（chat 页无 pane），台账标注「功能未移植，非刻意差异」；标记仅落 iOS `.sheet` 组与 Android `ModalBottomSheet`
- [x] D009：语义成立且本就 iOS-only（chips），标记落 `ConversationListView.sourceChips`
- [x] P-027 状态列补括号注记（剩余写面/钩子子代理个性化归 P-009/复验条件）
- [x] `diff-ledger` + `backlog` 复跑对上述条目全 PASS（见清零 VERDICT 存档）

### 首跑 VERDICT 存档（2026-11-20）

```
VERDICT baseline:    REVIEW (fail=0 review=3 pass=3)   # 历史基线缺审计字段（ML-1 之前记录），下次 done --commit 补记
VERDICT state-ledger: PASS (fail=0 review=0 pass=3)
VERDICT diff-ledger: FAIL  (fail=8 review=0 pass=5)   # D001–D009 台账脱钩 → ML-7
VERDICT page-index:  PASS (fail=0 review=0 pass=2)
VERDICT backlog:     FAIL  (fail=1 review=1 pass=1)   # P-027 裸状态 → ML-7
SELFTEST PASS: 6/6 assertions（diff-ledger/page-index/backlog/state-ledger/baseline 正负控）
```

### 清零 VERDICT 存档（2026-11-20，ML-7 处置后）

```
VERDICT diff-ledger: PASS (fail=0 review=0 pass=13)  # 8 个 active D 行全部有代码标记，无未登记标记
VERDICT backlog:     REVIEW (fail=0 review=1 pass=1)  # P-027 FAIL 清零；余 1 条跨行表格 REVIEW（解析器限制，人工核对项）
VERDICT state-ledger: PASS (fail=0 review=0 pass=3)
VERDICT page-index:  PASS (fail=0 review=0 pass=2)
VERDICT baseline:    REVIEW (fail=0 review=3 pass=3)  # 同首跑，历史审计字段待下次 done --commit 补记
SELFTEST PASS: 6/6 assertions
```

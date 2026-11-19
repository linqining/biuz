# 方法论台账（methodology ledger）

> 用户指令（2026-11-20 移植自 mtt-web-sync 2026-11-14 同名协议，最高优先级）：
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
| ML-7 | `methodology_checks.py` 首跑（2026-11-20）实证三类登记脱钩：① 台账 D001–D009 共 8 行「生效」但全仓代码 APP-DIFF 标记仅命中 D014–D017（两级缺一不可被打破）；② BACKLOG 状态列词表漂移——真实状态出现「已解除阻塞」「如实不做」「部分已修」等文件头未声明的衍生词（P-013/014/025/026/027/030/031）；③ P-027 状态裸「部分已修」未注记剩余部分 | M7 台账/登记脱钩（登记与代码实态分叉；状态机词表未正式化） | ① `source-to-contract.md` §5 新增 `diff-ledger` 双向对账；② backlog 状态词表正式化（待修/修复中/部分已修*/已修/已完成/已解除/如实不做，`*` 与「如实不做」必须括号注记剩余/堵点/复验条件），写入 SKILL.md 留痕规则；③ 处置工作项见下 | `methodology_checks.py diff-ledger`（双向对账 + kind 词表 + pending 挂账）+ `backlog`（编号唯一/词表/证据非空） | **partial**（工具 enforced；待处置：D001–D009 逐行核对语义→成立者补代码标记、不成立者改「作废」写理由；P-027 补注记；复验条件=diff-ledger 与 backlog 对上述条目全 PASS） |

### ML-7 处置清单（复验条件）

- [ ] D001/D002/D003/D005/D006/D007/D008/D009：逐行核对语义是否仍成立 → 成立者在三端对应实现处补 `APP-DIFF[D###]` 标记；不成立者台账改「作废」并写理由（先例 D004）
- [ ] P-027 状态列补括号注记（剩余部分/堵点）
- [ ] `diff-ledger` + `backlog` 复跑对上述条目全 PASS

### 首跑 VERDICT 存档（2026-11-20）

```
VERDICT baseline:    REVIEW (fail=0 review=3 pass=3)   # 历史基线缺审计字段（ML-1 之前记录），下次 done --commit 补记
VERDICT state-ledger: PASS (fail=0 review=0 pass=3)
VERDICT diff-ledger: FAIL  (fail=8 review=0 pass=5)   # D001–D009 台账脱钩 → ML-7
VERDICT page-index:  PASS (fail=0 review=0 pass=2)
VERDICT backlog:     FAIL  (fail=1 review=1 pass=1)   # P-027 裸状态 → ML-7
SELFTEST PASS: 6/6 assertions（diff-ledger/page-index/backlog/state-ledger/baseline 正负控）
```

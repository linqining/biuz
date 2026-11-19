# 为什么有源码仍有偏差：契约优先方法论（source → contract）

> 本文件是 web-to-app 的**方法论主干**，移植自 mtt-web-sync（`mtt_mobile/.zcode/skills/mtt-web-sync`）
> 在 R1–R15 收敛弧线中沉淀并经用户确证的实证方法论，按本仓（ZCode web → iOS / Android /
> 微信小程序三端同步）的真实事故修订与播种。
>
> 核心命题：**源码解决"知道"，不解决"一致"**。一致性来自可机检的契约：文件映射、
> 状态机、逻辑金标、页面索引、差异标记。没有契约，实现就是记忆转录，验收就是相似度打分。
>
> 配套：机械检查 `scripts/methodology_checks.py`（§5）；变更台账 `docs/methodology-ledger.md`（§6）。

## 1 十类偏差机制（M1–M10，都有实证）

| # | 机制 | 表现 | 参考实例 |
|---|---|---|---|
| M1 | **记忆转录** | 源读过一次，写端时凭记忆重建，不复核源码 | mtt：web 是 `dealer.png` 图片，Android 凭记忆拼图元+字符 |
| M2 | **身份缺失** | 有资产/常量不用，或用了错误代际的；多端各起各名 | mtt：`dealer_badge` 在册但全仓零消费 |
| M3 | **层组合/落点缺失** | 只画主层，漏底框/遮罩/伴随层 | mtt：兔子只有图标缺牌底；头像缺底框 |
| M4 | **状态维度缺失** | 只实现默认态，状态分支靠猜 | mtt：隐藏牌与摊牌背面共用一套尺寸 |
| M5 | **能力词汇不足** | 端上没有对应原语/图元，静默用近似实现 | mtt：无九宫格→整图拉伸。**本仓**：小程序无 AES/webcrypto 原语→纯 JS HMAC 计数器流（D015，**正确做法是登记 APP-DIFF + 台账**，不是伪装成一致） |
| M6 | **指标只测相似度** | 比例/色差/文件数可全绿，语义错不可见 | **本仓**：同一任务不同模型核查深度差异显著——doubao 判「三端无落点仅文档登记」，deepseek-flash 找到 P-031 三个真实落点（iOS Keychain 无降级态、Android 无差别 `clear()`、小程序明文 JWT 落盘）。「没有发现」≠「不存在」 |
| M7 | **收敛对象是 findings 不是 coverage** | 每轮只闭合报告点名的项，长尾从未枚举；把"读到的"当"权威的" | **本仓**：2026-11-19 基线把源仓本地 HEAD（`a9fe401`，滞后）记成同步目标（实际是 `fec9759`）；台账 9 行「生效」但代码 0 标记（ML-7 台账脱钩） |
| M8 | **镜像不核形制** | 只核数据/逻辑/文案，不核各端判定口径与渲染形制 | mtt：iOS 用图、Android 拼图元，两端各自"通过"。**本仓**：三端凭据读取失败语义各自表述→D016 统一判定口径与清理白名单 |
| M9 | **无整帧回归** | 修一处、坏一处，回归覆盖不到 | mtt：R9 修复批引入 K2/K3/K6 三个回归 |
| M10 | **门自身未验证** | 门/通道/判定未做正负控就投入使用 | **本仓**：`--invert-grep` OR 语义误判续跑；node 不在 PATH 门禁假失败；`--ff-only` 在本地 base 领先 origin 时漏放漂移提交；GitHub Connector 合并 403 未预验证 |

## 2 主干流水线：Source → Isomorphism → Contract → Generate → Implement → Verify

映射到本仓九步（`sync-workflow.md` §0）：

| 方法论步骤 | 本仓落点 | 硬约束 |
|---|---|---|
| Step 0 代码同构 | Intake/Freeze：变动文件 → 五类归类 → `file-map.json` | **每个变更 web 文件必须有行**：`webPath / change(A/M/D/R) / 各端落点 / status ∈ same\|renamed\|diverged\|missing\|extra\|na`；`na` 必须写理由（桌面专属能力如实登记横切面，不硬造代码）；`missing/diverged` 进实现清单 |
| Step 1 枚举 | 受影响页面（page-index 定位）+ 横切面四项 | 弹窗/动画/平台语义/渲染差异从采证与契约阶段就进入 |
| Step 2 台账 | 任务包（brief/file-map/state-machine/popup-inventory/motion-spec） | 行缺字段 = 不完整；**注释不是台账**——百分比、换算值、"同源"结论必须能在代码或契约里找到落点，注释只允许写证据指针（`web file:line`） |
| Step 3 契约 | logic-vectors（web 金标先行）+ 状态机契约 + 平台分支 | 测试先行，不等代码写完再编断言 |
| Step 4 生成 | 适配换算按 `ui-parity.md`（先摸清适配栈，禁拿 CSS 值直接除屏宽） | 禁手抄数字；生成物禁手改 |
| Step 5 实现 | 逻辑实现（一个 agent 全平台）→ UI 静态 → 交互/动效 | **缺能力时禁止静默近似**：先登记 APP-DIFF（kind=platform/business），再实现 |
| Step 6 验收 | 逻辑对拍 → 实拍对比 → 全门 → `sync_state.py done` | 门禁语义见 §3；口径统一（M8）：多端对同一语义的判定口径与清理白名单必须一致（先例 D016） |

### Step 2 台账的四条硬约束（mtt R12 修订版，适配本仓）

1. **注释不是台账**。代码注释只允许写证据指针；结论性数字必须有代码/契约落点。
2. **资产与凭据必须有中位键**。同一 web 资产/常量允许各端不同名，但必须登记映射与校验值
   （md5 / 逐字节对拍）；只登记 URL 或"参照 web"不算身份。
3. **权威必须可判**。web 本地检出可能过期（`web.repo` 只读、HEAD 滞后）——基线一律显式
   钉 commit（`sync_state.py --commit`），并记录 `source_head`/`head_lagging` 供审计。
4. **可见门/条件分支并排**。web 的多条件门必须逐项映射到各端（等价改名写映射），只转录
   一项而注释抄全条件 = 不合格。

## 3 收敛定义（替换 findings=0）

```
coverage = 有契约（file-map/状态机/逻辑金标其一）且对应门通过的变更项数（按平台加权）
         / 规则引擎或人工判定的相关变更项总数
cleared  ⇔ coverage = 100% ∧ 全门 pass ∧ regressed = 0
```

- `findings = 0` 只是"本轮清单清空"，**不作为通过条件**（零集自指：mtt R8 的 `cleared`
  九项全 verified，但八项从未进入清单）。
- 报告必须同时给出"清单覆盖了哪些维度、哪些维度本轮没看"；未覆盖项单列并写复验条件
  （`NOT_FOUND` / 环境不可达 / 无采证通道），**不得补成通过**。

## 4 能力矩阵（web 构造 → 三端对应）

| web 构造 | iOS | Android | 小程序 | 缺能力时的处置 |
|---|---|---|---|---|
| `aes-256-gcm`（node crypto） | Keychain + 系统 KDF | Keystore + 系统 KDF | **无 AES/webcrypto 原语** → 纯 JS EtM（D015） | 登记 `platform` 差异并写明安全边界，禁止伪装成一致 |
| 密钥来源降级链 | Keychain 唯一来源 | Keystore 唯一来源 | 本地密钥 + 来源钉住（D014） | 移动端无来源切换的物理前提：钉住条件恒成立，不引入死代码状态机 |
| 失败语义（错误码族） | 枚举四分 | sealed 类分层 | `OUTCOME_*` 常量 | 形状可以不同，**判定口径与清理白名单必须一致**（D016） |
| hover 行内操作 | 无 hover → 长按菜单 | 同左 | `wx.showActionSheet`（D006） | 触屏语义登记 `platform` 差异 |
| `history.pushState` 返回 | NavigationStack push | back dispatcher | `navigateBack`（D002/D005） | 浏览器历史无原生对应，映射系统返回 |
| `dvh` 视口高度 | 安全区 inset | WindowInsets | safe-area（D003） | 无地址栏收放，登记即可 |

缺能力时的统一处置：**先登记（差异标记 + 台账，两级缺一不可），再实现；禁止只写注释、
只改一端、或静默近似**。小程序专项差异（rpx/分包/页面栈）见 `platform-differences.md` §5。

## 5 机械检查目录

工具：`python3 <skill>/scripts/methodology_checks.py <子命令>`（只读业务代码与台账，
不改任何业务文件）。注册表即本仓真实数据：`docs/parity-sync-state.json`、
`docs/parity-diff-ledger.md`、`config/page-index.json`、`docs/PARITY_BACKLOG.md`。
自检：`methodology_checks.py selftest`（临时树正/负控）。

| 子命令 | 对应 ML | 判据 |
|---|---|---|
| `baseline` | ML-1 | `parity-sync-state.json`：last_synced commit 在 web 源仓可达；审计字段（`commit_source`/`source_head`/`head_lagging`）在位（缺 → REVIEW，历史记录）；`in_progress` 残留 >24h = FAIL（脱管）；gates 有非 pass 项 = FAIL；`logic=skipped` 无 `skip_reason` = FAIL |
| `state-ledger` | ML-3 | 状态文件 `last_synced.commit` ↔ 差异台账 `<ledger_key>` 基线行一致（镜像脱钩 = FAIL）；`上次同步范围` 行在位 |
| `diff-ledger` | ML-7 | 代码 `APP-DIFF[D###]` 标记 ↔ 台账行双向对账：代码有标记台账缺 = FAIL（未登记）；台账「生效」行代码无标记 = FAIL（脱钩）；kind 不在 `diff_language.kinds` = FAIL；kind=pending 无 BACKLOG 编号 = FAIL；作废行残留代码标记 = REVIEW |
| `page-index` | ML-7 | page-index JSON：可解析；`pages[].key` 全局唯一且 `^\d{3}-` 格式；每页有非空 route 与 app 映射；未覆盖配置平台 = REVIEW |
| `backlog` | — | `PARITY_BACKLOG.md`：P-### 编号唯一；状态词表（待修/修复中/已修/已完成）；可解析行的证据列非空；跨行单元格 REVIEW |
| `selftest` | M10 | 临时树构造正/负控，验证上述全部检测逻辑（门自身先自证） |

**执行点**：轮次报告 / PR 描述必须贴出上述命令的 `VERDICT` 行与计数；没有命令输出的
方法论变更记 `partial`（`--strict` 时 REVIEW 也 exit 1）。

## 6 方法论变更协议（最高优先级）

用户指令（移植自 mtt-web-sync 2026-11-14 协议）：**发现偏差若属方法论问题，必须更新 skill
并持续调整；这是解决根本问题/共性问题的路径。** 落地五步（同轮完成，不推迟到下一轮）：

1. **归因**：内容缺陷（单元素）vs 方法论缺陷（一类元素/重复失效模式）。
   判据：同类问题是否可能再发生在别的元素/页面上？会 → 方法论。
2. **定位机制**：对照 §1 的 M1–M10，确定是哪一类或哪几类。
3. **改 skill**：同轮更新本文件与相关 reference/SKILL.md，写清规则、判据与反例。
4. **上机械检查**：同一轮加一个可执行检查。没有机械检查的变更记 `partial`，不算关闭。
   本仓落点固定为 `methodology_checks.py` 的一个子命令（§5）；新增一类缺陷时**先加注册表
   数据，再加子命令**，不要只写散文。
5. **记账**：`docs/methodology-ledger.md` 追加 ML-N（症状/机制/skill 变更/检查/执行点）。

**升级规则**：同一类缺陷在更新后再次出现 → 说明更新不足，必须升级为「能力/门禁」变更
（补工具子命令、改契约结构、加工作流门禁），不是再写一条散文。

## 6.5 门自身的七条验收（M10）

任何门（采证稳定门/对拍门/基线门/落地通道）在接入流程前必须自证：

1. **正控**：已知正确的输入 → PASS（证据文件齐、非空、时效新）。
2. **负控**：已知错误的输入 → 必须 FAIL，且给可读原因；**不许只看退出码**。
3. **落点校验**：采证门 `ready=true` 必须验证**实际落点**（页面 key/内容锚点），
   不能只比对 requested 路由；请求串归一化一致 ≠ 到达该页。
4. **失败清理**：门失败时删除/隔离该 key 的旧最终帧与 sidecar，禁止"旧帧 + 新 ready=false"
   混合证据集。
5. **可执行性**：脚本必须在目标机 shell 跑通（macOS bash 3.2 + `set -u`；`--only` 与全量
   两条路径都要自测）。
6. **构建身份**：采证帧的 `buildMd5` 必须等于本轮构建产物；不等即旧运行体，帧只能当
   回归基线（本仓 iOS/Android 构建门各自记录产物标识）。
7. **状态 seam 专属**：验收缝值必须是该状态专有的触发（深链参数/启动参数/状态位）；
   页面级采证只证明"页面能到"，不证明"状态出现"（本仓：page-index 的 app 缝值 +
   `interaction-and-motion.md` 触发优先级）。

缺任一条 → 该门记 `partial`，不得作为验收 oracle。

## 7 与现有流程的关系

- 九步同步流水线（`sync-workflow.md`）不变；本文件是"从源码到实现"的归因与收敛主干。
- 每轮报告必须同时给出 **coverage**（§3）与 ML-N 编号，不再只给 findings 表。
- 修复批必须附带回归证据（实拍成对 diff / 对拍账本），否则视为未完成。
- 与 `docs/methodology-ledger.md` 的关系：本文件定义机制与协议，台账记录每次变更的实证。

## 8 反例库（本仓实证，写入规则时对照）

| # | 反例 | 机制 | 现在的检查 |
|---|---|---|---|
| 1 | 源仓本地 HEAD 滞后（`a9fe401`）被记成同步基线（实际目标 `fec9759`），下次运行重复同步旧区间 | M7 权威漂移 | `baseline` 审计字段 + `sync_state.py reconcile` 向前自校正（ML-1） |
| 2 | 续跑识别用 `git log --grep --invert-grep` 判重，多 `--grep` 下 invert 是 OR 语义，续跑被误判成「重复」提前退出 | M10 | 纯区间提交计数状态机（ML-2，手册 §3「不要退回 grep 方案」） |
| 3 | 工作流收尾白名单漏 `parity-diff-ledger.md`，残留脏文件挡住下次准入 | M10 | `WORKFLOW_TRACKED_WRITES` 双文件 + `state-ledger`（ML-3） |
| 4 | 工作流 bash 非登录 shell 无 node，小程序门禁假失败被误读成代码问题 | M10 | 多级 node 回退 + `exit 127` 区分环境缺陷与代码缺陷（ML-4） |
| 5 | doubao 判「三端无落点仅文档登记」，deepseek-flash 找到 P-031 三个真实落点——「没有发现」≠「不存在」 | M6 | 重要分析结论交叉复核 + 报告 agent 自检（ML-5，partial） |
| 6 | `git merge --ff-only` 在本地 base 领先 origin 时以 "Already up to date" 通过，落地推送把漂移提交一起带上远程 | M10 | Phase 7.5 base 与 origin/base 逐字节守卫（ML-6） |
| 7 | 台账 D001–D009 九行「生效」，全仓代码 APP-DIFF 标记仅命中 D014–D017——两级缺一不可被打破 | M7 台账脱钩 | `diff-ledger` 双向对账（ML-7，2026-11-20 机检首发发现） |
| 8 | 小程序无 AES 原语，若静默用 `Math.random` 拼加密 = 确定性加密（原 `randomBytes` 异步误用曾静默产出全零密钥） | M5 | 正确先例：D015 登记安全边界 + 异步 CSPRNG 池同步消费 + 耗尽即拒绝 |

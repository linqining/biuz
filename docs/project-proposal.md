# BiuZ 移动端项目立项报告（对接 ZCode 社区版桌面端）

> 版本 v1.6 · 2026-10-04 · 面向立项评审
> v1.6 修订：**M2 迭代「逐功能自测验收」完成后的迭代更新**——①**新增 §10「M2：逐功能自测验收」**：阶段材料经**桌面功能对照（23 项：must 3/nice 14/skip 6）**认定 v1.3–v1.5 的连接态只读边界存在**过度限制**（「客户端发命令、桌面执行」的合法面被整体禁用——sendText/resolveInteraction/stop/队列/带 firstInput 的 createSession 全拦、六动作方法空实现、五处 UI 只读降级）；本轮完成 **ReadOnlyGate 边界口径修订**（三分类→readOnly/session/command/directWrite 四分类，v4 信封仅 applyFileRewind 属直写拦截，file 频道改「默认拒绝+读白名单」，git/terminal/设置同步/凭据等写族黑名单全部保留）并恢复**连接态发送（sendText 信封+乐观回显）、审批与提问应答（resolveInteraction 信封+结构化审批卡+三档 scope）、停止任务（v4 stop）、新建会话带首条指令**四类交互；补齐**会话重命名/标记未读（长按菜单）、已归档分组与取消归档（修正 setArchived(false) 误发 archiveTask）、本地通知真实化**（NotificationService+UNUserNotificationCenter，三类内容映射）与 P2 四项（任务服务端搜索、会话内搜索、产物行结构化、图片/PDF 只读预览）；②**验收清单 24 项全部 pass（阶段/验收会话口径）+ 构建通过=true + e2e 通过=true**，按材料口径声明全流程通过（**v1.6 二次修订注：该声明已降级为逐级如实表述——汇总不得强于项级证据，见下方二次修订②与 §10.6/§10.7**）；**报告撰写会话实跑单元测试 40/40 通过**（`xcodebuild test -only-testing:ZCodeMobileTests` → TEST SUCCEEDED：ReadOnlyGateTests 16 重写后全量+RelayLinkTests 18+TaskNotificationContentTests 6 新增）——**v1.5 遗留的「单测待补跑」闭环、v1.5 的 PTY 环境阻塞实测已消除**（M1.z e2e 补跑环境前提就绪但仍未执行）；③竞品交互参考（Qoder/Trae 19 条）按已落地/部分落地/后续候选/不适用四级归档（§10.3），无落盘截图口径不变；④测试面 62→78 用例（实测），**孤儿 UI 测试文件扩大**（新增 testSettingsNotificationsTogglePersist 加入仍不参与编译的 ZCodeMobileUITests.swift，风险 3 恶化并如实升级，§10.8-2）；⑤§12 风险更新：风险 17「连接态能力收窄」已由本轮纠偏反转（改记残余）、风险 3 重写、新增风险 22「放行命令在真实桌面的 payload 契约未对照」，风险 1/2/8/18 更新；⑥§14 里程碑 M1 行补 M2 前置事实、M2 行通知口径更新、§14.2 联调项补放行命令契约对照（⑩）；⑦§4.3/§4.4/§5 更新（App 源码 47→48、a11y 156→167、ReadOnlyGate 246→265 行）；⑧全文对外品牌表述保持 BiuZ。**编号变更**：自 v1.6 起，原 §10–§13 顺延为 §11–§14，M2 迭代章节为新增 §10；v1.6 之前修订记录与附 B–附 H 对照表中的章节号均为彼时编号（附 I 为 v1.6 对照、使用 v1.6 编号）。
> v1.6 二次修订（独立审阅意见 14 条逐条处置，对照见附 I 补表；13 条属实更正、1 条为与既有披露一致的核实确认）：①**【最严重】M1.z 验收证据全部失效**——审阅实测 /tmp 14 条路径全部 MISSING（`for p in /tmp/relay-log-stream.txt /tmp/relay-probe.html /tmp/relay-verify-*.png /tmp/relay_swift_verify /tmp/relay-js /tmp/login_design_check.png /tmp/zcode-api-research /tmp/zcode-api-readonly /tmp/zcode-api-client /tmp/zcode-research; do … done` → 14×MISSING，本报告撰写会话复跑同结果），撰写会话已不存在：/tmp/relay-* 日志与截图 8 张、probe.html、relay_swift_verify、relay-js（57 bundle+7 探针）与三个调研克隆件（zcode-api-research/readonly/client）；正文 §9.3「全部在位」、§11.6 M1.z 条目③、附 A 两行的「在位」结论已全部改写为失效状态（原始数值降级为 v1.5 彼时抽查值、仅存文字），**M1.z 真实链路验收与 392 项对照的克隆件抽查自此无可复核凭据**（§12 风险 1/2/6/20/21 更新；/tmp 清理事实上达成了风险 21 的「评审后清理项」，但协议复验能力一并丢失）；②**M2 汇总门禁口径降级（审阅 #2）**——「验收清单 24 项全部 pass、e2e 通过=true」强于项级证据：第 1 项材料自注验收会话未运行、第 14/15 项断言载体为孤儿文件不可执行、e2e 证据截图无任何落盘路径；§0/§10.1/§10.6/§10.7/§14.1 汇总句全部改为逐级如实表述，**不再以单一「全流程通过」对外声明**（改为「材料判定全 pass+本会话单测实跑 40/40+载体与运行限制如实列明」）；③**行号勘误体例第三次沿用（审阅 #3 全部属实，逐值实跑复测）**——M2 对 7 文件加厚致 v1.5「现网」值再失效：ZCodeServerConnection call 出口 :786→**:795**、拦截 append :794-795→**:803**、抛错 :797→**:806**；AppSession -ZCodeE2EResetState :130/:134→:137/:141、refreshDesktopReadonlyInfo :225→:232、fetchDesktopOAuthInfo :237→:244、fetchCodingPlanUsage :275→:282；StoreProtocols.isReadOnly :45/:91-92/:114→:55/:129/:153；05-questioncard :294→:277；mock 标题 :276/:282→:288/:294；04-row-sess-e2e-1 :406/:458-463→:413/:470；设计稿 Tab 标注 html:597-600→:613-616 与 :842-845——§7.2 增设「勘误·续二」，§3.1/§3.3/§5/§6.7/§7.2 纵深防御/§7.4/§8.3/§9.4/§10.4/§11.6 逐处更正（§10.4 同段自相矛盾一并修正）；④**relay-handoff.md 27→29 行（审阅 #4）**——v1.5 二次修订凭据掩码整改（:7 新增掩码注记）后未复测，§9 引言/附 A/§11.6 三处更正；⑤**验收表覆盖缺口补录（审阅 #5）**——§10.6 表后新增补录 3 行（新建会话带首条指令=有编译内断言 test06 :438-441、会话内消息搜索与图片/PDF 预览=pass(代码核对)·**无任何测试断言**如实降级，`grep -rn '05-act-search\|binaryPreview' ios/Tests/ ios/UnitTests/` 仅替身/白名单命中）；§10.5 表头 P 计数口径更正（12 项=阶段口径 P0×3/P1×5/P2×4，表内按依赖序呈现 4/4/4，口径修订地基项阶段优先级为 P1）；⑥**§12 风险 20 探针「6 个」漏改更正（审阅 #6）**——v1.5 二次修订已全文更正为 7 个、此处漏网，且该 /tmp/relay-js 现已不存在，风险 20 缓解① 重写；⑦**§1 未随 v1.6 纠偏更新（审阅 #7）**——§1.2「M1.x 收敛为连接态只读远控」与 §1.3「批」条目补 v1.6 注（M2 已纠偏反转），消除与 §10 的首读矛盾；⑧**「M1.z 24 用例」笔误更正（审阅 #8）**——§14.1 状态格改为实跑口径（v1.6 撰写会话 40=Gate 16+RelayLink 18+Notify 6；M1.z 面为 RelayLinkTests 18）；⑨**blockedWriteCommandCount 断言计数 20→19（审阅 #9）**——grep 20 处含 :1254 注释行 1 处，以断言计 19，§10.6-23/§11.6⑦ 更正；⑩**§11.1「见 10.6」编号残留更正（审阅 #10）**→「见 §11.6」；⑪**§10.6 第 3 项标题分组错位更正（审阅 #11）**——「云中继：局域网配对五步连接」改为「连接（局域网直连）：配对五步连接」并括注阶段 checklist 归域与更正理由；⑫**§9.5 括注 xcresult 指针失效更新（审阅 #12）**——DerivedData 现仅存 11-05-25 与 11-43-31 两枚（`ls` 实测），M1.z 失败运行留痕 03-21-42 已不存在；11-43-31 与 v1.6 单测 40/40 时间戳吻合、该佐证成立；⑬**外部链接状态时效更新（审阅 #13）**——`curl -sI -m 6` 复测 pixso HTTP 200、github HTTP 200（彼时「github 000」为沙箱时点结论；「内容未人工核验」边界维持），§12 风险 10 更新；⑭审阅 #14（凭据留存核实）与既有 §12 风险 21 披露一致、属未整改状态确认而非新发现，补记 /tmp 含凭据 8 文件已随清理消失。审阅第 15 条核对通过项（40 工作区路径/48 源文件/a11y 167/78 用例分布/各行数/testid/md5/test16-18 行号/M2 落点锚点/只读锚点 0 残留/两副本 MD5 一致等）与本报告第七轮实测一致，留痕备查。**全文事实口径以本次二次修订后的现网值为准。**
> v1.5 二次修订（独立审阅意见 12 条逐条处置，对照见附 H 补表；11 条属实更正、1 条为核对范围说明）：①**【最严重】凭据留存实况更正与整改**——§11 风险 21 原「文档与交接材料不留存工作区」缓解声明与事实不符：docs/relay-handoff.md 曾以完整明文留存配对链接（sid/hash），审阅复核属实且经 `grep -rln` 全量扫描，凭据实际留存位置为工作区 relay-handoff.md + ios/ 源码 2 处（RelayLink.swift:90 注释、RelayLinkTests.swift:11-22/:62 proof 单测向量）+ /tmp 8 文件（7 探针脚本 + relay_swift_verify/main.swift）；**已整改：relay-handoff.md 链接掩码（sid/hash 中段掩码+注记留痕）**，源码向量替换（需 build 回归）列入 M1 剩余研发第一项安全整改、/tmp 列评审后清理项，风险 21 按实况重写；②**门禁口径矛盾更正**——§13.2-1「M1.z 单测已复核」系笔误，与 §9.5 括注/§10.1/§11 风险 1 的「未能运行（PTY 环境阻塞）」直接冲突，已统一为「未能运行、待补跑」唯一口径；③**行号勘误体例沿用（v1.4 二次修订 #8 同款）**——M1.z 加厚（ZCodeServerConnection 654→807/RemoteConversationStore 926→944/AppSession 368→410 行）致 §7.2/§7.4/§8.3 的「现网」值再度失效（call 拦截现网 :786-797 而非 :638-643、blockedExecutionCalls :257 而非 :254 等），§7.2 勘误注扩容「勘误·续」段（20+ 组 v1.4 时点→v1.5 现网对照，逐值实跑复测），§7.1/§7.4/§7.2 纵深防御/§8.3/§8.4/§6.7 逐处更正——含 §7.2 isReadOnly/ChatView 分支/LoginFlowView/ConnectFlowView/替身 302 等锚点现网值（审阅 #3/#4 全部属实）；④**M1.z 逆向引用错挂更正（审阅 #5）**——HMAC proof 函数 lVn 实在 index-BO-TaBle.js@~6144121（阶段材料错挂 src-D3H6NV7w.js@336279，该处实为 wN 内 passHash 字段、lVn 在 src bundle 零命中），wN 实为 @336142–336400 区、Mh 首现 @181160（原记 181256 为函数内字面量位）；算法结论不受影响，其余偏移（xh/Ju/Yv/cA/TN 等）经审阅抽查属实保留；⑤**§9.1 常量映射与分片描述更正（审阅 #6/#7）**——AN/jN/MN/NN 四常量对五语义的错位拆明（AN=心跳 10s/jN=抖动 2s/MN=看门狗 30s/NN=2s 未定位），「stale 1.5s」「离线宽限 15s」降级为材料转述（bundle 未定位 15e3，实现按材料值 15s 并如实存疑）；分片自适应描述按实现（RelayFrameCodec.swift:41-47）更正为「片长初值=整条消息，片数>64 或单片信封>1MB 则减半」（原描述方向相反）；⑥**探针脚本 6→7 个（审阅 #8）**——`ls /tmp/relay-js/*.py` 实测含 ws_bridge_agent_probe.py（产出会话未在阶段材料标注、mtime 22:43 归属未溯源），§9 引言/附 A/§10.6⑥ 三处更正；⑦**附 A 断链与 §9.2 指位更正（审阅 #9/#10）**——「§3.4 真机验证入口」系误指（本报告无 §3.4、relay-handoff.md 亦无该小节），更正为该文件「实施建议」节第 4 条；§9.6-③→§9.6-2；⑧**§6.5 历史字节数加时点注（审阅 #11）**——login-design.html 164,385 字节为 M1 时点值，现网实测 178,820 字节；⑨审阅 #12（核对范围说明，非缺陷）所列通过项与本轮复核一致。**全文事实口径以本次二次修订后的「现网」值为准。**
> v1.5 修订：**M1.z 迭代「云中继接入（remote/v4）」完成后的迭代更新**——①新增 §9「M1.z：云中继接入（remote/v4）」：基于官方网页壳与 **57 个前端 JS bundle 的字节偏移逆向 + Python 探针实测**（协议无开源参考，结论含 wsEndpoint `wss://zcode.z.ai/ws?mid=…`、auth 四帧 HMAC-SHA256 握手、data 帧编排 bootstrap/workspace-list/bridge-open/ready、rpc-frame 分片可靠层——无 13 字节 SocketProtocol 头/JSON 文本帧/seq+messageSeq 双序号/crc32 同参 V4Wire/replay 缓冲 8MB/45s/饱和水位），实现落地 **Services/Relay/ 4 个新文件**（RelayLink 105/RelayFrameCodec 244/RelayChannelClient 444/RelayTransport 802 行）+ 6 处既有改造（App 源码 43→47），ZCodeServerConnection 协议化为 RPCChannelTransport 门面——**ReadOnlyGate 出口拦截对中继路径同等生效，零新增业务命令**；②**真实链路端到端验收通过（connected=true / sessionsLoaded=true）**：真实配对链接一次连通真实桌面端（auth matched→bootstrap 240 tasks→bridge-ready kind=local→桥内 v4 握手 protocolVersion=3→sessions-index 订阅→真实会话渲染，连接保持 44 分钟+，**0 轮代码修复**），连接日志全文 65,805 字节与逐秒截图留档 /tmp（§9.3，工作区外如实标注，撰写会话抽查复核一致）；③门禁：构建=true（双执行主体）、**e2e=false 如实标注**（宿主机 PTY 泄漏内核层致测试 runner 无法启动；撰写会话实跑单测同因未能运行、独立复现该环境阻塞，§9.5 括注）；测试面 5→7 文件、40→62 用例（RelayLinkE2ETests 4 + RelayLinkTests 18）；④§11 风险更新：新增「中继协议为闭源逆向、可能随版本变化」（风险 20）与「配对凭据 sid/hash 时效与泄露面」（风险 21）两条，风险 2 更新（中继路径真实桌面数据面已验收，局域网直连与 OAuth 授权页仍未验证），风险 1 扩为五轮口径+第三类证据；⑤§12 决策信息④更新（云端中继已可用、后端依赖由自建 relay 降为托管端点依赖）；⑥§13 里程碑 M1 行补 M1.z 前置事实、§13.2 联调项补中继面五项；⑦全文对外品牌表述保持 BiuZ（中继服务域名 zcode.z.ai 与协议面标识按 works-with/功能性引用口径如实指称）。**编号变更**：自 v1.5 起，原 §9–§12 顺延为 §10–§13；v1.5 之前修订记录与附 B–附 G 对照表中的章节号均为彼时编号。
> v1.4 修订：**M1.y 迭代「按 packages/client 参考完成移动端接口对接」完成后的迭代更新**——①新增 §8「M1.y：按 packages/client 参考完成移动端接口对接」：对上游开源仓库 RemoteServiceAccess 暴露的 **40 个服务 / 392 个方法条目**（readonly 199 / session 148 / execution 45，合并计数口径）逐一对照，处置五类统计 **exists 11 / partial 4 / integrate 32 / skip-boundary 122 / defer 223**；32 项 integrate 落地（服务端文件搜索、会话向上分页、丢帧 resync 自愈、任务推送活性、置顶/已读/归档双写、配置/模型/用量只读展示、Diff 三分段、file-watcher 活性、桌面登录与额度只读卡、draft 转正等），约 30 项 skip-boundary 的「无入口」升级为「出口必拦」（ReadOnlyGate 116→246 行，拦截黑名单扩至 201 命令名 + 2 双态，本轮新增 177 命令名）；②§8.6 与 v1.3 只读边界章节自洽（skip-boundary 与拦截表关系、两轮独立调研交叉印证）；③§4/§5 更新技术方案与已实现范围（**测试 4→5 文件、27→40 用例**，新增 ZCodeMobileTests 单测 target 与 ReadOnlyGateTests；连接态 RPC 出口 15 call+3 listen→**40 call+7 listen** 全落 readonly/session 两类）；④§10 风险更新：新增「新增只读面的 schema 宽容解析未真机联测」与「对照计划 gaps 中未逐行核实的分类采保守处置」两条，并修正 M1 gap 8（置顶/归档写面**存在**于 zcode-task 频道，v1.2「缺失」判断已修正并落地双写）；⑤§12 里程碑 M1 行补 M1.y 前置事实；⑥全文对外品牌表述保持 BiuZ（上游开源项目/协议面指称按 naming.md works-with 口径保留）。**编号变更**：自 v1.4 起，原 §8–§11 顺延为 §9–§12；v1.4 之前修订记录与附录 E/F 对照表中的章节号均为彼时编号。
> v1.4 二次修订（独立审阅意见 11 条逐条处置，对照见附 G 补表）：①**删除 §8.7 末尾误入正文的 3 行 shell 残片**（撰写会话终端命令残片 `__zcode_status=…` 等，恢复 §8.7→§9 行文）；②**§8.2④ 口径拆分**——表内容为拦截黑名单命令名集合（含双态 203），非 skip-boundary 122 条目的枚举，两计数口径不可混用（表题下加声明，信封词表 17 归属 partial 接入的 sendConversationCommandV4）；③**更正「新增 201」**为「本轮新增 177 命令名」（24 命令名 + 2 双态为 v1.3 既有），约 30（计划建议条目）/177（实际新增命令名）/201+2（总量）三数字口径在 §8.6-2 集中区辨；④**§8.1 加「分类口径声明」**——表内 R/S/E 为参考盘点语义口径（git 写族归 S），产品拦截口径（§7.2）更严且 git 8 命令全拦，判定一律以拦截口径为准，gitService 行加 \* 注；⑤**证据链表述更正**——对照计划材料系随任务材料提供至撰写会话（实现会话不可读取，两处表述主体不同），复算范围如实限定（统计自洽 + exists/partial/integrate 逐项 + 暴露面实跑比对一致；**skip-boundary 122/defer 223 未逐条独立复核**），附 A 标注处置表未落盘、不可独立复核；⑥**§9.1 编号漏改更正**（「下述 8.2/8.3/8.4」→§9.2/§9.3/§9.4）并补单元测试例外；⑦**§9.6 加五轮与条目标签对应注**（第一/二轮=克隆抽查、均 v1.2 会话；第三轮=v1.3 会话；末条=v1.4；v1.0/v1.1 未设独立标签）；⑧**行号漂移勘误**——M1.y 加厚实现致 v1.3 时点行号失效：RemoteConversationStore 订阅参数 :111-117→:132-137、:191-196→:276-280、unsubscribe :136-140→:208、空态注释 :127-128→:154、停用位 :482-493→:633-644/:545-549→:741/:495-503→:646，RemoteTaskStore :114-134→:164-183，clientId :460-474→:608-612，ReadOnlyGate :66-67→:183-185，ZCodeServerConnection :514-531→:638-643/:246→:254，UI 锚点 4 处——§7.1/§7.2/§7.4/§7.8 逐处更正并设统一勘误注，附 F#1 补后记；⑨**合并族成员勘误**（对照克隆件实测）——mcpSync 读族 3→4 方法、coding-plan 企业族 6→8 方法（盘点遗漏 3 成员：checkRemoteUserMcpWriteAccess/getEnterprisePendingOrders/checkEnterpriseOrderStatus，均只读、归 defer 语义），§8.1 加条目口径与成员遗漏总注；⑩**gaps 清单移除混入的事实项**（原⑦盘点结论，已在 §8.1 陈述），6 条；⑪**「已运行检查」两套执行主体口径区分**（§7.6 的 3 项=迭代会话执行 vs M1.y 单元测试=撰写会话执行），§9.1/§10 风险 1 同步，两类均无凭据留存、不构成复核豁免。
> v1.3 修订：**M1.x 迭代「品牌迁移（BiuZ）与移动端只读边界」完成后的迭代更新**——①新增 §7「M1.x：品牌迁移（BiuZ）与移动端只读边界」：品牌替换范围与保留项（内部符号层不动；上游开源项目仍为 ZCode，协议面标识如实保留以维持兼容，产品身份用 works-with 描述性表述）、只读边界代码证据分类表（readonly/session/execution 三类，含 file:line 证据）、边界复核纠错 3 条、实现清单、门禁结果（构建通过=true、e2e通过=true，采信边界如实声明）；②新增 §7.6「全面验收清单」（21 项全部 pass，pass 分级如实标注）与 §7.7「布局走查」（8 条走查用例、走查发现并处置的遮挡问题、回归断言）；③§4/§5/§6 更新技术方案与已实现范围（新增 ReadOnlyGate 纵深防御与 Assets.xcassets 图标落位；App 源码 42→43 个、测试 3→4 个文件共 27 用例；v1.2 所列 test06 唯一降级断言已升级为强断言）；④§9（原 §8）风险更新：新增「bundle id 迁移的 Keychain 旧凭据失效」与「连接态能力收窄的产品影响」两条；⑤§11（原 §10）后续路线保持既有顺序不变（门禁复核→端到端联调→真机→Android→上架），上架合规因品牌迁移已改善（BiuZ 定名/图标/works-with 元数据口径就位；商标检索仍为上架前人工步骤）；⑥全文对外品牌表述统一 BiuZ（指 ZCode 桌面端产品的调研事实与协议兼容表述按 naming.md works-with 口径保留）。**编号变更**：自 v1.3 起，原 §7–§10 顺延为 §8–§11；v1.3 之前修订记录中的章节号均为彼时编号。
> v1.3 二次修订（独立审阅意见 10 条逐条处置，对照见附 F）：①**更正「订阅参数只传 topic」错误结论**——实测现网订阅即传全参数（RemoteConversationStore.swift:111-117 topic+workspacePath / :191-196 topic+sessionId，全源码仅 4 处订阅调用点），连带 §7.2 gaps⑦、§7.4、§7.8-2、§9 风险 8、§11.2-2③ 更正；②**补测两份设计 HTML**：主稿 1991 行、登录稿 1949 行（首版沿用 v1.2 旧值 1975/1869 未复测），testid 123/102/95 复测吻合，05-ask-chip 行号更正为 :946-947；③**修正「实测」误引行号**：KeychainStore.swift:15→:16、zcodeJwtToken :90-91→:22/:215、MessageViews.swift:276→:294；④a11y 计数 139→**149**（M1.x 新增 10 处锚点，§4.3 更新）；⑤「绑定假设 9.7.7」标注为 **design-spec 条款号**（区别于 §6.8 ①–⑧ 编号）；⑥§7.6 补两套测试 test06/test07 **归属说明**并统一替身计数器口径 :77-104；⑦§7.6 标题即标 **pass 分级（18 项编译级 + 3 项已运行检查）**；⑧孤儿冒烟用例收编补入 §11.1 M1 行落点（③ 括注的风险归属同步修正为仅风险 4）；⑨「docs/ 无 cn.zcode 残留」改述为「**无作为现值的 cn.zcode**」（grep 16 行均为迁移历史描述）；⑩风险 10 补 curl 复测时效（pixso HTTP 200 / github 000 / docs.qoder.com 307）。
> v1.2 修订：**M1 迭代一「登录与 API 接入」完成后的迭代更新**——①新增 §6「M1 迭代：登录与 API 接入」：两条登录路径（Z.ai 账号 OAuth + 桌面配对，均附 file:line 证据）、接口调研结论（含 10 条 gaps）、登录设计（spec v2.4 第 9 章 + 专项稿 18 屏）、Pixso 同步结果（pushed=false）、实现范围、门禁结果（阶段自报）、8 条绑定假设与待后端确认项、应用内授权的安全取舍；②**修正早期「仓库无账号体系」结论**：Z.ai OAuth 账号登录存在于 packages/web（§6.1）；③更新 §3 设计基准（spec v2.3→v2.4、新增登录专项稿）、§4 技术方案（新增 §4.4 传输层与登录架构）、§5 已实现范围（25→42 个源文件）；④§10 后续路线更新为：端到端联调（真实桌面 Host + chat.z.ai 授权页，含应用内 WebView 兼容性人工验证）→ 真机签名 → Android 评估 → 上架计划；⑤本报告撰写时对工作区实测 6 项、对接口调研克隆件（/tmp/zcode-api-research，v3.14.3）抽查 13 处行级证据（§7.6）。
> v1.2 二次修订（独立审阅意见 9 条逐条处置，对照见附 D）：①test06 判定口径按代码实况精确化（数据面强断言 + UI 断言混合，唯一降级断言为「替身行∨远端空态」，§6.7 如实声明③）；②自证抽查数字更正：首轮 13 处（按文件归并 10 组）+ 二次修订增补 9 处、另 2 处未能定位（§7.6）；③立项申请范围统一为「M1 剩余迭代–M3」，M1 迭代一为前置事实报请追认（§0/§10.1）；④顺序唯一化：门禁凭据复核为评审/开工前置，端到端联调为 M1 剩余研发第一项技术工作（§10.2）；⑤修正可证伪数字与行号漂移（TUI 86→实测 95 个文件/61 个 .ts；ZCodeMobileE2ETests.swift:11→:9；design-spec.md:314→:315、88/86→89/87；zcode-cua/README.md 在 v3.14.3 不存在）；⑥§9 增补「团队/人力在位性」与「数据合规与 App Store 隐私」两项决策要素；⑦§6.3 端点计数改为「HTTP/WS 端点 8 个 + RPC 频道面」；⑧外部链接维持未核验声明并补记评审侧复核亦不可达。
> v1.1 修订：按独立审阅意见全面修订——①明确定位：本报告为**立项申请报告**，申请对象是 M1–M3 正式研发，M0 仅为立项前预研验证；②如实降级门禁表述（工作区内无凭据可复核）；③更正与代码不符的协议名/AppStore 门面/Swift 文件数/Tab 骨架与命名/屏 08 统计/选择器转述；④新增「立项决策所需信息」一节，如实列明成本、排期、指标等评审必需要素当前空缺。
> 事实来源：六个阶段材料（功能调研/设计参考/设计稿/设计基准核对/iOS 原生实现/测试计划）+ M1 迭代材料（接口调研/OAuth 机制核实/登录设计/Pixso 同步/M1 实现/登录测试计划）+ M1.x 迭代材料（品牌盘点/只读边界协议调研/边界复核/只读改造实现/测试计划）+ M1.y 迭代材料（packages/client 参考客户端盘点/iOS 现状盘点/逐接口对照计划/M1.y 实现与测试计划，§8）+ **M1.z 迭代材料（中继协议规格——网页壳与 57 个 bundle 逆向及探针实测记录/M1.z 实现与真实验收记录/中继测试计划，§9；探针脚本与 bundle 留存 /tmp/relay-js/，交接材料 docs/relay-handoff.md）** + **M2 迭代材料（桌面功能对照 23 项/竞品交互参考 19 条/验收清单与补全计划（checklist 24 项+gaps 12 项）/补全实现记录/验收测试记录 24 项，§10）** + 本工作区七轮实测核验（v1.0 撰写、v1.1 修订、v1.2–v1.5 迭代更新、v1.6 迭代更新，命令与结果见 §11.6；v1.4 及之前记录中的 §9.6 即现 §11.6，编号顺延前旧值）。

---

## 0. 报告定位（先读）

**本报告申请的是 M1 剩余研发–M3 的正式研发立项**（见 §14，与 §14.1 同一口径）：在 M0 预研验证成果的基础上，批准投入研发资源，把 BiuZ 移动端从「mock 数据层的可编译原型」推进到「接入真实后端的可内测产品」。**M1 迭代一（登录与 API 接入）、M1.x 迭代（品牌迁移与只读边界，§7）、M1.y 迭代（按 packages/client 参考完成接口对接，§8）、M1.z 迭代（云中继接入，§9）与 M2 迭代（逐功能自测验收——过度限制纠偏与功能补全，§10）均已在本次立项批准之前执行完毕**（前置事实，性质同 M0 预研的延伸），其成果与自报门禁一并呈报、**报请评审追认**；本次批准范围不含已完成部分。§14.1 原所列「迭代二」四项整改中，test06 强断言整改已在 M1.x 兑现（§6.7 v1.3 注），其余三项（门禁脚本入库、端到端联调、设计基线冻结）仍为研发起点；孤儿冒烟用例收编（§12 风险 3）已在 §14.1 M1 行补落点（v1.3 二次修订；**v1.6 更新：风险 3 恶化**——本轮新增 UI 用例被加入仍不参与编译的孤儿文件，§10.8-2）。

- **M0（立项前预研验证）已完成**，作为可行性证据呈报于 §3–§5、§11：完成了功能调研、设计基准（规范 v2.4 + 14 屏主稿 + 登录专项稿 18 屏）与 iOS 原型编码与自检。
- **M1 迭代一（登录与 API 接入）已完成编码与自报门禁**（v1.2 新增，见 §6）：两条登录路径（Z.ai 账号 OAuth / 桌面配对）、传输层逐行移植、真实 Store、登录与连接域 UI 已落地。**M1.x 迭代（品牌迁移与只读边界）已完成编码与门禁**（v1.3 新增，见 §7）：对外品牌全面切换 BiuZ（显示名 / bundle id `cn.biuz.mobile` / App 图标 / 界面文案 / 设计稿与文档），连接态收敛为**只读远控**（execution 类命令经 UI/Store/连接层三层拦截）。**M1.y 迭代（按上游参考完成接口对接）已完成编码与门禁**（v1.4 新增，见 §8）：对上游 RemoteServiceAccess 全量 40 服务 / 392 方法条目逐一对照处置，只读面从 15 call+3 listen 扩至 **40 call+7 listen**（全部落 readonly/session 两类），历史/丢帧自愈/任务推送/双写持久化/只读数据面等 32 项缺口补齐。**M1.z 迭代（云中继接入）已完成编码、门禁与真实链路端到端验收**（v1.5 新增，见 §9）：对官方网页壳与前端 bundle 逆向 + Python 探针实测得出 remote/v4 云中继协议全程，落地 4 个 Relay 新文件 + 6 处既有改造，**真实配对链接一次连通真实桌面端（auth matched→bootstrap 240 tasks→桥内 v4 握手→sessions-index 订阅→真实会话渲染，连接保持 44 分钟+，0 轮代码修复）**——M1.y 所记「真实桌面端端到端联调未进行」在中继路径上已部分兑现（数据面经真实桌面验证），局域网直连路径与 OAuth 授权页仍未验证（§12 风险 2 更新口径）。门禁凭据复核仍为评审/开工前置、先于其余联调执行，全文以此唯一口径表述（§12 风险 1、§14.2）。**M2 迭代（逐功能自测验收）已完成编码、纠偏与逐功能验收**（v1.6 新增，见 §10）：阶段材料经桌面功能对照（23 项）认定 v1.3 只读边界存在**过度限制**——「客户端发命令、桌面执行」的合法面被整体禁用；本轮完成边界口径修订（四分类，仅拦手机直写面）并恢复连接态发送/审批应答/停止/新建会话带指令四类交互，补齐会话重命名/标记未读/已归档分组与取消归档、本地通知真实化与 P2 搜索/预览四项；**验收清单 24 项按材料判定全部 pass、构建通过=true、e2e 通过=true（阶段/验收会话口径；v1.6 二次修订注：项级证据分级与载体限制如实列明于 §10.6——含第 1 项材料自注未运行、第 14/15 项断言载体为孤儿文件不可执行、e2e 截图证据无落盘路径，汇总口径不得强于项级证据）**；报告撰写会话实跑单元测试 **40/40 通过**（v1.5 遗留单测补跑闭环、v1.5 PTY 环境阻塞消除，§10.7）。
- **必须向评审如实说明**：M0、M1 迭代一、M1.x、M1.y、M1.z 与 M2 的门禁中，构建/e2e 结果均为**阶段会话自报**，工作区内**无门禁脚本、测试产物或日志可复核**（v1.6 撰写时实测 `find` 仍零命中，详见 §11.1、§12 风险 1），门禁结果不能作为已验证事实采信；**例外与如实标注**：M1.y 的 ReadOnlyGate 单元测试 10 用例已由该轮报告撰写会话实跑通过（§8.5，pass(已运行检查)）；M1.z 的撰写会话曾因宿主机 PTY 泄漏未能运行单测（§9.5 括注），**v1.6 撰写会话实测该环境阻塞已消除并实跑单测 40/40 通过（§10.7）——M1.z 遗留的「单测待补跑」就此闭环，但 M1.z e2e 补跑仍未执行**；M1.z 另有**真实链路端到端验收**（连接日志全文 65,805 字节与逐秒截图留档 /tmp，§9.3——工作区外文件，评审复核需访问该机 /tmp）；M2 的 e2e 门禁为 true（验收会话口径，逐项证据见 §10.6），撰写会话未复跑完整 UI 套件。
- 本报告不隐匿已知缺陷：§12 逐项列明实现与设计基准的偏差、孤儿测试文件（v1.6 恶化：新增用例入孤儿文件）、协议未冻结、令牌无撤销、应用内 WebView 授权页兼容性未知、bundle id 迁移的一次性凭据失效、纠偏放行命令在真实桌面的 payload 契约未对照（v1.6 新增）、中继协议为闭源逆向可能随版本变化、配对凭据时效等问题。

## 1. 项目背景与目标

### 1.1 背景

ZCode 是桌面端 AI 编程工具，仓库定位 **desktop-first、web-compatible**（DESIGN.md:22，转引自调研材料；M1 接口调研克隆件 /tmp/zcode-api-research 在位，证据状态见 §2 开头与 §12 风险 6）。产品以「任务（Task）」为会话单位，一个任务绑定一个工作目录，用户通过对话驱动 agent 执行 bash、读写文件、Web 检索、Git 变更等工具链，完成从 0 到 1 的开发工作。

ZCode 官方的移动路径是「**手机远控桌面**」：手机侧经 web-remote-replayable 链路复用桌面 Host，不另起 agent（AGENTS.md:62-63，转引，已于 v3.14.3 克隆件复验属实；M1 接口调研已在协议层证实该链路的完整形状，见 §6.3）；另有 IM Bot 通道在微信/飞书/Lark/Telegram 中远程下达任务（WebRemoteControlDialog）。共享 UI 已预留 `isMobileViewport/isMobileActive` 适配点（v4/SessionPane.tsx:2079、ConversationComposer.tsx:827 已复验属实；TaskList.tsx:365 在 v3.14.3 克隆件中未能定位同名适配点，未复核），但当前快照中均传 `false`——**移动适配尚在铺路，尚无可用的移动客户端形态**。

竞品侧，TRAE App 与 Qoder 已验证「手机 = 遥控与审批台，重活 = 云端/PC 执行」的移动范式（Qoder 的 My Quests 看板按 Running 蓝/Waiting 橙/Completed 绿组织任务，docs.qoder.com）。这是 ZCode 当前产品矩阵的空缺。**注意**：竞品范式结论目前仅有文字材料，无落盘截图/链接证据（见 §13 决策信息 ⑤）。

### 1.2 两条移动路径的优先级（v1.2 更新：A 路线已落地 MVP）

材料与设计稿并列了两条移动路径：

- **A. 原生 App 经 web-remote-replayable 远控桌面 Host**（主路径，**M1 迭代一已按 MVP 落地；M1.x 曾收敛为连接态只读远控，v1.6 注：该口径已经 M2 迭代认定为过度限制并纠偏反转**——连接态发送/审批/停止/新建已恢复、仅拦手机直写面，见 §10.1/§10.4；本句保留 M1.x 历史口径）：iOS 直连桌面机上的 `zcode --web` 进程；协议与「Host attachment + 外部 relay」受信链路**完全同构**（同一 channel RPC + v4 协议，见 §6.3），后续接 relay 只需替换发现/鉴权层，不需要重写业务层；
- **B. IM Bot 通道**（微信/飞书/Lark/Telegram，BotsDialog）：零安装成本、触达快，但交互深度受 IM 容器限制；M1 调研另确认 Bot 绑定码体系（6 位 HEX、30s TTL、聊天内 `bind <code>`）是「聊天账号↔工作区」绑定，**不是 WS 接入凭据**，不能复用作 iOS 登录（§6.3 gaps）。

当前设计稿两通道均保留入口（屏 11 设备与配对页含 IM Bot 行）；原型实现 A 的完整形态。B 是否投入仍待评审决策（§13 决策信息 ⑦）。

### 1.3 目标

打造独立 iOS 原生客户端「BiuZ」（bundle id `cn.biuz.mobile`；对接的上游开源项目仍为 ZCode 社区版桌面端），把桌面端对话驱动的 agent 工作流映射为移动端「**任务看板 → 会话流 → 审批/Diff 审查 → 产物验收**」闭环：

- **看**：任务状态三分组看板（进行中蓝 / 待操作橙 / 已完成绿）、Agent 执行过程可视化（工具卡、todo、终端输出、模型轨迹）；
- **批**：Agent 关键操作的审批请求（批准/拒绝/追问），授权范围（仅本次/始终允许）可选；**v1.3 口径更新**：审批应答在协议面属执行类（respondPermission/resolveInteraction 收口，§7.2），连接态按只读边界由桌面端完成、移动端展示只读提示，演示态交互保留完整形态（§7.4、§12 风险 17）；**v1.6 口径更新（现行口径）**：该只读审批口径经 M2 迭代认定为过度限制并已纠偏——连接态审批/拒绝（含三档授权范围）与提问应答已恢复下发桌面执行，仅拦手机直写面（§10.1/§10.4；本条上一句为 v1.3–v1.5 历史口径，保留备查）；
- **审**：Unified Diff 逐文件审查与批准/拒绝；
- **驱**：新建任务并选择执行端（云端沙盒/配对电脑），文字 + 语音输入，@ 引用上下文。

M0 已以 mock 数据层交付全部核心页面与交互链路；M1 迭代一已交付登录与 API 接入（两条登录路径 + 传输层 + 真实 Store，见 §6）；M1.x 已交付品牌迁移（BiuZ）与连接态只读边界（§7），待端到端联调兑现「真实账号在手机端完成全闭环」的出口标准（§14 M1 行）。

## 2. ZCode 功能模块调研（模块 → 移动端策略）

调研方法：实际克隆 `https://github.com/zai-org/ZCode`（`--depth 1`）至 /tmp/zcode-research，通读 README、CONTEXT、AGENTS、DESIGN 及 packages/ui、apps/zcode-cli、packages/server 等源码与 zh-CN 文案（只读，未运行构建）。共梳理 **27 个功能模块**，按移动端价值分为 core（8）/ secondary（14）/ skip（5）三档。
**⚠ 证据状态（v1.2 二次修订更新）**：早期调研克隆目录 /tmp/zcode-research 已不存在，27 模块分级与竞品结论仍无落盘原始材料可查（§13 决策信息 ⑥）。M1 迭代重新克隆仓库至 **/tmp/zcode-api-research（v3.14.3）**，报告撰写时在位：首轮对接口调研抽查 13 处行级证据全部属实；v1.2 二次修订再对 §2 早期转引抽查 9 处属实、2 处未能定位（TaskList.tsx:365、zcode-cua/README.md），并据此更正了 1 处可证伪数字（§2.3 TUI 文件数，明细见 §11.6）——分级结论的采信口径为「转引 + 抽查核验」，未抽查部分仍为转引；克隆件位于 /tmp 未落盘工作区。

### 2.1 core——移动端必须保留

| 模块 | 桌面端现状（摘要） | 移动端策略 |
|---|---|---|
| 会话/任务管理 | 以「任务」为会话单位：TaskList 列表、置顶/归档/时间线分组、新建/重命名、命令面板快速找任务；后端为 packages/services/src/session | 状态三分组看板 + 会话时间线列表（置顶/今天/昨天），左滑置顶/归档/重命名 |
| 对话界面 | v4 会话区（SessionPane/ConversationTimeline/ConversationComposer）+ Lexical 富文本输入：@文件提及、附件、斜杠命令、模型切换、思考等级、上下文用量条 | 会话流为主体页：工具卡 / todo 卡 / 提问卡流式渲染，底部常驻输入栏（模型 pill + 思考 pill + 上下文进度条 + 语音/发送键） |
| Agent 执行过程可视化 | 工具调用块（bash/read/write/edit/webfetch/todo/node-repl 等）、todo 列表、计划面板、权限/引导对话框、后台 bash 输出、完整轨迹可展开/搜索 | 工具卡（bash 输出展开、edit 卡 diff 片段）、todo 拆解卡、执行输出页（后台 Bash / 模型轨迹分段）；权限对话转为审批 Sheet |
| 工作区文件树与预览 | WorkspaceFileTree + 文件搜索（.zcodeignore）；PreviewPane 支持 markdown/代码/图片/音视频/PDF/PPTX/Office | 产物预览页（预览/源码分段）+ 底部「浏览工作区文件」抽屉（medium/large detents + 文件搜索） |
| Git 变更与 diff | GitPane 变更列表 + diff 渲染（懒加载、diff 内查找）、分支切换、git 操作、文件回退；diff 色令牌见 DESIGN.md:118-119 | 独立审查 Tab：文件卡折叠 + Unified Diff（hunk/del/add/ctx 着色、横滚禁折行）+ 逐文件/全部批准与拒绝 |
| 登录与账号 | **v1.2 修正**：早期调研口径为「仓库无账号体系」，不成立——Z.ai OAuth 账号登录存在于 packages/web（授权入口/回调解析/令牌交换，证据见 §6.2 路径 A）；另有 WelcomeScreen OAuth + API Key 表单、token 刷新与凭据服务 | 已在 M1 迭代一落地为独立登录栈：账号层 OAuth（应用内授权）+ 连接层桌面配对双路径（§6.2），凭据均存 Keychain |
| 模型/Provider 设置 | 设置「模型设置」：Provider 端点/密钥管理，会话内快速切模型 | 「我的/设置」Tab 内模型设置二级页，UserDefaults 持久化；会话页模型 pill 快速切换 |
| 主题与国际化 | System/Zai Light/Zai Dark 三主题（DESIGN.md:44-50）、zh-CN/en-US 双语、跨窗口广播同步（AGENTS.md:54） | 深浅双主题全量令牌（对齐 design-spec §2.1/§2.2），外观二级页即时生效并持久化 |

### 2.2 secondary——保留查看/消费侧，生成与配置入口精简

| 模块 | 移动端策略 |
|---|---|
| 会话分享 | 移动端重点是消费侧只读查看（packages/web/src/share 落地页同源）；生成入口可精简 |
| 动态工作流 | 保留运行状态/产物查看；创建/编辑 skip |
| 子智能体（Subagents） | 保留运行状态查看入口（对应 SubagentSessionSidePane） |
| 插件系统与插件商店 | 浏览商店为 secondary；安装/卸载管理可精简 |
| IM Bot / 手机远控 | 配置可精简，但该通道是移动使用的核心之一，保留设备页入口（优先级待决策，见 §1.2） |
| Agent 能力配置（技能/MCP/命令/钩子） | 保留查看/启停；钩子等开发向配置 skip |
| 自动化（beta） | 保留列表与状态查看；编辑器精简 |
| 记忆（Memory） | 保留查看/管理；移动端低频 |
| 用量统计与 Coding Plan 额度 | 「我的」页额度条与套餐徽章直接承载 |
| 集成终端 | 仅保留后台任务输出查看；交互式终端精简（对应执行输出页） |
| 远程工作区（SSH/WSL） | 桌面开发场景为主；移动端查看远程任务状态为 secondary |
| 画板（Whiteboard） | 触屏适配潜力好但属低频辅助，暂不纳入 |
| 浏览器控制（Browser Use） | agent 能力，移动端仅看状态 |
| 新手引导与反馈 | 低频但轻量，接入后端后随首启流程补充 |

### 2.3 skip——移动端不适用

| 模块 | 理由 |
|---|---|
| Computer Use（电脑控制） | 仅桌面注入（packages/ui/src/settings/settingsPageConfig.ts:173-177，`showComputerUse = isDesktop \|\| isMacDesktop \|\| isWindowsDesktop`，v3.14.3 复验）；「开源构建 fail-closed 不可用」所引 zcode-cua/README.md **在 v3.14.3 克隆件中不存在**（实测 `ls zcode-cua/` → No such file or directory），该结论降级为早期转引未复核；移动端无意义 |
| 终端 TUI 界面 | 键盘驱动的终端 UI（apps/zcode-cli/packages/tui，**实测 95 个文件、其中 .ts 61 个**，`find -type f \| wc -l`；早期转引「约 86 个」与任一口径不符，v1.2 二次修订更正）；移动端无终端场景 |
| 键盘快捷键与命令面板 | 键盘驱动交互（DESIGN.md:29）；移动端不适用 |
| Treemapping 文件活动图 | 大屏信息可视化；移动端不适合 |
| 桌面壳与系统功能 | Electron 窗口管理/自动更新/托盘等桌面专属 |

## 3. 设计方案与基准

### 3.1 设计定位

基于 TRAE / Qoder 两款竞品官方 App 验证的移动范式，确定设计总纲：

> **手机 = 遥控与审批台，重活 = 云端沙盒 / 配对电脑执行。**

- **信息架构（设计基准）**：底部 4 Tab「任务 / 对话 / 审查 / 我的」+ 三层 push 栈「任务看板 → 任务会话页 → 全屏 Diff/产物页」；iOS 边缘右滑返回，Android 预留系统返回 + 页内面包屑（规范已写入 design-spec）。
  ⚠ **实现偏差**：交付代码的 Tab 为「会话 / 任务 / 文件 / 设置」且会话（chat）为第一位、启动默认选中（ios/ZCodeMobileApp/Sources/App/AppRouter.swift:25-28），与设计稿命名/顺序不一致（审阅方核验设计稿 Tab 标注确为 任务/对话/审查/我的——行号经 v1.6 二次修订复测更正：现网在 html:613-616 与 :842-845 两处 Tab 条，v1.2 所引 :597-600 现为任务卡「已完成」分组区）；详见 §12 风险 4。
- **视觉语言**：深色优先、低饱和近黑背景 + 单一高饱和强调色（TRAE 主色近黑 #0a0b0d + 亮绿 #32f08c；Qoder 状态色 Running=蓝 / Waiting=橙 / Completed=绿，docs.qoder.com）。移动端降低 IDE 基因带来的信息密度：每屏 1 个主卡片流 + 状态色胶囊承载 Agent 状态语义；同时交付 Light 主题（WCAG AA 按最差复合底实算，`--accent-text` 5.08:1、`--text-3` 5.78:1，design-spec.md:89/87，审阅方已核验属实；v1.2 二次修订复测行号并更正）。
- **关键交互**：审批走独立 Sheet（盾图标 + 命令卡 + 授权范围单选 + 批准/拒绝/追问/稍后，下拉不可关闭）；Diff 用 Unified 单列 + 文件级折叠 + 逐文件批准；输入栏语音 + 文字双通道，键盘态自动折叠建议区。**v1.2 新增**：登录/连接域关键交互——OAuth 授权用应用内 Sheet（WKWebView 容器 + 回调拦截，不跳系统浏览器）；桌面配对凭据走「扫码 / 剪贴板 / 手动」三通道（§6.4）。

### 3.2 设计基准文件（工作区，v1.2 更新）

| 基准 | 路径 | 说明 |
|---|---|---|
| 设计规范 | [design/design-spec.md](../design/design-spec.md) | **v2.4 · 2026-10-02 · 495 行**（实测 `wc -l`；v1.1 时为 v2.3/377 行），基准机型 390×844。**v2.4 唯一改动为新增第 9 章「登录与连接」**：两条登录路径（A 账号 OAuth：应用内授权 Sheet→回调拦截→令牌交换；B 桌面配对：zcode-server 进程→server-info→WS→v4 握手→workspaces[0]）、两载体禁止注入自绘同意页（9.7.10）、testid 扩展 O/L 前缀（9.6）；既有令牌/组件/屏 01–14 规范保持 v2.3 口径。**v1.3 品牌迁移**：标题改「BiuZ 移动端客户端 · 设计规范」（design-spec.md:1 实测），:453/:469 两处本地网络权限固定文案与 Info.plist 新措辞逐字同步（实测一致）——设计稿是 plist 文案的源头规范，只改代码不改稿会导致下次实现回退 |
| 高保真主稿 | [design/zcode-mobile-design.html](../design/zcode-mobile-design.html) | 单文件自包含；**1991 行、123 处内联 data-testid**（v1.3 二次修订复测 `wc -l`/`grep -o`；v1.1 时 1975 行——M1.x 品牌改稿内嵌 SVG 后行数变化，v1.3 首版未复测、独立审阅指出后已更正），14 屏 |
| **登录与连接专项稿（v1.2 新增基准）** | [design/login-design.html](../design/login-design.html) | **1949 行**（v1.3 二次修订复测 `wc -l`；M1.x 品牌改稿前为 1869）；**18 屏**（O1–O3 六帧 + L1–L5 十二帧，含 O2-A-M BigModel 变体 / L2-N 免鉴权 / L1-H 帮助页 / L4-B 已过期样例）+ 画布头部双路径流程模型；**102 处 testid 标注 / 95 唯一**（v1.3 复测 `grep -o 'data-testid="[^"]*"' \| wc -l` = 102、`sort -u \| wc -l` = 95，与 spec 9.6 数字一致） |
| Pixso 原稿 | <https://pixso.cn/app/design/AbZPdSg5AO6CqRDpr5RKwg>（主稿画板节点 5:3026；登录稿同步尝试节点 9:1，**pushed=false**，见 §6.5） | ⚠ 外部链接，本工作区未联网核验其有效性，评审如需查证请自行打开 |

**主稿 14 屏结构**：01 登录与扫码配对 / 02 任务看板（Tab 1 根）/ 03 新建任务·分发 Sheet / 04 会话列表（Tab 2 根）/ 05 Agent 对话·会话流（Push L2）/ 06 审批请求弹层（模态）/ 07 任务执行输出·终端（Push L3）/ 08 Diff 审查（Tab 3 根）/ 09 产物预览（Push L3）/ 10 通知中心+Live Activity / 11 设备与配对管理 / 12 我的·设置与设备（Tab 4 根）/ 13 状态与次级页样张 / 14 Light 主题样张。
**登录专项稿 18 屏**：O1 登录主页 / O2-A 应用内授权 Sheet / O2-A-M BigModel 变体 / O2-B 回调拦截与授权中 / O3-A 登录成功 / O3-B 失败取消与重试 / L1 默认连接页 / L1-K 手动连接键盘态 / L1-S 扫码取景器 / L1-S-D 相机权限拒绝 / L1-H 连接帮助 / L2 连接中 / L2-N 免鉴权变体 / L3 连接失败/重试 / L4 我的·分组 / L4-B 配置区 / L5 服务器详情 / L5-S 危险操作二次确认（明细见 §6.4）。

### 3.3 设计基准核对结论（修订）

基准核对阶段结论为 ready = true，其覆盖范围是 **spec ↔ HTML 两方一致性**。v1.1 修订后如实呈现三层缺口（均不阻塞预研结论，但影响基线冻结）：

1. **spec ↔ HTML 漂移**：design-spec.md:315（v1.2 二次修订实测更正，原引 314）的 §5.10 表格把 `05-toolcard-head-ask` 列为「设计稿已标注的关键选择器」，但 HTML 内联 123 个 testid 中不存在该值——Agent 提问卡实际标注为 `05-ask-chip-1/2`（zcode-mobile-design.html:946-947，v1.3 二次修订复测更正；v1.2 所引 :930-931 为 M1.x 品牌改稿前的历史行号），卡头无 testid。
2. **app ↔ 设计基准漂移（v1.1 新增）**：实现 app 的提问卡 id 为第三值 `05-questioncard`（MessageViews.swift:294 为 v1.3 时点值，**v1.6 二次修订复测现网 :277**——M2 对 MessageViews 加厚后漂移，v1.1 所引 :276 为更早历史值，E2E 流程 3 等待它），既不在 HTML 的 123 个 testid 中、也不在 spec §5.10 示例中——**spec / 设计稿 / 代码三方互不一致**。加上 §3.1 所述 Tab 命名/顺序偏差，说明「app 与设计基准一致性」此前未被任何阶段核对过。
3. **Light 实渲稿覆盖**：主稿其余 10 屏无 Light 实渲稿（仅屏 14 看板/Diff 两张），为 spec §11.3 已声明的取舍；按最差复合底 4.5:1 逐屏抽检（优先屏 01/05/06）兜底。
4. **登录专项稿（v1.2 新增）**：O/L 屏在设计阶段已完成三轮评审修订（8 条逐条修复，见 §6.4），testid 标注与 spec 9.6 数字一致（102/95，实测核验通过）；**但未做真机渲染、辅助功能审计与 Light 逐板回归**（设计阶段已声明超出本稿范围），与主稿缺口 3 同口径管理。

## 4. 技术方案与选型理由

### 4.1 选型：Swift + SwiftUI 纯原生（iOS 17.0+，仅竖屏）

- **为什么不复用桌面 Web 技术栈**：ZCode 桌面 UI 为 TypeScript/React（packages/ui），虽 web-compatible，但共享 UI 的移动适配点（`isMobileViewport` 等）当前均传 `false`，无可复用的移动层；且小屏重交互（Diff 渲染、流式会话、手势返回、深浅色动态令牌、系统推送/Live Activity）对原生能力依赖强。早前迭代的 Web 半成品脚手架已应用户要求清理（见 §12 风险 7）。
- **原生收益**：系统级手势返回与下拉刷新（NavigationStack + .refreshable）、深浅色动态令牌（UIColor dynamicProvider + preferredColorScheme 覆盖）、后续推送与 Live Activity 的系统能力路径；满足 design-spec 的 44px 触控热区、95px Tab 栏构成、键盘避让等像素级标注。
- **自绘 Tab 栏**：设计稿要求 95px 高度构成（顶部 7px + 48px 热区 + 底部 6px + 系统安全区）、毛玻璃底色与蓝/橙双色徽章，系统 TabView badge 无法满足，故自绘 ZCodeTabBar；根视图切换亦未用 TabView，而是 ZStack + opacity 切换以保留各栈滚动位置（RootView.swift:14-38）。
- **流式架构（v1.2 兑现）**：M0 会话发送返回 `AsyncStream<ChatStreamEvent>`，View 侧 `for await` 事件循环落状态——接口边界与真实后端事件流模型同构；M1 已在该边界后接入真实流（ChannelClient 二进制帧 + v4 逻辑帧→行模型投影，见 §4.4、§6.3），Mock 与 Remote 实现可按连接状态整体切换。

### 4.2 工程管理：XcodeGen

工程以 [ios/project.yml](../ios/project.yml)（**98 行**，实测；v1.3 时 84 行）声明，`xcodegen generate` 产出 ios/ZCodeMobile.xcodeproj。理由：工程文件可 review、避免 .xcodeproj 合并冲突。定义应用 target `ZCodeMobile`（bundle id `cn.biuz.mobile`、显示名 BiuZ、iOS 17.0、仅竖屏；M1.x 品牌迁移前为 cn.zcode.mobile / ZCode，迁移记录见 §7.1）、UI 测试 target `ZCodeMobileUITests`（sources 仅 `path: Tests`，即编译 ios/Tests/ 下四个测试文件；bundle id 显式值 `cn.biuz.mobile.uitests`，随品牌迁移同步）。**M1 新增工程配置**（project.yml、Info.plist 实测）：注册 `zcode` URL scheme（OAuth 回调，CFBundleURLName `cn.biuz.mobile.oauth`）、`NSLocalNetworkUsageDescription`（本地网络权限）、`NSCameraUsageDescription`（扫码）、`NSAppTransportSecurity.NSAllowsLocalNetworking`（局域网 http；OAuth 令牌端点 https 不受影响）。**M1.x 新增**：sources 追加 `ZCodeMobileApp/Resources/Assets.xcassets`（AppIcon.appiconset 单尺寸声明 + BiuzMark.imageset 三档切图，见 §7.1）。**M1.y 新增（v1.4）**：`ZCodeMobileTests` 单元测试 target（project.yml:74-86 实测：`type: bundle.unit-test`、sources `path: UnitTests`、bundle id `cn.biuz.mobile.tests`、依赖 host app ZCodeMobile——@testable 链接需 unit-test bundle，UI-test bundle 链接不可行，注释已在 project.yml:73 说明）；scheme 测试动作同时挂 `ZCodeMobileUITests` 与 `ZCodeMobileTests` 两个 target（project.yml:94-98 实测）。

### 4.3 架构与可测性（v1.2 更新为代码实况）

- **分层与注入**：数据层协议为 `ConversationStore / TaskStore / FileStore / SettingsStore` 四个（StoreProtocols.swift:13-53 区间，实测协议边界与 v1.1 一致），经 SwiftUI EnvironmentValues `@Entry` 注入（StoreEnvironment.swift:9-14）；SettingsStore 经 `UserDefaultsSettingsStore` 包装为 `AppSettingsModel` 单独传入。**M1 起该边界有双实现**：`Remote*` 三件套（Stores/Remote/）实现同一协议，`AppSession` 按 `demo/connecting/connected/connectFailed/disconnected` 状态机装配——connected 切 Remote 三件套，其余状态回退 Mock（每次重建，与既有 e2e 前提一致）；不存在名为「AppStore」的门面类型。
- **Mock 流式剧本**：逐字打字机 24ms/字符（MockConversationStore.swift:127）→ bash 卡逐行输出 → edit 卡 diff → todo 更新 → 提问 → 收尾；M1 后保留为演示模式与断连回退层。
- **E2E 可测性**：App 侧 accessibilityIdentifier 静态调用实测 **167 处**（v1.6 `grep -rho 'accessibilityIdentifier(' ios/ZCodeMobileApp/Sources | wc -l`；M1.y 后 156、M1.x 后 149、M1 迭代一后 139、M0 时 69）——M2 新增 11 处锚点（05-approval-card/05-choice-\*/05-act-approve·reject、04-act-archived/04-archact-unarchive-\*、05-act-search/05-search-messages、12-row-notify 接线等，§10.5）。id 规约主稿部分仍存在 §3.3 所列三方漂移点。

### 4.4 M1 新增技术方案：传输层移植与登录域架构（v1.2 新增）

| 层 | 文件（ios/ZCodeMobileApp/Sources/） | 方案与依据 |
|---|---|---|
| 二进制序列化 | Services/RPC/RPCSerialization.swift | `JSONValue` 宽松解析 + RPCValue 七类标签（Undefined=0/String=1/Buffer=2/VSBuffer=3/Array=4/Object=5/Int=6）+ VQL 变长整数，对应 packages/rpc/src/serialization.ts:109-218；Int 取 32 位二补数口径（与 JS `(data|0)===data` 一致）；嵌套 Uint8Array 编 `{__zcode_rpc_nested_uint8array_v1,base64}` |
| 帧与信道客户端 | Services/RPC/ChannelClient.swift | 13 字节帧 `[type:1B][id:4B BE][ack:4B BE][length:4B BE]`（packages/rpc/src/protocol.ts:183-230，实测帧格式注释）；RequestType 100-103 / ResponseType 200-204（channels.shared.ts:18-31）；Initialize 门状态机、Promise 请求 id↔continuation（超时/响应两路竞争收口恰好 resume 一次）、EventListen/EventDispose 订阅、传输断开 fail-closed 全部挂起请求；传输用 `URLSessionWebSocketTask`——一条二进制消息即一帧，无需自处理粘包 |
| v4 线协议 | Services/RPC/V4Wire.swift | TopicWireFrame（complete/fragment，wire.ts:16-58）、TopicWireFrameAssembler 分片重组（logicalFrameId 键控、1024 分片/16MB 上限、可选 crc32）、V4TopicFrame 逻辑帧（snapshot\|deltas，transport.ts:162-186） |
| OAuth | Services/OAuth/ZaiOAuthProvider.swift | 按已核实机制实现：buildAuthorizeURL 双分支（ZAI `{origin}/api/oauth/authorize?redirect_uri&response_type=code&client_id&state` / BigModel `{origin}/login?redirect&appId&state}`，参数名差异按证据处理）、parseCallbackParams（code/authCode 双兼容、state 必填、error 参数）、exchangeToken（POST `{tokenOrigin}/api/v1/oauth/token`，body `{provider, code, redirect_uri, state}`，code≠0 抛错）、normalizeTokenResponse、toUserInfo 兼容；origin/client_id/appId/redirectURI 均可经启动参数覆盖（`-ZCodeOAuthZaiOrigin` 等，供测试指向本地替身） |
| 凭据 | Services/Keychain/KeychainStore.swift | kSecClassGenericPassword 封装 + OAuthCredentialStore（tokenSet/userInfo 仅存 Keychain，不落 UserDefaults，掩码展示末 4 位，isExpired 判定、过期即要求重登**不做静默续期**）+ ServerRegistry（服务器配置/选中项）；service 常量随品牌迁移改为 `cn.biuz.mobile`（v1.3，KeychainStore.swift:16 实测——v1.3 首版误引 :15，独立审阅指出后更正，:15 为「E2E 不受影响」注释行；旧 cn.zcode.mobile 条目不可见，影响面见 §7.1） |
| 桌面配对连接 | Services/Remote/ZCodeServerConnection.swift | ServerRemoteInfo 解析（server-remote.ts:5-38）→ 连接五步状态机（发现 GET /api/server-info 3s 超时重试 1 次 → 令牌校验（authRequired=false 显示「免鉴权」）→ WS /ws?token=（5s，clientMode=web-remote-replayable）→ v4 握手（protocolVersion==3 严格校验，不符即失败**不降级**；clientHello 恒不带 capabilities，满足 transport.ts `.strict()` 单向宣告规则）→ workspaces[0] 载入）；ConnectError 分型（401/超时/协议版本/空工作区/握手/传输）；帧流订阅（onDynamicConversationFrame / onDynamicSessionsIndexFrame 按 topic 前缀分流） |
| 链接解析与装配 | Services/Remote/ConnectURLParser.swift、AppSession.swift | 完整链接拆解（scheme ∈ http/https/ws/wss、?token= 剥离、host:port 缺省 + 剪贴板识别）；装配状态机（冷启动：未配置→演示模式；已配置后台自动重连失败回退 mock+横幅；OAuth 会话与服务器两层凭据独立，退出登录只清 tokenSet；QA 启动参数 `-ZCodeOpenLoginFlow`/`-ZCodeOpenConnectFlow`/`-ZCodeE2EResetState`） |
| 真实 Store | Stores/Remote/Remote{Conversation,Task,File}Store.swift | 实现与 Mock 同一协议边界：subscribeSessionsIndexV4/subscribeConversationV4 行模型投影（row.delta 逐字拼接、state.updated 键级替换、pendingInteractions→提问卡）、conversationRowsRangeV4 历史（limit 200）、sendConversationCommandV4（**v1.3 只读边界后仅 createSession 且不带 firstInput**；sendText/resolveInteraction 已停用为协议位 no-op，见 §7.4）；zcode-task.listTaskList、file.readdir/readTextFile、git.getChanges/getDiff |
| **只读边界（v1.3 新增，v1.4 扩展）** | Services/Remote/ReadOnlyGate.swift（新文件，v1.3 时 116 行，**v1.4 扩至 246 行**，实测）+ ZCodeServerConnection.call | 命令三分类判定（readOnly/session/execution，分类口径在文件头注释固化，ReadOnlyGate.swift:7-16）；execution 黑名单（v1.4 实测构成）：信封内 17 个命令 type（:38-47）+ firstInput 双态 2 type（:51-53）+ zcode-task 15 命令（:61-68，v1.3 时 5）+ git 8 命令（:74-78，v1.3 时 2）+ zcode-agent 34 命令（:86-102，v1.3 时无此集合）+ 频道级黑名单 25 频道 127 命令（:111-181）——**合计 201 命令拦截项 + 2 双态**；连接态在 RPC 唯一出口 `call` 拦截并留存最近 20 条拦截记录（ZCodeServerConnection.swift:638-643 实测）；未知命令放行避免误杀握手/订阅/查询链路（:184-185）；演示态不经过连接层，交互完全不变。设计依据与分类证据见 §7.2，扩展依据见 §8.2/§8.6 |
| **接口对接只读面（v1.4/M1.y 新增）** | Stores/Remote/ 三 Store 扩展 + Services/Remote/AppSession.swift + Services/RPC/V4Wire.swift | ①文件面：searchWorkspaceFiles 服务端搜索（RemoteFileStore.swift:151）、file.stat 守卫（:183）、readTextFile 有界分页 contentPage（:207，首屏 256KiB + 「加载更多」）、file-watcher watch/unwatch/disposeAll+onDynamicChange 文件树活性（:70-113）；②会话面：conversationRowsRangeV4 向上分页 loadOlder（RemoteConversationStore.swift:262）、订阅失败兜底 listSessions（:162）与 readSession 对账（:303）、置顶/已读/归档**双写**（zcode-task.setTaskPinned/setTaskUnread/archiveTask，:678-729，远端失败回滚本地）、draft 转正 promoteDeferredDraftSession（:666）；③任务面：onDynamicTaskEvent 服务端推送（RemoteTaskStore.swift:58-94）、getTaskConfigOptions/getTaskModelSelection/getTaskTokenUsage 三读（:222-289）；④Diff 面：git.refresh 前置（RemoteFileStore.swift:257）、staged 维度（:265 参数化）、conversationFileChangesV4 会话维度（:322）；⑤自愈面：V4Wire 丢帧标记 droppedSinceLastConsume（V4Wire.swift:68/:125/:133-134）→ resyncConversationV4/resyncSessionsIndexV4/resyncWorkspaceConfigV4（ZCodeServerConnection.routeFrame :559-575）；⑥配置/模型面：workspace-config 订阅族（runtimePolicy=existing-only 不拉起 Agent）+ model-selection.getView/onDidChange（RemoteConversationStore.swift:747-870）；⑦只读信息面：oauth 三读 + usage-stats 两读（AppSession.swift:185-282）；全部经 ZCodeServerConnection.call 出口受 ReadOnlyGate 判定，连接态 RPC 出口 40 call + 7 listen 全落 readonly/session 两类（§8.6 实测口径） |
| **云中继接入（v1.5/M1.z 新增）** | Services/Relay/ 四件（RelayLink/RelayFrameCodec/RelayTransport/RelayChannelClient，§9.2）+ ConnectURLParser/KeychainStore/ZCodeServerConnection/AppSession/ConnectFlowView/RemoteConversationStore 改造 | 官方网页壳 + 前端 bundle 逆向 + 探针实测得出的 remote/v4 协议（§9.1）：①**链接面**——ConnectURLParser.parseRelayLink（ConnectURLParser.swift:35）按 https+/remote/ 前缀识别云中继配对链接→wss://host/ws?mid=…（sid/hash 不进 URL，hash 百分号解码为原字符串作 HMAC 密钥）；②**鉴权**——RelayAuth.proof（RelayLink.swift:92）=base64url 无填充(HMAC-SHA256(key=UTF8(passHash), msg=nonce\|terminal\|deviceSid))，单测锚定探针实测向量；③**传输**——RelayTransport（actor，802 行）URLSessionWebSocketTask JSON 文本帧收发、auth 握手、心跳 10s+jitter≤2s、ack 看门狗 30s、指数退避重连、error 帧分类；④**可靠层**——RelayFrameCodec（244 行）rpc-frame 编解码/分片二分自适应（≤64 片且单片信封≤1MB）/crc32（与 V4Wire.crc32 同参）/下行重组器；⑤**通道**——RelayChannelClient（444 行）与 ChannelClient 同语义 call/listen，经新 RPCChannelTransport 门面注入 ZCodeServerConnection（:236-237），**ReadOnlyGate 出口拦截对中继路径同等生效**（§9.4）；连接编排 auth→bootstrap→workspace-list→bridge-open→桥内 v4 握手（桌面桥有 handshakeRequired 闸，局域网 server 无闸故 connect() 顺序不变）；⑥**凭据**——ServerConfig 增可选 relay 字段（KeychainStore.swift:96，Codable 向后兼容有单测）；AppSession `-ZCodeRelayLink` 启动参数（:145-167）供真机验收；中继服务器跳过局域网 HTTP 探测（probeSavedServer :405）。局域网直连与演示模式行为不变（ChannelClient 仅加显式 conformance，connect() 五步与 ReadOnlyGate 出口未动）。协议依据与验收证据见 §9，风险见 §12 风险 20/21 |

| **M2 纠偏与补全（v1.6 新增）** | ReadOnlyGate.swift 全文件重写（246→265 行，四分类）+ RemoteConversationStore/RemoteTaskStore 动作面恢复 + NotificationService（新文件）+ 五处 UI 降级恢复 | 边界口径修订（§10.4）：readOnly/session/command/directWrite 四分类，isBlocked 仅对 directWrite——v4 sendConversationCommandV4 仅 applyFileRewind 拦截，消息/审批/停止/队列/会话任务管理全部归 command 放行；zcode-task 黑名单收窄为配置写 5 项；zcode-agent 旧协议消息面放行、新增直写族 4 命令拦截；**file 频道改「默认拒绝+读白名单」**（5 个读命令放行，堵未知写命令放行口子）；git 写 8/terminal 4/settings-sync 等频道写族黑名单保留。动作面（§10.5）：send 信封+乐观回显去重、resolveInteractionRaw（interactionId+answer）、stop v4 信封、createSession+firstInput、renameTask/unarchiveTask/listArchivedTasks 双写、NotificationService（UNUserNotificationCenter 三类通知+3 秒去重+开关联动）、binaryPreview（图片/PDF 只读预览）、会话内/任务服务端搜索。连接态 RPC 出口 40 call+7 listen 的**分类标签**由 readonly/session 两类扩为三类放行（readOnly/session/command）——出口唯一性与拦截链路不变（出口现网 :795-806，§7.2 勘误·续二） |

**选型理由**：直接照 packages/rpc/src/{protocol,serialization,channelClient,proxy-channel}.ts 与 packages/client/src/websocket.ts 逐行移植（总量约 1.5k 行 TS），而非自造协议层——服务端形状以 zod schema 为准，移植保真度最高；协议未冻结（§12 风险 11），故实现采取「protocolVersion 严格校验失败即报错不降级」口径，避免静默错配。

## 5. 已实现范围（M0 原型，代码位于 [ios/](../ios/)）

> v1.2 注：本节记录 M0 范围（仍然成立）；M1 迭代在其上**新增 17 个源文件**（见 §6）。**v1.3 注**：M1.x 在其上**新增 1 个源文件**（Services/Remote/ReadOnlyGate.swift）与 `Resources/Assets.xcassets`（品牌资产，§7.1），App 源码合计 **43 个**（实测 `find ios/ZCodeMobileApp -name '*.swift' \| wc -l` = 43），并叠加连接态只读化改造（§7.4）。**v1.4 注**：M1.y 未新增 App 源文件（43 个不变，实测），改为对既有 Remote 三 Store / 连接层 / AppSession / ReadOnlyGate **加厚实现**（ReadOnlyGate 116→246 行、RemoteConversationStore ~570→926 行、RemoteFileStore ~201→463 行、RemoteTaskStore ~170→289 行、ZCodeServerConnection ~538→654 行、AppSession ~232→368 行，均为实测 `wc -l`；v1.3 时点值出自 M1.x 现状盘点）——测试面另增 ios/UnitTests/ 目录与 ZCodeMobileTests target（§8.3）。**v1.5 注**：M1.z 新增 **Services/Relay/ 4 个源文件**（RelayLink 105 行 / RelayFrameCodec 244 行 / RelayChannelClient 444 行 / RelayTransport 802 行，实测 `wc -l`），App 源码合计 **47 个**（实测 `find` = 47）；另对 ConnectURLParser（186 行）/KeychainStore（199）/ZCodeServerConnection（654→807）/AppSession（368→410）/ConnectFlowView（733）/RemoteConversationStore（926→944）加厚改造（行数均实测；v1.4 时点值出自 §8.3）——协议依据与实现清单见 §9.2。

**规模（M0 口径）**：App 源码 25 个 Swift 文件（M1 后 42 个、M1.x 起 43 个、M1.z 起 47 个、**M2 起合计 48 个**——M2 新增 Services/Notifications/NotificationService.swift（139 行，实测），实测 `find ios/ZCodeMobileApp -name '*.swift' | wc -l` = 48）；编译内测试 **v1.6 时为 8 个文件 / 78 个用例**（实测 `wc -l` 与 `grep -c "func test"`；v1.5 时 7 文件/62 用例）：ios/Tests/ 五文件（ZCodeMobileE2ETests.swift 443 行 **8 用例**（+test08）+ ZCodeMobileLoginE2ETests.swift 1470 行 **18 用例**（+test16/17/18）+ E2ELoginStubServer.swift **1950 行**替身服务器 + LayoutAuditTests.swift 501 行 8 用例 + RelayLinkE2ETests.swift 212 行 4 用例）+ ios/UnitTests/ 三文件（ReadOnlyGateTests.swift 246 行 **16 用例**（重写）+ RelayLinkTests.swift 347 行 18 用例 + **TaskNotificationContentTests.swift 63 行 6 用例（M2 新增）**，随 ZCodeMobileTests 单测 target 编译，§4.2/§8.4/§10.7）。⚠ 行数与阶段材料自报值的差异如实标注：登录套件 v1.4 记录 1216 行、替身 1747 行，v1.5 实测各 +4 行且差异未溯源（§12 风险 5 口径）；RelayLinkTests 阶段自报 16 用例、实测 18（以实测为准）；M2 材料对 ReadOnlyGateTests 两处自报（13/15+1）不一、**实测 16 个 test 方法且本会话实跑全通过**（§10.7）。另有**不参与编译的孤儿文件** ios/ZCodeMobileUITests/ZCodeMobileUITests.swift（**9 条用例**——v1.6 恶化：新增 testSettingsNotificationsTogglePersist 亦加入该孤儿文件，见 §12 风险 3、§10.8-2）。**v1.6 注**：M2 对既有文件加厚改造（实测）：ReadOnlyGate 246→**265**、RemoteConversationStore 944→**1164**、RemoteTaskStore 289→**359**、RemoteFileStore 463→**490**、ZCodeServerConnection 807→**823**、AppSession 410→**417**、ChatView 455、ConversationListView 375、TaskOutputView 382、NewConversationSheet 210（后五个为 v1.6 时点实值，此前未单列）——改动明细见 §10.5。

| 范围 | 内容 |
|---|---|
| Tab 骨架 | 四 Tab（实现命名/顺序：**会话→任务→文件→设置**，chat 首位且启动默认，AppRouter.swift:25-28）+ 各自独立 NavigationStack（Router 注入跨层 push）；根视图为 ZStack + opacity 切换（RootView.swift:14-38），非 TabView；自绘底部 ZCodeTabBar（95px 构成、毛玻璃、三色徽章）；push 层隐藏 Tab 栏；手势返回、下拉刷新、左滑操作。**M1 增量**：根层连接状态横幅（connectFailed 红/disconnected 橙，5.9 模式）；Router 新增 serverAccount/serverDetail 路由值（AppRouter.swift:20 实测） |
| 任务看板 | 时间问候+铃铛+头像+搜索框；待操作（橙）/ 进行中（蓝）/ 已完成（绿）三分组卡片流；FAB 56px |
| 新建任务 Sheet | 大输入区 + 上下文 chips + 执行端单选卡 + 工作目录与模型分组 + 建议提示词 + 「开始任务」48px；键盘态自动折叠建议区 |
| 会话列表 | 置顶/今天/昨天时间线分组；会话行=40px 头像+标题+运行中蓝/待批准橙胶囊+摘要+时间+未读徽章（mock 标题「重构会话持久层」「修复登录超时问题」，MockConversationStore.swift:276,282 为 v1.2 时点值、**v1.6 二次修订复测现网 :288/:294**）；下拉刷新；左滑置顶/归档/重命名 |
| Agent 对话 | 自定义导航 + 3px 渐变进度条；消息流=用户渐变气泡+Agent 全宽文本+工具调用卡+todo 拆解卡+提问卡（44px 快捷回复 chips，id `05-questioncard`）+流式行（逐字渲染自动吸底）；底部常驻输入栏，键盘避让 |
| 审批请求 Sheet | 盾图标橙+命令卡+授权范围三行单选+拒绝/批准执行各 48px+追问/稍后各 44px；`interactiveDismissDisabled`；待批准会话自动直达 |
| 执行输出 | 44px 分段（后台 Bash/模型轨迹）+状态条+终端卡+复制全部/停止任务 44px（二次确认）+模型轨迹/子智能体入口 |
| Diff 审查（文件 Tab 根） | 大标题+分支胶囊+统计行（**mock 动态累加：3 文件合计 +33/−11**，MockFileStore.swift:61-90）；文件卡 44px 文件头+unified diff+批准/拒绝此文件；底部「全部批准/桌面端继续」动作栏；空态；下拉刷新 |
| 产物预览 | 导航+44px 分段（预览/源码）；markdown 预览与等宽源码视图；底部「浏览工作区文件」抽屉 |
| 通知中心 | 全部/待办分段+三类聚合+全部已读 |
| 设备与配对 | 云端沙盒卡+配对电脑卡+扫码配对主按钮+IM Bot 行 |
| 我的/设置 | 用户卡（渐变头像+Coding Plan 徽章+额度条 68%）+四分组 48px 行；外观与模型设置二级页经 UserDefaultsSettingsStore 持久化。**M1 增量**：用户卡后插入「服务器与账户」分组（l4-row-server/token/account/add）；页脚演示/实时数据提示随 isDemo 切换；既有四分组与 12-row-\* 选择器原样保留 |
| 数据层 | 四协议 + InMemory Mock + `@Entry` 注入 + `AsyncStream<ChatStreamEvent>` 剧本化流式模拟；**M1 起另有 Remote 真实实现（§4.4/§6.6）** |
| 测试文件 | M0：ios/Tests/ZCodeMobileE2ETests.swift（5 条流程，随 ZCodeMobileUITests target 编译）。**v1.3 实测**：该 target 编译 4 个测试文件（常规 7 用例 + 登录 12 用例 + 替身服务器 + 布局走查 8 用例，pbxproj 复核同 target）。**v1.4 实测**：UI 测试 target 仍 4 文件（登录套件扩至 15 用例、替身扩至 1747 行）；**另新增 ZCodeMobileTests 单测 target**（sources `path: UnitTests`，编译 ReadOnlyGateTests.swift 10 用例，pbxproj 复核）；孤儿：ios/ZCodeMobileUITests/ZCodeMobileUITests.swift（8 条，不属任何 target）。**v1.5 实测**：UI 测试 target 扩至 5 文件（新增 RelayLinkE2ETests.swift 4 用例，pbxproj 含该文件引用，实测）；单测 target 扩至 2 文件（新增 RelayLinkTests.swift 18 用例）；两 target sources 均目录通配（project.yml:63-64/:77-78 实测），Relay 文件经 `xcodegen generate` 纯增量入工程（pbxproj 复核 Relay 4 文件 + 2 测试文件均在 Sources phase） |

**已知取舍**（实现阶段 notes 归纳）：屏 01 登录页与屏 13/14 样张板 M0 未单独成页——**该欠账已在 M1 迭代一兑现**（登录栈成页，见 §6.6）；语音按住说话、分享、桌面端接力为占位交互；扫码取景器已在 M1 实现（L1-S，AVCaptureSession QR）；流式吸底为始终跟随；根目录 [e2e-contract-uitests.reference.swift](../e2e-contract-uitests.reference.swift) 为早期遗留契约对照物，按裁定不参与编译。

## 6. M1 迭代：登录与 API 接入（v1.2 新增章节）

本章记录 M1 迭代一的全部产出：早期结论修正（6.1）→ 两条登录路径与证据（6.2）→ 接口调研结论与 gaps（6.3）→ 登录设计（6.4）→ Pixso 同步（6.5）→ 实现范围（6.6）→ 门禁结果（6.7）→ 绑定假设与待确认项（6.8）→ 应用内授权安全取舍（6.9）。证据来源：本轮接口调研对开源仓库 /tmp/zcode-api-research（v3.14.3）的只读精读（未运行其构建/测试），其中 13 处行级引用（按文件归并 10 组）已由本报告撰写会话抽查复验（§11.6）；仓库内引用为 `包路径/文件:行号` 文本（工作区外，非链接）。**v1.3 注**：本节记录迭代一交付时点的形态；其中执行类发送入口（sendText/resolveInteraction/带 firstInput 的 createSession）已在 M1.x 迭代按只读边界停用（§7.4），本节文字保留历史口径。

### 6.1 早期结论修正：「仓库无账号体系」不成立

早期功能调研曾得出「仓库无账号体系」的口径（该结论未落盘工作区，仅存于阶段材料）。**本轮逐行核实予以修正**：Z.ai OAuth 账号登录**存在于 packages/web**——授权入口构造（packages/web/src/auth/webZaiOAuthConfig.ts:26-61）、回调参数解析与令牌交换（packages/web/src/auth/zaiWebOAuthProvider.ts，exchangeToken/normalizeTokenResponse/parseCallbackParams）、凭据持久化（packages/web/src/auth/browserOAuthCredentialRepo.ts:40-76，localStorage/sessionStorage）、Bearer 携带（packages/web/src/share/conversationSharePreviewClient.ts:145，实测）。该体系此前仅以 Web 形态存在，**本轮已按移动端形态（应用内授权 + Keychain）接入**（§6.6）；§2.1 core 表「登录与账号」行已同步修正。

### 6.2 两条登录路径（登录界面设计依据）

两路径互相独立：**A 是账户层（Z.ai 账号），B 是连接层（桌面机）**；凭据均仅存 Keychain。产品语义上，A 服务于云端分享/Coding Plan 账号身份，B 服务于局域网远控桌面执行——登录页不做「OAuth 即配对」的隐含绑定。

#### 路径 A · Z.ai 账号 OAuth（应用内授权）

| 步骤 | 机制 | 证据（已核实，除注明转引外均为本轮精读） |
|---|---|---|
| 1 授权入口（ZAI 默认） | `{origin}/api/oauth/authorize?redirect_uri=…&response_type=code&client_id=…&state=…`，origin 缺省 `https://chat.z.ai`；源码注释明确：ZAI 走 `/api/oauth` 前缀，`/auth/oauth` 是旧入口 | packages/web/src/auth/webZaiOAuthConfig.ts:26-29、54-56 |
| 2 授权入口（BigModel 高级选项） | `{origin}/login?redirect=…&appId=…&state=…`，appId 缺省 `zcode` | webZaiOAuthConfig.ts:39-47、59-61 |
| 3 应用内授权 + 回调拦截 | WKWebView 容器加载授权页；redirect 导航在视图内被拦截（redirectUri 前缀匹配即 cancel，不加载、不离开 App）；**不跳系统浏览器**（用户已决策，取舍见 §6.9） | 移动端实现口径（实现于 LoginFlowView.swift）；设计口径 spec 9.7.10 |
| 4 回调参数解析 | `code`（或 `authCode` 双兼容）、`state`（必填，缺失即报错）、`error` | packages/web/src/auth/zaiWebOAuthProvider.ts parseCallbackParams |
| 5 令牌交换 | `POST {tokenOrigin}/api/v1/oauth/token`，JSON body `{provider, code, redirect_uri, state}`；响应 `{code:0, msg, data:{token(zcode JWT), zai:{access_token}, bigmodel:{access_token}, expires_in, user}}` | zaiWebOAuthProvider.ts exchangeToken/normalizeTokenResponse；tokenUrl 定义 webZaiOAuthConfig.ts:56（实测） |
| 6 凭据结构 | tokenSet={accessToken, zcodeJwtToken, expiresAt}；userInfo={id, username, displayName, avatarUrl?}（toUserInfo 兼容 user.id/user_id、name/email、avatar/avatarUrl 与 data:image base64）；**移动端存 Keychain**（web 端存 localStorage/sessionStorage：browserOAuthCredentialRepo.ts:40-76）；过期/401 → 重新登录，不做静默续期 | zaiWebOAuthProvider.ts toUserInfo；过期口径=设计稿 L4-B `l4-b-card-expired` |
| 7 后续请求携带 | `Authorization: Bearer {accessToken}` | packages/web/src/share/conversationSharePreviewClient.ts:145（实测）。注意：该 Bearer 面≠桌面配对鉴权——桌面链路用 `?token=`（路径 B）；iOS App 当前无 Bearer 发送点（测试计划 notes 如实记录，替身测试已同等校验 Bearer 形态供后续切换） |
| 8 client_id | 来自部署环境变量 `VITE_ZAI_OAUTH_CLIENT_ID`，代码缺省样例值 `client_P8X5CMWmlaRO9gyO-KSqtg` | webZaiOAuthConfig.ts:58（实测；调研材料标注 53-56 有 2 行漂移） |

#### 路径 B · 桌面配对（局域网远控）

**架构事实（登录界面设计的对端模型）**：开源仓库中对外暴露 HTTP/WS 的是独立的 Node 服务 `packages/server`（@zcode/server）与 `packages/zcode-server-cli`（bin 名 `zcode`，`--web` 分流）；**Electron 桌面 App（packages/desktop）不内嵌任何面向客户端的 HTTP/WS server**——grep 仅命中 127.0.0.1 随机端口的本地媒体预览代理（packages/desktop/src/host/remoteMediaPreviewProxyHelpers.ts:102），桌面进程间走 Electron MessagePort + stdio channel RPC（AGENTS.md；packages/shared/src/channels.ts:488-500）。因此 iOS「连接桌面端」的实际对端是**桌面机上运行的 zcode-server 进程**（与桌面 App 共享 `~/.zcode` 数据目录但各自拉起 agent）——登录页文案与帮助页均按此口径表述。

| 步骤 | 机制 | 证据 |
|---|---|---|
| 1 桌面机启动 | `zcode --web --host 0.0.0.0 [--port 3030] [--workspace /path]`（README.md:91-105，转引）；监听非 loopback 时自动生成访问令牌 `randomBytes(24).toString("base64url")`，并把 `http://<LAN-IP>:<port>/?token=<token>` 逐个打印到终端；`--token` 可指定、`--no-token` 关闭；**仓库无 mDNS/Bonjour/二维码发现组件** | 令牌生成 scripts/zcode-distribution/runner.mjs:110（实测；材料标注 105-107 有 3 行漂移）；URL 拼打 runner.mjs:121-147 区间（实测 formatUrl 于 133 起） |
| 2 发现与鉴权 | 保护路径 `/ws`、`/ws/*`、`/api/*`（http.ts:239-241，实测）；首次任意路径带 `?token=` 即通过并写 HttpOnly+SameSite=Lax 会话 cookie `zcode_lite_token`（http.ts:187、227-237，实测）；原生客户端可每次 query 带 token（WS 升级同走该中间件）；令牌**无过期、无撤销接口**，随进程存活 | packages/server/src/http.ts |
| 3 能力发现 | `GET /api/server-info` → ServerRemoteInfo{serverId, name?, version, protocolVersion:1, authRequired, workspaces:[{path,label,workspaceIdentity}], capabilities{desktopContinuous, websocketRpc, processResourceTelemetry}}；iOS 取 `workspaces[0]` 为初始 workspace（web 同款逻辑 packages/web/src/main.tsx:370-388，转引） | http.ts:320（实测）；packages/shared/src/server-remote.ts:19-38 |
| 4 WS 接入 | `ws://<host>:<port>/ws?token=…`；服务端**固定**以 clientMode=web-remote-replayable、role=terminal-client 接入（旧 `x-zcode-rpc-client-mode` 提权头已废弃）；连接建立即推 RPC Initialize，随后 40+ 服务频道 channel RPC | http.ts:323-332（实测）；channels.ts:501；packages/rpc/src/channelServer.ts:30-35（转引）；channels.ts:75-152（转引） |
| 5 v4 协议握手 | `helloConversationV4()` → HelloMessage{kind:"hello", protocolVersion:3, connectionId, clientMode, deliveryProfile:"replayable", serverTime, capabilities, auth}；回 `initializeConversationV4(ClientHello{protocolVersion:3, clientId, clientKind:"mobileApp", appVersion})`——clientKind 枚举显式预留 `"mobileApp"/"mobileRemote"`（全库仅此一处引用，iOS 接入无参考端） | packages/shared/src/zcode-protocol-v4/transport.ts:44-86（枚举实测在 :73）；桌面参考实现 packages/ui/src/v4/agentV4ConnectionHandshake.ts:42-67（转引） |
| 6 业务面 | 会话列表流 subscribeSessionsIndexV4、会话流 subscribeConversationV4、历史 conversationRowsRangeV4（limit≤200）、命令 sendConversationCommandV4（sendText/createSession/resolveInteraction/renameSession/applyFileRewind 等）、文件 file.readdir/readTextFile、Git git.getChanges/getDiff、设置 setting.get/update 等 40+ 频道，**无 REST/JSON 业务 API** | transport.ts/command.ts/rows.ts/delta.ts/sessions-index.ts；packages/services/src/node.ts 注册面（均转引，形状以 zod schema 为准） |
| 7 流式投递 | 两档 profile：desktop-continuous→continuous（30ms，全量字段）；**web-remote-replayable→replayable（150ms flush，仅 text 增量，运行中带 toolProgress，desktopOnlyRows 关闭）——手机即 replayable 档**；断线用 resync + base{logEpoch,seq} 水位恢复（每 session 保留 2000 事件、snapshot 尾窗 60 行）；单 WS 帧 1MB、逻辑帧重组上限 16MB/1024 分片 | packages/shared/src/zcode-protocol-v4/core.ts:47-49（replayable flushWindowMs:150 实测）:31-110、71-77（转引） |
| 8 备选受信链路（不在 MVP） | `POST /api/rpc-host-capability` 签发 30 秒一次性 capability（hostCapability.ts:4 `DEFAULT_HOST_CAPABILITY_TTL_MS=30_000` 实测、issue() :34-40），带请求头 `x-zcode-rpc-host-capability`（channels.ts:502-503 实测）连 `GET /ws/host` 获得 desktop-continuous/trusted-host-relay 身份——「桌面把本地 Host 挂上 relay、手机复用桌面会话运行时」的通道；**但桌面侧连接端与外部 relay 均不在开源仓库**，无法据此直接实现。MVP 走 zcode --web 路径，协议与之完全同构 | packages/server/src/hostCapability.ts；http.ts:334-346（转引） |
| 9 备选 Bot 渠道（不复用作登录） | 微信/飞书/Lark/Telegram Bot 配置（WebRemoteControlDialog.tsx:23-30，zh-CN.ts:1615-1629「移动端远程控制」）；手机在聊天里发 `bind <6位HEX码>`（30 秒 TTL）绑定工作区；微信绑定本身走二维码 get_bot_qrcode——是「聊天账号↔工作区」绑定，**不是 WS 接入凭据** | botsService.ts:398-400、5372-5394；bots.ts:72；commandParser.ts:27；weixinRegistration.ts:132-134（均转引） |

### 6.3 接口调研结论（含证据与 gaps）

**端点面（v1.2 二次修订口径更正：HTTP/WS 端点 8 个；另有 RPC 频道面，非 HTTP 端点）**：①`GET /api/server-info`（发现）；②`GET /ws`（主 RPC 入口）；③`GET /ws/host`（受信 Host 挂载）；④`POST /api/rpc-host-capability`（30s capability）；⑤`POST /api/connect-remote`（Web 模式 SSH/WSL/Docker，手机场景基本用不到）；⑥`GET /ws/remote/:id`（远端桥接，仅 file/git/system/terminal 四频道）；⑦`POST /api/bots/:provider(/:botId)`（Bot webhook 回调，仅 webhook provider 走 HTTP）；⑧`GET /*`（静态资源 + SPA fallback，非业务 API）。**RPC 频道面（在 /ws 之内，不计入端点数）**：WS 频道 40+（setting/zcode-session/zcode-task/git/file/bots/oauth/credential/usage-stats 等，方法名→`call(command,[arg])`、`onXxx`→listen 的 ProxyChannel 约定）。**没有 SSE**（grep `text/event-stream` 仅命中 CLI 内部调试与模型侧 HTTP 流解析）；流式分两层：传输帧（13 字节头 + VQL 序列化 + Promise/Event 四类请求、五类响应）与业务流（v4 协议 wireVersion=3：会话行模型 9 种 kind 闭集、增量 op 闭集 row.appended/upserted/removed/delta + state.updated + workflowRun.updated、fromSeq/toSeq 区间记账）。
**关键配置常量**：PORT 默认 3030；`ZCODE_SERVER_HOST/HOST`、`ZCODE_SERVER_AUTH_TOKEN`、`ZCODE_SERVER_WORKSPACE`、`ZCODE_SERVER_ID/NAME`、`ZCODE_WEB_STATIC_ROOT`；server-cli Core 版仅允许 loopback（非 loopback 拒绝启动，fail-closed）；cookie `zcode_lite_token` 会话级无 Max-Age；capability TTL=30_000ms；Bot 绑定码 TTL=30_000ms；数据目录 `~/.zcode/`（`ZCODE_DATA_BASE_DIR` 可重定向）；`SERVER_REMOTE_PROTOCOL_VERSION=1`、`V4_WIRE_PROTOCOL_VERSION=3`（无兼容性承诺）。

**gaps（10 条，全部如实标注；绑定建议已按 §6.6/§6.8 落地）**：

| # | gap | 对本项目的含义 |
|---|---|---|
| 1 | 无任何移动端原生实现：`"mobileRemote"/"mobileApp"` 仅在 transport.ts:73 声明一次，全库无第二个引用 | iOS 接入是全新实现，无参考端；以桌面 UI（agentV4ConnectionHandshake.ts）为握手参考 |
| 2 | 无局域网发现/配对机制：无 mDNS/Bonjour/二维码渲染；`zcode --web` 只把带 token 的 URL 打到终端 | 登录页做「手动输入 host:port+token / 扫桌面端出示的二维码（App 自渲染，属仓库外包装）」；用 server-info 的 serverId/version 做连接确认页 |
| 3 | 桌面 Electron App 无对外 server，iOS 无法直连「正在运行的桌面 App」 | 两条现实路径：①用户在桌面机跑 `zcode --web --host 0.0.0.0`（MVP 已实现）；②Host attachment+外部 relay 的服务端入口在仓库、桌面侧连接端与 relay 不在——协议同构，后续只替换发现/鉴权层 |
| 4 | 访问令牌无过期与撤销（启动时固定，随进程存活） | iOS 按「服务器密码」管理（Keychain）；401 引导重新扫码；提示仅可信局域网使用；wss 需自备反代（仓库无 TLS） |
| 5 | 自定义二进制协议 self-declared 未冻结（core.ts:2「数据模型草稿，schema 定型以黄金测试为准」）；两个版本常量无兼容承诺 | Swift 实现严格校验 protocolVersion + 单向 capabilities 规则（clientHello 不带 capabilities），失败不降级；版本跟踪列入 §14 |
| 6 | web-remote-replayable 权限降级：/ws 固定 role=terminal-client，provider-provisioning-target 频道显式拒绝；Web 模式不支持页内再开远程工作区 | iOS 功能面按「单 workspace 的会话/任务/文件」设计 |
| 7 | 「逐文件 diff 审批」无服务端接口：只有会话级 rewind（applyFileRewind）与 git 层 discardPaths/stagePaths | DiffReviewView 的 setFileDecision/approveAll 改为本地 UI 态 + git 操作，或砍掉该交互（本轮取前者） |
| 8 | 置顶/归档在 v4 命令面缺失：数据在 tasks-index.sqlite（读面 listPinnedTaskIds），未见对外 pin/archive 写命令 | setPinned/setArchived 实现为本地 UI 态；写面待与桌面团队核实（本轮仅确认读面，如实标注含糊）。**v1.4 修正：该判断不成立**——M1.y 对 packages/services 参考盘点发现写面存在于 zcode-task 频道：setTaskPinned/setTaskUnread/archiveTask（zcodeTaskService.ts:666-695，session 类），已按双写落地（§8.3），v1.2 调研面未覆盖该频道所致 |
| 9 | 无 REST/JSON 业务 API：/api/* 仅 4 类非业务端点，全部业务在 WS channel RPC | 不自建 REST 网关翻译（双倍维护，不建议） |
| 10 | Bot 绑定码体系是「聊天账号↔工作区」绑定，不是 WS 接入凭据 | 不复用作 iOS 登录凭据 |
| — | 调研阶段未运行任何构建/测试验证（只读源码调研，协议形状以 zod schema 与注释为准） | 以替身服务器端到端测试（§6.7）+ 端到端联调（§14）补验证 |

### 6.4 登录设计（spec v2.4 第 9 章 + 专项稿 18 屏）

设计基准由 v2.3 升至 **v2.4**：唯一改动是新增第 9 章「登录与连接」，配套新增加密专项稿 [design/login-design.html](../design/login-design.html)（18 屏 + 画布头部双路径流程模型，102 处 testid/95 唯一，实测核验与 spec 9.6 一致）。经三轮评审修订（8 条意见逐条修复），要点：

- **双路径流程模型**（画布头部）：A1 授权入口（ZAI `{origin}/api/oauth/authorize`，A2 端点行含 redirect_uri）→ A 应用内 Sheet → 回调拦截 → 令牌交换 → 成功/失败；B L1 凭据传递（回连/剪贴板/扫码/手动）→ L2 发现+鉴权 → WS → v4 握手 → workspaces[0]。
- **O 系（账号层）**：O1 登录主页（双路径入口 + BigModel 高级选项折叠卡）；O2-A 应用内授权 Sheet（✕ 40×40 视觉/≥44 命中、2.5px 进度条、`.wv-page` 以虚线胶囊标注「**真实授权页由 chat.z.ai 提供，外观与按钮不可控**」）；O2-A-M BigModel 变体；O2-B 回调拦截与授权中（四步进度 + oauth.log 掩码 + 60s 无响应引导）；O3-A 成功（displayName/avatarUrl/tokenSet 摘要）；O3-B 失败/取消（主卡为**用户取消态**中性文案，`error=access_denied` 归位授权服务器 error 分支；五态对照含 Keychain）。
- **L 系（连接层）**：L1 默认连接页（最近连接回连 + 剪贴板横幅 + 扫码/手动）与 L1-K/L1-S/L1-S-D/L1-H 变体（键盘态/扫码取景器/相机权限拒绝逃生口/帮助页含本地网络权限预说明）；L2 连接中五步进度与 **L2-N 免鉴权变体**（`--no-token` 部署：第 2 步「免鉴权」直接打勾，不出现「校验通过」，WS 升级无 token 段）；L3 失败/重试（401 样张 + 四态错误对照含本地网络权限检查项）；L4/L4-B 服务器与账户（账户三形态：已登录/未登录/**已过期 `l4-b-card-expired`**——expiresAt 过期/401 → 重新登录，不做静默续期）；L5 服务器详情 + L5-S 危险操作二次确认。
- **注入边界固化（spec 9.7.10）**：ASWebAuthenticationSession/WKWebView 两载体**均禁止注入自绘同意页**（钓鱼反模式）；视觉/E2E 断言只针对 Sheet 容器与 chrome；授权页外观不可控、深色「跟随系统」而非「跟随应用」。
- **评审修复 8 条（P1–P8）**：远端授权页口径、A2 补 redirect_uri、O3-B 取消态、四态改五态、L1-S-D 手输逃生口统一 `l1-s-btn-manual` 并确立跨帧共用选择器口径、令牌纪律（.flow-step-hl/.scrim/额度条渐变端点 var(--accent-press)→var(--accent)）、键盘标注 pill 10→10.5px 与文字链接热区 43.8→45.8px、新增 O2-A-M/L2-N/L1-H/L4-B 过期样例四帧。自包含检查（MISSING none）、HTML 标签闭合零错误、Chrome 无头渲染逐板确认通过。
- **未做（设计阶段已声明）**：真机渲染、辅助功能审计、Light 逐板回归。

### 6.5 Pixso 同步结果：pushed = false（如实记录）

将 design/login-design.html（**164,385 字节，M1 迭代一时点值**——v1.5 二次修订实测现网 **178,820 字节**，M1.x 品牌改稿内嵌 SVG 所致，与 §3.2 行数变化同因）经 MCP `code_to_design` 粘贴至 Pixso（file_key=AbZPdSg5AO6CqRDpr5RKwg，390×844），返回成功（画布节点 **9:1**，body 高 21,771px，18 屏内容完整在节点树内）。

- **字体：通过**——705 个文本节点，字体族 HarmonyOS Sans SC(391)/JetBrains Mono(159)/Noto Sans SC(30) 均在可用列表，截图无缺字/豆腐块（Noto Sans SC 为转换器替代选择，视觉一致；该插件面无逐区间缺失检查方法，结论基于字体族可用性比对+截图目检）。
- **渲染：不通过**——对 705 个文本节点按同父两两 bbox 相交审计（248,160 对）发现 **86 对文本叠印**，模式一致：行内 `<code>` 片段被转成带前导空格的独立文本节点并与正文文本框相撞（视口内两例实锤，含「chat.z.ai/api/oauth/authorize」与说明文字完全叠印、`zcode --web` 命令行跨行错位）。属转换器系统性产物，对 21,771px 文档全量重排风险大且超出本步范围，未做手工修复。
- **结论与处置**：按「仅当粘贴且截图校验都成功才 pushed=true」的约定**不标成功**；画布留存节点 9:1 供查看或删除；**实现与验收一律以工作区 [design/login-design.html](../design/login-design.html) 为准**，不受本步影响。截图存 /tmp/login_design_check.png（工作区外）。

### 6.6 实现范围（M1 迭代一交付，代码位于 [ios/ZCodeMobileApp/Sources](../ios/ZCodeMobileApp/Sources)）

新增/改动 17 个源文件（App 源码 25→42 个，实测），分层清单见 §4.4 表。按用户可感知面归纳：

| 域 | 交付 |
|---|---|
| 登录域 UI（Features/Login/） | LoginFlowView.swift + OAuthResultView.swift：O1 登录主页（o1-btn-oauth/o1-btn-connect/o1-card-adv+o1-row-bigmodel/协议脚注）、O2-A 应用内授权 Sheet（WKWebView 容器：✕ 44 命中、域名行、2.5px 进度条、下拉关闭=用户取消；WKNavigationDelegate 拦截 redirectUri 前缀匹配回调即 cancel 不离开 App；一次性 state `SecRandomCopyBytes` 发起前暂存；重试重置 state）、O2-B 授权中（四步进度 + 回调参数卡 code 掩码 + oauth.log 终端条一律 \*\*\* + 60s 无回调兜底）、O3-A 成功（displayName/avatarUrl 含 base64 解码/tokenSet 掩码摘要）、O3-B 失败取消（用户取消中性文案、红描边错误卡、五态对照、重新登录/改用 BigModel/跳过连接） |
| 连接域 UI（Features/Connect/） | ConnectFlowView/ScanView/ConnectingView/ServerViews：L1（最近连接卡+中性探测点 1.5s、剪贴板横幅一键填充不自动连接、扫码/手动主次 CTA、ws:// 无加密安全脚注）、L1-H 帮助页（mono 命令卡+44px 复制+二维码为仓库外能力说明+本地网络权限预说明）、L1-K 手动表单（链接拆解、令牌可选+明文切换+末 4 位掩码、内联校验）、L1-S 扫码取景器（AVCaptureSession QR，四角括号+扫描线，识别失败内联红字，成功触觉反馈）与 L1-S-D 权限拒绝引导、L2 五步进度（含免鉴权变体）+取消双入口立即中断、L3 失败（错误卡红描边+mono 错误码+token 掩码、主 CTA 随错误类型切换、四态对照）、L4-B 服务器与账户（账户三形态含过期卡、退出登录二次确认仅清账户层、服务地址/令牌编辑、连接测试 1.5s 仅探测）、L5 服务器详情（server-info 只读回显、工作区单选默认 workspaces[0]、能力 chips、令牌与安全说明、删除二次确认） |
| 传输与连接服务（Services/） | RPC 三件（RPCSerialization/ChannelClient/V4Wire，§4.4）、ZaiOAuthProvider、KeychainStore、ZCodeServerConnection（五步状态机）、ConnectURLParser、AppSession（装配状态机+两层凭据独立+QA 启动参数） |
| 真实 Store（Stores/Remote/） | RemoteConversationStore（sessions-index 订阅→会话列表投影、会话行模型→ChatMessage、row.delta 逐字、pendingInteractions→提问卡、rowsRangeV4 历史 limit 200、sendText/createSession/resolveInteraction）、RemoteTaskStore（listTaskList、approve/reject 优先 resolveInteraction 回退 respondPermission、stopGeneration、轨迹投影）、RemoteFileStore（readdir 3 层、readTextFile、git.getChanges/getDiff 行型四类映射） |
| 装配与既有页面（不推翻既有结构） | ZCodeMobileApp.swift 注入 AppSession + fullScreenCover 流程容器 + 按 `task(id: mode)` 驱动 Store 切换（connected→Remote 三件套，其余→Mock 回退且每次重建，与既有 e2e 前提一致）；RootView 仅新增连接状态横幅；SettingsView 仅新增「服务器与账户」分组与页脚数据源提示；AppRouter 仅新增 2 个路由值 |
| 工程配置 | project.yml + Info.plist：`zcode` URL scheme、NSLocalNetworkUsageDescription、NSCameraUsageDescription、ATS `NSAllowsLocalNetworking`（§4.2） |

**按 gaps 降级实现的部分（如实标注）**：置顶/归档（setPinned/setArchived）与逐文件批准（setFileDecision/approveAll）为**本地 UI 态**（当时判断服务端无写命令面，gap 7/8）；retry(taskID) 因 retryTurn 需 rowId 级 target 而实现为安全 no-op（仅刷新列表）；「刷新额度」为占位（额度接口不在本轮调研面）；断线重连后 Store 全量重建重新订阅（replayable 语义下正确但非最优，水位 resync 未实现）；conversationRowsRangeV4 仅拉尾窗 200 行，hasMore 向上翻页入口未接；KeepAlive/Ack 帧解析时忽略（长时间空闲靠重连横幅兜底）；SettingsStore 保持本地 UserDefaults 版（需求未要求接服务端 setting 频道）。**v1.4 更新**：上列欠账中**五项已在 M1.y 兑现**——置顶/归档双写（gap 8 判断修正，§6.3 注）、刷新额度（usage-stats 两只读接口，§8.3）、水位 resync（丢帧自愈，§8.3）、历史向上翻页（loadOlder，§8.3）、断线重连横幅消费链路中的订阅恢复经 resync 承载（**自动重连/退避仍缺**，§12 风险 8）；逐文件批准（gap 7）维持本地 UI 态（本轮对照再次确认服务端无逐文件批准接口，conversationFileChangesV4 只补会话维度只读 Diff），retry 与 KeepAlive/Ack 仍未接（§12 风险 8）。

### 6.7 门禁结果（阶段自报，未复核）

| 门禁 | 结果（阶段材料口径） | 阶段执行记录 |
|---|---|---|
| iOS 构建 | **通过 = true**（自报） | `xcodegen generate --spec ios/project.yml --project ios` → Created project；`xcodebuild -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -sdk iphonesimulator build` → **BUILD SUCCEEDED**（且为**最终代码状态复跑通过**）；`build-for-testing` → TEST BUILD SUCCEEDED |
| e2e | **通过 = true**（自报） | 按阶段材料口径，完整测试套件（含登录 e2e 11 用例）**由统一门禁执行**；实现阶段自检为**编译级**（build-for-testing）+ 三项补充验证（下）。**「通过」的采信边界与 test06 的判定口径见本节如实声明 ③** |
| 补充验证 ① | 二进制序列化字节级交叉验证 | jsc 运行按 serialization.ts 转录的 TS 参考生成 8 组向量，与 swiftc 编译的 Swift 移植对照：数组/整数 VQL/undefined/单键对象**字节完全一致**（v1_requestHeader 两侧同为 `040406640600010b7a636f64652d6167656e74011368656c6c6f436f6e766572736174696f6e5634`）；多键对象仅 JSON 键序与 `/` 转义差异（RFC 8259 合法，服务端 zod 按键名解析无影响）；负数 VQL 回归通过 |
| 补充验证 ② | 模拟器冒烟（独立 iPhone 17 模拟器，未动用户 booted 设备） | 冷启动直接进演示模式会话列表正常（既有行为不变）；`-ZCodeOpenLoginFlow` 直开 O1 渲染正确；`-ZCodeOpenConnectFlow` 直开 L1 渲染正确（首启隐藏最近连接卡符合设计稿） |
| 补充验证 ③ | 并发与现场纪律 | 开工/收尾两度核实 ios/ 无并行进程持句柄；冒烟用独立模拟器并已 shutdown |

**如实声明**：①本报告撰写会话**未复跑**任何构建与测试；②工作区实测仍**无门禁脚本与 xcresult**（v1.3 撰写时 `find . -path ./.zcode -prune -o \( -name '*.sh' -o -name '*.xcresult' -o -name 'Makefile' \) -print` 复跑仍零命中）——§12 风险 1 的整改要求对本轮门禁**同样适用**；
③**test06 判定口径（v1.2 二次修订按代码实况明确）**：test06_pairingSuccessConnectsAndLoadsStubData（ios/Tests/ZCodeMobileLoginE2ETests.swift:361-429，已逐行核对）为「数据面强断言 + UI 断言」混合，**并非改写为纯数据面验证**——
　· 数据面强断言：替身收到正确令牌且 WS 升级 ≥1（:370-372）、conversation 增量事件 ≥1（:412-414）、sessions-index 订阅与快照事件 ≥1 且 topic 前缀匹配（:421-424）；
　· UI 强断言：连接流程自动收起、无失败横幅（:373-376）、设置页服务器行显示替身名「E2E Stub Desktop」与「已连接」、数据源切「已连接」（:384-391）、createSession 回执推入会话页 + 会话历史来自替身 conversationRowsRangeV4 + 发送后收到替身增量帧回执（:403-411）；
　· **唯一的降级断言**：返回会话列表后的列表内容断言写成「替身会话行 `04-row-sess-e2e-1` **∨** 远端空态 `04-empty`」二选一（:425-428；代码注释 ：416-417 明确因果：ConversationListView `.task(id:0)` 固定订阅初始 mock store 实例，替身快照早于列表绑定远端时呈现空态）——即「替身会话行渲染为列表行」本身**未被断言**，此即本用例已知缺口的准确边界；
　· **判定标准**：「11 用例通过（自报）」中的 test06 按**上述改写后断言集**判定通过与否；若评审要求「替身会话行渲染为列表行」的强断言，须先修复列表绑定（`.task(id:0)` 固定订阅初始 mock 实例）再补断言，已列入迭代二整改清单（§14.1 M1 行）。**v1.3 更新：该整改已在 M1.x 迭代闭环**——列表订阅绑定改为 `.task(id: ObjectIdentifier(store))`（ConversationListView.swift:70 实测），test06 返回列表后的断言升级为**强断言**：替身行 `04-row-sess-e2e-1` 必须上屏（ZCodeMobileLoginE2ETests.swift，M1.x 时 :438-440，v1.5 实测现网 :406/:458-463，**v1.6 二次修订复测现网 :413/:470**——M2 加厚登录套件后再度漂移，§7.2 勘误·续二）且点击推入只读详情（彼时 :442-444）；
④未对真实 `zcode --web` 服务与真实 chat.z.ai OAuth 端点做过任何端到端验证（需真实桌面环境与真实账号交互，列入 §14.2 第 2 项）。

### 6.8 按绑定假设实现的部分与待后端/部署方确认项

以下 8 项**无仓库依据或依据不足**，按假设实现并在代码注释标注；前 4 项需后端/部署方确认后方可冻结：

| # | 绑定假设 | 现状与覆盖手段 | 待确认 |
|---|---|---|---|
| ① | OAuth 回调 redirect_uri = `zcode://oauth/callback`（已注册 `zcode` scheme，project.yml:49-52 实测；spec 侧对应条款号为 design-spec 9.7.7，design-spec.md:495 与 project.yml:48 注释均以「绑定假设 9.7.7」指称——注意与本表 ①–⑧ 编号是两套体系） | web 端用 webShareCallbackUrl（zcodeEndpoint.ts:62，转引）；移动端 scheme 名为假设 | 是否改 Universal Links；与部署方对齐 redirect_uri 白名单 |
| ② | client_id 打包内置取样例值 `client_P8X5CMWmlaRO9gyO-KSqtg`（实测 webZaiOAuthConfig.ts:58） | web 端来自 `VITE_ZAI_OAUTH_CLIENT_ID`，移动端打包值与可否用户覆盖均未定义；已留 `-ZCodeOAuthClientID` 启动参数覆盖 | 移动端正式 client_id 的发放与轮换策略 |
| ③ | 令牌交换 origin 假设 `https://zcode.z.ai` | web 端 tokenUrl 是相对路径 `/api/v1/oauth/token` 挂部署 origin（zcodeEndpoint.ts:3，转引）；已留 `-ZCodeOAuthTokenOrigin` 覆盖 | 移动端直连的令牌端点域名 |
| ④ | BigModel origin 缺省 `https://bigmodel.cn`、appId 缺省 `zcode` | 按 webZaiOAuthConfig.ts:40-43,61（转引）口径实现 | BigModel 移动端入口是否开放 |
| ⑤ | 桌面端「出示连接二维码」为仓库外包装（仓库仅打印 URL） | MVP 兜底=剪贴板一键填充+手动输入已实现；扫码（L1-S）按自渲染二维码目标实现 | 桌面端是否补二维码展示包装 |
| ⑥ | 置顶/归档与逐文件批准无服务端命令面 | 实现为本地 UI 态（跨设备不同步）。**v1.4 更新**：置顶/归档写面已确认存在（zcode-task.setTaskPinned/setTaskUnread/archiveTask，session 类）并双写落地，跨设备同步待真实服务端联测；逐文件批准仍无接口（gap 7 维持本地态） | 桌面团队确认逐文件审批产品口径（gap 7）；双写投影在真实服务端的验证（§8.7） |
| ⑦ | retry(taskID) 降级为 no-op | retryTurn 需 rowId 级 target，会话行上下文缺失 | 会话页重试交互接 rowId 后恢复 |
| ⑧ | 「刷新额度」占位 | 仅触发过期判定重算。**v1.4 更新**：已接 usage-stats.getCodingPlanUsageSnapshot/getCodingPlanResetStatus 两只读接口（session 类），SettingsView 用户卡连接态绑真实 quota（§8.3）；accountAccess 请求形态按参考 schema 固定个人 coding-plan 形态，真实服务端若拒绝则回退演示值（§8.7） | accountAccess schema 在真实服务端的运行期验证（§14.2） |

### 6.9 应用内授权的安全取舍（RFC 8252 vs 内嵌 WebView，如实记录）

- **产品决策**：用户已决策**应用内完成授权**（不跳系统浏览器），设计稿 v2.4 二次修订落稿（O2-A 内嵌浏览器视图）。
- **标准基线**：RFC 8252（OAuth for Native Apps）推荐系统浏览器或 ASWebAuthenticationSession——凭据不经过 app 进程、可复用浏览器 SSO Cookie、授权页面不被 app 篡改。
- **本版取舍的代价（如实列出）**：①凭据路径在 app 内；②无浏览器 SSO（已登录用户需重新输密码）；③授权页深浅由远端页面与系统决定而非 App 设置（v2.4 三次修订已把「深色跟随应用」降级为「跟随系统」，待真机验证）。
- **缓解措施（已实现）**：①redirect 拦截**仅在 redirectUri 前缀匹配时** cancel 导航（不加载回调页、不离开 App）；②state 一次性（`SecRandomCopyBytes`）+ 发起前本地暂存 + 回调严格比对，缺失/不匹配即失败**不降级**；③**不做任何 JS 注入**截取页面内容；④错误回显与日志中 code/token 一律 `\*\*\*` 掩码；⑤回调面以 redirectUri 前缀匹配收敛；授权过程中的中间跳转域**不过滤**——授权服务器可能经自有域 302，过滤过严会破坏流程（此为实现取舍，如实记录）。
- **设计侧固化（spec 9.7.10）**：两载体（ASWebAuthenticationSession/WKWebView）均**禁止注入自绘同意页**（钓鱼反模式）；视觉/E2E 断言只针对 Sheet 容器与 chrome；O2-A 页内区域以「远端授权页示意」口径标注。
- **残余风险**：真实 chat.z.ai 授权页在应用内 WebView 的兼容性（风控/验证码/CSP 对内嵌 UA 的策略）**未验证**（§12 风险 13），已列为端到端联调第一项中的人工验证点。

## 7. M1.x 迭代：品牌迁移（BiuZ）与移动端只读边界（v1.3 新增章节）

本章记录 M1.x 迭代全部产出：品牌迁移的范围与保留项（7.1）→ 只读边界的协议证据分类（7.2）→ 边界复核纠错记录（7.3）→ 实现清单（7.4）→ 门禁结果（7.5）→ 全面验收清单（7.6）→ 布局走查（7.7）→ 已知限制（7.8）。证据来源：①品牌盘点（branding/naming.md 通读 + 品牌资产 `file` 实测）；②协议只读边界专项调研（浅克隆 `git clone --depth 1 --single-branch https://github.com/zai-org/ZCode /tmp/zcode-api-readonly`，对服务端/CLI 侧只读精读，未运行其构建/测试）；③边界复核（对调研结论逐条下钻至 Core admission/进程创建层，3 处纠错，见 7.3）；④本工作区实现与测试材料；⑤本报告撰写会话对工作区的实测核验（本节标注「实测」的 file:line 均经本轮 Read/grep 逐行核对；克隆件内行号为调研/复核会话记录，与本报告其余 /tmp 克隆件同口径管理，见 §12 风险 6）。

### 7.1 品牌迁移：替换范围与保留项

**定名与合规依据**（branding/naming.md，72 行，已通读；行号转引品牌盘点材料）：定名 **BiuZ**（App Store 显示名 BiuZ，naming.md:10）；「ZCode」是 z.ai 产品商标，直接上架有商标侵权与审核风险，「××Code」还撞 Apple 的 Xcode（naming.md:4、:40）；商店元数据（名称/副标题/截图/关键词）不得出现 ZCode、Z.ai、Zai 字样与官方素材，社区版身份靠 works-with 式描述性文字立足（naming.md:14、:69）；应用内第三方服务功能性引用可保留（naming.md:70）；上架前仍需中国商标网 9/42 类核查「BiuZ」「Biu」家族（naming.md 合规清单第 6 条——人工流程，本轮未执行，列入 §14.2 第 5 项）。

**BiuZ 品牌资产**（branding/，本轮新增；尺寸/颜色通道经 `file` 命令实测）：

| 资产 | 实测规格 | 用途 |
|---|---|---|
| logo/biuz-icon-1024.svg | 方形全出血、无透明，圆角由系统裁切 | App Store 提审图标源稿 |
| logo/png/appicon-1024.png | 1024×1024，8-bit RGB **无 alpha**（实测），满足 App Store 1024 图标「无透明」强制要求 | AppIcon 主文件（已入 Assets.xcassets，md5 与工程内副本一致 = f2108efa2f932785ebd7739a080cf7c3，实测） |
| logo/png/mark-960.png | 960×960 RGBA 透明底单标 | 登录页 84px / 连接页 76px 品牌位切图源（@1x 84 / @2x 168 / @3x 252 已切） |
| logo/biuz-mark.svg、biuz-mark-card.svg、biuz-lockup-dark/light.svg | 纯矢量路径不依赖字体 | 单标/卡片/横版字标（深色副标题「手机上的 AI Agent 遥控台」） |
| logo/png/ 其余（appicon-180、icon-preview-512、lockup-dark/light、mark-card-840） | 实测 180 RGB / 512 RGBA / 1100×560 RGBA ×2 / 840×700 RGBA | 文档与 README 渲染用；appicon-180 在单尺寸 appiconset 方案下非必需，仅兼容旧 Xcode 传统全尺寸集 |

**用户可见层替换清单（已全部落地，均实测核对；行号为 v1.3 时点值——M1.z 加厚后 LoginFlowView/ConnectFlowView/AppSession/E2ELoginStubServer 多处锚点漂移，现网值见 §7.2 勘误续，如 o1-brand-name :309→:321、ZCodeBrandMark :398/:402→:408-410/:414、ConnectFlowView 权限文案 :339→:371、AppSession -ZCodeE2EResetState :103→:130/:134、替身 302 :252→:436）**：

| 文件:行（实测） | 改动 |
|---|---|
| ios/project.yml:3 / :26 / :37 / :50 | bundleIdPrefix `cn.zcode`→`cn.biuz`；PRODUCT_BUNDLE_IDENTIFIER `cn.zcode.mobile`→`cn.biuz.mobile`；CFBundleDisplayName `ZCode`→`BiuZ`；CFBundleURLName→`cn.biuz.mobile.oauth`（UITests bundle id 显式值同步为 `cn.biuz.mobile.uitests`，project.yml:69；改后重跑 xcodegen，pbxproj 复验 cn.zcode 0 处 / cn.biuz 4 处） |
| ios/ZCodeMobileApp/Info.plist:7-8/:33-34/:41-42 | 显示名 BiuZ、URLName、本地网络权限文案「用于查找并连接同一局域网内的桌面端 Agent 服务（ZCode 社区版桌面端）」——与 project.yml properties 同源（XcodeGen 生成后复验一致）；NSCameraUsageDescription 无品牌字样不动 |
| ConnectFlowView.swift:339 | 权限预说明与 Info.plist:42 新措辞**逐字同步**（冒号+引号引用系统弹窗原文），避免 App 内文案与系统弹窗不一致 |
| LoginFlowView.swift:102 / :304-309 / :395-402 | 导航标题「登录 ZCode」→「登录 BiuZ」；O1 品牌区 `Text("ZCode")`→`Text("BiuZ")`（新增可测锚点 o1-brand-name :309）；品牌标渲染本体由自绘 `Text("Z")` 渐变方块改为 `Image("BiuzMark")` 资产切图（:402；结构体名 ZCodeBrandMark 属内部符号保留，84px/76px 品牌位共用自动生效） |
| ConnectFlowView.swift:130/:132/:135 | 76px 品牌位同步；brand 区文案改 BiuZ；副文案改「连接桌面端 Agent 服务（ZCode 社区版），开始遥控与审批」（works-with 口径） |
| ScanView.swift:130 | 相机指路「系统设置 → ZCode」→「系统设置 → BiuZ」（显示名改后系统设置条目名就是 BiuZ，不改会指错路径） |
| SettingsView.swift:15-22 / :24-28 | 页脚身份行「ZCode for iOS」→「BiuZ for iOS」（保留 12-foot-data-source）；新增独立品牌名行「BiuZ · 为 ZCode 社区版桌面端打造的移动遥控台」（accessibilityIdentifier `12-brand-name`，works-with 表述，供 e2e 断言） |
| KeychainStore.swift:16 | service 常量 `cn.zcode.mobile`→`cn.biuz.mobile`（注释 :10-15 写明影响面与 E2E 不受影响的依据） |
| design/design-spec.md:1/:453/:469 与 design/login-design.html、zcode-mobile-design.html | 规范标题改「BiuZ 移动端客户端 · 设计规范」；两处权限固定文案与 Info.plist 逐字同步；两份 HTML 的 title/头注释/kicker/h1/「登录 ZCode」改 BiuZ，自绘「Z」渐变方块替换为内嵌 biuz-mark SVG（单文件自包含：登录稿 5 处 = 84px×3+56px×1+76px×1，主稿 1 处 84px 品牌方块）；主稿 App 版本页脚改「BiuZ 1.0.0 (1)」（对齐 MARKETING_VERSION 1.0.0 / CURRENT_PROJECT_VERSION 1，project.yml:28-29），主稿桌面 Host 版本行「ZCode 1.2.0」为桌面端指称保留 |
| docs/立项报告.md 与 docs/project-proposal.md、branding/naming.md | 两份文档同步品牌化修改（diff 实测逐字节一致）；naming.md 合规清单第 2 条的 bundle id 候选值统一为 `cn.biuz.mobile`（消除与 cn.biuz.app 的双候选） |

**图标落位**（Assets.xcassets 为本轮新建，实测目录结构）：迁移前现状为「build 设置已声明、资产缺失」——`find ios -name "*.xcassets"` 零命中，而 pbxproj 已写有 `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`，装到设备上是系统默认图标。落位：①新建 `ios/ZCodeMobileApp/Resources/Assets.xcassets`（根 Contents.json `{"info":{"author":"xcode","version":1}}`）；②`AppIcon.appiconset/` 采用 iOS 12+/Xcode 14+ **单尺寸声明**（Contents.json 仅 1024×1024 universal/ios 一条，实测），放入 appicon-1024.png（无 alpha，满足强制要求），全部切片由 Xcode 编译时自动产出；③`BiuzMark.imageset/` 由 mark-960.png 经 sips 切出 @1x 84 / @2x 168 / @3x 252 三档（`file` 实测 84/168/252 RGBA）；④project.yml sources 追加（:23）后重跑 `xcodegen generate`。

**保留项（三类，如实说明）**：

1. **内部符号层不动**：target 名 ZCodeMobile / ZCodeMobileUITests（project.yml:1/:60）、xcodeproj 文件名、PRODUCT_NAME: ZCodeMobile（project.yml:27）、代码标识符（ZCodeMobileApp / ZCodeServerConnection / ZCodeTabBar / ZCodeBrandMark 结构体名 LoginFlowView.swift:398 / 测试类名）——无用户可见收益，改动纯属搅动。
2. **上游开源项目仍为 ZCode，协议兼容如实说明**：本 App 是 ZCode 社区版桌面端的配套客户端，协议面标识必须与服务端/对端注册值逐字一致，改任一处即断连/断登录/丢数据——URL scheme `zcode` 与 redirect_uri `zcode://oauth/callback`（Z.ai OAuth 服务端注册值；绑定假设为 design-spec 9.7.7 条款，对应 §6.8 假设①；Info.plist:37、替身 302 E2ELoginStubServer.swift:252 实测）、OAuth appId 缺省 `zcode`（ZaiOAuthProvider.swift:90）与 token 字段 `zcodeJwtToken`（结构体声明 :22、令牌解析 :215；v1.3 首版误引 :90-91 为 token 字段行——:91 实为 redirectURI 缺省值，独立审阅指出后更正）、RPC 频道 zcode-agent / zcode-task、clientId `"zcode-mobile"`（RemoteConversationStore.swift 信封，v1.3 时 :460-474，M1.y 加厚后现网 :608-612——v1.4 二次修订复测更正）、线协议标签 `__zcode_rpc_nested_uint8array_v1`、UserDefaults 键 `zcode.settings.v1`。产品身份以 works-with 描述性文字表达（SettingsView.swift:24「BiuZ · 为 ZCode 社区版桌面端打造的移动遥控台」），不构成商店元数据联动。
3. **功能性 CLI 与演示/测试数据**：`zcode --web` 终端命令与排障文案（用户必须在桌面终端真实运行才能配对，改写会导致无法照做，naming.md:70 口径）；Mock 演示工作目录 `~/work/zcode`、E2E 替身 `/Users/e2e/zcode-workspace` 等（指桌面端真实工作区，非本 App 品牌，保留）。

**bundle id 迁移影响面（如实）**：①Keychain——service 常量已同步改，旧凭据（oauth.tokenSet.v1 / oauth.userInfo.v1 / server.configs.v1）对新 service 不可见，本机需重新 OAuth 登录与重新配对桌面端；可接受，工程未上架、无外部存量用户（预期一次性成本，详见 §12 风险 16）。②测试不受影响：每用例以 `-ZCodeE2EResetState` 主动清空凭据态（AppSession.swift:103 实测）、stub 地址经 launchArguments 注入与 bundle id 无关、替身 302 回调与保留的 scheme 一致、TEST_TARGET_NAME 指向的 target 名未变。③App Store Connect 需以 `cn.biuz.mobile` 新建 App 记录，旧 cn.zcode.* 记录不可复用。

### 7.2 移动端只读边界：协议证据分类（readonly / session / execution）

**调研方法与关键架构事实**（/tmp/zcode-api-readonly 克隆件精读）：同一个 RPC 命令面经 clientMode 分两条链路——`GET /ws` 以 clientMode=web-remote-replayable 建立通道（手机/恢复链路，packages/server/src/http.ts:325-335），`GET /ws/host` 以 desktop-continuous 建立（需 POST /api/rpc-host-capability 发放的一次性 capability，http.ts:321/:341-346）；两者 hello 能力集不同（zcodeAgentConnectionScope.ts:206-228），deliveryProfile 强制匹配（transport.ts:56）。**读链路**：全部订阅/查询方法统一走 `getReadOnlyClient`（zcodeAgentService.ts:4947/:5119/:5278-5285/:5494），该入口把 client entry 的 modelExecutionEnabled 置 false（:3097-3141）。**执行链路**：唯一执行入口是 `sendConversationCommandV4`（:5044），它改用 `getClient`（modelExecutionEnabled=true，:3037-3060）后把信封原样转发 CLI 的 v4/command，CLI 侧经 startPromptTurn → app → Core admission 进入 agent 循环（v4-gateway.ts:10、prompt-turn.ts:52-54）。**关键否定性结论**：scope 层对 web-remote-replayable 的命令面**没有任何收窄**——sendConversationCommandV4 仅校验握手与 clientId 归属后原样透传（zcodeAgentConnectionScope.ts:702-718）。即**协议/服务端并不阻止手机发执行类命令，「移动端只读取」是产品自律边界而非服务端强制**——因此移动端必须在 RPC 出口自查，这是本节分类表与 7.4 拦截实现的存在依据。

**分类口径**（已在 ReadOnlyGate.swift:7-16 文件头注释固化，实测）：execution = 触发 harness/agent 执行（凡经 startPromptTurn → Core admission、应答权限/反向请求、或写工作区文件）；session = 会话元数据/配置/队列账本写，不驱动 agent；readonly = 订阅/分页查询/附件读。

**readonly（订阅/查询，连接态放行）**：

| 命令/命令组 | 含义 | 证据（克隆件 file:line） |
|---|---|---|
| zcode-agent.helloConversationV4 / initializeConversationV4 | v4 握手两步，无业务副作用 | zcodeAgentService.ts:4901-4926；zcodeAgentConnectionScope.ts:668-683 |
| subscribeConversationV4 / unsubscribe / resync | 会话 topic 快照+增量；走 getReadOnlyClient，「订阅只启动 CLI，不提升写能力」；有 spawn CLI 进程的副作用（如实标注，见 7.8） | zcodeAgentService.ts:4939-5042（:4945-4947） |
| subscribeSessionsIndexV4 族 | 会话列表活性 | zcodeAgentService.ts:5494-5553 |
| subscribeWorkspaceConfigV4 族 | workspace 配置目录活性订阅 | zcodeAgentService.ts:5300/:5562+ |
| conversationRowsRangeV4 | 历史行分页（只读 query，超时重发安全） | zcodeAgentService.ts:5278-5295 |
| conversationPlansV4 / workflow run 事件/枚举/产物/transcript 查询族 | 计划目录与 workflow 产物只读分页 | zcodeAgentService.ts:5297-5345；transport.ts:344-372 |
| usageStatsV4 / conversationUsageV4 | 用量统计查询 | transport.ts:374-378 |
| queryConversationCommandsV4 | 命令 ACK 终态幂等回查 | zcodeAgentService.ts:5111-5121 |
| backgroundBashOutputV4 | 按任务授权的有界输出查询 | transport.ts:391-395；scope :726-730 |
| attachment 读族（Read/Stat/PreviewSource） | 附件内容与元数据分块授权读取 | transport.ts:382-389 |
| onDynamicConversationFrame / onDynamicSessionsIndexFrame | workspace 级下行帧流事件订阅 | zcodeAgentService.ts:5555-5557；transport.ts:406-409 |
| zcode-task.listTaskList | 任务列表聚合查询（tasks-index.sqlite 纯读） | channels.ts:94；zcodeTaskServiceAdapter.ts:2375-2381 |
| file.readdir / file.readTextFile | 目录列举 / 文本读取 | file.ts:24/:35 |
| git.getChanges / git.getDiff | 工作区变更状态 / 逐文件 patch（纯读） | gitService.ts:214-229 |
| REST GET /api/server-info | 服务发现探测（纯静态返回） | http.ts:320 + createServerInfo:169-185 |
| WS GET /ws（web-remote-replayable 通道本身） | 手机链路通道入口（通道只做传输；可调命令面分类见各行） | http.ts:325-335 |

**session（会话/配置管理面，不驱动 agent，连接态放行）**：

| 命令/命令组 | 含义 | 证据 |
|---|---|---|
| createSession（**不带** firstInput） | 创建空 draft 会话（不进 sqlite、不启动 turn）——移动端「新建空会话」即此形态 | command.ts:45；session-mgmt.ts:23-25/:78 |
| forkAssistant | 会话分支纯复制（stable resolver + conversation-only copy），不重跑 | fork-edit-retry.ts:6/:275-309（复核拆分，见 7.3） |
| renameSession / deleteSession / discardSharedContext | 重命名 / 关闭会话（非真删历史）/ 丢弃待导入上下文 | session-mgmt.ts:123-189 |
| switchModelConfig / switchCollaborationMode | 切模型配置 / 协作模式（改运行配置，无 startPromptTurn） | model-config.ts:76-166 |
| editQueueItem / reorderQueueItem / deleteQueueItem / setFollowupMode | 队列账本操作（**setAutoDrain 除外**——复核上调 execution，见 7.3） | queue.ts:71-129/:146-154 |
| snoozeInteractionAutoResolution | 推迟 AskUserQuestion 自动结束倒计时（幂等） | interaction-background.ts:65-79 |
| setAssistantFeedback | assistant 行点赞/点踩（元数据写） | assistant-feedback.ts:10-33 |
| toggleWorkspaceHookReviewItem / revokeWorkspaceHookTrust / requestWorkspaceHookReview | hook 审核配置面（**respondWorkspaceHookReview 除外**——复核上调 execution） | interaction-background.ts:96-139 |
| 附件上传事务 begin/chunk/commit/abort | 附件存储写（不驱动 agent；附件仅随 sendText/createSession 进入会话） | zcodeAgentService.ts:5123-5139；transport.ts:379-381「禁止 full-data RPC」 |
| setConnectionFlowStateV4 | 连接级流控，仅 trusted-host-relay 可调——手机（terminal-client）实际不可达 | zcodeAgentService.ts:4928-4937；scope 非 relay 直接 throw（:684-701） |
| REST POST /api/rpc-host-capability；WS /ws/host、/ws/remote/:id；POST /api/connect-remote | 一次性凭证与通道/连接管理面。⚠ /ws/remote/:id 桥接的 ITerminalService 是交互 shell（执行面），若移动端经它开终端即违反边界——iOS 未使用该端点（grep 无引用），仅作风险记录 | http.ts:321/:341-346/:418-442；server-core/http.ts:141 |

**execution（执行面：喂 agent / 应答权限 / 写工作区，连接态一律拦截）**：

| 命令/命令组 | 含义 | 证据 |
|---|---|---|
| sendText | 用户消息进入 agent 循环（start/queue/guide 三路 admission） | session-flow.ts:181-234 → startPromptTurn → prompt-turn.ts:52-64；服务端 zcodeAgentService.ts:5044-5108（:5102） |
| createSession（**携带** firstInput） | 建会话并立即启动首条 agent turn（与 sendText 同一写路径） | command.ts:46；session-mgmt.ts:27-28/:95-99 |
| createSelectionSideSession（带 firstInput） | 侧屏子会话带首条输入创建后立即启动 | selection-side-session.ts:29-50（:43）（复核拆分，见 7.3） |
| stop | 中断当前 agent turn | session-flow.ts:305-330/:440-441；adapter「session/stop → v4 stop」（zcodeTaskServiceAdapter.ts:2062-2096） |
| resolveInteraction | 应答挂起的权限/AskUserQuestion 反向请求，决定 agent 是否继续执行工具 | interaction-background.ts:45-63；interaction-registry.ts:159-190（broker 收口） |
| retryTurn / editUserQuery | rewind 截断该 turn 后重发（原文本/新文本）——重新喂 agent | fork-edit-retry.ts:4-5/:120-273（:373） |
| applyFileRewind | 工作区文件恢复到历史轮快照（写 workspace 文件） | command.ts:3-4；file-rewind.ts:13-27 |
| sendQueuedNow | 排队输入立即提升为执行（占用空闲位） | queue.ts:165-291（:247 startPromptTurn） |
| **setAutoDrain**（复核上调） | autoDrain=true 且 idle 时按 sendQueuedNow 原子路径立即提升排队输入 | queue.ts:131-144；v4-bridge.ts:1080-1082/:645-656 |
| sendGoalCommand / compact / pauseGoal / resumeGoal | 目标驱动轮 / 内建压缩轮 / goal 后台续跑的中断与恢复 | goal-compact.ts:56-118/:241-304/:371-427/:479 |
| cancelBackgroundWork / resumeWorkflowRun / startSavedWorkflow / amendWorkflowRunSettings | 取消/恢复后台 run、请 agent 启动已保存工作流/修订 run 配置 | interaction-background.ts:177-308；command.ts:221-243 |
| **respondWorkspaceHookReview**（复核上调） | 批准决定决定 workspace hook 外部命令进程是否放行（fail-closed 安全闸），与 resolveInteraction 同性质 | interaction-background.ts:88-97；workspace-hook-trust.ts:52/:188-189；configured-runner.ts:83-84 |
| zcode-task.respondPermission / respondElicitation / stopGeneration / compactSession / goalSession | 任务面权限应答/反向请求应答/停止——服务端均收敛为 v4 resolveInteraction / stop | zcodeTaskServiceAdapter.ts:2062-2096/:2208-2266；zcodeTaskService.ts:315-334 |
| git.stagePaths / git.commit | 工作区 git 写操作（不喂 agent，但属实际操作工作区的写面，移动端不保留） | gitService.ts:242-244/:301 |
| REST POST /api/bots/:provider(/:botId) | Bot webhook 回调：外部消息最终以 sendText 喂 agent | botsService.ts:4873-4875/:6300 |

**iOS 现网执行面调用点（改造前 6 处，全部处置）**：

| 调用点（改造前） | 执行类命令 | UI 入口 | 处置（实测） |
|---|---|---|---|
| RemoteConversationStore.swift `send()` | sendConversationCommandV4 → sendText | ChatViewModel.swift:16 `isReadOnly` → ChatView.swift:115 分支 | 方法停用为协议位 no-op（RemoteConversationStore.swift:482-488），连接态输入区整体替换为只读提示（ChatView.swift:130-146，id `05-composer-readonly`） |
| RemoteConversationStore.swift `answerQuestion()` | resolveInteraction{freeText} | ChatView.swift:76 消息回复 | 停用（:489-493） |
| RemoteConversationStore.swift `createConversation()` | createSession **携带 firstInput**（新建即启动首条 turn） | NewConversationSheet.swift「开始任务」 | 降级为**不带 firstInput** 的空会话创建（session 类 draft，:495-503）；新建 Sheet 连接态展示只读说明（NewConversationSheet.swift:91-118，id `03-readonly-notice`） |
| RemoteConversationStore.swift `resolveInteractionRaw()` | resolveInteraction{optionId} | RemoteTaskStore 权限应答复用 | 停用（:545-549） |
| RemoteTaskStore.swift `approve()/reject()` | respondPermission（收敛 v4 resolveInteraction） | ApprovalSheetView.swift 批准/拒绝 | 停用（RemoteTaskStore.swift:114-123）；连接态审批 Sheet 以只读提示替换批准/拒绝/授权范围（ApprovalSheetView.swift:197-212，id `06-readonly-notice`；演示态交互不变） |
| RemoteTaskStore.swift `stop()` | stopGeneration（转 v4 stop） | TaskOutputView.swift「停止任务」 | 停用（:125-128）；连接态隐藏停止入口（TaskOutputView.swift:201-213，id `07-act-stop-readonly`） |

**行号漂移勘误（v1.4 二次修订实测；M1.y 对 Store/连接层加厚实现后，上表 v1.3 时点行号已漂移，参数实质内容逐行核对仍属实）**：RemoteConversationStore.swift——订阅参数 :111-117→现网 :132-137、:191-196→:276-280、unsubscribe :136-140→:208、订阅失败空态注释 :127-128→:154（且该缺口已在 M1.y 以 listSessions 兜底缓解，§7.8-2 v1.4 注）、停用位 :482-493→:633-644、createConversation :495-503→:646、resolveInteractionRaw :545-549→:741；RemoteTaskStore.swift——approve/reject/stop/retry 停用 :114-134→:164-183；UI 锚点——ChatView 05-composer-readonly :130-146→:181、NewConversationSheet 03-readonly-notice :91-118→:118、ApprovalSheetView 06-readonly-notice :197-212→:212、TaskOutputView 07-act-stop-readonly :201-213→:292（MessageViews 05-questioncard :294、ConversationListView.swift:70 未漂移）。下文 §7.4/§7.8 引用同步按此勘误口径标注，v1.3 历史记录（§11.6 第三轮、附 F）保留彼时值。**（v1.5 二次修订注：本段「现网」为 v1.4/M1.y 后时点值；M1.z 加厚实现后再度漂移，见下段。）**

**行号漂移勘误·续（v1.5 二次修订实测；M1.z 对连接层/RemoteConversationStore/AppSession/ConnectFlowView 加厚——ZCodeServerConnection 654→807 行、RemoteConversationStore 926→944 行、AppSession 368→410 行——致上段「现网」值再度失效；独立审阅意见逐条实跑核对属实，本轮逐值复测确认，以下为 v1.5 时点现网值——**v1.6 二次修订注：M2 对 7 文件再度加厚后本段值再度漂移，现值见下方「勘误·续二」；无版本历史，漂移均为 M1.z 插入中继编排代码所致**）**：ZCodeServerConnection.swift——call 唯一出口与 ReadOnlyGate 拦截 :638-643→**现网 :786-797**（:638-643 现为帧流订阅 MARK 注释区；§9.4-1 同口径）、blockedExecutionCalls 声明 :254→**现网 :257**（:254 现为 clientId 声明）、subscribeFrameStreams :491-511→**现网 :644 起**、routeFrame :559-575→**现网 :712 起**（frameDropHandlers 触发 :722/:728、声明 :247；:621-625 现为 finishFailure）；RemoteConversationStore.swift——sessions-index 订阅参数 :132-137→**现网订阅 call :147**（M1.z 改为 handler 先于订阅注册，函数 :128 起；参数实质 topic+workspacePath 全参不变）、conversation 订阅参数 :276-280→**现网 :281-284**（参数实质 topic+sessionId 不变）、fallbackListSessions :162→**:167**、loadOlder :262→**:267**、reconcileViaReadSession :303→**:308**、停用位 send/answerQuestion :633-644→**现网 :638/:644 起**、createConversation :646→**:651**、promoteDeferredDraftSession :666→**:671**、setPinned 双写 :678-729→**现网 :683 起**（setArchived :701、markRead :719）、workspaceConfig :747→**:752**、modelSelectionView :809→**:814**、observeModelSelection :828→**:833**、resolveInteractionRaw :741→**:746**；RemoteTaskStore.swift **未漂移**（289 行不变，approve/reject/stop 停用位实测仍 :164-183）；AppSession.swift——-ZCodeE2EResetState :103→**现网 :130/:134**、连接成功后台拉取 :177→**:217**、refreshDesktopReadonlyInfo :185→**:225**、fetchDesktopOAuthInfo :197-232→**现网 :237 起**、fetchCodingPlanUsage :235-282→**现网 :275 起**、断开/失败清空 :353-354→**现网 :393 区**；UI/测试锚点——ChatView 只读分支（v1.2 所引 :115）→**现网 :136**（05-composer-readonly :181 未漂移，实测）、StoreProtocols.isReadOnly :16/:35-36→**现网 :16/:45/:91-92/:114**（:35-36 现为 loadOlder 默认实现区；Mock 两 Store `grep isReadOnly` 零命中，演示态 false 由协议默认实现承载——「Mock 恒 false」结论不变、引用行号更正）、ChatViewModel.isReadOnly :16→**现网 :23**（:16 现为注释）、LoginFlowView o1-brand-name :309→**:321**、ZCodeBrandMark :398/:402→**现网 :408-410/:414**（该文件现 **839 行**，v1.3 时点后膨胀原因未溯源，与 §5 行数差异注记同口径）、ConnectFlowView 权限文案 :339→**:371**、E2ELoginStubServer 302 回调 :252→**:436**；ZCodeMobileLoginE2ETests.swift——04-row-sess-e2e-1 强断言 :438-440/:442-444→**现网 :406/:458-463**、test12 :612-735→**现网 :641 起**、test13/14/15 :881/:998/:1166→**现网 :885/:1002/:1170**（与替身/登录套件各 +4 行的未溯源差异同源，§5 规模注记）。**参数实质内容逐行核对仍属实**（只读拦截链路、停用位、订阅全参、双写、只读提示锚点均在位）；未列条目按本段口径类推。

**行号漂移勘误·续二（v1.6 二次修订实测；M2 对 ReadOnlyGate/RemoteConversationStore/RemoteTaskStore/RemoteFileStore/ZCodeServerConnection/AppSession/NotificationService 等加厚改造——§5 v1.6 注——致上段 v1.5 时点值再度失效；独立审阅意见逐条实跑核对属实，本轮逐值复测确认，以下为 v1.6 现值口径）**：ZCodeServerConnection.swift——call 唯一出口 :786→**现网 :795**（`func call`）、ReadOnlyGate 拦截 append :794-795→**现网 :803**（blockedExecutionCalls.append；声明 :257 未漂移）、抛 ReadOnlyViolation :797→**现网 :806**（§9.4-1/§10.4 同口径）；AppSession.swift——-ZCodeE2EResetState :130/:134→**现网 :137/:141**、refreshDesktopReadonlyInfo :225→**:232**、fetchDesktopOAuthInfo :237 起→**现网 :244 起**、fetchCodingPlanUsage :275 起→**现网 :282 起**；StoreProtocols.swift——isReadOnly 具体实现 :45/:91-92/:114→**现网 :55/:129/:153**（:16 协议声明未漂移；「Mock 恒 false」结论不变）；MessageViews.swift——05-questioncard :294→**现网 :277**（§3.3 缺口 2 同口径）；MockConversationStore.swift——mock 标题 :276/:282→**现网 :288/:294**（§5/§9.3 同口径）；ZCodeMobileLoginE2ETests.swift——04-row-sess-e2e-1 强断言 :406/:458-463→**现网 :413/:470**（该锚点另有 :599/:666 等多处使用点，§6.7 v1.3 注同口径）；design/zcode-mobile-design.html——Tab 标注 :597-600→**现网 :613-616 与 :842-845**（02-tab-\*/04-tab-\* 两处 Tab 条，§3.1 同口径）。**参数实质内容逐行核对仍属实**；未列条目按本段口径类推。

**纵深防御三层（v1.3 落地）**：①**UI 层**——连接态 `store.isReadOnly`（StoreProtocols.swift:16/:35-36、ChatViewModel.swift:16；Mock 演示恒 false :30/:50——行号为 v1.3 时点值，M1.z 后现网 :16/:45/:91-92/:114、ChatViewModel :23、Mock 依赖协议默认实现，见 §7.2 勘误续）把发送/应答/审批/停止入口替换为只读提示（上表）；②**Store 层**——execution 类方法停用为协议位 no-op（上表「处置」列），不再下发任何命令也不做本地回显（避免「已发送」错觉）；③**API 层**——所有 RPC 经 `ZCodeServerConnection.call` 唯一出口，`ReadOnlyGate.inspect` 黑名单判定，execution 直接抛 `ReadOnlyViolation` 并留存最近 20 条拦截记录（ZCodeServerConnection.swift，v1.3 时 :514-531/:246，M1.y 后 :638-643/:254，**M1.z 后现网 :786-797/:257，v1.6 二次修订复测现网 :795-806/:257（call :795/append :803/抛错 :806，勘误·续二）**——v1.4/v1.5/v1.6 三次二次修订复测更正，§7.2 勘误续），即使 UI 层有遗漏入口也不会触达服务端执行链；未知命令放行避免误杀握手/订阅/查询链路（ReadOnlyGate.swift，v1.3 时 :66-67，现网 :183-185，同前勘误口径）。演示态不经过连接层，五条既有演示流程交互完全不变。

**保留的只读面（safeOps 摘要）**：server-info 探测、v4 握手、onDynamic 双帧订阅、sessions-index/conversation 订阅、历史分页、listTaskList、file.readdir/readTextFile、git.getChanges/getDiff、本地 UI 态（setPinned/setArchived/markRead、setFileDecision/approveAll、retry no-op）——以上全部保留；session 类的 renameSession 与不带 firstInput 的 createSession 可安全纳入移动端（后者已启用）。iOS 全量 RPC 出口实测仅 15 个 call + 3 个 listen（`connection.call`/`client.listen` 全量枚举），全部落入上述两类。**v1.4 更新**：M1.y 按 packages/client 参考盘点将连接态 RPC 出口扩至 **40 call + 7 listen**（9 频道，实测 `grep -rhoE 'call\(...|listen\(...' ios/ZCodeMobileApp/Sources` 全量枚举，清单见 §8.6），仍全部落 readonly/session 两类；本地 UI 态中的 setPinned/setArchived/markRead 已升级为 zcode-task 双写（§8.3），setFileDecision/approveAll 维持本地态（服务端无逐文件批准接口，本轮对照再确认）。

**调研 gaps（7 条，如实；⑦ 已于 v1.3 二次修订更正）**：①repo 内无官方移动远控客户端源码（packages/web 仅分享落地页/OAuth，0 处 RPC 调用点），「web-remote 实际发送的命令集合」无法从客户端代码列举，本次以服务端语义专项替代核实——结论：协议不阻止 web-remote 发执行命令，官方移动端是否自律收敛无代码可证；②desktop-continuous 侧 5 个 session 类命令未找到 renderer 直接发送点（协议+handler 均在）；③订阅类 readonly 有 spawn CLI 进程副作用（拉起桌面端 CLI 并冷恢复历史 Session，不提升写能力）；④zcode-server-cli core 形态 /ws 只暴露 zcode-agent channel，task/file/git 调用会失败而非越权（iOS 对接全量形态，实测直连 /ws）；⑤hook 审核组分类含主观口径（respondWorkspaceHookReview 已按复核上调）；⑥/ws/remote/:id 的 ITerminalService 执行面风险（iOS 未使用）；⑦~~iOS 订阅参数只传 topic、不满足服务端签名~~——**v1.3 二次修订更正：该缺口不成立**，现网代码 subscribeSessionsIndexV4 即传 topic+workspacePath（RemoteConversationStore.swift，v1.3 时 :111-117、现网 :132-137，注释引 zcodeAgentPluginParams.ts:8-11）、subscribeConversationV4 即传 topic+sessionId（v1.3 时 :191-196、现网 :276-280，注释引 zcodeAgent.ts:144-146）、unsubscribe 同步带 workspacePath（v1.3 时 :136-140、现网 :208，见 §7.2 勘误）；grep 证实全源码订阅调用仅此 4 处、无「只传 topic」调用点。调研稿所记为误记或改造前形态（工作区无版本历史，无法考证二者），现网实测仅存「订阅失败空态兜底会掩盖失败」这一软化缺口（§7.8-2）。

### 7.3 边界复核纠错记录（3 处，均已回写实现）

| # | 原分类（调研稿） | 复核结论 | 证据（克隆件亲读） | 落实 |
|---|---|---|---|---|
| 1 | setAutoDrain 随队列组归 session（「不立即启动 turn」） | **上调 execution**：autoDrain=true 且队列非空 + idle 时立即按 sendQueuedNow 原子路径把排队输入喂进 agent 循环 | queue.ts:131-144（afterLegacyStateMutation「queue_auto_drain_resumed」）；v4-bridge.ts:1080-1082 → autoDrainV4QueueIfReady；:645-656 队首就绪且 idle 直接 execute({type:"sendQueuedNow"}) → startPromptTurn | 列入 ReadOnlyGate execution 黑名单（ReadOnlyGate.swift:43，实测）；iOS 现网未调用该命令 |
| 2 | respondWorkspaceHookReview 随 hook 审核组归 session（调研稿自标主观） | **上调 execution**：批准决定直接决定 workspace hook 外部命令进程是否放行（fail-closed 安全闸），与 resolveInteraction 批准工具执行同性质；toggle/revoke/request 仅配置面维持 session | interaction-background.ts:88-97 → create-app.ts:926-938 → workspace-hook-trust.ts:52/:188-189 controller.respond；configured-runner.ts:83-84 把 admission.evaluateDispatch 注册为放行闸；runner-helpers.ts:18-34 | 列入黑名单（ReadOnlyGate.swift:44，实测，注释注明上调理由）；iOS 现网未调用 |
| 3 | createSelectionSideSession（带 firstInput）/ forkAssistant 合并条目归 execution | **拆分**：createSelectionSideSession（带 firstInput）维持 execution（firstInput 在场即 startPromptTurn，selection-side-session.ts:43）；forkAssistant 单独为 **session**（纯复制不重跑：不读 activeAbortController、不 stop parent、无 admission——防止被误杀，它本可安全保留） | fork-edit-retry.ts:6（文件头语义）/:275-309（实现仅 resolveStableForkTarget + forkStableConversation） | createSelectionSideSession 列入 firstInput 双态判定（ReadOnlyGate.swift:52、:107-112）；forkAssistant 落入 session 放行分支（:113-114） |

**复核其余确认（如实）**：appViolations 6 条与 safeOps 11 条逐行复核与 iOS 代码一致；iOS 全量 RPC 出口 15 call + 3 listen 逐一对入清单、无遗漏执行入口（设置页各分区均为静态占位数据，DiffReviewView 仅本地态，iOS 仅连 /ws、未触碰 /ws/host、/ws/remote/:id、/api/connect-remote、/api/rpc-host-capability）；execution 类全部下钻至 Core admission/进程创建层核实，readonly/session 类抽查确认无执行下游。**复核中发现并已随改造处置的缺陷**：RemoteTaskStore.respondPermission 兜底支路把 requestId 误传 optionId（必然 resolve 不中的幂等 no-op）——该兜底支路已随只读改造一并移除（RemoteTaskStore.swift:12 注释实测）。E2E 登录套件原经替身走 createSession（带 firstInput）+ sendText 全流程，连接态只读化已同步改造该流程与替身（替身加 execution 命令计数断言）。

### 7.4 实现清单

**品牌迁移**（工程配置 / Info.plist / 图标落位 / 视图层 / Keychain / 设计稿 / 文档，逐项见 7.1 表格，此处不重复）。

**只读边界改造**：

| 层 | 交付 |
|---|---|
| 新增文件 | Services/Remote/ReadOnlyGate.swift（116 行，App 源码 42→43 个实测）：命令三分类 + execution 黑名单 + firstInput 双态判定（7.2/7.3） |
| 连接层 | ZCodeServerConnection.call 改为全量 RPC 唯一出口并内建拦截（v1.3 时 :514-531，M1.y 后 :638-643，M1.z 后 :786-797，**v1.6 二次修订现网 :795-806**，见 §7.2 勘误·续二），blockedExecutionCalls 拦截记录最近 20 条（v1.3 时 :246、M1.y 后 :254、**M1.z 后现网 :257**） |
| Store 层 | RemoteConversationStore：send/answerQuestion/resolveInteractionRaw 停用（v1.3 时 :482-493/:545-549，现网 :633-644/:741，见 §7.2 勘误），createConversation 降级为不带 firstInput 的空会话（v1.3 时 :495-503、现网 :646）；RemoteTaskStore：approve/reject/stop/retry 停用（v1.3 时 :114-134、现网 :164-183），兜底支路缺陷一并移除（文件头注释） |
| UI 层 | 连接态只读提示替换四类入口（聊天输入区 05-composer-readonly / 新建 Sheet 03-readonly-notice / 审批 Sheet 06-readonly-notice / 停止任务 07-act-stop-readonly，行号见 7.2 表）；isReadOnly 经 StoreProtocols 协议暴露（:16/:35-36），Mock 演示态恒 false、交互不变 |
| 缺陷修复 | ConversationListView 列表订阅绑定 `.task(id:0)` 固定初始 mock 实例 → 改 `.task(id: ObjectIdentifier(store))`（ConversationListView.swift:70），连带把 v1.2 所列 test06 唯一降级断言升级为强断言（§6.7 v1.3 注） |
| 测试基建 | 替身服务器新增 git/task 读面计数器（gitReadCommandCount/taskReadCommandCount，E2ELoginStubServer.swift:77-104 实测，与 §7.6 第 2 项同口径）与只读应答数据（2 个变更文件 + unified patch + 2 条任务快照，:421-441/:524-602）；新增用例：登录套件 test12 连接态交互闭环（ZCodeMobileLoginE2ETests.swift:612-735）、常规套件 test06 品牌断言与 test07 演示列表操作闭环（ZCodeMobileE2ETests.swift:233-368）；布局走查套件 LayoutAuditTests.swift（494 行 8 用例，7.7） |
| 订阅参数核对（v1.3 二次修订补记） | 独立审阅指出调研稿 gaps⑦「iOS 订阅参数只传 topic」与现网代码不符：实测 ensureSessionsIndexSubscribed 传 topic+workspacePath（RemoteConversationStore.swift，v1.3 时 :111-117、现网 :132-137）、ensureConversationSubscribed 传 topic+sessionId（v1.3 时 :191-196、现网 :276-280）、unsubscribe 同步带 workspacePath（v1.3 时 :136-140、现网 :208，见 §7.2 勘误），全源码订阅调用仅此 4 处（`grep -rn "subscribeSessionsIndexV4\|subscribeConversationV4" ios/ZCodeMobileApp/Sources` 证实），无「只传 topic」调用点——该缺口已消除；残留事实为订阅失败的空态兜底（v1.3 时 :127-128 注释；M1.y 已加 listSessions 兜底缓解，现网 :154 注释）仍可能掩盖失败（§7.8-2 v1.4 注） |

**迭代会话自检记录（阶段材料口径）**：`xcodegen generate` → pbxproj 复验 cn.biuz 4 处 / cn.zcode 0 处、Assets.xcassets 入 Resources phase；`xcodebuild build` → BUILD SUCCEEDED（警告均为存量 Swift 6 mode 预警）；产物验证——.app 内 AppIcon60x60@2x.png 切片存在（120×120，由 1024 自动产出）、`assetutil --info Assets.car` 列出 AppIcon 与 BiuzMark、plutil 验证产物 Info.plist 显示名/bundle id/权限文案；残留扫描——App 源码字符串字面量层用户可见 ZCode 仅余 works-with/功能性引用（与本报告撰写会话 grep 复核一致：3 处，SettingsView.swift:24、ConnectFlowView.swift:135/:339，均为 works-with 口径；其余命中为代码标识符与 OAuth 启动参数）。

### 7.5 门禁结果

| 门禁 | 结果 | 执行记录 |
|---|---|---|
| 构建 | **通过 = true**（阶段口径） | 迭代会话：`xcodegen generate` + `xcodebuild -project ZCodeMobile.xcodeproj -scheme ZCodeMobile -sdk iphonesimulator build` → BUILD SUCCEEDED（7.4 自检记录）；`build-for-testing`（destination 'platform=iOS Simulator,name=iPhone 18 Pro'）→ **TEST BUILD SUCCEEDED 两轮，代码零警告零错误**，产物 strings 列出 27 个用例符号（含 3 个新用例） |
| e2e | **通过 = true**（阶段口径） | 扩展后套件（登录 12 + 常规 7 + 布局走查 8 = 27 用例）由统一门禁执行；实现阶段自检为编译级 + 产物资产验证（7.4），**测试套件本体未在实现会话运行**——采信边界与 §11.1 同口径 |
| 本报告撰写会话 | 未复跑构建与测试 | 静态核验代替：工作区门禁脚本/xcresult `find` 复跑零命中（v1.3 实测）；测试文件行数/用例数、Assets 目录与 PNG 规格、md5 同源、pbxproj bundle id 计数、只读改造各文件行级核对（各节「实测」标注）；详见 §11.6 |

### 7.6 全面验收清单（21 项全部 pass——其中 18 项 pass(编译级)、3 项 pass(已运行检查)；分级如实标注）

> 分级口径：**pass(编译级)** = 断言已写入测试代码并经 build-for-testing 编译验证（TEST BUILD SUCCEEDED），测试套件本体由统一门禁执行通过（e2e=true，阶段口径，工作区无 xcresult 可复核——§11.1）；**pass(已运行检查)** = 本迭代会话/本报告撰写会话实际执行命令验证。21/21 pass，按此口径可声明本轮验收通过；门禁凭据入库复核仍是 §12 风险 1 的前置整改项。
> **用例归属说明（v1.3 二次修订加注）**：两套测试各有 test06——登录套件 test06_pairingSuccessConnectsAndLoadsStubData（ZCodeMobileLoginE2ETests.swift:371）与常规套件 test06_brandShowsBiuZAndMainScreenHasNoZCodeCopy（ZCodeMobileE2ETests.swift:233）；test12 仅存在于登录套件；test07 两套各一（登录套件令牌重试 ：456 / 常规套件演示列表操作 ：319）。下表凡未标「常规套件」的 test06/test07 均指登录套件，行号区间亦可辨识。

| # | 功能 | 状态 | 备注 |
|---|---|---|---|
| 1 | 只读边界：连接态发送入口禁用/只读提示可见 | pass(编译级) | 登录套件 test06 断言 05-composer-readonly 存在、05-composer-input/05-composer-send 不存在（ZCodeMobileLoginE2ETests.swift:413-419 实测）；ChatView.swift:130-146 只读提示实现 |
| 2 | 替身 execution 命令计数断言（连接态恒为 0） | pass(编译级) | 计数器（E2ELoginStubServer.swift:77-104，getter :77-85 + 私有存储 :100-104）+ 登录套件 test06/test12 多处 `XCTAssertEqual(stub.executionCommandCount, 0)` |
| 3 | 会话列表/历史只读展示来自替身 | pass(编译级) | 登录套件 test06：sessions-index 订阅与快照事件计数（:434-437）+ 历史行「替身助手：历史行链路正常」上屏（:445-446） |
| 4 | diff 只读展示来自替身（本轮补齐缺口） | pass(编译级) | 替身原走 default 回 `{"ok":true}`（diff 页必为空态），本轮新增 git.getChanges（顶层数组，对齐 RemoteFileStore 解析）与 git.getDiff（{patch}）；test12⑤ 断言 gitReadCommandCount≥1 → 文件卡/+30/−10/hunk/add/del 行回执（:681-703 实测） |
| 5 | 品牌：主界面/设置页品牌名显示 BiuZ（12-brand-name） | pass(编译级) | 常规套件 test06_brandShowsBiuZAndMainScreenHasNoZCodeCopy：12-brand-name hasPrefix "BiuZ"（ZCodeMobileE2ETests.swift:260-264）+ 12-foot-data-source 含 "BiuZ for iOS"（:267-268） |
| 6 | 品牌：主界面可见文案不含 ZCode | pass(编译级) | 同用例遍历主界面窗口 staticTexts（isHittable 过滤，上限 60）断言 label 不含 "ZCode"（:245-256）；源码 grep 复核：可见文案仅设置页 works-with 行含「ZCode 社区版」（不在主界面） |
| 7 | App 图标资产已换（构建产物层面） | pass(已运行检查) | md5 实测：Assets.xcassets/AppIcon-1024.png = branding/logo/png/appicon-1024.png = f2108efa2f932785ebd7739a080cf7c3（同源 BiuZ 图标）；1024×1024 无 alpha，Contents.json 正确引用；产物切片/assetutil 验证见 7.4 |
| 8 | 回归：演示模式五条既有流程继续全绿 | pass(编译级) | 常规套件 test01–05 未改动；应用侧仅补 identifier（04-search-input、04-rowact-*），不改变布局/交互逻辑，既有选择器不受影响 |
| 9 | 闭环：会话列表加载 | pass(编译级) | test12①：冷启动自动重连 → subscribedTopics 前缀 sessions-index/ 且快照事件≥1 → 04-row-sess-e2e-1/2 上屏（:626-648 实测） |
| 10 | 闭环：新建会话 | pass(编译级) | 登录套件 test06：03-readonly-notice + 无 03-input-title → 03-submit-start 创建 → createSessionCount≥1 且 **createSessionWithFirstInputCount==0** → 05-composer-readonly 回执（:402-428） |
| 11 | 闭环：置顶/取消置顶 | pass(编译级) | 连接态 test12③（置顶 →「置顶」分组头出现/消失 + 零命令，:664-673）；演示模式 test07①②（操作可重复，ZCodeMobileE2ETests.swift:327-345） |
| 12 | 闭环：归档 | pass(编译级，含如实标注) | 连接态归档为本地覆盖且列表不过滤（v4 命令面缺口）——test12④ 只断言动作可达+零命令（:675-679），不虚构行消失回执；归档行消失的完整 UI 反射在演示模式断言（test07③ :347-352，Mock 过滤归档行） |
| 13 | 闭环：搜索过滤 | pass(编译级) | 连接态 test12②（「E2E」→ sess-e2e-1 消失、sess-e2e-2 保留、清空恢复、零命令，:650-662）；演示模式 test07④（:354-367）；搜索框经新增 identifier 04-search-input 定位 |
| 14 | 闭环：聊天历史与只读提示 | pass(编译级) | 登录套件 test06：历史行来自替身 + 05-composer-readonly + 无可回复提问卡 + executionCommandCount==0（:430-451） |
| 15 | 闭环：diff 展示 | pass(编译级) | test12⑤：git 读面计数≥1 → 两张文件卡 + 聚合统计 +30/−10 + 展开 hunk/add/del + 零 execution 命令（:681-703） |
| 16 | 闭环：任务列表 | pass(编译级) | 替身本轮新增 listTaskList 应答（2 条任务，对齐 mapTask 必填字段）；test12⑥：taskReadCommandCount≥1 → 02-taskcard-task-e2e-1 + 标题回执 + 零命令（:705-716） |
| 17 | 闭环：设置各项修改 | pass(编译级) | 演示态 test04（外观改 Zai Dark → 行值回执 → 冷启动持久化）；连接态 test12⑦（同一交互 → 行值即时回执 + 全程零 execution 命令，:718-733）；设置属本地 UserDefaults，闭环以「零命令+行值回执」表达 |
| 18 | 闭环：OAuth 登录与退出 | pass(编译级) | test01（替身收到 authorize/token 请求且参数/计数吻合 → 已登录回执）；test05（退出二次确认 → 未登录卡）；test02/03/04 覆盖 state 篡改、交换失败、用户取消 |
| 19 | 闭环：配对连接与令牌重试 | pass(编译级) | test06（websocketUpgrades/lastAcceptedPairingToken → 已连接 + server-info 名称回执）；test07（401 失败页 → 更新令牌重试成功）；test08（地址超时 → 原配置重试）；test10（保存配置自动重连） |
| 20 | 布局回归并入常规套件一起执行 | pass(已运行检查) | pbxproj 实测 LayoutAuditTests.swift 与全部 e2e 同在 ZCodeMobileUITests target Sources（无独立布局 target、无 skip），门禁按 scheme 整体执行即一并运行 |
| 21 | 自检：xcodebuild build-for-testing 编译级验证 | pass(已运行检查) | 实跑命令与结果见 7.5（TEST BUILD SUCCEEDED 两轮、零警告零错误、27 用例符号）；按要求未运行测试套件本体 |

### 7.7 布局走查

**走查范围**：ios/Tests/LayoutAuditTests.swift（494 行、**8 条用例**，实测；随 ZCodeMobileUITests target 编译，pbxproj 复核同 target）——覆盖**演示模式与连接替身两种状态**下的全部页面/关键状态：演示四 Tab 根页、聊天详情、设置分区与深浅两态、O1 登录主页与授权 Sheet、L1 手动表单键盘态、L2 连接中/L3 失败态、连接替身后的只读聊天与只读新建 Sheet、连接态设置服务器分区；每步 XCTAttachment 截图（keepAlways，导出后逐张人工复核），并对关键 frame 做回归断言。

**断言三类遮挡**（LayoutAuditTests.swift:46-75 基础设施实测）：①内容底边不越 TabBar 顶边（assertAboveTabBar）；②键盘弹起后输入元素仍完整可见（assertAboveKeyboard）；③底部动作栏与 TabBar 不互相遮挡（assertOnScreen + 逐帧 maxY/minY 比较）。

**走查发现并处置的遮挡问题**（以文件内回归注释口径如实记录）：

| # | 问题 | 处置与回归断言 |
|---|---|---|
| 1 | iOS「保存密码？」系统自动填充弹窗盖在 App 上，遮挡设置页并吞掉后续全部滑动手势（连接流程实测发生） | 连接流程先点「眼睛」把令牌 SecureField 切为明文 TextField 避免触发（:102-104），并保留 Springboard 双语按钮兜底关闭 dismissPasswordAlertIfPresent（:107-108、:459-472） |
| 2 | 提交连接后键盘残留，遮挡 L3 失败态错误对照表（P1 回归） | 提交时主动收键盘 + 用例断言进入 L3 后键盘必须收起（:345-346），配 waitKeyboardDismiss 有界轮询（:475-482，规避收起动画期误判） |
| 3 | 连接完成后 UI 停在设置 Tab：四 Tab 同挂 ZStack，行元素跨 Tab 在可访问性树但不可命中，直接 tap 落空（门禁第 2 轮实证） | 走查用例显式切回会话 Tab 再推入，行点击带推入效果有界重试（:369-381、:441-444） |
| 4 | 任务看板滚动到底后末位任务卡被 FAB+TabBar 遮挡区覆盖（P2 回归：底部余量须覆盖 FAB 56 + 间距 16 + TabBar ≈87） | 断言滚动到底后末位卡 maxY ≤ FAB minY 且不越 TabBar（:153-167），配套底部余量 180pt 约束 |
| 5 | 令牌栏在表单低处，键盘弹起后可能不可命中（已知遮挡线索） | 断言令牌栏 isHittable 且完整在键盘上方（:303-310） |

**本轮回归断言（新增元素无遮挡）**：新增品牌名行 12-brand-name 的帧断言——演示态与连接态滚动到底后均不得被 TabBar 遮挡（:183-189、:421-426 实测）；只读提示 05-composer-readonly / 03-readonly-notice 完整在屏断言（:385、:393-401）。**本轮未发现新的需修复遮挡问题**（新增品牌行与只读提示均经帧断言验证无遮挡——如实呈现；8 条用例全部保持断言通过口径，测试本体由门禁执行）。

### 7.8 已知限制（如实）

1. **门禁采信边界**：构建/e2e 通过均为阶段口径，工作区仍无门禁脚本与 xcresult（v1.3 实测零命中）——§12 风险 1 前置整改不变。
2. **订阅参数与空态兜底（v1.3 二次修订更正）**：首版所记「iOS 订阅参数只传 topic」**经复核不成立**——实测 subscribeSessionsIndexV4 传 topic+workspacePath（RemoteConversationStore.swift，v1.3 时 :111-117、现网 :132-137，注释引 zcodeAgentPluginParams.ts:8-11）、subscribeConversationV4 传 topic+sessionId（v1.3 时 :191-196、现网 :276-280，注释引 zcodeAgent.ts:144-146）、unsubscribe 同步带 workspacePath（v1.3 时 :136-140、现网 :208，见 §7.2 勘误），`grep` 证实全源码订阅调用仅此 4 处、不存在「只传 topic」的调用点；首版所引 :105-110/:181-187 实为 MARK 注释与 messages() 分页逻辑，属引用错位。残留的如实缺口（**v1.4 二次修订更新**：①的行为与行号均已随 M1.y 变化）：①订阅失败兜底——v1.3 时为空态兜底（:127-128 注释自证「订阅失败：列表保持空态」）且失败不被 UI 感知；M1.y 已升级为 listSessions 只读拉取 + readSession 对账兜底（现网 :154/:296 注释），失败被部分掩盖但列表不再恒空，联调时仍需显式断言订阅成功；②订阅参数与真实服务端的运行期绑定仍未端到端验证（列入 §14.2 第 2 项）。
3. **归档的连接态行为（v1.4 更新）**：M1.y 已接 zcode-task.archiveTask 双写（session 类，§8.3），e2e 断言升级为「左滑归档 → archiveTaskCount≥1 → 本地覆盖保留行」三段闭环（§8.4）；连接态列表不过滤归档行的**展示语义**不变（服务端 sessions-index 快照仍含归档行，过滤与否属产品口径），不再是「零命令」。
4. **订阅类的进程副作用**：移动端订阅会拉起桌面端 CLI 进程并冷恢复历史 Session（readonly 分类维持，但需知情）。
5. **只读边界是客户端自律**：ReadOnlyGate 为出口黑名单口径（未知命令放行，ReadOnlyGate.swift，v1.3 时 :66-67、现网 :183-185，见 §7.2 勘误），服务端对 web-remote-replayable 不收窄命令面；绕过客户端直连协议不在此边界内。若未来产品要求服务端强制，需上游/桌面团队配合。
6. **商标检索未执行**：中国商标网 9/42 类「BiuZ」「Biu」家族核查属上架前人工流程（naming.md 合规清单第 6 条），列入 §14.2 第 5 项。

## 8. M1.y 迭代：按 packages/client 参考完成移动端接口对接（v1.4 新增章节）

本章记录 M1.y 迭代全部产出：参考客户端盘点（8.1）→ 逐接口对照（8.2）→ 实现清单（8.3）→ 替身验证闭环（8.4）→ 门禁结果（8.5）→ 与 v1.3 只读边界章节的自洽关系（8.6）→ 已知限制与待真实后端确认项（8.7）。证据来源：①**packages/client 参考客户端盘点**——浅克隆上游开源仓库（`git clone --depth 1 https://ghfast.top/https://github.com/zai-org/ZCode /tmp/zcode-api-client`，直连 github.com 443 超时改用镜像；与 §6/§7 两个克隆件同口径管理，§12 风险 6），通读 packages/client 传输层（websocket.ts/messageport.ts）与 RemoteServiceAccess（remoteServiceAccess.ts:96-222）、40 个服务接口文件、packages/shared 频道表（channels.ts:75-152）与 v4 协议面（command.ts:44-247、transport.ts:332-384），并对 v4 命令 handlers、zcodeSessionService、zcodeAgentService、gitService、packages/server/src/http.ts（:84-125/:300-475）、zcodeAgentConnectionScope（:690-850）抽查服务端实现核实（全程只读，未运行其构建/测试）；②**iOS 现状盘点**——ios/ 全部 48 个 Swift 文件中接入相关 Sources 通读并以 grep 交叉验证实际下发的 wire 命令全集；③**逐接口对照计划**——392 条逐方法处置表（**随任务材料提供至本报告撰写会话**；实现会话不可读取该材料、其 integrate/partial 清单系从实施代码逐项核对得出——两处表述主体不同、不矛盾，§8.5。撰写会话对该材料的复算范围如实限定为：统计口径自洽（392=11+4+32+122+223；R/S/E 总数与条目数吻合）、exists/partial/integrate 三类的逐项清单与实现落点核对、40 服务暴露面与 /tmp/zcode-api-client 克隆件 remoteServiceAccess.ts:52-94（39 个 readonly 属性 + :76/:162 defineProperty 隐藏属性，本轮实跑比对）一一对应；**skip-boundary 122 与 defer 223 的逐项构成未逐条独立复核**，数量依赖材料自述——该 392 条处置表本身不在工作区、评审不可独立复核其五类拆分（§11.1 证据等级同口径））；④M1.y 实现与测试计划（工程代码与替身扩展，均在本工作区，本轮逐项核对属实）；⑤本报告撰写会话对工作区的第五轮实测核验（本节标注「实测」的 file:line 与命令均经本轮 Read/grep/逐行核对，明细见 §11.6 倒数第一条——v1.5 新增末条后的指位更新）。

### 8.1 参考客户端盘点：RemoteServiceAccess 服务/方法清单（含分类与证据）

**盘点对象与计数口径**：packages/client/src/remoteServiceAccess.ts:96-222 经 ProxyChannel.toService 创建服务代理——ProxyChannel 约定（packages/rpc/src/proxy-channel.ts:46-146）：服务端 fromService 把方法→call、onXxx/onDynamicXxx→listen；客户端 ES6 Proxy 拦截属性，方法调用=channel.call(propKey, args)，即**每条方法的底层 wire=(channelName, commandName) 二元组**；onDynamicXxx 为带参动态事件 listen(propKey, arg)。channelName 总表见 packages/shared/src/channels.ts:75-152。RemoteServiceAccess 实际暴露 **40 个服务**（39 个 readonly 属性 + 1 个 defineProperty 隐藏属性 providerProvisioningTargetService，remoteServiceAccess.ts:162-167 实测）。方法条目按参考清单合并口径计数（同族方法合并 1 条，如 botsService 的 handleProviderCallback/Response、providerSettingsService 的个人模型 6 方法），合计 **392 条**；分类统计 **readonly 199 / session 148 / execution 45**（口径与 §7.2 相同）。

**40 服务全量清单**（方法标注 R=readonly / S=session / E=execution；证据列为接口声明 file:line，每条方法的签名/用途/分类证据以盘点材料逐条记载，本表收录方法名与分类）：

> **分类口径声明（v1.4 二次修订加注，两套口径不可混读）**：表内 R/S/E 沿用**参考盘点的语义口径**——execution 仅指「驱动 agent/模型/harness 执行」（sendPrompt/模型调用/hook 信任闸/插件安装等），而 git 索引/仓库写、远端发布等「不驱动 agent 的工作区或远端写」在该口径下归 session。本产品的**移动端拦截口径（§7.2）更严**：凡写工作区/仓库/远端/凭据/harness 能力面一律按 execution 拦截——因此 gitService 行标 S 的 stagePaths/unstagePaths/discardPaths/commit/push/switchBranch/createBranchAndSwitch 等在本产品 ReadOnlyGate 中全部列入 execution 黑名单（executionGitCommands 8 命令，§8.2④/§8.3），terminalService 的 create/resize/dispose 同理按频道级黑名单全拦。两套口径的差异是「盘点如实转记 vs 产品自律边界」的关系，不存在分类错误，但读者判定放行/拦截时**一律以 §7.2/§8.3 拦截口径为准**。

| 服务 | channelName | 条目 | R/S/E | 方法清单（标注分类） | 证据（接口声明） |
|---|---|---|---|---|---|
| fileService | `file` | 17 | 13/4/0 | searchWorkspaceFiles(R)、readdir(R)、stat(R)、checkFilesExist(R)、resolvePath(R)、ensureConversationWorkspace(S)、createDefaultWorkspace(S)、createScratchWorkspace(S)、readTextFile(R)、readMediaPreview(R)、readFileRange(R)、readBinaryPreview(R)、listWorkspaceFilesLength(R)、listWorkspaceFilesRange(R)、readWorkspaceFileSearchIgnore(R)、applyWorkspaceFileSearchIgnoreTransform(R)、writeWorkspaceFileSearchIgnore(S) | packages/services/src/file/file.ts:23-79 |
| mediaPreviewService | `media-preview` | 3 | 3/0/0 | prepare(R)、refreshPlaybackUrl(R)、release(R) | media-preview/mediaPreview.ts:30-39 |
| gitService | `git` | 18 | 10/7/1 | getRepositorySummary(R)、getWorkspaceRepositoryInfo(R)、getLocalBranches(R)、getCommitGraph(R)、switchBranch(S\* )、createBranchAndSwitch(S\* )、getChanges(R)、getIgnoredPaths(R)、getDiff(R)、getBranchComparison(R)、stagePaths(S\* )、unstagePaths(S\* )、discardPaths(S\* )、generateCommitMessage(E)、commit(S\* )、push(S\* )、getIdentity(R)、refresh(R)。\* 按 §7.2 移动端拦截口径并入 execution 全拦（见上「分类口径声明」） | git/git.ts:33-52（实现 gitService.ts:250-296） |
| gitCheckpointService | `git-checkpoint` | 4 | 1/3/0 | createCheckpoint(S)、diffCheckpoints(R)、restoreBetweenCheckpoints(S)、deleteCheckpoint(S) | git/gitCheckpoint.ts:14-17 |
| systemService | `system` | 3 | 3/0/0 | info(R)、listIntegratedTerminalShells(R)、probeIntranet(R) | system/system.ts:11-13 |
| terminalService | `terminal` | 6 | 2/3/1 | create(S)、write(E)、resize(S)、dispose(S)、onDynamicData(R)、onDynamicExit(R) | terminal/terminal.ts:12-25 |
| settingService | `setting` | 4 | 1/3/0 | get(R)、update(S)、updateDataBaseDir(S)、ensureDefaultProject(S) | setting/setting.ts:6-16 |
| onboardingRecordService | `onboarding-record` | 9 | 3/6/0 | appendRecord(S)、shouldOnboard(R)、dismissOnboarding(S)、claimAnonymousRecord(S)、getLatestEntry(R)、syncSettingsFromRecord(S)、updateRecordPreferences(S)、getRecords(R)、clearRecords(S) | onboarding/onboardingRecord.ts:24-56 |
| credentialService | `credential` | 3 | 1/2/0 | load(R)、save(S)、delete(S) | credential/credential.ts:12-14 |
| broadcastService | `broadcast` | 6 | 1/5/0 | send(S)、acquireClaim(S)、commitClaim(S)、releaseClaim(S)、tryClaim(S)、onMessage(R) | broadcast/broadcast.ts:39-49 |
| zcodeTaskService | `zcode-task` | 65 | 26/29/10 | initialize(S)、releaseWorkspacePreparation(S)、createTask(S)、sendPrompt(E)、deliverSessionMessage(E)、sendSessionMessageDeliveryResult(S)、enqueueTaskCommand(E)、promoteTaskCommand(E)、cancelTaskCommand(E)、stopGeneration(E)、compactSession(E)、goalSession(E)、respondPermission(E)、respondElicitation(E)、closeTask(S)、resumeTask(S)、listTasks(R)、listPinnedTaskIds(R)、listPinnedTasks(R)、listDeletedTaskIds(R)、listTaskList(R)、createTaskGroup(S)、renameTaskGroup(S)、updateTaskGroupColor(S)、deleteTaskGroup(S)、listGroupedTaskViewStructure(R)、applyGroupedTaskViewOrder(S)、listArchivedTasks(R)、archiveStaleTasks(S)、archiveWorkspaceTasks(S)、getTaskSnapshot(R)、getTaskSnapshotWithEtag(R)、getTaskSnapshotBody(R)、getTaskSnapshotRef(R)、getTaskSnapshotToolCallsSlice(R)、getTaskMeta(R)、getTaskConfigOptions(R)、getTaskModelSelection(R)、setAssistantMessageFeedback(S)、scanImportableClaudeSessions(R)、importClaudeSessions(S)、setMode(S)、setConfigOption(S)、setModel(S)、setAutomationSessionConfig(S)、getTaskNativeSessionLogFile(R)、getModelTrajectory(R)、getTaskTokenUsage(R)、getTaskSessionFilePath(R)、restartWorkspaceProcess(S)、deleteTask(S)、deleteArchivedTask(S)、deleteArchivedTasks(S)、renameTask(S)、setTaskPinned(S)、setTaskUnread(S)、archiveTask(S)、unarchiveTask(S)、branchTaskFromPrompt(S)、onDynamicStreamEvent(R)、onDynamicTaskTerminalOutcome(R)、onDynamicTaskReady(R)、onDynamicTaskEvent(R)、onDynamicWorkspaceEvent(R)、onError(R) | session/zcodeTaskService.ts:199-742 |
| windowControllerService | `window-controller` | 8 | 5/3/0 | deleteArchivedTask(S)、deleteArchivedTasks(S)、listTaskList(R)、mutateTask(S)、subscribeControllerV4(R)、resyncControllerV4(R)、unsubscribeControllerV4(R)、onDynamicControllerFrame(R) | window-controller/windowController.ts:23-66 |
| zcodeAgentService | `zcode-agent` | 116 | 67/26/23 | prepareStorage(S)、getStorageStartupState(R)、onDynamicStorageStartupState(R)、initialize(S)、syncAppRuntimePreferences(S)、getWorkspaceRuntimeIdentity(R)、createSession(S)、resumeSession(S)、listSessions(R)、listSessionSubagents(R)、getAppUsageStats(R)、getTaskTokenUsage(R)、readSession(R)、readSessionMessages(R)、readSessionDebug(R)、readSessionEvents(R)、readWorkspacePresentation(R)、grantWorkspaceHookTrust(E)、listMcpServerStatuses(E)、listPlugins(R)、getPluginReferenceCatalog(R)、getSkillReferenceCatalog(R)、listSavedWorkflows(R)、getSavedWorkflow(R)、updateSavedWorkflowMeta(S)、deleteSavedWorkflow(S)、listSavedWorkflowRuns(R)、moveSavedWorkflow(S)、resolveSuggestedPluginReference(R)、onDynamicPluginOperationProgress(R)、getPluginsOverview(R)、collectLocalRuntimeChildProcesses(R)、addPluginMarketplace(E)、removePluginMarketplace(E)、updatePluginMarketplace(E)、installPlugin(E)、cancelPluginOperation(E)、uninstallPlugin(E)、updatePlugin(E)、restoreBuiltinPlugin(E)、configurePlugin(E)、resetPluginConfig(E)、validatePlugin(E)、describePlugin(R)、setPluginEnabled(E)、listAutomations(R)、listAllAutomations(R)、createAutomation(S)、updateAutomation(S)、deleteAutomation(S)、setAutomationEnabled(S)、restartAutomation(E)、runAutomationNow(E)、listAutomationRuns(R)、deleteAutomationRun(S)、generateWorkspaceText(E)、testModelConnectivity(E)、sendPrompt(E)、compactSession(E)、goalSession(E)、closeSession(S)、setModel(S)、setThoughtLevel(S)、setMode(S)、respondSessionRuntimePreferences(E)、onDynamicSessionRuntimePreferencesRequest(R)、onDynamicProcessResourceSample(R)、onDynamicMcpTelemetry(R)、onDynamicMcpResourceSamples(R)、onDynamicToolExecResource(R)、onDynamicSessionEvent(R)、helloConversationV4(S)、initializeConversationV4(S)、setConnectionFlowStateV4(S)、subscribeConversationV4(R)、resyncConversationV4(R)、unsubscribeConversationV4(R)、conversationRowsRangeV4(R)、conversationPlansV4(R)、conversationWorkflowRunEventsV4(R)、conversationWorkflowRunsV4(R)、conversationWorkflowRunArtifactsV4(R)、conversationWorkflowRunArtifactDataV4(R)、conversationWorkflowRunArtifactReadV4(R)、conversationWorkflowRunWorkspaceV4(R)、conversationWorkflowRunNodeResultV4(R)、backgroundBashOutputV4(R)、conversationFileChangesV4(R)、conversationFileRewindPreviewV4(R)、sendConversationCommandV4(E)、queryConversationCommandsV4(R)、attachmentBeginV4(S)、attachmentChunkV4(S)、attachmentCommitV4(S)、attachmentAbortV4(S)、attachmentPreviewSourceV4(R)、attachmentReadV4(R)、conversationAttachmentReadV4(R)、conversationAttachmentStatV4(R)、onDynamicConversationFrame(R)、onDynamicLocalTtftFacts(R)、onDynamicConversationTelemetryFact(R)、onDynamicCuaPermissionObservation(R)、subscribeSessionsIndexV4(R)、resyncSessionsIndexV4(R)、unsubscribeSessionsIndexV4(R)、onDynamicSessionsIndexFrame(R)、subscribeWorkspaceConfigV4(R)、resyncWorkspaceConfigV4(R)、unsubscribeWorkspaceConfigV4(R)、onDynamicWorkspaceConfigFrame(R)、onAgentRuntimeRestarted(R)、onAgentRuntimeLifecycle(R)、hasActiveCuaOperationTurn(R)、disposeWorkspace(S)、disposeAll(S) | zcode-agent/zcodeAgent.ts:569-862 |
| zcodeSessionService | `zcode-session` | 15 | 6/9/0 | initializeWorkspace(S)、getWorkspaceRuntimeIdentity(R)、readWorkspacePresentation(R)、createSession(S)、resumeSession(S)、listSessions(R)、readSession(R)、readSessionMessages(R)、readSessionEvents(R)、promoteDeferredDraftSession(S)、closeSession(S)、closeDeferredDraftSession(S)、setModel(S)、setThoughtLevel(S)、setMode(S) | zcode-session/zcodeSession.ts:135-156（实现 zcodeSessionService.ts:195-314） |
| cuaPermissionService | `cua-permission` | 2 | 1/1/0 | getStatus(R)、restartHelper(S) | packages/zcode-cua/broker.d.ts:139-151（services 侧 re-export cuaPermissionService.ts:51-55） |
| conversationShareService | `conversation-share` | 9 | 7/2/0 | getCapabilities(R)、preflight(R)、publish(S)、onDynamicPublishProgress(R)、importShare(S)、onDynamicImportProgress(R)、getImportedConversation(R)、getPreview(R)、getContinuation(R) | conversation-share/conversationShare.ts:424-447 |
| botsService | `bots` | 21 | 6/12/3 | syncAppRuntimePreferences(S)、getStatus(R)、getConfig(R)、listWorkspaceRefs(R)、getUserConfigOptions(R)、beginFeishuRegistration(S)、pollFeishuRegistration(S)、beginWeixinRegistration(S)、pollWeixinRegistration(S)、saveConfig(S)、listBots(R)、saveBot(S)、removeBotSecret(S)、deleteBot(S)、testBot(E)、createBindCode(S)、getBotStates(R)、resetBotState(S)、watchAutomationRun(S)、handleInboundMessage(E)、handleProviderCallback(E，含 handleProviderCallbackResponse 合并计 1 条) | bots/bots.ts:127-163 |
| fileWatcherService | `file-watcher` | 4 | 1/3/0 | watch(S)、unwatch(S)、disposeAll(S)、onDynamicChange(R) | fileWatcher/fileWatcher.ts:14-20 |
| oauthService | `oauth` | 13 | 6/7/0 | getProviders(R)、getActiveProvider(R)、restoreCachedSession(R)、restoreCachedSessionState(R)、restoreSession(R)、startOAuth(S)、startOAuthWithPolling(S)、pollPendingOAuth(R)、handleCallback(S)、refreshToken(S)、logout(S)、logoutAll(S)、cancelPending(S) | oauth/oauth.ts:20-72 |
| providerSettingsService | `provider-settings` | 8 | 4/3/1 | onDidChange(R)、getView(R)、refresh(R)、createPersonalProvider(S)、resolveModelConfig(R)、savePersonalProviderOverlay/deletePersonalProvider/reorderPersonalProviders(S，合并)、个人模型增删改排序启停 6 方法(S，合并)、testModelConnectivity(E) | model-provider/providerFacadeServices.ts:31-70 |
| modelSelectionService | `model-selection` | 2 | 2/0/0 | onDidChange(R)、getView(R) | model-provider/providerFacadeServices.ts:96-97 |
| providerProvisioningTargetService | `provider-provisioning-target` | 1 | 0/0/1 | apply(E，host-only：不进 IServiceAccessor，remoteServiceAccess.ts:75-76/:162-167 以 defineProperty enumerable:false 隐藏) | model-provider/providerProvisioning.ts:5-8（服务端 http.ts:108-119 强制边界） |
| usageStatsService | `usage-stats` | 8 | 5/3/0 | getAppUsageSnapshot(R)、getCodingPlanUsageSnapshot(R)、getCodingPlanResetStatus(R)、requestCodingPlanResetOpportunity(S)、useCodingPlanReset(S)、markCodingPlanResetHistoryRead(S)、getSnapshot(R)、getEntitlementSnapshot(R) | usage-stats/usageStats.ts:21-32 |
| codingPlanSubscriptionService | `coding-plan-subscription` | 4 | 2/2/0 | 产品/灰度/配置查询 8 方法(R，合并)、订单/支付状态查询 6 方法(R，合并)、签约/绑卡/支付 7 交易(S，合并)、企业订单计算/创建/取消/续付 8 方法(S，合并：…createEnterpriseOrder/getEnterprisePendingOrders/cancelEnterpriseOrder/continueEnterpriseOrderPayment/checkEnterpriseOrderStatus)。接口实有 29 成员（codingPlanSubscription.ts 实测）——getEnterprisePendingOrders/checkEnterpriseOrderStatus 为 v1.4 二次修订对照克隆件补记的盘点遗漏，只读无消费方归 defer 语义 | coding-plan-subscription/codingPlanSubscription.ts:58-118 |
| clientConfigService | `client-config` | 1 | 1/0/0 | getSnapshot(R) | client-config/clientConfig.ts:10 |
| clientScenesService | `client-scenes` | 1 | 1/0/0 | list(R) | client-scenes/clientScenes.ts:60 |
| offPeakTaskService | `off-peak-task` | 7 | 3/3/1 | getCodingPlanSupport(R)、getTakeNumberAvailability(R)、createTask(S)、cancelTask/pauseTask/continueTask(E，合并)、deleteTask/deleteHistory(S，合并)、updateTask(S)、list/get(R，合并) | session/offPeakTask.ts:26-42 |
| skillsService | `skills` | 4 | 2/2/0 | list(R)、setEnabled(S)、buildPromptContext(R)、copyToCommon/removeFromCommon/deleteSkill(S，合并) | skills/skills.ts:6-45 |
| skillSyncService | `skill-sync` | 3 | 2/1/0 | 本地/远端候选与写权限检查 3 方法(R，合并)、exportSkillsArchive(R)、importSkillsArchive(S) | skill-sync/skillSync.ts:12-25 |
| mcpSyncService | `mcp-sync` | 5 | 2/2/1 | 用户目录 MCP 配置与状态读 4 方法(R，合并：loadMcpFromUserDirectory/listLocalUserMcpCandidates/listRemoteUserMcpStatuses/checkRemoteUserMcpWriteAccess)、listWorkspaceMcpServerStatuses(E)、saveMcpToUserDirectory(S)、exportMcpServers(R)、importMcpServers(S)。接口实有 8 成员（mcpSync.ts:17-44 实测）——checkRemoteUserMcpWriteAccess 为 v1.4 二次修订对照克隆件补记的盘点遗漏，按只读无消费方归 defer 语义 | mcp-sync/mcpSync.ts:17-44 |
| pluginSyncService | `plugin-sync` | 2 | 1/1/0 | 候选/状态/导出/写权限检查 5 方法(R，合并)、归档导入 2 方法(S，合并) | plugin-sync/pluginSync.ts:12-39 |
| pluginsService | `plugins` | 2 | 1/0/1 | getOverview(R)、marketplace 与插件变更 6 方法(E，合并；通道已 retired，plugins/pluginManagement.ts:8-9 注释) | plugins/plugins.ts:10-51 |
| pluginManagementService | `plugin-management` | 2 | 1/0/1 | 插件视图/catalog/描述/进度 6 方法(R，合并)、插件与 marketplace 变更 12 写(E，合并) | plugins/pluginManagement.ts:47-89 |
| subagentsService | `subagents` | 2 | 1/1/0 | list/getPrimaryUserAgentsDirectory(R，合并)、agent 启停/模型覆盖/增删改 6 写(S，合并) | subagents/subagents.ts:16-40 |
| commandsService | `commands` | 2 | 1/1/0 | list/getPrimaryUserCommandsDirectory(R，合并)、命令文件增删改启停 4 写(S，合并) | commands/commands.ts:14-25 |
| hooksService | `hooks` | 3 | 1/1/1 | loadHooks(R)、saveHooks(S)、grantWorkspaceHookTrust(E) | hooks/hooks.ts:10-36 |
| memoryService | `memory` | 2 | 2/0/0 | listProjectMemories(R)、readProjectMemoryFile(R) | memory/memory.ts:26-32 |
| settingsSyncService | `settings-sync` | 2 | 1/1/0 | 迁移状态/发现/首跑状态 3 读(R，合并)、拷贝/导入/标记 3 写(S，合并) | settings-sync/settingsSync.ts:14-36 |
| feedbackService | `feedback` | 3 | 2/1/0 | 工单列表/详情/设备信息 3 读(R，合并)、工单生命周期与附件上传 11 写(S，合并)、onDynamicUploadProgress(R) | feedback/feedback.ts:26-64 |
| promptAttachmentTransferService | `prompt-attachment-transfer` | 2 | 1/1/0 | stage/adopt/cancel/cleanup(S，合并)、onDynamicProgress(R) | prompt-attachment-transfer/promptAttachmentTransfer.ts:37-42 |

**条目口径与成员遗漏总注（v1.4 二次修订）**：392 条目为对照计划的**合并口径条目数**（同族方法计 1 条），五类处置统计与 R/S/E 分布（199/148/45）均基于该口径；**不等于接口方法成员总数**。经本轮对照克隆件实测核对：盘点对 mcp-sync 少列 1 个成员（checkRemoteUserMcpWriteAccess）、对 coding-plan-subscription 少列 2 个成员（getEnterprisePendingOrders/checkEnterpriseOrderStatus），3 个遗漏成员均属只读、按其所在面的既有口径归 defer 语义，不影响五类统计与拦截面；其余服务的成员覆盖与 remoteServiceAccess.ts 暴露面比对一致（§11.6 倒数第一条⑧）。

**传输层与移动端选型结论（盘点结论，证据已核）**：

- **两种传输**均为 @zcode/rpc ChannelClient 之上封装：`connectViaWebSocket`（websocket.ts:62-105，open 后包 ISocket→SocketProtocol→ChannelClient+RemoteServiceAccess；协议层 Initialize 由 ChannelClient/ChannelServer 自动完成，无应用层握手帧）与 `createMessagePortServiceConnection`（messageport.ts:25-49，桌面 Desktop 模式专用——utilityProcess/main 经 postMessage 转发 port，移动端拿不到 MessagePort，不适用）。
- **服务端三条 WS 路径**（packages/server/src/http.ts:323-346/:418-447）：①`GET /ws`——永远按 clientMode=web-remote-replayable、role=terminal-client 建立，旧 header x-zcode-rpc-client-mode 已废弃不能提权（channels.ts:500-501）；②`GET /ws/host`——需先 POST /api/rpc-host-capability 申请 capability 再在升级头一次性消费（http.ts:339-346），按 desktop-continuous/trusted-host-relay 建立，**移动端不应用也无法获取**；③`GET /ws/remote/:id`——一次性连接 ID 桥接远端 services，**仅 file/git/system/terminal 四通道**（http.ts:433-443）。可选全局 token 鉴权（ZCODE_SERVER_TOKEN，http.ts:306-315）。clientMode 决定 deliveryProfile（desktop-continuous→continuous、web-remote-replayable→replayable，zcodeAgentConnectionScope.ts:201-202），hello 阶段强制匹配（transport.ts:57-61）。
- **移动端选型**：走 WebSocket `/ws` 端点（terminal-client + web-remote-replayable）——与 iOS 已实现路径一致（§4.4/§6.2 路径 B）；历史漏洞「mobile 可伪造顶层 clientMode」已由上游以 trusted carrier 修复（zcodeAgentConnectionScope.ts:712-717 注释）；sendConversationCommandV4 在 terminal-client 角色下要求先握手且 envelope.clientId 与绑定值一致否则 fault.command.clientMismatch（:704-711）。web 参考用法 packages/web/src/main.tsx:446 `connectViaWebSocket(bootstrap.wsUrl)`。
- **结构提示（盘点原文）**：移动端建议自实现「只读 IServiceAccessor 子集」，不照搬全量 40 通道——/ws/remote/:id 只桥接四通道（http.ts:433-443）说明服务端可按连接裁剪暴露面。

**盘点纠错与复核要点（本轮证据；其中 1/2/4 与 §7.3 边界复核两轮独立调研交叉印证）**：

| # | 参考面方法 | 名义形态 | 盘点核实结论 | 证据（克隆件 file:line） |
|---|---|---|---|---|
| 1 | zcode-agent.sendConversationCommandV4 信封 type=setAutoDrain | 形似队列配置 | **execution**：autoDrain=true 且 idle 时立即按 sendQueuedNow 原子路径提升队首启动 turn | handlers/queue.ts:131-147；v4-bridge 命令执行=V4CommandExecutor 全部原生直驱 core（v4-bridge.ts:12-18） |
| 2 | 信封 type=respondWorkspaceHookReview | 形似审阅 | **execution**：hook 信任应答直接决定 harness hook 执行放行 | handlers/interaction-background.ts:88-96 → create-app.ts:926（workspaceHookRuntimeSecurity.respond） |
| 3 | listMcpServerStatuses（zcode-agent）/ listWorkspaceMcpServerStatuses（mcp-sync） | 名为 list 状态查询 | **execution**：驱动 agent 进程真实 connect/listTools 探测 | mcpSync.ts:22-26（注释「真实 connect/listTools 检查必须发生在 agent 进程」） |
| 4 | grantWorkspaceHookTrust（hooks 与 zcode-agent 两处） | 形似配置写 | **execution**：hook 信任闸，与 #2 同族 | zcodeAgent.ts:603-606/:669 |
| 5 | terminalService.write | 终端操作 | **execution**：write 直写 PTY=在宿主 shell 执行任意命令，整个 terminal 频道移动端不接 | terminal.ts:12-25 |
| 6 | providerProvisioningTargetService.apply | 服务方法 | **双重不可达**：服务端对非 desktop-continuous 客户端 override 为直接抛错「Provider Provisioning 仅支持受信 Desktop Host」 | http.ts:108-119 |
| 7 | file.applyWorkspaceFileSearchIgnoreTransform | 名近 write 邻域 | **readonly 确认**：对 ignore 文本做分区变换只返回新内容不落盘（注释「保存才落盘」） | file.ts:70-78 |
| 8 | zcode-task.setTaskPinned/setTaskUnread/archiveTask | —— | **session 类写面存在**：修正 v1.2 gap 8「v4 命令面缺失」的判断（§6.3 表注），已按双写落地（§8.3） | zcodeTaskService.ts:666-695 |

### 8.2 逐接口对照：392 项五类处置与取舍

**处置口径**（对照计划定义）：**exists**=与既有实现等价，无需改动（回归验证）；**partial**=已有实现但有明确缺口，本轮补全；**integrate**=本轮新增接入（准入线：当前三 Store 或连接/设置页有直接消费方，且分类为 readonly/session）；**skip-boundary**=按只读边界不接入——UI 永不提供入口、不新增调用方，其中可枚举命令名者纳入出口拦截黑名单兜底（§8.6）；**defer**=暂缓——readonly/session 类但当前移动形态无消费方（「接上无消费方」非技术阻塞，各项已写明未来启用位），或属桌面职责/服务端拒绝面。

**统计：exists 11、partial 4、integrate 32、skip-boundary 122、defer 223，合计 392**（口径与复核范围见 §8 引言③：总量与三类小类经撰写会话按材料复算并统计自洽；skip-boundary/defer 两类的逐项构成未逐条独立复核，处置表不在工作区）。

**① exists 11（既有实现等价，回归验证）**：

| 参考接口 | 既有实现（v1.3 时点） | 回归 |
|---|---|---|
| file.readdir | RemoteFileStore 递归建树（depth<3、目录优先、内存缓存） | 文件树 E2E 冒烟 + 本轮 test14②（§8.4） |
| git.getDiff | fetchPatch + parsePatch 行型映射 | 既有 Diff E2E + test12⑤ |
| zcode-task.listTaskList | RemoteTaskStore.refresh（kind=timeline、workspaceScopes、limit=50）+ mapTask 投影 | test12⑥ |
| zcode-agent.helloConversationV4 / initializeConversationV4 | 五步连接第 4 步：protocolVersion=3 严格校验 + clientHello（clientKind=mobileApp、capabilities 缺席=旧客户端单向规则） | 既有连接 E2E test06 |
| zcode-agent.subscribeConversationV4 / unsubscribeConversationV4 | topic+sessionId 订阅与随订阅释放退订 | test06 |
| zcode-agent.onDynamicConversationFrame | Envelope 解析→分片重组→topic 分流 | test06 |
| zcode-agent.subscribeSessionsIndexV4 / unsubscribeSessionsIndexV4 | topic+workspacePath 订阅、快照/delta 投影 | test12① |
| zcode-agent.onDynamicSessionsIndexFrame | 事件别名与完整 topic 双键兜底 | test12① |

**② partial 4（本轮补全缺口）**：

| 参考接口 | v1.3 缺口 | 本轮补全 |
|---|---|---|
| file.readTextFile | 全量读，无有界分页 | 协议新增 contentPage(of:offset:length:)（首屏 256KiB）+ FileTextSlice{content,offset,bytesRead,totalBytes} 截断判定 + FilePreviewView「已截断·加载更多」追加拼接；与 file.stat 集成大小预判 |
| git.getChanges | 仅 unstaged 前 20 条 | 抽取 fetchChanges(sourceId:) 参数化；DiffReviewView 增「未暂存/已暂存/本次会话」三分段（仅连接态渲染）；prefix(20) 保留 + 「仅显示前 20 个文件」提示 |
| zcode-agent.conversationRowsRangeV4 | 首屏固定尾部 200 行，beforeRowId 向上分页无入口 | Store 新增 loadOlder(conversationID:)（取 rows 最小 rowId 为游标，rowId 字典天然拼接去重，hasMore 优先/非空页近似）+ ChatView 顶部「加载更早消息」（仅连接态显示） |
| zcode-agent.sendConversationCommandV4 | 已按信封分级接入 session 子集（仅 createSession 无 firstInput），负向拦截无单测 | 新增 UnitTests/ReadOnlyGateTests：sendText 信封拦截、createSession+firstInput 拦截、createSession draft 放行三类断言；维持 execution 词表不扩展（stub execution 计数恒 0 断言保留） |

**③ integrate 32（本轮新增接入）**：

| 参考接口（频道） | 分类 | 落点与内容 |
|---|---|---|
| file.searchWorkspaceFiles | R | RemoteFileStore.searchFiles（call file.searchWorkspaceFiles{rootPath,query,limit:50}→候选映射 FileNode）；FileTreeView 连接态搜索防抖 250ms 走服务端、命中自动展开父目录；演示态保持本地过滤 |
| file.stat | R | RemoteFileStore.stat(of:)（{path,type,size?} 宽容解析）；content(of:) 前置 stat 守卫（二进制/超大文件给截断提示而非静默空串）；FilePreviewView 大小展示兜底 |
| git.refresh | R | fetchChanges 前置 call git.refresh{workspacePath}（失败 try? 静默降级），Diff 角标与 Diff 页读到最新仓库状态 |
| zcode-task.getTaskConfigOptions | R | RemoteTaskStore.taskConfigOptions(taskID:)；TaskOutputView 连接态头部只读行（07-meta-config），缺数据不渲染 |
| zcode-task.getTaskModelSelection | R | RemoteTaskStore.taskModelSelection(taskID:)；TaskOutputView 只读模型行（07-meta-model） |
| zcode-task.getTaskTokenUsage | R | RemoteTaskStore.taskTokenUsage(taskID:)（input/output/total）；TaskOutputView 只读用量行（07-meta-usage） |
| zcode-task.onDynamicTaskEvent | R（事件） | ensureTaskEventsSubscribed（listen zcode-task.onDynamicTaskEvent{workspacePath}）；observeTasks 由纯本地广播升级为「服务端推送+本地兜底」，宽容解析（完整 task 就地 upsert、仅 taskId 回退 refresh）——修复「任务状态变化只能手动下拉刷新」 |
| zcode-task.onError | R（事件） | ZCodeServerConnection.subscribeFrameStreams 增 listen("zcode-task","onError")（无参固定事件）→ 连接日志面板 ConnectLogLine(.error) |
| zcode-task.setTaskPinned | S | RemoteConversationStore.setPinned 双写：本地 override 即时反馈 + call zcode-task.setTaskPinned{taskId,workspacePath,pinned}，远端失败回滚本地态；SessionSummary.parse 增 pinned 投影解析——修复重启后置顶丢失 |
| zcode-task.setTaskUnread | S | markRead 双写：call setTaskUnread{unread:false, expectedUnreadAt?}（compare-and-clear 防并发覆盖）；失败不回滚（下次快照自愈） |
| zcode-task.archiveTask | S | setArchived 双写：call archiveTask{taskId,workspacePath}；SessionSummary.parse 增 archived 投影——修复重启后归档丢失 |
| zcode-agent.listSessions | R | ensureSessionsIndexSubscribed 失败分支回退 call zcode-agent.listSessions{workspacePath}（顶层数组/{sessions} 双形态宽容）灌入列表——修复「订阅失败列表恒空」 |
| zcode-agent.readSession | R | ensureConversationSubscribed 失败兜底第二步：call readSession{sessionId, runtimePolicy:"existing-only"}（只读恢复不拉起 Agent）对账 pendingInteractionSummary → 角标/交互态修正 |
| zcode-agent.resyncConversationV4 | R | 订阅回执 subscriptionId+水位（logEpoch+toSeq）保存（原丢弃返回值）；assembler dropped 时按 base 重同步，缺 logEpoch 传 null 全量快照 |
| zcode-agent.resyncSessionsIndexV4 | R | 与上同构：sessions-index 侧 dropped→resync（无 logEpoch 传 null 全量）——修复断线/换代/丢帧后静默断流 |
| zcode-agent.subscribeWorkspaceConfigV4 | R | 第三路 listen（assemblerKey "workspace-config"）+ call subscribe{topic:"workspace-config/<path>", workspacePath, **runtimePolicy:"existing-only"**}（被动观察者禁止拉起 Agent）+ subscriptionId 保存 |
| zcode-agent.resyncWorkspaceConfigV4 | R | dropped→resync（connection 内闭环）；teardownTransport 先 unsubscribe 再断开 |
| zcode-agent.unsubscribeWorkspaceConfigV4 | R | 断线/Store 释放退订（EventSubscription cancel 链）；handler 晚注册由 connection 缓存重放（≤32 帧）兜底 |
| zcode-agent.onDynamicWorkspaceConfigFrame | R（事件） | 帧分流至 workspace-config handler；RemoteConversationStore.handleWorkspaceConfigFrame（snapshot.config 整体替换 + delta 唯一 op config.updated）→ WorkspaceConfigInfo（configOptions/slashCommands 只读投影） |
| zcode-agent.conversationFileChangesV4 | R | RemoteFileStore.sessionDiffFiles(sessionId:baseRevision:baseLogEpoch:)（target{sessionId}，items[].patches hunk→DiffLine）；DiffReviewView「本次会话」分段以最近活动会话为数据源 |
| zcode-session.promoteDeferredDraftSession | S | createConversation 成功后补 call promoteDeferredDraftSession{sessionId}（task-index 元数据写，失败静默）——使新建空会话持久化并在桌面可见；stub 侧 promoteDeferredDraftCount 供断言（stub v4 createSession 不回推 sessions-index 快照→转正必要，替换计划的前置验证） |
| file-watcher.watch | S | fileTree() 首载成功后 call watch{path, recursive:true}→保存 watcherId——修复 cachedTree 永不失效 |
| file-watcher.unwatch | S | deinit/teardown 发 unwatch{id} 兜底 |
| file-watcher.disposeAll | S | 连接拆除兜底 disposeAll |
| file-watcher.onDynamicChange | R（事件） | listen onDynamicChange(watcherId)→失效 cachedTree 并经 AsyncStream 通知 FileTreeView 重拉 |
| oauth.getProviders | R | AppSession 连接成功后 refreshDesktopReadonlyInfo() 拉取（逐一 try?，任一成功即产出）→ DesktopOAuthInfo；ServerViews 服务器信息卡增「桌面端登录」只读行（断开隐藏） |
| oauth.getActiveProvider | R | 同上；信息卡显示桌面当前 provider |
| oauth.restoreCachedSessionState | R | 同上（authenticated/expired 展示态；**不消费桌面凭据本体**） |
| usage-stats.getCodingPlanUsageSnapshot | R | AppSession.fetchCodingPlanUsage（accountAccess 按参考 schema 个人 coding-plan 形态）→ CodingPlanUsageInfo；SettingsView 用户卡连接态绑真实 quota（剩余百分比/积分/重置时间），演示态与缺数据回退演示值不变 |
| usage-stats.getCodingPlanResetStatus | R | 同上：重置窗口取最近可用 five-hour 机会投影 |
| model-selection.getView | R | RemoteConversationStore.modelSelectionView()（providers[].models/modelThoughtLevels/preferredSelection 宽容解析）；ChatView 只读态 ComposerBar 增真实数据 chips（05-composer-remote-chips，只读展示不调 set*）——替换 v1.3 所记演示态硬编码 chips（ChatView.swift:148-169 时点） |
| model-selection.onDidChange | R（事件） | listen onDidChange 失效重拉；缺数据回退「--」不渲染死控件 |

**④ skip-boundary 122（不接入 + 拦截兜底）——处置口径与拦截黑名单映射**：

> **两个计数口径不可混用**：五类统计的 **122 项**是按参考盘点**方法条目**计的 skip-boundary 处置归属（属 defer 同层的逐接口决策）；下表罗列的是**出口拦截黑名单的命令名集合**（按命令名计，含 v1.3 既有拦截项），并**不是 122 项的逐项枚举**——两者的差异有三：①「信封 execution 词表 17」与「双态 2」归属被 **partial 接入**的 sendConversationCommandV4 接口（§8.2② 第 4 行），是信封内 type 的拦截判定，不占 122 项 skip-boundary 条目；②同一 skip-boundary 条目可能对应多条命令名，反之频道级黑名单按命令名穷举也不与条目一一对应；③末行「无命令名可枚举」的写面无计数。黑名单总量、v1.3 既有/本轮新增的拆分见 §8.6-2。

| 家族 | 项数（约） | 内容与拦截去向 |
|---|---|---|
| 信封 execution 词表 | 17 | sendConversationCommandV4 的 sendText/stop/resolveInteraction/retryTurn/editUserQuery/applyFileRewind/compact/sendGoalCommand/pauseGoal/resumeGoal/sendQueuedNow/setAutoDrain/respondWorkspaceHookReview/cancelBackgroundWork/resumeWorkflowRun/startSavedWorkflow/amendWorkflowRunSettings → ReadOnlyGate.executionCommandTypes（v1.3 已拦） |
| createSession/createSelectionSideSession 带 firstInput | 2（双态） | firstInputDependentTypes 双态判定（v1.3 已拦） |
| zcode-task 执行/配置写 | 15 | 既有 5（respondPermission/respondElicitation/stopGeneration/compactSession/goalSession）+ 本轮扩展 10（sendPrompt/deliverSessionMessage/enqueueTaskCommand/promoteTaskCommand/cancelTaskCommand/setMode/setConfigOption/setModel/setAutomationSessionConfig/restartWorkspaceProcess）→ executionTaskCommands |
| git 仓库写 | 8 | 既有 2（stagePaths/commit）+ 扩展 6（unstagePaths/discardPaths/push/switchBranch/createBranchAndSwitch/generateCommitMessage）→ executionGitCommands |
| zcode-agent 直发 execution | 34 | 旧协议执行入口（sendPrompt/compactSession/goalSession/closeSession）、set*、respondSessionRuntimePreferences、grantWorkspaceHookTrust、listMcpServerStatuses、generateWorkspaceText/testModelConnectivity、插件写 12、workflow 文件写 3、自动化写 7 → zcodeAgentExecutionCommands（新增集合） |
| 频道级写/执行面 | 25 频道 127 命令 | terminal 全量 4、setting 2、onboarding-record 6、settings-sync 3、git-checkpoint 3、credential 2、oauth 写族 7、conversation-share 2、usage-stats 重置 2、coding-plan-subscription 交易 11、off-peak-task 7、window-controller.mutateTask 1（resume 变体服务端行为未核实，保守整体拦截）、bots 写族 14、skills 4、skill-sync 1、mcp-sync 3、plugin-sync 2、plugins 6、plugin-management 12、subagents 6、commands 4、hooks 2、feedback 11、provider-settings 11、provider-provisioning-target 1 → channelExecutionCommands（新增集合） |
| 无命令名可枚举的写面 | 其余 | 如 settingService.ensureDefaultProject、onboardingRecordService 读面外的宿主装配、broadcast 全部、file.writeWorkspaceFileSearchIgnore（计划原文明确「UI 永不提供入口；file 频道维持 default 放行但无调用方」，本轮按原文未加拦）等——保持「UI 无入口 + 无调用方」口径 |

**⑤ defer 223（暂缓——「接上无消费方」或桌面职责，非技术阻塞）家族汇总**：

| 家族 | 内容与未来启用位 |
|---|---|
| 无消费 UI 的只读面 | 插件/MCP/skill/subagents/commands/hooks 读面与目录、自动化列表（listAutomations 族）、bot 只读（getStatus/getConfig/listBots 等）、分享只读（getCapabilities/preflight/getPreview/getImportedConversation——「扫码看分享」只读页可优先启用 getPreview）、workflow run 读族（conversationWorkflowRun\*V4 9 项）、快照分片族（getTaskSnapshot\* 6 项，历史主链路已由 v4 订阅+rowsRange 承载）、遥测诊断族（onDynamic\*Resource/Ttft/Telemetry/ToolExecResource）、附件读族（attachmentRead/previewSource/conversationAttachmentRead/Stat——ChatMessage 附件模型未做）、媒体预览族（media-preview 三态，local-url 态指向宿主路径需自建播放器）、memory、client-config/client-scenes、git 只读无展示位族（getRepositorySummary/getLocalBranches/getCommitGraph 等）、file 高级读族（readFileRange/readBinaryPreview/listWorkspaceFilesLength/Range）、usage-stats/coding-plan 其余只读查询 |
| 桌面职责面 | workspace 装配族（zcodeAgent.initialize/prepareStorage/disposeWorkspace、zcodeTask.initialize/releaseWorkspacePreparation——被动观察者口径禁止为订阅拉起 Agent，接口注释 zcodeAgent.ts:527-529 与 :582）、onboarding 全部、settings-sync 读面、skill/plugin/mcp 远程同步读面、systemService（server-info 已承载版本展示）、bot 注册/配置写 |
| 冗余通道归并 | zcodeSessionService 读面统一走 zcode-agent 通道（listSessions/readSession 等，避免双通道）；zcodeAgent.getTaskTokenUsage 统一走 zcode-task 通道；getAppUsageStats 统一走 usage-stats 通道 |
| /ws 不可达面 | onAgentRuntimeRestarted/onAgentRuntimeLifecycle（本地回调注册，非 wire 事件；runtime 换代恢复由 assembler dropped→resync 等价承载）、setConnectionFlowStateV4（trusted-host-relay 专用，terminal RPC caller 必被 connection scope 拒绝） |

**关键取舍（对照计划声明的五条，本轮维持）**：①接入线划在「当前三 Store+连接/设置页有直接消费方的 readonly 面」，32 项 integrate 全部对应 iOS 现状盘点点名的 gap 或现有页面数据源；②skip-boundary 中约 30 项计划的「出口必拦」建议已随本轮落地（三个数字的口径区分：**约 30**=对照计划建议纳入黑名单的 skip-boundary 条目数；**177**=本轮实际新增的黑名单命令名数；**201+2**=扩展后黑名单总量，含 v1.3 既有 24 命令名+2 双态——详见 §8.6-2）；③defer 223 项各项 plan 已写明未来启用位，其中 onAgentRuntimeRestarted/Lifecycle 与 setConnectionFlowStateV4 属 /ws 不可达面；④会话创建统一收敛 v4 createSession（无 firstInput），zcodeAgent/zcodeSession 两通道的 createSession 均不另接，避免三通道并存；⑤所有 integrate 项替身验证统一落点为 E2ELoginStubServer handleRPCCall 扩展回执 + 新增 E2E 断言，gate 类新增纯函数单测（§8.4）。

### 8.3 实现清单

| 域 | 交付（本轮逐项核对，file:line 实测） |
|---|---|
| **gate 扩展（出口必拦）** | ReadOnlyGate.swift 116→246 行（实测）：executionGitCommands 8、executionTaskCommands 15、新增 zcodeAgentExecutionCommands 34、新增 channelExecutionCommands 25 频道 127 命令——合计 **201 命令拦截项 + 2 双态**（其中 24 命令名+2 双态为 v1.3 既有、本轮新增 177，拆分见 §8.6-2）；ZCodeServerConnection.call 出口拦截链路不变（M1.y 后 :638-643，M1.z 后 :786-797，**v1.6 二次修订现网 :795-806**，§7.2 勘误·续二），blockedExecutionCalls 留存最近 20 条（M1.y 后 :254、**M1.z 后现网 :257**）；mock 演示不经该出口、行为不变 |
| **文件面** | RemoteFileStore（201→463 行）：searchFiles(:151)、stat(:183)、contentPage 有界分页(:207)/readTextSlice(:218)、fileTree 首载后 startWatching(:70)→watcherId+onDynamicChange 失效缓存(:85-113)、observeFileTreeChanges(:102)、git.refresh 前置(:257)、fetchChanges(sourceId:) 参数化(:265)、hasMoreChanges(:305)、sessionDiffFiles(:322)；FileTreeView 搜索防抖走服务端+命中展开父目录；FilePreviewView「加载更多」（09-act-load-more，FileTreeView.swift:293 实测） |
| **会话面** | RemoteConversationStore（~570→926 行）：loadOlder(M1.y 后 :262，**M1.z 后现网 :267**)、fallbackListSessions(:162→**:167**)、reconcileViaReadSession(:303→**:308**)、resyncSessionsIndex(:186)/resyncAllConversations(:329)/disposeConversation(:345，subscriptionId+水位保存)、setPinned/setArchived/markRead 双写(:678-729→**现网 :683 起**，setArchived :701/markRead :719)、promoteDeferredDraftSession(:666→**:671**)、workspaceConfig(:747→**:752**)/handleWorkspaceConfigFrame(:766)、modelSelectionView(:809→**:814**)/observeModelSelection(:828→**:833**)——M1.z 后行号见 §7.2 勘误续；ChatView「加载更早消息」（05-act-load-older，:94）与真实数据 chips（05-composer-remote-chips，:162）——v1.3 所记 148-169 行演示态硬编码 chips 在连接态由真实数据源替换 |
| **任务面** | RemoteTaskStore（~170→289 行）：ensureTaskEventsSubscribed+handleTaskEvent(:58-94)、taskConfigOptions(:222)/taskModelSelection(:251)/taskTokenUsage(:271)；TaskOutputView 头部只读三行（07-meta-model/config/usage，TaskOutputView.swift:157/:165/:172，缺数据行不渲染，演示态不变） |
| **Diff 面** | DiffReviewView「未暂存/已暂存/本次会话」ZSegmentedPicker（08-seg-source 前缀，:182，仅连接态渲染）+ prefix(20)「仅显示前 20 个文件」提示；conversationFileChangesV4 hunk 结构→DiffLine 转换（RemoteFileStore.parsePatch 复用 :398） |
| **自愈面（resync）** | V4Wire.TopicWireFrameAssembler 增 droppedSinceLastConsume 标记（crc 失配/解码失败/超限/信封残缺/dropAll 置位，V4Wire.swift:68/:80-134）+ consumeDroppedFlag；ZCodeServerConnection.routeFrame 检测 dropped 回调 frameDropHandlers（M1.y 后 :559-575/:621-625，**M1.z 后现网 :712 起、触发 :722/:728、声明 :247**，§7.2 勘误续）；两类订阅回执 subscriptionId+水位（logEpoch+toSeq）不再丢弃；subscribeFrameStreams 增第三路 workspace-config listen 与 zcode-task onError listen（M1.y 后 :491-511，**M1.z 后现网 :644 起**） |
| **只读信息面** | AppSession（~232→368 行）：desktopOAuthInfo/codingPlanUsage 状态(:95/:97，M1.z 后未漂移)、refreshDesktopReadonlyInfo(:185→**现网 :225**)、fetchDesktopOAuthInfo（oauth 三读，:197-232→**现网 :237 起**）/fetchCodingPlanUsage（usage-stats 两读+accountAccess，:235-282→**现网 :275 起**）；连接成功后台拉取(:177→**现网 :217**)、断开/失败清空(:353-354→**现网 :393 区**)——M1.z 插入 -ZCodeRelayLink 块（:145-167）所致，§7.2 勘误续；ServerViews.ServerDetailView「桌面端登录」只读行；SettingsView 用户卡连接态绑真实 quota（:167-190） |
| **模型类型与协议扩展** | 新增 struct：WorkspaceConfigInfo/ModelSelectionInfo/TaskTokenUsage/FileContentPage（StoreProtocols.swift:65/:82/:129/:194）、FileStat（RemoteFileStore.swift:178）、DesktopOAuthInfo/CodingPlanUsageInfo（AppSession.swift:7/:17）；StoreProtocols 协议扩展（loadOlder/workspaceConfig/modelSelectionView/taskConfigOptions 等）全部带默认实现——Mock 三 Store 零改动、演示路径不变 |
| **工程** | project.yml 84→98 行：ZCodeMobileTests unit-test target（bundle id cn.biuz.mobile.tests、path UnitTests、依赖 host app）+ scheme 双测试 target（§4.2） |
| **自检（阶段口径）** | xcodegen generate → Created project；xcodebuild -sdk iphonesimulator build → BUILD SUCCEEDED；build-for-testing → TEST BUILD SUCCEEDED（app+UITests+UnitTests 三 bundle）；仅运行 ZCodeMobileTests → 10 tests passed（§8.5 复验） |

### 8.4 替身验证闭环

- **替身扩展**：E2ELoginStubServer.swift 1184→**1747 行**（实测；本轮测试计划轮再补 readSession 兜底应答与游标断言）。新增回执：subscribe\*（带 subscriptionId）/resync\*、readdir 两级树、stat、readTextFile（offset+length 切片+totalBytes，300KB 大文件）、searchWorkspaceFiles、git.refresh、getChanges（按 sourceId：staged 单文件）、conversationFileChangesV4、getTaskConfigOptions/getTaskModelSelection/getTaskTokenUsage、setTaskPinned（记录+回推 session.upserted pinned 投影）/setTaskUnread/archiveTask、promoteDeferredDraftSession、oauth 三读、usage-stats 两读、model-selection.getView、file-watcher watch/unwatch/disposeAll；conversationRowsRangeV4 增 beforeRowId 向上翻页分支（sess-e2e-1 预置 6 行，尾页 3 行+hasMore）。
- **断言面**：rpcCallLog/resyncCalls/recordedPinned/setTaskUnreadCount/archiveTaskCount/promoteDeferredDraftCount/rowsRangeRequests（游标证据）/fileWatcherWatchCount/gitRefreshCount 等计数器（:79-263 实测）；用例主动触发器 fireTaskEvent/fireWorkspaceConfigFrame/fireOnError/fireBadChecksumFragment（坏 crc32 分片）+ hasEventListener；failConversationSubscribe 开关（promiseError 拒绝订阅，驱动 readSession 兜底路径）。**替身 execution 命令黑名单集合与 ReadOnlyGate 同步扩展**（handleRPCCall 注释明示口径）。
- **新增用例**（登录套件 12→15 用例）：test13_connectedLivePushResyncRefreshPinPersistence（M1.y 时 :881，**v1.5 实测现网 :885**——任务推送免刷新/坏分片→resync 自愈+自愈后列表行恢复/git.refresh→getChanges 时序/置顶双写+execution 恒 0/loadOlder 拼接更早页+替身侧游标断言 beforeRowId=4）；test14_connectedReadonlyDataFacesChipsSearchTruncationSegments（M1.y 时 :998，**现网 :1002**——chips 真实模型/服务端搜索/大文件截断加载更多/Diff 三分段/任务只读元数据三读计数/用量卡 340·500+重置时间投影/桌面端登录行/文件树 readdir+watch 参数）；test15_connectedUnreadClearLoopAndSubscribeFallbackReconciliation（M1.y 时 :1166，**现网 :1170**——快照未读徽章→markRead→setTaskUnreadCount≥1→徽章消失；替身拒绝 subscribeConversationV4→客户端走 zcode-agent.readSession（runtimePolicy=existing-only）只读对账、历史分页不中断；全程 execution 命令数恒 0）。
- **单元级**：新增 ios/UnitTests/ReadOnlyGateTests.swift（162 行 10 用例）：信封级（sendText 拦截/createSession+firstInput 拦截/draft 放行）+ 既有黑名单回归 + 扩展黑名单（git/task/zcode-agent/频道级）+ 新只读面放行回归。
- **用例规模**：40 用例（登录 15 + 常规 7 + 布局走查 8 + 单元 10，实测 grep 'func test' 逐文件计数）。

### 8.5 门禁结果

| 门禁 | 结果 | 执行记录 |
|---|---|---|
| 构建 | **通过 = true**（阶段口径） | 迭代会话：`xcodegen generate` → Created project；`xcodebuild -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -sdk iphonesimulator build` → BUILD SUCCEEDED；`build-for-testing` → TEST BUILD SUCCEEDED（app+UITests+UnitTests 三 bundle 编译） |
| e2e | **通过 = true**（阶段口径） | 完整测试套件（登录 15 + 常规 7 + 布局走查 8 = 30 用例）由统一门禁执行；实现阶段自检为编译级，**测试套件本体未在实现会话运行**——采信边界与 §11.1 同口径；test13/14/15 为按替身新回执编写的新用例，运行时行为（等待窗口、元素命中）未经实跑校验、可能需门禁轮微调（阶段自认，如实转记） |
| **单元级（pass-已运行检查）** | **ReadOnlyGateTests 10/10 通过** | **本报告撰写会话实跑复验**：`xcodebuild test -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -only-testing:ZCodeMobileTests -destination 'platform=iOS Simulator,name=iPhone 18 Pro'` → 10 个用例逐条 passed、`** TEST SUCCEEDED **`、退出码 0（该命令同时完成 app+单测 bundle 的编译链接） |
| 未运行（按约定） | 完整 xcodebuild test 套件 not_run | 按任务约定 UI 门禁由统一脚本执行（xcodebuild test -destination 'platform=iOS Simulator,name=iPhone 18 Pro'）；另：上游对照计划（IntegrationPlan JSON）为运行时产物、本会话不可读取，integrate/partial 清单系从应用侧实施代码与替身既有能力逐项核对得出（阶段自认，如实转记） |
| 门禁脚本入库 | 仍缺 | 工作区门禁脚本/xcresult `find` 复跑零命中（本轮实测）——§12 风险 1 对本轮门禁**同样适用** |

### 8.6 与 v1.3 只读边界章节的自洽关系（skip-boundary 项与拦截表）

1. **分类口径同源**：本轮对照计划的三分类（readonly/session/execution）沿用 §7.2 在 ReadOnlyGate.swift 文件头注释固化的口径（:7-16），本轮未改动定义；§7.3 边界复核纠错 3 条（setAutoDrain/respondWorkspaceHookReview 上调 execution、forkAssistant 拆分 session）**被本轮独立维持**——参考盘点对 setAutoDrain/respondWorkspaceHookReview 以另一克隆件（/tmp/zcode-api-client）独立核实为 execution（§8.1 纠错表 #1/#2），两轮调研交叉印证；forkAssistant 在参考清单中亦为 session 类，放行分支不变（ReadOnlyGate.swift:243-244）。
2. **skip-boundary 与拦截表的关系**：v1.3 的纵深防御三层（UI/Store/连接层，§7.2）不变；本轮把对照计划中约 30 项「UI 无入口但黑名单未覆盖」的 skip-boundary 项升级为**出口必拦**——ReadOnlyGate 从 116 行扩至 246 行。**黑名单总量/既有/新增三个数字的口径区分（v1.4 二次修订更正，首版「新增 201」系把既有项误计入增量）**：扩展后黑名单总量 = **201 个命令名 + 2 双态 type**（17 信封 execution type + 15 zcode-task + 8 git + 34 zcode-agent + 127 频道级，实测脚本逐集合计数）；其中 **24 命令名 + 2 双态为 v1.3 既有拦截**（信封 17 + 双态 2 + zcode-task 既有 5 + git 既有 2）；**本轮实际新增 = 177 个命令名**（zcode-task +10、git +6、zcode-agent 新增 34、频道级新增 127）；「约 30 项」是对照计划建议纳入黑名单的 skip-boundary **条目**数（与命令名数是两个口径——一条目可对应多命令名，且 34+127 中含计划未逐项建议、按同族口径一并穷举的命令）。黑名单口径仍为「未知命令放行」避免误杀握手/订阅/查询链路（ReadOnlyGate.swift:183-185；v1.3 时为 :66-67，M1.y 加厚后漂移）。**未入黑名单的 skip-boundary 项**（如 zcode-session.set\*、file.writeWorkspaceFileSearchIgnore、broadcast 全部、各只读频道的写方法）按对照计划原文维持「UI 永不提供入口 + 无调用方 + 频道 default 放行」，其中 file.writeWorkspaceFileSearchIgnore 计划原文即明确不加拦——此为声明过的口径差异而非遗漏。
3. **本轮新增调用全部落 readonly/session 两类**：连接态 RPC 出口实测 **40 call + 7 listen**（9 频道，`grep -rhoE 'call\(...|listen\(...'` 全量枚举核对）——zcode-agent 16 call（握手 2、订阅/退订/重同步 9、rowsRange/fileChanges 2、listSessions/readSession 兜底 2、sendConversationCommandV4 仅 createSession draft 1）+ zcode-task 7 call（listTaskList、元数据三读、双写三命令）+ zcode-session 1（promoteDeferredDraftSession，task-index 元数据写）+ file 4 + file-watcher 3 + git 3 + model-selection 1 + oauth 3 + usage-stats 2；listen 7（onDynamicConversationFrame/SessionsIndexFrame/WorkspaceConfigFrame、onDynamicTaskEvent、onError、onDynamicChange、model-selection.onDidChange）。全部与 §7.2 safeOps 同口径，**执行面调用点仍为 §7.2 所列 6 处停用位，零新增**。
4. **替身侧同源拦截**：E2ELoginStubServer 的 execution 命令计数集合与 ReadOnlyGate 黑名单同步扩展（handleRPCCall 注释明示）；「连接态替身收到的 execution 类命令数恒为 0」断言跨 test06/test12/test13/test14/test15 多点保持，test15 在「订阅被拒→readSession 兜底→setTaskUnread 写」新路径上再次断言边界不被新代码绕过。
5. **对 v1.2/v1.3 记录的三处修正**：①gap 8（§6.3）——置顶/归档写面**存在**于 zcode-task 频道（session 类），v1.2「v4 命令面缺失」判断系当时调研面未覆盖该频道所致，已按双写落地；②「会话/任务/文件搜索均为本地内存过滤」等 iOS 现状盘点 named gaps 中的搜索、向上分页、任务推送、置顶持久化四项已在本轮补齐；③ChatView 工具行演示态硬编码在连接态由 model-selection/workspace-config 真实数据源替换（演示态不变）。

### 8.7 已知限制与待真实后端确认项

**参考盘点 gaps（6 条，如实转记；v1.4 二次修订将原第⑦条移出——「RemoteServiceAccess 实际 40 服务（39 readonly 属性 + 1 defineProperty）」是已查明的盘点结论而非缺口，见 §8.1）**：①packages/services 多数服务的 host 端实现未逐一深读（本轮深读 v4 命令 handlers、zcodeSessionService、zcodeAgentService、gitService、http.ts、connectionScope；其余服务分类依据接口契约注释与实现抽查），个别方法副作用细节（如 zcodeTaskService.setConfigOption 是否隐式触发 v4 投影刷新）未逐行验证；②windowControllerService.mutateTask 的 resume 变体在服务端的确切行为未读实现核实（已按 session 分类 + 频道黑名单整体拦截双保险）；③offPeakTaskService.createTask 派发时序未读实现（保守按 session 分类且整体 skip）；④connectionScope 对 terminal-client 角色除 sendConversationCommandV4 clientId 校验外是否还有其他方法级写路径拦截未逐一枚举（800+ 行抽查关键段）；⑤packages/zcode-cua 仅含编译产物（.d.ts/.js），方法语义依据 broker.d.ts；⑥协议层 Initialize 握手帧格式未逐字节核对（仅确认自动完成）。

**实现/测试已知限制（阶段自认，如实转记）**：①本轮未运行任何 XCUITest（test13/14/15 运行时行为未经实跑校验，可能需门禁轮微调；ReadOnlyGateTests 已实跑 10/10）；②真实桌面服务端（非替身）下新增回执形态按参考仓库 schema 宽容解析（providers/models、quota.limits、FileTextSlice、workspace-config snapshot 等），字段缺席时回退 nil/演示值，**schema 非 additive 演进未经真机联测**；③workspace-config/model-selection/oauth/usage-stats 在旧版桌面（无这些 additive 面）下订阅/调用静默失败、对应 UI 区块不渲染（缺数据不渲染死控件口径）。

**待真实后端确认清单（并入 §14.2 第 2 项联调范围）**：①双写投影：sessions-index 是否投影 pinned/unread/archived——投影缺席时本地 override 兜底、stub 已回推投影闭环验证，真实服务端行为待确认；②promoteDeferredDraftSession：stub v4 createSession 不回推 sessions-index 快照故转正必要，真实服务端若入索引则该项降级为冗余调用（无害）；③accountAccess 请求形态（固定个人 coding-plan 形态）真实服务端若拒绝则额度卡回退演示值；④conversationFileChangesV4 的 baseRevision/baseLogEpoch 取会话 state 快照缺省 0 的真实语义；⑤workspace-config 订阅的 topic 命名（workspace-config/<workspacePath>）与 delta op 词表（config.updated）在真实服务端的匹配；⑥会话维度 Diff 的 sessionId 取「最近活动会话」（文件 Tab 无会话上下文）的产品口径。

## 9. M1.z 迭代：云中继接入（remote/v4）（v1.5 新增章节）

本章记录 M1.z 迭代全部产出：协议逆向结论（9.1）→ 实现清单（9.2）→ 真实链路端到端验收（9.3）→ 只读边界在中继路径的保持（9.4）→ 门禁结果与测试面（9.5）→ 已知限制与逆向 gaps（9.6）。证据来源：①**协议逆向**——官方网页壳（`https://zcode.z.ai/remote/v4?sid=…&hash=…&mid=…`，curl 实测 HTTP 200 / 18,180 字节）与 **57 个前端 JS bundle 全量下载**（工作区外 /tmp/relay-js/，核心文件 index-BO-TaBle.js 6.2MB、src-D3H6NV7w.js 355KB），对 bundle 字节偏移逐段精读（中继传输类 _Vn、rpc-frame 通道 Vzn/重放缓冲 Pzn/水位 f9、HMAC proof lVn、channel 名表 cA、常量 Ju/AN/jN/MN/NN、本地路径 13 字节头 JSe/qSe 等）；②**协议探针**——python3 WebSocket 探针三轮实测（auth 四帧 / bootstrap+workspace-list 81KB / bridge-open→rpc-frame 双向往返与重传观测），另以 curl 手写 upgrade 头验证 WS 端点（HTTP/1.1 101 Switching Protocols + Sec-WebSocket-Accept 校验一致）；探针脚本沉淀于 /tmp/relay-js/（**实测 7 个**：ws_probe/ws_app_probe/ws_bridge_probe 为交接既有，disc_probe/disc_probe2/probe9 为本轮新增，ws_bridge_agent_probe.py 亦在本轮窗口产出——mtime 22:43，产出会话未在阶段材料清单标注、归属未溯源；可复验协议结论——**v1.6 二次修订注：/tmp/relay-js/ 现已整体不存在（审阅与本报告撰写会话双重复测 MISSING），协议复验能力随之丢失，§12 风险 20 缓解① 已重写**。v1.5 二次修订按 `ls /tmp/relay-js/*.py` 实测更正，阶段材料与 v1.5 首版均漏计为 6 个）；③**M1.z 实现**（本工作区 ios/，本轮逐文件核对）与**真实链路验收**（模拟器 + 交接材料真实配对链接 + `-ZCodeRelayLink` 启动，simctl 日志全文 /tmp/relay-log-stream.txt 65,805 字节 + 逐秒截图，均为本轮验收会话实地产出，v1.5 撰写会话彼时抽查复核一致（**v1.6 二次修订：全部 /tmp 证据文件现已不存在，见 9.3 失效更新**）；④M1.z 测试计划材料；⑤本报告撰写会话对工作区的第六轮实测核验（明细见 §11.6 末条）。**闭环源与交接材料**：docs/relay-handoff.md（v1.5 时 27 行，**v1.6 二次修订复测现网 29 行**——v1.5 凭据掩码整改新增注记所致，三处引用更正；审阅 #4 属实）提供配对链接与 iOS 现状交接；交接链接中的 sid/hash 凭据彼时即已掩码，链接本体随 /tmp 时效与桌面端重新生成而失效（§12 风险 21）。

**与前序迭代的边界**：M1.z 不改变 §6–§8 已交付的任何行为面——局域网直连（`zcode --web`）路径与演示模式行为不变（ChannelClient 仅加显式 conformance 声明，connect() 原有五步与 ReadOnlyGate 出口未动；ServerConfig.relay 为可选字段且旧数据兼容有单测）；新增的是**第三条连接路径**（云中继），经同一 RPCChannelTransport 门面汇入既有 Store/连接层。§1.2 路径 A 所述「后续接 relay 只需替换发现/鉴权层，不需要重写业务层」的架构预判在本轮兑现。

### 9.1 协议逆向结论（网页壳与 bundle 实测）

**接入端点与配对链接解析（全部 bundle 实测）**：中继 WS 端点为 **`wss://zcode.z.ai/ws?mid=<配对链接的 mid 参数>`**——证据：src-D3H6NV7w.js 字节偏移 179516 处 `xh="wss://zcode.z.ai/ws"`；同文件 `Lh()` 构造 `relayWsUrl: \`${i}/ws\``（https origin 转 wss）；index-BO-TaBle.js 偏移 6147xxx 处 `_Vn.connect()` 将配对链接的 mid 写入 `searchParams.set('mid', …)` 后 `new WebSocket(e.toString())`。**sid/hash 不进 URL**：参数解析函数 wN（src-D3H6NV7w.js 偏移 **336142–336400 区**——v1.5 二次修订按偏移实读更正，阶段材料原记「336800 附近」有偏）把配对链接 query 的 sid→deviceSid、hash（URL 解码 `%3D`→`=` 后的 base64 **原字符串**，不做 base64 解码）→passHash、mid→deviceMid、t→timestamp（仅 Number.isFinite 校验）、name→deviceName、app_version→appVersion；deviceSid/passHash 仅用于 auth 帧。App 实现同构：解析配对链接三值→连 `wss://host/ws?mid=<mid>`，hash 保留为字符串作 HMAC 密钥。端点连通性：curl 手写 upgrade 头实测 `HTTP/1.1 101 Switching Protocols`（Server: ESA，Sec-WebSocket-Accept 与 SHA1(key+GUID) 比对一致），无 mid 参数亦可 101（网页代码 mid 可选）。

**连接后帧序（_Vn 中继传输类，index-BO-TaBle.js@6147xxx-6154xxx，探针全部实测）**：①WS open → 客户端发 **auth_init** `{type:'auth_init',role:'terminal',device_sid,meta:{platform:'web',version,name:'mobile-browser'},client_ts}`；②服务端回 **auth_challenge** `{server_ts,nonce:<22位>}`；③客户端发 **auth_response**，proof=calculateProof(passHash,nonce,'terminal',deviceSid)=**base64url 无填充(HMAC-SHA256(key=UTF8(passHash), msg=`${nonce}|terminal|${deviceSid}`))**（实现函数 lVn 在 **index-BO-TaBle.js 偏移 ~6144121**，`async function lVn(e,t,n,r)` 经 crypto.subtle HMAC-SHA256——v1.5 二次修订按偏移实读更正：阶段材料原记「src-D3H6NV7w.js@336279」系错挂，该偏移实为参数解析函数 wN 内的 passHash 字段、`lVn` 符号在 src bundle 中 grep 零命中；算法结论本身两侧一致；探针实测向量 TVEonHQI…）；④服务端回 **auth_ack** `{server_ts,device_sid,terminal_sid:'t_…',pair_status:'waiting'|'matched'}`——matched 即 paired 态并启动心跳；首次 waiting 启动 30s 超时（报 invalid-mobile-connection）。⑤应用层经 **data 帧** `{type:'data',payload,client_ts}` 编排：paired 后依次 bootstrap-request→bootstrap-response（desktopAppVersion/initialViewState/tasks）→workspace-list-request/response→（打开工作区）workspace-bridge-open{bridgeSessionId:客户端 UUID,bridgeGeneration:自增,workspaceKey,…}→workspace-bridge-ready{bridge:{recoveryId,kind,workspacePath,…}}→此后 **rpc-frame** 承载标准 RPC；另有 workspace-reconnect、mobile-view-state-update、mobile-diagnostic、telemetry-report 单向帧。心跳：每 10s±jitter 发 pair_status_query→pair_status_ack，30s 无 ack 判 stale 重连。错误帧 `{type:'error',code,message}`：code∈KICKED(终态 session-conflict)/DEVICE_OFFLINE(15s 宽限重连)/AUTH_FAILED|WRONG_PARAM(终态 invalid-mobile-connection，探针错 proof 实测 `{code:'AUTH_FAILED',message:''}`)/INTERNAL。重连：指数退避 min(10000,500·2^attempt) 重连重走 auth_init。WS close code 表（src-D3H6NV7w.js TN）：4004 SessionNotFound/4009 SessionConflict/4010 DesktopDisconnected/4011 SessionExpired/4012 WorkspaceClosed/4013 InvalidMobileConnection。

**中继帧三层封装与既有协议层的关系（framing 八条关键差异，探针实测）**：

| # | 差异 | 结论与证据 |
|---|---|---|
| 1 | **中继桥载荷无 13 字节 SocketProtocol 头（最重要）** | 探针实测下行 Initialize=`04 01 06 c8 01 00`=serialize([200])+serialize(undefined)，listTasks 响应=[202,1,<err>]，均为纯 RPCSerialization 字节；现有 ChannelClient.swift:22-40（RPCFrame.write/read）对每条 WS 消息加/剥 13 字节头（type/id/ack/len）——**中继模式必须绕过 RPCFrame**，直接把 serialize(header)+serialize(body) 作为 rpc-frame 载荷、对下行直接 deserialize；带 13 字节头发送时桌面端虽回 ack 但不产生 RPC 响应（探针第 2 轮实测） |
| 2 | WS 层从「二进制帧」变「JSON 文本帧」 | 现有 ChannelClient.swift:302-311 只收 .data 消息；中继是 text JSON（auth_init/auth_challenge/auth_ack/pair_status_query/pair_status_ack/data/error）——需新增 RelayTransport 承载文本帧 |
| 3 | 新增 rpc-frame 分片/可靠层 | iOS 需实现 bridgeSessionId/seq（物理帧序号逐片递增）/messageSeq（逻辑消息）/fragmentIndex/fragmentCount/crc32 分片收发、rpc-frame-ack 回执（探针实测：不回 ack 桌面端每帧重传 4+ 次）、未确认重放（重连后 resetReplay+重发，replayBuffer 8MB/45s 窗口）、饱和水位（高 1MB/低 256KB，饱和时暂停发送） |
| 4 | 与 V4Wire.swift 的关系：**原样复用** | TopicWireFrame（wireVersion/kind/logicalFrameId/topic/subscriptionId）是 rpc 消息体内 v4 topic 订阅帧，出现位置不变（eventFire(204) 载荷），V4Wire/Assembler 可原样复用；新增的只是它外面三层中继封装 |
| 5 | 鉴权算法需新增 | HMAC-SHA256（见上帧序③）；错误 proof 实测回 `{type:'error',code:'AUTH_FAILED'}` 后应终态不重试 |
| 6 | 心跳/重连需新增 | 10s pair_status_query、30s ack 看门狗、指数退避 min(10s,500·2^n)、close code 六值映射（见上） |
| 7 | 链接识别与探测跳过 | ConnectURLParser 按 https+/remote/ 前缀识别 relay 链接→wss（保留 mid/name/app_version 参数语义）；AppSession 对中继服务器跳过 /api/server-info HTTP 探测，直连 WS 后先走 auth 握手而非等服务端 Initialize |
| 8 | execution 拦截表照旧在 RPC 出口 | 只读边界为客户端自律（§7.8-5），与传输层无关——M1.z 未放松（§9.4）；zcode-task 快照类只读命令照旧放行 |

**常量表（出自 bundle 字节偏移，探针交叉验证；v1.5 二次修订对常量→语义映射按实读复核更正）**：maxPhysicalFrameBytes=**1MB**（整条 data JSON 上限，Ju.maxFrameBytes@122124；超限→onRawTransportFault envelopeTooLarge，>1MB 拒发）；maxMessageBytes=**16MB**、maxFragments=**64**、assemblyTimeoutMs=**30s**、transportIdMaxChars=256（Yv@243672 区）；**bundle 实证常量四个**（src bundle `var AN=1e4,jN=2e3,MN=3e4,NN=2e3`）：AN=心跳 **10s**、jN=2s（心跳抖动上限，配合百分比抖动 ≤min(2s,20%)）、MN=ack 看门狗 **30s**、NN=2s（第二处 2s 常量，具体挂接点未逐一定位）——**「stale 恢复 1.5s」与「desktop 离线宽限 15s」两值降级为阶段材料转述**（bundle 内未能定位对应常量，审阅在 DEVICE_OFFLINE 邻域检索 15e3 未见；iOS 实现按材料值取 15s 宽限，RelayTransport.deviceOfflineGraceSeconds=15，常量出处如实存疑）；crc32=反射 0xEDB88320 hex8（Gv），**与 V4Wire.swift:147 crc32 同参**——探针两侧校验值一致（b4ff6360/883f5baa）；base64 为标准字母表（非 urlsafe）；**分片大小二分自适应（v1.5 二次修订按实现 RelayFrameCodec.swift:41-47 更正描述——阶段材料原文方向有误）**：片长初值=整条消息（1 片），循环条件为「**片数>maxFragments(64) 或单片信封>maxPhysicalFrameBytes(1MB)** 则片长减半」——两约束交集非空（16MB/64 片→每片 250KB→信封约 333KB<1MB，代码注释同口径）；replay 缓冲 unacknowledged>8MB 或 45s 未 ack→degraded（replayBufferExceeded/ackGraceExceeded/replayGraceExceeded），需整通道重建；桌面版本变更检测（bootstrap-response.desktopAppVersion≠URL app_version→弹 reload，jVn）；测试环境 endpointOrigin=https://zcode.chatglm.site 时 relayWsUrl=wss://zcode.chatglm.site/ws（**Mh@181160 起**——v1.5 二次修订实读更正，阶段材料原记 181256 为该函数体内 chatglm URL 字面量位置）。

**会话读取两层路径（探针实测，均只读）**：A. **会话列表（最短路径，无需 bridge）**——paired 后 data 帧发 bootstrap-request→bootstrap-response.result{desktopAppVersion,initialViewState,tasks[]}，及 workspace-list-request→workspace-list-response.result{activeWorkspaceKey,activeTaskId,tasks[]}；tasks[] 元素实测含 taskId('sess_…')/title/displayStatus/createdAt/updatedAt(ms)/provider/hasBackgroundWork/workflowActivity{runs[…]}；81KB 列表单帧直出（<1MB 不分片）。B. **会话历史/详情**——发 workspace-bridge-open 开桥（实测 ready 回 bridge.kind='local'）→rpc-frame 内走 channel='zcode-task' 的标准 RPC：listTasks/listTaskList/getTaskSnapshot 族/getTaskMeta/listArchivedTasks/setTaskUnread/getTaskTokenUsage 等（方法名清单 index-BO-TaBle.js@6198xxx cHn stub；**参数必须含 workspacePath**，实测缺参返回 TypeError）。channel 名表（cA@308570）共 43 个频道（zcode-task/window-controller/zcode-agent/zcode-session/file/media-preview/system/terminal/git/git-checkpoint/setting/credential/broadcast/conversation-share/file-watcher/oauth/provider-settings/model-selection 等）；只读会话的 v4 topic 订阅帧（TopicWireFrame）在 rpc 消息体内经 eventFire(204) 到达，结构与 iOS 现有实现一致。

**逆向 gaps（7 条，如实转记，均标注于 §9.6 对接情况）**：①配对链接 t 参数的服务端过期策略未验证（网页端仅做 Number.isFinite 校验，过期判定在服务端）；②recoveryId 断线恢复路径未实测（首连 workspace-bridge-ready 的 recoveryId=None；带 recoveryId 重开 bridge 的语义只从 JS 反推）；③v4 topic 订阅帧未在真实中继上端到端验证（探针止步于 bridge 内首个 RPC 往返，未发 eventListen 订阅）；④16MB 逻辑消息×64 分片、replayBuffer 8MB 重放、饱和水位等极限路径未实测（探针消息均 <1MB）；⑤auth_ack 的 terminal_sid（t_ 前缀）在后续帧中的作用未确认（pair_status_ack 中为空串）；⑥服务端是否对 execution 类命令有独立边界未知（只读拦截目前只能确认是 iOS 客户端侧既定约束）；⑦desktop 端（role≠terminal）一侧的帧交换未观测。

### 9.2 实现清单（M1.z，行号实测）

**新增 4 文件（Services/Relay/，App 源码 43→47 个）**：

| 文件（行数实测） | 内容 |
|---|---|
| RelayLink.swift（105 行） | RelayLinkConfig（wssURL/machineName/deviceSid/passHash/desktopAppVersion）；RelayCloseReason——WS close code 4004/4009/4010/4011/4012/4013→session-not-found/session-conflict/desktop-disconnected/session-expired/workspace-closed/invalid-mobile-connection + error 帧 KICKED/DEVICE_OFFLINE/AUTH_FAILED/WRONG_PARAM/INTERNAL 映射，**终态判定含取舍说明**（见 §9.6-2）；RelayAuth.proof（**:92 实测**）=base64url 无填充(HMAC-SHA256(key=UTF8(passHash), msg=nonce\|terminal\|deviceSid))，单测锚定探针实测向量 TVEonHQI…（RelayLinkTests.testProofMatchesProbeVector）——**注（v1.5 二次修订）：该实测向量内嵌真实配对凭据（RelayLinkTests.swift:11-22/:62），属「锚定实测」的设计取舍，安全整改（替换合成向量+build 回归）已列 §12 风险 21 整改项；RelayLink.swift:90 注释含完整 sid，同批整改** |
| RelayFrameCodec.swift（244 行） | rpc-frame/rpc-frame-ack 编解码（strict schema 键集，与 bundle ny/ry 逐字段对齐）；seq/messageSeq 双序号；片长二分自适应（≤64 片且单片信封≤1MB）；crc32 反射 0xEDB88320（与 V4Wire.crc32 同参，单测 testCrc32MatchesV4WireAndStandardVector 锚定标准向量）；RelayFrameAssembler 下行重组器（乱序凑齐/重复帧幂等回 ack/30s 组装超时/crc 校验） |
| RelayTransport.swift（802 行，actor） | URLSessionWebSocketTask JSON 文本帧收发；auth 握手（auth_init meta 照 bundle 原样→challenge→response→ack matched）；心跳 10s+jitter≤2s、ack 看门狗 30s、waiting 30s 超时终态、指数退避 min(10s,500ms·2^n) 重连重走 auth（**上限 6 次后终态上抛**，浏览器端无限退避的移动端功耗对应，UI 保留手动重试）、DEVICE_OFFLINE 15s 宽限重连、error 帧分类；**socket 世代计数**区分「同 socket 心跳 matched」与「重连 matched」（对齐 web 端 lastPairedSocketGeneration，避免心跳误触发桥重建）；app 层请求-响应（bootstrap/workspace-list/bridge-open，bridge-ready 按 bridgeSessionId 匹配——桌面回包未必回带 requestId）；rpc-frame 通道（replay 缓冲 8MB/45s、饱和水位高 1MB 低 256KB、下行重组+ack 回执、degraded 上抛） |
| RelayChannelClient.swift（444 行，actor） | 与 ChannelClient 同语义的 call/listen/disconnect（经新协议 **RPCChannelTransport** 门面注入 ZCodeServerConnection）；连接编排 auth→bootstrap→workspace-list→bridge-open→等桥内 Initialize；断线重连后重建桥（bridgeGeneration 递增+携带 recoveryId）并重发全部活跃 eventListen；协议要点落地：①rpc-frame 载荷无 13 字节头；②promise 面入参 body=serialize([arg]) 参数数组（见下「协议修正」①）；③eventListen 面 arg 对象原样（dynamic event 柯里化直传） |

**修改 6 文件（行数实测）**：

| 文件 | 改动 |
|---|---|
| ConnectURLParser.swift（186 行） | parseRelayLink（**:35 实测**，token 解析 :44 起）：https + /remote/ 路径识别→wss://host/ws?mid=…，sid/hash 不进 URL，hash 百分号解码为原字符串；ParsedLink 统一形态（relay 优先/direct 兜底）+ extractLink |
| KeychainStore.swift（199 行） | ServerConfig 增可选 relay 字段（**:96 实测**，Codable 向后兼容，旧 Keychain 数据 decode 为 nil，单测覆盖）；displayName 中继优先机器名（:115） |
| ZCodeServerConnection.swift（654→807 行） | client 协议化为 **any RPCChannelTransport**（**:236-237 实测**；ChannelClient 显式 conform，局域网行为不变）；新增 connectRelay(to:)（**:438 实测**）五步映射（发现=跳过局域网探测标注云端中继/鉴权=auth matched/WS=paired/握手=bootstrap+bridge-open+桥内 Initialize/工作区=bridge.workspacePath）；桥内先 v4 握手（hello→initializeConversationV4）再订阅（**桌面桥有 handshakeRequired 闸，局域网 server 无闸故 connect() 顺序不变**）；连接日志记录中继握手帧（auth_init/auth_challenge/auth_ack/bootstrap-response tasks 数/bridge-ready kind/path），DEBUG 下同步进 unified log 供 simctl log stream 真机诊断；伪 ServerRemoteInfo 供既有 Store 装配复用；**ReadOnlyGate 出口拦截对中继路径同等生效**（call 单一出口未变，§9.4） |
| AppSession.swift（368→410 行） | bootstrap 增 `-ZCodeRelayLink <url>` 调试钩子（**:145-167 实测**：解析→持久化→直连，按 deviceSid 复用注册表条目——真机验收入口）；connectToSaved 分流 relay/局域网（:195）；probeSavedServer 对中继服务器跳过 HTTP 探测（:405） |
| ConnectFlowView.swift（733 行） | 剪贴板横幅支持云中继配对链接（icloud 图标+机器名·云中继·host）；手动输入框支持粘贴中继链接（识别提示）；近期连接卡片云中继徽标；ScanView/ConnectingView 相应适配 |
| RemoteConversationStore.swift（926→944 行） | subscribeSessionsIndexV4 回执解析兼容 `{ack:{subscriptionId,mode,logEpoch}}`（**:147-149 实测**，中继桥实测形态，原顶层解析得 nil）；帧 handler 先于订阅注册（中继快照帧在回执前即推，防丢首帧）；SessionSummary lastActivityAt 双形态兼容（真实桌面毫秒时间戳/替身 ISO8601，真实桌面局域网路径同样受益）；订阅成功/失败进连接日志 |

**实现期协议修正（对 §9.1 逆向结论的 5 条补充，均由真机+探针实测得出）**：①桥内 RPC 的 promise 面 body=serialize(**[参数数组]**)——桌面 toService 代理将调用参数列表整体序列化、host fromService apply 展开；对象直传会被解成 undefined（listTasks 报 TypeError reading 'workspacePath'，数组包对象同帧成功返回 []）；②eventListen 面 arg=对象原样不数组化（dynamic event 柯里化直传，数组化后桌面不推快照帧）；③桥内有 handshakeRequired 闸：必须先 hello→initializeConversationV4 再订阅；④subscribeSessionsIndexV4 回执形如 {ack:{subscriptionId,mode,logEpoch}}；⑤sessions-index 快照的 lastActivityAt 为毫秒时间戳。

**既有面零改动声明**：演示模式与局域网直连模式行为未变——ChannelClient 仅加显式 conformance 声明、connect() 原有五步与 ReadOnlyGate 出口未动；ServerConfig.relay 为可选字段且旧数据兼容有单测（testServerConfigDecodesLegacyJSONWithoutRelay）；ZCodeMobileE2ETests/ZCodeMobileLoginE2ETests/LayoutAuditTests/E2ELoginStubServer 及 ReadOnlyGateTests 文件内容不变（行数微差见 §5 规模注记，如实标注未溯源）。

### 9.3 真实链路端到端验收（connected=true / sessionsLoaded=true，验收会话实地产出）

**结论：真实云中继链接一次连通，协议全程匹配，0 轮代码修复（应用代码无需改动）。**

| 验收步 | 结果与证据 |
|---|---|
| 1 探活 | `curl -s -o /tmp/relay-probe.html -w "…"` 对交接材料真实配对链接（https://zcode.z.ai/remote/v4?sid=…&hash=…%3D&t=…&mid=…&name=MacBook-Pro-8.local&app_version=3.14.4）→ **HTTP 200 / text/html / 18,180 bytes**，正常 React 网页壳（深色主题+App 图标），无过期/登录跳转（/tmp/relay-probe.html 在位，本轮撰写会话实测 18,180 字节一致） |
| 2 构建安装启动 | `xcodebuild … -configuration Debug -destination 'platform=iOS Simulator,id=6F5AD678-…' build` → **BUILD SUCCEEDED**；`xcrun simctl install` 成功；`xcrun simctl launch 6F5AD678-… cn.biuz.mobile -ZCodeRelayLink "<链接>"`（进程 67015） |
| 3 连接过程日志 | 后台 `xcrun simctl spawn … log stream --predicate 'eventMessage CONTAINS "relay-log"'` 全程捕获（/tmp/relay-log-stream.txt，65,805 字节 / 430 条 relay-log 为 v1.5 撰写会话彼时抽查值——**该文件现已不存在，v1.6 二次修订实测 MISSING**，数值仅存本节文字）。时间线：01:11:25.111 云端中继·跳过局域网探测→zcode.z.ai/ws → 01:11:25.278 WS 升级 + auth_init·role=terminal·sid 与链接一致 → 01:11:25.388 auth_challenge·nonce=n5IFDGxD…→auth_response（HMAC-SHA256 proof，RelayLink.swift:92）→ 01:11:25.486 **auth_ack·pair_status=matched**·terminal_sid=t_NjdmrhBduZQsVsw62KEXz → 01:11:25.703 **bootstrap-response·3.14.4·tasks=240**（与链接 app_version 一致，无版本漂移告警）→ 01:11:25.926 workspace-bridge-open·gen=1·workspace=/Users/mac/projects/zcode_mobile（本机真实项目目录）→ 01:11:26.102 **workspace-bridge-ready·kind=local** → 01:11:33.971 桥内 Initialize 已到·RPC 通道就绪 → 01:11:34.311 桥内 v4 握手·protocolVersion=3·deliveryProfile=replayable → 01:11:34.810 subscribeSessionsIndexV4·six-mupwpij8-4b0310hw-16。**本轮撰写会话日志抽查**：`grep -c pair_status=matched` = 405 次（心跳 44 分钟持续）、tasks=240/kind=local/protocolVersion=3 各 1 次均在案 |
| 4 connected=true 依据 | 中继五步全链路完成（auth matched→bootstrap→bridge-open/ready→桥内 Initialize→桥内 v4 握手 protocolVersion=3）；auth_ack 心跳 pair_status=matched 持续至 01:55:39（**连接保持 44 分钟+**，进程未重启）；UI 稳定呈现真实数据 |
| 5 sessionsLoaded=true 依据 | 截图 /tmp/relay-verify-t4.png~t8、final、postcheck 显示会话列表两条**真实桌面会话**：「ZCode移动端立项与开发流程」今天 1:09（摘要「边界口径已对齐并落进验收工作流草稿」）、「zcode社区版起名与 Logo 设计避…」昨天 5小时前。**非 mock 佐证**：MockConversationStore.swift 的 6 条 mock 标题（v1.5 时引 :276-302，v1.6 二次修订复测标题现网 :288/:294，勘误·续二）（重构会话持久层/修复登录超时问题/首页性能调优/生成周报·第40周/API v3 迁移评估/补齐单元测试）与截图标题零交集，且 t1 截图恰好拍到启动瞬间的 mock 数据（即上述 6 条），与 t4 真实数据形成对照；会话主题与 docs/立项报告.md、docs/relay-handoff.md 真实工作内容吻合。桌面端共 240 个任务（bootstrap tasks=240），App 列表呈现最近会话行 + tab 徽标（会话3/任务2/文件3）——呈现真实桌面数据，非空态 |
| 6 只读边界 | 连接日志全部上行行为核对（grep requestId/send/发送）：仅 auth_init/auth_response 帧、→bootstrap-request、→workspace-list-request、→workspace-bridge-open 三个编排请求、桥内 v4 握手（helloConversationV4/initializeConversationV4）与订阅注册（eventListen、subscribeSessionsIndexV4、subscribeWorkspaceConfigV4），**全部为握手/只读/订阅面，无任何 execution 类命令发送**（详见 §9.4） |

**证据文件（原在验收机 /tmp，工作区外；**v1.6 二次修订实测更新：现已全部不存在**——审阅 `for p in /tmp/relay-log-stream.txt /tmp/relay-probe.html /tmp/relay-verify-t1.png /tmp/relay-verify-t4.png /tmp/relay-verify-t8.png /tmp/relay-verify-final.png /tmp/relay-verify-postcheck.png …; do [ -e ] && echo OK || echo MISSING; done` 14 条全 MISSING，本报告撰写会话复跑同结果）**：/tmp/relay-verify-t1.png~t8.png（逐秒过程）、/tmp/relay-verify-final.png（01:13 最终态）、/tmp/relay-verify-postcheck.png（01:55 44 分钟稳定性复核）、/tmp/relay-log-stream.txt（连接日志全文）、/tmp/relay-probe.html（探活响应）——v1.5 撰写会话彼时实测在位并抽查复核一致（大小/条数见上，以文字留存）；**自此 M1.z 真实链路端到端验收在工作区内外均无任何可复核凭据**，验收结论降级为「v1.5 验收会话记录、彼时复核一致、现不可复核」（§12 风险 1/2 更新；重建验收需桌面端重新生成配对链接后按 §9.3 流程复验，列 §14.2）。逐秒截图 t1-t3 为演示态（mock 数据），t4 起切换为真实桌面数据，完整记录切换过程。

### 9.4 只读边界在中继路径的保持

**结论：ReadOnlyGate 出口拦截对中继路径同等生效，中继未引入任何新的执行面。**

1. **代码面（本轮实测）**：中继 client 与局域网 client 共用同一门面——ZCodeServerConnection.client 声明为 `any RPCChannelTransport`（:236-237，ChannelClient 与 RelayChannelClient 显式 conform），全部 RPC 汇入唯一出口 `call`（v1.5 时 :786，**v1.6 二次修订现网 :795**），execution 命令在出口被 ReadOnlyGate 拦截并留存最近 20 条拦截记录（**现网 :803**）、抛 ReadOnlyViolation（**现网 :806**）——**拦截逻辑不区分传输层**（M2 后口径为四分类、仅拦 directWrite，§10.4），黑名单对中继路径原样生效；真实验收材料引 :791-799 为 v1.5 时点同一区间（勘误·续二）。**注意（v1.6）**：本小节描述的「三分类与 201 命令黑名单」为 M1.z 时点口径，M2 已修订为四分类（§10.4），中继路径拦截随出口同步生效的结论不变。
2. **运行面（验收日志核对）**：连接日志全部上行行为核对仅含 auth_init/auth_response、bootstrap-request、workspace-list-request、workspace-bridge-open、桥内 v4 握手（helloConversationV4/initializeConversationV4）与订阅注册（eventListen、subscribeSessionsIndexV4、subscribeWorkspaceConfigV4）——全部为握手/只读/订阅面，**无任何 execution 类命令发送**。
3. **逆向面（如实）**：服务端是否对中继链路的 execution 类命令有独立边界**未知**（§9.1 逆向 gap ⑥）——只读拦截目前只能确认是 iOS 客户端侧既定约束；中继链路把 RPC 透传至桌面端，服务端不收窄命令面的结论（§7.2）在云中继路径上没有理由不同样成立，但未经服务端代码证实，客户端自律边界因此更重要（§12 风险 20 关联）。
4. **新增上行面分类**：M1.z 新增的上行帧全部为握手/编排/订阅面（auth 帧、pair_status_query 心跳、rpc-frame-ack 回执、bridge-open/ready 编排）与既有 40 call+7 listen 的透传——**零新增业务命令**；zcode-task 快照类只读命令照旧放行（§9.1 framing ⑧）。

### 9.5 门禁结果与测试面

| 门禁 | 结果 | 执行记录 |
|---|---|---|
| 构建 | **通过 = true** | 双执行主体：①实现会话自检 `xcodegen generate` + `xcodebuild -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -sdk iphonesimulator build` → BUILD SUCCEEDED、`build-for-testing` → TEST BUILD SUCCEEDED（阶段口径）；②真实验收会话再次实跑 build → BUILD SUCCEEDED 并完成 simctl install/launch（§9.3 步 2，独立执行主体的构建证据） |
| e2e | **通过 = false（如实标注）** | 宿主机 PTY 泄漏（内核层）致 XCUITest 测试 runner 无法启动（Pseudo Terminal Setup Error，Errno 6 Device not configured；iPhone 18 Pro 与 iPhone Air 两台模拟器均复现，CoreSimulatorService 重启/多台模拟器/erase 均无法绕过且不可杀用户调试进程）——**e2e 门禁待环境重启后补跑**；新增 RelayLinkE2ETests 4 用例为编译级验证（build-for-testing 通过 + nm 确认 ZCodeMobileUITests 产物内含该测试符号 107 处，阶段口径） |
| 单元级 | **见下行如实分级** | ①实现会话曾在环境正常窗口（22:29）实跑全量单测一次：**26 用例（ReadOnlyGate 10 + RelayLink 16）TEST SUCCEEDED**（阶段口径，无 xcresult 留存）；其后三处协议修正（§9.2 ①②④）涉及的单测断言改由 **macOS 同源等价验证覆盖**——用 swiftc 直接编译工程内同一份 RelayLink/RelayFrameCodec/ConnectURLParser/KeychainStore/RPCSerialization/V4Wire 源文件执行与单测相同断言，**32 项全 PASS**（/tmp/relay_swift_verify 在位，本轮撰写会话实测）；②**本报告撰写会话实跑**：`xcodebuild test -only-testing:ZCodeMobileTests -destination 'platform=iOS Simulator,id=6F5AD678-…'` → **未能运行**（测试 runner 启动失败的 PTY 环境阻塞，独立复现阶段自述故障；详见下方括注；单测行数以实测 18 用例为准，与阶段自报 16 的差异见 §5 规模注记） |
| 真实链路端到端 | **pass（真实验收会话实地产出）** | 见 §9.3——该项在测试计划材料中标注 pending（彼时截图证据未获得），后由真实验证工程师以 `simctl launch -ZCodeRelayLink "<配对链接>"` + 截图/日志证据完成验收（connected=true/sessionsLoaded=true），本报告按证据采信（v1.5 撰写会话对日志与证据文件抽查复核一致） |
| 门禁脚本入库 | 仍缺 | 工作区门禁脚本/xcresult `find` 复跑零命中（本轮实测）——§12 风险 1 对本轮门禁同样适用 |

> **撰写会话单测实跑结果括注（v1.5，如实）**：本报告撰写会话实跑 `xcodebuild test -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -only-testing:ZCodeMobileTests -destination 'platform=iOS Simulator,id=6F5AD678-…'` → **未能运行**：app 与单测 bundle 编译链接完成后，测试 runner 安装/启动失败——`Pseudo Terminal Setup Error. ErrorCode: 7 Errno: 6. (The operation couldn't be completed. Device not configured)`、`Failed to install or launch the test runner`、等待 600s 超时后 `** TEST FAILED **`（xcresult 留痕：~/Library/Developer/Xcode/DerivedData/ZCodeMobile-…/Logs/Test/Test-ZCodeMobile-2026.10.04_03-21-42-+0800.xcresult——**v1.6 二次修订实测：该留痕已不存在**，DerivedData 现仅存 2026.10.04_11-05-25 与 11-43-31 两枚；11-43-31 为 v1.6 撰写会话单测 40/40 通过的 xcresult、时间戳与 §10.7 吻合，该佐证成立）。**该失败独立复现了实现会话与验收会话自述的宿主机 PTY 泄漏（内核层）**——非用例断言失败，属环境级故障；阶段材料所述三处协议修正后的单测结论仍以 macOS swiftc 同源等价验证 32 项 PASS（/tmp/relay_swift_verify）为最强可得证据。**推论（对门禁采信的意义）**：e2e=false 与单测不可运行同源，均为环境阻塞而非代码回归——但本报告不以此推断「补跑必过」，补跑仍列入 §14.2 第 1 项。

**测试面（40→62 用例）**：新增 ios/Tests/RelayLinkE2ETests.swift（200 行 **4 用例**，实测）——test01 手动连接页 /remote/ 配对链接识别与解析反射（断言无直连解析报错 l1-field-host-err + 解析成功提示 l1-parse-ok 携带机器名）、test02 拦截负例（非 /remote/ 路径与带 token= 的链接不得进中继分支，按直连规则报缺端口）、test03 `-ZCodeRelayLink` 有效链接冷启动发起中继连接→回环拒连失败后出现「桌面端连接失败 · 已回退演示数据」横幅且演示会话行仍在、test04 无效链接（缺 hash）直接回演示态无横幅；用例全部指向 127.0.0.1 回环不触外部中继端点（解析器按逆向结论固定 wss 端口 443，本机即刻拒连），**真实中继链路按约定不做 XCUITest**（依赖外部桌面端活会话的时效 sid/hash，以 §9.3 真实验收替代）。新增 ios/UnitTests/RelayLinkTests.swift（347 行 **18 用例**，实测）——配对链接解析/拒绝非法输入/proof 实测向量/帧 strict 键集/分片上限与信封上限/乱序重组与重复帧幂等/crc 失配/-ZCodeRelayLink 参数/ServerConfig 旧数据兼容/lastActivityAt 双形态等。既有套件（演示 7 + 登录 15 + 布局走查 8 + ReadOnlyGate 10）零改动随原目标编译。

### 9.6 已知限制与逆向 gaps 对接（如实）

1. **逆向 gaps 的实现对接（7 条逐项）**：①t 参数过期策略未验证——App 不本地判过期，服务端拒绝时经 auth/错误帧终态上抛 UI；②recoveryId 未实测——**断线重连采用保守策略：重连 paired 后重建桥（generation+1+recoveryId）而非同桥重放未确认帧**，pending RPC 失败由 Store 层 resync/刷新自愈；③v4 topic 订阅帧未端到端验证——iOS 现有实现假定不变（V4Wire/Assembler 原样复用，§9.1 framing ④），真实验收中 sessions-index 快照帧已经桥内到达（§9.3 时间线），覆盖了订阅主链路；④16MB×64 分片、replayBuffer 8MB 重放、饱和水位等极限路径未实测（探针消息均 <1MB）——代码已实现、单测覆盖分片上限与信封上限的纯函数面，真实大消息路径待联调；⑤terminal_sid 作用未确认——实现按不透明字符串透传保存；⑥服务端 execution 边界未知——客户端自律边界（§9.4-3）承担全部拦截职责；⑦desktop 侧帧交换未观测——waiting→matched 的桌面行为只从客户端代码反推，实测层面以「auth_ack matched 即可用」为口径。
2. **close code 终态取舍**：4004/4011/4013 终态不重试，4009/4010/4012 走重连（桌面侧暂态）——与 web 端全量重连策略不同（移动端功耗取舍），代码注释含取舍说明。
3. **重连上限**：指数退避重连 6 次后终态上抛（浏览器端无限退避的移动端功耗对应），UI 保留手动重试。
4. **桌面桥不支持 workspace-config 订阅**（subscribeWorkspaceConfigV4 服务端未提供）：chips 走缺省展示，日志可诊断；桥内 hello/initialize 失败时降级继续（旧版本兼容），日志标注。
5. **e2e 门禁未跑**（PTY 环境阻塞）——新增 4 个 UI 用例的运行时行为未经实跑校验，环境恢复补跑时可能需微调（与 M1.y test13/14/15 同类如实标注）。
6. **凭据面**：passHash（配对链接 hash 原字符串）即 HMAC 密钥，仅存 Keychain（ServerConfig.relay 可选字段）；sid/hash 随配对链接存在时效性（服务端过期策略未验证，§9.1 gap ①），凭据泄露面与时效风险见 §12 风险 21。

## 10. M2 迭代：逐功能自测验收（v1.6 新增章节）

本章记录 M2 迭代全部产出：迭代背景与纠偏结论（10.1）→ 桌面功能对照与适配决策（10.2）→ 竞品交互参考与采纳情况（10.3）→ 只读边界口径修订（10.4，本轮纠偏地基）→ 实现清单与补全项落地（10.5）→ 逐功能验收清单（10.6，24 项全表）→ 门禁结果与测试面（10.7）→ 已知限制（10.8）。证据来源：①**M2 阶段材料四件**——桌面功能对照（23 项，含桌面端 file:line 证据）、竞品交互参考（Qoder/Trae 19 条 pattern 与建议）、验收清单与补全计划（checklist 24 项 + gaps 12 项）、补全实现记录（含实现会话自检）与验收测试记录（24 项逐项状态与证据）；实现/验收会话的材料为本节「阶段口径」，本报告撰写会话（第七轮实测，§11.6 末条）对工作区逐项实测核验并**实跑单元测试**（40/40 通过，§10.7）；②M2 测试计划材料（替身/E2E/单测改写口径）。**命名说明**：本轮迭代阶段材料命名为「M2：逐功能自测验收」；§14.1 路线里程碑「M2 真机与系统能力」为待批研发里程碑编号，两者同名不同指——本迭代性质同 M1.x/M1.y/M1.z，为立项批准前完成的前置事实、报请评审追认，记入 §14.1 M1 里程碑行。

### 10.1 迭代背景与核心结论：过度限制纠偏

**背景**：v1.3 确立的连接态只读边界（§7.2）经 M2 阶段材料逐项核对，被认定为**过度限制**——它把「客户端发命令、桌面执行」的合法面与「手机直写文件/回滚/仓库」的禁止面混为一类全拦。阶段材料给出的核对结论（全部附 file:line 证据，撰写会话逐项复核属实，§10.7）：①五处 UI 只读降级（ChatView 输入区锁形提示、提问卡快捷回复降级、ApprovalSheet 动作栏隐藏、TaskOutput 停止按钮替换、NewConversationSheet 表单降级）；②六个动作方法空实现（RemoteConversationStore 的 send/answerQuestion/resolveInteractionRaw 与 RemoteTaskStore 的 approve/reject/stop）；③ReadOnlyGate 把 sendText/resolveInteraction/stop/retryTurn/队列命令/带 firstInput 的 createSession 全归 execution 并在 RPC 出口拦截（v1.5 时点 :38-47/:61-68，拦截出口 v1.5 时点 :786-797、**v1.6 现网 :795-806**，§7.2 勘误·续二）。

**纠偏后的边界语义（v3 口径，用户澄清后固化于 ReadOnlyGate.swift 文件头注释，实测 :5-23）**：**放行 = 客户端发命令、由电脑/云端进程代执行**（消息/审批应答/停止与队列/会话任务管理/任务元数据写）；**拦截 = 手机直写面**（文件直写与回滚、git 仓库破坏性写、宿主 terminal、桌面配置与凭据写族）。v4 命令面仅 applyFileRewind（会话内文件回退）属直写；file 频道由黑名单口径改为「**默认拒绝 + 读白名单**」（仅 readdir/readTextFile/stat/searchWorkspaceFiles/readBinaryPreview 放行，未知/写命令一律拦）。git 写 8 项、terminal 4 项、settings/onboarding/settings-sync/credential/oauth/plugin/skill/mcp-sync/bots/coding-plan 等写族黑名单**全部保留**（本轮非开放对象）。

**核心结论**：①连接态四类交互恢复——发送消息（sendText 信封+乐观回显）、审批与提问应答（resolveInteraction 信封）、停止任务（v4 stop）、新建会话带首条指令（createSession+firstInput）；②会话管理补全——重命名（renameTask）、标记未读（setTaskUnread 反向）、已归档分组与取消归档（listArchivedTasks/unarchiveTask）；③本地通知真实化——「通知」假开关接 UNUserNotificationCenter，三类内容映射；④P2 部分兑现——会话内搜索（已加载范围）、任务服务端搜索、产物行结构化、图片/PDF 只读预览；⑤**验收判定（阶段/验收会话口径，v1.6 二次修订按审阅意见降级表述）**：验收清单 24 项按验收材料判定全部 pass（§10.6），构建通过=true、e2e 通过=true——**但汇总口径不得强于项级证据**：24 项中第 1 项（OAuth）材料自注「本会话未运行、列交付门禁」、第 14/15 项所引扩展断言与第 18 项子项②的断言载体为孤儿文件（不参与编译、不可执行，§10.8-2）、e2e 的截图证据无任何落盘路径可复核；**本报告不再以单一「全流程通过」表述 M2 门禁**，如实口径为：**报告撰写会话实跑单元测试 40/40 通过**（§10.7，v1.5 遗留「单测待补跑」闭环、PTY 环境阻塞消除）+ 编译内断言在位（build-for-testing 通过）+ 其余按材料分级转记（§10.6 分级口径）。

### 10.2 桌面功能对照与适配决策（23 项全表）

阶段材料对桌面端功能逐项对照（含桌面仓库 file:line 证据），给出 must/nice/skip 三档适配结论；本轮收入 must 3 项与高价值 nice 项，skip 项不接入。统计口径：**对照数组实测 23 项 = must 3 + nice 14 + skip 6**；阶段小结称「8 项 skip」，其差额系 nice 行内注记的写面子项（分支切换、套餐重置等保持不接入），两口径差异如实标注。

| # | 桌面功能（桌面证据） | 适配 | 移动端现状与本轮处置 |
|---|---|---|---|
| 1 | 会话重命名（TaskRenameDialog；renameTask 持久化 tasks-index，zcodeTaskService.ts:658/适配器 :2919；经 ProxyChannel 暴露 remoteServiceAccess.ts:129） | **must** | 原仅有置顶/归档滑块且无 renameTask 调用；不在任何拦截黑名单（与 setTaskPinned/archiveTask 同为 tasks-index 元数据写）。**本轮落地**：行长按菜单「重命名」（ConversationListView.swift:207-224 实测）+ renameConversation 双写（RemoteConversationStore.swift:816），演示态同步可用 |
| 2 | 已归档会话查看与取消归档（WorkspaceArchivedTasksFlatSection.tsx:34；listArchivedTasks/unarchiveTask/deleteArchivedTask，zcodeTaskService.ts:631-674） | **must** | 原归档后在手机上彻底消失且 setArchived(false) 仍误发 archiveTask。**本轮落地**：「已归档」分区（04-act-archived :175）+ archivedConversations()（listArchivedTasks 只读拉取 :871）+ 取消归档改发 unarchiveTask（:807，04-archact-unarchive-*）；批量删除为破坏性写，按材料 skip 不做 |
| 3 | 任务状态/待审批系统通知（dispatchTaskNotification，desktopNotifications.ts:17-125；(status,taskId) 3 秒去重/聚焦抑制/点击聚焦） | **must** | 原 grep UNUserNotification 全仓 0 命中、设置页开关为假开关。**本轮落地**：NotificationService.swift（139 行，实测）三类内容映射 + 3 秒去重 + 开关联动授权与撤销；通知到达与点击路由为真机手动验收项（未验，如实标注，§10.8） |
| 4 | 会话标记未读（task-context-menu-content.tsx:129；setTaskUnread zcodeTaskService.ts:676） | nice | 原只有清零方向（unread:false compare-and-clear）。**本轮落地**：长按菜单「标记为未读」+ markUnread（:840，unread:true 补齐反向） |
| 5 | 会话分组与手动排序（task-context-menu-content.tsx:48-95；createTaskGroup/renameTaskGroup 等 :404-437） | nice | 手机窄屏多级分组收益有限；本轮不做，移动端维持「置顶 + 时间分组」两级（相关写面均为索引元数据、技术上可接，列后续候选） |
| 6 | 任务看板多视图（分组/时间线/置顶/归档四形态） | nice | TaskBoardView 已有四段状态分组；时间线视图最契合扫读、列为优先复用候选；分组/归档形态已由本表 #2/看板覆盖，不照搬视图切换 |
| 7 | 任务全文搜索（useGlobalTaskList.ts:118 透传 searchQuery；zcodeTaskService.ts:415-423 服务端 searchable_text/snippets） | nice | **本轮部分落地**：任务侧 searchTasks 透传服务端检索 + 400ms 防抖（TaskBoardView.swift:92 实测，空回执回退本地过滤）；会话侧服务端检索未接（无已验证检索面，如实标注） |
| 8 | 任务变更摘要（WorkspaceHeader.tsx:33；taskChangeSummary.ts:82） | nice | 本轮未做摘要层（移动端已有 Diff 三分段）；列下一批候选（只读投影、契合列表行扫读） |
| 9 | Git 提交图谱与分支只读信息（GitBranchSwitcher.tsx:46、GitGraphDialog.tsx:59；getLocalBranches/getBranchComparison/getRepositorySummary git.ts:33-42） | nice | 只读查询、defer 家族「git 只读无展示位」；本轮未做仓库信息卡（列候选）；分支切换 switchBranch 属仓库写、保持拦截不接入 |
| 10 | Git 暂存/提交/推送等仓库写（git.ts:43-51 stage/unstage/discard/commit/push/切分支/AI 提交信息） | **skip** | 全部为仓库写且保持三层拦截（git 写 8 项黑名单保留，本轮重写后为 directWriteGitCommands）；手机无法审查冲突与分支状态，写仓库留在桌面 |
| 11 | MCP/插件/Skills/子代理/命令/Hooks 只读清单（settingsNavigation.ts:9-27；PluginStorePage 独立页） | nice | defer 家族「无消费 UI 的只读面」；建议后续设「桌面能力」只读页（本轮未做，列候选）；安装/卸载/启停/同步写面保持拦截 |
| 12 | 自动化（定时任务）只读列表与运行历史（settingsNavigation.ts:38-40；listRuns automationService.ts:463） | nice | defer 表明确列可优先启用的只读位；本轮未做（列下一批候选）；布置/启停/立即运行写族（7 项）保持拦截 |
| 13 | 工作流运行只读视图（zh-CN.ts:4855-4856、4696-4706；WorkflowNotificationArtifactChips） | nice | conversationWorkflowRun*V4 读族 9 项在 defer 清单；本轮未做（列候选）；启动/恢复/调整为 execution 词表拦截项、保持拦截 |
| 14 | 会话分享预览·扫码看分享（ConversationShareMenu.tsx:22；getCapabilities/preflight/getPreview/getImportedConversation conversationShare.ts:425-442） | nice | defer 表建议优先启用 getPreview 的只读页；本轮未做（列候选）；publish/importShare 写面保持出口拦截 |
| 15 | 会话附件与媒体预览（ChatMediaAttachmentPreviewDialog；media-preview 服务） | nice | **本轮部分落地**：产物行文案结构化（📦 产物 · 名（类型））；附件图片缩略图/全屏未做（无已验证附件读 API 形态，如实标注，§10.8）；音视频 local-url 态需自建播放器、后置 |
| 16 | 文件预览富类型（PreviewPane.tsx:480 图片/PDF/Office 分发） | nice | **本轮部分落地**：图片（png/jpg/jpeg/gif/webp/heic/bmp→UIImage）与 PDF（PDFKitView）只读预览（FileTreeView.swift:272/:307/:420 实测，readBinaryPreview 分页读、失败降级说明）；Office 渲染成本高、后置 |
| 17 | 应用用量与工具调用统计（UsageStatsSection；getSnapshot/getAppUsageSnapshot usageStats.ts:21-32） | nice | 套餐剩余额度已接真实数据（属 exists 不列缺口）；应用用量分布为纯只读查询、列 nice 候选；套餐重置操作为写面且在黑名单、保持 skip |
| 18 | 会话内消息搜索（ModelTrajectoryPane + ModelTrajectorySearch 一族） | nice | **本轮部分落地**：ChatView 搜索条（05-act-search :45/05-search-messages :114 实测），已加载 messages 本地检索 + 检索前自动 loadOlder 扩页一次；命中高亮与上下跳转未做（如实标注） |
| 19 | 设置同步（settingsSyncService.ts 设备间同步/导入） | **skip** | defer 归「桌面职责面」：移动端无可同步的桌面设置本体，方法族保持出口拦截 |
| 20 | 集成终端（Terminal.tsx；terminal 频道 create/write/resize/dispose） | **skip** | 纯执行面、四命令保持拦截；任务输出已有只读轨迹流（RemoteTaskStore 轨迹投影 + TaskOutputView 承载） |
| 21 | 画板/内嵌浏览器/电脑控制 CUA（WhiteboardPane.tsx:22；cuaPermissionPanel；settingsNavigation.ts:40-46 computerUse 已隐藏） | **skip** | 均依赖桌面宿主窗口与本地权限、移动端无宿主载体；画板/截图类产出并入 #15 附件只读渲染覆盖查看需求 |
| 22 | 命令中心与快捷键定制（CommandCenterDialog；zh-CN.ts:462-466/:1947 明确「仅桌面端生效」） | **skip** | 键盘/窗口形态能力、触屏无对应交互；移动端各页已有独立搜索框 |
| 23 | 应用内更新与发布说明（UpdateStatusButton/UpdateStatusDialog/UpdateReleaseNotesTooltip） | **skip** | 桌面自更新链路；iOS 走 App Store 更新机制、无对应物 |

### 10.3 竞品交互参考与采纳情况（Qoder/Trae 19 条）

阶段材料给出 Qoder/Trae 两竞品 19 条交互 pattern 与逐条建议（来源标注 qoder.com/trae.ai 官方文档与更新日志，部分转引自教程/报道并如实标注）。本轮按「已落地/部分落地/后续候选/不适用」四级归档；**注意**：竞品证据仍为文字材料、无落盘截图（§13 决策信息 ⑤ 口径不变）。

| # | 竞品参考（来源） | 采纳级别 | 对应落点 |
|---|---|---|---|
| 1 | Qoder「Agent control, wherever you are」四件事定位：监控/审批/回答/追加，App 只是窗口 | **已落地（能力面）** | 纠偏后连接态四能力与之一致（发送/审批/停止/查看）；文案与首屏信息架构未专门改版（列候选） |
| 2 | Qoder 会话列表三态 + 云端/本地双来源统一混排 | 部分 | 置顶/时间分组 + 本轮新增已归档分区（#2）；「💻电脑/☁️云端」来源徽标未做（列候选） |
| 3 | Qoder 任务四态语义化（Running/Action Required/Ready/Error）+ 状态分段筛选 | 部分 | 看板四段（待操作/进行中/失败/已完成）已对应；「待你处理」置顶命名与筛选 chip 未做；待审批角标（pendingInteractionSummary）已具备 |
| 4 | Qoder 任务卡片信息层级（标题+描述+workspace+徽章；长按快捷菜单） | 部分 | 行=头像+标题+胶囊+摘要+相对时间；**本轮落地长按菜单**（重命名/标记未读/复制会话 ID/归档，对齐参考）；项目名·分支行未做 |
| 5 | Qoder Plan 手机审阅后放行（计划时间线卡 + 放行/驳回） | 后续候选 | 未做计划时间线卡（挂起交互中 plan 类仅按类型标注）；列下一批候选 |
| 6 | Qoder 审批请求结构化呈现（命令/路径/目标/原因 + scope 三档 + 风险配色） | **已落地** | ApprovalInteractionCard 结构化排版（类型/mono 命令体/路径/影响，05-approval-card ChatView.swift:306 实测）+ 三档 scope（05-choice-once/task/always）注入 answer，与参考逐项对齐 |
| 7 | Qoder 手机可见权限档位 + 「最近 7 天全量+增量」同步策略 | 后续候选 | 审批模式徽章未做；列表缓存秒开+静默增量未做专项优化（resync 自愈已具备） |
| 8 | Qoder 扫码配对三步 + 设备在线状态 + 断连黄色横幅 | **部分（断线面本轮闭环）** | 配对三通道已有；**本轮新增断线黄色横幅+重连**（AppSession 订阅 onConnectionDropped、test17 验证）；设备在线绿点/多机切换未做 |
| 9 | Qoder Summary 面板（当前步骤+改动规模+工具/技能 chips 下钻） | 部分 | 任务详情元数据三行+轨迹投影已有（M1.y）；工具卡可展开；会话头部摘要卡与改动规模行未做（列候选） |
| 10 | Qoder Review 作用域三档切换 + 外部改动警示 + diff 行评论 | 部分 | 三分段（未暂存/已暂存/本次会话）M1.y 已落地；⚠️ 外部改动标记与 diff 行评论未做（后者是少数高价值可写点，列候选） |
| 11 | Qoder 云端任务委派四步流 + 定时任务运行历史页 | 后续候选 | 新建会话表单本轮恢复完整形态（标题/执行端/首条指令）；云端委派与定时任务列表列候选（依赖云端执行面） |
| 12 | Trae SOLO 三栏压成「一轴一抽屉」+ 多智能体参与者头像行 | 部分 | 消息流+工具卡+产物行已内联；参与者头像行未做 |
| 13 | Trae Plan 可编辑再执行 + 引用追问 | 后续候选 | 只读计划时间线与「引用追问」（@步骤）未做；与 #5 同批候选 |
| 14 | Trae SOLO Mobile「单行追问+长按语音」调度台定位 | 部分 | 输入区本轮恢复可发送；语音仍为占位交互（§12 风险 8 口径不变）；「地铁上说一句」语音链路列 M2 真机与系统能力里程碑 |
| 15 | Trae APP 四个已验证细节（产物内联播放/+ 引用/分享链接二维码/Query 分段 chips） | 部分 | 产物行本轮结构化（图片内联渲染待附件读 API，§10.8）；**会话内搜索本轮落地**（对应 Query 分段的检索面，命中跳转未做）；「+」引用与分享未做（列候选） |
| 16 | Trae Builder 变更确认（全部接受/拒绝 + 单文件滑动） | 部分 | 「全部批准」为本地决策记录（服务端无逐文件批准接口，口径不变）；单文件滑动操作与「标记已审」未做 |
| 17 | TraeGo 移动通知提案（上下文感知三级通知 + 快捷按钮） | **已落地（本轮核心之一）** | NotificationService 三类映射（完成带摘要/待审批/失败带末错误）+ 80 字截断 + 去重；快捷按钮本轮仅「打开」单按钮（审批动作在应用内完成）；真机到达/点击路由未验（§10.8） |
| 18 | Qoder 空态三件套（定位文案+扫码主 CTA+示例会话；OAuth 精简；H5 兜底） | 后续候选 | 未做（登录/空态文案沿用 M1 设计稿基线）；列上架前打磨候选 |
| 19 | Qoder Quest 退役独立桌面端信号：「会话」取代「编辑器」成一级对象 | **已符合（架构面）** | BiuZ 底部导航以会话为首位、推送/搜索/分享以会话为粒度；无需调整信息架构（与风险 4 的 Tab 命名偏差问题互不影响） |

### 10.4 只读边界口径修订（纠偏地基项）

**修订对象**：ReadOnlyGate.swift 全文件重写（246→265 行，实测），原「readonly/session/execution」三分类改为「readOnly/session/command/directWrite」**四分类**（V4CommandClassification，实测 :26-31），isBlocked 仅对 directWrite 成立。分类语义（文件头注释 :5-23 实测固化，含与 v1.3/v1.4 差异的取舍说明）：

| 分类 | 内容 | 判定 |
|---|---|---|
| command | 客户端发命令桌面代执行：sendText/retryTurn/editUserQuery/resolveInteraction/respondWorkspaceHookReview/respondPermission/respondElicitation/stop/pauseGoal/resumeGoal/stopGeneration/sendQueuedNow/setAutoDrain/队列三件（enqueue/promote/cancelTaskCommand）/compact/goal/sendPrompt/createSession±firstInput/workflow 控制等 | **放行** |
| session | 会话元数据/账本写：置顶/归档/未读/renameTask/draft 转正等 | **放行** |
| readOnly | 订阅/分页查询/附件读（原口径不变） | **放行** |
| directWrite | 手机直写面：applyFileRewind（v4 信封唯一直写 type）、git 写 8 项、terminal 4 项、file 非白名单命令（**默认拒绝**）、zcode-agent 直写族（writeWorkspaceFile/saveFile/writeFile/applyEdits）、zcode-task 配置写 5 项（setMode/setConfigOption/setModel/setAutomationSessionConfig/restartWorkspaceProcess）、settings/onboarding/settings-sync/credential/oauth/plugin/skill/mcp-sync/bots/coding-plan 等频道写族 | **拦截**（Verdict.reason 记录原因，blockedWriteCommandCount 出口留存） |

**与 v1.3–v1.5 口径的关键差异**：①「旧协议消息面」（zcode-agent sendPrompt/compactSession/goalSession/closeSession）由拦改放（消息面同性质）；②zcode-task 响应面（respondPermission/respondElicitation/stopGeneration/sendPrompt/deliverSessionMessage）与队列三件由拦改放；③file 频道由「未知命令放行」改为「默认拒绝 + 读白名单」（堵住 writeTextFile/mkdir/delete 等未知写命令因黑名单未枚举而放行的口子——阶段材料标注的原口径缺陷，本轮收口）；④拦截记录双层保留并厘清语义：连接层 blockedExecutionCalls 原样保留（最近 20 条，ZCodeServerConnection.swift:257/:803-804 实测，GateTests 有容器+reason 契约用例）；替身侧执行计数器由 executionCommandCount 重定义为 **blockedWriteCommandCount**（仅计仍被拦的直写/配置写，E2E 各连接态用例以其 ==0 收口）。**出口拦截不变**：全部 RPC 仍经 ZCodeServerConnection.call 唯一出口（**现网 :795 起**——call :795、blockedExecutionCalls.append :803、抛 ReadOnlyViolation :806，v1.6 二次修订实测；本节初版误引 v1.5 时点 :786-797 与同段 :803-804 并存系自相矛盾，已按勘误·续二统一），directWrite 抛错并记录；演示态不经连接层、不受影响。

### 10.5 实现清单与补全项落地（gaps 12 项全闭环对照）

阶段补全计划 12 项的落地状态与撰写会话实测核验。**计数口径（v1.6 二次修订更正）**：阶段材料 gaps 清单口径为 P0×3（发送/审批/停止）/P1×5（口径修订地基、新建会话带指令、重命名+标记未读、已归档分组、本地通知）/P2×4；本表按**依赖顺序**呈现为「地基 1+P0 3+P1 4+P2 4」四段（口径修订地基项在阶段口径中优先级为 P1、因系其余各项前提列于首位）——两种排法均为 12 项，勿以表内行首标签直接对读阶段计数：

| gap（优先级） | 落地内容（阶段口径） | 实测核验（本会话） |
|---|---|---|
| P1 地基·边界口径修订（阶段优先级 P1；列首因系其余各项前提） | ReadOnlyGate 全文件重写（四分类，见 10.4）；UnitTests/ReadOnlyGateTests 断言反转+重写；替身计数器改 blockedWriteCommandCount | ✅ 265 行四分类结构实测（:5-23/:26-31/:62-115 五集合）；GateTests 16 用例**本会话实跑通过** |
| P0-1 发送消息恢复 | RemoteConversationStore.send 实现 sendText 信封（commandId/clientId/sessionId/type/payload{text}）+乐观回显（锚点插入+去重消费+失败撤销）；ChatView 删锁形降级（连接/演示共用输入区，保留桌面模型只读 chips）；草稿清空 | ✅ send :682 实测；05-composer-input ChatView:409；05-composer-readonly Sources 0 残留（测试文件 2 处为失败信息文案）；isReadOnly 在 ChatView 0 命中 |
| P0-2 审批与提问应答恢复 | resolveInteractionRaw（interactionId+answer 信封，顶层冗余兼容）；RemoteTaskStore.approve/reject 经 currentPendingInteraction 解析真实 interactionId（answer={approved,scope}）；RemotePendingInteraction 模型+宽容解析；ChatView ApprovalInteractionCard（结构化排版+三档 scope+48px 双按钮）；ApprovalSheetView 删 06-readonly-notice；提问卡 chips 连接态恢复 | ✅ resolveInteractionRaw :952、resolvePermission RemoteTaskStore:190-195、05-approval-card :306/05-choice-once :204/05-act-approve :296 实测；06-readonly-notice 全仓 0 残留（实测 grep） |
| P0-3 停止恢复 | RemoteTaskStore.stop 实现 v4 stop 信封（sessionId=taskId, payload.reason=user-requested）；TaskOutputView 删 07-act-stop-readonly、恢复「停止任务」+二次确认；retry 因缺 target{rowId,entityId} 游标保持不下发（UI 无重试入口，如实不虚构） | ✅ 07-act-stop TaskOutputView:289、文件头纠偏注释 :46 实测；07-act-stop-readonly 全仓 0 残留 |
| P1-4 新建会话带首条指令 | createSession+firstInput 一次下发（跳过 draft 转正）、空标题保持 draft+promote 链路；NewConversationSheet 删 03-readonly-notice 恢复完整表单，按钮恒「开始任务」 | ✅ 03-input-title NewConversationSheet:91、03-submit-start :64 实测；03-readonly-notice 0 残留 |
| P1-5 重命名+标记未读 | renameConversation（renameTask+localTitleOverrides+失败回滚）/markUnread（unread:true+localUnreadFlags）；行 contextMenu 四项；MockConversationStore 补 rename/markUnread（演示态可用、既有行为零改动） | ✅ contextMenu :207-224（重命名/标记为未读/复制会话 ID/归档）、:80 alert 实测 |
| P1-6 已归档分组与取消归档 | setArchived(false) 改发 unarchiveTask（修正误发 archiveTask）；archivedConversations()（listArchivedTasks+本地归档缓存合并）；「已归档」分区+取消归档滑块；主列表过滤归档行 | ✅ unarchiveTask :807、archivedConversations :871、04-act-archived :175、04-archact-unarchive-* :294 实测 |
| P1-7 本地通知真实化 | 新建 Services/Notifications/NotificationService.swift：TaskNotificationContent.make 纯函数（done/failed/waiting 三类、(status,taskId) 去重、80 字截断、running 不投递）；授权仅用户显式开启时请求；关闭撤销待投递；RemoteTaskStore.handleTaskEvent 挂钩；SettingsView 12-row-notify 接线+App 层点击路由 | ✅ NotificationService.swift 139 行实测；**TaskNotificationContentTests 6 用例本会话实跑通过**；UNUserNotificationCenter 全仓命中仅该文件（实测） |
| P2 附件/产物图片渲染 | 部分落地：产物行文案结构化（📦 产物 · 名（类型））；ChatMessage attachment 模型与图片缩略图/全屏未做（无已验证附件读 API 形态） | ⚠ 如实标注未完成部分（§10.8-4） |
| P2 任务/会话服务端全文搜索 | 任务侧完整：RemoteTaskStore.searchTasks 透传 listTaskList searchQuery+400ms 防抖（连接态服务端优先/空回执回退本地、演示态纯本地）；会话侧服务端检索未接 | ✅ searchTasks TaskBoardView:92 实测；会话侧未接如实标注 |
| P2 会话内消息搜索 | ChatView 搜索条（已加载 messages 本地检索：正文/工具卡目标/输出+命中计数+检索前自动 loadOlder 扩页一次）；命中高亮与上下跳转未做 | ✅ 05-act-search :45、05-search-messages :114 实测 |
| P2 图片/PDF 只读预览 | RemoteFileStore.binaryPreview（file.readBinaryPreview，base64/bytes 宽容解析）+FilePreviewView 按扩展名分发（图片→UIImage；pdf→PDFKitView）；失败/演示态降级说明不虚构预览；Office 后置 | ✅ binaryPreview 调用 FileTreeView:272、UIImage :293、PDFKitView :307/:420 实测 |

### 10.6 逐功能验收清单（24 项全表，验收会话口径全部 pass）

> **结果分级口径**（沿用 §7.6 体例并扩两档执行主体）：**pass(本会话实跑)** = 本报告撰写会话实际执行并通过（本轮：ZCodeMobileTests 40/40、grep/行号实测）；**pass(验收会话)** = 验收会话实际运行产出（截图/替身计数在位，撰写会话未复跑）；**pass(编译级)** = 断言已写入测试代码并经 build-for-testing 编译验证，完整套件实跑属统一门禁（阶段口径 e2e=true）；**pass(代码核对)** = 以代码走查/grep 佐证。24/24 pass 为**验收材料判定口径**；**v1.6 二次修订限定（审阅 #2）**：汇总不得强于项级证据——第 1 项材料自注验收会话未运行（列交付门禁）、第 14/15/18③ 的断言载体为不参与编译的孤儿文件（不可执行）、e2e 截图证据无落盘路径（工作区不可复核）、本表 #25–#27 补录两项无测试断言；故对外表述为「材料判定全 pass + 本会话单测实跑 40/40 + 上述载体与运行限制如实列明」，**不使用「全流程通过」单一表述**。证据等级总声明见 §11.1，真机手动项与未完成子项如实标注（§10.8）。

| # | 功能 | 验收标准（摘要） | 结果 | 证据 |
|---|---|---|---|---|
| 1 | 连接/OAuth：OAuth 登录全流程（zai/bigmodel 双 provider） | O1 拉起授权页（state 防伪）→回调校验（不匹配拒绝可重试）→code 交换→tokenSet+userInfo 写 Keychain→账户行「已登录 · Coding Plan · 用户名」；业务错/401 进失败页可重试可换 provider；退出仅清账户层 | pass(编译级；材料声明验收会话未运行、列交付门禁) | LoginE2ETests test01-05 既有在位（state 篡改/401 双分支/取消/退出仅清账户层）；验收材料注明「本会话未运行」 |
| 2 | 连接/OAuth：冷启动路由与 E2E 状态钩子 | 未配置进演示、已配置自动重连、失败回退演示+红横幅；-ZCodeOpenLoginFlow/-ZCodeOpenConnectFlow/-ZCodeRelayLink/-ZCodeE2EResetState 四钩子行为正确 | pass(编译级+代码核对) | test09/test10+RelayLinkE2ETests test03/04 既有；四钩子 AppSession.bootstrap 代码核对（验收材料；bootstrap 区间见 §7.2 勘误续） |
| 3 | 连接（局域网直连）：配对五步连接（阶段 checklist 原归「云中继」域、v1.6 二次修订按验收对象更正前缀——该项走 `zcode --web` 直连五步而非中继路径，中继路径为第 4 项） | server-info（3s 重试 1）→令牌校验→WS→v4 握手（clientKind=mobileApp）→workspaces[0]；L2 五步进度/耗时/meta 正确 | pass(验收会话) | test06/07/08 既有+LayoutAuditTests test06 扩展五步行断言（l2-step-discover/auth/ws/handshake/workspace）+「连接进度 · 1/5」meta；截图 20 在位（3/5、5/5 中间态属人工复核项） |
| 4 | 云中继：Relay Link 云中继连接 | zcode-relay 链接解析→auth matched→bootstrap+bridge-open→桥内 v4 握手（旧桌面降级放行）→桥内 workspacePath 装配；与局域网共用门面与出口 | pass(编译级；真机活会话人工项未验) | RelayLinkE2ETests test01/03/04 既有（回环 127.0.0.1 约定）；真机 zcode.z.ai pair_status=matched 联调为人工项（M1.z §9.3 真实验收已覆盖一次，本轮未重做） |
| 5 | 云中继：断线自愈与状态横幅 | dropped→resync 续流（水位）；断线黄横幅「与桌面端的连接已断开 · 重连」；失败红横幅「已回退演示数据 · 重试」 | pass(验收会话，含应用缺陷修复) | **应用缺陷已修**：AppSession.Mode.disconnected 原无产生路径（黄色横幅为死代码）→ZCodeServerConnection 新增 onConnectionDropped 回调+AppSession 订阅；新增 test17（替身 dropAllWebSocketChannels→13-banner→重连→websocketUpgrades+1、横幅消失），截图 30 在位；test13② resync 既有 |
| 6 | 会话列表：订阅与分区渲染 | sessions-index 订阅失败回退 listSessions；分区=置顶+今天/昨天/更早；行=头像+标题+运行中胶囊+摘要+相对时间+未读徽章；搜索过滤标题/摘要 | pass(编译级+代码核对) | test12/14/15 既有；新增 ZCodeMobileE2ETests test08（分组头+「运行中」胶囊+未读徽章「2」断言，「昨天」时钟边沿防抖）；ConversationListView.swift:124-141 分区结构实测核对 |
| 7 | 会话列表：列表操作双写（置顶/归档/已读清零+新增重命名/取消归档） | 滑块本地即时反馈+zcode-task 写、失败回滚；markRead 清零徽章消失；renameTask/unarchiveTask 到达断言（gaps P1-5/P1-6 验收） | pass(编译级) | test12④ archiveTask/test15① setTaskUnread/test16③ renameTask（lastRenameTitle）+unarchiveTask（listArchivedTasks+归档分区）到达断言在位（实测 test16 :1255 起）；失败回滚在 setPinned 双写模式（本地 override 先行+guard connection） |
| 8 | 聊天：消息流只读渲染 | 六类行渲染（userInput/assistantText/reasoning💭/toolCall 工具卡/subagent/artifact）；row.delta 流式；loadOlder 向上分页；桌面模型/思考档只读 chips | pass(验收会话) | 新增 test18（替身 sess-e2e-3 预置六类行全 kind：工具卡/subagent/ artifact 首窗，loadOlder 拼接 userInput/assistantText/reasoning），截图 31 在位；test14④b 轨迹分段投影（截图 28） |
| 9 | 聊天：连接态发送消息（纠偏核心） | ReadOnlyGate sendText 放行不抛 ReadOnlyViolation；连接态输入→发送→替身 sendText 计数≥1→回执上屏→草稿清空；演示态发送路径不变 | pass(本会话实跑+编译级) | ①GateTests.testConversationMessageFaceAllowed 放行断言——**本会话实跑通过**（40/40 内）；②test06 发送断言在位（05-composer-input→05-composer-send→sendTextCount≥1+回执+草稿清空，实测 :444-452）；③ChatView isReadOnly/锁形分支 0 命中（实测 grep）；MockConversationStore.send 零改动（走查） |
| 10 | 聊天：连接态审批与提问应答（纠偏核心） | 待审批会话详情可见审批卡（命令/路径/影响结构化）+三档 scope；批准/拒绝下发 resolveInteraction 携真实 interactionId 与 answer；提问卡 chips 可点并发应答 | pass(本会话实跑+编译级) | ①GateTests resolveInteraction/respondPermission/respondElicitation 放行——**本会话实跑通过**；②test16① 断言在位（05-approval-card→scope 切换→05-act-approve→resolveInteractionCount≥1 且 interactionId=int-e2e-perm-1、approved=true→卡片撤下；实测 :1255 起，验收材料引 :1224-1249 系改写期行号、已漂移约 30 行，如实标注）；③06-readonly-notice 0 残留（实测）；提问卡 chips 扩展断言在 ZCodeMobileE2ETests test03；连接态 question 类交互无独立渲染卡以代码核对佐证（验收材料如实声明） |
| 11 | 任务：看板四段与实时推送 | 待操作/进行中/失败/已完成四段+计数徽章；onDynamicTaskEvent 免刷新 upsert；下拉刷新；标题搜索 | pass(编译级+代码核对) | test12⑥/test13① 既有；四段分组+计数徽章（TaskBoardView.swift:116-141）、greeting 计数行、失败卡红描边（:268）代码核对（验收材料） |
| 12 | 任务：详情只读元数据与轨迹 | 后台 Bash/模型轨迹两段；绑定模型/会话配置/Token 用量三行（缺数据不渲染）；轨迹=reasoning+toolCall 投影；复制全部输出 | pass(验收会话) | test14④ 三读命令+07-meta-readonly 三行断言既有；④b 轨迹分段切换+reasoning/BASH 投影断言（截图 28-task-trajectory-segment 在位）；07-act-copyall 由 UITests 覆盖（⚠ 该用例在孤儿文件，见 §10.8-2） |
| 13 | 任务：停止任务（纠偏核心） | 运行中详情「停止任务」可点→二次确认→v4 stop 下发且替身收到→状态回流翻转；演示态 MockTaskStore.stop 不变 | pass(本会话实跑+编译级) | ①GateTests stop/队列放行——**本会话实跑通过**；②test16② 断言在位（07-act-stop→二次确认→stopCount≥1→替身回推 completed→07-status-bar 翻转）；③07-act-stop-readonly 0 残留（实测）；停止按钮恢复实测（TaskOutputView:289） |
| 14 | 任务：审批 Sheet 演示态回归 | 授权范围三行单选/拒绝批准 48px 双按钮/追问稍后出口/下拉不关闭——纠偏不得触碰 | pass(编译级；断言载体为孤儿文件，如实降级) | 验收材料引 ZCodeMobileUITests testTaskBoardApprovalSheet+testTerminalOutput；**实测：该文件不参与编译（孤儿，§10.8-2），本轮新增第 9 用例亦在其中——该两项回归当前不可执行，实际演示态回归依赖 ZCodeMobileE2ETests 演示套件（test01-08 编译在位）**；ApprovalSheetView 演示路径未被纠偏触碰（走查） |
| 15 | 文件 diff：三分段与文件树/预览 | 未暂存/已暂存/本次会话三分段（连接态可见演示隐藏）；git.refresh+getChanges/getDiff 纯读；文件树服务端搜索+watcher；256KiB 截断+加载更多；行号源码视图 | pass(验收会话) | ZCodeMobileE2ETests test05+LoginE2ETests test14②③ 既有；UITests testFilePreviewAndDrawer 扩展 09-seg-source/09-source 断言+截图 40（⚠ 同在孤儿文件，断言在位不可执行——如实标注）；编译内覆盖=test14②③ |
| 16 | 文件 diff：文件只读边界（不直写） | 无文件写/保存/回滚入口；git 写 8 项拦截；「全部批准」仅本地决策记录 | pass(本会话实跑) | ①GateTests git 写/file 默认拒绝拦截——**本会话实跑通过**；②RemoteFileStore 仅读面调用（验收材料逐位点核对：file-watcher/readdir/searchWorkspaceFiles/stat/readTextFile/git refresh/getChanges/getDiff）；③grep 全仓文件写/git 写调用入口 0 命中（验收会话实跑，撰写会话复核 Sources 命中仅黑名单定义）；08-act-approve-all 本地决策（RemoteFileStore.swift:452-460 口径） |
| 17 | 设置：账户与额度卡 | 连接态额度绑真实数据（usage-stats 两读只读投影）；演示态回退 68%·340/500；账户行桌面 OAuth 投影 | pass(编译级) | test14⑤（340/500+重置时间+oauth 三读+usage-stats 两读命令级断言）+test10 既有；新增演示态回退断言（test08：「68%」+「340 条已使用」）；12-foot-data-source 随模式切换（test09/test10） |
| 18 | 设置：通知开关真实化（P1-7 验收） | 开关驱动 UNUserNotificationCenter 授权与投递；三类通知带上下文摘要+3 秒去重；点击路由；关闭撤销待投递；真机到达为手动项 | pass(本会话实跑+⚠ 孤儿子项) | ①TaskNotificationContentTests 6 用例（三类映射/去重/80 字截断/空白退化/running 不投）——**本会话实跑通过**；②NotificationService 真实实现（UNUserNotificationCenter delegate 路由）代码核对；③开关持久化 UI 用例 testSettingsNotificationsTogglePersist **被加入孤儿文件、当前不可执行（实测 orphan :229，§10.8-2）——降级为断言在位**；④通知到达/点击路由真机手动项未验（如实） |
| 19 | 设置：服务器管理 | 已存服务器连接态/最近连接；令牌更新重连；添加扫码/剪贴板/手动；删除二次确认清 Keychain；连接测试 1.5s 探测 | pass(编译级) | test10（令牌更新→重连→数据源页脚）/test11（query token+Bearer 双形态强制校验）既有；l4-row-*/l5-info-card 锚点在位（截图 26）；deleteServer 清注册表走查（AppSession:347-352 区，验收材料）；扫码/剪贴板为人工项 |
| 20 | 品牌：BiuZ 自我指称与商标避险 | App 自称一律 BiuZ；主界面无 ZCode 暗示性使用（works-with 除外）；12-brand-name 锚点与数据源页脚在位；新文案不得引入「由 ZCode 出品」 | pass(编译级) | ZCodeMobileE2ETests test06 既有（isHittable 遍历无 ZCode/12-brand-name BiuZ 前缀/12-foot-data-source 自称 BiuZ for iOS）；本轮新增文案全为中性表述（替身/E2E 语义），实测无新品牌联动表述 |
| 21 | 演示模式：行为不变（贯穿约束） | 冷启动无配置进演示；失败回退演示+横幅；演示态发送/审批/停止/新建完整可用；mock 路径零改动 | pass(编译级门禁口径) | ZCodeMobileE2ETests test01-08（本轮 test03 扩 chips 应答/test05 扩只读走查/新增 test08，只增不改）作为门禁；Mock 三 store 零改动（本轮仅新增 rename/markUnread 本地实现）；五处 UI 恢复删除的均为 isReadOnly 降级分支；mock 不经 ZCodeServerConnection（AppSession 装配隔离，§7.2） |
| 22 | 横切：布局与可访问性基线 | 内容不越 TabBar/键盘避让/动作栏不盖 TabBar/FAB 不压卡片/44pt 热区；关键路径无遮挡死控件 | pass(验收会话) | LayoutAuditTests 8 用例（截图 01-27 keepAlways）；本轮仅扩充 test06 五步断言与 test07 修订（输入区/发送键在屏），未删除走查断言；关键路径由 test16 全程覆盖（新截图 28/30/31） |
| 23 | 横切：只读边界防回归（纠偏后新口径） | GateTests 双向断言全绿；blockedExecutionCalls 记录契约；E2E 以 blockedWriteCommandCount==0 收口；grep 无 file/git 写入口 | pass(本会话实跑) | ①ReadOnlyGateTests **16 用例本会话实跑全通过**（放行：消息/审批/停止/队列/createSession±firstInput/任务元数据；拦截：applyFileRewind/git 写/file 默认拒绝含未知命令/zcode-task 配置写/zcode-agent 直写族/频道黑名单）；含 blockedWrite 记录契约用例（verdict.reason 非空+新连接记录为空）；②LoginE2ETests 内 blockedWriteCommandCount==0 收口断言 **19 处**（`grep -n blockedWriteCommandCount` 命中 20 处、其中 :1254 为注释行，以断言计 19——v1.6 二次修订按审阅意见更正）；③grep 实测 Sources 层只读锚点 0 残留 |
| 24 | 新增 E2E 闭环用例（纠偏回归主链） | test16：发送→审批→停止→重命名→归档查看→取消归档全链+全程零直写；test17：断线横幅重连；test18：六类行渲染 | pass(编译级) | test16_connectedSendApproveStopRenameUnarchiveLoop（:1255）/test17_droppedConnectionShowsBannerAndRetryReconnects（:1389）/test18_connectedMessageStreamRendersAllSixRowKinds（:1429）实测在位；运行时行为列统一门禁（验收材料口径：test16/17/18 属验收会话执行范围，截图 28/30/31 为其产出） |

**v1.6 二次修订补录（审阅 #5：阶段 checklist 24 项对补全清单的覆盖缺口，以下 3 项为补录验收行）**：

| # | 功能 | 验收标准（摘要） | 结果 | 证据 |
|---|---|---|---|---|
| 25 | 新建会话带首条指令（P1-4） | 标题非空时 createSession+firstInput 一次下发（替身计数≥1）、跳过 draft 转正；空标题保持 draft+promote 链路 | pass(编译级；断言在位) | test06 内断言实测在位（ZCodeMobileLoginE2ETests:438-441 `createSessionWithFirstInputCount >= 1`、03-submit-start :437）；v1.6 首版 24 项表未显式列项，补录 |
| 26 | 会话内消息搜索（P2） | 05-act-search 切换搜索条、已加载范围本地检索、命中计数、检索前自动 loadOlder | pass(代码核对)·**无测试断言（如实降级）** | UI 实现实测在位（ChatView:45/:114）；`grep -rn '05-act-search\|05-search-messages' ios/Tests/ ios/UnitTests/` → 0 命中（v1.6 二次修订实测），无编译内断言，列补测项（§10.8-8） |
| 27 | 图片/PDF 文件只读预览（P2） | binaryPreview 取字节渲染 UIImage/PDFKitView、失败降级不虚构 | pass(代码核对)·**无测试断言（如实降级）** | UI 实现实测在位（FileTreeView:272/:293/:307/:420）；`grep -rn binaryPreview ios/Tests/ ios/UnitTests/` → 仅替身应答（E2ELoginStubServer.swift:648）与放行白名单（ReadOnlyGateTests.swift:90），无预览断言（v1.6 二次修订实测），列补测项（§10.8-8） |
### 10.7 门禁结果与测试面

| 门禁 | 结果 | 执行记录 |
|---|---|---|
| 构建 | **通过 = true**（阶段口径） | 实现会话自检：`cd ios && xcodegen generate` → Created project；`xcodebuild -project ZCodeMobile.xcodeproj -scheme ZCodeMobile -sdk iphonesimulator build` → **BUILD SUCCEEDED**；`build-for-testing` → **TEST BUILD SUCCEEDED**（App+UITests+UnitTests 三目标）——阶段材料口径，撰写会话未复跑构建 |
| e2e | **通过 = true**（验收会话口径；v1.6 二次修订限定：汇总不得强于项级证据——§10.6 引言） | 验收清单逐项执行口径见 §10.6 分级；验收材料引截图 20/23/26/28/30/31/40 为证据，**但全文无这些截图的任何落盘路径（工作区与 /tmp 均不可复核——审阅 #2③ 属实，如实标注）**；**撰写会话未复跑完整 UI 套件**（38 条编译内 UI 用例的批量实跑属统一门禁；v1.5 的 PTY 环境阻塞本轮实测已消除，见下行，补跑环境前提已具备）；可复核的实测凭据=本表单元级一行（40/40，xcresult 11-43-31 在位） |
| **单元级** | **pass（本会话实跑，40/40）** | **本报告撰写会话实跑**：`xcodebuild test -project ZCodeMobile.xcodeproj -scheme ZCodeMobile -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:ZCodeMobileTests` → **Executed 40 tests, with 0 failures，`** TEST SUCCEEDED **`**（2026-10-04 11:43，xcresult 留痕 DerivedData）：ReadOnlyGateTests **16**（重写后全量）+ RelayLinkTests 18 + TaskNotificationContentTests **6**（新增）。**v1.5 遗留的「M1.z 单测待补跑」在本轮闭环，且 v1.5 的 PTY 环境阻塞（Errno 6）已不再复现**（同一命令此前失败、本轮成功）——M1.z e2e 补跑（§14.2-1）的环境前提已具备，但仍未执行（如实） |
| 材料数字差异（实测裁定） | — | ①ReadOnlyGateTests：补全实现自报 13、验收材料自报 15+1，**实测 16 个 test 方法**（以实测为准，差异系两份材料出自不同会话时点）；②RelayLinkTests 材料自报 16、实测 18（v1.5 已有差异，延续）；③test16 行号：验收材料引 :1224-1249、实测 :1255 起（改写期漂移，如实标注） |
| 门禁脚本入库 | 仍缺 | 工作区 `find` 复跑零命中（本会话实测，与前六轮一致）——§12 风险 1 对本轮门禁同样适用；本轮新增可用凭据：撰写会话 xcresult（DerivedData 路径见上行） |

**测试面（62→78 用例，实测）**：编译内 **78** = UI target 38（ZCodeMobileE2ETests 8 [+test08]、ZCodeMobileLoginE2ETests 18 [+test16/17/18]、LayoutAuditTests 8、RelayLinkE2ETests 4）+ 单测 target 40（ReadOnlyGateTests 16 重写、RelayLinkTests 18、TaskNotificationContentTests 6 新增）；另有**孤儿文件** ZCodeMobileUITests.swift **9 用例**（原 8+新增 testSettingsNotificationsTogglePersist，不参与编译，§10.8-2）。文件行数实测：替身 1751→**1950**、登录套件 1220→**1470**、常规套件 369→**443**、布局 494→**501**、中继 e2e 200→**212**、ReadOnlyGateTests 162→**246**、TaskNotificationContentTests **63**（新增）。**替身扩展**（阶段口径+实测计数器在位）：executionCommandCount 重定义为 blockedWriteCommandCount（仅计仍被拦的直写/配置写）、新增 sendText/resolveInteraction（记录 interactionId+payload 并回推 state.updated 清空挂起交互）/stop（回推 completed 事件）/renameTask/unarchiveTask/listArchivedTasks（归档样例 sess-e2e-arch-1）应答分支与 lastResolveInteraction/lastRenameTitle 计数器（E2ELoginStubServer.swift:81-101 实测）、conversation 订阅后对 sess-e2e-1 下发 permission 挂起交互（id=int-e2e-perm-1）驱动审批卡、dropAllWebSocketChannels 掐断能力（test17 依赖）。

### 10.8 已知限制（如实）

1. **完整 UI 套件未由撰写会话复跑**：e2e=true 为验收会话口径（逐项证据见 §10.6），撰写会话仅实跑单测 40/40 与静态实测；统一门禁批量实跑仍列 §14.2-1（工作区仍无门禁脚本与工作区内 xcresult）。
2. **孤儿 UI 测试文件扩大（风险 3 恶化，如实升级）**：本轮新增的 testSettingsNotificationsTogglePersist（通知开关持久化）被加入 **ios/ZCodeMobileUITests/ZCodeMobileUITests.swift（仍不参与任何 target，实测 pbxproj 0 引用）**——该文件现有 9 用例全部不可执行；验收清单 #12/#15 所引 testTaskBoardApprovalSheet/testTerminalOutput/testFilePreviewAndDrawer 扩展断言同属该文件、断言在位但当前不可运行。**整改**：收编（sources 改指或迁入 ios/Tests/）列为 M1 剩余研发项（§14.1），收编前该三项回归以编译内套件对应面为准（演示审批卡=ZCodeMobileE2ETests test03、文件预览=LoginE2ETests test14②③）。
3. **放行命令的真实桌面对照未验证（本轮最重要残余）**：resolveInteraction 应答 payload 形态（answer={approved,scope}/提问 answer=文本，含顶层冗余兼容）与 v4 stop/renameTask/unarchiveTask 信封按阶段材料口径构造，**未与真实桌面端逐字段核对**（撰写与实现会话均无桌面端源码）——M1.z 真实验收止步于列表只读面；联调时如字段不符需调 RemoteConversationStore.resolveInteractionRaw 与 RemoteTaskStore.resolvePermission 等两三处（已列 §14.2-⑩）。file.readBinaryPreview 同为未与真实桌面核对的高级读面（失败降级不虚构预览）。
4. **P2 未完成子项**：①附件图片缩略图/全屏未做（ChatMessage 无 attachment 模型，无已验证附件读 API——桌面对照 #15 的硬缺口收窄为「产物行结构化」）；②会话内搜索命中高亮/上下跳转未做；③会话列表服务端检索未接；④retry（retryTurn）因缺 target{rowId,entityId} 游标未接（UI 无重试入口，与 v1.2 口径一致）。
5. **通知真机手动项未验**：通知实际到达与点击路由依赖真机 UNUserNotificationCenter（模拟器行为不完整）——验收单如实标注「手动未验」；开关授权弹窗已做自动化兜底（还原开启时关闭系统弹窗）。
6. **纠偏口径下的回归防护依赖**：放行面（消息/审批/停止/队列）的边界防护从「黑名单拦截」转为「directWrite 分类+替身 blockedWriteCommandCount==0 收口+GateTests 16 用例」三重；「未知命令」在 v4 信封面仍放行（与 §7.8-5 自律口径一致，仅 file 频道改为默认拒绝）——绕过客户端直连协议不在边界内（口径不变）。
7. **竞品参考为文字材料**：19 条 pattern 无落盘截图/链接存档（§13 决策信息 ⑤ 口径不变）；其中 TraeGo 社区提案与部分 Trae 细节系转引（材料已如实标注来源等级）。
8. **M2 验收证据落盘缺口与 P2 断言缺口（v1.6 二次修订新增）**：①验收会话截图（20/23/26/28/30/31/40）未落盘工作区亦无路径留存、不可复核（§10.7）；②P2 两项（会话内搜索、图片/PDF 预览）无任何编译内测试断言（§10.6 补录 #26/#27），与替身二进制预览应答（E2ELoginStubServer:648）未形成闭环——**整改**：补 E2E 断言（搜索条切换+命中计数、binaryPreview→UIImage 渲染冒烟）并落盘验收截图，列入 §14.1 M1 剩余研发①②的整改范围。

## 11. iOS 构建与 XCUITest e2e 结果（M0 / M1 迭代一 / M1.x / M1.y / M1.z / M2 六轮，均如实降级表述）

### 11.1 证据等级声明

**以下门禁结果均为阶段会话的自报结论（附其执行命令记录），工作区内不存在任何可复核凭据**：

- 无门禁脚本：`find . -path ./.zcode -prune -o \( -name '*.sh' -o -name '*.xcresult' -o -name '*.log' -o -name 'Makefile' \) -print` 零命中（v1.1 修订、v1.2 更新、v1.3 更新各实跑一次，结论一致）；
- 测试代码自身即声明依赖外部门禁：ios/Tests/ZCodeMobileE2ETests.swift:9（v1.2 二次修订实测更正，原引 :11；第 9 行原文「本文件只做编译级自检，由统一门禁脚本在模拟器上执行」）、登录 e2e 与布局走查套件同口径（「由统一门禁脚本在模拟器上执行」，LayoutAuditTests.swift:8 实测）——各轮引用的「统一门禁脚本」均不在工作区任何位置；
- 本报告七轮撰写/修订过程中，前六轮**均未复跑完整构建与 UI 测试套件**（v1.3 撰写会话以静态核验代替，见 §11.6；「见 10.6」系 v1.5 编号残留、v1.6 二次修订更正——§10.6 现为 M2 验收清单）；**v1.4 例外**：v1.4 撰写会话实际执行了 M1.y 单元测试（ReadOnlyGateTests 10/10 通过，`xcodebuild test -only-testing:ZCodeMobileTests`，§8.5）；**v1.5 尝试**：v1.5 撰写会话实跑 M1.z 单元测试**未能运行**（测试 runner 启动的 PTY 环境阻塞，独立复现阶段自述故障——非断言失败，§9.5 括注）。**v1.6 更新（本报告撰写会话实跑，环境阻塞已消除）**：`xcodebuild test -project ZCodeMobile.xcodeproj -scheme ZCodeMobile -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:ZCodeMobileTests` → **Executed 40 tests, with 0 failures，`** TEST SUCCEEDED **`**（ReadOnlyGateTests 16 + RelayLinkTests 18 + TaskNotificationContentTests 6）——M1.y（10 用例）与 M1.z（18+6 用例，含重写后的 ReadOnlyGateTests）两轮单元级在本轮一并补跑闭环；M2 的构建=true/e2e=true/验收全绿=true 仍为阶段与验收会话口径（撰写会话未复跑完整 UI 套件，§10.7；汇总口径限制——项级载体与运行限制——见 §10.6 引言，v1.6 二次修订）。**M1.z 另有两类非自报证据**：①真实链路端到端验收（§9.3）产出日志全文与截图（/tmp，工作区外）——**v1.6 二次修订实测：上述 /tmp 证据文件现已全部不存在（14 条路径逐一 MISSING），该验收自此无可复核凭据、降级为「v1.5 验收会话记录、彼时复核一致、现不可复核」**；②e2e 门禁结果为 **false**（非「通过=true 的自报」，而是如实标注的环境阻塞），属六轮中首个如实为 false 的门禁项（§9.5）。

因此：下述 §11.2/§11.3/§11.4 的「通过」应理解为**阶段自报、待复核**，不能作为已验证事实采信（§8.5 的 M1.y 门禁同口径，但单元级一项已实跑复核；§9.5 的 M1.z 门禁构建项有验收会话第二执行主体佐证、e2e 项如实为 false）；复核责任见 §14 里程碑表与 §12 风险 1。**（v1.4 二次修订更正：本句原为「下述 8.2/8.3/8.4」，系 v1.4 章节顺延时的漏改——彼时指向旧 §8 的构建小节，现已指向 §11.2–§11.4）**

### 11.2 阶段自报：M0 iOS 构建通过（未复核）

阶段会话记录的命令与结果：`xcodegen generate` → Created project；`xcodebuild -sdk iphonesimulator build` → BUILD SUCCEEDED；`build-for-testing` → TEST BUILD SUCCEEDED（编译含 UI 测试 bundle，未执行测试）。

### 11.3 阶段自报：M0 XCUITest e2e 5 流程通过（未复核）

测试代码在位：[ios/Tests/ZCodeMobileE2ETests.swift](../ios/Tests/ZCodeMobileE2ETests.swift)（224 行，5 个 test 方法，随 ZCodeMobileUITests target 编译）。5 条流程：①启动进入会话列表并展示 mock 数据（`04-row-c1`…）；②底部 Tab 四根页切换；③新建会话→发送→模拟流式回复（`05-questioncard` 等）；④设置修改外观并持久化；⑤打开 diff 视图（`08-filecard-toggle-d1`，v1.0 转述的 `08-filecard-d1` 系笔误，grep 零命中已更正）。

### 11.4 阶段自报：M1 迭代一与 M1.x 门禁通过（未复核，明细见 §6.7/§7.5）

**M1 迭代一**：构建 BUILD SUCCEEDED（最终代码状态复跑）；登录 e2e（[ios/Tests/ZCodeMobileLoginE2ETests.swift](../ios/Tests/ZCodeMobileLoginE2ETests.swift)，v1.2 时 634 行 11 用例，v1.3 实测已扩至 **827 行 12 用例**）+ 替身服务器（[ios/Tests/E2ELoginStubServer.swift](../ios/Tests/E2ELoginStubServer.swift)，v1.2 时 1032 行，v1.3 实测 **1184 行**：NWListener 单端口承载假授权页/令牌交换三分支/server-info/手工 WS 101 + 13 字节帧 VQL 协议与 v4 下行帧 + git/task 读面只读应答数据）按阶段材料口径由统一门禁执行；实现阶段自检为编译级 + 序列化字节级交叉验证 + 模拟器冒烟 3 项（§6.7）。迭代一用例覆盖：OAuth 全流程、state 篡改拒绝与重试、令牌交换业务错/HTTP 401、用户取消、退出登录、配对成功、错误令牌与重试、错误地址与重试、未配置冷启动、保存配置重连、替身鉴权面（query token 与 Bearer 双形态 + WS 升级 Initialize 帧）。

**M1.x 迭代（v1.3 新增）**：门禁结果为**构建通过 = true、e2e 通过 = true**（阶段口径）；实现会话实跑 build-for-testing 两轮 TEST BUILD SUCCEEDED（零警告零错误，27 用例符号在产物中）；测试面扩展为登录 12 + 常规 7 + 布局走查 8 共 27 用例（三份测试文件均实测行数/用例数，§7.4/§7.5）；全面验收 21 项逐项状态见 §7.6（分级如实标注）。

**M1.y 迭代（v1.4 新增，明细见 §8.5）**：门禁结果为**构建通过 = true、e2e 通过 = true**（阶段口径）；实现会话实跑 build-for-testing TEST BUILD SUCCEEDED（app+UITests+UnitTests 三 bundle）；**例外——单元级已复核**：ReadOnlyGateTests 10 用例由本报告撰写会话实跑通过（`xcodebuild test -only-testing:ZCodeMobileTests` → TEST SUCCEEDED，pass(已运行检查)）；测试面扩展为登录 15 + 常规 7 + 布局走查 8 + 单元 10 共 **40 用例**（五份测试文件均实测行数/用例数，§8.3/§8.4）；e2e 侧 test13/14/15 运行时行为未经实跑校验（阶段自认）。

**M1.z 迭代（v1.5 新增，明细见 §9.5）**：门禁结果为**构建通过 = true、e2e 通过 = false（环境阻塞，如实标注）**；构建项有双执行主体（实现会话自检 + 真实验收会话实跑 BUILD SUCCEEDED 并 install/launch 成功）；**真实链路端到端验收通过**（§9.3，真实配对链接一次连通、44 分钟+ 稳定、0 轮代码修复——日志全文 65,805 字节与逐秒截图留档 /tmp，六轮中首个带可复核运行日志的验收证据）；e2e 侧因宿主机 PTY 泄漏（内核层）测试 runner 无法启动，新增 RelayLinkE2ETests 4 用例仅编译级验证；单元级 26 用例曾由实现会话在环境正常窗口实跑通过（阶段口径）+ macOS swiftc 同源等价验证 32 项 PASS（/tmp/relay_swift_verify）；**v1.5 撰写会话尝试实跑单测同样未能运行（独立复现同一 PTY 环境阻塞，§9.5 括注）**。

**M2 迭代（v1.6 新增，明细见 §10.7）**：门禁结果为**构建通过 = true、e2e 通过 = true、验收全绿 = true**（实现会话/验收会话阶段口径；汇总口径限制见 §10.6 引言/§10.7——项级含未运行项与孤儿载体，v1.6 二次修订）；验收清单 24 项逐项 pass（§10.6，分级如实标注：本会话实跑/验收会话/编译级/代码核对）；**例外——单元级已由 v1.6 撰写会话实跑复核**：ZCodeMobileTests 40/40 通过（含重写后 ReadOnlyGateTests 16 用例与新增 TaskNotificationContentTests 6 用例，§10.7）；e2e 侧新增 test16/17/18（连接态发送→审批→停止→重命名→取消归档全链、断线横幅重连、六类行渲染）与 test08（演示分区渲染），其运行时行为以验收会话证据（截图 28/30/31 在位）与统一门禁批量实跑为准；**如实标注**：孤儿文件 ZCodeMobileUITests.swift 新增第 9 用例不可执行（§10.8-2）；通知真机到达/点击路由、Relay Link 真机活会话为人工验收项未验。

### 11.5 设计基准核对门禁：ready（缺口清单见 §3.3）

主稿 spec↔HTML 两方 ready（v1.1 口径）；登录专项稿 102/95 testid 实测一致（v1.2），真机/Light 回归未做（§3.3 缺口 4）。

### 11.6 本报告在工作区实测核验过的事实（七轮；v1.2 新增部分加粗，v1.3 新增见倒数第三条，v1.4 新增见倒数第二条，v1.5 新增见倒数第一条，v1.6 新增见末条）

> **六轮与下述条目标签的对应关系（v1.4 二次修订加注，v1.5 扩充）**：六轮指本报告的六个撰写/修订会话——**v1.0 撰写、v1.1 修订、v1.2 迭代更新、v1.3 迭代更新、v1.4 迭代更新、v1.5 迭代更新**。下述带「第 N 轮」标签的条目是**克隆件抽查的轮次**（第一、二轮均为 v1.2 会话所做，第三轮为 v1.3 会话所做，含二次修订补测），并非报告会话轮次；v1.0/v1.1 两轮的核验以正文各节「实测」标注为准、未设独立标签条目；v1.4 会话核验见倒数第一条、v1.5 会话核验见末条（标签即版本号）。

- `wc -l`（v1.2 时点值；两份 HTML 已因 M1.x 品牌改稿变化，v1.3 复测见末条①）：design/design-spec.md = **495（v2.4）**、design/zcode-mobile-design.html = 1975、**design/login-design.html = 1869**、ios/Tests/ZCodeMobileE2ETests.swift = 224、**ios/Tests/ZCodeMobileLoginE2ETests.swift = 634、ios/Tests/E2ELoginStubServer.swift = 1032**、ios/project.yml = **83**；
- 选择器与文件数（v1.2 时点值；a11y 于 v1.3 复测为 149、两份 HTML 行数复测见末条①）：设计稿 data-testid = 123 处；**登录稿 testid = 102 处标注/95 唯一**；App accessibilityIdentifier = **139 处**（M0 时 69）；App Swift 文件 = **42 个**（M0 时 25）；**登录 e2e test 方法 = 11 个**；
- 目录在位：**Features/Login、Features/Connect、Services/{RPC,OAuth,Keychain,Remote}、Stores/Remote** 及 M0 全部目录；**工作区仍无门禁脚本/xcresult（find 零命中）**；
- 代码事实：协议边界（StoreProtocols.swift:13-53 区间）、注入方式、Tab 顺序（AppRouter.swift:25-28）、**新路由 serverAccount/serverDetail（AppRouter.swift:20）**、mock diff 统计 +33/−11、mock 标题与 24ms 打字机；**工程配置 zcode scheme/本地网络/相机/ATS 例外（project.yml:47-57、Info.plist）**；
- **接口调研克隆件抽查·第一轮（v1.2 迭代更新会话；/tmp/zcode-api-research，v3.14.3，实测 package.json:3；README 版本声明在 :20）**：**13 处行级引用复验、全部属实**（按文件归并 10 组）——http.ts 4 处（:239-241 保护路径、:320 server-info、:323-332 web-remote-replayable、:187/:227-237 cookie zcode_lite_token/HttpOnly/SameSite）、server-remote.ts:4-10（zod schema）、transport.ts:73（clientKind 枚举含 mobileApp/mobileRemote）、hostCapability.ts:4/:34-40（TTL 30_000/issue）、channels.ts:501-503（capability 头）、protocol.ts:183-230（13 字节帧）、runner.mjs:110（randomBytes 令牌）、webZaiOAuthConfig.ts:58（client_id 缺省）、conversationSharePreviewClient.ts:145（Bearer）、core.ts:47-49（replayable flushWindowMs:150）；其中 3 处与调研材料标注存在 2~5 行漂移（runner.mjs、webZaiOAuthConfig、README 版本行），本报告一律以实测行号为准。
- **§2 早期转引抽查·第二轮（v1.2 二次修订会话增补，同克隆件）**：**9 处属实**——DESIGN.md:22（desktop-first/web-compatible）、DESIGN.md:44-50（主题选择）、DESIGN.md:118-119（diff 色令牌）、AGENTS.md:54（广播同步）、AGENTS.md:62-63（Host attachment/web-remote-replayable）、v4/SessionPane.tsx:2079 与 v4/ConversationComposer.tsx:827（isMobileViewport:false）、zh-CN.ts:1615-1616（「移动端远程控制」）、settings/settingsPageConfig.ts:173-177（Computer Use 仅桌面，路径较原引多 settings/ 一级目录，已更正）；**2 处未能定位**——TaskList.tsx:365（克隆件内 grep 不到该文件的 isMobileViewport 适配点）、zcode-cua/README.md（目录不存在），均在正文降级为「未复核」标注（§1.1、§2.3）。两轮合计 **22 处属实、2 处未定位**。
- **M1.x 迭代核验·第三轮（v1.3 迭代更新会话实测；**v1.3 二次修订按独立审阅意见复测补正，以「二次修订补测」标出**）**：①`wc -l`：ios/project.yml = 84、ios/Tests/ 四文件 = 369/827/1184/494（用例数 `grep -c "func test"` 或逐个 grep = 7/12/替身无用例/8，合计 27）、branding/naming.md = 72、design/design-spec.md = 495；**二次修订补测**：design/zcode-mobile-design.html = 1991、design/login-design.html = 1949（首版漏测沿用 v1.2 旧值 1975/1869，审阅指出后复测更正；testid 复测 123/102/95 吻合）；②品牌落位逐行核对：project.yml:3/:23/:26/:37/:50/:69、Info.plist:7-8/:33-34/:41-42、KeychainStore.swift:16（首版误引 :15，:15 为注释行）、LoginFlowView.swift:102/:304-309/:395-402、ConnectFlowView.swift:130-135/:339、ScanView.swift:130、SettingsView.swift:15-28、design-spec.md:1/:453/:469、两份 HTML 标题；③`file` 实测全部品牌 PNG 规格（1024 RGB 无 alpha、mark-960 RGBA、BiuzMark 切片 84/168/252），`md5` 实测 appicon-1024 与工程内副本同源（f2108efa…）；④pbxproj 复验 cn.biuz 4 处 / cn.zcode 0 处、四个测试文件同 UITests target；⑤只读改造逐行核对：ReadOnlyGate.swift 全文 116 行、ZCodeServerConnection.call 拦截（:514-531）、RemoteConversationStore/RemoteTaskStore 停用位、ConversationListView.swift:70 绑定修复、四处只读提示 identifier；**二次修订补测**：订阅参数实为全参（:111-117/:191-196，首版「只传 topic」结论错误并连带 §7.2 gaps⑦/风险 8/§14.2 更正）、a11y 静态调用复测 149 处（首版沿用 139）、行号修正 KeychainStore.swift:15→:16、zcodeJwtToken :90-91→:22/:215、MessageViews.swift:276→:294、05-ask-chip :930-931→:946-947；⑥残留扫描：App 源码可见字符串层 ZCode 仅 3 处 works-with/功能性引用（SettingsView.swift:24、ConnectFlowView.swift:135/:339）、docs/ **无作为现值的 cn.zcode**（实测 `grep -rn 'cn\.zcode' docs/` = 16 行，均为迁移历史描述本身——立项报告.md 内 8 行，属 §4.2/§4.4/§7.1/§12 风险 16 等处的旧值注记与迁移说明，首版「无 cn.zcode 残留」表述不严谨已更正）；⑦门禁脚本 `find` 复跑零命中。
- **M1.y 迭代核验（v1.4 迭代更新会话实测；含二次修订补测，以「二次修订补测」标出）**：①`wc -l`：ios/project.yml = **98**（v1.3 时 84）、ios/Tests/ 四文件 = 369/**1216**/​**1747**/494、ios/UnitTests/ReadOnlyGateTests.swift = **162**（用例数 `grep -c "func test"` = 7/**15**/替身无用例/8/**10**，合计 **40**）、ReadOnlyGate.swift = **246**（v1.3 时 116）、RemoteConversationStore/RemoteTaskStore/RemoteFileStore/ZCodeServerConnection/AppSession = **926/289/463/654/368**（v1.3 时点值出自 M1.x 现状盘点行区间 ~570/~170/~201/~538/~232）；App 源码仍 **43 个**（`find ios/ZCodeMobileApp -name '*.swift' \| wc -l`）；②a11y 静态调用复测 **156 处**（v1.3 时 149，M1.y 新增 7 处）；③gate 黑名单逐集合脚本计数：信封 execution 17 + 双态 2 + zcode-task 15 + git 8 + zcode-agent 34 + 频道级 25 频道 127 命令 = **201 拦截项**；④连接态 RPC 出口 `grep -rhoE 'call\(...|listen\(...'` 全量枚举 = **40 call + 7 listen**（9 频道：zcode-agent 16、zcode-task 7、zcode-session 1、file 4、file-watcher 3、git 3、model-selection 1、oauth 3、usage-stats 2；listen 7）；⑤M1.y 新增落点逐行核对：RemoteFileStore.searchFiles(:151)/stat(:183)/contentPage(:207)/sessionDiffFiles(:322)、RemoteConversationStore.loadOlder(:262)/双写(:678-729)/promoteDeferredDraftSession(:666)/workspaceConfig(:747)/modelSelectionView(:809)、RemoteTaskStore 三读(:222-289)+onDynamicTaskEvent(:58)、V4Wire.droppedSinceLastConsume(:68/:133-134)、ZCodeServerConnection 第三路 listen 与 frameDropHandlers(:491-511/:559-575)、AppSession.fetchDesktopOAuthInfo/fetchCodingPlanUsage(:185-282)、UI 锚点 05-composer-remote-chips(ChatView:162)/05-act-load-older(:94)/09-act-load-more(FileTreeView:293)/08-seg-source(DiffReviewView:182)/07-meta-\*(TaskOutputView:157/:165/:172)；⑥替身断言面核对：rpcCallLog/resyncCalls/rowsRangeRequests/failConversationSubscribe/fire\* 触发器（E2ELoginStubServer.swift:79-263）；⑦**单元测试实跑**：`xcodebuild test -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -only-testing:ZCodeMobileTests -destination 'platform=iOS Simulator,name=iPhone 18 Pro'` → ReadOnlyGateTests 10/10 passed、`** TEST SUCCEEDED **`、退出码 0（报告撰写会话首次实跑测试，其余构建/套件未复跑）；⑧对照计划材料统计复算（范围限定）：40 服务暴露面与 /tmp/zcode-api-client 的 remoteServiceAccess.ts:52-94 实跑比对一致（39 readonly + 1 defineProperty）；392=11+4+32+122+223 与 readonly 199/session 148/execution 45 统计自洽；exists/partial/integrate 三类逐项核对；**skip-boundary 122/defer 223 未逐条独立复核**；另实测发现盘点对两接口存在成员遗漏（§8.1 表注）；⑨门禁脚本/xcresult `find` 复跑零命中（与前四轮一致）。
- **M1.z 迭代核验（v1.5 迭代更新会话实测）**：①`wc -l`：App 源码 **47 个**（`find ios/ZCodeMobileApp -name '*.swift' | wc -l`，M1.z 新增 Services/Relay/ 4 文件），Relay 四件 = RelayLink **105** / RelayFrameCodec **244** / RelayChannelClient **444** / RelayTransport **802** 行；改动文件 = ConnectURLParser **186** / KeychainStore **199** / ZCodeServerConnection **807**（v1.4 时 654）/ AppSession **410**（v1.4 时 368）/ ConnectFlowView **733** / RemoteConversationStore **944**（v1.4 时 926）；project.yml 仍 **98 行**（两 target sources 目录通配，Relay 文件经 xcodegen 纯增量入工程，pbxproj 复核 Relay 4 文件 + RelayLinkTests + RelayLinkE2ETests 均在 Sources phase）；测试面 = ios/Tests/ 五文件 369/1220/1751/494/**200** + ios/UnitTests/ 两文件 162/**347**，用例数 `grep -c "func test"` = 7/**15**/替身无用例/8/**4**/10/**18**，合计 **62**（⚠ 登录套件 v1.4 记录 1216 行、替身 1747 行，本轮实测各 +4，阶段材料自报「既有套件零改动」、差异未溯源；RelayLinkTests 阶段自报 16 用例、实测 18）；②关键锚点行号实测：RelayLink.proof :92、ConnectURLParser.parseRelayLink :35（token 解析 :44 起）、KeychainStore relay 字段 :96、ZCodeServerConnection client 门面 :236-237/connectRelay :438/call 出口与拦截 :786-:797（真实验收材料引 :791-799 为同一区间）、AppSession -ZCodeRelayLink :145-167/probeSavedServer :405、RemoteConversationStore ack 回执 :147-149；③验收证据抽查（**v1.6 二次修订注：以下 /tmp 路径现已全部不存在**——14 条路径逐一检测 MISSING，彼时「在位」结论失效、数值降级为 v1.5 彼时抽查值，§9.3 失效更新）：/tmp/relay-log-stream.txt = **65,805 字节、430 条 relay-log**（`grep -c pair_status=matched` = 405、tasks=240/kind=local/protocolVersion=3 各 1 次）、/tmp/relay-probe.html = **18,180 字节**、逐秒截图 t1/t4/final/postcheck 均在位（01:11~01:55 时间戳与 §9.3 时间线吻合）、/tmp/relay-js/ 57 个 JS + **7 个探针脚本**（ws_probe/ws_app_probe/ws_bridge_probe/disc_probe/disc_probe2/probe9/**ws_bridge_agent_probe**——v1.5 二次修订实测更正，首版漏计后者）在位、/tmp/relay_swift_verify/ 在位（均失效，见上注）、docs/relay-handoff.md = 27 行（**v1.6 二次修订复测现网 29 行**——v1.5 掩码整改新增注记，§9 引言同口径更正）；④macOS swiftc 同源等价验证（/tmp/relay_swift_verify）32 项 PASS 系实现会话产出、本轮仅核验产物彼时在位未复跑（**现该目录亦已不存在**，swiftc 验证产物一并失效）；⑤门禁脚本/xcresult `find` 复跑零命中（与前五轮一致）；⑥**单测实跑尝试**：`xcodebuild test -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -only-testing:ZCodeMobileTests -destination 'platform=iOS Simulator,id=6F5AD678-…'` → 编译链接完成后测试 runner 启动失败（Pseudo Terminal Setup Error, Errno 6；600s 超时，`** TEST FAILED **`）——**独立复现阶段自述的 PTY 环境阻塞，未能运行**（明细与采信口径见 §9.5 括注）。
- **M2 迭代核验（v1.6 迭代更新会话实测；含单元测试实跑）**：①`wc -l`/文件数：App 源码 **48 个**（`find ios/ZCodeMobileApp -name '*.swift' | wc -l`，M2 新增 Services/Notifications/NotificationService.swift **139 行**）；加厚改造实测——ReadOnlyGate **265**（v1.5 时 246）/RemoteConversationStore **1164**（944）/RemoteTaskStore **359**（289）/RemoteFileStore **490**（463）/ZCodeServerConnection **823**（807）/AppSession **417**（410）；测试面实测——替身 **1950**（1751）/登录套件 **1470 行 18 用例**（1220/15）/常规套件 **443 行 8 用例**（369/7）/布局 **501 行 8 用例**/中继 e2e **212 行 4 用例**/ReadOnlyGateTests **246 行 16 用例**（162/10）/TaskNotificationContentTests **63 行 6 用例**（新增）/孤儿 UITests **264 行 9 用例**（+1）；合计编译内 **78 用例**（62→78）；②a11y 静态调用复测 **167 处**（v1.5 时 156，M2 新增 11）；③**ReadOnlyGate 四分类重写逐行核对**：文件头口径注释（:5-23，含「客户端允许发送命令由电脑/云端执行、手机不得直写文件」的用户澄清口径）、V4CommandClassification 四 case（:26-31）、directWrite 五集合（directWriteTaskCommands :62/directWriteGitCommands :70/fileReadCommands :79/zcodeAgentDirectWriteCommands :90/channelDirectWriteCommands :115）、file 频道「默认拒绝+读白名单」注释在位；④**纠偏落点逐行核对**：RemoteConversationStore send（:682）/resolveInteractionRaw（:952）/renameConversation（:816）/markUnread（:840）/archivedConversations（:871）/unarchiveTask 调用（:807）、RemoteTaskStore resolvePermission（:190-195）、TaskOutputView 07-act-stop（:289）、NewConversationSheet 03-input-title（:91）、ChatView 05-approval-card（:306）/05-choice-once（:204）/05-act-approve（:296）/05-composer-input（:409）/05-act-search（:45）/05-search-messages（:114）、ConversationListView contextMenu（:207-224）与 04-act-archived（:175）/04-archact-unarchive-*（:294）、FileTreeView binaryPreview（:272）/UIImage（:293）/PDFKitView（:307/:420）、TaskBoardView searchTasks（:92）、NotificationService（UNUserNotificationCenter 全仓唯一命中文件）；⑤**只读锚点残留扫描**：05-composer-readonly/03-readonly-notice/06-readonly-notice/07-act-stop-readonly/05-question-readonly 在 Sources 层 0 命中（ios/Tests/ 2 处为测试失败信息文案中的历史锚点名，非元素断言）；ChatView `isReadOnly` 0 命中（锁形分支已移除）；⑥**单元测试实跑（本会话）**：`xcodebuild test -project ios/ZCodeMobile.xcodeproj -scheme ZCodeMobile -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:ZCodeMobileTests` → **40 tests, 0 failures，`** TEST SUCCEEDED **`**（2026-10-04 11:43，xcresult 留痕 DerivedData：Test-ZCodeMobile-2026.10.04_11-43-31-+0800.xcresult）——**v1.5 同命令曾因 PTY 环境阻塞失败，本轮成功，环境阻塞已消除**；⑦替身计数器核对：blockedWriteCommandCount/sendTextCount/resolveInteractionCount(+lastResolveInteraction)/stopCount/renameTaskCount/unarchiveTaskCount/listArchivedTasksCount/lastRenameTitle（E2ELoginStubServer.swift:81-101）；blockedWriteCommandCount==0 收口断言在 LoginE2ETests **19 处**（grep 命中 20 处含 :1254 注释行 1 处——v1.6 二次修订按审阅意见以断言计更正）；⑧**孤儿文件恶化实锤**：`grep -n "ZCodeMobileUITests.swift" ios/ZCodeMobile.xcodeproj/project.pbxproj` → 0 命中（不参与任何 target），新增 testSettingsNotificationsTogglePersist（:229）不可执行（§10.8-2）；⑨门禁脚本/工作区内 xcresult `find` 复跑零命中（与前六轮一致）；⑩test16/17/18 行号实测（:1255/:1389/:1429）与验收材料自报（:1224-1249 等）漂移约 30 行，如实标注（改写期漂移，参数实质核对属实）。
- **v1.6 二次修订核验（第八轮实测；独立审阅意见 14 条逐条复测）**：①/tmp 失效实锤：`for p in /tmp/relay-log-stream.txt /tmp/relay-probe.html /tmp/relay-verify-*.png /tmp/relay_swift_verify /tmp/relay-js /tmp/login_design_check.png /tmp/zcode-api-research /tmp/zcode-api-readonly /tmp/zcode-api-client /tmp/zcode-research; do …` → **14 条全部 MISSING**（`ls /tmp/` 无任何 relay-*/zcode-* 条目）——M1.z 验收证据、swiftc 等价验证产物、三个调研克隆件全部消失（§9.3/§9.5/§11.1/§12 风险 1/2/6/20/21 更新）；②行号勘误·续二逐值复测：call :795/append :803/抛错 :806、AppSession :137/:141/:232/:244/:282、StoreProtocols :55/:129/:153、05-questioncard :277、mock 标题 :288/:294、04-row :413/:470、设计稿 Tab :613-616 与 :842-845（grep/sed 实测）；③relay-handoff.md `wc -l` = **29 行**（报告三处 27 行更正）；④blockedWriteCommandCount 断言 19 处（20 处含 1 注释行，`:1254p` 实测为注释）；⑤孤儿复确认：`grep -c 'ZCodeMobileUITests.swift' ios/ZCodeMobile.xcodeproj/project.pbxproj` = 0；⑥DerivedData xcresult：`ls ~/Library/Developer/Xcode/DerivedData/ZCodeMobile-*/Logs/Test/` → 仅 2026.10.04_11-05-25 与 11-43-31 两枚（M1.z 失败留痕 03-21-42 已不存在，§9.5 括注更新；11-43-31 与本会话单测 40/40 吻合）；⑦外链复测 `curl -sI -m 6`：pixso HTTP **200**、github HTTP **200**（彼时 github 000 为沙箱时点结论，§12 风险 10 更新；「内容未人工核验」边界维持）；⑧P2 断言缺口实锤：`grep -rn '05-act-search\|05-search-messages\|binaryPreview' ios/Tests/ ios/UnitTests/` → 仅替身应答与放行白名单命中、无 UI/E2E 断言（§10.6 补录 #26/#27、§10.8-8）；⑨P1-4 断言在位实锤：test06 内 :438-441 `createSessionWithFirstInputCount >= 1`（§10.6 补录 #25）。

## 12. 风险与已知限制（v1.2 重排、v1.3/v1.4/v1.5/v1.6 增补更新，按严重度）

| # | 风险/限制 | 说明与缓解 |
|---|---|---|
| 1 | **门禁结果不可复核（立项前置整改项，M0/M1 迭代一/M1.x/M1.y/M1.z/M2 六轮同适用）** | 五轮构建与 e2e 中「通过」的均为阶段自报；工作区无门禁脚本、无 xcresult/日志（v1.5 实测仍零命中）；测试代码引用的「统一门禁脚本」不存在。**例外（「已运行检查」两类执行主体的口径区分）**：①**迭代会话执行**（v1.3 §7.6 的 3 项：图标 md5/pbxproj 核验/build-for-testing；M1.z 的 macOS swiftc 同源等价验证 32 项 PASS 亦属此类）；②**报告撰写会话执行**（v1.4 起：M1.y 单元测试 10/10 实跑通过；v1.5 尝试实跑 M1.z 单测**未能运行**——PTY 环境阻塞独立复现，§9.5 括注；另含各轮工作区实测）；③**v1.5 新增第三类**：真实链路验收会话实地产出的日志全文与截图（/tmp，工作区外但可复核，§9.3）。风险 1 所指的「测试级证据」仅第②类中实跑通过的 M1.y 单元测试一项；各类同样无 xcresult 等工作区凭据留存，均不构成门禁复核豁免。**M1.z e2e=false 为六轮中首个如实标注 false 的门禁项**（环境阻塞，§9.5）。**v1.6 更新**：①该轮报告撰写会话实跑单元测试 **40/40 通过**（ReadOnlyGateTests 16 重写后全量+RelayLinkTests 18+TaskNotificationContentTests 6，xcresult 留痕 DerivedData，§10.7）——**M1.y/M1.z 两轮遗留的「单测待补跑」就此闭环**，且 v1.5 的 PTY 环境阻塞实测已不再复现（M1.z e2e 补跑的环境前提已具备，**但仍未执行**）；②M2 的构建=true/e2e=true/验收全绿=true 为阶段与验收会话自报，撰写会话未复跑完整 UI 套件。**v1.6 二次修订追加（最严重）**：M1.z 的真实链路端到端验收证据（/tmp 日志 65,805 字节+截图 8 张+probe.html+relay_swift_verify+relay-js 探针与 bundle）**现已全部不存在**（14 条路径逐一 MISSING）——该验收降级为「v1.5 验收会话记录、彼时复核一致、现不可复核」，M1.y 392 项对照的克隆件抽查证据（/tmp/zcode-api-client）同批消失；本报告撰写会话新产 xcresult（DerivedData 11-43-31，单测 40/40）成为当前唯一在盘测试凭据。**整改（更新）**：门禁脚本入库（xcodegen generate + build + test 三步），评审/开工前实跑并留存 xcresult（含 M1.z e2e 补跑与 M2 全套件批量实跑）；**测试/验收证据一律落盘工作区或指定归档目录（不得依赖 /tmp）**；M1.z 验收重建列 §14.2；复核 §11 后各里程碑方可记为达标 |
| 2 | **真实后端端到端未验证（v1.5 更新：中继路径已验收，直连与 OAuth 仍未）** | **v1.5 更新（v1.6 二次修订降级：该验收的 /tmp 证据已全部消失，结论降级为「v1.5 验收会话记录、彼时复核一致、现不可复核」，重建列 §14.2）**：①**云中继路径的「真实桌面端数据面」曾于 v1.5 端到端验收**（§9.3：真实配对链接→zcode.z.ai 中继→真实桌面 auth/bootstrap 240 tasks/桥内 v4 握手/sessions-index 订阅→真实会话渲染，44 分钟+ 稳定；凭据已失效）——桥内走的是真实桌面端 zcode-agent/zcode-task 服务，M1.y 所列「新增只读面未经真实服务端联测」中的 sessions-index/订阅主链路已获真实桌面佐证；②**仍未验证**：局域网直连路径（`zcode --web` 五步，含真实 server-info 发现与令牌校验）、真实 chat.z.ai OAuth 授权页（§12 风险 13）、M1.y 六项确认清单中的双写投影/promoteDeferredDraft 降级/accountAccess schema 等写面与 additive 面、真实云中继的会话历史/详情面（bridge 内 getTaskSnapshot 族等，验收止步于列表）。**缓解**：端到端联调列为 §14.2 第 2 项（第 1 项为门禁凭据复核前置）；中继链路已证明协议栈（RPCSerialization/V4Wire/Store 面）在真实桌面端可用，直连路径风险相应下降；字段缺席时回退 nil/演示值、不渲染死控件。**v1.6 扩大**：M2 纠偏恢复了连接态发送/审批/停止/新建四类**写命令下发面**，其在真实桌面的行为验证进入联调范围——resolveInteraction 应答 payload（answer={approved,scope}/提问文本，含顶层冗余兼容）、v4 stop/renameTask/unarchiveTask/createSession+firstInput 信封均按阶段材料口径构造、**未与真实桌面逐字段核对**（§12 风险 22、§14.2-⑩） |
| 3 | **孤儿 UI 测试文件（v1.6 恶化：8→9 条且新增用例仍入孤儿）** | ios/ZCodeMobileUITests/ZCodeMobileUITests.swift 不在任何 target（project.yml 的 UITests target sources 仅 `path: Tests`，实测；v1.6 复测 pbxproj 对该文件 0 引用）。**v1.6 恶化**：M2 新增的 testSettingsNotificationsTogglePersist（通知开关持久化）被加入该孤儿文件（实测 :229），现 9 条用例全部不可执行——验收清单所引 testTaskBoardApprovalSheet/testTerminalOutput/testFilePreviewAndDrawer 扩展断言同属该文件、**断言在位但当前不可运行**（§10.8-2），对应验收面以编译内套件降级覆盖（演示审批卡=ZCodeMobileE2ETests test03、文件预览=LoginE2ETests test14②③）。**整改**：收编（sources 改指或迁入 ios/Tests/）后全量回归，列 §14.1 M1 剩余研发 |
| 4 | **实现与设计基准存在系统性偏差（主稿）** | ①Tab 命名/顺序（实现「会话/任务/文件/设置」vs 设计稿「任务/对话/审查/我的」）；②提问卡 id 三方不一致（`05-questioncard` vs HTML `05-ask-chip-1/2` vs spec §5.10）。**整改**：基线冻结二择一并全量回归 id。登录域（O/L）为设计先行、偏差风险低，但同样待全量回归 |
| 5 | **并行进程冲突遗留（实现期已发生）** | 见 v1.1 记录（Trae CN IDE ai-agent 三次覆盖工程文件，21 vs 25 文件差 4 个未溯源）。M1 迭代开工/收尾已执行并发检查（§6.7 补充验证③）；后续仍需警惕宿主进程写工作区 |
| 6 | **调研证据未落盘（部分缓解）** | 早期 27 模块调研与竞品结论仍无原始材料；克隆件 /tmp/zcode-api-research（v3.14.3）曾两轮抽查 22 处属实、2 处未能定位（§11.6），并据此更正 1 处可证伪数字（TUI 文件数）——**v1.6 二次修订实测：/tmp/zcode-api-research、/tmp/zcode-api-readonly、/tmp/zcode-api-client 三个克隆件现已全部不存在**，抽查结论降级为「彼时实测记录、现不可独立复核」，§8 引言③ 的复算范围声明同步失去克隆件支撑。**整改**：调研笔记/引用清单落盘 docs/，或评审认可「转引+抽查」采信口径（§2 开头已给出该口径） |
| 7 | **Web 半成品脚手架已清理** | 不属于交付；交付物仅 iOS 工程（ios/）、设计基准（design/）与文档（docs/） |
| 8 | **占位交互与体验欠账（v1.4 更新）** | 语音按住说话、分享、桌面端接力仍占位；retry 为 no-op；KeepAlive/Ack 帧忽略；**断线无自动重连/指数退避**（WS 断开仅置 .disconnected，AppSession.Mode.disconnected 仍无赋值方，横幅不出现、重连靠用户重走连接流程——本轮 resync 丢帧自愈不解决断线本身）。**v1.4 已兑现**（自 v1.3 欠账核销）：置顶/已读/归档双写持久化、刷新额度（usage-stats 两只读接口）、历史向上翻页（loadOlder）、丢帧 resync 自愈、服务端文件搜索、任务推送活性。**v1.6 更新**：断线自动重连与横幅已落地（onConnectionDropped+重连，test17，§10.6-5）；语音按住说话、分享、桌面端接力仍占位；**retry 维持不下发**（retryTurn 需 target{rowId,entityId} 游标、UI 无重试入口，M2 如实不虚构）；KeepAlive/Ack 帧忽略维持；**任务服务端搜索已接**（searchTasks 防抖透传）而会话服务端检索未接、会话内搜索命中高亮/跳转未做（§10.8-4）；订阅失败兜底与连接态归档行口径不变 |
| 9 | **Light 实渲稿覆盖不全** | 主稿其余 10 屏无 Light 实渲稿；**登录稿未做 Light 逐板回归与真机渲染、辅助功能审计**（设计阶段声明） |
| 10 | **外部链接未核验（v1.3 二次修订更新时效）** | Pixso 原稿与 GitHub 仓库两个外部链接未联网核验其内容（v1.2 二次修订轮独立审阅复核亦不可达：对 github.com/zai-org/ZCode 的 WebFetch 实测 Connect Timeout，沙箱无外网，Pixso 与 docs.qoder.com 同样无法访问——彼时结论）；**v1.3 二次修订 `curl -sI -m 5` 复测：pixso 200、github 000（彼时沙箱不可达）、docs.qoder.com 307；v1.6 二次修订 `curl -sI -m 6` 再复测：pixso HTTP 200、github HTTP 200（**github 页面存在性本次已可确认**，彼时 000 为沙箱时点结论）、docs.qoder.com 307**——对评审查证成本更有利，链接内容核验（Pixso 画布 / 仓库 HEAD）仍待人工执行；**Pixso 登录稿同步 pushed=false（86 处叠印）**，画布节点 9:1 仅供查看（§6.5） |
| 11 | **自定义二进制协议未冻结、版本无兼容承诺** | core.ts:2 草稿声明；`SERVER_REMOTE_PROTOCOL_VERSION=1`、`V4_WIRE_PROTOCOL_VERSION=3` 无兼容性承诺。**缓解**：实现严格校验失败即报错不降级（§4.4）；协议定版跟踪列入 §14 |
| 12 | **访问令牌无过期与撤销；ws:// 无 TLS** | 令牌随进程存活、cookie 无 Max-Age；仓库无 TLS。**缓解**：Keychain 按「服务器密码」管理、401 引导重扫码、仅可信局域网提示、wss 需自备反代（§6.3 gap 4） |
| 13 | **应用内 WebView 授权页兼容性未知** | chat.z.ai 风控/验证码/CSP 对内嵌 UA 的策略未验证，可能出现无法完成授权的产品风险。**缓解**：人工验证排入端到端联调第一项；两载体禁止注入自绘同意页；必要时回退系统浏览器载体（需产品再决策） |
| 14 | **web-remote-replayable 功能面降级** | /ws 固定 role=terminal-client，provider-provisioning-target 频道拒绝，Web 模式不能页内再开远程工作区。iOS 功能面按「单 workspace 会话/任务/文件」设计（§6.3 gap 6）；v1.3 起客户端侧另把连接态收敛为只读（§7.2），本条指服务端能力面，二者叠加 |
| 15 | **relay 受信链路依赖仓库外组件** | /ws/host + /api/rpc-host-capability 服务端入口在仓库，但桌面侧连接端与外部 relay 均不在开源仓库；MVP 走 zcode --web，接 relay 需后端/桌面团队提供组件（§6.2 路径 B 第 8 步） |
| 16 | **bundle id 迁移的一次性凭据失效（v1.3 新增，影响已知且可接受）** | Keychain service 常量随品牌迁移改为 `cn.biuz.mobile`（KeychainStore.swift:16），旧 service（cn.zcode.mobile）写入的 tokenSet/userInfo/服务器配置对新 App 不可见——**已装旧包的设备升级后需重新 OAuth 登录与重新配对桌面端**；App Store Connect 需以新 bundle id 新建记录，旧 cn.zcode.* 不可复用。**缓解**：工程未上架、无外部存量用户（§14 路线尚在「上架计划」之前）；E2E 每用例 `-ZCodeE2EResetState` 清空凭据态，测试不受影响（§7.1）；若不迁移则与版权隔离目标相悖，属权衡后取舍 |
| 17 | **连接态能力收窄的产品影响（v1.3 新增；v1.6 已由 M2 纠偏反转，改记残余）** | 只读边界后，移动端连接态**不可发送消息、不可审批/拒绝权限、不可停止任务、不可应答提问**（execution 类三层拦截，§7.2/§7.4）——立项目标中「批」闭环在连接态由桌面端完成，移动端退化为「看板 + 只读会话/历史/Diff/任务 + 空会话创建」；与竞品「遥控与审批台」范式相比收窄了审批面。**v1.6 反转**：M2 经桌面功能对照认定 v1.3 口径为**过度限制**并完成纠偏——连接态**发送消息/审批与提问应答（含三档授权范围）/停止任务/新建会话带首条指令**已恢复（§10.1/§10.4），「批」闭环回到移动端；桌面对照材料判 skip 的 Git 仓库写/终端/设置同步/插件写族**保持拦截**（§10.2）。**残余**：①放行命令信封在真实桌面的字段契约未对照（风险 22）；②审批 scope 语义（仅本次/本任务内/始终允许）在桌面端的真实执行语义未联测；③服务端对 web-remote-replayable 不收窄命令面的结论不变——边界仍是客户端自律（§7.8-5） |
| 18 | **M1.y 新增只读面按参考 schema 宽容解析，未真机联测（v1.4 新增）** | workspace-config/model-selection/oauth/usage-stats/getTaskConfigOptions 等新增面的回执解析按参考仓库 schema 宽容处理，字段缺席回退 nil/演示值；**schema 非 additive 演进**（字段改名/结构变化）会导致对应 UI 区块静默不渲染（缺数据不渲染死控件口径，不致错误数据但不自预警）；双写（setTaskPinned 等）在服务端不回投影时以本地 override 兜底，持久化语义依赖服务端投影（§8.7 清单）。**缓解**：6 项确认清单并入 §14.2 第 2 项联调；替身已按参考 schema 闭环验证；`try?` 容忍单面失败不阻断连接。**v1.6 扩面**：M2 新增面同一口径——resolveInteraction 应答 payload、file.readBinaryPreview（base64/bytes 宽容解析、失败降级不虚构预览）、listTaskList searchQuery 检索回执、listArchivedTasks 归档样例、NotificationService 的任务事件投影（onDynamicTaskEvent 已有面）均按材料 schema 宽容解析、未真机联测（§10.8-3） |
| 19 | **对照计划部分分类未逐行核实，采保守处置（v1.4 新增）** | 参考盘点 gaps 自认：多数服务 host 端实现未逐一深读（未深读者分类依据接口契约注释与抽查）、mutateTask resume 变体服务端行为未核实、offPeakTask.createTask 派发时序未核实、connectionScope 对 terminal-client 的方法级拦截未逐一枚举（§8.7）。**缓解**：三类未核实面均采保守处置——mutateTask 频道级整体拦截、off-peak-task 整体 skip、不接任何未深读写面；黑名单「未知命令放行」的残余面靠「UI 无入口」兜底（§7.8-5 口径不变） |
| 20 | **中继协议为闭源逆向、可能随版本变化（v1.5 新增，M1.z 特有）** | remote/v4 云中继协议无开源参考实现（与 §6/§8 的开源仓库逐行移植不同）——本轮全部结论出自官方网页壳与 **57 个前端 bundle 的字节偏移逆向 + Python 探针实测**（§9.1）；上游前端随桌面版本（当前 3.14.4）发版即可能变更帧序/常量/鉴权细节而无兼容承诺，届时中继路径静默失配。**缓解**：①~~探针脚本沉淀 /tmp/relay-js/（6 个，可对新版本 bundle 复验）~~——**v1.6 二次修订重写**：v1.5 二次修订已更正为 7 个（此处系漏改，审阅 #6 属实）；且 /tmp/relay-js/ 现已整体不存在，**复验能力丢失**——重建需重新下载目标版本 bundle 并重写探针（探针逻辑要点留存于 §9.1 文字），协议跟踪改为「桌面端升级后按 §9.1 帧序/常量文字对照复验」；②实现侧防御——bootstrap-response.desktopAppVersion 与链接 app_version 比对的版本漂移告警（对齐 web 端 jVn）、auth/错误帧/close code 六值分型降级、未知帧忽略不崩溃、协议版面常量集中于 RelayLink/RelayFrameCodec 单点可改；③真实验收 0 轮代码修复说明当前实现与现网 bundle 一致；④协议跟踪列入 §14.2 联调项，桌面端升级后需复验。**残余**：逆向结论中的 7 条 gaps（§9.1）特别是 recoveryId 恢复语义、terminal_sid 用途、服务端 execution 边界均未实证，对应实现采保守策略（§9.6） |
| 21 | **配对凭据（sid/hash）时效与泄露面（v1.5 新增；v1.5 二次修订按审阅意见重写缓解声明——原「文档与交接材料不留存工作区」与事实不符，已更正并整改）** | 云中继凭据 = 配对链接中的 sid + hash（hash 原字符串即 HMAC-SHA256 密钥，等同口令级敏感面）：①**时效**——链接 t 参数仅做 Number.isFinite 校验，服务端过期策略未验证（§9.1 gap ①），链接被转发/泄露后在过期前的窗口内可被第三方用于连接；②**传输**——wss（TLS）承载，凭据不出现在 WS URL query（仅 mid），auth proof 为 HMAC 挑战应答、hash 本体不上行（§9.1 帧序）；运行日志已掩码（/tmp/relay-log-stream.txt 审阅复核 sid 无明文）；③**存储（App 侧合规）**——实现仅存 Keychain（ServerConfig.relay，Codable 兼容），不落 UserDefaults/日志；④**撤销**——服务端撤销/设备解绑接口未知，唯一已知失效路径是桌面端重新生成链接。**凭据留存实况（v1.5 二次修订 `grep -rln` 全量扫描，原缓解声明不成立之处如实列明）**：工作区内 docs/relay-handoff.md 曾以完整明文留存链接（**已由本轮掩码整改**：sid/hash 中段掩码、保留形态与 mid/name/app_version，掩码注记留痕）；ios/ 源码 2 处——RelayLink.swift:90 注释（sid 完整、hash 部分掩码）与 RelayLinkTests.swift:11-22/:62（proof 单测向量内嵌完整真实 hash+sid，向量为「锚定探针实测」的设计取舍）；/tmp 8 文件（7 个探针脚本 + relay_swift_verify/main.swift:10-12）均内嵌完整凭据——探针需真实凭据才能复验协议，属功能需要，已列评审后清理项——**v1.6 二次修订核实（审阅 #14，与既有披露一致属未整改状态确认）：上述 /tmp 文件已随 /tmp 清理全部消失（「评审后清理项」事实上达成），但协议复验能力一并丢失（§12 风险 20①）；工作区留存面不变**：relay-handoff.md（已掩码，现 29 行）+ RelayLink.swift:90 注释 + RelayLinkTests.swift:11-22/:62 向量（审阅复测在位，安全整改仍列 M1 剩余研发第一项）。**整改**：①单测向量替换为合成向量（自算 HMAC 期望值，保持测试有效性）并回归 build——列入 M1 剩余研发第一项安全整改（改源码需重新构建验证，本轮未擅动）；②/tmp 全部含凭据文件评审后删除；③产品化前与中继服务方确认过期/撤销/解绑策略（并入 §14.2 联调确认清单）；④交接/评审材料的链接一律掩码流转 |
| 22 | **纠偏放行命令在真实桌面的行为未验证（v1.6 新增，M2 特有）** | M2 将连接态从「只读」反转为「客户端发命令、桌面代执行」（sendText/resolveInteraction/stop/createSession+firstInput/renameTask/unarchiveTask 等），信封形态（commandId/clientId/sessionId/type/payload）与应答 payload（answer={approved,scope}、顶层冗余兼容）按阶段材料与替身口径构造，**实现与撰写会话均无桌面端源码、未与真实桌面逐字段对照**（M1.z 真实验收止步于列表只读面，§9.3）；若字段不符，表现为命令静默无效或桌面报错，替身测试无法暴露。**缓解**：①实现侧集中收口（RemoteConversationStore.resolveInteractionRaw 与 RemoteTaskStore.resolvePermission 等两三处可调，§10.8-3）；②乐观回显失败路径如实反馈未送达、不虚构成功；③blockedWriteCommandCount==0 收口保证纠偏不越直写边界；④联调对照列 §14.2-⑩（真实桌面逐命令验证：发送回显去重/审批应答生效/停止翻转/重命名与取消归档投影） |

## 13. 立项决策所需信息（v1.4 更新、v1.6 增补，仍有多项空缺，如实列明）

以下评审必需要素在现有材料中**均无数据或未闭环**，本报告不编造估算；需业务方/评审会补齐或授权专项评估后再批预算与排期：

| # | 要素 | 现状（v1.4） |
|---|---|---|
| ① | **成本估算** | 人力投入、Apple Developer 账号、真机设备、后端联调资源等，材料无任何估算 |
| ② | **排期** | §14 各里程碑仅定义内容与出口标准，无时间估计；端到端联调工作量取决于风险 2/13 的验证结果 |
| ③ | **成功指标** | 无北极星指标与数值口径（目标用户规模、留存、审批时延目标值、崩溃率预算等均未定义） |
| ④ | **后端依赖成本（v1.2 部分明确、v1.4 部分收敛、v1.5 再收敛+新增云端依赖）** | 协议契约已查明（§6.3：40+ 频道、13 字节帧、v4 wireVersion=3、两常量版本）；MVP 路径（zcode --web）**无需桌面 App 配合改动**；但 relay 受信链路的桌面侧连接端与 relay 服务不在开源仓库（风险 15），逐文件审批口径需桌面团队确认（§6.8 ⑥；pin/archive 写面已于 v1.4 确认存在并接入，§8.1 纠错 #8）；协议未冻结带来的版本联动节奏未约定。**v1.3 追加**：只读边界为客户端自律（§7.8-5）。**v1.4 收敛**：接口面已按参考客户端完成全量 392 项逐方法对照（§8.2），接口决策成本已收敛；服务端可按连接裁剪暴露面（/ws/remote/:id 四通道先例 http.ts:433-443），若产品要求服务端按 clientMode 强制只读，技术上存在上游可落地的先例路径，需上游/桌面团队配合。**v1.5 再收敛**：M1.z 证实官方云端中继（zcode.z.ai/ws，网页壳同款端点）**已可用且无需自建 relay 服务**——跨网远控的后端依赖由「需桌面团队提供组件」（风险 15）降为「依赖官方托管端点的可用性与协议稳定性」（风险 20/21）；凭据时效/撤销策略需与中继服务方确认（§14.2）。**v1.6**：M2 恢复连接态命令下发面后，与桌面团队的产品口径议题由「是否恢复移动端审批」（风险 17 原文）变为「**已恢复——payload 契约待真实桌面联调确认**」（风险 22，§14.2-⑩）；审批 scope（仅本次/本任务内/始终允许）与桌面端审批事件的双端语义需在联调中对齐 |
| ⑤ | **竞品证据落盘（v1.6 部分改善）** | TRAE/Qoder 范式结论仅有文字材料，无截图/链接存档。**v1.6**：M2 材料新增 19 条结构化竞品交互参考（pattern+来源+逐条建议，Qoder 官方文档/更新日志为主，部分转引如实标注，§10.3）——参考颗粒度显著改善，但**仍无落盘截图/存档**，本口径不闭环 |
| ⑥ | **调研证据落盘** | 见 §12 风险 6（克隆件在位但仍未落盘工作区；v1.3 另有 /tmp/zcode-api-readonly 只读边界调研克隆件，**v1.4 另有 /tmp/zcode-api-client 参考客户端盘点克隆件**，同口径） |
| ⑦ | **双移动路径优先级** | A 路线已按 MVP 落地并收敛为只读远控（§1.2）；B（IM Bot）是否投入仍待决策 |
| ⑧ | **团队/人力在位性（v1.2 二次修订新增）** | M1 剩余迭代至 M3 由谁执行、iOS 工程师/后端联调人力配置是否在位，材料**无任何团队组织信息**（§13-① 仅谈成本无估算，本文亦无法从材料推断）；需业务方在评审会明确执行主体与人力配置，否则排期（②）无法成立 |
| ⑨ | **数据合规与 App Store 隐私（v1.2 新增；v1.3 品牌面已改善）** | 当前仅 §14 M3 行一句带过，**无专项评估安排**；本产品涉及三类高敏面：OAuth 令牌与凭据存储（Keychain）、局域网远控（本地网络权限 + ws:// 明文链路）、相机权限（扫码）。需独立评估：App 隐私标签与采集数据类型申报、中国区备案/合规要求、OAuth 凭据出境路径——评估结论直接影响 M3 上架可行性与 §14.2 第 5 项节奏。**v1.3 改善项**：商标/上架合规的品牌面已就位——显示名 BiuZ、App 图标、works-with 元数据口径（naming.md 合规清单，§7.1）；剩余为商标检索人工流程与上述数据合规专项 |

## 14. 后续路线与里程碑建议（v1.4 更新、v1.6 增补；顺序保持 v1.2 二次修订唯一化口径不变）

### 14.1 里程碑（本次申请批准范围：M1 剩余研发–M3；M4 为评估项；与 §0 同一口径）

| 里程碑 | 内容 | 出口标准 | 状态 |
|---|---|---|---|
| **M0 立项前预研验证** | 功能调研、设计基准、iOS 原生 mock 版编码与自检 | 模拟器构建通过 + e2e 5 流程通过 + 设计基准 ready | 编码完成；门禁为阶段自报，待按 §12 风险 1 复核后方可记为达标 |
| **M1 后端接入**（申请立项） | **迭代一（已于立项批准前完成，§6，报请评审追认）**：两条登录路径、传输层移植、真实 Store、登录/连接域 UI、登录 e2e 与替身服务器、spec v2.4。**M1.x 迭代（已于立项批准前完成，§7，报请评审追认）**：品牌迁移（BiuZ 定名/bundle id cn.biuz.mobile/App 图标/界面文案/设计稿与文档）+ 移动端只读边界（execution 三分类与三层拦截）+ 全面验收 21 项 + 布局走查 8 用例（§7.6/§7.7）。**M1.y 迭代（已于立项批准前完成，§8，报请评审追认）**：按 packages/client 参考完成移动端接口对接——40 服务/392 方法条目五类处置（integrate 32/skip-boundary 122/defer 223 等）、ReadOnlyGate 出口必拦扩展（201 命令+2 双态）、连接态只读面 15 call+3 listen→40 call+7 listen、替身扩展与 3 个新 e2e 用例+10 个单测用例（§8.2–§8.5）。**M1.z 迭代（已于立项批准前完成，§9，报请评审追认）**：云中继接入（remote/v4）——协议逆向（网页壳+57 bundle+探针实测）、Relay 四件套实现（App 源码 43→47）、只读边界经 RPCChannelTransport 门面在云中继路径同等生效、**真实链路端到端验收通过**（真实配对链接一次连通真实桌面端、auth matched→bootstrap 240 tasks→桥内 v4 握手→sessions-index 订阅→真实会话渲染、44 分钟+ 稳定、0 轮代码修复，§9.3；测试面 40→62 用例，e2e 门禁因环境阻塞如实为 false 待补跑）。**M2 迭代（已于立项批准前完成，§10，报请评审追认；阶段材料命名为「M2：逐功能自测验收」，与下文路线里程碑「M2 真机与系统能力」同名不同指）**：只读边界过度限制纠偏（ReadOnlyGate 四分类口径修订）+ 连接态发送/审批/停止/新建四类交互恢复 + 会话重命名/标记未读/已归档分组补全 + 本地通知真实化 + P2 搜索/预览四项 + 桌面功能对照 23 项与竞品参考 19 条归档 + 验收清单 24 项（阶段口径全 pass；单测 40/40 由 v1.6 撰写会话实跑复核）。**M1 剩余研发（本次申请的研发起点）**：①门禁脚本入库并复跑留存凭据（评审/开工前置，§12 风险 1；含 M1.z e2e 补跑与 M2 全套件批量实跑）；②端到端联调（研发第一项，§14.2——**中继路径列表面曾于 v1.5 验收（证据已失效、重建列 §14.2-⑫），剩余为直连路径/OAuth 授权页/会话详情与写面投影/放行命令 payload 对照**）；③冻结设计基线（消除风险 4——Tab 命名/顺序与 testid 三方漂移）；④收编或删除孤儿冒烟用例（§12 风险 3，v1.3 二次修订补落点）。原「迭代二」第 ③ 项（test06 列表绑定与强断言整改）**已在 M1.x 兑现**（§6.7 v1.3 注） | 真实账号在手机端完成「创建任务→追踪执行→查看 Diff」全闭环（**v1.6 口径更新**：连接态发送/审批/停止已由 M2 恢复——客户端发命令桌面代执行，§10.1；残余为真实桌面 payload 契约对照，§12 风险 22） | 迭代一、M1.x、M1.y、M1.z 与 **M2** 完成（M1.z 真实链路验收证据**已随 /tmp 清理失效**——降级为「v1.5 验收会话记录、彼时复核一致、现不可复核」，§9.3 v1.6 注；单元级：M1.y 10/10 由彼时撰写会话实跑、**M1.z 面的 RelayLinkTests 18 用例与重写后 ReadOnlyGateTests 16+新增 TaskNotificationContentTests 6 共 40/40 由 v1.6 撰写会话实跑复验**——「M1.z 24 用例」系本行 v1.6 首版笔误，二次修订更正，§10.7；M2 验收清单 24 项为材料判定口径、载体与运行限制见 §10.6）；**局域网直连与 OAuth 端到端联调未做、放行命令 payload 未对照真实桌面，出口标准未兑现** |
| **M2 真机与系统能力**（申请立项；命名说明：本表路线里程碑编号沿用 v1.4 口径不变，与已完成迭代「M2 逐功能自测验收」（§10，记入 M1 行）同名不同指） | **真机签名**、真机调试、推送/Live Activity、语音与分享落地、流式吸底优化 | TestFlight 包分发内测；推送端到端可达（**v1.6 口径更新**：本地通知三类内容映射已随 M2 落地并经单测锁定，真机到达/点击路由未验——本里程碑承接真机验收与 APNs/Live Activity 扩展，§10.6-18） | 待批准（排期在端到端联调之后） |
| **M3 公测与上架**（申请立项） | 稳定性/性能打磨、Light 全屏抽检回归（含登录稿）、App Store 提审与**上架计划**（合规专项见 §13-⑨：隐私标签/采集数据类型/备案，覆盖 OAuth 令牌、局域网远控、相机权限三类高敏面）。**v1.3 注：上架合规因品牌迁移已改善**——显示名 BiuZ、App 图标、works-with 元数据口径均已就位（§7.1）；剩余为商标检索（naming.md 合规清单第 6 条）与数据合规专项 | 审核通过并发布；稳定性指标按 §13-③ 补定义后验收 | 待批准 |
| **M4 Android 评估** | 技术选型与立项评估（传输层移植件为 Swift；Android 需按 Kotlin 重新移植 RPC/v4 层与 ReadOnlyGate 拦截表，工作量参考 §4.4/§7.2；设计规范已含 Android 返回/面包屑条款） | 评估报告与立项决议 | 单独评估，不在本次申请范围 |

### 14.2 后续技术路线（v1.2 二次修订唯一化顺序保持不变：先门禁前置复核，后研发；全文仅此一处顺序定义）

1. **门禁与质量（评审/开工前置，先于一切研发动作）**：门禁脚本入库（xcodegen generate + build + test 两级）并实跑留存 xcresult，复核 §11 六轮自报结果（v1.2 所列 test06 断言整改已在 M1.x 完成，§6.7 v1.3 注；M1.y 单元级 10/10 已实跑复核，§8.5；**v1.6 更新：M1.z 单测待补跑已由 v1.6 撰写会话闭环（40/40 TEST SUCCEEDED，含 RelayLinkTests 18+TaskNotificationContentTests 6，§10.7）且 PTY 环境阻塞已消除；M1.z 的 e2e 门禁补跑仍未执行、环境前提已具备**；M2 全套件批量实跑一并列入本项，§10.7）；
2. **端到端联调（M1 剩余研发的第一项技术工作）**：与真实桌面端 Host（用户桌面机运行 `zcode --web --host 0.0.0.0`）和 chat.z.ai 授权页联调——①**绑定假设验证**：§6.8 ①–④ 逐项与后端/部署方确认（回调 scheme/client_id/令牌交换 origin/BigModel 入口）；②**人工验证一次真实授权页在应用内 WebView 的兼容性**——风控/验证码/CSP 对内嵌 UA 的策略（§12 风险 13），不兼容则触发载体回退决策；③真实桌面配对全链路（发现→鉴权→WS→v4 握手→只读订阅/查询/空会话创建）与断线重连实测，**含订阅参数在真实服务端的运行期验证**（参数构造已传全 workspacePath/sessionId，§7.8-2；订阅失败空态兜底会掩盖失败，联调时需显式断言订阅成功）；**v1.4 追加**：④M1.y 新增只读面逐面真机联测（§8.7 六项确认清单：双写投影/promoteDeferredDraft 降级判断/accountAccess schema/conversationFileChangesV4 base 参数/workspace-config topic 与 delta 词表/会话维度 Diff 的 sessionId 口径），并补 test13/14/15 运行时校验；⑤**协议冻结跟踪**：v4 数据模型为草稿（core.ts:2），以黄金测试定版为准，定版后回归序列化字节级交叉验证；**v1.5 追加（M1.z 云中继面）**：⑥中继路径剩余联测——会话历史/详情（bridge 内 getTaskSnapshot 族/rowsRange）、断线重连与桥重建实测（generation+recoveryId 保守策略的真实行为，§9.6-1②）、16MB×64 分片与饱和水位极限路径（§9.1 gap ④）、v4 topic 订阅帧（eventFire）在桥内的会话流订阅；⑦**凭据策略确认**：与中继服务方确认配对链接过期/撤销/设备解绑策略（§12 风险 21）与 t 参数语义；⑧**协议稳定性跟踪**：桌面端发版后以 /tmp/relay-js/ 探针对新 bundle 复验帧序/常量（§12 风险 20）；⑨M1.z 的 RelayLinkE2ETests 4 用例运行时校验（随 e2e 门禁补跑）；**v1.6 追加（M2 纠偏面）**：⑩**放行命令 payload 契约对照**——在真实桌面逐命令验证 M2 恢复面的行为：sendText 下发与乐观回显去重、resolveInteraction 应答（answer={approved,scope}/提问文本）真实生效与审批卡撤下、v4 stop 状态回流翻转、createSession+firstInput 转正、renameTask/unarchiveTask/listArchivedTasks 投影、审批 scope 三档在桌面端的执行语义（§12 风险 22，字段不符时调 resolveInteractionRaw/resolvePermission 两三处）；⑪通知真机验收（到达+点击路由）与孤儿 UITests 文件收编后 9 用例全量回归（§12 风险 3）；**v1.6 二次修订追加**：⑫**M1.z 真实链路验收重建**——桌面端重新生成配对链接后按 §9.3 流程复验（auth matched→bootstrap→桥内 v4 握手→订阅→渲染），日志与截图**落盘工作区或指定归档目录**（不得依赖 /tmp，§12 风险 1 整改），并顺带完成 §14.2-⑩ 放行命令 payload 契约对照与 relay 探针/调研克隆件重建评估；⑬M2 两项 P2 面补 E2E 断言（会话内搜索、图片/PDF 预览，§10.8-8）并落盘验收截图；
3. **真机与系统能力**：签名、推送与 Live Activity（屏 10 已预留信息架构）、流式吸底优化；
4. **Android 评估**：M4 评估后另行立项；
5. **上架计划**：TestFlight 内测（先只读远控/看板核心闭环）→ **商标检索**（中国商标网 9/42 类「BiuZ」「Biu」家族核查，naming.md 合规清单第 6 条，品牌迁移后新增的前置人工步骤）→ App Store 提审（元数据按 BiuZ/works-with 口径，§7.1）→ 正式发布与桌面端版本联动（前置依赖 §13-⑨ 合规专项评估结论）。

---

### 附 A：关键交付物索引（v1.6 更新）

| 交付物 | 路径 |
|---|---|
| 立项报告（本文；docs/project-proposal.md 为同内容副本——v1.6 更新后已同步，中文文件名与 ASCII 文件名双份逐字节一致） | [docs/立项报告.md](立项报告.md) |
| 品牌命名与商标避险报告（BiuZ 定名与 works-with 口径） | [branding/naming.md](../branding/naming.md) |
| **BiuZ 品牌资产（SVG 源稿 + PNG 渲染）** | [branding/logo/](../branding/logo/)（biuz-icon-1024、biuz-mark、biuz-lockup-dark/light 等；PNG 实测规格见 §7.1） |
| **App 图标与品牌位资产目录（M1.x 新增）** | [ios/ZCodeMobileApp/Resources/Assets.xcassets](../ios/ZCodeMobileApp/Resources/Assets.xcassets)（AppIcon.appiconset 单尺寸 + BiuzMark.imageset 84/168/252） |
| 设计规范 v2.4（BiuZ 标题；新增第 9 章登录与连接） | [design/design-spec.md](../design/design-spec.md) |
| 高保真主稿（14 屏，123 处 data-testid，品牌已换 BiuZ） | [design/zcode-mobile-design.html](../design/zcode-mobile-design.html) |
| **登录与连接专项稿（18 屏，102 处 testid/95 唯一，品牌已换 BiuZ）** | [design/login-design.html](../design/login-design.html) |
| iOS 工程（XcodeGen，含 M1 工程配置、M1.x 资产目录、M1.y ZCodeMobileTests 单测 target） | [ios/project.yml](../ios/project.yml) → ios/ZCodeMobile.xcodeproj |
| iOS 源码（**48 个 Swift 文件** = M0 25 + M1 17 + M1.x 1 + M1.z 4（Services/Relay/）+ **M2 1（Services/Notifications/）**；M1.y/M2 其余为加厚实现） | [ios/ZCodeMobileApp/Sources](../ios/ZCodeMobileApp/Sources) |
| **云中继实现四件套（M1.z 新增）** | [ios/ZCodeMobileApp/Sources/Services/Relay/](../ios/ZCodeMobileApp/Sources/Services/Relay/)：RelayLink.swift（105 行）/RelayFrameCodec.swift（244 行）/RelayChannelClient.swift（444 行）/RelayTransport.swift（802 行） |
| **只读边界拦截（M1.x 新增）** | [ios/ZCodeMobileApp/Sources/Services/Remote/ReadOnlyGate.swift](../ios/ZCodeMobileApp/Sources/Services/Remote/ReadOnlyGate.swift) |
| M0 编译内 E2E 用例（v1.3 实测 7 用例 = 5 既有 + 品牌断言/演示列表操作 2 新增） | [ios/Tests/ZCodeMobileE2ETests.swift](../ios/Tests/ZCodeMobileE2ETests.swift) |
| **M1 登录 e2e（v1.3 实测 12 用例 = 11 既有 + 连接态交互闭环 test12）** | [ios/Tests/ZCodeMobileLoginE2ETests.swift](../ios/Tests/ZCodeMobileLoginE2ETests.swift) |
| **M1 e2e 替身服务器（OAuth+RPC+v4+只读应答数据与读面计数器）** | [ios/Tests/E2ELoginStubServer.swift](../ios/Tests/E2ELoginStubServer.swift) |
| **布局走查套件（M1.x，8 用例 + 截图审计）** | [ios/Tests/LayoutAuditTests.swift](../ios/Tests/LayoutAuditTests.swift) |
| **中继链接 UI 回归（M1.z 新增，4 用例）** | [ios/Tests/RelayLinkE2ETests.swift](../ios/Tests/RelayLinkE2ETests.swift)（/remote/ 链接识别/拦截负例/-ZCodeRelayLink 冷启动钩子；编译级验证，运行待 e2e 门禁补跑） |
| **中继单测向量（M1.z 新增，18 用例实测）** | [ios/UnitTests/RelayLinkTests.swift](../ios/UnitTests/RelayLinkTests.swift)（解析/proof 实测向量/分片/重组/crc/ServerConfig 兼容） |
| **单元测试套件（M1.y 新增，ZCodeMobileTests target；v1.6 重写扩至 16 用例并实跑通过）** | [ios/UnitTests/ReadOnlyGateTests.swift](../ios/UnitTests/ReadOnlyGateTests.swift)（16 用例，四分类双向断言+blockedWrite 记录契约） |
| **通知内容纯函数单测（M2 新增，6 用例，实跑通过）** | [ios/UnitTests/TaskNotificationContentTests.swift](../ios/UnitTests/TaskNotificationContentTests.swift)（三类映射/去重键/80 字截断/running 不投递） |
| **本地通知服务（M2 新增）** | [ios/ZCodeMobileApp/Sources/Services/Notifications/NotificationService.swift](../ios/ZCodeMobileApp/Sources/Services/Notifications/NotificationService.swift)（139 行，实测；UNUserNotificationCenter 授权/投递/撤销+delegate 点击路由；真机到达为手动验收项） |
| **中继协议逆向与探针（M1.z，工作区外 /tmp/relay-js/，可复验）** | bundle 57 个 JS（index-BO-TaBle.js 6.2MB、src-D3H6NV7w.js 355KB 等）+ 探针脚本 **7 个**（ws_probe/ws_app_probe/ws_bridge_probe/disc_probe/disc_probe2/probe9/ws_bridge_agent_probe .py；v1.5 二次修订实测更正）+ 网页壳存档 page.html——**证据等级同 §12 风险 6（/tmp 未落盘工作区）；v1.6 二次修订实测：该目录现已整体不存在（含全部探针与 bundle），协议复验能力丢失（§12 风险 20①）；探针脚本曾内嵌真实配对凭据、随清理一并消失（§12 风险 21）** |
| **云中继真实链路验收证据（M1.z，原工作区外 /tmp/）** | /tmp/relay-verify-t1~t8.png、relay-verify-final.png、relay-verify-postcheck.png、relay-log-stream.txt（65,805 字节连接日志全文，sid 已掩码）、relay-probe.html——**v1.6 二次修订实测：以上文件现已全部不存在（14 条路径逐一 MISSING），评审已不可按本行复核；验收结论降级为「v1.5 验收会话记录、彼时复核一致、现不可复核」，重建列 §14.2-⑫（§9.3/§12 风险 1/2）** |
| **中继交接材料（M1.z 前置，工作区内）** | [docs/relay-handoff.md](relay-handoff.md)（**29 行，v1.6 二次修订实测**——v1.5 时 27 行、掩码整改新增注记所致，§9 引言/§11.6 同口径更正；配对链接与 iOS 现状交接，链接凭据已掩码、本体随时效失效。**v1.5 二次修订**：①链接凭据已掩码（原完整明文，§12 风险 21）；②真机验证命令在该文件「实施建议」节第 4 条（simctl launch -ZCodeRelayLink）——v1.5 首版误写「§3.4 真机验证入口」，本报告 §3 无 3.4 节、该文件亦无 §3.4 小节，指位已更正） |
| 孤儿冒烟用例（不参与编译，待收编） | [ios/ZCodeMobileUITests/ZCodeMobileUITests.swift](../ios/ZCodeMobileUITests/ZCodeMobileUITests.swift) |
| 早期契约对照物（不参与编译） | [e2e-contract-uitests.reference.swift](../e2e-contract-uitests.reference.swift) |
| Pixso 原稿（未联网核验；登录稿同步节点 9:1 pushed=false） | <https://pixso.cn/app/design/AbZPdSg5AO6CqRDpr5RKwg> |
| 接口调研克隆件（工作区外 /tmp，v3.14.3，未落盘） | <https://github.com/zai-org/ZCode> |
| 只读边界调研克隆件（工作区外 /tmp/zcode-api-readonly，浅克隆，未落盘） | 同上仓库 |
| 参考客户端盘点克隆件（M1.y 新增，工作区外 /tmp/zcode-api-client，浅克隆，未落盘） | 同上仓库 |
| **逐接口对照计划材料（M1.y，392 条处置表）** | 阶段材料，**未落盘工作区、评审不可独立复核五类拆分**（§8 引言③如实声明；§8.2 收录统计与取舍，§8.1 收录服务/条目层清单——该层已与克隆件暴露面实跑比对一致） |
| **macOS 同源等价验证（M1.z，原工作区外 /tmp/relay_swift_verify/）** | swiftc 编译工程内同一份源文件执行单测同断言 32 项 PASS（实现会话产出；PTY 环境阻塞下的替代验证，§9.5）——**v1.6 二次修订实测：该目录已不存在，产物失效；其覆盖面已由 v1.6 撰写会话单测实跑 40/40（含 RelayLinkTests 18）实质替代（§10.7）** |

### 附 B：v1.1 修订对照（审阅意见 → 处置，原文保留）

> 注：下表章节号为 **v1.1 时点编号**（彼时 §6=构建与 e2e、§7=风险、§8=决策信息、§9=路线）；v1.2 起各章顺延为 §7/§8/§9/§10，M1 章节为新增 §6；v1.3 起再顺延为 §8/§9/§10/§11，M1.x 章节为新增 §7；v1.4 起顺延为 §9–§12，M1.y 章节为新增 §8；v1.5 起再顺延为 §10–§13，M1.z 章节为新增 §9；v1.6 起再顺延为 §11–§14，M2 迭代章节为新增 §10。**附 B–附 G 表内章节号均为彼时编号，不随 v1.3/v1.4/v1.5/v1.6 顺延**（附 G 为 v1.4 对照、使用 v1.4 编号；附 H 为 v1.5 对照、使用 v1.5 编号；附 I 为 v1.6 对照、使用 v1.6 编号）。

| 审阅意见 | 处置 |
|---|---|
| 1 文档定位断裂（M0 同日「已达成」） | 新增 §0 报告定位；§9 重排为「申请 M1–M3 立项，M0 为预研且门禁待复核」 |
| 2 门禁无凭据 | §6 重写：降级为「阶段自报、待复核」，列明 find 零命中与测试文件内对门禁脚本的自述；列 §7 风险 1 整改项 |
| 3 冒烟用例孤儿文件 | §4.2/§5/附 A 更正；§7 风险 3 |
| 4 协议名/AppStore 门面失真 | §4.3/§5 更正为代码实况（Conversation/Task/File/Settings Store + @Entry 注入 + UserDefaultsSettingsStore） |
| 5 文件数 21 vs 25 | §5/§7 风险 5 更正为实测 25，并注明与阶段口径的差异未溯源 |
| 6 Tab 命名/顺序与 TabView 表述失真 | §3.1/§4.1/§5 更正（ZStack+opacity、会话/任务/文件/设置、默认 .chat）；§7 风险 4 |
| 7 屏 08 统计照抄设计稿 | §5 更正为 mock 实况 +33/−11，注明 +86/−12 为设计稿示意 |
| 8 选择器 08-filecard-d1 漂移 | §6.3 更正为 08-filecard-toggle-d1 并附 grep 证据 |
| 9 提问卡 id 三方不一致未覆盖 | §3.3 缺口 2 新增；§7 风险 4 合并处置 |
| 10 「90+ a11y」无口径 | §4.3 更正为实测 69 处并给出命令 |
| 11 调研证据消失 | §2/§7 风险 6 如实声明；§8-⑥ 列为整改 |
| 12 成本/排期/指标等空缺 | 新增 §8 决策所需信息（七项，全部如实标注空缺） |
| 13 外部链接未核验 | §3.2/§7 风险 10/附 A 标注 |
| 14 正面核对结果 | 已核对项保留并在 §4.3/§5/§6.5 标注实测来源 |

### 附 C：v1.2 修订对照（本次迭代内容 → 章节）

| 迭代内容 | 处置章节 |
|---|---|
| 接口调研（发现/鉴权/端点/流式/配置/gaps） | §6.2 路径 B、§6.3；证据抽查 §7.6 |
| OAuth 机制逐行核实（双入口/回调/令牌交换/Bearer） | §6.2 路径 A、§6.1；实现 §4.4 |
| 「仓库无账号体系」早期结论修正 | §6.1、§2.1 core「登录与账号」行 |
| 登录设计（spec v2.4 第 9 章 + 专项稿 18 屏 + 评审 8 条修复） | §3.2/§3.3、§6.4 |
| Pixso 同步（pushed=false，86 处叠印） | §6.5、§8 风险 10 |
| M1 实现（传输层 3 件/OAuth/Keychain/连接/真实 Store/双域 UI/工程配置） | §4.4、§6.6；文件数 25→42 实测 |
| 门禁结果（构建/e2e 自报 + 序列化交叉验证 + 冒烟） | §6.7、§7.1/§7.4 |
| 绑定假设 8 条与待确认项 | §6.8 |
| 应用内授权安全取舍（RFC 8252 vs 内嵌 WebView） | §6.9、§8 风险 13 |
| 后续路线（端到端联调→真机签名→Android→上架） | §10 |
| 风险表重排（+协议未冻结/令牌无撤销/WebView 兼容性/功能面降级/relay 依赖） | §8（15 条） |
| 实测口径更新（a11y 69→139、testid 102/95、11 用例、project.yml 83 行） | §4.3、§7.6 |

### 附 D：v1.2 二次修订对照（独立审阅意见 9 条 → 处置）

| # | 审阅意见 | 处置（含本次复核实测） |
|---|---|---|
| 1 | test06 算不算通过无法判读 | 实测逐行核对 ios/Tests/ZCodeMobileLoginE2ETests.swift:361-429：test06 为「数据面强断言 + UI 断言」混合、并非纯数据面验证；唯一降级断言为列表内容「替身行∨远端空态」二选一（:425-428）。§6.7 如实声明③ 给出判定标准（按改写后断言集判定；强断言须先修列表绑定），e2e 门禁行加采信边界指针；断言整改列入 §10.1 M1 迭代二 |
| 2 | 自证抽查「9 处」实列 13 处 | 承认口径错误。全文统一为：首轮 **13 处（按文件归并 10 组）**全部属实 + 二次修订增补 9 处属实、2 处未能定位，合计 22 处/2 处（§7.6 两轮明细、版本头⑤、§2 开头、§6 开头、§8 风险 6 同步更正） |
| 3 | 立项申请范围口径不一 | 统一为「**M1 剩余迭代（迭代二）–M3**」：§0 改写并明确 M1 迭代一为立项前完成的前置事实、报请追认；§10.1 标题与 M1 行同步（研发起点=迭代二） |
| 4 | 「下一步第一项」两处顺序矛盾 | 唯一化：**门禁凭据复核=评审/开工前置（第 1），端到端联调=M1 剩余研发第一项技术工作（第 2）**。§0、§6.7 ④、§10.1 M1 行、§10.2 全部按此改写；§10.2 开头声明「全文仅此一处顺序定义」 |
| 5 | §2 分级无落盘依据且 TUI 数字失真 | 实测复核：`find /tmp/zcode-api-research/apps/zcode-cli/packages/tui -type f \| wc -l` = 95（.ts 61 个），「约 86 个」与任一口径不符，§2.3 已更正并标注；§2 开头给出采信口径「转引+抽查」，Computer Use 行同步标注 zcode-cua/README.md 在 v3.14.3 不存在 |
| 6 | 行号引用 1~2 行漂移 | 三处实测更正：①ZCodeMobileE2ETests.swift:11→**:9**（Read 核对第 9 行即门禁自述、第 11 行为空行）；②design-spec.md:314→**:315**（grep -n 核对）；③design-spec.md:88/86→**:89/87**（grep -n 核对，5.08:1 在 :89、5.78:1 在 :87） |
| 7 | 决策信息缺「谁来做」与合规/隐私 | §9 新增 **⑧ 团队/人力在位性**（材料无任何团队组织信息，如实标注空缺）与 **⑨ 数据合规与 App Store 隐私专项**（OAuth 令牌/局域网远控/相机权限三类高敏面，需独立评估）；§10.1 M3 行合规项改为指向 §9-⑨，§10.2 第 5 项标注前置依赖 |
| 8 | §6.3 端点计数口径混乱 | 改为「**HTTP/WS 端点 8 个**（逐一编号）+ **RPC 频道面**（在 /ws 之内，不计入端点数）」，webhook 与静态兜底各自独立成项 |
| 9 | 外部链接无法核验 | 维持未核验声明；§8 风险 10 补记独立审阅复核亦不可达（WebFetch Connect Timeout，沙箱无外网） |

### 附 E：v1.3 修订对照（M1.x 迭代内容 → 章节）

| 迭代内容 | 处置章节 |
|---|---|
| 品牌迁移：定名依据、BiuZ 资产、用户可见层替换清单、图标落位、保留项三类（内部符号层/上游 ZCode 协议兼容/功能性 CLI） | §7.1；设计基准注记 §3.2；工程配置 §4.2；凭据 service 常量 §4.4 |
| bundle id 迁移影响面（Keychain 失效/测试不受影响/App Store Connect 新建） | §7.1、§9 风险 16（新增） |
| 只读边界协议证据分类（readonly/session/execution 三类含证据）与关键否定性结论（scope 不收窄、产品自律边界） | §7.2；分类口径固化 ReadOnlyGate.swift:7-16（§4.4 新增行） |
| iOS 现网执行面调用点 6 处与纵深防御三层（UI/Store/连接层） | §7.2、§7.4 |
| 边界复核纠错 3 条（setAutoDrain/respondWorkspaceHookReview 上调 execution；forkAssistant 拆分 session）与复核确认、兜底支路缺陷移除 | §7.3 |
| 只读改造实现清单与迭代会话自检（xcodegen/build/产物资产/残留扫描） | §7.4 |
| 门禁结果（构建=true、e2e=true、build-for-testing 两轮零警告） | §7.5、§8.4 |
| 全面验收清单（21 项 pass = 18 编译级 + 3 已运行检查，分级如实标注） | §7.6 |
| 布局走查（范围、三类遮挡断言、5 项遮挡问题处置、本轮回归断言） | §7.7 |
| 已知限制（门禁采信边界、订阅参数缺口、归档连接态行为、订阅进程副作用、客户端自律边界、商标检索未执行） | §7.8 |
| 技术方案与已实现范围更新（源码 42→43、测试 3→4 文件 27 用例、Assets.xcassets、权限文案三处同源） | §4.2/§4.4/§5、§8.6 |
| test06 列表绑定修复与强断言升级（v1.2 遗留整改闭环） | §6.7 v1.3 注、§7.4 缺陷修复行、§11.1 M1 行 |
| 风险更新（Keychain 失效、连接态能力收窄的产品影响；门禁三轮口径） | §9（风险 1/8/14 更新，16/17 新增） |
| 后续路线保持既有顺序；上架合规因品牌迁移改善；商标检索列入上架计划 | §11（M1/M3 行、11.2 第 2/5 项） |
| 对外品牌表述统一 BiuZ（桌面端产品指称与协议兼容表述按 works-with 口径保留） | 全文（§1.3、§7.1、附 A 等） |
| 编号变更（原 §7–§10 顺延为 §8–§11） | 版本头「编号变更」注、§8–§11 全部交叉引用、附 B 注 |

### 附 F：v1.3 二次修订对照（独立审阅意见 10 条 → 处置，含本次复核实测）

| # | 审阅意见 | 处置（本会话实测口径） |
|---|---|---|
| 1 | 「订阅参数只传 topic」与代码不符，且被 §7.2/§9/§11.2 沿用 | **意见属实，已更正**。实测 RemoteConversationStore.swift：subscribeSessionsIndexV4 传 topic+workspacePath（:111-117，注释明写「服务端签名要求 topic + workspacePath（zcodeAgentPluginParams.ts:8-11）」）、subscribeConversationV4 传 topic+sessionId（:191-196，注释引 zcodeAgent.ts:144-146）、unsubscribe 带 workspacePath（:136-140）；`grep -rn "subscribeSessionsIndexV4\|subscribeConversationV4" ios/ZCodeMobileApp/Sources` 证实全源码仅此 4 处订阅调用、无「只传 topic」调用点；首版所引 :105-110/:181-187 实为 MARK 注释与 messages() 分页逻辑、「空态兜底」注释实际在 :127-128。§7.2 gaps⑦、§7.4（补「订阅参数核对」行）、§7.8-2（重写）、§9 风险 8、§11.2-2③ 全部更正；工作区无版本历史，「M1.x 内已修复」与「调研稿误记」无法判别，如实标注为二者之一、以现网实测为准。**v1.4 二次修订后记**：本条所引行号为 v1.3 时点值（:111-117/:191-196/:136-140），M1.y 加厚实现后现网为 :132-137/:276-280/:208，参数实质内容经第五轮逐行核对仍属实（勘误明细见 §7.2） |
| 2 | 两份设计 HTML 行数过时、连带行号漂移 | **属实**。`wc -l` 复测：design/zcode-mobile-design.html = 1991、design/login-design.html = 1949；`grep -o 'data-testid…'` 复测 123/102/95 吻合；05-ask-chip-1 实测在 :946-947。§3.2 两行更新（并注明 v1.1/改稿前值）、§3.3 缺口 1 更正 :946-947、§8.6 首轮清单标注「v1.2 时点值」+末条①补复测 |
| 3 | 多处「实测」行号与现文件不符 | **属实，逐条实测更正**：KeychainStore service 常量在 **:16**（:12-15 为注释块，:15 是「E2E 不受影响」行）——§4.4/§7.1 表/§9 风险 16 三处更正；zcodeJwtToken 声明在 ZaiOAuthProvider.swift:**:22**、解析在 **:215**（:90 实为 bigModelAppID "zcode"、:91 实为 redirectURI 缺省值）——§7.1 保留项 2 更正；05-questioncard 在 MessageViews.swift:**:294**（:276 为字体修饰符行）——§3.3 缺口 2 更正。附 D 第 6 条确立的行号核对口径由本次执行并留痕 |
| 4 | a11y=139 为 v1.2 口径 | **属实**。`grep -rc 'accessibilityIdentifier(' ios/ZCodeMobileApp/Sources` 逐文件汇总 = **149**（实测）；§4.3 更新为 149（M1 迭代一后 139、M0 时 69），列明 M1.x 新增 10 处品牌/只读锚点；§8.6 ⑤ 补记复测 |
| 5 | 「绑定假设 9.7.7」指代不清 | **属实**。实测 design-spec.md:495 与 project.yml:48 注释均以「绑定假设 9.7.7」指称 redirect_uri——9.7.7 是 spec 条款号，与 §6.8 ①–⑧ 是两套编号。§7.1 保留项 2 改为「design-spec 9.7.7 条款，对应 §6.8 假设①」；§6.8 ① 加注声明两套体系 |
| 6 | 两套测试各有 test06，§7.6 未标归属 | §7.6 增加「用例归属说明」段（登录套件 test06 :371 与常规套件 test06 :233 重名、test12 仅登录套件、test07 两套各一）；第 1/3/10/14 项 test06 标「登录套件」、第 5 项标「常规套件」；替身计数器引用统一为 **:77-104**（getter :77-85 + 私有存储 :100-104，实测），§7.4 同步 |
| 7 | 「21 项全部 pass」首屏读法偏强 | §7.6 标题改为「**21 项全部 pass——其中 18 项 pass(编译级)、3 项 pass(已运行检查)；分级如实标注**」；附 E 对照行同步；分级口径引言与 §7.5/§8.1 声明不变 |
| 8 | §11.1「消除风险 3/4」中风险 3 归属牵强 | **属实**。§11.1 M1 行 ③ 括注改为「消除风险 4」（Tab/id 三方漂移）；孤儿冒烟用例收编（风险 3）新增为 M1 剩余研发 ④ 落点；§0 补记 |
| 9 | 「docs/ 无 cn.zcode 残留」字面不成立 | **属实**。实测 `grep -rn 'cn\.zcode' docs/` = 16 行（立项报告.md 内 8 行），均为迁移历史描述本身（§4.2/§4.4/§7.1/§9 风险 16 的旧值注记）。§8.6⑥ 改述为「docs/ **无作为现值的 cn.zcode**」并列明命中构成 |
| 10 | 风险 10 外链可达性已部分变化 | 本会话 `curl -sI -m 5` 实测：pixso.cn/app/design/AbZPdSg5AO6CqRDpr5RKwg → **HTTP 200**（域名与页面可达，画布内容与访问权限仍未验证）、github.com/zai-org/ZCode → **000**（沙箱仍不可达）、docs.qoder.com → **HTTP 307**（有响应）。§9 风险 10 补记 v1.3 复测时效，维持「链接内容未经人工核验」的边界 |

### 附 G：v1.4 修订对照（M1.y 迭代内容 → 章节）

| 迭代内容 | 处置章节 |
|---|---|
| 参考客户端盘点：RemoteServiceAccess 40 服务/392 方法条目（readonly 199/session 148/execution 45）、传输层与移动端选型结论、盘点纠错 8 条（含 setAutoDrain/respondWorkspaceHookReview 交叉印证、M1 gap 8 修正） | §8.1；gap 8 修正回写 §6.3 表注/§6.8 ⑥/§9.6 末条 |
| 逐接口对照：392 项五类处置（exists 11/partial 4/integrate 32/skip-boundary 122/defer 223）与关键取舍五条 | §8.2 |
| 实现清单：gate 扩展（ReadOnlyGate 116→246 行、201 命令+2 双态）、文件/会话/任务/Diff/自愈/配置模型/只读信息七域、模型类型与协议扩展、工程（ZCodeMobileTests target） | §8.3、§4.2/§4.4、§5 |
| 替身验证闭环：替身 1184→1747 行、新回执/计数器/触发器、test13/14/15、ReadOnlyGateTests 10 用例、用例 27→40 | §8.4、§4.3（a11y 149→156）、§9.6 |
| 门禁结果（构建=true、e2e=true 阶段口径；**单元级 10/10 已由报告撰写会话实跑复验**；完整套件 not_run 按约定；门禁脚本仍缺） | §8.5、§9.1/§9.4 |
| 与 v1.3 只读边界章节自洽：分类口径同源、skip-boundary 与拦截表关系（约 30 项「无入口」升级「出口必拦」、未入黑名单项口径声明）、40 call+7 listen 全落 readonly/session、两轮调研交叉印证、v1.2/v1.3 记录三处修正 | §8.6、§7.2 v1.4 注/§7.8-3 更新 |
| 已知限制与待真实后端确认项（盘点 gaps 7 条、schema 宽容解析、6 项联调确认清单） | §8.7、§12.2 第 2 项 |
| 技术方案与已实现范围更新（project.yml 84→98 行、测试 4→5 文件/27→40 用例、M1.y 技术方案行、规模注记） | §4.2/§4.3/§4.4、§5 |
| M1 欠账核销五项（置顶/已读/归档双写、刷新额度、水位 resync、向上分页、搜索）；维持项（逐文件批准/retry/KeepAlive/自动重连缺） | §6.6 v1.4 注、§10 风险 8 |
| 风险更新（风险 1 四轮口径+单元级例外、风险 2 联调范围扩大、风险 8 核销重写、风险 17 已放开部分注记；新增风险 18 schema 宽容解析、风险 19 分类保守处置） | §10 |
| 决策信息更新（④接口决策成本收敛+/ws/remote 裁剪先例、⑥新增盘点克隆件） | §11 |
| 里程碑 M1 行补 M1.y 前置事实；后续技术路线补联调确认清单与四轮复核口径 | §12.1/§12.2 |
| 全文对外品牌表述保持 BiuZ（上游开源项目/协议面指称按 works-with 口径保留；新增章节未引入品牌变更） | 全文 |
| 编号变更（原 §8–§11 顺延为 §9–§12） | 版本头「编号变更」注、§9–§12 全部交叉引用、附 B 注（保护范围扩至附 F） |

**v1.4 二次修订对照（独立审阅意见 11 条 → 处置；本会话实测口径）**：

| # | 审阅意见 | 处置 |
|---|---|---|
| 1 | §8.7 与 §9 之间嵌入 3 行 shell 残片（`__zcode_status=$?` 等），破坏行文、与逐行核验口径相悖 | **属实，已删除**（Read 全文逐字定位 :757-759 原行，恢复 §8.7 末段→「## 9.」正常衔接）；两副本同步 |
| 2 | §8.2④ 表题「skip-boundary 122」与表内合计 203 对不上 | **属实**。表题改为「处置口径与拦截黑名单映射」，表题下加声明：表罗列黑名单命令名集合、非 122 条目枚举；信封词表 17 归属 partial 接入的 sendConversationCommandV4；末行无计数项如实标注 |
| 3 | 「新增 201」与既有/扩展拆分矛盾；约 30/177/201 三数字无口径区分 | **属实，已更正**。§8.6-2 重写：总量 201 命令名+2 双态 = v1.3 既有（24+2）+ 本轮新增（**177**=task+10/git+6/agent+34/频道+127）；「约 30」为计划建议条目数；三口径在 §8.6-2 与 §8.2 关键取舍②集中区辨；§8.3/版本头同步 |
| 4 | gitService 行标 S 与 §7.2 把 git 写列为 execution 冲突，自称口径同源 | **属实**。§8.1 加「分类口径声明」：表内为参考盘点语义口径（execution 仅指驱动 agent/harness），产品拦截口径更严（工作区/仓库/远端写全拦）；gitService 行 7 个 S 项加 \* 注指向拦截口径；判定以 §7.2/§8.3 为准 |
| 5 | 证据链自相矛盾（既称按材料复算、又称计划不可读取）；处置表不在工作区不可复核 | **属实，已更正**。§8 引言③重写：材料随任务材料提供至撰写会话、实现会话不可读取（主体不同不矛盾）；复算范围如实限定——统计自洽 + exists/partial/integrate 逐项 + 40 服务暴露面与克隆件实跑比对一致（39 readonly + defineProperty :76/:162），**skip-boundary 122/defer 223 未逐条独立复核**；§8.2/§9.6⑧/附 A 同步（附 A 标注未落盘不可独立复核） |
| 6 | §9.1 末句「下述 8.2/8.3/8.4」编号漏改，且未补单元测试例外 | **属实，已更正**。改为 §9.2/§9.3/§9.4 并留勘误注；例外句补入（v1.4 撰写会话实跑单元测试），证据等级与 §0/§8.5/§10 风险 1 对齐 |
| 7 | §9.6「五轮」与条目标签第一/二/三/五轮缺第四轮、与会话轮次对不上 | **属实，已更正**。标题下加对应注：五轮=五个撰写/修订会话（v1.0–v1.4），「第 N 轮」标签是克隆件抽查轮次（第一、二轮均 v1.2 会话、第三轮 v1.3 会话），v1.0/v1.1 未设独立标签；各条目标签补会话归属 |
| 8 | v1.3「实测」行号在 M1.y 加厚后失效仍以实测名义保留 | **属实，已逐处更正**（本会话逐行核对现网行号）：9 组 RemoteConversationStore/RemoteTaskStore/ReadOnlyGate/ZCodeServerConnection 行号以「v1.3 时 :旧值、现网 :新值」格式更正（§7.1/§7.2/§7.4/§7.8-2/§7.8-5），§7.2 设统一勘误注（含 UI 锚点 4 处），附 F#1 补后记；参数实质内容核对仍属实 |
| 9 | mcpSync 合并族写 3 方法（实为 4）、coding-plan 四族之和 27（接口实有 29） | **属实，已更正**（对照克隆件实测）：mcpSyncService 行改 4 方法并补 checkRemoteUserMcpWriteAccess；codingPlanSubscriptionService 企业族改 8 方法并补 getEnterprisePendingOrders/checkEnterpriseOrderStatus；§8.1 加「条目口径与成员遗漏总注」（392 为合并条目数≠成员总数；3 个遗漏成员均只读、归 defer 语义，不影响五类统计与拦截面） |
| 10 | gaps ⑦「40 服务（39 readonly+1 defineProperty）」是事实不是缺口 | **属实，已更正**。gaps 改 6 条，原⑦移除并注明其为 §8.1 已陈述的盘点结论 |
| 11 | 「已运行检查」两套口径（§7.6 迭代会话 3 项 vs 风险 1 撰写会话单元测试）易判读为矛盾 | **属实，已更正**。§10 风险 1 区分两类执行主体并声明均无 xcresult 凭据留存、不构成复核豁免；§9.1 例外句同步收窄为「唯一由报告撰写会话实跑的测试级证据」 |

### 附 H：v1.5 修订对照（M1.z 迭代内容 → 章节；使用 v1.5 编号）

| 迭代内容 | 处置章节 |
|---|---|
| 协议逆向结论：wsEndpoint（wss://zcode.z.ai/ws?mid=…）与配对链接解析（sid/hash 不进 URL）、auth 四帧握手（HMAC-SHA256 proof）、data 帧应用层编排、WS close code 六值映射、错误帧五 code 分型、心跳/重连参数 | §9.1 |
| framing 八条关键差异（无 13 字节 SocketProtocol 头/JSON 文本帧/rpc-frame 分片可靠层/V4Wire 原样复用/鉴权算法/心跳重连/链接识别与探测跳过/execution 拦截照旧在出口）与常量表（1MB/16MB/64 片/30s/10s/8MB/45s/crc32 同参） | §9.1、§4.4（M1.z 技术方案行） |
| 会话读取两层路径（列表免桥 81KB 单帧 / 历史详情走 bridge+channel RPC）与 channel 名表 43 频道；逆向 gaps 7 条 | §9.1、§9.6 |
| 实现清单：Relay 四件套（RelayLink 105/RelayFrameCodec 244/RelayChannelClient 444/RelayTransport 802 行）+ 6 处既有改造（ConnectURLParser/KeychainStore/ZCodeServerConnection/AppSession/ConnectFlowView/RemoteConversationStore）；实现期协议修正 5 条（promise 面参数数组化/listen 对象原样/handshakeRequired 闸/ack 回执形态/毫秒时间戳）；既有面零改动声明 | §9.2、§5（v1.5 注与规模行） |
| 真实链路端到端验收（connected=true/sessionsLoaded=true）：探活 HTTP 200/18,180B、构建安装启动、连接时间线（auth matched→bootstrap 240 tasks→bridge-ready kind=local→桥内 v4 握手→sessions-index 订阅）、44 分钟+ 稳定、非 mock 佐证（mock 标题零交集 + t1 对照截图）、0 轮代码修复；证据文件 /tmp 留档与撰写会话抽查复核（日志 65,805 字节/430 条、pair_status=matched 405 次） | §9.3、§10.4（M1.z 段） |
| 只读边界在中继路径的保持：RPCChannelTransport 单一门面（:236-237）、ReadOnlyGate 出口拦截不区分传输层（:786-797）、验收日志上行行为核对（零 execution）、逆向面如实（服务端边界未知）、零新增业务命令 | §9.4、§11 风险 20 关联 |
| 门禁：构建=true（双执行主体）、e2e=false 如实标注（PTY 环境阻塞）、单测 26 用例实现会话实跑（阶段口径）+ swiftc 等价验证 32 项 PASS + 撰写会话实跑未能运行（独立复现环境阻塞）、真实链路验收 pass 采信、门禁脚本仍缺 | §9.5（含括注）、§10.1/§10.4/§10.6⑥、§11 风险 1 |
| 测试面 40→62 用例：RelayLinkE2ETests 4 用例（识别/负例/冷启动钩子/无效链接）+ RelayLinkTests 18 用例（实测，阶段自报 16）；既有套件零改动；真实中继链路不做 XCUITest 的约定 | §9.5、§5 规模行/测试文件行 |
| 已知限制：recoveryId 保守重连策略、close code 终态取舍、重连上限 6 次、workspace-config 桥内不支持、e2e 运行时未校验、凭据面 | §9.6、§11 风险 21 |
| 风险更新：风险 1 扩五轮口径+第三类证据（验收日志/截图）、风险 2 更新（中继数据面已验收/直连与 OAuth 未验）、新增风险 20（闭源逆向随版本变化）与风险 21（sid/hash 凭据时效与泄露面） | §11 |
| 决策信息④更新：云端中继已可用、后端依赖由自建 relay 降为托管端点依赖；里程碑 M1 行补 M1.z 前置事实；后续技术路线补中继面联调五项（⑥~⑨）与门禁补跑 | §12-④、§13.1/§13.2 |
| 技术方案与已实现范围更新：§4.4 加 M1.z 行、§5 v1.5 注（App 源码 43→47、改动文件行数、测试 5→7 文件 62 用例、行数与阶段自报差异如实标注） | §4.4、§5 |
| 全文对外品牌表述保持 BiuZ（中继服务域名 zcode.z.ai/网页壳指称按 works-with/功能性引用口径；新增章节未引入品牌变更） | 全文（§9 各节） |
| 编号变更（原 §9–§12 顺延为 §10–§13） | 版本头「编号变更」注、§10–§13 全部交叉引用、附 B 注（保护范围扩至附 G，附 H 使用 v1.5 编号） |
| 撰写会话第六轮实测核验（文件数/行号/锚点/日志抽查/证据在位/单测实跑尝试） | §10.6 末条 |

**v1.5 二次修订对照（独立审阅意见 12 条 → 处置；本会话实测口径，11 条属实更正 + 1 条核对范围说明）**：

| # | 审阅意见 | 处置（本会话实测口径） |
|---|---|---|
| 1 | 风险 21「文档与交接材料不留存工作区」与 relay-handoff.md 明文凭据、附 A 将其标为工作区交付物自相矛盾；/tmp/relay_swift_verify/main.swift 亦内嵌凭据 | **属实，且扫描范围比审阅所指更大**。`grep -rln 'sid=d_5BHs…\|juuBpa…'` 实测：工作区 docs/relay-handoff.md + ios/ 2 处（RelayLink.swift:90 注释、RelayLinkTests.swift:11-22/:62 单测向量）+ /tmp 8 文件（7 探针脚本全数内嵌 + main.swift:10-12）。**已整改**：relay-handoff.md §1 链接掩码（sid/hash 中段掩码、保留 mid/name/app_version，掩码注记留痕——该文件为纯文档，掩码不影响构建与已完成验收）；§11 风险 21 按留存实况重写（原「不留存工作区」声明删除）；源码向量替换（需 build 回归）列为 M1 剩余研发第一项安全整改（本轮未擅动源码）；/tmp 列评审后清理项；§9.2 proof 向量处、附 A relay-handoff/探针行同步注记 |
| 2 | §13.2-1「M1.z 单测已复核」与 §9.5/§10.1/§11 风险 1「未能运行」矛盾 | **属实（撰写笔误），已更正**。§13.2-1 改为「M1.z 单测实跑未能运行、e2e 门禁因同一 PTY 环境阻塞待补跑——两者状态一致，均以待补跑为唯一口径」 |
| 3 | §7.2/§7.4/§8.3 以「现网」名义保留 M1.y 时点行号（call :638-643/:254），与 §9.4 实测 :786-797/:257 矛盾；v1.4 二次修订 #8 的勘误体例未沿用 | **属实，已沿用体例更正**。实跑复核：`sed -n '254p;257p;636,644p;786p;794,797p'` → blockedExecutionCalls 现网 :257（:254 为 clientId）、call 拦截现网 :786-797、:638-643 现为帧流订阅 MARK 区。§7.2 勘误注扩容「勘误·续」段（ZCodeServerConnection/RemoteConversationStore/AppSession 20+ 组「M1.y 后→M1.z 后现网」对照，本轮逐值复测），§7.2 纵深防御段/§7.4 连接层行/§8.3 gate 行逐处更正 |
| 4 | 多处「实测」行号锚点现网不成立（AppSession :103、StoreProtocols :35-36、ChatView :115、LoginFlowView :309/:398/:402、ConnectFlowView :339、替身 :252、04-row :438-440、test12/13/14/15、§8.3 Store 行号整体 +5 等） | **属实，逐条实跑复测后更正**（与审阅给出的现网值全部吻合，另补测审阅未列的 ensureSessionsIndexSubscribed/ensureConversationSubscribed 订阅参数、setArchived :701/markRead :719、AppSession :217/:225/:237/:275/:393 等）：§7.2 勘误·续段集中列明；§7.1 表加时点注+关键值、§6.7 v1.3 注（04-row 现 :406/:458-463）、§8.3 会话面/自愈面/只读信息面、§8.4 test13/14/15（现 :885/:1002/:1170）逐处更正；RemoteTaskStore 289 行未漂移、四处只读提示 UI 锚点（:181/:118/:212/:292）未漂移均如实标注。LoginFlowView 现 839 行（v1.3 时点后膨胀）原因未溯源、按 §5 行数差异注记同口径标注。参数实质内容逐行核对仍属实 |
| 5 | lVn 错挂 src-D3H6NV7w.js@336279（实为 index-BO-TaBle.js@~6144121；336279 是 wN 内 passHash 字段；Mh 首现 @181160 非 181256；wN 实为 ~336142-336400） | **属实，已更正**（python 按偏移实读 bundle 复核：`lVn` 在 src bundle 零命中、`async function lVn` 在 index@6144121 区、Mh@181160 起、wN@336142 起）。§9.1 帧序③/端点段/常量段三处更正并标注阶段材料原记值；算法结论两侧一致不受影响；其余偏移（xh@179516/Ju@122124/Yv@243672/cA/TN/searchParams.set@6146834）经审阅抽查属实保留 |
| 6 | 常量 AN/jN/MN/NN=1e4/2e3/3e4/2e3 对不上五个语义（NN=2s≠1.5s、15s 无对应常量且 15e3 检索未见） | **属实，已更正**。§9.1 常量段拆明：AN=心跳 10s/jN=抖动 2s/MN=看门狗 30s/NN=2s（挂接点未逐一定位）；「stale 恢复 1.5s」「desktop 离线宽限 15s」降级为阶段材料转述（bundle 未定位），实现按材料值 15s（RelayTransport.deviceOfflineGraceSeconds=15）并如实标注常量出处存疑 |
| 7 | §9.1 分片自适应描述与实现相反（实现是片长初值=整条消息，片数>64 或信封>1MB 则减半；按报告字面减片长只会增片数） | **属实，已更正**。实读 RelayFrameCodec.swift:41-47 与文件内注释确认实现口径，§9.1 常量段按实现重写（两约束交集：16MB/64 片→250KB/片→信封约 333KB<1MB），并标注阶段材料原文方向有误 |
| 8 | 探针脚本实为 7 个（多 ws_bridge_agent_probe.py），§9 引言/附 A/§10.6⑥ 三处均写 6 个 | **属实，已更正**。`ls /tmp/relay-js/*.py` 实测 7 个；ws_bridge_agent_probe.py mtime 22:43（实现/验收窗口），产出会话未在阶段材料清单标注、归属未溯源（如实标注）；三处全部更正 |
| 9 | 附 A relay-handoff 行「§3.4 真机验证入口」指位断裂（本报告无 §3.4） | **属实，已更正**。真机验证命令（simctl launch -ZCodeRelayLink）实为 relay-handoff.md「实施建议」节第 4 条（该文件仅 ## 1/2/3 三节、无 §3.4 小节）；附 A 行改为如实描述并同步凭据掩码注记 |
| 10 | §9.2 RelayLink 行「见 §9.6-③」指针错位（close code 取舍在 §9.6-2） | **属实，已更正**为「见 §9.6-2」 |
| 11 | §6.5 login-design.html「164,385 字节」为 M1 时点历史值未加注（现网 178,820 字节） | **属实，已更正**。`wc -c` 实测 178,820 字节；§6.5 加「M1 迭代一时点值」注并注明现网值与 M1.x 品牌改稿成因（与 §3.2 行数变化同因） |
| 12 | 核对范围说明（非缺陷）：其余路径/锚点/计数核查全部通过，未发现虚假引用；未执行项如实声明 | 无需改正，留痕备查。审阅通过项与本轮复核一致（工作区 25 个文件路径、a11y 156、testid 123/102/95、md5 同源、/tmp 证据自报数字、克隆件 6 处行号等）；未复跑构建/测试与外链核验的边界报告已自标，维持 |

### 附 I：v1.6 修订对照（M2 迭代内容 → 章节；使用 v1.6 编号）

| 迭代内容 | 处置章节 |
|---|---|
| 迭代背景与核心结论：v1.3–v1.5 只读边界被桌面功能对照材料认定为过度限制（六动作方法空实现/五处 UI 只读降级/信封与 zcode-task 黑名单整体禁用消息审批停止面），纠偏后边界语义=「放行=客户端发命令桌面代执行；拦截=手机直写面」 | §10.1、§10.4；版本头 |
| 桌面功能对照 23 项全表（must 3/nice 14/skip 6；桌面端 file:line 证据逐项；阶段小结「8 项 skip」与数组实测 6 项的口径差异如实标注）与逐项处置（重命名/归档查看/通知=must 本轮落地；搜索/预览/产物=nice 部分落地；Git 写/终端/CUA/设置同步/命令中心/应用更新=skip 保持拦截） | §10.2 |
| 竞品交互参考 19 条（Qoder/Trae）四级归档：已落地（审批结构化卡+三档 scope/通知三级映射/会话为一级对象）、部分落地（断线横幅重连/长按菜单/看板四段/Diff 三分段/会话内搜索）、后续候选（Plan 时间线/摘要头卡/空态三件套/H5 兜底）、不适用 | §10.3；§13-⑤ |
| 只读边界口径修订（纠偏地基）：ReadOnlyGate 三分类→四分类（readOnly/session/command/directWrite）、v4 信封仅 applyFileRewind 拦截、file 频道「默认拒绝+读白名单」收口、git/terminal/配置与凭据写族黑名单保留、blockedExecutionCalls→blockedWriteCommandCount 语义更名 | §10.4、§4.4（M2 技术方案行）、§7.2 口径关联 |
| 实现清单与 gaps 12 项落地对照（P0×3 纠偏：发送/审批应答/停止队列；P1×5：口径修订地基、新建会话带指令、重命名+标记未读、已归档分组+取消归档（修正误发 archiveTask）、本地通知真实化；P2×4：附件产物结构化（部分）、任务服务端搜索（完整）/会话检索（未接）、会话内搜索（部分）、图片/PDF 预览）——撰写会话逐项实测核验（file:line） | §10.5 |
| 逐功能验收清单 24 项全表（功能/标准/结果/证据；分级=本会话实跑/验收会话/编译级/代码核对；24/24 pass→材料判定全 pass（首版曾声明「全流程通过」，二次修订已降级为逐级表述，见附 I 补表 #2）；降级项如实：审批 Sheet 演示态与文件预览扩展断言载体为孤儿文件、通知真机项未验） | §10.6 |
| 门禁：构建=true（阶段）、e2e=true（验收会话口径，截图 20/23/26/28/30/31/40 在位）、**单元级 40/40 本报告撰写会话实跑通过（xcresult 留痕）**、材料数字差异实测裁定（GateTests 13/15+1→实测 16）、测试面 62→78 用例、替身扩展（blockedWriteCommandCount 等 8 计数器+permission 挂起交互+dropAllWebSocketChannels） | §10.7、§11.1/§11.4/§11.6 末条 |
| 已知限制七条（完整 UI 套件未由撰写会话复跑/孤儿文件恶化/放行命令真实桌面 payload 未对照/P2 未完成子项/通知真机项/回归防护依赖/竞品文字材料） | §10.8 |
| 应用缺陷修复（随验收发现）：AppSession.Mode.disconnected 无产生路径（断线黄色横幅为死代码）→ZCodeServerConnection 新增 onConnectionDropped 回调+AppSession 订阅，test17 验证 | §10.6-5、§12 风险 8 |
| 风险更新：风险 1（六轮口径+v1.6 单测闭环+PTY 消除）、风险 2（放行命令联调范围扩大）、风险 3（孤儿 8→9 恶化重写）、风险 8（断线重连兑现/retry 维持）、风险 17（纠偏反转改记残余）、风险 18（M2 新增面宽容解析）；**新增风险 22**（放行命令在真实桌面的行为未验证） | §12 |
| 决策信息：④（审批口径议题转为 payload 契约联调）、⑤（19 条竞品参考部分改善、仍无截图） | §13 |
| 路线：M1 里程碑行补 M2 前置事实、M1 出口标准按纠偏后口径更新、路线里程碑 M2 行通知口径更新+命名说明、§14.2-1 补跑状态更新（单测闭环/e2e 待补）、§14.2-⑩⑪ 新增（payload 契约对照/通知真机/孤儿收编） | §14.1/§14.2 |
| 技术方案与已实现范围：§4.3 a11y 156→167、§4.4 M2 行、§5 v1.6 注（App 源码 47→48、测试 7→8 文件 62→78 用例、ReadOnlyGate 246→265 等加厚实测） | §4.3/§4.4/§5 |
| 报告撰写会话第七轮实测核验（文件数/行号/四分类/纠偏落点/锚点残留/单测实跑/替身计数器/孤儿实锤） | §11.6 末条 |
| 全文对外品牌表述保持 BiuZ（上游开源项目/协议面指称按 works-with 口径保留；本轮新增文案均为中性表述，无「由 ZCode 出品」类联动表述） | 全文 |
| 编号变更（原 §10–§13 顺延为 §11–§14，新增 §10） | 版本头「编号变更」注、§11–§14 全部交叉引用、附 B 注（保护范围扩至附 H，附 I 使用 v1.6 编号） |

**v1.6 二次修订对照（独立审阅意见 14 条 → 处置；本会话实测口径，13 条属实更正 + 1 条为与既有披露一致的核实确认）**：

| # | 审阅意见 | 处置（本会话实测口径） |
|---|---|---|
| 1 | /tmp 14 条路径全部 MISSING，正文「在位」结论失效 | **属实，已更正**。本会话复跑同一 for 循环 14×MISSING；§9.3 证据段/§9 引言/§9.5/§11.1/§11.6③/附 A 三行全部改为失效状态（数值降级为 v1.5 彼时抽查值）；M1.z 验收与克隆件抽查降级为「彼时记录、现不可复核」；§12 风险 1/2/6/20/21 更新、§14.2-⑫ 验收重建、风险 1 整改加「证据落盘工作区」 |
| 2 | M2 汇总门禁口径强于项级证据 | **属实，已更正**。§0/§10.1⑤/§10.6 引言/§10.7/§14.1 汇总句全部降级为「材料判定全 pass+载体与运行限制如实列明」，删除「全流程通过」单一表述；§10.7 e2e 行明示截图无落盘路径 |
| 3 | v1.6 加厚后行号再漂移、未沿用勘误体例 | **属实，已沿用体例更正**（勘误·续二）：call :795/append :803/抛错 :806（`grep -n` 实测）、AppSession 四值、StoreProtocols 三值、05-questioncard :277、mock 标题 :288/:294、04-row :413/:470、Tab :613-616/:842-845；§3.1/§3.3/§5/§6.7/§7.2/§7.4/§8.3/§9.4/§10.4（含同段自相矛盾修正）/§11.6 逐处更正 |
| 4 | relay-handoff.md 29 行 vs 报告 27 行 | **属实，已更正**。`wc -l` = 29（v1.5 掩码整改新增注记未复测）；§9 引言/附 A/§11.6 三处更正 |
| 5 | 验收表覆盖缺口（P2 两项无断言无行、P1-4 有断言无行、P 计数口径不符） | **属实，已更正**。§10.6 表后补录 #25/#26/#27 三行（含 grep 实测：P2 两项 0 断言、P1-4 断言 test06:438-441 在位）；§10.5 表头计数口径声明更正、地基行阶段优先级标注更正（P1） |
| 6 | 风险 20 探针「6 个」漏改 | **属实，已更正**（v1.5 二次修订更正为 7 个时此处漏网），并按 /tmp 已消失现实重写缓解① |
| 7 | §1 未随 v1.6 纠偏更新、与 §10 冲突 | **属实，已更正**。§1.2/§1.3 补 v1.6 现行口径注、保留历史口径并标明 |
| 8 | 「M1.z 24 用例」无出处 | **属实（笔误），已更正**为实跑口径（v1.6 撰写会话 40=16+18+6，其中 RelayLinkTests 18 为 M1.z 面） |
| 9 | 收口断言 20 处应为 19（含 1 注释行） | **属实，已更正**。`:1254p` 实测为注释行、`, 0` 形态 19 处；§10.6-23/§11.6⑦ 更正 |
| 10 | §11.1「见 10.6」编号残留 | **属实，已更正**为「见 §11.6」并加残留说明 |
| 11 | §10.6 第 3 项「云中继：」前缀分组错位 | **属实，已更正**为「连接（局域网直连）：配对五步连接」并括注阶段归域与更正理由 |
| 12 | §9.5 xcresult 03-21-42 留痕已不存在 | **属实，已更新**。`ls` DerivedData 仅存 11-05-25/11-43-31；括注加失效说明，11-43-31 与 v1.6 单测时间戳吻合的佐证成立 |
| 13 | 外链状态过时（github 现可达） | **属实，已更新**。`curl -sI -m 6` pixso 200/github 200 复测留痕；风险 10 更新、内容未人工核验边界维持 |
| 14 | 凭据留存确认（非新发现） | 与 §12 风险 21 既有披露一致，补记 /tmp 8 文件已随清理消失（清理项事实达成、复验能力一并丢失）；工作区三处留存面复测在位、安全整改维持 |
| — | 审阅第 15 条核对通过项（40 路径/48 文件/167 a11y/78 用例/各行数/md5/锚点/0 残留/两副本一致等） | 与本报告第七轮实测一致，留痕备查，无需改正 |

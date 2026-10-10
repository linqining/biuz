# Android 工程（BiuZ，`cn.biuz.mobile` 的移动遥控台）

**开工前必读 [docs/Android开发交接.md](../docs/Android开发交接.md)**——它包含：

- 必读文档地图与阅读顺序（协议接口文档 → 架构根因分析 → 本 README 指向的交接文档）
- iOS → Kotlin 可移植资产清单（约 1/3 代码为纯协议逻辑可直译）
- 会话考古问题全集（甲乙丙丁戊五类根因，42 个历史主会话提炼）
- 产品裁决速查、平台差异映射、模块分层与 M1–M7 移植里程碑、开工核对清单

三条红线（详见交接文档 §0）：一切 RPC 走唯一出口 + ReadOnlyGate；一切写命令走单点信封 Factory + schema 校验；ACK 非 accepted 必上屏原文。

## 环境权威信息（用户 2026-10-09 提供——子代理/新会话勿再探测，直接采信）

- **JDK 17**：`JAVA_HOME=$HOME/opt/jdk17/Contents/Home`。机器上另有 `~/opt/jdk-17.0.20.1+1/Contents/Home`（被 Kotlin daemon 使用）与 brew `openjdk@17`——多 JDK 并存是常态，一律以 `$HOME/opt/jdk17` 为准。
- **Android SDK**：`ANDROID_HOME=/usr/local/share/android-commandlinetools`（platform android-36、build-tools 36.0.0；`local.properties` 已指向它，勿改）。
- **Gradle 8.14.3**：约定经 `./gradlew`（wrapper）调用；构建脚本已去掉 `--no-daemon`（共享热 daemon）。工作流门禁用固定路径 `/Users/mac/opt/gradle/gradle-8.14.3/bin/gradle`——与 wrapper 同版本、共享同一热 daemon，两者等价。
- 构建必须串行（并行 gradle 互相等锁，遇锁等待，勿杀 daemon）；构建判定显式退出码，禁 `| tail` 管道判定。

---

## 工程状态（2026-10-10：M1–M7 全部实装——终验门禁全绿 + 真机冒烟通过；真机/真桌面联调挂账）

**M2–M7 工作流轮（2026-10-10）收尾**：connection/store/relay/app 四模块随 `53785bd`（android store）→ `4ba23b1`（conversation）→ `3f3fb70`（store）→ `4cae0ba`（cas）→ `962d18e`（relay+attach）→ `4a4392d`（workflow）六批入库。**终验门禁全绿**（逐模块数字见「门禁现状」）、**独立终审 3 条（高 1 条已修）**、**真机冒烟通过**（截图 `docs/acceptance-android/`，止步扫码页——边界见「已知未决」）。终验后工作区另有一批**未提交**的连接层装配改动（`connection/AppSession.kt` + `ZCodeServerConnection.kt`：连接态 StoreSurface 换装 RemoteConversationStore/RemoteFileStore、连接本体 `implements ConversationFrameSurface`、workspace-list-updated 回写 `allWorkspaces`），本页门禁数字已覆盖该改动，待入库。

### 里程碑状态（对照交接文档 §8.3 验收口径，如实）

| 里程碑 | 状态 | 证据 / 挂账 |
|---|---|---|
| M1 protocol + 单测平移 | **达成，挂账已回补** | 2 例 @Disabled（relayLinkArgument / ServerConfig 旧 JSON 解码）随 :connection 落位迁回（`protocol/RelayLinkTests.kt` 复活 + `connection/RelayLinkConnectionLayerTests.kt` 迁入，占位删除）；parseInteraction 缺例落 store 层 `RemoteConversationStoreTest.pendingInteractionList_normalizesPlanApprovalSchema`（plan_approval schema 归位全形状断言）。protocol 记账 78 例 0 skip（M1 时 77 例 / 2 skip）；store/VM 层约 71 例挂账以 Android 等价套件落地（非 iOS 逐例平移记账） |
| M2 直连 + 握手 + sessions 列表 + bootstrap.tasks | **自动化面达成；「真机桌面直连目检」挂账** | `DirectConnectE2ETest`（真实 OkHttp WS ↔ Ktor 替身 127.0.0.1，建链→握手→订阅→call→回执→帧序五步全链路）+ `AppSessionAssemblyTest` + `RemoteSessionsIndexStore`（sessions-index 订阅 + bootstrap.tasks 多工作区）。真机侧冒烟只到扫码页（见「已知未决」），连接完成态目检待真机+桌面在线轮 |
| M3 会话订阅（寻址/帧面/屏障）+ 行渲染 + 发送 | **自动化面达成（替身级）** | `ConversationDeltaSmokeTest`（快照/增量/订阅回执）+ store 层 RemoteConversationStore/Send/Command 三套（乐观回显 sourceCommandId 消费、ACK 判定）；「跨 workspace 会话实收」为替身级断言，真桌面实收挂账 |
| M4 CAS 族 + 审批三族 + 队列 + 模型/模式切换 | **自动化面达成** | stale 自愈：`casRetry_staleOnceResolvedOnSecondSend_noResync`（RemoteConversationCommandTest:114）+ `StubCasGenerationTest` 4 例（替身 CAS 生成器 stale 注水）；CAS 未就绪本地拒发：`casRevisionUnavailable_rejectedLocallyWithoutWire`（RemoteConversationSendTest:172）；provider 复合键 `ProviderDisplayTest`；双源运行态 `DualSourceRunningSynthesisTest` |
| M5 附件四方法 + 视频 + 缓存 | **自动化面达成** | `AttachmentUploadServiceTest`（超 20MB 本地拒 + 分块各自带 padding A11 断言）+ `StubAttachmentStrictTest`（替身 strict 对拍）+ `AttachmentMediaKeysTest` |
| M6 中继链路（Relay 四件 + 开桥重握手单点） | **自动化面达成；验收口径「真实中继链接连通」未达，如实挂账** | `RelayChannelE2ETest`（3 例，本地伪中继端到端）+ `RelayConnectE2ETest`（瞬断 1.2s 退避重试测真实 onBridgeOpened 单点 + 直连/中继双链路装配）。该文件头原文：「真实中继验证需用户桌面在线且串行，本轮替身级如实挂账」；真实验证另受中继**单连接槽**约束（AGENTS §6 -1001 挂账），须串行 |
| M7 工作流面板/完成卡/产物详情 + 组织态 + 设置/权益/i18n | **自动化面达成；「对齐 iOS FeatureCompletion 场景清单」未做，挂账** | `WorkflowPanelsPureTest` / `WorkflowRunsArbitrationTest` / `WorkflowActorBindingTest` / `BackgroundWorkPanelTests`；组织态 `MembershipJoinTest` + `ConversationGroupingTest`（三级合成）；设置/权益 `SettingsPatchShapeTest` + `UsageEntitlementParseTest` + `EntitlementUiModelTest` + `AppSettingsStoreTest`；i18n `values/` 与 `values-en/` 各 330 键对齐。app 无 androidTest/instrumented 套件，FeatureCompletion 级场景对齐未执行 |

### 模块结构（六个模块，见 `settings.gradle.kts`）

| 模块 | 状态 | 内容 |
|---|---|---|
| `protocol` | **已实装** | 纯协议层直译（零 Android 依赖，仅 kotlin-stdlib + kotlinx-coroutines）：Models / RPCSerialization（自研 RFC 8259 JsonCodec，模块禁第三方 JSON 依赖）/ V4Wire / ChannelClient / ProtocolSchema / CommandSchemas / ConversationCommandFactory / ReadOnlyGate / AckActivationBarrier / ConnectURLParser / Bootstrap 十一件；`relay/` 子包中继帧面两件（RelayLink / RelayFrameCodec）；单测 7 套 |
| `relay` | **已实装（M6）** | RelayTransport / RelayTextSocket / RelayChannelClient（rpc-frame 编解码 + 中继 WS 信道 + 开桥重握手单点）；单测 1 套 3 例 |
| `connection` | **已实装（M2）** | ZCodeServerConnection（`call` 唯一出口 + ReadOnlyGate 出口拦截 + 桥重开握手单点 + 帧面多路挂载 + `ConversationFrameSurface` 实现）、AppSession 装配（直连/中继双路分派、workspace-list-updated 回写）、OkHttpWebSocketTransport、ServerConfig / ServerRegistry；单测 4 套 11 例 |
| `store` | **已实装（M3–M5、M7）** | RemoteConversationStore / RemoteTaskStore / RemoteFileStore / RemoteSessionsIndexStore / RemoteSessionMerge / AttachmentUploadService / RemoteUsageStatsStore / TaskNotificationContent / StoreProtocols（三大协议面 + Empty* 空实现）；单测 17 套 145 例 |
| `teststub` | **已实装并扩展** | Ktor 本地替身服务器（`E2EStubServer.kt`）+ StubAck / StubV4Frames / StubIndexFixtures：握手/快照/增量/命令回执伪造 + strict 信封对拍（复用 `:protocol` 校验器）+ CAS 生成器 stale 注水 + 附件 strict + sessions-index 注水；单测 6 套 14 例 |
| `app` | **已实装（M2–M7 UI）** | 单 Activity Compose UI（`cn.biuz.android`）：Connect / Scan（相机扫码）/ Sessions（分组）/ Chat（行渲染+审批卡+composer+工作流轨道）/ Files / Settings / UsageStats / Language / About 九屏 + ChatViewModel + 附件视图；`values` 与 `values-en` 各 330 键；单测 10 套 103 例（1 例为默认禁用的手工活体替身宿主，见下） |

### 构建与测试

```bash
# 推荐入口：自带并行互斥锁（多个 Gradle 进程并发构建会互杀共享 daemon，禁绕过本脚本直调 gradle）
android/scripts/build-jvm.sh :protocol:test      # 协议层单测
android/scripts/build-jvm.sh :teststub:test      # 替身服务器冒烟
android/scripts/build-jvm.sh                     # 全量 build（无参数默认 build）

# 全量门禁（2026-10-10 终验口径：六模块单测 + APK）
android/scripts/build-jvm.sh :protocol:test :relay:test :connection:test :store:test \
  :teststub:test :app:testDebugUnitTest :app:assembleDebug

# 等价直调（确认无并行构建时）
/Users/mac/opt/gradle/gradle-8.14.3/bin/gradle -p android :protocol:test --console=plain

# :app 产出 APK（需 Android SDK，见 android/local.properties 的 sdk.dir）
/Users/mac/opt/gradle/gradle-8.14.3/bin/gradle -p android :app:assembleDebug --console=plain
# 产物：android/app/build/outputs/apk/debug/app-debug.apk

# 可选：宿主机活体替身（模拟器/真机手动联调用；默认禁用，否则阻塞等哨兵）
android/scripts/build-jvm.sh :app:testDebugUnitTest --tests cn.biuz.android.app.StubLiveServerTest -Dbiuz.stubLive=true
# 端口落 /tmp/m2ui-stub-port；模拟器内连 10.0.2.2:<port>；写 /tmp/m2ui-stub-stop 哨兵收尾
```

### 门禁现状（2026-10-10 终验 + 收尾轮强制重跑复核）

- 终验命令：`android/scripts/build-jvm.sh :protocol:test :relay:test :connection:test :store:test :teststub:test :app:testDebugUnitTest :app:assembleDebug` → exit=0、`BUILD SUCCESSFUL`；APK 产出 `android/app/build/outputs/apk/debug/app-debug.apk`。
- 收尾轮强制重执行复核：同任务面加 `--rerun-tasks` → exit=0、`BUILD SUCCESSFUL in 9m 45s`、`46 actionable tasks: 46 executed`（六个 `:test` 全部真实重跑，非 up-to-date 放行）。注：首次强制重跑被外部 `gradle --stop` 杀共享 daemon 中断一次（环境事件，非代码失败），重试干净通过。
- JUnit XML 记账（failures=0 / errors=0）：**protocol 78 / relay 3 / connection 11 / store 145 / teststub 14 / app(testDebugUnitTest) 103——合计记账 354 例，执行 353 例全绿**；1 例 skip = `StubLiveServerTest.hostStubUntilSentinel`（手工活体替身宿主，`-Dbiuz.stubLive=true` 才启用，默认跳过是设计行为非挂账）。
- M1 挂账回补完成（详见里程碑表 M1 行）；M1 独立复核 5 条 low 级「遗留观察」维持挂账（明细见交接文档 §7.1 的 2026-10-09 注记）。

### 已知未决（如实挂账，2026-10-10）

**联调面（需真机 / 真桌面在线）**

- **真实中继链接连通未验证**（M6 验收口径未达）：替身级全绿；真实验证需用户桌面在线且**串行**——中继单连接槽（AGENTS §6「-1001 单槽」挂账在案），不得与 web/其他客户端并行占用。
- **真机直连完成链路未目检**：冒烟截图止步扫码页（`docs/acceptance-android/01-launch.png` 真机 home + `01-launch-real.png` 扫码页取景框对准桌面配对二维码面板）；扫码→握手→会话列表→行增量→发送/审批全链待真机 + 桌面在线轮。
- 桌面在线轮取证（交接文档 §7.3 清单 Android 侧同源适用）与 §7.2 真机复测场景（plan 审批卡、协作模式 chip 回流、键盘在场胶囊等）Android 实现后同样要过。

**功能面（代码内如实标注，file:line 为证）**

- OAuth 账户层未开工：登录 UI / 凭据存储缺位，连接凭据只走 `ServerConfig.token` / `relay.passHash`（`connection/AppSession.kt:25-28`）。
- iOS P1 重连缓存迁移（exportCaches/adoptCaches）未移植：重连即重建行缓存（`connection/AppSession.kt:32-33`）。
- 部分 payload type 未移植 schema（sendGoalCommand / startSavedWorkflow / deleteSession / workspaceHook 三族 / resumeWorkflowRun 等）：payloadSchema 返回 null，仅信封层校验 + 构造告警，接入 UI 前须按 AGENTS §5-11 取证补条目（`protocol/CommandSchemas.kt:14-19`、`:247`）。
- 系统通知投递 / 点击路由未接：本批只落内容构建纯逻辑 G-016（`store/TaskNotificationContent.kt:10-11`）。
- 文件页 PDF 只读预览未移植（如实提示桌面端查看）、TinyMarkdown 富渲染未移植（markdown 正文原样呈现）（`app/ui/screen/FilesScreen.kt:1085-1086`）；全屏图片「保存相册」面挂账另轮（scoped storage 权限差异，`app/ui/chat/AttachmentViews.kt:518`）；多服务器去重挂账装配层（`app/ui/screen/ConnectFlow.kt:16`）。
- usage-stats 投影由 AppSession 自有承载，:store 的 RemoteUsageStatsStore 为自测孪生件不装配（`connection/AppSession.kt:29-31`）。

**工程面**

- 终验后工作区存在**未提交**改动：`android/connection/.../AppSession.kt`、`ZCodeServerConnection.kt`（见页首「M2–M7 工作流轮收尾」段），待入库。
- mobile-view-state-update 的 deviceInfo.platform 如实报 `"android"`（值域未取证，iOS 报 `"ios"` 同病；viewState 不生效首查此值，`relay/RelayChannelClient.kt:50`）。
- M1 既定 deviations 存续（JSON 运行时自研 / JSONValue.Int 32 位值域毫秒恒走 Double / AckActivationBarrier stagingLimit=256 / ReadOnlyGate draft 直译等），明细见交接文档 §7.1 的 2026-10-09 注记。
- 独立终审 3 条（高 1 条已修）为交接材料结论转述，报告本体未存档于本仓。

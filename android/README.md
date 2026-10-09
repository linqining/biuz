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

## 工程状态（2026-10-09：M1 已达成，M2 开工——:app 壳已产出 APK）

**M1 里程碑达成**（骨架随 `a547f6c`、protocol/teststub 实装随 `be25b65 feat: zcode mobile` 入库）：纯 JVM 多模块骨架 + protocol 层直译 + 单测平移 + 替身服务器骨架。未达成的项在下文如实挂账。

**M2 开工（2026-10-09 同日）**：:app Android 壳落地——AGP 8.13.2 + Kotlin 2.2.0 + Compose（BOM 2025.06.01 + material3/activity-compose/navigation-compose/lifecycle-runtime-compose），单 Activity + NavHost 五路由占位（connect/scan/sessions/chat/{sessionId}/settings）；Material3 深色 ColorScheme 直映 design/design-spec.md §2.1 令牌（近黑 #0a0b0d + 强调绿 #32f08c）；biuz-mark.svg 转 VectorDrawable adaptive 图标（mipmap-anydpi-v26 + API 24/25 兜底）；应用名 BiuZ，品牌字样守 branding/naming.md 裁决（仅「works with ZCode」描述性用法）。连接/会话业务链路仍未接线（下方「下一步」）。

### 模块结构（五个 JVM 模块，见 `settings.gradle.kts`）

| 模块 | 状态 | 内容 |
|---|---|---|
| `protocol` | **已实装** | 纯协议层直译（零 Android 依赖，仅 kotlin-stdlib + kotlinx-coroutines）：Models / RPCSerialization（含自研 RFC 8259 JsonCodec，模块禁第三方 JSON 依赖）/ V4Wire / ChannelClient / ProtocolSchema / CommandSchemas / ConversationCommandFactory / ReadOnlyGate / AckActivationBarrier / ConnectURLParser 十件；`relay/` 子包另有中继帧面两件（RelayLink / RelayFrameCodec，iOS Services/Relay 归属）；单测 6 套 |
| `relay` | 占位 | 中继传输（OkHttp WS；RelayTransport / RelayChannelClient），M6 实装 |
| `connection` | 占位 | `Connection.call` 唯一出口 + ReadOnlyGate 接线 + 桥重开握手单点 + AppSession 装配，M2 实装 |
| `store` | 占位 | 三大 Store + 协议面 interface + 空实现（未连接态），M2–M4 实装 |
| `teststub` | **骨架已实装** | Ktor 本地替身服务器（`E2EStubServer.kt`）：握手/快照/增量/命令回执伪造 + strict 信封对拍（复用 `:protocol` 校验器）+ ACK/CAS/快照注水开关，随后续里程碑扩展 |
| `app` | **壳已实装（M2 开工）** | 单 Activity Compose UI（`cn.biuz.android.app.MainActivity`，applicationId `cn.biuz.android`）：NavHost 五路由占位 + BiuZTheme（深色 Material3 直映射 design-spec 令牌）+ adaptive 启动图标（biuz-mark 矢量移植）。业务零接线，依赖 :protocol/:connection/:store/:relay（后三者仍占位） |

### 构建与测试

```bash
# 推荐入口：自带并行互斥锁（多个 Gradle 进程并发构建会互杀共享 daemon，禁绕过本脚本直调 gradle）
android/scripts/build-jvm.sh :protocol:test      # 协议层单测
android/scripts/build-jvm.sh :teststub:test      # 替身服务器冒烟
android/scripts/build-jvm.sh                     # 全量 build（无参数默认 build）

# 等价直调（确认无并行构建时）
/Users/mac/opt/gradle/gradle-8.14.3/bin/gradle -p android :protocol:test --console=plain

# :app 产出 APK（需 Android SDK，见 android/local.properties 的 sdk.dir）
/Users/mac/opt/gradle/gradle-8.14.3/bin/gradle -p android :app:assembleDebug --console=plain
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

### 门禁现状（2026-10-09 实跑记录）

- `android/scripts/build-jvm.sh :protocol:test --rerun-tasks` → exit=0，JUnit XML 记账 tests=77 / skipped=2 / failures=0 / errors=0：执行 75 例全绿；2 例 @Disabled 挂账（RelayLinkTests 的 relayLinkArgument / ServerConfig 旧 JSON 解码——被测 API 在 connection 装配层，随 M2 回补）。
- `android/scripts/build-jvm.sh :teststub:test --rerun-tasks` → exit=0，冒烟 3 例全绿（握手→订阅→sendText→ACK 帧序与替身记账；A5 能力越权 + strict 信封拒收；CAS stale-once + 写拦截 + ACK 六态词表）。
- **未达成（如实挂账）**：
  - 交接文档 §8.3 的 M1 验收口径「148 用例同口径绿」未整达——iOS 148 例中约 71 例属 store/VM 层单测，依赖未移植的 store/connection 代码，挂账至对应里程碑；Android 协议层五套平移 76 例（缺 parseInteraction 1 例，Store 层 API 缺位）+ 骨架占位 BootstrapTest 1 例 = 记账 77 例。
  - 独立复核发现 5 条，高优先级 0 条（未触发修复轮）；5 条 low 级发现以「遗留观察」挂账供人工判断，明细见交接文档 §7.1 的 2026-10-09 注记。

### 下一步（M2，按交接文档 §8.3；:app 壳/主题/图标已就绪，以下为剩余项）

- `connection` 模块：ZCodeServerConnection 同构移植（`call` 唯一出口 + 帧面多路挂载 + 桥重开握手单点）+ 局域网直连（`GET /ws?token=…`）。
- `store` 模块：RemoteTaskStore + sessions 列表 + bootstrap.tasks 多工作区。
- 替身服务器扩展：附件四方法、乐观回显（sourceCommandId 消费）、CAS stale 注水。
- 回补 2 例 @Disabled 单测与 parseInteraction 平移（connection/store 层 API 就位后）。

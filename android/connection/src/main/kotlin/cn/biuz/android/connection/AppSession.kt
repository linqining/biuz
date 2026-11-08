package cn.biuz.android.connection

// 直译自 ios/ZCodeMobileApp/Sources/Services/Remote/AppSession.swift（1358 行）：
// 应用会话装配层——连接生命周期（五态 Mode）+ Store 装配策略 + 桌面端只读信息
// （oauth / usage-stats 投影）+ 服务器注册表接线 + 多工作区枚举。
//
// 装配策略（对齐修复后，《对齐修复与降级体验设计》§0/§1，iOS 原文 :164-170 逐字）：
// - 连接成功 → 真实 API 实现 Store 协议（remote 面）；
// - 断线/连接中 → 保留现有 Store 引用（断线保留最后快照，不回落引导页——iOS 13-③
//   黄色横幅口径：mode 切 Disconnected 但不 teardown）；
// - 未配对/连接失败 → Empty*Store 空实现（**未连接 ≠ 演示**：无假数据；未配对=连接
//   引导页为根，失败=空数据 + 红横幅重试）。
// 红线（交接 §0-4 / AGENTS §5-13）：Mock 演示数据仅测试用途，正式路径永不装配——
// Android 侧自 M1 起即无任何 Mock Store 编译引用，本类不存在演示装配分支。
//
// 并发模型适配（docs/Android开发交接.md §2.2）：
// - Swift @MainActor @Observable → StateFlow 投影（mode/storeSurface/桌面只读四投影）；
//   回调改写（onConnectionDropped）在连接层后台线程到达，StateFlow 写入线程安全；
// - UserDefaults diag.* → [diagListener] 钩子（键名与 iOS 同名；:app 接线落 logcat/
//   文件，缺省静默）+ [diagModeEnabled] 守卫（iOS diag.wf.mode 在场判定的对等口径）；
// - ProcessInfo.arguments → 构造参数 [startupArguments]（App 进程无 argv 面；:app 由
//   启动 intent/instrumentation args 注入）；
// - `String(localized:)` → 同串字面量（同 Models.kt 文件头约定）。
//
// 未移植面（如实挂账，非本批范围）：
// - OAuth 账户层（OAuthTokenSet/OAuthCredentialStore/Keychain、handleOAuthSuccess/
//   logout/autoConnectAfterLogin 登录直达）：Android 无 OAuth 模型与凭据存储，登录 UI
//   未开工（协议面不受影响——连接层凭据只走 ServerConfig.token/relay.passHash）；
// - usage-stats 投影在本类自有承载（iOS AppSession usageStatsCall 同位直译）：
//   :store 的 RemoteUsageStatsStore 是同面提取孪生件（自测用），UI 数据源恒为本类
//   codingPlanUsage/entitlements/useCodingPlanResetCard——不重复装配零消费实例；
// - iOS P1 重连缓存迁移（exportCaches/adoptCaches）：Android RemoteConversationStore
//   未移植该对方法，重连即重建行缓存（同批 iOS Task { adoptCaches } 面如实挂账）。

import cn.biuz.android.protocol.relay.RelayLinkConfig
import cn.biuz.android.protocol.ConnectURLParser
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.JSONObjectBuilder
import cn.biuz.android.protocol.JsonParseException
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCValue
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.EmptyConversationStore
import cn.biuz.android.store.EmptyFileStore
import cn.biuz.android.store.EmptyTaskStore
import cn.biuz.android.store.FileStore
import cn.biuz.android.store.RemoteConversationStore
import cn.biuz.android.store.RemoteFileStore
import cn.biuz.android.store.RemoteSessionsIndexStore
import cn.biuz.android.store.RemoteTaskStore
import cn.biuz.android.store.StoreWorkspaceInfo
import cn.biuz.android.store.TaskStore
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// MARK: - 桌面端只读信息模型（oauth / usage-stats 投影；AppSession.swift:4-160 直译）

/** 桌面端 OAuth 登录展示态：provider 名单 + 当前 provider + 登录用户/过期态（纯只读安全面）。 */
data class DesktopOAuthInfo(
    val providers: List<String> = emptyList(),
    val activeProvider: String? = null,
    val authenticated: Boolean = false,
    val requiresReauthentication: Boolean = false,
    val userName: String? = null,
    val userHandle: String? = null,
)

/**
 * 单个额度窗口（level 词表宽容映射：five-hour/weekly/monthly）。
 * iOS SwiftUI Identifiable 的 id 用 label——同 type 两窗（5 小时/每周都叫 TOKENS_LIMIT）
 * 以 level 作 id 会重复渲染（原文 :56-58 注）；Android 列表渲染沿用 label 键约定。
 */
data class CodingPlanQuotaWindow(
    val level: String,
    val label: String,
    val used: Int? = null,
    val limit: Int? = null,
    val unit: String? = null,
    val percentUsed: Double? = null,
    val resetsAtText: String? = null,
) {
    /** 剩余比例（0~1；无 percentage 时由 used/limit 推导；原文 :61-67 同式）。 */
    val percentRemaining: Double?
        get() {
            percentUsed?.let { return (1 - it / 100).coerceIn(0.0, 1.0) }
            val usedValue = used ?: return null
            val limitValue = limit ?: return null
            if (limitValue <= 0) return null
            return ((limitValue - usedValue).toDouble() / limitValue.toDouble()).coerceIn(0.0, 1.0)
        }
}

/** 重置卡信息（2026-10-05 取证：available*Resets 数组 + latest*ResetHistory.usedAt + 分组最早过期）。 */
data class CodingPlanResetCards(
    val fiveHourCount: Int = 0,
    val weekCount: Int = 0,
    /** 最早过期（5 小时卡组）——5h/周卡分别作用于对应窗口，分开展示（用户裁决不合并取最早） */
    val fiveHourEarliestText: String? = null,
    /** 最早过期（周卡组） */
    val weekEarliestText: String? = null,
    /** 最近一次使用（5h 窗） */
    val lastFiveHourUsedText: String? = null,
    /** 最近一次使用（周窗） */
    val lastWeekUsedText: String? = null,
)

/** Coding Plan 用量只读投影（设置用户卡数据源；无数据 UI 不渲染进度条——H10，不回退演示值）。 */
data class CodingPlanUsageInfo(
    val percentRemaining: Double? = null,
    val used: Int? = null,
    val limit: Int? = null,
    val unitText: String? = null,
    val resetsAtText: String? = null,
    /** 全部额度窗口（quota.limits：5 小时/每周/每月；此前只取首窗致「额度不正确」） */
    val windows: List<CodingPlanQuotaWindow> = emptyList(),
    /** 重置卡（getCodingPlanResetStatus；null = 未取到） */
    val resetCards: CodingPlanResetCards? = null,
)

/** 套餐权益条目（getEntitlementSnapshot subscription.details[]；web $Fe 同构，过期条目过滤）。 */
data class CodingPlanEntitlement(
    val name: String,
    /** 数值/额度文案（web 回执无此层，恒 null；保留渲染位） */
    val value: String? = null,
    val detail: String? = null,
)

/**
 * 套餐权益快照（P3-9 额度页；web 口径审查报告 B-5）：null = 调用失败/未连接（诚实降级）；
 * noPlan = 未订阅套餐（web 空态判定）；entitlements 空 = 成功但无在期条目（不渲染子块）。
 */
data class CodingPlanEntitlementInfo(
    /** 套餐档位（web IAt 同构：quota.level 优先，回落 subscription.details[0].productName） */
    val tier: String? = null,
    val entitlements: List<CodingPlanEntitlement> = emptyList(),
    /** unavailableReason === "no_plan"（未订阅套餐——web 空态文案口径） */
    val noPlan: Boolean = false,
    /** 其他不可用原因（not_configured/not_authenticated/unavailable 等） */
    val unavailableReason: String? = null,
    /** 顶层 remaining（web 仅做在场判定；单位语义未取证，原样透出） */
    val remainingText: String? = null,
    /** mcpQuota.aggregate 原始形态（§11.2：MCP 配额只在 entitlement 快照——
     * usage-stats.ts:80-86/269-280 实证 usage 快照无此键；供 usage 窗口合并造「ZCode MCP」行） */
    val mcpQuotaAggregate: JSONValue? = null,
)

/** 使用统计快照（usage-stats.getAppUsageSnapshot；桌面「使用统计」页同源数据）。 */
data class AppUsageInfo(
    val summary: AppUsageSummary,
    val models: List<AppUsageModelSlice>,
    val daily: List<AppUsageDailyPoint>,
    val tools: List<AppUsageToolStat>,
)

data class AppUsageSummary(
    val totalTokens: Double? = null,
    val peakDayTokens: Double? = null,
    val longestSessionMs: Int? = null,
    val currentStreakDays: Int? = null,
    val longestStreakDays: Int? = null,
    val totalSessions: Int? = null,
    val totalTurns: Int? = null,
    val activeDays: Int? = null,
    val cacheHitRate: Double? = null,
    val favoriteModelId: String? = null,
    val favoriteModelShare: Double? = null,
)

/** 模型用量占比（桌面 donut 的移动端等价呈现：占比条 + 百分比）。 */
data class AppUsageModelSlice(
    val modelId: String,
    val totalTokens: Double,
    val share: Double? = null,
)

/** 每日各模型 token（趋势折线数据点；date=yyyy-MM-dd）。 */
data class AppUsageDailyPoint(
    val date: String,
    val byModel: Map<String, Double>,
)

/** 工具调用统计。 */
data class AppUsageToolStat(
    val toolName: String,
    val callCount: Int,
    val avgDurationMs: Double,
    val errorRate: Double? = null,
)

// MARK: - 应用会话装配（iOS AppSession.swift:162-1358 主体直译）

/**
 * Store 装配面（iOS remoteConversationStore/remoteTaskStore/remoteFileStore 三可选属性 +
 * storeEpoch 的 Kotlin 承载）：字段恒非空——未连接/失败态即 Empty 三件（红线：无 Mock，
 * 读面空、写面如实失败），UI 永不判空；换源绑定以 [epoch] 为 key（视图/ViewModel 按
 * store 实例重建，防永久抱空 store——iOS .task(id: storeEpoch) 环境值换绑同语义）。
 *
 * 连接态三件 = 真实实现（iOS assembleRemoteStores 同构）：
 * - conversation = [RemoteConversationStore]（行读/发送/审批/信封命令面；会话列表读面
 *   委托 sessions-index 订阅直供——:store 承载口径）；
 * - task = RemoteTaskStore（审批/停止经 RemoteConversationBridge 注入上者）；
 * - file = RemoteFileStore（会话维度变更经 FileConversationAnchor 注入上者）。
 */
data class StoreSurface(
    val epoch: Int,
    val conversation: ConversationStore,
    val task: TaskStore,
    val file: FileStore,
    /** sessions-index 订阅面（连接态非空：列表失败文本 conversationsLoadFailure 的读源） */
    val sessionsIndex: RemoteSessionsIndexStore?,
    /** 装配时工作区（未连接 null） */
    val workspace: ServerWorkspaceInfo?,
) {
    companion object {
        fun empty(epoch: Int): StoreSurface = StoreSurface(
            epoch = epoch,
            conversation = EmptyConversationStore(),
            task = EmptyTaskStore(),
            file = EmptyFileStore(),
            sessionsIndex = null,
            workspace = null,
        )
    }
}

/**
 * :store 的 RPCChannelTransport 适配：一切调用转发 [ZCodeServerConnection].call/listen——
 * ReadOnlyGate 出口拦截与握手自愈对 Store 调用面同等生效（工程纪律 §5-5 唯一出口；
 * 未连接时 listen 返回空句柄：Store 的 subscribe 调用随即 NotConnected 失败走各自兜底链，
 * 语义与 iOS「未连接 listen 得空订阅」一致）。
 */
internal class ConnectionTransport(
    private val connection: ZCodeServerConnection,
) : RPCChannelTransport {
    override suspend fun call(
        channel: String,
        command: String,
        arg: RPCValue,
        timeout: kotlin.time.Duration,
    ): RPCValue = connection.call(channel, command, arg, timeout)

    override suspend fun listen(
        channel: String,
        event: String,
        arg: RPCValue,
        handler: (RPCValue) -> Unit,
    ): EventSubscription = connection.listen(channel, event, arg, handler) ?: EventSubscription { }

    override suspend fun disconnect() = connection.disconnect()
}

/**
 * 应用会话装配（iOS @MainActor @Observable AppSession 直译）。
 * 生命周期：init（E2E 复位先于凭据加载）→ bootstrap（冷启动自动重连）→ connect/
 * cancelConnecting/reconnect/updateToken/deleteServer → assembleStores/teardownStores。
 */
class AppSession(
    /** iOS Bundle.main CFBundleShortVersionString 的构造参数对应物（透传连接握手 appVersion）。 */
    val appVersion: String = ZCodeServerConnection.DEFAULT_APP_VERSION,
    /** 服务器注册表持久化后端（:app 注入 SharedPreferences 承接；测试注入内存实现）。 */
    registryPersistence: ServerRegistryPersistence = InMemoryServerRegistryPersistence(),
    /** 启动参数（iOS ProcessInfo.arguments 对应物；E2E/诊断钩子从此读取）。 */
    val startupArguments: List<String> = emptyList(),
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * 中继 bootstrap.tasks 数据源（iOS connection.relayBootstrapTasks 的注入面）：M6 中继
     * 客户端落位后由装配方回填；局域网直连恒 null = 不注水（iOS 非 中继连接为 nil 同口径）。
     */
    var bootstrapTasksProvider: (suspend () -> JSONValue?)? = null,
    /**
     * OAuth 账户凭据持久化后端（iOS Keychain 对应物；:app 注入 Keystore 加密实现，
     * 测试注入内存实现）。与连接凭据（ServerRegistry）是两套独立存储。
     */
    oauthPersistence: OAuthCredentialPersistence = InMemoryOAuthCredentialPersistence(),
) {

    // MARK: 连接五态（iOS enum Mode 直译；Swift Equatable → Kotlin data class 值相等）

    sealed class Mode {
        /** 未配对（连接引导页为根；无演示数据——Mock 红线） */
        data object Demo : Mode()

        data class Connecting(val server: ServerConfig) : Mode()

        data class Connected(val server: ServerConfig) : Mode()

        /** 连接失败（空数据 + 红横幅重试） */
        data class ConnectFailed(val server: ServerConfig, val error: ConnectError) : Mode()

        /** 曾连接后断线（保留最后快照 + 橙横幅重连） */
        data class Disconnected(val server: ServerConfig, val detail: String) : Mode()
    }

    // MARK: 连接层

    private val _mode = MutableStateFlow<Mode>(Mode.Demo)

    /** iOS @Observable mode 的 Kotlin 投影（UI collectAsState 承接刷新）。 */
    val mode: StateFlow<Mode>
        get() = _mode

    val connection: ZCodeServerConnection = ZCodeServerConnection(appVersion = appVersion)

    // MARK: Store 装配面（P3-10 世代语义）

    private val _storeSurface = MutableStateFlow(StoreSurface.empty(epoch = 0))

    /**
     * Store 装配面（未连接 = Empty 三件）。装配成功与 teardown 时 epoch +1——UI 层以
     * epoch/实例为 key 重挂观察流（iOS storeEpoch 驱动 .task 重跑同语义）。
     */
    val storeSurface: StateFlow<StoreSurface>
        get() = _storeSurface

    /** Store 装配世代（iOS storeEpoch 直译；= storeSurface.value.epoch）。 */
    val storeEpoch: Int
        get() = _storeSurface.value.epoch

    /** 当前装配的工作区路径（P1 重连缓存迁移判定用：teardown 清空；迁移面随 M3 接入）。 */
    private var assembledWorkspacePath: String? = null

    /** 会话级纵深防御守卫（switchWorkspace 并发切换串行化，iOS :1309 直译）。 */
    private var isSwitchingWorkspace = false

    /** 断线保留快照判定用（iOS `remoteConversationStore != nil` 的具名承载）。 */
    private var storesAssembled = false

    // MARK: 桌面端只读信息（oauth / usage-stats 只读面；连接态拉取，断开清空）

    private val _desktopOAuthInfo = MutableStateFlow<DesktopOAuthInfo?>(null)
    val desktopOAuthInfo: StateFlow<DesktopOAuthInfo?>
        get() = _desktopOAuthInfo

    private val _codingPlanUsage = MutableStateFlow<CodingPlanUsageInfo?>(null)
    val codingPlanUsage: StateFlow<CodingPlanUsageInfo?>
        get() = _codingPlanUsage

    private val _codingPlanEntitlements = MutableStateFlow<CodingPlanEntitlementInfo?>(null)
    val codingPlanEntitlements: StateFlow<CodingPlanEntitlementInfo?>
        get() = _codingPlanEntitlements

    private val _appUsageSnapshot = MutableStateFlow<AppUsageInfo?>(null)
    val appUsageSnapshot: StateFlow<AppUsageInfo?>
        get() = _appUsageSnapshot

    // MARK: 多工作区枚举（⑤切换器派生源；bootstrap.tasks 面见 assembleStores）

    private val _bootstrapWorkspaces = MutableStateFlow<List<ServerWorkspaceInfo>>(emptyList())

    /** bootstrap.tasks 派生的工作区清单（装配时派生，断开清空；iOS :1258 直译）。 */
    val bootstrapWorkspaces: StateFlow<List<ServerWorkspaceInfo>>
        get() = _bootstrapWorkspaces

    // MARK: 诊断承载（iOS UserDefaults diag.* 的 Kotlin 钩子；键名与 iOS 同名）

    /**
     * 诊断写出点（键名与 iOS diag.* 同名；缺省 null = 静默）。:app 装配层接线到
     * logcat/文件；与 iOS 一致，诊断键不承载业务逻辑。
     */
    var diagListener: ((key: String, value: String) -> Unit)? = null

    /** iOS `UserDefaults.standard.string(forKey: "diag.wf.mode") != nil` 的对等口径：
     * 启动参数携 -ZCodeDiag* 前缀即开（取证用 one-shot diag 键由此 gating）。 */
    private val diagModeEnabled: Boolean =
        startupArguments.any { it.startsWith("-ZCodeDiag") }

    private fun diag(key: String, value: String) {
        diagListener?.invoke(key, value)
    }

    // MARK: 注册表与便捷投影

    private val registry: ServerRegistry = ServerRegistry(registryPersistence)

    /** 已保存服务器（最近连接一台，L4 多台口径：N>1 跳最近连接）。 */
    val savedServer: ServerConfig?
        get() = registry.selectedServer ?: registry.servers.firstOrNull()

    /** E2E Mock 激活开关（iOS demoDataArgument 直译）。Android 无 Mock Store 可装配
     * （红线：Mock 仅测试 target）——本开关仅保留语义位，恒不产生演示数据。 */
    val isDemoDataEnabled: Boolean
        get() = startupArguments.contains(DEMO_DATA_ARGUMENT)

    /** iOS isDemo 语义（未连接 ≠ 演示）：仅开关激活且未连接时 true；Android 无 Mock 装配，
     * 该值仅供未来演示态 UI gating，不改变任何数据面。 */
    val isDemo: Boolean
        get() {
            if (!isDemoDataEnabled) return false
            return _mode.value !is Mode.Connected
        }

    /** 是否已连接桌面端（横幅/入口 gating 便捷属性）。 */
    val isConnected: Boolean
        get() = _mode.value is Mode.Connected

    /** 连接中进度（仅 mode=Connecting 时透出；iOS connectProgress 同守卫）。 */
    val connectProgress: ConnectProgress?
        get() {
            if (_mode.value !is Mode.Connecting) return null
            return (connection.state.value as? ZCodeServerConnection.ConnectionState.Connecting)?.progress
        }

    /** 连接日志（最近 40 条快照副本；L2 面板数据源）。 */
    val connectLogs: List<ConnectLogLine>
        get() = connection.logs()

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    // MARK: - OAuth 账户层（iOS AppSession.swift:1276-1514 同构）
    //
    // 与连接凭据（ServerRegistry）是两套独立凭据：退出登录只清账户层，不动已保存服务器。

    private val oauthConfig = OAuthConfig.resolve(startupArguments)
    val oauthCredentials: OAuthCredentialStore = OAuthCredentialStore(oauthPersistence)
    private val oauthClient = ZaiOAuthClient(oauthConfig)
    private val deviceCatalog = RelayDeviceCatalog()

    private val _oauthSessionVersion = MutableStateFlow(0)
    val oauthSessionVersion: StateFlow<Int> = _oauthSessionVersion

    /** 进行中的授权（provider + nonce）：回调时必须与 state 里的 nonce 对上。 */
    @Volatile
    private var pendingOAuth: Pair<OAuthProviderId, String>? = null

    @Volatile
    private var lastRelayDeviceSid: String? = null

    @Volatile
    private var lastRelayDeviceMid: String? = null

    val oauthTokenSet: OAuthTokenSet?
        get() = oauthCredentials.tokenSet

    val oauthUserInfo: OAuthUserInfo?
        get() = oauthCredentials.userInfo

    val isLoggedIn: Boolean
        get() = oauthCredentials.tokenSet != null

    val isOAuthExpired: Boolean
        get() = oauthCredentials.tokenSet?.let { oauthCredentials.isExpired(it) } ?: false

    /** 登录后设备直达结果（iOS `AutoConnectOutcome`；UI 据此渲染）。 */
    sealed class AutoConnectOutcome {
        data object Connected : AutoConnectOutcome()

        data object NoPairedDevice : AutoConnectOutcome()

        data class NeedsSelection(val devices: List<RelayDeviceSummary>) : AutoConnectOutcome()

        data class Failed(val target: String) : AutoConnectOutcome()
    }

    /** ① 发起授权：返回授权 URL（UI 用 WebView 打开），并记下 nonce 供回调校验。 */
    fun beginOAuth(provider: OAuthProviderId): String {
        val nonce = UUID.randomUUID().toString()
        pendingOAuth = provider to nonce
        return oauthClient.buildAuthorizeUrl(provider, OAuthStateCodec.buildState(nonce))
    }

    /**
     * ② 回调落地：校验 state → 用 code 换 token → 落凭据 → 推送登录态。
     * 任一环节失败抛 [OAuthException]（调用方按五态呈现文案，不吞错）。
     */
    @Throws(OAuthException::class)
    suspend fun completeOAuth(
        callbackUrl: String,
        cookieHeader: String? = null,
    ): OAuthCredentialStore.Credentials {
        val params = oauthClient.parseCallbackParams(callbackUrl)
        val pending = pendingOAuth
        val actualNonce = OAuthStateCodec.nonce(params.state)
        if (pending == null || actualNonce == null || actualNonce != pending.second) {
            throw OAuthException.StateMismatch()
        }
        params.error?.let { throw OAuthException.ServerError(it) }
        val code = params.code ?: throw OAuthException.ServerError("授权回调缺少 code")
        val credentials = oauthClient.exchangeToken(
            provider = pending.first,
            code = code,
            state = params.state,
            cookieHeader = cookieHeader,
        )
        oauthCredentials.save(credentials.tokenSet, credentials.userInfo)
        pendingOAuth = null
        _oauthSessionVersion.value += 1
        return credentials
    }

    /** 凭据已由调用方落盘后的状态推送（iOS `handleOAuthSuccess`）。 */
    fun handleOAuthSuccess(tokenSet: OAuthTokenSet, userInfo: OAuthUserInfo) {
        oauthCredentials.save(tokenSet, userInfo)
        _oauthSessionVersion.value += 1
    }

    /** 退出登录：仅清账户层 tokenSet，不动已保存服务器与连接令牌。 */
    fun logout() {
        oauthCredentials.clear()
        pendingOAuth = null
        _oauthSessionVersion.value += 1
    }

    /**
     * 登录成功后自动发现设备并连接（iOS `autoConnectAfterLogin` :1318-1376）。
     *
     * 顺序：设备目录（JWT）按官方规则选 → 多在线设备让用户选 → 目录不可用则回退本地已配对中继。
     */
    suspend fun autoConnectAfterLogin(): AutoConnectOutcome {
        if (_mode.value is Mode.Connected) return AutoConnectOutcome.Connected

        val devices = fetchRelayDevices()
        if (devices != null) {
            val online = devices.filter { it.online }
            val savedSid = registry.servers
                .filter { it.relay != null }
                .sortedByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
                .mapNotNull { it.relay?.deviceSid }
                .firstOrNull()
            val match = RelayDeviceCatalog.selectAutoConnectDevice(
                devices = devices,
                savedSid = savedSid,
                lastSid = lastRelayDeviceSid,
                lastMid = lastRelayDeviceMid,
            )
            if (match != null) return connectRelayDevice(match)
            if (online.size > 1) return AutoConnectOutcome.NeedsSelection(online)
        }

        val target = registry.servers
            .filter { it.relay != null }
            .sortedByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
            .firstOrNull()
            ?: return AutoConnectOutcome.NoPairedDevice
        connect(target)
        if (_mode.value is Mode.Connected) {
            rememberRelayDevice(target.relay?.deviceSid, target.relay?.deviceMid)
            return AutoConnectOutcome.Connected
        }
        cancelConnecting()
        return AutoConnectOutcome.Failed(target.name ?: target.host)
    }

    /** 连接设备目录中的一台设备（自动落点与多设备选择共用）。 */
    suspend fun connectRelayDevice(device: RelayDeviceSummary): AutoConnectOutcome {
        val existing = registry.servers.firstOrNull { it.relay?.deviceSid == device.deviceSid }
        // 目录来自 Relay V2：已有 V1 hash 配置不能复用（端点/鉴权不同），统一合成 V2 链接
        val link = existing?.relay?.takeIf { it.isV2 } ?: relayLinkFor(device)
        val server = ServerConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = existing?.name ?: device.name,
            host = link.endpointHost ?: "zcode.z.ai",
            port = 443,
            useTLS = link.wssURL.lowercase().startsWith("wss://"),
            token = "",
            lastConnectedAt = existing?.lastConnectedAt,
            preferredWorkspacePath = existing?.preferredWorkspacePath,
            relay = link,
        )
        connect(server)
        if (_mode.value is Mode.Connected) {
            rememberRelayDevice(device.deviceSid, device.deviceMid)
            return AutoConnectOutcome.Connected
        }
        cancelConnecting()
        return AutoConnectOutcome.Failed(device.displayName)
    }

    /** 由设备目录条目合成 V2 中继链接（iOS `relayLink(for:)` :1434-1451）。 */
    private fun relayLinkFor(device: RelayDeviceSummary): RelayLinkConfig {
        val origin = oauthConfig.tokenOrigin
        val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: "zcode.z.ai"
        val scheme = if (origin.lowercase().startsWith("http://")) "ws" else "wss"
        return RelayLinkConfig(
            wssURL = "$scheme://$host/api/v1/remote/ws2",
            machineName = device.name,
            deviceSid = device.deviceSid,
            passHash = null,
            desktopAppVersion = device.version,
            protocolVersion = 2,
            deviceMid = device.deviceMid,
        )
    }

    /** 拉设备目录；任何失败返回 null（不代表「没有设备」，调用方要回退本地）。 */
    private suspend fun fetchRelayDevices(): List<RelayDeviceSummary>? {
        val jwt = oauthCredentials.tokenSet?.zcodeJwtToken?.trim().orEmpty()
        if (jwt.isEmpty()) return null
        return deviceCatalog.fetch(oauthConfig.tokenOrigin, jwt)
    }

    private fun rememberRelayDevice(sid: String?, mid: String?) {
        if (!sid.isNullOrEmpty()) lastRelayDeviceSid = sid
        if (!mid.isNullOrEmpty()) lastRelayDeviceMid = mid
    }

    init {
        // Relay V2：每次（重）连现取 JWT（账号层凭据；未登录返回 null → V2 连接如实报
        // 「需要登录 Z.ai 账号」，不静默降级）
        connection.relayJwtProvider = { oauthCredentials.tokenSet?.zcodeJwtToken }
        // 断线回落（iOS 13-③ 黄色横幅）：曾连接后通道意外中断 → mode 切 Disconnected
        // （不 teardown——Store 与缓存快照保留，UI 展示「已断开 · 显示断线前的数据 · 重连」）；
        // 重连动作走 reconnect/connect（横幅重试同入口）。手动断开/连接期失败不经此路径。
        connection.onConnectionDropped = { detail ->
            val current = _mode.value
            if (current is Mode.Connected) {
                _mode.value = Mode.Disconnected(current.server, detail)
            }
        }
        // 工作区清单推送（P3-10 workspace-list-updated）：清单本体已由 connection 回写
        // serverInfo（切换器菜单联动）；此处回写会话 Store 聚合清单（iOS :304-309 直译
        // —— membership scope 枚举/identity 反查/聚合任务列表的活体清单源，键级整体替换）
        connection.onWorkspaceListUpdated = { workspaces ->
            (_storeSurface.value.conversation as? RemoteConversationStore)?.allWorkspaces =
                workspaces.map { it.toStoreWorkspace() }
        }
        // E2E 钩子：须先于凭据加载执行（iOS :310-315 同序；OAuth 凭据加载随 OAuth 层
        // 移植接入，当前无操作）。
        performE2EStateResetIfNeeded()
    }

    /**
     * E2E 专用：仅在启动参数携带 `-ZCodeE2EResetState` 时清空本机凭据态（服务器注册表），
     * 保证登录/配对用例可重复、无顺序依赖；不携带该参数时为空操作。
     * iOS 同函数的 UserDefaults 各复位键（语言偏好/设置档/列表过滤档/chat.target.*）随
     * 对应 Store 移植时在此追加（键名保持 iOS 同名）。
     */
    fun performE2EStateResetIfNeeded(arguments: List<String> = startupArguments) {
        if (!arguments.contains(E2E_RESET_STATE_ARGUMENT)) return
        registry.clear()
    }

    // MARK: 冷启动（iOS bootstrap :348-393 直译）

    /**
     * 冷启动：已配置服务器 → 后台自动重连（失败空态 + 红横幅重试）；未配置 → 连接
     * 引导页为根（demo 态，无假数据）。
     * QA/E2E 钩子：`-ZCodeOpenLoginFlow` 直开登录主页（OAuth UI 挂账，当前仅吞参保序）；
     * `-ZCodeOpenConnectFlow` 直开连接页（Android 连接页即根路由，吞参保序）；
     * `-ZCodeRelayLink <url>` 解析中继配对链接并直接发起云中继连接（链接失效保持演示态）。
     */
    suspend fun bootstrap() {
        val arguments = startupArguments
        diag("diag.args", "args=${arguments.size} relayArg=${relayLinkArgument(arguments) ?: "nil"}")
        when {
            arguments.contains("-ZCodeOpenLoginFlow") -> {
                // OAuth 登录主页随 OAuth 层移接入；当前保序吞参（保持 demo 根）
            }
            arguments.contains("-ZCodeOpenConnectFlow") -> {
                // Android 连接页即导航根（NavHost startDestination），无需流程弹层
            }
            else -> {
                val relayURL = relayLinkArgument(arguments)
                if (relayURL != null) {
                    connectRelayLink(relayURL)
                    return
                }
            }
        }
        // iOS Keychain 冷启动 errSecMissingEntitlement 抖动的短退避重读（模拟器已知抖动）；
        // Android SharedPreferences 无该缺陷，重读保序直译（无害幂等）。
        var server = savedServer
        if (server == null) {
            repeat(3) {
                delay(600)
                server = savedServer
                if (server != null) return@repeat
            }
        }
        val target = server
        if (target != null) {
            diag(
                "diag.lastConnect",
                "bootstrap: relay=${target.relay != null} count=${registry.servers.size} host=${target.host}:${target.port}",
            )
            _mode.value = Mode.Connecting(target)
            connectToSaved(target)
        } else {
            diag(
                "diag.lastConnect",
                "bootstrap: savedServer=nil(含重试) servers=${registry.servers.size} selected=${registry.selectedServerId ?: "nil"} → demo",
            )
            _mode.value = Mode.Demo
        }
    }

    // `relayLinkArgument` 承载于 companion（M1 静态面原位；类体内免限定名直呼同函数）。

    /**
     * 中继链接直连入口（调试钩子 / L1 粘贴链接共用）：解析 → 持久化 → 连接。
     * 建链本体随 :relay M6 落位——[connectOnce] 对中继配置如实失败，mode 落 ConnectFailed。
     */
    suspend fun connectRelayLink(rawLink: String) {
        val link = ConnectURLParser.parseRelayLink(rawLink)
        if (link == null) {
            _mode.value = Mode.Demo
            return
        }
        // 中继服务器在注册表内按 mid/sid 复用（重复连接不膨胀列表）
        val existing = registry.servers.firstOrNull { it.relay?.deviceSid == link.deviceSid }
        val server = ServerConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = existing?.name,
            host = link.endpointHost ?: "zcode.z.ai",
            port = 443,
            useTLS = true,
            token = "",
            lastConnectedAt = existing?.lastConnectedAt,
            preferredWorkspacePath = existing?.preferredWorkspacePath,
            relay = link,
        )
        _mode.value = Mode.Connecting(server)
        // 连接前置写入注册表（iOS :414-418 注：失败也保留设备，自动连接与目标菜单均以
        // 注册表 relay 项为准；漏 upsert 曾致「暂未发现已配对的桌面设备」误引导）
        registry.upsert(server)
        connectToSaved(server)
    }

    private suspend fun connectOnce(server: ServerConfig): ConnectResult {
        // iOS 分支 connectRelay(to:)（AppSession.swift:422-425）：中继配置 → 云中继链路
        // （WS + auth 握手 → bootstrap → workspace 桥），不静默按局域网五步打 wss 面
        if (server.relay != null) {
            return connection.connectRelay(server)
        }
        return connection.connect(server = server, preferredWorkspace = server.preferredWorkspacePath)
    }

    private suspend fun connectToSaved(server: ServerConfig) {
        var result = connectOnce(server)
        // 云中继链路偶发瞬断（桌面端重启/网络抖动）：短退避自动重试两次再判失败（iOS :431-438）
        if (result is ConnectResult.Failure && server.relay != null) {
            for (delayMs in longArrayOf(2_000, 4_000)) {
                delay(delayMs)
                result = connectOnce(server)
                if (result is ConnectResult.Success) break
            }
        }
        when (result) {
            is ConnectResult.Success -> {
                // 中继连接的 bootstrap.tasks 注水源（iOS assembleRemoteStores 内
                // connection.relayBootstrapTasks 同源，AppSession.swift:1226；先于
                // assembleStores 赋值——装配协程读取该 provider）
                if (server.relay != null) {
                    bootstrapTasksProvider = { connection.relayBootstrapTasks }
                }
                // 先装配 Store 再切 mode（iOS :441 注：装配完成后 UI 才切换数据源）
                val connected = connection.state.value
                    as? ZCodeServerConnection.ConnectionState.Connected
                assembleStores(workspace = connected?.workspace ?: return)
                _mode.value = Mode.Connected(server)
                // 连接成功收口：注册表更新（名称回填 + 最近连接时间 + 选中台）
                var updated = server.copy(lastConnectedAt = System.currentTimeMillis())
                if (updated.name == null) {
                    updated = updated.copy(name = result.info.name)
                }
                registry.upsert(updated)
                registry.selectedServerId = updated.id
                diag("diag.lastConnect", "connected: relay=${server.relay != null}")
                // 桌面端只读信息（oauth 登录展示态 + Coding Plan 用量）后台拉取；失败各字段保持 null
                scope.launch { refreshDesktopReadonlyInfo() }
            }
            is ConnectResult.Failure -> {
                diag(
                    "diag.lastConnect",
                    "failed: relay=${server.relay != null} host=${server.host}:${server.port} error=${result.error.codeLine}",
                )
                teardownStores()
                _mode.value = Mode.ConnectFailed(server, result.error)
            }
        }
    }

    // MARK: 配对流程（L1 → L2 → 成功/失败；iOS :1095-1135 直译）

    suspend fun connect(server: ServerConfig) {
        _mode.value = Mode.Connecting(server)
        connectToSaved(server)
    }

    fun cancelConnecting() {
        connection.cancelConnecting()
        // 取消落态按场景分流（iOS 设计稿 §1.4，防快照丢失）：从未连上（无 Store 实例）→
        // .demo（回连接引导页——无数据可保留）；断线重连中取消 → .disconnected（快照保留 +
        // 橙横幅「已取消重连」，可随时再点重连）
        val current = _mode.value
        _mode.value = if (storesAssembled && current is Mode.Connecting) {
            Mode.Disconnected(current.server, "已取消重连")
        } else {
            Mode.Demo
        }
    }

    /** L3「重新扫码更新令牌」：以新令牌更新已存服务器并重连。 */
    suspend fun updateToken(server: ServerConfig, token: String) {
        val updated = server.copy(token = token)
        registry.upsert(updated)
        connect(server = updated)
    }

    /** L5 删除服务器：清注册表记录与令牌（二次确认后调用；iOS :1123-1128 直译）。 */
    fun deleteServer(server: ServerConfig) {
        registry.remove(id = server.id)
        connection.disconnect()
        teardownStores()
        _mode.value = Mode.Demo
    }

    /** 断线重连（横幅「重试」）。 */
    suspend fun reconnect() {
        savedServer?.let { connect(server = it) }
    }

    // MARK: 桌面端只读信息拉取链（iOS :493-1093 直译）

    /**
     * 连接态拉取桌面只读信息（oauth 三读 + usage-stats 两读 + 多 workspace 枚举探测）；
     * 断开/重连时刷新。usage-stats 串行（桌面拒绝并发快照请求——usageStatsCall 注）。
     */
    suspend fun refreshDesktopReadonlyInfo() {
        if (!connection.isActive) {
            _desktopOAuthInfo.value = null
            _codingPlanUsage.value = null
            _codingPlanEntitlements.value = null
            _appUsageSnapshot.value = null
            return
        }
        _desktopOAuthInfo.value = fetchDesktopOAuthInfo(connection)
        _codingPlanUsage.value = fetchCodingPlanUsage(connection)
        _appUsageSnapshot.value = fetchAppUsageSnapshot(connection)
        // 权益快照顺读链末位（usage-stats 对并发快照请求拒绝——与上方两读保持串行）
        _codingPlanEntitlements.value = fetchCodingPlanEntitlements(connection)
        mergeMcpQuotaWindow()
        probeRemoteControlBootstrap()
    }

    /** 只读链后台补全（用量页主卡先行渲染后调用；在途不重发，完成自清——iOS :557-567）。 */
    fun refreshDesktopReadonlyInfoInBackground() {
        synchronized(this) {
            if (readonlyBackgroundJob != null) return
            readonlyBackgroundJob = scope.launch {
                refreshDesktopReadonlyInfo()
                synchronized(this@AppSession) { readonlyBackgroundJob = null }
            }
        }
    }

    private var readonlyBackgroundJob: Job? = null

    /** 「ZCode MCP」窗口合并（§11.2：mcpQuota 只在 entitlement 快照——此前从 usage 快照读恒 nil）。 */
    private fun mergeMcpQuotaWindow() {
        val aggregate = _codingPlanEntitlements.value?.mcpQuotaAggregate?.objectValue ?: return
        val usage = _codingPlanUsage.value ?: return
        if (usage.windows.any { it.level == "MCP" }) return
        val window = makeMcpQuotaWindow(aggregate) ?: return
        _codingPlanUsage.value = usage.copy(windows = usage.windows + window)
    }

    /** mcpQuota.aggregate → 窗口行（currentValue/usage/nextResetTime 同 makeWindow 口径）。 */
    private fun makeMcpQuotaWindow(d: Map<String, JSONValue>): CodingPlanQuotaWindow? {
        var window = CodingPlanQuotaWindow(
            level = "MCP",
            label = "ZCode MCP",
            used = d["currentValue"].countValue(),
            limit = d["usage"].countValue(),
            unit = if (d["currentValue"] != null) "条" else null,
            percentUsed = null,
            resetsAtText = null,
        )
        val pct = d["percentage"]?.doubleValue
        if (pct != null) {
            window = window.copy(percentUsed = (pct * 100).coerceIn(0.0, 100.0))
        }
        val nextReset = d["nextResetTime"].millisValue()
        if (nextReset != null && nextReset > 0) {
            window = window.copy(resetsAtText = shortFormat(nextReset))
        }
        return window
    }

    /** Coding Plan 额度单项重拉（用量页主卡快路径；失败不清既有缓存值——读面 last-good 纪律）。 */
    suspend fun refreshCodingPlanUsage() {
        if (!connection.isActive) return
        _codingPlanUsage.value = fetchCodingPlanUsage(connection)
        mergeMcpQuotaWindow()
    }

    /** 使用重置卡（桌面代执行三步，web reset-cards 同构；仅接线不自动触发）。返回用户可读反馈（null = 成功）。 */
    suspend fun useCodingPlanResetCard(type: CodingPlanResetType): String? {
        if (!connection.isActive) {
            return "未连接桌面端"
        }
        val idempotencyKey = UUID.randomUUID().toString()
        // ① 幂等探测领取机会（web 手动路径同构：探测失败不阻断用卡，仅记日志——
        // 用卡资格以先前 getCodingPlanResetStatus 读数为准）
        try {
            usageStatsCall(
                connection,
                "requestCodingPlanResetOpportunity",
                JSONObjectBuilder.build {
                    set("preferredProviderId", CODING_PLAN_PROVIDER_ID)
                    set("accountAccess", CODING_PLAN_ACCOUNT_ACCESS)
                    set("idempotencyKey", idempotencyKey)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[usage-stats] requestCodingPlanResetOpportunity 失败（不阻断用卡）: ${e.message}")
        }
        // ② 使用重置卡（平铺四键；失败如实回传 UI）
        try {
            usageStatsCall(
                connection,
                "useCodingPlanReset",
                JSONObjectBuilder.build {
                    set("preferredProviderId", CODING_PLAN_PROVIDER_ID)
                    set("accountAccess", CODING_PLAN_ACCOUNT_ACCESS)
                    set("idempotencyKey", idempotencyKey)
                    set("resetType", type.rawValue)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return "领取失败 · ${e.message ?: e.toString()}"
        }
        // ③ 清历史未读角标（web fire-and-forget 同构：失败仅记日志，不影响用卡结果）
        try {
            usageStatsCall(
                connection,
                "markCodingPlanResetHistoryRead",
                JSONObjectBuilder.build {
                    set("preferredProviderId", CODING_PLAN_PROVIDER_ID)
                    set("accountAccess", CODING_PLAN_ACCOUNT_ACCESS)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[usage-stats] markCodingPlanResetHistoryRead 失败（不影响用卡结果）: ${e.message}")
        }
        // 额度/重置状态回流刷新
        refreshDesktopReadonlyInfo()
        return null
    }

    /**
     * usage-stats 读面统一入口：桌面端对并发快照请求拒绝（"Request in progress,
     * please wait"——连接刷新与用量页 task 竞态时实测），首败 1.2s 退避重试一次
     * （同 loadOlder 中继瞬断口径）；重试仍败则原错误上抛（调用方取证）。
     */
    private suspend fun usageStatsCall(connection: ZCodeServerConnection, command: String, args: JSONValue): RPCValue {
        return try {
            connection.call("usage-stats", command, RPCValue.json(args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            delay(1_200)
            connection.call("usage-stats", command, RPCValue.json(args))
        }
    }

    /** oauth 只读三接口 → 展示态（provider 名单/当前 provider/登录用户与过期态）。全部失败容忍（逐一容错），任一成功即产出。 */
    private suspend fun fetchDesktopOAuthInfo(connection: ZCodeServerConnection): DesktopOAuthInfo? {
        var info = DesktopOAuthInfo()
        runCatching { connection.call("oauth", "getProviders") }.getOrNull()?.let { providersResult ->
            val json = providersResult.jsonValue
            val list = json?.arrayValue ?: json?.get("providers")?.arrayValue ?: emptyList()
            info = info.copy(
                providers = list.mapNotNull { item ->
                    item.stringValue
                        ?: item.objectValue?.get("providerId")?.stringValue
                        ?: item.objectValue?.get("name")?.stringValue
                },
            )
        }
        runCatching { connection.call("oauth", "getActiveProvider") }.getOrNull()?.let { activeResult ->
            val json = activeResult.jsonValue
            info = info.copy(
                activeProvider = json?.stringValue
                    ?: json?.get("providerId")?.stringValue
                    ?: json?.get("name")?.stringValue,
            )
        }
        runCatching { connection.call("oauth", "restoreCachedSessionState") }.getOrNull()
            ?.let { stateResult ->
                val dict = stateResult.jsonValue?.objectValue ?: return@let
                when (dict["status"]?.stringValue) {
                    "authenticated" -> {
                        info = info.copy(authenticated = true)
                        val userInfo = dict["userInfo"]?.objectValue ?: dict["user"]?.objectValue
                        info = info.copy(
                            userName = userInfo?.get("displayName")?.stringValue,
                            userHandle = userInfo?.get("username")?.stringValue,
                        )
                    }
                    "reauthentication-required" -> {
                        info = info.copy(authenticated = false, requiresReauthentication = true)
                    }
                    else -> {
                        info = info.copy(authenticated = false)
                    }
                }
            }
        if (info.providers.isEmpty() && info.activeProvider == null &&
            !info.authenticated && !info.requiresReauthentication
        ) {
            return null
        }
        return info
    }

    /** 使用统计快照（usage-stats.getAppUsageSnapshot {range, timeZone}；桌面同源：summary + models + daily + tools）。 */
    private suspend fun fetchAppUsageSnapshot(connection: ZCodeServerConnection): AppUsageInfo? {
        val args = JSONObjectBuilder.build {
            set("range", "30d")
            set("timeZone", TimeZone.getDefault().id)
        }
        val dict = runCatching { usageStatsCall(connection, "getAppUsageSnapshot", args) }
            .getOrNull()?.jsonValue?.objectValue ?: return null
        // 取证（一次性）：App 用量统计快照原始形态（diag 模式才写；§11 登记口径）
        if (diagModeEnabled) {
            diag(
                "diag.usage.app",
                "topKeys=${dict.keys.sorted().joinToString("|")} ${dict.toString().take(2000)}",
            )
        }
        var summary = AppUsageSummary()
        dict["summary"]?.objectValue?.let { s ->
            summary = AppUsageSummary(
                totalTokens = s["totalTokens"]?.doubleValue,
                peakDayTokens = s["peakDayTokens"]?.doubleValue,
                longestSessionMs = s["longestSessionMs"].countValue(),
                currentStreakDays = s["currentStreakDays"].countValue(),
                longestStreakDays = s["longestStreakDays"].countValue(),
                totalSessions = s["totalSessions"].countValue(),
                totalTurns = s["totalTurns"].countValue(),
                activeDays = s["activeDays"].countValue(),
                cacheHitRate = s["cacheHitRate"]?.doubleValue,
                favoriteModelId = s["favoriteModel"]?.objectValue?.get("modelId")?.stringValue,
                favoriteModelShare = s["favoriteModel"]?.objectValue?.get("share")?.doubleValue,
            )
        }
        val models = (dict["models"]?.arrayValue ?: emptyList()).mapNotNull { m ->
            val d = m.objectValue ?: return@mapNotNull null
            val modelId = d["modelId"]?.stringValue ?: return@mapNotNull null
            AppUsageModelSlice(
                modelId = modelId,
                totalTokens = d["totalTokens"]?.doubleValue ?: 0.0,
                share = d["share"]?.doubleValue,
            )
        }
        val daily = (dict["dailyModelUsage"]?.arrayValue ?: emptyList()).mapNotNull { point ->
            val d = point.objectValue ?: return@mapNotNull null
            val date = d["date"]?.stringValue ?: return@mapNotNull null
            val byModel = LinkedHashMap<String, Double>()
            for (entry in d["models"]?.arrayValue ?: emptyList()) {
                val e = entry.objectValue ?: continue
                val modelId = e["modelId"]?.stringValue ?: continue
                byModel[modelId] = e["totalTokens"]?.doubleValue ?: 0.0
            }
            AppUsageDailyPoint(date = date, byModel = byModel)
        }
        val tools = (dict["tools"]?.arrayValue ?: emptyList()).mapNotNull { t ->
            val d = t.objectValue ?: return@mapNotNull null
            val toolName = d["toolName"]?.stringValue ?: return@mapNotNull null
            AppUsageToolStat(
                toolName = toolName,
                callCount = d["callCount"].countValue() ?: 0,
                avgDurationMs = d["avgDurationMs"]?.doubleValue ?: 0.0,
                errorRate = d["errorRate"]?.doubleValue,
            )
        }
        if (summary.totalTokens == null && models.isEmpty() && daily.isEmpty()) return null
        return AppUsageInfo(summary = summary, models = models, daily = daily, tools = tools)
    }

    // MARK: usage-stats 个人 Coding Plan 统一参数（web bundle 取证 2026-10-06；iOS :719-921 直译）

    /**
     * preferredProviderId 必须用注册表完整 id（「zai」短 id 匹配不到任何 provider，
     * 桌面端落到 bigmodel API-key 面 → no_bigmodel_api_key——§9.10 实证先例）。
     */
    val codingPlanProviderID: String
        get() = CODING_PLAN_PROVIDER_ID

    /**
     * web 端 accountAccess 统一形状【实证·bundle 逆向】：所有 usage-stats 调用点恒传
     * `{type:'zhipu-account', family, planKind}`（family 由 provider id 推导——account:zai-* → 'zai'）。
     */
    val codingPlanAccountAccess: JSONValue
        get() = CODING_PLAN_ACCOUNT_ACCESS

    private suspend fun fetchCodingPlanUsage(connection: ZCodeServerConnection): CodingPlanUsageInfo? {
        var usage = CodingPlanUsageInfo()
        val snapshotArgs = JSONObjectBuilder.build {
            set("range", "30d")
            // preferredProviderId 完整 id 口径见 CODING_PLAN_PROVIDER_ID 注；web 端另带 timeZone
            set("preferredProviderId", CODING_PLAN_PROVIDER_ID)
            set("accountAccess", CODING_PLAN_ACCOUNT_ACCESS)
            set("timeZone", TimeZone.getDefault().id)
        }
        val snapshotDict: Map<String, JSONValue>? = try {
            usageStatsCall(connection, "getCodingPlanUsageSnapshot", snapshotArgs).jsonValue?.objectValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 失败不再静默（「桌面端未返回 Coding Plan 用量」的取证口）
            if (diagModeEnabled) {
                diag("diag.usage.snapshot.error", "err=${(e.message ?: e.toString()).take(500)}")
            }
            null
        }
        if (snapshotDict != null) {
            // 取证（一次性）：quota.limits 全量窗口形态（level 词表定位 5h/周/月）
            if (diagModeEnabled) {
                diag("diag.usage.snapshot", snapshotDict.toString())
            }
            // quota = {level:"max"|…, limits:[…], mcpQuota?}。
            // 窗口映射（2026-10-06 桌面 web bundle 精确取证，app.asar 内 U5/JH 选择器）：
            // 按 type+unit+number 三元组匹配（与数组顺序无关）：
            //   5 小时   = TOKENS_LIMIT, unit:3, number:5（v2/v3 百分比窗）
            //   每周     = TOKENS_LIMIT, unit:6
            //   工具调用 = TIME_LIMIT,   unit:5, number:1（v1 条数窗）
            //   ZCode MCP = quota.mcpQuota.aggregate（独立字段，合并见 mergeMcpQuotaWindow）
            // 百分比口径（web vGt/yGt 同构，2026-10-06 校正）：percentage 为 **0–100 已用
            // 百分数** → 剩余 = 100 − pct；v1 条数套餐 percentage 缺席时 remaining/number×100
            // 兜底（分母是 number，不是 usage）。未识别条目回退 generic 行（≤3）。
            val limits = snapshotDict["quota"]?.objectValue?.get("limits")?.arrayValue ?: emptyList()

            fun findLimit(type: String, unit: Int, number: Int? = null): Map<String, JSONValue>? {
                for (limit in limits) {
                    val d = limit.objectValue ?: continue
                    if (d["type"]?.stringValue?.uppercase() != type.uppercase()) continue
                    if (d["unit"]?.intValue != unit) continue
                    if (number != null && d["number"]?.intValue != number) continue
                    return d
                }
                return null
            }

            // 剩余百分比（web vGt 同构）：percentage 为 0–100 已用百分数 → 剩余 = 100 − pct；
            // v1 条数套餐 percentage 缺席时 remaining/number×100 兜底（分母 number）
            fun remainingPercent(d: Map<String, JSONValue>?): Double? {
                if (d == null) return null
                d["percentage"]?.doubleValue?.let { return (100 - it).coerceIn(0.0, 100.0) }
                val remaining = d["remaining"]?.doubleValue
                val number = d["number"]?.doubleValue
                if (remaining != null && number != null && number > 0) {
                    return (remaining / number * 100).coerceIn(0.0, 100.0)
                }
                return null
            }

            fun makeWindow(label: String, d: Map<String, JSONValue>?): CodingPlanQuotaWindow? {
                if (d == null) return null
                var window = CodingPlanQuotaWindow(
                    level = d["type"]?.stringValue ?: "window",
                    label = label,
                    used = d["currentValue"].countValue(),
                    limit = d["usage"].countValue(),
                    unit = if (d["currentValue"] != null) "条" else null,
                    percentUsed = null,
                    resetsAtText = null,
                )
                val remaining = remainingPercent(d)
                if (remaining != null) {
                    // struct: percentRemaining = 1 - percentUsed/100（iOS :815 同式）
                    window = window.copy(percentUsed = 100 - remaining)
                }
                val nextReset = d["nextResetTime"].millisValue()
                if (nextReset != null && nextReset > 0) {
                    window = window.copy(resetsAtText = shortFormat(nextReset))
                }
                return window
            }

            val windows = ArrayList<CodingPlanQuotaWindow>()
            makeWindow("5 小时", findLimit("TOKENS_LIMIT", unit = 3, number = 5))?.let { windows.add(it) }
            makeWindow("每周", findLimit("TOKENS_LIMIT", unit = 6))?.let { windows.add(it) }
            makeWindow("工具调用", findLimit("TIME_LIMIT", unit = 5, number = 1))?.let { windows.add(it) }
            // 未识别条目回退 generic 行（web：slice(0,3)，label「额度」）
            if (windows.isEmpty()) {
                for (d in limits.take(3).mapNotNull { it.objectValue }) {
                    val type = d["type"]?.stringValue ?: ""
                    makeWindow(if (type.isEmpty()) "额度" else type, d)?.let { windows.add(it) }
                }
            }
            usage = usage.copy(windows = windows)
            // ZCode MCP 窗口不在此读（§11.2：mcpQuota 只在 entitlement 快照；合并见
            // mergeMcpQuotaWindow）。套餐档位（quota.level："max" 等）记入 unitText 供卡片角标
            usage = usage.copy(unitText = snapshotDict["quota"]?.objectValue?.get("level")?.stringValue)
            // 主窗口（legacy 字段兼容用户卡）：5 小时窗优先
            val primary = usage.windows.firstOrNull { it.label == "5 小时" } ?: usage.windows.firstOrNull()
            if (primary != null) {
                usage = usage.copy(
                    used = primary.used,
                    limit = primary.limit,
                    percentRemaining = primary.percentRemaining,
                    resetsAtText = primary.resetsAtText,
                )
            }
        }
        val resetDict = runCatching {
            usageStatsCall(
                connection,
                "getCodingPlanResetStatus",
                JSONObjectBuilder.build {
                    set("preferredProviderId", CODING_PLAN_PROVIDER_ID)
                    set("accountAccess", CODING_PLAN_ACCOUNT_ACCESS)
                },
            )
        }.getOrNull()?.jsonValue?.objectValue
        if (resetDict != null) {
            // 取证（一次性）：重置卡全量形态
            if (diagModeEnabled) {
                diag("diag.usage.reset", resetDict.toString().take(1200))
            }
            // 重置卡全量解析：5h/周两组可用卡 + 最早过期 + 最近使用历史
            var cards = CodingPlanResetCards()
            val fiveHour = resetDict["availableFiveHourResets"]?.arrayValue ?: emptyList()
            val week = resetDict["availableWeekResets"]?.arrayValue ?: emptyList()
            cards = cards.copy(fiveHourCount = fiveHour.size, weekCount = week.size)
            // 过期时间按组分开展示（5h 卡只重置 5h 窗、周卡只重置周窗，语义不同——
            // 用户裁决：不能合并取最早）
            fun earliestText(group: List<JSONValue>): String? =
                group.mapNotNull { it.objectValue?.get("expireAt").millisValue() }
                    .filter { it > 0 }
                    .minOrNull()
                    ?.let { shortFormat(it) }
            cards = cards.copy(
                fiveHourEarliestText = earliestText(fiveHour),
                weekEarliestText = earliestText(week),
            )
            val lastFiveHourUsed = resetDict["latestFiveHourResetHistory"]?.objectValue?.get("usedAt").millisValue()
            if (lastFiveHourUsed != null && lastFiveHourUsed > 0) {
                cards = cards.copy(lastFiveHourUsedText = shortFormat(lastFiveHourUsed))
            }
            val lastWeekUsed = resetDict["latestWeekResetHistory"]?.objectValue?.get("usedAt").millisValue()
            if (lastWeekUsed != null && lastWeekUsed > 0) {
                cards = cards.copy(lastWeekUsedText = shortFormat(lastWeekUsed))
            }
            usage = usage.copy(resetCards = cards)
            // 注意：usage.resetsAtText 保留额度窗口自身 nextResetTime（自动重置时间）；
            // 重置卡的 expireAt 是「卡过期时间」，语义不同——不能覆写（曾致用户卡显示
            // 卡过期日当额度重置日，数据对不上）
        }
        // 取证（一次性）：App 用量统计快照形状由 fetchAppUsageSnapshot 在 diag 模式下落
        // diag.usage.app（iOS :898-919 同批取证键，避免同链重复 RPC）
        return if (usage.used != null || usage.limit != null || usage.percentRemaining != null) usage else null
    }

    /**
     * 套餐权益快照（usage-stats.getEntitlementSnapshot；P3-9 额度页；web 口径 B-5）。
     * 入参六键 {includeSubscription:true, preferredProviderId, accountAccess,
     * allowDisabledPreferredProvider:true, requirePreferredProvider:true, allowEnvApiKey:false}；
     * 回执 {provider, authenticated?, unavailableReason?, quota:{level,limits},
     * subscription:{details:[{productName,productId,expireTime?}]}, remaining?}。
     * 调用失败 → null；成功但无在期订阅条目 → 空 entitlements（UI 不渲染子块）。
     */
    private suspend fun fetchCodingPlanEntitlements(connection: ZCodeServerConnection): CodingPlanEntitlementInfo? {
        val args = JSONObjectBuilder.build {
            set("includeSubscription", true)
            set("preferredProviderId", CODING_PLAN_PROVIDER_ID)
            set("accountAccess", CODING_PLAN_ACCOUNT_ACCESS)
            set("allowDisabledPreferredProvider", true)
            set("requirePreferredProvider", true)
            set("allowEnvApiKey", false)
        }
        val result: RPCValue? = try {
            usageStatsCall(connection, "getEntitlementSnapshot", args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (diagModeEnabled) {
                diag("diag.usage.entitlement", "err=${(e.message ?: e.toString()).take(500)}")
            }
            null
        }
        if (result == null) return null // 调用失败 → nil（UI 诚实降级，iOS :945-951 同口径）
        // 成功但回执无 json → 空 entitlements（UI 不渲染子块；iOS :953 同口径）
        val dict: Map<String, JSONValue> = result.jsonValue?.objectValue
            ?: return CodingPlanEntitlementInfo()
        // 取证（一次性）：原始回执形态（真桌面验收后回写 §9.10 并清理诊断键）
        if (diagModeEnabled) {
            diag("diag.usage.entitlement", dict.toString().take(2000))
        }
        var info = CodingPlanEntitlementInfo()
        info = info.copy(unavailableReason = dict["unavailableReason"]?.stringValue)
        info = info.copy(noPlan = info.unavailableReason == "no_plan")
        // authenticated 显式为 false 时无订阅可言（web $Fe 同构过滤）；键缺席视为已认证
        val authenticated = dict["authenticated"]?.boolValue ?: true
        val details = if (authenticated) {
            dict["subscription"]?.objectValue?.get("details")?.arrayValue ?: emptyList()
        } else {
            emptyList()
        }
        // 权益条目 = subscription.details[]（web $Fe：过期条目按 expireTime 过滤；
        // 名称取 productName，缺席回落 productId）
        val entitlements = details.mapNotNull { item ->
            val d = item.objectValue ?: return@mapNotNull null
            val name = d["productName"]?.stringValue ?: d["productId"]?.stringValue
            if (name.isNullOrEmpty() || entitlementEntryExpired(d["expireTime"])) return@mapNotNull null
            val detail = entitlementEntryDate(d["expireTime"])?.let { "${shortFormat(it.time)} 前有效" }
            CodingPlanEntitlement(name = name, value = null, detail = detail)
        }
        // 档位（web IAt 同构）：quota.level 优先，回落首个条目名
        info = info.copy(
            tier = dict["quota"]?.objectValue?.get("level")?.stringValue ?: entitlements.firstOrNull()?.name,
            entitlements = entitlements,
        )
        // 顶层 remaining（web 仅做在场判定；单位语义未取证，原样透出不加解释）
        val remaining = dict["remaining"]
        if (remaining != null && !remaining.isNull) {
            info = info.copy(
                remainingText = remaining.stringValue
                    ?: (remaining as? JSONValue.Int)?.value?.toString()
                    ?: remaining.doubleValue?.let { "%g".format(it) },
            )
        }
        // mcpQuota 原始形态透出（§11.2：usage 窗口合并阶段消费）
        info = info.copy(mcpQuotaAggregate = dict["mcpQuota"]?.objectValue?.get("aggregate"))
        return info
    }

    /**
     * subscription.details[].expireTime → epoch 毫秒（web Date.parse 同构 = ISO 字符串；
     * 数字形态宽容按毫秒时间戳）。返回 null = 无法解析（不过滤、不展示）。
     */
    private fun entitlementEntryDate(value: JSONValue?): Date? {
        value.millisValue()?.let { ms -> if (ms > 0) return Date(ms) }
        val text = value?.stringValue ?: return null
        val epochMs = parseIsoEpochMs(text) ?: return null
        return Date(epochMs)
    }

    private fun entitlementEntryExpired(value: JSONValue?): Boolean {
        val date = entitlementEntryDate(value) ?: return false
        return date.time <= System.currentTimeMillis()
    }

    // MARK: 多 workspace 枚举探测（web 远控 REST 面，2026-10-06 bundle 取证；iOS :569-608 直译）

    /**
     * `GET {relayOrigin}/api/remote-control/windows/bootstrap/{token}` →
     * `{workspaces, tasks, mobileViewState|initialViewState}`——web 端工作区切换器数据源。
     * 移动端以配对链接 sid 试探：2xx → diag 落 workspace 清单；401/404 → diag 落状态码。
     * GET 只读，无副作用。
     */
    private suspend fun probeRemoteControlBootstrap() {
        val relay = savedServer?.relay
        val server = savedServer
        if (relay == null || relay.deviceSid.isEmpty() || server == null) return
        val origin = if (server.useTLS) "https://${server.host}" else "http://${server.host}"
        var urlConnection: HttpURLConnection? = null
        try {
            val url = URL("$origin/api/remote-control/windows/bootstrap/${relay.deviceSid}")
            val conn = url.openConnection() as HttpURLConnection
            urlConnection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            val status = conn.responseCode
            val body = try {
                conn.inputStream.use { it.readBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ByteArray(0)
            }
            val json = try {
                JSONValue.parse(body)
            } catch (e: JsonParseException) {
                null
            }
            val dict = json?.objectValue
            if (dict != null) {
                val workspaces = dict["workspaces"]?.arrayValue ?: emptyList()
                val paths = workspaces.mapNotNull { item ->
                    item.objectValue?.get("workspacePath")?.stringValue
                        ?: item.objectValue?.get("path")?.stringValue
                        ?: item.objectValue?.get("workspaceKey")?.stringValue
                }
                diag(
                    "diag.remote.bootstrap",
                    "status=$status topKeys=${dict.keys.sorted().joinToString("|")}" +
                        " workspaces=${workspaces.size} [${paths.joinToString(" | ").take(300)}]",
                )
            } else {
                diag("diag.remote.bootstrap", "status=$status body=${body.decodeToString().take(200)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diag("diag.remote.bootstrap", "ERR ${(e.message ?: e.toString()).take(200)}")
        } finally {
            runCatching { urlConnection?.disconnect() }
        }
    }

    // MARK: Store 装配（iOS assembleRemoteStores/teardownRemoteStores :1203-1250 直译）

    /**
     * 连接成功装配（iOS 同名直译；先于 mode=.connected 执行——装配完成后 UI 才切数据源）。
     * - sessions-index 订阅面 + 任务 Store 走 [ConnectionTransport]（唯一出口，ReadOnlyGate
     *   出口拦截与握手自愈对 Store 调用面同等生效）；
     * - conversation = [RemoteConversationStore]（行读/发送/审批/信封命令面全量接入；
     *   列表读面委托 sessionsIndex 直读；帧面=连接本体 implements ConversationFrameSurface；
     *   clientId=握手注册值构造注入——A2 全生命周期单一来源）；
     * - 任务 Store 审批/停止经 RemoteConversationBridge、文件 Store 会话维度变更经
     *   FileConversationAnchor，均注入同一会话 Store 实例（iOS 同构三 Store 同批）；
     * - iOS P1 重连缓存迁移（exportCaches/adoptCaches）未移植——重连即重建行缓存；
     * - 多 workspace 回写两源（iOS Task { setAllWorkspaces/setBootstrapTasks } 同批）：
     *   连接层采集清单 + bootstrap.tasks 派生工作区（membership scope 枚举源）；
     * - bootstrap.tasks 派生清单 + 直入会话表（iOS Task { setBootstrapTasks } 同批）——中继
     *   bootstrap 面 M6 经 [bootstrapTasksProvider] 注入，局域网直连恒 null = 不注水。
     */
    private fun assembleStores(workspace: ServerWorkspaceInfo) {
        diag("diag.assemble", "assemble: workspace=${workspace.path}")
        val previous = _storeSurface.value
        val transport = ConnectionTransport(connection)
        val storeWorkspace = StoreWorkspaceInfo(
            path = workspace.path,
            label = workspace.label,
            workspaceIdentity = workspace.workspaceIdentity,
        )
        val sessionsIndex = RemoteSessionsIndexStore(
            transport = transport,
            workspace = storeWorkspace,
            backgroundDispatcher = backgroundDispatcher,
        )
        sessionsIndex.diagListener = { key, value -> diagListener?.invoke(key, value) }
        val conversationStore = RemoteConversationStore(
            transport = transport,
            // 帧面=连接本体：四件方法签名与 ConversationFrameSurface 逐字一致（A8+陷阱④
            // 帧 handler 先于 subscribe 注册的次序由 Store 内部序保证）
            frameSurface = connection,
            workspace = storeWorkspace,
            sessionsIndex = sessionsIndex,
            clientId = connection.registeredClientId,
            backgroundDispatcher = backgroundDispatcher,
        )
        conversationStore.diagListener = { key, value -> diagListener?.invoke(key, value) }
        // 多 workspace：连接期 workspace-list-response 采集的桌面全部工作区回写
        // （聚合任务列表 + membership scope 枚举 + identity 反查；iOS setAllWorkspaces :441-443）
        conversationStore.allWorkspaces = connection.serverInfo?.workspaces?.map { it.toStoreWorkspace() }
            ?: emptyList()
        val taskStore = RemoteTaskStore(
            transport = transport,
            workspace = storeWorkspace,
            // 审批（resolvePermission）/停止（stopTurn）/行投影经桥注入——不再恒 Undelivered
            conversationStore = conversationStore,
            backgroundDispatcher = backgroundDispatcher,
        )
        taskStore.diagListener = { key, value -> diagListener?.invoke(key, value) }
        val fileStore = RemoteFileStore(
            transport = transport,
            workspace = storeWorkspace,
            // 会话维度变更（sessionDiffFiles）的水位/归属寻址注入
            conversationAnchor = conversationStore,
            backgroundDispatcher = backgroundDispatcher,
        )
        fileStore.diagListener = { key, value -> diagListener?.invoke(key, value) }
        _storeSurface.value = StoreSurface(
            epoch = previous.epoch + 1,
            conversation = conversationStore,
            task = taskStore,
            file = fileStore,
            sessionsIndex = sessionsIndex,
            workspace = workspace,
        )
        storesAssembled = true
        assembledWorkspacePath = workspace.path
        scope.launch {
            val tasks = runCatching { bootstrapTasksProvider?.invoke() }.getOrNull()
            _bootstrapWorkspaces.value = deriveBootstrapWorkspaces(tasks)
            sessionsIndex.setBootstrapTasks(tasks)
            // bootstrap 派生工作区（membership scope 枚举第二源；iOS setBootstrapTasks 同批）
            conversationStore.bootstrapWorkspaces = deriveBootstrapWorkspaces(tasks).map { it.toStoreWorkspace() }
        }
    }

    /** 连接层工作区条目 → :store 工作区条目（三处回写共用映射）。 */
    private fun ServerWorkspaceInfo.toStoreWorkspace() = StoreWorkspaceInfo(
        path = path,
        label = label,
        workspaceIdentity = workspaceIdentity,
    )

    /**
     * 旧 Store 显式释放（iOS deinit 兜底的 Kotlin 对应物；仅在 [teardownStores] 调用）：
     * 真退订（携 subscriptionId，桌面侧订阅单调增长——缺省退订=泄漏）+ file-watcher
     * unwatch + 订阅面撤销。**重装配路径不调用**：assembleStores 前桥已重开/传输已拆
     * （teardownTransport 清空 frameHandlers 注册表，旧订阅随桥消亡），异步 dispose 的
     * removeFrameHandler 会与新 Store 的同名 topic handler 竞态（陷阱④同类静默丢帧）；
     * iOS 重装配同样直接丢弃旧 Store 不退订（AppSession.swift:1225 口径）。
     */
    private fun disposeStoreStores(surface: StoreSurface) {
        val conversation = surface.conversation as? RemoteConversationStore
        val file = surface.file as? RemoteFileStore
        val index = surface.sessionsIndex
        if (conversation == null && file == null && index == null) return
        scope.launch {
            if (conversation != null) runCatching { conversation.dispose() }
            if (file != null) runCatching { file.dispose() }
            if (index != null) runCatching { index.dispose() }
        }
    }

    private fun teardownStores() {
        // 未连接/连接失败 = Empty 三件（红线：读面空、写面如实失败，无 Mock）；
        // epoch +1 驱动 UI 换源重绑（防旧视图永久抱已失效 Store 实例）
        val previous = _storeSurface.value
        disposeStoreStores(previous)
        _storeSurface.value = StoreSurface.empty(epoch = previous.epoch + 1)
        storesAssembled = false
        _desktopOAuthInfo.value = null
        _codingPlanUsage.value = null
        _codingPlanEntitlements.value = null
        _bootstrapWorkspaces.value = emptyList()
        assembledWorkspacePath = null
    }

    // MARK: 多工作区切换（P3-10；iOS :1252-1343 直译）

    /** 切换器清单：连接层采集清单（workspace-list-response/updated，active 首位）∪
     * bootstrap.tasks 派生全量，按 path 去重保序（iOS :1292-1304 同序）。 */
    val switcherWorkspaces: List<ServerWorkspaceInfo>
        get() {
            val merged = ArrayList<ServerWorkspaceInfo>()
            val seenPaths = HashSet<String>()
            connection.serverInfo?.workspaces?.forEach { entry ->
                if (seenPaths.add(entry.path)) merged.add(entry)
            }
            _bootstrapWorkspaces.value.forEach { entry ->
                if (seenPaths.add(entry.path)) merged.add(entry)
            }
            return merged
        }

    /**
     * 切换活动工作区（iOS :1317-1343 直译；iOS connection.switchRelayWorkspace 属中继链路，
     * 局域网直连无该面——iOS supportsWorkspaceSwitching=false 时切换器 UI 即隐藏）。
     * 守卫链直译：未连接拒、并发切换拒、不支持拒（用户可读失败原因）；失败时原工作区
     * Store/桥均保持不动。M6 完成路径：桥重开 → 订阅重定向 → 以新工作区重跑
     * [assembleStores]（旧工作区 Store 随面替换丢弃——桥重开已回收订阅，不迁移缓存）。
     */
    suspend fun switchWorkspace(target: ServerWorkspaceInfo): String? {
        if (_mode.value !is Mode.Connected) {
            return "未连接桌面端"
        }
        if (isSwitchingWorkspace) {
            return "已有工作区切换在进行中，请稍后再试"
        }
        isSwitchingWorkspace = true
        try {
            // M6 完成路径（iOS :1317-1343 直译）：connection.switchRelayWorkspace（桥重开 →
            // 订阅重定向 → workspace-config 重订）成功后复核连接态（切换在途断线会重装配
            // Store，迟到成功不得覆盖重连链路新 Store）→ 以新工作区重跑 assembleStores
            //（epoch 触发 UI 换绑）→ return null；失败保持当前工作区并带出底层 detail。
            when (val result = connection.switchRelayWorkspace(target)) {
                is WorkspaceSwitchResult.Success -> {
                    val current = _mode.value
                    val info = connection.serverInfo
                    if (current !is Mode.Connected || info == null) {
                        return "切换结果未落定 · 连接已变化，请重试"
                    }
                    assembleStores(workspace = result.workspace)
                    return null
                }
                is WorkspaceSwitchResult.Failure -> {
                    // 失败带出底层原因（原 Store/工作区不动）；.Transport 携桥层原文
                    //（桌面拒绝 reason / 超时 / 并发拒绝），其余形态回退 headline
                    val detail = (result.error as? ConnectError.Transport)?.detail
                        ?: result.error.headline
                    return "切换失败 · 已保持当前工作区（$detail）"
                }
            }
        } finally {
            isSwitchingWorkspace = false
        }
    }

    // MARK: 连接测试（L4-B：1.5s 超时，仅探测不建 WS；iOS :1345-1357 直译）

    suspend fun testConnection(server: ServerConfig): ServerInfoClient.ProbeResult =
        ServerInfoClient.probe(server = server, timeoutMs = 1_500)

    /**
     * 在线探测点（L1/L4：1.5s 超时，绿=可达 / 灰=不可达 / 半透明=未探测）。
     * 中继服务器无 /api/server-info HTTP 面（在线性由连接态体现），跳过探测。
     */
    suspend fun probeSavedServer(): ServerInfoClient.ProbeResult? {
        val server = savedServer ?: return null
        if (server.relay != null) return null
        return testConnection(server)
    }

    companion object {
        /** iOS demoDataArgument（`-ZCodeDemoData`；Android 无 Mock 装配，语义位保留）。 */
        const val DEMO_DATA_ARGUMENT = "-ZCodeDemoData"

        /** E2E 状态复位开关（iOS -ZCodeE2EResetState 同名）。 */
        const val E2E_RESET_STATE_ARGUMENT = "-ZCodeE2EResetState"

        /** `-ZCodeRelayLink` 启动参数键（M1 静态面原字面量）。 */
        private const val RELAY_LINK_ARGUMENT = "-ZCodeRelayLink"

        /** usage-stats 个人 Coding Plan 统一参数（iOS codingPlanProviderID :723 直译）。 */
        private const val CODING_PLAN_PROVIDER_ID = "account:zai-individual-coding-plan"

        /** web 端 accountAccess 统一形状【实证·bundle 逆向】（iOS :730-734 直译）。 */
        private val CODING_PLAN_ACCOUNT_ACCESS: JSONValue = JSONValue.Obj(
            linkedMapOf(
                "type" to JSONValue.Str("zhipu-account"),
                "family" to JSONValue.Str("zai"),
                "planKind" to JSONValue.Str("individual-coding-plan"),
            ),
        )

        /** 重置卡 resetType 词表（web 端 FIVE_HOUR|WEEK；iOS :1035-1038 直译）。 */
        enum class CodingPlanResetType(val rawValue: String) {
            FIVE_HOUR("FIVE_HOUR"),
            WEEK("WEEK"),
        }

        /**
         * bootstrap.tasks → 工作区条目（web 同构【移植·bundle 逆向】：任务行按键
         * `workspaceIdentity?.trim()||workspacePath` 归组进工作区；行内 workspaceIdentity
         * 可选）。按 path 去重保序；label 取路径末段（iOS Conversation.projectName 同款口径）。
         */
        fun deriveBootstrapWorkspaces(tasks: JSONValue?): List<ServerWorkspaceInfo> {
            val items = tasks?.arrayValue ?: return emptyList()
            val byPath = LinkedHashMap<String, ServerWorkspaceInfo>()
            for (item in items) {
                val d = item.objectValue ?: continue
                val path = d["workspacePath"]?.stringValue ?: continue
                if (path.isEmpty()) continue
                val identity = d["workspaceIdentity"]?.stringValue?.trim()
                val existing = byPath[path]
                if (existing == null) {
                    byPath[path] = ServerWorkspaceInfo(
                        path = path,
                        label = path.split("/").filter { it.isNotEmpty() }.lastOrNull(),
                        workspaceIdentity = identity?.takeIf { it.isNotEmpty() },
                    )
                } else if (!identity.isNullOrEmpty() && existing.workspaceIdentity == null) {
                    // 同工作区多行：补首个非空身份（远端工作区判别键，切换反查用）
                    byPath[path] = existing.copy(workspaceIdentity = identity)
                }
            }
            return byPath.values.toList()
        }

        /** `-ZCodeRelayLink <url>` 取值（相邻参数；纯函数不触 UI 态；iOS :388-393 直译）。 */
        fun relayLinkArgument(from: List<String>): String? {
            val index = from.indexOf(RELAY_LINK_ARGUMENT)
            if (index < 0 || index + 1 >= from.size) return null
            val value = from[index + 1]
            return if (value.startsWith("-")) null else value
        }

        /** 短格式（iOS shortFormatter "M 月 d 日 HH:mm" 直译；SimpleDateFormat 非线程安全 → 每次新建）。 */
        internal fun shortFormat(epochMs: Long): String =
            SimpleDateFormat("M 月 d 日 HH:mm", Locale.CHINA).format(Date(epochMs))

        /**
         * ISO 8601 → epoch 毫秒（web Date.parse 同构；含/不含毫秒、'Z' 或 ±HH(:MM)? 时区）。
         * Android 无 Foundation ISO8601DateFormatter，手写归一 + SimpleDateFormat 承载。
         */
        internal fun parseIsoEpochMs(raw: String): Long? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            // 'Z' → +0000（SimpleDateFormat 'Z'（RFC 822）全 API 级可解析）
            if (text.endsWith("Z") || text.endsWith("z")) {
                text = text.substring(0, text.length - 1) + "+0000"
            }
            // ±HH:MM → ±HHMM（RFC 822 无冒号形态归一）
            text = text.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            // 毫秒位归一到 3 位（.123456 → .123；无小数秒走无毫秒 pattern）
            val hasMillis = Regex("\\.\\d").containsMatchIn(text)
            if (hasMillis) {
                text = text.replace(Regex("\\.(\\d{3})\\d+")) { match ->
                    "." + match.groupValues[1]
                }
            }
            val pattern = if (hasMillis) "yyyy-MM-dd'T'HH:mm:ss.SSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
            return try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(text)?.time
            } catch (e: ParseException) {
                null
            }
        }
    }
}

// MARK: - JSON 数值宽容读（文件级私有；JSONValue.Int 32 位值域——毫秒时间戳/issuedAt/
// revisionAtDecision 一律走 double/Long 路径，M1 残留 deviations 摘要口径）

/** 毫秒时间戳宽容读（越界整值 double 恒走此路径，防 Int32 静默回绕）。 */
private fun JSONValue?.millisValue(): Long? {
    val d = this?.doubleValue ?: return null
    if (d != kotlin.math.floor(d) || d.isInfinite()) return null
    return d.toLong()
}

/** 计数字段宽容读（同 32 位值域纪律；越界整值返回 null 不回绕）。 */
private fun JSONValue?.countValue(): Int? {
    val d = this?.doubleValue ?: return null
    if (d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
    if (d != kotlin.math.floor(d)) return null
    return d.toInt()
}

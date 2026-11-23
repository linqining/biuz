package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Stores/Remote/RemoteFileStore.swift（942 行）：
// file / git / file-watcher 频道 → FileStore 协议。
// - fileTree() ← file.readdir 递归（深度限制，跳过隐藏目录）+ file-watcher 失效
// - content(of:) ← file.readTextFile（offset/length 有界读，首屏 256KiB）
// - diffFiles() ← git.refresh 前置 + git.getChanges（unstaged/staged）+ git.getDiff
//   逐文件 patch（恒带 sourceId，web 同参；回执结构化 {availability, patch?, before/after}）
// - sessionDiffFiles() ← conversationFileChangesV4（会话维度变更，hunk 结构自解析）
// 逐文件批准（2026-10-06 git 写族放开）：setFileDecision/approveAll 落地为
// git.stagePaths/unstagePaths 桌面代执行（红线：批准=stagePaths、拒绝=unstagePaths）；
// 失败文案随返回值上屏（写面禁止静默）。**破坏性命令（discardPaths/push）网关虽放行
// 但本 Store 不接**（AGENTS §6：接入必须带确认弹层，UI 轮裁决）。
//
// 并发模型适配（docs/Android开发交接.md §2.2，与 RemoteTaskStore.kt 同口径）：
// Swift actor → Mutex 临界区；挂起点（RPC/delay）一律在锁外，锁内只做纯内存读写。
// 连接面适配：iOS 持 ZCodeServerConnection 强引用；Kotlin 以 protocol 预置的
// RPCChannelTransport 门面承接（call/listen 同签名，M1 残留 deviations 摘要——勿重复定义）；
// ReadOnlyGate 出口拦截由 :connection 装配层注入的 transport 实现（ZCodeServerConnection.call
// 唯一出口）。
//
// 生命周期适配：Swift deinit 的 unwatch/disposeAll 兜底清理在 Kotlin 无析构对应物——
// 显式 [dispose]（RemoteSessionsIndexStore.dispose 同款；Store 释放/断线 teardown 时调用）。

import cn.biuz.android.protocol.DiffFile
import cn.biuz.android.protocol.DiffLine
import cn.biuz.android.protocol.DiffLineKind
import cn.biuz.android.protocol.EventSubscription
import cn.biuz.android.protocol.FileNode
import cn.biuz.android.protocol.JSONValue
import cn.biuz.android.protocol.RPCChannelTransport
import cn.biuz.android.protocol.RPCError
import cn.biuz.android.protocol.RPCValue
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RemoteFileStore 对会话 Store 的依赖面（iOS 直引 RemoteConversationStore 同型方法：
 * conversationBaseWatermark :1998-2001 / conversationWorkspaceTarget :1992-1994；
 * 独立接口避免与 RemoteTaskStore.RemoteConversationBridge 耦合）。
 * [RemoteConversationStore] 已具备同签名实现，装配轮声明 implements 即接线。
 */
interface FileConversationAnchor {
    /** 会话当前 revision/logEpoch 水位（sessionDiffFiles 的 base*；解析不出回退 (0, "0")）。 */
    suspend fun conversationBaseWatermark(sessionId: String): Pair<Long, String>

    /** 会话归属 workspace 寻址（A7：path, identity?；null identity = 不携键）。 */
    suspend fun conversationWorkspaceTarget(sessionId: String): Pair<String, String?>
}

/** file.stat 只读投影（RemoteFileStore.swift FileStat :231-234）。 */
data class FileStat(
    val size: Int,
    val isDirectory: Boolean,
)

/**
 * 真实文件/Diff Store（FileStore 协议连接态实现；mappingToApp 第 5 条）。
 */
class RemoteFileStore(
    transport: RPCChannelTransport,
    workspace: StoreWorkspaceInfo,
    /** 会话归属寻址/水位解析（sessionDiffFiles 用；null = 会话维度变更不可用）。 */
    private val conversationAnchor: FileConversationAnchor? = null,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FileStore {

    private val transport: RPCChannelTransport = transport
    private val workspace: StoreWorkspaceInfo = workspace

    /** Swift actor 串行化的 Kotlin 等价物；锁内禁挂起（RPC/delay 一律在锁外）。 */
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private val localApprovals = HashSet<String>()
    private val localRejections = HashSet<String>()
    private var cachedTree: List<FileNode> = emptyList()

    // 读面三态支撑（2026-10-06 用户回归「点击后列表无提示清空」）：读失败不再折叠成
    // 空成功——last-good 兜底 + 失败信号供 UI 区分 加载/错误/空 三态。
    /** 文件树失效标记（onDynamicChange 置位；失败重拉保旧树，不再先清缓存再赌成功） */
    private var treeDirty = false
    /** 最近一次文件树拉取失败文案（null=成功） */
    private var treeFailure: String? = null
    /** getChanges/sessionDiffFiles 最近一次成功回执（按源缓存；失败时兜底返回，防列表闪空） */
    private val lastGoodChanges = HashMap<String, List<DiffFile>>()
    /** 最近一次变更拉取失败文案（键：unstaged/staged/session；null=成功） */
    private val changesFailure = HashMap<String, String?>()
    /** 最近一次 readTextFile 失败文案（null=成功；预览页空内容三态判定用） */
    private var textReadFailure: String? = null

    /** 诊断钩子（iOS UserDefaults diag.* 的 Kotlin 承载；非 null = diag 模式在开）。 */
    @Volatile
    var diagListener: ((key: String, value: String) -> Unit)? = null

    // MARK: file-watcher（文件树活性：修复 cachedTree 永不失效）

    private var watcherId: String? = null
    private var watcherStarted = false
    private var changeSubscription: EventSubscription? = null
    /** 变更通知流（iOS treeChangeContinuations 逐订阅者 yield 的 Kotlin 承载）。 */
    private val treeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    override val isRemote: Boolean
        get() = true

    /** 首屏有界读（file.readTextFile length 上限）：256KiB，超出走「加载更多」分页 */
    val firstPageSizeBytes: Int
        get() = COMPANION.firstPageSizeBytes

    // MARK: 文件树

    override suspend fun fileTree(): List<FileNode> {
        // 失效/空缓存才重拉；重拉失败保留旧树（last-good）并记失败信号——
        // 树任何时刻不得因瞬时 RPC 失败无提示变空（watcher 事件驱动的重拉随时会到）
        val needFetch = mutex.withLock { cachedTree.isEmpty() || treeDirty }
        if (needFetch) {
            val fresh = readDirectory(path = workspace.path, depth = 0)
            mutex.withLock {
                if (fresh != null) {
                    cachedTree = fresh
                    treeDirty = false
                    treeFailure = null
                } else if (cachedTree.isEmpty()) {
                    treeFailure = "文件树读取失败 · 请检查桌面端连接后重试"
                } else {
                    // 旧树继续展示（可能过期），失败文案供视图挂横幅；脏标记保留——
                    // 视图「重试」/下次事件到来时 fileTree() 会真正重拉
                    treeFailure = "文件树刷新失败 · 暂显示上次结果"
                }
            }
        }
        val nonEmpty = mutex.withLock { cachedTree.isNotEmpty() }
        if (nonEmpty) {
            startWatching()
        }
        return mutex.withLock { cachedTree }
    }

    /** 最近一次文件树拉取失败文案（null=成功；FileTreeView 三态判定用） */
    suspend fun treeLoadFailure(): String? = mutex.withLock { treeFailure }

    /**
     * file-watcher.watch（recursive）：首载成功后启动；onDynamicChange 失效缓存并通知视图重拉。
     * watch 失败静默（旧服务端无该 additive 面时文件树退回手动刷新）。
     */
    private suspend fun startWatching() {
        if (mutex.withLock { watcherStarted }) return
        val fields = LinkedHashMap<String, JSONValue>()
        fields["path"] = JSONValue.Str(workspace.path)
        fields["recursive"] = JSONValue.Bool(true)
        val handle: EventSubscription
        try {
            val reply = transport.call(
                "file-watcher", "watch", RPCValue.json(JSONValue.Obj(fields)),
            )
            val id = reply.jsonValue?.get("id")?.stringValue
            // onDynamicChange 按 watcherId 订阅（onDynamicChange(id) 签名）
            val eventFields = LinkedHashMap<String, JSONValue>()
            if (id != null) eventFields["id"] = JSONValue.Str(id)
            handle = transport.listen(
                "file-watcher", "onDynamicChange", RPCValue.json(JSONValue.Obj(eventFields)),
                handler = { _ ->
                    scope.launch { invalidateTree() }
                },
            )
            mutex.withLock {
                if (watcherStarted) {
                    handle.cancel() // 并发竞态：后来者让位并释放重复句柄
                } else {
                    watcherId = id
                    watcherStarted = true
                    changeSubscription = handle
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock { watcherStarted = false }
        }
    }

    /** 变更即失效：置脏标记（旧树保留兜底），下一次 fileTree() 重拉；同时通知视图即时刷新。 */
    private suspend fun invalidateTree() {
        mutex.withLock { treeDirty = true }
        treeChanges.emit(Unit)
    }

    override fun observeFileTreeChanges(): Flow<Unit> = treeChanges.asSharedFlow()

    /**
     * 递归读目录（3 层展示深度，跳过隐藏目录）。返回 null = RPC 失败（区别于真实空目录
     * 空列表，三态判定依赖此区分）；子目录读失败该子树记空但不拖垮整树。幂等读级：
     * 首败 1.2s 退避重试一次（中继瞬断纪律，AGENTS §5.8；generateCommitMessage 同款先例）。
     */
    private suspend fun readDirectory(path: String, depth: Int): List<FileNode>? {
        val arg = RPCValue.json(
            JSONValue.Obj(
                mapOf(
                    "path" to JSONValue.Str(path),
                    "includeHidden" to JSONValue.Bool(false),
                ),
            ),
        )
        for (attempt in 0..1) {
            try {
                val result = transport.call("file", "readdir", arg)
                val entries = result.jsonValue?.arrayValue ?: run {
                    // 结构不符按失败走重试/上报（原实现折叠成空目录成功，静默吞错）
                    if (attempt == 0) {
                        delay(RETRY_BACKOFF_MS)
                        continue
                    }
                    return null
                }
                val nodes = ArrayList<FileNode>()
                for (entry in entries) {
                    val dict = entry.objectValue ?: continue
                    val name = dict["name"]?.stringValue ?: continue
                    val entryPath = dict["path"]?.stringValue ?: continue
                    val isDirectory = dict["type"]?.stringValue == "directory"
                    var node = FileNode(
                        id = entryPath,
                        name = name,
                        path = entryPath,
                        isDirectory = isDirectory,
                        size = dict["size"].longValue()?.toInt(),
                        children = null,
                    )
                    if (isDirectory) {
                        // 子目录失败（null）降级为空目录，不拖垮整树拉取
                        val children = readDirectory(path = entryPath, depth = depth + 1)
                        node = if (children != null && children.isNotEmpty()) {
                            node.copy(children = children)
                        } else {
                            node.copy(children = emptyList())
                        }
                    }
                    nodes.add(node)
                }
                return nodes.sortedWith(
                    compareByDescending<FileNode> { it.isDirectory }
                        // iOS localizedStandardCompare（Finder 自然序）的 Kotlin 简化：忽略
                        // 大小写字典序（自然数字序为 UI 细节，偏差已登记）
                        .thenComparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt > 0) return null
                delay(RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    // MARK: 服务端搜索（file.searchWorkspaceFiles；host 有界候选）

    override suspend fun searchFiles(query: String, limit: Int): List<FileNode> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val arg = RPCValue.json(
            JSONValue.Obj(
                mapOf(
                    "rootPath" to JSONValue.Str(workspace.path),
                    "query" to JSONValue.Str(trimmed),
                    "limit" to JSONValue.Int(limit),
                ),
            ),
        )
        return try {
            val result = transport.call("file", "searchWorkspaceFiles", arg)
            val entries = result.jsonValue?.arrayValue ?: return emptyList()
            entries.mapNotNull { entry ->
                val dict = entry.objectValue ?: return@mapNotNull null
                val name = dict["name"]?.stringValue ?: return@mapNotNull null
                val path = dict["path"]?.stringValue ?: return@mapNotNull null
                FileNode(
                    id = path,
                    name = name,
                    path = path,
                    isDirectory = dict["type"]?.stringValue == "directory",
                    size = null,
                    children = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // MARK: 文件元信息（file.stat）

    suspend fun stat(path: String): FileStat? {
        val arg = RPCValue.json(JSONValue.Obj(mapOf("path" to JSONValue.Str(path))))
        return try {
            val result = transport.call("file", "stat", arg)
            val dict = result.jsonValue?.objectValue ?: return null
            // 回执：{path, type: "file"|"directory", size?, mtimeMs?}
            val isDirectory = dict["type"]?.stringValue == "directory"
            val size = dict["size"].longValue()?.toInt() ?: 0
            FileStat(size = size, isDirectory = isDirectory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // MARK: 文件内容（readTextFile 有界读：offset/length + totalBytes 截断判定）

    override suspend fun content(path: String): String {
        // 协议位：全量读语义保留给旧调用方；内部走分页实现（首屏 256KiB）
        return contentPage(path, 0, Int.MAX_VALUE).content
    }

    override suspend fun contentPage(path: String, offset: Int, length: Int): FileContentPage {
        // stat 前置守卫：超大文件先截断提示，避免静默拉全量（二进制/超大文件不静默空串）
        if (offset == 0) {
            val fileStat = stat(path)
            if (fileStat != null && !fileStat.isDirectory && fileStat.size > COMPANION.firstPageSizeBytes) {
                return readTextSlice(
                    path = path,
                    offset = 0,
                    length = COMPANION.firstPageSizeBytes,
                    knownTotal = fileStat.size,
                )
            }
        }
        return readTextSlice(path = path, offset = offset, length = length)
    }

    private suspend fun readTextSlice(path: String, offset: Int, length: Int, knownTotal: Int? = null): FileContentPage {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["path"] = JSONValue.Str(path)
        if (offset > 0) fields["offset"] = JSONValue.Int(offset)
        if (length != Int.MAX_VALUE) fields["length"] = JSONValue.Int(length)
        return try {
            val result = transport.call("file", "readTextFile", RPCValue.json(JSONValue.Obj(fields)))
            val dict = result.jsonValue?.objectValue ?: run {
                // 结构不符记失败信号（空串不再伪装成功空文件，预览页三态判定用）
                mutex.withLock { textReadFailure = "文件内容读取失败 · 回执结构不符" }
                return FileContentPage(content = "", totalBytes = knownTotal ?: 0, isTruncated = false)
            }
            val content = dict["content"]?.stringValue ?: ""
            // FileTextSlice：{path, content, offset, bytesRead, totalBytes}
            val totalBytes = dict["totalBytes"].longValue()?.toInt()
                ?: knownTotal
                ?: (offset + content.toByteArray(Charsets.UTF_8).size)
            val bytesRead = dict["bytesRead"].longValue()?.toInt()
                ?: content.toByteArray(Charsets.UTF_8).size
            val isTruncated = offset + bytesRead < totalBytes
            mutex.withLock { textReadFailure = null }
            FileContentPage(content = content, totalBytes = totalBytes, isTruncated = isTruncated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                textReadFailure = readFailureText(command = "file.readTextFile", error = e)
            }
            FileContentPage(content = "", totalBytes = knownTotal ?: 0, isTruncated = false)
        }
    }

    /** 最近一次 readTextFile 失败文案（null=成功；FilePreviewView 空内容三态判定用） */
    suspend fun textReadFailureMessage(): String? = mutex.withLock { textReadFailure }

    // MARK: 二进制预览（P2：file.readBinaryPreview 读族，gate 读白名单内）

    /**
     * 有界读二进制首块（图片/PDF 只读预览）。回执宽容：base64 字段（dataBase64/base64/data）
     * 或 bytes 数组；失败返回 null（UI 降级为说明文案，不虚构预览）。
     */
    override suspend fun binaryPreview(path: String, maxBytes: Int): ByteArray? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["path"] = JSONValue.Str(path)
        fields["offset"] = JSONValue.Int(0)
        fields["length"] = JSONValue.Int(maxBytes)
        return try {
            val result = transport.call("file", "readBinaryPreview", RPCValue.json(JSONValue.Obj(fields)))
            val dict = result.jsonValue?.objectValue ?: return null
            val base64 = dict["dataBase64"]?.stringValue
                ?: dict["base64"]?.stringValue
                ?: dict["data"]?.stringValue
            if (base64 != null) {
                return decodeBase64Lenient(base64)
            }
            dict["bytes"]?.arrayValue?.let { bytes ->
                return bytes.mapNotNull { it.longValue()?.toInt()?.toByte() }
                    .toByteArray()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // MARK: Diff（git 层：工作区变更，unstaged/staged 双维度 + refresh 前置）

    override suspend fun diffFiles(): List<DiffFile> = fetchChanges(sourceId = "unstaged")

    override suspend fun diffFiles(sourceId: String): List<DiffFile> = fetchChanges(sourceId = sourceId)

    /** git.refresh 前置（named gap：Diff 角标/Diff 页读到过期仓库状态）：失败静默降级维持现行为。 */
    private suspend fun refreshGit() {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        try {
            transport.call("git", "refresh", RPCValue.json(JSONValue.Obj(fields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS `_ = try?` 同口径
        }
    }

    /**
     * getChanges 有界拉取（prefix(20)）：sourceId ∈ unstaged | staged。
     * 幂等读级首败 1.2s 退避重试一次（AGENTS §5.8）；再败返回 last-good 并记失败信号
     * （loadFailure(sourceId:)），列表不得因瞬时失败无提示清空。
     */
    private suspend fun fetchChanges(sourceId: String): List<DiffFile> {
        val arg = RPCValue.json(
            JSONValue.Obj(
                mapOf(
                    "workspacePath" to JSONValue.Str(workspace.path),
                    "sourceId" to JSONValue.Str(sourceId),
                ),
            ),
        )
        for (attempt in 0..1) {
            try {
                refreshGit()
                val result = transport.call("git", "getChanges", arg)
                val changes = result.jsonValue?.arrayValue
                    ?: throw RPCError(message = "getChanges 回执非数组", name = "badReply")
                val files = ArrayList<DiffFile>()
                for (change in changes.take(20)) { // 移动端有界展示
                    val dict = change.objectValue ?: continue
                    val path = dict["path"]?.stringValue ?: continue
                    val displayPath = dict["repoRelativePath"]?.stringValue ?: path
                    var lines: List<DiffLine> = emptyList()
                    var added = dict["added"].longValue()?.toInt() ?: 0
                    var removed = dict["removed"].longValue()?.toInt() ?: 0
                    val patch = fetchPatch(path = path, sourceId = sourceId)
                    if (patch != null) {
                        lines = parsePatch(patch)
                        added = maxOf(added, lines.count { it.kind == DiffLineKind.add })
                        removed = maxOf(removed, lines.count { it.kind == DiffLineKind.del })
                    }
                    val approved: Boolean
                    val rejected: Boolean
                    mutex.withLock {
                        // 已暂存 = 桌面 git index 实态（stagePaths 落地结果）：批准标记以
                        // 桌面实态对账，Store 重建（重连/换工作区）丢内存 localApprovals
                        // 后「已批准」投影仍可恢复；未暂存维持本地批准回显
                        approved = sourceId == "staged" || localApprovals.contains(path)
                        rejected = localRejections.contains(path)
                    }
                    files.add(
                        DiffFile(
                            id = displayPath,
                            path = displayPath,
                            language = languageFor(displayPath),
                            added = added,
                            removed = removed,
                            lines = lines,
                            isApproved = approved,
                            isRejected = rejected,
                            // git 写面（stagePaths/unstagePaths）用服务端原始 path——web 同参
                            //（bundle：变更条目 stagePath=n.path；repoRelativePath 仅展示用）
                            stagePath = path,
                        ),
                    )
                }
                mutex.withLock {
                    lastGoodChanges[sourceId] = files
                    changesFailure[sourceId] = null
                }
                return files
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt > 0) {
                    mutex.withLock {
                        changesFailure[sourceId] = readFailureText(command = "git.getChanges", error = e)
                    }
                    return mutex.withLock { lastGoodChanges[sourceId] } ?: emptyList()
                }
                delay(RETRY_BACKOFF_MS)
            }
        }
        return mutex.withLock { lastGoodChanges[sourceId] } ?: emptyList()
    }

    /**
     * 最近一次变更拉取失败文案（键：unstaged/staged/session；null = 该源最近一次拉取成功；
     * DiffViewModel 三态判定用——空结果 + 本信号在场 = 瞬时失败而非「工作区干净」）
     */
    suspend fun loadFailure(sourceId: String): String? = mutex.withLock { changesFailure[sourceId] }

    /** 是否有超出有界展示的更多变更（fetchChanges 截断到 20 条的「更多」提示判定） */
    override suspend fun hasMoreChanges(sourceId: String): Boolean {
        val arg = RPCValue.json(
            JSONValue.Obj(
                mapOf(
                    "workspacePath" to JSONValue.Str(workspace.path),
                    "sourceId" to JSONValue.Str(sourceId),
                ),
            ),
        )
        return try {
            val result = transport.call("git", "getChanges", arg)
            val changes = result.jsonValue?.arrayValue ?: return false
            changes.size > 20
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // MARK: 会话维度文件变更（conversationFileChangesV4）

    /**
     * 本次会话变更：target={sessionId} + **会话归属 workspace 信封**（§11.3——
     * 此前缺信封，跨工作区会话在服务端按 undefined workspaceKey 不命中任何活跃
     * CLI；web 实调用恒 ...workspace，agentConversationTransport.ts:393-403）；
     * base* 取会话当前水位（web 同口径——web SessionPane.tsx:1631-1632 用当前
     * revision/logEpoch，非恒 0；解析不出回退 0/"0"）。
     * 回执 items[].patches 为 hunk 结构（oldStart/lines），非 unified 文本，单独转换。
     */
    override suspend fun sessionDiffFiles(sessionId: String): List<DiffFile> {
        val base = conversationAnchor?.conversationBaseWatermark(sessionId) ?: (0L to "0")
        val fields = LinkedHashMap<String, JSONValue>()
        fields["sessionId"] = JSONValue.Str(sessionId)
        fields["target"] = JSONValue.Obj(mapOf("sessionId" to JSONValue.Str(sessionId)))
        fields["baseRevision"] = JSONValue.fromAny(base.first) ?: JSONValue.Int(base.first.toInt())
        fields["baseLogEpoch"] = JSONValue.Str(base.second)
        conversationAnchor?.conversationWorkspaceTarget(sessionId)?.let { target ->
            fields["workspacePath"] = JSONValue.Str(target.first)
            target.second?.let { fields["workspaceIdentity"] = JSONValue.Str(it) }
        }
        val arg = RPCValue.json(JSONValue.Obj(fields))
        for (attempt in 0..1) {
            try {
                val result = transport.call("zcode-agent", "conversationFileChangesV4", arg)
                val dict = result.jsonValue?.objectValue
                    ?: throw RPCError(message = "conversationFileChangesV4 回执非对象", name = "badReply")
                val files = ArrayList<DiffFile>()
                var id = 0
                for (item in dict["items"]?.arrayValue ?: emptyList()) {
                    val itemDict = item.objectValue ?: continue
                    val path = itemDict["path"]?.stringValue ?: continue
                    val lines = ArrayList<DiffLine>()
                    var oldNumber = 0
                    var newNumber = 0
                    for (hunk in itemDict["patches"]?.arrayValue ?: emptyList()) {
                        val hunkDict = hunk.objectValue ?: continue
                        val hStart = hunkDict["oldStart"].longValue()?.toInt() ?: 0
                        val hLines = hunkDict["oldLines"].longValue()?.toInt() ?: 0
                        val nStart = hunkDict["newStart"].longValue()?.toInt() ?: 0
                        val nLines = hunkDict["newLines"].longValue()?.toInt() ?: 0
                        lines.add(
                            DiffLine(
                                id = "sl-$id",
                                kind = DiffLineKind.hunk,
                                oldNumber = null,
                                newNumber = null,
                                text = "@@ -$hStart,$hLines +$nStart,$nLines @@",
                            ),
                        )
                        id += 1
                        oldNumber = hStart
                        newNumber = nStart
                        for (raw in hunkDict["lines"]?.arrayValue ?: emptyList()) {
                            val text = raw.stringValue ?: continue
                            when {
                                text.startsWith("+") -> {
                                    lines.add(DiffLine("sl-$id", DiffLineKind.add, null, newNumber, text.drop(1)))
                                    newNumber += 1
                                }
                                text.startsWith("-") -> {
                                    lines.add(DiffLine("sl-$id", DiffLineKind.del, oldNumber, null, text.drop(1)))
                                    oldNumber += 1
                                }
                                else -> {
                                    lines.add(DiffLine("sl-$id", DiffLineKind.ctx, oldNumber, newNumber, text.drop(1)))
                                    oldNumber += 1
                                    newNumber += 1
                                }
                            }
                            id += 1
                        }
                    }
                    val added = itemDict["additions"].longValue()?.toInt()
                        ?: lines.count { it.kind == DiffLineKind.add }
                    val removed = itemDict["deletions"].longValue()?.toInt()
                        ?: lines.count { it.kind == DiffLineKind.del }
                    // 显示口径对齐 git.getChanges 路径（repoRelativePath/短名）：
                    // 完整路径作 id 会让文件卡 identifier 带绝对路径，与其余分段不一致
                    val displayPath = lastPathComponent(path)
                    val approved: Boolean
                    val rejected: Boolean
                    mutex.withLock {
                        approved = localApprovals.contains(path)
                        rejected = localRejections.contains(path)
                    }
                    files.add(
                        DiffFile(
                            id = displayPath,
                            path = displayPath,
                            language = languageFor(displayPath),
                            added = added,
                            removed = removed,
                            lines = lines,
                            isApproved = approved,
                            isRejected = rejected,
                            stagePath = path,
                        ),
                    )
                }
                mutex.withLock {
                    lastGoodChanges["session"] = files
                    changesFailure["session"] = null
                }
                return files
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt > 0) {
                    mutex.withLock {
                        changesFailure["session"] = readFailureText(command = "conversationFileChangesV4", error = e)
                    }
                    return mutex.withLock { lastGoodChanges["session"] } ?: emptyList()
                }
                delay(RETRY_BACKOFF_MS)
            }
        }
        return mutex.withLock { lastGoodChanges["session"] } ?: emptyList()
    }

    /**
     * git.getDiff：web 恒带 sourceId（bundle 取证 `getDiff({workspacePath, path, sourceId})`，
     * GitPane 按页签传 unstaged|staged）——staged 页签漏传会被桌面按默认源解析，patch 口径错位。
     */
    private suspend fun fetchPatch(path: String, sourceId: String): String? {
        val arg = RPCValue.json(
            JSONValue.Obj(
                mapOf(
                    "workspacePath" to JSONValue.Str(workspace.path),
                    "path" to JSONValue.Str(path),
                    "sourceId" to JSONValue.Str(sourceId),
                ),
            ),
        )
        return try {
            val result = transport.call("git", "getDiff", arg)
            val json = result.jsonValue
            val dict = json?.objectValue
                // 宽容：旧桌面顶层字符串形态
                ?: return json?.stringValue
            // web 结构化回执 {availability: "patch"|"unavailable", patch?, beforeContent?, afterContent?}
            dict["patch"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { return it }
            if (dict["availability"]?.stringValue == "unavailable") return null
            // patch 缺席但前后全文在场（web 形态）：合成整文件替换 diff（行内容为服务端原文）
            synthesizePatch(
                beforeContent = dict["beforeContent"]?.stringValue,
                afterContent = dict["afterContent"]?.stringValue,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // MARK: 逐文件批准（2026-10-06 边界放开：git 写族与 web bundle 对齐，桌面代执行
    // ——`git.stagePaths {workspacePath, paths}` / `git.unstagePaths {…}`，web 端同参同面）
    // path 形参 = DiffFile.stagePath（getChanges 原始 path，web stagePath 同源）；
    // 返回 null=成功，非 null=可直接上屏的错误文案（含服务端 fault reason）。

    override suspend fun setFileDecision(path: String, approved: Boolean?): String? {
        try {
            when (approved) {
                true -> {
                    // 批准 → stage：文件移入已暂存，「批准」落地为桌面 git 实态
                    callGitWrite("stagePaths", paths = listOf(path))?.let { return it }
                    mutex.withLock {
                        localApprovals.add(path)
                        localRejections.remove(path)
                    }
                }
                false -> {
                    // 拒绝 → unstage：仅退索引（安全可逆、幂等）；工作区改动不动
                    //（破坏性 discardPaths 不接）
                    callGitWrite("unstagePaths", paths = listOf(path))?.let { return it }
                    mutex.withLock {
                        localRejections.add(path)
                        localApprovals.remove(path)
                    }
                }
                null -> mutex.withLock {
                    localApprovals.remove(path)
                    localRejections.remove(path)
                }
            }
            return null
        } finally {
            gitDecisionDiag(path = path, approved = approved)
        }
    }

    /** 全部批准 = 未决文件一次性 stagePaths（web 提交前批量 stage 同款；一次 RPC） */
    override suspend fun approveAll(): String? {
        val files = diffFiles()
        val undecided = files.filter { !it.isApproved && !it.isRejected }
        if (undecided.isEmpty()) return null
        val targets = undecided.map { it.stagePath ?: it.path }
        try {
            callGitWrite("stagePaths", paths = targets)?.let { return it }
            mutex.withLock {
                for (file in undecided) {
                    val key = file.stagePath ?: file.path
                    localApprovals.add(key)
                    localRejections.remove(key)
                }
            }
            return null
        } finally {
            gitDecisionDiag(path = "approveAll(${targets.size})", approved = true)
        }
    }

    /**
     * git 写命令统一出口（ReadOnlyGate 已按 web 对齐放行）。
     * 返回 null=成功；非 null=含服务端 reason 的错误文案（诊断沿用 diag.git.write）。
     */
    private suspend fun callGitWrite(command: String, paths: List<String>): String? {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        fields["paths"] = JSONValue.Arr(paths.map { JSONValue.Str(it) })
        return try {
            transport.call("git", command, RPCValue.json(JSONValue.Obj(fields)))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagListener?.invoke(
                "diag.git.write",
                "git.$command paths=${paths.size} err=${e.toString().take(300)}",
            )
            writeFailureText(command = command, error = e)
        }
    }

    // MARK: 一站式提交（P3-8：git 写族 UI 化，gate 2026-10-06 与 web 对齐全放行）
    // 协议事实：git.generateCommitMessage / git.commit 命令名来自上游 gitService
    // 18 方法清单（立项报告 §8.1:578，git/git.ts:33-52）；参数与回执零记录【宽容】。

    /**
     * git.generateCommitMessage：参数按 web 形状（bundle 取证 GitActionMenu——
     * `generateCommitMessage({workspacePath, workspaceIdentity?, locale, includeUnstaged,
     * currentSessionFilePaths?, conversationContext?})`，选中集=includeUnstaged? staged+
     * unstaged : staged）。移动端从已暂存清单进入 → includeUnstaged 恒 false、所选文件
     * 经 currentSessionFilePaths 携带（协议形参 paths 仍为已暂存文件，仅载荷键名对齐）。
     * 回执 {providerId, model, message}（web 读法）；message|commitMessage|text 宽容兜底，
     * 空串视为失败。幂等读级：首败 1.2s 退避重试一次（中继瞬断纪律，AGENTS §5.8）。
     */
    override suspend fun generateCommitMessage(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        workspace.workspaceIdentity?.let { fields["workspaceIdentity"] = JSONValue.Str(it) }
        fields["locale"] = JSONValue.Str(java.util.Locale.getDefault().toString())
        fields["includeUnstaged"] = JSONValue.Bool(false)
        fields["currentSessionFilePaths"] = JSONValue.Arr(paths.map { JSONValue.Str(it) })
        for (attempt in 0..1) {
            try {
                val result = transport.call("git", "generateCommitMessage", RPCValue.json(JSONValue.Obj(fields)))
                val json = result.jsonValue
                val dict = json?.objectValue ?: run {
                    // 顶层字符串形态兜底
                    val text = json?.stringValue ?: result.stringValue ?: ""
                    return if (text.isEmpty()) null else text
                }
                val text = dict["message"]?.stringValue
                    ?: dict["commitMessage"]?.stringValue
                    ?: dict["text"]?.stringValue
                    ?: ""
                return if (text.isEmpty()) null else text

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt > 0) {
                    gitWriteDiag(command = "generateCommitMessage", detail = "files=${paths.size}", error = e)
                    return null
                }
                delay(RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    // MARK: 提交身份预检（git.getIdentity：email 配置缺失是桌面常见提交失败根因，
    // 手机端预检省一次来回）

    /**
     * git.getIdentity {workspacePath}（web GitBranchSwitcher 与 getChanges('staged')
     * Promise.allSettled 并发同面，bundle 取证【移植·bundle 逆向】；web 侧回执
     * fulfilled→identity 直入提交上下文、rejected→null 禁用提交按钮并提示
     * identityMissing）。回执字段名未在 web 消费点出现——name|userName /
     * email|userEmail 宽容链【未取证】；对象在场但字段空 = 身份未配置（isComplete
     * 判定），调用方如实警示。幂等读级：首败 1.2s 退避重试一次（AGENTS §5.8）。
     */
    override suspend fun gitIdentity(): GitIdentityInfo? {
        val arg = RPCValue.json(JSONValue.Obj(mapOf("workspacePath" to JSONValue.Str(workspace.path))))
        for (attempt in 0..1) {
            try {
                val result = transport.call("git", "getIdentity", arg)
                val dict = result.jsonValue?.objectValue ?: return null
                return GitIdentityInfo(
                    name = dict["name"]?.stringValue ?: dict["userName"]?.stringValue,
                    email = dict["email"]?.stringValue ?: dict["userEmail"]?.stringValue,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt > 0) {
                    gitWriteDiag(command = "getIdentity", detail = "read", error = e)
                    return null
                }
                delay(RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    /**
     * git.commit {workspacePath, message}：提交已暂存变更（提交集由桌面端 git
     * index 定义——stagePaths 的结果；不传 paths，web BranchSwitcher 双参同形；
     * GitActionMenu 的 paths/stagedOnly 三参为「含未暂存选中集」场景，移动端不触）。
     * 成功判定以回执为准（2026-10-06 用户报障「假提交」修复）：取到非空
     * commitHash|hash|oid|id 才算成功——回执非对象、hash 缺席、空提交
     * （nothing to commit）一律按失败渲染明确原因，绝不报「已提交」。
     * web 端以 RPC reject 判成败且不读 hash（bundle GitActionMenu/GitBranchSwitcher
     * try/catch 取证）——移动端加严为 hash 判定：假成功比误报失败危害大。
     * 失败 hash=null + errorMessage 必带原因（写面禁止静默）；写命令不自动重试
     * （防双重提交）。成功即同步清空本地 staged 投影（乐观更新，与 UI loading
     * 停止同一渲染帧；桌面实态由调用方提交后重拉 getChanges 对账）。
     */
    override suspend fun commit(message: String): GitCommitOutcome {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        fields["message"] = JSONValue.Str(message)
        return try {
            val result = transport.call("git", "commit", RPCValue.json(JSONValue.Obj(fields)))
            val dict = result.jsonValue?.objectValue
                ?: return GitCommitOutcome(
                    hash = null,
                    errorMessage = "桌面端回执无提交结果 · 提交未生效（可能暂存区为空）",
                )
            val rawHash = dict["commitHash"]?.stringValue
                ?: dict["hash"]?.stringValue
                ?: dict["oid"]?.stringValue
                ?: dict["id"]?.stringValue
                ?: ""
            if (rawHash.isEmpty()) {
                return GitCommitOutcome(
                    hash = null,
                    errorMessage = "桌面端未返回提交标识 · 提交未生效（可能没有可提交的变更）",
                )
            }
            // 乐观更新：提交集=桌面整个 index → staged 投影整体清空（键级整体替换）；
            // 后续 diffFiles("staged") 在重拉成功/失败兜底时均不再回出已提交文件
            mutex.withLock {
                lastGoodChanges["staged"] = emptyList()
                changesFailure["staged"] = null
            }
            GitCommitOutcome(hash = rawHash.take(8), errorMessage = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            gitWriteDiag(command = "commit", detail = "msg=${message.take(40)}", error = e)
            GitCommitOutcome(hash = null, errorMessage = writeFailureText(command = "commit", error = e))
        }
    }

    // MARK: 检查点（P3-11：git-checkpoint 频道，gate 已按前置裁决放行
    // create/restore；命令名来自上游 gitCheckpointService 四方法清单——立项报告
    // §8.1:579，git/gitCheckpoint.ts:14-17。参数与回执零记录【宽容】）
    // ⚠️ HIDDEN(对齐修复 H3)：web bundle 三方法 0 命中且协议文档零实证条目（审查报告 §五），
    // UI 入口已在 iOS FileTreeView 注释隐藏；本节发送方法保留编译，真机取证立条后恢复入口。

    /** git-checkpoint.diffCheckpoints {workspacePath}（读面）：检查点清单，按时间倒序。
     * 回执宽容：checkpoints[]|items[]|顶层数组。幂等读级：首败 1.2s 退避重试一次。 */
    override suspend fun checkpoints(): List<CheckpointInfo>? {
        val arg = RPCValue.json(JSONValue.Obj(mapOf("workspacePath" to JSONValue.Str(workspace.path))))
        for (attempt in 0..1) {
            try {
                val result = transport.call("git-checkpoint", "diffCheckpoints", arg)
                val json = result.jsonValue
                val items = json?.get("checkpoints")?.arrayValue
                    ?: json?.get("items")?.arrayValue
                    ?: json?.arrayValue
                    ?: emptyList()
                return items.mapNotNull { parseCheckpoint(it) }
                    .sortedByDescending { it.date ?: Long.MIN_VALUE }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt > 0) {
                    gitWriteDiag(command = "diffCheckpoints", detail = "read", error = e)
                    return null
                }
                delay(RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    /** git-checkpoint.createCheckpoint {workspacePath, message?}：创建检查点
     * （桌面代执行快照写；note 缺省时键省略）。写命令不自动重试。 */
    override suspend fun createCheckpoint(note: String?): Boolean {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        note?.let { fields["message"] = JSONValue.Str(it) }
        return try {
            transport.call("git-checkpoint", "createCheckpoint", RPCValue.json(JSONValue.Obj(fields)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            gitWriteDiag(
                command = "createCheckpoint",
                detail = "note=${note?.take(40) ?: ""}",
                error = e,
            )
            false
        }
    }

    /**
     * git-checkpoint.restoreBetweenCheckpoints：恢复工作区到检查点（破坏性——
     * UI 侧硬要求 destructive 确认弹层后才可达此处）。参数未取证，按最可能单参
     * 形态携 checkpointId（别名形态见 docEntries【宽容】注）。成功后失效文件树
     * 缓存（工作区文件已回退）。
     */
    override suspend fun restoreCheckpoint(checkpoint: CheckpointInfo): Boolean {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["workspacePath"] = JSONValue.Str(workspace.path)
        fields["checkpointId"] = JSONValue.Str(checkpoint.id)
        return try {
            transport.call("git-checkpoint", "restoreBetweenCheckpoints", RPCValue.json(JSONValue.Obj(fields)))
            invalidateTree()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            gitWriteDiag(command = "restoreBetweenCheckpoints", detail = "id=${checkpoint.id}", error = e)
            false
        }
    }

    // MARK: 释放（iOS deinit 的 Kotlin 显式对应物）

    /**
     * Store 释放/断线 teardown：unwatch 当前 watcher + disposeAll 兜底，
     * 随后取消事件订阅（EventSubscription cancel 链发 EventDispose）。
     * Swift deinit → Kotlin 无析构，装配层必须在 Store 释放时显式调用。
     */
    suspend fun dispose() {
        val (handle, identifier) = mutex.withLock {
            val h = changeSubscription
            changeSubscription = null
            watcherStarted = false
            h to watcherId
        }
        if (identifier != null) {
            val fields = LinkedHashMap<String, JSONValue>()
            fields["id"] = JSONValue.Str(identifier)
            try {
                transport.call("file-watcher", "unwatch", RPCValue.json(JSONValue.Obj(fields)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // iOS `try?` 同口径：teardown 尽力收口
            }
        }
        try {
            transport.call("file-watcher", "disposeAll", RPCValue.Undefined)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 同上
        }
        handle?.cancel()
    }

    // MARK: 纯函数（companion；单测锁定面）

    companion object COMPANION {
        /** 首屏有界读（file.readTextFile length 上限）：256KiB */
        const val firstPageSizeBytes: Int = 256 * 1024

        /** 幂等读级首败退避（中继瞬断纪律，AGENTS §5.8）：1.2s */
        private const val RETRY_BACKOFF_MS: Long = 1_200L

        /** before/after 全文 → 伪 unified diff（整文件替换）。仅用于 getDiff 结构化回执中
         * patch 键缺席的宽容回退：行内容均来自服务端回执，不虚构任何行；缺少最小化
         * hunk 切分（web 端此形态走前后双栏 diff 视图，移动端无等价组件，降级整读展示）。 */
        fun synthesizePatch(beforeContent: String?, afterContent: String?): String? {
            if (beforeContent == null && afterContent == null) return null
            val beforeLines = (beforeContent ?: "").split("\n")
            val afterLines = (afterContent ?: "").split("\n")
            val out = ArrayList<String>(beforeLines.size + afterLines.size + 1)
            out.add("@@ -1,${beforeLines.size} +1,${afterLines.size} @@")
            out.addAll(beforeLines.map { "-$it" })
            out.addAll(afterLines.map { "+$it" })
            return out.joinToString("\n")
        }

        /** unified diff 文本 → DiffLine（kind 按行首 +/-/@@ 前缀映射） */
        fun parsePatch(patch: String): List<DiffLine> {
            val lines = ArrayList<DiffLine>()
            var oldNumber = 0
            var newNumber = 0
            var id = 0
            for (raw in patch.split("\n")) {
                when {
                    raw.startsWith("@@") -> {
                        // @@ -a,b +c,d @@
                        val numbers = raw.split(" ").mapNotNull { part ->
                            when {
                                part.startsWith("-") -> part.drop(1).split(",").firstOrNull()?.toIntOrNull()
                                part.startsWith("+") -> part.drop(1).split(",").firstOrNull()?.toIntOrNull()
                                else -> null
                            }
                        }
                        oldNumber = numbers.getOrElse(0) { 0 }
                        newNumber = numbers.getOrElse(1) { 0 }
                        lines.add(DiffLine(id = "dl-$id", kind = DiffLineKind.hunk, oldNumber = null, newNumber = null, text = raw))
                        id += 1
                    }
                    raw.startsWith("+") -> {
                        lines.add(DiffLine(id = "dl-$id", kind = DiffLineKind.add, oldNumber = null, newNumber = newNumber, text = raw.drop(1)))
                        newNumber += 1
                        id += 1
                    }
                    raw.startsWith("-") -> {
                        lines.add(DiffLine(id = "dl-$id", kind = DiffLineKind.del, oldNumber = oldNumber, newNumber = null, text = raw.drop(1)))
                        oldNumber += 1
                        id += 1
                    }
                    raw.startsWith("+++") || raw.startsWith("---") ||
                        raw.startsWith("diff ") || raw.startsWith("index ") -> Unit
                    else -> {
                        lines.add(DiffLine(id = "dl-$id", kind = DiffLineKind.ctx, oldNumber = oldNumber, newNumber = newNumber, text = raw))
                        oldNumber += 1
                        newNumber += 1
                        id += 1
                    }
                }
            }
            return lines
        }

        fun languageFor(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "swift" -> "swift"
                "ts", "tsx", "js", "jsx", "mjs" -> "typescript"
                "py" -> "python"
                "md" -> "markdown"
                "json", "yml", "yaml" -> "json"
                "html" -> "html"
                "css", "scss" -> "css"
                "sh", "zsh" -> "bash"
                "" -> "text"
                else -> ext
            }
        }

        /** 检查点行宽容解析：id ← id|checkpointId|hash|revision；时间 ← timestamp|
         * createdAt|time|date（毫秒优先，<1e11 视为秒级兜底；ISO 字符串次之）；
         * 说明 ← message|label|description|note。id 与时间全缺的行丢弃。 */
        fun parseCheckpoint(json: JSONValue?): CheckpointInfo? {
            val dict = json?.objectValue ?: return null
            val id = dict["id"]?.stringValue
                ?: dict["checkpointId"]?.stringValue
                ?: dict["hash"]?.stringValue
                ?: dict["revision"]?.stringValue
            val date = parseCheckpointDate(dict)
            if (id == null && date == null) return null
            val label = dict["message"]?.stringValue
                ?: dict["label"]?.stringValue
                ?: dict["description"]?.stringValue
                ?: dict["note"]?.stringValue
            return CheckpointInfo(id = id ?: "", date = date, label = label)
        }

        /** 时间字段双形态解析（lastActivityAt 同口径：真实桌面推毫秒，ISO 字符串兜底）。epoch 毫秒。 */
        private fun parseCheckpointDate(dict: Map<String, JSONValue>): Long? {
            for (key in listOf("timestamp", "createdAt", "time", "date")) {
                val value = dict[key] ?: continue
                val ms = value.longValue() ?: 0L
                if (ms > 0) {
                    // iOS：>1e11 毫秒，否则秒（<1e11）
                    return if (ms > 100_000_000_000L) ms else ms * 1000
                }
                val iso = value.stringValue
                if (iso != null) {
                    parseIsoToMillis(iso)?.let { return it }
                }
            }
            return null
        }

        /** ISO-8601 → epoch 毫秒（带/不带小数秒；RemoteSessionMerge.parseIsoMillis 同构）。 */
        private fun parseIsoToMillis(iso: String): Long? = try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }

        /** RPC 失败 → 可上屏文案：服务端 fault message 优先（RPCError.message 原文，
         * 非 toString 加工串——iOS localizedDescription 同坑：通用串丢真实信息）。 */
        private fun writeFailureText(command: String, error: Throwable): String =
            "桌面端执行 git.$command 失败 · ${readFailureReason(error)}"

        /** 读面失败 → 可上屏文案（writeFailureText 同构；「读取」口径） */
        private fun readFailureText(command: String, error: Throwable): String =
            "桌面端读取 $command 失败 · ${readFailureReason(error)}"

        private fun readFailureReason(error: Throwable): String {
            if (error is RPCError) {
                return error.message.ifEmpty { error.name }
            }
            return error.message ?: error.toString()
        }
    }

    /** base64 宽容解码（MimeDecoder 容忍换行/空白；附件链路分块各自带 padding 的整串兜底口径）。 */
    private fun decodeBase64Lenient(base64: String): ByteArray? = try {
        java.util.Base64.getMimeDecoder().decode(base64)
    } catch (e: Exception) {
        null
    }

    /** git/git-checkpoint 命令失败取证（diagListener 接线才写；通道沿用 diag.git.write） */
    private fun gitWriteDiag(command: String, detail: String, error: Throwable) {
        diagListener?.invoke(
            "diag.git.write",
            "git.$command $detail err=${error.toString().take(300)}",
        )
    }

    private fun gitDecisionDiag(path: String, approved: Boolean?) {
        diagListener?.invoke("diag.git.decision", "path=$path approved=$approved")
    }
}

/** NSString.lastPathComponent 对应物（POSIX 路径分隔）。 */
internal fun lastPathComponent(path: String): String =
    path.trimEnd('/').substringAfterLast('/', missingDelimiterValue = path.trimEnd('/'))

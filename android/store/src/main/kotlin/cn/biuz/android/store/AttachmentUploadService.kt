package cn.biuz.android.store

// 直译自 ios/ZCodeMobileApp/Sources/Services/Attachments/AttachmentUploadService.swift
// （329 行，2026-10-10 附件链路移植轮）。行号指位皆为 iOS 原文行号。
//
// 机械映射（docs/Android开发交接.md §2.2 同口径，与本包其余文件一致）：
// - Swift @MainActor @Observable → 纯内存态 + synchronized(lock) 临界区（锁内零挂起）+
//   私有 CoroutineScope 承载异步派发（iOS Task { } 同位；hint 4s 自动清除任务同构）；
//   UI 绑定（@Observable items）挂 :app Compose 轮——本类只承载状态机与事务逻辑，
//   进度经 [onProgress] 回调 + [itemsSnapshot]/[hint] 快照读面透出；
// - String(localized:) → 同串字面量（:store 无资源目录；xcstrings 落 :app i18n 轮）；
// - UUID().uuidString（大写连字符）→ UUID.randomUUID()（小写连字符）——两者都满足
//   uploadId schema `^[A-Za-z0-9][A-Za-z0-9._:-]*$`、≤128（transport.ts:840-843）；
// - UTType(filenameExtension)（UniformTypeIdentifiers）→ 内置后缀表（纯 JVM 无该框架；
//   常见图/视/文档后缀直映，兜底 application/octet-stream——平台差异，deviation 登记）；
// - SHA256（CryptoKit）→ java.security.MessageDigest("SHA-256")，同为小写 hex。
//
// 红线对照（A-4/A11/甲-2，逐条落点见各成员 KDoc）：
// - 事务三步 begin(sha256+totalChunks) → chunk(**chunkIndex 块序号，非字节 offset**)
//   → commit；失败路径 AbortV4 尽力收口；
// - 客户端参数恒无 connectionId（服务端注入，多发即 Invalid params——RemoteConversationStore
//   四方法侧已按 web 客户端键面构造）；
// - 384KiB 分块、20MB 上限、64 块上限、每块独立 base64 自带 padding；
// - 失败如实回传 errorText/hint（不静默吞错，§5-12；abort 超时「中止指令未送达」
//   如实上屏——桌面繁忙期 30s 一次性超时是单管道负载现象，戊-3）。

import java.util.Base64
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: - 待发附件（P1-1 设计稿 §1.4 状态矩阵的 UI 态模型；iOS :6-46）

/**
 * 单个待发附件的完整生命周期：pending → uploading → committed / failed。
 * 失败保留 uploadId（Abort 未送达时重试按服务端已收块数续传，Begin 回执
 * staging.nextChunkIndex 为权威起点——web PCe 循环同款）；Abort 已送达则
 * uploadId 置 nil，重试重开新事务。
 */
class PendingAttachment(
    val name: String,
    /** image/… | video/… | application/pdf | application/octet-stream（前缀族；「斜杠星号」在 Kotlin 注释内开嵌套层，此处改写——StoreProtocols.kt:64 同坑先例） */
    val mediaType: String,
    val data: ByteArray,
) {
    enum class State { pending, uploading, committed, failed }

    val id: UUID = UUID.randomUUID()

    var state: State = State.pending

    /** 已送达字节数（nextChunkIndex×384KB 截断到 totalBytes；UI 进度条数据源）。 */
    var uploadedBytes: Int = 0

    /** 上传事务 id（`upload-<uuid>`，web MCe 同构；null = 未开启/已中止待重开）。 */
    var uploadId: String? = null

    /** 最终附件引用（Commit 回执 ref / Begin committed 短路 ref；随 sendText 携带）。 */
    var ref: String? = null

    var errorText: String? = null

    val totalBytes: Int get() = data.size

    val progress: Double
        get() = if (totalBytes > 0) uploadedBytes.toDouble() / totalBytes.toDouble() else 0.0

    val percentText: String get() = "${(progress * 100).roundToInt()}%"

    val isImage: Boolean get() = mediaType.startsWith("image/")

    val isVideo: Boolean get() = mediaType.startsWith("video/")
}

/** 上传链路本地错误（errorText 直出 UI 失败原因行；iOS :325-329 AttachmentUploadError）。 */
class AttachmentUploadError(val text: String) : Exception(text)

/**
 * 附件上传服务（A-4 对齐 web：attachmentBeginV4 → ChunkV4×N → CommitV4；iOS :48-323）。
 *
 * 全部命令经 ConversationStore 统一面（四条 channel RPC，远端实现内注入 connectionId
 * 与 1.2s 首败退避重试；本服务不直接触碰连接层）。事务口径【实证·上游仓
 * attachmentUploadTransaction.ts】：384KB/块（ATTACHMENT_UPLOAD_CHUNK_BYTES=384*1024）、
 * uploadId=`upload-<uuid>`、checksum="sha256:"+64hex、进度判定=回执 nextChunkIndex 恒等于
 * chunkIndex+1、Begin 回执 state 判别联合（staging 续传 | committed 直接拿 ref 短路）、
 * Begin 成功后的失败路径发 AbortV4。
 * 纪律：每块独立 base64 编码自带 padding（AGENTS §5-8 附件链路实证）、顺序上传、
 * 失败如实回传 errorText（不静默吞错）。
 *
 * @param store 会话 store（四方法真实面 = [RemoteConversationStore]，演示态默认实现
 *   如实失败；iOS ConversationStore 协议同位）
 * @param sessionID 目标会话 id
 * @param backgroundDispatcher 异步派发器（iOS MainActor Task { } 的对位；测试注入）
 */
class AttachmentUploadService(
    private val store: ConversationStore,
    private val sessionID: String,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    companion object {
        /** 分块大小（web hy=384*1024【实证·上游仓 attachmentUploadTransaction.ts:15】）。 */
        const val CHUNK_SIZE = 384 * 1024

        /**
         * 单附件上限：attachmentMaxBytes=20MB【实证·上游仓 zcode-protocol-v4/core.ts:84
         * PROTOCOL_V4_LIMITS】——旧 4MB 系 bundle 外保守口径（C-16），相机原图普遍
         * 4-6MB 全被误拦（用户报障「附件不能上传」表象之一）；64 块上限（24MB）不先绑定。
         */
        const val MAX_BYTES = 20 * 1024 * 1024

        /**
         * 待发条数上限（上游 MAX_CHAT_ATTACHMENTS=8——无上限时一次多选大文件可击穿
         * 服务端 staged 64MB 预算〔fault.attachment.stagingCapacityExceeded，
         * core.ts:97 attachmentUploadMaxStagedBytes〕且全量数据常驻内存）。
         */
        const val MAX_ITEMS = 8

        /** hint 自动清除窗（iOS :145 Task.sleep 4s）。 */
        private const val HINT_CLEAR_MS = 4_000L
    }

    private val lock = Any()
    private val itemsInternal = ArrayList<PendingAttachment>()
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    /** 超限/来源读取类提示（设计稿 1.3⑤ 文案；短暂展示后自动清除）（iOS :77）。 */
    @Volatile
    var hint: String? = null
        private set

    /** 进度回调（每块送达后触发；UI 主要经快照读面绑定，回调供诊断/埋点）（iOS :79）。 */
    @Volatile
    var onProgress: ((PendingAttachment) -> Unit)? = null

    private var hintClearJob: Job? = null

    // MARK: 投影（iOS :89-94）

    val isEmpty: Boolean get() = synchronized(lock) { itemsInternal.isEmpty() }

    val totalCount: Int get() = synchronized(lock) { itemsInternal.size }

    /** 有待发附件且有未 committed 项：sendButton 禁用（设计稿 1.3③/1.4）。 */
    val blocksSend: Boolean
        get() = synchronized(lock) { itemsInternal.any { it.state != PendingAttachment.State.committed } }

    val firstFailureText: String?
        get() = synchronized(lock) {
            itemsInternal.firstOrNull { it.state == PendingAttachment.State.failed }?.errorText
        }

    val firstFailureIndex: Int?
        get() = synchronized(lock) {
            itemsInternal.indexOfFirst { it.state == PendingAttachment.State.failed }
                .takeIf { it >= 0 }
        }

    /** 待发条快照读面（UI/诊断；iOS @Observable items 绑定的对位）。 */
    fun itemsSnapshot(): List<PendingAttachment> = synchronized(lock) { itemsInternal.toList() }

    // MARK: 待发条管理（iOS :96-116）

    /**
     * 入队（来源三通道共用）；超限不发起事务并给提示，返回是否入队。
     * 纯内存操作（iOS @MainActor 同步段）——非 suspend，锁用 synchronized。
     */
    fun add(name: String, mediaType: String, data: ByteArray): Boolean {
        val trimmedName = name.ifEmpty { "未命名附件" }
        if (data.isEmpty()) {
            setHint("《$trimmedName》内容为空，已跳过")
            return false
        }
        if (data.size > MAX_BYTES) {
            setHint("桌面端通道单附件上限 20MB，已跳过《$trimmedName》")
            return false
        }
        synchronized(lock) {
            if (itemsInternal.size >= MAX_ITEMS) {
                setHint("最多同时携带 $MAX_ITEMS 个附件，已跳过《$trimmedName》")
                return false
            }
            itemsInternal.add(PendingAttachment(name = trimmedName, mediaType = mediaType, data = data))
        }
        return true
    }

    /**
     * 移除（iOS :118-132）：未 committed 且已开启事务的尽力 AbortV4 收口——A-4 对齐 web；
     * Abort 未送达仅提示（「中止指令未送达 · 原文」如实上屏，戊-3），孤儿事务由桌面端
     * 超时回收（attachmentUploadTtlMs=5min，core.ts:98）。
     */
    fun remove(id: UUID) {
        val item = synchronized(lock) {
            val index = itemsInternal.indexOfFirst { it.id == id }
            if (index < 0) return
            itemsInternal.removeAt(index)
        }
        if (item.state == PendingAttachment.State.committed) return
        val uploadId = item.uploadId ?: return
        scope.launch {
            store.attachmentAbortV4(sessionId = sessionID, uploadId = uploadId)
                .onFailure { reason ->
                    setHint("附件上传中止指令未送达 · ${attachmentRpcText(reason)}")
                }
        }
    }

    /**
     * 失败重试（iOS :134-139）：同 uploadId 重发 Begin——桌面按已收块数回报
     * staging.nextChunkIndex，从该点续传（Abort 已送达的事务 uploadId 已置 nil，重开新事务）。
     */
    fun retry(id: UUID) {
        val item = synchronized(lock) {
            itemsInternal.firstOrNull { it.id == id && it.state == PendingAttachment.State.failed }
        } ?: return
        scope.launch { upload(item) }
    }

    /** 提示（iOS :141-149）：4s 自动清除；新提示取消旧清除任务。 */
    fun setHint(text: String) {
        synchronized(lock) {
            hintClearJob?.cancel()
        }
        hint = text
        hintClearJob = scope.launch {
            delay(HINT_CLEAR_MS)
            hint = null
        }
    }

    // MARK: 发送联动（设计稿 1.3③：全部 committed 后随下一次发送携带；iOS :151-190）

    /**
     * 发送前收口：未完成项顺序补传（顺序上传=并发 1，上游 COMPOSER_ATTACHMENT_
     * UPLOAD_CONCURRENCY=2 内——core.ts:96）。返回 false = 仍有失败项（本次不发送，
     * 保留失败态由用户重试/移除——直发文本会丢附件）。
     */
    suspend fun ensureAllCommitted(): Boolean {
        if (isEmpty) return true
        val pending = synchronized(lock) {
            itemsInternal.filter { it.state != PendingAttachment.State.committed }
        }
        for (item in pending) {
            upload(item)
        }
        return synchronized(lock) { itemsInternal.all { it.state == PendingAttachment.State.committed } }
    }

    /**
     * 已 committed 引用快照（**不移除**——发送失败保留重发的数据源；iOS :164-174）。
     * §11.1#6：上游语义「成功才清空、失败保留为可重试草稿」（ConversationComposer.tsx
     * adoptSentAttachments→clear）——投递结果前清空会让中继断线/拒收时用户已上传的
     * 附件静默消失且桌面侧 artifact 不可复用。
     */
    fun committedAttachments(): List<OutgoingAttachment> =
        synchronized(lock) {
            itemsInternal
                .filter { it.state == PendingAttachment.State.committed }
                .map { item ->
                    // sendText 附件元素键恒 `{ref, fileName, mime, bytes}`（B-1 web wire 同形，
                    // 禁 {name,mediaType,size} 甲-4）
                    OutgoingAttachment(
                        ref = item.ref ?: "",
                        fileName = item.name,
                        mime = item.mediaType,
                        bytes = item.data.size,
                    )
                }
        }

    /** 发送成功后移除已随消息下发的 committed 附件（与 peek 配对；失败路径不调用）（iOS :176-181）。 */
    fun clearCommitted(attachments: List<OutgoingAttachment>) {
        val sentRefs = attachments.map { it.ref }.toHashSet()
        synchronized(lock) {
            itemsInternal.removeAll { item ->
                item.state == PendingAttachment.State.committed && item.ref != null && item.ref in sentRefs
            }
        }
        if (isEmpty) {
            hint = null
        }
    }

    /** 发送受理后取全部 committed 引用并清空待发条（附件随消息已下发，不再重传）（iOS :183-190）。 */
    @Deprecated(
        message = "改用 committedAttachments() + 成功后 clearCommitted(attachments)——失败保留语义",
    )
    fun takeCommitted(): List<OutgoingAttachment> {
        val refs = committedAttachments()
        synchronized(lock) { itemsInternal.clear() }
        hint = null
        return refs
    }

    // MARK: 上传事务（A-4：BeginV4 → ChunkV4×N → CommitV4；失败 AbortV4；iOS :192-283）

    private suspend fun upload(item: PendingAttachment) {
        if (item.state == PendingAttachment.State.uploading ||
            item.state == PendingAttachment.State.committed
        ) {
            return
        }
        item.state = PendingAttachment.State.uploading
        item.errorText = null
        try {
            item.ref = runTransaction(item)
            item.uploadedBytes = item.data.size
            item.state = PendingAttachment.State.committed
            onProgress?.invoke(item)
        } catch (e: CancellationException) {
            // 取消非失败（iOS 无对应分支——Kotlin 结构化取消显式让位）
            item.state = PendingAttachment.State.failed
            item.errorText = "附件上传已取消"
            throw e
        } catch (e: Exception) {
            // 失败保留 uploadId/进度游标（Abort 未送达的事务可续传）；文案直出失败行
            //（iOS :203-207 `catch` 全捕同口径）
            item.state = PendingAttachment.State.failed
            item.errorText = (e as? AttachmentUploadError)?.text ?: e.message ?: e.toString()
        }
    }

    /**
     * 完整事务（iOS :213-283 直译）。Begin 回执 committed（checksum 命中已上传附件）直接
     * 短路拿 ref；staging 以 nextChunkIndex 为起点逐块顺序上传，进度判定=回执恒等于
     * index+1；Begin 成功后的任何失败（含进度越界/错位）发 AbortV4 收口后上抛。
     */
    private suspend fun runTransaction(item: PendingAttachment): String {
        if (item.uploadId == null) item.uploadId = makeUploadId()
        val uploadId = item.uploadId ?: makeUploadId()
        val totalBytes = item.data.size
        val totalChunks = totalChunksFor(totalBytes)
        val begin = store.attachmentBeginV4(
            sessionId = sessionID,
            uploadId = uploadId,
            fileName = item.name,
            mime = item.mediaType,
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            checksum = sha256Checksum(item.data),
        )
        val resumeFrom: Int
        when (val outcome = begin.fold(
            onSuccess = { it },
            onFailure = { error ->
                // Begin 未成功：无事务可中止（web u=false 不发 Abort 同款），如实上抛
                throw AttachmentUploadError("附件上传开启失败 · ${attachmentRpcText(error)}")
            },
        )) {
            is AttachmentBeginOutcome.Committed -> {
                // checksum 命中桌面已有附件：免分块直取引用（web PCe committed 短路）
                item.uploadedBytes = totalBytes
                onProgress?.invoke(item)
                return outcome.ref
            }
            is AttachmentBeginOutcome.Staging -> {
                if (outcome.nextChunkIndex > totalChunks) {
                    // 服务端进度越界 = 事务状态不可信（web fault.attachment.invalidServerProgress）
                    abortTransaction(item, uploadId)
                    throw AttachmentUploadError("桌面端上传进度异常（fault.attachment.invalidServerProgress）")
                }
                resumeFrom = outcome.nextChunkIndex
                item.uploadedBytes = min(outcome.nextChunkIndex * CHUNK_SIZE, totalBytes)
                onProgress?.invoke(item)
            }
        }
        try {
            var index = resumeFrom
            while (index < totalChunks) {
                val start = index * CHUNK_SIZE
                val end = min(start + CHUNK_SIZE, totalBytes)
                val chunk = item.data.copyOfRange(start, end)
                // 每块独立 base64 编码自带 padding（读写同纪律，AGENTS §5-8）
                val ack = store.attachmentChunkV4(
                    sessionId = sessionID,
                    uploadId = uploadId,
                    chunkIndex = index,
                    dataBase64 = Base64.getEncoder().encodeToString(chunk),
                )
                val reported = ack.fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        throw AttachmentUploadError("附件分块未送达 · ${attachmentRpcText(error)}")
                    },
                )
                if (reported != index + 1) {
                    throw AttachmentUploadError("桌面端上传进度异常（fault.attachment.invalidServerProgress）")
                }
                index += 1
                item.uploadedBytes = min(index * CHUNK_SIZE, totalBytes)
                onProgress?.invoke(item)
            }
            val commit = store.attachmentCommitV4(sessionId = sessionID, uploadId = uploadId)
            return commit.fold(
                onSuccess = { it },
                onFailure = { error ->
                    throw AttachmentUploadError("附件提交未送达 · ${attachmentRpcText(error)}")
                },
            )
        } catch (e: CancellationException) {
            // 取消不中止远端事务（iOS 原文无此分支——Kotlin 结构化取消显式让位；
            // 事务交由孤儿超时回收，与 Abort 未送达同口径）
            throw e
        } catch (e: Throwable) {
            // Begin 已成功的事务失败 → AbortV4 尽力收口（web PCe catch 同款）；
            // Abort 送达 = 事务已关（重开新事务），未送达 = 保留续传（断线重连场景）
            abortTransaction(item, uploadId)
            throw e
        }
    }

    /**
     * 失败路径中止（iOS :285-294）。Abort 失败不掩盖原始错误（web warn-and-continue
     * 同款）——以短暂提示如实告知（戊-3：桌面繁忙期 30s 一次性超时 = 单管道负载，
     * 「中止指令未送达 · 原文」上屏），孤儿事务由桌面端超时回收。
     */
    private suspend fun abortTransaction(item: PendingAttachment, uploadId: String) {
        store.attachmentAbortV4(sessionId = sessionID, uploadId = uploadId)
            .onSuccess { item.uploadId = null }
            .onFailure { reason ->
                setHint("附件上传中止指令未送达 · ${attachmentRpcText(reason)}")
            }
    }

    // MARK: wire 形状辅助（A-4 web 同构；iOS :296-314）

    /**
     * `upload-<uuid>`（web MCe：`upload-${crypto.randomUUID()}`；满足 schema
     * `^[A-Za-z0-9][A-Za-z0-9._:-]*$`、≤128 字符，transport.ts:840-843）。
     */
    fun makeUploadId(): String = "upload-${UUID.randomUUID()}"

    /** totalBytes>0 时恒 ≥1（zero-byte 声明 zero chunks 的 superRefine 天然满足——add() 已拒空数据）。 */
    fun totalChunksFor(bytes: Int): Int = maxOf(1, (bytes + CHUNK_SIZE - 1) / CHUNK_SIZE)

    /** "sha256:"+64 位小写十六进制（web jCe：subtle.digest SHA-256 → hex 同构）。 */
    fun sha256Checksum(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    // MARK: 来源辅助（相册/拍照/文件三通道共用；iOS :316-322）

    /**
     * 文件扩展名 → MIME（image 前缀族判定与缩略图视图同源 mediaType 口径）。iOS UTType 的
     * 纯 JVM 对位：内置常见后缀表 + 兜底 application/octet-stream（平台差异，deviation：
     * 未覆盖后缀与 iOS UTType 声明库可能有出入，接入相册/文件选择器时按需补表）。
     */
    fun mediaTypeForFileExtension(ext: String): String {
        val normalized = ext.trim().removePrefix(".").lowercase()
        return when (normalized) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            else -> "application/octet-stream"
        }
    }
}

/** AttachmentRPCError 原文提取（failure 面统一文本；其余异常落 message）。 */
private fun attachmentRpcText(error: Throwable): String =
    (error as? AttachmentRPCError)?.text ?: (error.message ?: error.toString())

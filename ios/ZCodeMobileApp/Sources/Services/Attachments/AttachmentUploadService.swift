import CryptoKit
import Foundation
import Observation
import UniformTypeIdentifiers

// MARK: - 待发附件（P1-1 设计稿 §1.4 状态矩阵的 UI 态模型）

/// 单个待发附件的完整生命周期：pending → uploading → committed / failed。
/// 失败保留 uploadId（Abort 未送达时重试按服务端已收块数续传，Begin 回执
/// staging.nextChunkIndex 为权威起点——web PCe 循环同款）；Abort 已送达则
/// uploadId 置 nil，重试重开新事务。
@MainActor
@Observable
final class PendingAttachment: Identifiable {
    enum State: Equatable {
        case pending
        case uploading
        case committed
        case failed
    }

    let id = UUID()
    let name: String
    let mediaType: String   // image/* | video/* | application/pdf | application/octet-stream
    let data: Data
    var state: State = .pending
    /// 已送达字节数（nextChunkIndex×384KB 截断到 totalBytes；UI 进度条数据源）
    var uploadedBytes = 0
    /// 上传事务 id（`upload-<uuid>`，web MCe 同构；nil = 未开启/已中止待重开）
    var uploadId: String?
    /// 最终附件引用（Commit 回执 ref / Begin committed 短路 ref；随 sendText 携带）
    var ref: String?
    var errorText: String?

    init(name: String, mediaType: String, data: Data) {
        self.name = name
        self.mediaType = mediaType
        self.data = data
    }

    var totalBytes: Int { data.count }
    var progress: Double { totalBytes > 0 ? Double(uploadedBytes) / Double(totalBytes) : 0 }
    var percentText: String { "\(Int((progress * 100).rounded()))%" }
    var isImage: Bool { mediaType.hasPrefix("image/") }
}

// MARK: - 附件上传服务（A-4 对齐 web：attachmentBeginV4 → ChunkV4×N → CommitV4）
//
// 全部命令经 ConversationStore 统一面（四条 channel RPC，远端实现内注入 connectionId
// 与 1.2s 首败退避重试；本服务不直接触碰连接层）。事务口径【移植·bundle 逆向 PCe】：
// 384KB/块（hy=384*1024）、uploadId=`upload-<uuid>`、checksum="sha256:"+64hex、
// 进度判定=回执 nextChunkIndex 恒等于 chunkIndex+1、Begin 回执 state 判别联合
// （staging 续传 | committed 直接拿 ref 短路）、Begin 成功后的失败路径发 AbortV4。
// 纪律：每块独立 base64 编码自带 padding（AGENTS §5.7 附件链路实证）、顺序上传、
// 失败如实回传 errorText（不静默吞错）。
@MainActor
@Observable
final class AttachmentUploadService {

    /// 分块大小（web hy=384*1024【实证·上游仓 attachmentUploadTransaction.ts】）
    nonisolated static let chunkSize = 384 * 1024
    /// 单附件上限：attachmentMaxBytes=20MB【实证·上游仓 zcode-protocol-v4/core.ts
    /// PROTOCOL_V4_LIMITS】——旧 4MB 系 bundle 外保守口径（C-16），相机原图普遍
    /// 4-6MB 全被误拦（用户报障「附件不能上传」表象之一）；64 块上限（24MB）不先绑定
    nonisolated static let maxBytes = 20 * 1024 * 1024

    private let store: ConversationStore
    private let sessionID: String

    private(set) var items: [PendingAttachment] = []
    /// 超限/来源读取类提示（设计稿 1.3⑤ 文案；短暂展示后自动清除）
    private(set) var hint: String?
    /// 进度回调（每块送达后触发；UI 主要经 Observable items 绑定，回调供诊断/埋点）
    var onProgress: (@MainActor (PendingAttachment) -> Void)?
    private var hintClearTask: Task<Void, Never>?

    init(store: ConversationStore, sessionID: String) {
        self.store = store
        self.sessionID = sessionID
    }

    // MARK: 投影

    var isEmpty: Bool { items.isEmpty }
    var totalCount: Int { items.count }
    /// 有待发附件且有未 committed 项：sendButton 禁用（设计稿 1.3③/1.4）
    var blocksSend: Bool { items.contains { $0.state != .committed } }
    var firstFailureText: String? { items.first { $0.state == .failed }?.errorText }
    var firstFailureIndex: Int? { items.firstIndex { $0.state == .failed } }

    // MARK: 待发条管理

    /// 入队（来源三通道共用）；超限不发起事务并给提示，返回是否入队。
    @discardableResult
    func add(name: String, mediaType: String, data: Data) -> Bool {
        let trimmedName = name.isEmpty ? String(localized: "未命名附件") : name
        guard !data.isEmpty else {
            setHint(String(localized: "《\(trimmedName)》内容为空，已跳过"))
            return false
        }
        guard data.count <= Self.maxBytes else {
            setHint(String(localized: "桌面端通道单附件上限 20MB，已跳过《\(trimmedName)》"))
            return false
        }
        items.append(PendingAttachment(name: trimmedName, mediaType: mediaType, data: data))
        return true
    }

    /// 移除（未 committed 且已开启事务的尽力 AbortV4 收口——A-4 对齐 web；
    /// Abort 未送达仅提示，孤儿事务由桌面端超时回收）
    func remove(_ id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        let item = items.remove(at: index)
        guard item.state != .committed, let uploadId = item.uploadId else { return }
        let store = self.store
        let sessionID = self.sessionID
        Task {
            if case .failure(let reason) = await store.attachmentAbortV4(
                sessionID: sessionID, uploadId: uploadId) {
                self.setHint(String(localized: "附件上传中止指令未送达 · \(reason.text)"))
            }
        }
    }

    /// 失败重试：同 uploadId 重发 Begin——桌面按已收块数回报 staging.nextChunkIndex，
    /// 从该点续传（Abort 已送达的事务 uploadId 已置 nil，重开新事务）
    func retry(_ id: UUID) {
        guard let item = items.first(where: { $0.id == id }), item.state == .failed else { return }
        Task { await upload(item) }
    }

    func setHint(_ text: String) {
        hintClearTask?.cancel()
        hint = text
        hintClearTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { self?.hint = nil }
        }
    }

    // MARK: 发送联动（设计稿 1.3③：全部 committed 后随下一次发送携带）

    /// 发送前收口：未完成项顺序补传。返回 false = 仍有失败项（本次不发送，
    /// 保留失败态由用户重试/移除——直发文本会丢附件）。
    func ensureAllCommitted() async -> Bool {
        guard !items.isEmpty else { return true }
        for item in items where item.state != .committed {
            await upload(item)
        }
        return items.allSatisfy { $0.state == .committed }
    }

    /// 发送受理后取全部 committed 引用并清空待发条（附件随消息已下发，不再重传）。
    func takeCommitted() -> [OutgoingAttachment] {
        let refs = items
            .filter { $0.state == .committed }
            .map { OutgoingAttachment(
                ref: $0.ref ?? "", fileName: $0.name, mime: $0.mediaType, bytes: $0.data.count) }
        items.removeAll()
        hint = nil
        return refs
    }

    // MARK: 上传事务（A-4：BeginV4 → ChunkV4×N → CommitV4；失败 AbortV4）

    private func upload(_ item: PendingAttachment) async {
        guard item.state != .uploading, item.state != .committed else { return }
        item.state = .uploading
        item.errorText = nil
        do {
            item.ref = try await runTransaction(item)
            item.uploadedBytes = item.data.count
            item.state = .committed
            onProgress?(item)
        } catch {
            // 失败保留 uploadId/进度游标（Abort 未送达的事务可续传）；文案直出失败行
            item.state = .failed
            item.errorText = (error as? AttachmentUploadError)?.message ?? error.localizedDescription
        }
    }

    /// 完整事务。Begin 回执 committed（checksum 命中已上传附件）直接短路拿 ref；
    /// staging 以 nextChunkIndex 为起点逐块顺序上传，进度判定=回执恒等于 index+1；
    /// Begin 成功后的任何失败（含进度越界/错位）发 AbortV4 收口后上抛。
    private func runTransaction(_ item: PendingAttachment) async throws -> String {
        if item.uploadId == nil { item.uploadId = Self.makeUploadId() }
        let uploadId = item.uploadId ?? Self.makeUploadId()
        let totalBytes = item.data.count
        let totalChunks = Self.totalChunks(forBytes: totalBytes)
        let begin = await store.attachmentBeginV4(
            sessionID: sessionID, uploadId: uploadId, fileName: item.name,
            mime: item.mediaType, totalBytes: totalBytes, totalChunks: totalChunks,
            checksum: Self.sha256Checksum(item.data))
        let resumeFrom: Int
        switch begin {
        case .failure(let reason):
            // Begin 未成功：无事务可中止（web u=false 不发 Abort 同款），如实上抛
            throw AttachmentUploadError(
                message: String(localized: "附件上传开启失败 · \(reason.text)"))
        case .success(.committed(let ref)):
            // checksum 命中桌面已有附件：免分块直取引用（web PCe committed 短路）
            item.uploadedBytes = totalBytes
            onProgress?(item)
            return ref
        case .success(.staging(let next)):
            guard next <= totalChunks else {
                // 服务端进度越界 = 事务状态不可信（web fault.attachment.invalidServerProgress）
                await abortTransaction(item, uploadId: uploadId)
                throw AttachmentUploadError(
                    message: String(localized: "桌面端上传进度异常（fault.attachment.invalidServerProgress）"))
            }
            resumeFrom = next
            item.uploadedBytes = min(next * Self.chunkSize, totalBytes)
            onProgress?(item)
        }
        do {
            var index = resumeFrom
            while index < totalChunks {
                let start = index * Self.chunkSize
                let end = min(start + Self.chunkSize, totalBytes)
                let chunk = item.data.subdata(
                    in: item.data.startIndex + start ..< item.data.startIndex + end)
                // 每块独立 base64 编码自带 padding（读写同纪律，AGENTS §5.7）
                let ack = await store.attachmentChunkV4(
                    sessionID: sessionID, uploadId: uploadId, chunkIndex: index,
                    dataBase64: chunk.base64EncodedString())
                switch ack {
                case .failure(let reason):
                    throw AttachmentUploadError(
                        message: String(localized: "附件分块未送达 · \(reason.text)"))
                case .success(let reported) where reported == index + 1:
                    break
                case .success:
                    throw AttachmentUploadError(
                        message: String(localized: "桌面端上传进度异常（fault.attachment.invalidServerProgress）"))
                }
                index += 1
                item.uploadedBytes = min(index * Self.chunkSize, totalBytes)
                onProgress?(item)
            }
            let commit = await store.attachmentCommitV4(sessionID: sessionID, uploadId: uploadId)
            switch commit {
            case .failure(let reason):
                throw AttachmentUploadError(
                    message: String(localized: "附件提交未送达 · \(reason.text)"))
            case .success(let ref):
                return ref
            }
        } catch {
            // Begin 已成功的事务失败 → AbortV4 尽力收口（web PCe catch 同款）；
            // Abort 送达 = 事务已关（重开新事务），未送达 = 保留续传（断线重连场景）
            await abortTransaction(item, uploadId: uploadId)
            throw error
        }
    }

    /// 失败路径中止。Abort 失败不掩盖原始错误（web warn-and-continue 同款）——
    /// 以短暂提示如实告知，孤儿事务由桌面端超时回收。
    private func abortTransaction(_ item: PendingAttachment, uploadId: String) async {
        switch await store.attachmentAbortV4(sessionID: sessionID, uploadId: uploadId) {
        case .success:
            item.uploadId = nil
        case .failure(let reason):
            setHint(String(localized: "附件上传中止指令未送达 · \(reason.text)"))
        }
    }

    // MARK: wire 形状辅助（A-4 web 同构）

    /// `upload-<uuid>`（web MCe：`upload-${crypto.randomUUID()}`；满足 schema
    /// `^[A-Za-z0-9][A-Za-z0-9._:-]*$`、≤128 字符）
    nonisolated static func makeUploadId() -> String {
        "upload-\(UUID().uuidString)"
    }

    /// totalBytes>0 时恒 ≥1（zero-byte 声明 zero chunks 的 superRefine 天然满足——
    /// add() 已拒空数据）
    nonisolated static func totalChunks(forBytes bytes: Int) -> Int {
        max(1, (bytes + chunkSize - 1) / chunkSize)
    }

    /// "sha256:"+64 位小写十六进制（web jCe：subtle.digest SHA-256 → hex 同构）
    nonisolated static func sha256Checksum(_ data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return "sha256:" + digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: 来源辅助（相册/拍照/文件三通道共用）

    /// 文件扩展名 → MIME（image/* 判定与 AttachmentThumbView 同源 mediaType 口径）
    static func mediaType(forFileExtension ext: String) -> String {
        guard let type = UTType(filenameExtension: ext) else { return "application/octet-stream" }
        return type.preferredMIMEType ?? "application/octet-stream"
    }
}

/// 上传链路本地错误（errorText 直出 UI 失败原因行）
struct AttachmentUploadError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

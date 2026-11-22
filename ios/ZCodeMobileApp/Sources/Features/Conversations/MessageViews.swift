import AVFoundation
import AVKit
import SwiftUI

/// 消息视图路由：用户气泡 / Agent 正文 / 工具卡 / todo 卡 / 提问卡
/// （v3 纠偏：快捷回复连接态恢复可用，应答经 resolveInteraction 桌面代执行）
struct MessageView: View {
    let message: ChatMessage
    /// 所属会话 ID（attachmentReadV4 的 sessionId 入参；message.id 是行合成 id，不能冒充）
    var sessionID: String = ""
    /// P1-3 反馈回显（viewModel.assistantFeedback[id]；nil = 未反馈）
    var feedbackState: Bool? = nil
    /// 消息在流内序号（第 N 条；编辑重发确认文案用，0 = 未知走通用文案）
    var ordinal: Int = 0
    var onQuickReply: (String) -> Void
    var onRetryToolCall: (ToolCall, Int) -> Void = { _, _ in }
    /// P1-3 助手反馈点按（value：true=赞 / false=踩 / nil=取消；返回失败文案）。
    /// nil 回调（演示态/未接线）= 反馈行不渲染——命令不可达不渲染死入口
    var onFeedback: ((Bool?) async -> String?)? = nil
    /// P1-3 编辑重发下发（新文本；返回失败文案）。nil 回调 = 长按菜单无「编辑重发」项
    var onEditResend: ((String) async -> String?)? = nil
    /// 工作流完成卡四格 join（活投影 run 按 runId 对位；nil = 拿不到的格写「—」）
    var workflowCompletionRun: WorkflowRunSummary? = nil
    /// 完成卡 ⤢ 打开工作流面板（宿主门控：仅卡对应 run 是面板当前 run 时接线——
    /// 面板只显 workflowRuns.first，错位打开会读成另一次运行；nil = 不渲染入口）
    var onOpenWorkflowRun: (() -> Void)? = nil
    /// 完成卡产物点按 → 内容详情（宿主接线 sheet；nil = 点按无响应不渲染手势力）
    var onOpenWorkflowArtifact: ((WorkflowCompletionArtifact) -> Void)? = nil

    /// 消息行 rowId（id 形如 "row-<n>"，G-015 retryTurn 游标之一）
    private var rowId: Int? {
        guard message.id.hasPrefix("row-") else { return nil }
        return Int(message.id.dropFirst(4))
    }

    /// 编辑重发门槛（retryTurn「游标缺失不渲染入口」同纪律）：回调接线（连接态）
    /// + 行合成消息（rowId 可解析）+ 行元数据游标在场
    private var canEditResend: Bool {
        onEditResend != nil && rowId != nil && message.entityId != nil
    }

    /// v3.15.1 Inline Gen UI：`::visualize{path=… title=…}` 在桌面端投影为内联卡片；
    /// 移动端不承载 HTML 渲染，至少剥离标记并给出「桌面端查看」占位，避免 JSON 污染正文。
    private var genUiProjection: GenUiMarker.Projection {
        GenUiMarker.project(message.text, streaming: message.status == .streaming)
    }

    @State private var showEditResend = false

    var body: some View {
        switch message.role {
        case .user:
            // 桌面口径：用户消息附件位于气泡上方、右对齐（FlexibleFlow 行对齐 trailing；
            // 外层 frame 对恒定占满宽度的 Layout 无效——根因是 flow 行对齐）
            VStack(alignment: .trailing, spacing: T.sp1) {
                if !message.attachments.isEmpty {
                    AttachmentStripView(
                        sessionID: sessionID, refs: message.attachments,
                        rowAlignment: .trailing)
                }
                // userselect 尾部块渲染层剥离 + 引用卡（官方 web 同口径：正文渲染前剥块、
                // 气泡外呈现引用胶囊）。解析必须在渲染层——store 层乐观回显去重按原始
                // 文本匹配（rebuildMessages 尾部 serverUserTexts.contains），改写 text 会破坏去重
                if let selection = UserSelectionSupport.parse(message.text) {
                    if !selection.visibleText.isEmpty {
                        userBubble(selection.visibleText)
                    }
                    UserSelectionCardView(selections: selection.selections)
                } else {
                    userBubble(message.text)
                }
            }
            .sheet(isPresented: $showEditResend) {
                EditResendSheet(
                    ordinal: ordinal,
                    originalText: message.text,
                    onResend: { newText in
                        await onEditResend?(newText)
                            ?? String(localized: "命令未送达（连接中断或不在连接态）")
                    },
                    onCancel: { showEditResend = false })
            }
        case .agent:
            VStack(alignment: .leading, spacing: T.sp2) {
                // 前向兼容兜底行（web ConversationRowView.tsx:2075-2076 unreadable 同构）：
                // 服务端降级标记/worktreeCreated 等本端未渲染形态 → 中性提示卡，不静默丢行
                if message.rowKind == "unreadable" {
                    HStack(alignment: .top, spacing: T.sp2) {
                        AgentAvatar(size: 22)
                        Text(message.text)
                            .font(T.font(12))
                            .foregroundColor(T.text3)
                            .padding(.horizontal, T.sp2)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(T.bgCode)
                            .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    }
                } else {
                if !message.text.isEmpty {
                    HStack(alignment: .top, spacing: T.sp2) {
                        AgentAvatar(size: 22)
                        MarkdownMessageBody(
                            text: genUiProjection.visible,
                            streaming: message.status == .streaming)
                    }
                }
                if !genUiProjection.references.isEmpty {
                    VStack(alignment: .leading, spacing: T.sp2) {
                        ForEach(genUiProjection.references) { reference in
                            GenUiReferenceCard(reference: reference)
                        }
                    }
                    .padding(.leading, 30)
                }
                // 项 4：思考折叠块（正文后、工具卡前；折叠态不渲染正文，列表不因长思考抖动）
                if let thinking = message.thinking {
                    ThinkingBlockView(thinking: thinking)
                }
                // G-014：附件缩略图行（无附件数据不渲染任何占位块）
                if !message.attachments.isEmpty {
                    AttachmentStripView(sessionID: sessionID, refs: message.attachments)
                }
                if let call = message.toolCall {
                    // ExitPlanMode 计划预览卡（官方 switch-mode 渲染器同构）优先——
                    // 载荷形状门命中即整卡替换（官方 renderer 同为整块替换）
                    if let plan = PlanToolCallSupport.parse(call.target) {
                        PlanToolCallCardView(plan: plan)
                    } else {
                        ToolCallCardView(call: call) { action in
                            // G-015：失败工具卡重试 → retryTurn（rowId 来自行合成 id，
                            // entityId 由卡片渲染门槛保证在场）
                            if action == "retry", let rowId, call.entityId != nil {
                                onRetryToolCall(call, rowId)
                            }
                        }
                    }
                }
                if let todos = message.todos {
                    TodoCardView(todos: todos)
                }
                if let question = message.question {
                    QuestionCardView(question: question, onReply: onQuickReply)
                }
                // 工作流完成卡（桌面 ConversationWorkflowCompletion 同款，2026-10-09 点单）
                if let completion = message.workflowCompletion {
                    WorkflowCompletionCardView(
                        card: completion,
                        run: workflowCompletionRun,
                        onOpenRun: onOpenWorkflowRun,
                        onOpenArtifact: onOpenWorkflowArtifact)
                }
                // P1-3 反馈行（设计稿 §3.1 门槛：仅助手正文行——reasoning/subagent/
                // artifact/state-todos 等合成消息 rowKind ≠ assistantText 天然排除；
                // 游标缺失/非连接态不渲染；流式首帧行空文本不渲染，文本抵达随重建出现）
                } // else（unreadable 兜底）闭合
                if let onFeedback, message.rowKind == "assistantText",
                   rowId != nil, message.entityId != nil, !message.text.isEmpty {
                    MessageFeedbackRow(state: feedbackState) { value in
                        await onFeedback(value)
                    }
                }
            }
        }
    }

    /// 用户气泡 + P1-3 长按菜单（displayText 为剥离 userselect 尾块后的可见正文；
    /// 「复制文本」恒复制 message.text 原文——剥离仅影响展示不改数据）
    @ViewBuilder
    private func userBubble(_ displayText: String) -> some View {
        UserBubble(text: displayText)
            .contextMenu {
                if canEditResend {
                    Button {
                        showEditResend = true
                    } label: {
                        Label(String(localized: "编辑重发"), systemImage: "pencil")
                    }
                    .accessibilityIdentifier("05-msg-act-edit-resend")
                }
                Button {
                    UIPasteboard.general.string = message.text
                } label: {
                    Label(String(localized: "复制文本"), systemImage: "doc.on.doc")
                }
                .accessibilityIdentifier("05-msg-act-copy")
            }
    }
}

/// 助手消息反馈行（P1-3 设计稿 §3.2：低调行，44pt 热区；未反馈 text3、已选中
/// accentText 高亮；赞/踩互斥——再点已选项 = 取消反馈。失败：行旁一行橙字 3s，
/// 不弹窗；成功本地记录由 viewModel 写入并回流为 feedbackState）。
struct MessageFeedbackRow: View {
    /// 当前反馈态（viewModel 会话级记录；nil = 未反馈）
    let state: Bool?
    /// 点按回调；返回失败文案（nil = 成功）
    let onAction: (Bool?) async -> String?

    @State private var failure: String?
    @State private var failureClear: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: T.sp1) {
                feedbackButton(
                    icon: "hand.thumbsup", selected: state == true, value: true,
                    identifier: "05-feedback-like")
                feedbackButton(
                    icon: "hand.thumbsdown", selected: state == false, value: false,
                    identifier: "05-feedback-dislike")
                Spacer(minLength: 0)
            }
            .padding(.top, -6) // 44pt 热区外扩后收紧行距（低调行不与正文拉开大空档）
            if let failure {
                Text(failure)
                    .font(T.font(10.5))
                    .foregroundColor(T.orange)
                    .accessibilityIdentifier("05-feedback-failure")
            }
        }
    }

    private func feedbackButton(
        icon: String, selected: Bool, value: Bool, identifier: String) -> some View {
        Button {
            let target: Bool? = selected ? nil : value // 再点已选项 = 取消反馈（§3.3）
            Task {
                if let result = await onAction(target) {
                    failureClear?.cancel()
                    failure = result
                    failureClear = Task {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        guard !Task.isCancelled else { return }
                        failure = nil
                    }
                } else {
                    failure = nil
                    UISelectionFeedbackGenerator().selectionChanged()
                }
            }
        } label: {
            Image(systemName: selected ? icon + ".fill" : icon)
                .font(.system(size: 14))
                .foregroundColor(selected ? T.accentText : T.text3)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
        .accessibilityHint(selected ? String(localized: "再点一次取消反馈") : String(localized: "反馈这条回复"))
    }
}

/// 编辑重发 sheet（P1-3 设计稿 §3.2：§0.1 sheet 范式——Capsule 把手 + 预填原文 +
/// rewind 橙色说明行 + 主钮；§3.5 rewind 不可撤销 → 说明行 + destructive 确认弹层
/// 双重确认。失败：sheet 内错误行不 dismiss，可改后重试或取消）。
struct EditResendSheet: View {
    /// 原消息在流内序号（确认文案「第 N 条」；0 = 未知走通用文案）
    let ordinal: Int
    let originalText: String
    /// 下发回调（editUserQuery；返回失败文案，nil = 成功）
    let onResend: (String) async -> String?
    let onCancel: () -> Void

    @State private var text: String
    @State private var sending = false
    @State private var failure: String?
    @State private var showConfirm = false

    init(ordinal: Int, originalText: String,
         onResend: @escaping (String) async -> String?, onCancel: @escaping () -> Void) {
        self.ordinal = ordinal
        self.originalText = originalText
        self.onResend = onResend
        self.onCancel = onCancel
        _text = State(initialValue: originalText) // 预填原文全文，不截断（§3.4）
    }

    private var trimmedText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("编辑并重发")
                    .font(T.font(17, .bold))
                    .foregroundColor(T.text)
                Spacer()
                Button {
                    onCancel()
                } label: {
                    Text("取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("05-edit-resend-cancel")
            }
            .padding(.horizontal, T.sp4)

            TextField(String(localized: "消息内容"), text: $text, axis: .vertical)
                .font(T.font(14.5))
                .foregroundColor(T.text)
                .lineSpacing(4)
                .lineLimit(3...10)
                .padding(T.sp3)
                .background(T.bgInput)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
                .padding(.horizontal, T.sp4)
                .padding(.top, T.sp2)
                .accessibilityIdentifier("05-edit-resend-field")

            // rewind 语义说明行（§3.2：T.orange 11.5pt；与确认弹层构成双重确认）
            HStack(alignment: .top, spacing: T.sp1) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 11))
                    .foregroundColor(T.orange)
                Text("重发将把对话回退到这条消息之前（rewind），它之后的所有回复会被替换，不可撤销。")
                    .font(T.font(11.5))
                    .foregroundColor(T.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp2)

            if let failure {
                Text(failure)
                    .font(T.font(11.5))
                    .foregroundColor(T.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, T.sp4)
                    .padding(.top, T.sp1)
                    .accessibilityIdentifier("05-edit-resend-failure")
            }

            Spacer(minLength: 0)

            Button {
                showConfirm = true
            } label: {
                HStack(spacing: T.sp2) {
                    if sending {
                        SpinnerView(color: T.onAccent, size: 14)
                    }
                    Text(sending ? String(localized: "重发中…") : String(localized: "编辑并重发"))
                        .font(T.font(15, .semibold))
                }
                .foregroundColor(T.onAccent)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(trimmedText.isEmpty ? T.accent.opacity(0.4) : T.accent)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .disabled(sending || trimmedText.isEmpty)
            .padding(.horizontal, T.sp4)
            .padding(.vertical, T.sp3)
            .accessibilityIdentifier("05-act-edit-resend")
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .interactiveDismissDisabled(sending) // 重发中防误滑关闭
        .confirmationDialog(
            String(localized: "确认重发并回退对话？"),
            isPresented: $showConfirm, titleVisibility: .visible) {
            Button(role: .destructive) {
                Task { await resend() }
            } label: {
                Text("重发")
            }
            .accessibilityIdentifier("05-act-edit-resend-confirm")
            Button(String(localized: "取消"), role: .cancel) {}
        } message: {
            Text(confirmMessage)
        }
    }

    private var confirmMessage: String {
        ordinal > 0
            ? String(localized: "将回退到第 \(ordinal) 条消息之前，之后的回复会被替换。")
            : String(localized: "将回退到这条消息之前，之后的回复会被替换。")
    }

    private func resend() async {
        sending = true
        failure = nil
        let result = await onResend(text)
        sending = false
        if let result {
            failure = result // sheet 内错误行；可改后重试或取消（§3.4）
        } else {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onCancel() // 成功：dismiss；消息流由桌面 rows 键级重建自动刷新（乐观不做）
        }
    }
}

/// 用户右侧气泡（渐变底）
struct UserBubble: View {
    let text: String

    /// 超长粘贴截断（用户 2026-10-06「消息太大要想办法处理」）：> 6k 字先截断，
    /// 「展开全部」按需全文——巨幅粘贴块渲染是滚动卡顿源之一
    @State private var expanded = false
    private static let displayLimit = 6_000

    var body: some View {
        HStack {
            Spacer(minLength: 56)
            VStack(alignment: .leading, spacing: 6) {
                Text(expanded || text.count <= Self.displayLimit
                     ? text : String(text.prefix(Self.displayLimit)) + "…")
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                    .lineSpacing(4)
                if text.count > Self.displayLimit {
                    Button(expanded ? String(localized: "收起") : String(localized: "展开全部（\(text.count) 字）")) {
                        withAnimation { expanded.toggle() }
                    }
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, T.sp3)
            .padding(.vertical, 10)
            .background(T.gradBubble)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        }
        .padding(.leading, T.sp6)
    }
}

// MARK: - 用户选区引用（userselect 尾部块；官方 web 渲染前剥离 + 胶囊化，移动端卡片化）

/// 单条选区引用。围栏载荷合同仅 text + 可选 path（文件选段才有）
/// 【实证·上游仓】conversationSelectionReference.ts:154（发送只序列化 text/path）；
/// 官方限额：单条 8k 字 / 最多 8 条 / 总计 16k 字（:3-5）
struct UserSelectionReference: Identifiable, Equatable {
    let id: String
    let text: String
    var path: String?

    init(text: String, path: String? = nil, id: String) {
        self.id = id
        self.text = text
        self.path = path
    }
}

/// 详情 sheet 载荷（呈现期捕获 selections，§5-15：关闭单路径置 nil）
struct UserSelectionDetail: Identifiable {
    let id = UUID().uuidString
    let selections: [UserSelectionReference]
}

enum UserSelectionSupport {
    /// 解析用户消息尾部的 userselect 块；nil = 不是引用消息，按原样渲染。
    /// 锚定文本末尾（官方语义），剥离块后剩余为可见正文——空正文合法（纯引用消息）
    static func parse(_ raw: String) -> (visibleText: String, selections: [UserSelectionReference])? {
        // 先官方后宽容：官方头必须先试，否则裸围栏分支会把「# userselect:」头行残留进可见正文
        for variant in Variant.allCases {
            if let result = match(raw, variant: variant) { return result }
        }
        return nil
    }

    /// 卡片 label（官方三规则，ConversationSelectionReferenceChip.tsx:24-38）：
    /// 单条有 path → 「文件名 · 引用」；多条含 path → 「n 条引用」；纯对话 → 「n 条对话引用」
    static func cardLabel(_ selections: [UserSelectionReference]) -> String {
        if selections.count == 1, let path = selections[0].path {
            return String(localized: "\(pathFilename(path)) · 引用")
        }
        if selections.contains(where: { $0.path != nil }) {
            return String(localized: "\(selections.count) 条引用")
        }
        return String(localized: "\(selections.count) 条对话引用")
    }

    /// 文件名取路径尾段（官方 path.split(/[\\/]/).pop()）
    static func pathFilename(_ path: String) -> String {
        let parts = path.split { $0 == "/" || $0 == "\\" }
        return parts.last.map(String.init) ?? path
    }

    private enum Variant: CaseIterable {
        /// 官方：【实证·上游仓】conversationSelectionReference.ts:56
        case official
        /// 旧版拼写：【实证·上游仓】conversationSelectionReference.ts:57-58
        case legacy
        /// 裸围栏（无注释头）：【宽容·未取证】防御桌面变体，桌面现行走官方头形态
        case bare

        var header: String? {
            switch self {
            case .official: return "# userselect:"
            case .legacy: return "# Conversation selections:"
            case .bare: return nil
            }
        }

        var fence: String {
            switch self {
            case .official, .bare: return "```userselect"
            case .legacy: return "```zcode-conversation-selections"
            }
        }
    }

    /// 尾部块正则（镜像上游，锚定 \z；载荷惰性匹配至「满足结尾锚的最早 \n```」——
    /// 与 JS USER_SELECT_BLOCK_PATTERN 行为一致，载荷含 ``` 字符时仍能对位真闭合围栏）
    private static func regex(for variant: Variant) -> NSRegularExpression? {
        // (?:\n\n)?[#头\n]?```lang\n(载荷)\n```[空白]*\z —— 头行仅官方/legacy 形态携带
        let headerPart = variant.header.map { NSRegularExpression.escapedPattern(for: $0) + "\\n" } ?? ""
        let fence = NSRegularExpression.escapedPattern(for: variant.fence)
        let pattern = "(?:\\n\\n)?\(headerPart)\(fence)\\n([\\s\\S]*?)\\n```[ \\t\\r\\n]*\\z"
        return try? NSRegularExpression(pattern: pattern)
    }

    private static func match(
        _ raw: String, variant: Variant
    ) -> (visibleText: String, selections: [UserSelectionReference])? {
        guard let regex = regex(for: variant) else { return nil }
        let ns = raw as NSString
        guard let m = regex.firstMatch(in: raw, range: NSRange(location: 0, length: ns.length)),
              m.range.location != NSNotFound,
              m.numberOfRanges > 1 else { return nil }
        let json = ns.substring(with: m.range(at: 1))
        guard let selections = decode(json) else { return nil }
        // 可见正文 = 整个匹配之前的前缀（匹配已吞掉块前的空行分隔），尾部再收一次空白
        let visible = ns.substring(to: m.range.location)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (visible, selections)
    }

    /// 宽容解码：忽略未知键（官方合同仅 text/path）；无有效条目 → nil（回退原样渲染）
    private static func decode(_ json: String) -> [UserSelectionReference]? {
        guard let data = json.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return nil
        }
        var items: [UserSelectionReference] = []
        for (index, entry) in array.enumerated() {
            guard let text = entry["text"] as? String, !text.isEmpty else { continue }
            let path = entry["path"] as? String
            items.append(UserSelectionReference(
                text: text, path: (path?.isEmpty == true) ? nil : path,
                id: "userselect-\(index)"))
        }
        return items.isEmpty ? nil : items
    }
}

/// 引用卡（官方 web ContextAttachmentPill 胶囊 + hover 详情浮层的移动端形态：
/// 卡片点开详情 sheet）。sheet 挂卡内行级 @State——宿主行已有 EditResend sheet，
/// 同视图链双 sheet 后挂遮蔽先挂（fullScreenCover 同坑先例 AttachmentThumbView）
struct UserSelectionCardView: View {
    let selections: [UserSelectionReference]
    @State private var detail: UserSelectionDetail?

    var body: some View {
        Button {
            detail = UserSelectionDetail(selections: selections)
        } label: {
            HStack(alignment: .top, spacing: T.sp2) {
                Image(systemName: "text.quote")
                    .font(.system(size: 14))
                    .foregroundColor(T.text3)
                    .padding(.top, 2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(UserSelectionSupport.cardLabel(selections))
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.text)
                    Text(selections[0].text)
                        .font(T.font(12.5))
                        .foregroundColor(T.text3)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(T.text3)
                    .padding(.top, 3)
            }
            .padding(T.sp3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("05-userselect-card")
        .sheet(item: $detail) { presented in
            UserSelectionDetailSheet(detail: presented) { detail = nil }
        }
    }
}

/// 引用详情 sheet（官方 AttachmentHoverCardContent 浮层的全屏形态：
/// 逐条 = 引号图标 + 全文可选中复制 + 来源行）
struct UserSelectionDetailSheet: View {
    let detail: UserSelectionDetail
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: T.sp2) {
                    ForEach(detail.selections) { selection in
                        HStack(alignment: .top, spacing: T.sp2) {
                            Image(systemName: "text.quote")
                                .font(.system(size: 14))
                                .foregroundColor(T.text3)
                                .padding(.top, 2)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(selection.text)
                                    .font(T.font(13.5))
                                    .foregroundColor(T.text)
                                    .textSelection(.enabled)
                                Text(selection.path ?? String(localized: "对话引用"))
                                    .font(T.font(11.5))
                                    .foregroundColor(T.text3)
                                    .lineLimit(2)
                            }
                        }
                        .padding(T.sp3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(T.bgCard)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
                    }
                }
                .padding(T.sp3)
            }
            .background(T.bg)
            .navigationTitle("引用详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "关闭")) { onClose() }
                        .accessibilityIdentifier("05-userselect-close")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - ExitPlanMode 计划预览卡（官方 web switch-mode 渲染器同构，2026-10-09 点单）

/// ExitPlanMode 工具行提取出的计划内容（官方 planToolCall.ts PlanToolCallContent 同构）
struct PlanToolCallContent: Equatable {
    var markdown: String
    var planFilePath: String?
}

/// 详情 sheet 载荷（呈现期捕获，§5-15：关闭单路径置 nil）
struct PlanToolCallDetail: Identifiable {
    let id = UUID().uuidString
    let markdown: String
    let title: String?
}

enum PlanToolCallSupport {
    /// ExitPlanMode（switch_mode 族）工具行 → 计划内容提取。
    /// 官方按 tool identity family 分流（toolIdentity.ts ExitPlanMode → switch-mode →
    /// SwitchModeToolCallBlock），提取链 input对象 → JSON.parse(inputText) → output → raw
    /// 【实证·上游仓 planToolCall.ts extractPlanToolCallContent】。移动端 ToolCall 不带
    /// toolName（store 行映射只留 inputText 作 target），收敛为**载荷形状门**：target 解析
    /// 为 JSON 对象且 `plan` 字段为非空串才成卡【移植·上游仓 + 形状门收敛，防 bash 命令/
    /// MCP text 字段误判】；planFilePath 可选。
    static func parse(_ target: String) -> PlanToolCallContent? {
        let trimmed = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        guard let markdown = object["plan"] as? String,
              !markdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        let path = object["planFilePath"] as? String
        return PlanToolCallContent(
            markdown: markdown,
            planFilePath: (path?.isEmpty == true) ? nil : path)
    }

    /// 计划标题：首个 H1，否则首个非空行去行首装饰（#{1,6}/引用/列表符）
    /// 【实证·上游仓 planToolCall.ts getPlanDirectoryTitle】
    static func planTitle(_ markdown: String) -> String? {
        // JS /m 多行锚点 → NSRegularExpression 必须显式 anchorsMatchLines
        //（缺省时 ^$ 只匹配串首尾，H1 永不命中、恒走首行回退——单测实证）
        let h1Regex = try? NSRegularExpression(
            pattern: #"^\s{0,3}#(?!#)\s+(.+?)\s*#*\s*$"#,
            options: [.anchorsMatchLines])
        var h1: String?
        if let h1Regex {
            h1Regex.enumerateMatches(
                in: markdown,
                options: [],
                range: NSRange(location: 0, length: (markdown as NSString).length)
            ) { match, _, stop in
                if let match, match.numberOfRanges > 1 {
                    h1 = (markdown as NSString).substring(with: match.range(at: 1))
                    stop.pointee = true
                }
            }
        }
        if let h1 { return h1 }
        for line in markdown.split(whereSeparator: \.isNewline) {
            let title = line.trimmingCharacters(in: .whitespaces)
            guard !title.isEmpty else { continue }
            return title.replacingOccurrences(
                of: #"^(?:#{1,6}\s+|>\s*|[-*+]\s+)"#,
                with: "",
                options: .regularExpression)
        }
        return nil
    }

    /// 路径尾段展示名（官方 getPlanFileLabel = getPathLeaf）
    static func planFileLabel(_ path: String) -> String {
        let parts = path.split { $0 == "/" || $0 == "\\" }
        return parts.last.map(String.init) ?? path
    }
}

/// 计划预览卡（官方 SwitchModeToolCallBlock 移动端同构：「📋 计划」头 + 复制钮 +
/// 渐隐 markdown 预览 + 居中「查看完整计划」胶囊 → 全文 sheet；整卡点按同开）
struct PlanToolCallCardView: View {
    let plan: PlanToolCallContent
    @State private var detail: PlanToolCallDetail?
    @State private var copied = false
    @State private var copiedReset: Task<Void, Never>?

    /// 预览排版上限（>4k 字计划正文截断防卡顿；全文在 sheet——官方 max-h-64 渐隐同思路）
    private static let previewLimit = 4_000

    private var title: String? { PlanToolCallSupport.planTitle(plan.markdown) }

    var body: some View {
        VStack(spacing: 0) {
            header
            previewBody
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: T.rM))
        .onTapGesture { openDetail() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-plan-toolcard")
        .sheet(item: $detail) { presented in
            PlanToolCallSheet(detail: presented) { detail = nil }
        }
    }

    /// 头：「计划」+ 可选计划文件尾段 + 复制（复制后 2s 打勾，官方 copy→check 同款）
    private var header: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "list.clipboard")
                .font(.system(size: 13))
                .foregroundColor(T.text3)
            Text(String(localized: "计划"))
                .font(T.font(13, .medium))
                .foregroundColor(T.text3)
            if let path = plan.planFilePath {
                Text(PlanToolCallSupport.planFileLabel(path))
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
                    .truncationMode(.head)
                    .layoutPriority(-1)
            }
            Spacer(minLength: 0)
            Button {
                UIPasteboard.general.string = plan.markdown
                copiedReset?.cancel()
                copied = true
                copiedReset = Task {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    guard !Task.isCancelled else { return }
                    copied = false
                }
            } label: {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(copied ? T.accentText : T.text3)
                    .frame(minWidth: 32, minHeight: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-plan-toolcard-copy")
        }
        .padding(.horizontal, T.sp3)
        .padding(.top, T.sp3)
    }

    /// 预览体：markdown 渐隐（mask 三段渐变，官方 mask-image 同构）+ 居中胶囊浮钮
    private var previewBody: some View {
        let preview = plan.markdown.count > Self.previewLimit
            ? String(plan.markdown.prefix(Self.previewLimit)) + "…"
            : plan.markdown
        return ZStack(alignment: .bottom) {
            MarkdownMessageBody(text: preview)
                .frame(maxWidth: .infinity, maxHeight: 216, alignment: .top)
                .clipped()
                .mask(
                    LinearGradient(
                        stops: [
                            .init(color: .black, location: 0),
                            .init(color: .black, location: 0.55),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .top, endPoint: .bottom)
                )
                .padding(.bottom, 34)
            Button {
                openDetail()
            } label: {
                HStack(spacing: 6) {
                    Text(String(localized: "查看完整计划"))
                        .font(T.font(14, .semibold))
                    Image(systemName: "arrow.right")
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundColor(T.onAccent)
                .padding(.horizontal, 20)
                .padding(.vertical, 11)
                .background(T.accent)
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-plan-toolcard-open")
            .padding(.bottom, T.sp3)
        }
    }

    private func openDetail() {
        detail = PlanToolCallDetail(markdown: plan.markdown, title: title)
    }
}

/// 计划全文 sheet（官方 onOpenPlanDetail 侧栏的移动端形态：全量 markdown 可滚动）
struct PlanToolCallSheet: View {
    let detail: PlanToolCallDetail
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                MarkdownMessageBody(text: detail.markdown)
                    .padding(T.sp3)
            }
            .background(T.bg)
            .navigationTitle(detail.title ?? String(localized: "计划"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "关闭")) { onClose() }
                        .accessibilityIdentifier("05-plan-sheet-close")
                }
            }
        }
        .presentationDetents([.large])
    }
}

/// Agent 正文（markdown 块渲染 + 流式光标）
struct MarkdownMessageBody: View {
    let text: String
    var streaming: Bool = false

    /// 超长消息截断（同 UserBubble）：> 6k 字先截断再进 markdown 解析——巨幅
    /// tool 输出/日志块的解析与渲染曾致列表卡顿；流式中不截断（光标语义优先）
    @State private var expanded = false
    private static let displayLimit = 6_000

    private var displayText: String {
        streaming || expanded || text.count <= Self.displayLimit
            ? text : String(text.prefix(Self.displayLimit)) + "…"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            ForEach(TinyMarkdown.parse(displayText)) { block in
                MarkdownBlockView(block: block)
            }
            if text.count > Self.displayLimit, !streaming {
                Button(expanded ? String(localized: "收起") : String(localized: "展开全部（\(text.count) 字）")) {
                    withAnimation { expanded.toggle() }
                }
                .font(T.font(11.5, .semibold))
                .foregroundColor(T.accentText)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if streaming {
                BlinkingCursor()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 工具调用卡（spec 5.3：头 min-height 44，展开体 12px mono 弱底）

struct ToolCallCardView: View {
    let call: ToolCall
    var onAction: (String) -> Void = { _ in }
    @State private var expanded = false
    @Environment(AppRouter.self) private var router

    /// 路径类目标（含路径分隔符且带扩展名）→ 文件图标 + 双段路径展示
    private var isPathTarget: Bool {
        let target = call.target
        guard target.contains("/") else { return false }
        return !((target as NSString).pathExtension).isEmpty
    }

    /// 工具种类图标（桌面工具行同构）。ToolKind 五 case 已穷举，不设 default：
    /// 未来新增 case 时由编译期穷尽检查兜底（"default will never be executed" 消除）
    private static func kindIcon(_ kind: ToolKind) -> String {
        switch kind {
        case .bash: return "terminal"
        case .read: return "doc.text"
        case .edit: return "pencil.line"
        case .ask: return "questionmark.bubble"
        case .browser: return "safari"
        }
    }

    /// 工具种类中文动词（桌面「编辑/读取/正在执行」同构）
    private static func kindLabel(_ kind: ToolKind) -> String {
        switch kind {
        case .bash: return String(localized: "执行")
        case .read: return String(localized: "读取")
        case .edit: return String(localized: "编辑")
        case .ask: return String(localized: "提问")
        case .browser: return String(localized: "浏览")
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: T.sp2) {
                    // 桌面同构头：图标 + 中文动词 + 文件名（突出）+ 目录（弱化）+ 增删行数
                    Image(systemName: Self.kindIcon(call.kind))
                        .font(.system(size: 11))
                        .foregroundColor(T.text3)
                    Text(Self.kindLabel(call.kind))
                        .font(T.font(12, .medium))
                        .foregroundColor(T.text)
                    if isPathTarget {
                        FileTypeBadgeView(path: call.target)
                        // 文件名 + 父目录双段展示：文件名保留完整，目录头部截断
                        Text((call.target as NSString).lastPathComponent)
                            .font(T.mono(12, .semibold))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        let dir = (call.target as NSString).deletingLastPathComponent
                        if !dir.isEmpty {
                            Text(dir + "/")
                                .font(T.mono(11))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                                .truncationMode(.head)
                                .layoutPriority(-1)
                        }
                    } else {
                        Text(call.target)
                            .font(T.mono(11.5))
                            .foregroundColor(T.text2)
                            .lineLimit(1)
                            .truncationMode(.head)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    statusArea
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(T.text3)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-toolcard-head-\(call.kind.rawValue)")
            .accessibilityHint("展开或收起工具调用详情")

            if expanded {
                detailBody
            }
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }

    @ViewBuilder
    private var statusArea: some View {
        switch call.status {
        case .running:
            SpinnerView(size: 14)
        case .done:
            HStack(spacing: 3) {
                Image(systemName: "checkmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(T.accentText)
                if let duration = call.duration {
                    Text(duration)
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                }
                if let added = call.addedLines, let removed = call.removedLines {
                    Text("+\(added) -\(removed)")
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                }
            }
        case .failed:
            HStack(spacing: T.sp1) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 13))
                    .foregroundColor(T.red)
                // G-015：失败且行元数据游标（entityId）在场才出现「重试」——
                // 无游标不渲染，不虚构可重试
                if call.entityId != nil {
                    Button {
                        onAction("retry")
                    } label: {
                        Label(String(localized: "重试"), systemImage: "arrow.clockwise")
                            .font(T.font(11, .semibold))
                            .foregroundColor(T.accentText)
                            .padding(.horizontal, T.sp1)
                            .frame(minHeight: 28)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("05-toolcard-act-retry")
                }
            }
        }
    }

    @ViewBuilder
    private var detailBody: some View {
        Divider().overlay(T.border)
        // G-020：workflow 启动轮（CreateWorkflow/AmendWorkflow/StartSavedWorkflow）宽容解析
        // 出 runId → 有界徽标，作为会话内 run 图（头部工作流面板）的联接线索
        if let runId = call.workflowRunId {
            HStack(spacing: T.sp1) {
                Image(systemName: "flowchart.fill")
                    .font(.system(size: 10))
                    .foregroundColor(T.violet)
                Text(String(localized: "工作流 run"))
                    .font(T.font(11, .medium))
                    .foregroundColor(T.text2)
                Text(runId)
                    .font(T.mono(10))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, T.sp3)
            .padding(.vertical, 4)
            .accessibilityIdentifier("05-toolcard-workflow-run")
        }
        switch call.kind {
        case .bash, .read:
            VStack(spacing: 0) {
                // 展开体首行：具体命令/文件路径（头部截断仅是摘要，展开后要能看到全量）
                targetDetailBlock
                if let output = call.output {
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(output)
                            .font(T.mono(12))
                            .foregroundColor(T.text2)
                            .lineSpacing(4)
                            .padding(T.sp3)
                            .frame(minWidth: UIScreen.main.bounds.width - 96, alignment: .leading)
                    }
                }
            }
            .background(T.bgCode)
            .accessibilityIdentifier("05-toolcard-body-bash")
        case .edit:
            VStack(spacing: 0) {
                targetDetailBlock
                if let diff = call.diff {
                    DiffLineListView(lines: diff)
                    Button {
                        router.openDiffFromChat()
                    } label: {
                        Text("查看完整 Diff")
                            .font(T.font(13, .medium))
                            .foregroundColor(T.accentText)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .accessibilityIdentifier("05-act-view-full-diff")
                }
            }
            .background(T.bgCode)
            .accessibilityIdentifier("05-toolcard-body-edit")
        case .ask, .browser:
            Text(call.target)
                .font(T.font(12))
                .foregroundColor(T.text2)
                .padding(T.sp3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// 展开体目标块：bash=完整命令 / edit·read=完整文件路径（换行铺开，不再截断）
    private var targetDetailBlock: some View {
        Text(call.target)
            .font(T.mono(12))
            .foregroundColor(T.text)
            .lineSpacing(3)
            .padding(T.sp3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("05-toolcard-target-detail")
    }
}

// MARK: - 文件类型徽标（桌面端语言图标同构：.swift → Swift 字形，其余扩展名色块缩写）

struct FileTypeBadgeView: View {
    let path: String

    private var ext: String { (path as NSString).pathExtension.lowercased() }

    var body: some View {
        Group {
            if ext == "swift" {
                Image(systemName: "swift")
                    .font(.system(size: 13))
                    .foregroundColor(Color(red: 1.0, green: 0.47, blue: 0.24))
            } else {
                Text(abbr)
                    .font(T.mono(8, .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 3.5)
                    .padding(.vertical, 2.5)
                    .background(color)
                    .clipShape(RoundedRectangle(cornerRadius: 3.5))
            }
        }
        .frame(width: 20)
    }

    /// 扩展名缩写（缺省取前 3 字符大写）
    private var abbr: String {
        switch ext {
        case "ts", "tsx": return "TS"
        case "js", "jsx", "mjs", "cjs": return "JS"
        case "json": return "{}"
        case "md", "markdown": return "MD"
        case "py": return "PY"
        case "rs": return "RS"
        case "go": return "GO"
        case "java": return "JV"
        case "kt", "kts": return "KT"
        case "rb": return "RB"
        case "php": return "PHP"
        case "c", "h": return "C"
        case "cpp", "cc", "hpp", "mm": return "C++"
        case "sh", "zsh", "bash": return "SH"
        case "yml", "yaml": return "YML"
        case "toml": return "TOML"
        case "html", "htm": return "<>"
        case "css", "scss", "less": return "CSS"
        case "png", "jpg", "jpeg", "gif", "webp", "svg": return "IMG"
        case "pdf": return "PDF"
        case "zip", "tar", "gz": return "ZIP"
        case "lock": return "LOCK"
        default: return String(ext.prefix(3).uppercased())
        }
    }

    /// 语言族色（桌面 Seti/语言色同族；缺省中性灰）
    private var color: Color {
        switch ext {
        case "ts", "tsx": return Color(red: 0.19, green: 0.47, blue: 0.80)
        case "js", "jsx", "mjs", "cjs", "json": return Color(red: 0.80, green: 0.62, blue: 0.08)
        case "md", "markdown", "txt": return Color(red: 0.26, green: 0.52, blue: 0.96)
        case "py": return Color(red: 0.22, green: 0.55, blue: 0.55)
        case "rs": return Color(red: 0.72, green: 0.34, blue: 0.20)
        case "go": return Color(red: 0.19, green: 0.60, blue: 0.70)
        case "java", "php": return Color(red: 0.68, green: 0.35, blue: 0.30)
        case "kt": return Color(red: 0.60, green: 0.35, blue: 0.85)
        case "rb": return Color(red: 0.75, green: 0.25, blue: 0.30)
        case "c", "h", "cpp", "cc", "hpp", "mm": return Color(red: 0.35, green: 0.55, blue: 0.75)
        case "sh", "zsh", "bash": return Color(red: 0.35, green: 0.45, blue: 0.35)
        case "yml", "yaml", "toml", "lock": return Color(red: 0.45, green: 0.50, blue: 0.55)
        case "html", "htm": return Color(red: 0.78, green: 0.42, blue: 0.18)
        case "css", "scss", "less": return Color(red: 0.25, green: 0.50, blue: 0.78)
        case "png", "jpg", "jpeg", "gif", "webp", "svg": return Color(red: 0.45, green: 0.60, blue: 0.35)
        case "pdf": return Color(red: 0.80, green: 0.30, blue: 0.28)
        case "zip", "tar", "gz": return Color(red: 0.55, green: 0.45, blue: 0.30)
        default: return Color(red: 0.52, green: 0.55, blue: 0.60)
        }
    }
}

// MARK: - todo 拆解卡（项 3 双态卡：头部可点折叠/展开；done 划线 / now 蓝框 spinner / todo 空框 / failed 红叉）
// 口径（Qoder 官方三态 + 朱批 failed 扩展）：空心圆=未开始、旋转圆=进行中、复选框=已完成；
// 新计划/进行中默认展开，全部完成自动折叠只留头部；用户手动切换后以用户为准。

struct TodoCardView: View {
    let todos: [TodoItem]
    /// nil = 未手动切换（走自动规则：全部完成→折叠，否则展开）
    @State private var expandedOverride: Bool?

    private var doneCount: Int { todos.filter { $0.state == .done }.count }
    private var allDone: Bool { !todos.isEmpty && todos.allSatisfy { $0.state == .done } }
    private var expanded: Bool { expandedOverride ?? !allDone }

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { expandedOverride = !expanded }
                UISelectionFeedbackGenerator().selectionChanged()
            } label: {
                HStack(spacing: T.sp2) {
                    Image(systemName: "list.bullet")
                        .font(.system(size: 14))
                        .foregroundColor(T.text3)
                    Text("任务拆解")
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.text2)
                    Text("\(doneCount)/\(todos.count)")
                        .font(T.mono(11, .semibold))
                        .foregroundColor(allDone ? T.accentText : T.blue)
                    ThinProgressBar(
                        progress: Double(doneCount) / Double(max(todos.count, 1)),
                        height: 4,
                        tint: allDone ? T.accent : T.blue)
                        .frame(maxWidth: .infinity)
                        .frame(height: 4)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(T.text3)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .padding(.horizontal, T.sp2)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-todocard-head")

            if expanded {
                VStack(spacing: T.sp1) {
                    ForEach(Array(todos.enumerated()), id: \.element.id) { index, item in
                        todoRow(item, index: index)
                    }
                }
                .padding(.top, T.sp1)
                .padding(.bottom, 6)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .card()
        // 透明容器：容器可定位（05-todocard），子元素保留各自 identifier
        // （05-todocard-head / 05-todocard-row-*；否则容器 identifier 聚合吞掉后代，
        // 门禁实证 head/row 不进可访问性树——同 05-approval-card 处理）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-todocard")
    }

    private func todoRow(_ item: TodoItem, index: Int) -> some View {
        HStack(spacing: T.sp2) {
            todoIcon(item)
            Text(item.title)
                .font(T.font(12.5, item.state == .now ? .semibold : .regular))
                .strikethrough(item.state == .done, color: T.text3)
                .foregroundColor(item.state == .done ? T.text3 : T.text)
                .lineLimit(1)
            if item.state == .failed {
                Text("失败")
                    .font(T.mono(10.5))
                    .foregroundColor(T.red)
            }
            Spacer()
        }
        .padding(.horizontal, T.sp2)
        .padding(.vertical, 6)
        .background {
            if item.state == .now {
                RoundedRectangle(cornerRadius: T.rS)
                    .fill(T.blueDim)
                    .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.blue.opacity(0.6), lineWidth: 1))
            }
        }
        .accessibilityIdentifier("05-todocard-row-\(index)")
    }

    @ViewBuilder
    private func todoIcon(_ item: TodoItem) -> some View {
        switch item.state {
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.accentText)
        case .now:
            // Qoder「旋转圆圈」口径：蓝圈 + 内弧 spinner（2.7 spinner 1s 线性）
            SpinnerView(color: T.blue, size: 14)
                .frame(width: 14, height: 14)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.red)
        case .todo:
            Circle().strokeBorder(T.borderStrong, lineWidth: 1.5).frame(width: 14, height: 14)
        }
    }
}

// MARK: - 思考折叠块（项 4：streaming 全程折叠只留头部摘要；用户点开后跟随流式渲染）
// 摘要三态：思考中…（+已思考 Ns）/ 已深度思考（+用时 Ns · N 字）/ 思考已中断。
// 摘要只给元信息、不截断正文（文案取通用范式，Qoder/Trae 无官方显式规格——调研 notes 如实标注）。

struct ThinkingBlockView: View {
    let thinking: ThinkingContent
    /// 用户点开过 = 跟随流式渲染正文（B 档）；默认全程折叠（A 档）
    @State private var userOpened = false

    private var expanded: Bool { userOpened }

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { userOpened.toggle() }
                UISelectionFeedbackGenerator().selectionChanged()
            } label: {
                HStack(spacing: T.sp2) {
                    Image(systemName: "brain.head.profile")
                        .font(.system(size: 14))
                        .foregroundColor(T.text3)
                    Text(summaryTitle)
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.text2)
                    metaText
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                    Spacer(minLength: 0)
                    if thinking.state == .streaming {
                        SpinnerView(color: T.blue, size: 14)
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(T.text3)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .padding(.horizontal, T.sp3)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("05-thinking-head")
            .accessibilityHint("展开或收起思考过程")

            if expanded {
                bodyContent
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }

    private var summaryTitle: String {
        switch thinking.state {
        case .streaming: return "思考中…"
        case .done: return "已深度思考"
        case .interrupted: return "思考已中断"
        }
    }

    /// 元信息（mono 10.5 text-3，禁叠 opacity）：流式秒表实时累计；完成态给用时 + 字数
    @ViewBuilder
    private var metaText: some View {
        switch thinking.state {
        case .streaming:
            if let startedAt = thinking.startedAt {
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    Text(String(localized: "已思考 \(max(0, Int(Date().timeIntervalSince(startedAt))))s"))
                }
            }
        case .done:
            let characters = thinking.text.count
            if let duration = thinking.duration {
                Text(String(localized: "用时 \(Int(duration.rounded()))s · \(characters) 字"))
            } else {
                Text(String(localized: "\(characters) 字"))
            }
        case .interrupted:
            EmptyView()
        }
    }

    /// 展开体：左 2px 竖线 + 正文 12.5/20 text2（自然语言非 mono）+ 流式尾随光标
    private var bodyContent: some View {
        HStack(alignment: .top, spacing: T.sp3) {
            Rectangle()
                .fill(T.borderStrong)
                .frame(width: 2)
            VStack(alignment: .leading, spacing: T.sp1) {
                Text(thinking.text)
                    .font(T.font(12.5))
                    .foregroundColor(T.text2)
                    .lineSpacing(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if thinking.state == .streaming {
                    BlinkingCursor()
                }
            }
        }
        .padding(.leading, T.sp3)
        .padding(.trailing, T.sp3)
        .padding(.top, 2)
        .padding(.bottom, T.sp2)
        .accessibilityIdentifier("05-thinking-body")
    }
}

// MARK: - Agent 提问卡（蓝描边 + 44px 快捷回复 chips；连接态与演示态同构可点）

struct QuestionCardView: View {
    let question: AgentQuestion
    var onReply: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "questionmark.circle.fill")
                    .font(.system(size: 15))
                    .foregroundColor(T.blue)
                Text(question.text)
                    .font(T.font(13.5, .medium))
                    .foregroundColor(T.text)
            }
            FlowChips(items: question.quickReplies, identifierPrefix: "05-chip-reply") { reply in
                onReply(reply)
            }
        }
        .card()
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.blue.opacity(0.55), lineWidth: 1))
        // 透明容器：容器可定位（05-questioncard），快捷回复 chips 保留各自可查询性
        // （同 05-approval-card / 05-todocard 处理，容器 identifier 不吞后代）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-questioncard")
    }
}

/// 可交互 chips 流式布局（44px 热区）
struct FlowChips: View {
    let items: [String]
    var identifierPrefix: String = "chip"
    var onTap: (String) -> Void

    var body: some View {
        FlexibleFlow(spacing: T.sp2) {
            ForEach(items, id: \.self) { item in
                Button {
                    onTap(item)
                } label: {
                    Text(item)
                        .font(T.font(12.5, .medium))
                        .foregroundColor(T.accentText)
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 44)
                        .background(T.accentDim)
                        .clipShape(Capsule())
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("\(identifierPrefix)-\(item.hashValue.magnitude % 1000)")
            }
        }
    }
}

/// 简易流式布局（自动换行 chips）
struct FlexibleFlow: Layout {
    var spacing: CGFloat = 8
    /// 行内水平对齐（默认 leading；用户附件行传 trailing——靠左会被误读为 agent 发的图）
    var rowAlignment: HorizontalAlignment = .leading

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > width, x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // 先按宽度分行，再按行对齐放置（trailing/center 时整行偏移）
        var rows: [[(LayoutSubview, CGSize)]] = [[]]
        var rowWidth: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > bounds.width, !rows[rows.count - 1].isEmpty {
                rows.append([])
                rowWidth = 0
            }
            rows[rows.count - 1].append((subview, size))
            rowWidth += size.width + spacing
        }
        var y = bounds.minY
        for row in rows where !row.isEmpty {
            let width = row.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(row.count - 1)
            let rowHeight = row.map(\.1.height).max() ?? 0
            var x: CGFloat
            if rowAlignment == .trailing {
                x = bounds.maxX - width
            } else if rowAlignment == .center {
                x = bounds.minX + (bounds.width - width) / 2
            } else {
                x = bounds.minX
            }
            for (subview, size) in row {
                subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += rowHeight + spacing
        }
    }
}

// MARK: - 附件缩略图（G-014：attachmentReadV4 分块读 → 缩略图 + 全屏 + 保存相册）
// 会话流附件是伴随刚需；无附件数据（refs 空 / 读失败 / 演示态）不渲染占位死块。

struct AttachmentStripView: View {
    @Environment(\.conversationStore) private var store
    /// 会话 ID（message.id 为 row-\(rowId) / state-todos 等合成 id；附件读需真实 sessionId，
    /// 由调用方场景保证——连接态行消息 id 即 row 键，sessionID 传所属会话）
    let sessionID: String
    let refs: [String]
    /// 行对齐（用户消息传 .trailing：图随气泡右对齐，避免误读为 agent 附件）
    var rowAlignment: HorizontalAlignment = .leading

    var body: some View {
        FlexibleFlow(spacing: T.sp2, rowAlignment: rowAlignment) {
            ForEach(refs, id: \.self) { ref in
                AttachmentThumbView(store: store, sessionID: sessionID, ref: ref)
            }
        }
    }
}

struct AttachmentThumbView: View {
    let store: any ConversationStore
    let sessionID: String
    let ref: String

    @State private var preview: AttachmentPreview?
    @State private var failed = false
    /// 全屏承载单口（enum 合流，2026-10-09 报障「视频点击不能播放」加固：同一视图链上
    /// 挂两个 fullScreenCover(item:) 时后挂者遮蔽先挂者——图片/视频共用一个 cover 位点）
    private enum FullscreenAttachment: Identifiable {
        case image(AttachmentPreview)
        case video(URL)

        var id: String {
            switch self {
            case .image(let preview): return "img-\(preview.id)"
            case .video(let url): return "video-\(url.lastPathComponent)"
            }
        }
    }
    @State private var fullscreenItem: FullscreenAttachment?
    @State private var savedNotice: String?
    /// 视频封面帧（2026-10-08 报障「视频附件一直转圈」根因：video/* 无渲染分支，
    /// 预览到达后 image==nil、failed==false → 恒落 SpinnerView）
    @State private var videoPoster: UIImage?
    @State private var videoFileURL: URL?
    /// 点按重取仍失败（附件读不回/写盘失败）——如实上屏，不静默空点
    @State private var videoLoadFailed = false

    private var image: UIImage? {
        guard let preview, preview.mediaType.hasPrefix("image/") else { return nil }
        return UIImage(data: preview.data)
    }

    private var isVideo: Bool {
        preview?.mediaType.hasPrefix("video/") == true
    }

    var body: some View {
        Group {
            if let image {
                Button {
                    if let preview {
                        fullscreenItem = .image(preview)
                    }
                } label: {
                    thumbFrame(Image(uiImage: image).resizable().scaledToFill())
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("05-attachment-thumb")
            } else if isVideo {
                videoThumb
            } else if failed {
                // 读失败：给轻量失败态（不占死块——仅一行提示）
                HStack(spacing: T.sp1) {
                    Image(systemName: "photo.badge.exclamationmark")
                        .font(.system(size: 11))
                    Text("附件加载失败")
                        .font(T.font(11))
                }
                .foregroundColor(T.text3)
                .accessibilityIdentifier("05-attachment-failed")
            } else {
                SpinnerView(size: 14)
                    .padding(6)
                    .accessibilityIdentifier("05-attachment-loading")
            }
        }
        .task {
            preview = await store.attachmentPreview(sessionID: sessionID, ref: ref)
            failed = (preview == nil)
            if isVideo {
                await prepareVideo()
            }
        }
        .fullScreenCover(item: $fullscreenItem) { item in
            switch item {
            case .image(let preview):
                AttachmentFullscreenView(preview: preview) {
                    fullscreenItem = nil
                } onSave: {
                    saveToPhotos(preview)
                }
            case .video(let url):
                AttachmentVideoFullscreenView(videoURL: url) {
                    fullscreenItem = nil
                }
            }
        }
    }

    /// 视频缩略格：封面帧（生成失败落 video 图标兜底格，不转圈不空块）+ 播放角标；
    /// 点按进全屏播放器
    @ViewBuilder private var videoThumb: some View {
        Button {
            openVideo()
        } label: {
            thumbFrame(
                Group {
                    if videoLoadFailed {
                        ZStack {
                            T.bgInput
                            VStack(spacing: T.sp1) {
                                Image(systemName: "play.slash")
                                    .font(.system(size: 18))
                                Text("视频加载失败")
                                    .font(T.font(10))
                            }
                            .foregroundColor(T.text3)
                        }
                    } else if let videoPoster {
                        Image(uiImage: videoPoster).resizable().scaledToFill()
                    } else {
                        ZStack {
                            T.bgInput
                            Image(systemName: "video.fill")
                                .font(.system(size: 22))
                                .foregroundColor(T.text3)
                        }
                    }
                }
                .overlay(
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.white, T.text.opacity(0.55))
                        .shadow(radius: 3))
            )
        }
        .buttonStyle(PressableButtonStyle())
        .accessibilityIdentifier("05-attachment-thumb")
    }

    private func thumbFrame<Content: View>(_ content: Content) -> some View {
        content
            .frame(width: 132, height: 96)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }

    /// 视频点按（2026-10-09 用户报障「点击不能播放，是改了格式还是被清理了」）：
    /// 缓存文件可能已被系统回收（切后台再回前台即可能发生——用户发完视频切桌面核对
    /// 再回手机点按的远控使用形态必踩；封面帧在内存所以缩略图还在，点开只有黑屏）。
    /// 点按即校验文件在场，缺则重取重建；仍失败如实上屏（禁静默空点）
    private func openVideo() {
        if let url = videoFileURL, FileManager.default.fileExists(atPath: url.path) {
            fullscreenItem = .video(url)
            return
        }
        Task {
            await prepareVideo(force: true)
            if let url = videoFileURL, FileManager.default.fileExists(atPath: url.path) {
                fullscreenItem = .video(url)
            } else {
                videoLoadFailed = true
            }
        }
    }

    /// 视频数据落**稳定缓存路径**（Caches/zhupi-video/<会话-附件键>.<ext>——原实现写
    /// temporaryDirectory + 随机 UUID：iOS 可随时回收 tmp，切后台回来文件已失而封面
    /// 仍在内存，点按即黑屏；稳定键跨视图重建/返场复用同一路径，不叠随机文件垃圾）
    /// → AVAssetImageGenerator 抽 0s 帧（背景线程回调，单帧即收）。失败静默走兜底格；
    /// force = 点按重取路径（文件被回收后重建；封面已在则不重抽）
    private func prepareVideo(force: Bool = false) async {
        guard let preview else { return }
        if !force, let existing = videoFileURL,
           FileManager.default.fileExists(atPath: existing.path) { return }
        let ext: String
        switch preview.mediaType {
        case "video/quicktime": ext = "mov"
        case "video/webm": ext = "webm"
        default: ext = "mp4"
        }
        let safeKey = ("\(sessionID)-\(ref)")
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "att"
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("zhupi-video", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("zhupi-\(safeKey).\(ext)")
        do {
            try preview.data.write(to: url, options: .atomic)
        } catch {
            return
        }
        videoFileURL = url
        videoLoadFailed = false
        guard videoPoster == nil else { return }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 528, height: 384)
        videoPoster = await withCheckedContinuation { continuation in
            var resumed = false
            generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: .zero)]) { _, cgImage, _, result, _ in
                // 单帧请求通常单回调；防重复 resume 只认第一次
                guard !resumed else { return }
                resumed = true
                if result == .succeeded, let cgImage {
                    continuation.resume(returning: UIImage(cgImage: cgImage))
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    private func saveToPhotos(_ item: AttachmentPreview) {
        guard let image = UIImage(data: item.data) else {
            savedNotice = String(localized: "仅图片可保存到相册")
            return
        }
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        savedNotice = String(localized: "已保存到相册")
    }
}

/// 全屏查看（原图渲染 + 保存相册 + 关闭）
struct AttachmentFullscreenView: View {
    let preview: AttachmentPreview
    let onClose: () -> Void
    let onSave: () -> Void
    @State private var savedNotice: String?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image = UIImage(data: preview.data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .accessibilityIdentifier("05-attachment-full")
            } else {
                Text("预览不可用")
                    .font(T.font(13))
                    .foregroundColor(T.text2)
            }
        }
        .overlay(alignment: .bottom) {
            HStack {
                Button {
                    onSave()
                    savedNotice = String(localized: "已保存到相册")
                } label: {
                    Label("保存相册", systemImage: "square.and.arrow.down")
                        .font(T.font(13, .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 44)
                        .background(.ultraThinMaterial)
                        .clipShape(Capsule())
                }
                .accessibilityIdentifier("05-attachment-save")
                Spacer()
                Button {
                    onClose()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 44, height: 44)
                        .background(.ultraThinMaterial)
                        .clipShape(Circle())
                }
                .accessibilityIdentifier("05-attachment-close")
            }
            .padding(.horizontal, T.sp4)
            .padding(.bottom, T.sp3)
        }
        .overlay(alignment: .top) {
            if let savedNotice {
                Text(savedNotice)
                    .font(T.font(12, .semibold))
                    .foregroundColor(.white)
                    .padding(.vertical, T.sp1)
                    .padding(.horizontal, T.sp3)
                    .background(.ultraThinMaterial)
                    .clipShape(Capsule())
                    .padding(.top, T.sp2)
                    .transition(.opacity)
            }
        }
    }
}

/// 视频附件全屏播放（2026-10-08 报障「视频在手机端没有显示」配套：缩略格点按进入；
/// 自动开播，关闭即停并释放 player）。无保存入口——相册写入仅图片族（saveToPhotos 同口径）。
/// AVPlayer 对不支持容器（webm 等）/损坏/失联文件不弹错只黑屏——KVO 状态失败如实
/// 上屏（写面禁静默吞错 §5-12 同口径，2026-10-09 报障「点击不能播放」配套）
struct AttachmentVideoFullscreenView: View {
    let videoURL: URL
    let onClose: () -> Void
    @State private var player: AVPlayer?
    @State private var failureText: String?
    @State private var statusObserver: NSKeyValueObservation?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let player {
                VideoPlayer(player: player)
                    .accessibilityIdentifier("05-attachment-video-full")
            } else if let failureText {
                VStack(spacing: T.sp2) {
                    Image(systemName: "play.slash")
                        .font(.system(size: 26))
                        .foregroundColor(.white.opacity(0.7))
                    Text(failureText)
                        .font(T.font(13))
                        .foregroundColor(.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, T.sp4)
                }
            }
        }
        .overlay(alignment: .topTrailing) {
            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .background(.ultraThinMaterial)
                    .clipShape(Circle())
            }
            .padding(.top, T.sp2)
            .padding(.trailing, T.sp3)
            .accessibilityIdentifier("05-attachment-close")
        }
        .onAppear {
            let p = AVPlayer(url: videoURL)
            player = p
            p.play()
            statusObserver = p.observe(\.status, options: [.new]) { player, _ in
                guard player.status == .failed else { return }
                Task { @MainActor in
                    self.failureText = String(localized: "视频无法播放（格式不受支持或文件已损坏）")
                }
            }
        }
        .onDisappear {
            statusObserver?.invalidate()
            statusObserver = nil
            player?.pause()
            player = nil
        }
    }
}

/// 工作流完成卡（桌面 WorkflowCompletionCard 同款，2026-10-09 用户看桌面截图点单
/// 「手机端也做个类似的卡片」）。三段收据式：表头（种类词+run 名+状态灯）→ 交付物行/
/// 产物索引（通知载荷 artifacts，join 活投影补 bytes/条目）→ 四格数字（时间/tokens/
/// 子代理/阶段）。数字的诚实：拿不到的格写「—」不写 0【实证·上游仓
/// WorkflowCompletionCard.tsx:13 注释 + ConversationWorkflowCompletion.tsx:39-48】。
/// 产物不可点（移动端无产物侧板能力——不渲染死入口，§5 门禁口径）。
struct WorkflowCompletionCardView: View {
    let card: WorkflowCompletionCard
    /// 活投影 run（按 runId 对位；nil = tokens/子代理/阶段三格「—」）
    let run: WorkflowRunSummary?
    /// ⤢ 打开工作流面板（宿主门控接线；nil = 不渲染入口）
    let onOpenRun: (() -> Void)?
    /// 产物点按 → 内容详情 sheet（markdown/plain 渲染全文；其余类型 sheet 内如实标注）
    var onOpenArtifact: ((WorkflowCompletionArtifact) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp3) {
            header
            deliverables
            statsRow
        }
        .padding(T.sp3)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-wf-completion")
    }

    /// 表头：flowchart 图标 + 种类词 + run 名，右侧绿点「已完成」+ [⤢]
    private var header: some View {
        HStack(alignment: .top, spacing: T.sp2) {
            Image(systemName: "flowchart.fill")
                .font(.system(size: 14))
                .foregroundColor(T.text3)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "工作流已完成"))
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                Text(card.name)
                    .font(T.font(13))
                    .foregroundColor(T.text2)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
            HStack(spacing: T.sp1) {
                Circle().fill(T.accentText).frame(width: 7, height: 7)
                Text(String(localized: "已完成"))
                    .font(T.font(12, .medium))
                    .foregroundColor(T.accentText)
                if let onOpenRun {
                    Button {
                        onOpenRun()
                    } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(T.text3)
                            .frame(minWidth: 32, minHeight: 32)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("05-wf-completion-open")
                }
            }
        }
    }

    @ViewBuilder
    private var deliverables: some View {
        let primary = card.artifacts.first(where: \.primary)
        let rest = card.artifacts.filter { !($0.primary == true) }
        if primary != nil || !rest.isEmpty || card.truncated {
            VStack(alignment: .leading, spacing: T.sp2) {
                if let primary {
                    Button {
                        onOpenArtifact?(primary)
                    } label: {
                        VStack(alignment: .leading, spacing: T.sp1) {
                            Text(primary.title ?? primary.id)
                                .font(T.font(13.5, .semibold))
                                .foregroundColor(T.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if let description = primary.description, !description.isEmpty {
                                Text(description)
                                    .font(T.font(12.5))
                                    .foregroundColor(T.text3)
                                    .lineLimit(4)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            artifactMetaText(primary)
                                .font(T.font(11.5))
                                .foregroundColor(T.text3)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(T.sp2)
                        .background(T.bgElevated)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("05-wf-completion-artifact")
                }
                ForEach(Array(rest.enumerated()), id: \.element.id) { _, artifact in
                    Button {
                        onOpenArtifact?(artifact)
                    } label: {
                        HStack(spacing: T.sp2) {
                            Image(systemName: "doc.text")
                                .font(.system(size: 11))
                                .foregroundColor(T.text3)
                            Text(artifact.title ?? artifact.id)
                                .font(T.font(12.5))
                                .foregroundColor(T.text2)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                            artifactMetaText(artifact)
                                .font(T.font(11.5))
                                .foregroundColor(T.text3)
                        }
                        .frame(minHeight: 36)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if card.truncated {
                    Text(String(localized: "产物清单已截断 · 其余经桌面端查看"))
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                }
            }
        }
    }

    /// 种类词 + 大小/条目（bytes/itemCount 来自活投影 join，缺席只显种类词）
    private func artifactMetaText(_ artifact: WorkflowCompletionArtifact) -> Text {
        var parts: [String] = [Self.kindLabel(artifact.kind)]
        if let bytes = joinedArtifact(for: artifact.id)?.bytes ?? artifact.bytes {
            parts.append(Self.byteText(bytes))
        }
        if let count = joinedArtifact(for: artifact.id)?.itemCount ?? artifact.itemCount {
            parts.append(String(localized: "\(count) 条"))
        }
        return Text(parts.joined(separator: " · "))
    }

    private func joinedArtifact(for id: String) -> WorkflowArtifactBrief? {
        run?.artifactBriefs.first { $0.id == id }
    }

    /// 四格数字（桌面 figures 同序：时间/tokens/子代理/阶段）
    private var statsRow: some View {
        HStack(alignment: .top, spacing: 0) {
            figure(label: String(localized: "时间"), value: UsageStatsView.durationText(card.durationMs))
            figure(label: String(localized: "tokens"), value: run?.spentTokens.map(Self.compactCount))
            figure(label: String(localized: "子代理"), value: run.map { "\($0.actors.count)" })
            figure(label: String(localized: "阶段"), value: run?.phaseCount.map(String.init))
        }
    }

    private func figure(label: String, value: String?) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value ?? "—")
                .font(T.font(17, .semibold).monospacedDigit())
                .foregroundColor(T.text)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(label)
                .font(T.font(11))
                .foregroundColor(T.text3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }

    /// tokens 紧凑写法（上游 formatCompactCount 逐字移植：固定位数让四格等宽数字对齐）
    static func compactCount(_ count: Int) -> String {
        if count < 1_000 { return String(count) }
        if count < 1_000_000 { return String(format: "%.1fk", Double(count) / 1_000) }
        return String(format: "%.2fM", Double(count) / 1_000_000)
    }

    static func byteText(_ bytes: Int) -> String {
        if bytes < 1_000 { return "\(bytes) B" }
        if bytes < 1_000_000 { return String(format: "%.1f KB", Double(bytes) / 1_000) }
        return String(format: "%.1f MB", Double(bytes) / 1_000_000)
    }

    /// 产物种类词（六态闭集【实证·上游仓 workflow-row-meta.ts:60】；文档=markdown 桌面同款）
    static func kindLabel(_ kind: String) -> String {
        switch kind {
        case "markdown": return String(localized: "文档")
        case "chart": return String(localized: "图表")
        case "table": return String(localized: "表格")
        case "metrics": return String(localized: "指标")
        case "board": return String(localized: "看板")
        default: return String(localized: "文件")
        }
    }
}

/// 工作流产物详情 sheet（完成卡产物点按落点）：workflowRunArtifactReadV4 分块拼装
/// markdown/plain 全文渲染；其余 mediaType 如实标注「暂不支持手机端预览」；加载失败
/// 保留错误行 + 重试（读面失败不折叠成空态，§5-12 口径）。
struct WorkflowArtifactRequest: Identifiable, Equatable {
    let id = UUID()
    let sessionID: String
    let card: WorkflowCompletionCard
    let artifact: WorkflowCompletionArtifact
}

struct WorkflowArtifactSheetView: View {
    let request: WorkflowArtifactRequest
    let onClose: () -> Void
    /// 内容读取（宿主注入 viewModel 透传——store 在 VM 内私有）
    let load: (WorkflowArtifactRequest) async -> (data: Data, mediaType: String)?
    @State private var content: String?
    @State private var mediaType: String?
    @State private var failureText: String?
    @State private var isLoading = false

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    CenterLoadingView(text: "加载产物内容…")
                } else if let failureText {
                    VStack(spacing: T.sp2) {
                        Text(failureText)
                            .font(T.font(13))
                            .foregroundColor(T.text3)
                            .multilineTextAlignment(.center)
                        Button(String(localized: "重试")) { Task { await load() } }
                            .font(T.font(14, .semibold))
                            .foregroundColor(T.accentText)
                    }
                } else if let content {
                    ScrollView {
                        // markdown/plain 都走正文渲染（>6k 字内置截断护解析）
                        MarkdownMessageBody(text: content)
                            .padding(T.sp3)
                    }
                } else {
                    VStack(spacing: T.sp2) {
                        Text(request.artifact.title ?? request.artifact.id)
                            .font(T.font(14, .semibold))
                        Text(String(localized: "该产物类型暂不支持手机端预览 · 请在桌面端查看"))
                            .font(T.font(12.5))
                            .foregroundColor(T.text3)
                            .multilineTextAlignment(.center)
                    }
                    .padding(T.sp4)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(T.bg)
            .navigationTitle(request.artifact.title ?? request.artifact.id)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "关闭")) { onClose() }
                        .accessibilityIdentifier("07-wf-artifact-close")
                }
            }
        }
        .task { await load() }
    }

    /// 可渲染判定：markdown/纯文本族（v1 渲染面；二进制族如实体签名出提示页）
    private static func isRenderable(_ mediaType: String) -> Bool {
        let mt = mediaType.lowercased()
        return mt.hasPrefix("text/")
            || mt.contains("markdown")
            || mt == "application/json"
    }

    private func load() async {
        guard !isLoading else { return }
        isLoading = true
        failureText = nil
        content = nil
        defer { isLoading = false }
        guard let (data, mt) = await load(request) else {
            failureText = String(localized: "产物内容加载失败 · 桌面端未回执")
            return
        }
        mediaType = mt
        guard Self.isRenderable(mt), let text = String(data: data, encoding: .utf8),
              !text.isEmpty else {
            // 非文本族：不出空态，出如实提示（产物元数据仍可见）
            return
        }
        content = text
    }
}

// MARK: - Inline Gen UI 标记（v3.15.1 `::visualize{...}`）

/// 上游 `packages/ui/src/gen-ui/domain/references.ts` 的移动端最小移植：
/// 只做「剥离标记 + 提取 path/title」，不渲染 HTML（移动端无插件沙箱）。
/// - 支持 `::visualize{path="…" title="…"}`、`::visualize{"path":…}` 与
///   Unicode `\u{E000}visualize\u{E001}…\u{E002}` 三种形态；
/// - 跳过围栏代码块；流式未闭合标记整段隐藏，避免半截 JSON 上屏。
enum GenUiMarker {
    struct Reference: Identifiable, Equatable {
        let id: String
        let path: String?
        let title: String?
    }

    struct Projection: Equatable {
        var visible: String
        var references: [Reference]
    }

    static func project(_ text: String, streaming: Bool) -> Projection {
        guard !text.isEmpty else { return Projection(visible: "", references: []) }
        let ns = text as NSString
        let codeRanges = fencedCodeRanges(ns)
        var removals: [NSRange] = []
        var references: [Reference] = []
        var cursor = 0

        while cursor < ns.length {
            let search = NSRange(location: cursor, length: ns.length - cursor)
            let ascii = ns.range(of: "::visualize", options: [], range: search)
            let unicode = ns.range(of: "\u{E000}visualize\u{E001}", options: [], range: search)
            var found = ascii
            var isUnicode = false
            if unicode.location != NSNotFound,
               (found.location == NSNotFound || unicode.location < found.location) {
                found = unicode
                isUnicode = true
            }
            guard found.location != NSNotFound else { break }
            if codeRanges.contains(where: { NSLocationInRange(found.location, $0) }) {
                cursor = found.location + found.length
                continue
            }
            let bodyStart = found.location + found.length

            if isUnicode {
                let endMarker = ns.range(
                    of: "\u{E002}", options: [],
                    range: NSRange(location: bodyStart, length: ns.length - bodyStart))
                guard endMarker.location != NSNotFound else {
                    removals.append(NSRange(location: found.location, length: ns.length - found.location))
                    break
                }
                let body = ns.substring(
                    with: NSRange(location: bodyStart, length: endMarker.location - bodyStart))
                if let reference = parseReference(body) { references.append(reference) }
                removals.append(NSRange(
                    location: found.location,
                    length: endMarker.location + endMarker.length - found.location))
                cursor = endMarker.location + endMarker.length
                continue
            }

            var brace = bodyStart
            while brace < ns.length, isWhitespace(ns.character(at: brace)) {
                brace += 1
            }
            guard brace < ns.length, ns.character(at: brace) == 0x7B else {
                // 形态不完整：整段隐藏，不把半截标记留给用户
                removals.append(NSRange(location: found.location, length: ns.length - found.location))
                break
            }
            var depth = 0
            var inString = false
            var escaped = false
            var end = NSNotFound
            var index = brace
            while index < ns.length {
                let ch = ns.character(at: index)
                if inString {
                    if escaped { escaped = false }
                    else if ch == 0x5C { escaped = true }
                    else if ch == 0x22 { inString = false }
                } else if ch == 0x22 {
                    inString = true
                } else if ch == 0x7B {
                    depth += 1
                } else if ch == 0x7D {
                    depth -= 1
                    if depth == 0 { end = index; break }
                }
                index += 1
            }
            guard end != NSNotFound else {
                // 流式半截 / 非法：从标记起到文末隐藏
                removals.append(NSRange(location: found.location, length: ns.length - found.location))
                break
            }
            let body = ns.substring(with: NSRange(location: brace, length: end - brace + 1))
            if let reference = parseReference(body) { references.append(reference) }
            removals.append(NSRange(location: found.location, length: end + 1 - found.location))
            cursor = end + 1
        }

        // 流式尾部半截 `::visua…`：上游同款抑制（标记可出现在行中，不要求行首）。
        if streaming, let markerStart = text.range(of: "::", options: .backwards) {
            let tail = String(text[markerStart.lowerBound...])
            if !tail.isEmpty, "::visualize".hasPrefix(tail) {
                removals.append(NSRange(location: ns.length - tail.utf16.count,
                                        length: tail.utf16.count))
            }
        }

        let visible = NSMutableString(string: text)
        for range in removals.sorted(by: { $0.location > $1.location }) {
            guard range.location >= 0, range.location + range.length <= visible.length else { continue }
            visible.replaceCharacters(in: range, with: "")
        }
        return Projection(
            visible: (visible as String).trimmingCharacters(in: .whitespacesAndNewlines),
            references: references)
    }

    private static func isWhitespace(_ value: unichar) -> Bool {
        value == 0x20 || value == 0x09 || value == 0x0A || value == 0x0D
    }

    private static func parseReference(_ body: String) -> Reference? {
        var path: String?
        var title: String?
        if let data = body.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            path = (object["path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            title = (object["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            path = capture(body, key: "path")
            title = capture(body, key: "title")
        }
        guard let path, !path.isEmpty || !(title ?? "").isEmpty else {
            if let title, !title.isEmpty {
                return Reference(id: "title:\(title)", path: nil, title: title)
            }
            return nil
        }
        let identity = "\(path)|\(title ?? "")"
        return Reference(id: identity, path: path.isEmpty ? nil : path, title: title)
    }

    private static func capture(_ body: String, key: String) -> String? {
        let pattern = "\\b\(key)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|'((?:\\\\.|[^'])*)')"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(location: 0, length: (body as NSString).length)
        guard let match = regex.firstMatch(in: body, range: range) else { return nil }
        for index in 1..<match.numberOfRanges {
            let candidate = (body as NSString).substring(with: match.range(at: index))
            if !candidate.isEmpty {
                return candidate
                    .replacingOccurrences(of: "\\\"", with: "\"")
                    .replacingOccurrences(of: "\\\\", with: "\\")
            }
        }
        return nil
    }

    /// 围栏代码块范围（``` … ```）；代码块里的 `::visualize` 是示例，不执行。
    private static func fencedCodeRanges(_ ns: NSString) -> [NSRange] {
        var ranges: [NSRange] = []
        var cursor = 0
        var open: Int?
        while cursor < ns.length {
            let found = ns.range(of: "```", options: [], range: NSRange(location: cursor, length: ns.length - cursor))
            guard found.location != NSNotFound else { break }
            if let start = open {
                ranges.append(NSRange(location: start, length: found.location + found.length - start))
                open = nil
            } else {
                open = found.location
            }
            cursor = found.location + found.length
        }
        if let start = open {
            ranges.append(NSRange(location: start, length: ns.length - start))
        }
        return ranges
    }
}

/// 移动端不渲染 Gen UI HTML：给出可读占位（桌面端查看），避免原始 JSON 污染正文。
struct GenUiReferenceCard: View {
    let reference: GenUiMarker.Reference

    private var filename: String? {
        guard let path = reference.path, !path.isEmpty else { return nil }
        return (path as NSString).lastPathComponent
    }

    var body: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "rectangle.on.rectangle.angled")
                .font(.system(size: 15))
                .foregroundColor(T.blue)
            VStack(alignment: .leading, spacing: 2) {
                Text(reference.title ?? filename ?? String(localized: "可视化内容"))
                    .font(T.font(12.5, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Text(String(localized: "内联可视化 · 请在桌面端查看"))
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("05-genui-card")
    }
}

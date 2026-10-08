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
                UserBubble(text: message.text)
                    // P1-3 长按菜单（全 App contextMenu 先例 ConversationListView）：
                    // 「编辑重发」仅连接态且行游标在场才出现；「复制文本」恒有
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
                if !message.text.isEmpty {
                    HStack(alignment: .top, spacing: T.sp2) {
                        AgentAvatar(size: 22)
                        MarkdownMessageBody(text: message.text, streaming: message.status == .streaming)
                    }
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
                    ToolCallCardView(call: call) { action in
                        // G-015：失败工具卡重试 → retryTurn（rowId 来自行合成 id，
                        // entityId 由卡片渲染门槛保证在场）
                        if action == "retry", let rowId, call.entityId != nil {
                            onRetryToolCall(call, rowId)
                        }
                    }
                }
                if let todos = message.todos {
                    TodoCardView(todos: todos)
                }
                if let question = message.question {
                    QuestionCardView(question: question, onReply: onQuickReply)
                }
                // P1-3 反馈行（设计稿 §3.1 门槛：仅助手正文行——reasoning/subagent/
                // artifact/state-todos 等合成消息 rowKind ≠ assistantText 天然排除；
                // 游标缺失/非连接态不渲染；流式首帧行空文本不渲染，文本抵达随重建出现）
                if let onFeedback, message.rowKind == "assistantText",
                   rowId != nil, message.entityId != nil, !message.text.isEmpty {
                    MessageFeedbackRow(state: feedbackState) { value in
                        await onFeedback(value)
                    }
                }
            }
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
// 口径（Qoder 官方三态 + BiuZ failed 扩展）：空心圆=未开始、旋转圆=进行中、复选框=已完成；
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
    @State private var fullscreen: AttachmentPreview?
    @State private var savedNotice: String?

    private var image: UIImage? {
        guard let preview, preview.mediaType.hasPrefix("image/") else { return nil }
        return UIImage(data: preview.data)
    }

    var body: some View {
        Group {
            if let image {
                Button {
                    fullscreen = preview
                } label: {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 132, height: 96)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("05-attachment-thumb")
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
        }
        .fullScreenCover(item: $fullscreen) { item in
            AttachmentFullscreenView(preview: item) {
                fullscreen = nil
            } onSave: {
                saveToPhotos(item)
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

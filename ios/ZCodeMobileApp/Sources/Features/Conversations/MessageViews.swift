import SwiftUI

/// 消息视图路由：用户气泡 / Agent 正文 / 工具卡 / todo 卡 / 提问卡
/// （v3 纠偏：快捷回复连接态恢复可用，应答经 resolveInteraction 桌面代执行）
struct MessageView: View {
    let message: ChatMessage
    /// 所属会话 ID（attachmentReadV4 的 sessionId 入参；message.id 是行合成 id，不能冒充）
    var sessionID: String = ""
    var onQuickReply: (String) -> Void
    var onRetryToolCall: (ToolCall, Int) -> Void = { _, _ in }

    /// 消息行 rowId（id 形如 "row-<n>"，G-015 retryTurn 游标之一）
    private var rowId: Int? {
        guard message.id.hasPrefix("row-") else { return nil }
        return Int(message.id.dropFirst(4))
    }

    var body: some View {
        switch message.role {
        case .user:
            // 桌面口径：用户消息附件位于气泡上方、右对齐（G-014 缩略图行）
            VStack(alignment: .trailing, spacing: T.sp1) {
                if !message.attachments.isEmpty {
                    AttachmentStripView(sessionID: sessionID, refs: message.attachments)
                }
                UserBubble(text: message.text)
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
            }
        }
    }
}

/// 用户右侧气泡（渐变底）
struct UserBubble: View {
    let text: String

    var body: some View {
        HStack {
            Spacer(minLength: 56)
            Text(text)
                .font(T.font(14.5))
                .foregroundColor(T.text)
                .lineSpacing(4)
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

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            ForEach(TinyMarkdown.parse(text)) { block in
                MarkdownBlockView(block: block)
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

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: T.sp2) {
                    Text(call.kind.rawValue.uppercased())
                        .font(T.mono(11, .semibold))
                        .foregroundColor(T.text3)
                        .frame(minWidth: 40, alignment: .leading)
                    Text(call.target)
                        .font(T.mono(11.5))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
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
            if let output = call.output {
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(output)
                        .font(T.mono(12))
                        .foregroundColor(T.text2)
                        .lineSpacing(4)
                        .padding(T.sp3)
                        .frame(minWidth: UIScreen.main.bounds.width - 96, alignment: .leading)
                }
                .background(T.bgCode)
                .accessibilityIdentifier("05-toolcard-body-bash")
            }
        case .edit:
            if let diff = call.diff {
                VStack(spacing: 0) {
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
                .background(T.bgCode)
                .accessibilityIdentifier("05-toolcard-body-edit")
            }
        case .ask, .browser:
            Text(call.target)
                .font(T.font(12))
                .foregroundColor(T.text2)
                .padding(T.sp3)
                .frame(maxWidth: .infinity, alignment: .leading)
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
                    Text("已思考 \(max(0, Int(Date().timeIntervalSince(startedAt))))s")
                }
            }
        case .done:
            let characters = thinking.text.count
            if let duration = thinking.duration {
                Text("用时 \(Int(duration.rounded()))s · \(characters) 字")
            } else {
                Text("\(characters) 字")
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
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
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

    var body: some View {
        FlexibleFlow(spacing: T.sp2) {
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

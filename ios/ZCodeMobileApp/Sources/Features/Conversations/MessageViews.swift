import SwiftUI

/// 消息视图路由：用户气泡 / Agent 正文 / 工具卡 / todo 卡 / 提问卡
/// （v3 纠偏：快捷回复连接态恢复可用，应答经 resolveInteraction 桌面代执行）
struct MessageView: View {
    let message: ChatMessage
    var onQuickReply: (String) -> Void

    var body: some View {
        switch message.role {
        case .user:
            UserBubble(text: message.text)
        case .agent:
            VStack(alignment: .leading, spacing: T.sp2) {
                if !message.text.isEmpty {
                    HStack(alignment: .top, spacing: T.sp2) {
                        AgentAvatar(size: 22)
                        MarkdownMessageBody(text: message.text, streaming: message.status == .streaming)
                    }
                }
                if let call = message.toolCall {
                    ToolCallCardView(call: call)
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
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 13))
                .foregroundColor(T.red)
        }
    }

    @ViewBuilder
    private var detailBody: some View {
        Divider().overlay(T.border)
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

// MARK: - todo 拆解卡（done 划线 / now 蓝框 / todo 空框）

struct TodoCardView: View {
    let todos: [TodoItem]

    private var doneCount: Int { todos.filter { $0.state == .done }.count }

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack {
                Text("任务拆解")
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text2)
                Spacer()
                Text("\(doneCount)/\(todos.count)")
                    .font(T.mono(11, .semibold))
                    .foregroundColor(T.blue)
            }
            ThinProgressBar(progress: Double(doneCount) / Double(max(todos.count, 1)))
            ForEach(todos) { item in
                todoRow(item)
            }
        }
        .card()
        .accessibilityIdentifier("05-todocard")
    }

    @ViewBuilder
    private func todoRow(_ item: TodoItem) -> some View {
        HStack(spacing: T.sp2) {
            todoIcon(item)
            Text(item.title)
                .font(T.font(12.5, item.state == .now ? .semibold : .regular))
                .strikethrough(item.state == .done, color: T.text3)
                .foregroundColor(item.state == .done ? T.text3 : T.text)
                .lineLimit(1)
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
    }

    @ViewBuilder
    private func todoIcon(_ item: TodoItem) -> some View {
        switch item.state {
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.accentText)
        case .now:
            Circle().strokeBorder(T.blue, lineWidth: 1.5).frame(width: 14, height: 14)
        case .todo:
            Circle().strokeBorder(T.borderStrong, lineWidth: 1.5).frame(width: 14, height: 14)
        }
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

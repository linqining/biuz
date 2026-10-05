import SwiftUI

/// 屏 06 · 审批请求 Sheet：授权范围 52px 单选行、拒绝/批准 48px、追问/稍后 44px 出口。
/// v3 纠偏：连接态动作栏恢复（批准/拒绝经 v4 resolveInteraction 下发，桌面代执行），
/// 演示态 MockTaskStore 路径行为不变。
struct ApprovalSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.taskStore) private var store
    @Environment(\.conversationStore) private var conversationStore
    let task: TaskRecord

    enum AuthScope: String, CaseIterable, Identifiable {
        case once, task, always
        var id: String { rawValue }
        var label: String {
            switch self {
            case .once: return "仅本次"
            case .task: return "本任务内"
            case .always: return "始终允许"
            }
        }
        var detail: String {
            switch self {
            case .once: return "只批准这一条命令"
            case .task: return "同任务同类命令不再询问"
            case .always: return "同类命令全部放行"
            }
        }
        var identifier: String {
            switch self {
            case .once: return "06-choice-once"
            case .task: return "06-choice-task"
            case .always: return "06-choice-always"
            }
        }
    }

    @State private var scope: AuthScope = .once
    @State private var decisionToast: String?
    /// G-025 追问输入态
    @State private var showFollowupInput = false
    @State private var followupText = ""

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            ScrollView {
                VStack(alignment: .leading, spacing: T.sp4) {
                    titleRow
                    commandCard
                    scopeSection
                    decisionToastRow
                }
                .padding(T.sp4)
            }
            actionBar
            exitActions
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .interactiveDismissDisabled(true) // 审批 Sheet 下拉不关闭（spec 3.3/4.06）
        .overlay(alignment: .top) {
            Text("任务在等待你的批准")
                .font(T.font(11, .semibold))
                .foregroundColor(T.orange)
                .padding(.horizontal, T.sp3)
                .frame(height: 26)
                .background(T.orangeDim)
                .clipShape(Capsule())
                .offset(y: -38)
        }
    }

    private var titleRow: some View {
        HStack(spacing: T.sp3) {
            Image(systemName: "shield.lefthalf.filled")
                .font(.system(size: 22))
                .foregroundColor(T.orange)
                .frame(width: 44, height: 44)
                .background(T.orangeDim)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
            VStack(alignment: .leading, spacing: 2) {
                Text("关键操作待批准")
                    .font(T.font(17, .bold))
                    .foregroundColor(T.text)
                Text(task.title)
                    .font(T.font(12))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(.top, T.sp2)
    }

    /// 命令卡：类型行 + mono 命令体 12px + 影响摘要（连接态缺命令数据时退化为占位说明，
    /// 不渲染空 mono 块避免死控件）
    @ViewBuilder
    private var commandCard: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Text("BASH")
                    .font(T.mono(11, .semibold))
                    .foregroundColor(T.text3)
                Text(task.directory)
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            if let command = task.pendingCommand, !command.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(command)
                        .font(T.mono(12))
                        .foregroundColor(T.text)
                        .padding(T.sp2)
                        .frame(minWidth: 0, alignment: .leading)
                        .background(T.bgCode)
                        .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .accessibilityIdentifier("06-perm-cmd")
            } else {
                Text("命令详情以桌面端交互卡为准（待审批对象：\(task.title)）")
                    .font(T.font(12))
                    .foregroundColor(T.text2)
                    .padding(T.sp2)
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                    .background(T.bgCode)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                    .accessibilityIdentifier("06-perm-cmd")
            }
            if let impact = task.pendingImpact, !impact.isEmpty {
                HStack(spacing: T.sp1) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 11))
                        .foregroundColor(T.orange)
                    Text(impact)
                        .font(T.font(11.5))
                        .foregroundColor(T.text2)
                }
            }
        }
        .card()
    }

    /// 授权范围：三行 52px 卡片式单选（默认仅本次，「始终允许」红字提示）
    private var scopeSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("授权范围")
                .font(T.font(13, .semibold))
                .foregroundColor(T.text3)
            VStack(spacing: T.sp2) {
                ForEach(AuthScope.allCases) { item in
                    Button {
                        scope = item
                    } label: {
                        HStack(spacing: T.sp3) {
                            Image(systemName: scope == item ? "largecircle.fill.circle" : "circle")
                                .font(.system(size: 17))
                                .foregroundColor(scope == item ? T.accent : T.borderStrong)
                            VStack(alignment: .leading, spacing: 1) {
                                HStack(spacing: T.sp2) {
                                    Text(item.label)
                                        .font(T.font(14, .semibold))
                                        .foregroundColor(T.text)
                                    if item == .always {
                                        Text("需谨慎")
                                            .font(T.font(10.5, .semibold))
                                            .foregroundColor(T.red)
                                    }
                                }
                                Text(item.detail)
                                    .font(T.font(11.5))
                                    .foregroundColor(T.text3)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 52)
                        .background(T.bgCard)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .overlay(RoundedRectangle(cornerRadius: T.rM)
                            .stroke(scope == item ? T.accent.opacity(0.6) : T.border, lineWidth: 1))
                    }
                    .buttonStyle(PressableButtonStyle())
                    .accessibilityIdentifier(item.identifier)
                }
            }
        }
    }

    @ViewBuilder
    private var decisionToastRow: some View {
        if let toast = decisionToast {
            Text(toast)
                .font(T.font(12))
                .foregroundColor(T.accentText)
                .frame(maxWidth: .infinity, minHeight: 32)
                .background(T.accentDim)
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
        }
    }

    /// 批准/拒绝动作（演示态 MockTaskStore 路径；连接态经 v4 resolveInteraction 下发）
    private var actionBar: some View {
        HStack(spacing: 10) {
            Button {
                Task {
                    await store.reject(taskID: task.id)
                    decisionToast = "已拒绝，Agent 正在调整方案"
                    dismissAfterDecision()
                }
            } label: {
                Text("拒绝")
                    .font(T.font(15, .semibold))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
            }
            .accessibilityIdentifier("06-act-reject")

            Button {
                Task {
                    await store.approve(taskID: task.id)
                    decisionToast = "已批准执行"
                    dismissAfterDecision()
                }
            } label: {
                Text("批准执行")
                    .font(T.font(15, .semibold))
                    .foregroundColor(T.onAccent)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .accessibilityIdentifier("06-act-approve")
        }
        .padding(.horizontal, T.sp4)
        .padding(.top, T.sp2)
    }

    private var exitActions: some View {
        VStack(spacing: 0) {
            // G-025：追问接真——文本输入后经会话通道 sendText 下发（桌面会话流出现
            // 该追问；不 resolveInteraction，任务保持待操作）。发送失败给错误提示。
            TextActionButton(title: "追问 Agent，再决定", tint: T.text2, identifier: "06-act-followup") {
                showFollowupInput = true
            }
            TextActionButton(title: "稍后处理", tint: T.text3, identifier: "06-act-later") {
                dismiss()
            }
        }
        .padding(.bottom, T.sp3)
        .alert("追问 Agent", isPresented: $showFollowupInput) {
            TextField("想追问什么…", text: $followupText)
                .accessibilityIdentifier("06-field-followup")
            Button("取消", role: .cancel) { followupText = "" }
            Button("发送") { Task { await sendFollowup() } }
                .accessibilityIdentifier("06-act-followup-send")
        } message: {
            Text("追问将发送到该任务的会话流；批准/拒绝仍待你决定。")
        }
    }

    /// 追问下发（G-025）：演示态走 Mock 会话通道（会话流出现追问），连接态 sendText
    /// 桌面代执行；失败（未连接/信封被拒）如实提示，不再给假反馈
    private func sendFollowup() async {
        let text = followupText.trimmingCharacters(in: .whitespacesAndNewlines)
        followupText = ""
        guard !text.isEmpty else { return }
        let ack = await conversationStore.send(text, in: task.id)
        // send 返回是否已受理（v3 纠偏后的接口语义：false=未送达）
        if ack {
            decisionToast = String(localized: "已把追问发送给 Agent，任务保留在待操作")
        } else {
            decisionToast = String(localized: "追问发送失败 · 请确认桌面端连接后再试")
        }
    }

    private func dismissAfterDecision() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { dismiss() }
    }
}

import SwiftUI

/// 屏 06 · 审批请求 Sheet。U-4/A-3 改造（设计稿 §3.2）：授权范围三档 52px 单选卡
/// 改与会话内审批卡同构的 44pt chips 行（两档 once/always + 服务端自定义档直选），
/// 决议 answer={optionId}（四族 allowOnce/allowAlways/rejectOnce/rejectAlways 由
/// PermissionOptionMatrix 按服务端 options 组合拼好透传）。U-5：结果三态如实回传，
/// 不再无条件假成功 + dismiss。演示态 MockTaskStore 路径行为不变（忽略 optionId）。
struct ApprovalSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.taskStore) private var store
    @Environment(\.conversationStore) private var conversationStore
    let task: TaskRecord

    /// 授权范围两档（A-3/设计稿 §3.2 规则 4：task 档在 wire 四族值域无对应，删除；
    /// 服务端自定义 id 经直选 chip 呈现，不进两步矩阵）
    enum AuthScope: String, CaseIterable, Identifiable {
        case once, always
        var id: String { rawValue }
        var label: String {
            switch self {
            case .once: return "仅本次"
            case .always: return "始终允许"
            }
        }
        var identifier: String {
            switch self {
            case .once: return "06-choice-once"
            case .always: return "06-choice-always"
            }
        }
    }

    @State private var scope: AuthScope = .once
    @State private var decisionToast: String?
    /// U-5：决议进行中（两钮禁用，防重复下发）
    @State private var deciding = false
    /// 服务端 options 投影（A-3：渲染服务端选项；加载失败/演示态为空 → 默认两档矩阵）
    @State private var interactionOptions: [RemoteInteractionOption] = []
    /// workspaceHookReview 信任审核（kind 分派：在场时渲染专属卡，授权范围矩阵
    /// 与批准/拒绝两钮让位——该 kind 应答走 respondWorkspaceHookReview 而非
    /// resolveInteraction）
    @State private var hookInteraction: RemotePendingInteraction?
    /// G-025 追问输入态
    @State private var showFollowupInput = false
    @State private var followupText = ""
    /// P2-7B「稍后处理」下发中（防重复点按）
    @State private var snoozing = false

    private var matrix: PermissionOptionMatrix {
        PermissionOptionMatrix(options: interactionOptions)
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            ScrollView {
                VStack(alignment: .leading, spacing: T.sp4) {
                    titleRow
                    commandCard
                    if let hookInteraction {
                        // kind 分派：workspaceHookReview 信任审核（Web 对齐）——复用
                        // 会话内同款卡（闭包直连 conversationStore），成功 toast 走本
                        // sheet 的 decisionToast 通道
                        WorkspaceHookReviewCard(
                            interaction: hookInteraction,
                            onTrust: { ids in
                                let failure = await hookAckFailure(
                                    await conversationStore.respondWorkspaceHookReview(
                                        task.id, reviewItemIds: ids),
                                        verb: String(localized: "信任"))
                                if failure == nil {
                                    decisionToast = String(localized: "已信任所选 hook 项")
                                    dismissAfterDecision()
                                }
                                return failure
                            },
                            onRequest: {
                                await hookAckFailure(
                                    await conversationStore.requestWorkspaceHookReview(task.id),
                                    verb: String(localized: "请求审核"))
                            },
                            onRevoke: { ids in
                                let failure = await hookAckFailure(
                                    await conversationStore.revokeWorkspaceHookTrust(
                                        task.id, reviewItemIds: ids),
                                        verb: String(localized: "撤销信任"))
                                if failure == nil {
                                    decisionToast = String(localized: "已撤销信任")
                                    dismissAfterDecision()
                                }
                                return failure
                            })
                    } else {
                        scopeSection
                    }
                    decisionToastRow
                }
                .padding(T.sp4)
            }
            if hookInteraction == nil, !matrix.isAllCustom || matrix.customOptions.isEmpty {
                actionBar
            }
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
        .task {
            // A-3：渲染服务端 options（会话 pendingInteractions 投影——本 sheet 数据源
            // 只有 TaskRecord，交互选项须经会话读面取）。演示态/无挂起交互为空，
            // 走默认两档矩阵。kind 分派：workspaceHookReview 在场时走信任审核卡
            let list = await conversationStore.pendingInteractionList(in: task.id)
            if let hook = list.first(where: { $0.isWorkspaceHookReview }) {
                hookInteraction = hook
            } else if let interaction = list.first(where: { $0.isPermission }) {
                interactionOptions = interaction.options
                scope = PermissionOptionMatrix(options: interaction.options)
                    .defaultScopeAlways() ? .always : .once
            }
        }
    }

    /// hook 审核命令回执 → 失败文案（ChatViewModel.controlFeedback 同口径；
    /// nil = 成功/已受理）。写面禁止静默：nil 回执与 rejected 均如实透出
    private func hookAckFailure(_ ack: JSONValue?, verb: String) -> String? {
        guard let ack else {
            return String(localized: "\(verb)未送达（连接中断或不在连接态）")
        }
        let status = ack["status"]?.stringValue
            ?? ack.objectValue?["ack"]?.objectValue?["status"]?.stringValue
        switch status {
        case nil, "accepted", "noop", "applied", "ok":
            return nil
        default:
            var detail = ack["reasonCode"]?.stringValue ?? status ?? "?"
            if let message = ack["message"]?.stringValue,
               let range = message.range(of: "\"message\": \"") {
                let tail = message[range.upperBound...]
                if let end = tail.firstIndex(of: "\"") {
                    detail += "：" + tail[..<end]
                }
            }
            return String(localized: "桌面端拒绝（\(detail)）")
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

    /// 授权范围（U-4 改造：三档 52px 单选卡 → 与会话内审批卡同构的 44pt chips 行）。
    /// 默认两档（options 空 / 含四族）；服务端携带自定义档时追加直选 chip（自带
    /// 方向语义，点选即发）；全为非四族 options 时 chips 即选项（单选即决，
    /// 两钮整组隐藏——actionBar 条件渲染）
    private var scopeSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("授权范围")
                .font(T.font(13, .semibold))
                .foregroundColor(T.text3)
            HStack(spacing: T.sp2) {
                if matrix.isAllCustom, !matrix.customOptions.isEmpty {
                    ForEach(matrix.customOptions) { option in
                        directChip(option)
                    }
                } else {
                    if matrix.showsOnceChip {
                        scopeChip(.once)
                    }
                    if matrix.showsAlwaysChip {
                        scopeChip(.always)
                    }
                    ForEach(matrix.customOptions) { option in
                        directChip(option)
                    }
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("06-perm-scope")
        }
    }

    /// 范围 chip（44pt 胶囊；ApprovalInteractionCard.scopeChip 同款样式，两处权限 UI 归一）
    private func scopeChip(_ item: AuthScope) -> some View {
        Button {
            scope = item
        } label: {
            HStack(spacing: T.sp1) {
                Text(item.label)
                if item == .always {
                    Text("需谨慎")
                        .font(T.font(10.5, .semibold))
                        .foregroundColor(scope == item ? T.onAccent : T.red)
                }
            }
            .font(T.font(12, .medium))
            .foregroundColor(scope == item ? T.onAccent : T.text2)
            .padding(.horizontal, T.sp2)
            .frame(minHeight: 44)
            .background(scope == item ? T.accent : T.bgInput)
            .clipShape(Capsule())
        }
        .disabled(deciding)
        .accessibilityIdentifier(item.identifier)
    }

    /// 服务端自定义选项 chip（单选即决：点选即发该原生 optionId）
    private func directChip(_ option: RemoteInteractionOption) -> some View {
        Button {
            decide(approved: nil, optionId: option.id, scopeLabel: option.label)
        } label: {
            Text(option.label)
                .font(T.font(12, .medium))
                .foregroundColor(T.text2)
                .padding(.horizontal, T.sp2)
                .frame(minHeight: 44)
                .background(T.bgInput)
                .clipShape(Capsule())
        }
        .disabled(deciding || snoozing)
        .accessibilityIdentifier("06-choice-custom-\(option.id)")
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
                .accessibilityIdentifier("06-perm-decision-toast")
        }
    }

    /// 批准/拒绝动作（U-4：optionId 透传——按 scope 拼/选四族 optionId；
    /// U-5：TaskDecisionOutcome 三态如实回传，失败不 dismiss、按钮恢复可点）。
    /// 演示态 MockTaskStore 路径恒 .accepted，行为不变
    private var actionBar: some View {
        HStack(spacing: 10) {
            Button {
                decideMatrix(approved: false)
            } label: {
                Text("拒绝")
                    .font(T.font(15, .semibold))
                    .foregroundColor(T.red)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
            }
            .disabled(deciding
                      || !matrix.canDecide(approved: false, always: scope == .always))
            .accessibilityIdentifier("06-act-reject")

            Button {
                decideMatrix(approved: true)
            } label: {
                Text("批准执行")
                    .font(T.font(15, .semibold))
                    .foregroundColor(T.onAccent)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .disabled(deciding
                      || !matrix.canDecide(approved: true, always: scope == .always))
            .accessibilityIdentifier("06-act-approve")
        }
        .padding(.horizontal, T.sp4)
        .padding(.top, T.sp2)
    }

    private var exitActions: some View {
        VStack(spacing: 0) {
            // G-025：追问接真——文本输入后经会话通道 sendText 下发（桌面会话流出现
            // 该追问；不 resolveInteraction，任务保持待操作）。发送失败给错误提示。
            TextActionButton(title: "追问 Agent，再决定", tint: T.text2, action: {
                showFollowupInput = true
            }, identifier: "06-act-followup")
            TextActionButton(title: "稍后处理", tint: T.text3, action: {
                Task { await snoozeInteraction() }
            }, identifier: "06-act-later")
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

    /// 两步矩阵决议入口：按 scope 拼/选四族 optionId（approved=false 走 reject 面）
    private func decideMatrix(approved: Bool) {
        guard let optionId = matrix.optionId(approved: approved, always: scope == .always) else {
            decisionToast = String(localized: "该授权范围无对应选项，请切换范围")
            return
        }
        decide(approved: approved, optionId: optionId, scopeLabel: scope.label)
    }

    /// 决议下发（U-4 传参落点 + U-5 如实回传）。scopeLabel 供成功 toast 范围回显
    /// （与所点 chips 闭环，设计稿 §5.2）。
    private func decide(approved: Bool?, optionId: String, scopeLabel: String) {
        deciding = true
        Task {
            let outcome: TaskDecisionOutcome
            if approved == false {
                outcome = await store.reject(taskID: task.id, optionId: optionId)
            } else {
                outcome = await store.approve(taskID: task.id, optionId: optionId)
            }
            deciding = false
            switch outcome {
            case .accepted:
                decisionToast = approved == false
                    ? String(localized: "已拒绝 · \(scopeLabel)")
                    : String(localized: "已批准执行 · \(scopeLabel)")
                dismissAfterDecision()
            case .interactionMissing:
                // U-5 根治：不再无条件假成功——如实提示 + 后台刷新任务状态
                // （tasks() 为只读刷新，结果经观察流回流看板；toast 已承载用户反馈）
                decisionToast = String(localized: "未找到待审批交互 · 可能已在桌面端处理")
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
                _ = await store.tasks()
            case .undelivered:
                decisionToast = String(localized: "审批未送达 · 连接恢复后再试")
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            case .rejected(let detail):
                decisionToast = detail
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
    }

    /// P2-7B「稍后处理」（设计稿 §7B：纯 dismiss 替换为 snoozeInteractionAutoResolution
    /// 下发）。interactionId 从会话 pendingInteractions 投影取（任务页仅有 kinds:
    /// ["permission"] 单条在手）——投影不在手（演示态/会话无挂起交互）时维持原纯
    /// dismiss 口径诚实降级，不虚构「已稍后」；下发失败卡片（sheet）保留 + toast。
    private func snoozeInteraction() async {
        guard !snoozing else { return }
        snoozing = true
        defer { snoozing = false }
        let list = await conversationStore.pendingInteractionList(in: task.id)
        guard let interaction = list.first(where: { $0.isPermission }) else {
            dismiss() // interactionId 不在手：诚实降级为纯 dismiss（不伪造成功态）
            return
        }
        let ack = await conversationStore.snoozeInteractionAutoResolution(
            task.id, interactionId: interaction.id)
        let status = ack?["status"]?.stringValue ?? "rejected"
        if status == "accepted" || status == "noop" || status == "applied" || status == "ok" {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
        } else {
            let reason = ack?["reasonCode"]?.stringValue ?? status
            decisionToast = String(localized: "稍后失败 · \(reason)")
        }
    }

    private func dismissAfterDecision() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { dismiss() }
    }
}

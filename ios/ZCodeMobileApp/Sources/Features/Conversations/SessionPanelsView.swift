import SwiftUI

// MARK: - 会话面板容器（goal / plan / 工作流 / btw 后台 / side 子代理）
//
// 用户对齐桌面基准的三点诉求：
// 1. 可折叠——默认收起为一行 chips（数据在场的面板才出 chip），点选展开、再点收起，
//    不再固定占据消息流上方空间；
// 2. 逐项展开——工作流阶段行可展开为子代理实例行（「N 个任务」），带 sessionId 的
//    实例可下钻只读转录（桌面端「查看子 agent 任务对话」同构）；
// 3. 控制面——目标暂停/继续（pauseGoal/resumeGoal）、子代理模型（amendWorkflowRunSettings
//    .subagentModel）、并发 agent 数（amendWorkflowRunSettings.maxConcurrency）、
//    运行取消/恢复（cancelBackgroundWork/resumeWorkflowRun）。
// 边界不变：全部为「客户端发命令、桌面代执行」（ReadOnlyGate command 类放行）。

enum SessionPanelKind: String, CaseIterable, Identifiable {
    case goal, plan, workflow, works, subagents
    var id: String { rawValue }

    var label: String {
        switch self {
        case .goal: return String(localized: "目标")
        case .plan: return String(localized: "计划")
        case .workflow: return String(localized: "工作流")
        case .works: return String(localized: "后台")
        case .subagents: return String(localized: "子代理")
        }
    }

    var icon: String {
        switch self {
        case .goal: return "target"
        case .plan: return "list.clipboard"
        case .workflow: return "flowchart.fill"
        case .works: return "clock.arrow.circlepath"
        case .subagents: return "cpu"
        }
    }
}

struct SessionPanelsView: View {
    let viewModel: ChatViewModel
    @State private var expanded: SessionPanelKind?
    // 控制命令反馈（失败原因一行提示；3 秒自动清除）
    @State private var controlFeedback: String?
    @State private var feedbackClearTask: Task<Void, Never>?
    // 转录下钻（工作流 actor 行 / 子代理面板行共用）
    @State private var transcriptTitle: String?
    @State private var transcriptMessages: [ChatMessage] = []
    @State private var transcriptLoading = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            chipsRow
            // 数据消失（会话切换/状态清空）时面板自动收起，不渲染空壳
            if let kind = expanded, availableKinds.contains(kind) {
                ScrollView {
                    panelBody(kind)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 320)
                .fixedSize(horizontal: false, vertical: true)
                .padding(T.sp3)
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
                .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.violet.opacity(0.35), lineWidth: 1))
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            if let message = controlFeedback {
                Text(message)
                    .font(T.font(11))
                    .foregroundColor(T.orange)
                    .transition(.opacity)
                    .accessibilityIdentifier("05-panel-feedback")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-session-panels")
        // P2-5 编辑目标 sheet（下发与反馈都走 showControlFeedback 既有通道；
        // isPresented 手工 Binding——viewModel 为引用类型 let 属性，免 @Bindable 改签名）
        .sheet(isPresented: Binding(
            get: { viewModel.editingGoalActive },
            set: { viewModel.editingGoalActive = $0 })) {
            GoalEditSheet(
                initialText: viewModel.goalSummary?.text ?? "",
                onSend: { text in await viewModel.sendGoal(text) },
                onSuccess: {
                    showControlFeedback(String(localized: "目标已下发 · 桌面端将按新目标继续"))
                })
        }
        .onAppear {
            // 诊断钩子：-ZCodePanelExpand <kind> 冷启自动展开对应面板
            // （PTY 受限期间无 XCUITest 点按路径；模式同 -ZCodeOpenConversationId）
            guard expanded == nil else { return }
            let args = ProcessInfo.processInfo.arguments
            guard let i = args.firstIndex(of: "-ZCodePanelExpand"),
                  i + 1 < args.count else { return }
            expanded = SessionPanelKind(rawValue: args[i + 1])
        }
    }

    /// 控制命令反馈落点（成功静默；失败一行提示，3 秒自动清除）
    private func showControlFeedback(_ message: String?) {
        guard let message else { return }
        feedbackClearTask?.cancel()
        withAnimation { controlFeedback = message }
        feedbackClearTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { withAnimation { controlFeedback = nil } }
        }
    }

    // MARK: chips 行（默认收起态的全部可见面；每 chip 附紧凑进度徽标）

    private var chipsRow: some View {
        HStack(spacing: T.sp2) {
            ForEach(availableKinds) { kind in
                chip(kind)
            }
            Spacer(minLength: 0)
        }
    }

    private var availableKinds: [SessionPanelKind] {
        var kinds: [SessionPanelKind] = []
        if viewModel.goalSummary != nil { kinds.append(.goal) }
        if viewModel.planPanel != nil { kinds.append(.plan) }
        if viewModel.workflowRun != nil { kinds.append(.workflow) }
        if !viewModel.backgroundWorks.isEmpty { kinds.append(.works) }
        if !viewModel.subagentSessions.isEmpty { kinds.append(.subagents) }
        return kinds
    }

    private func chip(_ kind: SessionPanelKind) -> some View {
        let selected = expanded == kind
        return Button {
            withAnimation(.easeInOut(duration: 0.15)) {
                expanded = selected ? nil : kind
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: kind.icon)
                    .font(.system(size: 10))
                Text(kind.label)
                    .font(T.font(11.5, .semibold))
                if let badge = badgeText(kind) {
                    Text(badge)
                        .font(T.mono(9.5, .semibold))
                        .foregroundColor(selected ? T.accentText : T.text3)
                }
            }
            .foregroundColor(selected ? T.accentText : T.text2)
            .padding(.horizontal, T.sp2)
            .padding(.vertical, 5)
            .background(selected ? T.accentDim.opacity(0.55) : T.bgCard)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(
                selected ? T.accentText.opacity(0.6) : T.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("05-panel-chip-\(kind.rawValue)")
    }

    /// chip 徽标：工作流进度 n/m；后台/子代理条目数；目标暂停标记
    private func badgeText(_ kind: SessionPanelKind) -> String? {
        switch kind {
        case .goal:
            return viewModel.goalSummary?.isPaused == true ? String(localized: "已暂停") : nil
        case .plan:
            return nil
        case .workflow:
            let runs = viewModel.workflowRuns
            if runs.count > 1 {
                let live = runs.filter(\.isLive).count
                return String(localized: "\(live)活/\(runs.count)")
            }
            guard let run = runs.first else { return nil }
            return "\(run.doneCount)/\(run.nodes.count)"
        case .works:
            return "\(viewModel.backgroundWorks.count)"
        case .subagents:
            return "\(viewModel.subagentSessions.count)"
        }
    }

    // MARK: 面板体（数据缺席返回 nil，chips 与面板体同源不会错位）

    @ViewBuilder
    private func panelBody(_ kind: SessionPanelKind) -> some View {
        switch kind {
        case .goal:
            if let goal = viewModel.goalSummary {
                GoalPanelView(goal: goal) {
                    if let message = await viewModel.toggleGoalPause() {
                        showControlFeedback(message)
                    }
                } onEditGoal: {
                    viewModel.editingGoalActive = true
                }
            }
        case .plan:
            if let plan = viewModel.planPanel {
                PlanPanelView(plan: plan)
            }
        case .workflow:
            // 多 run（用户实测：多个工作流并行时此前只显示第一个）——逐 run 卡片；
            // store 排序口径：活 run 在前、新 run 优先（首卡即最新活 run），被替换旧 run
            // 沉底为历史；每 run 独立控制条（取消打到各自 workId）
            let runs = viewModel.workflowRuns
            ForEach(Array(runs.enumerated()), id: \.element.id) { index, run in
                if index > 0, !run.isLive, runs[..<index].contains(where: \.isLive) {
                    historyCaption
                }
                WorkflowPanelContent(
                    run: run,
                    availableModels: viewModel.modelSelection?.models ?? [],
                    planGroups: viewModel.modelSelection?.planGroups ?? [],
                    hasGoal: viewModel.goalSummary != nil,
                    goalPaused: viewModel.goalSummary?.isPaused ?? false,
                    onPauseGoal: {
                        if let message = await viewModel.toggleGoalPause() {
                            showControlFeedback(message)
                        }
                    },
                    onModelChange: { model in
                        if let message = await viewModel.setSubagentModel(model, workId: run.workId ?? run.id) {
                            showControlFeedback(message)
                        }
                    },
                    onConcurrencyChange: { limit in
                        if let message = await viewModel.setMaxConcurrency(limit, workId: run.workId ?? run.id) {
                            showControlFeedback(message)
                        }
                    },
                    onCancelRun: {
                        if let message = await viewModel.cancelWork(run.workId ?? run.id) {
                            showControlFeedback(message)
                        }
                    },
                    onResumeRun: {
                        if let message = await viewModel.resumeWork(run.workId ?? run.id, name: run.name) {
                            showControlFeedback(message)
                        }
                    },
                    onLoadActorTranscript: { sessionId in
                        await viewModel.storeActorTranscript(sessionId: sessionId)
                    })
                if index < runs.count - 1 {
                    Divider().overlay(T.border).padding(.vertical, T.sp1)
                }
            }
        case .works:
            WorksPanelView(works: viewModel.backgroundWorks) { workId in
                if let message = await viewModel.cancelWork(workId) {
                    showControlFeedback(message)
                }
            } onResume: { workId, name in
                if let message = await viewModel.resumeWork(workId, name: name) {
                    showControlFeedback(message)
                }
            }
        case .subagents:
            SubagentsPanelView(subagents: viewModel.subagentSessions) { sub in
                await openTranscript(
                    title: sub.name ?? sub.agentType ?? String(localized: "子代理"),
                    sessionId: sub.childSessionId)
            }
        }
    }

    /// 历史 run 分界标注（多 run 面板：活 run 主导，被替换旧 run 沉底保留查看入口）
    private var historyCaption: some View {
        HStack(spacing: T.sp1) {
            Text(String(localized: "历史 run"))
                .font(T.mono(9.5, .semibold))
                .foregroundColor(T.text3)
            Rectangle().fill(T.border).frame(height: 1)
        }
        .padding(.vertical, T.sp1)
        .accessibilityIdentifier("05-wf-history-caption")
    }

    private func openTranscript(title: String, sessionId: String) async {
        transcriptTitle = title
        transcriptMessages = []
        transcriptLoading = true
        transcriptMessages = await viewModel.storeActorTranscript(sessionId: sessionId)
        transcriptLoading = false
    }
}

// MARK: - 目标面板（goal 文本 + 编辑 + 暂停/继续；命令面：sendGoalCommand + pauseGoal/resumeGoal）

struct GoalPanelView: View {
    let goal: RemoteGoalSummary
    var onTogglePause: () async -> Void
    /// P2-5 编辑目标（✏️ → viewModel.editingGoalActive → GoalEditSheet 下发）
    var onEditGoal: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "target")
                    .font(.system(size: 12))
                    .foregroundColor(T.violet)
                Text(String(localized: "当前目标"))
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
                // 编辑目标（暂停/继续左侧；面板小钮同暂停钮 Capsule 语言）
                Button {
                    onEditGoal()
                } label: {
                    Image(systemName: "pencil")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(T.text3)
                        .padding(6)
                        .background(T.bgInput)
                        .clipShape(Capsule())
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-goal-act-edit")
                .accessibilityLabel(String(localized: "编辑目标"))
                Button {
                    Task { await onTogglePause() }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: goal.isPaused ? "play.fill" : "pause.fill")
                            .font(.system(size: 9, weight: .semibold))
                        Text(goal.isPaused ? String(localized: "继续") : String(localized: "暂停"))
                            .font(T.font(11, .semibold))
                    }
                    .foregroundColor(T.accentText)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 4)
                    .background(T.accentDim.opacity(0.5))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-goal-act-pause")
            }
            Text(goal.text)
                .font(T.font(12))
                .foregroundColor(T.text2)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-panel-goal")
    }
}

// MARK: - P2-5 编辑目标 sheet（goal 面板 ✏️；sendGoalCommand 下发，与暂停/继续并存）
//
// 三态：空文本主钮禁用（editQueueItem 同口径）/ 下发中禁用防重 / 失败错误行留 sheet
// 可重发。成功 dismiss + 面板反馈行 3s（成功也提示——setSubagentModel 先例：
// 下发语义非即时可见，state.updated 回流前先给确认）。不设 destructive 确认层：
// 改目标可逆（可再编辑纠正），sheet 内说明行承担知情义务（设计稿 §5.5）。

struct GoalEditSheet: View {
    let initialText: String
    /// 下发回调（ChatViewModel.sendGoal）：返回错误文案（nil = 成功）
    var onSend: (String) async -> String?
    /// 成功回调（宿主 showControlFeedback 反馈行）
    var onSuccess: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var sending = false
    @State private var errorText: String?

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text(String(localized: "编辑目标"))
                    .font(T.font(17, .bold))
                    .foregroundColor(T.text)
                Spacer()
                Button(String(localized: "取消")) { dismiss() }
                    .font(T.font(14, .medium))
                    .foregroundColor(T.text2)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityIdentifier("05-goal-edit-act-cancel")
            }
            .padding(.horizontal, T.sp4)
            ScrollView {
                VStack(alignment: .leading, spacing: T.sp3) {
                    TextEditor(text: $text)
                        .font(T.font(13))
                        .foregroundColor(T.text)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 96, alignment: .topLeading)
                        .padding(T.sp2)
                        .background(T.bgInput)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        .accessibilityIdentifier("05-goal-edit-field")
                    Text(String(localized: "将替换桌面端当前目标，Agent 会按新目标继续执行；进行中的回合不受影响。"))
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                        .lineSpacing(3)
                    Button {
                        send()
                    } label: {
                        HStack(spacing: T.sp2) {
                            if sending {
                                SpinnerView(color: T.onAccent, size: 13)
                            }
                            Text(sending ? String(localized: "下发中…") : String(localized: "下发目标"))
                                .font(T.font(13.5, .semibold))
                        }
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(trimmed.isEmpty || sending ? T.accent.opacity(0.5) : T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    }
                    .disabled(trimmed.isEmpty || sending)
                    .accessibilityIdentifier("05-goal-edit-act-send")
                    if let errorText {
                        Text(errorText)
                            .font(T.font(11.5, .semibold))
                            .foregroundColor(T.red)
                            .accessibilityIdentifier("05-goal-edit-error")
                    }
                }
                .padding(T.sp4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .onAppear {
            // 每次打开预填当前 goal（state.updated 回流后的最新文本；残留编辑不跨次携带）
            text = initialText
            errorText = nil
        }
    }

    private func send() {
        guard !trimmed.isEmpty, !sending else { return }
        sending = true
        errorText = nil
        Task {
            let error = await onSend(trimmed)
            sending = false
            if let error {
                errorText = error // 失败留在 sheet，主钮即重试
            } else {
                dismiss()
                onSuccess()
            }
        }
    }
}

// MARK: - 计划面板（plan 正文只读卡；审批仍走会话流审批卡，不在此重复）

struct PlanPanelView: View {
    let plan: PlanPanelSummary

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: "list.clipboard")
                    .font(.system(size: 12))
                    .foregroundColor(T.violet)
                Text(plan.title ?? String(localized: "当前计划"))
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if let status = plan.rawStatus, !status.isEmpty {
                    StatusPill(text: status, kind: .tag, compact: true)
                }
            }
            Text(plan.content)
                .font(T.font(12))
                .foregroundColor(T.text2)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-panel-plan")
    }
}

// MARK: - 工作流面板（桌面基准：阶段行展开 → 子代理行 → 只读转录；控制条）

struct WorkflowPanelContent: View {
    let run: WorkflowRunSummary
    let availableModels: [String]
    /// 模型套餐分组（个人套餐/体验套餐；空 = 数据缺席退化为平铺列表）
    var planGroups: [ModelPlanGroup] = []
    /// 会话是否存在 goal（桌面同口径：无 goal 不渲染暂停/继续钮——pauseGoal 对无目标会话是 noop）
    let hasGoal: Bool
    /// 目标暂停态（state.goal 只读回显；暂停/继续下发 pauseGoal/resumeGoal）
    let goalPaused: Bool
    var onPauseGoal: () async -> Void
    var onModelChange: (String?) async -> Void
    var onConcurrencyChange: (Int?) async -> Void
    var onCancelRun: () async -> Void
    var onResumeRun: () async -> Void
    var onLoadActorTranscript: (String) async -> [ChatMessage]

    @State private var expandedPhases: Set<String> = []
    @State private var transcriptActor: WorkflowActorSummary?
    @State private var transcript: [ChatMessage] = []
    @State private var transcriptLoading = false
    @State private var sendingControl = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            header
            phaseChain
            controlBar
        }
        .sheet(item: $transcriptActor) { actor in
            ActorTranscriptSheet(
                actor: actor,
                messages: transcript,
                isLoading: transcriptLoading)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-panel-workflow")
        .onAppear {
            // 诊断钩子：-ZCodePanelExpandPhases 冷启自动展开全部阶段行（无点按路径的
            // actor 行/转录入口取证；模式同 -ZCodePanelExpand）
            if ProcessInfo.processInfo.arguments.contains("-ZCodePanelExpandPhases") {
                expandedPhases = Set(run.nodes.map(\.id))
            }
        }
    }

    /// 头部：工作流名 + 五态胶囊 + 进度计数
    private var header: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "flowchart.fill")
                .font(.system(size: 13))
                .foregroundColor(T.violet)
            Text(run.name)
                .font(T.font(13, .semibold))
                .foregroundColor(T.text)
                .lineLimit(1)
            StatusPill(text: Self.runStatusText(run), kind: Self.runPillKind(run), compact: true)
            if run.truncated {
                Text(String(localized: "已截断"))
                    .font(T.mono(9.5))
                    .foregroundColor(T.orange)
            }
            Spacer(minLength: 0)
            Text("\(run.doneCount)/\(run.nodes.count)")
                .font(T.mono(10.5, .semibold))
                .foregroundColor(run.status == .done ? T.accentText : T.text3)
        }
    }

    // MARK: 阶段链（桌面同构：阶段行 n/m + chevron；展开为子代理实例行）

    private var phaseChain: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(run.nodes.enumerated()), id: \.element.id) { index, node in
                let actors = run.actors.filter { $0.phaseName == node.label }
                let expanded = expandedPhases.contains(node.id)
                HStack(alignment: .top, spacing: T.sp3) {
                    VStack(spacing: 0) {
                        nodeDot(node)
                        if index < run.nodes.count - 1 {
                            Rectangle()
                                .fill(T.border)
                                .frame(width: 1.5)
                                .frame(maxHeight: .infinity)
                        }
                    }
                    .frame(width: 16)

                    VStack(alignment: .leading, spacing: T.sp1) {
                        // 阶段行（有子代理实例才可展开）
                        Button {
                            guard !actors.isEmpty else { return }
                            withAnimation(.easeInOut(duration: 0.15)) {
                                if expanded {
                                    expandedPhases.remove(node.id)
                                } else {
                                    expandedPhases.insert(node.id)
                                }
                            }
                        } label: {
                            HStack(spacing: T.sp2) {
                                Text(node.label)
                                    .font(T.font(12.5, node.status == .running ? .semibold : .regular))
                                    .foregroundColor(node.status == .done ? T.text3 : T.text)
                                    .lineLimit(1)
                                Spacer(minLength: 0)
                                if !actors.isEmpty {
                                    Text("\(actors.filter { $0.status == .done }.count)/\(actors.count)")
                                        .font(T.mono(10.5, .semibold))
                                        .foregroundColor(T.text3)
                                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                                        .font(.system(size: 9, weight: .semibold))
                                        .foregroundColor(T.text3)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("05-wf-phase-\(index)")

                        // 子代理实例行（桌面「N 个任务」同构；sessionId 在场可下钻转录）
                        if expanded {
                            ForEach(actors) { actor in
                                actorRow(actor)
                            }
                            .transition(.opacity)
                        } else if let summary = node.summary, !summary.isEmpty {
                            Text(summary)
                                .font(T.font(11))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                        }
                    }
                    .padding(.bottom, index < run.nodes.count - 1 ? T.sp3 : 0)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func actorRow(_ actor: WorkflowActorSummary) -> some View {
        let actionable = actor.sessionId != nil
        return Button {
            guard let sessionId = actor.sessionId else { return }
            transcriptActor = actor
            transcript = []
            transcriptLoading = true
            Task {
                transcript = await onLoadActorTranscript(sessionId)
                transcriptLoading = false
            }
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "cpu")
                    .font(.system(size: 11))
                    .foregroundColor(actor.status == .running ? T.blue : T.text3)
                Text(actor.name ?? String(localized: "子代理"))
                    .font(T.font(12, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                if let total = actor.tasksTotal {
                    Text(String(format: String(localized: "%lld 个任务"), total))
                        .font(T.mono(9.5))
                        .foregroundColor(T.text3)
                }
                Spacer(minLength: 0)
                if actionable {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundColor(T.text3)
                }
                if actor.status == .running {
                    SpinnerView(color: T.blue, size: 11)
                } else if actor.status == .done {
                    Image(systemName: "checkmark.circle")
                        .font(.system(size: 12))
                        .foregroundColor(T.accentText)
                } else {
                    Circle()
                        .strokeBorder(T.borderStrong, lineWidth: 1.2)
                        .frame(width: 10, height: 10)
                }
            }
            .padding(.horizontal, T.sp2)
            .padding(.vertical, 5)
            .background(T.blueDim.opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: T.rS))
        }
        .buttonStyle(.plain)
        .disabled(!actionable)
        .padding(.leading, T.sp3)
        .accessibilityIdentifier("05-wf-actor-\(actor.id)")
        .accessibilityHint(actionable ? String(localized: "查看子代理只读转录") : "")
    }

    // MARK: 控制条（暂停/继续 + 子代理模型 + 并发数 + 取消/恢复）

    private var controlBar: some View {
        HStack(spacing: T.sp2) {
            // 目标暂停/继续（桌面顶栏 onPauseGoal/onResumeGoal 同语义；无 goal 不渲染）
            if hasGoal {
                controlButton(
                    icon: goalPaused ? "play.fill" : "pause.fill",
                    text: goalPaused ? String(localized: "继续目标") : String(localized: "暂停目标")) {
                        await onPauseGoal()
                    }
                    .accessibilityIdentifier("05-wf-act-pause")
            }

            // 子代理模型（amendWorkflowRunSettings.subagentModel；nil = 跟随主模型；
            // 按套餐分组展示——个人套餐/体验套餐，数据缺席退化为平铺列表）
            Menu {
                MenuPicker(label: String(localized: "跟随主模型"), model: nil, current: run.subagentModel, onPick: pickModel)
                if groupedModels.isEmpty {
                    ForEach(availableModels, id: \.self) { model in
                        MenuPicker(label: model, model: model, current: run.subagentModel, onPick: pickModel)
                    }
                } else {
                    ForEach(groupedModels) { group in
                        Section(group.plan) {
                            ForEach(group.models, id: \.self) { model in
                                MenuPicker(label: model, model: model, current: run.subagentModel, onPick: pickModel)
                            }
                        }
                    }
                }
            } label: {
                controlLabel(
                    icon: "cpu",
                    text: run.subagentModel.map { Self.shortModelName($0) }
                        ?? String(localized: "跟随主模型"))
            }
            .accessibilityIdentifier("05-wf-act-model")

            // 并发 agent 数（amendWorkflowRunSettings.maxConcurrency）
            Menu {
                ForEach(concurrencyChoices, id: \.self) { limit in
                    MenuConcurrencyPicker(
                        label: "\(limit)", limit: limit,
                        current: run.concurrencyCeiling, onPick: pickConcurrency)
                }
                MenuConcurrencyPicker(
                    label: String(localized: "无上限"), limit: nil,
                    current: run.concurrencyCeiling, onPick: pickConcurrency)
            } label: {
                controlLabel(
                    icon: "square.stack.3d.up",
                    text: run.concurrencyCeiling.map { String(localized: "并发 \($0)") }
                        ?? String(localized: "并发默认"))
            }
            .accessibilityIdentifier("05-wf-act-concurrency")

            Spacer(minLength: 0)

            // 运行取消（live 且 cancellable）/ 恢复（stopped+resumable）
            if run.isLive && run.cancellable {
                controlButton(icon: "stop.fill", text: String(localized: "取消运行")) {
                    await onCancelRun()
                }
                .accessibilityIdentifier("05-wf-act-cancel")
            } else if !run.isLive && run.resumable {
                controlButton(icon: "arrow.clockwise", text: String(localized: "恢复运行")) {
                    await onResumeRun()
                }
                .accessibilityIdentifier("05-wf-act-resume")
            }
        }
    }

    private var groupedModels: [ModelPlanGroup] { planGroups }

    /// 长模型名缩短显示：「account:zai-individual-coding-plan/GLM-5.3-Flash$max」→「GLM-5.3-Flash·max」
    static func shortModelName(_ raw: String) -> String {
        let afterSlash = raw.contains("/")
            ? String(raw.split(separator: "/").last ?? Substring(raw))
            : raw
        let parts = afterSlash.split(separator: "$", maxSplits: 1)
        let name = parts.count > 1 ? "\(parts[0])·\(parts[1])" : afterSlash
        return name
    }

    private var concurrencyChoices: [Int] {
        let ceiling = max(run.concurrencyCeiling ?? 4, 4)
        return Array(1...min(ceiling, 8))
    }

    private func pickModel(_ model: String?) {
        guard !sendingControl else { return }
        sendingControl = true
        Task {
            await onModelChange(model)
            sendingControl = false
        }
    }

    private func pickConcurrency(_ limit: Int?) {
        guard !sendingControl else { return }
        sendingControl = true
        Task {
            await onConcurrencyChange(limit)
            sendingControl = false
        }
    }

    private func controlButton(
        icon: String, text: String, action: @escaping () async -> Void
    ) -> some View {
        Button {
            Task { await action() }
        } label: {
            controlLabel(icon: icon, text: text)
        }
        .buttonStyle(.plain)
    }

    private func controlLabel(icon: String, text: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 9, weight: .semibold))
            Text(text)
                .font(T.font(11, .semibold))
                .lineLimit(1)
        }
        .foregroundColor(T.text2)
        .padding(.horizontal, T.sp2)
        .padding(.vertical, 4)
        .background(T.bgInput)
        .clipShape(Capsule())
    }

    /// 节点状态圆点（done 复选 / running spinner / failed 红叉 / pending 空心）
    @ViewBuilder
    private func nodeDot(_ node: WorkflowNodeSummary) -> some View {
        switch node.status {
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.accentText)
                .frame(width: 16)
        case .running:
            SpinnerView(color: T.blue, size: 14)
                .frame(width: 16)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 14))
                .foregroundColor(T.red)
                .frame(width: 16)
        case .pending:
            Circle().strokeBorder(T.borderStrong, lineWidth: 1.5)
                .frame(width: 13, height: 13)
                .frame(width: 16)
        }
    }

    private static func runStatusText(_ run: WorkflowRunSummary) -> String {
        switch run.rawStatus {
        case "running": return String(localized: "运行中")
        case "completed": return String(localized: "已完成")
        case "errored": return String(localized: "失败")
        case "stopped": return String(localized: "已停止")
        default: return String(localized: "待执行")
        }
    }

    private static func runPillKind(_ run: WorkflowRunSummary) -> PillKind {
        switch run.rawStatus {
        case "running": return .run
        case "completed": return .done
        case "errored": return .err
        case "stopped": return .tag
        default: return .wait
        }
    }
}

/// Menu 内选项（SwiftUI Menu content 需要 View；picker 化避免闭包重载歧义）
private struct MenuPicker: View {
    let label: String
    let model: String?
    let current: String?
    let onPick: (String?) -> Void

    var body: some View {
        Button {
            onPick(model)
        } label: {
            if model == current {
                Label(label, systemImage: "checkmark")
            } else {
                Text(label)
            }
        }
    }
}

private struct MenuConcurrencyPicker: View {
    let label: String
    let limit: Int?
    let current: Int?
    let onPick: (Int?) -> Void

    var body: some View {
        Button {
            onPick(limit)
        } label: {
            if current == limit {
                Label(label, systemImage: "checkmark")
            } else {
                Text(label)
            }
        }
    }
}

// MARK: - btw 后台工作面板（cancel/resume 命令面）

struct WorksPanelView: View {
    let works: [BackgroundWorkSummary]
    var onCancel: (String) async -> Void
    var onResume: (String, String?) async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp1) {
            ForEach(works) { work in
                HStack(spacing: T.sp2) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 11))
                        .foregroundColor(T.blue)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(work.title ?? work.workId)
                            .font(T.font(12, .medium))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        if let status = work.rawStatus, !status.isEmpty {
                            Text(status)
                                .font(T.mono(9.5))
                                .foregroundColor(T.text3)
                        }
                    }
                    Spacer(minLength: 0)
                    if work.resumable {
                        rowButton(icon: "arrow.clockwise", label: String(localized: "恢复")) {
                            await onResume(work.workId, work.title)
                        }
                        .accessibilityIdentifier("05-works-act-resume-\(work.workId)")
                    }
                    if work.cancellable {
                        rowButton(icon: "stop.fill", label: String(localized: "取消")) {
                            await onCancel(work.workId)
                        }
                        .accessibilityIdentifier("05-works-act-cancel-\(work.workId)")
                    }
                }
                .padding(.horizontal, T.sp2)
                .padding(.vertical, 5)
                .background(T.blueDim.opacity(0.5))
                .clipShape(RoundedRectangle(cornerRadius: T.rS))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-panel-works")
    }

    private func rowButton(
        icon: String, label: String, action: @escaping () async -> Void
    ) -> some View {
        Button {
            Task { await action() }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 9, weight: .semibold))
                Text(label)
                    .font(T.font(11, .semibold))
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, T.sp2)
            .padding(.vertical, 3)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - side 子代理面板（运行中子代理实例；行点击 → 只读转录下钻）

struct SubagentsPanelView: View {
    let subagents: [SubagentSessionSummary]
    var onOpen: (SubagentSessionSummary) async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: T.sp1) {
            ForEach(subagents) { sub in
                Button {
                    Task { await onOpen(sub) }
                } label: {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "cpu")
                            .font(.system(size: 11))
                            .foregroundColor(sub.status == .running ? T.blue : T.text3)
                        Text(sub.name ?? sub.agentType ?? String(localized: "子代理"))
                            .font(T.font(12, .medium))
                            .foregroundColor(T.text)
                            .lineLimit(1)
                        if let type = sub.agentType, !type.isEmpty {
                            Text(type)
                                .font(T.mono(9.5))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundColor(T.text3)
                        if sub.status == .running {
                            SpinnerView(color: T.blue, size: 11)
                        } else {
                            Image(systemName: sub.status == .failed
                                ? "xmark.circle" : "checkmark.circle")
                                .font(.system(size: 12))
                                .foregroundColor(sub.status == .failed ? T.red : T.accentText)
                        }
                    }
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 5)
                    .background(T.blueDim.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("05-subagents-row-\(sub.id)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-panel-subagents")
    }
}

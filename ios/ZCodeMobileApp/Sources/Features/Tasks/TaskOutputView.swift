import SwiftUI

/// 任务详情可观察模型：连接态只读元数据（三读）与当前任务态经此直绑渲染。
/// 曾用 @State 在 .task 内 await 后赋值——赋值不触发重渲染（门禁修复轮实证），
/// 与 TaskBoardModel/ChatViewModel 同模式迁移到 @Observable。
@MainActor
@Observable
final class TaskOutputModel {
    var currentTask: TaskRecord?
    /// 连接态只读元数据（getTaskConfigOptions/getTaskModelSelection/getTaskTokenUsage）；
    /// 演示态保持 nil（不渲染，演示行为完全不变）
    var configOptions: [String: String]?
    var modelSelection: String?
    var tokenUsage: TaskTokenUsage?
}

/// 任务执行输出（Push L3）：模型轨迹卡（reasoning+toolCall 行投影）+ 停止任务。
/// v1.39 死 UI 移除：原「后台 Bash」分段是死控件——terminalStream 在 RemoteTaskStore
/// 只注册 continuation 从无 yield（上游 terminal 频道需 create/write 自建 PTY，均被
/// ReadOnlyGate 拦截，移动端无「读任务终端」接口），故撤分段与假终端卡，轨迹投影为唯一
/// 真实输出面；停止任务（v4 stop 桌面代执行）保留。
struct TaskOutputView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.taskStore) private var store
    let task: TaskRecord

    /// 连接态数据源标记（mock 演示恒为 false）：只读元数据三行仅连接态拉取
    private var isReadOnly: Bool { store.isReadOnly }

    @State private var outputModel = TaskOutputModel()
    @State private var showStopConfirm = false
    @State private var copied = false
    @State private var trajectoryLines: [TerminalLine] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: T.sp4) {
                statusBar
                if isReadOnly {
                    readonlyMetaRows
                }
                trajectoryCard
                outputActions
                // G-022：子智能体行移除——原为硬编码假数据（test-runner · 回归 46 用例）
                // 且整行不可点；桌面 SubagentSession 读面接入前不渲染占位死控件。
            }
            .padding(T.sp4)
        }
        .scrollIndicators(.hidden)
        .background(T.bg)
        .navigationTitle(String(localized: "执行输出"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { outputModel.currentTask = task }
        .task {
            if outputModel.currentTask == nil { outputModel.currentTask = task }
            trajectoryLines = await store.trajectoryLines(taskID: task.id)
            if isReadOnly {
                // @Observable 写入锚定主线程（非主线程写不触发视图 invalidate）
                let options = await store.taskConfigOptions(taskID: task.id)
                let selection = await store.taskModelSelection(taskID: task.id)
                let usage = await store.taskTokenUsage(taskID: task.id)
                await MainActor.run {
                    outputModel.configOptions = options
                    outputModel.modelSelection = selection
                    outputModel.tokenUsage = usage
                }
            }
        }
        // 状态条随任务流翻转（验收口径：状态流回推后卡片/状态条翻转）。此前详情页
        // 只在停止确认回调里拉取一次——回推事件晚于该拉取时状态条停留在运行中。
        .task {
            for await tasks in store.observeTasks() {
                if let index = tasks.firstIndex(where: { $0.id == task.id }) {
                    outputModel.currentTask = tasks[index]
                }
            }
        }
        .confirmationDialog(String(localized: "停止该任务？"), isPresented: $showStopConfirm, titleVisibility: .visible) {
            Button(String(localized: "停止任务"), role: .destructive) {
                Task {
                    await store.stop(taskID: task.id)
                    let all = await store.tasks()
                    if let index = all.firstIndex(where: { $0.id == task.id }) {
                        outputModel.currentTask = all[index]
                    }
                }
            }
            Button(String(localized: "继续运行"), role: .cancel) {}
        }
    }

    private var statusBar: some View {
        HStack(spacing: T.sp2) {
            if outputModel.currentTask?.status == .running {
                SpinnerView(size: 14)
            } else if outputModel.currentTask?.status == .failed {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.system(size: 14))
                    .foregroundColor(T.red)
            }
            Text(outputModel.currentTask?.title ?? task.title)
                .font(T.font(13.5, .semibold))
                .foregroundColor(T.text)
                .lineLimit(1)
            Text(durationText)
                .font(T.mono(11))
                .foregroundColor(T.text3)
            Spacer(minLength: 0)
            if let status = outputModel.currentTask?.status ?? Optional(task.status) {
                StatusPill(text: status.label, kind: status.pillKind, compact: true)
            }
        }
        .accessibilityIdentifier("07-status-bar")
    }

    private var durationText: String {
        // G-032：绝对时长（updatedAt 基准差值，不取模）——跨小时不回卷
        let minutes = max(1, Int(-task.updatedAt.timeIntervalSinceNow / 60))
        if minutes >= 60 {
            return String(localized: "已运行 \(minutes / 60) 小时 \(minutes % 60) 分")
        }
        return String(localized: "已运行 \(minutes) 分钟")
    }

    /// 连接态只读元数据行（模型绑定 / 思考与配置 / Token 用量；缺数据行不渲染避免死控件）
    @ViewBuilder
    private var readonlyMetaRows: some View {
        VStack(spacing: 0) {
            if let modelSelection = outputModel.modelSelection {
                metaRow(icon: "cpu", label: String(localized: "绑定模型"), value: modelSelection,
                        identifier: "07-meta-model")
                Divider().overlay(T.border)
            }
            if let options = outputModel.configOptions, !options.isEmpty {
                let text = options.sorted { $0.key < $1.key }
                    .map { "\($0.key)=\($0.value)" }
                    .joined(separator: " · ")
                metaRow(icon: "slider.horizontal.3", label: String(localized: "会话配置"), value: text,
                        identifier: "07-meta-config")
                Divider().overlay(T.border)
            }
            if let usage = outputModel.tokenUsage {
                // token 是数量不是字节（清查 2026-11-25：ByteCountFormatter.memory 曾把
                // 100k tokens 渲染成「97.7 KB」）；千分位分组与用量页同口径
                let formatted = usage.total.formatted(.number.grouping(.automatic))
                metaRow(icon: "number", label: String(localized: "Token 用量"),
                        value: String(localized: "输入 \(usage.input) · 输出 \(usage.output) · 合计 \(formatted)"),
                        identifier: "07-meta-usage")
            }
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("07-meta-readonly")
    }

    private func metaRow(icon: String, label: String, value: String, identifier: String) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(T.codeLab)
                .frame(width: 28)
            Text(label)
                .font(T.font(12.5))
                .foregroundColor(T.text2)
            Spacer(minLength: 0)
            Text(value)
                .font(T.mono(11))
                .foregroundColor(T.text)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 40)
        .accessibilityIdentifier(identifier)
    }

    private var terminalText: String {
        trajectoryLines.map { line in
            (line.label.map { "\($0) " } ?? "") + line.text
        }.joined(separator: "\n")
    }

    private var outputActions: some View {
        VStack(spacing: 0) {
            TextActionButton(title: "复制全部输出", action: {
                UIPasteboard.general.string = terminalText
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { copied = false }
            }, identifier: "07-act-copyall")
            Divider().overlay(T.border).padding(.horizontal, T.sp4)
            // 停止任务（v3 纠偏：v4 stop 桌面代执行；演示态 MockTaskStore 路径不变）
            TextActionButton(title: "停止任务", tint: T.red, action: {
                showStopConfirm = true
            }, identifier: "07-act-stop")
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
    }

    private var trajectoryCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("TRAJECTORY")
                    .font(T.mono(10.5, .semibold))
                    .foregroundColor(T.codeLab)
                Spacer()
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 44)
            .accessibilityIdentifier("07-toolcard-head-trajectory")
            Divider().overlay(T.border)
            VStack(alignment: .leading, spacing: T.sp2) {
                ForEach(trajectoryLines) { line in
                    HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
                        Text(line.label ?? "")
                            .font(T.mono(10.5, .semibold))
                            .foregroundColor(T.codeLab)
                            .frame(width: 74, alignment: .leading)
                        Text(line.text)
                            .font(T.mono(12))
                            .foregroundColor(T.text2)
                            .lineLimit(2)
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(T.sp3)
        }
        .background(T.bgCode)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }

}

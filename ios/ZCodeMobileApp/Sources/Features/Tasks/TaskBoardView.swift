import SwiftUI

/// 屏 02 · 任务看板（Tab「任务」根）：状态三分组卡片流 + FAB
/// 任务看板可观察模型：推送更新（onDynamicTaskEvent upsert → observeTasks 流）经此
/// 直绑渲染。曾用 @State 在 .task 的 for await 恢复点赋值——cache 已更新而卡片不重绘
/// （门禁修复轮 os_log 实证）；与 ChatViewModel 同模式的 @Observable 直绑渲染可靠。
@MainActor
@Observable
final class TaskBoardModel {
    var tasks: [TaskRecord] = []
    var isLoading = true
}

struct TaskBoardView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.taskStore) private var store
    @State private var model = TaskBoardModel()
    @State private var query = ""
    @State private var showNewSheet = false
    @State private var approvalTarget: TaskRecord?
    @State private var showAllDone = false
    /// 服务端搜索回执（连接态；P2 全文搜索）。nil = 未触发远端搜索，走本地过滤。
    @State private var remoteResults: [TaskRecord]?
    @State private var searchDebounce: Task<Void, Never>?
    /// G-057 状态筛选 chips（nil = 全部）
    @State private var statusFilter: TaskStatus?

    private var waiting: [TaskRecord] { filtered.filter { $0.status == .waiting } }
    private var running: [TaskRecord] { filtered.filter { $0.status == .running } }
    private var failed: [TaskRecord] { filtered.filter { $0.status == .failed } }
    private var done: [TaskRecord] { filtered.filter { $0.status == .done } }
    private var filtered: [TaskRecord] {
        var base = model.tasks
        // G-057：状态筛选先行
        if let statusFilter {
            base = base.filter { $0.status == statusFilter }
        }
        guard !query.isEmpty else { return base }
        // 连接态优先服务端全文搜索回执（searchable_text 检索）；空回执回退本地标题过滤
        if let remoteResults, store.isReadOnly {
            return remoteResults.filter { statusFilter == nil || $0.status == statusFilter }
        }
        return base.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    /// G-057 筛选 chips（数据驱动计数；"待操作"即待审批/待应答语义）
    private var statusFilterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: T.sp2) {
                chip(label: String(localized: "全部"), count: model.tasks.count, status: nil)
                chip(label: String(localized: "运行中"), count: model.tasks.filter { $0.status == .running }.count, status: .running)
                chip(label: String(localized: "待操作"), count: model.tasks.filter { $0.status == .waiting }.count, status: .waiting)
                chip(label: String(localized: "已完成"), count: model.tasks.filter { $0.status == .done }.count, status: .done)
                chip(label: String(localized: "失败"), count: model.tasks.filter { $0.status == .failed }.count, status: .failed)
            }
            .padding(.horizontal, T.sp4)
        }
        .accessibilityIdentifier("02-filter-chips")
    }

    private func chip(label: String, count: Int, status: TaskStatus?) -> some View {
        let selected = statusFilter == status
        return Button {
            withAnimation(.easeOut(duration: 0.18)) {
                statusFilter = status
            }
        } label: {
            HStack(spacing: 4) {
                Text(label).font(T.font(12, .medium))
                Text("\(count)").font(T.mono(10.5)).foregroundColor(selected ? T.onAccent.opacity(0.8) : T.text3)
            }
            .foregroundColor(selected ? T.onAccent : T.text2)
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 44)
            .background(selected ? T.accent : T.bgInput)
            .clipShape(Capsule())
        }
        .accessibilityIdentifier("02-chip-status-\(status?.rawValue ?? "all")")
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if model.isLoading {
                    CenterLoadingView(text: "正在同步任务…").accessibilityIdentifier("02-loading-center")
                } else if model.tasks.isEmpty {
                    EmptyStateView(
                        icon: "tray",
                        title: "还没有任务",
                        detail: "从新建任务开始，Agent 将在云端沙盒中执行",
                        cta: "新建任务", ctaAction: { showNewSheet = true },
                        ctaIdentifier: "02-btn-newtask")
                    .accessibilityIdentifier("02-empty")
                } else {
                    board
                }
            }
            fab
        }
        .background(T.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("任务").font(T.font(17, .bold)).foregroundColor(T.text)
            }
        }
        // id 绑定 store 实例：连接成功后数据源 mock→远端 切换时重新拉取并重挂观察流
        // （无 id 的 .task 仅在首次挂载（当时还是 mock）执行，连接态任务列表永不加载——门禁第 1 轮实证）
        .task(id: ObjectIdentifier(store)) {
            model.tasks = await store.tasks()
            model.isLoading = false
            for await list in store.observeTasks() {
                // @Observable 写入必须锚定主线程：非主线程写不触发视图 invalidate
                // （门禁修复轮实证：cache/model 已更新而卡片不重绘）
                await MainActor.run { model.tasks = list }
            }
        }
        .refreshable {
            model.tasks = await store.tasks()
        }
        .onChange(of: query) { _, newValue in
            // 连接态防抖 400ms 透传服务端检索（listTaskList searchQuery）；演示态纯本地过滤
            guard store.isReadOnly else { return }
            searchDebounce?.cancel()
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else {
                remoteResults = nil
                return
            }
            searchDebounce = Task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                guard !Task.isCancelled else { return }
                let hits = await store.searchTasks(trimmed)
                if !Task.isCancelled { remoteResults = hits }
            }
        }
        .sheet(isPresented: $showNewSheet) {
            NewConversationSheet { _ in showNewSheet = false }
        }
        .sheet(item: $approvalTarget) { task in
            ApprovalSheetView(task: task)
        }
    }

    private var board: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: T.sp2, pinnedViews: []) {
                greeting
                SearchField(text: $query, placeholder: String(localized: "搜索任务"), identifier: "02-search")
                    .padding(.horizontal, T.sp4)
                // G-057：状态筛选 chips（全部/运行中/待操作/已完成/失败）
                statusFilterChips

                if !waiting.isEmpty {
                    groupHeader("待操作", count: waiting.count)
                    ForEach(waiting) { item in
                        TaskCardView(task: item, onApprove: { approvalTarget = $0 })
                    }
                }
                if !running.isEmpty {
                    groupHeader("进行中", count: running.count)
                    ForEach(running) { item in
                        TaskCardView(task: item, onApprove: { approvalTarget = $0 })
                            .id("\(item.id)-\(item.status.rawValue)")
                    }
                }
                if !failed.isEmpty {
                    groupHeader("失败", count: failed.count)
                    ForEach(failed) { item in
                        TaskCardView(task: item, onApprove: { approvalTarget = $0 })
                    }
                }
                if !done.isEmpty {
                    let visible = showAllDone ? done : Array(done.prefix(3))
                    groupHeader("已完成", count: done.count)
                    ForEach(visible) { task in
                        TaskCardView(task: task, onApprove: { approvalTarget = $0 })
                            .id("\(task.id)-\(task.status.rawValue)")
                            .opacity(0.88)
                    }
                    if done.count > 3 {
                        TextActionButton(
                            title: showAllDone ? "收起" : "查看全部 \(done.count) 个已完成",
                            action: {
                                withAnimation { showAllDone.toggle() }
                            },
                            identifier: "02-act-see-all-done")
                    }
                }
                // 底部滚动余量：需盖住 FAB(56) + 其底部间距(16) + TabBar(≈87)，
                // 否则滚到底时最后一张任务卡仍被 FAB/TabBar 压住无法完整露出（走查 R1 实证）
                Color.clear.frame(height: 180)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var greeting: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(greetingText)
                .font(T.font(22, .heavy))
                .foregroundColor(T.text)
            Text("\(running.count) 个任务进行中 · \(waiting.count) 个待操作")
                .font(T.font(12))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp4)
        .padding(.top, T.sp1)
    }

    private var greetingText: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<11: return "早上好"
        case 11..<13: return "中午好"
        case 13..<18: return "下午好"
        default: return "晚上好"
        }
    }

    private func groupHeader(_ title: String, count: Int) -> some View {
        HStack(spacing: T.sp2) {
            Text(title)
                .font(T.font(12, .semibold))
                .foregroundColor(T.text3)
            Text("\(count)")
                .font(T.mono(10.5, .semibold))
                .foregroundColor(T.text3)
                .padding(.horizontal, 6)
                .background(T.bgInput)
                .clipShape(Capsule())
        }
        .padding(.leading, T.sp4 + 2)
        .padding(.top, T.sp3)
    }

    /// FAB 56px、18px 圆角、绿底深字 + 光晕。
    /// 底边距需避开浮层 TabBar（内容高 53pt + 底部安全区 ≈ 100pt），否则 FAB 下半被
    /// TabBar 盖住不可点（第 1 轮走查实测：padding 16 时 FAB 底 824 > TabBar 顶 774）。
    private var fab: some View {
        Button {
            showNewSheet = true
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(T.onAccent)
                .frame(width: 56, height: 56)
                .background(T.accent)
                .clipShape(RoundedRectangle(cornerRadius: 18))
                .shadow(color: T.shadowFab, radius: 14, y: 6)
        }
        .padding(.trailing, 18)
        .padding(.bottom, 74)
        .accessibilityIdentifier("02-fab-newtask")
    }
}

// MARK: - 任务卡（spec 5.2）

struct TaskCardView: View {
    @Environment(AppRouter.self) private var router
    let task: TaskRecord
    var onApprove: (TaskRecord) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: T.sp2) {
                Text(task.title)
                    .font(T.font(15.5, .bold))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                StatusPill(text: task.status.label, kind: task.status.pillKind, compact: true)
                Spacer(minLength: 0)
                if task.status == .running {
                    SpinnerView(size: 13)
                }
            }
            Text(task.directory)
                .font(T.mono(11))
                .foregroundColor(T.text3)
                .lineLimit(1)
            Text(task.summary)
                .font(T.font(12.5))
                .foregroundColor(T.text2)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            if task.status == .failed, let log = task.lastLog {
                Text(log)
                    .font(T.mono(11))
                    .foregroundColor(T.red)
                    .lineLimit(2)
                    .padding(T.sp2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(T.redDim)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
            }
            if task.status != .done {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        ThinProgressBar(progress: task.progress)
                        Text("todo \(task.todoDone)/\(task.todoTotal)")
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
                    }
                }
            }
            toolTags
        }
        .card()
        .overlay {
            if task.status == .failed {
                RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            router.pushTask(taskID: task.id, task: task)
        }
        // 透明容器：卡片可定位（02-taskcard-<id>），「去审批」等子元素保留各自
        // identifier——onTapGesture+identifier 会把整卡折叠成单一 a11y 元素吞掉后代
        // （症状：去审批按钮可见而 02-taskcard-approve 查询失败，同 05-approval-card /
        // o3-card-autolink 处理），children: .contain 让容器不吞后代
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("02-taskcard-\(task.id)")
    }

    @ViewBuilder
    private var toolTags: some View {
        HStack(spacing: T.sp2) {
            ForEach(task.tools, id: \.self) { tool in
                Text(tool)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .padding(.horizontal, T.sp2)
                    .padding(.vertical, 3)
                    .background(T.bgInput)
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
            }
            Spacer()
            if task.status == .waiting, task.pendingCommand != nil {
                Button {
                    onApprove(task)
                } label: {
                    Text("去审批")
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.onOrange)
                        .padding(.horizontal, T.sp3)
                        .frame(minHeight: 44)
                        .background(T.orangeBright)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM - 2))
                }
                .accessibilityIdentifier("02-taskcard-approve")
            }
        }
    }
}

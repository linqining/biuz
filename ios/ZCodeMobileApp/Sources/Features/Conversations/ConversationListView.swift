import SwiftUI

/// 屏 04 · 会话列表（Tab「会话」根）
/// v3 纠偏增量：行长按菜单（重命名 / 标记未读 / 复制会话 ID）与「已归档」分区
/// （连接态 listArchivedTasks + unarchiveTask；演示态 rename/markUnread 走本地 mock）。
struct ConversationListView: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.conversationStore) private var store
    @State private var conversations: [Conversation] = []
    @State private var archivedConversations: [Conversation] = []
    @State private var query = ""
    @State private var isLoading = true
    @State private var showNewSheet = false
    @State private var showArchived = false
    @State private var renameTarget: Conversation?
    @State private var renameText = ""

    private var pinned: [Conversation] { filtered.filter(\.isPinned) }
    private var timeline: [Conversation] { filtered.filter { !$0.isPinned } }
    private var archived: [Conversation] {
        let base = showArchived ? archivedConversations : []
        guard !query.isEmpty else { return base }
        return base.filter {
            $0.title.localizedCaseInsensitiveContains(query)
        }
    }

    private var filtered: [Conversation] {
        guard !query.isEmpty else { return conversations }
        return conversations.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || $0.summary.localizedCaseInsensitiveContains(query)
        }
    }

    /// 连接态（远端 store）才提供已归档分区入口（演示态归档行即刻消失，行为不变）
    private var supportsArchiveSection: Bool { store.isReadOnly }

    var body: some View {
        Group {
            if isLoading {
                CenterLoadingView(text: "正在同步会话…").accessibilityIdentifier("04-loading-center")
            } else if conversations.isEmpty {
                EmptyStateView(
                    icon: "bubble.left.and.text.bubble.right",
                    title: "还没有会话",
                    detail: "从任务看板下发任务，或直接新建一个会话",
                    cta: "新建会话", ctaAction: { showNewSheet = true },
                    ctaIdentifier: "04-act-new-empty")
                .accessibilityIdentifier("04-empty")
            } else {
                list
            }
        }
        .background(T.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("会话").font(T.font(17, .bold)).foregroundColor(T.text)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showNewSheet = true
                } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 17))
                        .foregroundColor(T.text)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("04-act-new")
            }
        }
        .sheet(isPresented: $showNewSheet) {
            NewConversationSheet(onCreated: { conversation in
                showNewSheet = false
                router.openChat(conversationID: conversation.id)
            })
        }
        .alert("重命名会话", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } })) {
            TextField("新名称", text: $renameText)
            Button("取消", role: .cancel) { renameTarget = nil }
            Button("保存") {
                if let target = renameTarget {
                    let newTitle = renameText
                    Task { await store.renameConversation(newTitle, conversationID: target.id) }
                }
                renameTarget = nil
            }
        } message: {
            Text("新名称会同步到桌面端任务列表")
        }
        .task { await reload() }
        .refreshable { await reload(showSpinner: true) }
        // id 绑定 store 实例：连接成功后数据源 mock→远端 切换时重订阅（id 为常量会一直挂在旧流上）。
        // 切换时必须主动 reload 一次：远端订阅（subscribeSessionsIndexV4）只在 conversations() 内建立，
        // 而首个 .task { reload() } 仅在首次挂载（当时还是 mock）执行——若只 observe 不拉取，
        // 用户停留在列表页时远端会话永不加载（e2e 门禁第 1 轮 test12 实证）。
        .task(id: ObjectIdentifier(store)) {
            await reload()
            for await event in store.observeConversations() {
                if case .conversationsReplaced(let list) = event { conversations = list }
                if case .conversationUpdated(let updated) = event {
                    if let index = conversations.firstIndex(where: { $0.id == updated.id }) {
                        conversations[index] = updated
                    }
                }
            }
        }
    }

    private var list: some View {
        List {
            Section {
                SearchField(text: $query, placeholder: "搜索会话", identifier: "04-search")
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: T.sp1, leading: T.sp4, bottom: 0, trailing: T.sp4))
                    .listRowSeparator(.hidden)
            }
            if !pinned.isEmpty {
                Section {
                    ForEach(pinned) { item in row(item) }
                } header: {
                    sectionHeader("置顶")
                }
            }
            let groups = Dictionary(grouping: timeline, by: { Self.groupName(of: $0.updatedAt) })
            ForEach(["今天", "昨天", "更早"], id: \.self) { name in
                if let items = groups[name], !items.isEmpty {
                    Section {
                        ForEach(items) { item in row(item) }
                    } header: {
                        sectionHeader(name)
                    }
                }
            }
            // 已归档分区（P1-6，连接态）：入口行展开 → listArchivedTasks 只读拉取 + 取消归档
            if supportsArchiveSection {
                Section {
                    if showArchived {
                        if archived.isEmpty {
                            Text("暂无已归档会话")
                                .font(T.font(12.5))
                                .foregroundColor(T.text3)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        } else {
                            ForEach(archived) { item in archivedRow(item) }
                        }
                    }
                    Button {
                        Task {
                            showArchived.toggle()
                            if showArchived, archivedConversations.isEmpty {
                                archivedConversations = await store.archivedConversations()
                            }
                        }
                    } label: {
                        HStack(spacing: T.sp2) {
                            Image(systemName: showArchived ? "chevron.down" : "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(T.text3)
                                .frame(width: 16)
                            Text(showArchived ? "收起已归档" : "已归档会话")
                                .font(T.font(13, .medium))
                                .foregroundColor(T.text2)
                            Spacer()
                        }
                        .frame(minHeight: 44)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("04-act-archived")
                } header: {
                    sectionHeader("归档")
                }
            }
            Color.clear.frame(height: 80)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets())
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(T.font(11, .semibold))
            .foregroundColor(T.text3)
    }

    private func row(_ conversation: Conversation) -> some View {
        Button {
            router.openChat(conversationID: conversation.id)
        } label: {
            ConversationRowView(conversation: conversation)
        }
        .buttonStyle(PressableButtonStyle())
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 4, leading: T.sp4, bottom: 4, trailing: T.sp4))
        .listRowSeparator(.hidden)
        .accessibilityIdentifier("04-row-\(conversation.id)")
        .contextMenu {
            Button {
                renameText = conversation.title
                renameTarget = conversation
            } label: {
                Label("重命名", systemImage: "pencil")
            }
            .accessibilityIdentifier("04-ctx-rename-\(conversation.id)")
            Button {
                Task { await store.markUnread(conversationID: conversation.id) }
            } label: {
                Label("标记为未读", systemImage: "envelope.badge")
            }
            .accessibilityIdentifier("04-ctx-unread-\(conversation.id)")
            Button {
                UIPasteboard.general.string = conversation.id
            } label: {
                Label("复制会话 ID", systemImage: "doc.on.doc")
            }
            Button(role: .destructive) {
                Task { await store.setArchived(true, conversationID: conversation.id) }
            } label: {
                Label("归档", systemImage: "archivebox")
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                Task { await store.setArchived(true, conversationID: conversation.id) }
            } label: {
                Label("归档", systemImage: "archivebox")
            }
            .tint(T.borderStrong)
            .accessibilityIdentifier("04-rowact-archive-\(conversation.id)")
            Button {
                Task { await store.setPinned(!conversation.isPinned, conversationID: conversation.id) }
            } label: {
                Label(conversation.isPinned ? "取消置顶" : "置顶",
                      systemImage: conversation.isPinned ? "pin.slash" : "pin")
            }
            .tint(T.orange)
            .accessibilityIdentifier("04-rowact-pin-\(conversation.id)")
        }
    }

    /// 已归档行：滑块「取消归档」（unarchiveTask）+ 回到主列表
    private func archivedRow(_ conversation: Conversation) -> some View {
        Button {
            Task {
                await store.setArchived(false, conversationID: conversation.id)
                archivedConversations.removeAll { $0.id == conversation.id }
            }
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "archivebox")
                    .font(.system(size: 14))
                    .foregroundColor(T.text3)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(conversation.title)
                        .font(T.font(14, .semibold))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                    Text("点击取消归档并恢复到列表")
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                }
                Spacer()
            }
            .padding(12)
            .background(T.bgCard.opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .buttonStyle(PressableButtonStyle())
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 4, leading: T.sp4, bottom: 4, trailing: T.sp4))
        .listRowSeparator(.hidden)
        .accessibilityIdentifier("04-archivedrow-\(conversation.id)")
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                Task {
                    await store.setArchived(false, conversationID: conversation.id)
                    archivedConversations.removeAll { $0.id == conversation.id }
                }
            } label: {
                Label("取消归档", systemImage: "tray.and.arrow.up")
            }
            .tint(T.accent)
            .accessibilityIdentifier("04-archact-unarchive-\(conversation.id)")
        }
    }

    private func reload(showSpinner: Bool = false) async {
        if showSpinner == false, (try? await Task.sleep(nanoseconds: 350_000_000)) != nil { }
        conversations = await store.conversations()
        isLoading = false
    }

    static func groupName(of date: Date) -> String {
        if Calendar.current.isDateInToday(date) { return "今天" }
        if Calendar.current.isDateInYesterday(date) { return "昨天" }
        return "更早"
    }
}

/// 会话行：40px 头像 + 标题 + 摘要 + 时间 + 未读徽章 + 运行中胶囊
struct ConversationRowView: View {
    let conversation: Conversation

    private var timeText: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.locale = Locale(identifier: "zh_CN")
        let delta = conversation.updatedAt.timeIntervalSinceNow
        if abs(delta) < 60 { return "刚刚" }
        if Calendar.current.isDateInToday(conversation.updatedAt) {
            return conversation.updatedAt.formatted(date: .omitted, time: .shortened)
        }
        return formatter.localizedString(fromTimeInterval: delta)
    }

    var body: some View {
        HStack(spacing: T.sp3) {
            avatar
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: T.sp2) {
                    Text(conversation.title)
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .lineLimit(1)
                    if conversation.isRunning {
                        StatusPill(text: "运行中", kind: .run, compact: true)
                    }
                    Spacer(minLength: 0)
                    Text(timeText)
                        .font(T.font(10.5, .medium))
                        .foregroundColor(T.text3)
                }
                HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
                    Text(conversation.summary)
                        .font(T.font(12.5))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    if conversation.unreadCount > 0 {
                        TabBadge(count: conversation.unreadCount, color: T.orangeBright)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
    }

    @ViewBuilder
    private var avatar: some View {
        if conversation.taskProgress == 1.0 {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 22))
                .foregroundColor(T.accentText)
                .frame(width: 40, height: 40)
                .background(T.accentDim)
                .clipShape(RoundedRectangle(cornerRadius: T.rM - 2))
        } else {
            AgentAvatar(size: 40)
        }
    }
}

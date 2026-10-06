import Foundation

/// 任务内存实现：审批动作驱动状态流转，终端输出经 AsyncStream 模拟。
actor MockTaskStore: @preconcurrency TaskStore {

    private var tasks: [TaskRecord] = []
    private var taskContinuations: [UUID: AsyncStream<[TaskRecord]>.Continuation] = [:]
    private var terminalContinuations: [String: [UUID: AsyncStream<TerminalLine>.Continuation]] = [:]
    private var terminalLineID = 0

    init() {
        seed()
    }

    func tasks() async -> [TaskRecord] {
        sortedTasks()
    }

    func observeTasks() -> AsyncStream<[TaskRecord]> {
        AsyncStream { continuation in
            let key = UUID()
            taskContinuations[key] = continuation
            continuation.yield(sortedTasks())
            continuation.onTermination = { _ in
                Task { await self.removeTaskContinuation(key) }
            }
        }
    }

    private func removeTaskContinuation(_ key: UUID) {
        taskContinuations[key] = nil
    }

    private func yieldTasks() {
        let current = sortedTasks()
        taskContinuations.values.forEach { $0.yield(current) }
    }

    /// U-4 签名同步：optionId 形参（A-3 wire 形态）；演示路径忽略参数保持原行为
    @discardableResult
    func approve(taskID: String, optionId: String) async -> TaskDecisionOutcome {
        mutate(taskID: taskID) { task in
            task.status = .running
            task.pendingCommand = nil
            task.pendingImpact = nil
            task.summary = "已批准执行，任务继续推进中。"
        }
        return .accepted
    }

    @discardableResult
    func reject(taskID: String, optionId: String) async -> TaskDecisionOutcome {
        mutate(taskID: taskID) { task in
            task.status = .running
            task.pendingCommand = nil
            task.pendingImpact = nil
            task.summary = "已拒绝执行命令，Agent 正在调整方案。"
        }
        return .accepted
    }

    func stop(taskID: String) async -> String? {
        mutate(taskID: taskID) { task in
            task.status = .failed
            task.lastLog = "任务已被手动停止（exit code 130）"
            task.summary = "已停止。可从最后快照续跑。"
        }
        return nil
    }

    func retry(taskID: String) async {
        mutate(taskID: taskID) { task in
            task.status = .running
            task.lastLog = nil
            task.summary = "从最后快照续跑中…"
        }
    }

    func terminalStream(taskID: String) -> AsyncStream<TerminalLine> {
        AsyncStream { continuation in
            let key = UUID()
            terminalContinuations[taskID, default: [:]][key] = continuation
            continuation.onTermination = { _ in
                Task { await self.removeTerminalContinuation(taskID: taskID, key: key) }
            }
            Task { await self.pumpTerminal(taskID: taskID) }
        }
    }

    func trajectoryLines(taskID: String) async -> [TerminalLine] {
        [
            TerminalLine(id: 1, label: "[plan]", text: "拆解任务为 5 个步骤，预计 12 分钟"),
            TerminalLine(id: 2, label: "[tool]", text: "read Sources/Core/SessionStore.swift (128 行)"),
            TerminalLine(id: 3, label: "[tool]", text: "edit Sources/Core/SessionStore.swift +5 -2"),
            TerminalLine(id: 4, label: "[agent]", text: "调用方迁移决策待用户确认，已发起提问"),
            TerminalLine(id: 5, label: "[subagent]", text: String(localized: "子智能体 test-runner 运行中（回归 46 用例）")),
        ]
    }

    // MARK: - 终端泵

    private func pumpTerminal(taskID: String) async {
        let history: [(String?, String, Bool, Bool)] = [
            (nil, "npm run db:migrate -- --env=staging", true, false),
            ("[migrate]", "resolving migrations… found 3 pending", false, false),
            ("[migrate]", "applying 0042_add_index_to_sessions.sql", false, false),
            ("[migrate]", "applying 0043_backfill_tokens.sql", false, false),
            ("[migrate]", "backfill complete: 18,204 rows in 9.6s", false, true),
        ]
        for (label, text, isCommand, isSuccess) in history {
            emit(taskID, TerminalLine(id: nextLineID(), label: label, text: text, isCommand: isCommand, isSuccess: isSuccess))
            try? await Task.sleep(nanoseconds: 120_000_000)
        }
        let tail = [
            ("[migrate]", "applying 0044_partition_events.sql", false, false),
            ("[migrate]", "scanning events… 42%", false, false),
            ("[migrate]", "scanning events… 78%", false, false),
            ("[migrate]", "partition created, vacuuming", false, false),
            ("[migrate]", "3 migrations applied, schema v44 ✓", false, true),
        ]
        for (label, text, _, isSuccess) in tail {
            emit(taskID, TerminalLine(id: nextLineID(), label: label, text: text, isSuccess: isSuccess))
            try? await Task.sleep(nanoseconds: 900_000_000)
        }
    }

    private func nextLineID() -> Int {
        terminalLineID += 1
        return terminalLineID
    }

    private func emit(_ taskID: String, _ line: TerminalLine) {
        terminalContinuations[taskID]?.values.forEach { $0.yield(line) }
    }

    private func removeTerminalContinuation(taskID: String, key: UUID) {
        terminalContinuations[taskID]?[key] = nil
    }

    private func sortedTasks() -> [TaskRecord] {
        tasks.sorted { a, b in
            let rank: [TaskStatus: Int] = [.waiting: 0, .running: 1, .failed: 2, .done: 3]
            if rank[a.status] != rank[b.status] { return rank[a.status]! < rank[b.status]! }
            return a.updatedAt > b.updatedAt
        }
    }

    private func mutate(taskID: String, _ change: (inout TaskRecord) -> Void) {
        guard let index = tasks.firstIndex(where: { $0.id == taskID }) else { return }
        change(&tasks[index])
        tasks[index].updatedAt = Date()
        yieldTasks()
    }

    private func seed() {
        let now = Date()
        tasks = [
            TaskRecord(
                id: "t1", title: "执行数据库迁移 v42",
                summary: "等待批准：将修改 staging 库 3 张表结构。",
                directory: "~/work/zcode/server", status: .waiting,
                todoDone: 4, todoTotal: 5, progress: 0.8,
                tools: ["bash", "edit"], updatedAt: now.addingTimeInterval(-240),
                pendingCommand: "npm run db:migrate -- --env=staging",
                pendingImpact: "修改 sessions / tokens / events 三张表结构，约 120s，期间写入短暂排队。"),
            TaskRecord(
                id: "t2", title: "重构会话持久层",
                summary: "协议抽取完成，迁移最后 2 处调用方中。",
                directory: "~/work/zcode", status: .running,
                todoDone: 3, todoTotal: 5, progress: 0.6,
                tools: ["edit", "bash"], updatedAt: now.addingTimeInterval(-720)),
            TaskRecord(
                id: "t3", title: "首页性能调优",
                summary: "长列表懒加载改造，回归跑测中。",
                directory: "~/work/zcode-mobile", status: .running,
                todoDone: 1, todoTotal: 4, progress: 0.25,
                tools: ["read", "edit"], updatedAt: now.addingTimeInterval(-3600)),
            TaskRecord(
                id: "t4", title: "同步镜像仓库",
                summary: "网络解析失败，可从快照续跑。",
                directory: "~/work/mirror", status: .failed,
                todoDone: 2, todoTotal: 6, progress: 0.33,
                tools: ["bash"], updatedAt: now.addingTimeInterval(-5400),
                lastLog: "fatal: unable to access 'https://git.internal/zcode.git': Could not resolve host: git.internal"),
            TaskRecord(
                id: "t5", title: "生成周报 · 第 40 周",
                summary: "WEEK-40.md 已生成并同步。",
                directory: "~/work/notes", status: .done,
                todoDone: 3, todoTotal: 3, progress: 1.0,
                tools: ["bash", "edit"], updatedAt: now.addingTimeInterval(-60 * 60 * 26)),
            TaskRecord(
                id: "t6", title: "补齐单元测试",
                summary: "覆盖率 61% → 78%。",
                directory: "~/work/zcode", status: .done,
                todoDone: 5, todoTotal: 5, progress: 1.0,
                tools: ["edit", "bash"], updatedAt: now.addingTimeInterval(-60 * 60 * 50)),
        ]
    }
}

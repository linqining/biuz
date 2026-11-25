import SwiftUI

// MARK: - 二级模型选择器（2026-11-25 重设计裁决 R5/批4，design/design-spec.md §3.3）
//
// 替换 ComposerOptionSheet 的扁平模型长列表（套餐多、模型多时一条滚动到底，60pt/行）。
// 结构对齐 web 窄屏形态（ComposerProviderMenu ≤639px 下钻先例）：
//  一级 = 搜索 + 最近使用（≤3）+ 分组下钻行（套餐getView planGroups 序 + 「其他」组）
//         + 页脚「管理模型」；
//  二级 = 该组模型单选 + 思考强度分段（当前模型词表，缺席隐藏）。
// 「其他」组（必修缺陷，Android 同构已修）：非套餐 provider（ccgithub / DeepSeek 等
// 自定义源）的模型此前不进任何 planGroups → 面板里彻底消失；现按「不在任一组内的
// models 归入其他」回退（web composerPlanGroups.ts:74-120 同语义），组名固定「其他」，
// 行键「其他|模型」经 provider(forRowKey:) 复合表/裸名表解析 providerId。
// 最近使用：UserDefaults 行键（composer.modelRecents.v1，cap 3）——web 无搜索/最近，
// 移动端触屏补两项可达性（design-spec 登记为刻意新增）。
struct ModelPickerSheet: View {
    /// getView 投影（nil = 未就绪 → 呈「重新获取」行）
    let selection: ModelSelectionInfo?
    /// workspace-config 思考档词表（ChatViewModel.thoughtLevels；nil 回退 selection 词表）
    var configuredThoughtLevels: [String]? = nil
    /// 点模型行（rowKey =「套餐|模型」/「其他|模型」复合键；modelLabel = 纯模型名；
    /// thought = 二级分段当前选中，nil = 未动档位）
    let onPick: (_ rowKey: String, _ modelLabel: String, _ thought: String?) -> Void
    /// 「重新获取」（宿主负责重拉 getView + 失败提示；重开由宿主 onDismiss 链承担）
    let onReload: () -> Void
    /// 页脚「管理模型」（宿主跳设置.模型页；nil = 不显示页脚）
    var onManageModels: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    /// 下钻中的组（nil = 一级）
    @State private var activeGroup: ModelPlanGroup?
    @State private var recents: [String] = ModelPickerRecents.load()
    /// 二级思考分段选中（呈现时预选当前档）
    @State private var pickedThought: String?

    private static let othersPlan = String(localized: "其他")

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            if let group = activeGroup {
                groupBody(group)
            } else {
                rootBody
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .background(T.bgElevated)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .presentationBackground(T.bgElevated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-model-sheet")
    }

    // MARK: 一级：搜索 + 最近 + 分组下钻

    private var groups: [ModelPlanGroup] {
        Self.makeGroups(selection)
    }

    /// 分组装配（纯函数，可测）：套餐分组原序 + 「其他」组（models 里不在任一
    /// 分组的模型——ccgithub 等自定义 provider 必修回退，web
    /// composerPlanGroups.ts:74-120 同语义）。
    static func makeGroups(_ selection: ModelSelectionInfo?) -> [ModelPlanGroup] {
        let info = selection ?? ModelSelectionInfo()
        var list = info.planGroups
        let claimed = Set(info.planGroups.flatMap(\.models))
        let others = info.models.filter { !claimed.contains($0) }
        // 其他 = **按 provider 平铺**（2026-11-25 用户报「只有我的和其他；其他的应该平铺、
        // 和我的同级」）：此前把未归属套餐的模型全塞进一个「其他」组，自定义 provider
        // （ccgithub 等）整块消失在一个没有身份的组里。现按 modelProviders 的 providerId
        // 分桶，每个 provider 自成一档、与套餐组同级；providerId 缺席的才落到「其他」。
        if !others.isEmpty {
            var order: [String] = []
            var buckets: [String: [String]] = [:]
            for model in others {
                let key = Self.providerGroupName(info.modelProviders[model])
                if buckets[key] == nil { order.append(key) }
                buckets[key, default: []].append(model)
            }
            for key in order {
                list.append(ModelPlanGroup(plan: key, models: buckets[key] ?? []))
            }
        }
        return list
    }

    /// providerId → 一级分组名（去掉 `provider:` 前缀与尾部 `-plan`，保持原词大小写）。
    /// 例：`ccgithub` → `ccgithub`；`zai:individual-coding-plan` → `zai`（真套餐组由
    /// planGroups 承担，这里只处理未认领的）；空/未知 → 「其他」。
    static func providerGroupName(_ providerId: String?) -> String {
        guard let raw = providerId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return Self.othersPlan }
        var name = raw
        if let colon = name.lastIndex(of: ":") { name = String(name[name.index(after: colon)...]) }
        if name.hasSuffix("-plan") { name = String(name.dropLast("-plan".count)) }
        return name.isEmpty ? Self.othersPlan : name
    }

    private var rootBody: some View {
        VStack(spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Text(String(localized: "模型"))
                    .font(T.font(16, .bold))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(T.text2)
                        .frame(width: 30, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("05-model-close")
            }
            .padding(.horizontal, T.sp4)
            SearchField(text: $query, placeholder: String(localized: "搜索模型"), identifier: "05-model-search")
                .padding(.horizontal, T.sp4)
            ScrollView {
                VStack(spacing: T.sp2) {
                    if !hasSelectableModels {
                        reloadRow
                    } else if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                        searchResults
                    } else {
                        if !recents.isEmpty {
                            sectionHeader(String(localized: "最近使用"))
                            ForEach(recents, id: \.self) { rowKey in
                                recentRow(rowKey)
                            }
                        }
                        sectionHeader(String(localized: "按套餐分组"))
                        ForEach(groups) { group in
                            groupRow(group)
                        }
                    }
                    manageModelsFooter
                }
                .padding(.horizontal, T.sp4)
            }
            .scrollIndicators(.hidden)
        }
    }

    private var hasSelectableModels: Bool {
        (selection?.hasSelectableModels ?? false) || !groups.isEmpty
    }

    /// 搜索结果：跨组扁平命中（行键保留组前缀——provider 解析与正常点选同路）
    private var searchResults: some View {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        let hits: [(group: ModelPlanGroup, model: String)] = groups.flatMap { group in
            group.models.filter { $0.lowercased().contains(needle) }.map { (group, $0) }
        }
        return Group {
            if hits.isEmpty {
                Text(String(localized: "无匹配模型"))
                    .font(T.font(12))
                    .foregroundColor(T.text3)
                    .frame(maxWidth: .infinity, minHeight: 44)
            } else {
                ForEach(hits, id: \.model) { hit in
                    modelRow(group: hit.group, model: hit.model)
                }
            }
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(T.font(11, .semibold))
            .foregroundColor(T.text3)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 「重新获取」行（getView 失败/空回执；不拒开面板——死胡同收口同旧口径）
    private var reloadRow: some View {
        Button {
            dismiss()
            onReload()
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 14))
                    .foregroundColor(T.text)
                Text(String(localized: "重新获取模型列表"))
                    .font(T.font(14.5, .medium))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 56)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        }
        .accessibilityIdentifier("05-model-reload")
    }

    /// 一级组行：组名 + N 个模型 + chevron（下钻）
    private func groupRow(_ group: ModelPlanGroup) -> some View {
        Button {
            pickedThought = selection?.activeThoughtLevel
            activeGroup = group
        } label: {
            HStack(spacing: T.sp3) {
                Image(systemName: "cpu")
                    .font(.system(size: 14))
                    .foregroundColor(T.text2)
                    .frame(width: 38, height: 38)
                    .background(T.accentDim)
                    .clipShape(Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(group.plan)
                        .font(T.font(14.5, .medium))
                        .foregroundColor(T.text)
                    Text(String(localized: "\(group.models.count) 个模型"))
                        .font(T.font(11))
                        .foregroundColor(T.text3)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(T.text3)
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 60)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        }
        .accessibilityIdentifier("05-model-group-\(group.plan)")
    }

    /// 最近使用行（行键 → 展示名；解析不出组归属时退「其他」键样式照常工作）
    private func recentRow(_ rowKey: String) -> some View {
        let label = selection?.modelLabel(forRowKey: rowKey) ?? rowKey
        return Button {
            pick(rowKey: rowKey, modelLabel: label, thought: selection?.activeThoughtLevel)
        } label: {
            HStack(spacing: T.sp2) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 13))
                    .foregroundColor(T.text3)
                Text(label)
                    .font(T.font(14.5, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if isSelectedRow(rowKey) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 52)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        }
        .accessibilityIdentifier("05-model-row-recent-\(rowKey)")
    }

    // MARK: 二级：组内单选 + 思考分段

    private func groupBody(_ group: ModelPlanGroup) -> some View {
        let selection = self.selection ?? ModelSelectionInfo()
        return VStack(spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Button {
                    activeGroup = nil
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(T.text)
                        .frame(width: 30, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("05-model-back")
                Text(group.plan)
                    .font(T.font(16, .bold))
                    .foregroundColor(T.text)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, T.sp4)
            ScrollView {
                VStack(spacing: T.sp2) {
                    ForEach(group.models, id: \.self) { model in
                        modelRow(group: group, model: model)
                    }
                    thoughtSection(group: group, selection: selection)
                }
                .padding(.horizontal, T.sp4)
            }
            .scrollIndicators(.hidden)
        }
    }

    /// 模型单选行（选中勾 = 行 provider == 当前绑定；同名跨套餐消歧按复合表）
    private func modelRow(group: ModelPlanGroup, model: String) -> some View {
        let rowKey = "\(group.plan)|\(model)"
        return Button {
            pick(rowKey: rowKey, modelLabel: model, thought: effectiveThought(for: model))
        } label: {
            HStack(spacing: T.sp3) {
                Image(systemName: "cpu")
                    .font(.system(size: 14))
                    .foregroundColor(T.text2)
                    .frame(width: 38, height: 38)
                    .background(T.accentDim)
                    .clipShape(Circle())
                Text(model)
                    .font(T.font(14.5, .medium))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if isSelectedRow(rowKey) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 56)
            .background(isSelectedRow(rowKey) ? T.accentDim.opacity(0.45) : T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(
                isSelectedRow(rowKey) ? T.accentText.opacity(0.45) : T.border, lineWidth: 1))
        }
        .accessibilityIdentifier("05-model-row-\(rowKey)")
    }

    /// 二级思考强度分段（当前模型词表；单档静态、空档隐藏——web 同口径）
    @ViewBuilder
    private func thoughtSection(group: ModelPlanGroup, selection: ModelSelectionInfo) -> some View {
        let probeModel = group.models.first ?? ""
        let levels = thoughtLevels(for: probeModel, selection: selection)
        if !levels.isEmpty {
            VStack(alignment: .leading, spacing: T.sp1) {
                sectionHeader(String(localized: "思考强度"))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: T.sp2) {
                        ForEach(levels, id: \.self) { level in
                            Button {
                                pickedThought = level
                            } label: {
                                Text(level)
                                    .font(T.font(12, .medium))
                                    .foregroundColor(pickedThought == level ? T.onAccent : T.text2)
                                    .padding(.horizontal, T.sp3)
                                    .frame(minHeight: 44)
                                    .background(pickedThought == level ? T.accent : T.bgInput)
                                    .clipShape(Capsule())
                            }
                            .accessibilityIdentifier("05-thought-seg-\(level)")
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, T.sp1)
        }
    }

    /// 点选生效：记录最近使用（cap 3）→ 关面板 → 宿主回调（切模型走既有
    /// switchModel 乐观落 chip + 3s 护栏 + 常驻错误行口径）
    private func pick(rowKey: String, modelLabel: String, thought: String?) {
        ModelPickerRecents.record(rowKey)
        dismiss()
        onPick(rowKey, modelLabel, thought)
    }

    // MARK: 解析辅助

    private func isSelectedRow(_ rowKey: String) -> Bool {
        guard let selection else { return false }
        let providerMatches = selection.activeProviderId?.isEmpty == false
            ? selection.modelProvidersByPlan[rowKey] == selection.activeProviderId
            : true
        return selection.modelLabel(forRowKey: rowKey) == selection.activeModel && providerMatches
    }

    /// 点模型时的思考档：二级已动过分段用 pickedThought；否则沿用当前档
    /// （switchModel thought 缺省会带 activeThoughtLevel，宿主口径不变）
    private func effectiveThought(for model: String) -> String? {
        let selection = self.selection ?? ModelSelectionInfo()
        let levels = thoughtLevels(for: model, selection: selection)
        if let picked = pickedThought, levels.contains(picked) { return picked }
        return selection.activeThoughtLevel
    }

    private func thoughtLevels(for model: String, selection: ModelSelectionInfo) -> [String] {
        if let byModel = selection.thoughtByModel[model], !byModel.isEmpty {
            return byModel
        }
        if let byModel = selection.thoughtByModel[selection.modelLabel(forRowKey: model)], !byModel.isEmpty {
            return byModel
        }
        if let configured = configuredThoughtLevels, !configured.isEmpty {
            return configured
        }
        return selection.thoughtLevels
    }

    private var manageModelsFooter: some View {
        Group {
            if let onManageModels {
                Button {
                    dismiss()
                    onManageModels()
                } label: {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 13))
                            .foregroundColor(T.text3)
                        Text(String(localized: "管理模型"))
                            .font(T.font(13, .medium))
                            .foregroundColor(T.text2)
                        Spacer(minLength: 0)
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 11))
                            .foregroundColor(T.text3)
                    }
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 44)
                }
                .accessibilityIdentifier("05-model-manage")
            } else {
                EmptyView()
            }
        }
    }
}

/// 最近使用模型行键（UserDefaults，cap 3；进程内全局——跨会话复用的选择习惯）
enum ModelPickerRecents {
    static let storeKey = "composer.modelRecents.v1"
    static let capacity = 3

    static func load() -> [String] {
        UserDefaults.standard.stringArray(forKey: storeKey) ?? []
    }

    static func record(_ rowKey: String) {
        var list = load().filter { $0 != rowKey }
        list.insert(rowKey, at: 0)
        if list.count > capacity {
            list = Array(list.prefix(capacity))
        }
        UserDefaults.standard.set(list, forKey: storeKey)
    }
}

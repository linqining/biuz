import SwiftUI

// MARK: - Composer 选择器自绘底部面板（统一设计语言）
//
// 用户 2026-10-07：系统 Menu 弹层与 App 设计风格不符——AttachmentSourceSheet
// 同款设计语言（grabber + 卡片行 + 取消），全部主题令牌（Color(light:dark:) 双形态
// 自适配系统深浅色）。宿主：ChatView ComposerBar（协作/投递/执行目标/模型/思考五处）
// + NewConversationSheet（模型/思考两处）。
// 行按钮聚合 label 恒为项名：test07 以 app.buttons label 精确匹配
// 「云端沙盒 / E2E-Relay-Mac」、test12 以 label == 'low' 匹配思考档——目标/思考行
// detail 必须为空、选中态用符号图不用文字。

/// 面板档位（宿主各持 options 装配器，本枚举只承载标题/页脚/触发标签）
enum ComposerSheetKind: String, Identifiable {
    case collaboration, delivery, target, model, thought
    var id: String { rawValue }

    var title: String {
        switch self {
        case .collaboration: return String(localized: "协作模式")
        case .delivery: return String(localized: "投递模式")
        case .target: return String(localized: "执行目标")
        case .model: return String(localized: "模型")
        case .thought: return String(localized: "思考强度")
        }
    }

    var footer: String? {
        switch self {
        case .collaboration: return nil
        case .delivery: return String(localized: "引导模式桌面语义以实际执行行为为准")
        // G-012：如实标注——当前为发送偏好记录，无信封级目标路由
        case .target: return String(localized: "仅记录发送偏好 · 消息经当前连接的桌面端执行")
        case .model, .thought: return nil
        }
    }
}

struct ComposerOptionItem: Identifiable {
    let id: String
    let title: String
    let detail: String
    let icon: String
    var selected: Bool = false
    var a11yId: String? = nil
    /// 套餐分节标题（模型菜单按 planGroups 分节；nil = 不带节头）
    var section: String? = nil
    /// 应用值（缺省回退 id）：模型行 id 携套餐前缀保唯一，payload 存纯模型名
    var payload: String? = nil
}

struct ComposerOptionSheet: View {
    let title: String
    var footer: String? = nil
    let options: [ComposerOptionItem]
    var onPick: (ComposerOptionItem) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: T.sp3) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text(title).font(T.font(16, .bold)).foregroundColor(T.text)
                Spacer()
            }
            .padding(.horizontal, T.sp4)
            // 滚动区：模型清单可能超一屏（medium/large 双档），固定三选择器内容
            // 不超界时无滚动表现
            ScrollView {
                VStack(spacing: T.sp2) {
                    ForEach(groupedRows, id: \.item.id) { row in
                        if let section = row.section {
                            Text(section)
                                .font(T.font(11, .semibold))
                                .foregroundColor(T.text3)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.top, T.sp1)
                        }
                        optionRow(row.item)
                    }
                }
                .padding(.horizontal, T.sp4)
            }
            .scrollIndicators(.hidden)
            if let footer {
                Text(footer)
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, T.sp4)
            }
            Button {
                dismiss()
            } label: {
                Text("取消")
                    .font(T.font(14.5, .medium))
                    .foregroundColor(T.text2)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(T.bgInput)
                    .clipShape(Capsule())
            }
            .padding(.horizontal, T.sp4)
            .accessibilityIdentifier("05-composer-option-cancel")
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .background(T.bgElevated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("05-composer-option-sheet")
    }

    /// 分节装配：section 与上一行相同时不再重复节头
    private var groupedRows: [(section: String?, item: ComposerOptionItem)] {
        var rows: [(String?, ComposerOptionItem)] = []
        var last: String??
        for option in options {
            if last != .some(option.section) {
                rows.append((option.section, option))
            } else {
                rows.append((nil, option))
            }
            last = .some(option.section)
        }
        return rows
    }

    private func optionRow(_ option: ComposerOptionItem) -> some View {
        Button {
            onPick(option)
        } label: {
            HStack(spacing: T.sp3) {
                Image(systemName: option.icon)
                    .font(.system(size: 15))
                    .foregroundColor(T.accentText)
                    .frame(width: 38, height: 38)
                    .background(T.accentDim)
                    .clipShape(Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(option.title).font(T.font(14.5, .medium)).foregroundColor(T.text)
                    if !option.detail.isEmpty {
                        Text(option.detail)
                            .font(T.font(11.5))
                            .foregroundColor(T.text3)
                            .multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: 0)
                if option.selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(T.accentText)
                }
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 60)
            .background(option.selected ? T.accentDim.opacity(0.45) : T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(
                option.selected ? T.accentText.opacity(0.45) : T.border, lineWidth: 1))
        }
        .buttonStyle(PressableButtonStyle())
        .accessibilityIdentifier(option.a11yId ?? "05-composer-option-\(option.id)")
    }
}

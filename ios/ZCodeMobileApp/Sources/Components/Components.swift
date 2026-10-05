import SwiftUI

// MARK: - 状态胶囊（spec 5.1）：22px 高、11px/600、999 圆角、6px 状态点

enum PillKind {
    case run, wait, done, err, tag
    var fg: Color {
        switch self {
        case .run: return T.blue
        case .wait: return T.orange
        case .done: return T.accentText
        case .err: return T.red
        case .tag: return T.violet
        }
    }
    var bg: Color {
        switch self {
        case .run: return T.blueDim
        case .wait: return T.orangeDim
        case .done: return T.accentDim
        case .err: return T.redDim
        case .tag: return T.violetDim
        }
    }
}

struct StatusPill: View {
    let text: String
    let kind: PillKind
    var compact: Bool = false

    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(kind.fg).frame(width: 6, height: 6)
            Text(text)
                .font(T.font(11, .semibold))
                .foregroundColor(kind.fg)
                .lineLimit(1)
        }
        .padding(.horizontal, compact ? 7 : 9)
        .frame(height: compact ? 18 : 22)
        .background(kind.bg)
        .clipShape(Capsule())
    }
}

extension TaskStatus {
    var pillKind: PillKind {
        switch self {
        case .running: return .run
        case .waiting: return .wait
        case .done: return .done
        case .failed: return .err
        }
    }
    var label: String {
        switch self {
        case .running: return String(localized: "运行中")
        case .waiting: return String(localized: "待操作")
        case .done: return String(localized: "已完成")
        case .failed: return String(localized: "失败")
        }
    }
}

// MARK: - 数字徽章（Tab 三色徽章，16px，前景 --badge-fg）

struct TabBadge: View {
    let count: Int
    let color: Color

    var body: some View {
        Text("\(count)")
            .font(T.font(10, .bold))
            .foregroundColor(T.badgeFg)
            .padding(.horizontal, 4)
            .frame(minWidth: 16, minHeight: 16)
            .background(color)
            .clipShape(Capsule())
    }
}

// MARK: - 按钮（spec 5.5）

struct PrimaryButton: View {
    // G-007：LocalizedStringKey 使字面量标题走 Localizable 查表（调用点全为字面量，已核对）
    let title: LocalizedStringKey
    var identifier: String = ""
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(T.font(15, .semibold))
                .foregroundColor(T.onAccent)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(T.accent)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .buttonStyle(PressableButtonStyle())
        .accessibilityIdentifier(identifier)
    }
}

/// 文字链接类动作：min-height 44 热区（padding 外扩法）
struct TextActionButton: View {
    let title: LocalizedStringKey
    var tint: Color = T.accentText
    var action: () -> Void
    var identifier: String = ""

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(T.font(13, .medium))
                .foregroundColor(tint)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .accessibilityIdentifier(identifier)
    }
}

struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - spinner（2.7：1s 线性旋转）

struct SpinnerView: View {
    var color: Color = T.blue
    var size: CGFloat = 18
    @State private var rotating = false

    var body: some View {
        Circle()
            .stroke(color.opacity(0.28), lineWidth: 2)
            .overlay(
                Circle()
                    .trim(from: 0, to: 0.3)
                    .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .rotationEffect(.degrees(rotating ? 360 : 0))
            )
            .frame(width: size, height: size)
            .onAppear {
                withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) {
                    rotating = true
                }
            }
    }
}

/// 流式输出光标（绿块闪烁）
struct BlinkingCursor: View {
    @State private var visible = true
    var body: some View {
        RoundedRectangle(cornerRadius: 1.5)
            .fill(T.accent)
            .frame(width: 7, height: 14)
            .opacity(visible ? 1 : 0)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) {
                    visible = false
                }
            }
    }
}

// MARK: - 空态 / 加载态（spec 5.9：一律不用骨架屏）

struct EmptyStateView: View {
    let icon: String
    let title: String
    let detail: String
    var cta: String? = nil
    var ctaAction: () -> Void = {}
    var ctaIdentifier: String = ""

    var body: some View {
        VStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 24))
                .foregroundColor(T.text3)
                .frame(width: 56, height: 56)
                .background(T.bgInput)
                .clipShape(Circle())
            Text(title)
                .font(T.font(14, .bold))
                .foregroundColor(T.text)
            Text(detail)
                .font(T.font(11.5))
                .foregroundColor(T.text3)
                .multilineTextAlignment(.center)
            if let cta {
                Button(action: ctaAction) {
                    Text(cta)
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.onAccent)
                        .padding(.horizontal, T.sp4)
                        .frame(minHeight: 44)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .padding(.top, T.sp1)
                .accessibilityIdentifier(ctaIdentifier)
            }
        }
        .padding(T.sp6)
        .frame(maxWidth: .infinity)
    }
}

struct CenterLoadingView: View {
    let text: LocalizedStringKey
    var identifier: String = "loading-center"

    var body: some View {
        VStack(spacing: T.sp3) {
            SpinnerView(size: 28)
            Text(text)
                .font(T.font(11.5))
                .foregroundColor(T.text3)
        }
        .frame(maxWidth: .infinity, minHeight: 240)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - 分段控件（spec 5.7：容器 bg-input 12px，段高 ≥44）

struct ZSegmentedPicker<Item: Hashable>: View {
    let items: [Item]
    let label: (Item) -> String
    @Binding var selection: Item
    var identifierPrefix: String = "seg"

    var body: some View {
        HStack(spacing: 4) {
            ForEach(items, id: \.self) { item in
                Button {
                    withAnimation(.easeOut(duration: 0.18)) { selection = item }
                } label: {
                    Text(label(item))
                        .font(T.font(13, selection == item ? .semibold : .regular))
                        .foregroundColor(selection == item ? T.text : T.text2)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background {
                            if selection == item {
                                RoundedRectangle(cornerRadius: T.rS)
                                    .fill(T.bgElevated)
                                    .shadow(color: T.shadowCard.opacity(0.3), radius: 6, y: 2)
                            }
                        }
                }
                .accessibilityIdentifier("\(identifierPrefix)-\(label(item).lowercased())")
            }
        }
        .padding(4)
        .background(T.bgInput)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
    }
}

// MARK: - 搜索框（44px、16px 字号防缩放）

struct SearchField: View {
    @Binding var text: String
    var placeholder: String = String(localized: "搜索")
    var identifier: String = "search"

    var body: some View {
        HStack(spacing: T.sp2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14))
                .foregroundColor(T.text3)
            TextField(placeholder, text: $text)
                .font(T.font(16))
                .foregroundColor(T.text)
                .autocorrectionDisabled()
                .accessibilityIdentifier(identifier + "-input")
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundColor(T.text3)
                }
            }
        }
        .padding(.horizontal, T.sp3)
        .frame(height: 44)
        .background(T.bgInput)
        .clipShape(Capsule())
        // 容器声明为「包含子元素」：否则整行被合成为单一可访问性元素，
        // 内部输入框的 identifier（<id>-input）不暴露，e2e 无法定位输入（门禁第 1 轮实证）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - 卡片容器

struct CardBackground: ViewModifier {
    var padding: CGFloat = 14

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
    }
}

extension View {
    func card(padding: CGFloat = 14) -> some View {
        modifier(CardBackground(padding: padding))
    }
}

// MARK: - 头像（Agent 渐变 Z）

struct AgentAvatar: View {
    var size: CGFloat = 40

    var body: some View {
        Text("Z")
            .font(T.font(size * 0.45, .bold))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(T.gradAvatar)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.28))
    }
}

// MARK: - 进度细条

struct ThinProgressBar: View {
    let progress: Double
    var height: CGFloat = 3
    var tint: Color = T.blue

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(T.bgInput)
                Capsule().fill(tint).frame(width: proxy.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: height)
    }
}

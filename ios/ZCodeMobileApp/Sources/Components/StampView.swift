import SwiftUI

// MARK: - 朱批盖章动效（motion-spec 口径，brand/seal/motion-spec.html）
//
// 落印 460ms：scale 1.6 → 0.96 → 1.04 → 1.0 · rotate −10° → −2.5° · opacity 0→0.94，
// cubic-bezier(.22,.68,.32,1)；印泥外溢圈 520ms 延迟 180ms（颜色随印：朱印朱圈、墨印墨圈）。
// 印面 Path = brand/seal/seal-mark.svg 逐字移植（右下残边是品牌签名，不得修复）；
// 印文「照办/不准」为图形——Text(verbatim:) 不进 xcstrings（印文不译，同 logo），
// VoiceOver 由容器 label「已批准/已拒绝」播报。
// 落印时机 = ACK 权威成功之后（失败/未送达不落印，U-5：印是权威成功专属）；
// prefers-reduced-motion 下动效停用，印以终态直出（落印场合恒 −2.5°，上限 ±8°）。
// 变换与透明度只在本文件「一层」生效：静态路径直出终态，动画路径由 keyframe 独占。

/// 印面关键帧态（initialValue = 落印起点）
struct SealStampFrame: Equatable {
    var scale: CGFloat = 1.6
    var rotation: Double = -10
    var opacity: Double = 0
}

/// 印面形状：100×100 坐标系，缩放铺满给定 rect
struct SealFaceShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let s = min(rect.width, rect.height) / 100
        let ox = rect.midX - 50 * s
        let oy = rect.midY - 50 * s
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: ox + x * s, y: oy + y * s)
        }
        p.move(to: pt(14, 0))
        p.addLine(to: pt(86, 0))
        p.addQuadCurve(to: pt(100, 14), control: pt(100, 0))
        p.addLine(to: pt(100, 84))
        p.addLine(to: pt(91, 86))
        p.addLine(to: pt(94, 94))
        p.addLine(to: pt(85, 96))
        p.addLine(to: pt(82, 100))
        p.addLine(to: pt(14, 100))
        p.addQuadCurve(to: pt(0, 86), control: pt(0, 100))
        p.addLine(to: pt(0, 14))
        p.addQuadCurve(to: pt(14, 0), control: pt(0, 0))
        p.closeSubpath()
        return p
    }
}

/// 一枚印：朱印「照办」（批准）/ 墨印「不准」（拒绝）。墨印在深色档翻转为纸面墨字
/// （T.text/T.bg 动态取值），保证墨底上依然可读——印是「落」在当前界面上的。
struct SealStampView: View {
    let approved: Bool
    /// 视觉边长（布局框恒按 100pt 设计坐标构建后整体缩放）
    var size: CGFloat = 100

    @State private var played = false
    @State private var ringExpanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var faceColor: Color { approved ? T.seal : T.text }
    private var inkColor: Color { approved ? T.onSeal : T.bg }
    private var ringColor: Color { approved ? T.seal : T.text }
    private var characters: [String] { approved ? ["照", "办"] : ["不", "准"] }

    var body: some View {
        Group {
            if reduceMotion {
                faceVisual
                    .scaleEffect(1)
                    .rotationEffect(.degrees(-2.5))
                    .opacity(0.94)
            } else {
                faceVisual.keyframeAnimator(
                    initialValue: SealStampFrame(),
                    trigger: played
                ) { view, frame in
                    view
                        .scaleEffect(frame.scale)
                        .rotationEffect(.degrees(frame.rotation))
                        .opacity(frame.opacity)
                } keyframes: { _ in
                    KeyframeTrack(\.scale) {
                        CubicKeyframe(0.96, duration: 0.253)
                        CubicKeyframe(1.04, duration: 0.100)
                        CubicKeyframe(1.0, duration: 0.107)
                    }
                    KeyframeTrack(\.rotation) {
                        CubicKeyframe(-2.0, duration: 0.253)
                        CubicKeyframe(-3.2, duration: 0.100)
                        CubicKeyframe(-2.5, duration: 0.107)
                    }
                    KeyframeTrack(\.opacity) {
                        CubicKeyframe(1.0, duration: 0.253)
                        CubicKeyframe(0.97, duration: 0.100)
                        CubicKeyframe(0.94, duration: 0.107)
                    }
                }
            }
        }
        .overlay {
            if !reduceMotion {
                Circle()
                    .stroke(ringColor, lineWidth: 2)
                    .scaleEffect(ringExpanded ? 1.45 : 0.9)
                    .opacity(ringExpanded ? 0 : 0.7)
                    .onAppear {
                        withAnimation(.easeOut(duration: 0.52).delay(0.18)) {
                            ringExpanded = true
                        }
                    }
            }
        }
        .frame(width: 100, height: 100)
        .scaleEffect(size / 100)
        .frame(width: size, height: size)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: approved ? "已批准" : "已拒绝"))
        .onAppear { played = true }
    }

    /// 印面本体（不含落印变换）：朱磦/墨印面 + 白刻印文
    private var faceVisual: some View {
        ZStack {
            SealFaceShape()
                .fill(faceColor)
            VStack(spacing: 2) {
                ForEach(characters, id: \.self) { char in
                    Text(verbatim: char)
                        .font(.system(size: 30, weight: .black, design: .serif))
                        .foregroundColor(inkColor)
                }
            }
        }
    }
}

/// 审批卡盖章 overlay：VM 在 ACK 权威成功后置 stampHolds[id]，卡面落印并停驻至
/// 期满（期满摘除由 VM 的停驻 Task 承担，卡随投影/遮罩离场）。
/// a11y：印本体纯视觉（hidden），标识/label 挂容器静态文本（XCUITest 可查，
/// VoiceOver 播「已批准/已拒绝」）
struct ApprovalStampOverlay: ViewModifier {
    let viewModel: ChatViewModel
    let interactionId: String

    func body(content: Content) -> some View {
        content.overlay {
            if let hold = viewModel.stampHolds[interactionId] {
                ZStack {
                    SealStampView(approved: hold.approved, size: 116)
                        .accessibilityHidden(true)
                    Text(verbatim: hold.approved ? "已批准" : "已拒绝")
                        .font(.system(size: 1))
                        .foregroundColor(.clear)
                        .accessibilityLabel(String(localized: hold.approved ? "已批准" : "已拒绝"))
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("05-approval-stamp")
            }
        }
    }
}

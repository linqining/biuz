import SwiftUI

/// 屏 036 白板浮层（web 侧栏面板的 iOS 对位；Android WhiteboardPanel.kt 镜像）。
/// 零 RPC 纯客户端：画布 1280×800 白底、六色板、笔宽 2–18、eraser=画背景色；
/// 「添加到聊天」= exportPNG 直插 composer 附件（viewModel.uploads.add，web
/// add-to-chat 事件同效）。
struct WhiteboardPanelView: View {
    let workspaceKey: String
    /// 导出 PNG 直插 composer 附件（返回 false = 被拒收，上屏失败提示）
    let onAddToChat: (Data) -> Bool
    let onClose: () -> Void

    @State private var board: WhiteboardDocument
    @State private var tool: WhiteboardTool = .pen
    @State private var color: String = WhiteboardDocument.defaultColor
    @State private var strokeWidth: Double = 4
    @State private var draft: WhiteboardStroke?
    @State private var notice: String?
    @State private var noticeIsError = false

    init(
        workspaceKey: String,
        onAddToChat: @escaping (Data) -> Bool,
        onClose: @escaping () -> Void
    ) {
        self.workspaceKey = workspaceKey
        self.onAddToChat = onAddToChat
        self.onClose = onClose
        _board = State(initialValue: WhiteboardBoardStore.shared.board(for: workspaceKey))
    }

    private func apply(_ mutation: (WhiteboardDocument) -> WhiteboardDocument) {
        board = WhiteboardBoardStore.shared.mutate(workspaceKey, mutation)
    }

    var body: some View {
        VStack(spacing: 0) {
            // 头部：关闭 + 名称即改（web Input onBlur 同口径；空串忽略）+ 添加到聊天
            HStack(spacing: T.sp2) {
                Button(action: onClose) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(T.text)
                        .frame(width: 30, height: 30)
                }
                .accessibilityIdentifier("36-act-close")
                TextField(String(localized: "白板"), text: Binding(
                    get: { board.name },
                    set: { newValue in apply { WhiteboardMutations.rename($0, newValue) } }
                ))
                .font(T.font(14, .semibold))
                .foregroundColor(T.text)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("36-name-field")
                Spacer(minLength: 0)
                Button {
                    let added = String(localized: "已添加到聊天附件")
                    let failed = String(localized: "添加失败 · 附件被拒收")
                    let ok = onAddToChat(exportPNG(board))
                    notice = ok ? added : failed
                    noticeIsError = !ok
                } label: {
                    Text(String(localized: "添加到聊天"))
                        .font(T.font(12, .medium))
                        .foregroundColor(T.accentText)
                }
                .accessibilityIdentifier("36-act-add-to-chat")
            }
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 48)
            Divider().overlay(T.border)

            if let notice {
                Text(notice)
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(noticeIsError ? T.red : T.accentText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, T.sp4)
                    .padding(.vertical, T.sp1)
                    .accessibilityIdentifier("36-export-notice")
            }

            // 画布（1280×800 文档空间按宽适配；笔迹文档坐标存，渲染 ×scale）
            GeometryReader { geo in
                let scale = geo.size.width / CGFloat(WhiteboardDocument.canvasWidth)
                canvas(scale: scale)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(T.border, lineWidth: 1))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(T.sp4)
            }

            // 工具行：pen/eraser + 六色板
            HStack(spacing: T.sp2) {
                toolButton(symbol: "pencil.tip", tool: .pen, id: "36-tool-pen")
                toolButton(symbol: "eraser.fill", tool: .eraser, id: "36-tool-eraser")
                Spacer(minLength: 0)
                ForEach(WhiteboardDocument.swatches, id: \.self) { swatch in
                    Circle()
                        .fill(Color(hex: swatch))
                        .frame(width: 22, height: 22)
                        .overlay(
                            Circle().stroke(
                                color == swatch ? T.accent : T.border,
                                lineWidth: color == swatch ? 2 : 1
                            )
                        )
                        .onTapGesture { color = swatch }
                        .accessibilityIdentifier("36-swatch-\(swatch)")
                }
            }
            .padding(.horizontal, T.sp4)

            // 笔宽滑条（2–18）
            HStack(spacing: T.sp2) {
                Slider(
                    value: Binding(get: { strokeWidth }, set: { strokeWidth = $0 }),
                    in: WhiteboardDocument.minStrokeWidth...WhiteboardDocument.maxStrokeWidth
                )
                .accessibilityIdentifier("36-width-slider")
                Text("\(Int(strokeWidth))")
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
                    .frame(minWidth: 20)
                    .accessibilityIdentifier("36-width-value")
            }
            .padding(.horizontal, T.sp4)

            // 动作行：撤销/重做/清空
            HStack(spacing: T.sp3) {
                actionButton(symbol: "arrow.uturn.backward", enabled: board.canUndo, id: "36-act-undo") {
                    WhiteboardMutations.undo($0)
                }
                actionButton(symbol: "arrow.uturn.forward", enabled: board.canRedo, id: "36-act-redo") {
                    WhiteboardMutations.redo($0)
                }
                Button {
                    apply { WhiteboardMutations.clear($0) }
                } label: {
                    Text(String(localized: "清空"))
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text)
                        .padding(.horizontal, T.sp2)
                        .padding(.vertical, 6)
                }
                .accessibilityIdentifier("36-act-clear")
                Spacer(minLength: 0)
            }
            .padding(.horizontal, T.sp4)
            .padding(.bottom, T.sp2)
        }
        .background(T.bg)
    }

    // MARK: 画布（Canvas 渲染 + DragGesture 采点；eraser=背景色、单点=圆）

    private func canvas(scale: CGFloat) -> some View {
        let bgColor = Color(hex: WhiteboardDocument.backgroundColor)
        return Canvas { context, size in
            context.fill(
                Path(CGRect(x: 0, y: 0, width: size.width, height: size.height)),
                with: .color(bgColor))
            for stroke in board.strokes {
                drawStroke(stroke, scale: scale, backgroundColor: bgColor, context: context)
            }
            if let draft {
                drawStroke(draft, scale: scale, backgroundColor: bgColor, context: context)
            }
        }
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    let point = WhiteboardPoint(
                        x: Double(value.location.x / scale),
                        y: Double(value.location.y / scale))
                    if var current = draft {
                        current.points.append(point)
                        draft = current
                    } else {
                        draft = WhiteboardStroke(
                            tool: tool, color: color,
                            width: strokeWidth, points: [point])
                    }
                }
                .onEnded { _ in
                    // addStroke = 追加 + 清空 redo 栈（web 同口径）
                    if let stroke = draft {
                        apply { WhiteboardMutations.addStroke($0, stroke) }
                    }
                    draft = nil
                })
    }

    /// drawWhiteboardStroke 直译（lib/whiteboard.ts:126-152）
    private func drawStroke(
        _ stroke: WhiteboardStroke, scale: CGFloat,
        backgroundColor: Color, context: GraphicsContext
    ) {
        guard let first = stroke.points.first else { return }
        let paintColor = stroke.tool == .eraser ? backgroundColor : Color(hex: stroke.color)
        let lineWidth = CGFloat(stroke.width) * scale
        if stroke.points.count == 1 {
            let r = lineWidth / 2
            let rect = CGRect(
                x: CGFloat(first.x) * scale - r, y: CGFloat(first.y) * scale - r,
                width: lineWidth, height: lineWidth)
            context.fill(Path(ellipseIn: rect), with: .color(paintColor))
            return
        }
        var path = Path()
        path.move(to: CGPoint(x: CGFloat(first.x) * scale, y: CGFloat(first.y) * scale))
        for point in stroke.points.dropFirst() {
            path.addLine(to: CGPoint(x: CGFloat(point.x) * scale, y: CGFloat(point.y) * scale))
        }
        context.stroke(
            path, with: .color(paintColor),
            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
    }

    private func toolButton(symbol: String, tool target: WhiteboardTool, id: String) -> some View {
        Button {
            self.tool = target
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 13))
                .foregroundColor(tool == target ? T.text : T.text3)
                .frame(width: 34, height: 30)
                .background(tool == target ? T.bgCard : Color.clear)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(tool == target ? T.accent : T.border, lineWidth: 1))
        }
        .accessibilityIdentifier(id)
    }

    private func actionButton(
        symbol: String, enabled: Bool, id: String,
        action: @escaping (WhiteboardDocument) -> WhiteboardDocument
    ) -> some View {
        Button {
            apply(action)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(enabled ? T.text : T.text3)
                .padding(.horizontal, T.sp2)
                .padding(.vertical, 6)
        }
        .disabled(!enabled)
        .accessibilityIdentifier(id)
    }
}

// MARK: PNG 导出（web exportWhiteboardDataUrl 同构：1280×800 白底 + 全量笔画重绘）

func exportPNG(_ board: WhiteboardDocument) -> Data {
    let width = board.width
    let height = board.height
    guard let context = CGContext(
        data: nil, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: width * 4,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return Data() }
    // CoreGraphics 坐标系 Y 向上；翻转使文档坐标（Y 向下）一致
    context.translateBy(x: 0, y: CGFloat(height))
    context.scaleBy(x: 1, y: -1)
    context.setFillColor(CGColor.whiteboardHex(WhiteboardDocument.backgroundColor) ?? CGColor(gray: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    for stroke in board.strokes {
        guard let first = stroke.points.first else { continue }
        let cgColor = CGColor.whiteboardHex(stroke.color)
            ?? CGColor.whiteboardHex(
                stroke.tool == .eraser ? WhiteboardDocument.backgroundColor : WhiteboardDocument.defaultColor)!
            ?? CGColor(gray: 1, alpha: 1)
        let paintColor = stroke.tool == .eraser ? (CGColor.whiteboardHex(WhiteboardDocument.backgroundColor) ?? cgColor) : cgColor
        context.setStrokeColor(paintColor)
        context.setFillColor(paintColor)
        context.setLineWidth(CGFloat(stroke.width))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        let points = stroke.points.map { CGPoint(x: $0.x, y: $0.y) }
        if points.count == 1 {
            let r = CGFloat(stroke.width) / 2
            context.fillEllipse(in: CGRect(x: first.x - r, y: first.y - r, width: r * 2, height: r * 2))
            continue
        }
        let path = CGMutablePath()
        path.move(to: CGPoint(x: first.x, y: first.y))
        for point in points.dropFirst() { path.addLine(to: point) }
        context.addPath(path)
        context.strokePath()
    }
    guard let image = context.makeImage() else { return Data() }
    // PNG 编码（UIImage.pngData 桥；主线程外大图也安全）
    return UIImage(cgImage: image).pngData() ?? Data()
}

// MARK: 颜色桥（#RRGGBB → SwiftUI/CG；白板词表内的固定色板用）

extension CGColor {
    /// CF 类型不支持 extension convenience init——静态工厂承载（#RRGGBB）
    static func whiteboardHex(_ hex: String) -> CGColor? {
        let cleaned = hex.replacingOccurrences(of: "#", with: "")
        guard cleaned.count == 6 else { return nil }
        var value: UInt64 = 0
        Scanner(string: cleaned).scanHexInt64(&value)
        let redComponent = CGFloat((value >> 16) & 0xFF) / 255.0
        let greenComponent = CGFloat((value >> 8) & 0xFF) / 255.0
        let blueComponent = CGFloat(value & 0xFF) / 255.0
        return CGColor(
            red: redComponent,
            green: greenComponent,
            blue: blueComponent,
            alpha: 1)
    }
}

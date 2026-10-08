import AVFoundation
import SwiftUI

// MARK: - L1-S / L1-S-D 扫码取景器（全屏相机模态 · 三态：取景 / 识别失败内联 / 权限拒绝）

struct ScanView: View {
    /// L3「重新扫码更新令牌」模式：识别成功后仅更新令牌不新建服务器
    var updateTokenMode = false
    /// 显式关闭回调（presenting 侧置 isPresented=false）。内嵌 fullScreenCover 中
    /// `@Environment(\.dismiss)` 不生效（嵌套 cover 下环境 dismiss 不绑定内层
    /// presentation——扫码页 ✕ 点了没反应，test16 2026-10-08 实证）；未传时回退
    /// 环境 dismiss（兼容 RootView 等旧入口）
    var onClose: (() -> Void)? = nil

    @Environment(AppSession.self) private var session
    @Environment(AppSettingsModel.self) private var settings
    @Environment(\.dismiss) private var dismiss
    @State private var permissionDenied = false
    @State private var inlineError: String?
    @State private var submitting = false

    var body: some View {
        ZStack {
            T.bgTerm.ignoresSafeArea()

            if permissionDenied {
                deniedCard
            } else {
                CameraScannerView(onRecognized: handleRecognized)
                    .ignoresSafeArea()
                    // UIViewRepresentable 偶发压住 SwiftUI 兄弟视图的 hit-test（✕ 点击
                    // 无反应而 a11y 帧/hittable/app idle 均正常——2026-10-08 test16 帧级
                    // 诊断实证）。预览层零触摸需求（识别走 AVCaptureMetadataOutput 回调），
                    // 显式关断触摸测试
                    .allowsHitTesting(false)
                VStack {
                    frameOverlay
                    Spacer()
                    if let error = inlineError {
                        inlineErrorRow(error)
                    }
                    // 手动输入逃生口与「桌面端未出码」指引均为开发向信息：仅开发者模式呈现
                    // （关闭后取景器保留 header ✕ 关闭，主路径不死路）
                    if settings.effectiveDeveloperMode {
                        bottomEscape
                    }
                }
            }
        }
        .task { await checkPermission() }
    }

    private func closeTapped() {
        if let onClose {
            onClose()
        } else {
            dismiss()
        }
    }

    private var header: some View {
        HStack(spacing: T.sp2) {
            Button {
                closeTapped()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(T.text)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("l1-s-act-close")
            Spacer()
            Text("扫描连接二维码")
                .font(T.font(16, .bold))
                .foregroundColor(T.text)
            Spacer()
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, T.sp3)
    }

    private var frameOverlay: some View {
        VStack(spacing: 16) {
            header
            Spacer()
            ScanFrame()
            VStack(spacing: 5) {
                Text("对准桌面端出示的连接二维码")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.text)
                Text("识别 http(s)://<host>:<port>/?token=…")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
            }
            Spacer()
            Spacer()
        }
    }

    private func inlineErrorRow(_ message: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 11))
                .foregroundColor(T.red)
            Text(message)
                .font(T.font(11))
                .foregroundColor(T.red)
        }
        .padding(.bottom, T.sp2)
        .accessibilityIdentifier("l1-s-err-inline")
    }

    private var bottomEscape: some View {
        VStack(spacing: 10) {
            Button {
                closeTapped()
            } label: {
                Label("改用手动输入连接", systemImage: "server.rack")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.text)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(T.bgCard.opacity(0.9))
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .accessibilityIdentifier("l1-s-btn-manual") // 跨帧共用选择器（L1-S 与 L1-S-D）
            Text("桌面端未出码？先在终端运行 zcode --web 获取链接")
                .font(T.font(10.5))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp6)
        .padding(.bottom, 14)
    }

    // MARK: 相机权限拒绝态（L1-S-D：主 CTA 不死路）

    private var deniedCard: some View {
        VStack(spacing: 0) {
            header
            Spacer()
            VStack(spacing: 12) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 22))
                    .foregroundColor(T.orange)
                    .frame(width: 56, height: 56)
                    .background(T.orangeDim)
                    .clipShape(Circle())
                VStack(spacing: 5) {
                    Text("相机权限已关闭")
                        .font(T.font(15, .bold))
                        .foregroundColor(T.text)
                    Text("扫码需要在 系统设置 → BiuZ 中开启相机；\n也可以不扫码，直接输入桌面端地址连接")
                        .font(T.font(11.5))
                        .foregroundColor(T.text3)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                }
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Label("去系统设置开启", systemImage: "shield.lefthalf.filled")
                        .font(T.font(13.5, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .accessibilityIdentifier("l1-s-btn-settings")
                if settings.effectiveDeveloperMode {
                    Button {
                        closeTapped()
                    } label: {
                        Label("改用手动输入连接", systemImage: "server.rack")
                            .font(T.font(13.5, .semibold))
                            .foregroundColor(T.text)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                    }
                    .accessibilityIdentifier("l1-s-btn-manual-denied")
                }
            }
            .card(padding: 20)
            .padding(.horizontal, T.sp6)
            Spacer()
        }
    }

    // MARK: 识别处理

    private func checkPermission() async {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .notDetermined:
            permissionDenied = await AVCaptureDevice.requestAccess(for: .video) == false
        case .denied, .restricted:
            permissionDenied = true
        default:
            permissionDenied = false
        }
    }

    private func handleRecognized(_ text: String) {
        guard !submitting else { return }
        guard let parsed = ConnectURLParser.extractLink(from: text) else {
            inlineError = "未识别到有效连接链接，请对准二维码后重试"
            return
        }
        submitting = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        Task {
            switch parsed {
            case .relay:
                // 桌面端「远程控制」二维码即中继配对链接（sid+hash 自带凭据）——与
                // 剪贴板/手动路径同款 connectRelayLink（用户 2026-10-06「扫码连接
                // 根本没通」：扫码分支此前只认局域网直连链接，relay 二维码被当
                // 无效码处理，永远不发起中继连接）
                closeTapped()
                await session.connectRelayLink(text)
            case .direct(let parsed):
                if updateTokenMode, let existing = session.savedServer {
                    // L3 401 恢复：只更新令牌重连
                    closeTapped()
                    await session.updateToken(for: existing, token: parsed.token ?? "")
                } else {
                    var server = ServerConfig(
                        id: UUID().uuidString, name: nil, host: parsed.host, port: parsed.port,
                        useTLS: parsed.useTLS, token: parsed.token ?? "", lastConnectedAt: nil,
                        preferredWorkspacePath: nil, relay: nil)
                    if let existing = session.savedServer,
                       existing.host == parsed.host, existing.port == parsed.port {
                        server.id = existing.id
                        server.name = existing.name
                        server.preferredWorkspacePath = existing.preferredWorkspacePath
                    }
                    ServerRegistry.upsert(server)
                    closeTapped()
                    await session.connect(server: server)
                }
            }
        }
    }
}

/// 取景框（四角括号 + 扫描线）
private struct ScanFrame: View {
    @State private var scanning = false
    private let side: CGFloat = 216
    private let length: CGFloat = 30

    var body: some View {
        ZStack {
            corner(hAlign: .leading, vAlign: .top)
            corner(hAlign: .trailing, vAlign: .top)
            corner(hAlign: .leading, vAlign: .bottom)
            corner(hAlign: .trailing, vAlign: .bottom)
            Rectangle()
                .fill(LinearGradient(colors: [.clear, T.accent, .clear], startPoint: .leading, endPoint: .trailing))
                .frame(height: 2)
                .offset(y: scanning ? side / 2 : -side / 2)
                .opacity(0.7)
                .animation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true), value: scanning)
        }
        .frame(width: side, height: side)
        .onAppear { scanning = true }
    }

    private func corner(hAlign: HorizontalAlignment, vAlign: VerticalAlignment) -> some View {
        ZStack(alignment: Alignment(horizontal: hAlign, vertical: vAlign)) {
            Rectangle()
                .fill(T.accent)
                .frame(width: length, height: 3)
            Rectangle()
                .fill(T.accent)
                .frame(width: 3, height: length)
        }
        .frame(width: side, height: side)
    }
}

// MARK: - 相机会话（AVCaptureMetadataOutput · QR）

struct CameraScannerView: UIViewRepresentable {
    var onRecognized: (String) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        context.coordinator.attach(to: view, onRecognized: onRecognized)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onRecognized = onRecognized
    }

    func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.stop()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var onRecognized: ((String) -> Void)?
        private let session = AVCaptureSession()
        /// 会话专用串行队列：startRunning/stopRunning 均为阻塞调用（Apple 文档明示），
        /// **严禁主线程**——模拟器相机服务卡死时 startRunning 无限挂起主队列（
        /// _buildAndRunGraph → _waitInMode，2026-10-08 test16 sample 实证 1867/1917
        /// 采样困于该栈），表象 = 整个 UI 触摸/更新停摆而动画与 AX 照常，极难排查
        private let sessionQueue = DispatchQueue(label: "cn.biuz.scan.sessionQueue")
        private var previewLayer: AVCaptureVideoPreviewLayer?
        private var configured = false
        private var lastEmitted = TimeInterval(0)

        func attach(to container: UIView, onRecognized: @escaping (String) -> Void) {
            self.onRecognized = onRecognized
            let previewLayer = AVCaptureVideoPreviewLayer(session: session)
            previewLayer.videoGravity = .resizeAspectFill
            previewLayer.frame = container.bounds
            container.layer.addSublayer(previewLayer)
            self.previewLayer = previewLayer

            DispatchQueue.main.async { [weak self] in
                self?.previewLayer?.frame = container.bounds
            }
            sessionQueue.async { [weak self] in
                self?.configureAndStart()
            }
        }

        private func configureAndStart() {
            guard !configured else { return }
            configured = true
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device) else { return }
            session.beginConfiguration()
            if session.canAddInput(input) { session.addInput(input) }
            let output = AVCaptureMetadataOutput()
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            session.commitConfiguration()
            if session.isRunning == false {
                session.startRunning()
            }
        }

        func stop() {
            sessionQueue.async { [weak self] in
                if let self, self.session.isRunning {
                    self.session.stopRunning()
                }
            }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard let object = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first,
                  object.type == .qr,
                  let value = object.stringValue else { return }
            let now = Date().timeIntervalSince1970
            guard now - lastEmitted > 1.5 else { return } // 节流
            lastEmitted = now
            onRecognized?(value)
        }
    }
}

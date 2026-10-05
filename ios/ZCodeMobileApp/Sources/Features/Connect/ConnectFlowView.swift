import SwiftUI

// MARK: - 连接流程容器（L1 → L2 → L3；扫码为全屏模态；帮助为内嵌 Push）

struct ConnectFlowView: View {
    var initialEditTokenOnly = false

    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var path: [ConnectRoute] = []
    @State private var showScanner = false
    @State private var scannerUpdateToken = false // 401 重扫模式

    enum ConnectRoute: Hashable {
        case manual(tokenFocusOnly: Bool)
        case help
    }

    var body: some View {
        NavigationStack(path: $path) {
            ConnectHomeView(
                onScan: { scannerUpdateToken = false; showScanner = true },
                onManual: { path.append(.manual(tokenFocusOnly: false)) },
                onHelp: { path.append(.help) },
                onFilled: { /* 剪贴板一键填充已在 ConnectHomeView 内直接发起连接 */ })
            .navigationDestination(for: ConnectRoute.self) { route in
                switch route {
                case .manual(let tokenFocusOnly):
                    ManualConnectView(tokenFocusOnly: tokenFocusOnly)
                case .help:
                    ConnectHelpView()
                }
            }
            // G-009：L1 连接主页 cover 此前无任何关闭控件（从设置/登录成功页进入后
            // 不实际发起连接即无法退出）。补 ✕（44px，同 LoginFlowView o1-act-close 模式）：
            // 连接进行中先取消连接再收起 cover；presentedFlow 置 nil。
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        session.cancelConnecting()
                        session.dismissFlow()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(T.text)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityIdentifier("l1-act-close")
                }
            }
            .animation(.easeOut(duration: 0.22), value: session.mode)
        }
        .overlay {
            // L2 连接中 / L3 失败 覆盖整层（挂 NavigationStack：栈内任意页提交连接均可见）
            switch session.mode {
            case .connecting:
                ConnectingView(onCancel: {
                    session.cancelConnecting()
                })
                .transition(.opacity)
            case .connectFailed(let server, let error):
                ConnectFailureView(server: server, error: error,
                                   onRescan: {
                                       scannerUpdateToken = true
                                       showScanner = true
                                   },
                                   onManualToken: {
                                       // 退出失败覆盖层并把栈重置为单个手动更新页：
                                       // 不用 append（栈内会残留两个手动页实例，遮挡下层且选择器歧义）
                                       session.cancelConnecting()
                                       path = [.manual(tokenFocusOnly: true)]
                                   },
                                   onRetry: { Task { await session.reconnect() } },
                                   onDone: { session.cancelConnecting() })
                    .transition(.opacity)
            default:
                EmptyView()
            }
        }
        .animation(.easeOut(duration: 0.22), value: session.mode)
        .fullScreenCover(isPresented: $showScanner) {
            ScanView(updateTokenMode: scannerUpdateToken)
        }
        .task {
            // 上一次连接失败的残留态不得遮挡本次配置编辑（L4-B「更新令牌」入口）
            if case .connectFailed = session.mode {
                session.cancelConnecting()
            }
            if initialEditTokenOnly, path.isEmpty {
                path = [.manual(tokenFocusOnly: true)]
            }
        }
    }
}

// MARK: - L1 默认连接页（回连 / 扫码 / 剪贴板 / 手动）

struct ConnectHomeView: View {
    @Environment(AppSession.self) private var session
    var onScan: () -> Void
    var onManual: () -> Void
    var onHelp: () -> Void
    var onFilled: () -> Void

    @State private var clipboardLink: ConnectURLParser.ParsedLink?
    @State private var probeReachable: Bool?

    var body: some View {
        ScrollView {
            VStack(spacing: T.sp3) {
                if let link = clipboardLink { clipboardBanner(link) }
                brand
                if let server = session.savedServer { recentCard(server) }
                PrimaryButton(title: "扫码连接桌面端", identifier: "l1-btn-scan", action: onScan)
                Button(action: onManual) {
                    Label("手动输入地址连接", systemImage: "server.rack")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("l1-btn-manual")
                helpRow
                Spacer(minLength: 24)
                footer
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp1)
        }
        .scrollIndicators(.hidden)
        .task {
            // 剪贴板横幅：检测到连接链接形态即出现（中继配对链接 / 局域网链接；
            // iOS 16+ 受「允许粘贴」约束，拒绝则横幅不出现）
            if let text = UIPasteboard.general.string ?? UIPasteboard.general.string,
               let parsed = ConnectURLParser.extractLink(from: text) {
                clipboardLink = parsed
            }
            // 中性探测点：进入页面后台探测 server-info（1.5s 超时；中继服务器跳过）
            if let result = await session.probeSavedServer() {
                probeReachable = (result.error == nil)
            }
        }
    }

    private var brand: some View {
        VStack(spacing: 12) {
            ZCodeBrandMark(size: 76)
            VStack(spacing: 3) {
                Text("BiuZ")
                    .font(T.font(22, .heavy))
                    .foregroundColor(T.text)
                Text("连接桌面端 Agent 服务（ZCode 社区版），开始遥控与审批")
                    .font(T.font(12.5))
                    .foregroundColor(T.text2)
            }
        }
        .padding(.vertical, 16)
    }

    private func clipboardBanner(_ link: ConnectURLParser.ParsedLink) -> some View {
        HStack(spacing: 10) {
            Image(systemName: link.isRelay ? "icloud" : "link")
                .font(.system(size: 13))
                .foregroundColor(T.accentText)
            VStack(alignment: .leading, spacing: 1) {
                Text(link.isRelay ? "检测到剪贴板中的云中继配对链接" : "检测到剪贴板中的连接链接")
                    .font(T.font(12.5, .bold))
                    .foregroundColor(T.text)
                Text(link.isRelay ? link.relaySummary : "\(link.directHost):\(link.directPort) · token 已就绪")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                clipboardLink = nil
                Task { await connectParsed(link) }
            } label: {
                Text("一键填充")
                    .font(T.font(13, .semibold))
                    .foregroundColor(T.onAccent)
                    .padding(.horizontal, T.sp4)
                    .frame(minHeight: 44)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .accessibilityIdentifier("l1-act-paste-fill")
        }
        .padding(.leading, T.sp3)
        .padding(.trailing, T.sp2)
        .padding(.vertical, T.sp2)
        .background(T.accentDim)
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.accent.opacity(0.35), lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .accessibilityIdentifier("l1-banner-clipboard")
    }

    private func recentCard(_ server: ServerConfig) -> some View {
        HStack(spacing: T.sp3) {
            Image(systemName: server.relay != nil ? "icloud.fill" : "laptopcomputer.and.iphone")
                .font(.system(size: 17))
                .foregroundColor(T.text2)
                .frame(width: 44, height: 44)
                .background(T.bgInput)
                .clipShape(RoundedRectangle(cornerRadius: 13))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 0) {
                    Text(server.displayName)
                        .font(T.font(14.5, .bold))
                        .foregroundColor(T.text)
                    if server.relay != nil {
                        Text("云中继")
                            .font(T.font(9, .bold))
                            .foregroundColor(T.accentText)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1.5)
                            .background(T.accentDim)
                            .clipShape(Capsule())
                            .padding(.leading, 6)
                    } else {
                        ProbeDot(reachable: probeReachable)
                    }
                }
                Text((server.relay?.machineName.map { "\($0) · " } ?? "") + server.displayAddress)
                    .font(T.mono(11))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
                Text(server.lastConnectedAt.map {
                    "上次连接 · " + Self.relativeFormatter.localizedString(for: $0, relativeTo: Date())
                } ?? "尚未连接过")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
            Spacer()
            Button {
                Task { await connect(config: server) }
            } label: {
                Text("连接")
                    .font(T.font(13.5, .semibold))
                    .foregroundColor(T.onAccent)
                    .padding(.horizontal, 20)
                    .frame(minHeight: 44)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
            }
            .accessibilityIdentifier("l1-act-reconnect")
        }
        .card(padding: 14)
        .accessibilityIdentifier("l1-card-recent")
    }

    private var helpRow: some View {
        Button(action: onHelp) {
            HStack(spacing: T.sp3) {
                Image(systemName: "terminal")
                    .font(.system(size: 13))
                    .foregroundColor(T.text3)
                VStack(alignment: .leading, spacing: 2) {
                    Text("如何在桌面端开启？")
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.text)
                    Text("$ zcode --web --host 0.0.0.0")
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundColor(T.text3)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .background(T.bgElevated)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .accessibilityIdentifier("l1-row-help")
    }

    private var footer: some View {
        Text("访问令牌即服务器密码，仅存本机 Keychain\n服务无加密（ws://），请仅在可信局域网使用 · wss:// 需自备反向代理")
            .font(T.font(11))
            .foregroundColor(T.text3)
            .multilineTextAlignment(.center)
            .lineSpacing(4)
            .padding(.bottom, 12)
    }

    private func connectParsed(_ link: ConnectURLParser.ParsedLink) async {
        switch link {
        case .relay(let relay):
            // 云中继：解析产物直连（机器名进注册表名，passHash 随配置仅存 Keychain）
            if let existing = session.savedServer, existing.relay != nil {
                await connect(config: ServerConfig(
                    id: existing.id, name: existing.name,
                    host: relay.endpointHost ?? "zcode.z.ai", port: 443, useTLS: true,
                    token: "", lastConnectedAt: existing.lastConnectedAt,
                    preferredWorkspacePath: existing.preferredWorkspacePath, relay: relay))
            } else {
                await connect(config: ServerConfig(
                    id: UUID().uuidString, name: nil,
                    host: relay.endpointHost ?? "zcode.z.ai", port: 443, useTLS: true,
                    token: "", lastConnectedAt: nil, preferredWorkspacePath: nil, relay: relay))
            }
        case .direct(let parsed):
            var server = savedOrNew(matching: parsed)
            server.host = parsed.host
            server.port = parsed.port
            server.useTLS = parsed.useTLS
            server.relay = nil
            if let token = parsed.token { server.token = token }
            await connect(config: server)
        }
    }

    private func connect(config: ServerConfig) async {
        ServerRegistry.upsert(config)
        await session.connect(server: config)
    }

    private func savedOrNew(matching parsed: ConnectURLParser.Parsed) -> ServerConfig {
        if let existing = session.savedServer,
           existing.host == parsed.host, existing.port == parsed.port {
            return existing
        }
        return ServerConfig(
            id: UUID().uuidString, name: nil, host: parsed.host, port: parsed.port,
            useTLS: parsed.useTLS, token: parsed.token ?? "", lastConnectedAt: nil,
            preferredWorkspacePath: nil, relay: nil)
    }

    static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()
}

/// 中性探测点（5.1 解耦规则：在线≠任务状态；未探测=半透明灰）
struct ProbeDot: View {
    let reachable: Bool?

    var body: some View {
        Circle()
            .fill(dotColor)
            .frame(width: 6, height: 6)
            .padding(.leading, 5)
    }

    private var dotColor: Color {
        switch reachable {
        case .some(true): return T.accent
        case .some(false): return T.text3
        case nil: return T.text3.opacity(0.6)
        }
    }
}

// MARK: - L1-H 连接帮助 · 内嵌说明页

struct ConnectHelpView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    private let command = "zcode --web --host 0.0.0.0 [--port 3030]"

    var body: some View {
        ScrollView {
            VStack(spacing: T.sp3) {
                terminalCard
                VStack(spacing: 0) {
                    helpStep(1, title: "桌面机启动局域网服务",
                             detail: "zcode --web --host 0.0.0.0 · Core 版仅 loopback，须走 --web 分发")
                    helpStep(2, title: "终端打印带令牌的连接链接",
                             detail: "http://<LAN-IP>:3030/?token=… · 令牌随进程存活")
                    helpStep(3, title: "手机扫码 / 复制粘贴 / 手动输入",
                             detail: "识别成功自动进入连接流程")
                }
                .background(T.bgCard)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))

                noteCard(icon: "qrcode", tint: T.violet,
                         attributed: "桌面端出示二维码为后续版本能力（仓库暂无二维码渲染）；当前请复制终端打印的链接，回本页用「手动输入」粘贴或依赖剪贴板横幅。")
                noteCard(icon: "shield.lefthalf.filled", tint: T.orange,
                         attributed: "首次连接系统将询问本地网络权限：「用于查找并连接同一局域网内的桌面端 Agent 服务（ZCode 社区版桌面端）」——拒绝后请求将静默失败，可随时在 设置 → 隐私与安全性 → 本地网络 中开启。")

                Spacer(minLength: 16)
                PrimaryButton(title: "知道了", identifier: "l1-h-btn-done") { dismiss() }
                    .padding(.bottom, 12)
            }
            .padding(.horizontal, T.sp4)
        }
        .scrollIndicators(.hidden)
        .navigationTitle("连接帮助")
        .navigationBarTitleDisplayMode(.inline)
        .background(T.bg)
    }

    private var terminalCard: some View {
        VStack(spacing: 0) {
            HStack(spacing: T.sp2) {
                Image(systemName: "terminal")
                    .font(.system(size: 12))
                    .foregroundColor(T.text3)
                Text("桌面端终端")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                Spacer()
                Button {
                    UIPasteboard.general.string = command
                    copied = true
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 12))
                        .foregroundColor(copied ? T.accentText : T.text3)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("l1-h-act-copy")
            }
            .padding(.horizontal, T.sp3)
            .overlay(alignment: .bottom) { Rectangle().fill(T.border).frame(height: 1) }

            VStack(alignment: .leading, spacing: 3) {
                (Text("$ ").foregroundColor(T.accent) + Text(command).foregroundColor(T.text2))
                    .font(T.mono(12))
                Text("[ok] LAN → http://192.168.1.24:3030/?token=****")
                    .font(T.mono(12))
                    .foregroundColor(T.codeLab)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(T.sp3)
        }
        .background(T.bgTerm)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(T.border, lineWidth: 1))
    }

    private func helpStep(_ index: Int, title: String, detail: String) -> some View {
        HStack(spacing: T.sp2) {
            Text("\(index)")
                .font(T.mono(11, .bold))
                .foregroundColor(T.accentText)
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(T.accentDim)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(T.font(13, .semibold)).foregroundColor(T.text)
                Text(detail)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
    }

    private func noteCard(icon: String, tint: Color, attributed: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(tint)
            Text(attributed)
                .font(T.font(11))
                .foregroundColor(T.text2)
                .lineSpacing(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(padding: 12)
    }
}

// MARK: - L1-K 手动连接（完整链接拆解 / 令牌可选 / 校验态）

struct ManualConnectView: View {
    var tokenFocusOnly = false

    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @FocusState private var hostFocused: Bool
    @FocusState private var tokenFocused: Bool
    @State private var hostText = ""
    @State private var tokenText = ""
    @State private var showToken = false
    @State private var hostError: String?
    @State private var parseNotice: String?
    @State private var connecting = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                hostField
                tokenField
                connectButton
                if let notice = parseNotice { parseNoticeRow(notice) }
                Spacer(minLength: 24)
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp1)
        }
        .scrollIndicators(.hidden)
        .navigationTitle("手动连接")
        .navigationBarTitleDisplayMode(.inline)
        .background(T.bg)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button {
                    Task { await submit() }
                } label: {
                    Text("连接")
                        .font(T.font(14, .bold))
                        .foregroundColor(T.accentText)
                }
                .accessibilityIdentifier("l1-keyboard-connect")
            }
        }
        .onAppear {
            if let server = session.savedServer {
                hostText = server.displayAddress
                tokenText = server.token
            }
            if tokenFocusOnly {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    tokenFocused = true
                }
            }
        }
    }

    private var hostField: some View {
        VStack(alignment: .leading, spacing: 0) {
            fieldLabel("服务器地址", pill: "URL 键盘", pillId: "l1-field-kbd-host")
            HStack {
                TextField("http://192.168.1.24:3030", text: $hostText)
                    .font(T.mono(16))
                    .foregroundColor(T.text)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .focused($hostFocused)
                    .accessibilityIdentifier("l1-field-host")
                    .onChange(of: hostText) { _, _ in
                        hostError = nil
                        parseNotice = nil
                    }
            }
            .padding(.horizontal, T.sp3)
            .frame(height: 48)
            .background(T.bgInput)
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(hostError == nil ? T.borderStrong : T.redLine, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            if let error = hostError {
                fieldError(error, id: "l1-field-host-err")
            }
            captionRow(icon: "terminal", tint: T.text3,
                       text: "可粘贴完整链接（http/https/ws/wss）或云中继配对链接（https://…/remote/v4?…）；端口默认 3030，仅输 host:port 时补 http://")
        }
    }

    private var tokenField: some View {
        VStack(alignment: .leading, spacing: 0) {
            fieldLabel("访问令牌", pill: "安全输入 · 可选", pillId: "l1-field-kbd-token")
            HStack {
                if showToken {
                    TextField("留空即可（--no-token 部署）", text: $tokenText)
                        .font(T.mono(16))
                        .foregroundColor(T.text)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .focused($tokenFocused)
                        .accessibilityIdentifier("l1-field-token")
                } else {
                    SecureField("留空即可（--no-token 部署）", text: $tokenText)
                        .font(T.mono(16))
                        .foregroundColor(T.text)
                        .focused($tokenFocused)
                        .accessibilityIdentifier("l1-field-token")
                }
                if !tokenText.isEmpty {
                    Text("…\(tokenText.suffix(4))")
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                }
                Button {
                    showToken.toggle()
                } label: {
                    Image(systemName: showToken ? "eye.slash" : "eye")
                        .font(.system(size: 13))
                        .foregroundColor(T.text3)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("l1-act-eye")
            }
            .padding(.horizontal, T.sp3)
            .frame(height: 48)
            .background(T.bgInput)
            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
            captionRow(icon: "shield.lefthalf.filled", tint: T.accentText,
                       text: "即 URL 中 token= 段；桌面端 --no-token 启动时留空即可（authRequired=false）。仅存 Keychain，掩码展示（末 4 位）")
        }
    }

    private var connectButton: some View {
        Button {
            Task { await submit() }
        } label: {
            HStack {
                if connecting {
                    SpinnerView(color: T.onAccent, size: 16)
                } else {
                    Image(systemName: "link")
                }
                Text("连接")
            }
            .font(T.font(15, .semibold))
            .foregroundColor(T.onAccent)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(T.accent)
            .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .disabled(connecting)
        .accessibilityIdentifier("l1-submit-connect")
    }

    private func parseNoticeRow(_ notice: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(T.accentText)
            Text(notice)
                .font(T.font(11))
                .foregroundColor(T.accentText)
        }
        .accessibilityIdentifier("l1-parse-ok")
    }

    // MARK: 表单语义

    private func fieldLabel(_ title: String, pill: String, pillId: String) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(T.font(12, .bold))
                .foregroundColor(T.text2)
            Text(pill)
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .padding(.horizontal, 7)
                .padding(.vertical, 1)
                .background(T.bgInput)
                .clipShape(Capsule())
                .accessibilityIdentifier(pillId)
        }
        .padding(.bottom, 6)
        .padding(.top, 6)
    }

    private func fieldError(_ message: String, id: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 11))
                .foregroundColor(T.red)
            Text(message)
                .font(T.font(11))
                .foregroundColor(T.red)
        }
        .padding(.top, 6)
        .accessibilityIdentifier(id)
    }

    private func captionRow(icon: String, tint: Color, text: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 11))
                .foregroundColor(tint)
            Text(text)
                .font(T.font(10.5))
                .foregroundColor(T.text3)
                .lineSpacing(2)
        }
        .padding(.top, 6)
    }

    // MARK: 提交

    private func submit() async {
        guard !connecting else { return }
        // 收起键盘：连接中/失败态（L2/L3）替换整个视图层级，TextField 若仍持有
        // first responder，键盘会残留在 L2/L3 上遮挡底部内容（走查 R1 实证）
        hostFocused = false
        tokenFocused = false
        hostError = nil
        parseNotice = nil

        // 云中继配对链接（https://…/remote/v4?sid=…&hash=…&mid=…）：直连 wss 端点，
        // 令牌栏不参与（hash 即凭据，随配置仅存 Keychain）
        if let relay = ConnectURLParser.parseRelayLink(hostText) {
            // 识别即提示（解析为同步操作，提示不依赖连接结果的返回时序——
            // 中继失败最长可达 auth 15s 超时，提示不能等到那时才出现）
            parseNotice = "已识别云中继配对链接 · \(relay.machineName ?? relay.endpointHost ?? "桌面端")"
            connecting = true
            await session.connectRelayLink(hostText)
            connecting = false
            return
        }

        let parsed: ConnectURLParser.Parsed
        switch ConnectURLParser.parse(hostText) {
        case .success(let result):
            parsed = result
        case .failure(let error):
            hostError = error.message
            return
        }

        // 地址栏粘贴完整链接：?token= 段自动剥离入令牌栏（f-ok 提示）
        if let urlToken = parsed.token, !urlToken.isEmpty, tokenText.isEmpty {
            tokenText = urlToken
            parseNotice = "已从粘贴的链接自动拆解：?token= 段已填入令牌栏"
        }
        let token = tokenText.isEmpty ? (parsed.token ?? "") : tokenText

        // 复用已存服务器（更新令牌场景）
        let server: ServerConfig
        if let existing = session.savedServer,
           existing.host == parsed.host, existing.port == parsed.port {
            server = ServerConfig(
                id: existing.id, name: existing.name, host: parsed.host, port: parsed.port,
                useTLS: parsed.useTLS, token: token,
                lastConnectedAt: existing.lastConnectedAt,
                preferredWorkspacePath: existing.preferredWorkspacePath,
                relay: existing.relay)
        } else {
            server = ServerConfig(
                id: UUID().uuidString, name: nil, host: parsed.host, port: parsed.port,
                useTLS: parsed.useTLS, token: token, lastConnectedAt: nil,
                preferredWorkspacePath: nil, relay: nil)
        }
        connecting = true
        ServerRegistry.upsert(server)
        await session.connect(server: server)
        connecting = false
    }
}

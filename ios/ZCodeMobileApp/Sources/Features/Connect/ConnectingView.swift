import SwiftUI

// MARK: - L2 / L2-N 连接中（五步进度 + connect.log）

struct ConnectingView: View {
    @Environment(AppSession.self) private var session
    var onCancel: () -> Void

    var body: some View {
        ZStack {
            T.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(spacing: 14) {
                        targetHeader
                        stepsCard
                        logTerminal
                    }
                    .padding(.horizontal, T.sp4)
                    .padding(.top, T.sp2)
                }
                .scrollIndicators(.hidden)
                Button(action: onCancel) {
                    Label(String(localized: "取消连接"), systemImage: "xmark")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("l2-act-cancel")
                .padding(.horizontal, T.sp4)
                .padding(.bottom, 12)
            }
        }
    }

    private var header: some View {
        HStack {
            Text(String(localized: "连接中"))
                .font(T.font(16.5, .bold))
                .foregroundColor(T.text)
            Spacer()
            Button(action: onCancel) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(T.text2)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("l2-act-close")
        }
        .padding(.horizontal, T.sp3)
    }

    private var targetHeader: some View {
        HStack(spacing: 13) {
            Image(systemName: "laptopcomputer.and.iphone")
                .font(.system(size: 20))
                .foregroundColor(T.accent)
                .frame(width: 52, height: 52)
                .background(T.accentDim)
                .clipShape(RoundedRectangle(cornerRadius: 16))
            VStack(alignment: .leading, spacing: 3) {
                // 头部时序：首次连接以 host:port 占位，server-info 返回后升级为名称
                Text(String(localized: "正在连接 \(displayHost)"))
                    .font(T.font(15, .heavy))
                    .foregroundColor(T.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Text(subtitle)
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            }
            Spacer()
            StatusPill(text: String(localized: "连接中"), kind: .run)
        }
        .padding(.vertical, 4)
    }

    private var displayHost: String {
        if let server = session.savedServer {
            return server.displayName
        }
        return ""
    }

    private var subtitle: String {
        if session.savedServer?.relay != nil {
            return session.connection.serverInfo.map { _ in
                String(localized: "云端中继 · auth 握手完成 · 桥与订阅建立中")
            } ?? String(localized: "云中继直连 wss · auth 握手与桥建立中")
        }
        return session.connection.serverInfo.map { _ in String(localized: "server-info 已返回 · 正在建立会话") }
            ?? String(localized: "首次连接 · 名称待 server-info 返回后显示")
    }

    private var progress: ConnectProgress {
        session.connectProgress ?? ConnectProgress()
    }

    private var authRequiredFree: Bool {
        session.connection.serverInfo.map { !$0.authRequired } ?? false
    }

    private var stepsCard: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Circle().fill(T.blue).frame(width: 7, height: 7)
                    Text(String(localized: "连接进度 · \(progress.completedCount + progress.runningCount)/5"))
                        .font(T.font(13, .bold))
                        .foregroundColor(T.text2)
                }
                Spacer()
                ThinProgressBar(progress: progress.fraction, height: 4, tint: T.blue)
                    .frame(width: 120)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            stepRow(id: "l2-step-discover", name: String(localized: "发现服务"),
                    detail: "GET /api/server-info", state: progress.discover)
            stepRow(id: authRequiredFree ? "l2-n-step-auth" : "l2-step-auth",
                    name: authRequiredFree ? String(localized: "免鉴权") : String(localized: "校验访问令牌"),
                    detail: authRequiredFree
                        ? String(localized: "--no-token · authRequired=false · 不校验令牌")
                        : "?token=*** · authRequired=true",
                    state: progress.auth)
            stepRow(id: "l2-step-ws", name: String(localized: "建立 WebSocket"),
                    detail: "\(session.savedServer?.wsBaseURL ?? "")/ws · web-remote-replayable",
                    state: progress.websocket)
            stepRow(id: "l2-step-handshake", name: String(localized: "协议握手 v4"),
                    detail: "helloConversationV4 · clientKind=mobileApp",
                    state: progress.handshake)
            stepRow(id: "l2-step-workspace", name: String(localized: "载入工作区"),
                    detail: "workspaces[0] · \(session.connection.workspace?.path ?? "…")",
                    state: progress.workspace)
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("l2-card-steps")
    }

    private func stepRow(id: String, name: String, detail: String, state: ConnectStepState) -> some View {
        HStack(spacing: 11) {
            ZStack {
                switch state.phase {
                case .done:
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(T.accentText)
                        .frame(width: 24, height: 24)
                        .background(T.accentDim)
                        .clipShape(Circle())
                case .running:
                    SpinnerView(size: 16).frame(width: 24, height: 24)
                case .failed:
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(T.red)
                        .frame(width: 24, height: 24)
                        .background(T.redDim)
                        .clipShape(Circle())
                case .pending:
                    Circle()
                        .strokeBorder(T.borderStrong, style: StrokeStyle(lineWidth: 1.5, dash: [3]))
                        .frame(width: 24, height: 24)
                }
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(T.font(13.5, state.phase == .pending ? .medium : .semibold))
                    .foregroundColor(state.phase == .pending ? T.text3 : T.text)
                Text(detail)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if !state.meta.isEmpty {
                Text(state.meta)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
            } else if state.phase == .running {
                Text("…").font(T.mono(10.5)).foregroundColor(T.text3)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
        .accessibilityIdentifier(id)
    }

    private var logTerminal: some View {
        VStack(spacing: 0) {
            HStack(spacing: T.sp2) {
                Image(systemName: "terminal")
                    .font(.system(size: 12))
                    .foregroundColor(T.text3)
                Text("connect.log")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                Spacer()
                SpinnerView(size: 12)
            }
            .padding(.horizontal, T.sp3)
            .padding(.vertical, T.sp2)
            .overlay(alignment: .bottom) { Rectangle().fill(T.border).frame(height: 1) }

            VStack(alignment: .leading, spacing: 2) {
                ForEach(session.connectLogs) { line in
                    HStack(spacing: 6) {
                        Text(label(for: line.kind))
                            .font(T.mono(12))
                            .foregroundColor(color(for: line.kind))
                        Text(line.text)
                            .font(T.mono(12))
                            .foregroundColor(T.text2)
                    }
                }
                BlinkingCursor()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(T.sp3)
        }
        .background(T.bgTerm)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("l2-log-term")
    }

    private func label(for kind: ConnectLogLine.Kind) -> String {
        switch kind {
        case .ok: return "[ok]"
        case .working: return "[..]"
        case .info: return "[i]"
        case .error: return "[!!]"
        }
    }

    private func color(for kind: ConnectLogLine.Kind) -> Color {
        switch kind {
        case .ok: return T.accent
        case .working: return T.codeLab
        case .info: return T.text3
        case .error: return T.red
        }
    }
}

// MARK: - L3 连接失败 · 错误与重试（四态对照 + 删除二次确认）

struct ConnectFailureView: View {
    let server: ServerConfig
    let error: ConnectError
    var onRescan: () -> Void
    var onManualToken: () -> Void
    var onRetry: () -> Void
    var onDone: () -> Void

    @Environment(AppSettingsModel.self) private var settings
    @State private var showDeleteConfirm = false

    var body: some View {
        ZStack {
            T.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Button(action: onDone) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(T.text2)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    Spacer()
                    Text(String(localized: "连接失败"))
                        .font(T.font(16.5, .bold))
                        .foregroundColor(T.text)
                    Spacer()
                    Color.clear.frame(width: 44, height: 44)
                }
                ScrollView {
                    VStack(spacing: T.sp3) {
                        errorCard
                        recoveryActions
                        comparisonList
                        deleteButton
                        Text(String(localized: "令牌随桌面进程存活 · 删除将同时清除 Keychain 中的令牌\n请仅在可信局域网使用 · wss:// 需自备反向代理"))
                            .font(T.font(10.5))
                            .foregroundColor(T.text3)
                            .multilineTextAlignment(.center)
                            .lineSpacing(3)
                            .padding(.bottom, 10)
                    }
                    .padding(.horizontal, T.sp4)
                    .padding(.top, T.sp1)
                }
                .scrollIndicators(.hidden)
            }
        }
        .sheet(isPresented: $showDeleteConfirm) {
            ConfirmSheet(
                title: String(localized: "删除「\(server.name ?? server.displayAddress)」？"),
                message: String(localized: "将清除本机 Keychain 中的访问令牌与最近连接记录；桌面端服务不受影响，可随时重新扫码添加。"),
                confirmTitle: String(localized: "删除服务器"), confirmIcon: "trash",
                identifierPrefix: "l5-sheet-del") {
                    onDoneConfirmed()
                } onConfirmAction: {
                    deleteServer()
                }
        }
    }

    private func deleteServer() {
        showDeleteConfirm = false
        if let target = sessionServer {
            session.deleteServer(target)
        }
        onDoneConfirmed()
    }

    private func onDoneConfirmed() {
        onDone()
    }

    private var sessionServer: ServerConfig? {
        if case .connectFailed(let config, _) = session.mode { return config }
        return nil
    }

    private var errorCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 17))
                    .foregroundColor(T.red)
                    .frame(width: 44, height: 44)
                    .background(T.redDim)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                VStack(alignment: .leading, spacing: 5) {
                    Text(error.headline)
                        .font(T.font(15.5, .bold))
                        .foregroundColor(T.text)
                    HStack(spacing: 8) {
                        Text(error.codeLine)
                            .font(T.mono(11))
                            .foregroundColor(T.red)
                            .padding(.horizontal, T.sp2)
                            .padding(.vertical, 3)
                            .background(T.redDim)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .accessibilityIdentifier("l3-err-code")
                        if !server.token.isEmpty {
                            Text("token=\(OAuthCredentialStore.mask(server.token))")
                                .font(T.mono(10.5))
                                .foregroundColor(T.text3)
                        }
                    }
                }
            }
            Text(explanation)
                .font(T.font(12.5))
                .foregroundColor(T.text2)
                .lineSpacing(3)
        }
        .card(padding: 14)
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
    }

    private var explanation: String {
        switch error {
        case .http(let status, _):
            return status == 401
                ? String(localized: "桌面端重启后令牌会重新生成（自动生成模式，无过期与撤销接口），旧令牌随即失效。重新扫码或粘贴新链接即可更新。")
                : String(localized: "服务返回 HTTP \(status)。请确认桌面端 zcode --web 进程存活后重试。")
        case .timeout:
            return String(localized: "请求超时。请自查三件事：手机与桌面机在同一局域网、zcode --web 进程运行中（端口 \(server.port)）、本地网络权限已允许（设置 → 隐私与安全性 → 本地网络，被拒时请求静默失败）。")
        case .protocolVersion(let actual):
            return String(localized: "服务端协议为 \(actual)，本端要求 remote v1 · v4 wire v3。请升级其中一端后重试（不做静默降级）。")
        case .emptyWorkspaces:
            return String(localized: "server-info 返回的工作区列表为空。请在桌面端检查 --workspace / ZCODE_SERVER_WORKSPACE 配置后重试。")
        case .handshakeFailed(let detail):
            return String(localized: "v4 握手失败（\(detail)）。请确认桌面端版本支持 v4 协议后重试。")
        case .transport(let detail):
            return String(localized: "无法建立 WebSocket（\(detail)）。请确认桌面端 zcode --web 进程运行中且端口可达。")
        }
    }

    /// 主 CTA 随错误类型切换（401 → 重新扫码；超时 → 原配置重试；协议不符 → 升级指引）
    private var recoveryActions: some View {
        VStack(spacing: 10) {
            switch error {
            case .http(let status, _) where status == 401:
                PrimaryButton(title: "重新扫码更新令牌", identifier: "l3-btn-rescan", action: onRescan)
            case .protocolVersion:
                primaryButton(title: String(localized: "升级后重试"), identifier: "l3-btn-retry", action: onRetry)
            case .timeout:
                primaryButton(title: String(localized: "原配置重试"), identifier: "l3-btn-retry", action: onRetry)
            default:
                primaryButton(title: String(localized: "原配置重试"), identifier: "l3-btn-retry", action: onRetry)
            }
            HStack(spacing: 10) {
                secondaryButton(title: String(localized: "原配置重试"), icon: "arrow.clockwise", identifier: "l3-btn-retry-secondary") {
                    Task { onRetry() }
                }
                // 手动更新令牌 = 手动输入族入口：仅开发者模式（正式用户 401 恢复走
                // 「重新扫码」主 CTA；二按钮单列时全宽）
                if settings.effectiveDeveloperMode {
                    secondaryButton(title: String(localized: "手动更新令牌"), icon: "key", identifier: "l3-btn-update-token", action: onManualToken)
                }
            }
        }
    }

    private func primaryButton(title: String, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(T.font(15, .semibold))
                .foregroundColor(T.onAccent)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(T.accent)
                .clipShape(RoundedRectangle(cornerRadius: T.rM))
        }
        .accessibilityIdentifier(identifier)
    }

    private func secondaryButton(title: String, icon: String, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(T.font(13.5, .semibold))
                .foregroundColor(T.text)
                .frame(maxWidth: .infinity, minHeight: 44)
                .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
        }
        .accessibilityIdentifier(identifier)
    }

    private var comparisonList: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(String(localized: "常见错误对照 · 4 态"))
                .font(T.font(13, .bold))
                .foregroundColor(T.text2)
            VStack(spacing: 0) {
                row(id: "l3-row-401", icon: "key", tint: T.red,
                    title: String(localized: "401 · 令牌不匹配"),
                    detail: String(localized: "桌面重启后自动令牌轮换 → 重新扫码 / 粘贴新链接"))
                row(id: "l3-row-timeout", icon: "wifi", tint: T.orange,
                    title: String(localized: "超时 · 无法连接"),
                    detail: String(localized: "同一局域网？zcode --web 运行中？本地网络权限已允许（被拒时请求静默失败）→ 重试"))
                row(id: "l3-row-protocol", icon: "square.stack.3d.up", tint: T.violet,
                    title: String(localized: "协议版本不匹配"),
                    detail: String(localized: "需 remote v1 / v4 wire v3 → 升级一端"))
                row(id: "l3-row-empty-ws", icon: "folder", tint: T.text2,
                    title: String(localized: "工作区列表为空"),
                    detail: String(localized: "在桌面端检查 --workspace / ZCODE_SERVER_WORKSPACE 配置 → 重试"))
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        }
    }

    private func row(id: String, icon: String, tint: Color, title: String, detail: String) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(tint)
                .frame(width: 30, height: 30)
                .background(T.bgElevated)
                .clipShape(RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(T.font(13.5, .semibold)).foregroundColor(T.text)
                Text(detail)
                    .font(T.font(11))
                    .foregroundColor(T.text3)
                    .lineLimit(3)
            }
            Spacer()
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .accessibilityIdentifier(id)
    }

    private var deleteButton: some View {
        Button {
            showDeleteConfirm = true
        } label: {
            Label(String(localized: "删除此服务器"), systemImage: "trash")
                .font(T.font(13.5, .semibold))
                .foregroundColor(T.red)
                .frame(maxWidth: .infinity, minHeight: 44)
                .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
        }
        .accessibilityIdentifier("l3-btn-delete")
    }

    @Environment(AppSession.self) private var session
}

// MARK: - 危险操作二次确认 Sheet（L5-S：下拉不可关闭，必须显式二选一）

struct ConfirmSheet: View {
    let title: String
    let message: String
    let confirmTitle: String
    let confirmIcon: String
    let identifierPrefix: String
    var onCancel: () -> Void
    var onConfirmAction: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture { } // 遮罩点击不关闭（不可逆操作）
            VStack(spacing: T.sp3) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(T.borderStrong)
                    .frame(width: 36, height: 4)
                    .padding(.top, T.sp2)
                HStack(alignment: .top, spacing: T.sp3) {
                    Image(systemName: confirmIcon)
                        .font(.system(size: 17))
                        .foregroundColor(T.red)
                        .frame(width: 44, height: 44)
                        .background(T.redDim)
                        .clipShape(RoundedRectangle(cornerRadius: 13))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title)
                            .font(T.font(16, .heavy))
                            .foregroundColor(T.text)
                        Text(message)
                            .font(T.font(12))
                            .foregroundColor(T.text2)
                            .lineSpacing(3)
                    }
                }
                Button(action: onConfirmAction) {
                    Label(confirmTitle, systemImage: confirmIcon)
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.red)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.redLine, lineWidth: 1))
                }
                .accessibilityIdentifier("\(identifierPrefix)-act-confirm")
                Button(action: onCancel) {
                    Text(String(localized: "取消"))
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("\(identifierPrefix)-act-cancel")
            }
            .padding(.horizontal, T.sp4)
            .padding(.bottom, 12)
            .frame(maxWidth: .infinity)
            .background(T.bgElevated)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .presentationDetentsCompat()
        }
    }
}

extension View {
    @ViewBuilder
    func presentationDetentsCompat() -> some View {
        if #available(iOS 16.4, *) {
            self.presentationDetents([.medium])
        } else {
            self
        }
    }
}

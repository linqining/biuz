import SwiftUI

// MARK: - O3-A 登录成功（displayName / avatarUrl / tokenSet 摘要）

struct OAuthSuccessView: View {
    @Environment(AppSession.self) private var session
    var onContinue: () -> Void

    /// 登录直达会话（项 1）状态机：进入本页即自动尝试连上已配对的桌面端
    enum AutoLinkPhase: Equatable {
        case idle
        case connecting(source: String?)
        case connected
        case noDevice
        case failed(source: String?, detail: String)
    }
    @State private var autoPhase: AutoLinkPhase = .idle
    @State private var pasteError: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                VStack(spacing: 12) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(T.accentText)
                        .frame(width: 56, height: 56)
                        .background(T.accentDim)
                        .clipShape(Circle())
                    Text("已使用 Z.ai 账号登录")
                        .font(T.font(17, .heavy))
                        .foregroundColor(T.text)
                }
                .padding(.top, T.sp2)

                if let userInfo = session.oauthUserInfo, let tokenSet = session.oauthTokenSet {
                    userCard(userInfo)
                    tokenSetSummary(tokenSet)
                    bearerNote
                }
                autoLinkSection
                Spacer(minLength: 16)
                Button(action: onContinue) {
                    HStack {
                        Text("开始使用")
                        Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                    }
                    .font(T.font(15.5, .semibold))
                    .foregroundColor(T.onAccent)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(T.accent)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .accessibilityIdentifier("o3-btn-continue")
                .padding(.bottom, 12)
            }
            .padding(.horizontal, T.sp6)
        }
        .scrollIndicators(.hidden)
        .task {
            // 登录成功回调链的直达动作：自动发现设备并连接（无设备/失败不静默，
            // 落为下方可行动引导卡）。无配对设备时该调用立即返回，不影响页面呈现。
            guard autoPhase == .idle else { return }
            autoPhase = .connecting(source: session.savedServer?.relay != nil
                ? session.savedServer?.displayName : nil)
            let outcome = await session.autoConnectAfterLogin()
            switch outcome {
            case .connected:
                autoPhase = .connected
            case .noPairedDevice:
                autoPhase = .noDevice
            case .failed(let source):
                autoPhase = .failed(source: source, detail: "连接未成功，桌面端可能未在线")
            }
        }
    }

    // MARK: 登录直达区块（连接中 / 无设备 / 失败三态，全部可行动）

    @ViewBuilder
    private var autoLinkSection: some View {
        switch autoPhase {
        case .idle, .connected:
            EmptyView()
        case .connecting(let source):
            HStack(spacing: T.sp3) {
                SpinnerView(size: 16)
                VStack(alignment: .leading, spacing: 1) {
                    Text("正在连接我的 Mac…")
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.text)
                    Text(source ?? "正在发现已配对的桌面设备")
                        .font(T.mono(10.5))
                        .foregroundColor(T.text3)
                        .lineLimit(1)
                }
                Spacer()
            }
            .card(padding: T.sp3)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("o3-card-autolink")
        case .noDevice:
            autoLinkGuide(
                icon: "desktopcomputer.and.arrow.down", tint: T.orange,
                title: "暂未发现已配对的桌面设备",
                detail: "在桌面端打开 Web 远程控制并复制配对链接，回到这里粘贴即可直达；也可以先开始使用，稍后从连接页扫码。")
        case .failed(let source, let detail):
            autoLinkGuide(
                icon: "exclamationmark.triangle", tint: T.orange,
                title: "连接 \(source ?? "我的 Mac") 失败",
                detail: "\(detail)。可检查桌面端是否在线后重试，或重新复制配对链接粘贴。")
        }
    }

    private func autoLinkGuide(icon: String, tint: Color, title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            HStack(spacing: T.sp2) {
                Image(systemName: icon)
                    .font(.system(size: 13))
                    .foregroundColor(tint)
                Text(title)
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text)
                Spacer()
            }
            Text(detail)
                .font(T.font(11.5))
                .foregroundColor(T.text2)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
            if let pasteError {
                Text(pasteError)
                    .font(T.font(11))
                    .foregroundColor(T.red)
            }
            HStack(spacing: 10) {
                Button {
                    connectFromClipboard()
                } label: {
                    Label("粘贴链接连接", systemImage: "doc.on.clipboard")
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .accessibilityIdentifier("o3-act-paste-link")

                Button {
                    onContinue()
                } label: {
                    Text("先去连接桌面端")
                        .font(T.font(13, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("o3-act-skip-connect")
            }
            .padding(.top, 2)
        }
        .card(padding: T.sp3)
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.orange.opacity(0.45), lineWidth: 1))
        // 透明容器：容器可定位（o3-card-autolink），粘贴/跳过动作保留各自 identifier
        // （同 05-approval-card 处理，容器 identifier 不吞后代）
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("o3-card-autolink")
    }

    /// 剪贴板 → 云中继配对链接 → 既有 connectRelayLink（成功由 dismissFlow 收起本 cover）
    private func connectFromClipboard() {
        guard let text = UIPasteboard.general.string,
              ConnectURLParser.parseRelayLink(text) != nil else {
            pasteError = "剪贴板中没有云中继配对链接（桌面端 Web 远程控制 → 复制链接）"
            return
        }
        pasteError = nil
        autoPhase = .connecting(source: "剪贴板配对链接")
        Task {
            await session.connectRelayLink(text)
            if case .connected = session.mode {
                autoPhase = .connected
            } else {
                session.cancelConnecting()
                autoPhase = .failed(source: "剪贴板配对链接", detail: "链接无效或桌面端离线")
            }
        }
    }

    private func userCard(_ userInfo: OAuthUserInfo) -> some View {
        HStack(spacing: 14) {
            OAuthAvatar(userInfo: userInfo, size: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(userInfo.displayName)
                    .font(T.font(17, .heavy))
                    .foregroundColor(T.text)
                Text("@\(userInfo.username) · \(userInfo.id)")
                    .font(T.mono(12))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    StatusPill(text: "ZAI · chat.z.ai", kind: .done, compact: true)
                    StatusPill(text: "Coding Plan", kind: .tag, compact: true)
                }
                .padding(.top, 4)
            }
            Spacer()
        }
        .card(padding: T.sp4)
        .accessibilityIdentifier("o3-card-user")
    }

    private func tokenSetSummary(_ tokenSet: OAuthTokenSet) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: T.sp2) {
                Text("凭据摘要 · tokenSet")
                    .font(T.font(13, .bold))
                    .foregroundColor(T.text2)
                Text("仅存 Keychain")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
            VStack(spacing: 0) {
                summaryRow(icon: "key", title: "accessToken",
                           detail: "zai access_token · \(OAuthCredentialStore.mask(tokenSet.accessToken))",
                           trailing: "掩码")
                summaryRow(icon: "checkmark.seal", title: "zcodeJwtToken",
                           detail: "data.token · zcode JWT · 已签发", trailing: nil, tint: T.accentText)
                summaryRow(icon: "clock", title: "expiresAt",
                           detail: tokenSet.expiresAt.map {
                               "有效期至 \(Self.formatter.string(from: $0)) · 由 expires_in 换算"
                           } ?? "未返回 expires_in", trailing: nil)
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        }
    }

    private func summaryRow(icon: String, title: String, detail: String, trailing: String?, tint: Color = T.text2) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(tint)
                .frame(width: 30, height: 30)
                .background(T.bgElevated)
                .clipShape(RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(T.font(13)).foregroundColor(T.text)
                Text(detail)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(1)
            }
            Spacer()
            if let trailing {
                Text(trailing).font(T.mono(10.5)).foregroundColor(T.text3)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
    }

    private var bearerNote: some View {
        HStack(alignment: .top, spacing: 7) {
            Image(systemName: "curlybraces")
                .font(.system(size: 11))
                .foregroundColor(T.text3)
            Text("后续请求携带 Authorization: Bearer {accessToken}（conversationSharePreviewClient.ts:145，转引）")
                .font(T.font(10.5))
                .foregroundColor(T.text3)
        }
    }

    static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter
    }()
}

/// 头像位：avatarUrl 渲染；data:image base64 解码；无图渐变+首字母兜底
struct OAuthAvatar: View {
    let userInfo: OAuthUserInfo
    var size: CGFloat = 48

    var body: some View {
        Group {
            if let image = decodedImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if let urlString = userInfo.avatarUrl, urlString.hasPrefix("http"), let url = URL(string: urlString) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        fallback
                    }
                }
            } else {
                fallback
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    private var fallback: some View {
        Text(String(userInfo.displayName.prefix(1)).uppercased())
            .font(T.font(size * 0.4, .heavy))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(T.gradAvatar)
    }

    private var decodedImage: UIImage? {
        guard let urlString = userInfo.avatarUrl,
              urlString.lowercased().hasPrefix("data:image"),
              let commaIndex = urlString.firstIndex(of: ",") else { return nil }
        let base64 = String(urlString[urlString.index(after: commaIndex)...])
        guard let data = Data(base64Encoded: base64) else { return nil }
        return UIImage(data: data)
    }
}

// MARK: - O3-B 失败 / 用户取消 · 重试（五态对照）

struct OAuthFailureView: View {
    let error: OAuthError
    let stateValue: String
    var onRetry: () -> Void
    var onSwitchProvider: () -> Void
    var onSkip: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: T.sp3) {
                errorCard
                Button(action: onRetry) {
                    Label("重新登录", systemImage: "arrow.clockwise")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(T.accent)
                        .clipShape(RoundedRectangle(cornerRadius: T.rM))
                }
                .accessibilityIdentifier("o3-btn-retry")

                HStack(spacing: 10) {
                    Button(action: onSwitchProvider) {
                        Label("改用 BigModel 登录", systemImage: "key")
                            .font(T.font(13.5, .semibold))
                            .foregroundColor(T.text)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                    }
                    .accessibilityIdentifier("o3-btn-switch-provider")
                    Button(action: onSkip) {
                        Label("跳过 · 连接桌面端", systemImage: "display")
                            .font(T.font(13.5, .semibold))
                            .foregroundColor(T.text)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                    }
                    .accessibilityIdentifier("o3-btn-skip")
                }

                comparisonList
                Spacer(minLength: 12)
                Text("重试保留发起参数（client_id / redirect_uri）并重置一次性 state\n登录非使用前提：可跳过登录直接连接局域网桌面端")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.bottom, 10)
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp2)
        }
        .scrollIndicators(.hidden)
    }

    private var errorCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 17))
                    .foregroundColor(error == .userCancelled ? T.text2 : T.red)
                    .frame(width: 44, height: 44)
                    .background(error == .userCancelled ? T.bgInput : T.redDim)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                VStack(alignment: .leading, spacing: 5) {
                    Text(error.title)
                        .font(T.font(15.5, .bold))
                        .foregroundColor(T.text)
                    HStack(spacing: 8) {
                        Text(errCode)
                            .font(T.mono(11))
                            .foregroundColor(T.text2)
                            .padding(.horizontal, T.sp2)
                            .padding(.vertical, 3)
                            .background(T.bgInput)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .accessibilityIdentifier("o3-err-code")
                        Text("state=\(stateValue.isEmpty ? "—" : stateValue)")
                            .font(T.mono(10.5))
                            .foregroundColor(T.text3)
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

    private var errCode: String {
        switch error {
        case .userCancelled: return "未产生 code · 未产生 error"
        case .serverError(let detail): return "error=\(detail)"
        case .stateMismatch: return "state 缺失或不匹配"
        case .exchangeFailed(let detail): return "EXCHANGE · \(detail)"
        case .keychainFailed: return "KEYCHAIN 写入失败"
        }
    }

    private var explanation: String {
        switch error {
        case .userCancelled:
            return "你在授权 Sheet 中取消了授权（✕ / 下拉抓手 / 页内「取消」等价），未产生 code 亦无 error 参数。重新登录即可再次发起，登录状态不受影响。"
        case .serverError(let detail):
            return "授权服务器返回 error=\(detail)。可重试或改用 BigModel 登录；若持续出现请检查账号状态。"
        case .stateMismatch:
            return "回调 state 与发起值不一致或缺失（state 必填，防 CSRF）。为安全起见不接受降级，请重新发起授权。"
        case .exchangeFailed(let detail):
            return "POST /api/v1/oauth/token 失败（\(detail)）。请检查网络后重试；code/token 不回显。"
        case .keychainFailed:
            return "令牌交换成功但凭据写入 Keychain 失败。请重试登录；若持续失败请检查系统存储设置。"
        }
    }

    private var comparisonList: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("常见登录失败对照 · 5 态")
                .font(T.font(13, .bold))
                .foregroundColor(T.text2)
            VStack(spacing: 0) {
                comparisonRow(id: "o3-row-cancel", icon: "xmark", tint: T.text2,
                              title: "用户取消 / Sheet 关闭",
                              detail: "✕ / 下拉 / 页内取消 → 未产生 code → 重新登录")
                comparisonRow(id: "o3-row-error-param", icon: "exclamationmark.triangle", tint: T.red,
                              title: "授权服务器返回 error",
                              detail: "按 error 值展示原因（如 access_denied）→ 重试或换 Provider")
                comparisonRow(id: "o3-row-state", icon: "shield", tint: T.orange,
                              title: "state 缺失或不匹配",
                              detail: "防伪校验失败（state 必填，缺失即报错）→ 重新发起，不接受降级")
                comparisonRow(id: "o3-row-exchange", icon: "wifi", tint: T.blue,
                              title: "令牌交换失败",
                              detail: "POST /api/v1/oauth/token 超时或响应 code≠0 → 检查网络后重试")
                comparisonRow(id: "o3-row-keychain", icon: "shield.lefthalf.filled", tint: T.orange,
                              title: "Keychain 写入失败",
                              detail: "交换成功但凭据落地失败 → 就地错误态，重试登录")
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
            .overlay(RoundedRectangle(cornerRadius: T.rL).stroke(T.border, lineWidth: 1))
        }
    }

    private func comparisonRow(id: String, icon: String, tint: Color, title: String, detail: String) -> some View {
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
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .accessibilityIdentifier(id)
    }
}

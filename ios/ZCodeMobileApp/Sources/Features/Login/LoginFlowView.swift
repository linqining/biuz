import SwiftUI
import WebKit

// MARK: - 登录流程（板 O1–O3：双路径主页 → 应用内授权 Sheet → 回调拦截 → 交换 → 结果）

/// O1 登录主页入口（未登录拦截栈的完整版；从设置页「账户」/ 连接失败引导唤起）
struct LoginFlowView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AppSession.self) private var session
    @State private var step: LoginStep = .home
    @State private var provider: OAuthProviderID = .zai
    @State private var pendingState = ""
    @State private var authorizeURL: URL?
    @State private var callbackParams: OAuthCallbackParams?
    @State private var exchangeError: OAuthError?
    @State private var stepTimings: [String] = []
    @State private var exchangeLogs: [ConnectLogLine] = []
    @State private var exchangeStep: ExchangeStep = .callback
    /// G-026 协议页（ nil=关闭）
    @State private var agreementPage: AgreementPage?

    enum LoginStep: Equatable {
        case home
        case authorizing
        case exchanging
        case success
        case failure
    }

    enum ExchangeStep: Equatable {
        case callback, stateCheck, exchanging, keychain, done
    }

    private let oauth = ZaiOAuthProvider()

    var body: some View {
        NavigationStack {
            ZStack {
                T.bg.ignoresSafeArea()
                switch step {
                case .home:
                    LoginHomeView(
                        onStartOAuth: { startOAuth(.zai) },
                        onConnectDesktop: { dismiss() },
                        onBigModel: { startOAuth(.bigmodel) },
                        onAgreement: { agreementPage = $0 })
                case .authorizing:
                    // O2-A：背景页压暗 + 缩放（2.7 Sheet 转场），授权 Sheet 升起
                    LoginHomeView(
                        onStartOAuth: { startOAuth(.zai) },
                        onConnectDesktop: { dismiss() },
                        onBigModel: { startOAuth(.bigmodel) },
                        onAgreement: { agreementPage = $0 },
                        dimmed: true)
                        .overlay(alignment: .bottom) {
                            OAuthSheetView(
                                provider: provider,
                                url: authorizeURL,
                                onCallback: handleCallback,
                                onClose: { handleUserCancel() },
                                noCallback: $noCallbackFallback)
                                .presentationCornerRadiusCompatible()
                        }
                        .overlay(alignment: .top) {
                            if noCallbackFallback { noCallbackBanner }
                        }
                case .exchanging:
                    OAuthProgressView(
                        provider: provider,
                        step: exchangeStep,
                        params: callbackParams,
                        logs: exchangeLogs,
                        onCancel: { cancelFlow() })
                case .success:
                    OAuthSuccessView(onContinue: { dismiss() })
                case .failure:
                    OAuthFailureView(
                        error: exchangeError ?? .userCancelled,
                        stateValue: pendingState,
                        onRetry: { startOAuth(provider) },
                        onSwitchProvider: {
                            startOAuth(provider == .zai ? .bigmodel : .zai)
                        },
                        onSkip: { dismiss() })
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { navigationBar }
            .sheet(item: $agreementPage) { page in
                AgreementWebViewSheet(page: page)
            }
        }
    }

    @State private var noCallbackFallback = false

    @ToolbarContentBuilder
    private var navigationBar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Text(title)
                .font(T.font(16.5, .bold))
                .foregroundColor(T.text)
        }
        // 显式关闭：不想登录/不想连接时随时可退出（全屏 cover 无下滑手势）
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                cancelFlowIfRunning()
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(T.text)
                    .frame(width: 44, height: 44)
            }
            .accessibilityIdentifier("o1-act-close")
        }
    }

    /// 关闭前清理进行中的授权（避免后台回调残留），与 O2 ✕ 同语义
    private func cancelFlowIfRunning() {
        if step == .authorizing || step == .exchanging {
            handleUserCancel()
        }
    }

    private var title: String {
        switch step {
        case .home, .authorizing: return String(localized: "登录 BiuZ")
        case .exchanging: return String(localized: "正在完成登录")
        case .success: return String(localized: "登录成功")
        case .failure: return String(localized: "登录未完成")
        }
    }

    private var noCallbackBanner: some View {
        // 无回调兜底（o2-card-nocallback）：>60s 无进展时出现；主动取消不走此分支
        HStack(spacing: T.sp2) {
            Image(systemName: "clock")
                .font(.system(size: 13))
                .foregroundColor(T.text3)
            VStack(alignment: .leading, spacing: 1) {
                Text("授权页未响应？")
                    .font(T.font(11.5, .semibold))
                    .foregroundColor(T.text2)
                Text("检查网络后关闭并重新发起")
                    .font(T.font(10.5))
                    .foregroundColor(T.text3)
            }
            Spacer()
            Button {
                noCallbackFallback = false
                startOAuth(provider) // 重置一次性 state 重走 O1→O2-A
            } label: {
                Label("关闭并重新发起", systemImage: "arrow.clockwise")
                    .font(T.font(12.5, .semibold))
                    .foregroundColor(T.text2)
                    .padding(.horizontal, T.sp3)
                    .frame(minHeight: 44)
                    .background(T.bgCard)
                    .overlay(RoundedRectangle(cornerRadius: T.rS).stroke(T.borderStrong, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: T.rS))
            }
            .accessibilityIdentifier("o2-act-restart")
        }
        .card(padding: T.sp2)
        .padding(.horizontal, T.sp4)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    // MARK: 流程动作

    private func startOAuth(_ target: OAuthProviderID) {
        provider = target
        // state：一次性 nonce 编码为 web 同构载荷 base64url(JSON{nonce}) 后本地暂存（9.2 ①；
        // 服务端交换时解析校验，裸随机串会被拒 `{"detail":"invalid state"}`）
        pendingState = OAuthStateCodec.buildState(nonce: Self.randomState())
        authorizeURL = oauth.buildAuthorizeURL(provider: target, state: pendingState)
        noCallbackFallback = false
        guard authorizeURL != nil else {
            exchangeError = .exchangeFailed("无法构建授权 URL")
            step = .failure
            return
        }
        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
            step = .authorizing
        }
        // 60s 无回调兜底计时
        Task {
            try? await Task.sleep(nanoseconds: 60_000_000_000)
            if step == .authorizing {
                withAnimation { noCallbackFallback = true }
            }
        }
    }

    private func handleCallback(_ url: URL) {
        // ③ 回调拦截：code（或 authCode）+ state（必填，缺失即报错）+ error
        switch oauth.parseCallbackParams(from: url) {
        case .failure:
            exchangeError = .stateMismatch
            exchangeStep = .stateCheck
            step = .failure
        case .success(let params):
            callbackParams = params
            if let error = params.error, !error.isEmpty {
                exchangeError = .serverError(error)
                exchangeStep = .stateCheck
                step = .failure
                return
            }
            guard let code = params.code, !code.isEmpty else {
                exchangeError = .exchangeFailed("回调缺少 code")
                exchangeStep = .callback
                step = .failure
                return
            }
            guard params.state == pendingState else {
                exchangeError = .stateMismatch // 与发起值不一致 → 防伪失败，不接受降级
                exchangeStep = .stateCheck
                step = .failure
                return
            }
            // 结构校验（防伪的语义层）：state 必须能解析回发起时的 nonce
            guard OAuthStateCodec.nonce(in: params.state) != nil else {
                exchangeError = .stateMismatch
                exchangeStep = .stateCheck
                step = .failure
                return
            }
            Task { await exchange(code: code, state: params.state) }
        }
    }

    private func exchange(code: String, state: String) {
        withAnimation { step = .exchanging }
        exchangeStep = .callback
        exchangeLogs = [ConnectLogLine(kind: .ok, text: "callback received · state match")]
        exchangeStep = .stateCheck
        exchangeLogs.append(ConnectLogLine(kind: .ok, text: "state 校验通过 · 防伪一致"))
        exchangeStep = .exchanging
        exchangeLogs.append(ConnectLogLine(kind: .working, text: "POST /api/v1/oauth/token · provider=\(provider.rawValue) · code=***"))
        Task {
            do {
                // 授权 WebView 会话 cookie 透传（web 参考在浏览器同源上下文交换）
                let tokenHost = oauth.tokenOriginHost
                let webCookies = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
                    .filter { $0.domain.hasSuffix(tokenHost) }
                let (tokenSet, userInfo) = try await oauth.exchangeToken(provider: provider, code: code, state: state, cookies: webCookies)
                do {
                    try OAuthCredentialStore.save(tokenSet: tokenSet, userInfo: userInfo)
                } catch {
                    exchangeError = .keychainFailed
                    exchangeStep = .keychain
                    exchangeLogs.append(ConnectLogLine(kind: .error, text: "Keychain 写入失败"))
                    withAnimation { step = .failure }
                    return
                }
                exchangeStep = .keychain
                exchangeLogs.append(ConnectLogLine(kind: .ok, text: "凭据写入 Keychain · tokenSet + userInfo"))
                session.handleOAuthSuccess(tokenSet: tokenSet, userInfo: userInfo)
                exchangeStep = .done
                withAnimation { step = .success }
            } catch let error as OAuthError {
                exchangeError = error
                exchangeLogs.append(ConnectLogLine(kind: .error, text: "交换失败 · \(Self.describe(error))"))
                withAnimation { step = .failure }
            } catch {
                exchangeError = .exchangeFailed(error.localizedDescription)
                withAnimation { step = .failure }
            }
        }
    }

    private func handleUserCancel() {
        // ✕ / 下拉 / 页内取消三等价：中性结果非故障（O3-B 第一态）
        exchangeError = .userCancelled
        exchangeStep = .callback
        withAnimation { step = .failure }
    }

    private func cancelFlow() {
        exchangeLogs.append(ConnectLogLine(kind: .info, text: "用户取消 · 清除暂存 state"))
        step = .home
    }

    private static func randomState() -> String {
        var bytes = [UInt8](repeating: 0, count: 6)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func describe(_ error: OAuthError) -> String {
        switch error {
        case .userCancelled: return "用户取消"
        case .serverError(let detail): return "error=\(detail)"
        case .stateMismatch: return "state 校验失败"
        case .exchangeFailed(let detail): return detail
        case .keychainFailed: return "Keychain 写入失败"
        }
    }
}

// MARK: - O1 登录主页（双路径入口）

struct LoginHomeView: View {
    var onStartOAuth: () -> Void
    var onConnectDesktop: () -> Void
    var onBigModel: () -> Void
    var onAgreement: (AgreementPage) -> Void = { _ in }
    var dimmed = false

    @State private var advancedExpanded = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                brand
                PrimaryButton(title: "使用 Z.ai 账号登录", identifier: "o1-btn-oauth", action: onStartOAuth)
                Button(action: onConnectDesktop) {
                    Label("连接桌面端", systemImage: "display")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("o1-btn-connect")

                advancedCard
                Spacer(minLength: 24)
                footer
            }
            .padding(.horizontal, T.sp6)
            .padding(.top, T.sp3)
        }
        .scrollIndicators(.hidden)
        .opacity(dimmed ? 0.32 : 1)
        .scaleEffect(dimmed ? 0.96 : 1, anchor: .top)
    }

    private var brand: some View {
        VStack(spacing: 14) {
            ZCodeBrandMark(size: 84)
            VStack(spacing: 4) {
                Text("BiuZ")
                    .font(T.font(24, .heavy))
                    .foregroundColor(T.text)
                    .accessibilityIdentifier("o1-brand-name")
                Text("AI 编程智能体 · 移动工作台")
                    .font(T.font(13))
                    .foregroundColor(T.text2)
            }
        }
        .padding(.top, 28)
        .padding(.bottom, 12)
    }

    private var advancedCard: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { advancedExpanded.toggle() }
            } label: {
                HStack(spacing: T.sp2) {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 13))
                        .foregroundColor(T.text3)
                    Text("高级选项")
                        .font(T.font(12.5, .semibold))
                        .foregroundColor(T.text2)
                    Spacer()
                    Text("BigModel · 其他登录方式")
                        .font(T.font(10.5))
                        .foregroundColor(T.text3)
                    Image(systemName: advancedExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12))
                        .foregroundColor(T.text3)
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityIdentifier("o1-adv-toggle")

            if advancedExpanded {
                Button(action: onBigModel) {
                    HStack(spacing: T.sp2) {
                        Image(systemName: "key")
                            .font(.system(size: 12))
                            .foregroundColor(T.text2)
                            .frame(width: 26, height: 26)
                            .background(T.bgElevated)
                            .clipShape(RoundedRectangle(cornerRadius: T.rS))
                        VStack(alignment: .leading, spacing: 1) {
                            Text("通过 BigModel 智能体平台登录")
                                .font(T.font(13, .semibold))
                                .foregroundColor(T.text)
                            Text("高级入口 · /login?appId=zcode · 默认无需改动")
                                .font(T.font(11.5))
                                .foregroundColor(T.text3)
                                .lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12))
                            .foregroundColor(T.text3)
                    }
                    .padding(.horizontal, T.sp3)
                    .padding(.bottom, T.sp2)
                    .frame(minHeight: 44)
                }
                .accessibilityIdentifier("o1-row-bigmodel")
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(T.bgElevated)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("o1-card-adv")
    }

    private var footer: some View {
        VStack(spacing: 2) {
            Text("Z.ai 账号登录在应用内授权 Sheet 中完成，凭据仅存本机 Keychain")
            // G-026：协议名可点 → 内嵌 WebView Sheet（可滚动/可关闭/断网失败态）
            HStack(spacing: 2) {
                Text("也可跳过登录直接连接局域网桌面端 · 登录即同意")
                Button { onAgreement(.terms) } label: {
                    Text("《用户协议》").foregroundColor(T.accentText)
                }
                .accessibilityIdentifier("o1-act-agreement")
                Text("与")
                Button { onAgreement(.privacy) } label: {
                    Text("《隐私政策》").foregroundColor(T.accentText)
                }
                .accessibilityIdentifier("o1-act-privacy")
            }
        }
        .font(T.font(11))
        .foregroundColor(T.text3)
        .multilineTextAlignment(.center)
        .lineSpacing(4)
        .padding(.bottom, 12)
    }
}

// MARK: - 品牌标（84px/76px 品牌位共用；BiuzMark 资产切图，源 branding/logo/png/mark-960.png）
// 结构体名沿用 ZCodeBrandMark（内部符号，无用户可见差异；视觉本体已换 BiuZ 单标）

struct ZCodeBrandMark: View {
    var size: CGFloat = 84

    var body: some View {
        Image("BiuzMark")
            .resizable()
            .interpolation(.high)
            .frame(width: size, height: size)
            .shadow(color: T.shadowFab, radius: 24, y: 8)
            .accessibilityHidden(true)
    }
}

// MARK: - O2-A 应用内授权 Sheet（WKWebView 容器 + redirect 拦截；禁止注入自绘同意页）

struct OAuthSheetView: View {
    let provider: OAuthProviderID
    let url: URL?
    var onCallback: (URL) -> Void
    var onClose: () -> Void
    @Binding var noCallback: Bool

    @State private var progress: Double = 0
    @State private var showProgress = true

    var body: some View {
        VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2.5)
                .fill(T.borderStrong)
                .frame(width: 36, height: 5)
                .padding(.top, T.sp2)
                .padding(.bottom, 2)

            // 内嵌视图工具行：✕（40×40 视觉 / 44 命中）+ 域名行 + 刷新
            HStack(spacing: T.sp2) {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(T.text2)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
                .accessibilityIdentifier("o2-act-close")

                HStack(spacing: 6) {
                    Image(systemName: "shield.fill")
                        .font(.system(size: 11))
                        .foregroundColor(provider == .zai ? T.accentText : T.blue)
                    Text(domainLabel)
                        .font(T.mono(11))
                        .foregroundColor(T.text2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background(T.bgInput)
                .overlay(RoundedRectangle(cornerRadius: 9).stroke(T.borderStrong, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 9))

                Button {
                    NotificationCenter.default.post(name: .oauthSheetReload, object: nil)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 14))
                        .foregroundColor(T.text3)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
                .opacity(0.45)
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 6)

            // 加载进度条（2.5px，随页面加载推进，完成后淡出）
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Rectangle().fill(T.bgInput)
                    Rectangle()
                        .fill(T.accent)
                        .frame(width: proxy.size.width * progress)
                        .opacity(showProgress ? 1 : 0)
                        .animation(.easeOut(duration: 0.25), value: showProgress)
                }
            }
            .frame(height: 2.5)
            .accessibilityIdentifier("o2-wv-progress")

            if let url {
                OAuthWebView(url: url, isCallbackURL: { oauthChecker.isCallbackURL($0) }) { callbackURL in
                    showProgress = false
                    onCallback(callbackURL)
                } onProgress: { value in
                    progress = value
                    showProgress = value < 1
                }
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity)
        .background(T.bgElevated)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(T.border, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.6), radius: 24, y: -6)
        .padding(.top, 96)
        .gesture(
            DragGesture().onEnded { value in
                if value.translation.height > 80 { onClose() } // 下拉抓手关闭（与审批 Sheet 不同）
            }
        )
    }

    private var domainLabel: String {
        switch provider {
        case .zai: return "chat.z.ai · Z.ai 账号授权"
        case .bigmodel: return "{origin}/login · BigModel 智能体平台"
        }
    }

    private var oauthChecker: ZaiOAuthProvider { ZaiOAuthProvider() }
}

extension Notification.Name {
    static let oauthSheetReload = Notification.Name("oauthSheetReload")
}

extension View {
    func presentationCornerRadiusCompatible() -> some View { self }
}

/// WKWebView 容器：仅做容器 + redirect 拦截（不做 JS 注入、不改写页面内容）
struct OAuthWebView: UIViewRepresentable {
    let url: URL
    let isCallbackURL: (URL) -> Bool
    let onCallback: (URL) -> Void
    let onProgress: (Double) -> Void

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.addObserver(context.coordinator, forKeyPath: "estimatedProgress", options: [.new], context: nil)
        context.coordinator.webView = webView
        context.coordinator.onCallback = onCallback
        context.coordinator.onProgress = onProgress
        context.coordinator.isCallbackURL = isCallbackURL
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        webView.load(request)
        context.coordinator.reloadObserver = NotificationCenter.default.addObserver(
            forName: .oauthSheetReload, object: nil, queue: .main) { [weak webView] _ in
            webView?.reload()
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.removeObserver(coordinator, forKeyPath: "estimatedProgress")
        if let observer = coordinator.reloadObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        weak var webView: WKWebView?
        var onCallback: ((URL) -> Void)?
        var onProgress: ((Double) -> Void)?
        var isCallbackURL: ((URL) -> Bool)?
        var callbackHandled = false
        var reloadObserver: NSObjectProtocol?

        override func observeValue(forKeyPath keyPath: String?, of object: Any?,
                                   change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
            if keyPath == "estimatedProgress", let progress = webView?.estimatedProgress {
                onProgress?(progress)
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // 回调拦截：redirect 导航在视图内被拦截（不真正加载、不离开 App）
            if let url = navigationAction.request.url,
               let checker = isCallbackURL, checker(url), !callbackHandled {
                callbackHandled = true
                decisionHandler(.cancel)
                onCallback?(url)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation?,
                     withError error: Error) {
            onProgress?(1)
        }
    }
}

// MARK: - O2-B 回调拦截与授权中（四步进度 + oauth.log）

struct OAuthProgressView: View {
    let provider: OAuthProviderID
    let step: LoginFlowView.ExchangeStep
    let params: OAuthCallbackParams?
    let logs: [ConnectLogLine]
    var onCancel: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: T.sp3) {
                header
                callbackParamsCard
                stepsCard
                logTerminal
                Spacer(minLength: 16)
                Button(action: onCancel) {
                    Label("取消登录", systemImage: "xmark")
                        .font(T.font(15, .semibold))
                        .foregroundColor(T.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.borderStrong, lineWidth: 1))
                }
                .accessibilityIdentifier("o2-act-cancel-flow")
                .padding(.bottom, 12)
            }
            .padding(.horizontal, T.sp4)
            .padding(.top, T.sp2)
        }
        .scrollIndicators(.hidden)
    }

    private var header: some View {
        HStack(spacing: 13) {
            Image(systemName: "link")
                .font(.system(size: 20))
                .foregroundColor(T.blue)
                .frame(width: 52, height: 52)
                .background(T.blueDim)
                .clipShape(RoundedRectangle(cornerRadius: 16))
            VStack(alignment: .leading, spacing: 3) {
                Text("已拦截授权回调")
                    .font(T.font(16.5, .heavy))
                    .foregroundColor(T.text)
                Text("redirect 拦截 · 正在校验授权结果")
                    .font(T.font(11))
                    .foregroundColor(T.text3)
            }
            Spacer()
            StatusPill(text: "授权中", kind: .run)
        }
        .padding(.vertical, 4)
    }

    private var callbackParamsCard: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("zcode://oauth/callback?")
                .font(T.mono(11))
                .foregroundColor(T.codeLab)
            if let params {
                HStack(spacing: 4) {
                    Text("code=").font(T.mono(11)).foregroundColor(T.codeLab)
                    Text(OAuthCredentialStore.mask(params.code ?? "…"))
                        .font(T.mono(11)).foregroundColor(T.text3)
                    Text("  // 或 authCode，双兼容")
                        .font(T.mono(10.5)).foregroundColor(T.text3)
                }
                HStack(spacing: 4) {
                    Text("state=").font(T.mono(11)).foregroundColor(T.codeLab)
                    Text(params.state)
                        .font(T.mono(11)).foregroundColor(T.accentText)
                    Text("  // 与发起值一致 ✓")
                        .font(T.mono(10.5)).foregroundColor(T.text3)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(11)
        .background(T.bgCode)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("o2-params-callback")
    }

    private var stepsCard: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Circle().fill(T.blue).frame(width: 7, height: 7)
                    Text("登录进度 · \(stepIndex)/4")
                        .font(T.font(13, .bold))
                        .foregroundColor(T.text2)
                }
                Spacer()
                ThinProgressBar(progress: Double(stepIndex) / 4.0, height: 4, tint: T.blue)
                    .frame(width: 110)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            stepRow(index: 0, name: "接收回调",
                    detail: "code 已提取 · error 参数未出现", isDone: stepIndex > 1, isRunning: stepIndex == 1)
            stepRow(index: 1, name: "校验 state",
                    detail: "与发起值一致 · 防伪通过", isDone: stepIndex > 2, isRunning: stepIndex == 2)
            stepRow(index: 2, name: "交换令牌",
                    detail: "POST /api/v1/oauth/token · provider=\(provider.rawValue)",
                    isDone: stepIndex > 3, isRunning: stepIndex == 3)
            stepRow(index: 3, name: "凭据写入 Keychain",
                    detail: "tokenSet + userInfo · 仅存本机",
                    isDone: stepIndex > 4, isRunning: stepIndex == 4)
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
        .accessibilityIdentifier("o2-card-steps")
    }

    private var stepIndex: Int {
        switch step {
        case .callback: return 1
        case .stateCheck: return 2
        case .exchanging: return 3
        case .keychain: return 4
        case .done: return 5
        }
    }

    private func stepRow(index: Int, name: String, detail: String, isDone: Bool, isRunning: Bool) -> some View {
        HStack(spacing: 11) {
            ZStack {
                if isDone {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(T.accentText)
                        .frame(width: 24, height: 24)
                        .background(T.accentDim)
                        .clipShape(Circle())
                } else if isRunning {
                    SpinnerView(size: 16)
                        .frame(width: 24, height: 24)
                } else {
                    Circle()
                        .strokeBorder(T.borderStrong, style: StrokeStyle(lineWidth: 1.5, dash: [3]))
                        .frame(width: 24, height: 24)
                }
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(T.font(13.5, isDone || isRunning ? .semibold : .medium))
                    .foregroundColor(isDone || isRunning ? T.text : T.text3)
                Text(detail)
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                    .lineLimit(2)
            }
            Spacer()
            if isRunning {
                Text("…").font(T.mono(10.5)).foregroundColor(T.text3)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
        .accessibilityIdentifier("o2-step-\(["callback", "state", "exchange", "keychain"][index])")
    }

    private var logTerminal: some View {
        VStack(spacing: 0) {
            HStack(spacing: T.sp2) {
                Image(systemName: "terminal")
                    .font(.system(size: 12))
                    .foregroundColor(T.text3)
                Text("oauth.log")
                    .font(T.mono(10.5))
                    .foregroundColor(T.text3)
                Spacer()
                SpinnerView(size: 12)
            }
            .padding(.horizontal, T.sp3)
            .padding(.vertical, T.sp2)
            .overlay(alignment: .bottom) { Rectangle().fill(T.border).frame(height: 1) }

            VStack(alignment: .leading, spacing: 2) {
                ForEach(logs) { line in
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
        .accessibilityIdentifier("o2-log-term")
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


// MARK: - 协议内嵌页（G-026：可滚动 / 可关闭 / 断网失败态；URL 集中配置）

/// 协议页身份（URL 集中配置点；运营侧更换仅需改 rawValue 指向）
enum AgreementPage: String, Identifiable {
    case terms
    case privacy
    var id: String { rawValue }

    var title: String {
        switch self {
        case .terms: return String(localized: "用户协议")
        case .privacy: return String(localized: "隐私政策")
        }
    }

    /// 当前为占位地址（可配置点）；404/断网由 WebView 失败态兜底呈现
    var url: URL {
        switch self {
        case .terms: return URL(string: "https://biuz.app/legal/terms")!
        case .privacy: return URL(string: "https://biuz.app/legal/privacy")!
        }
    }
}

struct AgreementWebViewSheet: View {
    @Environment(\.dismiss) private var dismiss
    let page: AgreementPage
    @State private var loadFailed = false

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text(page.title).font(T.font(17, .bold)).foregroundColor(T.text)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(T.text)
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("o1-legal-close")
            }
            .padding(.horizontal, T.sp4)

            ZStack {
                AgreementWebView(url: page.url, onLoadFailed: { loadFailed = true })
                    .opacity(loadFailed ? 0 : 1)
                if loadFailed {
                    VStack(spacing: T.sp2) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 24))
                            .foregroundColor(T.text3)
                            .frame(width: 56, height: 56)
                            .background(T.bgInput)
                            .clipShape(Circle())
                        Text("加载失败")
                            .font(T.font(14, .bold))
                            .foregroundColor(T.text)
                        Text("检查网络后重试；协议内容也可在官网查看")
                            .font(T.font(11.5))
                            .foregroundColor(T.text3)
                        Button {
                            loadFailed = false
                            // 重建视图触发重新加载
                            reloadToken += 1
                        } label: {
                            Text("重试")
                                .font(T.font(13, .semibold))
                                .foregroundColor(T.onAccent)
                                .padding(.horizontal, T.sp4)
                                .frame(minHeight: 44)
                                .background(T.accent)
                                .clipShape(RoundedRectangle(cornerRadius: T.rM))
                        }
                        .accessibilityIdentifier("o1-legal-retry")
                    }
                    .id(reloadToken)
                }
            }
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
    }

    @State private var reloadToken = 0
}

/// 轻量 WKWebView 容器（协议只读页；不做注入与拦截）
struct AgreementWebView: UIViewRepresentable {
    let url: URL
    let onLoadFailed: () -> Void

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView(frame: .zero)
        webView.navigationDelegate = context.coordinator
        context.coordinator.onLoadFailed = onLoadFailed
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var onLoadFailed: (() -> Void)?
        var reported = false

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation?, withError error: Error) {
            if !reported { reported = true; onLoadFailed?() }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation?, withError error: Error) {
            if !reported { reported = true; onLoadFailed?() }
        }
    }
}

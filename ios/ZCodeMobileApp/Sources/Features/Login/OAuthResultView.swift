import SwiftUI

// MARK: - O3-A 登录成功（displayName / avatarUrl / tokenSet 摘要）

struct OAuthSuccessView: View {
    @Environment(AppSession.self) private var session
    var onContinue: () -> Void

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

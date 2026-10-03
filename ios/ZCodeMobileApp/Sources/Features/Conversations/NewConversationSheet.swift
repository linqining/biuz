import SwiftUI

/// 屏 03 · 新建会话 Sheet（抓手 + sticky 头部 + 大输入区 + 执行端单选 + 建议提示词）
/// v3 纠偏：连接态恢复完整表单——标题/首条指令以 createSession+firstInput 一次下发
/// （桌面端立即开跑），空标题保持 draft 空会话 + 转正写；演示态行为不变。
struct NewConversationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.conversationStore) private var conversationStore
    @Environment(AppSettingsModel.self) private var settings
    var onCreated: (Conversation) -> Void

    @State private var title = ""
    @State private var directory = "~/work/zcode"
    @State private var executor: ExecutorKind = .cloudSandbox
    @FocusState private var inputFocused: Bool

    private var suggestions: [(String, String)] {
        [
            ("修一个 bug", "定位并修复登录超时问题，补回归测试"),
            ("写一段功能", "为会话列表增加搜索防抖与高亮"),
            ("重构一个模块", "把 SessionStore 拆成协议 + 内存/文件双实现"),
            ("解释这段代码", "阅读 TaskRunner.swift 并解释执行模型"),
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(T.borderStrong).frame(width: 36, height: 4).padding(.top, T.sp2)
            HStack {
                Text("新建会话").font(T.font(17, .bold)).foregroundColor(T.text)
                Spacer()
                Button {
                    if inputFocused {
                        inputFocused = false // 键盘态：取消 = 收起键盘
                    } else {
                        dismiss()
                    }
                } label: {
                    Text(inputFocused ? "收起键盘" : "取消")
                        .font(T.font(14, .medium))
                        .foregroundColor(T.text2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityIdentifier("03-act-cancel")
            }
            .padding(.horizontal, T.sp4)

            ScrollView {
                VStack(alignment: .leading, spacing: T.sp4) {
                    inputArea
                    contextChips
                    if !inputFocused {
                        executorSection
                    }
                    settingsSection
                    if !inputFocused {
                        suggestionSection
                    }
                }
                .padding(T.sp4)
            }
            .scrollDismissesKeyboard(.interactively)

            PrimaryButton(title: "开始任务", identifier: "03-submit-start") {
                Task {
                    let conversation = await conversationStore.createConversation(
                        title: title, directory: directory, executor: executor)
                    onCreated(conversation)
                }
            }
            .padding(.horizontal, T.sp4)
            .padding(.vertical, T.sp2)
            .background(T.bgElevated)
        }
        .background(T.bgElevated)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
    }

    private var inputArea: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            TextField("描述你要做的事…", text: $title, axis: .vertical)
                .font(T.font(16))
                .foregroundColor(T.text)
                .lineLimit(2...5)
                .focused($inputFocused)
                .padding(T.sp3)
                .frame(minHeight: 88, alignment: .topLeading)
                .background(T.bgInput)
                .clipShape(RoundedRectangle(cornerRadius: T.rL))
                .accessibilityIdentifier("03-input-title")
        }
    }

    private var contextChips: some View {
        FlexibleFlow(spacing: T.sp2) {
            chip("引用文件 @", icon: "doc.text", id: "03-chip-atfile")
            chip("附件", icon: "paperclip", id: "03-chip-attach")
            chip("仓库", icon: "shippingbox", id: "03-chip-repo")
            chip("语音", icon: "mic", id: "03-chip-voice")
        }
    }

    private func chip(_ text: String, icon: String, id: String) -> some View {
        Button {
            inputFocused = true
        } label: {
            HStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 11))
                Text(text).font(T.font(12.5, .medium))
            }
            .foregroundColor(T.text2)
            .padding(.horizontal, T.sp3)
            .frame(minHeight: 44)
            .background(T.bgInput)
            .clipShape(Capsule())
        }
        .accessibilityIdentifier(id)
    }

    private var executorSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("执行端").font(T.font(13, .semibold)).foregroundColor(T.text3)
            ForEach(ExecutorKind.allCases) { kind in
                Button {
                    executor = kind
                } label: {
                    HStack(spacing: T.sp3) {
                        Image(systemName: kind == .cloudSandbox ? "cloud.fill" : "laptopcomputer.and.macbook")
                            .font(.system(size: 16))
                            .foregroundColor(executor == kind ? T.accentText : T.text3)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(kind.label).font(T.font(14, .semibold)).foregroundColor(T.text)
                            Text(kind.subtitle).font(T.font(11.5)).foregroundColor(T.text3)
                        }
                        Spacer()
                        Image(systemName: executor == kind ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 18))
                            .foregroundColor(executor == kind ? T.accent : T.borderStrong)
                    }
                    .padding(T.sp3)
                    .background(executor == kind ? T.accentDim : T.bgCard)
                    .clipShape(RoundedRectangle(cornerRadius: T.rM))
                    .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(executor == kind ? T.accent.opacity(0.5) : T.border, lineWidth: 1))
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityIdentifier("03-exec-\(kind == .cloudSandbox ? "cloud" : "mac")")
            }
        }
    }

    private var settingsSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("设置").font(T.font(13, .semibold)).foregroundColor(T.text3)
            VStack(spacing: 0) {
                row(label: "工作目录", value: directory, icon: "folder")
                Divider().overlay(T.border).padding(.leading, 44)
                row(label: "模型与思考等级",
                    value: "\(settings.value.model) · \(settings.value.thoughtLevel.label)",
                    icon: "cpu")
            }
            .background(T.bgCard)
            .clipShape(RoundedRectangle(cornerRadius: T.rL))
        }
    }

    private func row(label: String, value: String, icon: String) -> some View {
        HStack(spacing: T.sp2) {
            Image(systemName: icon)
                .font(.system(size: 13))
                .foregroundColor(T.text3)
                .frame(width: 30)
            Text(label).font(T.font(14.5)).foregroundColor(T.text)
            Spacer()
            Text(value)
                .font(T.mono(11.5))
                .foregroundColor(T.text3)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(T.text3)
        }
        .padding(.horizontal, T.sp3)
        .frame(minHeight: 48)
    }

    private var suggestionSection: some View {
        VStack(alignment: .leading, spacing: T.sp2) {
            Text("试试这些").font(T.font(13, .semibold)).foregroundColor(T.text3)
            FlexibleFlow(spacing: T.sp2) {
                ForEach(suggestions, id: \.0) { suggestion in
                    Button {
                        title = suggestion.1
                        inputFocused = true
                    } label: {
                        Text(suggestion.0)
                            .font(T.font(12.5, .medium))
                            .foregroundColor(T.accentText)
                            .padding(.horizontal, T.sp3)
                            .frame(minHeight: 44)
                            .background(T.accentDim)
                            .clipShape(Capsule())
                    }
                    .accessibilityIdentifier("03-chip-suggest")
                }
            }
        }
    }
}

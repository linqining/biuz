import SwiftUI

/// Store 环境键：`@Environment(\.conversationStore)` 读取。
/// SwiftUI 的 `@Environment(T.self)` 只接受具体 Observable 类型，
/// 协议存在类型（any Store）经由 @Entry 环境值注入（SDK 27 的
/// EnvironmentKey 协议要求 _valuesEqual，手写 conformance 不再可行）。
/// 默认值给共享 Mock 实例，保证未注入时视图仍可用。
extension EnvironmentValues {
    @Entry var conversationStore: any ConversationStore = MockConversationStore()
    @Entry var taskStore: any TaskStore = MockTaskStore()
    @Entry var fileStore: any FileStore = MockFileStore()
}

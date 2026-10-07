import Foundation

// MARK: - 协议帧（packages/rpc/src/protocol.ts 移植）

/// 13 字节头：[type:1][id:4 BE][ack:4 BE][length:4 BE]（protocol.ts:183-230）
enum ProtocolMessageType: UInt8 {
    case none = 0
    case regular = 1
    case control = 2
    case ack = 3
    case disconnect = 5
    case replayRequest = 6
    case pause = 7
    case resume = 8
    case keepAlive = 9
}

enum RPCFrame {
    static let headerSize = 13

    /// 写一帧 Regular 消息（payload 为已序列化的 RPC 值）。
    static func write(payload: Data) -> Data {
        var out = Data(capacity: headerSize + payload.count)
        out.append(ProtocolMessageType.regular.rawValue)
        out.appendUInt32BE(0) // id
        out.appendUInt32BE(0) // ack
        out.appendUInt32BE(UInt32(payload.count))
        out.append(payload)
        return out
    }

    /// 解析一帧；返回 payload。非 Regular / 长度不符返回 nil（Ack/KeepAlive 丢弃）。
    static func read(_ data: Data) -> Data? {
        guard data.count >= headerSize else { return nil }
        let type = data[data.startIndex]
        guard type == ProtocolMessageType.regular.rawValue else { return nil }
        let length = data.readUInt32BE(at: 9)
        guard Int(length) == data.count - headerSize else { return nil }
        return data.subdata(in: (data.startIndex + headerSize)..<(data.startIndex + data.count))
    }
}

private extension Data {
    func readUInt32BE(at offset: Int) -> UInt32 {
        let start = startIndex + offset
        let bytes = (0..<4).map { self[start + $0] }
        return (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16) | (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
    }

    mutating func appendUInt32BE(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }
}

// MARK: - 请求/响应类型（channels.shared.ts:18-31）

enum RequestType: Int {
    case promise = 100
    case promiseCancel = 101
    case eventListen = 102
    case eventDispose = 103
}

enum ResponseType: Int {
    case initialize = 200
    case promiseSuccess = 201
    case promiseError = 202
    case promiseErrorObj = 203
    case eventFire = 204
}

/// RPC 层错误（PromiseError data.message/name 口径，channelClient.ts:96-117）
struct RPCError: Error, CustomStringConvertible {
    var message: String
    var name: String = "Error"
    var detail: JSONValue?

    var description: String { "\(name): \(message)" }

    /// 桥重开竞态下 assertReady 类调用的确定性拒绝【实证·上游仓
    /// zcodeAgentConnectionScope.assertReady】——真机 detail 形态 name="Error"、
    /// message="fault.connection.handshakeRequired"（sess_e8677b05 rowsRange 取证）
    var isHandshakeRequired: Bool {
        message.contains("fault.connection.handshakeRequired")
    }
}

// MARK: - 事件订阅句柄

/// 事件订阅：释放（cancel）时自动发 EventDispose（channelClient.ts:163-190）。
final class EventSubscription: @unchecked Sendable {
    private let onCancel: @Sendable () -> Void
    private var cancelled = false
    private let lock = NSLock()

    init(onCancel: @escaping @Sendable () -> Void) {
        self.onCancel = onCancel
    }

    func cancel() {
        lock.lock()
        let already = cancelled
        cancelled = true
        lock.unlock()
        if !already { onCancel() }
    }

    deinit {
        cancel()
    }
}

// MARK: - ChannelClient（channelClient.ts 移植；URLSessionWebSocketTask 传输）

/// 一条 WS 二进制消息 = 一个协议帧（websocket.ts write/send 一一对应，无需处理粘包）。
actor ChannelClient: RPCChannelTransport {

    enum State: CustomStringConvertible {
        case uninitialized
        case idle
        case closed
        var description: String {
            switch self {
            case .uninitialized: return "uninitialized"
            case .idle: return "idle"
            case .closed: return "closed"
            }
        }
    }

    private(set) var state: State = .uninitialized
    private var lastRequestId = 0
    private var socket: URLSessionWebSocketTask?
    private var pendingResponses: [Int: CheckedContinuation<RPCValue, Error>] = [:]
    private var initializeWaiters: [CheckedContinuation<Void, Error>] = []
    private var eventHandlers: [Int: @Sendable (RPCValue) -> Void] = [:]
    private var onClosed: (@Sendable (Error?) -> Void)?
    private let urlSession: URLSession

    init(urlSession: URLSession = .shared) {
        self.urlSession = urlSession
    }

    // MARK: 连接

    /// 连接并等待服务端 Initialize（channelServer.ts:30-35 连接建立即推）。
    func connect(url: URL, timeout: TimeInterval, onClosed: @escaping @Sendable (Error?) -> Void) async throws {
        self.onClosed = onClosed
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        let task = urlSession.webSocketTask(with: request)
        socket = task
        task.resume()
        receiveNext() // 读循环先行：Initialize 依赖它

        // Initialize 看门狗（channelClient.ts whenInitialized + 超时）
        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            await self?.failInitializeWaiters(
                RPCError(message: String(localized: "等待服务端 Initialize 超时"), name: "TimeoutError"))
        }
        defer { timeoutTask.cancel() }

        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                // 本闭包在 actor 上下文同步执行
                switch state {
                case .idle: continuation.resume()
                case .closed:
                    continuation.resume(throwing: RPCError(message: String(localized: "连接在 Initialize 前已关闭"), name: "ConnectionClosed"))
                case .uninitialized:
                    initializeWaiters.append(continuation)
                }
            }
        } catch {
            socket?.cancel(with: .normalClosure, reason: nil)
            throw error
        }
    }

    func disconnect() {
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        close()
    }

    private func close() {
        guard state != .closed else { return }
        state = .closed
        let closedError = RPCError(message: String(localized: "连接已关闭"), name: "ConnectionClosed")
        failPending(closedError)
        failInitializeWaiters(closedError)
        eventHandlers.removeAll()
        onClosed?(nil)
    }

    private func handleTransportError(_ error: Error) {
        guard state != .closed else { return }
        state = .closed
        let rpcError = RPCError(message: String(format: String(localized: "传输错误：%@"), error.localizedDescription), name: "TransportError")
        failPending(rpcError)
        failInitializeWaiters(rpcError)
        eventHandlers.removeAll()
        onClosed?(error)
    }

    private func failPending(_ error: Error) {
        let responses = pendingResponses
        pendingResponses.removeAll()
        for (_, continuation) in responses {
            continuation.resume(throwing: error)
        }
    }

    private func failInitializeWaiters(_ error: Error) {
        let waiters = initializeWaiters
        initializeWaiters.removeAll()
        for waiter in waiters {
            waiter.resume(throwing: error)
        }
    }

    // MARK: 请求（Promise）

    /// Promise 调用：serialize([100, id, channel, command]) + serialize(arg)（channelClient.ts:225-231）。
    /// 超时由看门狗任务兜底，超时/响应两路都经 resolveResponse 恰好 resume 一次。
    func call(_ channel: String, _ command: String, _ arg: RPCValue = .undefined,
              timeout: TimeInterval = 30) async throws -> RPCValue {
        guard state == .idle else {
            throw RPCError(message: String(format: String(localized: "通道未就绪（state=%@）"), String(describing: state)), name: "NotInitialized")
        }
        let id = lastRequestId
        lastRequestId += 1

        let header = RPCSerialization.serialize(.array([
            .int(RequestType.promise.rawValue), .int(id), .string(channel), .string(command),
        ]))
        let body = RPCSerialization.serialize(arg)
        let frame = RPCFrame.write(payload: header + body)

        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            await self?.resolveResponse(
                id: id,
                result: .failure(RPCError(message: String(format: String(localized: "RPC 超时：%@.%@"), channel, command), name: "TimeoutError")))
        }
        defer { timeoutTask.cancel() }

        return try await withCheckedThrowingContinuation { continuation in
            // 本闭包在 actor 上下文同步执行
            pendingResponses[id] = continuation
            sendOnCurrentSocket(frame)
        }
    }

    // MARK: 事件（EventListen / EventDispose）

    /// EventListen 一次，服务端按原 id 持续 EventFire（serialization：[102, id, channel, event] + arg）。
    func listen(_ channel: String, _ event: String, _ arg: RPCValue = .undefined,
                handler: @escaping @Sendable (RPCValue) -> Void) -> EventSubscription {
        let id = lastRequestId
        lastRequestId += 1
        eventHandlers[id] = handler

        let header = RPCSerialization.serialize(.array([
            .int(RequestType.eventListen.rawValue), .int(id), .string(channel), .string(event),
        ]))
        let body = RPCSerialization.serialize(arg)
        sendOnCurrentSocket(RPCFrame.write(payload: header + body))

        return EventSubscription { [weak self] in
            guard let self else { return }
            Task { await self.disposeEvent(id) }
        }
    }

    private func disposeEvent(_ id: Int) {
        guard eventHandlers.removeValue(forKey: id) != nil, state == .idle else { return }
        let header = RPCSerialization.serialize(.array([
            .int(RequestType.eventDispose.rawValue), .int(id),
        ]))
        let body = RPCSerialization.serialize(.undefined)
        sendOnCurrentSocket(RPCFrame.write(payload: header + body))
    }

    // MARK: 内部：发送与接收

    private func sendOnCurrentSocket(_ frame: Data) {
        guard let socket else { return }
        socket.send(.data(frame)) { [weak self] error in
            if let error, let self {
                Task { await self.handleTransportError(error) }
            }
        }
    }

    /// 读循环：每条 WS 二进制消息 = 一个协议帧。
    private func receiveNext() {
        guard let socket else { return }
        socket.receive { [weak self] result in
            guard let self else { return }
            Task { await self.handleReceive(result) }
        }
    }

    private func handleReceive(_ result: Result<URLSessionWebSocketTask.Message, Error>) {
        switch result {
        case .failure(let error):
            // cancel 主动断开也走这里；state 已 closed 时静默
            handleTransportError(error)
            return
        case .success(let message):
            defer { receiveNext() }
            guard case .data(let data) = message else { return }
            handleFrame(data)
        }
    }

    private func handleFrame(_ data: Data) {
        guard let payload = RPCFrame.read(data) else { return }
        var offset = 0
        guard let headerValue = RPCSerialization.deserialize(payload, &offset),
              let body = RPCSerialization.deserialize(payload, &offset) else { return }
        guard case .array(let headerItems) = headerValue,
              let rawType = headerItems[safe: 0]?.intValue,
              let type = ResponseType(rawValue: rawType) else { return }

        switch type {
        case .initialize:
            state = .idle
            let waiters = initializeWaiters
            initializeWaiters.removeAll()
            for waiter in waiters { waiter.resume() }
        case .promiseSuccess:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            resolveResponse(id: id, result: .success(body))
        case .promiseError:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            resolveResponse(id: id, result: .failure(decodeError(body)))
        case .promiseErrorObj:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            resolveResponse(id: id, result: .failure(
                RPCError(message: String(localized: "RPC 错误对象"), name: "ErrorObj", detail: body.jsonValue)))
        case .eventFire:
            guard let id = headerItems[safe: 1]?.intValue else { return }
            eventHandlers[id]?(body)
        }
    }

    /// 恰好 resume 一次（响应 / 超时两路竞争收口）。
    private func resolveResponse(id: Int, result: Result<RPCValue, Error>) {
        guard let continuation = pendingResponses.removeValue(forKey: id) else { return }
        switch result {
        case .success(let value): continuation.resume(returning: value)
        case .failure(let error): continuation.resume(throwing: error)
        }
    }

    private func decodeError(_ body: RPCValue) -> RPCError {
        guard let json = body.jsonValue, case .object(let dict) = json else {
            return RPCError(message: String(localized: "未知 RPC 错误"), name: "Error")
        }
        var error = RPCError(message: String(localized: "未知 RPC 错误"), name: "Error", detail: .object(dict))
        if case .string(let message)? = dict["message"] { error.message = message }
        if case .string(let name)? = dict["name"] { error.name = name }
        return error
    }
}

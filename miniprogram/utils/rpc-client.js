// RPC 客户端（通道层）—— web `packages/rpc/src/channelClient.ts` 的 JS 移植。
//
// 职责：request 帧（Promise=100）/ eventListen 帧（102）构建、响应/事件分发、
// 超时与取消。socket 为注入形态（{ send(Uint8Array), onMessage(cb), onClose(cb) }）
// ——单测用内存假 socket，运行时接 wx.connectSocket（binary 帧）。
//
// 约定（与 RelayChannelClient/ChannelClient 2026-11-10 根修同口径）：
// args = 完整位置参数数组（web toService 同形）——单对象参数服务传 [obj]，
// 多参服务传 [a, b]；由调用方（connection.js）按服务面组装。

const rpc = require('./rpc-serialization.js');

function createRpcClient(transport, options) {
  const timeoutMs = (options && options.timeoutMs) || 30000;
  let nextId = 1;
  let buffer = new Uint8Array(0);
  const pending = new Map(); // id → { resolve, reject, timer }
  const eventHandlers = new Map(); // "channel/event" → Set<fn>
  let closed = false;

  transport.onMessage(function (data) {
    const incoming = data instanceof Uint8Array ? data : new Uint8Array(data);
    buffer = concat(buffer, incoming);
    // WS 帧可能分片/黏包：循环按 13 字节头解析
    for (;;) {
      if (buffer.length < rpc.FRAME_HEADER_SIZE) return;
      const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
      const length = view.getUint32(9);
      if (buffer.length < rpc.FRAME_HEADER_SIZE + length) return;
      const frameBytes = buffer.subarray(0, rpc.FRAME_HEADER_SIZE + length);
      buffer = buffer.subarray(rpc.FRAME_HEADER_SIZE + length);
      handleFrame(frameBytes);
    }
  });

  transport.onClose(function () {
    if (closed) return;
    closed = true;
    const error = new Error('连接已关闭');
    pending.forEach(function (entry) {
      clearTimeout(entry.timer);
      entry.reject(error);
    });
    pending.clear();
  });

  function handleFrame(frameBytes) {
    const parsed = rpc.parseFramePayload(frameBytes.subarray(rpc.FRAME_HEADER_SIZE));
    if (!parsed || !Array.isArray(parsed.header)) return;
    const type = parsed.header[0];
    const id = parsed.header[1];
    if (type === rpc.REQUEST_PROMISE) {
      const entry = pending.get(id);
      if (!entry) return; // 迟到响应：移除后静默丢弃（Swift 同口径）
      pending.delete(id);
      clearTimeout(entry.timer);
      // 响应体形态：TS ChannelServer 回 [type, id, {statusCode, value}]？——
      // 桌面侧 promise 回执为正常值（iOS/Android 同面：resolve Response.value）。
      entry.resolve(parsed.body);
    } else if (type === rpc.EVENT_LISTEN) {
      // 事件帧 header = [102, id, channel, event]（注册 id 复用）；body = 载荷
      const channelName = parsed.header[2];
      const eventName = parsed.header[3];
      const handlers = eventHandlers.get(key(channelName, eventName));
      if (handlers) handlers.forEach(function (fn) {
        try { fn(parsed.body); } catch (err) { console.error('[BiuZ] event handler failed', err); }
      });
    }
  }

  function key(channel, event) { return channel + '/' + event; }

  return {
    /** 一次 promise RPC；args = 位置参数数组。回执 resolve（失败 reject）。 */
    call: function (channel, command, args, timeoutOverride) {
      if (closed) return Promise.reject(new Error('连接已关闭'));
      const id = nextId;
      nextId += 1;
      const frame = rpc.buildRequestFrame(id, channel, command, args || []);
      return new Promise(function (resolve, reject) {
        const timer = setTimeout(function () {
          pending.delete(id);
          reject(new Error('RPC 超时：' + channel + '.' + command));
        }, timeoutOverride || timeoutMs);
        pending.set(id, { resolve: resolve, reject: reject, timer: timer });
        try {
          transport.send(frame);
        } catch (err) {
          clearTimeout(timer);
          pending.delete(id);
          reject(err);
        }
      });
    },

    /** 订阅事件；返回退订函数。 */
    listen: function (channel, event, arg, handler) {
      const k = key(channel, event);
      if (!eventHandlers.has(k)) eventHandlers.set(k, new Set());
      eventHandlers.get(k).add(handler);
      const id = nextId;
      nextId += 1;
      try {
        transport.send(rpc.buildEventListenFrame(id, channel, event, arg === undefined ? [] : arg));
      } catch (err) {
        console.error('[BiuZ] eventListen send failed', err);
      }
      return function unsubscribe() {
        const handlers = eventHandlers.get(k);
        if (handlers) handlers.delete(handler);
      };
    },

    /** 是否已关闭（重连判定用）。 */
    isClosed: function () { return closed; },
  };
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

module.exports = { createRpcClient: createRpcClient };

// 中继 V2 全链对拍（node miniprogram/tests/relay-connection.test.js）——
// connectRelayDesktop（token → ws2 → auth_init 文本 → auth_ack paired →
// rpc 数据源点亮 + 设备目录接中继目录）。
const assert = require('assert');
const rpc = require('../utils/rpc-serialization.js');

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) { cases.push([name, fn]); }

function fakeSocketFactory(desktop) {
  const sockets = [];
  const factory = function (params) {
    const socket = {
      url: params.url, sent: [], messageCbs: [], closeCbs: [],
      send: function (payload) {
        socket.sent.push(payload);
        if (desktop.handle) desktop.handle(payload);
      },
      onMessage: function (cb) { socket.messageCbs.push(cb); },
      onClose: function (cb) { socket.closeCbs.push(cb); },
      close: function () {},
      deliver: function (payload) { socket.messageCbs.forEach(function (cb) { cb(payload); }); },
    };
    sockets.push(socket);
    desktop.socket = socket;
    return socket;
  };
  factory.sockets = sockets;
  return factory;
}

/** 中继桌面替身：文本 auth_init → auth_ack paired；二进制 rpc → 回执。 */
function makeDesktopHandler(desktop) {
  return function (payload) {
    if (typeof payload === 'string') {
      const init = JSON.parse(payload);
      assert.strictEqual(init.type, 'auth_init');
      assert.strictEqual(init.role, undefined, 'V2 auth_init 不带 role');
      assert.ok(Number.isInteger(init.client_ts), 'client_ts 整数秒');
      desktop.socket.deliver(JSON.stringify({
        type: 'auth_ack', pair_status: 'paired', desktop_version: '3.17.2',
      }));
      return;
    }
    const frame = rpc.readFrame(payload);
    if (!frame) return;
    const parsed = rpc.parseFramePayload(frame.payload);
    if (!parsed || parsed.header[0] !== 100) return;
    const id = parsed.header[1];
    const command = parsed.header[3];
    if (command === 'listTaskList') {
      desktop.socket.deliver(rpc.writeFrame(rpc.concat(
        rpc.serialize([100, id]),
        rpc.serialize({ items: [{ sessionId: 'relay-s1', title: '中继会话', updatedAt: 5 }] }),
      )));
    } else {
      desktop.socket.deliver(rpc.writeFrame(rpc.concat(
        rpc.serialize([100, id]), rpc.serialize({ ok: true }))));
    }
  };
}

function fakeHttpPost(deviceCatalogRows) {
  const calls = [];
  const fn = function (url, jwt, bodyObj) {
    calls.push({ url: url, jwt: jwt, bodyObj: bodyObj });
    if (url.indexOf('/api/v1/remote/token') >= 0) {
      return Promise.resolve({ status: 200, body: { code: 0, data: { token: 'one-time' } } });
    }
    if (url.indexOf('/api/v1/remote/device') >= 0) {
      return Promise.resolve({ status: 200, body: { code: 0, data: [
        { device_sid: 't_relay', device_mid: 'm_relay', online: true,
          meta: { name: 'MacBook Pro', platform: 'darwin' } },
      ].concat(deviceCatalogRows || []) } });
    }
    return Promise.resolve({ status: 404, body: {} });
  };
  fn.calls = calls;
  return fn;
}

test('connectRelayDesktop：token→ws2→auth_init→paired→sessions 点亮+设备目录', function* () {
  const desktopOptions = { desktop: {} };
  const factory = fakeSocketFactory(desktopOptions);
  desktopOptions.handle = makeDesktopHandler(desktopOptions);
  const httpPost = fakeHttpPost();
  const httpGet = function () { return Promise.resolve({ status: 200, body: {} }); };
  yield connection_module.connectRelayDesktop({
    relayLink: 'https://zcode.z.ai/remote/v4?sid=t_ABC&mid=m_456',
    jwt: 'jwt-relay',
    socketFactory: factory,
    httpPost: httpPost,
    httpGet: httpGet,
  });
  // token 请求形状
  assert.strictEqual(httpPost.calls.length, 1, 'token POST 一次');
  assert.ok(httpPost.calls[0].url.indexOf('/api/v1/remote/token') >= 0);
  assert.strictEqual(httpPost.calls[0].jwt, 'jwt-relay');
  assert.deepStrictEqual(httpPost.calls[0].bodyObj, { role: 'terminal' });
  // ws URL
  assert.ok(factory.sockets[0].url.indexOf('/api/v1/remote/ws2') >= 0, 'ws2 路径');
  assert.ok(factory.sockets[0].url.indexOf('token=one-time') >= 0, '一次性 token');
  assert.ok(factory.sockets[0].url.indexOf('mid=m_456') >= 0, 'mid');
  // store 状态（sessions 点亮——desktop 同步 deliver 回执后 .then 链走完）
  // store.refresh 异步读面——轮询到 connected（500ms 上限足够同步假件）
  const store = require('../utils/store.js');
  const deadline = Date.now() + 2000;
  let s = store.getState();
  while (s.status !== 'connected' && Date.now() < deadline) {
    yield new Promise(function (r) { setTimeout(r, 50); });
    s = store.getState();
  }
  assert.strictEqual(s.status, 'connected', 'store 点亮');
  assert.strictEqual(s.rows.length, 1);
  assert.strictEqual(s.rows[0].id, 'relay-s1');
});

test('connectRelayDesktop：JWT 缺失如实拒（云中继需要登录）', function () {
  return connection_module.connectRelayDesktop({
    relayLink: 'https://zcode.z.ai/remote/v4?sid=t_A',
    socketFactory: fakeSocketFactory({}),
    httpPost: fakeHttpPost(),
    httpGet: function () { return Promise.resolve({ status: 200, body: {} }); },
  }).then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/JWT/.test(err.message), err.message); },
  );
});

test('connectRelayDesktop：token 401 → 重新登录文案', function () {
  return connection_module.connectRelayDesktop({
    relayLink: 'https://zcode.z.ai/remote/v4?sid=t_A', jwt: 'expired',
    socketFactory: fakeSocketFactory({}),
    httpPost: function () { return Promise.resolve({ status: 401, body: {} }); },
    httpGet: function () { return Promise.resolve({ status: 200, body: {} }); },
  }).then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/重新登录/.test(err.message), err.message); },
  );
});

// connection.js 顶层依赖 store 等；这里 require 一次并复用
const connection_module = require('../utils/connection.js');

// 串行执行
(function run(i) {
  if (i >= cases.length) {
    console.log(passed + ' 项通过');
    if (failed > 0) { console.error(failed + ' 项失败'); process.exitCode = 1; }
    return;
  }
  const pair = cases[i];
  Promise.resolve()
    .then(function () {
      const result = pair[1]();
      // generator 逐步驱动（await→yield）
      if (result && typeof result.next === 'function') {
        return new Promise(function (resolve, reject) {
          (function step(state) {
            if (state.done) { resolve(state.value); return; }
            Promise.resolve(state.value).then(function (v) { step(result.next(v)); },
              function (e) { reject(e); });
          })(result.next());
        });
      }
      return result;
    })
    .then(function () { passed += 1; console.log('  ok', pair[0]); })
    .catch(function (err) { failed += 1; console.error('  FAIL', pair[0], err.message); })
    .then(function () { run(i + 1); });
})(0);

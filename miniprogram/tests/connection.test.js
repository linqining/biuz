// 连接管理器对拍（node miniprogram/tests/connection.test.js）——
// 假 socket/http 注入 + 内存版 wx storage（wx 未定义时退化）。
const assert = require('assert');
const rpc = require('../utils/rpc-serialization.js');
const connection = require('../utils/connection.js');

// 内存 wx.storage 垫片（connection.js try/catch 兜底，这里提供真实现以测持久化）
global.wx = {
  storage: {},
  setStorageSync(key, value) { this.storage[key] = value; },
  getStorageSync(key) { return this.storage[key]; },
};

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) { cases.push([name, fn]); }

function stubServerInfo() {
  return {
    serverId: 'srv-1', name: 'E2E Stub Desktop', version: '3.17.2',
    protocolVersion: 1, authRequired: false,
    workspaces: [{ path: '/Users/e2e/zcode-workspace', label: 'e2e' }],
    capabilities: { workflowRunDeltas: true },
  };
}

function fakeSocketFactory(desktop) {
  const sockets = [];
  const factory = function (params) {
    const socket = {
      url: params.url, sent: [], messageCbs: [], closeCbs: [],
      send(bytes) {
        socket.sent.push(bytes);
        desktopFrame(bytes); // 环回
      },
      onMessage(cb) { socket.messageCbs.push(cb); },
      onClose(cb) { socket.closeCbs.push(cb); },
      close() {},
      deliver(bytes) { socket.messageCbs.forEach(function (cb) { cb(bytes); }); },
    };
    sockets.push(socket);
    return socket;
  };
  factory.sockets = sockets;
  return factory;
}

function desktopFrame(bytes) {
  const frame = rpc.readFrame(bytes);
  if (!frame) return;
  const parsed = rpc.parseFramePayload(frame.payload);
  if (!parsed || parsed.header[0] !== 100) return;
  const id = parsed.header[1];
  const command = parsed.header[3];
  const socket = currentSocket;
  if (command === 'helloConversationV4') {
    socket.deliver(rpc.writeFrame(rpc.concat(rpc.serialize([100, id]), rpc.serialize({
      protocolVersion: 3, connectionId: 'c1', clientMode: 'web-remote-replayable',
      deliveryProfile: 'interactive', capabilities: { workflowRunDeltas: true },
      auth: { userId: 'user-1' },
    }))));
  } else if (command === 'initializeConversationV4') {
    socket.deliver(rpc.writeFrame(rpc.concat(rpc.serialize([100, id]), rpc.serialize({ ok: true }))));
  } else if (command === 'listTaskList') {
    socket.deliver(rpc.writeFrame(rpc.concat(rpc.serialize([100, id]), rpc.serialize({
      items: [
        {
          sessionId: 'sess-1', title: '重构会话持久层', summary: '迁移调用方',
          status: 'running', updatedAt: 1731200000000, pinned: true,
          workspacePath: '/Users/e2e/zcode-mobile', unreadCount: 2,
        },
        {
          id: 'sess-2', title: '生成周报', status: 'completed',
          updatedAt: 1731100000000,
        },
        null, // 坏行跳过
        { title: '缺 id' }, // 坏行跳过
      ],
    }))));
  } else {
    socket.deliver(rpc.writeFrame(rpc.concat(rpc.serialize([100, id]), rpc.serialize({ ok: true }))));
  }
}

let currentSocket = null;

test('connectDesktop：握手 → identity/source 注入 → refresh 拉真实行', function () {
  const desktop = {};
  const factory = fakeSocketFactory(desktop);
  factory.__defineGetter__('current', function () { return currentSocket; });
  const wrapped = function (params) {
    const socket = factory(params);
    currentSocket = socket;
    return socket;
  };
  // desktopFrame 用 currentSocket 回执
  return connection.connectDesktop({
    baseUrl: 'http://127.0.0.1:9100',
    token: '',
    socketFactory: wrapped,
    httpGet: function () {
      return Promise.resolve({ status: 200, body: stubServerInfo() });
    },
  }).then(function (result) {
    assert.strictEqual(result.serverInfo.name, 'E2E Stub Desktop');
    // store 身份与数据（store 是单例——直接断言状态）
    const store = require('../utils/store.js');
    const state = store.getState();
    assert.strictEqual(state.deviceName, 'E2E Stub Desktop', '身份注入');
    assert.strictEqual(state.accountName, 'user-1', 'hello.authUserId 注入');
    // 地址持久化（重连用）
    assert.deepStrictEqual(global.wx.storage['biuz.desktop'], { baseUrl: 'http://127.0.0.1:9100', token: '' });
    // store.refresh 是异步读面——轮询到 connected（上限 2s）
    return new Promise(function (resolve) {
      const store2 = require('../utils/store.js');
      const started = Date.now();
      (function poll() {
        const s = store2.getState();
        if (s.status !== 'loading' || Date.now() - started > 2000) { resolve(s); return; }
        setTimeout(poll, 50);
      })();
    }).then(function (s) {
      assert.strictEqual(s.status, 'connected', '异步读面完成后点亮');
      assert.strictEqual(s.rows.length, 2, '坏行跳过后 2 条');
      const first = s.rows[0];
      assert.strictEqual(first.id, 'sess-1');
      assert.strictEqual(first.isPinned, true, 'pinned→isPinned 映射');
      assert.strictEqual(first.unread, true, 'unreadCount>0→unread');
      assert.strictEqual(first.projectName, 'zcode-mobile', 'workspacePath 末段兜底');
      assert.strictEqual(first.status, 'running');
    });
  });
});

test('mapTaskRows：宽容映射与坏行跳过（纯函数）', function () {
  const rows = connection.mapTaskRows({
    items: [
      { sessionId: 'a', title: 'A', status: 'failed', updatedAt: 5 },
      { id: 'b', pinned: true, summary: '预览文本' },
      null,
      { title: '缺 id' },
      'not-an-object',
    ],
  });
  assert.strictEqual(rows.length, 2);
  assert.strictEqual(rows[0].id, 'a');
  assert.strictEqual(rows[0].status, 'failed');
  assert.strictEqual(rows[1].id, 'b');
  assert.strictEqual(rows[1].isPinned, true);
  assert.strictEqual(rows[1].summary, '预览文本');
});

test('connectDesktop：空地址如实拒', function () {
  return connection.connectDesktop({}).then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/地址/.test(err.message)); },
  );
});

// 串行执行
(function run(i) {
  if (i >= cases.length) {
    console.log(passed + ' 项通过');
    if (failed > 0) { console.error(failed + ' 项失败'); process.exitCode = 1; }
    return;
  }
  const pair = cases[i];
  Promise.resolve()
    .then(pair[1])
    .then(function () { passed += 1; console.log('  ok', pair[0]); })
    .catch(function (err) { failed += 1; console.error('  FAIL', pair[0], err.message); })
    .then(function () { run(i + 1); });
})(0);

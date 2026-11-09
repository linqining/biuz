// 连接层对拍（node miniprogram/tests/rpc-connection.test.js）——全双工环回假 socket。
// 线缆事实权威：Android ZCodeServerConnection / iOS 同源（server-info → WS → v4 握手）。
const assert = require('assert');
const rpc = require('../utils/rpc-serialization.js');
const connection = require('../utils/rpc-connection.js');

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) { cases.push([name, fn]); }

function stubServerInfo() {
  return {
    serverId: 'srv-1',
    name: 'E2E Stub Desktop',
    version: '3.17.2',
    protocolVersion: 1,
    authRequired: false,
    workspaces: [{ path: '/Users/e2e/zcode-workspace', label: 'e2e 主工作区' }],
    capabilities: { workflowRunDeltas: true, conversationV4: true },
  };
}

/** 桌面侧帧处理器：hello → 校验 clientHello → 后续命令按 onCommand 回。 */
function makeDesktopHandler(options) {
  return function (data) {
    const frame = rpc.readFrame(data instanceof Uint8Array ? data : new Uint8Array(data));
    if (!frame) return;
    const parsed = rpc.parseFramePayload(frame.payload);
    if (!parsed) return;
    const type = parsed.header[0];
    const id = parsed.header[1];
    const command = parsed.header[3];
    if (type !== 100) return;
    if (command === 'helloConversationV4') {
      options.helloCount = (options.helloCount || 0) + 1;
      reply(socketRefFor(options), id, {
        protocolVersion: options.helloProtocolVersion || 3,
        connectionId: 'conn-1',
        clientMode: 'web-remote-replayable',
        deliveryProfile: 'interactive',
        capabilities: options.capabilities || { workflowRunDeltas: true },
      });
      return;
    }
    if (command === 'initializeConversationV4') {
      options.clientHello = parsed.body;
      reply(socketRefFor(options), id, { ok: true });
      return;
    }
    const handler = options.onCommand ? options.onCommand(command, parsed.body) : undefined;
    reply(socketRefFor(options), id, handler === undefined ? { ok: true } : handler);
  };
}

// 桌面→手机方向需要引用 socket（工厂创建后回填）
const socketByOptions = new Map();
function socketRefFor(options) { return socketByOptions.get(options); }
function reply(socket, id, value) {
  socket.deliver(rpc.writeFrame(rpc.concat(rpc.serialize([100, id]), rpc.serialize(value))));
}

/** 假 socket 工厂：send 环回给桌面处理器（全双工），桌面回复 deliver 给 client。 */
function fakeSocketFactory(options) {
  const opts = options || {};
  const desktopHandler = makeDesktopHandler(opts);
  const sockets = [];
  const factory = function (params) {
    const socket = {
      url: params.url,
      sent: [],
      messageCbs: [],
      closeCbs: [],
      send: function (bytes) {
        socket.sent.push(bytes);
        desktopHandler(bytes); // 环回：client 出帧喂桌面
      },
      onMessage: function (cb) { socket.messageCbs.push(cb); },
      onClose: function (cb) { socket.closeCbs.push(cb); },
      close: function () {},
      deliver: function (bytes) { socket.messageCbs.forEach(function (cb) { cb(bytes); }); },
    };
    socketByOptions.set(opts, socket);
    sockets.push(socket);
    return socket;
  };
  factory.sockets = sockets;
  return factory;
}

test('connect：server-info → WS → v4 握手全链（A5 单向 capabilities 回显）', function () {
  const desktop = {};
  const factory = fakeSocketFactory(desktop);
  const httpGets = [];
  return connection.connect({
    baseUrl: 'http://192.168.1.9:9100',
    token: 'tok en+1',
    appVersion: '1.0.0',
    socketFactory: factory,
    httpGet: function (url) {
      httpGets.push(url);
      return Promise.resolve({ status: 200, body: stubServerInfo() });
    },
  }).then(function (result) {
    assert.strictEqual(
      httpGets[0],
      'http://192.168.1.9:9100/api/server-info?token=tok%20en%2B1',
      'token 应编码进查询串',
    );
    assert.strictEqual(
      factory.sockets[0].url,
      'ws://192.168.1.9:9100/ws?token=tok%20en%2B1',
      'WS URL：http→ws + /ws?token',
    );
    const helloFrame = rpc.parseFramePayload(factory.sockets[0].sent[0].subarray(13));
    assert.deepStrictEqual(helloFrame.header, [100, 1, 'zcode-agent', 'helloConversationV4']);
    const clientHello = desktop.clientHello;
    assert.ok(Array.isArray(clientHello) && clientHello[0], 'clientHello 应为单参位置数组');
    const hello = clientHello[0];
    assert.strictEqual(hello.kind, 'clientHello');
    assert.strictEqual(hello.protocolVersion, 3);
    assert.strictEqual(hello.clientKind, 'mobileApp');
    assert.ok(/^zcode-mobile-[a-z0-9]{8}$/.test(hello.clientId), 'clientId 形态');
    assert.deepStrictEqual(hello.capabilities, { workflowRunDeltas: true }, 'A5：Host 宣告 → 回显');
    assert.strictEqual(result.workspace.path, '/Users/e2e/zcode-workspace');
    assert.strictEqual(result.serverInfo.version, '3.17.2');
  });
});

test('connect：Host 未宣告 workflowRunDeltas → clientHello 不带 capabilities（A5 单向）', function () {
  const desktop = { capabilities: {} }; // Host hello 未宣告 workflowRunDeltas
  const factory = fakeSocketFactory(desktop);
  const info = stubServerInfo();
  info.capabilities = { conversationV4: true };
  return connection.connect({
    baseUrl: 'http://h:1',
    socketFactory: factory,
    httpGet: function () { return Promise.resolve({ status: 200, body: info }); },
  }).then(function () {
    assert.strictEqual(
      desktop.clientHello[0].capabilities, undefined,
      '未宣告的键不得宣告',
    );
  });
});

test('connect：server-info 协议版本不符如实拒（v2）', function () {
  const factory = fakeSocketFactory({});
  const badInfo = stubServerInfo();
  badInfo.protocolVersion = 2;
  return connection.connect({
    baseUrl: 'http://h:1', socketFactory: factory,
    httpGet: function () { return Promise.resolve({ status: 200, body: badInfo }); },
  }).then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/remote v2/.test(err.message), err.message); },
  );
});

test('connect：wire 协议版本不符如实拒（hello v4）', function () {
  const desktop = { helloProtocolVersion: 4 };
  const factory = fakeSocketFactory(desktop);
  return connection.connect({
    baseUrl: 'http://h:2', socketFactory: factory,
    httpGet: function () { return Promise.resolve({ status: 200, body: stubServerInfo() }); },
  }).then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/v4 wire v4/.test(err.message), err.message); },
  );
});

test('connect：HTTP 401 如实拒（token 错误）', function () {
  const factory = fakeSocketFactory({});
  return connection.connect({
    baseUrl: 'http://h:1', socketFactory: factory,
    httpGet: function () { return Promise.resolve({ status: 401, body: {} }); },
  }).then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/401/.test(err.message)); },
  );
});

test('connect：握手后 call 可用（listTaskList 读面形）', function () {
  const desktop = {
    onCommand: function (command) {
      if (command === 'listTaskList') {
        return { items: [{ sessionId: 'sess-1', title: '白板' }] };
      }
      return undefined;
    },
  };
  const factory = fakeSocketFactory(desktop);
  return connection.connect({
    baseUrl: 'http://h:1', socketFactory: factory,
    httpGet: function () { return Promise.resolve({ status: 200, body: stubServerInfo() }); },
  }).then(function (result) {
    return result.client.call('zcode-task', 'listTaskList', [{ scope: 'all' }]);
  }).then(function (value) {
    assert.deepStrictEqual(value, { items: [{ sessionId: 'sess-1', title: '白板' }] });
  });
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

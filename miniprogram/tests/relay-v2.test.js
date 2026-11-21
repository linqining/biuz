// 中继 V2 对拍（node miniprogram/tests/relay-v2.test.js）——Android RelayV2.kt +
// ConnectURLParser.parseRelayLink 直译锁定面（出处行号见 utils/relay-v2.js 头注）。
const assert = require('assert');
const relay = require('../utils/relay-v2.js');
const rpc = require('../utils/rpc-serialization.js');

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) { cases.push([name, fn]); }

test('parseRelayLink：V2 链接（无 hash）全字段', function () {
  const link = relay.parseRelayLink(
    'https://zcode.z.ai/remote/v4?sid=t_ABC123&mid=m_456&name=MacBook%20Pro&app_version=3.17.2');
  assert.ok(link, '应解析成功');
  assert.strictEqual(link.deviceSid, 't_ABC123');
  assert.strictEqual(link.deviceMid, 'm_456');
  assert.strictEqual(link.machineName, 'MacBook Pro');
  assert.strictEqual(link.desktopAppVersion, '3.17.2');
  assert.strictEqual(link.protocolVersion, 2);
  assert.strictEqual(link.wssUrl, 'wss://zcode.z.ai/api/v1/remote/ws2');
  assert.strictEqual(link.httpOrigin, 'https://zcode.z.ai');
});

test('parseRelayLink：V1 链接（有 hash）拒绝（本传输只走 V2）', function () {
  const link = relay.parseRelayLink(
    'https://zcode.z.ai/remote/v4?sid=t_A&hash=abc123');
  assert.strictEqual(link, null, 'V1 hash 形态应返回 null');
});

test('parseRelayLink：带 token= 查询项拒绝（一次性 token 不进链接）', function () {
  const link = relay.parseRelayLink(
    'https://zcode.z.ai/remote/v4?sid=t_A&token=leak');
  assert.strictEqual(link, null);
});

test('parseRelayLink：非 https / 非 /remote/ 路径 / 缺 sid / 空串拒绝', function () {
  assert.strictEqual(relay.parseRelayLink('http://x/remote/v4?sid=a'), null, '非 https');
  assert.strictEqual(relay.parseRelayLink('https://x/other?sid=a'), null, '非 /remote/');
  assert.strictEqual(relay.parseRelayLink('https://x/remote/v4?mid=m'), null, '缺 sid');
  assert.strictEqual(relay.parseRelayLink(''), null, '空串');
});

test('buildSocketUrl：替换 token/mid 查询项 + 保留其余', function () {
  const url = relay.buildSocketUrl(
    'wss://zcode.z.ai/api/v1/remote/ws2?mid=m_456&foo=bar',
    'one-time-token', 'm_456');
  assert.ok(url.indexOf('token=one-time-token') >= 0, '应含新 token');
  assert.ok(url.indexOf('mid=m_456') >= 0, '应含 mid');
  assert.ok(url.indexOf('foo=bar') >= 0, '应保留无关查询项');
  assert.strictEqual(url.indexOf('token='), url.lastIndexOf('token='), 'token 不重复');
});

test('buildAuthInitText：无 role + meta 身份 + client_ts 秒（V2 严格拒毫秒）', function () {
  const config = relay.parseRelayLink('https://zcode.z.ai/remote/v4?sid=t_ABC');
  const text = relay.buildAuthInitText(config, 1731200000, 'miniprogram', '1.0', '朱批');
  const obj = JSON.parse(text);
  assert.strictEqual(obj.type, 'auth_init');
  assert.strictEqual(obj.device_sid, 't_ABC');
  assert.strictEqual(obj.role, undefined, 'V2 auth_init 不带 role');
  assert.strictEqual(obj.client_ts, 1731200000, 'client_ts 为秒');
  assert.deepStrictEqual(obj.meta, { platform: 'miniprogram', version: '1.0', name: '朱批' });
});

test('parseTokenResponse：{code:0,data.token} 提取；code!=0/坏体 null', function () {
  assert.strictEqual(
    relay.parseTokenResponse('{"code":0,"data":{"token":"tok-1"}}'), 'tok-1');
  assert.strictEqual(relay.parseTokenResponse('{"code":401,"data":{}}'), null);
  assert.strictEqual(relay.parseTokenResponse('not-json'), null);
});

test('端到端（假 ws + 假 http）：token → ws2 → auth_init 文本 → auth_ack → rpc 可用', function () {
  const httpPostCalls = [];
  const httpPost = function (url, jwt, bodyObj) {
    httpPostCalls.push({ url: url, jwt: jwt, bodyObj: bodyObj });
    if (url.indexOf('/api/v1/remote/token') >= 0) {
      assert.strictEqual(jwt, 'jwt-test', 'Bearer JWT 透传');
      assert.deepStrictEqual(bodyObj, { role: 'terminal' }, 'role=terminal');
      return Promise.resolve({ status: 200, body: { code: 0, data: { token: 'one-time' } } });
    }
    return Promise.resolve({ status: 404, body: {} });
  };
  const sockets = [];
  const socketFactory = function (params) {
    const socket = {
      url: params.url, sent: [], messageCbs: [], closeCbs: [],
      send: function (payload) {
        socket.sent.push(payload);
        // 替身桌面：文本 auth_init → 回 auth_ack paired；二进制 rpc → 回执
        if (typeof payload === 'string') {
          const init = JSON.parse(payload);
          assert.strictEqual(init.type, 'auth_init');
          socket.deliver(JSON.stringify({
            type: 'auth_ack', pair_status: 'paired',
            desktop_version: '3.17.2',
          }));
        } else {
          const frame = rpc.readFrame(payload);
          if (!frame) return;
          const parsed = rpc.parseFramePayload(frame.payload);
          if (parsed && parsed.header[0] === 100) {
            socket.deliver(rpc.writeFrame(rpc.concat(
              rpc.serialize([100, parsed.header[1]]),
              rpc.serialize({ items: [{ sessionId: 'relay-1', title: '中继会话' }] }),
            )));
          }
        }
      },
      onMessage: function (cb) { socket.messageCbs.push(cb); },
      onClose: function (cb) { socket.closeCbs.push(cb); },
      close: function () {},
      deliver: function (payload) { socket.messageCbs.forEach(function (cb) { cb(payload); }); },
    };
    sockets.push(socket);
    return socket;
  };
  const link = relay.parseRelayLink('https://zcode.z.ai/remote/v4?sid=t_ABC&mid=m_456');
  return relay.connectRelayV2({
    relayLink: link,
    jwt: 'jwt-test',
    platform: 'miniprogram',
    socketFactory: socketFactory,
    httpPost: httpPost,
  }).then(function (result) {
    assert.strictEqual(result.config.deviceSid, 't_ABC');
    // ws2 建连 URL 含一次性 token
    assert.ok(sockets[0].url.indexOf('token=one-time') >= 0, 'ws URL 应携一次性 token');
    // auth_init 文本帧已发
    const initText = sockets[0].sent.find(function (p) { return typeof p === 'string'; });
    assert.ok(initText && JSON.parse(initText).type === 'auth_init', 'auth_init 文本帧已发');
    // 桥内 RPC（二进制 rpc-frame）
    const rpcClient = require('../utils/rpc-client.js');
    const rpcSocket = rpcClient.createRpcClient({
      send: function (bytes) { sockets[0].send(bytes); },
      onMessage: function (cb) { sockets[0].onMessage(function (d) { if (typeof d !== 'string') cb(d); }); },
      onClose: function () {},
    });
    const promise = rpcSocket.call('zcode-task', 'listTaskList', [{ scope: 'all' }]);
    // 喂回执（假桌面已回——上面 deliver 已触发？这里手动再喂）
    sockets[0].deliver(rpc.writeFrame(rpc.concat(
      rpc.serialize([100, 2]),
      rpc.serialize({ items: [{ sessionId: 'relay-1' }] }),
    )));
    return promise.then(function (value) {
      assert.strictEqual(value.items[0].sessionId, 'relay-1');
      assert.strictEqual(httpPostCalls.length, 1);
    });
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

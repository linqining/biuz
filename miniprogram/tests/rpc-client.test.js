// RPC 客户端对拍（node miniprogram/tests/rpc-client.test.js）——注入式假 socket。
const assert = require('assert');
const rpc = require('../utils/rpc-serialization.js');
const clientFactory = require('../utils/rpc-client.js');

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) {
  cases.push([name, fn]);
}

/** 内存假 socket：send 记录帧；测试用 deliver() 注入服务端回执。 */
function fakeSocket() {
  const sent = [];
  const messageCbs = [];
  const closeCbs = [];
  return {
    sent: sent,
    send: function (bytes) { sent.push(bytes); },
    onMessage: function (cb) { messageCbs.push(cb); },
    onClose: function (cb) { closeCbs.push(cb); },
    deliver: function (bytes) { messageCbs.forEach(function (cb) { cb(bytes); }); },
    closeFromServer: function () { closeCbs.forEach(function (cb) { cb(); }); },
  };
}

test('call：请求帧 = [100, id, channel, command] + args 数组；回执 resolve', function () {
  const socket = fakeSocket();
  const client = clientFactory.createRpcClient(socket);
  const promise = client.call('project', 'readTree', [{ includeDeleted: true }]);
  assert.strictEqual(socket.sent.length, 1, '应发出一帧');
  const parsed = rpc.parseFramePayload(socket.sent[0].subarray(13));
  assert.deepStrictEqual(parsed.header, [100, 1, 'project', 'readTree']);
  assert.deepStrictEqual(parsed.body, [{ includeDeleted: true }]);
  const reply = rpc.writeFrame(rpc.concat(rpc.serialize([100, 1]), rpc.serialize({ projects: [] })));
  socket.deliver(reply);
  return promise.then(function (value) {
    assert.deepStrictEqual(value, { projects: [] });
  });
});

test('call：迟到响应静默丢弃（不误投后继请求）', function () {
  const socket = fakeSocket();
  const client = clientFactory.createRpcClient(socket, { timeoutMs: 50 });
  const p1 = client.call('zcode-agent', 'slow');
  return p1.then(
    function () { throw new Error('应超时'); },
    function (err) {
      assert.ok(/超时/.test(err.message), '应超时拒绝');
      const late = rpc.writeFrame(rpc.concat(rpc.serialize([100, 1]), rpc.serialize('late')));
      socket.deliver(late);
      const p2 = client.call('zcode-agent', 'next');
      const reply = rpc.writeFrame(rpc.concat(rpc.serialize([100, 2]), rpc.serialize('ok2')));
      socket.deliver(reply);
      return p2.then(function (value) {
        assert.strictEqual(value, 'ok2', '新请求不受迟到响应干扰');
      });
    },
  );
});

test('call：socket 关闭 reject 所有 pending', function () {
  const socket = fakeSocket();
  const client = clientFactory.createRpcClient(socket);
  const p1 = client.call('a', 'b');
  const p2 = client.call('a', 'c');
  socket.closeFromServer();
  return Promise.all([
    p1.then(function () { throw new Error('应 reject'); }, function (e) { return e; }),
    p2.then(function () { throw new Error('应 reject'); }, function (e) { return e; }),
  ]).then(function (errors) {
    assert.ok(errors.every(function (e) { return /已关闭/.test(e.message); }));
    assert.strictEqual(client.isClosed(), true);
  });
});

test('listen：eventListen 帧 + 事件按 (channel,event) 分发；退订后不再投递', function () {
  const socket = fakeSocket();
  const client = clientFactory.createRpcClient(socket);
  const received = [];
  const unsubscribe = client.listen('zcode-agent', 'session.upserted', [], function (body) {
    received.push(body);
  });
  const reg = rpc.parseFramePayload(socket.sent[socket.sent.length - 1].subarray(13));
  assert.strictEqual(reg.header[0], 102);
  assert.strictEqual(reg.header[2], 'zcode-agent');
  assert.strictEqual(reg.header[3], 'session.upserted');
  const event = rpc.writeFrame(
    rpc.concat(rpc.serialize([102, 2, 'zcode-agent', 'session.upserted']), rpc.serialize({ sessionId: 's1' })),
  );
  socket.deliver(event);
  assert.strictEqual(received.length, 1);
  assert.deepStrictEqual(received[0], { sessionId: 's1' });
  unsubscribe();
  socket.deliver(event);
  assert.strictEqual(received.length, 1, '退订后不再投递');
});

test('黏包/分片：两帧粘连一次投递仍分别解析', function () {
  const socket = fakeSocket();
  const client = clientFactory.createRpcClient(socket);
  const p1 = client.call('a', 'one');
  const p2 = client.call('a', 'two');
  const reply1 = rpc.writeFrame(rpc.concat(rpc.serialize([100, 1]), rpc.serialize('r1')));
  const reply2 = rpc.writeFrame(rpc.concat(rpc.serialize([100, 2]), rpc.serialize('r2')));
  socket.deliver(rpc.concat(reply1, reply2));
  return Promise.all([p1, p2]).then(function (values) {
    assert.deepStrictEqual(values, ['r1', 'r2']);
  });
});

// 串行执行
(function run(i) {
  if (i >= cases.length) {
    console.log(passed + ' 项通过');
    if (failed > 0) {
      console.error(failed + ' 项失败');
      process.exitCode = 1;
    }
    return;
  }
  const pair = cases[i];
  Promise.resolve()
    .then(pair[1])
    .then(function () {
      passed += 1;
      console.log('  ok', pair[0]);
    })
    .catch(function (err) {
      failed += 1;
      console.error('  FAIL', pair[0], err.message);
    })
    .then(function () { run(i + 1); });
})(0);

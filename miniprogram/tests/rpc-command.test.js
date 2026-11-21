// rpc-command 单测 —— 信封构造纪律（A1-A3【实证】协议文档 §7.1）与 ACK 判定（A13）。
// 运行：node miniprogram/tests/rpc-command.test.js（自包含 harness 同其它套件）。

const rpcCommand = require('../utils/rpc-command.js');

function test(name, fn) {
  try {
    fn();
    console.log('  ok ' + name);
  } catch (err) {
    console.error('  FAIL ' + name + ' → ' + (err && err.message));
    process.exitCode = 1;
  }
}

function assertEq(actual, expected, msg) {
  if (actual !== expected) {
    throw new Error((msg || 'assertEq') + '：actual=' + JSON.stringify(actual) + ' expected=' + JSON.stringify(expected));
  }
}

test('makeSendConversationArgs：嵌套 envelope + 外层 workspacePath（A1）', function () {
  const args = rpcCommand.makeSendConversationArgs({
    clientId: 'zcode-mobile-abcd1234',
    type: 'resolveInteraction',
    payload: { interactionId: 'i-1', answer: { optionId: 'allowOnce' } },
    sessionId: 'sess-1',
    workspacePath: '/tmp/ws',
    issuedAtMs: 1759650000000,
    commandId: 'cmd-fixed0000000000',
  });
  if (!Array.isArray(args) || args.length !== 1) throw new Error('args 应为单元素数组');
  const arg = args[0];
  if (arg.workspacePath !== '/tmp/ws') throw new Error('外层 workspacePath 缺失（A1 嵌套纪律）');
  if (!arg.envelope || typeof arg.envelope !== 'object') throw new Error('必须嵌套 envelope:{…}');
  if (arg.envelope.commandId !== 'cmd-fixed0000000000') throw new Error('commandId 回传丢失');
  if (arg.envelope.clientId !== 'zcode-mobile-abcd1234') throw new Error('A2：clientId 必须等于握手注册值');
  if (arg.envelope.type !== 'resolveInteraction') throw new Error('type 丢失');
  const payload = arg.envelope.payload;
  if (!payload || payload.interactionId !== 'i-1') throw new Error('A3：payload {interactionId, answer} 两键');
  if (!payload.answer || payload.answer.optionId !== 'allowOnce') throw new Error('answer 恒为对象（三族分形）');
  return arg;
});

const arg = (function () {
  const args = rpcCommand.makeSendConversationArgs({
    clientId: 'zcode-mobile-abcd1234',
    type: 'resolveInteraction',
    payload: { interactionId: 'i-1', answer: { optionId: 'allowOnce' } },
    sessionId: 'sess-1',
    workspacePath: '/tmp/ws',
    issuedAtMs: 1759650000000,
    commandId: 'cmd-fixed0000000000',
  });
  return args[0];
})();

test('A1\'：envelope.sessionId 键恒在场且为字符串（缺省键被拒 proto.invalidPayload）', function () {
  if (!('sessionId' in arg.envelope)) throw new Error('sessionId 键必须恒在场');
  assertEq(arg.envelope.sessionId, 'sess-1');
});

test('issuedAt = epoch 毫秒 int（ISO 字符串被拒）', function () {
  assertEq(arg.envelope.issuedAt, 1759650000000);
  if (!Number.isInteger(arg.envelope.issuedAt)) throw new Error('issuedAt 必须是 int');
});

test('issuedAt 缺省 = Date.now() 毫秒（调用方不传时）', function () {
  const args = rpcCommand.makeSendConversationArgs({
    clientId: 'c', type: 't', payload: {}, sessionId: 's', workspacePath: 'w',
  });
  const now = Date.now();
  if (Math.abs(args[0].envelope.issuedAt - now) > 5000) throw new Error('issuedAt 应为当前毫秒');
});

test('commandId 缺省 = 自动生成且两次调用不同（唯一性）', function () {
  const a = rpcCommand.makeSendConversationArgs({ clientId: 'c', type: 't', payload: {}, sessionId: 's', workspacePath: 'w' });
  const b = rpcCommand.makeSendConversationArgs({ clientId: 'c', type: 't', payload: {}, sessionId: 's', workspacePath: 'w' });
  if (!a[0].envelope.commandId || a[0].envelope.commandId === b[0].envelope.commandId) {
    throw new Error('commandId 应自动生成且唯一');
  }
});

test('ackStatus：顶层与 ack 包裹双形态宽容（iOS CommandAck 同口径）', function () {
  assertEq(rpcCommand.ackStatus({ status: 'accepted' }), 'accepted');
  assertEq(rpcCommand.ackStatus({ ack: { status: 'duplicate' } }), 'duplicate');
  assertEq(rpcCommand.ackStatus(null), null);
  assertEq(rpcCommand.ackStatus('nonsense'), null);
});

test('A13 isFailureStatus：词表内非 accepted/duplicate/noop = 失败', function () {
  assertEq(rpcCommand.isFailureStatus('accepted'), false);
  assertEq(rpcCommand.isFailureStatus('duplicate'), false);
  assertEq(rpcCommand.isFailureStatus('noop'), false);
  assertEq(rpcCommand.isFailureStatus('rejected'), true);
  assertEq(rpcCommand.isFailureStatus('stale'), true);
  assertEq(rpcCommand.isFailureStatus('failed'), true);
});

test('未知态（status 缺席/词表外）= 非失败（读面宽容；落印门槛由 isAuthoritativeSuccess 把守）', function () {
  assertEq(rpcCommand.isFailureStatus(null), false);
  assertEq(rpcCommand.isFailureStatus('applied'), false); // 词表外旧通道回执
  assertEq(rpcCommand.isAuthoritativeSuccess(null), false);
  assertEq(rpcCommand.isAuthoritativeSuccess('applied'), false);
  assertEq(rpcCommand.isAuthoritativeSuccess('accepted'), true);
  assertEq(rpcCommand.isAuthoritativeSuccess('duplicate'), true);
  assertEq(rpcCommand.isAuthoritativeSuccess('noop'), true);
  assertEq(rpcCommand.isAuthoritativeSuccess('rejected'), false);
});

if (process.exitCode === 1) {
  console.error('rpc-command 套件存在失败用例');
} else {
  console.log('rpc-command 全部通过');
}

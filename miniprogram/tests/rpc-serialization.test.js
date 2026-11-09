// RPC 序列化对拍（node miniprogram/tests/rpc-serialization.test.js）
// 线缆口径权威：web packages/rpc/src/serialization.ts + RPCFrame（13 字节头）；
// 与 iOS RPCSerialization / Android RPCSerialization 同源第三实现——
// 已知向量来自真机实测（readTree 回执、deleteProject 位置数组、hello 帧）。
const assert = require('assert');
const rpc = require('../utils/rpc-serialization.js');

let passed = 0;
function test(name, fn) {
  try {
    fn();
    passed += 1;
    console.log('  ok', name);
  } catch (err) {
    console.error('  FAIL', name, err.message);
    process.exitCode = 1;
  }
}

test('VQL：0/127/128/16384 逐位对拍', function () {
  const cases = [
    [0, [0x00]],
    [127, [0x7f]],
    [128, [0x80, 0x01]],
    [16384, [0x80, 0x80, 0x01]],
  ];
  cases.forEach(function (pair) {
    const out = rpc.serialize(pair[0]);
    assert.deepStrictEqual(Array.from(out), [6].concat(pair[1]), 'int ' + pair[0]);
    assert.strictEqual(rpc.deserialize(out), pair[0]);
  });
});

test('undefined/字符串/布尔/null/对象 往返', function () {
  assert.strictEqual(rpc.deserialize(rpc.serialize(undefined)), undefined);
  const obj = { a: '文本', b: 12, c: true, d: null, e: { f: [1, 2] } };
  assert.deepStrictEqual(rpc.deserialize(rpc.serialize(obj)), obj);
  assert.strictEqual(rpc.deserialize(rpc.serialize('中文 utf8 ✓')), '中文 utf8 ✓');
});

test('数组往返（含嵌套）', function () {
  const value = ['x', 3, undefined, [true, null]];
  assert.deepStrictEqual(rpc.deserialize(rpc.serialize(value)), value);
});

test('已知向量：hello 请求帧（[100, 1, "zcode-agent", "helloConversationV4"], undefined）', function () {
  const frame = rpc.buildRequestFrame(1, 'zcode-agent', 'helloConversationV4', undefined);
  // 13 字节头 + payload
  assert.strictEqual(frame[0], 1, 'Regular 帧类型');
  const view = new DataView(frame.buffer);
  assert.strictEqual(view.getUint32(9), frame.length - 13, '帧长字段与实际一致');
  const parsed = rpc.parseFramePayload(frame.subarray(13));
  assert.deepStrictEqual(parsed.header, [100, 1, 'zcode-agent', 'helloConversationV4']);
  assert.strictEqual(parsed.body, undefined);
});

test('已知向量：多参位置数组（deleteProject(id, hostId)）', function () {
  const frame = rpc.buildRequestFrame(
    7,
    'project',
    'deleteProject',
    ['proj-9', 'local'],
  );
  const parsed = rpc.parseFramePayload(frame.subarray(13));
  assert.deepStrictEqual(parsed.body, ['proj-9', 'local'], 'body = 位置参数数组（web toService 同形）');
});

test('已知向量：readTree 单对象参数', function () {
  const frame = rpc.buildRequestFrame(
    8,
    'project',
    'readTree',
    [{ includeDeleted: true, includeDeletedTasks: true }],
  );
  const parsed = rpc.parseFramePayload(frame.subarray(13));
  assert.deepStrictEqual(parsed.body, [{ includeDeleted: true, includeDeletedTasks: true }]);
});

test('readFrame：不足头长/类型不符/长度不符 → null', function () {
  assert.strictEqual(rpc.readFrame(new Uint8Array(5)), null);
  const bad = new Uint8Array(20);
  bad[0] = 2; // 非 Regular
  assert.strictEqual(rpc.readFrame(bad), null);
  const short = new Uint8Array(20);
  short[0] = 1;
  new DataView(short.buffer).setUint32(9, 99);
  assert.strictEqual(rpc.readFrame(short), null);
});

test('readFrame：合法帧解析出 payload', function () {
  const payload = rpc.serialize(['answer', 42]);
  const frame = rpc.writeFrame(payload);
  const read = rpc.readFrame(frame);
  assert.ok(read, '应解析出帧');
  assert.deepStrictEqual(rpc.deserialize(read.payload), ['answer', 42]);
});

test('嵌套 Uint8Array 标记往返（web 同口径）', function () {
  const payload = new Uint8Array([1, 2, 3, 250]);
  const obj = { data: payload };
  const restored = rpc.deserialize(rpc.serialize(obj));
  assert.ok(restored.data instanceof Uint8Array, '应恢复为 Uint8Array');
  assert.deepStrictEqual(Array.from(restored.data), [1, 2, 3, 250]);
});

test('eventListen 帧头', function () {
  const frame = rpc.buildEventListenFrame(9, 'zcode-agent', 'session.upserted', undefined);
  const parsed = rpc.parseFramePayload(frame.subarray(13));
  assert.strictEqual(parsed.header[0], 102, 'EventListen');
  assert.strictEqual(parsed.header[2], 'zcode-agent');
});

test('base64 往返（非 ASCII 安全）', function () {
  const bytes = new Uint8Array([0, 1, 250, 251, 128, 77]);
  const decoded = rpc.base64Decode(rpc.base64Encode(bytes));
  assert.deepStrictEqual(Array.from(decoded), Array.from(bytes));
});

console.log(passed + ' 项通过');
if (process.exitCode) {
  console.error('存在失败项');
}

// 003-设备列表 纯逻辑对拍（node miniprogram/tests/device-model.test.js）
// 金标口径来自 web 源码 webRemoteControlDeviceListPage.tsx:94-130（分组/描述/重名消歧）。
const assert = require('assert');
const model = require('../utils/device-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

function device(mid, name, platform, connections) {
  return { deviceMid: mid, name: name, platform: platform, onlineConnections: connections || [] };
}

const conn = (sid, version) => ({ device_sid: sid, meta: { version: version } });

test('在线/离线分组：在线恒在前，空组不渲染', () => {
  const sections = model.buildSections([
    device('m1', 'MacBook-Pro', 'darwin', [conn('s1', '3.17.2')]),
    device('m2', 'OldPC', 'win32', []),
  ]);
  assert.deepStrictEqual(sections.map((s) => s.label), ['在线', '离线']);
  assert.strictEqual(sections[0].count, 1);
  assert.strictEqual(sections[1].count, 1);
});

test('只有在线设备时不渲染离线分区', () => {
  const sections = model.buildSections([device('m1', 'Mac', 'darwin', [conn('s1', '3.17.2')])]);
  assert.deepStrictEqual(sections.map((s) => s.label), ['在线']);
});

test('描述行：在线显示 平台 · ZCode 版本', () => {
  const d = device('m1', 'Mac', 'darwin', [conn('s1', '3.17.2')]);
  assert.strictEqual(model.describeDevice(d, false), 'macOS · ZCode 3.17.2');
});

test('描述行：离线显示 平台 · 离线', () => {
  assert.strictEqual(model.describeDevice(device('m2', 'PC', 'win32', []), false), 'Windows · 离线');
});

test('描述行：同组重名才追加 ID 前 8 位', () => {
  const sections = model.buildSections([
    device('abcdef123456', '同名', 'darwin', [conn('s1', '3.17.2')]),
    device('ffffffff9999', '同名', 'darwin', [conn('s2', '3.17.2')]),
  ]);
  const rows = sections[0].devices;
  assert.ok(rows[0].descText.endsWith('ID abcdef12'), '重名行应带 ID 前 8 位：' + rows[0].descText);
  assert.ok(rows[1].descText.endsWith('ID ffffffff'));

  const single = model.buildSections([device('abcdef123456', '唯一', 'darwin', [conn('s1', '3.17.2')])]);
  assert.ok(!single[0].devices[0].descText.includes('ID'), '非重名不应带 ID');
});

test('未命名设备回退「未命名设备」', () => {
  const sections = model.buildSections([device('m3', '', 'linux', [conn('s9')])]);
  assert.strictEqual(sections[0].devices[0].nameText, '未命名设备');
});

test('多连接时展示「N 个连接」', () => {
  assert.strictEqual(model.connectionsText(3), '3 个连接');
  const sections = model.buildSections([
    device('m1', 'Mac', 'darwin', [conn('s1', '3.17.2'), conn('s2', '3.17.2')]),
  ]);
  assert.strictEqual(sections[0].devices[0].connectionsText, '2 个连接');
});

test('账号行文案：名字 · Provider', () => {
  assert.strictEqual(model.accountText({ displayName: '旅行者0554', provider: 'zai' }), '旅行者0554 · Z.ai');
  assert.strictEqual(model.accountText({ displayName: 'u', provider: 'bigmodel' }), 'u · BigModel');
  assert.strictEqual(model.accountText(null), '');
});

test('空白名单返回空分区（页面给空态）', () => {
  assert.deepStrictEqual(model.buildSections([]), []);
  assert.deepStrictEqual(model.buildSections(null), []);
});

console.log('\n' + passed + ' 项通过');

/* ---- 006 多实例选择 ---- */

test('多实例：同组在线 >1 才需要选择弹层', () => {
  assert.strictEqual(model.needsConnectionSheet(device('m1', 'Mac', 'darwin', [conn('s1'), conn('s2')])), true);
  assert.strictEqual(model.needsConnectionSheet(device('m1', 'Mac', 'darwin', [conn('s1')])), false);
  assert.strictEqual(model.needsConnectionSheet(device('m1', 'Mac', 'darwin', [])), false);
});

test('多实例行：版本 + 启动时间文案', () => {
  const now = 1_700_000_000_000;
  const d = device('m1', 'Mac', 'darwin', [
    { device_sid: 's1', meta: { version: '3.17.2', createdAt: now / 1000 - 180 } },
    { device_sid: 's2', meta: { version: '3.17.1', createdAt: now / 1000 - 7200 } },
  ]);
  const rows = model.connectionRows(d, now);
  assert.strictEqual(rows.length, 2);
  assert.strictEqual(rows[0].title, 'ZCode 3.17.2');
  assert.strictEqual(rows[0].subtitle, '3 分钟前启动');
  assert.strictEqual(rows[1].subtitle, '2 小时前启动');
  assert.strictEqual(rows[0].sid, 's1');
});

test('相对时间口径与 web numeric:auto 对齐', () => {
  const now = 1_700_000_000_000;
  assert.strictEqual(model.formatStartedAgo(now / 1000, now), '刚刚');
  assert.strictEqual(model.formatStartedAgo(now / 1000 - 90, now), '2 分钟前');
  assert.strictEqual(model.formatStartedAgo(now / 1000 - 3 * 3600, now), '3 小时前');
  assert.strictEqual(model.formatStartedAgo(now / 1000 - 5 * 86400, now), '5 天前');
});

console.log('\n' + passed + ' 项通过（含 006）');

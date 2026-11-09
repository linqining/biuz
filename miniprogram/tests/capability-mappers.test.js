// 能力清单读面对拍（node miniprogram/tests/capability-mappers.test.js）——
// 形状权威：Android RemoteCapabilities.kt 同源（MCP statuses map 为桌面 3.17.2
// 真机实测形状；数组分支为宽容兜底）。
const assert = require('assert');
const capability = require('../utils/capability.js');

/** 内存假 client：记录调用并按表回执。 */
function fakeClient(replies) {
  const calls = [];
  return {
    calls: calls,
    call: function (channel, command, args) {
      calls.push({ channel: channel, command: command, args: args });
      const handler = replies[command];
      return Promise.resolve(handler ? handler(args) : {});
    },
  };
}

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) { cases.push([name, fn]); }

test('memory：workspaces 条目映射（N 个记忆文件 · 更新于）', function () {
  const client = fakeClient({
    listProjectMemories: function () {
      return { workspaces: [
        { id: 'ws-1', label: 'zcode-mobile', updatedAt: new Date(2026, 10, 10).getTime(),
          files: ['a.md', 'b.md'] },
        { id: 'ws-2', files: [] }, // 缺 label → id 兜底（Android label ?: id 同口径）
      ] };
    },
  });
  return capability.loadCapability(client, null, 'memory').then(function (result) {
    assert.strictEqual(result.rows.length, 2);
    assert.strictEqual(result.rows[0].title, 'zcode-mobile');
    assert.ok(/2 个记忆文件 · 更新于 11月10日/.test(result.rows[0].subtitle), result.rows[0].subtitle);
    assert.strictEqual(result.rows[1].title, 'ws-2', 'label 缺席回退 id');
    assert.strictEqual(result.rows[1].subtitle, '0 个记忆文件');
    // wire 形状：memory 频道 + 无参
    assert.strictEqual(client.calls[0].channel, 'memory');
    assert.deepStrictEqual(client.calls[0].args, []);
  });
});

test('skills：skills[] 三键宽容 + enabled 徽标；workspace 信封透传', function () {
  const client = fakeClient({
    getSkillReferenceCatalog: function () {
      return { skills: [
        { name: 'visual-judge', description: '视觉判定', enabled: true },
        { title: 'web-reader', summary: '网页读取' }, // title/summary 宽容键
        { description: '缺 name 跳过' },
      ] };
    },
  });
  const workspace = { path: '/ws', workspaceIdentity: 'wid-1' };
  return capability.loadCapability(client, workspace, 'skills').then(function (result) {
    assert.strictEqual(result.rows.length, 2);
    assert.strictEqual(result.rows[0].badge, '已启用');
    assert.strictEqual(result.rows[1].title, 'web-reader');
    // workspace 信封：path + identity 都带
    assert.deepStrictEqual(client.calls[0].args[0], { workspacePath: '/ws', workspaceIdentity: 'wid-1' });
  });
});

test('mcp：statuses map（真机形状）→ plugin: 前缀名清洗 + 状态词中文化', function () {
  const client = fakeClient({
    listMcpServerStatuses: function () {
      return { statuses: {
        'plugin:documents:documents': { status: 'connected', transport: 'stdio', toolCount: 15 },
        'filesystem': { status: 'failed', transport: 'stdio', toolCount: 6 },
        'legacy': { status: 'weird-state' },
      } };
    },
  });
  return capability.loadCapability(client, { path: '/ws' }, 'mcp').then(function (result) {
    assert.strictEqual(result.rows.length, 3);
    assert.strictEqual(result.rows[0].title, 'documents', 'plugin: 前缀清洗取末段');
    assert.strictEqual(result.rows[0].subtitle, 'stdio · 15 个工具');
    assert.strictEqual(result.rows[0].badge, '已连接');
    assert.strictEqual(result.rows[1].badge, '异常');
    assert.strictEqual(result.rows[2].badge, 'weird-state', '未知状态词原样透传');
  });
});

test('mcp：数组宽容分支（servers[]）', function () {
  const client = fakeClient({
    listMcpServerStatuses: function () {
      return { servers: [{ name: 'fs', status: 'connected', command: 'node fs.js' }] };
    },
  });
  return capability.loadCapability(client, { path: '/ws' }, 'mcp').then(function (result) {
    assert.strictEqual(result.rows.length, 1);
    assert.strictEqual(result.rows[0].title, 'fs');
    assert.strictEqual(result.rows[0].subtitle, 'node fs.js');
  });
});

test('plugins：plugins[] 宽容 + version 徽标', function () {
  const client = fakeClient({
    listPlugins: function () {
      return { plugins: [
        { name: 'documents', version: '1.4.0', marketplace: 'official' },
        { pluginName: 'visualize' },
        { marketplace: '缺 name 跳过' },
      ] };
    },
  });
  return capability.loadCapability(client, { path: '/ws' }, 'plugins').then(function (result) {
    assert.strictEqual(result.rows.length, 2);
    assert.strictEqual(result.rows[0].badge, '1.4.0');
    assert.strictEqual(result.rows[1].title, 'visualize');
  });
});

test('未知 kind 如实拒；无 client 如实拒', function () {
  const client = fakeClient({});
  return capability.loadCapability(client, null, 'bogus').then(
    function () { throw new Error('应拒'); },
    function (err) { assert.ok(/未知能力类型/.test(err.message)); },
  ).then(function () {
    return capability.loadCapability(null, null, 'memory').then(
      function () { throw new Error('应拒'); },
      function (err) { assert.ok(/未连接/.test(err.message)); },
    );
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

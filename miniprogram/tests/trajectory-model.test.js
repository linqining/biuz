// 模型轨迹 纯逻辑对拍（node miniprogram/tests/trajectory-model.test.js）
const assert = require('assert');
const model = require('../utils/trajectory-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

test('未知 kind 落 reasoning，未知 status 落 done', () => {
  const entry = model.normalizeEntry({ text: '想了下' }, 0);
  assert.strictEqual(entry.kind, 'reasoning');
  assert.strictEqual(entry.status, 'done');
  assert.strictEqual(entry.label, '思考');
});

test('三种 kind 的标签与颜色令牌', () => {
  assert.strictEqual(model.normalizeEntry({ kind: 'toolCall', toolName: 'Bash' }, 0).label, '工具');
  assert.strictEqual(model.normalizeEntry({ kind: 'assistant', text: 'hi' }, 0).label, '回复');
  assert.strictEqual(
    model.normalizeEntry({ kind: 'toolCall', toolName: 'Bash' }, 0).color,
    'var(--color-info)',
  );
});

test('归一化保序，丢弃空正文非工具条', () => {
  const list = model.normalizeTrajectory([
    { kind: 'reasoning', text: '思考一' },
    { kind: 'reasoning', text: '' },
    { kind: 'toolCall', toolName: 'Read', text: '' },
    { kind: 'assistant', text: '结论' },
  ]);
  assert.deepStrictEqual(list.map((e) => e.kind), ['reasoning', 'toolCall', 'assistant']);
  assert.deepStrictEqual(list.map((e) => e.text), ['思考一', '', '结论']);
});

test('工具摘要：名称 · 状态 · 耗时', () => {
  assert.strictEqual(
    model.toolSummary({ kind: 'toolCall', toolName: 'Bash', status: 'success', durationMs: 1500 }),
    'Bash · 成功 · 1.5s',
  );
  assert.strictEqual(
    model.toolSummary({ kind: 'toolCall', toolName: 'Read', status: 'error', durationMs: 300 }),
    'Read · 失败 · 300ms',
  );
  assert.strictEqual(model.toolSummary({ kind: 'reasoning' }), '');
});

test('统计：步数与工具次数', () => {
  const list = model.normalizeTrajectory([
    { kind: 'reasoning', text: 'a' },
    { kind: 'toolCall', toolName: 'Bash' },
    { kind: 'toolCall', toolName: 'Read' },
    { kind: 'assistant', text: 'b' },
  ]);
  assert.deepStrictEqual(model.stats(list), { steps: 4, tools: 2 });
});

console.log('\n' + passed + ' 项通过');

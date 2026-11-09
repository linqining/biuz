// Diff 审查 纯逻辑对拍（node miniprogram/tests/diff-model.test.js）
const assert = require('assert');
const model = require('../utils/diff-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

test('分段标签与三端口径一致', () => {
  assert.deepStrictEqual(model.SEGMENTS.map((s) => s.label), ['未暂存', '已暂存', '本次会话']);
});

test('归一化：缺字段给默认，未知 kind 落 ctx', () => {
  const file = model.normalizeFile({ path: 'a/b.ts', lines: [{ text: 'x' }, { kind: 'weird', text: 'y' }] });
  assert.strictEqual(file.path, 'a/b.ts');
  assert.strictEqual(file.segment, 'unstaged');
  assert.deepStrictEqual(file.lines.map((l) => l.kind), ['ctx', 'ctx']);
});

test('行统计：added/removed 缺省从行算', () => {
  const file = model.normalizeFile({
    path: 'a.ts',
    lines: [
      { kind: 'hunk', text: '@@ -1,2 +1,3 @@' },
      { kind: 'del', text: '-old' },
      { kind: 'add', text: '+new1' },
      { kind: 'add', text: '+new2' },
      { kind: 'ctx', text: ' keep' },
    ],
  });
  assert.strictEqual(file.added, 2);
  assert.strictEqual(file.removed, 1);
  // 显式给出则以显式为准
  const explicit = model.normalizeFile({ path: 'b.ts', added: 9, removed: 4, lines: [] });
  assert.strictEqual(explicit.added, 9);
  assert.strictEqual(explicit.removed, 4);
});

test('分段归组：空分段不渲染，顺序固定', () => {
  const sections = model.groupBySegment([
    { path: 'staged.ts', segment: 'staged' },
    { path: 'unstaged.ts', segment: 'unstaged' },
    { path: 'session.ts', segment: 'session' },
  ]);
  assert.deepStrictEqual(sections.map((s) => s.key), ['unstaged', 'staged', 'session']);
  assert.strictEqual(sections[0].files[0].path, 'unstaged.ts');

  const onlyStaged = model.groupBySegment([{ path: 'x.ts', segment: 'staged' }]);
  assert.deepStrictEqual(onlyStaged.map((s) => s.label), ['已暂存']);
});

test('总计：文件数 + 行增删', () => {
  const total = model.totals([
    { path: 'a.ts', added: 3, removed: 1 },
    { path: 'b.ts', added: 5, removed: 2, segment: 'staged' },
  ]);
  assert.deepStrictEqual(total, { files: 2, add: 8, del: 3 });
});

test('路径末段', () => {
  assert.strictEqual(model.baseName('src/core/store.ts'), 'store.ts');
  assert.strictEqual(model.baseName('Makefile'), 'Makefile');
  assert.strictEqual(model.baseName(''), '');
});

console.log('\n' + passed + ' 项通过');

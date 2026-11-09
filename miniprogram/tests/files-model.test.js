// 文件树 纯逻辑对拍（node miniprogram/tests/files-model.test.js）
const assert = require('assert');
const model = require('../utils/files-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

const tree = [
  {
    id: 'd1', name: 'src', path: 'src', isDirectory: true, children: [
      { id: 'f1', name: 'main.ts', path: 'src/main.ts', size: 2048 },
      {
        id: 'd2', name: 'core', path: 'src/core', isDirectory: true, children: [
          { id: 'f2', name: 'store.ts', path: 'src/core/store.ts', size: 512 },
        ],
      },
    ],
  },
  { id: 'f3', name: 'README.md', path: 'README.md', size: 100 },
];

test('归一化：目录按 children 判定，缺字段给默认', () => {
  const node = model.normalizeNode({ path: 'a/b' });
  assert.strictEqual(node.name, '未命名');
  assert.strictEqual(node.isDirectory, false);
  assert.strictEqual(node.size, null);
  const dir = model.normalizeNode({ path: 'a', children: [] });
  assert.strictEqual(dir.isDirectory, true);
});

test('未展开时只渲染根层', () => {
  const rows = model.flatten(tree, {});
  assert.deepStrictEqual(rows.map((r) => r.node.path), ['src', 'README.md']);
  assert.strictEqual(rows[0].expandable, true);
  assert.strictEqual(rows[0].expanded, false);
});

test('展开一级目录下钻一层', () => {
  const rows = model.flatten(tree, { src: true });
  assert.deepStrictEqual(rows.map((r) => r.node.path), ['src', 'src/main.ts', 'src/core', 'README.md']);
  assert.deepStrictEqual(rows.map((r) => r.depth), [0, 1, 1, 0]);
});

test('嵌套展开按深度递减', () => {
  const rows = model.flatten(tree, { src: true, 'src/core': true });
  assert.deepStrictEqual(
    rows.map((r) => r.node.path),
    ['src', 'src/main.ts', 'src/core', 'src/core/store.ts', 'README.md'],
  );
  assert.deepStrictEqual(rows.map((r) => r.depth), [0, 1, 1, 2, 0]);
});

test('空目录不可展开', () => {
  const rows = model.flatten([{ path: 'empty', name: 'empty', isDirectory: true, children: [] }], { empty: true });
  assert.strictEqual(rows.length, 1);
  assert.strictEqual(rows[0].expandable, false);
  assert.strictEqual(rows[0].expanded, false);
});

test('尺寸文案：B / KB / MB，目录与未知返回空', () => {
  assert.strictEqual(model.sizeText({ size: 512, isDirectory: false }), '512 B');
  assert.strictEqual(model.sizeText({ size: 2048, isDirectory: false }), '2 KB');
  assert.strictEqual(model.sizeText({ size: 3 * 1024 * 1024, isDirectory: false }), '3.0 MB');
  assert.strictEqual(model.sizeText({ size: 100, isDirectory: true }), '');
  assert.strictEqual(model.sizeText({ size: null, isDirectory: false }), '');
});

test('语言与文件名提示', () => {
  assert.strictEqual(model.languageOf('src/main.ts'), 'typescript');
  assert.strictEqual(model.languageOf('README.md'), 'markdown');
  assert.strictEqual(model.languageOf('Makefile'), '');
  assert.strictEqual(model.baseName('src/core/store.ts'), 'store.ts');
});

console.log('\n' + passed + ' 项通过');

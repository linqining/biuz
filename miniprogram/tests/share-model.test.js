// 分享落地 纯逻辑对拍（node miniprogram/tests/share-model.test.js）
const assert = require('assert');
const model = require('../utils/share-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

test('行宽容：text / inputText / summaryText 三键任一', () => {
  const preview = model.parsePreview({
    share: { title: 'T', owner: { displayName: 'Lin' } },
    rows: [
      { kind: 'userInput', text: '问题' },
      { kind: 'assistantText', inputText: '输入型' },
      { kind: 'toolCall', summaryText: '摘要型' },
    ],
  });
  assert.deepStrictEqual(preview.rows.map((r) => r.text), ['问题', '输入型', '摘要型']);
  assert.deepStrictEqual(preview.rows.map((r) => r.isUser), [true, false, false]);
  assert.strictEqual(preview.ownerName, 'Lin');
  assert.strictEqual(preview.title, 'T');
  assert.strictEqual(preview.unsupportedRowCount, 0);
});

test('认不出的行计数，不整份失败', () => {
  const preview = model.parsePreview({
    share: {},
    rows: [null, {}, { kind: 'userInput', text: 'ok' }, 'string-row'],
  });
  assert.strictEqual(preview.rows.length, 1);
  assert.strictEqual(preview.unsupportedRowCount, 3);
  assert.strictEqual(preview.title, '');
});

test('回执非对象 = null（调用方上屏解析失败）', () => {
  assert.strictEqual(model.parsePreview(null), null);
  assert.strictEqual(model.parsePreview('oops'), null);
});

test('错误文案：401/403 与其余状态分口径', () => {
  assert.strictEqual(model.errorText(401), '该分享需要登录后查看（请求被拒绝）');
  assert.strictEqual(model.errorText(403), '该分享需要登录后查看（请求被拒绝）');
  assert.strictEqual(model.errorText(404), '分享预览获取失败（HTTP 404）· 链接可能已失效');
  assert.strictEqual(model.errorText(500), '分享预览获取失败（HTTP 500）· 链接可能已失效');
});

console.log('\n' + passed + ' 项通过');

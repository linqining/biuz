// 011-会话对话 纯逻辑对拍（node miniprogram/tests/chat-model.test.js）
// 对位：Android `ui/chat/ChatMessageViews.kt`（用户气泡/助手正文/工具卡/思考块）+ web 会话页。
const assert = require('assert');
const model = require('../utils/chat-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

test('归一化：缺字段给安全默认值，不猜角色', () => {
  const row = model.normalizeMessage({ id: 'r1' });
  assert.strictEqual(row.role, 'agent');
  assert.strictEqual(row.kind, 'text');
  assert.strictEqual(row.text, '');
  assert.strictEqual(row.streaming, false);
});

test('归一化：用户 / 工具 / 思考三种形态', () => {
  assert.strictEqual(model.normalizeMessage({ id: 'a', role: 'user' }).role, 'user');
  const tool = model.normalizeMessage({ id: 'b', kind: 'tool', toolName: 'Bash', toolStatus: 'done' });
  assert.strictEqual(tool.kind, 'tool');
  assert.strictEqual(tool.toolName, 'Bash');
  assert.strictEqual(model.normalizeMessage({ id: 'c', kind: 'thinking' }).kind, 'thinking');
});

test('块解析：代码围栏 / 标题 / 段落', () => {
  const blocks = model.parseBlocks([
    '# 结论',
    '',
    '正文第一行',
    '正文第二行',
    '',
    '```js',
    'const a = 1;',
    '```',
  ].join('\n'));
  assert.deepStrictEqual(blocks.map((b) => b.type), ['heading', 'text', 'code']);
  assert.strictEqual(blocks[0].content, '结论');
  assert.strictEqual(blocks[0].level, 1);
  assert.ok(blocks[1].content.includes('正文第一行'));
  assert.strictEqual(blocks[2].content, 'const a = 1;');
});

test('块解析：未闭合围栏按代码块渲染（流式半截代码不当正文）', () => {
  const blocks = model.parseBlocks('前言\n```py\nprint(1)');
  assert.deepStrictEqual(blocks.map((b) => b.type), ['text', 'code']);
  assert.strictEqual(blocks[1].content, 'print(1)');
});

test('块解析：空文本返回空数组', () => {
  assert.deepStrictEqual(model.parseBlocks(''), []);
  assert.deepStrictEqual(model.parseBlocks(null), []);
  assert.deepStrictEqual(model.parseBlocks('   \n  '), []);
});

test('时间口径：今天 HH:mm / 跨天 M月D日 HH:mm', () => {
  const now = new Date(2026, 10, 9, 15, 30, 0).getTime();
  const today = new Date(2026, 10, 9, 8, 5, 0).getTime();
  const other = new Date(2026, 9, 13, 7, 3, 0).getTime();
  assert.strictEqual(model.formatMessageTime(today, now), '08:05');
  assert.strictEqual(model.formatMessageTime(other, now), '10月13日 07:03');
  assert.strictEqual(model.formatMessageTime(0, now), '');
});

test('装饰：挂 blocks / 时间 / 角色名', () => {
  const msg = model.normalizeMessage({ id: 'r9', role: 'user', text: '你好', timestamp: 1 });
  const decorated = model.decorateMessage(msg, 1 + 60000, 'r9');
  assert.strictEqual(decorated.roleLabel, '你');
  assert.strictEqual(decorated.isActive, true);
  assert.deepStrictEqual(decorated.blocks, []); // 用户气泡不做块解析
});

test('顶栏标题：空标题回落「任务会话」', () => {
  assert.strictEqual(model.headerTitle({ title: '三端对比' }), '三端对比');
  assert.strictEqual(model.headerTitle({ title: '' }), '任务会话');
  assert.strictEqual(model.headerTitle(null), '任务会话');
});

console.log('\n' + passed + ' 项通过');

// 片四映射对拍（node miniprogram/tests/wave4-mappers.test.js）——
// chat/git 映射（形状权威：zcode-protocol-legacy-types.ts:458-463 strict +
// Android RemoteFileStore git.getChanges 同参）。
const assert = require('assert');
const connection = require('../utils/connection.js');

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

test('mapChatMessages：user 文本 / assistant 文本 / reasoning→thinking / tool→tool', function () {
  const value = [
    {
      info: { role: 'user' },
      parts: [{ type: 'text', text: '帮我看看这段代码' }],
    },
    {
      info: { role: 'assistant' },
      parts: [
        { type: 'reasoning', text: '先分析结构' },
        { type: 'text', text: '这段代码的问题是…' },
      ],
    },
    {
      info: { role: 'assistant' },
      parts: [{ type: 'tool', partId: 'p', sessionId: 's', messageId: 'm', name: 'grep' }],
    },
    // 无可见内容（纯 metadata）→ 跳过
    { info: { role: 'assistant' }, parts: [] },
    null,
  ];
  const result = connection.mapChatMessages(value, 'sess-1');
  const rows = result.messages;
  assert.strictEqual(rows.length, 3);
  assert.deepStrictEqual(rows[0], { role: 'user', kind: 'text', text: '帮我看看这段代码' });
  assert.strictEqual(rows[1].role, 'agent');
  assert.strictEqual(rows[1].kind, 'thinking', '有 reasoning 时优先 thinking');
  assert.strictEqual(rows[1].text, '先分析结构');
  assert.strictEqual(rows[2].kind, 'tool');
  assert.strictEqual(rows[2].text, 'grep');
});

test('mapChatMessages：非数组/空回执 → 空消息', function () {
  assert.deepStrictEqual(connection.mapChatMessages(null, 's').messages, []);
  assert.deepStrictEqual(connection.mapChatMessages('nope', 's').messages, []);
  const r = connection.mapChatMessages([], 'sess-x');
  assert.strictEqual(r.title, 'sess-x');
  assert.deepStrictEqual(r.messages, []);
});

test('mapGitChanges：changes/files/顶层数组三形态 + 坏行跳过', function () {
  const wrapped = connection.mapGitChanges({
    changes: [
      { path: 'src/a.ts', status: 'modified', additions: 12, deletions: 3 },
      { filePath: 'src/b.ts', state: 'added' },
      { status: 'deleted' }, // 缺 path 跳过
      null,
    ],
  });
  assert.strictEqual(wrapped.length, 2);
  assert.strictEqual(wrapped[0].path, 'src/a.ts');
  assert.strictEqual(wrapped[0].additions, 12);
  assert.strictEqual(wrapped[1].path, 'src/b.ts');
  assert.strictEqual(wrapped[1].status, 'added');
  const topLevel = connection.mapGitChanges([{ path: 'x', status: 'deleted' }]);
  assert.strictEqual(topLevel.length, 1);
});

test('mapChatMessages：多条 text parts 拼接', function () {
  const result = connection.mapChatMessages([
    {
      info: { role: 'assistant' },
      parts: [
        { type: 'text', text: '第一段' },
        { type: 'text', text: '第二段' },
      ],
    },
  ], 's');
  assert.strictEqual(result.messages[0].text, '第一段\n\n第二段');
});

console.log(passed + ' 项通过');
if (process.exitCode) {
  console.error('存在失败项');
}

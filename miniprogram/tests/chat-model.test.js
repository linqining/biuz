// 011-会话对话 纯逻辑对拍（node miniprogram/tests/chat-model.test.js）
// 对位：Android `ui/chat/ChatMessageViews.kt`（用户气泡/助手正文/工具卡/思考块）+ web 会话页。
const assert = require('assert');
const model = require('../utils/chat-model.js');
const chatModel = model;

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

// ---- Markdown 块/行内（与 iOS TinyMarkdown、Android MarkdownParser 同口径）----
const md = require('../utils/chat-model.js');

test('块级：标题/列表/段落/围栏代码（带语言）', () => {
  const blocks = md.parseBlocks('## 现状\n- 第一项\n- 第二项\n\n正文一段\n```kotlin\nval a = 1\n```');
  assert.deepStrictEqual(blocks.map((b) => b.type), ['heading', 'listItem', 'listItem', 'text', 'code']);
  assert.strictEqual(blocks[0].level, 2);
  assert.strictEqual(blocks[4].language, 'kotlin');
  assert.strictEqual(blocks[4].content, 'val a = 1');
});

test('未闭合围栏：剩余内容按代码块（流式不丢字）', () => {
  const blocks = md.parseBlocks('说明\n```js\nconsole.log(1)');
  assert.deepStrictEqual(blocks.map((b) => b.type), ['text', 'code']);
  assert.strictEqual(blocks[1].content, 'console.log(1)');
});

test('管道表格：表头 + 行，分隔行剔除', () => {
  const blocks = md.parseBlocks('| 名称 | 值 |\n|---|:--:|\n| a | 1 |');
  assert.strictEqual(blocks.length, 1);
  assert.strictEqual(blocks[0].type, 'table');
  assert.deepStrictEqual(blocks[0].header.map((c) => c.text), ['名称', '值']);
  assert.deepStrictEqual(blocks[0].rows.map((r) => r.map((c) => c.text)), [['a', '1']]);
});

test('表格缺分隔行降级为段落', () => {
  const blocks = md.parseBlocks('| a | b |');
  assert.deepStrictEqual(blocks.map((b) => b.type), ['text']);
});

test('行内：粗体 / 斜体 / 行内代码分段', () => {
  const segs = md.parseInline('前 **粗** 中 *斜* 后 `码` 尾');
  assert.deepStrictEqual(segs.map((s) => s.text), ['前 ', '粗', ' 中 ', '斜', ' 后 ', '码', ' 尾']);
  assert.strictEqual(segs[1].bold, true);
  assert.strictEqual(segs[3].italic, true);
  assert.strictEqual(segs[5].code, true);
});

test('行内：未闭合标记按字面量保留', () => {
  assert.deepStrictEqual(md.parseInline('2 * 3 = 6').map((s) => s.text), ['2 * 3 = 6']);
  assert.deepStrictEqual(md.parseInline('反引号 `未闭合').map((s) => s.text), ['反引号 `未闭合']);
  assert.deepStrictEqual(md.parseInline('a**b').map((s) => s.text), ['a**b']);
});

// ---- 挂起交互宽容解析（盖章子轮 2026-11-22；§10 宽容链 + iOS parseInteraction 同构） ----

test('parsePendingInteraction：permission 基本形状（id/kind/title/options 对象链）', function () {
  const parsed = chatModel.parsePendingInteraction({
    id: 'i-1', kind: 'permission', title: '执行 shell',
    command: 'npm test', impact: '写入 node_modules',
    options: [{ id: 'allowOnce', label: 'Allow once' }, { id: 'rejectOnce', label: 'Reject' }],
  });
  if (!parsed || parsed.id !== 'i-1' || parsed.kind !== 'permission') throw new Error('基本字段丢失');
  if (parsed.options.length !== 2 || parsed.options[0].id !== 'allowOnce') throw new Error('options 解析失败');
});

test('parsePendingInteraction：id 链宽容（interactionId / payload.interactionId）', function () {
  const a = chatModel.parsePendingInteraction({ interactionId: 'x', kind: 'permission' });
  const b = chatModel.parsePendingInteraction({ payload: { interactionId: 'y' }, kind: 'permission' });
  if (!a || a.id !== 'x') throw new Error('interactionId 链失效');
  if (!b || b.id !== 'y') throw new Error('payload.interactionId 链失效');
});

test('ExitPlanMode 归位：kind="userInput" + schema.interaction="plan_approval" → plan_approval（实证 product-projection.ts:3117-3134）', function () {
  const parsed = chatModel.parsePendingInteraction({
    id: 'p-1', kind: 'userInput',
    payload: {
      schema: { interaction: 'plan_approval' },
      prompt: '审核原因标题',
      input: { plan: '第一步…\n第二步…' },
    },
  });
  if (!parsed || parsed.kind !== 'plan_approval') throw new Error('schema 归位失效');
  if (parsed.title !== '审核原因标题') throw new Error('标题应取 payload.prompt');
  if (parsed.plan !== '第一步…\n第二步…') throw new Error('计划正文应取 payload.input.plan');
});

test('提问族：questions 数组宽容（question/multiSelect/options.value）+ options 裸字符串', function () {
  const parsed = chatModel.parsePendingInteraction({
    id: 'q-1', kind: 'userInput',
    questions: [{ question: '用哪个版本？', multiSelect: false, options: [{ value: 'v1', label: 'V1' }] }],
    options: ['由你定夺'],
  });
  if (!parsed || parsed.questions.length !== 1) throw new Error('questions 解析失败');
  if (parsed.questions[0].options[0].id !== 'v1') throw new Error('question options.value 映射失败');
  if (parsed.options[0].id !== '由你定夺') throw new Error('options 裸字符串应 id=label=该串');
});

test('G22 提问自由输入开关：payload.freeText 显式值优先 + 无选项题缺省开 + 纯自由输入强制开', function () {
  // 显式 true（iOS RemoteConversationStore.swift:3358-3366 同构）
  const explicit = chatModel.parsePendingInteraction({
    id: 'f1', kind: 'userInput', freeText: true,
    questions: [{ question: 'q', options: [{ value: 'a', label: 'A' }] }],
  });
  if (explicit.freeTextEnabled !== true) throw new Error('payload.freeText=true 应开');
  // 缺省 = 存在无选项题
  const derived = chatModel.parsePendingInteraction({
    id: 'f2', kind: 'userInput',
    questions: [{ question: 'q2' }],
  });
  if (derived.freeTextEnabled !== true) throw new Error('无选项题应缺省开');
  // 有选项题 + freeText 缺席 = 关
  const closed = chatModel.parsePendingInteraction({
    id: 'f3', kind: 'userInput',
    questions: [{ question: 'q3', options: [{ value: 'b', label: 'B' }] }],
  });
  if (closed.freeTextEnabled !== false) throw new Error('有选项题缺省应关');
});

test('G22 多题向导应答信封：answer_N 逐题 + answers 汇总 + 单题 answer 捷径（iOS submitQuestions 同构）', function () {
  const qs = [
    { question: '部署到哪？', options: [{ id: 'staging', label: '预发' }, { id: 'prod', label: '生产' }], multiSelect: false },
    { question: '补充说明', options: [], multiSelect: true },
  ];
  const multi = chatModel.questionAnswersAnswer(qs, { 0: ['prod'] }, { 1: '  先灰度  ' });
  if (multi.action !== 'accept') throw new Error('action 应为 accept');
  if (multi.content.answer_0 !== 'prod') throw new Error('单选题 answer_N 应字符串');
  if (JSON.stringify(multi.content.answer_1) !== JSON.stringify(['先灰度'])) throw new Error('multiSelect 题应数组且文本 trim');
  if (multi.content.answers['部署到哪？'] !== 'prod') throw new Error('answers 汇总缺失');
  if (multi.content.answers['补充说明'] !== '先灰度') throw new Error('answers 文本汇总缺失');
  if (multi.content.answer !== undefined) throw new Error('多题不应有 answer 捷径键');

  const single = chatModel.questionAnswersAnswer([qs[0]], { 0: ['staging'] }, {});
  if (single.content.answer !== 'staging') throw new Error('单题应补 answer 捷径键');
});

test('坏形状跳过：非对象/null/缺 id 或 kind → null（不造假卡）', function () {
  if (chatModel.parsePendingInteraction(null) !== null) throw new Error('null 应跳过');
  if (chatModel.parsePendingInteraction({ id: 'i', kind: '' }) !== null) throw new Error('缺 kind 应跳过');
  const arr = chatModel.parsePendingInteractions([null, { id: 'ok', kind: 'permission' }, 'junk']);
  if (arr.length !== 1 || arr[0].id !== 'ok') throw new Error('数组级宽容失败');
});

// MARK: G21/G23 镜像（2026-11-25 与 iOS 同步）
test('G21 permissionFreeText：payload.freeText=true 才开反馈框（permission 之外不读）', () => {
  const on = chatModel.parsePendingInteraction({
    id: 'i1', kind: 'permission', payload: { freeText: true, command: 'git push' },
  });
  assert.strictEqual(on.permissionFreeText, true);
  const off = chatModel.parsePendingInteraction({ id: 'i2', kind: 'permission', payload: {} });
  assert.strictEqual(off.permissionFreeText, false);
  const other = chatModel.parsePendingInteraction({ id: 'i3', kind: 'userInput', payload: { freeText: true } });
  assert.strictEqual(other.permissionFreeText, false);
});

test('G23 pluginInstall：字段族解析（displayName 优先 + action/scope 透传）', () => {
  const p = chatModel.parsePendingInteraction({
    id: 'i4', kind: 'pluginInstall',
    payload: {
      kind: 'pluginInstall', pluginId: 'ccgithub', name: 'ccgithub',
      displayName: 'CC GitHub', description: 'GitHub 集成', reason: '模型请求安装',
      action: 'install', scope: 'workspace', toolCallId: 't1',
    },
  });
  assert.strictEqual(p.pluginName, 'CC GitHub');
  assert.strictEqual(p.pluginDescription, 'GitHub 集成');
  assert.strictEqual(p.pluginReason, '模型请求安装');
  assert.strictEqual(p.pluginAction, 'install');
  assert.strictEqual(p.pluginScope, 'workspace');
});

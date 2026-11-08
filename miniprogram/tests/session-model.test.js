// 010-任务主页 纯逻辑对拍（可在 Node 直接跑：node miniprogram/tests/session-model.test.js）
// 金标口径来自 web 源码：
//   project-sidebar/index.tsx:252-318（pinned 恒在最前；projects/grouped/unread 分发）
//   ProjectSidebarListHeader.tsx:95-102（timeline = 最近更新，单列按时间倒序，无日期分桶）
const assert = require('assert');
const model = require('../utils/session-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

const NOW = 1_700_000_000_000;
const MIN = 60 * 1000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

function rows() {
  return [
    { id: 'p1', title: '置顶旧', updatedAt: NOW - 10 * DAY, isPinned: true, projectName: 'A' },
    { id: 'p2', title: '置顶新', updatedAt: NOW - 5 * MIN, isPinned: true, projectName: 'A' },
    { id: 'a1', title: 'A 旧', updatedAt: NOW - 3 * DAY, projectName: 'A' },
    { id: 'a2', title: 'A 新', updatedAt: NOW - 2 * HOUR, projectName: 'A' },
    { id: 'b1', title: 'B 一', updatedAt: NOW - 1 * DAY, projectName: 'B' },
    { id: 'u1', title: '未归属', updatedAt: NOW - 30 * MIN, unread: true },
    { id: 'g1', title: '分组一', updatedAt: NOW - 4 * HOUR, groupName: '组甲' },
    { id: 'g2', title: '分组二', updatedAt: NOW - 6 * HOUR, groupName: '组甲' },
  ];
}

test('projects 视图：置顶恒在首位，且按时间倒序', () => {
  const sections = model.buildView(rows(), 'projects', []);
  assert.strictEqual(sections[0].key, model.PINNED_SECTION_KEY);
  assert.deepStrictEqual(sections[0].items.map((r) => r.id), ['p2', 'p1']);
});

test('projects 视图：项目组按组内最新一条降序，组内按时间倒序', () => {
  const sections = model.buildView(rows(), 'projects', []);
  const projects = sections.filter((s) => s.key.indexOf('project:') === 0);
  assert.deepStrictEqual(projects.map((s) => s.title), ['A', 'B']);
  assert.deepStrictEqual(projects[0].items.map((r) => r.id), ['a2', 'a1']);
});

test('projects 视图：无项目的任务落「任务」区（web conversation-section），不丢弃', () => {
  const sections = model.buildView(rows(), 'projects', []);
  const ungrouped = sections.filter((s) => s.key === model.UNGROUPED_SECTION_KEY)[0];
  assert.ok(ungrouped, '应存在任务分区');
  assert.strictEqual(ungrouped.title, '任务');
  assert.deepStrictEqual(ungrouped.items.map((r) => r.id), ['u1', 'g1', 'g2']);
});

test('grouped 视图：按 groupName 分组，未分组成区', () => {
  const sections = model.buildView(rows(), 'grouped', []);
  const group = sections.filter((s) => s.key === 'group:组甲')[0];
  assert.ok(group, '应存在组甲分区');
  assert.deepStrictEqual(group.items.map((r) => r.id), ['g1', 'g2']);
  const rest = sections.filter((s) => s.key === model.UNGROUPED_SECTION_KEY)[0];
  assert.ok(rest.items.length === rows().filter((r) => !r.isPinned && !r.groupName).length);
});

test('timeline 视图：单列倒序，不做日期分桶（web 口径）', () => {
  const sections = model.buildView(rows(), 'timeline', []);
  assert.strictEqual(sections.filter((s) => s.collapsible).length, 0);
  const timeline = sections.filter((s) => s.key === 'section:timeline')[0];
  const ids = timeline.items.map((r) => r.id);
  assert.deepStrictEqual(ids, ['u1', 'a2', 'g1', 'g2', 'b1', 'a1']);
});

test('unread 视图：只保留未读，且不丢置顶', () => {
  const sections = model.buildView(rows(), 'unread', []);
  const unread = sections.filter((s) => s.key === 'section:unread')[0];
  assert.deepStrictEqual(unread.items.map((r) => r.id), ['u1']);
  assert.strictEqual(sections[0].key, model.PINNED_SECTION_KEY);
});

test('折叠态按 key 生效', () => {
  const sections = model.buildView(rows(), 'projects', ['project:A']);
  const a = sections.filter((s) => s.key === 'project:A')[0];
  assert.strictEqual(a.collapsed, true);
});

test('未知字段不猜：缺 title/status 时给安全默认值', () => {
  const normalized = model.normalizeRow({ id: 'x' });
  assert.strictEqual(normalized.title, '未命名会话');
  assert.strictEqual(normalized.status, 'idle');
  assert.strictEqual(normalized.preview, '');
});

test('相对时间口径与线上基线一致', () => {
  assert.strictEqual(model.formatTime(NOW - 20 * 1000, NOW), '刚刚');
  assert.strictEqual(model.formatTime(NOW - 20 * MIN, NOW), '20分');
  assert.strictEqual(model.formatTime(NOW - 5 * HOUR, NOW), '5小时');
  assert.strictEqual(model.formatTime(NOW - 26 * DAY, NOW), '26天');
  assert.strictEqual(model.formatTime(0, NOW), '');
});

test('decorateSections 摊平时间文案与首字', () => {
  const sections = model.buildView(rows(), 'projects', []);
  const decorated = model.decorateSections(sections, NOW);
  const first = decorated[0].items[0];
  assert.strictEqual(first.timeText, '5分');
  assert.strictEqual(first.initial, '置');
});

console.log('\n' + passed + ' 项通过');

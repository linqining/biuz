// 任务主页（010）的纯逻辑层 —— 分组/排序/视图切换。
//
// web 权威源：
//   packages/ui/src/project-sidebar/index.tsx:252-318（视图分发）
//   packages/ui/src/project-sidebar/ProjectSidebarListHeader.tsx:87-102（四个视图选项）
//   packages/ui/src/project-sidebar/view.js（selectProjectRows 的 kind 语义）
//
// 设计：不依赖 wx API，可在 Node 里直接跑单测（miniprogram/tests/session-model.test.js）。
// 行数据形状与 iOS Conversation 对齐（id/title/summary/updatedAt/isPinned/projectName/source）。

/** 四种视图，顺序与 web 下拉菜单一致。 */
const VIEWS = [
  { key: 'projects', label: '按项目' },
  { key: 'grouped', label: '按分组' },
  { key: 'timeline', label: '最近更新' },
  { key: 'unread', label: '按未读' },
];

const PINNED_SECTION_KEY = 'section:pinned';
const UNGROUPED_SECTION_KEY = 'section:ungrouped';

/**
 * 工作流迷你轨道归一化。
 * web 行内形态（实测）：6×6 站点灯 + 6×1 连接线 + 12px 阶段名（`task-item` 内）。
 * 入参兼容两种真实形状：
 *   ① 已展开的 `track: { label, states: [...], extra }`
 *   ② sessions-index 的 `workflowActivity: { liveRuns: [{ phases: [{status}], currentPhaseName }] }`
 * 只取第一条 live run（web 每会话最多画 2 条，移动端首版取 1 条，见 BACKLOG）。
 */
function normalizeTrack(raw) {
  if (raw.track) {
    const t = raw.track;
    return {
      label: t.label || '',
      states: Array.isArray(t.states) ? t.states : [],
      extra: t.extra || '',
    };
  }
  const runs = (raw.workflowActivity && raw.workflowActivity.liveRuns) || [];
  if (!runs.length) return null;
  const run = runs[0];
  const phases = run.phases || [];
  const states = phases.map(function (p) {
    if (p.status === 'done' || p.status === 'completed') return 'done';
    if (p.status === 'running' || p.status === 'active') return 'active';
    if (p.status === 'failed') return 'attention';
    return 'pending';
  });
  return {
    label: run.currentPhaseName || run.name || '',
    states: states.slice(0, 6),
    extra: states.length > 6 ? '+' + (states.length - 6) : '',
  };
}

/** 归一化：把远端行补成渲染所需的稳定形状，不猜字段（缺字段给安全默认值）。 */
function normalizeRow(raw) {
  return {
    id: String(raw.id || ''),
    title: raw.title || '未命名会话',
    preview: raw.summary || raw.preview || '',
    status: raw.status === 'running' || raw.status === 'completed' || raw.status === 'failed'
      ? raw.status
      : 'idle',
    updatedAt: Number(raw.updatedAt || 0),
    isPinned: Boolean(raw.isPinned),
    unread: Boolean(raw.unread),
    projectName: raw.projectName || null,
    groupName: raw.groupName || null,
    track: normalizeTrack(raw),
    // G33 行内待批徽标（轻量投影透传；行未携带 = null 不渲染——宽容读，
    // listTaskList 行携带面未取证）
    pendingInteractionId: raw.pendingInteractionId || null,
    pendingInteractionKind: raw.pendingInteractionKind || null,
    pendingInteractionDeadline: raw.pendingInteractionDeadline || null,
    pendingInteractionVisibleAt: raw.pendingInteractionVisibleAt || null,
    pendingInteractionSnoozedAt: raw.pendingInteractionSnoozedAt || null,
    // G40 残面透传（cron/off-peak/worktree/远端设备；缺席 = null 不渲染）
    isCron: raw.isCron === true,
    isOffPeak: raw.isOffPeak === true,
    worktreeRoot: raw.worktreeRoot || null,
    serverDisplayName: raw.serverDisplayName || null,
  };
}

/** G40 残面：worktree 显示名 `<仓> · <桶>`（web worktreeLabel.ts 同构——切两种
 * 分隔符取末段/次末段；段数不足两段 = null 不显示）。 */
function worktreeLabel(root) {
  if (!root) return null;
  const segments = String(root).split(/[\\/]+/).filter(function (s) { return s.length > 0; });
  if (segments.length < 2) return null;
  return segments[segments.length - 1] + ' · ' + segments[segments.length - 2];
}

/**
 * G33 倒计时进度 0…1（web TaskInteractionBadge.tsx:44-58 同式；2026-11-25 与 iOS 同步）：
 * 仅 userInput；snoozed / 静默期（now < visibleAt）/ 分母非正 = null 不画环。
 */
function countdownProgress(row, nowMs) {
  if (!row || row.pendingInteractionKind !== 'userInput') return null;
  if (row.pendingInteractionSnoozedAt) return null;
  const deadline = Number(row.pendingInteractionDeadline) || 0;
  const visibleAt = Number(row.pendingInteractionVisibleAt) || 0;
  if (!deadline || !visibleAt || nowMs < visibleAt) return null;
  const span = deadline - visibleAt;
  if (span <= 0) return null;
  const progress = (deadline - nowMs) / span;
  return Math.min(1, Math.max(0, progress));
}

function byUpdatedDesc(a, b) {
  return b.updatedAt - a.updatedAt;
}

/** 置顶区：web 侧恒定在最前，且不受视图影响（index.tsx:256-261 / :287-292 两处都先渲染 pinned）。 */
const PINNED_PAGE_SIZE = 20;

/**
 * G34 置顶 20 上限（web ProjectTaskRows.tsx:112 `kind === "pinned" ? (showAll ?
 * ordered : ordered.slice(0, 20))` 同语义；2026-11-25 与 iOS/Android 同步）。
 * showAll = 翻页后全量；溢出数由 pinnedOverflowCount 给「显示更多」行。
 */
function pinnedSection(rows, showAll) {
  const all = rows.filter(function (r) { return r.isPinned; }).sort(byUpdatedDesc);
  if (!all.length) return null;
  const items = showAll ? all : all.slice(0, PINNED_PAGE_SIZE);
  return { key: PINNED_SECTION_KEY, title: '已置顶', items: items, collapsible: false, collapsed: false };
}

/** G34：置顶溢出数（>20 时「显示更多置顶（N）」行数据源）。 */
function pinnedOverflowCount(rows, showAll) {
  if (showAll) return 0;
  return Math.max(0, rows.filter(function (r) { return r.isPinned; }).length - PINNED_PAGE_SIZE);
}

function groupBy(rows, pick) {
  const buckets = {};
  const order = [];
  rows.forEach(function (row) {
    const name = pick(row);
    if (!name) return;
    if (!buckets[name]) {
      buckets[name] = [];
      order.push(name);
    }
    buckets[name].push(row);
  });
  return order.map(function (name) {
    const items = buckets[name].sort(byUpdatedDesc);
    return { name: name, items: items, weight: items[0].updatedAt };
  });
}

function toSections(groups, prefix, collapsedKeys) {
  // 组间按组内最新一条降序（与 iOS projectGroups 同口径）
  return groups
    .slice()
    .sort(function (a, b) { return b.weight - a.weight; })
    .map(function (g) {
      const key = prefix + g.name;
      return {
        key: key,
        title: g.name,
        items: g.items,
        collapsible: true,
        collapsed: collapsedKeys.indexOf(key) >= 0,
        count: g.items.length,
      };
    });
}

/**
 * 按视图组装分区。
 * @param {Array} rows 原始行
 * @param {string} view projects | grouped | timeline | unread
 * @param {Array<string>} collapsedKeys 已折叠分区 key
 */
function buildView(rows, view, collapsedKeys, showAllPinned) {
  const all = (rows || []).map(normalizeRow);
  const collapsed = collapsedKeys || [];
  const sections = [];

  const pinned = pinnedSection(all, showAllPinned === true);
  if (pinned) sections.push(pinned);

  const rest = all.filter(function (r) { return !r.isPinned; });

  if (view === 'unread') {
    const unread = rest.filter(function (r) { return r.unread; }).sort(byUpdatedDesc);
    if (unread.length) {
      sections.push({
        key: 'section:unread',
        title: '未读',
        items: unread,
        collapsible: false,
        collapsed: false,
      });
    }
    return sections;
  }

  if (view === 'grouped') {
    const withGroup = rest.filter(function (r) { return Boolean(r.groupName); });
    const withoutGroup = rest.filter(function (r) { return !r.groupName; });
    toSections(groupBy(withGroup, function (r) { return r.groupName; }), 'group:', collapsed)
      .forEach(function (s) { sections.push(s); });
    if (withoutGroup.length) {
      sections.push({
        key: UNGROUPED_SECTION_KEY,
        title: '未分组',
        items: withoutGroup.sort(byUpdatedDesc),
        collapsible: false,
        collapsed: false,
      });
    }
    return sections;
  }

  if (view === 'timeline') {
    // web 的「最近更新」是单列按时间倒序（ProjectSidebarListHeader 「最近更新」+ ProjectTaskRows kind=timeline），
    // 不做日期分桶——分桶是 iOS/Android 的历史实现，属偏差，已登记 BACKLOG。
    if (rest.length) {
      sections.push({
        key: 'section:timeline',
        title: '最近更新',
        items: rest.slice().sort(byUpdatedDesc),
        collapsible: false,
        collapsed: false,
      });
    }
    return sections;
  }

  // 默认 projects
  const withProject = rest.filter(function (r) { return Boolean(r.projectName); });
  const withoutProject = rest.filter(function (r) { return !r.projectName; });
  toSections(groupBy(withProject, function (r) { return r.projectName; }), 'project:', collapsed)
    .forEach(function (s) { sections.push(s); });
  if (withoutProject.length) {
    // web 的「按项目」视图把无项目的任务落在 conversation-section（标题「任务」），不是丢弃
    sections.push({
      key: UNGROUPED_SECTION_KEY,
      title: '任务',
      items: withoutProject.sort(byUpdatedDesc),
      collapsible: false,
      collapsed: false,
    });
  }
  return sections;
}

/**
 * 相对时间文案 —— 与 web 实测口径一致：
 *   <1 分钟「刚刚」／<1 小时「N分」／<24 小时「N小时」／<30 天「N天」／否则「M月D日」
 * 证据：线上 home 基线 .capture/runs/20261108-080125-*（20分 / 52分 / 26天 / 2天）。
 */
function formatTime(updatedAt, now) {
  if (!updatedAt) return '';
  const t = Number(updatedAt);
  const current = now === undefined ? Date.now() : now;
  const diff = Math.max(0, current - t);
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) return '刚刚';
  if (diff < hour) return Math.floor(diff / minute) + '分';
  if (diff < day) return Math.floor(diff / hour) + '小时';
  if (diff < 30 * day) return Math.floor(diff / day) + '天';
  const d = new Date(t);
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}

/** 渲染前把时间戳摊平成文案，WXML 里不做计算。 */
function decorateSections(sections, now) {
  return sections.map(function (section) {
    const copy = Object.assign({}, section);
    copy.items = section.items.map(function (row) {
      return Object.assign({}, row, {
        timeText: formatTime(row.updatedAt, now),
        initial: (row.title || '').slice(0, 1),
      });
    });
    return copy;
  });
}

function viewLabel(view) {
  const hit = VIEWS.filter(function (v) { return v.key === view; })[0];
  return hit ? hit.label : VIEWS[0].label;
}

module.exports = {
  VIEWS: VIEWS,
  PINNED_SECTION_KEY: PINNED_SECTION_KEY,
  UNGROUPED_SECTION_KEY: UNGROUPED_SECTION_KEY,
  normalizeRow: normalizeRow,
  buildView: buildView,
  decorateSections: decorateSections,
  formatTime: formatTime,
  viewLabel: viewLabel,
  countdownProgress: countdownProgress,
  worktreeLabel: worktreeLabel,
  pinnedOverflowCount: pinnedOverflowCount,
};

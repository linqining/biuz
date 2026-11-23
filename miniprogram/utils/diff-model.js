// 029-Diff 审查纯逻辑层 —— 分段归组 / 行统计 / 行样式。
//
// 数据形状与 Android `protocol/Models.kt:605-627` / iOS DiffFile 同源：
//   file = { id, path, language, added, removed, lines:[{id, kind, text}], segment? }
//   kind ∈ hunk | add | del | ctx
// web 权威源：`packages/ui/src/GitPane.tsx`；分段标签沿用三端口径「未暂存 / 已暂存 / 本次会话」。

const SEGMENTS = [
  { key: 'unstaged', label: '未暂存' },
  { key: 'staged', label: '已暂存' },
  { key: 'session', label: '本次会话' },
];

/** 归一化一个文件条目（缺字段给安全默认）。 */
function normalizeFile(raw) {
  const lines = (raw.lines || []).map(function (line, index) {
    const kind = line.kind === 'add' || line.kind === 'del' || line.kind === 'hunk' ? line.kind : 'ctx';
    return {
      id: String(line.id || index),
      kind: kind,
      text: line.text || '',
    };
  });
  const counted = countOf(lines);
  return {
    id: String(raw.id || raw.path || ''),
    path: raw.path || '',
    language: raw.language || '',
    added: typeof raw.added === 'number' ? raw.added : counted.add,
    removed: typeof raw.removed === 'number' ? raw.removed : counted.del,
    lines: lines,
    segment: raw.segment === 'staged' || raw.segment === 'session' ? raw.segment : 'unstaged',
  };
}

/** 行统计（add/del）。 */
function countOf(lines) {
  let add = 0;
  let del = 0;
  (lines || []).forEach(function (line) {
    if (line.kind === 'add') add += 1;
    if (line.kind === 'del') del += 1;
  });
  return { add: add, del: del };
}

/**
 * 按分段归组：`[{ key, label, files:[{...file, expanded 由页面持有}] }]`
 * 空分段不渲染（与 Android/iOS 分段空态一致）。
 */
function groupBySegment(files) {
  const list = (files || []).map(normalizeFile);
  return SEGMENTS
    .map(function (segment) {
      const items = list.filter(function (file) { return file.segment === segment.key; });
      return { key: segment.key, label: segment.label, files: items };
    })
    .filter(function (section) { return section.files.length > 0; });
}

/** 总改动统计（页头「N 个文件 · +A -D」）。 */
function totals(files) {
  return (files || []).map(normalizeFile).reduce(function (acc, file) {
    acc.files += 1;
    acc.add += file.added;
    acc.del += file.removed;
    return acc;
  }, { files: 0, add: 0, del: 0 });
}

/** 路径末段（文件卡标题）。 */
function baseName(path) {
  const parts = String(path || '').split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : String(path || '');
}

module.exports = {
  SEGMENTS: SEGMENTS,
  normalizeFile: normalizeFile,
  countOf: countOf,
  groupBySegment: groupBySegment,
  totals: totals,
  baseName: baseName,
};

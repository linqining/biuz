// 文件树（030-文件管理器）纯逻辑层 —— 展开态扁平化 + 尺寸/图标展示。
//
// 数据形状与 Android `protocol/Models.kt:601 FileNode` / iOS FileNode 同源：
//   { id, name, path, isDirectory, size?, children? }
// web 权威源：`packages/ui/src/app-shell/AnimatedSidePanePanel.tsx:1333`（side pane 的
// file-manager / code-viewer 两种 tab）；移动端对位 iOS FileTreeView / Android FileTreeScreen。
//
// 不依赖 wx API，可在 Node 里直接跑单测。

/** 归一化一个节点（缺字段给安全默认，不猜类型）。 */
function normalizeNode(raw) {
  const children = Array.isArray(raw.children) ? raw.children.map(normalizeNode) : null;
  return {
    id: String(raw.id || raw.path || ''),
    name: raw.name || '未命名',
    path: raw.path || '',
    isDirectory: Boolean(raw.isDirectory) || children !== null,
    size: typeof raw.size === 'number' ? raw.size : null,
    children: children,
  };
}

/**
 * 按展开集合扁平化为可渲染行（深度优先，目录展开才下钻）。
 * expandedKeys：目录 path 集合。
 * 返回 [{ node, depth, expandable, expanded }]
 */
function flatten(nodes, expandedKeys) {
  const expanded = expandedKeys || {};
  const rows = [];
  function walk(list, depth) {
    (list || []).forEach(function (raw) {
      const node = raw.children !== undefined ? raw : normalizeNode(raw);
      const expandable = node.isDirectory && Array.isArray(node.children) && node.children.length > 0;
      const isExpanded = expandable && Boolean(expanded[node.path]);
      rows.push({ node: node, depth: depth, expandable: expandable, expanded: isExpanded });
      if (isExpanded) walk(node.children, depth + 1);
    });
  }
  walk(nodes, 0);
  return rows;
}

/** 文件尺寸文案（web 同款人类可读；目录/未知返回空串）。 */
function sizeText(node) {
  if (!node || node.isDirectory || node.size === null || node.size === undefined) return '';
  const bytes = Number(node.size);
  if (!isFinite(bytes) || bytes < 0) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

/** 语言高亮提示（预览页用；按扩展名，未知返回空串 → 按纯文本渲染）。 */
function languageOf(path) {
  const ext = String(path || '').split('.').pop().toLowerCase();
  const map = {
    swift: 'swift', kt: 'kotlin', java: 'java', ts: 'typescript', tsx: 'typescript',
    js: 'javascript', jsx: 'javascript', json: 'json', md: 'markdown', py: 'python',
    go: 'go', rs: 'rust', css: 'css', html: 'html', yml: 'yaml', yaml: 'yaml',
    sh: 'shell', sql: 'sql',
  };
  return map[ext] || '';
}

/** 路径末段（预览页标题用）。 */
function baseName(path) {
  const parts = String(path || '').split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : String(path || '');
}

module.exports = {
  normalizeNode: normalizeNode,
  flatten: flatten,
  sizeText: sizeText,
  languageOf: languageOf,
  baseName: baseName,
};

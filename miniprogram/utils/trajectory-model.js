// 038-模型轨迹 纯逻辑层 —— 轨迹条目归一化与展示口径。
//
// web 权威源：`packages/ui/src/ModelTrajectoryPane.tsx`（side pane 的 model-trajectory tab）；
// iOS 对位 `TaskOutputView`（reasoning + toolCall 轨迹卡 + 元数据 + token 用量）。
// 条目 kind：reasoning（思考）/ toolCall（工具）/ assistant（正文）。

const ROLE_STYLES = {
  reasoning: { label: '思考', color: 'var(--color-violet)' },
  toolCall: { label: '工具', color: 'var(--color-info)' },
  assistant: { label: '回复', color: 'var(--color-muted)' },
};

/** 归一化一个轨迹条目。 */
function normalizeEntry(raw, index) {
  const kind = raw.kind === 'toolCall' || raw.kind === 'assistant' ? raw.kind : 'reasoning';
  const status = raw.status === 'success' || raw.status === 'error' ? raw.status : 'done';
  return {
    id: String(raw.id || ('t-' + index)),
    kind: kind,
    label: ROLE_STYLES[kind].label,
    color: ROLE_STYLES[kind].color,
    text: raw.text || '',
    toolName: raw.toolName || '',
    status: status,
    durationMs: typeof raw.durationMs === 'number' ? raw.durationMs : null,
  };
}

/** 归一化整条轨迹（保序）。 */
function normalizeTrajectory(entries) {
  return (entries || []).map(normalizeEntry).filter(function (entry) {
    // 空正文且非工具条的条目丢弃（避免渲染空块）
    return entry.text !== '' || entry.kind === 'toolCall';
  });
}

/** 工具条摘要：`name · 状态 · 耗时`。 */
function toolSummary(entry) {
  if (!entry || entry.kind !== 'toolCall') return '';
  const parts = [];
  if (entry.toolName) parts.push(entry.toolName);
  parts.push(entry.status === 'error' ? '失败' : entry.status === 'success' ? '成功' : '完成');
  if (entry.durationMs !== null && entry.durationMs >= 0) {
    parts.push(entry.durationMs >= 1000
      ? (entry.durationMs / 1000).toFixed(1) + 's'
      : entry.durationMs + 'ms');
  }
  return parts.join(' · ');
}

/** 轨迹统计（页头「N 步 · 工具 M 次」）。 */
function stats(entries) {
  const list = entries || [];
  return {
    steps: list.length,
    tools: list.filter(function (entry) { return entry.kind === 'toolCall'; }).length,
  };
}

module.exports = {
  ROLE_STYLES: ROLE_STYLES,
  normalizeEntry: normalizeEntry,
  normalizeTrajectory: normalizeTrajectory,
  toolSummary: toolSummary,
  stats: stats,
};

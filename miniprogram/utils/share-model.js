// 009-分享落地 纯逻辑层 —— 回执解析与错误文案。
//
// 契约来源：iOS `RemoteExtrasViews.swift:34-110`（SharePreviewStore）与 web
// `ConversationShareLandingPage.tsx`：`GET /api/v1/shares/{code}/preview`，公开分享未登录可看。
// 行模型宽容：text / inputText / summaryText 三键任一；认不出的行计数不整份失败。

function parsePreview(body) {
  if (!body || typeof body !== 'object') return null;
  const share = body.share || {};
  const owner = share.owner || {};
  const rows = [];
  let unsupported = 0;
  (body.rows || []).forEach(function (item) {
    if (!item || typeof item !== 'object') {
      unsupported += 1;
      return;
    }
    const text = item.text || item.inputText || item.summaryText || '';
    if (!text) {
      unsupported += 1;
      return;
    }
    const kind = item.kind || 'unknown';
    rows.push({ kind: kind, text: text, isUser: kind === 'userInput' });
  });
  return {
    title: share.title || '',
    ownerName: share.ownerName || owner.displayName || '',
    createdAtMs: typeof share.createdAt === 'number' ? share.createdAt : null,
    rows: rows,
    unsupportedRowCount: unsupported,
  };
}

/** HTTP 状态 → 错误文案（对齐 iOS 同款措辞）。 */
function errorText(status) {
  if (status === 401 || status === 403) return '该分享需要登录后查看（请求被拒绝）';
  return '分享预览获取失败（HTTP ' + status + '）· 链接可能已失效';
}

module.exports = { parsePreview: parsePreview, errorText: errorText };

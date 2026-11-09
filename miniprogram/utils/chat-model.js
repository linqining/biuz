// 011-会话对话的纯逻辑层 —— 消息归一化 + 轻量块解析 + 时间口径。
//
// web 权威源：`packages/ui/src/v4/SessionPane.tsx` / `V4ChatPane` 的消息渲染层；
// Android 对位 `ui/chat/ChatMessageViews.kt`（用户气泡 / 助手正文 / 思考块 / 工具卡）。
//
// 小程序没有富文本容器，助手正文按「块」渲染：代码围栏 → 等宽块，标题 → 加粗行，
// 其余按段落。这不是完整 markdown（表格/列表未做），差异登记在 BACKLOG（P-001 同族）。

/** 归一化一条消息为渲染所需形状（缺字段给安全默认值，不猜类型）。 */
function normalizeMessage(raw) {
  const role = raw.role === 'user' ? 'user' : 'agent';
  const kind = raw.kind === 'tool' || raw.kind === 'thinking' ? raw.kind : 'text';
  return {
    id: String(raw.id || ''),
    role: role,
    kind: kind,
    text: raw.text || '',
    toolName: raw.toolName || '',
    toolStatus: raw.toolStatus || '',
    streaming: Boolean(raw.streaming),
    timestamp: Number(raw.timestamp || 0),
  };
}

/**
 * 轻量块解析（顺序扫描，支持 ``` 围栏 / # 标题 / 空行分段）。
 * 返回 [{ type: 'code'|'heading'|'text', content: string, level?: number }]
 */
function parseBlocks(text) {
  const source = String(text == null ? '' : text);
  if (!source) return [];
  const lines = source.split('\n');
  const blocks = [];
  let buffer = [];
  let inFence = false;
  let fenceLines = [];

  function flushText() {
    const content = buffer.join('\n').replace(/\s+$/, '');
    if (content.trim()) blocks.push({ type: 'text', content: content });
    buffer = [];
  }

  lines.forEach(function (line) {
    if (line.trim().startsWith('```')) {
      if (inFence) {
        blocks.push({ type: 'code', content: fenceLines.join('\n') });
        fenceLines = [];
        inFence = false;
      } else {
        flushText();
        inFence = true;
      }
      return;
    }
    if (inFence) {
      fenceLines.push(line);
      return;
    }
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      flushText();
      blocks.push({ type: 'heading', content: heading[2], level: heading[1].length });
      return;
    }
    buffer.push(line);
  });

  if (inFence) {
    // 围栏未闭合（流式中）：按代码块渲染，避免把半截代码当正文
    blocks.push({ type: 'code', content: fenceLines.join('\n') });
  } else {
    flushText();
  }
  return blocks;
}

/** 渲染前摊平：给每条消息挂 blocks / 时间文案 / 角色展示名。 */
function decorateMessage(message, now, activeId) {
  // 用户气泡按原文渲染（web 同口径）；只有助手正文走块解析
  const blocks = message.kind === 'text' && message.role === 'agent' ? parseBlocks(message.text) : [];
  return Object.assign({}, message, {
    blocks: blocks,
    timeText: formatMessageTime(message.timestamp, now),
    isActive: message.id === activeId,
    roleLabel: message.role === 'user' ? '你' : 'Agent',
  });
}

/** 时间口径：今天 HH:mm；否则 M月D日 HH:mm。 */
function formatMessageTime(timestampMs, nowMs) {
  if (!timestampMs) return '';
  const now = new Date(nowMs === undefined ? Date.now() : nowMs);
  const then = new Date(timestampMs);
  const hh = String(then.getHours()).padStart(2, '0');
  const mm = String(then.getMinutes()).padStart(2, '0');
  const sameDay = now.getFullYear() === then.getFullYear() &&
    now.getMonth() === then.getMonth() &&
    now.getDate() === then.getDate();
  return sameDay ? (hh + ':' + mm) : ((then.getMonth() + 1) + '月' + then.getDate() + '日 ' + hh + ':' + mm);
}

/**
 * 会话页顶栏标题：web 为「任务会话」；有任务标题时显示标题（过长省略）。
 * 空标题回落 web 同款兜底文案。
 */
function headerTitle(session) {
  const title = session && session.title ? String(session.title).trim() : '';
  return title || '任务会话';
}

module.exports = {
  normalizeMessage: normalizeMessage,
  parseBlocks: parseBlocks,
  decorateMessage: decorateMessage,
  formatMessageTime: formatMessageTime,
  headerTitle: headerTitle,
};

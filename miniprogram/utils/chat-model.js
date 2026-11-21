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
 * 轻量块解析 —— 与 iOS `TinyMarkdown.swift` / Android `MarkdownParser.kt` 同块集：
 * 围栏代码块（带语言）/ 标题 1-6 / 列表项 / 管道表格 / 段落。
 * 行内标记（**粗体** / *斜体* / `行内代码`）解析为 segments，供 WXML 嵌套 <text> 渲染。
 * 返回 [{ type, ... }]，type ∈ heading|text|listItem|code|table
 */
function parseBlocks(text) {
  const source = String(text == null ? '' : text);
  if (!source) return [];
  const lines = source.split('\n');
  const blocks = [];
  let index = 0;

  function pushText(content) {
    if (content.trim()) blocks.push({ type: 'text', content: content, segments: parseInline(content) });
  }

  while (index < lines.length) {
    const line = lines[index];

    if (line.trim().startsWith('```')) {
      const language = line.trim().slice(3).trim();
      index += 1;
      const code = [];
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        code.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) index += 1;
      blocks.push({ type: 'code', content: code.join('\n'), language: language });
      continue;
    }

    if (line.trim().startsWith('|')) {
      const tableLines = [];
      while (index < lines.length && lines[index].trim().startsWith('|')) {
        tableLines.push(lines[index]);
        index += 1;
      }
      const cellsOf = function (row) {
        const parts = row.split('|').map(function (cell) { return cell.trim(); });
        if (parts.length && parts[0] === '') parts.shift();
        if (parts.length && parts[parts.length - 1] === '') parts.pop();
        return parts;
      };
      if (tableLines.length < 2) {
        tableLines.forEach(function (row) { pushText(row); });
        continue;
      }
      const header = cellsOf(tableLines[0]).map(function (cell) {
        return { text: cell, segments: parseInline(cell) };
      });
      const rows = [];
      tableLines.slice(1).forEach(function (row) {
        if (/^\|[\s:|-]+\|\s*$/.test(row.trim())) return;
        rows.push(cellsOf(row).map(function (cell) {
          return { text: cell, segments: parseInline(cell) };
        }));
      });
      if (header.length) blocks.push({ type: 'table', header: header, rows: rows });
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, content: heading[2], segments: parseInline(heading[2]) });
      index += 1;
      continue;
    }

    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) {
      blocks.push({ type: 'listItem', content: bullet[1], segments: parseInline(bullet[1]) });
      index += 1;
      continue;
    }

    if (!line.trim()) {
      index += 1;
      continue;
    }

    // 连续非空行合并为一段
    const para = [line];
    index += 1;
    while (
      index < lines.length &&
      lines[index].trim() &&
      !lines[index].trim().startsWith('```') &&
      !lines[index].trim().startsWith('|') &&
      !/^#{1,6}\s+/.test(lines[index]) &&
      !/^\s*[-*]\s+/.test(lines[index])
    ) {
      para.push(lines[index]);
      index += 1;
    }
    pushText(para.join('\n'));
  }
  return blocks;
}

/**
 * 行内标记 → segments[{ text, bold, italic, code }]。
 * 顺序与 Android `inlineAnnotated` 一致：先双星粗体，再单星斜体，再反引号行内代码；
 * 未闭合标记按字面量保留（流式半截文本不吞字符）。
 */
function parseInline(text) {
  const source = String(text == null ? '' : text);
  const segments = [];
  let buffer = '';
  let index = 0;

  function flush(flags) {
    if (buffer) {
      segments.push(Object.assign({ text: buffer }, flags || {}));
      buffer = '';
    }
  }

  while (index < source.length) {
    if (source.startsWith('**', index)) {
      const close = source.indexOf('**', index + 2);
      if (close < 0 || close === index + 2) {
        buffer += '*';
        index += 1;
      } else {
        flush();
        segments.push({ text: source.slice(index + 2, close), bold: true });
        index = close + 2;
      }
      continue;
    }
    if (source[index] === '*') {
      const close = source.indexOf('*', index + 1);
      if (close < 0 || close === index + 1) {
        buffer += '*';
        index += 1;
      } else {
        flush();
        segments.push({ text: source.slice(index + 1, close), italic: true });
        index = close + 1;
      }
      continue;
    }
    if (source[index] === '`') {
      const close = source.indexOf('`', index + 1);
      if (close < 0) {
        buffer += '`';
        index += 1;
      } else {
        flush();
        segments.push({ text: source.slice(index + 1, close), code: true });
        index = close + 1;
      }
      continue;
    }
    buffer += source[index];
    index += 1;
  }
  flush();
  return segments;
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


// MARK: - 挂起交互解析（审批卡数据面；§10 宽容链 + iOS parseInteraction 同构）

/**
 * 宽容解析单条挂起交互（形状【移植·iOS parseInteraction 同构】）：
 * - id 链：id | interactionId | payload.interactionId（§10）
 * - kind 链：kind | payload.kind；⚠ ExitPlanMode 真实形态 kind="userInput"、
 *   plan_approval 只在 payload.schema.interaction、正文在 payload.input.plan
 *   【实证·上游仓 product-projection.ts:3117-3134】——按 schema 归位 kind，
 *   标题优先 payload.prompt（审核原因）
 * - options 链：对象取 id|optionId + label|title，或裸字符串（元素形状未取证，宽容）
 * - questions 链（提问族）：{question, multiSelect?, options?}（iOS 同构宽容）
 */
function parsePendingInteraction(json) {
  if (!json || typeof json !== 'object') return null;
  const payload = json.payload && typeof json.payload === 'object' ? json.payload : {};
  const id = json.id || json.interactionId || payload.interactionId;
  let kind = json.kind || payload.kind;
  if (!id || !kind) return null;
  let title = json.title || json.toolName || payload.title || payload.toolName || '';
  const schema = (json.schema && typeof json.schema === 'object' ? json.schema : null)
    || (payload.schema && typeof payload.schema === 'object' ? payload.schema : null);
  if (kind === 'userInput' && schema && schema.interaction === 'plan_approval') {
    kind = 'plan_approval';
    if (payload.prompt) title = payload.prompt;
  }
  const command = json.command || json.inputText || payload.command
    || payload.inputText || payload.text || '';
  const impact = json.impact || json.description || payload.impact
    || payload.description || '';
  const rawOptions = json.options || payload.options || [];
  const options = (Array.isArray(rawOptions) ? rawOptions : []).map(function (el) {
    if (typeof el === 'string') return el ? { id: el, label: el } : null;
    if (!el || typeof el !== 'object') return null;
    const optId = el.id || el.optionId;
    if (!optId) return null;
    return { id: optId, label: el.label || el.title || optId };
  }).filter(function (o) { return !!o; });
  const rawQuestions = (json.questions && Array.isArray(json.questions)) ? json.questions
    : ((payload.questions && Array.isArray(payload.questions)) ? payload.questions : []);
  const questions = rawQuestions.map(function (q) {
    if (!q || typeof q !== 'object' || !q.question) return null;
    return {
      question: q.question,
      multiSelect: q.multiSelect === true,
      options: (Array.isArray(q.options) ? q.options : []).map(function (o) {
        if (typeof o === 'string') return { id: o, label: o };
        return (o && o.value) ? { id: o.value, label: o.label || o.value } : null;
      }).filter(function (o) { return !!o; }),
    };
  }).filter(function (q) { return !!q; });
  const rawReviewItems = json.reviewItems || json.items || json.hooks || json.reviewFlowItems;
  const reviewItems = (Array.isArray(rawReviewItems) ? rawReviewItems : []).map(function (r) {
    if (!r || typeof r !== 'object') return null;
    const itemId = r.reviewItemId || r.id || r.hookId;
    if (!itemId) return null;
    return { reviewItemId: itemId, title: r.title || r.name || String(itemId) };
  }).filter(function (r) { return !!r; });
  const plan = (payload.input && typeof payload.input === 'object' && payload.input.plan)
    || (schema && schema.plan) || '';
  return {
    id: id,
    kind: kind,
    title: title,
    command: command,
    impact: impact,
    options: options,
    questions: questions,
    reviewItems: reviewItems,
    plan: typeof plan === 'string' ? plan : JSON.stringify(plan),
  };
}

/** 数组级宽容：非数组/坏元素跳过（§10 缺键不出卡，不造假卡）。 */
function parsePendingInteractions(arr) {
  if (!Array.isArray(arr)) return [];
  return arr.map(parsePendingInteraction).filter(function (p) { return !!p; });
}

module.exports = {
  parseInline: parseInline,
  normalizeMessage: normalizeMessage,
  parseBlocks: parseBlocks,
  decorateMessage: decorateMessage,
  formatMessageTime: formatMessageTime,
  headerTitle: headerTitle,
  parsePendingInteraction: parsePendingInteraction,
  parsePendingInteractions: parsePendingInteractions,
};

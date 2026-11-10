// 连接管理器（应用级）—— 片三：wx 运行时接入 + sessions store 数据源点亮。
//
// 数据流：connectDesktop({baseUrl, token})
//   → rpc-connection.connect（server-info → WS → v4 握手）
//   → store.setIdentity({deviceName, accountName})
//   → store.setSource(读面 fn)（listTaskList → session-model 行形状映射）
//   → store.refresh()
//
// 纪律（AGENTS.md §5-13 / store.js 头注）：未连接/失败如实上屏，不填假数据；
// 读面失败保留 last-good（store.refresh 已按此实现）。address 持久化于
// wx.setStorageSync('biuz.desktop')（baseUrl/token），支持重连。

const rpcConnection = require('./rpc-connection.js');
const relayV2 = require('./relay-v2.js');
const wxTransport = require('./wx-transport.js');
const store = require('./store.js');
const chatStore = require('./chat-store.js');
const filesStore = require('./files-store.js');
const diffStore = require('./diff-store.js');
const trajectoryStore = require('./trajectory-store.js');
const capabilityStore = require('./capability-store.js');
const capability = require('./capability.js');
const devicesStore = require('./devices-store.js');

const STORAGE_KEY = 'biuz.desktop';

let active = null; // { client, serverInfo, workspace, hello, clientId }

function getState() {
  return active;
}

function lastAddress() {
  try {
    return wx.getStorageSync(STORAGE_KEY) || null;
  } catch (err) {
    return null;
  }
}

function saveAddress(baseUrl, token) {
  try {
    wx.setStorageSync(STORAGE_KEY, { baseUrl: baseUrl, token: token || '' });
  } catch (err) { /* 存储失败不影响连接 */ }
}

/**
 * 连接桌面并点亮 sessions store 数据源。失败 reject Error（message 直接上屏）。
 */
function connectDesktop(params) {
  const baseUrl = (params && params.baseUrl || '').trim();
  const token = (params && params.token || '').trim();
  if (!baseUrl) return Promise.reject(new Error('请先填写桌面端地址'));
  saveAddress(baseUrl, token);
  // 注入面：单测传假 socketFactory/httpGet/httpPost；运行时用 wx 薄壳
  const socketFactory = (params && params.socketFactory) || wxTransport.wxSocketFactory;
  const httpGet = (params && params.httpGet) || wxTransport.wxHttpGet;
  const httpPost = (params && params.httpPost) || wxTransport.wxHttpPost;
  return rpcConnection.connect({
    baseUrl: baseUrl,
    token: token,
    appVersion: '1.0.0',
    socketFactory: socketFactory,
    httpGet: httpGet,
  }).then(function (result) {
    active = result;
    store.setIdentity({
      deviceName: result.serverInfo.name || '桌面端',
      accountName: result.hello.authUserId || '',
    });
    store.setSource(function () {
      return result.client
        .call('zcode-task', 'listTaskList', [{ scope: 'all' }])
        .then(function (value) { return mapTaskRows(value); });
    });
    // 片四：chat/files/diff/trajectory 各页数据源同构接线（全部走 client 唯一出口）
    wireChatSource(result);
    wireFilesSource(result);
    wireGitSource(result);
    wireTrajectorySource(result);
    // 片五：能力清单四页（memory/skills/mcp/plugins）共用参数化读面
    capabilityStore.setSource(function (kind) {
      return capability.loadCapability(result.client, result.workspace, kind);
    });
    // 设备页（003）：设备目录来自中继账号服务（GET /api/v1/remote/device，
    // 需 OAuth 账号层）——LAN 直连如实拒绝（Mock 红线：不给假设备）；
    // 真机路径 = 中继 V2 接入波次
    devicesStore.setSource(function () {
      return Promise.reject(new Error('设备列表来自中继账号服务；当前为局域网直连，请用中继链接接入后查看'));
    });
    store.refresh();
    return result;
  });
}

/**
 * 中继 V2 连接（真机数据路径；写面波次⑥——Android RelayV2.kt + fetchToken 直译）：
 * relay link → POST /api/v1/remote/token（Bearer JWT）→ wss /remote/ws2 →
 * auth_init（文本帧，无 role）→ auth_ack paired → 桥内 rpc（二进制 rpc-frame）
 * → sessions 等 store 数据源同 LAN 口径点亮；设备页接中继设备目录
 * （GET /api/v1/remote/device，Bearer JWT——Mock 红线解除：JWT 在场才接）。
 */
/** 读取持久化 JWT（login 页 P-018 写入；connectRelayDesktop 自动回填）。 */
function getRelayJwt() {
  try { return wx.getStorageSync('biuz.jwt') || ''; } catch (err) { return ''; }
}

function connectRelayDesktop(params) {
  // relayLink 双形态：string（→ parseRelayLink）或 parsed object（跳过解析——
  // acceptance 缝 wss→ws 降级后传 parsed 对象）
  const relayLinkInput = params && params.relayLink;
  const parsed = typeof relayLinkInput === 'string'
    ? relayV2.parseRelayLink(relayLinkInput.trim())
    : relayLinkInput;
  // JWT 来源：显式传入 > 持久化（login 页粘贴写入）
  const jwt = (params && params.jwt || '').trim() || getRelayJwt();
  if (!parsed) return Promise.reject(new Error('中继链接无效（需 https + /remote/ + sid）'));
  if (!jwt) return Promise.reject(new Error('云中继需要登录 Z.ai 账号（缺 JWT）'));
  const socketFactory = (params && params.socketFactory) || wxTransport.wxSocketFactory;
  const httpPost = (params && params.httpPost) || wxTransport.wxHttpPost;
  const httpGetJson = (params && params.httpGetJson) || wxTransport.wxHttpGetJson;
  if (params.persist !== false) saveAddress(parsed.httpOrigin, jwt);
  return relayV2.connectRelayV2({
    relayLink: parsed,
    jwt: jwt,
    platform: 'miniprogram',
    appVersion: '1.0.0',
    socketFactory: socketFactory,
    httpPost: httpPost,
  }).then(function (result) {
    active = {
      client: makeRpcSocketClient(result),
      serverInfo: {
        name: parsed.machineName || '云端中继',
        version: parsed.desktopAppVersion || '',
      },
      workspace: { path: parsed.deviceMid || 'relay', workspaceIdentity: parsed.deviceMid },
      hello: { authUserId: '' },
      clientId: '',
      mode: 'relay-v2',
      jwt: jwt,
    };
    store.setIdentity({
      deviceName: active.serverInfo.name,
      accountName: '',
    });
    wireSources(active);
    store.refresh();
    return active;
  });
}

/** 桥内 rpc 客户端包装（auth 配对后的二进制 rpc-frame 面）。 */
function makeRpcSocketClient(result) {
  const rpcClient = require('./rpc-client.js');
  return rpcClient.createRpcClient({
    send: function (bytes) { result.socket.sendBinary(bytes); },
    onMessage: function (cb) { result.socket.onBinary(cb); },
    onClose: function (cb) { result.socket.onClosed(cb); },
  });
}

/** store 数据源装配（LAN/中继共用——同一 RPC 面）。 */
function wireSources(result) {
  const client = result.client;
  const workspacePath = result.workspace && result.workspace.path;
  store.setSource(function () {
    return client
      .call('zcode-task', 'listTaskList', [{ scope: 'all' }])
      .then(function (value) { return mapTaskRows(value); });
  });
  chatStore.setSource(function (sessionId) {
    return client
      .call('zcode-agent', 'readSessionMessages', [{ sessionId: sessionId, limit: 200 }])
      .then(function (value) { return mapChatMessages(value, sessionId); });
  });
  filesStore.setSource(function () {
    const root = workspacePath;
    return readDirRecursiveClient(client, root, 0).then(function (tree) {
      return { tree: tree, workspacePath: root };
    });
  });
  diffStore.setSource(function () {
    return client
      .call('git', 'getChanges', [{ workspacePath: workspacePath }])
      .then(function (value) { return mapGitChanges(value); });
  });
  trajectoryStore.setSource(function (sessionId) {
    return client
      .call('zcode-agent', 'readSessionMessages', [{ sessionId: sessionId, limit: 200 }])
      .then(function (value) {
        const mapped = mapChatMessages(value, sessionId);
        return { entries: mapped.messages, taskTitle: sessionId };
      });
  });
  capabilityStore.setSource(function (kind) {
    return capability.loadCapability(client, result.workspace, kind);
  });
  devicesStore.setSource(function () {
    // 中继设备目录（GET /api/v1/remote/device，Bearer JWT——Android
    // RelayDeviceCatalog.parse 直译）；jwt 缺席如实拒绝
    const jwt = (result.jwt || '').trim();
    if (!jwt) return Promise.reject(new Error('设备列表来自中继账号服务；当前未携带登录凭据'));
    const origin = result.httpOrigin || 'https://zcode.z.ai';
    const httpGetJson = (result.httpGetJson) || wxTransport.wxHttpGetJson;
    return httpGetJson(origin + '/api/v1/remote/device', jwt).then(function (response) {
      if (response.status !== 200) throw new Error('设备目录 HTTP ' + response.status);
      const json = response.body || {};
      const rows = json.data || [];
      return {
        devices: rows.map(function (row) {
          const meta = row.meta || {};
          return {
            deviceSid: row.device_sid, deviceMid: row.device_mid,
            online: row.online === true, name: meta.name || null,
            platform: meta.platform || null, version: meta.version || null,
          };
        }),
        account: null,
      };
    });
  });
}

/**
 * 行级反馈（P-020；Android RemoteConversationStore.setAssistantFeedback 同参）：
 * `zcode-agent.setAssistantFeedback` — payload {target:{rowId, entityId}, feedback:
 * "like"|"dislike"|null}。entityId 缺席如实拒（Android client.rowCursorUnavailable
 * 同口径）。返回 null=成功；非 null=可上屏错误文案。
 */
function setAssistantFeedback(sessionId, rowId, entityId, value) {
  const conn = active && active.client;
  if (!conn) return Promise.reject(new Error('未连接桌面端'));
  if (!entityId) return Promise.reject(new Error('该消息不可反馈（缺少行游标）'));
  const payload = {
    target: { rowId: rowId, entityId: entityId },
    feedback: value === null ? null : (value ? 'like' : 'dislike'),
  };
  return conn
    .call('zcode-agent', 'setAssistantFeedback', [payload])
    .then(function () { return null; })
    .catch(function (err) {
      return '反馈失败 · ' + ((err && err.message) || String(err)).substring(0, 120);
    });
}

function disconnect() {
  if (active && active.client && active.client.close) {
    active.client.close();
  }
  active = null;
  store.setSource(null);
  store.refresh();
}

/**
 * listTaskList 回执 → session-model 行形状（normalizeRow 消费键）。
 * 宽容映射：sessionId|id 二键、pinned|isPinned 二键、workspacePath 末段兜底
 * projectName；认不出的条目跳过（不整份失败）。
 */
function mapTaskRows(value) {
  const items = (value && value.items) || [];
  return items
    .map(function (item) {
      if (!item || typeof item !== 'object') return null;
      const id = item.sessionId || item.id;
      if (!id) return null;
      let projectName = item.projectName || null;
      if (!projectName && item.workspacePath) {
        const segments = String(item.workspacePath).split('/').filter(Boolean);
        projectName = segments.length ? segments[segments.length - 1] : null;
      }
      return {
        id: String(id),
        title: item.title || '未命名会话',
        summary: item.summary || item.preview || '',
        status: item.status || 'idle',
        updatedAt: Number(item.updatedAt || 0),
        isPinned: item.pinned === true || item.isPinned === true,
        unread: item.unread === true || item.unreadCount > 0,
        projectName: projectName,
        groupName: item.groupName || null,
        workflowActivity: item.workflowActivity || null,
      };
    })
    .filter(function (row) { return row !== null; });
}

// ---- 片四：各页数据源接线（形状证据见各映射函数头注） ----

/** 递归读目录（client 直收版——中继/局域网共用；深度 3 层）。 */
function readDirRecursiveClient(client, dirPath, depth) {
  if (depth > 3) return Promise.resolve([]);
  return client
    .call('file', 'readdir', [{ path: dirPath }])
    .then(function (value) {
      const entries = (value && (value.entries || value)) || [];
      if (!Array.isArray(entries)) return [];
      return Promise.all(
        entries.map(function (entry) {
          if (!entry || typeof entry !== 'object') return null;
          const name = entry.name || entry.fileName || '';
          if (!name) return null;
          const entryPath = entry.path || dirPath + '/' + name;
          const isDirectory = entry.isDirectory === true || entry.directory === true
            || entry.kind === 'directory';
          const node = {
            id: entryPath,
            name: name,
            path: entryPath,
            isDirectory: isDirectory,
            size: entry.size == null ? null : Number(entry.size),
            children: null,
          };
          if (!isDirectory) return node;
          return readDirRecursiveClient(client, entryPath, depth + 1)
            .then(function (children) { node.children = children; return node; });
        }),
      ).then(function (nodes) { return nodes.filter(function (n) { return n !== null; }); });
    })
    .catch(function () { return []; });
}

/**
 * 会话页数据源：`zcode-agent.readSessionMessages {sessionId, limit?}` →
 * ZCodeMessageWithParts[]（zcode-protocol-legacy-types.ts:458-463 strict：
 * {info:{role: user|assistant,…}, parts:[{type: text|reasoning|tool…, text?}]})。
 * 映射到 chat-store {title, messages:[{role, kind, text}]}（chat-model
 * normalizeMessage 消费键：role user|agent、kind tool|thinking|text）。
 */
function wireChatSource(result) {
  chatStore.setSource(function (sessionId) {
    return result.client
      .call('zcode-agent', 'readSessionMessages', [
        { sessionId: sessionId, limit: 200 },
      ])
      .then(function (value) { return mapChatMessages(value, sessionId); });
  });
}

function mapChatMessages(value, sessionId) {
  const messages = (Array.isArray(value) ? value : [])
    .map(function (msg) {
      if (!msg || typeof msg !== 'object' || !msg.parts) return null;
      const role = msg.info && msg.info.role === 'user' ? 'user' : 'agent';
      const texts = { text: [], thinking: [] };
      let toolText = null;
      (msg.parts || []).forEach(function (part) {
        if (!part || typeof part !== 'object') return;
        if (part.type === 'text' && part.text) texts.text.push(part.text);
        else if (part.type === 'reasoning' && part.text) texts.thinking.push(part.text);
        else if (part.type === 'tool_use' || part.type === 'tool') {
          toolText = part.name || part.toolName || '工具调用';
        }
      });
      if (toolText) {
        return { role: role, kind: 'tool', text: toolText };
      }
      if (texts.thinking.length) {
        return { role: role, kind: 'thinking', text: texts.thinking.join('\n\n') };
      }
      if (!texts.text.length) return null; // 无可见内容的行不渲染（web 同口径）
      return { role: role, kind: 'text', text: texts.text.join('\n\n') };
    })
    .filter(function (row) { return row !== null; });
  return { title: sessionId, messages: messages };
}

/**
 * 文件页数据源：`file.readdir {path, includeHidden?}` → FileEntry[] 递归
 * （Android RemoteFileStore.fileTree 同法：深度限制 3 层、跳过隐藏目录）。
 * FileEntry 字段宽容读取（name/path/directory|isDirectory/size）。
 */
function wireFilesSource(result) {
  filesStore.setSource(function () {
    const root = result.workspace.path;
    return readDirRecursive(result, root, 0).then(function (tree) {
      return { tree: tree, workspacePath: root };
    });
  });
}

function readDirRecursive(result, dirPath, depth) {
  if (depth > 3) return Promise.resolve([]);
  return result.client
    .call('file', 'readdir', [{ path: dirPath }])
    .then(function (value) {
      const entries = (value && (value.entries || value)) || [];
      if (!Array.isArray(entries)) return [];
      return Promise.all(
        entries.map(function (entry) {
          if (!entry || typeof entry !== 'object') return null;
          const name = entry.name || entry.fileName || '';
          if (!name) return null;
          const entryPath = entry.path || dirPath + '/' + name;
          const isDirectory = entry.isDirectory === true || entry.directory === true
            || entry.kind === 'directory';
          const node = {
            id: entryPath,
            name: name,
            path: entryPath,
            isDirectory: isDirectory,
            size: entry.size == null ? null : Number(entry.size),
            children: null,
          };
          if (!isDirectory) return node;
          return readDirRecursive(result, entryPath, depth + 1)
            .then(function (children) { node.children = children; return node; });
        }),
      ).then(function (nodes) { return nodes.filter(function (n) { return n !== null; }); });
    })
    // 读失败如实上抛（files-store last-good + 页面重试面承接——读面失败
    // 不折叠成空态，§5-12 同口径）；depth 递归上限见函数头。
}

/**
 * Diff 页数据源：`git.getChanges {workspacePath}`（Android RemoteFileStore:449
 * 同参同命令）→ 变更条目 {path, status} 映射 diff-store files。
 */
function wireGitSource(result) {
  diffStore.setSource(function () {
    return result.client
      .call('git', 'getChanges', [{ workspacePath: result.workspace.path }])
      .then(function (value) { return mapGitChanges(value); });
  });
}

function mapGitChanges(value) {
  const items = (value && (value.changes || value.files || value)) || [];
  if (!Array.isArray(items)) return [];
  return items
    .map(function (item) {
      if (!item || typeof item !== 'object') return null;
      const path = item.path || item.filePath || item.file;
      if (!path) return null;
      return {
        path: String(path),
        status: item.status || item.state || 'modified',
        additions: item.additions == null ? null : Number(item.additions),
        deletions: item.deletions == null ? null : Number(item.deletions),
      };
    })
    .filter(function (row) { return row !== null; });
}

/**
 * 任务轨迹页数据源：readSessionMessages 复用（思考/工具/回复三分类——
 * Android TaskOutputScreen 行投影同口径），taskTitle 由调用方（会话行）持有，
 * 此处回传 sessionId。
 */
function wireTrajectorySource(result) {
  trajectoryStore.setSource(function (sessionId) {
    return result.client
      .call('zcode-agent', 'readSessionMessages', [{ sessionId: sessionId, limit: 200 }])
      .then(function (value) {
        const mapped = mapChatMessages(value, sessionId);
        return { entries: mapped.messages, taskTitle: sessionId };
      });
  });
}

module.exports = {
  connectDesktop: connectDesktop,
  connectRelayDesktop: connectRelayDesktop,
  getRelayJwt: getRelayJwt,
  setAssistantFeedback: setAssistantFeedback,
  connectRelayDesktop: connectRelayDesktop,
  mapChatMessages: mapChatMessages,
  mapGitChanges: mapGitChanges,
  disconnect: disconnect,
  getState: getState,
  lastAddress: lastAddress,
  mapTaskRows: mapTaskRows,
};

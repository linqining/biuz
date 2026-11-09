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
const wxTransport = require('./wx-transport.js');
const store = require('./store.js');

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
  // 注入面：单测传假 socketFactory/httpGet；运行时用 wx 薄壳
  const socketFactory = (params && params.socketFactory) || wxTransport.wxSocketFactory;
  const httpGet = (params && params.httpGet) || wxTransport.wxHttpGet;
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
    store.refresh();
    return result;
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

module.exports = {
  connectDesktop: connectDesktop,
  disconnect: disconnect,
  getState: getState,
  lastAddress: lastAddress,
  mapTaskRows: mapTaskRows,
};

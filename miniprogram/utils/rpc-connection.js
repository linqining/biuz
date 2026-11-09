// 桌面连接（LAN 直连）—— Android `ZCodeServerConnection.kt:40`（server-info →
// WS /ws?token → v4 握手 → workspaces[0]）的 JS 移植。
//
// 线缆事实【实证·Android/iOS 同源实现 + 上游仓】：
// - server-info：GET {base}/api/server-info?token=… → {name?, version,
//   protocolVersion(=1), authRequired, workspaces[{path, label?, workspaceIdentity?}],
//   capabilities{…bools}}；
// - WS：ws(s)://{host}/ws?token=…，二进制 RPC 帧（rpc-serialization.js）；
// - v4 握手：zcode-agent.helloConversationV4(undefined) → 校验 protocolVersion=3、
//   clientMode="web-remote-replayable" → zcode-agent.initializeConversationV4
//   clientHello {kind:"clientHello", protocolVersion:3, clientId, clientKind:"mobileApp",
//   appVersion, capabilities?: {workflowRunDeltas:true} 仅回显 Host 宣告过的键
//   （A5 单向规则，transport.ts:72-83 .strict()）}；
// - clientId = "zcode-mobile-" + 8 位随机（Android :422 同构）。
//
// socket 工厂注入形态（单测用内存假 socket；运行时 wx.connectSocket 承载）：
//   factory({url}) → { send(Uint8Array), onMessage(cb), onClose(cb), close() }

const rpc = require('./rpc-serialization.js');
const clientFactory = require('./rpc-client.js');

const WIRE_PROTOCOL_VERSION = 3;
const SERVER_INFO_PROTOCOL_VERSION = 1;

/**clientId 生成（Android :422 同构；随机 8 位小写字母数字）。 */
function makeClientId() {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let suffix = '';
  for (let i = 0; i < 8; i++) {
    suffix += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return 'zcode-mobile-' + suffix;
}

/** 解析 server-info（宽容键读取；protocolVersion 必须 =1）。 */
function parseServerInfo(json) {
  if (!json || typeof json !== 'object') return null;
  const workspaces = (json.workspaces || [])
    .map(function (item) {
      if (!item || typeof item !== 'object' || !item.path) return null;
      return {
        path: item.path,
        label: item.label || '',
        workspaceIdentity: item.workspaceIdentity || '',
      };
    })
    .filter(function (item) { return item !== null; });
  const capabilities = [];
  if (json.capabilities && typeof json.capabilities === 'object') {
    Object.keys(json.capabilities).forEach(function (key) {
      if (json.capabilities[key] === true) capabilities.push(key);
    });
    capabilities.sort();
  }
  return {
    serverId: json.serverId || '',
    name: json.name || '',
    version: json.version || '',
    protocolVersion: json.protocolVersion || 0,
    authRequired: json.authRequired === true,
    workspaces: workspaces,
    capabilities: capabilities,
  };
}

/** 解析 hello 回执（V4HelloMessage 直译）。 */
function parseHello(json) {
  if (!json || typeof json !== 'object') return null;
  const capabilities = {};
  if (json.capabilities && typeof json.capabilities === 'object') {
    Object.keys(json.capabilities).forEach(function (key) {
      capabilities[key] = json.capabilities[key] === true;
    });
  }
  return {
    protocolVersion: json.protocolVersion || 0,
    connectionId: json.connectionId || '',
    clientMode: json.clientMode || '',
    deliveryProfile: json.deliveryProfile || '',
    serverTime: json.serverTime || null,
    capabilities: capabilities,
    authUserId: (json.auth && json.auth.userId) || null,
  };
}

/**
 * 连接并完成 v4 握手。
 * @param params {baseUrl, token?, appVersion?, socketFactory?, httpGet?}
 *   socketFactory({url}) → {send,onMessage,onClose,close}；httpGet(url) → Promise<{status, body}>
 *   （运行时 wx.request 承载；单测注入假件）。
 * @returns Promise<{client, serverInfo, workspace, hello}>（失败 reject Error）。
 */
function connect(params) {
  const httpGet = params.httpGet;
  const socketFactory = params.socketFactory;
  const clientId = params.clientId || makeClientId();
  const appVersion = params.appVersion || '1.0';
  const token = params.token || '';

  let url = params.baseUrl.replace(/\/+$/, '') + '/api/server-info';
  if (token) url += '?token=' + encodeURIComponent(token);
  return httpGet(url).then(function (response) {
    if (response.status < 200 || response.status > 299) {
      throw new Error('server-info HTTP ' + response.status);
    }
    const info = parseServerInfo(response.body);
    if (!info) throw new Error('server-info 解析失败');
    if (info.protocolVersion !== SERVER_INFO_PROTOCOL_VERSION) {
      throw new Error('协议版本不符：remote v' + info.protocolVersion);
    }
    if (!info.workspaces.length) throw new Error('workspaces=[]（桌面未打开任何工作区）');
    const workspace = info.workspaces[0];

    let wsUrl = params.baseUrl.replace(/^http/, 'ws').replace(/\/+$/, '') + '/ws';
    if (token) wsUrl += '?token=' + encodeURIComponent(token);
    return new Promise(function (resolve, reject) {
      const socket = socketFactory({ url: wsUrl });
      const client = clientFactory.createRpcClient(socket, { timeoutMs: 5000 });
      socket.onClose(function () { /* client 侧 onClose 已处理 pending */ });
      // hello（undefined args）
      client
        .call('zcode-agent', 'helloConversationV4', [])
        .then(function (helloValue) {
          const hello = parseHello(helloValue);
          if (!hello) throw new Error('hello 解析失败');
          if (hello.protocolVersion !== WIRE_PROTOCOL_VERSION) {
            throw new Error('协议版本不符：v4 wire v' + hello.protocolVersion);
          }
          if (hello.clientMode !== 'web-remote-replayable') {
            throw new Error('clientMode=' + hello.clientMode);
          }
          // clientHello（A5 单向：capabilities 仅回显 Host 宣告过的键）
          const fields = {
            kind: 'clientHello',
            protocolVersion: WIRE_PROTOCOL_VERSION,
            clientId: clientId,
            clientKind: 'mobileApp',
            appVersion: appVersion,
          };
          if (hello.capabilities.workflowRunDeltas === true) {
            fields.capabilities = { workflowRunDeltas: true };
          }
          return client
            .call('zcode-agent', 'initializeConversationV4', [fields])
            .then(function () { return hello; });
        })
        .then(function (hello) {
          resolve({
            client: client,
            serverInfo: info,
            workspace: workspace,
            hello: hello,
            clientId: clientId,
          });
        })
        .catch(reject);
    });
  });
}

module.exports = {
  WIRE_PROTOCOL_VERSION: WIRE_PROTOCOL_VERSION,
  SERVER_INFO_PROTOCOL_VERSION: SERVER_INFO_PROTOCOL_VERSION,
  makeClientId: makeClientId,
  parseServerInfo: parseServerInfo,
  parseHello: parseHello,
  connect: connect,
};

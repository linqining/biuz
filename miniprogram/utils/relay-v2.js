// 中继 V2 传输（真机数据路径）—— Android RelayV2.kt + ConnectURLParser
// `parseRelayLink` 的 JS 移植（形状逐字对齐，出处行号见各函数头注）。
//
// V2 事实（RelayV2.kt:15-17 头注）：
// 1. 建连 URL = `wss://<host>/api/v1/remote/ws2?token=<一次性>&mid=<mid>`；
// 2. 每次建连用 Z.ai JWT 现换一次性 token（POST /api/v1/remote/token
//    {role:"terminal"}，Bearer JWT；401=登录失效；token 60s 有效、WS Upgrade
//    前原子消费）；
// 3. auth_init **不带 role**，meta 上报终端身份，client_ts 为**秒**（V2 严格拒绝
//    毫秒）——JSON **文本帧**发送（Android sendJSONLocked :socket.send(text)）；
// 4. 心跳 5s±1s·ACK 15s（V1 10/2/30）。
// 配对成功后同一 socket 走二进制 rpc-frame（rpc-serialization/rpc-client 复用）。
//
// JWT 来源：中继链接页面由用户粘贴 JWT（或后续 login 页 OAuth 接入——P-018）；
// 持久化落 `utils/credential-store.js` 的加密容器（原注释里的 `biuz.relay` 键从未实现，
// 实际明文键是 `biuz.jwt`，已于 2026-11-19 迁入加密容器——见 APP-DIFF[D014]）。

const FRAME_TYPE_REGULAR = 1;
const FRAME_HEADER_SIZE = 13;

/** 中继链接解析（ConnectURLParser.kt:69-103 直译）：https + path=/remote/ +
 *  无 token= 查询项；sid 必带；hash 缺席=V2（ws2），有 hash=V1（本传输只走 V2）。 */
function parseRelayLink(input) {
  const trimmed = String(input == null ? '' : input).trim();
  if (!trimmed) return null;
  const pieces = trimmed.split(/\s+/).filter(Boolean);
  for (const piece of pieces) {
    const config = parseRelayLinkToken(piece);
    if (config) return config;
  }
  return parseRelayLinkToken(trimmed);
}

function parseRelayLinkToken(piece) {
  // URL 解析（wx/Node 双环境：无 URL 构造器时用正则）
  let m = /^https:\/\/([^/?#\s]+)(\/[^?#]*)?(\?[^#\s]*)?$/i.exec(piece);
  if (!m) return null;
  const host = m[1];
  const path = (m[2] || '').toLowerCase();
  if (path.indexOf('/remote/') !== 0) return null;
  const query = m[3] || '';
  if (query.toLowerCase().indexOf('token=') >= 0) return null;
  const params = {};
  query.replace(/^\?/, '').split('&').forEach(function (pair) {
    if (!pair) return;
    const kv = pair.split('=');
    params[kv[0].toLowerCase()] = decodeURIComponent(kv[1] || '');
  });
  const sid = params.sid;
  if (!sid) return null;
  const hash = params.hash;
  const mid = params.mid || null;
  const isV2 = !hash;
  if (!isV2) return null; // 本传输只走 V2（V1 保留 Android/iOS 承载）
  // V2 不在 parse 时追加 mid（Android 直译：mid 由 buildSocketUrl 与 token 一并追加）
  const wss = 'wss://' + host + '/api/v1/remote/ws2';
  return {
    wssUrl: wss,
    httpOrigin: 'https://' + host,
    machineName: params.name || null,
    deviceSid: sid,
    deviceMid: mid,
    desktopAppVersion: params.app_version || null,
    protocolVersion: 2,
  };
}

/** V2 建连 URL（RelayV2.kt:24-41 直译）：替换 token/mid 查询项后追加本次新 token。 */
function buildSocketUrl(baseWssUrl, token, mid) {
  let base = baseWssUrl;
  if (base.indexOf('://') < 0) base = 'wss://' + base;
  const queryIndex = base.indexOf('?');
  const kept = [];
  if (queryIndex >= 0) {
    base.slice(queryIndex + 1).split('&').forEach(function (pair) {
      const key = pair.split('=')[0].toLowerCase();
      if (pair && key !== 'token' && key !== 'mid') kept.push(pair);
    });
  }
  kept.push('token=' + encodeURIComponent(token));
  if (mid) kept.push('mid=' + encodeURIComponent(mid));
  const clean = queryIndex >= 0 ? base.slice(0, queryIndex) : base;
  return clean + '?' + kept.join('&');
}

/** V2 auth_init（RelayV2.kt:43-65 直译）：无 role，meta 身份，client_ts 秒。 */
function buildAuthInitText(config, clientTsSeconds, platform, appVersion, clientName) {
  return JSON.stringify({
    type: 'auth_init',
    device_sid: config.deviceSid,
    meta: { platform: platform, version: appVersion, name: clientName },
    client_ts: clientTsSeconds,
  });
}

/** 令牌回执解析（RelayV2.kt:67-74 直译）：{code:0,data:{token}}；否则 null。 */
function parseTokenResponse(body) {
  let json;
  try {
    json = typeof body === 'string' ? JSON.parse(body) : body;
  } catch (err) {
    return null;
  }
  if (!json || typeof json !== 'object') return null;
  if (json.code != null && json.code !== 0) return null;
  const token = json.data && typeof json.data === 'object'
    ? String(json.data.token || '').trim() : '';
  return token || null;
}

/**
 * 连接中继 V2 并完成 auth 握手（Android fetchToken + ws2 建连 + auth_init 直译）。
 * @param params {relayLink(解析产物|链接原文), jwt, appVersion?, platform?,
 *                socketFactory({url})→{send(text|Uint8Array),onMessage(cb),onClose(cb)},
 *                httpPost(url, jwt, bodyObj)→Promise<{status, body}>}
 * @returns Promise<{socket, rpcSocket, config}>——rpcSocket 为桥内 RPC 通道
 *          （配对成功后同一 socket 走二进制 rpc-frame；onMessage 已分诊 text=auth
 *           / binary=rpc）。auth 非 paired（waiting/conflict 等）reject。
 */
function connectRelayV2(params) {
  const config = typeof params.relayLink === 'string'
    ? parseRelayLink(params.relayLink) : params.relayLink;
  if (!config) return Promise.reject(new Error('中继链接无效（需 https + /remote/ + sid，V2 无 hash）'));
  if (!params.jwt) return Promise.reject(new Error('云中继需要登录 Z.ai 账号（缺 JWT）'));
  const httpPost = params.httpPost;
  const socketFactory = params.socketFactory;
  const platform = params.platform || 'miniprogram';
  const appVersion = params.appVersion || '1.0';
  const clientName = params.clientName || '朱批';

  return httpPost(config.httpOrigin + '/api/v1/remote/token', params.jwt, { role: 'terminal' })
    .then(function (response) {
      if (response.status === 401) {
        throw new Error('云中继登录已失效，请重新登录 Z.ai 账号');
      }
      if (response.status < 200 || response.status > 299) {
        throw new Error('中继 token 请求失败（HTTP ' + response.status + '）');
      }
      const token = parseTokenResponse(response.body);
      if (!token) throw new Error('中继 token 回执无效');
      return token;
    })
    .then(function (token) {
      return new Promise(function (resolve, reject) {
        const socket = socketFactory({ url: buildSocketUrl(config.wssUrl, token, config.deviceMid) });
        const messageCbs = [];
        const closeCbs = [];
        let settled = false;
        socket.onMessage(function (data) {
          if (typeof data === 'string') {
            // auth 面文本帧：auth_ack / error
            let auth;
            try { auth = JSON.parse(data); } catch (e) { return; }
            if (auth && auth.type === 'auth_ack') {
              if (!settled) {
                settled = true;
                resolve({
                  config: config,
                  socket: {
                    sendBinary: function (bytes) { socket.send(bytes); },
                    onBinary: function (cb) { messageCbs.push(function (d) { cb(d); }); },
                    onClosed: function (cb) { closeCbs.push(cb); },
                    close: function () { socket.close(); },
                  },
                });
              }
            } else if (auth && auth.type === 'auth_error') {
              if (!settled) {
                settled = true;
                reject(new Error(auth.message || '中继 auth 失败'));
              }
            }
          } else if (data instanceof Uint8Array) {
            // 二进制帧透传（配对后桥内 rpc）
            messageCbs.forEach(function (cb) { cb(data); });
          }
        });
        socket.onClose(function () {
          closeCbs.forEach(function (cb) { cb(); });
          if (!settled) {
            settled = true;
            reject(new Error('中继连接已关闭（auth 未完成）'));
          }
        });
        // open 后发 auth_init 文本帧（wx onOpen 排队语义在 wx-transport 内；
        // 此处 socketFactory 直接给 send 排队能力——注释见 wx-transport）
        socket.send(buildAuthInitText(
          config, Math.floor(Date.now() / 1000), platform, appVersion, clientName));
      });
    });
}

module.exports = {
  parseRelayLink: parseRelayLink,
  buildSocketUrl: buildSocketUrl,
  buildAuthInitText: buildAuthInitText,
  parseTokenResponse: parseTokenResponse,
  connectRelayV2: connectRelayV2,
};

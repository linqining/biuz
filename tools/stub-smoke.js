// 小程序 RPC 数据面端到端 smoke（node tools/stub-smoke.js）
// —— rpc-connection.connect 全层直打 tools/stub-desktop.js 替身：
// server-info(HTTP) → WS（net + RFC6455 客户端掩码）→ v4 握手 → listTaskList。
// 退出码 0 = 全链通；1 = 失败（附阶段日志）。
const net = require('net');
const crypto = require('crypto');
const rpc = require('../miniprogram/utils/rpc-serialization.js');
const connection = require('../miniprogram/utils/rpc-connection.js');

const port = Number(process.argv[process.argv.indexOf('--port') + 1] || 9100);

/** net socket → rpc-connection 需要的 socket 形态（WS 客户端帧掩码）。 */
function netSocketAdapter(socket) {
  let acc = Buffer.alloc(0);
  const messageCbs = [];
  const closeCbs = [];
  let handshaken = false;
  const sendQueue = [];
  // WS 升级请求（net 层不发这个就不是 WS）
  socket.on('connect', function () {
    socket.write(
      'GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n' +
      'Connection: Upgrade\r\nSec-WebSocket-Key: ' +
      crypto.randomBytes(16).toString('base64') + '\r\nSec-WebSocket-Version: 13\r\n\r\n');
  });
  socket.on('data', function (chunk) {
    acc = Buffer.concat([acc, chunk]);
    if (!handshaken) {
      // 首块含 HTTP 101 响应——跳过到 \r\n\r\n，剩余喂帧解析；冲排队帧
      const idx = acc.indexOf('\r\n\r\n');
      if (idx < 0) return;
      handshaken = true;
      const rest = acc.subarray(idx + 4);
      acc = Buffer.alloc(0);
      if (rest.length) feedBytes(rest);
      while (sendQueue.length) sendFrame(sendQueue.shift());
      return;
    }
    feedBytes(acc);
    acc = Buffer.alloc(0);
  });
  function feedBytes(chunk) {
    acc = Buffer.concat([acc, chunk]);
    for (;;) {
      if (acc.length < 2) return;
      let len = acc[1] & 0x7f;
      let off = 2;
      if (len === 126) { if (acc.length < 4) return; len = acc.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (acc.length < 10) return; len = Number(acc.readBigUInt64BE(2)); off = 10; }
      if (acc.length < off + len) return;
      const payload = acc.subarray(off, off + len);
      acc = acc.subarray(off + len);
      const bytes = new Uint8Array(payload);
      messageCbs.forEach(function (cb) { cb(bytes); });
    }
  }
  function sendFrame(bytes) {
    console.log('[smoke-sent] len =', bytes.length, 'head =', Buffer.from(bytes.subarray(0, 6)).toString('hex'));
    // bytes 已是完整 RPC 帧（rpc-client buildRequestFrame 含 13 字节头）——
    // 只做 WS 层掩码，不再包 RPCFrame（二次包装曾致替身解析失败）
    const frame = bytes;
    const maskKey = crypto.randomBytes(4);
    const len = frame.length;
    let lenBytes;
    if (len < 126) lenBytes = Buffer.from([0x82, 0x80 | len]);
    else if (len < 65536) {
      // 16 位长度：[0x82, 0x80|126, len16BE(2B), mask4]——len 写偏移 2（曾错写 1
      // 覆盖标志字节 → 替身读到 len=0 流失配）
      lenBytes = Buffer.alloc(4);
      lenBytes[0] = 0x82; lenBytes[1] = 0x80 | 126;
      lenBytes.writeUInt16BE(len, 2);
    } else {
      lenBytes = Buffer.alloc(10);
      lenBytes[0] = 0x82; lenBytes[1] = 0x80 | 127;
      lenBytes.writeBigUInt64BE(BigInt(len), 2);
    }
    const masked = Buffer.from(frame);
    for (let i = 0; i < masked.length; i++) masked[i] ^= maskKey[i % 4];
    socket.write(Buffer.concat([lenBytes, maskKey, masked]));
  }
  socket.on('close', function () { closeCbs.forEach(function (cb) { cb(); }); });
  socket.on('error', function () { closeCbs.forEach(function (cb) { cb(); }); });
  return {
    send: function (bytes) {
      if (!handshaken) { sendQueue.push(bytes); return; }
      sendFrame(bytes);
    },
    onMessage: function (cb) { messageCbs.push(cb); },
    onClose: function (cb) { closeCbs.push(cb); },
    close: function () { try { socket.end(); } catch (e) { /* 幂等 */ } },
  };
}

const httpGet = function (url) {
  return new Promise(function (resolve, reject) {
    require('http').get(url, function (res) {
      let body = '';
      res.on('data', function (c) { body += c; });
      res.on('end', function () {
        try { resolve({ status: res.statusCode, body: JSON.parse(body) }); }
        catch (e) { reject(new Error('server-info JSON 解析失败')); }
      });
    }).on('error', reject);
  });
};

connection.connect({
  baseUrl: 'http://127.0.0.1:' + port,
  token: '',
  appVersion: '1.0.0',
  socketFactory: function (params) {
    return netSocketAdapter(net.connect(Number(new URL(params.url).port), '127.0.0.1'));
  },
  httpGet: httpGet,
}).then(function (result) {
  console.log('[smoke] 握手完成 · desktop =', result.serverInfo.name,
    '· workspace =', result.workspace.path,
    '· clientId =', result.clientId);
  return result.client.call('zcode-task', 'listTaskList', [{ scope: 'all' }]);
}).then(function (rows) {
  const items = (rows && rows.items) || [];
  console.log('[smoke] listTaskList items =', items.length,
    '· first =', items[0] && items[0].title);
  if (!items.length) throw new Error('替身应回 2 条样例会话');
  console.log('SMOKE-OK');
  process.exit(0);
}).catch(function (err) {
  console.error('SMOKE-FAIL:', err.message);
  process.exit(1);
});
setTimeout(function () { console.error('SMOKE-TIMEOUT'); process.exit(1); }, 8000);

// 小程序 automator 实测用替身桌面（node tools/stub-desktop.js --port 9100）
// ——零依赖：HTTP server-info + WS 升级（RFC6455 最小实现）+ RPC 帧（wire 口径
// 与 miniprogram/utils/rpc-serialization.js 同一套，直接 require 复用）。
// 行为：hello → clientHello 校验 → listTaskList 回 2 条样例会话（供 sessions
// 页点亮断言）。仅测试用途。

const http = require('http');
const crypto = require('crypto');
const path = require('path');
const rpc = require(path.join(__dirname, '..', 'miniprogram', 'utils', 'rpc-serialization.js'));

const port = Number(process.argv[process.argv.indexOf('--port') + 1] || 9100);
const seen = { hello: 0, clientHello: null, listTaskList: 0 };

const STUB_INFO = {
  serverId: 'stub-1',
  name: 'MP Stub Desktop',
  version: '3.17.2',
  protocolVersion: 1,
  authRequired: false,
  workspaces: [{ path: '/Users/e2e/zcode-workspace', label: 'e2e 主工作区' }],
  capabilities: { workflowRunDeltas: true },
};

const STUB_ROWS = {
  items: [
    {
      sessionId: 'sess-stub-1', title: '重构会话持久层', summary: '迁移调用方',
      status: 'running', updatedAt: Date.now() - 60000, pinned: true,
      workspacePath: '/Users/e2e/zcode-mobile', unreadCount: 2,
    },
    {
      sessionId: 'sess-stub-2', title: '生成周报 · 第 40 周', summary: '',
      status: 'completed', updatedAt: Date.now() - 86400000,
      workspacePath: '/Users/e2e/zcode-mobile',
    },
  ],
};

const server = http.createServer(function (req, res) {
  if (req.url && req.url.indexOf('/api/server-info') === 0) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(STUB_INFO));
    return;
  }
  res.writeHead(404);
  res.end('not found');
});

server.on('upgrade', function (req, socket) {
  const key = req.headers['sec-websocket-key'];
  if (!key) { socket.destroy(); return; }
  const accept = crypto
    .createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n',
  );
  let acc = Buffer.alloc(0);
  socket.on('data', function (chunk) {
    acc = Buffer.concat([acc, chunk]);
    console.error('[mp-stub] acc head =', Buffer.from(acc.subarray(0, 10)).toString('hex'), 'len =', acc.length);
    for (;;) {
      const frame = readWsFrame(acc);
      if (!frame) break;
      acc = acc.slice(frame.total);
      console.error('[mp-stub] ws frame opcode =', frame.opcode, 'len =', frame.payload.length,
        'head =', Buffer.from(frame.payload.subarray(0, 6)).toString('hex'));
      if (frame.opcode === 0x8) { socket.end(); return; } // close
      if (frame.opcode !== 0x2) continue; // 仅二进制（RPC 帧）
      // ws payload = RPCFrame（13 字节头 + RPC payload）——先剥帧头再解析
      const rpcFrame = rpc.readFrame(frame.payload);
      if (!rpcFrame) {
        console.error('[mp-stub] RPCFrame 剥头失败，len =', frame.payload.length);
        continue;
      }
      handleRpc(rpcFrame.payload, socket);
    }
  });
  socket.on('error', function (err) {
    console.error('[mp-stub] socket error:', err.message);
  });
});

function handleRpc(payload, socket) {
  const parsed = rpc.parseFramePayload(payload);
  if (!parsed || !Array.isArray(parsed.header)) {
    console.error('[mp-stub] rpc parse failed, len =', payload.length);
    return;
  }
  const type = parsed.header[0];
  const id = parsed.header[1];
  const command = parsed.header[3];
  if (type !== rpc.REQUEST_PROMISE) return;
  const reply = function (value) {
    const body = rpc.concat(rpc.serialize([100, id]), rpc.serialize(value));
    socket.write(encodeWsFrame(rpc.writeFrame(body)));
  };
  if (command === 'helloConversationV4') {
    seen.hello += 1;
    reply({
      protocolVersion: 3, connectionId: 'mp-stub-conn',
      clientMode: 'web-remote-replayable', deliveryProfile: 'interactive',
      capabilities: { workflowRunDeltas: true },
    });
  } else if (command === 'initializeConversationV4') {
    seen.clientHello = parsed.body;
    reply({ ok: true });
  } else if (command === 'listTaskList') {
    seen.listTaskList += 1;
    reply(STUB_ROWS);
  } else {
    reply({ ok: true });
  }
}

// ---- RFC6455 最小帧（服务端→客户端不掩码；客户端→服务端必掩码） ----

function readWsFrame(buf) {
  if (buf.length < 2) return null;
  const opcode = buf[0] & 0x0f;
  const masked = (buf[1] & 0x80) !== 0;
  let len = buf[1] & 0x7f;
  let offset = 2;
  if (len === 126) {
    if (buf.length < 4) return null;
    len = buf.readUInt16BE(2);
    offset = 4;
  } else if (len === 127) {
    if (buf.length < 10) return null;
    len = Number(buf.readBigUInt64BE(2));
    offset = 10;
  }
  let mask = null;
  if (masked) {
    if (buf.length < offset + 4) return null;
    mask = buf.subarray(offset, offset + 4);
    offset += 4;
  }
  if (buf.length < offset + len) return null;
  let payload = Buffer.from(buf.subarray(offset, offset + len));
  if (mask) {
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
  }
  return { opcode: opcode, payload: payload, total: offset + len };
}

function encodeWsFrame(payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x82, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x82; header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x82; header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

server.listen(port, function () {
  console.log('[mp-stub-desktop] listening on http://127.0.0.1:' + port +
    ' (server-info + ws /ws)（仅测试用途）');
});

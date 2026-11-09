// RPC 二进制序列化（屏面数据面地基）—— web `packages/rpc/src/serialization.ts` +
// `channelServer.ts` 帧格式的 JS 移植（iOS RPCSerialization / Android RPCSerialization
// 同源第三实现；小程序端唯一权威是这三处的线缆口径）。
//
// 帧格式（RPCFrame，13 字节头）：[type=1(Regular), id u32BE, ack u32BE, len u32BE] + payload
// 值编码：[1B 类型标签] [VQL 长度] [数据]；VQL = 7bit 小端组、最高位续传。
//   0=undefined  1=string  2=Buffer(Uint8Array)  3=VSBuffer  4=array  5=object(JSON)  6=int(VQL)
// 每条消息 = serialize([RequestType, id, channelName, methodName]) + serialize(body)；
// RequestType.Promise=100 / EventListen=102（channels.shared.ts:18-31）。
//
// 实现纪律：只用 Uint8Array/DataView（小程序无 Node Buffer/无 TextEncoder 全局——
// UTF-8 编解码自带纯 JS 实现，与运行环境无关）。

const REQUEST_PROMISE = 100;
const EVENT_LISTEN = 102;
const FRAME_TYPE_REGULAR = 1;
const FRAME_HEADER_SIZE = 13;

const TAG_UNDEFINED = 0;
const TAG_STRING = 1;
const TAG_BUFFER = 2;
const TAG_VSBUFFER = 3;
const TAG_ARRAY = 4;
const TAG_OBJECT = 5;
const TAG_INT = 6;

// ---- UTF-8（纯 JS；小程序无 TextEncoder） ----

function utf8Encode(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    let code = text.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
      const lo = text.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (lo - 0xdc00);
        i += 1;
      }
    }
    if (code < 0x80) {
      out.push(code);
    } else if (code < 0x800) {
      out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      out.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f),
      );
    }
  }
  return new Uint8Array(out);
}

function utf8Decode(bytes) {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i];
    if (b0 < 0x80) {
      out += String.fromCharCode(b0);
      i += 1;
    } else if (b0 < 0xe0) {
      out += String.fromCharCode(((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
      i += 2;
    } else if (b0 < 0xf0) {
      out += String.fromCharCode(
        ((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f),
      );
      i += 3;
    } else {
      const cp =
        ((b0 & 0x07) << 18) |
        ((bytes[i + 1] & 0x3f) << 12) |
        ((bytes[i + 2] & 0x3f) << 6) |
        (bytes[i + 3] & 0x3f);
      const offset = cp - 0x10000;
      out += String.fromCharCode(0xd800 + (offset >> 10), 0xdc00 + (offset & 0x3ff));
      i += 4;
    }
  }
  return out;
}

// ---- VQL（7bit 变长整数；与 TS writeInt32VQL 逐位同构） ----

function writeIntVQL(out, value) {
  if (value === 0) {
    out.push(0x00);
    return;
  }
  let v = value >>> 0;
  const groups = [];
  while (v !== 0) {
    groups.push(v & 0x7f);
    v = v >>> 7;
  }
  for (let i = 0; i < groups.length; i++) {
    out.push(i + 1 < groups.length ? groups[i] | 0x80 : groups[i]);
  }
}

function readIntVQL(bytes, cursor) {
  let value = 0;
  for (let n = 0; ; n += 7) {
    if (cursor.pos >= bytes.length) throw new Error('rpc-serialization: VQL 越界');
    const next = bytes[cursor.pos];
    cursor.pos += 1;
    value |= (next & 0x7f) << n;
    if (!(next & 0x80)) return value >>> 0;
  }
}

// ---- serialize：JS 值 → Uint8Array ----

function serialize(data) {
  const out = [];
  serializeInto(out, data);
  return new Uint8Array(out);
}

function serializeInto(out, data) {
  if (data === undefined) {
    out.push(TAG_UNDEFINED);
  } else if (typeof data === 'string') {
    const bytes = utf8Encode(data);
    out.push(TAG_STRING);
    writeIntVQL(out, bytes.length);
    for (let i = 0; i < bytes.length; i++) out.push(bytes[i]);
  } else if (data instanceof Uint8Array) {
    out.push(TAG_BUFFER);
    writeIntVQL(out, data.length);
    for (let i = 0; i < data.length; i++) out.push(data[i]);
  } else if (Array.isArray(data)) {
    out.push(TAG_ARRAY);
    writeIntVQL(out, data.length);
    for (let i = 0; i < data.length; i++) serializeInto(out, data[i]);
  } else if (typeof data === 'number' && (data | 0) === data) {
    out.push(TAG_INT);
    writeIntVQL(out, data);
  } else {
    // 对象/布尔/null/非整数值 → JSON fallback（嵌 Uint8Array 的标记口径与 TS 相同）
    const bytes = utf8Encode(JSON.stringify(data, encodeRpcJsonValue));
    out.push(TAG_OBJECT);
    writeIntVQL(out, bytes.length);
    for (let i = 0; i < bytes.length; i++) out.push(bytes[i]);
  }
}

const NESTED_MARKER = '__zcode_rpc_nested_uint8array_v1';
const NESTED_BASE64_KEY = 'base64';

function encodeRpcJsonValue(key, value) {
  if (value instanceof Uint8Array) {
    const obj = {};
    obj[NESTED_MARKER] = true;
    obj[NESTED_BASE64_KEY] = base64Encode(value);
    return obj;
  }
  return value;
}

// ---- deserialize：Uint8Array → JS 值 ----

function deserialize(bytes) {
  const cursor = { pos: 0 };
  return deserializeValue(bytes, cursor);
}

function deserializeValue(bytes, cursor) {
  if (cursor.pos >= bytes.length) throw new Error('rpc-serialization: 越界');
  const tag = bytes[cursor.pos];
  cursor.pos += 1;
  switch (tag) {
    case TAG_UNDEFINED:
      return undefined;
    case TAG_STRING: {
      const len = readIntVQL(bytes, cursor);
      const text = utf8Decode(bytes.subarray(cursor.pos, cursor.pos + len));
      cursor.pos += len;
      return text;
    }
    case TAG_BUFFER:
    case TAG_VSBUFFER: {
      const len = readIntVQL(bytes, cursor);
      const slice = bytes.slice(cursor.pos, cursor.pos + len);
      cursor.pos += len;
      return slice;
    }
    case TAG_ARRAY: {
      const count = readIntVQL(bytes, cursor);
      const items = [];
      for (let i = 0; i < count; i++) items.push(deserializeValue(bytes, cursor));
      return items;
    }
    case TAG_OBJECT: {
      const len = readIntVQL(bytes, cursor);
      const text = utf8Decode(bytes.subarray(cursor.pos, cursor.pos + len));
      cursor.pos += len;
      return JSON.parse(text, decodeRpcJsonValue);
    }
    case TAG_INT:
      return readIntVQL(bytes, cursor) | 0;
    default:
      throw new Error('rpc-serialization: 未知类型标签 ' + tag);
  }
}

function decodeRpcJsonValue(key, value) {
  if (
    value &&
    typeof value === 'object' &&
    value[NESTED_MARKER] === true &&
    typeof value[NESTED_BASE64_KEY] === 'string'
  ) {
    return base64Decode(value[NESTED_BASE64_KEY]);
  }
  return value;
}

// ---- base64（小程序无 atob；纯 JS） ----

const B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function base64Encode(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += B64_CHARS[b0 >> 2];
    out += B64_CHARS[((b0 & 3) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? B64_CHARS[((b1 & 15) << 2) | (b2 >> 6)] : '=';
    out += i + 2 < bytes.length ? B64_CHARS[b2 & 63] : '=';
  }
  return out;
}

function base64Decode(text) {
  const clean = text.replace(/[^A-Za-z0-9+/]/g, '');
  const out = [];
  for (let i = 0; i < clean.length; i += 4) {
    const n0 = B64_CHARS.indexOf(clean[i]);
    const n1 = B64_CHARS.indexOf(clean[i + 1]);
    const n2 = i + 2 < clean.length ? B64_CHARS.indexOf(clean[i + 2]) : -1;
    const n3 = i + 3 < clean.length ? B64_CHARS.indexOf(clean[i + 3]) : -1;
    out.push((n0 << 2) | (n1 >> 4));
    if (n2 >= 0) out.push(((n1 & 15) << 4) | (n2 >> 2));
    if (n3 >= 0) out.push(((n2 & 3) << 6) | n3);
  }
  return new Uint8Array(out);
}

// ---- 帧面（RPCFrame 13 字节头） ----

function writeFrame(payload) {
  const frame = new Uint8Array(FRAME_HEADER_SIZE + payload.length);
  const view = new DataView(frame.buffer);
  frame[0] = FRAME_TYPE_REGULAR;
  view.setUint32(1, 0); // id
  view.setUint32(5, 0); // ack
  view.setUint32(9, payload.length);
  frame.set(payload, FRAME_HEADER_SIZE);
  return frame;
}

/**
 * 从累积缓冲解析一帧；返回 { payload, rest } 或 null（非 Regular / 长度不符 /
 * 数据不足——调用方保留 rest 续等）。
 */
function readFrame(data) {
  if (data.length < FRAME_HEADER_SIZE) return null;
  if (data[0] !== FRAME_TYPE_REGULAR) return null;
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const length = view.getUint32(9);
  if (length !== data.length - FRAME_HEADER_SIZE) return null;
  return {
    payload: data.subarray(FRAME_HEADER_SIZE),
    rest: new Uint8Array(0),
  };
}

/** 拼 request 帧：header=[Promise, id, channel, command] + body=args（web 客户端同序）。 */
function buildRequestFrame(id, channel, command, args) {
  const payload = concat(serialize([REQUEST_PROMISE, id, channel, command]), serialize(args));
  return writeFrame(payload);
}

/** 拼 eventListen 帧。 */
function buildEventListenFrame(id, channel, event, arg) {
  const payload = concat(serialize([EVENT_LISTEN, id, channel, event]), serialize(arg));
  return writeFrame(payload);
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/** 解析响应/事件 payload：header=[type, id, …] + body。 */
function parseFramePayload(payload) {
  const cursor = { pos: 0 };
  const header = deserializeValue(payload, cursor);
  if (!Array.isArray(header) || header.length < 2) return null;
  let body;
  try {
    body = cursor.pos < payload.length ? deserializeValue(payload, cursor) : undefined;
  } catch (err) {
    body = undefined;
  }
  return { header: header, body: body };
}

module.exports = {
  REQUEST_PROMISE: REQUEST_PROMISE,
  EVENT_LISTEN: EVENT_LISTEN,
  FRAME_HEADER_SIZE: FRAME_HEADER_SIZE,
  serialize: serialize,
  deserialize: deserialize,
  writeFrame: writeFrame,
  readFrame: readFrame,
  buildRequestFrame: buildRequestFrame,
  buildEventListenFrame: buildEventListenFrame,
  parseFramePayload: parseFramePayload,
  concat: concat,
  utf8Encode: utf8Encode,
  utf8Decode: utf8Decode,
  base64Encode: base64Encode,
  base64Decode: base64Decode,
};

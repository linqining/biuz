// 凭据封装/解封（纯逻辑，无 wx 依赖）——web `systemCredentialCipher.ts` 的语义对齐面。
//
// 语义出处（web，v3.18.2 `fec97590`，`packages/shared/src/credential-security/systemCredentialCipher.ts`）：
// - `seal`/`open`（:76-96）：随机 12 字节 IV + 密文 + 认证 tag，`iv.tag.bytes` 三段
//   base64url 用 `.` 连接；任何格式/认证失败都必须抛「解密失败」，绝不把坏密文当明文返回
//   （:107-108「GCM 认证决定正确历史密钥，绝不把解密失败的密文作为明文返回」）。
// - 密文前缀携带**来源**与**密钥指纹**：`${SYSTEM_PREFIX}${source}:${fingerprint}:${seal(...)}`
//   （:398-401 写入；:412-425 读取时 `parts[0] !== source` / `keyFingerprint(key) !== parts[1]`
//   一律判「密钥不可用」而不是「数据损坏」）。
// - 指纹口径（:203-216）：24 位小写 hex = sha256(key) 前 24 位；编码密钥恰好 43 位 base64url。
// - 「系统密钥不可用」是独立错误码，不能冒充密文损坏——否则上层恢复逻辑会误删登录
//   （:67-75 `unavailable()` 注释原文：「系统库锁定/缺失不能冒充密文损坏，否则既有 OAuth
//   恢复逻辑会误删登录」）。
//
// 加密原语差异（APP-DIFF[D015]）：
// web 用 node `aes-256-gcm`；微信小程序 JS 运行时**没有** AES 原语（base library 无
// crypto/webcrypto），因此在纯 JS 里实现 **HMAC-SHA256 计数器keystream + HMAC-SHA256
// 认证 tag（encrypt-then-MAC）**：keystream 块 = HMAC(encKey, iv‖u32be(counter))，
// tag = HMAC(macKey, iv‖ciphertext)，encKey/macKey 由主密钥 HMAC 派生。构造是标准的
// EtM 加密，抗篡改能力与 GCM 同级；但**不是**经平台认证的密码学实现（无硬件隔离）。
//
// 平台边界（诚实声明，APP-DIFF[D014]/[D015] + PARITY_BACKLOG P-031）：小程序无
// OS keychain / Keystore 等价物，密钥只能放在微信本地存储的独立键里 → 本端只消除
// 「凭据明文落盘 / 备份与日志可直接读到 JWT」，不能防同设备同账号下的运行时代码读取。
// 安全等级低于 iOS Keychain / Android Keystore，差距登记为 APP-DIFF[D014]。

// ---- 来源与错误码（口径与 web 逐字对齐） ----

/** web `CREDENTIAL_SOURCE_FILE = "credential-source"`（:23）→ 本端独立存储键。 */
const CREDENTIAL_SOURCE_KEY = 'biuz.credential-source';
/** web `OS_SOURCE_MARKER = "os\n"`（:24）：来源钉住为 OS 系统库。 */
const OS_SOURCE_MARKER = 'os\n';
/** 本端来源标记：微信本地存储自托管密钥（无 OS 库可钉，见 APP-DIFF[D014]）。 */
const LOCAL_SOURCE_MARKER = 'local\n';
const SOURCE_OS = 'os';
const SOURCE_LOCAL = 'local';

/** web `CREDENTIAL_DECRYPT_ERROR_CODE`（`packages/shared/src/oauth.ts:39`）：密文/格式确坏。 */
const DECRYPT_ERROR_CODE = 'ZCODE_CREDENTIAL_DECRYPT_FAILED';
/** web `CREDENTIAL_SECURE_STORAGE_UNAVAILABLE_ERROR_CODE`（`validation.ts:1006`、cipher:73）。 */
const UNAVAILABLE_ERROR_CODE = 'CREDENTIAL_SECURE_STORAGE_UNAVAILABLE';

/** 密文头（web `SYSTEM_PREFIX = "enc:v2:"`，:16）。 */
const CIPHER_PREFIX = 'enc:v2:';
/** 来源词法：小写字母开头的小写标识（web 只认 os/env，:418；本端另加 local，见 D014）。
 *  **结构性**校验而非白名单——他端写入的其他合法来源要判「来源不符（保留数据）」
 *  而不是「密文损坏（可清理）」（web :423 `parts[0] !== source → unavailable`）。 */
const SOURCE_TOKEN_PATTERN = /^[a-z][a-z0-9_]{0,23}$/;
/** web `KEY_FINGERPRINT_PATTERN = /^[a-f0-9]{24}$/`（:204）。 */
const KEY_FINGERPRINT_PATTERN = /^[a-f0-9]{24}$/;
/** web `ENCODED_KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/`（:205）：32 字节 base64url。 */
const ENCODED_KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const BASE64URL_PART_PATTERN = /^[A-Za-z0-9_-]*$/;
const KEY_BYTES = 32;
const IV_BYTES = 12;
const TAG_BYTES = 32;

const B64URL_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const B64URL_INDEX = (function () {
  const table = {};
  for (let i = 0; i < B64URL_ALPHABET.length; i += 1) table[B64URL_ALPHABET.charAt(i)] = i;
  return table;
})();

function failure(code, kind) {
  const err = new Error(code);
  err.code = code;
  err.errorKind = kind;
  return err;
}

function decryptError() {
  return failure(DECRYPT_ERROR_CODE, 'decrypt');
}

function unavailableError() {
  return failure(UNAVAILABLE_ERROR_CODE, 'unavailable');
}

// ---- 字节/编码工具 ----

function utf8Bytes(text) {
  const str = String(text == null ? '' : text);
  const out = [];
  for (let i = 0; i < str.length; i += 1) {
    let code = str.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
      const low = str.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
        i += 1;
      }
    }
    if (code < 0x80) out.push(code);
    else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    else if (code < 0x10000) {
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
  return out;
}

function utf8Text(bytes) {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i];
    if (b0 < 0x80) { out += String.fromCharCode(b0); i += 1; continue; }
    if (b0 < 0xe0) {
      out += String.fromCharCode(((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
      i += 2;
      continue;
    }
    if (b0 < 0xf0) {
      out += String.fromCharCode(
        ((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f),
      );
      i += 3;
      continue;
    }
    const code = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) |
      ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
    const delta = code - 0x10000;
    out += String.fromCharCode(0xd800 + (delta >> 10), 0xdc00 + (delta & 0x3ff));
    i += 4;
  }
  return out;
}

function encodeBase64url(bytes) {
  const input = bytes || [];
  let out = '';
  for (let i = 0; i < input.length; i += 3) {
    const b0 = input[i];
    const b1 = i + 1 < input.length ? input[i + 1] : null;
    const b2 = i + 2 < input.length ? input[i + 2] : null;
    out += B64URL_ALPHABET.charAt(b0 >> 2);
    out += B64URL_ALPHABET.charAt(((b0 & 0x03) << 4) | ((b1 === null ? 0 : b1) >> 4));
    if (b1 === null) break;
    out += B64URL_ALPHABET.charAt((((b1 || 0) & 0x0f) << 2) | ((b2 === null ? 0 : b2) >> 6));
    if (b2 === null) break;
    out += B64URL_ALPHABET.charAt((b2 || 0) & 0x3f);
  }
  return out;
}

function decodeBase64url(text) {
  const str = String(text == null ? '' : text);
  if (!BASE64URL_PART_PATTERN.test(str)) return null;
  const out = [];
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < str.length; i += 1) {
    const value = B64URL_INDEX[str.charAt(i)];
    if (value === undefined) return null;
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((buffer >> bits) & 0xff);
    }
  }
  return out;
}

function concatBytes() {
  const out = [];
  for (let i = 0; i < arguments.length; i += 1) {
    const part = arguments[i] || [];
    for (let j = 0; j < part.length; j += 1) out.push(part[j]);
  }
  return out;
}

function bytesEqual(left, right) {
  const a = left || [];
  const b = right || [];
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i] ^ b[i];
  return diff === 0;
}

function u32be(value) {
  return [(value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff];
}

// ---- SHA-256 / HMAC-SHA256（纯 JS；单测用 node:crypto 对拍） ----

const SHA256_K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

function rotr(value, shift) {
  return ((value >>> shift) | (value << (32 - shift))) >>> 0;
}

/** SHA-256（FIPS 180-4）：返回 32 字节数组。 */
function sha256(bytes) {
  const input = bytes || [];
  const bitLength = input.length * 8;
  const padded = input.slice();
  padded.push(0x80);
  while (padded.length % 64 !== 56) padded.push(0);
  for (let i = 0; i < 8; i += 1) {
    // 长度域 64 位大端（小程序消息长度远小于 2^32 字节，高位恒 0）
    padded.push(i < 4 ? 0 : (bitLength / Math.pow(2, (7 - i) * 8)) & 0xff);
  }
  const h = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ];
  const w = new Array(64);
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i += 1) {
      const base = offset + i * 4;
      w[i] = ((padded[base] << 24) | (padded[base + 1] << 16) |
        (padded[base + 2] << 8) | padded[base + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i += 1) {
      const s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
      const s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let a = h[0]; let b = h[1]; let c = h[2]; let d = h[3];
    let e = h[4]; let f = h[5]; let g = h[6]; let hh = h[7];
    for (let i = 0; i < 64; i += 1) {
      const s1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
      const ch = ((e & f) ^ (~e & g)) >>> 0;
      const t1 = (hh + s1 + ch + SHA256_K[i] + w[i]) >>> 0;
      const s0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const t2 = (s0 + maj) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0;
      d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0;
    h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0;
    h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
  }
  const out = [];
  for (let i = 0; i < 8; i += 1) {
    out.push((h[i] >>> 24) & 0xff, (h[i] >>> 16) & 0xff, (h[i] >>> 8) & 0xff, h[i] & 0xff);
  }
  return out;
}

/** HMAC-SHA256（RFC 2104）：返回 32 字节数组。 */
function hmacSha256(keyBytes, messageBytes) {
  let key = keyBytes || [];
  if (key.length > 64) key = sha256(key);
  const padded = key.slice();
  while (padded.length < 64) padded.push(0);
  const inner = [];
  const outer = [];
  for (let i = 0; i < 64; i += 1) {
    inner.push(padded[i] ^ 0x36);
    outer.push(padded[i] ^ 0x5c);
  }
  return sha256(concatBytes(outer, sha256(concatBytes(inner, messageBytes || []))));
}

function sha256Hex(bytes) {
  const hash = sha256(bytes);
  let hex = '';
  for (let i = 0; i < hash.length; i += 1) {
    hex += (hash[i] < 16 ? '0' : '') + hash[i].toString(16);
  }
  return hex;
}

// ---- 随机数（熵源受限：诚实登记在 APP-DIFF[D015]） ----

/**
 * 32/12 字节随机数。优先 `wx.getRandomValues`（存在时由平台 CSPRNG 提供）；
 * 否则退回 Math.random 采样——**熵源弱于 node crypto**，只用于本次 IV 与首建密钥。
 */
function randomBytes(size, source) {
  const out = [];
  if (source) {
    const bytes = source(size);
    if (!bytes || bytes.length !== size) throw unavailableError();
    return bytes.slice();
  }
  if (typeof wx !== 'undefined' && wx && typeof wx.getRandomValues === 'function') {
    try {
      const buffer = wx.getRandomValues({ length: size });
      const view = new Uint8Array(buffer);
      for (let i = 0; i < size; i += 1) out.push(view[i]);
      if (out.length === size) return out;
    } catch (err) { /* 落到 Math.random 混合器 */ }
  }
  let pool = [];
  let counter = 0;
  while (out.length < size) {
    const chunk = Date.now() + counter * 0x9e3779b1 + Math.floor(Math.random() * 0xffffffff);
    pool = sha256(utf8Bytes(String(chunk) + ':' + Math.random() + ':' + counter));
    for (let i = 0; i < pool.length && out.length < size; i += 1) out.push(pool[i]);
    counter += 1;
  }
  return out;
}

// ---- 指纹（web `keyFingerprint` :207-209 / `encodedKeyFingerprint` :212-216） ----

/** 24 位 hex 指纹：sha256(keyBytes) 前 24 个 hex 字符（web :207-209 同口径）。 */
function keyFingerprint(keyBytes) {
  return sha256Hex(keyBytes).slice(0, 24);
}

/** 编码密钥（43 位 base64url）的指纹；格式不合法返回 null（web :212-216 同口径）。 */
function encodedKeyFingerprint(encoded) {
  if (typeof encoded !== 'string' || !ENCODED_KEY_PATTERN.test(encoded)) return null;
  const bytes = decodeBase64url(encoded);
  if (!bytes || bytes.length !== KEY_BYTES) return null;
  return keyFingerprint(bytes);
}

function isEncodedKey(encoded) {
  if (typeof encoded !== 'string' || !ENCODED_KEY_PATTERN.test(encoded)) return false;
  const bytes = decodeBase64url(encoded);
  return !!bytes && bytes.length === KEY_BYTES;
}

// ---- EtM 加密（web seal/open 的构造替身，见文件头 APP-DIFF[D015]） ----

function deriveSubkeys(keyBytes) {
  return {
    enc: hmacSha256(keyBytes, utf8Bytes('biuz.credential.enc.v1')),
    mac: hmacSha256(keyBytes, utf8Bytes('biuz.credential.mac.v1')),
  };
}

function keystreamXor(input, encKey, iv) {
  const out = [];
  let counter = 0;
  while (out.length < input.length) {
    const block = hmacSha256(encKey, concatBytes(iv, u32be(counter)));
    for (let i = 0; i < block.length && out.length < input.length; i += 1) {
      out.push(input[out.length] ^ block[i]);
    }
    counter += 1;
  }
  return out;
}

/** 封装：返回 `iv.tag.bytes`（三段 base64url，web :80 同形）。 */
function seal(plaintext, keyBytes, options) {
  const opts = options || {};
  if (!keyBytes || keyBytes.length !== KEY_BYTES) throw unavailableError();
  const iv = opts.iv ? opts.iv.slice() : randomBytes(IV_BYTES, opts.randomSource);
  const subkeys = deriveSubkeys(keyBytes);
  const ciphertext = keystreamXor(utf8Bytes(plaintext), subkeys.enc, iv);
  const tag = hmacSha256(subkeys.mac, concatBytes(iv, ciphertext));
  return [iv, tag, ciphertext]
    .map(function (part) { return encodeBase64url(part); })
    .join('.');
}

/** 解封：任何格式/认证失败都抛解密失败（web :82-96 逐字对齐）。 */
function open(payload, keyBytes) {
  const parts = String(payload == null ? '' : payload).split('.');
  if (parts.length !== 3) throw decryptError();
  for (let i = 0; i < parts.length; i += 1) {
    if (!BASE64URL_PART_PATTERN.test(parts[i])) throw decryptError();
  }
  const iv = decodeBase64url(parts[0]);
  const tag = decodeBase64url(parts[1]);
  const bytes = decodeBase64url(parts[2]);
  if (!iv || !tag || !bytes) throw decryptError();
  if (iv.length !== IV_BYTES || tag.length !== TAG_BYTES) throw decryptError();
  const subkeys = deriveSubkeys(keyBytes);
  const expected = hmacSha256(subkeys.mac, concatBytes(iv, bytes));
  if (!bytesEqual(expected, tag)) throw decryptError();
  return utf8Text(keystreamXor(bytes, subkeys.enc, iv));
}

// ---- 密文记录（web encrypt/decrypt 的字符串形态 :398-425） ----

/** 组装 `enc:v2:<source>:<fingerprint>:<seal>`（web :401 同构）。 */
function buildCiphertext(plaintext, keyBytes, source, options) {
  if (source !== SOURCE_OS && source !== SOURCE_LOCAL) throw unavailableError();
  return CIPHER_PREFIX + source + ':' + keyFingerprint(keyBytes) + ':' + seal(plaintext, keyBytes, options);
}

/** 解析密文头（不解密）：格式错误抛解密失败（web :412-421 同口径）。 */
function parseCiphertext(value) {
  if (typeof value !== 'string' || value.indexOf(CIPHER_PREFIX) !== 0) throw decryptError();
  const parts = value.slice(CIPHER_PREFIX.length).split(':');
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) throw decryptError();
  if (!SOURCE_TOKEN_PATTERN.test(parts[0])) throw decryptError();
  if (!KEY_FINGERPRINT_PATTERN.test(parts[1])) throw decryptError();
  return { source: parts[0], fingerprint: parts[1], payload: parts[2] };
}

/**
 * 用已知密钥解开一条密文记录。**判定顺序与 web :403-425 完全一致**：
 * 1 头部格式坏 → decrypt（数据确坏，可安全清理）
 * 2 记录来源 ≠ 本次来源 → unavailable（换来源 ≠ 数据损坏，不能删）
 * 3 记录指纹 ≠ 密钥指纹 → unavailable（密钥丢失/更换 ≠ 数据损坏，不能删）
 * 4 认证 tag 失败 → decrypt（数据确坏）
 *
 * 与 web 的一处**刻意收紧**（APP-DIFF[D014] 同条）：web :418 把来源硬白名单成
 * `["os","env"]`，他端新增来源会被判成「密文损坏」进而被清理；本端来源词法合法
 * 但非本端来源 → unavailable（保留数据）。
 */
function decryptRecord(value, keyBytes, source, options) {
  const head = parseCiphertext(value);
  if (head.source !== source) throw unavailableError();
  if (keyFingerprint(keyBytes) !== head.fingerprint) throw unavailableError();
  return open(head.payload, keyBytes, options);
}

/** 容器内所有密文记录的指纹（web `readOsKeyFingerprints` :219-238 语义）。 */
function collectFingerprints(record) {
  const seen = {};
  if (!record || typeof record !== 'object') return [];
  const keys = Object.keys(record);
  for (let i = 0; i < keys.length; i += 1) {
    const value = record[keys[i]];
    if (typeof value !== 'string' || value.indexOf(CIPHER_PREFIX) !== 0) continue;
    let head;
    try {
      head = parseCiphertext(value);
    } catch (err) {
      continue;
    }
    seen[head.fingerprint] = true;
  }
  return Object.keys(seen);
}

/** 错误 → 稳定错误码（web `readCredentialErrorCode`，oauth.ts:47-56）。 */
function errorKind(err) {
  if (err && typeof err.code === 'string') {
    if (err.code === DECRYPT_ERROR_CODE) return 'decrypt';
    if (err.code === UNAVAILABLE_ERROR_CODE) return 'unavailable';
  }
  return 'unknown';
}

module.exports = {
  CREDENTIAL_SOURCE_KEY: CREDENTIAL_SOURCE_KEY,
  OS_SOURCE_MARKER: OS_SOURCE_MARKER,
  LOCAL_SOURCE_MARKER: LOCAL_SOURCE_MARKER,
  SOURCE_OS: SOURCE_OS,
  SOURCE_LOCAL: SOURCE_LOCAL,
  DECRYPT_ERROR_CODE: DECRYPT_ERROR_CODE,
  UNAVAILABLE_ERROR_CODE: UNAVAILABLE_ERROR_CODE,
  CIPHER_PREFIX: CIPHER_PREFIX,
  KEY_BYTES: KEY_BYTES,
  IV_BYTES: IV_BYTES,
  TAG_BYTES: TAG_BYTES,
  sha256: sha256,
  sha256Hex: sha256Hex,
  hmacSha256: hmacSha256,
  utf8Bytes: utf8Bytes,
  utf8Text: utf8Text,
  encodeBase64url: encodeBase64url,
  decodeBase64url: decodeBase64url,
  randomBytes: randomBytes,
  bytesEqual: bytesEqual,
  keyFingerprint: keyFingerprint,
  encodedKeyFingerprint: encodedKeyFingerprint,
  isEncodedKey: isEncodedKey,
  seal: seal,
  open: open,
  buildCiphertext: buildCiphertext,
  parseCiphertext: parseCiphertext,
  decryptRecord: decryptRecord,
  collectFingerprints: collectFingerprints,
  errorKind: errorKind,
  decryptError: decryptError,
  unavailableError: unavailableError,
};

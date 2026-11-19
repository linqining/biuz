// 凭据加密与来源辨析对拍（node --test miniprogram/tests/credential-store.test.js）
//
// 对齐面：web `packages/shared/src/credential-security/systemCredentialCipher.ts`（v3.18.2 `fec97590`）
// - sha256/hmac/base64url 与 node:crypto 逐字节对拍（自实现密码学原语必须被外部权威证明）
// - seal/open 往返 + 篡改/换密钥必拒（web :76-96 认证失败绝不返回明文）
// - 密文头三判定：来源不符/密钥指纹不符 → unavailable（不清理）；认证失败 → decrypt（可清理）
//   （web :403-425；`shouldClearOnFailure` 的白名单口径 = `isCredentialDecryptError`）
// - 来源钉住：已钉 local 后绝不新建密钥；有密文却无来源标记 → unavailable（web :276/:289/:305）
// - 存储面：明文 JWT 不落盘 + 旧明文键迁移 + 读失败不误清
const assert = require('assert');
const nodeCrypto = require('node:crypto');
const crypto2 = require('../utils/credential-crypto.js');
const store = require('../utils/credential-store.js');

let passed = 0;
let failed = 0;
const cases = [];

function test(name, fn) { cases.push([name, fn]); }

// 内存 wx 垫片（与 connection.test.js 同形；storage 可被用例重置）
// P-031 修复后：randomBytes 只从 CSPRNG 池取字节，不再回退 Math.random。
// 因此垫片必须提供 wx.getRandomValues（异步 Promise 形态，与真实小程序一致），
// 并在每个用例装填足够池，否则写入会（正确地）以 entropy-unavailable 拒绝。
function installWx(options) {
  const opts = options || {};
  global.wx = {
    storage: {},
    setStorageSync(key, value) { this.storage[key] = JSON.parse(JSON.stringify(value)); },
    getStorageSync(key) { return this.storage[key]; },
    removeStorageSync(key) { delete this.storage[key]; },
  };
  if (opts.withoutCspRng !== true) {
    // 真实平台是异步回调：返回 Promise<{ randomValues: ArrayBuffer }>
    global.wx.getRandomValues = function (o) {
      const len = (o && o.length) || 32;
      const buf = new Uint8Array(len);
      for (let i = 0; i < len; i += 1) buf[i] = Math.floor(Math.random() * 256);
      return Promise.resolve({ randomValues: buf.buffer });
    };
  }
  return global.wx;
}

/** 装填熵池：让需要随机数的同步写入能通过（模拟启动时 app.js 的预取）。 */
async function primeEntropy(bytes) {
  // 优先走真实异步预取路径（同时验证 platform 适配），失败则用测试专用直接装填
  try {
    await crypto2.prefillEntropy(bytes || crypto2.KEY_BYTES + crypto2.IV_BYTES * 8);
  } catch (err) {
    const n = bytes || (crypto2.KEY_BYTES + crypto2.IV_BYTES * 8);
    const filler = [];
    for (let i = 0; i < n; i += 1) filler.push(Math.floor(Math.random() * 256));
    crypto2.seedEntropyForTest(filler);
  }
}

function fixedIv(fill) {
  const iv = [];
  for (let i = 0; i < crypto2.IV_BYTES; i += 1) iv.push(fill == null ? i : fill);
  return iv;
}

// ---- 1. 原语对拍 node:crypto ----

test('sha256 与 node:crypto 逐字节一致（含空串/跨块/多字节）', function () {
  const samples = [
    '', 'abc', 'the quick brown fox jumps over the lazy dog',
    'eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoiMTIzIn0.c2ln',
    '中文凭据 🎉 emoji', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), 'a'.repeat(1000),
  ];
  samples.forEach(function (text) {
    const mine = crypto2.sha256Hex(crypto2.utf8Bytes(text));
    const ref = nodeCrypto.createHash('sha256').update(Buffer.from(text, 'utf8')).digest('hex');
    assert.strictEqual(mine, ref, 'SHA256 不一致：' + JSON.stringify(text.slice(0, 20)));
  });
});

test('hmac-sha256 与 node:crypto 一致（含超块长密钥 / 空 key / 空 msg）', function () {
  const pairs = [
    ['key', 'msg'], ['', ''], ['k'.repeat(100), 'm'.repeat(300)],
    ['中文密钥', '中文消息 🎉'],
  ];
  pairs.forEach(function (pair) {
    const mine = Buffer.from(crypto2.hmacSha256(
      crypto2.utf8Bytes(pair[0]), crypto2.utf8Bytes(pair[1]),
    )).toString('hex');
    const ref = nodeCrypto.createHmac('sha256', Buffer.from(pair[0], 'utf8'))
      .update(Buffer.from(pair[1], 'utf8')).digest('hex');
    assert.strictEqual(mine, ref);
  });
});

test('base64url 编解码与 node 一致且无损（0/1/2/3/31/32/43/64 字节）', function () {
  [0, 1, 2, 3, 31, 32, 43, 64].forEach(function (size) {
    const bytes = [];
    for (let i = 0; i < size; i += 1) bytes.push((i * 37 + 11) & 0xff);
    const mine = crypto2.encodeBase64url(bytes);
    assert.strictEqual(mine, Buffer.from(bytes).toString('base64url'));
    assert.deepStrictEqual(crypto2.decodeBase64url(mine), bytes);
  });
});

// ---- 2. 封装/解封（web seal/open） ----

test('seal/open 往返：中文 + emoji 原样还原', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('device-key-seed'));
  const plaintext = 'JWT 载荷 / 中文 / 🎉 混排';
  const sealed = crypto2.seal(plaintext, key, { iv: fixedIv() });
  assert.strictEqual(sealed.split('.').length, 3, 'iv.tag.bytes 三段（web :80）');
  assert.strictEqual(crypto2.open(sealed, key), plaintext);
});

test('seal 每次 IV 不同 → 同一明文的密文不同（随机 IV）', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  const a = crypto2.seal('same', key);
  const b = crypto2.seal('same', key);
  assert.notStrictEqual(a, b);
});

test('open：篡改密文 / 改 tag / 改 IV / 换密钥 / 截断 全部拒绝且码为 decrypt', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  const parts = crypto2.seal('payload', key, { iv: fixedIv() }).split('.');
  const flip = function (b64) {
    const bytes = crypto2.decodeBase64url(b64);
    bytes[0] = bytes[0] ^ 0x01;
    return crypto2.encodeBase64url(bytes);
  };
  const variants = [
    [parts[0], parts[1], flip(parts[2])], // 密文改一位
    [parts[0], flip(parts[1]), parts[2]], // tag 改一位
    [flip(parts[0]), parts[1], parts[2]], // IV 改一位
    [parts[0], parts[1]],                 // 少一段
    [parts[0], parts[1], parts[2], 'x'],  // 多一段
    ['', parts[1], parts[2]],             // 空 IV
    [parts[0], parts[1], parts[2] + '='], // 非 base64url 字符
  ];
  variants.forEach(function (variant, index) {
    assert.throws(
      function () { crypto2.open(variant.join('.'), key); },
      function (err) { return err.code === crypto2.DECRYPT_ERROR_CODE; },
      '第 ' + index + ' 种畸形必须判 decrypt',
    );
  });
  assert.throws(
    function () { crypto2.open(parts.join('.'), crypto2.sha256(crypto2.utf8Bytes('other'))); },
    function (err) { return err.code === crypto2.DECRYPT_ERROR_CODE; },
  );
});

test('密钥指纹：24 位小写 hex，且等于 sha256 前 24 位（web :207-209）', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  const fingerprint = crypto2.keyFingerprint(key);
  assert.strictEqual(fingerprint.length, 24);
  assert.ok(/^[a-f0-9]{24}$/.test(fingerprint));
  assert.strictEqual(fingerprint, nodeCrypto.createHash('sha256')
    .update(Buffer.from(key)).digest('hex').slice(0, 24));
});

test('encodedKeyFingerprint / isEncodedKey：43 位 base64url 才合法（web :212-216）', function () {
  const encoded = crypto2.encodeBase64url(crypto2.sha256(crypto2.utf8Bytes('seed')));
  assert.strictEqual(encoded.length, 43);
  assert.ok(crypto2.isEncodedKey(encoded));
  assert.ok(crypto2.encodedKeyFingerprint(encoded));
  ['', 'short', encoded.slice(0, 42), encoded + 'xx', encoded + '='].forEach(function (bad) {
    assert.strictEqual(crypto2.encodedKeyFingerprint(bad), null, '应拒：' + bad.slice(0, 12));
  });
});

// ---- 3. 密文记录：来源/指纹先于认证（web :403-425 判定顺序） ----

test('buildCiphertext：enc:v2:<source>:<fp> 头部形状与 web encrypt 一致', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  const record = crypto2.buildCiphertext('v', key, crypto2.SOURCE_LOCAL, { iv: fixedIv(3) });
  assert.ok(record.indexOf('enc:v2:local:' + crypto2.keyFingerprint(key) + ':') === 0);
});

test('buildCiphertext：来源只允许 os / local（其他一律 unavailable）', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  assert.throws(
    function () { crypto2.buildCiphertext('v', key, 'env-ish'); },
    function (err) { return err.code === crypto2.UNAVAILABLE_ERROR_CODE; },
  );
});

test('decryptRecord：来源不符 → unavailable（换来源 ≠ 数据损坏）', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  const record = crypto2.buildCiphertext('v', key, crypto2.SOURCE_LOCAL, { iv: fixedIv() });
  assert.throws(
    function () { crypto2.decryptRecord(record, key, crypto2.SOURCE_OS); },
    function (err) { return err.code === crypto2.UNAVAILABLE_ERROR_CODE; },
  );
});

test('decryptRecord：指纹不符 → unavailable（密钥丢失 ≠ 数据损坏）', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  const record = crypto2.buildCiphertext('v', key, crypto2.SOURCE_LOCAL, { iv: fixedIv() });
  const otherKey = crypto2.sha256(crypto2.utf8Bytes('other'));
  assert.throws(
    function () { crypto2.decryptRecord(record, otherKey, crypto2.SOURCE_LOCAL); },
    function (err) { return err.code === crypto2.UNAVAILABLE_ERROR_CODE; },
  );
});

test('decryptRecord：头部格式坏 → decrypt（只有数据确坏才允许清理）', function () {
  const key = crypto2.sha256(crypto2.utf8Bytes('seed'));
  // 指纹核对必须在解密之前（web :423-425：来源 → 指纹 → 认证）；这里指纹与密钥一致，
  // 才轮到 payload 本身畸形 → decrypt。
  const matching = 'enc:v2:local:' + crypto2.keyFingerprint(key) + ':';
  ['zzz:aa.bb.cc', 'aa', 'aa.bb', 'aa.bb.cc', 'aa.bb.cc.dd'].forEach(function (payload) {
    assert.throws(
      function () { crypto2.decryptRecord(matching + payload, key, crypto2.SOURCE_LOCAL); },
      function (err) { return err.code === crypto2.DECRYPT_ERROR_CODE; },
      '应判 decrypt：' + payload);
  });
  ['plaintext', 'enc:v1:x', 'enc:v2:local:zzz:aa.bb.cc', 'enc:v2:local:'.concat(crypto2.keyFingerprint(key))]
    .forEach(function (bad) {
      assert.throws(
        function () { crypto2.decryptRecord(bad, key, crypto2.SOURCE_LOCAL); },
        function (err) { return err.code === crypto2.DECRYPT_ERROR_CODE; },
        '头部格式坏应判 decrypt：' + bad);
    });
  // 来源词法合法但非本端来源（他端新增的 env 档）→ unavailable，**不得**当密文损坏清理
  assert.throws(
    function () {
      crypto2.decryptRecord('enc:v2:env:' + crypto2.keyFingerprint(key) + ':aa.bb.cc', key, crypto2.SOURCE_LOCAL);
    },
    function (err) { return err.code === crypto2.UNAVAILABLE_ERROR_CODE; },
    '他端来源必须判 unavailable（数据保留）',
  );
});

// ---- 4. 策略纯函数（web :68 注释「系统库锁定/缺失不能冒充密文损坏」） ----

test('shouldClearOnFailure：只有 record-corrupt 允许清（其余全部保留数据）', function () {
  assert.strictEqual(store.shouldClearOnFailure(store.REASON_RECORD_CORRUPT), true);
  [
    store.REASON_NO_SOURCE_MARKER, store.REASON_SOURCE_OS_PINNED, store.REASON_SOURCE_MISMATCH,
    store.REASON_KEY_MISSING, store.REASON_KEY_INVALID,
    store.REASON_KEY_FINGERPRINT_MISMATCH, store.REASON_STORAGE_UNAVAILABLE,
  ].forEach(function (reason) {
    assert.strictEqual(store.shouldClearOnFailure(reason), false, '不得清理：' + reason);
  });
});

test('classifyFailure：失败原因 → ok/missing/unavailable/decrypt 四分', function () {
  assert.strictEqual(store.classifyFailure(null), store.OUTCOME_OK);
  assert.strictEqual(store.classifyFailure(store.REASON_KEY_MISSING), store.OUTCOME_UNAVAILABLE);
  assert.strictEqual(store.classifyFailure(store.REASON_RECORD_CORRUPT), store.OUTCOME_DECRYPT);
});

test('resolveSourceDecision：来源钉住/未钉/他端来源/有密文无标记 四态', function () {
  assert.deepStrictEqual(
    store.resolveSourceDecision({ markedSource: null, hasCiphertext: false }),
    { mode: 'create-pin', source: 'local' },
  );
  assert.deepStrictEqual(
    store.resolveSourceDecision({ markedSource: crypto2.LOCAL_SOURCE_MARKER, hasCiphertext: true }),
    { mode: 'use-pin', source: 'local' },
  );
  assert.deepStrictEqual(
    store.resolveSourceDecision({ markedSource: crypto2.OS_SOURCE_MARKER, hasCiphertext: true }),
    { mode: 'refuse', source: 'os', reason: store.REASON_SOURCE_OS_PINNED },
  );
  assert.deepStrictEqual(
    store.resolveSourceDecision({ markedSource: null, hasCiphertext: true }),
    { mode: 'refuse', source: null, reason: store.REASON_NO_SOURCE_MARKER },
  );
  assert.deepStrictEqual(
    store.resolveSourceDecision({ markedSource: 'env\n', hasCiphertext: true }),
    { mode: 'refuse', source: 'env\n', reason: store.REASON_SOURCE_MISMATCH },
  );
});

test('evaluateRead：六种读结果（missing/ok/unavailable×4/decrypt）一次穷举', function () {
  const keyEncoded = crypto2.encodeBase64url(crypto2.sha256(crypto2.utf8Bytes('seed')));
  const keyBytes = crypto2.decodeBase64url(keyEncoded);
  const good = crypto2.buildCiphertext('jwt-value', keyBytes, crypto2.SOURCE_LOCAL, { iv: fixedIv() });
  const bad = (function () {
    const parts = good.split('.');
    const bytes = crypto2.decodeBase64url(parts[2]);
    bytes[0] ^= 0xff;
    return [parts[0], parts[1], crypto2.encodeBase64url(bytes)].join('.');
  })();
  const base = {
    markedSource: crypto2.LOCAL_SOURCE_MARKER,
    keyEncoded: keyEncoded,
    requireFields: ['jwt'],
  };
  const evaluate = function (patch) {
    return store.evaluateRead(Object.assign({}, base, patch));
  };
  assert.strictEqual(evaluate({ record: null }).outcome, store.OUTCOME_MISSING);
  const ok = evaluate({ record: { fields: { jwt: good } } });
  assert.strictEqual(ok.outcome, store.OUTCOME_OK);
  assert.strictEqual(ok.fields.jwt, 'jwt-value');
  assert.deepStrictEqual(ok.fingerprints, [crypto2.keyFingerprint(keyBytes)]);

  const reasonOf = function (patch) { return evaluate(patch).reason; };
  assert.strictEqual(reasonOf({ record: { fields: { jwt: good } }, markedSource: null }),
    store.REASON_NO_SOURCE_MARKER);
  assert.strictEqual(reasonOf({ record: { fields: { jwt: good } }, markedSource: crypto2.OS_SOURCE_MARKER }),
    store.REASON_SOURCE_OS_PINNED);
  assert.strictEqual(reasonOf({ record: { fields: { jwt: good } }, keyEncoded: null }),
    store.REASON_KEY_MISSING);
  assert.strictEqual(reasonOf({ record: { fields: { jwt: good } }, keyEncoded: 'not-a-key' }),
    store.REASON_KEY_INVALID);
  assert.strictEqual(
    reasonOf({ record: { fields: { jwt: good } }, keyEncoded: crypto2.encodeBase64url(crypto2.sha256(crypto2.utf8Bytes('k2'))) }),
    store.REASON_KEY_FINGERPRINT_MISMATCH,
  );
  const corrupt = evaluate({ record: { fields: { jwt: bad } } });
  assert.strictEqual(corrupt.reason, store.REASON_RECORD_CORRUPT);
  assert.strictEqual(corrupt.outcome, store.OUTCOME_DECRYPT);
});

// ---- 5. 存储面（明文不落盘 / 迁移 / 读失败不误清） ----

test('saveJwt：落盘内容不含明文 JWT，且来源标记为 local', function () {
  const wx = installWx();
  const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.signature-abcdefghijklmnop';
  const written = store.saveJwt(jwt);
  assert.strictEqual(written.ok, true, '应写入成功；reason=' + written.reason);
  const dump = JSON.stringify(wx.storage);
  assert.ok(dump.indexOf(jwt) < 0, '存储里不得出现明文 JWT');
  assert.ok(dump.indexOf('signature-abcdefghijklmnop') < 0, '片段也不得出现');
  assert.strictEqual(wx.storage[store.SOURCE_KEY], crypto2.LOCAL_SOURCE_MARKER);
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], undefined, '旧明文键不得残留');
  assert.strictEqual(store.readJwt().value, jwt, '读回应等价');
});

test('saveJwt 后 readJwt 返回 ok；显式 clearAll 后返回 missing', function () {
  installWx();
  store.saveJwt('j'.repeat(40));
  assert.strictEqual(store.readJwt().outcome, store.OUTCOME_OK);
  store.clearAll();
  assert.strictEqual(store.readJwt().outcome, store.OUTCOME_MISSING);
});

test('密钥丢失（容器还在、密钥键被清）→ unavailable/key-missing，且**不清**数据', function () {
  const wx = installWx();
  store.saveJwt('k'.repeat(40));
  delete wx.storage[store.KEY_SECRET];
  const result = store.readJwt();
  assert.strictEqual(result.outcome, store.OUTCOME_UNAVAILABLE);
  assert.strictEqual(result.reason, store.REASON_KEY_MISSING);
  assert.ok(wx.storage[store.RECORD_KEY], '数据必须保留（不得无差别清理）');
  // 再写新值不得生成替代密钥覆盖旧密文
  const rewrite = store.saveJwt('m'.repeat(40));
  assert.strictEqual(rewrite.ok, false);
  assert.strictEqual(rewrite.reason, store.REASON_KEY_MISSING);
});

test('来源标记被改成 os（他端写入）→ unavailable/os-source-pinned，且不清数据', function () {
  const wx = installWx();
  store.saveJwt('k'.repeat(40));
  wx.storage[store.SOURCE_KEY] = crypto2.OS_SOURCE_MARKER;
  const result = store.readJwt();
  assert.strictEqual(result.outcome, store.OUTCOME_UNAVAILABLE);
  assert.strictEqual(result.reason, store.REASON_SOURCE_OS_PINNED);
  assert.ok(wx.storage[store.RECORD_KEY]);
});

test('密文被损坏 → decrypt/record-corrupt，且只有显式 clearFieldOnCorruption 才清该字段', function () {
  const wx = installWx();
  store.saveJwt('k'.repeat(40));
  const record = wx.storage[store.RECORD_KEY];
  const parts = record.fields.jwt.split('.');
  const bytes = crypto2.decodeBase64url(parts[2]);
  bytes[0] ^= 0x01;
  record.fields.jwt = [parts[0], parts[1], crypto2.encodeBase64url(bytes)].join('.');
  wx.storage[store.RECORD_KEY] = record;

  const result = store.readJwt();
  assert.strictEqual(result.outcome, store.OUTCOME_DECRYPT);
  assert.strictEqual(result.reason, store.REASON_RECORD_CORRUPT);
  assert.strictEqual(store.clearFieldOnCorruption('jwt', store.REASON_RECORD_CORRUPT), true);
  assert.strictEqual(store.readJwt().outcome, store.OUTCOME_MISSING);
});

test('clearFieldOnCorruption 拒绝非 corrupt 原因（unavailable 时数据必须留住）', function () {
  installWx();
  store.saveJwt('k'.repeat(40));
  [store.REASON_KEY_MISSING, store.REASON_SOURCE_OS_PINNED, store.REASON_KEY_FINGERPRINT_MISMATCH]
    .forEach(function (reason) {
      assert.strictEqual(store.clearFieldOnCorruption('jwt', reason), false, reason);
    });
  assert.strictEqual(store.readJwt().outcome, store.OUTCOME_OK, '数据仍在且仍可解');
});

test('migrateLegacyJwt：旧明文 biuz.jwt → 加密容器并删除明文键', function () {
  const wx = installWx();
  const legacy = 'legacy-plaintext-jwt-' + 'z'.repeat(30);
  wx.storage[store.LEGACY_JWT_KEY] = legacy;
  const migrated = store.migrateLegacyJwt();
  assert.strictEqual(migrated.migrated, true);
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], undefined, '明文键必须删除');
  assert.ok(JSON.stringify(wx.storage).indexOf(legacy) < 0, '明文不得残留');
  assert.strictEqual(store.readJwt().value, legacy);
});

test('migrateLegacyJwt：已有可用密文时不覆盖（返回 migrated:false）', function () {
  const wx = installWx();
  store.saveJwt('current-' + 'c'.repeat(30));
  wx.storage[store.LEGACY_JWT_KEY] = 'stale-plaintext-jwt-value';
  const migrated = store.migrateLegacyJwt();
  assert.strictEqual(migrated.migrated, false);
  assert.strictEqual(store.readJwt().value, 'current-' + 'c'.repeat(30), '加密值优先');
});

test('readDesktopAddress：baseUrl/token 同容器加密，明文不可见', function () {
  const wx = installWx();
  store.saveDesktopAddress('http://192.168.1.24:9100', 'server-password-xyz');
  const dump = JSON.stringify(wx.storage);
  assert.ok(dump.indexOf('server-password-xyz') < 0, 'token 不得明文落盘');
  const restored = store.readDesktopAddress();
  assert.strictEqual(restored.outcome, store.OUTCOME_OK);
  assert.strictEqual(restored.baseUrl, 'http://192.168.1.24:9100');
  assert.strictEqual(restored.token, 'server-password-xyz');
});

// ---- P-031 修复专项：CSPRNG 熵源（防回归） ----
// 背景：旧实现把异步 API `wx.getRandomValues` 当同步 ArrayBuffer 用，
// `new Uint8Array(Promise)` → 空数组 → 填充 undefined，而 `out.length === size` 仍成立，
// 于是**静默返回全 undefined**：密钥等同全零、IV 固定（确定性加密）。
// 下面这些用例锁定"绝不退回弱随机"的行为。

test('randomBytes：池空时抛 unavailable，绝不返回 undefined / 不退回 Math.random', function () {
  crypto2.seedEntropyForTest([]);   // 清空池
  assert.throws(
    function () { crypto2.randomBytes(12); },
    function (err) { return err.code === crypto2.UNAVAILABLE_ERROR_CODE; },
    '池空必须明确失败'
  );
});

test('randomBytes：预取后返回真实字节，且两次调用 IV 不同', async function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  await crypto2.prefillEntropy(64);
  const a = crypto2.randomBytes(12);
  const b = crypto2.randomBytes(12);
  assert.strictEqual(a.length, 12);
  assert.ok(a.every(function (x) { return typeof x === 'number' && x >= 0 && x <= 255; }), '必须是 0..255 的字节');
  assert.notDeepStrictEqual(a, b, '两次随机数必须不同（IV 不能固定）');
});

// P-031 回归：平台返回值形态。官方文档是 success(res){res.randomValues:ArrayBuffer}，
// Promise 只是额外支持；某些版本可能只给回调（返回 undefined）。只依赖 Promise
// 会让预取在这些版本上永久失败 → 写入被永久拒绝。以上都与原 bug 同类风险。
test('prefillEntropy：仅回调形态（无 Promise 返回）也能取到随机数', async function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  global.wx.getRandomValues = function (o) {
    setTimeout(function () { o.success({ randomValues: new Uint8Array(o.length).buffer }); }, 1);
    // 故意不返回 Promise（模拟只支持回调的平台版本）
  };
  const n = await crypto2.prefillEntropy(64);
  assert.ok(n >= 64, '回调形态必须可用，实际 ' + n);
});

test('prefillEntropy：回调与 Promise 同时触发时只结算一次', async function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  global.wx.getRandomValues = function (o) {
    const res = { randomValues: new Uint8Array(o.length).buffer };
    o.success(res);              // 回调先结算
    return Promise.resolve(res); // Promise 随后到，必须被忽略
  };
  const n = await crypto2.prefillEntropy(64);
  assert.strictEqual(n, 1024, '池不得被重复填充，实际 ' + n);
});

test('prefillEntropy：TypedArray 子视图按视图范围取字节（尊重 byteOffset）', async function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  global.wx.getRandomValues = function (o) {
    const big = new Uint8Array(o.length * 2);
    for (let i = 0; i < big.length; i += 1) big[i] = i & 0xff;
    // 只给后半段视图：若实现错误地取 raw.buffer，长度会翻倍
    return Promise.resolve({ randomValues: big.subarray(o.length, o.length * 2) });
  };
  const n = await crypto2.prefillEntropy(64);
  assert.strictEqual(n, 1024, '必须按 byteOffset/byteLength 取，实际 ' + n);
});

test('prefillEntropy：平台 fail/同步抛错/空结果一律 unavailable，不伪造', async function () {
  // fail 回调
  installWx();
  crypto2.seedEntropyForTest([]);
  global.wx.getRandomValues = function (o) { setTimeout(function () { o.fail({ errMsg: 'x' }); }, 1); };
  let rejected = null;
  try { await crypto2.prefillEntropy(32); } catch (err) { rejected = err.code; }
  assert.strictEqual(rejected, 'CREDENTIAL_SECURE_STORAGE_UNAVAILABLE', 'fail 回调须拒绝');
  assert.strictEqual(crypto2.entropyStatus().available, 0, '不得留下空池成功态');

  // 同步抛错
  installWx();
  crypto2.seedEntropyForTest([]);
  global.wx.getRandomValues = function () { throw new Error('sync boom'); };
  rejected = null;
  try { await crypto2.prefillEntropy(32); } catch (err) { rejected = err.code; }
  assert.strictEqual(rejected, 'CREDENTIAL_SECURE_STORAGE_UNAVAILABLE', '同步抛错须拒绝');

  // 空结果
  installWx();
  crypto2.seedEntropyForTest([]);
  global.wx.getRandomValues = function () { return Promise.resolve({ randomValues: new ArrayBuffer(0) }); };
  rejected = null;
  try { await crypto2.prefillEntropy(32); } catch (err) { rejected = err.code; }
  assert.strictEqual(rejected, 'CREDENTIAL_SECURE_STORAGE_UNAVAILABLE', '空结果须拒绝');
});

test('randomBytes：平台无 CSPRNG 时 prefill 拒绝，不伪造随机数', async function () {
  installWx({ withoutCspRng: true });
  crypto2.seedEntropyForTest([]);
  let rejected = false;
  try { await crypto2.prefillEntropy(32); } catch (err) { rejected = true; }
  assert.strictEqual(rejected, true, '无 CSPRNG 必须拒绝');
  assert.strictEqual(crypto2.entropyStatus().platformCspRng, false);
  assert.throws(function () { crypto2.randomBytes(8); }, '且 randomBytes 仍必须抛错');
});

test('seal：相同明文两次封装必须不同（依赖随机 IV，防确定性加密回归）', async function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  await crypto2.prefillEntropy(128);
  const key = crypto2.randomBytes(crypto2.KEY_BYTES);
  const s1 = crypto2.seal('same-plaintext', key);
  const s2 = crypto2.seal('same-plaintext', key);
  assert.notStrictEqual(s1, s2, '两次密文必须不同，否则 IV 固定');
  assert.strictEqual(crypto2.open(s1, key), 'same-plaintext');
  assert.strictEqual(crypto2.open(s2, key), 'same-plaintext');
});

test('writeFieldsAsync：池不足时自动预取后写入成功（登录路径不被熵限制打断）', async function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  const written = await store.writeFieldsAsync({ jwt: 'x'.repeat(40) });
  assert.strictEqual(written.ok, true, '应自动预取后写成功');
  const back = store.readJwt();
  assert.strictEqual(back.outcome, store.OUTCOME_OK);
  assert.strictEqual(back.value, 'x'.repeat(40));
});

test('writeFields（同步）：池不足时返回 entropy-unavailable 且不写弱密钥', function () {
  installWx();
  crypto2.seedEntropyForTest([]);
  const res = store.writeFields({ jwt: 'y'.repeat(40) });
  assert.strictEqual(res.ok, false);
  assert.strictEqual(res.reason, store.REASON_ENTROPY_UNAVAILABLE);
  assert.ok(res.needBytes > 0, '应回报需要多少字节');
  // 关键：不得因失败而落盘任何密钥/密文
  assert.strictEqual(global.wx.storage[store.KEY_SECRET], undefined, '不得写入密钥');
  assert.strictEqual(store.readRecord(), null, '不得写入密文容器');
});

// P-031 回归：冷启动时序。登录页 onLoad 早于 app.onLaunch 的异步预取完成，
// 此时池必为空。若迁移走同步版，结果恒为 entropy-unavailable → 旧明文用户
// 表现为「未登录」且明文永远迁不进来。必须走 migrateLegacyJwtAsync。
test('migrateLegacyJwtAsync：池为空（冷启动）也能完成迁移并删除明文', async function () {
  const wx = installWx();
  const legacy = 'coldstart-legacy-jwt-' + 'k'.repeat(30);
  wx.storage[store.LEGACY_JWT_KEY] = legacy;
  crypto2.seedEntropyForTest([]); // 模拟冷启动：池为空
  const migrated = await store.migrateLegacyJwtAsync();
  assert.strictEqual(migrated.migrated, true, '冷启动必须能迁移：' + JSON.stringify(migrated));
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], undefined, '明文键必须删除');
  assert.strictEqual(store.readJwt().value, legacy, '迁移后必须读回原值');
});

test('migrateLegacyJwt（同步）：冷启动池为空时明确失败且不丢明文', function () {
  const wx = installWx();
  const legacy = 'coldstart-sync-jwt-' + 'm'.repeat(30);
  wx.storage[store.LEGACY_JWT_KEY] = legacy;
  crypto2.seedEntropyForTest([]);
  const migrated = store.migrateLegacyJwt();
  assert.strictEqual(migrated.migrated, false);
  assert.strictEqual(migrated.reason, store.REASON_ENTROPY_UNAVAILABLE);
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], legacy, '失败时明文必须保留，不得丢数据');
  assert.strictEqual(wx.storage[store.KEY_SECRET], undefined, '不得写入弱密钥');
  assert.strictEqual(wx.storage[store.RECORD_KEY], undefined, '不得写入容器');
});

test('migrateLegacyJwt：已有可用密文时清理遗留明文且不覆盖密文', function () {
  const wx = installWx();
  const current = 'current-encrypted-jwt-' + 'c'.repeat(30);
  store.saveJwt(current);
  wx.storage[store.LEGACY_JWT_KEY] = 'stale-plaintext-should-be-removed';
  const migrated = store.migrateLegacyJwt();
  assert.strictEqual(migrated.migrated, false);
  assert.strictEqual(migrated.cleaned, true, '遗留明文必须被清理');
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], undefined, '明文不得残留落盘');
  assert.strictEqual(store.readJwt().value, current, '密文优先，不得被旧明文覆盖');
});

test('migrateLegacyJwtAsync：无 CSPRNG 时失败但保留明文、不落弱密钥', async function () {
  const wx = installWx({ withoutCspRng: true });
  const legacy = 'no-csprng-jwt-' + 'p'.repeat(30);
  wx.storage[store.LEGACY_JWT_KEY] = legacy;
  // 关键：池是模块级状态，串行运行器会在每个用例前 primeEntropy 装填。
  // 本用例要验证「平台不给 CSPRNG」，必须显式清空，否则会用上一轮残留字节成功写入。
  crypto2.seedEntropyForTest([]);
  const migrated = await store.migrateLegacyJwtAsync();
  assert.strictEqual(migrated.migrated, false);
  assert.strictEqual(migrated.reason, store.REASON_ENTROPY_UNAVAILABLE);
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], legacy, '明文保留');
  assert.strictEqual(wx.storage[store.KEY_SECRET], undefined);
  assert.strictEqual(wx.storage[store.RECORD_KEY], undefined);
});

test('migrateLegacyJwtAsync：无旧明文时为空操作且不触发预取', async function () {
  const wx = installWx();
  let calls = 0;
  const original = global.wx.getRandomValues;
  global.wx.getRandomValues = function (o) { calls += 1; return original(o); };
  const migrated = await store.migrateLegacyJwtAsync();
  assert.strictEqual(migrated.migrated, false);
  assert.strictEqual(migrated.reason, null);
  assert.strictEqual(calls, 0, '没有旧明文时不应白白消耗平台随机数');
});

// P-031 回归：旧明文桌面地址（biuz.desktop，含配对 token）必须迁入加密容器。
// 原实现只在 writeFields 成功时 removeRaw 删掉它——即「删除」而非「迁移」，
// 只连桌面不进登录页的用户明文 token 会一直留本地；一旦某次写入清掉就永久丢连接。
test('migrateLegacyDesktopAsync：明文桌面地址+token 迁入加密容器并删除明文', async function () {
  const wx = installWx();
  wx.storage[store.LEGACY_DESKTOP_KEY] = { baseUrl: 'http://192.168.1.9:9100', token: 'legacy-desktop-token-secret' };
  const migrated = await store.migrateLegacyDesktopAsync();
  assert.strictEqual(migrated.migrated, true, JSON.stringify(migrated));
  assert.strictEqual(wx.storage[store.LEGACY_DESKTOP_KEY], undefined, '明文键必须删除');
  assert.ok(JSON.stringify(wx.storage).indexOf('legacy-desktop-token-secret') < 0, '明文 token 不得残留');
  const addr = store.readDesktopAddress();
  assert.strictEqual(addr.outcome, store.OUTCOME_OK);
  assert.strictEqual(addr.baseUrl, 'http://192.168.1.9:9100');
  assert.strictEqual(addr.token, 'legacy-desktop-token-secret', '迁移后不掉连接');
});

test('migrateLegacyDesktopAsync：JSON 字符串形态与对象形态都能解析', async function () {
  const wx = installWx();
  wx.storage[store.LEGACY_DESKTOP_KEY] = JSON.stringify({ baseUrl: 'http://10.0.0.6:9100', token: 'tok-str' });
  const migrated = await store.migrateLegacyDesktopAsync();
  assert.strictEqual(migrated.migrated, true, JSON.stringify(migrated));
  assert.strictEqual(store.readDesktopAddress().token, 'tok-str');
});

test('migrateLegacyDesktopAsync：已有可用密文时只清残留、不覆盖', async function () {
  const wx = installWx();
  store.saveDesktopAddress('http://new.example:9100', 'new-token');
  wx.storage[store.LEGACY_DESKTOP_KEY] = { baseUrl: 'http://stale.example:9100', token: 'stale-token' };
  const migrated = await store.migrateLegacyDesktopAsync();
  assert.strictEqual(migrated.cleaned, true);
  assert.strictEqual(wx.storage[store.LEGACY_DESKTOP_KEY], undefined);
  const addr = store.readDesktopAddress();
  assert.strictEqual(addr.baseUrl, 'http://new.example:9100', '密文优先');
  assert.strictEqual(addr.token, 'new-token');
});

test('migrateLegacyDesktopAsync：无 CSPRNG 时失败但保留明文、不落弱密钥', async function () {
  const wx = installWx({ withoutCspRng: true });
  wx.storage[store.LEGACY_DESKTOP_KEY] = { baseUrl: 'http://10.0.0.7:9100', token: 'tok-keep' };
  crypto2.seedEntropyForTest([]);
  const migrated = await store.migrateLegacyDesktopAsync();
  assert.strictEqual(migrated.migrated, false);
  assert.strictEqual(migrated.reason, store.REASON_ENTROPY_UNAVAILABLE);
  assert.ok(JSON.stringify(wx.storage).indexOf('tok-keep') >= 0, '明文必须保留');
  assert.strictEqual(wx.storage[store.KEY_SECRET], undefined);
  assert.strictEqual(wx.storage[store.RECORD_KEY], undefined);
});

test('migrateLegacyJwtAsync + migrateLegacyDesktopAsync 并发只取一次 CSPRNG', async function () {
  const wx = installWx();
  let calls = 0;
  const original = global.wx.getRandomValues;
  global.wx.getRandomValues = function (o) { calls += 1; return original(o); };
  crypto2.seedEntropyForTest([]);
  wx.storage[store.LEGACY_JWT_KEY] = 'legacy-jwt-' + 'a'.repeat(40);
  wx.storage[store.LEGACY_DESKTOP_KEY] = { baseUrl: 'http://10.0.0.8:9100', token: 'tok-both' };
  const [j, d] = await Promise.all([store.migrateLegacyJwtAsync(), store.migrateLegacyDesktopAsync()]);
  assert.strictEqual(j.migrated, true);
  assert.strictEqual(d.migrated, true);
  assert.strictEqual(calls, 1, 'poolPending 应复用同一次预取，实际 ' + calls + ' 次');
  assert.strictEqual(store.readJwt().value, 'legacy-jwt-' + 'a'.repeat(40));
  assert.strictEqual(store.readDesktopAddress().token, 'tok-both');
});

// P-031 回归：写入某类字段时不得删除另一类的未迁移明文。
// 曾把 removeRaw(LEGACY_*) 放在 writeFields 成功之后：用户「先连桌面」就会把
// 尚未迁移的 biuz.jwt 静默删掉 → 登录永久丢失且无任何报错。
test('writeFields 不得删除尚未迁移的旧明文（先连接后登录的丢凭据隐患）', async function () {
  const wx = installWx();
  const legacyJwt = 'legacy-jwt-' + 'h'.repeat(40);
  wx.storage[store.LEGACY_JWT_KEY] = legacyJwt;
  const written = await store.saveDesktopAddressAsync('http://10.0.0.9:9100', 'desk-token');
  assert.strictEqual(written.ok, true);
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], legacyJwt, '写桌面地址不得删掉未迁移的明文 JWT');
  // 随后迁移仍必须能拿到它
  const migrated = await store.migrateLegacyJwtAsync();
  assert.strictEqual(migrated.migrated, true, '迁移必须仍能成功：' + JSON.stringify(migrated));
  assert.strictEqual(store.readJwt().value, legacyJwt, '登录不得丢失');
  assert.strictEqual(wx.storage[store.LEGACY_JWT_KEY], undefined, '迁移完成后才清明文');
});

test('migrateLegacyJwtAsync 不得删除尚未迁移的明文桌面地址', async function () {
  const wx = installWx();
  wx.storage[store.LEGACY_DESKTOP_KEY] = { baseUrl: 'http://10.0.0.11:9100', token: 'desk-keep' };
  const migrated = await store.migrateLegacyJwtAsync();
  assert.strictEqual(migrated.migrated, false, '没有明文 JWT 时应为空操作');
  assert.ok(JSON.stringify(wx.storage).indexOf('desk-keep') >= 0, '桌面明文不得被 JWT 迁移删掉');
  const desk = await store.migrateLegacyDesktopAsync();
  assert.strictEqual(desk.migrated, true, '桌面迁移仍须成功：' + JSON.stringify(desk));
  assert.strictEqual(store.readDesktopAddress().token, 'desk-keep');
});

// ---- 串行执行（与本仓既有 node 测试风格一致） ----

(function run(i) {
  if (i >= cases.length) {
    console.log(passed + ' 项通过');
    if (failed > 0) { console.error(failed + ' 项失败'); process.exitCode = 1; }
    return;
  }
  const pair = cases[i];
  Promise.resolve()
    // P-031 修复后写入需要 CSPRNG 池；每个用例前重装，避免用例间互相耗尽
    .then(function () { return primeEntropy(); })
    .then(pair[1])
    .then(function () { passed += 1; console.log('  ok', pair[0]); })
    .catch(function (err) { failed += 1; console.error('  FAIL', pair[0], err.message); })
    .then(function () { run(i + 1); });
})(0);

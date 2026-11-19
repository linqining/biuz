// 凭据存储（微信本地存储后端）——web `systemCredentialCipher` 的「来源决策」语义对齐面。
//
// ============================ 平台边界（必读） ============================
// 小程序**没有** OS keychain / Android Keystore / iOS Keychain 等价物：没有任何平台
// 托管的、与其他应用隔离且不可导出的密钥容器。本端能做到与**做不到**的，逐条写在
// APP-DIFF[D014] / [D015] 与 PARITY_BACKLOG P-031 里，代码不假装安全：
//   能：凭据 JSON 不再以明文落盘（原来是 `wx.setStorageSync('biuz.jwt', jwt)` 明文字符串）；
//       即使存储被导出/备份/被日志打到，直接读到的也只是密文 + 指纹；
//       换存储 / 密钥丢失 / 记录损坏三种「读失败」被区分开，不再一律当「未登录」。
//   不能：防同设备同微信账号下运行时代码读取（密钥就在同一个本地存储里）；
//         平台不提供硬件隔离，熵源也可能是 Math.random（`randomBytes` 已优先 wx.getRandomValues）。
// =========================================================================
//
// 语义出处（web `packages/shared/src/credential-security/systemCredentialCipher.ts`，`fec97590`）：
// - 来源标记文件 `credential-source`（:23-24）：目录一旦成功用系统密钥，写入 `os\n` 把
//   来源**钉住**，此后系统密钥不可用也**绝不**降级到私有文件密钥（:276、:305 `!unbound`）。
// - `isOsSourcePinned`（:241-249）：标记缺失 = 未钉住；其他读取错误上抛。
// - 判定三分（:67-75 / :82-96 / :403-425）：`unavailable`（密钥来源不可用）、`decrypt`
//   （密文/格式确坏）、`missing`（本来就没有）。上层恢复逻辑**只**允许清理 decrypt
//   （oauth.ts `isCredentialDecryptError`；credentialService.ts:251-283 锁内二次确认才清）。
// - 密钥指纹（:203-216、:398-401）：密文头带来源+指纹，读取时来源/指纹不匹配 → unavailable。
//
// 刻意差异见 APP-DIFF[D014]（无 OS 库可钉 → 钉「local」）与 APP-DIFF[D015]（无 AES 原语）。

const crypto = require('./credential-crypto.js');

/** 固化的来源标记（web `credential-source` 文件对应物；不进业务数据，只记录来源）。 */
const SOURCE_KEY = crypto.CREDENTIAL_SOURCE_KEY;
/** 自托管密钥（明文 base64url 43 位；web 的 `credential-secret` 私有文件对应物）。 */
const KEY_SECRET = 'biuz.credential-secret';
/**
 * 结构化凭据容器：`{v:2, fields:{<name>: <ciphertext>}}`。
 * web 的 `credentials.json`（:227 逐条 value 加密）同构；**每条**独立密文。
 */
const RECORD_KEY = 'biuz.credentials';

/** @deprecated 旧明文键（本次改动前 login.js / connection.js 直接写 JWT 明文）。 */
const LEGACY_JWT_KEY = 'biuz.jwt';
/** @deprecated 旧明文连接地址键（`{baseUrl, token}` 明文）。 */
const LEGACY_DESKTOP_KEY = 'biuz.desktop';

/**
 * 凭据读取结果（web 三分对齐；命名按 JS 习惯用字符串常量 + 纯函数）：
 * APP-DIFF[D016] platform: 端上类型形状不同（JS 无枚举/密封类），判定口径与 web 一致。
 * - `ok`：读到并解开了全部字段
 * - `missing`：容器/字段本来就不存在（= web 的 undefined / null，不算错误）
 * - `unavailable`：密钥来源不可用（换设备/密钥丢失/来源被改），**数据不得清理**
 * - `decrypt`：密文或格式确坏（web `CREDENTIAL_DECRYPT_ERROR_CODE`），可清理该字段
 */
const OUTCOME_OK = 'ok';
const OUTCOME_MISSING = 'missing';
const OUTCOME_UNAVAILABLE = 'unavailable';
const OUTCOME_DECRYPT = 'decrypt';

/** 失败原因（UI 文案与「是否清理」决策的唯一口径）。 */
const REASON_NO_SOURCE_MARKER = 'no-source-marker';
const REASON_SOURCE_OS_PINNED = 'os-source-pinned';
const REASON_SOURCE_MISMATCH = 'source-mismatch';
const REASON_KEY_MISSING = 'key-missing';
const REASON_KEY_INVALID = 'key-invalid';
const REASON_KEY_FINGERPRINT_MISMATCH = 'key-fingerprint-mismatch';
const REASON_RECORD_CORRUPT = 'record-corrupt';
const REASON_STORAGE_UNAVAILABLE = 'storage-unavailable';
// P-031 修复：平台未提供 CSPRNG / 预取失败 / 池容量不足。
// 与「存储不可用」区分：这是密钥材料拿不到，写下去会变成弱密钥，必须拒绝写入。
const REASON_ENTROPY_UNAVAILABLE = 'entropy-unavailable';

/**
 * **纯函数**：读失败时是否允许清理该凭据（web 的 `isCredentialDecryptError`
 * 白名单口径，`packages/shared/src/oauth.ts:42-56`）。
 * 只有「数据确坏」才允许清；「密钥不可用」一律保留（web cipher:68 注释：系统库锁定/
 * 缺失不能冒充密文损坏，否则既有 OAuth 恢复逻辑会误删登录）。
 */
function shouldClearOnFailure(reason) {
  return reason === REASON_RECORD_CORRUPT;
}

/**
 * **纯函数**：失败原因 → 可观测结果分类。
 * - unavailable：保留数据、要求重新登录或恢复密钥来源
 * - decrypt：保留数据 + 清理确坏字段（调用方决定）
 */
function classifyFailure(reason) {
  if (!reason) return OUTCOME_OK;
  if (shouldClearOnFailure(reason)) return OUTCOME_DECRYPT;
  return OUTCOME_UNAVAILABLE;
}

/**
 * **纯函数**：来源决策（web `getKey` :261-327 的判定顺序的精简同构）。
 * 返回 `{mode:"use-pin"|"create-pin"|"refuse", source, reason?}`。
 *
 * - 已有标记但来源不是本端支持的 → `refuse`（web :276 `pinnedSource !== OS_SOURCE_MARKER → unavailable`）
 * - 已有标记 = 本端来源 → `use-pin`（web :314「已绑定来源绝不切换」，:305 降级只在 `unbound` 时）
 * - 无标记但有密文记录 → `refuse`：密文已存在却没有来源标记，说明密钥来源丢了，
 *   **必须**报「密钥不可用」而不是生成新密钥把旧数据永久锁死（web :286/:289/:462 同纪律）
 * - 无标记且无密文 → `create-pin`：新建密钥并钉住来源（web :289 `unbound` + :314 写标记）
 */
function resolveSourceDecision(options) {
  const marked = options && options.markedSource;
  const adoptedSource = (options && options.adoptedSource) || crypto.LOCAL_SOURCE_MARKER;
  const hasCiphertext = !!(options && options.hasCiphertext);
  if (marked !== null && marked !== undefined && marked !== '') {
    if (marked === crypto.OS_SOURCE_MARKER) {
      // 本端没有 OS 库：被钉到 os 说明记录来自能力更强的宿主，本端不能假装能解开。
      return { mode: 'refuse', source: crypto.SOURCE_OS, reason: REASON_SOURCE_OS_PINNED };
    }
    if (marked === adoptedSource) return { mode: 'use-pin', source: crypto.SOURCE_LOCAL };
    return { mode: 'refuse', source: String(marked), reason: REASON_SOURCE_MISMATCH };
  }
  if (hasCiphertext) return { mode: 'refuse', source: null, reason: REASON_NO_SOURCE_MARKER };
  return { mode: 'create-pin', source: crypto.SOURCE_LOCAL };
}

/**
 * **纯函数**：一次读取尝试的完整判定（来源 → 密钥 → 密文）。
 * 输入全部由调用方从存储层探明，本函数不碰 wx，便于单测穷举。
 *
 * @param {object} input
 *   - markedSource: string|null   来源标记原文（null = 无标记）
 *   - keyEncoded: string|null     存储里的编码密钥
 *   - record: object|null         `{field: ciphertext}`
 *   - requireFields: string[]     本次必须读到的字段
 *   - adoptedSource: string       本端采用的来源标记（默认 'local\n'）
 * @returns {{outcome:string, reason:string|null, fields:object, fingerprints:string[]}}
 */
function evaluateRead(input) {
  const opts = input || {};
  const record = opts.record && typeof opts.record.fields === 'object' && opts.record.fields
    ? opts.record.fields
    : null;
  const requireFields = opts.requireFields || [];
  const fingerprints = record ? crypto.collectFingerprints(record) : [];
  const present = record
    ? requireFields.filter(function (name) {
      return typeof record[name] === 'string' && record[name].length > 0;
    })
    : [];

  // 1) 本来就没有（web：undefined/null 不算错误）
  if (present.length === 0 && fingerprints.length === 0) {
    return { outcome: OUTCOME_MISSING, reason: null, fields: {}, fingerprints: fingerprints };
  }

  // 2) 来源决策
  const decision = resolveSourceDecision({
    markedSource: opts.markedSource,
    adoptedSource: opts.adoptedSource,
    hasCiphertext: fingerprints.length > 0 || present.length > 0,
  });
  if (decision.mode === 'refuse') {
    return {
      outcome: classifyFailure(decision.reason),
      reason: decision.reason,
      fields: {},
      fingerprints: fingerprints,
    };
  }

  // 3) 密钥可用性（web :283-314：缺失/格式非法/长度不对都是 unavailable，不是损坏）
  if (!opts.keyEncoded) {
    return {
      outcome: OUTCOME_UNAVAILABLE,
      reason: REASON_KEY_MISSING,
      fields: {},
      fingerprints: fingerprints,
    };
  }
  if (!crypto.isEncodedKey(opts.keyEncoded)) {
    return {
      outcome: OUTCOME_UNAVAILABLE,
      reason: REASON_KEY_INVALID,
      fields: {},
      fingerprints: fingerprints,
    };
  }

  // 4) 逐字段解密：任何一条 unavailable 优先（不允许把「密钥不可用」降级成「清理」）
  const keyBytes = crypto.decodeBase64url(opts.keyEncoded);
  const fields = {};
  let failure = null;
  for (let i = 0; i < present.length; i += 1) {
    const name = present[i];
    try {
      fields[name] = crypto.decryptRecord(record[name], keyBytes, decision.source);
    } catch (err) {
      const kind = crypto.errorKind(err);
      const reason = kind === 'decrypt' ? REASON_RECORD_CORRUPT : REASON_KEY_FINGERPRINT_MISMATCH;
      // unavailable 优先保留（web :403-425：来源/指纹不符先于认证失败判定）
      if (!failure || (failure.reason === REASON_RECORD_CORRUPT && kind !== 'decrypt')) {
        failure = { reason: reason, field: name };
      }
    }
  }
  if (failure) {
    return {
      outcome: classifyFailure(failure.reason),
      reason: failure.reason,
      fields: {},
      fingerprints: fingerprints,
    };
  }
  return { outcome: OUTCOME_OK, reason: null, fields: fields, fingerprints: fingerprints };
}

// ---- 平台（wx）薄壳：只做存储读写，不含策略 ----

function storage() {
  if (typeof wx === 'undefined' || !wx) return null;
  if (typeof wx.getStorageSync !== 'function' || typeof wx.setStorageSync !== 'function') return null;
  return wx;
}

function readRaw(key) {
  const api = storage();
  if (!api) return null;
  try {
    const value = api.getStorageSync(key);
    return value === '' || value === undefined ? null : value;
  } catch (err) {
    return null;
  }
}

function writeRaw(key, value) {
  const api = storage();
  if (!api) return false;
  try {
    api.setStorageSync(key, value);
    return true;
  } catch (err) {
    return false;
  }
}

function removeRaw(key) {
  const api = storage();
  if (!api) return;
  try {
    if (typeof api.removeStorageSync === 'function') api.removeStorageSync(key);
  } catch (err) { /* 幂等 */ }
}

/** 读来源标记原文（web `readFile(credential-source)` 对应物；缺失 = null）。 */
function readSourceMarker() {
  const marker = readRaw(SOURCE_KEY);
  return typeof marker === 'string' ? marker : null;
}

/** 读结构化凭据容器（不解密）。 */
function readRecord() {
  const raw = readRaw(RECORD_KEY);
  if (!raw || typeof raw !== 'object') return null;
  if (!raw.fields || typeof raw.fields !== 'object') return null;
  return raw;
}

/**
 * 读并解密整个容器。返回 `{outcome, reason, fields, fingerprints}`。
 * **不清理任何东西**（web 纪律：清理是调用方在锁内二次确认后的独立决定）。
 */
function readCredentials(requireFields) {
  const record = readRecord();
  return evaluateRead({
    markedSource: readSourceMarker(),
    keyEncoded: readRaw(KEY_SECRET),
    record: record,
    requireFields: requireFields || Object.keys((record && record.fields) || {}),
  });
}

/**
 * 写入字段（不存在则新建来源标记与密钥；已存在则沿用，**绝不覆盖**已有密钥——
 * web `publishPrivateSecretFile` :469-483「已有文件绝不覆盖」）。
 * 返回 `{ok:boolean, reason:string|null}`。
 */
function writeFields(values) {
  const names = Object.keys(values || {});
  if (names.length === 0) return { ok: true, reason: null };
  const markedSource = readSourceMarker();
  const existing = readRecord();
  const record = existing || { v: 2, fields: {} };
  const hasCiphertext = crypto.collectFingerprints(record.fields || {}).length > 0;
  const decision = resolveSourceDecision({ markedSource: markedSource, hasCiphertext: hasCiphertext });
  if (decision.mode === 'refuse') return { ok: false, reason: decision.reason };

  let keyEncoded = readRaw(KEY_SECRET);
  const needNewKey = !crypto.isEncodedKey(keyEncoded);
  if (needNewKey && decision.mode !== 'create-pin') {
    // 已有密文但密钥丢了：绝不生成替代密钥（web :266 注释：禁止生成替代密钥、误判损坏）
    return { ok: false, reason: REASON_KEY_MISSING };
  }

  // P-031 修复：写前检查 CSPRNG 池容量（新建密钥 32B + 每字段 IV 12B）。
  // 不足则明确失败并提示预取，**绝不退回 Math.random 写弱密钥**。
  const requiredBytes = (needNewKey ? crypto.KEY_BYTES : 0) + names.length * crypto.IV_BYTES;
  if (crypto.entropyStatus().available < requiredBytes) {
    return { ok: false, reason: REASON_ENTROPY_UNAVAILABLE, needBytes: requiredBytes };
  }

  if (needNewKey) {
    let fresh;
    try {
      fresh = crypto.encodeBase64url(crypto.randomBytes(crypto.KEY_BYTES));
    } catch (err) {
      return { ok: false, reason: REASON_ENTROPY_UNAVAILABLE, needBytes: requiredBytes };
    }
    if (!writeRaw(KEY_SECRET, fresh)) return { ok: false, reason: REASON_STORAGE_UNAVAILABLE };
    keyEncoded = fresh;
  }
  if (decision.mode === 'create-pin' && markedSource !== crypto.LOCAL_SOURCE_MARKER) {
    if (!writeRaw(SOURCE_KEY, crypto.LOCAL_SOURCE_MARKER)) {
      return { ok: false, reason: REASON_STORAGE_UNAVAILABLE };
    }
  }
  const keyBytes = crypto.decodeBase64url(keyEncoded);
  const fields = record.fields || {};
  try {
    for (let i = 0; i < names.length; i += 1) {
      const name = names[i];
      fields[name] = crypto.buildCiphertext(String(values[name]), keyBytes, crypto.SOURCE_LOCAL);
    }
  } catch (err) {
    // IV 池在循环中被其他调用耗尽等：不写半成品
    return { ok: false, reason: REASON_ENTROPY_UNAVAILABLE, needBytes: requiredBytes };
  }
  if (!writeRaw(RECORD_KEY, { v: 2, fields: fields })) {
    return { ok: false, reason: REASON_STORAGE_UNAVAILABLE };
  }
  // 注意：这里**不**清旧明文键。写某类字段并不代表另一类明文已被迁移——
  // 若在此无条件 removeRaw，用户「先连桌面」就会把尚未迁移的 `biuz.jwt` 删掉
  // （登录永久丢失且无任何报错）。旧明文的删除权只归各自的迁移函数与 clearAll。
  return { ok: true, reason: null };
}

/**
 * 异步写入：先确保 CSPRNG 池有足够字节再同步写。
 * 池不足时自动向平台预取一次；平台不提供 CSPRNG 则明确失败（不写弱密钥）。
 * @param {Object} values
 * @returns {Promise<{ok:boolean, reason:string|null}>}
 */
function writeFieldsAsync(values) {
  const names = Object.keys(values || {});
  if (names.length === 0) return Promise.resolve({ ok: true, reason: null });
  const needNewKey = !crypto.isEncodedKey(readRaw(KEY_SECRET));
  const requiredBytes = (needNewKey ? crypto.KEY_BYTES : 0) + names.length * crypto.IV_BYTES;
  const attempt = () => writeFields(values);

  const pre = crypto.entropyStatus().available >= requiredBytes
    ? Promise.resolve()
    : crypto.prefillEntropy(requiredBytes);

  return pre
    .then(attempt)
    .catch(function () {
      // 预取失败（平台无 CSPRNG / 调用失败）：返回明确原因，不退化为弱随机
      return { ok: false, reason: REASON_ENTROPY_UNAVAILABLE, needBytes: requiredBytes };
    });
}

/** 在 `shouldClearOnFailure` 允许时才清该字段；其他原因一律保留整份容器。 */
function clearFieldOnCorruption(name, reason) {
  if (!shouldClearOnFailure(reason)) return false;
  const record = readRecord();
  if (!record || !record.fields || typeof record.fields[name] !== 'string') return false;
  delete record.fields[name];
  return writeRaw(RECORD_KEY, record);
}

/** 显式登出：用户主动要求才清（web `clear()` 是显式调用，不是读失败副作用）。 */
function clearAll() {
  removeRaw(RECORD_KEY);
  removeRaw(SOURCE_KEY);
  removeRaw(KEY_SECRET);
  removeRaw(LEGACY_JWT_KEY);
  removeRaw(LEGACY_DESKTOP_KEY);
}

/**
 * 一次性迁移：旧明文 JWT（`biuz.jwt`）→ 加密容器。
 * 只在**没有**加密 JWT 时执行；迁移成功后旧明文键被删除（web 迁移同纪律）。
 * 返回 `{migrated:boolean, reason:string|null}`。
 */
function migrateLegacyJwt() {
  const legacy = readRaw(LEGACY_JWT_KEY);
  if (typeof legacy !== 'string' || !legacy) return { migrated: false, reason: null };
  const current = readCredentials(['jwt']);
  if (current.outcome === OUTCOME_OK && current.fields.jwt) {
    // 已有可用加密 JWT：旧明文是历史残留（旧版本遗留/迁移中断），按 web 删除纪律清掉。
    // 不覆盖已有密文——密文优先，明文只做清理。
    removeRaw(LEGACY_JWT_KEY);
    return { migrated: false, reason: null, cleaned: true };
  }
  if (current.outcome === OUTCOME_UNAVAILABLE || current.outcome === OUTCOME_DECRYPT) {
    // 存在但坏/不可用的密文：不覆盖，交给上层按原因决策
    return { migrated: false, reason: current.reason };
  }
  const written = writeFields({ jwt: legacy });
  // 先写后清：只有密文落盘成功才删明文（失败必须保留原值）
  if (written.ok) removeRaw(LEGACY_JWT_KEY);
  return { migrated: written.ok, reason: written.reason };
}

/**
 * 异步版迁移：先确保 CSPRNG 池就绪再同步写。
 *
 * 为什么必须有它：`wx.getRandomValues` 只有异步形态，冷启动时 `onLaunch` 的预取
 * 尚未完成，池必然为空。若登录页在 `onLoad` 里调用同步 `migrateLegacyJwt()`，
 * 结果恒为 `entropy-unavailable` → 旧明文用户表现为「未登录」且明文永不迁移。
 * 因此启动首屏必须走本函数（内部 `prefillEntropy`），不能依赖同步版。
 *
 * @returns {Promise<{migrated:boolean, reason:string|null, cleaned?:boolean}>}
 */
function migrateLegacyJwtAsync() {
  const legacy = readRaw(LEGACY_JWT_KEY);
  if (typeof legacy !== 'string' || !legacy) return Promise.resolve({ migrated: false, reason: null });
  const current = readCredentials(['jwt']);
  if (current.outcome === OUTCOME_OK && current.fields.jwt) {
    removeRaw(LEGACY_JWT_KEY);
    return Promise.resolve({ migrated: false, reason: null, cleaned: true });
  }
  if (current.outcome === OUTCOME_UNAVAILABLE || current.outcome === OUTCOME_DECRYPT) {
    return Promise.resolve({ migrated: false, reason: current.reason });
  }
  return writeFieldsAsync({ jwt: legacy }).then(function (written) {
    // 先写后清：只删本函数负责的那一个明文键
    if (written.ok) removeRaw(LEGACY_JWT_KEY);
    return { migrated: written.ok, reason: written.reason };
  });
}

/**
 * 读取旧版明文桌面地址（`biuz.desktop`）。
 * 旧版 `connection.js` 存的是对象 `{baseUrl, token}`；也容忍 JSON 字符串形态。
 * @returns {{baseUrl:string, token:string}|null}
 */
function readLegacyDesktopAddress() {
  const raw = readRaw(LEGACY_DESKTOP_KEY);
  if (!raw) return null;
  let obj = raw;
  if (typeof raw === 'string') {
    try { obj = JSON.parse(raw); } catch (err) { return null; }
  }
  if (!obj || typeof obj !== 'object') return null;
  const baseUrl = typeof obj.baseUrl === 'string' ? obj.baseUrl : '';
  if (!baseUrl) return null;
  return { baseUrl: baseUrl, token: typeof obj.token === 'string' ? obj.token : '' };
}

/**
 * 一次性迁移：旧明文桌面地址（`biuz.desktop`，含配对 token）→ 加密容器。
 *
 * 为什么单列一个函数：`writeFields` 成功后虽会 `removeRaw(LEGACY_DESKTOP_KEY)`，
 * 但那是「任意一次凭据写入的副作用」——只升级登录、不重新连接桌面端的用户，
 * 明文 token 会一直留在本地；而一旦某次写入清掉它却没先迁移，用户的已保存连接
 * 就永久丢失。所以必须**先读后写再清**，与 JWT 迁移同纪律。
 *
 * 安全约束：已有可用加密地址时不覆盖（加密优先）；坏/不可用的密文不覆盖。
 * @returns {Promise<{migrated:boolean, reason:string|null, cleaned?:boolean}>}
 */
function migrateLegacyDesktopAsync() {
  const legacy = readLegacyDesktopAddress();
  if (!legacy) return Promise.resolve({ migrated: false, reason: null });
  const current = readCredentials(['baseUrl', 'token']);
  if (current.outcome === OUTCOME_OK && current.fields.baseUrl) {
    // 已有可用加密地址：旧明文是残留，清掉（不覆盖密文）
    removeRaw(LEGACY_DESKTOP_KEY);
    return Promise.resolve({ migrated: false, reason: null, cleaned: true });
  }
  if (current.outcome === OUTCOME_UNAVAILABLE || current.outcome === OUTCOME_DECRYPT) {
    return Promise.resolve({ migrated: false, reason: current.reason });
  }
  return writeFieldsAsync({ baseUrl: legacy.baseUrl, token: legacy.token }).then(function (written) {
    // 先写后清：只删本函数负责的那一个明文键
    if (written.ok) removeRaw(LEGACY_DESKTOP_KEY);
    return { migrated: written.ok, reason: written.reason };
  });
}

// ---- JWT / 连接地址便捷面（login.js 与 connection.js 的既有调用点同形） ----

function readJwt() {
  const result = readCredentials(['jwt']);
  return { ...result, value: result.outcome === OUTCOME_OK ? (result.fields.jwt || '') : '' };
}

function saveJwt(jwt) {
  const text = String(jwt == null ? '' : jwt).trim();
  if (!text) return { ok: false, reason: 'empty' };
  return writeFields({ jwt: text });
}

/** 异步版：先预取 CSPRNG 再写，避免池不足导致登录失败（推荐调用方使用）。 */
function saveJwtAsync(jwt) {
  const text = String(jwt == null ? '' : jwt).trim();
  if (!text) return Promise.resolve({ ok: false, reason: 'empty' });
  return writeFieldsAsync({ jwt: text });
}

/** 连接地址（baseUrl/token）：token 与 JWT 一起加密，baseUrl 一并进容器（口径统一）。 */
function readDesktopAddress() {
  const result = readCredentials(['baseUrl', 'token']);
  if (result.outcome === OUTCOME_OK && result.fields.baseUrl) {
    return { outcome: result.outcome, reason: null, baseUrl: result.fields.baseUrl, token: result.fields.token || '' };
  }
  return { outcome: result.outcome, reason: result.reason, baseUrl: null, token: '' };
}

function saveDesktopAddress(baseUrl, token) {
  return writeFields({ baseUrl: String(baseUrl == null ? '' : baseUrl), token: String(token == null ? '' : token) });
}

/** 异步版：先预取 CSPRNG 再写（推荐调用方使用）。 */
function saveDesktopAddressAsync(baseUrl, token) {
  return writeFieldsAsync({
    baseUrl: String(baseUrl == null ? '' : baseUrl),
    token: String(token == null ? '' : token),
  });
}

module.exports = {
  SOURCE_KEY: SOURCE_KEY,
  KEY_SECRET: KEY_SECRET,
  RECORD_KEY: RECORD_KEY,
  LEGACY_JWT_KEY: LEGACY_JWT_KEY,
  LEGACY_DESKTOP_KEY: LEGACY_DESKTOP_KEY,
  OUTCOME_OK: OUTCOME_OK,
  OUTCOME_MISSING: OUTCOME_MISSING,
  OUTCOME_UNAVAILABLE: OUTCOME_UNAVAILABLE,
  OUTCOME_DECRYPT: OUTCOME_DECRYPT,
  REASON_NO_SOURCE_MARKER: REASON_NO_SOURCE_MARKER,
  REASON_SOURCE_OS_PINNED: REASON_SOURCE_OS_PINNED,
  REASON_SOURCE_MISMATCH: REASON_SOURCE_MISMATCH,
  REASON_KEY_MISSING: REASON_KEY_MISSING,
  REASON_KEY_INVALID: REASON_KEY_INVALID,
  REASON_KEY_FINGERPRINT_MISMATCH: REASON_KEY_FINGERPRINT_MISMATCH,
  REASON_RECORD_CORRUPT: REASON_RECORD_CORRUPT,
  REASON_STORAGE_UNAVAILABLE: REASON_STORAGE_UNAVAILABLE,
  REASON_ENTROPY_UNAVAILABLE: REASON_ENTROPY_UNAVAILABLE,
  shouldClearOnFailure: shouldClearOnFailure,
  classifyFailure: classifyFailure,
  resolveSourceDecision: resolveSourceDecision,
  evaluateRead: evaluateRead,
  readSourceMarker: readSourceMarker,
  readRecord: readRecord,
  readCredentials: readCredentials,
  writeFields: writeFields,
  writeFieldsAsync: writeFieldsAsync,
  clearFieldOnCorruption: clearFieldOnCorruption,
  clearAll: clearAll,
  migrateLegacyJwt: migrateLegacyJwt,
  migrateLegacyJwtAsync: migrateLegacyJwtAsync,
  migrateLegacyDesktopAsync: migrateLegacyDesktopAsync,
  readLegacyDesktopAddress: readLegacyDesktopAddress,
  readJwt: readJwt,
  saveJwt: saveJwt,
  saveJwtAsync: saveJwtAsync,
  readDesktopAddress: readDesktopAddress,
  saveDesktopAddress: saveDesktopAddress,
  saveDesktopAddressAsync: saveDesktopAddressAsync,
};

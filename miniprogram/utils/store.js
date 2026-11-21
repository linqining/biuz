// 会话/任务快照 store —— 显式状态机 + 可注入数据源。
//
// 纪律（AGENTS.md §5-12 / §5-13）：
// - 没有数据源时 status='disconnected'，页面给如实空态，**不填假数据**；
// - 写面（归档/新建）在 RPC 未接入前必须如实失败，不得伪装成功。
//
// 状态：idle → (有源) loading → connected | error ；(无源) disconnected
// 连接层接入方式：store.setSource(fn)，fn 返回 Promise<rows[]>。

const state = {
  status: 'idle',
  rows: [],
  error: '',
  deviceName: '',
  accountName: '',
  /**
   * 008 版本提示：桌面实际版本与配对链接声明不一致时的「桌面当前版本」。
   * web 口径 `webRemoteControlVersion.ts:26-52`；iOS `VersionGuard.swift` / Android `VersionGuard.kt` 同判据。
   */
  versionNotice: '',
};

const listeners = new Set();
let source = null;

function getState() {
  return state;
}

function emit() {
  listeners.forEach(function (fn) {
    try {
      fn(state);
    } catch (err) {
      console.error('[朱批] store listener failed', err);
    }
  });
}

function patch(next) {
  Object.assign(state, next);
  emit();
}

function subscribe(fn) {
  listeners.add(fn);
  return function unsubscribe() {
    listeners.delete(fn);
  };
}

/** 连接层注入数据源；传 null 表示断开（回到 disconnected）。 */
function setSource(fn) {
  source = fn || null;
  if (!source) {
    patch({ status: 'disconnected', rows: [], error: '' });
  }
}

function setIdentity(identity) {
  patch({
    deviceName: identity && identity.deviceName ? identity.deviceName : state.deviceName,
    accountName: identity && identity.accountName ? identity.accountName : state.accountName,
  });
}

function refresh() {
  if (!source) {
    patch({ status: 'disconnected', rows: [], error: '' });
    return Promise.resolve();
  }
  patch({ status: 'loading', error: '' });
  return Promise.resolve(source())
    .then(function (rows) {
      patch({ status: 'connected', rows: rows || [], error: '' });
    })
    .catch(function (err) {
      // 读面失败：保留 last-good 行，只叠加失败文案（不折叠成空态）
      patch({ status: 'error', error: (err && err.message) || String(err) });
    });
}

/** 测试/连接层注入快照（不伪造，只接受真实回执）。 */
function setRows(rows) {
  patch({ status: 'connected', rows: rows || [], error: '' });
}

/**
 * 版本守卫（纯判据，供连接层在 bootstrap 回执到达时调用）：
 * 链接声明版本 vs 实际桌面版本，任一侧为空不判（宁可放过不误拦）。
 */
function checkVersion(linkVersion, desktopVersion) {
  const actual = String(desktopVersion == null ? '' : desktopVersion).trim();
  const declared = String(linkVersion == null ? '' : linkVersion).trim();
  if (!actual || !declared) {
    patch({ versionNotice: '' });
    return null;
  }
  const mismatch = declared === actual ? '' : actual;
  patch({ versionNotice: mismatch });
  return mismatch || null;
}

/** 用户确认「重新加载」：清提示（连接层随后用新版本重连，需同步回写链接版本，避免死循环）。 */
function confirmVersionReload() {
  const version = state.versionNotice;
  patch({ versionNotice: '' });
  return version;
}

function archive(id) {
  if (!source) {
    patch({ error: '归档需要连接桌面端' });
    return false;
  }
  patch({ error: '归档命令待接入' });
  return false;
}

module.exports = {
  getState: getState,
  subscribe: subscribe,
  setSource: setSource,
  setIdentity: setIdentity,
  setRows: setRows,
  refresh: refresh,
  archive: archive,
  checkVersion: checkVersion,
  confirmVersionReload: confirmVersionReload,
};

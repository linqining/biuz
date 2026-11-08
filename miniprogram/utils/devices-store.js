// 设备列表快照 store（003）—— 显式状态机 + 可注入数据源。
//
// 纪律同 utils/store.js：无数据源时 status='disconnected' 不给假设备；写面（切换账号）
// 未接入时如实失败。连接层接入方式：setSource(fn)，fn 返回 Promise<{ devices, account }>。

const state = {
  status: 'idle', // idle | loading | ready | error | disconnected
  devices: [],
  account: null,
  error: '',
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
      console.error('[BiuZ] devices store listener failed', err);
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

function setSource(fn) {
  source = fn || null;
  if (!source) patch({ status: 'disconnected', devices: [], account: null, error: '' });
}

function refresh() {
  if (!source) {
    patch({ status: 'disconnected', devices: [], account: null, error: '' });
    return Promise.resolve();
  }
  patch({ status: 'loading', error: '' });
  return Promise.resolve(source())
    .then(function (result) {
      const data = result || {};
      patch({
        status: 'ready',
        devices: data.devices || [],
        account: data.account || null,
        error: '',
      });
    })
    .catch(function (err) {
      // 读面失败：保留 last-good 设备列表，只叠加失败文案（不折叠成空态）
      patch({ status: 'error', error: (err && err.message) || String(err) });
    });
}

/** 连接层/测试注入真实回执（不伪造）。 */
function setSnapshot(devices, account) {
  patch({ status: 'ready', devices: devices || [], account: account || null, error: '' });
}

module.exports = {
  getState: getState,
  subscribe: subscribe,
  setSource: setSource,
  setSnapshot: setSnapshot,
  refresh: refresh,
};

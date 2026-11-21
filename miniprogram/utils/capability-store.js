// 能力清单 store（四页共用一个参数化 store：kind ∈ memory|skills|mcp|plugins）。
// 纪律同 utils/store.js：无数据源/未连接如实空态；读失败 last-good + 错误行。
// 连接层接入方式：setSource(fn)，fn(kind) 返回 Promise<{rows}>。

const state = {
  kind: 'memory',
  status: 'idle', // idle | loading | ready | error | disconnected
  rows: [],
  error: '',
};

const listeners = new Set();
let source = null;

function getState() { return state; }

function emit() {
  listeners.forEach(function (fn) {
    try { fn(state); } catch (err) { console.error('[朱批] capability listener failed', err); }
  });
}

function patch(next) {
  Object.assign(state, next);
  emit();
}

function subscribe(fn) {
  listeners.add(fn);
  return function unsubscribe() { listeners.delete(fn); };
}

function setSource(fn) {
  source = fn || null;
  if (!source) patch({ status: 'disconnected', rows: [], error: '' });
}

function refresh(kind) {
  const nextKind = kind || state.kind;
  if (!source) {
    patch({ kind: nextKind, status: 'disconnected', rows: [], error: '' });
    return Promise.resolve();
  }
  patch({ kind: nextKind, status: 'loading', error: '' });
  return Promise.resolve(source(nextKind))
    .then(function (data) {
      patch({
        kind: nextKind,
        status: 'ready',
        rows: (data && data.rows) || [],
        error: '',
      });
    })
    .catch(function (err) {
      // 读面失败：last-good 保留 + 错误行（不折叠空态，§5-12）
      patch({ kind: nextKind, status: 'error', error: (err && err.message) || String(err) });
    });
}

module.exports = {
  getState: getState,
  subscribe: subscribe,
  setSource: setSource,
  refresh: refresh,
};

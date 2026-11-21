// 文件树快照 store（030）—— 显式状态机 + 可注入数据源。
// 纪律同其它 store：无数据源如实空态；预览内容未接入时如实提示。

const state = {
  status: 'idle', // idle | loading | ready | error | disconnected
  workspacePath: '',
  tree: [],
  error: '',
};

const listeners = new Set();
let source = null;

function getState() {
  return state;
}

function emit() {
  listeners.forEach(function (fn) {
    try { fn(state); } catch (err) { console.error('[朱批] files store listener failed', err); }
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
  if (!source) patch({ status: 'disconnected', tree: [], workspacePath: '', error: '' });
}

function refresh() {
  if (!source) {
    patch({ status: 'disconnected', tree: [], workspacePath: '', error: '' });
    return Promise.resolve();
  }
  patch({ status: 'loading', error: '' });
  return Promise.resolve(source())
    .then(function (data) {
      const payload = data || {};
      patch({
        status: 'ready',
        tree: payload.tree || [],
        workspacePath: payload.workspacePath || '',
        error: '',
      });
    })
    .catch(function (err) {
      patch({ status: 'error', error: (err && err.message) || String(err) });
    });
}

/** 连接层/测试注入真实回执（不伪造）。 */
function setTree(tree, workspacePath) {
  patch({ status: 'ready', tree: tree || [], workspacePath: workspacePath || '', error: '' });
}

module.exports = {
  getState: getState,
  subscribe: subscribe,
  setSource: setSource,
  setTree: setTree,
  refresh: refresh,
};

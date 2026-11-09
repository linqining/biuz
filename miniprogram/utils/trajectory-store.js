// 模型轨迹快照 store（038）—— 显式状态机 + 可注入数据源。
const state = { status: 'idle', entries: [], taskTitle: '', error: '' };
const listeners = new Set();
let source = null;

function getState() { return state; }
function emit() { listeners.forEach(function (fn) { try { fn(state); } catch (e) { console.error('[BiuZ] trajectory store listener failed', e); } }); }
function patch(next) { Object.assign(state, next); emit(); }
function subscribe(fn) { listeners.add(fn); return function () { listeners.delete(fn); }; }
function setSource(fn) { source = fn || null; if (!source) patch({ status: 'disconnected', entries: [], taskTitle: '', error: '' }); }
function refresh(taskId) {
  if (!source) { patch({ status: 'disconnected', entries: [], taskTitle: '', error: '' }); return Promise.resolve(); }
  patch({ status: 'loading', error: '' });
  return Promise.resolve(source(taskId))
    .then(function (data) {
      const payload = data || {};
      patch({ status: 'ready', entries: payload.entries || [], taskTitle: payload.taskTitle || '', error: '' });
    })
    .catch(function (err) { patch({ status: 'error', error: (err && err.message) || String(err) }); });
}
function setEntries(entries, taskTitle) { patch({ status: 'ready', entries: entries || [], taskTitle: taskTitle || '', error: '' }); }

module.exports = { getState: getState, subscribe: subscribe, setSource: setSource, setEntries: setEntries, refresh: refresh };

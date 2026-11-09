// Diff 快照 store（029）—— 显式状态机 + 可注入数据源。
const state = { status: 'idle', files: [], error: '' };
const listeners = new Set();
let source = null;

function getState() { return state; }
function emit() { listeners.forEach(function (fn) { try { fn(state); } catch (e) { console.error('[BiuZ] diff store listener failed', e); } }); }
function patch(next) { Object.assign(state, next); emit(); }
function subscribe(fn) { listeners.add(fn); return function () { listeners.delete(fn); }; }
function setSource(fn) { source = fn || null; if (!source) patch({ status: 'disconnected', files: [], error: '' }); }
function refresh() {
  if (!source) { patch({ status: 'disconnected', files: [], error: '' }); return Promise.resolve(); }
  patch({ status: 'loading', error: '' });
  return Promise.resolve(source())
    .then(function (files) { patch({ status: 'ready', files: files || [], error: '' }); })
    .catch(function (err) { patch({ status: 'error', error: (err && err.message) || String(err) }); });
}
function setFiles(files) { patch({ status: 'ready', files: files || [], error: '' }); }

module.exports = { getState: getState, subscribe: subscribe, setSource: setSource, setFiles: setFiles, refresh: refresh };

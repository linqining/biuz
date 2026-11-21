// 会话快照 store（011）—— 显式状态机 + 可注入数据源。
//
// 纪律同其它 store：无数据源如实显示空态，不造假消息；写面（发送）未接入时如实失败。
// 连接层接入方式：setSource(fn)，fn 返回 Promise<{ title, messages }>。

const state = {
  status: 'idle', // idle | loading | ready | error | disconnected
  title: '',
  messages: [],
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
      console.error('[朱批] chat store listener failed', err);
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
  if (!source) {
    patch({ status: 'disconnected', messages: [], title: '', error: '' });
  }
}

function refresh(sessionId) {
  if (!source) {
    patch({ status: 'disconnected', messages: [], title: '', error: '' });
    return Promise.resolve();
  }
  patch({ status: 'loading', error: '' });
  return Promise.resolve(source(sessionId))
    .then(function (data) {
      const payload = data || {};
      patch({
        status: 'ready',
        title: payload.title || '',
        messages: payload.messages || [],
        error: '',
      });
    })
    .catch(function (err) {
      // 读面失败：保留 last-good 消息，只叠加失败文案（不折叠成空态）
      patch({ status: 'error', error: (err && err.message) || String(err) });
    });
}

/** 连接层/测试注入真实回执（不伪造）。 */
function setMessages(messages, title) {
  patch({
    status: 'ready',
    messages: messages || [],
    title: title || state.title,
    error: '',
  });
}

module.exports = {
  getState: getState,
  subscribe: subscribe,
  setSource: setSource,
  setMessages: setMessages,
  refresh: refresh,
};

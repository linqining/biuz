// wx 运行时薄壳 —— wx.request / wx.connectSocket 适配到 utils/rpc-connection.js
// 的注入面（httpGet(url) → Promise<{status, body}>；socketFactory({url}) →
// {send(Uint8Array), onMessage(cb), onClose(cb), close()}）。
//
// 纪律：这里只做平台 API 形状适配，零协议逻辑（协议在 rpc-* 层）；
// 开发者工具可用 ws://127.0.0.1（urlCheck 以 project.private.config.json 优先）。

/** wx.request → httpGet 注入件。 */
function wxHttpGet(url) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: url,
      method: 'GET',
      responseType: 'text',
      success: function (res) {
        resolve({ status: res.statusCode, body: res.data });
      },
      fail: function (err) {
        reject(new Error((err && err.errMsg) || 'request failed'));
      },
    });
  });
}

/** wx.request POST → JSON API 注入件（中继 token / 设备目录）。 */
function wxHttpPost(url, jwt, bodyObj) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: url,
      method: 'POST',
      data: bodyObj,
      header: { Authorization: 'Bearer ' + jwt },
      success: function (res) { resolve({ status: res.statusCode, body: res.data }); },
      fail: function (err) {
        reject(new Error((err && err.errMsg) || 'request failed'));
      },
    });
  });
}

/** wx.request GET（带可选 Bearer）→ JSON API 注入件（设备目录）。 */
function wxHttpGetJson(url, jwt) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: url,
      method: 'GET',
      header: jwt ? { Authorization: 'Bearer ' + jwt } : {},
      success: function (res) { resolve({ status: res.statusCode, body: res.data }); },
      fail: function (err) {
        reject(new Error((err && err.errMsg) || 'request failed'));
      },
    });
  });
}

/** wx.connectSocket → socketFactory 注入件（单层：直接接 {url}——
 * 曾误包双层导致 rpc-client 收到内层函数当 transport）。 */
function wxSocketFactory(params) {
  {
    let task;
    try {
      task = wx.connectSocket({
        url: params.url,
        // 不带子协议（protocols）：替身 101 未回显子协议时 wx 侧会主动关连接
        // （实测「连接已关闭」根因）；二进制帧本就是 wx 默认帧形态之一
      });
    } catch (err) {
      return failedSocket(err && err.message);
    }
    const messageCbs = [];
    const closeCbs = [];
    const pendingSends = []; // onOpen 前的发送排队（hello 在 open 回调前即发）
    let opened = false;
    let failed = false;
    let failMessage = '';
    let closeNotified = false;

    function notifyClose() {
      if (closeNotified) return;
      closeNotified = true;
      closeCbs.forEach(function (cb) {
        try { cb(); } catch (e) { console.error('[朱批] close cb', e); }
      });
    }

    if (task && task.onOpen) {
      task.onOpen(function () {
        opened = true;
        while (pendingSends.length) {
          task.send({ data: pendingSends.shift() });
        }
      });
    }
    if (task && task.onMessage) {
      task.onMessage(function (res) {
        const data = res && res.data;
        if (data instanceof ArrayBuffer) {
          const bytes = new Uint8Array(data);
          messageCbs.forEach(function (cb) {
            try { cb(bytes); } catch (e) { console.error('[朱批] message cb', e); }
          });
        } else if (typeof data === 'string') {
          // 中继 V2 auth 面走文本帧（auth_init/auth_ack JSON——RelayTransport
          // sendJSONLocked 同口径）；rpc 桥内帧仍为二进制
          messageCbs.forEach(function (cb) {
            try { cb(data); } catch (e) { console.error('[朱批] message cb', e); }
          });
        }
      });
    }
    if (task && task.onError) {
      task.onError(function (err) {
        failed = true;
        failMessage = (err && err.errMsg) || 'socket error';
        notifyClose();
      });
    }
    if (task && task.onClose) {
      task.onClose(function () { notifyClose(); });
    }

    return {
      send: function (payload) {
        // 双形态：Uint8Array→ArrayBuffer（rpc 二进制帧）；string→文本（中继 V2
        // auth_init/auth_ack JSON——RelayTransport sendJSONLocked 同口径）
        const data = typeof payload === 'string'
          ? payload
          : payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength);
        if (opened && task) {
          task.send({ data: data });
        } else if (!failed) {
          pendingSends.push(data);
        }
        // failed 后静默丢弃：onError 已走 onClose → pending 由 client 统一 reject
      },
      onMessage: function (cb) { messageCbs.push(cb); },
      onClose: function (cb) { closeCbs.push(cb); },
      close: function () {
        try { if (task) task.close({}); } catch (e) { /* 幂等 */ }
        notifyClose();
      },
    };
  };
}

function failedSocket(message) {
  return {
    send: function () {},
    onMessage: function () {},
    onClose: function (cb) { setTimeout(cb, 0); },
    close: function () {},
  };
}

module.exports = {
  wxHttpGet: wxHttpGet,
  wxHttpPost: wxHttpPost,
  wxHttpGetJson: wxHttpGetJson,
  wxSocketFactory: wxSocketFactory,
};

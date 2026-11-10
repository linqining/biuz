// 001-登录 + P-018 JWT 粘贴持久化 + 中继 V2 连接
//
// web 权威源：packages/web/src/auth/WebLoginPage.tsx:13-52（结构）
//             packages/web/src/auth/webAuthLocale.ts:20-24（文案）
//
// 完整 OAuth 授权需 web-view 跳转 + 服务端回调域名白名单（后续波次）。
// 当前落法（务实）：粘贴 JWT → 持久化 → 直发 connectRelayDesktop（中继 V2 全链：
// token→ws2→auth_init→paired→sessions 等 store 数据源点亮）。
// provider 按钮如实提示「OAuth 授权待接入」——不伪造登录。
const COPY = {
  brand: 'ZCode',
  loginTitle: '登录后继续使用 Web 远程控制',
  loginDescription: '请选择与电脑端相同的登录平台，并使用同一账号。',
  providers: [
    { id: 'zai', action: '连接 Z.ai 继续使用', region: '全球', icon: 'Z' },
    { id: 'bigmodel', action: '连接 BigModel 继续使用', region: '中国', icon: '◆' },
  ],
};

const JWT_KEY = 'biuz.jwt';

Page({
  data: {
    copy: COPY,
    submitting: false,
    connecting: false,
    statusBarHeight: 0,
    jwtStored: false,
  },

  onLoad() {
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) {
      statusBarHeight = 0;
    }
    this.setData({
      statusBarHeight: statusBarHeight,
      jwtStored: this.hasJwt(),
    });
  },

  onShow() {
    this.setData({ jwtStored: this.hasJwt() });
  },

  hasJwt() {
    try {
      const jwt = wx.getStorageSync(JWT_KEY) || '';
      return jwt.length > 20;
    } catch (err) {
      return false;
    }
  },

  getStoredJwt() {
    try { return wx.getStorageSync(JWT_KEY) || ''; } catch (err) { return ''; }
  },

  /** P-018 务实落法：粘贴 JWT → 持久化（中继 V2 用）。 */
  onPasteJwt() {
    const self = this;
    wx.showModal({
      title: '粘贴 Z.ai JWT',
      editable: true,
      placeholderText: 'eyJhbGciOi…（从 web 端 ZCode 设置页复制）',
      success: function (res) {
        if (!res.confirm) return;
        const jwt = (res.content || '').trim();
        if (!jwt || jwt.length < 20) {
          wx.showToast({ title: 'JWT 无效（太短）', icon: 'none' });
          return;
        }
        try {
          wx.setStorageSync(JWT_KEY, jwt);
          self.setData({ jwtStored: true });
          wx.showToast({ title: 'JWT 已保存', icon: 'success' });
        } catch (err) {
          wx.showToast({ title: '存储失败', icon: 'none' });
        }
      },
    });
  },

  /** 清除 JWT（重登录用）。 */
  onClearJwt() {
    try { wx.removeStorageSync(JWT_KEY); } catch (err) { /* 幂等 */ }
    this.setData({ jwtStored: false });
    wx.showToast({ title: 'JWT 已清除', icon: 'none' });
  },

  /** 中继 V2 连接（JWT 已存时点按触发）。 */
  onConnectRelay() {
    const jwt = this.getStoredJwt();
    if (!jwt) {
      wx.showToast({ title: '请先粘贴 JWT', icon: 'none' });
      return;
    }
    const self = this;
    wx.showModal({
      title: '中继链接',
      editable: true,
      placeholderText: 'https://zcode.z.ai/remote/v4?sid=…&mid=…',
      success: function (res) {
        if (!res.confirm) return;
        const relayLink = (res.content || '').trim();
        if (!relayLink) return;
        self.setData({ connecting: true });
        const conn = require('../../utils/connection.js');
        conn.connectRelayDesktop({ relayLink: relayLink, jwt: jwt })
          .then(function () {
            self.setData({ connecting: false });
            wx.reLaunch({ url: '/pages/sessions/sessions' });
          })
          .catch(function (err) {
            self.setData({ connecting: false });
            wx.navigateTo({
              url: '/pages/connecting/connecting?mode=failure&reason=transport&detail=' +
                encodeURIComponent((err && err.message) || '中继连接失败'),
            });
          });
      },
    });
  },

  onLogin(event) {
    if (this.data.submitting) return;
    const provider = event.currentTarget.dataset.provider;
    this.setData({ submitting: true });
    wx.showToast({ title: 'OAuth 授权待接入；请先粘贴 JWT', icon: 'none' });
    this.setData({ submitting: false });
    console.log('[BiuZ] login intent', provider);
  },
});

// 001-登录 + P-018 JWT 粘贴持久化
//
// web 权威源：packages/web/src/auth/WebLoginPage.tsx:13-52（结构）
//             packages/web/src/auth/webAuthLocale.ts:20-24（文案）
//
// 完整 OAuth 授权需 web-view 跳转 + 服务端回调域名白名单（后续波次）。
// 当前落法（务实）：用户从 web 浏览器 Z.ai 设置页复制 JWT 粘贴到本页，
// 持久化于 wx.setStorageSync('biuz.jwt') 供中继 V2 使用（connectRelayDesktop）。
// provider 按钮保留 UI 结构但如实提示「OAuth 授权待接入」——不伪造登录。
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

module.exports = { JWT_KEY: JWT_KEY };
  },

  onShow() {
    this.setData({ jwtStored: this.hasJwt() });

module.exports = { JWT_KEY: JWT_KEY };
  },

  hasJwt() {
    try {
      const jwt = wx.getStorageSync(JWT_KEY) || '';
      return jwt.length > 20;
    } catch (err) {
      return false;
    }
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

module.exports = { JWT_KEY: JWT_KEY };
          return;
        }
        try {
          wx.setStorageSync(JWT_KEY, jwt);
          self.setData({ jwtStored: true });

module.exports = { JWT_KEY: JWT_KEY };
          wx.showToast({ title: 'JWT 已保存', icon: 'success' });

module.exports = { JWT_KEY: JWT_KEY };
        } catch (err) {
          wx.showToast({ title: '存储失败', icon: 'none' });

module.exports = { JWT_KEY: JWT_KEY };
        }
      },
    });

module.exports = { JWT_KEY: JWT_KEY };
  },

  /** 清除 JWT（重登录用）。 */
  onClearJwt() {
    try { wx.removeStorageSync(JWT_KEY); } catch (err) { /* 幂等 */ }
    this.setData({ jwtStored: false });

module.exports = { JWT_KEY: JWT_KEY };
    wx.showToast({ title: 'JWT 已清除', icon: 'none' });

module.exports = { JWT_KEY: JWT_KEY };
  },

  onLogin(event) {
    if (this.data.submitting) return;
    const provider = event.currentTarget.dataset.provider;
    this.setData({ submitting: true });

module.exports = { JWT_KEY: JWT_KEY };
    // OAuth 授权跳转待接入（web-view 组件 + 服务端回调域名白名单——后续波次）；
    // 当前如实提示，不伪造已登录
    wx.showToast({ title: 'OAuth 授权待接入；请先粘贴 JWT', icon: 'none' });

module.exports = { JWT_KEY: JWT_KEY };
    this.setData({ submitting: false });

module.exports = { JWT_KEY: JWT_KEY };
    console.log('[BiuZ] login intent', provider);
  },
});

module.exports = { JWT_KEY: JWT_KEY };

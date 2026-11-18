// 001-登录 + P-018 JWT 粘贴持久化 + 中继 V2 连接
//
// web 权威源：packages/web/src/auth/WebLoginPage.tsx:13-52（结构）
//             packages/web/src/auth/webAuthLocale.ts:20-24（文案）
//
// 完整 OAuth 授权需 web-view 跳转 + 服务端回调域名白名单（后续波次）。
// 当前落法（务实）：粘贴 JWT → 持久化 → 直发 connectRelayDesktop（中继 V2 全链：
// token→ws2→auth_init→paired→sessions 等 store 数据源点亮）。
// provider 按钮如实提示「OAuth 授权待接入」——不伪造登录。
//
// 凭据落盘（2026-11-19，web v3.18.2 凭证安全语义对齐）：原来 `wx.setStorageSync('biuz.jwt',
// jwt)` 是**明文**；现改走 utils/credential-store.js（加密容器 + 来源标记 + 失败原因三分）。
// APP-DIFF[D014]：小程序无 OS keychain 等价物，密钥托管在本地存储——只消除明文落盘，
// 不能防同设备同账号运行时读取，安全等级低于 iOS/Android（差距见 PARITY_BACKLOG P-031）。
const credentialStore = require('../../utils/credential-store.js');

const COPY = {
  brand: 'ZCode',
  loginTitle: '登录后继续使用 Web 远程控制',
  loginDescription: '请选择与电脑端相同的登录平台，并使用同一账号。',
  providers: [
    { id: 'zai', action: '连接 Z.ai 继续使用', region: '全球', icon: 'Z' },
    { id: 'bigmodel', action: '连接 BigModel 继续使用', region: '中国', icon: '◆' },
  ],
};

Page({
  data: {
    copy: COPY,
    submitting: false,
    connecting: false,
    statusBarHeight: 0,
    jwtStored: false,
    // 凭据读取失败原因（web：`unavailable` 与 `decrypt` 必须区别上屏，
    // 不能统一显示「未登录」——systemCredentialCipher.ts:67-75）
    credentialIssue: '',
  },

  onLoad() {
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) {
      statusBarHeight = 0;
    }
    // 旧明文 JWT（biuz.jwt）→ 加密容器；只在没有加密 JWT 时执行，失败不丢原值
    const migration = credentialStore.migrateLegacyJwt();
    const state = this.readJwtState();
    this.setData({
      statusBarHeight: statusBarHeight,
      jwtStored: state.stored,
      credentialIssue: state.issue || (migration.reason ? this.issueCopy(migration.reason) : ''),
    });
  },

  onShow() {
    const state = this.readJwtState();
    this.setData({ jwtStored: state.stored, credentialIssue: state.issue });
  },

  /**
   * 读取持久化 JWT 状态。三条读失败原因分别上屏（web 三分口径：
   * systemCredentialCipher.ts:67-75 / oauth.ts:39-56）：只有「密文确坏」才允许清。
   */
  readJwtState() {
    const result = credentialStore.readJwt();
    const stored = result.outcome === credentialStore.OUTCOME_OK && result.value.length > 20;
    if (result.outcome === credentialStore.OUTCOME_MISSING) return { stored: false, issue: '' };
    if (result.outcome === credentialStore.OUTCOME_UNAVAILABLE) {
      return { stored: false, issue: this.issueCopy(result.reason) };
    }
    if (result.outcome === credentialStore.OUTCOME_DECRYPT) {
      // 数据确坏：清理确坏字段并如实告知（web：只有 isCredentialDecryptError 才清）
      credentialStore.clearFieldOnCorruption('jwt', result.reason);
      return { stored: false, issue: '已保存的凭据无法解密（数据损坏），请重新粘贴 JWT' };
    }
    return { stored: stored, issue: '' };
  },

  /** 失败原因 → 用户可读文案（不暴露密钥材料，只说明可执行的下一步）。
   *  APP-DIFF[D017] platform: 小程序为登录页单行提示（`01-note-credential-issue`）。 */
  issueCopy(reason) {
    switch (reason) {
      case credentialStore.REASON_SOURCE_OS_PINNED:
      case credentialStore.REASON_SOURCE_MISMATCH:
        return '凭据由其他端写入（来源不一致），本机无法解密；请重新粘贴 JWT';
      case credentialStore.REASON_NO_SOURCE_MARKER:
      case credentialStore.REASON_KEY_MISSING:
      case credentialStore.REASON_KEY_INVALID:
        return '本机凭据密钥已丢失（换设备/清数据），已保存的登录无法恢复；请重新登录';
      case credentialStore.REASON_KEY_FINGERPRINT_MISMATCH:
        return '本机凭据密钥与原记录不匹配，无法解密；请重新登录';
      case credentialStore.REASON_RECORD_CORRUPT:
        return '已保存的凭据无法解密（数据损坏），请重新粘贴 JWT';
      case credentialStore.REASON_STORAGE_UNAVAILABLE:
        return '本地存储不可用，凭据未保存';
      default:
        return '';
    }
  },

  hasJwt() {
    return this.readJwtState().stored;
  },

  getStoredJwt() {
    const result = credentialStore.readJwt();
    return result.outcome === credentialStore.OUTCOME_OK ? result.value : '';
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
        // P-031 修复：异步写入（先预取平台 CSPRNG），池不可用时明确拒绝，绝不写弱密钥
        credentialStore.saveJwtAsync(jwt).then(function (written) {
          if (!written.ok) {
            wx.showToast({
              title: written.reason === credentialStore.REASON_STORAGE_UNAVAILABLE
                ? '存储失败'
                : written.reason === credentialStore.REASON_ENTROPY_UNAVAILABLE
                  ? '安全随机数不可用，无法加密保存'
                  : '凭据写入被拒绝',
              icon: 'none',
            });
            return;
          }
          self.setData({ jwtStored: true, credentialIssue: '' });
          wx.showToast({ title: 'JWT 已保存（加密）', icon: 'success' });
        }).catch(function () {
          wx.showToast({ title: '安全随机数不可用，无法加密保存', icon: 'none' });
        });
      },
    });
  },

  /** 清除 JWT（用户显式登出：web `clear()` 是显式调用，不是读失败的副作用）。 */
  onClearJwt() {
    credentialStore.clearAll();
    this.setData({ jwtStored: false, credentialIssue: '' });
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

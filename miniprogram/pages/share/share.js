// 009-分享落地（只读预览）
//
// web 权威源：`packages/web/src/share/ConversationShareLandingPage.tsx`（四态）；
// iOS 对位 `SharePreviewSheet`。小程序侧用 wx.request 直连预览接口（公开分享未登录可看），
// 私有分享由服务端 401/403 拒——如实上屏，不预先拦。
const layout = require('../../utils/layout.js');
const model = require('../../utils/share-model.js');

const ENDPOINT = 'https://zcode.z.ai/api/v1/shares/';

Page({
  data: {
    statusBarHeight: 20,
    status: 'loading', // loading | ready | error
    code: '',
    preview: null,
    errorText: '',
  },

  onLoad(query) {
    const code = (query && query.code) || '';
    this.setData({
      statusBarHeight: layout.statusBarHeight(),
      code: code,
    });
    if (!code) {
      this.setData({ status: 'error', errorText: '分享链接缺少分享码' });
      return;
    }
    this.load();
  },

  load() {
    const code = this.data.code;
    this.setData({ status: 'loading', errorText: '' });
    wx.request({
      url: ENDPOINT + encodeURIComponent(code) + '/preview',
      method: 'GET',
      timeout: 10000,
      success: (res) => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          this.setData({ status: 'error', errorText: model.errorText(res.statusCode) });
          return;
        }
        const preview = model.parsePreview(res.data);
        if (!preview) {
          this.setData({ status: 'error', errorText: '分享预览解析失败 · 响应不是 JSON 对象' });
          return;
        }
        this.setData({ status: 'ready', preview: preview });
      },
      fail: (err) => {
        this.setData({
          status: 'error',
          errorText: '暂时无法加载分享（' + ((err && err.errMsg) || '网络错误') + '）',
        });
      },
    });
  },

  onRetry() {
    this.load();
  },

  onClose() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },
});

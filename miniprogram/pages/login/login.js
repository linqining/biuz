// 001-登录
//
// web 权威源：packages/web/src/auth/WebLoginPage.tsx:13-52（结构）
//             packages/web/src/auth/webAuthLocale.ts:20-24（文案）
// 线上基线：.capture/runs/20261108-073043-001/01-viewport.png
//
// 小程序无 localStorage OAuth 跳转能力，真实登录需走服务端换取 code（W2 后续轮次接入
// 连接层后接线）；本页当前只负责界面与登录意图上报，不伪造登录成功。
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
    statusBarHeight: 0,
  },

  onLoad() {
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) {
      statusBarHeight = 0;
    }
    this.setData({ statusBarHeight: statusBarHeight });
  },

  onLogin(event) {
    if (this.data.submitting) return;
    const provider = event.currentTarget.dataset.provider;
    this.setData({ submitting: true });
    // 登录流程未接入：如实提示，不伪造已登录
    wx.showToast({ title: '登录待接入', icon: 'none' });
    this.setData({ submitting: false });
    console.log('[BiuZ] login intent', provider);
  },
});
